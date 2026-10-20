# 复用车厢行为逻辑并验证完整模拟隔离

## 目标

模拟运行真实逐车脚本和运营扩展，以独立逻辑调度/效果出口保持业务决定和完成时机。

## 需求与来源

本任务实现父任务的R1、R2、R5、R6的行为/扩展部分；父任务AC6及AC7相关部分。
完整用户决定与非目标以[父PRD](../10-18-isolated-simulation-runtime/prd.md)为唯一来源，不另行放宽。2026-10-19用户要求继续剩余目标、不必立即合并，授权进入本批实现；继续使用已通过信号候选的现有worktree，暂不提交合并。

## 验收标准

- [x] 同一真实脚本在两模式的独立车厢状态、门侧/换向、动作选择及拒绝一致。
- [x] 多个延迟效果、失败、取消及全部完成时机使用相同业务规则。
- [x] 不触发实际显示/声音，诊断可区分所属World；两个同源模拟不共享脚本、ticket或定时状态。
- [x] 运营扩展实例属于目标World；缺少必需领域能力明确失败。
- [x] 与真实SimpleSignals、碰撞及时刻表联合场景运行，向父任务提供并发隔离fixture。

## 范围限制

不部署、不迁移、不推送，不修改无关WIP，不新增Environment、逐次发布归属校验或未要求的兼容路径。仅修改本子任务拥有的部件；全Feature结论由父任务联合验收。

## 依赖

依赖10-18-world-runtime-isolation；本子任务最终联合验证也依赖10-18-simulation-signal-features。

2026-10-20父最终验收：53源c1d805b9（与P2候选相比仅两测试变化）通过当前8模块2507项/364 suites，0失败/错误/跳过；frontend108和生产构建通过，API消费/模块边界/JAR/三模块Dokka通过。补齐真实fromCore的LIVE-LIVE/LIVE-SIM/SIM-SIM模式矩阵，并以非空真实LIVE claim/Dispatcher/正式信号验证同源两SIM运行关闭不改源状态。父AC4/AC6/AC7代码及自动化验收通过，现场Paper/Folia/client/browser未执行；保留上游fatal host故障全进程停机政策。提交/合并/归档暂缓。最终索引见行为research/parent-acceptance.md，机器证据parent-final-evidence.json及独立parent-acceptance-check.md。
