# 首批 F1–F5 实现复核

复核范围：`b89b0d5d` 之后本任务工作区变更；不复审全模块，不处理已明确后置的 T02–T05、D1–D4、I01、C02。未提交、部署或操作远程服务器；保留原有无关 dirty 文件。

## 目前状态

A（物理帧/准入/人工状态）与 B（整车限速）静态复核完成。发现并修复一处新测试的协调器释放遗漏；未发现未解决的本批生产代码问题。最终候选编译/全套测试与本地回放由主会话执行，本文不能单独替代该动态验收。

## A 覆盖与证据

- `PhysicsCoordinator.runEpoch`：worker 全部结束后，在既有 topology→state 门禁内翻转缓冲、发布全部 train frame/mailbox，随后在两锁之外通知 controller。close/halt 检查保留；后续 occupancy/world publication 仍在 exact-ticket 门禁内。hook 开始前后可能与 close 并发是原合同允许的扩展效果，不误称 close 为 quiesce。
- `TrainController.commit`：显式路径只提交一次 train，然后通知；不会先通知后生成 frame，也没有为了复用旧 helper 重复 Core commit。
- `AbstractATO` / `PerceptionSnapshot` / `SimpleATO`：物理值直接引用 Core immutable frame；扫描同一捕获帧提供 head/body/perception。不覆盖 frame speed。控制 tick、perception epoch、frame revision 不混作同一个计数。初态由 controller 生命周期拥有，故障报告保留当时帧；JSON 继续显式选择小字段，没有直接序列化 render payload。
- `TimetableDispatcher`：owner tick 捕获 Core frame，不以旧 ATO head 比较决定是否保留 body；同帧进入 inspection。FULL_MANUAL 的 status 也读取当前 frame。无 Mission 投影时先调用既有物理授权检查保留具体 overrun 原因，并未放宽真实授权边界。
- `InspectionRuntime` / `EntryClearance`：公开同步入口不再接收独立标量长度；有要求时检查同一帧 identity/epoch/body。新 binding 在无有效 geometry 时尚未替换发布；旧绑定失败不扩大原 floor。Dispatcher 在同步失败后返回，不执行新 request/progress。真实所需 floor 大于路线时原合法请求裁剪仍存在，controller 的未裁剪净空目标保持不同语义。
- 直接消费者：Basic perception fixture、SimpleSignals 固定闭塞/区域/替换 fixture、Bukkit 运行线峰值采样均迁移；不新增旧 API alias、物理摘要 DTO、申请器或 schema。

## 回归有效性

- 新 Core hook 测试使用真实移动 epoch 和 latch；阻塞 hook 时由另线程完成 close，之后验证 train frame 不再前进、world frame 不发布，未用 sleep。
- `AbstractATOMotionPublicationTest` 对真实 coordinator 与显式 commit 两条路径参数化，比较 frame 引用、位置/速度/body，保留移动帧并验证下一故障冻结不改写旧现场。A owner 已记录旧实现新增断言红、新实现绿；本 reviewer 未另行重跑旧基线。
- `RollingStationAdmissionTest` 增加真实 inspection 要求，继续使用 Core/Basic admission/真实 handle dynamics；观察移动时请求不因丢失长度扩为全路线。共享 source 测试计数已使用 AtomicLong，支持多车 callback 并发。
- `EntryClearanceRuntimeTest` 验证 null/空 body/错误列车/错误 epoch 的新绑定拒绝及旧 floor 不扩大；已有长度迟滞回归继续走真实移动 Core frame。
- 人工状态测试确有 200 个移动 step，并与当前 Core head 比较，不只在静止转交时断言相等。

## 验证边界

- 本 reviewer 执行 `git diff --check`：通过。
- 全仓当前 Kotlin/Java/KTS 源码检索 `TrainSnapshot` / `trainSnapshot`：无残留（历史研究文档不计为生产引用）。
- 当前相关 Gradle 构建文件未声明独立 ktlint/detekt/spotless 任务；Kotlin/Java 类型检查由编译完成。按单一 Gradle owner 协调，本 reviewer 暂未运行 Gradle。
- A owner 已记录聚焦 Core、Basic 全套、SimpleSignals 全套和 Bukkit/API consumer/模块边界构建结果，见 `implementation-a.md`；不能把该中间结果替代 B 后最终候选验证。
- 最终模块全套、Dokka 与本地五交路 Tick 14219 回放由主会话负责，结果以 `implementation-validation.md` 为准；本地验证不代表 Paper/Folia、客户端或已部署行为。

## B 覆盖与证据

- `NativeBodySpeedLimits` 只消费已捕获 immutable body；按轨道 ID 去重读取 Basic 原生 effective limit，包含没有转向架落入的短轨。没有新缓存、marker、source 或资源机制。临时空间包含 map 产生的 B 个 ID；已通知主会话将规范 O(U) 修正为实际 O(B+U)，不额外改动生产算法。
- 自动路径在原 `updateBuiltInConstraints` 接入 CurrentSpeedLimitConstraint，已有 `controlFrame` 将其同时转换为距离零的 reference/protection target；手动路径共用读取函数，并从同一帧构建 head scan，删除 live bogie 补集。已有前方原生入口信号、路线终点、停车/MA/信号区间解除语义未改。
- 新 7 例参数化/独立测试覆盖 AB/BA、无 bogie 短轨、尾清前后、保留旧帧、有效最小值、缺 body 拒绝、手动单次 frame capture，以及前方目标仍在正距离。自动目标列表虽然采用窄反射观察，但并非唯一证据：两个真实 coordinator + HandleTrainActuator 设备回归检查覆盖期间实际/reference 速度不超 1.5m/s、清除后超过 2m/s，且无 ATP incident。
- 本 reviewer 独立检查聚焦 XML：NativeBodySpeedLimitsTest 7、ManualProtectionFrameTest 2、SimpleATOTest 28，共 37 项，0 failures/errors/skipped（时间 2026-11-06T19:27Z，清理修正以前）；清理修正后须由主会话全套重新编译/执行，不冒充已有结果已覆盖后续源码。
- fixture 明确 `PhysicsCoordinator(1, automaticFallback=false)`；单 worker 描述有源码依据。

## Findings

- Fixed：`NativeBodySpeedLimitsTest.withFixture` 显式提供 coordinator 时，`TestWorldFixture.ownsCoordinator=false`，原 `.use` 不关闭 physics，真实运动用例会遗留执行器。本 reviewer 使用外层 coordinator `use` 覆盖 world 创建失败，内层 `use` 保证 physics 先于 fixture tick manager 关闭；Core close 幂等，清理异常沿 `use` 追加 suppressed。只修改本测试和本报告，未改生产或规范。修正后的 `git diff --check` 通过。
- Not fixed：没有新发现的未解决本批确定代码缺陷。复杂度文案由规范 owner 修正；已知后续审查项保持 deferred，不以这次复核宣称 SimpleATO 全部问题解决。最终类型检查/全套测试/回放仍由主会话完成。
