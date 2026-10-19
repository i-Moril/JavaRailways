# 在隔离模拟中安装完整信号运行逻辑

## 目标

模拟执行真实固定闭塞、保护区域、进路选择、inspection、资源贡献及信号JS；每个LIVE World与每个SIM具有独立信号求值和关闭归属，不以空source或替代算法冒充完整支持。

## 需求与来源

本任务实现父任务的R1、R2、R6的信号部分；父任务AC5及AC7相关部分。
完整用户决定与非目标以[父PRD](../10-18-isolated-simulation-runtime/prd.md)为唯一来源，不另行放宽。状态保持planning，尚未批准实现。

## 验收标准

- [ ] 正式四灯JS及真实SimpleSignals编译/安装，不用手写空source替代。
- [ ] 占用停车、释放放行、保护进路选择/准备、折返及方向/静态速度约束一致。
- [ ] inspection与claim/尾部释放、admission更新均进入同一目标World运行链。
- [ ] **S1 运行实例**：LIVE A、LIVE B和每个SIM分别拥有SignalLogicRuntime的执行/关闭归属；manager不再把同一个串行logic实例用于多个World。
- [ ] **S2 并发与生命周期**：在真实JS求值或其执行锁内阻塞A，B仍完成物理/信号发布并得到正确结果；覆盖LIVE-LIVE、LIVE-SIM和SIM-SIM，同World继续有序。关闭/失败/重新安装只影响所属实例。
- [ ] **S3 共享目录边界**：单World规则编译、求值及其等待不持跨Worldmanager目录锁；只读规则包可共享，必要全局目录/发布事务仍正确协调。
- [ ] 没有Bukkit效果，诊断标识所属模拟World；不完整/不可用规则保持现有业务收紧或准备失败语义。

2026-10-19补充：原计划已要求每个SIM独立JS实例，本次明确加入各LIVE World之间的同等隔离要求和真实阻塞验收。当前仅更新计划，仍未启动实现；技术证据与保留锁边界见[剩余锁清单](../10-18-isolated-simulation-runtime/research/remaining-cross-world-locks.md)。

## 范围限制

不部署、不迁移、不推送，不修改无关WIP，不新增Environment、逐次发布归属校验或未要求的兼容路径。仅修改本子任务拥有的部件；全Feature结论由父任务联合验收。

## 依赖

依赖10-18-world-runtime-isolation；使用其已验证的捕获/安装和本地资源接线。
