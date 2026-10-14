# Lifecycle 与线程归属

## Composition 顺序

`BukkitPlugin.kt` 的阶段边界：

- `onLoad`：检测 platform capabilities、创建 dispatcher、读取 physics/global config、加载 packet runtime；需在连接注入前完成的工作放这里。
- `onEnable`：enable packet/runtime、创建 render backend、启动 Koin/Basic/plugin registry、安装 `ThreadGuard` bridge、注册 world/service/listener/control、加载模型、初始化 Wand/Cloud、注册 worlds，最后启动 WebUI sidecar 与 `WorldManager`。
- `onDisable`：先停止外部入口/sidecar/HTTP，再停止 world manager、保存并 unregister worlds，关闭 control/event/log/packet/thread bridge。每步通过有上下文日志的 cleanup 隔离异常。

新增 component 必须回答：谁创建、何时可用、谁注册、如何停止、world unload 与 plugin disable 是否都释放。不要依赖 JVM 退出回收 executor/task/registration。

主插件卸载顺序：先卸载依赖 JavaRailways 的附属插件，完成它们提交的显示清理，再卸载 JavaRailways。
附属插件必须声明对主插件的依赖，但依赖声明和 `onDisable` 返回本身不能证明跨区域删除任务已经完成。
通用模型显示服务的清理完成结果及强制停用边界见[模型生命周期契约](model-presentation-api.md#5-线程关闭和世界重载的规则)。
不在已禁用的主插件 `onDisable` 中阻塞等待区域任务，也不把整服停止误报为信号资源故障。

独立信号/ATO附属插件的正常停用必须先显式unapply其计划依赖，再调用唯一的
`InfrastructureResourceService.unregisterOwner`。该调用同时负责插件的基础资源注册和信号source全集；
`SignalEpochSourceService`不提供第二个完整owner删除入口。它允许空epoch-source replacement只清空该插件在该world的
区间占用callback；同owner的inspection、route-planning和infrastructure注册保持不变。如果三类signal source均为空，
内部可丢弃空owner记录，但这不等于`InfrastructureResourceService.unregisterOwner`，也不能删除基础资源owner。
该调用只是可检查的pre-disable步骤，不会也不能替Bukkit禁用插件；任一world仍有infrastructure dependency、pending
route reservation、非空host-owned claim、由其他requester持有的该插件resource type、active inspection binding或该插件
source发布的非空restriction流时抛出诊断。基础资源侧和signal侧的检查全部成功以前，provider/contributor与三类signal
callback均保持原样；只剩空route state和空restriction stream时才允许一次性移除这些注册及可丢弃platform ownership。
无关列车、仅已安装但restriction流均为空的source和单独存在的route-planning callback不阻止停用。`PluginDisableEvent`本身不可取消，
事件路径因此只处理“已经被强制禁用”：保留infrastructure dependency，移除普通provider/platform ownership和失效
signal callback，但不释放route-admission claim/reservation；只对该插件仍有实际基础资源使用或非空restriction的world
通过既有Stall回调fail-close。
检查同时覆盖插件作为requester持有的路线，以及它作为provider注册、但由其他requester持有的stable resource类型；
provider ownership不另建长期索引，而是只在低频停用边界读取infrastructure dependencies、现有claim、release queue和pending compiled request。
单个`InfrastructureResourceProviderRegistration.close()`执行同一检查，不能绕过owner unregister删除仍被使用的type。
route-resource contributor集合在任一world仍有route state时不可增删；强制失去contributor会通过既有Stall owner
fail-close该world，不能让后续suffix申请少执行一套信号策略。
空claim、无pending、仅遗漏`endGrant`的route state可直接清除。JavaRailways自身禁用、service已关闭或
`Bukkit.isStopping()`时走正常world/service close，不把服务器shutdown误报为附属插件故障。不使用永久owner tombstone、
启用周期token、自动drain、跨插件实例继承或恢复状态机。

跨service回归测试使用SimpleSignals实际的`simplesignals:fixed-block`、
`simplesignals:fixed-block-signals`、`simplesignals:protection-regions`和
`simplesignals:fixed-protection-routes`身份锁定这条顺序：任一claim/dependency/restriction/binding blocker拒绝planned
unregister时两侧注册仍可观测；依次清除blocker后同一入口成功释放全部身份。forced disable则立即移除死callback，保留已提交
claim/dependency，并只让包含该owner活动状态的world进入Stall；仅安装空闲planning callback的world不受影响。

`SignalEpochSourceService`同样绑定plugin instance和exact world runtime。全集replacement可删除其中部分或全部epoch source ID，
但删除前插件必须先对每个对应`(sourceId, trainId)`流发布更高revision的空restriction snapshot；空replacement只影响
epoch source类别，完整owner删除仍只由上述
统一pre-disable入口执行。`replaceSourcesAtomically`不取得跨world simulation lock；它验证并编译候选全集后运行一次调用方
bounded commit callback，callback失败保留旧runtime/owner set，成功后只做已编译引用替换。已开始的epoch完成其捕获的旧source，
下一epoch读取新source并建立baseline。callback只允许already-staged atomic rename与immutable state swap，不得执行graph/index
build、JSON encode、ordinary file write、Bukkit调用、service重入或future wait；host在任何其他source类别被修改前明确拒绝重入。
online editor必须在async worker调用这个可能随完整area集合增长的API。普通`replaceSources`等价于使用空callback。若附属插件被强制禁用，
host总是删除失效callback，但只在仍有非空owner restriction时Stall该world；world unload与JavaRailways shutdown只清理
binding，不误报故障。基础资源和signal的强制清理由同一个listener触发，不维护两套disable顺序。

`InspectionSourceService`由同一个内部owner service实现并注册到`ServicesManager`。插件注册的是
`InspectionSource` callback，host用自己选定的exact train/`RouteSnapshot`调用binding provider；API不暴露让插件先猜路线
再提交binding的不可用流程。dirty publication同时认证plugin instance、world和source ownership。活动路线上的inspection
source增删/替换不能静默放宽既有授权：普通replacement经world coordinator fail-close并保持旧全集；forced disable先Stall
存在active binding的world再移除死callback。world unload和JavaRailways shutdown直接清理binding，不把正常teardown误报为
插件故障，也不创建独立inspection unload入口。主动unregister若仍有active binding只拒绝卸载，不Stall仍可正常运行的world；
只有插件已经被强制禁用、callback确定不可再调用时才执行fail-close。binding provider/update handler同步运行在simulation owner，
必须有界、非阻塞且不得重入source service或直接访问Bukkit world。

`RoutePlanningRestrictionSourceService`也复用上述plugin/world owner registry和唯一
`InfrastructureResourceService.unregisterOwner`入口。它整体replace某owner的namespaced source bindings；world runtime
在实际选路前捕获已排序全集并调用每项一次。规划callback不是live train restriction或claim：正常unregister与forced
disable都直接清除callback，单独存在规划source不阻止停用也不stall world；已冻结路线继续由其route snapshot、Basic验证和
资源claim约束。若应用中的时刻表依赖该插件定义，则由既有infrastructure dependency先阻止正常unregister，不能把规划source
注册本身冒充运行期锁。

## Simulation 与 physics

Bukkit是列车控制的composition owner，但不是动力学owner：它从当前renderer的pinned/live策略解析
`TrainDynamics.TrainProfile`和逐车`CarProfile`，读取必填`physics.yml`的`track.gravity`，再构造Basic
`RealisticManualController`或SimpleATO的`HandleTrainActuator`。不得在Bukkit恢复纵向公式、硬编码重力或为已删除的
Bukkit controller保留兼容facade。隔离模拟capture必须把重力和profile一起冻结，worker阶段不得访问live renderer。

`WorldManager` 用 global scheduler 每 tick 驱动串行 simulation context：

```text
wake physics + generic preTick
  → capture exact PhysicsTicket and await
  → consume immutable published frames/safety requests
  → timetable dispatcher + generic postTick
  → dispatch platform commits to target owners
  → periodically capture immutable snapshots
```

只 capture 一次并等待该 ticket；不能在 physics failure 后消费旧 frame。snapshot capture 在 simulation owner 完成，JSON/IO 交给单线程 writer，per-world pending 采用 latest-only。

### 控制器接管与命令的帧间适配（CURRENT，第3b批平台侧）

玩家的座位、钥匙和UUID在entity owner捕获，随后将完整的接管、驾驶端对齐、Dispatcher控制交接及controller安装提交至
world的`submitBetweenFrames`。不能只异步化setter而让上层继续假定安装完成。导航的临时目标、验证、释放、启动和失败恢复
同样位于一项操作内；失败恢复须同时恢复controller和manual session，准备拒绝不能提前丢弃会话。时刻表start/reset/replan/stop、
ATO reset及必要的Depot reset命令也整体排队。执行前检查loaded world、train、controller和已有session的确切身份；
玩家反馈及测时报告回entity scheduler，console消息回global scheduler，不在队列中读取Player的world/location/inventory。

`TrainControlManager.acquireManualControl`、外部release和switch方法返回Future，UUID形式的内部register、releaseTrain和导航prepare/restore
保持同步，用于已进入帧间阶段的复合操作。会话表按实际train实例区分世界，不用可能跨world重复的train ID作唯一键。
pending acquisition持有目标train身份，seat-vacate/quit/unbind或目标world退休会撤销它；旧请求不能在驾驶员已离开后安装controller。
运行中controller已被物理脱轨或其他接管移除时，只删除仍是本请求的旧本地session，不改替代controller或Dispatcher，不能误报Acquired。
跨线程读取的`ManualControlSession.timetableMode`由volatile发布；不据此授权entity线程访问controller可写状态。

world卸载先撤销loaded lookup，再在有效帧间阶段释放该world控制权，且不drain已排队的外部请求，随后beginShutdown/最终捕获。
本地session及待接管引用另作一次exact-world退休，即使physics已STALLED/CLOSED也执行，不尝试修改或恢复停止的物理系统。
因此旧世界会话不会通过failed release阻断玩家接管另一world。插件停止时只取消平台扫描并清理管理器本地映射，不在world关闭后修改controller。

协调与映射操作为低频请求成本；队列/准入合同归[Core runtime](../../javarailways/core/runtime-publication.md)。
需验证旧controller仍在途时不修改、下一worker只运行替代controller、方向失败不提前改显示、导航失败恢复及unavailable-world卸载仍退休本地引用。
创建/摆位、完整外部脱轨与基础设施编辑仍按后续批次迁移；本平台适配不表示这些操作已经收口。

## Folia 线程归属

- `PlatformDispatcher.runGlobal*`：无 location 的 simulation/value 协调。
- `runAsync`：纯计算/IO；完成后不能直接触碰 Bukkit state。
- `runAt(location)`：block/location/region operation。
- `runForEntity*`：player/entity inventory、message、dialog、movement 等，并处理 retired callback。
- `BukkitThreadOwnership.ensureLocation/ensureEntity`：执行点断言，不是 scheduler。

`ThreadGuard.ensureMainThread()` 在 Bukkit bridge 下只证明 simulation serialization；对 entity/location 副作用仍要 target-aware ownership。参考 `BukkitThreadOwnership.kt`、`PlatformDispatcher.kt`、`BukkitThreadGuardBridgeTest.kt`。

异步结果回 owner 后应重查：plugin/world 是否 stopping/unloading、player/entity 是否仍有效、world 是否改变、revision 是否仍是最新。`BukkitPlugin`内部的`LatestAffinityTaskDispatcher`为现有车辆画面与platform commit提供per-key one-in-flight + latest-pending模式来限制region backlog；最终呈现对象负责stale revision检查。它不是`SimpleATOBukkit`或附属插件的公共API。不要恢复逐tick无界task queue；未来确有跨插件复用需求时再单独评审public platform facade。

在线基础设施编辑不得把“低频”当作阻塞平台线程或跨world simulation lock的理由。统一流程是：`runAsync`完成curve/geometry、
完整graph/index、serialization、validation与staging I/O；回到受影响world owner后只复核captured root/permit并发布prepared value；
玩家消息、render和其他Bukkit side effect在publication之后按target ownership执行。commit复杂度必须只与实际delta相关，不能在owner
线程重新做`O(world)`扫描。当前信号definition事务遵守该边界；RailWand的create/rebuild/smooth/split/merge/remove/snapshot-restore
也在现有async prepare中完成curve、diamond与完整normalized topology构建，owner transaction只验证exact base/delta并publish prepared root。
结构编辑先异步准备topology candidate，随后获取exact affected-infrastructure permit；permit持有期间由worker构造whole-world undo
`before`/`expectedAfter`，再回到owner复核exact root并提交。这样非拓扑store不会在undo capture与commit之间被重叠编辑。runtime active branch
不属于structural undo：准备值把它归一化掉，提交不能因为无关的道岔选择变化而失败或覆盖该选择。
`RailRenderMetaStore`的整文件编码、临时文件写入和旧primary复制也在该worker阶段完成；owner只验证primary身份并原子替换文件。
一个per-world writer slot串行化这个单文件边界，等待只发生在worker；准备值还比较捕获时的完整record集合，因而两个不相交轨道编辑
不能用各自的旧快照覆盖对方。若Core/player提交在文件发布后失败，仍持有edit permit时原子恢复旧primary，随后异步清理staging并释放writer slot。
正常准备和比较成本为`O(render record count)`且不阻塞physics/owner，owner publish/rollback只包含文件身份检查、rename和cache引用替换。

### 在线编辑并发验证边界

`RailWandTopologyEditPlanTest`在真实Core轨道准备的curve采样处以latch暂停worker，
同时让另一world的真实`PhysicsCoordinator`和`ManualController`完成两个确定性epoch。
必须从成功ticket之后的公开`latestWorldFrame`断言列车位置前移，而不能用线程存活、
锁外read或手工构造的frame代替物理推进。采样暂停期间、完整candidate准备后但提交前，
编辑world均保持原root和rail inventory；正常提交才发布新rail与明确连接。
另一个测试在准备后故意改变root，断言stale commit拒绝且mutation closure未执行，
保留此次故意编辑后的准确root/inventory，而不是错误地要求回到准备前状态。

这些准备阶段测试与Core已有的`CoreWorldRailTopologyTransactionTest`暂停真实提交时另一world完成epoch的测试互补。
signal source的旧epoch/下一epoch交接和edit permit不等待全局simulation lock，分别由
`DefaultSignalEpochSourceServiceTest`、`DefaultInfrastructureResourceServiceTest`与
SimpleSignals的`SignalReplacementContinuityTest`覆盖；不为同一机制重复添加测试。
这组证据覆盖production准备/提交/物理边界，不等于玩家操作到Folia调度器的完整smoke，
也不是规模负载下的延迟基准；无新增生产线程、接口、revision或兼容路径。

## 世界 lifecycle

- world load 的 split snapshot `Missing` 可创建新 world；`Corrupted` 禁用该 world 的 JavaRailways 功能并要求修复，不能当空世界覆盖。
- world factory从创建`TickManager`起负责尚未发布的构造资源；后续Core load、extension restore、materialize或render metadata恢复失败时，按render→physics→tick逆序关闭。资源只有在factory完整成功返回时才移交给`WorldManager`。
- unload先标记unloading并撤loaded-world lookup，再用coordinator `beginShutdown()`在其原stateLock内永久禁止新main/timer/simulation epoch并捕获exact ticket；在锁外成功await该ticket后才捕获/写最终snapshot，最后close coordinator/render/tick owner。beginShutdown保持RUNNING以允许在途正常publication，不是close/abort；失败ticket必须报告失败，不回退旧epoch，也不拿失败作为worker全部退出的证明。
- snapshot capture/write或任一teardown步骤失败时，仍须分别尝试撤销topology/frame订阅、三个宿主service、physics/render/tick owner、异常标记与owner map。最先发生的异常保持为主异常，后续清理异常作为suppressed保留；不得因清理成功吞掉保存失败，也不得让旧world留在主tick集合阻止同UUID重新绑定。owner map删除使用卸载开始时捕获的exact world identity，不能误删随后绑定的新world。
- platform commit 执行前重新解析 affinity；目标跨 region 时 reschedule，而不是在旧 owner 上执行。
- 每车JS行为见[车厢行为契约](car-behavior.md)。最终snapshot保存之后，先令该world尚未完成的模型操作future失败，再分别关闭每节车厢的行为及延迟效果，随后关闭physics/render/tick；不能等region队列执行才完成旧ticket。各车关闭独立尝试，某车失败不跳过其余车厢或后续owner。

上面的physics保存边界、CR-013卸载失败清理和CR-015 factory partial-init清理均已接入生产入口。没有因此承诺Bukkit跨region清理完成、失败输入可自动修复或Koin scope已经成对释放。

### 构造失败资源归还（CURRENT）

1. **范围、触发与owner**：`RailWorldFactory.createForBukkit`拥有尚未返回的world构造；从第一个tick executor创建成功起，到完整world返回前，调用方没有对象可清理，因此factory不能把失败归责给`WorldManager`。
2. **正常流程**：创建顺序为tick→physics→Core world/DI attach→render listener→extensions/restore/materialize/render metadata；全部成功后清空构造资源栈并返回world，此刻资源所有权一次性移交给已发布world lifecycle。
3. **内部机制**：`WorldConstructionResources`只记录本factory已成功取得的close动作，使用LIFO执行，因此后取得的render先于physics和tick关闭。它不是公共API、通用事务或extension rollback框架。
4. **构造器边界**：`RenderChunkManager`可能在注册listener后、完成initial-player refresh前抛出，此时factory尚未取得对象；构造器自身调用`close`，最终注销listener。`close`保留首个异常、继续清理其他player并把注销异常记为suppressed。
5. **接口与数据**：正式factory签名、split snapshot、revision/epoch及world map均不变；未新增schema version、兼容输入、自动重试或空world fallback。
6. **失败矩阵**：tick创建失败时无资源；physics创建失败只关tick；Core load或DI attach失败关physics/tick；render构造内部失败由其自身撤listener，再由factory关physics/tick；之后任一步失败关render/physics/tick。构造主异常始终保留，清理异常按发生顺序suppressed。
7. **线程与发布**：入口仍要求simulation main owner。失败world从未进入`WorldManager`可见map，也不得start/wake physics；清理不发布替代world，不吞掉坏snapshot诊断。
8. **性能与退化**：构造资源栈固定最多三个close动作，为O(1)；正常成功路径只有三次注册及一次clear。失败不扫描其他world、不重试I/O，也不增加常驻线程或锁。
9. **必需测试**：验证render→physics→tick逆序、主异常与suppressed顺序、成功移交后不误关；验证render listener已注册后initial refresh失败会注销，且注销失败只作为suppressed。模块完整测试与`shadowJar`仍是最终门禁。
10. **开放项与错误示例**：world context registration现由Core返回并加入同一构造栈；它只关闭DI binding。错误做法是在调用方包围factory、只关闭最后一个资源、用清理异常覆盖restore异常，或让render构造器注册副作用后抛出而无人持有；正确做法是各owner在对象可见性边界内归还自己已取得的资源。

### 卸载失败隔离（CURRENT）

1. **范围、触发与owner**：`WorldManager.unregisterRailWorld(BukkitWorld)`负责已发布world的宿主卸载。适用于正常world unload与插件停用逐world清理；未返回world的factory partial-init和Folia region task完成不在此机制内。Core-owned world context token在宿主consumer关闭后由此入口释放。
2. **正常流程**：simulation/main owner先把world加入unloading集合并撤销UUID lookup，捕获当时`railWorlds[bukkitWorld]`的exact对象；随后停止新physics epoch、等待exact ticket、捕获并写split snapshot，再依次撤订阅/service并关闭physics、render与generic tick owner，清异常标记，最后移除旧owner map。
3. **内部机制**：保存和每个teardown动作分别交给同一个顺序执行器。执行器记录首个`Throwable`，继续运行后续动作，并把之后不同的异常按顺序加入首个异常的suppressed列表；全部动作结束后才重新抛出。map删除使用`remove(bukkitWorld, capturedWorld)`，所以清理期间即使同一key已换成新world，也不会被旧清理删除。
4. **接口与数据**：正式入口仍只有`fun unregisterRailWorld(bukkitWorld: BukkitWorld)`；用于失败矩阵测试的internal overload只替换持久化、marker、render和tick动作，不改变生产owner或形成add-on API。`physicsEpoch`仍来自成功完成的exact `PhysicsTicket`，不是schema version。
5. **生命周期与线程规则**：入口必须通过`ThreadGuard.ensureMainThread`；UUID lookup在任何blocking await/I/O前撤出。保存失败也必须关闭资源并撤owner map；失败不能回退旧成功epoch或重新开放wake。新binding只能由后续load重新发布，旧清理不能按UUID宽泛删除它。
6. **失败矩阵**：physics/capture/write失败保留为主异常，后续cleanup仍执行；订阅/service/render/tick/marker失败时继续下一项；多个失败时首个为主、其余为suppressed；无已发布owner时只做lookup/marker撤出，不猜测或创建world。任何失败都不得被记录成保存成功。
7. **性能与退化**：固定service/owner关闭为O(1)，未完成操作、车厢行为及已接受延迟效果关闭为O(P+N+E)，P为world追踪的未完成操作数、N为车厢数、E为仍登记的效果数；snapshot capture/write另随world数据量增长。失败时不重试、不复制snapshot，也不新增后台队列；退化行为是逐项尽力释放资源后向调用方报告原失败。
8. **具体情形**：正常卸载写完最终snapshot后关闭全部owner；磁盘写失败仍从主tick集合移除旧world；render close失败仍关闭tick并清marker；清理回调重入并发布replacement时，exact identity删除保留replacement。
9. **必需测试**：持久化失败必须断言主异常身份、suppressed顺序、后续cleanup、physics CLOSED、map/lookup清除和同UUID重绑；teardown首先失败必须断言后续动作继续，并在final map remove前替换binding后验证新owner仍存在。模块测试与`shadowJar`门禁同时执行。
10. **迁移、开放项与错误示例**：无持久化格式迁移。CR-015/017均已接入各自owner。错误做法是只用一个`finally`包最外层close、在其外删除map，或用`railWorlds.remove(key)`删除可能已经替换的binding；正确做法是逐项失败隔离、释放world context并按captured identity删除。

线程相关权威证据：`WorldManager.kt`、`BukkitThreadGuardBridge.kt`、`PlatformDispatcher.kt`、`docs-site/docs/architecture/threading-model.md`。
