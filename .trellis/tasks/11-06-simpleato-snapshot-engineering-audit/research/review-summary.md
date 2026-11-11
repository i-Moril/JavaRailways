# SimpleATO 审查结论

2026-11-06；`codex/ato-per-car-rebuild` / `b89b0d5d`。任务仍为 planning，研究完成，不表示修复完成。仅新增本任务研究资料和临时探针；未改业务代码、提交或部署。

## 首先回答 TrainSnapshot

**按当前仓库实际用途，Core TrainFrameSnapshot 可以覆盖 TrainSnapshot 全部物理信息，建议迁移后删除后者。**

Core frame 本来就有 head、tail、speed、derail 状态、cars，以及这次资源计算真正需要的 bodyTraversal。TrainSnapshot.capture 原本就是从 frame 抽取少数字段。额外 revision 由调用者各自填 ATO tick、感知计数、调度 tick 或 physics epoch，并不是第二份物理事实，也没有查到驱动安全判断的消费者。

需要的是同一次捕获的物理帧，不是第二套物理副本，也不是把控制器业务状态塞进 Core。历史故障需要保存当时 frame 的引用；不能读取“最新帧”替代历史现场。API/诊断命名及计数器语义必须同步整理，但不应为开发期类型保留永久兼容壳。详见 `train-snapshot-role.md` 的全部生产/测试调用清单。

## 当前故障的完整链（P1）

K01 Core 在发布新 frame 前通知 ATO，ATO 读了旧 frame；K02 Dispatcher 用 ATO 旧车头与 Core 新车头比较，不同即丢弃当前有效车体帧；K03 检查层把因此得到的 null 车长变成“申请整个 Mission”。真实车长并不缺失，缺失是跨时点复制和比较制造的。现有规范和测试还把 K03 当作正确行为。

这解释了远方车占用导致不必要请求失败，但不等于已量化全部延误或“14219 时截图车辆已经停车死锁”。真实回放数值、实际超时交路和限制见 `known-14219-issues.md`。

## 新发现：确定缺陷

按优先级排列；除标有动态探针外均为静态生产链确认，不能当作已在测试服复现。

1. **C01 / P1：自动驾驶提前丢掉整车限速。** 车头进入宽松轨道后，车尾仍在严格限速轨道，自动控制却不保留该轨限制。手动保护已正确覆盖车体。真实 Core 几何的临时探针确认自动目标缺少 1.5m/s 限制；尚未动态复现加速/脱轨全过程。详见 `control-audit.md`。
2. **T02 / P2：选站转换丢失停站程序。** 普通与结构编译把 SelectStop 转 CallStation 时丢掉 stopPrograms，编译按未折返方向传播，运行却执行停站程序里的 Reverse。详见 `timetable-audit.md`。
3. **T03 / P2：强制丢弃运行没有取消普通扩展操作。** 首 Mission 前 PENDING extension 尚无 Basic grant，可以进入 discard，但其 ticket 未取消，run owner 已删除。详见同报告。
4. **T04 / P2：启动不存在的 service 仍创建调度时钟。** 调用返回失败，未来周期却可能自动启动同表其他计划车次。应先验证目标存在，再发布 clock。详见同报告。
5. **T01 / P2：人工接管后显示旧位置。** 状态展示继续读取已经脱离列车的 ATO 副本，不随人工 controller 移动更新。与快照统一一并处理。没有证明其绕过 Basic 资源安全。
6. **D1 / P2：没有信号来源时积压已退休 owner 的变更。** 空 sources 提前返回，没有清理 admission changes；不是当前超时的已证原因。详见 `domain-audit.md`。
7. **D2 / P2：车库值对象泄露可变集合。** tags/capabilities/allowedTags 可以在注册后从外部修改，已捕获保存 DTO 也随之改变，绕过 owner 的验证和锁。未证明现有平台调用者正在这样修改。
8. **D3 / P2（API 限定）：多站编译可能产生中间站台不连续的相邻区段。** 每站对独立选最短，可能 A→B近端、B远端→C；当前 HTTP 缺精确目的地路径会在后续验证被拒，不能报告为实际瞬移。
9. **D4 / P3（API 限定）：LineStore.restore 重复 ID 失败后留下半写状态。** 正常基础设施恢复上层已预检重复，影响限定于直接公共 API 错误输入。

## 新发现：明确可收敛的工程问题

- **I01 / P2：不变状态重建所有检查索引。** Dispatcher 每 Tick retainTrains 都重新发布全部 TrainBinding 和 authority floors；临时探针确认身份重建。修正空操作路径和局部删除即可，不需要新缓存系统。详见 `inspection-routing-audit.md`。
- **C02 / P2：具体 ATO 每 Tick 算了一份完全不用的规划结果。** MinimumConstraintPlanner 的 ControlDemand 被 concrete motionControlFrame 忽略，而诊断又重复约束归约。先去掉该生产路径无消费者的计算；不要直接推断公开默认 planner API 全无用。
- **T05 / P3：编译已禁止运行期条件分支，Dispatcher 仍保留对应解释执行路径。** 可清理已证合法计划不可达的范围，但 SelectStop continuation、cursor/address、结构 trace 仍有实际用途，不能全部连根删除。

## 待验证/需决定的风险（不混入确定缺陷）

控制报告：泛用感知借用0.3m停车容差延迟 PASS；普通参考模型失败被包装成ATP问题；SIMULATION混合驾驶模式与失败策略；初态诊断 map 无退休路径。

时刻表报告：结构证明要求整个 topology root 身份相同，可能因无关编辑失效，需实际编辑/结构作业复现及合同确认。

领域报告：world runtime data-class copy、重复车库分配接口、关闭回调对 Core 生命周期的依赖、持久化大 nullable union、旧规范冲突。当前不足以把它们一概认定为故障或授权删除。

## 建议后续修复批次（仅建议，未执行）

1. **物理事实统一**：K01–K03 + T01；删除重复物理模型，修发布顺序，移除制造空车长的比较和全路线兜底，同步规范/真实 epoch 回归。
2. **独立安全输入**：C01 整车限速；与前视入口信号职责区分，复用现有小型计算，不新建保护系统。
3. **编译及生命周期正确性**：T02–T04、D1/D2；各自小批次回归，不混入大重构。
4. **性能与死路径**：I01/C02/T05，限定实际生产消费者；API-only D3/D4 另附直连反例。

## 审查与验证边界

- 全部 **107 个生产 Kotlin 文件**、构建和2个模块文档完整阅读；**89 个测试/fixture** 全部盘点，测试源码是部分全文、部分定向阅读，逐项见 `coverage.md` 和分区报告。
- 原有 **788 个测试通过**（默认测试堆先 OOM；临时3GB堆复核通过）。两项新增审查探针均在目标断言失败，分别暴露 C01、I01，详见 `validation.md`。
- 没有实施后的回归、反事实模拟、测试服务器部署或游戏验收。审查不构成“没有其他缺陷”的保证，也不意味着原有安全生命周期整体是过度设计。
