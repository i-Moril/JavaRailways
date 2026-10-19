# 实施顺序与验收

## 规划门禁

- [x] 用户同意创建任务；此前只读范围不再阻止本任务规划。
- [x] 坐标/UI已先合入a76b076c；核对现有WIP和相关旧任务。
- [x] PRD收敛为R1-R6及AC1-AC7；技术决定与检查顺序分列。
- [x] 为父/子任务准备真实spec/research上下文。
- [x] 用户在最终规划摘要之后于2026-10-18回复“可以”，批准从第一批开始实现；按依赖逐个启动子任务。

## 子任务与依赖

1. `10-18-world-epoch-events`：R3/R4及R5碰撞发布；交付World模式、统一运行事件来源和逐物理epoch通知。独立验收AC1-AC3相关部分。
2. `10-18-world-runtime-isolation`：依赖1；交付每World执行归属、必要全局事务协调、共同领域运行装配及最小Feature安装接线。验收AC4及共同安全阶段。
3. `10-18-simulation-signal-features`：依赖2；交付完整SimpleSignals模拟安装和真实规则对照。验收AC5。
4. `10-18-simulation-behavior-features`：依赖2，最终检查也依赖3；交付逐车行为/扩展的共同运行与模拟效果出口。验收AC6，并向父任务提供联合fixture。

顺序是依赖约束，不因任务树自动启动其他子任务。3/4只有在2的接口与文件ownership明确后才可并行；未明确前串行。父任务拥有最终整合，不直接把所有模块交给同一个无边界worker。

## 实现工作区与分工

- 批准后从已合并HEAD创建/复用适合的独立实现worktree。原工作区49项状态是他人WIP，不能stash/drop、重置或顺手提交。
- 读取每个子任务prd/design/implement和相应phase 2.1；按本平台默认派发Trellis implement/check子代理，提示以`Active task: <子任务路径>`开头并声明确切文件ownership。
- 子代理不是唯一编辑者，必须保留其他工作并适应已合入修改；父会话负责模块接线、冲突、验收汇总及小提交。
- manifest校验提示train-physics、route-resource-admission及部分Signals规范超过自动注入上限；相关子代理须按原文件完整读取涉及章节，不能把截断注入当作完整合同。父会话派发时显式带上这条要求，不为本任务顺带拆分或改写这些既有规范。
- 每批改动后运行受影响测试并检查当前候选源；验证通过后才提交该批拥有的修改。不得把其他会话历史测试当成本批结果。

## 父任务最终检查

- [ ] 子任务1-4全部满足各自AC，比较最终源码与被测候选。
- [ ] 联合fixture使用真实Core/Basic/SimpleATO/SimpleSignals逻辑与正式四灯规则：占用停车/释放、保护进路、折返、碰撞联结/脱轨、逐车脚本及延迟操作。
- [ ] 真正并发地推进LIVE与SIMULATION，以及两个同源SIMULATION；暂停任一方仍可推进另一方；两边使用相同列车ID也不串状态。
- [ ] 模拟前后实际World的frame、列车、预约、Dispatcher、信号状态保持不变；没有实际平台效果，诊断可区分所属World。模拟只释放自己的资源。
- [ ] epoch事件包含正确World、epoch/对应frame；失败与合并唤醒无伪Post/重复Pre，取消及关闭无资源泄漏。
- [ ] 重载/插件卸载/玩家跨世界handoff保持原业务一致性，callback不在协调器提交锁内运行。
- [ ] 模拟遗漏必需领域Feature时明确失败，无空source/固定时长替身被报告为完整验证。
- [ ] 既有UI和坐标功能回归，更新旧事件开放项和相关规范；SimpleSignals平台拆分任务只记录本任务实际提取部分，不误报全部完成。
- [ ] 限定diff-check、任务manifest检查、提交归属和WIP保留核对；无部署、迁移、推送。

## 验证命令

先按子任务运行定向测试，再在最终候选运行：

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:test :BukkitPlugin:test :TunnelBridgeBuilder:test
./gradlew :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
./gradlew :BukkitPlugin:shadowJar :SimpleSignals:check
npm --prefix WebUI/frontend test
npm --prefix WebUI/frontend run build
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-isolated-simulation-runtime
git diff --check
```

具体JDK/缓存/离线参数以现有构建配置和可用依赖为准；缺依赖明确报告，不能以编译替代运行测试。API变化按当前Dokka V2真实任务补文档检查，不照抄失效任务名。浏览器与真实Paper/Folia运行若未执行，明确列为未验证。

## 回滚与状态

每个子任务保留独立提交/验证记录；回滚只操作这些提交，不撤回坐标/UI或无关WIP。父任务仅在联合验收完成后完成。

2026-10-18：子任务1在独立工作区实现及检查通过，见[验证记录](../archive/2026-10/10-18-world-epoch-events/research/validation.md)，本地提交已于2026-10-19获批，本批已整合至dev/signal-system，组合2343项测试及边界/文档门禁通过，子任务1完成。子任务2–4尚未实施，父任务联合验收未执行，不能声称全隔离、全Feature模拟已经完成。
