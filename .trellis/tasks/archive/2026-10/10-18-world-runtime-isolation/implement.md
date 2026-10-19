# 子任务执行计划

## 前置条件

- [x] 父任务规划已于2026-10-18获批；2026-10-19用户明确批准进入World隔离实现。
- [x] 依赖已满足：事件分支已通过423ea7dd合入主分支并归档，组合2343项测试通过。
- [x] 工作区.worktree/world-runtime-isolation，分支codex/world-runtime-isolation，基线8f156900；主工作区启动前干净，文件ownership按研究结论派发。
- [x] 读取父/子prd、design、manifest及phase 2.1，再启动本子任务；不启动父任务代替子任务。

## 实现与检查

- [x] 原计划owner/runner、管理事务与共同装配实现完成。
- [x] 实现新增O1/O2：快照构造按World独立，保留观察/发布/退休语义。
- [x] 原计划宿主、管理及关闭barrier/失败矩阵通过。
- [x] 新增真实fromCore转换A阻塞但B构造/发布继续，以及同World顺序和close/remove交错回归。
- [x] 原冻结候选的全范围Trellis检查完成。
- [x] O1/O2完成后重新派发全范围Trellis检查，明确覆盖共享观察服务的运行路径。静态复核无剩余具体缺陷，等待完整门禁后核对最终证据。
- [x] 原冻结候选的定向及8模块完整门禁通过。
- [x] O1/O2实现后先运行DefaultRailwayObservationServiceTest及相关准入/信号回归，再更新候选指纹并重跑本批完整门禁。

```bash
./gradlew :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :TunnelBridgeBuilder:test
./gradlew :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries
python3 .trellis/scripts/task.py validate .trellis/tasks/10-18-world-runtime-isolation
git diff --check
```

- [x] 原候选的实际owner规范、父验收及原任务对应开放项已更新。
- [x] O1/O2实现/检查后同步观察服务规范和新验证结果，不把原候选测试标为新增项已完成。
- [x] 只提交本批拥有的修改，向父任务提供commit、验证结果和未验证范围。

## 最终集成

跨子任务并发/业务对照/平台副作用隔离由父任务最终复核，不以局部绿测试替代。

2026-10-19启动：已有任务获明确实施批准；先核对全局锁和运行装配的真实调用，再执行本批。主工作区既有积压已全部提交，不再沿用父任务历史WIP描述。

## 本批最小变更边界

1. Core的帧间准入计数目前不串行两个host callback，因此为每个World提供可重入owner入口，并让现有帧间入口复用它；按真实持有关系支持区域等待的释放/恢复。线程分类和互斥分离，不给所有World绑定同一个线程。
2. WorldManager改为逐World推进及合并唤醒，全局scheduler只触发，不在遍历中等待任一World的ticket。迁移具体World的Bukkit入口；必要跨World目录/重载事务按稳定顺序取得有关owner及帧间准入，普通World不拿共享事务锁。玩家会话check/apply按玩家协调，不持玩家锁等待另一World的Future。
3. 补齐DefaultRailwayObservationService的共享锁范围，真实fromCore转换在目标World边界/共享目录锁外执行，并保留同World缓存、通知和退休语义。只改本服务及必要真实consumer，不以全服计算锁代替World隔离。
4. 实际与模拟复用已有领域装配及发布后阶段，补速度安全、静态限速signal materialization、exact epoch脱轨消费和admission update；捕获准备值/工厂，每目标World创建新的扩展registry/provider实例，提供后续真实Feature安装所需最小接线。

预计产品ownership为CoreWorld、PhysicsCoordinator、最小World owner类型；BukkitThreadGuardBridge、WorldManager、TrainControlManager、TrainResourceReloader、RailWorldFactory、TimetableHttpBridge、隔离capture工厂和真实service/编辑调用方；SimpleATO共同运行装配及IsolatedTimetableSimulation；Basic仅真实复用所需。实现代理拥有产品/测试，父会话拥有任务与规范，两研究代理分别只写lock-ownership与runtime-assembly研究。

不能将所有source replacement机械改为持World owner：SignalEpochSource原有离线prepare与bounded atomic swap允许旧epoch继续旧source，现有该回归必须保留。完整SimpleSignals运行安装、逐车JS行为、持久化兼容、数据迁移、部署和下一批实现均不在本批。


## 实现分工与已确认取舍

- `world_isolation_implement`：Core owner/帧间入口、Bukkit bridge与WorldManager、service目录、玩家控制、HTTP和其他实际caller；不编辑RailWorldFactory/隔离capture工厂及SimpleATO共同装配。
- `world_assembly_implement`：SimpleATO具体OperationalWorldRuntime/模拟本地端点/extension目标工厂；Bukkit RailWorldFactory与IsolatedTimetableSimulationFactory以及对应装配测试。与主实现代理约定WorldManager调用接口；不编辑Core、WorldManager或service目录。
- 父会话：任务/owner规范、整合及最终验收。检查代理已做首段只读Core审查，正式最终检查仍需全范围执行。Gradle构建由两实现代理串行协调。

普通owner不取得进程级共享读写门禁。低频LIVE目录事务的mutex只保护其管理快照、发布及相关World集合；模拟本地运行不参加它。曾考虑的global RW方案会使等候LIVE管理写锁间接挡住无关SIM，已在未发布阶段撤回。owner暂停恢复须继续使用原held-owner列表对象，确保嵌套scope退出更新同一记账；新回归覆盖混合World嵌套后再次暂停。


## 终止与资源所有权补充

独立检查确认HTTP超时/取消会中断exact ticket等待，调用`use`清理时物理或generic tick callback可能仍执行。原`PhysicsCoordinator.close()`只禁止新epoch并发出取消，不构成worker退出证明。模拟释放feature/domain前新增实现层明确终止确认：关闭并在state/topology锁外等待自身executor实际退出，临时吸收取消中断并最终恢复；失败ticket或CLOSED不作为静默卸载依据。generic TickManager worker遵循同一边界。等待只持目标SIM owner；LIVE保存尝试后及启动后注册失败的资源释放也复用此终止边界，未启动的factory构造仍按原资源栈清理。未退出的第三方callback会保留资源并低频日志，不新增强杀、超时卸载或通用恢复状态机。同JVM任意callback永久不退出不是本任务提供进程隔离的承诺。

最终检查还覆盖runner目录卸载不永久强持World、atomic signal commit重入在取得其他World owner前拒绝，以及JS加载完成由目标World帧间提交，避免普通单World命令占global scheduler等待owner。


## 当前交付状态

2026-10-19：原owner/runner、管理事务、共同装配及新增O1/O2均已实现，独立静态复核无剩余本批具体缺陷。最终44源码指纹`702e1db6c9b6e16e8af8cb18cfdec639f9d204d432f7f4441e7383a842bd3c4e`，八模块2395项全执行、零失败/错误/跳过，API/module/shadow/Dokka及完整docs check通过。原42源码/2391项仅保留为历史证据。参见[最终验证记录](research/validation.md)、[独立检查](research/check-result.md)与[最终提交清单](research/commit-plan.md)。已按用户确认清单本地提交并快进合入dev/signal-system：Core5d8cdabe、runtime1423116d、docs-sitecb9263b。只归档本子任务，不启动子任务3/4。

## O1/O2补充执行顺序

1. 先读Basic threading-and-publication及当前观察服务/正式测试，确认actual caller、同epoch owner变化和removeWorld/close边界。
2. implementation ownership以`DefaultRailwayObservationService.kt`和同名测试为主；必要caller才扩大，不动SimpleSignals的S1产品实现。
3. 在共享锁外完成真实转换，短锁只做必要目录/状态交换；维护现有owner/帧间和listener边界。
4. 确定性barrier进入真实转换，验证A暂停B继续、同World有序、缓存复用/最多一次通知和退休交错。
5. 独立check复核本批全范围，再生成新candidate hash及完整门禁。原2391项和108项只保留为原候选证据。


2026-10-19续行：用户回复“那你继续完成本批次”，批准实施最新O1/O2补充计划。复用原实现工作区与Trellis implement/check流程，产品ownership为观察服务与正式测试，必要caller经实际依赖确认；原候选日志/XML保存于`/tmp/world-runtime-pre-observation`，新结果单独收集后再更新最终清单。

2026-10-19 O1/O2实现完成：仅观察服务及同名测试两文件，真实转换在World entry内串行且在共享目录锁外；entry identity拒绝退休迟到结果，保留UUID订阅。56项focused全通过；独立check静态确认及44源码逐SHA一致，候选702e1db6c9b6e16e8af8cb18cfdec639f9d204d432f7f4441e7383a842bd3c4e。完整八模块门禁已通过2395项；root及checker分别核对全部356份XML和源码SHA，最终docs check也通过。
