# 按 World 隔离运行调度与模拟装配

## 目标

普通World和模拟独立推进，实际必要的跨World管理事务保持一致，并为信号/行为创建提供同一领域安装入口。

## 需求与来源

本任务实现父任务的R1的共同领域装配、R2、R6；父任务AC4及AC7相关部分。
完整用户决定与非目标以[父PRD](../10-18-isolated-simulation-runtime/prd.md)为唯一来源，不另行放宽。状态保持planning，尚未批准实现。

## 验收标准

- [ ] barrier暂停World A时World B及模拟仍推进，同一World操作保持串行。
- [ ] 实际World与模拟、两次模拟之间frame/预约/调度器/脚本运行实例不共享。
- [ ] 资源重载和provider卸载的检查-提交不与新准入交错，原有跨World玩家handoff成立。
- [ ] 区域等待释放并恢复正确owner；停止/卸载只清理所属World。
- [ ] 共同装配包含真实速度安全、脱轨消费及admission更新；缺失必需Feature在准备边界明确失败。

## 范围限制

不部署、不迁移、不推送，不修改无关WIP，不新增Environment、逐次发布归属校验或未要求的兼容路径。仅修改本子任务拥有的部件；全Feature结论由父任务联合验收。

## 依赖

依赖10-18-world-epoch-events；在子任务1完成并验证后实施。
