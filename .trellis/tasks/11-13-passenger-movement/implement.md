# platform 乘客移动：重新实施计划

## 起点和边界

分支 `feat/passenger-movement`，最初源码起点 `60201085`，当前 HEAD 为外部追加文档提交 `6e77496c`，保留它。用户批准重新实现及回到既有 commit。原未提交草稿已完整备份，相关源码按 HEAD 恢复后重做；不重写分支历史，不部署。

生产文件预期为 PassengerMovementManager、PassengerPlatforms、PassengerCarrierAccess、WorldManager、必要的 CarExtension 入口，以及 PacketEntityMotion/TrainPacketBundleCoordinator 删除旧持锁机制。LatestAffinityTaskDispatcher 应保持既有 latest-only API。对应测试和任务/规范同步更新。

## 实施顺序

- [x] 玩家 entity owner 单独推进局部位置、交接和退出，发布一份不可变完整状态。
- [x] 既有列车分发前统一采样，车厢 bundle 呈现后由 entity owner 同步真实位置；渲染不回写步行状态。
- [x] 停车仅通过乘客简化路径更新发生变化的 carrier，复用已接受平台姿态；不提交整列渲染，不新增 retained 缓存，保留正常行驶随车呈现。
- [x] 删除旧会话持锁、bundle 持锁及其 audience 延迟；回归 boarding pending、veto、稳定 carrier、迟到任务与真实 cleanup completion。
- [ ] 实现代理聚焦测试及编译；检查代理审查完整差异，跑完整 BukkitPlugin:test 与 shadowJar，单独记录既有 S100 基线失败。
- [x] 规范与任务已按最终源码同步，工作变更纳入本次提交；会话由 finish-work 记录，任务保留待客户端复验状态。

## 关键断言

行驶时玩家 tick 连续执行多次、区域显示暂未运行时，相对位移仍累计，期间没有独立位置包；停车输入直接走仅更新乘客的简化路径。一次列车分发对每位乘客采样一次，交接中前后车厢不重复或遗漏输出。同一显示批次的车厢和乘客使用匹配姿态。停车输入只移动玩家且车厢渲染次数不增加、正常随车呈现、多人其中一人停步、跨平台、原生 pending/veto、外部事件、分配/输出中 close、卸载后迟到回调必须覆盖。

保留已有纯几何测试和原生实现。fixture 不能证明 Craft/NMS 私有字段、真实客户端平滑度或实际 tick 性能。完整模块已有 CarAnimationPlaybackTest.kt:85 的 S100 ZIP/source 类型路径基线失败，相关输入保持不变并分别报告。

## 历史证据

历史实现/部署和测试证据见 research/validation.md、research/deployment-20261113.md。它们只代表对应版本，本轮最终证据在完成后追加，不覆盖历史结果。

## 用户边界

禁止臆测不存在的需求。如发现真正的问题，先停止，给出复现或明确代码证据并问用户，不自行增加同步/缓存/防御机制。此前首次上车 B/C 说法尚无复现，不作为实施依据。

用户已要求继续修复；已撤回的多批次清理等待扩展不实施，不编写只验证该额外限制的测试。

## 最终本地验证

聚焦编译与测试、独立完整模块检查和 shadowJar 已完成，证据与既有失败见 [validation.md](./research/validation.md) 及 [独立检查](./research/check-stationary-passenger-20261113.md)。源码检查无待修项；完整测试仍有一项已记录 S100 基线失败，不标为全绿。

- [ ] 部署本轮产物并执行客户端平滑度、边界连续性和跨区域实机复验；不把本地单测结果等同于这些验收。
