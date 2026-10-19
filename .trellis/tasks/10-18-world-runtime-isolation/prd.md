# 按 World 隔离运行调度与模拟装配

## 目标

普通World和模拟独立推进，实际必要的跨World管理事务保持一致，并为信号/行为创建提供同一领域安装入口。

## 需求与来源

本任务实现父任务的R1的共同领域装配、R2、R6；父任务AC4及AC7相关部分。
完整用户决定与非目标以[父PRD](../10-18-isolated-simulation-runtime/prd.md)为唯一来源，不另行放宽。用户于2026-10-19回复“可以进入下一步”，批准启动本子任务实现。

## 验收标准

- [x] 宿主owner/runner层的barrier暂停World A时World B及模拟仍推进，同一World操作保持串行。
- [x] **O1 构造隔离**：在真实`RailwayObservation.fromCore`转换A期间暂停，B仍能完成current/publish和对应领域阶段；共享服务锁不包围快照转换、ticket等待或listener。
- [x] **O2 观察语义与退休**：保留same-frame复用、同epoch owner frame更新不重复通知、epoch不回退、listener隔离与注销；removeWorld/close与转换交错不得重新填回已失效状态。
- [x] 实际World与模拟、两次模拟之间frame/预约/调度器及每目标创建的扩展provider实例不共享。
- [x] 资源重载和provider卸载的检查-提交不与新准入交错，原有跨World玩家handoff成立。
- [x] 同步等待能力释放并恢复实际持有的owner；停止/卸载只清理所属World。
- [x] 共同装配包含真实速度安全、脱轨消费及admission更新；缺失必需Feature在准备边界明确失败。

2026-10-19计划更新：原先明确列出的锁/调度改造已完成；O1/O2是补入本批的遗漏，现已实现并通过真实转换barrier、生命周期回归及新完整门禁；本批待本地提交/整合。各LIVE World之间的JS求值隔离及完整模拟信号安装归子任务3。分工及源码证据见[剩余锁清单](../10-18-isolated-simulation-runtime/research/remaining-cross-world-locks.md)。

实例隔离在本批覆盖Core/Basic/Dispatcher、扩展provider及local contributor。完整信号JS和逐车行为实例由后续两个子任务及父任务联合验收；当前没有生产同步区域等待调用，不声称已完成这类平台调用迁移。验收证据见[验证记录](research/validation.md)与[独立检查](research/check-result.md)。

## 范围限制

不部署、不迁移、不推送，不修改无关WIP，不新增Environment、逐次发布归属校验或未要求的兼容路径。仅修改本子任务拥有的部件；全Feature结论由父任务联合验收。

## 依赖

依赖10-18-world-epoch-events；在子任务1完成并验证后实施。
