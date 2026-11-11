# TrainSnapshot 全仓用途与替代性

基线 `b89b0d5d`，2026-11-06。全仓 Kotlin/Java/KTS 符号检索，逐一分类 8 个生产文件和 5 个直接测试引用文件，并跟踪推导类型消费。

## 结论

Core `TrainFrameSnapshot` 覆盖 `TrainSnapshot` 的全部物理信息。未发现必须由第二个列车物理值模型提供的生产能力；建议迁移后删除 `TrainSnapshot`，而非长期保留适配副本。不是一次类型名替换：独立计数器、故障现场的采样阶段、公开签名和诊断输出需明确处理。

模型本身也属于 Core，不是 SimpleATO 自定义类型；SimpleATO 是主要消费者，并重复保存它。

## 字段逐项

- trainId → frame.trainId。
- speedMetersPerSecond → frame.speedMetersPerSecond。
- derailed / derailReason → frame 同名字段。
- head / tail → frame 同名值，保留各端 facing。
- carCount → frame.cars.size；不需要另存物理状态。
- revision → 不是另一个物理事实。TrainSnapshot 允许调用方随意指定“physics epoch 或其他递增 revision”，Core frame 则明确区分 per-train revision 与 physicsEpoch。

`Core/.../api/physics/train/TrainSnapshot.kt:38-51` 的 capture 本身即全部从一份 publishedFrame 投影，仅 revision 为调用方提供。`TrainFrameSnapshot.kt:101-149` 提供全部上述字段及完整 body/head/tail traversal、render data。

## 生产者与消费者清单

1. `Core/.../TrainSnapshot.kt`：定义与 capture；没有独立生产物理信息。
2. `Basic/.../api/ato/snapshot/PerceptionSnapshot.kt:39-50`：感知包装的 train 字段。感知 epoch 已独立存在；可引用扫描开始捕获的 Core frame，而非再复制 head/speed。worker 用上一已提交帧作本轮输入是正常合同，不能为了“最新”去读取尚未提交的运动结果。
3. `SimpleATO/.../api/ato/AbstractATO.kt:72-105,198-235`：PublishedAtoState 的列车副本、currentTrainSnapshot 公共 getter、构造时初态记录、发布与故障报告。capture 使用 atoTick 作 revision，且用 train.speed/activeSpeed 覆盖从 Core 得到的速度，破坏单帧来源。这是 K01 的另一面，不应原样迁移为 frame.copy(speed=...)。
4. `SimpleATO/.../controller/SimpleATO.kt:91,225-239,548,1208`：SimpleAtoPublishedState 包装再次引用同一 TrainSnapshot（不是深复制第三份）；物理 origin/感知生成读 capture。消费 head 与 speed 作投影/inspection；没有额外独立车体信息。
5. `SimpleATO/.../timetable/TimetableDispatcher.kt`：自动模式从 ATO 状态取副本，手动接管模式 capture Core frame；方法参数传递 head/tail/speed/derailed，路径/任务诊断等。capture 的 revision 填 dispatcherTick；末尾 status 仍从旧 ATO 快照取 head，见 timetable 报告的手动接管问题。`capturePublishedTrainSnapshot` 可收敛成一次 Core 帧捕获，不能继续跨新/旧帧取字段。
6. `SimpleATO/.../control/RailwayControlRuntime.kt:12,74-77`：ATP 诊断 context。revision 填当前 Core physicsEpoch；仅复制旧模型无必要，直接保留这次输入帧。历史事件是故障前现场，不能输出时重新取最新帧。
7. `SimpleATO/.../api/ato/AtoWorldCoordinator.kt:24-25,54,66-70`：初始帧缓存与故障报告初态/当前态。需要保留“当时那一帧”，但不需要不同模型；只保留 immutable frame 引用即可。完整帧比小投影保留更多内存，需要检查已有 initialTrains 的退出清理，不能用新一层镜像掩盖生命周期问题。
8. `SimpleATO/.../api/ato/AtoStallReportJson.kt:64-75,101-105`：手工写出精简诊断 JSON。可从 frame 按需写出相同必要字段，不应直接把完整 frame/render 数据 toString 输出。JSON revision 原语义含糊，需明确列车 publication revision、physics epoch、ATO tick/感知 epoch，不借本次审查改 schema。

## 隐式类型消费者

- `SimpleSignals/.../block/FixedBlockInspectionSource.kt:129` 和 `region/ProtectionRegionInspectionSource.kt:80` 读取 perception.train.trainId，可保留语义。
- `BukkitPlugin/.../http/IsolatedLineSimulator.kt:120,154` 读取 SimpleAtoPublishedState.trainSnapshot.speed 采样峰值；类型/命名迁移时须同步，不属于 SimpleATO 全模块覆盖。
- SimpleATO inspection runtime 消费 perception.train trainId/speed；controller 使用 perception.train.head。
- `AbstractATO.currentTrainSnapshot` 未找到仓库生产调用方；它是公开 API，缺内部调用不单独构成删除理由，但本轮明确评估替代后可随类型迁移处理。

## revision 实际用途

capture 调用：感知计数 perceptionEpoch、ATO atoTick、调度 dispatcherTick、ATP 处物理 epoch，共四类来源。全仓检索 `train.revision`、trainRevision 及对应变量消费后，未找到用 TrainSnapshot.revision 驱动安全准入/去重的生产逻辑；主要进入 JSON/toString 诊断。PerceptionSnapshot 的注释称可拒绝 mixed/stale，但构造器并未验证 train revision 与感知 epoch 对齐。

因此这些调用方计数器不应阻止消除物理副本：有实际用途的计数器保留在各自上下文已有字段中。不能机械把旧 revision 改成 frame.revision 而声称数字语义没变。

## 测试与公开边界

直接测试引用：Core ImmutableSnapshotTest；Basic PhaseAFoundationTest；SimpleATO InspectionRuntimeTest；SimpleSignals FixedBlockInspectionSourceTest、SignalReplacementContinuityTest。替换时使用真实或明确构造的 Core frame fixture，不能为迁移方便恢复一个隐藏的 TrainSnapshot mirror。

与 PublishedAtoState/PerceptionSnapshot 签名有关的推导类型测试及 Java consumer 编译也必须纳入未来修复验证。当前未查到冻结的外部兼容要求；不能仅因 public 或开发服部署就添加长期旧 API alias。外部生态完全不存在不能由仓库检索证明。

## 最小替代方向（建议，未实施）

1. 消费所需物理信息由一次捕获的 Core frame 提供；控制状态仍由 SimpleATO 发布。若需要共同发布，保存所对应帧的引用而非重新抄写其字段。
2. 修正 Core 通知时点与 ATO 采样契约；保留关闭/失败的 publication gates，扩展 callback 不移进核心锁。
3. Dispatcher/inspection 不再通过坐标相等猜测帧一致，不把正常有效列车变成空车长。
4. 删除全路线兜底；修改将该兜底写成正确行为的 entry-clearance 规范和 `EntryClearanceRuntimeTest:59-60` 断言。
5. 故障诊断保留捕获时点，不拿最新帧覆盖历史；如需报告未提交控制速度，作为控制诊断单独记录，不冒充已发布物理速度。

本文件是当前源码的静态用途审查；K01-K03 已有本地真实回放，整体替代尚未实施或验证。
