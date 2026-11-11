# 实施顺序与验收

## 规划门禁

- [x] 用户同意创建任务；此前只读范围不再阻止本任务规划。
- [x] 坐标/UI已先合入a76b076c；核对现有WIP和相关旧任务。
- [x] PRD收敛为R1-R6及AC1-AC7；技术决定与检查顺序分列。
- [x] 为父/子任务准备真实spec/research上下文。
- [x] 用户在最终规划摘要之后于2026-10-18回复“可以”，批准从第一批开始实现；按依赖逐个启动子任务。

## 子任务与依赖

1. `10-18-world-epoch-events`：R3/R4及R5碰撞发布；交付World模式、统一运行事件来源和逐物理epoch通知。独立验收AC1-AC3相关部分。
2. `10-18-world-runtime-isolation`：依赖1；交付每World执行归属、必要全局事务协调、共同领域运行装配及最小Feature安装接线，补齐O1 observation构造的跨World独立推进。验收AC4及共同安全阶段。
3. `10-18-simulation-signal-features`：依赖2；交付完整SimpleSignals模拟安装和真实规则对照，并完成S1各LIVE World与每个SIM的信号JS求值/关闭归属隔离。验收AC5。
4. `10-18-simulation-behavior-features`：依赖2，最终检查也依赖3；交付逐车行为/扩展的共同运行与模拟效果出口。验收AC6，并向父任务提供联合fixture。

顺序是依赖约束，不因任务树自动启动其他子任务。3/4只有在2的接口与文件ownership明确后才可并行；未明确前串行。父任务拥有最终整合，不直接把所有模块交给同一个无边界worker。

## 实现工作区与分工

- 批准后从已合并HEAD创建/复用适合的独立实现worktree。规划时原工作区49项状态属于他人WIP；其后已按用户明确授权完整提交并保留，子任务2从干净基线`8f156900`启动。新出现的无关WIP仍不得stash/drop、重置或顺手提交。
- 读取每个子任务prd/design/implement和相应phase 2.1；按本平台默认派发Trellis implement/check子代理，提示以`Active task: <子任务路径>`开头并声明确切文件ownership。
- 子代理不是唯一编辑者，必须保留其他工作并适应已合入修改；父会话负责模块接线、冲突、验收汇总及小提交。
- manifest校验提示train-physics、route-resource-admission及部分Signals规范超过自动注入上限；相关子代理须按原文件完整读取涉及章节，不能把截断注入当作完整合同。父会话派发时显式带上这条要求，不为本任务顺带拆分或改写这些既有规范。
- 每批改动后运行受影响测试并检查当前候选源；验证通过后才提交该批拥有的修改。不得把其他会话历史测试当成本批结果。

## 父任务最终检查

- [x] 子任务1-4全部满足各自AC，比较最终源码与被测候选。
- [x] 联合fixture使用真实Core/Basic/SimpleATO/SimpleSignals逻辑与正式四灯规则：占用停车/释放、保护进路、折返、碰撞联结/脱轨、逐车脚本及延迟操作。
- [x] 真正并发地推进LIVE与SIMULATION，以及两个同源SIMULATION；暂停任一方仍可推进另一方；两边使用相同列车ID也不串状态。
- [x] 分别在真实observation构造与真实信号JS求值/执行锁内暂停A，B仍完成对应领域阶段；覆盖LIVE-LIVE、LIVE-SIM和SIM-SIM，不能只验证外层owner/runner。
- [x] 模拟前后实际World的frame、列车、预约、Dispatcher、信号状态保持不变；没有实际平台效果，诊断可区分所属World。模拟只释放自己的资源。
- [x] epoch事件包含正确World、epoch/对应frame；失败与合并唤醒无伪Post/重复Pre，取消及关闭无资源泄漏。
- [x] 重载/插件卸载/玩家跨世界handoff保持原业务一致性，callback不在协调器提交锁内运行。
- [x] 模拟遗漏必需领域Feature时明确失败，无空source/固定时长替身被报告为完整验证。
- [x] 既有UI和坐标功能回归，更新旧事件开放项和相关规范；SimpleSignals平台拆分任务只记录本任务实际提取部分，不误报全部完成。
- [x] 限定diff-check、任务manifest检查、提交归属和WIP保留核对；无部署、迁移、推送。

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

2026-10-18：子任务1在独立工作区实现及检查通过，见[验证记录](../archive/2026-10/10-18-world-epoch-events/research/validation.md)，本地提交已于2026-10-19获批，本批已整合至dev/signal-system，组合2343项测试及边界/文档门禁通过，子任务1完成。子任务2于2026-10-19获用户批准后在`.worktree/world-runtime-isolation`实施，原计划锁/调度候选实现及独立检查完成，8模块2391项全执行通过；2026-10-19补入O1后回到实现/检查，暂缓原提交计划；子任务3–4尚未实施，父任务联合验收未执行，不能声称全隔离、全Feature模拟已经完成。


2026-10-19计划补充：用户要求补齐遗漏的跨World串行点；按[剩余锁清单](research/remaining-cross-world-locks.md)分配O1至子任务2、S1至子任务3。仅O1加入当前批次，当前用户请求为计划更新；本轮未改产品或启动子任务3。必要管理/注册锁继续保留，不把目标扩写为消除所有进程级锁。

2026-10-19子任务2完成并整合：Core5d8cdabe、runtime1423116d、docs-sitecb9263b，O1/O2包含在2395项全通过候选，证据归档于[子任务2验证记录](../archive/2026-10/10-18-world-runtime-isolation/research/validation.md)。子任务3/4保持planning，父AC4/5/7未关闭。

2026-10-19子任务3候选完成：27源a5095090，完整8模块2411项、16新增case及API/模块边界/jar/三模块Dokka、docs完整check通过；每World信号执行与完整模拟安装已实现，最终独立复核/本地提交尚在收尾。子任务4仍planning；历史“子任务3尚未实施”只描述当时状态，不作为当前状态。父AC6和最终联合AC7开放，尚不声称全Feature完成。

2026-10-19用户要求继续而不立即合并：第四批在第三批已测a509工作区上推进，保留源码baseline与未提交改动。真实逐车JS、profile捕获/结构延续和operation资源真终止后释放已接入，首轮60项定向通过；实际信号/碰撞/时刻表/行为联合及当前候选完整门禁尚在进行，AC6/最终AC7不提前关闭。

2026-10-20：用户要求对齐最新dev。原工作区完整保留，整合迁至codex/simulation-world-alignment（基线87fe3e45），详见行为子任务research/upstream-alignment.md。上游未知宿主故障为全进程停止；普通业务/guest失败隔离和fatal故障边界分别验证，不能把旧基线测试或消除文本冲突当作完成证明。暂不提交或合回dev。

2026-10-20对齐收尾：最新dev87fe3e45上的48源ae862ac8已经独立检查，2491 JVM/364 suites零失败及108前端、API/JAR/Dokka/docs门禁通过。两个真实联合用例已包含在完整Bukkit532测试XML中。新增owner资源退休遗漏已修复并有失败→通过证据；其余上游合同保留。原候选和开发分支源码未改，活动工作区为codex/simulation-world-alignment，暂不提交/合并/归档。详见行为子任务research/upstream-alignment.md及upstream-check-result.md。

2026-10-20父最终验收：53源c1d805b9（与P2候选相比仅两测试变化）通过当前8模块2507项/364 suites，0失败/错误/跳过；frontend108和生产构建通过，API消费/模块边界/JAR/三模块Dokka通过。补齐真实fromCore的LIVE-LIVE/LIVE-SIM/SIM-SIM模式矩阵，并以非空真实LIVE claim/Dispatcher/正式信号验证同源两SIM运行关闭不改源状态。父AC4/AC6/AC7代码及自动化验收通过，现场Paper/Folia/client/browser未执行；保留上游fatal host故障全进程停机政策。提交/合并/归档暂缓。最终索引见行为research/parent-acceptance.md，机器证据parent-final-evidence.json及独立parent-acceptance-check.md。
子任务3/4实现与自动化验收均通过；未提交和未归档是当前交付状态，不作为产品功能缺口。
