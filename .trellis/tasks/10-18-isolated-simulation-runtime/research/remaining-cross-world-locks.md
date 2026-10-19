# 剩余跨World共享锁与验收归属（2026-10-19）

## 结论与证据基线

用户要求先确认本批原有锁拆除是否完成，再把遗漏项补入计划。计划扩充后用户批准继续实施；当前O1/O2已经完成并重新验证。原先明确列出的simulationLock拆除、World owner/独立runner及入口迁移已实现，原冻结候选42源码指纹为`9f1a685d4da7edaca5a767ac072f67c9aa054cfacf727524968da243b484f851`，2391项JVM与108项前端测试通过；未提交合入。新增项不能沿用这些结果宣称已实现。

以下是两处分别存在的跨World串行点：不同LIVE World竞争信号JS求值执行器；不同LIVE World竞争observation构造使用的服务锁。两种工作彼此没有共用同一把锁的含义。原设计已经要求模拟使用独立JS实例，但没有明确落实LIVE World之间的求值隔离；observation转换位于服务共享锁内也未被本批原验收覆盖。

## O1：observation构造，归子任务2

- 原基线源码（8f156900，当前实现已修正）：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultRailwayObservationService.kt:26,68-98`。`current`和`publish`在服务级lock内调用`currentLocked`，后者在第79行执行真实`RailwayObservation.fromCore`。监听器已在共享锁外调用，不能把缺口描述成listener互斥。
- 目标：把不可变frame到observation的转换放在目标World执行边界内、共享目录锁外；各World的缓存更新及发布仍正确串行。共享目录/订阅登记只保留短临界区，不在其中构造快照、等待ticket或调用listener。复用现有World/服务入口，不新增Environment、通用缓存/调度框架或永久World tombstone。
- 保持：same-frame缓存复用、同epoch owner frame变化可更新current但不重复通知、epoch不回退、每物理epoch最多一次通知、listener异常隔离、unsubscribe/owner注销、removeWorld/close后无迟到结果重新填回失效状态。
- 验收：在真实fromCore转换A时用barrier暂停，B必须能完成自己的current/publish及对应领域阶段；同A竞争仍维持上述缓存/通知合同。测试必须进入实际转换或受保护的数据路径，不能仅暂停外层owner或运行独立空任务。补close/removeWorld与转换交错回归，按现有生命周期归属处理，保留实际与模拟现有观察链一致性。
- 范围：修改现有服务及其正式测试，必要时调整真实caller；不改变公共wire或持久化格式。完成后重做本批最终检查、候选指纹及受影响完整门禁。

## S1：各World信号JS求值，归子任务3

- 源码：`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt:959-990,1025`。manager只创建一份PreparedSignalPacks.logic并传给不同World的SignalPackEpochRuntime；`signalpack/SignalPackEpochRuntime.kt:115`调用它；`signalpack/SignalLogicRuntime.kt:42-58`以实例monitor串行整个真实JS求值。
- 目标：LIVE A、LIVE B及每个SIM分别拥有SignalLogicRuntime的执行与关闭归属；同World仍保留必要局部互斥，不通过直接删除`@Synchronized`使同一执行器并发访问。纯只读catalog、规则源和已证明可共享的编译输入可以复用；Engine共享属于可验证的实现优化，不是先决条件，不共享Context/可变运行状态。
- 安装：维持完整occupancy/epoch source、route planning、inspection、资源贡献/准备及admission更新接线。manager共享锁只负责必要目录/发布协调；单World候选编译、规则执行和其等待不能占跨World目录锁。物理回调不取得host owner锁。
- 生命周期：关闭/失败A不关闭B的执行器或SIM资源；公共规则包仍为只读输入。保持每次求值fresh realm、规则语义和同epoch事实，不增加旧开发数据兼容或重写simulation版规则。
- 验收：两个真实LIVE World、LIVE与SIM、两个SIM分别使用正式规则和真实SignalLogicRuntime；A处于JS求值或持其执行锁的确定性阻塞期间，B继续物理/信号发布并得到正确输出；同A执行有序。关闭/失败/重新安装只作用于所属实例。只测试SIM新建对象或空source不足以关闭该项。

## 其他共享锁的边界

- 保留必要的LiveWorldManagement管理事务；资源重载/provider/contributor变更仍须协调实际有关World的检查-提交。
- WorldTickRunnerRegistry、WorldManager指标/快照队列，以及基础设施/信号服务目录可保留共享短锁；它们不能被用来包围普通World长时间计算或ticket等待。
- Core启动/注册锁（Koin、PluginRegistry、extension类型、ThreadGuard bridge和事件注册）保留相应生命周期归属；事件分发不持注册锁。
- ModelManager、平台显示/文字/预览服务、RailRenderMetaStore、SimpleSignals定义存储、脚本文件存储、日志/诊断等共享锁仍存在，其中部分包含I/O或集合扫描。本次不将这些项声称为已拆除，也不把全部I/O锁笼统称为短锁。它们不作为新增的“全面去锁”项目；若实际consumer把单World普通领域计算放进这些共享锁，须在所属子任务中明确处理和验证。
- 同JVM的CPU、GC、第三方代码不退出等进程资源限制保持原非目标。

## 当前状态

- 原锁/调度实现与原候选测试：完成，未提交。
- O1/O2：已在子任务2完成，真实fromCore转换在World entry中独立串行，共享目录锁外运行；entry identity拒绝退休迟到结果，保留UUID订阅语义。新增4个正式回归及56项focused通过；最终44源702e1db6，8模块2395项和完整docs check通过，独立检查无剩余本批具体缺陷。待本地提交/整合，见子任务2最终证据及替换后的提交清单。
- S1：已补入信号批次prd/design/implement和上下文，任务保持planning，尚未启动实现。
- 父任务AC4/AC5/AC7保持未完成：O1/O2子验收已通过，S1及完整信号/车厢行为和最终联合验收仍待完成。
