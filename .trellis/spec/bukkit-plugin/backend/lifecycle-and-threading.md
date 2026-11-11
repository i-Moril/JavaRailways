# Lifecycle 与线程归属

## Composition 顺序

`BukkitPlugin.kt` 的阶段边界：

- `onLoad`：检测 platform capabilities、创建 dispatcher、读取 physics/global config；不创建或初始化共享 PacketEvents。
- `onEnable`：绑定独立 PacketEvents、enable 自有 EntityLib/runtime、创建 render backend，安装 ThreadGuard bridge，注册 world/service/listener/control、模型与 Wand/Cloud，最后启动 WebUI sidecar 与逐世界运行器。
- 自身 PluginDisableEvent 在 enabled=false 前准备关闭；onDisable 幂等兜底。失效服务/入口，停止 dispatcher/physics，按当前逐世界 owner 恢复控制会话并保存，等待 owner cleanup 后释放自有 packet runtime、registry 与日志。独立 PacketEvents 继续运行；不恢复已经删除的领域 Koin owner。

`BukkitRailTargetService(plugin)` 构造时立即捕获本代 `plugin.worldManager`，因此 `enablePlugin` 必须先完成 `WorldManager(...)` 赋值，再构造 rail target service，最后向 ServicesManager 发布。不得依靠 lambda 形状推断依赖仍是延迟读取，也不得改回点击时解析下一代全局实例。更晚初始化的 TurnoutWand 可消费已发布服务。

实际启用失败会使附属插件随后出现宿主 API ClassNotFound；排查应先找主插件的第一个 enable 异常。2026-11-11 测试服曾复现提前构造的 lateinit worldManager 错误，按上述顺序修正后 Folia26.1.2、PacketEvents2.14.0、PlugManX3.2.1 及所有附属插件正常启动；两项 RailTargetService 回归通过，证据见主任务 research/merged-fixes-deployment-20261111.md。正常启动不等于在线玩家热重载矩阵已通过。

新增 component 必须回答：谁创建、何时可用、谁注册、如何停止、world unload 与 plugin disable 是否都释放。不要依赖 JVM 退出回收 executor/task/registration。

主插件卸载顺序：先卸载依赖 JavaRailways 的附属插件，完成它们提交的显示清理，再卸载 JavaRailways。
附属插件必须声明对主插件的依赖，但依赖声明和 `onDisable` 返回本身不能证明跨区域删除任务已经完成。
通用模型显示服务的清理完成结果及强制停用边界见[模型生命周期契约](model-presentation-api.md#5-线程关闭和世界重载的规则)。
不在已禁用的主插件 `onDisable` 中阻塞等待区域任务，也不把整服停止误报为信号资源故障。

独立信号/ATO附属插件的正常停用必须先显式unapply其计划依赖，再调用唯一的
`InfrastructureResourceService.unregisterOwner`。该调用同时负责插件的基础资源注册和信号source全集；
`SignalEpochSourceService`不提供第二个计划性完整owner删除入口。它允许空epoch-source replacement只清空该插件在该world的
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
epoch source类别，计划性完整owner删除仍只由上述
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
插件被强制禁用或一次领域安装已失败、callback不能继续使用时，才经对应失败清理入口执行fail-close。binding provider/update handler同步运行在simulation owner，
必须有界、非阻塞且不得重入source service或直接访问Bukkit world。

`RoutePlanningRestrictionSourceService`也复用上述plugin/world owner registry和唯一
`InfrastructureResourceService.unregisterOwner`入口。它整体replace某owner的namespaced source bindings；world runtime
在实际选路前捕获已排序全集并调用每项一次。规划callback不是live train restriction或claim：正常unregister与forced
disable都直接清除callback，单独存在规划source不阻止停用也不stall world；已冻结路线继续由其route snapshot、Basic验证和
资源claim约束。若应用中的时刻表依赖该插件定义，则由既有infrastructure dependency先阻止正常unregister，不能把规划source
注册本身冒充运行期锁。

## 失败安装按源对象身份退注册

`CURRENT`：Bukkit宿主的`SignalEpochSourceService.retireSources(pluginOwner, worldId, expectedEpochSources, expectedInspectionSources, expectedRoutePlanningSources)`仅供一次失败安装清理三类callback。调用方保留本次实际提交的源对象；普通计划性停用继续使用上面的withdraw/unregister门，不能把此API当作无条件清除插件全集的替代。接口不删除infrastructure provider/contributor/claim/dependency。

宿主捕获当前World binding，在该World owner中再次核对binding身份；仅在exact plugin owner集合中按`===`匹配每个候选对象。旧源与新源即使ID相同也不等价。同UUID世界重建、已卸载世界、没有匹配源都不修改后续安装。匹配的epoch/inspection若仍有非空restriction流或active binding，先通过现有stallWorld使目标World停止授权；停顿确认成功后才退对应callback。停顿抛错时保留实际安全源供重试，独立规划源仍可退休。每一成功子集更新宿主目录；失败保留首个异常及suppressed，不能回装已删除的源。空source owner记录移除，requiredDomainFeatures随真实剩余源变化，其他owner原样保留。

此入口在插件禁用或全局halt后仍可清理，禁止重入atomic commit。正常返回保证匹配源已退；抛错时调用方必须保留它们的引擎/资源并重试，不能在finally无条件close。源替换沿既有合同允许一个已开始epoch完成旧immutable快照；同步evaluate/close与checkOpen保证不会在关闭后执行JS，过期快照最多给出Unavailable，不增加跨World等待或全局drain。

例如epoch注册成功、inspection注册失败，应传入本次三类对象；不能以空全集replacement误删同ID新对象。源身份筛选为`O(N*E)`（本owner源数N、待退集合E），epoch监测区重编译复用既有union成本；这是低频安装失败路径，不新增每epoch扫描。

必需回归：部分类别/其他owner保留、同ID新对象及同UUID新World不受旧退注册影响、disabled/halt清理仍可用、atomic callback重入在副作用前拒绝、active状态仅停目标World、停顿失败保留安全源与重试成功后完整退注册。该接口仅运行态，不改存档或版本，不加旧实现默认兼容分支。SimpleSignals调用顺序和失败reservation归[信号模拟生命周期](../../simple-signals/backend/simulation-runtime.md)。

## Simulation 与 physics

Bukkit是列车控制的composition owner，但不是动力学owner：它从当前renderer的pinned/live策略解析
`TrainDynamics.TrainProfile`和逐车`CarProfile`，读取必填`physics.yml`的`track.gravity`，再构造Basic
`RealisticManualController`或SimpleATO的`HandleTrainActuator`。不得在Bukkit恢复纵向公式、硬编码重力或为已删除的
Bukkit controller保留兼容facade。隔离模拟capture必须把重力和profile一起冻结，worker阶段不得访问live renderer。

`WorldManager`用global scheduler每tick仅向各World的`WorldTickRunner`提交最新唤醒，global回调不等待owner或physics。每World独立单worker运行其领域阶段，至多一个执行中和一个latest pending；积压1000次唤醒也不重放1000项，释放后只处理最新值，不建立第二套物理循环。慢A的ticket等待只占A路径，B及本地SIM继续推进。周期snapshot随所属runner记录已成功捕获的周期bucket，合并唤醒跨过1200倍数时捕获一次，不补逐tick积压；capture/enqueue失败不消费该bucket，后续wake仍可重试，runner退休一并释放该记账。

```text
wake physics + generic preTick
  → capture exact PhysicsTicket and await
  → consume immutable published frames/safety requests
  → timetable dispatcher + generic postTick
  → dispatch platform commits to target owners
  → periodically capture immutable snapshots
```

每个World只capture并等待该次ticket；不能在physics failure后消费旧frame。snapshot capture在该World owner完成，JSON/IO交给既有单线程writer，per-world pending采用latest-only。跨World指标只聚合已完成的值，并发读写的recent metrics单独短锁保护，不能为指标重新把tick串成全世界屏障。

### 普通World入口与必要管理事务（2026-10-19）

`BukkitThreadGuardBridge.runInSimulationContext(action)`现在只建立原线程分类，不再持全局simulationLock；具体World入口使用带World参数的重载，进入真实CoreWorld owner。线程分类不是Folia entity/region权限。帧间准入、owner重入及暂停恢复由[Core publication](../../javarailways/core/runtime-publication.md#每world领域执行归属2026-10-19)拥有。

普通tick、HTTP World操作、模拟推进和具体World编辑都只进入所属owner。HTTP的稳定World读取/capture在async执行，不把等待owner的action排回global；整场模拟不持源World或LIVE管理锁。命令按真实目标决定归属，不能一律以sender.world推断；例如ATO指定其他World的reset只提交给该目标，不能先锁玩家World。

`LiveWorldManagement`只序列化LIVE目录/lifecycle、资源重载及provider/contributor/owner注册卸载。先在owner外进入其mutex，再短锁捕获稳定World集合，释放service短锁，按Core owner固定顺序进入有关World；计划性跨World检查/提交还在这些World的有效帧间准入内等待exact ticket，再全检查后提交。普通World与本地模拟不取得这个管理mutex。反例是持service锁等owner，或持单World owner后阻塞等管理mutex；管理入口须在首次取mutex前拒绝后者，合法管理重入保持原作用域。

provider/contributor变更、planned unregister必须阻止检查后新的准入或信号限制发布。检查失败则两侧注册都保留；forced disable、已停止World和宿主shutdown仍走原fail-close/cleanup，不因RUNNING帧间入口不可用而跳过死callback删除。World集合注册/撤销与管理快照保持同一边界，不能让新World躲过检查；正常卸载只停止目标runner、撤其lookup并等待其owner/shutdown，保留exact replacement保护与逐项清理。

Signal atomic replacement仍是特殊边界：同World替换由其owner串行，编译与bounded callback不持全service长锁、不拿physics帧间准入。已开始epoch继续旧source，下一epoch读取新source；A的prepare暂停时B的正常替换仍可完成。callback重入限制是调用线程局部状态，不把另一个World的并发调用误判为重入。

玩家接管保持旧World释放Future→新World入队的handoff；pending检查、session注册/释放和双索引更新按玩家短事务保护，不持玩家锁等待另一World Future或取得第二World owner。重复train ID继续以实际train/World实例区分，不增session代际或兼容路径。

共同领域装配/发布后阶段由[SimpleATO simulation](../../javarailways/simpleato/simulation.md#实际与模拟共用领域阶段)拥有；平台保留渲染、profile与service适配。owner同步成本不包含普通跨World扫描；多World管理成本随有关World及原检查集合增长。必须以barrier验证真实A/B/SIM推进、latest-only、目录检查提交原子性、玩家双请求、HTTP不挡global、source replacement旧epoch及卸载失败清理；离线回归不代替Paper/Folia现场验证。无持久化版本变化。

### 模拟Feature准备器登记

`SimulationFeatureService`通过ServicesManager向独立插件公开`register(Plugin, SimulationFeaturePreparer): AutoCloseable`。宿主在onEnable创建DefaultSimulationFeatureService，并通过JavaRailwaysServiceRegistration注册服务及owner禁用监听；onDisable先关闭登记目录，再停止World运行。插件名的小写形式与现有requiredDomainFeatures使用同一身份，每owner一个准备器；disabled/重复/服务关闭拒绝登记，token按registration实例删除，owner禁用按Plugin实例删除，不能误删重名新实例。

HTTP capture沿已有source World owner/帧间准入调用宿主prepareSimulationDomainFeatures，再由factory捕获返回的detached feature。service短锁只复制登记列表并检查closed，释放后依次调用preparer.prepare(source UUID)；回调可做该源的稳定快照读取，不能持共享登记锁编译或等待。返回feature.id必须匹配owner身份，异常直接使本次capture失败；缺必需owner的原factory检查保持。关闭目录不撤销已经捕获并属于SIM的准备值。

准备成本随登记数和原回调成本增长，短目录不增加世界扫描/后台队列。A准备器暂停时，B的独立capture、登记关闭和owner清理仍可完成；每目标安装及真终止后的资源清理由[SimpleATO公开安装边界](../../javarailways/simpleato/simulation.md)拥有。正式service/capture/registration测试与Java consumer须覆盖上述流程及失败身份，新增JUnit用例需核XML确实发现执行。无持久化格式或版本变化，不允许公开CoreWorld/内部domain给附属插件。

### 观察服务的构造与退休边界

`DefaultRailwayObservationService`当前按World独立构造Basic observation。WorldManager的发布后阶段及准入的latestObservation supplier仍在目标World owner/帧间边界调用服务；线程分类本身不能代替owner。输入是完整不可变Core frame，输出及同epoch/通知语义由[Basic publication](../../javarailways/basic/threading-and-publication.md#对外-observation-contract)拥有，不读取可变列车状态。

一次正常查询先在短目录锁中取得UUID对应的entry，再只在该entry内串行转换。已有相同frame引用直接复用；新frame先比较epoch，调用真实`RailwayObservation.fromCore`，最后在短目录锁中交换当前值。共享锁不能覆盖转换、等待目标entry或调用listener。所有会同时取得两锁的路径都先entry再目录；目录读取、订阅、卸载和关闭不等待entry，因此A转换暂停时B仍可查询、发布及完成领域阶段。

entry保存frame、observation及lastDeliveredEpoch。`publish(worldId, frame, completedPhysicsEpoch)`先验证ticket与frame epoch相同，随后在目标entry顺序内更新当前值，在短目录锁内一次更新通知记账并取订阅快照；释放两锁后调用listener。普通`current`不推进通知记账。listener异常仍隔离记录，同一发布已选中的listener使用该快照；注销不回收已开始的通知。调用方的World owner保持正常领域调用顺序，服务不新增独立通知队列。

`removeWorld(UUID)`撤销目录entry但保留逻辑UUID订阅，后续正式绑定可建立新entry。`close()`标记关闭并清除目录和订阅，不等待正在转换的entry。转换提交以及发布通知快照都必须确认目录仍指向开始时的同一entry；退休或被替换后返回null，不回填旧值、不通知旧结果，也不覆盖相同UUID的新entry。此identity只用于内部退休判断，不成为公开generation或永久tombstone。subscription.close与unregisterOwner仍可在转换暂停时完成。

转换抛错或epoch倒退时不提交新当前值；ticket不匹配在创建entry前拒绝。相同frame查询的缓存工作为O(1)，新frame承担既有列车/区间投影成本；发布选择listener为O(S)，S是服务当前订阅数，逐个listener回调保持既有成本。短目录锁只保护内存查找/交换及订阅快照，不承诺任意第三方listener执行时长。

例如A的新frame构造暂停时，B可以完成物理位移、observation发布和Dispatcher后的宿主返回；相同A的另一项查询等待该entry并复用结果。若A期间卸载且同UUID建立新记录，A迟到的转换返回null，新记录及原UUID订阅继续有效。正式`DefaultRailwayObservationServiceTest`用只读列车列表barrier暂停真实fromCore；World以LIVE-LIVE、LIVE-SIM、SIM-SIM三组实际模式创建，每组都断言B完成真实物理位移、observation发布与领域阶段，同时A仍暂停。其他回归保持同World一次转换/最多一次通知、listener两锁外重入、owner注销和close/remove交错。无持久化迁移；完整Signals JS执行归属由[信号运行规范](../../simple-signals/backend/simulation-runtime.md)拥有，不能仅凭observation测试宣称完成全部Feature验收。

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
创建/删除和摆位已按下述第4批迁移；外部脱轨与基础设施编辑已按第5批接入，联结/解编收口仍另行验证。

### 基础设施完整操作的帧间边界（CURRENT，第5批）

同步的Bound进路申请、progress、换向暂停/重基准和授权结束，在实际目标World owner内复用Core
`withStructuralMutation`，进入后重新检查插件及确切world/runtime；整个操作包含可能的选岔准备，不能只包底层setter。
退出前用`publishOwnerSelectionChanges()`按selection revision判断是否需要刷新；未变化轮询为O(1)，不逐次重建世界。
手动选岔使用异步Future入口，具体合同见[Turnout Wand](./turnout-wand.md)。

RailWand的几何/文件准备仍异步；八个普通prepared commit经统一回调，独立world undo是第九处。
完整player-owner回调在实际目标World owner和`processBetweenFrames`内执行root复核、Core/metadata/undo提交、失败清理及permit释放，
不将活Player/PDC/物品访问搬到global host队列。限速metadata更新和metadata undo也使用该边界。
world undo的Future在阶段退出和清理以后才完成，使Compound undo的同步后继不会嵌套`processBetweenFrames`。
门禁进入失败也须释放已准备undo/permit；不得只包Core install而让上层metadata或rollback跑在门禁外。
这不改变持久化格式，也不代表一般entity/platform副作用具备事务回滚。

### 创建与删除的结果消费（CURRENT，第4批）

`TrainCommand`将live身份/占用复核、车厢与列车构造、extension初始化、控制参数登记和最终Core发布作为一个帧间操作。
成功后才在原玩家entity owner显示模型及结果，不把“排队成功”报告成“创建成功”。
新建bogie/car/train在`CoreTrain.createOwned`的`TrainCreation`作用域登记，Core负责初始化失败回收，平台不再逐车销毁。`TrainCreation.publish()`在最终world publication成功后移交所有权；发布前且Core仍RUNNING时才清理未完成创建，Core已halt或已成功发布后不能因后续消息失败反向删除列车。Basic控制参数等sidecar回滚可通过作用域`onFailure`登记。
轨道`destroy`与`World.removeRail`同为完整删除：RailWand普通prepared commit与独立world undo汇总本次操作的所有异步清理，释放world/拓扑锁后等待完成，再回entity scheduler通知成功；清理失败进入原失败反馈，不能提前发成功。Dev清理也扁平化并消费train与rail的真实完成结果。
删除同样将会话/参数退出及`train.destroy()`放在一个操作；扁平化返回的CompletionStage，等待子模型、audience和平台实体清理后再回送成功反馈。模型及显示后端重复destroy复用同一结果；单项清理失败不阻断其他清理。entity调度拒绝、退休及执行失败均使结果失败，不能把提交成功当作删除完成。
隔离simulation工厂在其已有simulation owner中同步进入同一阶段；Dev测试命令也按真实Future消费创建、控制和删除结果。
不移动模型文件读取或扩展包准备到physics worker，不扩大为任意平台实体事务。
共享观测及失败边界由[Core帧间创建与摆位](../../javarailways/core/train-physics.md#帧间创建删除与维护摆位current第4批)拥有。

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
- world factory从创建`TickManager`起负责尚未发布的构造资源；后续Core load、extension restore、materialize或render metadata恢复失败时，逆序关闭已取得的共同领域runtime、render、runtime binding、physics与tick资源。资源只有在factory完整成功返回时才移交给`WorldManager`。
- unload先标记unloading并撤loaded-world lookup，再用coordinator `beginShutdown()`在其原stateLock内永久禁止新main/timer/simulation epoch并捕获exact ticket；在锁外成功await该ticket后才捕获/写最终snapshot，保存尝试结束后确认目标physics/generic executor真正终止，再释放共同runtime及service/render/tick资源。beginShutdown保持RUNNING以允许在途正常publication，不是close/abort；失败ticket必须报告失败，不回退旧epoch，也不拿失败作为worker全部退出的证明。已返回的factory world若在宿主注册中启动physics后失败，也必须先确认这两个pool终止，再卸载service/domain；未启动factory构造失败仍使用原资源栈。
- snapshot capture/write或任一teardown步骤失败时，仍先分别尝试目标physics/generic的实际终止确认，再撤销topology/frame订阅、三个宿主service、render/tick owner、异常标记与owner map。最先发生的异常保持为主异常，后续清理异常作为suppressed保留；不得因清理成功吞掉保存失败，也不得让旧world留在主tick集合阻止同UUID重新绑定。owner map删除使用卸载开始时捕获的exact world identity，不能误删随后绑定的新world。
- platform commit 执行前重新解析 affinity；目标跨 region 时 reschedule，而不是在旧 owner 上执行。
- 每车JS行为见[车厢行为契约](car-behavior.md)。最终snapshot保存尝试及目标worker终止确认之后，先令该world尚未完成的模型操作future失败，再分别关闭每节车厢的行为及延迟效果，随后关闭render/tick；不能等region队列执行才完成旧ticket。各车关闭独立尝试，某车失败不跳过其余车厢或后续owner。

上面的physics保存边界、CR-013卸载失败清理和CR-015 factory partial-init清理均已接入生产入口。没有因此承诺Bukkit跨region清理完成、失败输入可自动修复或所有平台资源已经成对释放。

### 构造失败资源归还（CURRENT）

1. **范围、触发与owner**：`RailWorldFactory.createForBukkit`拥有尚未返回的world构造；从第一个tick executor创建成功起，到完整world返回前，调用方没有对象可清理，因此factory不能把失败归责给`WorldManager`。
2. **正常流程**：创建顺序为tick→physics→显式 Core world→render listener→共同领域runtime→restore/materialize/render metadata；全部成功后清空构造资源栈并返回world，此刻资源所有权一次性移交给已发布world lifecycle。
3. **内部机制**：`WorldConstructionResources`只记录本factory已成功取得的close动作，使用LIFO执行，因此共同领域runtime先于render、runtime binding、physics和tick关闭。它不是公共API、通用事务或extension rollback框架。
4. **构造器边界**：`RenderChunkManager`可能在注册listener后、完成initial-player refresh前抛出，此时factory尚未取得对象；构造器自身调用`close`，最终注销listener。`close`保留首个异常、继续清理其他player并把注销异常记为suppressed。
5. **接口与数据**：正式factory签名、split snapshot、revision/epoch及world map均不变；未新增schema version、兼容输入、自动重试或空world fallback。
6. **失败矩阵**：tick创建失败时无资源；physics创建失败只关tick；Core load失败关physics/tick；render构造内部失败由其自身撤listener，再由factory关physics/tick；共同领域runtime取得后任一步失败先关闭它，再逐项关render/runtime binding/physics/tick。构造主异常始终保留，清理异常按发生顺序suppressed。
7. **线程与发布**：入口仍要求simulation main owner。失败world从未进入`WorldManager`可见map，也不得start/wake physics；清理不发布替代world，不吞掉坏snapshot诊断。
8. **性能与退化**：构造资源栈只含固定数量的本world资源，为O(1)；正常成功路径为固定次数注册及一次clear。失败不扫描其他world、不重试I/O，也不增加常驻线程或锁。
9. **必需测试**：验证render→physics→tick逆序、主异常与suppressed顺序、成功移交后不误关；验证render listener已注册后initial refresh失败会注销，且注销失败只作为suppressed。模块完整测试与`shadowJar`仍是最终门禁。
10. **开放项与错误示例**：Core world不再拥有DI binding；构造栈只保留当前显式取得的runtime资源。错误做法是在调用方包围factory、只关闭最后一个资源、用清理异常覆盖restore异常，或让render构造器注册副作用后抛出而无人持有；正确做法是各owner在对象可见性边界内归还自己已取得的资源。

### 卸载失败隔离（CURRENT）

1. **范围、触发与owner**：`WorldManager.unregisterRailWorld(BukkitWorld)`负责已发布world的宿主卸载。适用于正常world unload与插件停用逐world清理；未返回world的factory partial-init和Folia region task完成不在此机制内。Core-owned world context token在宿主consumer关闭后由此入口释放。
2. **正常流程**：simulation/main owner先把world加入unloading集合并撤销UUID lookup，捕获当时`railWorlds[bukkitWorld]`的exact对象；随后停止新physics epoch、等待exact ticket、捕获并写split snapshot，随后无论保存成败都先确认本World物理及generic worker终止，再逐项释放共同领域runtime、订阅/service及render/generic tick owner，清异常标记，最后移除旧owner map。
3. **内部机制**：保存和每个teardown动作分别交给同一个顺序执行器。执行器记录首个`Throwable`，继续运行后续动作，并把之后不同的异常按顺序加入首个异常的suppressed列表；全部动作结束后才重新抛出。map删除使用`remove(bukkitWorld, capturedWorld)`，所以清理期间即使同一key已换成新world，也不会被旧清理删除。
4. **接口与数据**：正式入口仍只有`fun unregisterRailWorld(bukkitWorld: BukkitWorld)`；用于失败矩阵测试的internal overload只替换持久化、marker、render和tick动作，不改变生产owner或形成add-on API。`physicsEpoch`仍来自成功完成的exact `PhysicsTicket`，不是schema version。
5. **生命周期与线程规则**：入口必须通过`ThreadGuard.ensureMainThread`；UUID lookup在任何blocking await/I/O前撤出。保存失败也必须关闭资源并撤owner map；失败不能回退旧成功epoch或重新开放wake。新binding只能由后续load重新发布，旧清理不能按UUID宽泛删除它。
6. **失败矩阵**：physics/capture/write失败保留为主异常，后续cleanup仍执行；订阅/service/render/tick/marker失败时继续下一项；多个失败时首个为主、其余为suppressed；无已发布owner时只做lookup/marker撤出，不猜测或创建world。任何失败都不得被记录成保存成功。
7. **性能与退化**：固定service/owner关闭为O(1)，未完成操作、车厢行为及已接受延迟效果关闭为O(P+N+E)，P为world追踪的未完成操作数、N为车厢数、E为仍登记的效果数；snapshot capture/write另随world数据量增长。失败时不重试、不复制snapshot，也不新增后台队列；退化行为是逐项尽力释放资源后向调用方报告原失败。
8. **具体情形**：正常卸载写完最终snapshot后关闭全部owner；磁盘写失败仍从主tick集合移除旧world；render close失败仍关闭tick并清marker；清理回调重入并发布replacement时，exact identity删除保留replacement。
9. **必需测试**：持久化失败必须断言主异常身份、suppressed顺序、后续cleanup、physics CLOSED、map/lookup清除和同UUID重绑；teardown首先失败必须断言后续动作继续，并在final map remove前替换binding后验证新owner仍存在。模块测试与`shadowJar`门禁同时执行。
10. **迁移、开放项与错误示例**：无持久化格式迁移。CR-015/017均已接入各自owner。错误做法是只用一个`finally`包最外层close、在其外删除map，或用`railWorlds.remove(key)`删除可能已经替换的binding；正确做法是逐项失败隔离、释放world context并按captured identity删除。

线程相关权威证据：`WorldManager.kt`、`BukkitThreadGuardBridge.kt`、`PlatformDispatcher.kt`、`docs-site/docs/architecture/threading-model.md`。

### 第二批审查：保存与取消事件（CURRENT）

WorldManager 的 snapshot revision 属于 exact CoreWorld 实例，不再取 serverTickIndex。loadSplitSnapshots 返回成功配对快照及所有可解码 primary/backup 候选的最高 revision；包括未配对的中断写入。注册时 restoreSnapshotRevision 初始化，captureSnapshots 在原 owner 捕获边界用 nextSnapshotRevision 原子递增，之后仍由原串行 writer 写盘。每次捕获 O(1)，加载仍只处理既有常数个候选；不添加扫描历史或兼容 reader。卸载与安装失败删除该实例的序号，未初始化和 Long 溢出明确失败，不能回落到零。它是保存排序号，不是 schema version，也不替代 physics epoch。

例如旧备份 10000、新会话首个保存必须大于 10000；中断写入留下孤立 10001 时下一捕获为 10002。WorldPersistenceRevisionTest 验证真实文件写入/重载、同 tick 捕获唯一性和溢出拒绝。禁止使用重启归零 tick 或只取配对文件的旧 revision 作新保存上限。无格式迁移。

WorldUnloadEvent 的破坏性清理由 MONITOR 且 ignoreCancelled=true 的监听器执行；WorldManager、HTTP 地图缓存、RailObjectWand 与 SimpleSignals runtime 遵循这一边界，EditorText 已采用相同规则。取消事件保留仍活动的 runtime；不在 NORMAL 阶段先关再恢复。SeatModel 的离座回调、位置调整及后续清理同样只对确认接受的下车事件执行。WorldManagerUnloadTest、SeatDismountTest 通过真实 RegisteredListener 验证取消过滤；真实 Paper/Folia 生命周期仍需游戏内验收。


### 第三批：时刻表编译与rail visual退休（CURRENT）

时刻表JS与宿主共享JVM，每次单独Graal Context；生产单次预算60秒，同时最多两个执行。sandbox是执行生命周期owner，所有HTTP同步和命令异步入口共用该限制；超时/close调用Context.close(true)实际取消guest，不能只完成等待future。关闭先拒绝新入口，逐项取消已登记执行；finally释放许可，取消的deadline从scheduler队列移除。512KiB源码和8MiB生成结果限制保留，stdout/stderr合计1MiB且只计数丢弃，不积累打印内存；超限即使guest捕获写异常也不能成功。没有独立进程/heap隔离，不宣称限制guest总内存。回归用短内部期限运行真实Graal顶层/main循环，验证取消、输出预算、名额恢复与关闭异步命令。

RailVisualBootstrap索引键为(exact CoreWorld.runtimeId, rail UUID)，不能只按rail UUID定位；复制世界或同持久化world UUID重载仍各有runtime。unregisterRail要求world，RailWorldFactory返回前的构造失败由其资源栈调用retireWorld；WorldManager后续安装失败和卸载均调用retireWorld，按准确条目退休显示owner。清理返回聚合CompletionStage，已有未完成删除仍参与结果；不在world owner等待entity区域操作，失败需观察/报告。移除全局索引仅证明引用退休，不等于后端实体已经删除，Paper/Folia删除需另验。物理轨道及磁盘UUID不改变。


## 热启停资源契约

### 1. 触发范围

BukkitPlugin、WorldManager、dispatcher、模型/工具和 add-on 在同实例启停或类加载器替换时必须释放单次 enable 资源。现代 Cloud 的新实例命令恢复由 Paper 描述文件与 COMMANDS 事件契约负责，见[交互规范](interaction-render-persistence.md#paper-描述文件与命令恢复current)；同实例启停与任意管理器兼容性不由此保证。

### 2. 签名

- `PhysicsCoordinator.stopAndAwait(timeoutMillis = 10_000)` 永久停止新 epoch，成功后允许读最终 ticket。
- `WorldManager.unregisterRailWorld()` 先在仍可进入的物理帧边界恢复控制会话，再 stopAndAwait，成功后捕获最终快照；不得全局先停止 physics 再进入 processBetweenFrames。
- `PlatformCleanup.runAndAwait(timeoutMillis = 10_000, action)` 追踪跨 owner 及其嵌套任务；等待时不得持 simulation lock。当前 destroy 返回 CompletionStage，通过 awaitCompletion 将完成/失败接入同一 batch，不把提交成功当作删除完成；Folia整服停止跳过临时owner动作时 completion 立即完成且不访问Bukkit。
- `PaperPlatformDispatcher.stopAndAwait()` 封住旧 enable 的正常回调，新 enable 使用新 dispatcher。
- `AbstractWand.close()` 保存在线玩家绑定状态、释放预览/会话并停止周期任务；不可读 PDC 不被内存默认值覆盖。

### 3. 契约

服务在任何 world/physics lock 之前注销，consumer 先失效 lease。最终 snapshot 只从成功静止的 physics 捕获；之后每个 world 的 render/tick owner 独立释放，即使保存失败也不跳过其他 cleanup。Native Display 删除通过 entity owner 调度。Seat 静态 registry 在模型销毁和 cleanup barrier 后关闭。

### 4. 校验与错误

- physics failure/timeout：禁止以旧成功 ticket 保存新 snapshot，保留现有文件并报告。
- services/HTTP/dispatcher producer 停止失败：跳过最终 snapshot，保留已有恢复材料。
- native cleanup 失败/超时：汇总错误；超时 batch 的晚到任务禁止再执行。不完整关闭后同实例再次 enable 必须失败并要求重启服务器，防止仍运行的旧回调进入新 runtime。
- 旧服务引用/回调：拒绝新操作，不能解析下一轮 WorldManager。
- 关闭失败诊断：逐组件异常保留，finishShutdown 的 SEVERE 汇总列出失败组件，并明确 PlugManX 可能继续卸载/加载、停止后续热重载、检查 PID/详细错误和恢复材料后重启。成功关闭不输出此警告；用户暂缓跨实例运行标记，日志不能被描述成已经阻止新实例启动。
- Folia server stopping：仅 `platformCapabilities.folia && server.isStopping` 时使用 `PlatformCleanup.runAndAwait(skipOwnerTasks = true)`；停止提交 region/entity 动作，保留本地 teardown 与最终快照 writer，见下节。

### 5. 正常、基本和失败场景

正常：global 禁用事件中完成所有 owner cleanup，再关闭网络。基本：无世界或部分启动失败也可以重复关闭。失败：从 region A 阻塞等待 B，而 B 再向 A 提交任务可能超时；不得把这种情况宣称为任意调用线程热禁用安全。

### 6. 必需测试

`PlatformCleanupTest` 覆盖嵌套任务、退役、异常、超时后迟到任务；`PlatformDispatchLifecycleTest` 覆盖已执行任务的停止屏障与旧代回调；`SeatListenerLifecycleTest` 覆盖 callback 释放。真实 Paper/Folia 在线/乘坐玩家和 PlugMan/PlugManX 的命令矩阵是独立验收，单元测试不能替代。

### 7. 错误与正确做法

错误：在 onDisable 才向 Folia 提交删除任务，并将排队返回视为实体已删除。正确：利用 enabled 仍为 true 的禁用前窗口，追踪任务实际完成；失败明确报告，不能绕过 owner guard 或伪造 enabled。


## 独立 PacketEvents 资源归属

### 1. 触发范围

JavaRailways 依赖独立安装的 `packetevents`，EntityLib 仍重定位在主插件中。此契约替代内嵌 PacketEvents 的启动和终止方案。

### 2. 签名

`PacketEntityRuntime.enable()` 取得 `PacketEvents.getAPI()` 并绑定本插件 EntityLib；`close()` 仅释放本插件资源。`PacketEntityMotion.start()/close()` 对注册时的 EventManager 成对注册、注销同一个 listener。

### 3. 契约

`paper-plugin.yml` 声明必需的 `dependencies.server.packetevents`，加载顺序为 BEFORE 并开放 classpath；编译基线为 2.12.1。启用时检查外部 API 已初始化、未终止，且版本不低于 2.12.1。禁止对共享 API 调用 `setAPI/load/init/terminate/uninject/clearBuildCache/unregisterAllListeners`。关闭只清理本插件的虚拟实体、motion listener 和状态；不得改变其他 consumer 的 listener、在线玩家协议状态或网络 handler。EntityLib 不启用 ViewerEngine、tickTickables、BStats 或更新检查。

### 4. 校验与错误

缺少依赖由插件加载器拒绝加载；API 不可用或未初始化时启用失败，不尝试自行启动 PacketEvents。部分启动失败和重复 close 仍遵循相同资源边界。独立 PacketEvents 自身的热重载不属于 JavaRailways 单独热重载验收范围。

### 5. 正常、基本和失败场景

正常：只重载 JavaRailways，复用持续运行的共享 PacketEvents。基本：无世界、没有 motion 或部分 EntityLib 初始化也可关闭。失败：依赖未就绪时不注册 listener；清理失败需报告，不能通过全局注销修复。

### 6. 必需测试

覆盖外部 API 状态校验、同实例关闭再启用、部分初始化失败；用真实 EventManager 验证自己的 listener 消失、其他 listener 保留。检查 shadow JAR 不含 PacketEvents 类且 paper-plugin.yml 含必需 server 依赖。实际 Paper/Folia 在线玩家热重载仍需单独验收。

### 7. 错误与正确做法

错误：JavaRailways 禁用时调用共享 API 的 `terminate()` 或 `unregisterAllListeners()`。正确：注销本插件注册的准确 listener，销毁自己的 EntityLib 实体，由独立 PacketEvents 插件管理网络库生命周期。


## Folia 单线程诊断与停服分支

### 1. 触发范围

Folia 完整停服不再依赖已停止的区域 tick 删除临时原生实体。PlugManX 3.2.1 的 unload/reload 使用下述 GentleUnload 非阻塞准备，支持自动配置选出单 tick worker；绕过 GentleUnload 的普通热禁用仍保留同步等待限制。

### 2. 签名

- `FoliaRuntimeDiagnostics.warnIfSingleWorker(logger)` 在 enable 后首个 global callback 读取 `TickRegions.getScheduler().getTotalThreadCount()`。
- `PlatformCleanup.runAndAwait(timeoutMillis = 10_000, skipOwnerTasks = false, action)` 的 skip 只用于真实 Folia 停服。
- `PlatformCleanup.skipsOwnerTasks()` 是当前 batch 的线程局部属性；dispatcher 的 location/entity immediate/delayed 操作在访问 Bukkit 或创建计数 task 前直接返回。

### 3. 契约

计数为 1 时警告；多线程不发单线程警告；未知/反射失败明确报告无法检查。不得把初次 onEnable 时未启动的 0 个线程或 CPU 数推断为 1。通过已存在的 dispatcher lifecycle gate 阻止禁用后的迟到诊断。

Folia + server.isStopping 才跳过 owner 动作；Paper 关闭、Folia 热禁用保留 barrier。跳过的是非持久化实体删除、预览和停服冗余的玩家工具最后扫描；服务、simulation/physics、JR 快照、writer、listener、HTTP、执行器和本地资源仍按顺序关闭。

所有活跃 World.spawn consumer 在实体加入世界前设置 isPersistent=false。客户端数据包实体不插入原生世界。玩家 persistent 不改动；非持久化载具仅阻止 RootVehicle 写入，不抑制玩家自身数据保存。工具物品 PDC 必须在各状态变更的玩家 owner 回调里立即写回；热禁用仍保留 close flush。不能把用户的 TBB 方块修改或 JR 快照归类为临时资源。

### 4. 校验与错误

线程数未知不阻止启用，也不能宣称已通过单线程检查。绕过 GentleUnload 的单线程热禁用仍可能 10 秒超时；注册 GentleUnload 后警告应指向两次请求流程，不能要求用户必须增加线程。停服跳过 owner 动作必须不调用 owns/entity scheduler/region scheduler，也不能绕过线程检查直接执行动作。保存/producer 失败沿用原失败保护。

### 5. 正常、基本和失败场景

正常：多线程 Folia 热禁用按原 barrier 完成；停服只做本地清理和 JR 保存。基本：一线程运行时记录风险；无实体、无在线玩家也可重复关闭。失败：无法反射读取 worker 数时发独立诊断；普通热禁用等待超时仍报告关闭不完整。

### 6. 必需测试

诊断覆盖 1、多线程、0/未知/读取异常；停服 batch 直接通过生产 dispatcher 验证动作、retired callback、Bukkit ownership 和 scheduler 全部不执行，且本地 action 仍完成、batch 状态在返回后恢复。默认 batch 的正常、嵌套、退役、失败和迟到回调测试保持。运行 Bukkit 测试及 shadowJar；实服实体/playerdata 保存验收单独记录。

### 7. 错误与正确做法

错误：根据 folia=true 对所有禁用跳过清理，或在停服线程直接 remove 任意实体。正确：只有确认真实停服才丢弃 owner 动作，临时实体依靠创建时的 nonpersistent 契约不入档，业务数据仍保存。


## PlugManX 操作与 sidecar 退出确认

### 1. 触发范围

当前支持目标限定 PlugManX 3.2.1 Paper/Folia。reload 使用其硬依赖级联；本轮不解决任意管理器的同实例 Cloud 注册，也不增加跨类加载器关闭失败标记。

### 2. 签名

PlugManX 配置 `reloadDependentsMode: REQUIRED_ONLY`（或 ALL），操作 `/plugman reload JavaRailways`。TimetableWebUiSidecar.start/stop 在实例 monitor 上串行，process 为 volatile，退出观察 callback 不清除 owner。

### 3. 契约

TBB/Dev 均声明 depend JavaRailways，不能被 PlugManX 忽略。默认 reload 先卸载附属，再替换主插件，最后加载附属；独立 disable/enable 不级联，不能在旧 API Class 的 consumer 留存时宣称可以恢复服务。本仓库只更新操作指引，不自动改外部配置或重载其他插件。

sidecar stop 依次正常终止/等待、必要时强制终止/等待，并核对实际 isAlive；只有确认退出才清空 process。BukkitPlugin 的 timetableWebUiSidecar owner 也仅在 stop 成功后清空。失败仍走现有 shutdownFailures 汇总，不能据此声称失败保护已跨类加载器生效。

### 4. 校验与错误

强制后仍存活、destroy/wait 异常：保留 Process，错误包含 PID 和 cause。InterruptedException 单独处理并恢复线程中断标志，保留引用。已退出直接释放；重复成功 stop 幂等。onExit 只报告意外退出，不替 stop 判定成功。

### 5. 正常、基本和失败场景

正常：正常退出或强制退出后才能启动下一轮。基本：已退出、重复 stop 不重复终止。失败：第二次等待超时仍活着必须报告失败，保留 handle 以供后续检查/重试；不能把请求 destroyForcibly 当作进程已退出。

### 6. 必需测试

TimetableWebUiSidecarTest 对实际 stop 和 process owner 字段验证正常退出、强制退出、二次超时与后续重试、已退出、重复停止、中断标志与异常 cause、终止方法异常的 PID 上下文。实际子进程端口与服务器重载验收单独记录。

### 7. 错误与正确做法

错误：先 process=null，再 destroyForcibly 并忽略 waitFor 结果。正确：持有 Process 到确认退出；任何失败保留 owner，并让 composition root 收到失败。


## Folia PlugManX GentleUnload 两次请求

### 1. 触发范围

仅 Folia + PlugManX 3.2.1 的 unload/reload 接入；与线程配置值、实际 worker 数无关，覆盖 threads=-1 选出一个 worker。普通 Paper/Bukkit、不安装 PlugManX、真实停服的原有边界保留。独立 disable 绕过上游 GentleUnload，不在此支持范围。

### 2. 签名

- `GentleUnloadPreparation.request(): Boolean`：IDLE/PREPARING/FAILED 返回 false，仅 READY 返回 true。
- `PlatformCleanup.start(timeoutMillis, skipOwnerTasks, action): Operation`：在当前正确 owner 上提交清理后立即返回；completion 代表全部真实任务（含嵌套）完成或失败，cancel 返回已进入回调的 drain future。
- `PlugManXGentleUnload.register(plugin, manager, request)`：运行时可选 API adapter；不打包 PlugManX 类，不新增硬依赖。

### 3. 契约

第一次 request 仅排入后续 global callback 并暂缓卸载，不能 inline 注销服务：PlugManX reload 收到 false 后会同步恢复刚卸载的依赖插件。global 准备仍按 producer 停止、physics 静止、控制释放及 world 保存顺序执行；跨 owner 清理通过非阻塞屏障汇合，不在 tick worker 等待区域任务。成功后回到原生 global scheduler 完成 seat registry 和 snapshot writer 收尾，全部成功才 READY。正常 runtime dispatcher 已停止，因此完成回调不能经过其业务 gate。

准备期间业务入口关闭；不自动卸载、重载、恢复业务或重放原命令。操作员等 ready 消息后再执行原命令。PluginDisableEvent/onDisable 复用已有准备结果，不能再次跑 owner 屏障。

上游 API 返回 boolean，无异步 continuation、operation kind 或公开注销 API；getGentleUnloads 返回副本。版本限定 adapter 注销时仅移除内部 gentleUnloads 中 key 为本插件、value 仍为本 callback 的精确条目，保留其他插件及替换回调。

### 4. 校验与失败

准备异常、任务超时或 snapshot writer 失败均进入 FAILED，记录详细异常并拒绝之后请求。超时立即 fence 尚未进入的 owner 回调，不能把延迟调度成功视为清理完成。仍在运行的 callback 与 completion timeout 分开追踪；未确认其退出时不得释放 packet 等共享 owner。外部直接禁用或停服打断准备时报告不完整关闭、取消排队回调，只等待已经运行的回调；禁止等待本线程队列中的未执行任务。

### 5. 正常、基本和失败场景

正常：首次 request 返回后，同一个线程推进区域和嵌套任务，最终保存成功后第二次卸载获准。基本：无 owner 任务也要求第一次暂缓，重复准备请求不重复关闭。失败：清理失败不 READY，迟到任务不再修改旧 runtime；直接 disable 不因此被描述为安全兼容。

### 6. 必需测试

覆盖单线程队列、首次返回前未启动准备、嵌套任务、重复请求、失败/超时、回调 drain、实际 composition 的保存/关闭接线及注册精确释放。运行 Bukkit test/shadowJar、必要下游检查和 git diff --check；检查 JAR 不含 PlugManX classes，softdepend 仅为可选加载顺序。实际 Folia 单 worker 在线玩家 unload/reload 仍需独立实服验收，单元测试不能替代。

### 7. 错误与正确做法

错误：在 GentleUnload 回调中同步 get 等 owner 清理；或将所有 Bukkit 清理直接搬到 async。正确：首次同步 veto 立即返回，保留每段 owner affinity，由异步完成通知推进到 READY，再由用户发起第二次请求。

### 第二批审查：保存与取消事件（CURRENT）

WorldManager 的 snapshot revision 属于 exact CoreWorld 实例，不再取 serverTickIndex。loadSplitSnapshots 返回成功配对快照及所有可解码 primary/backup 候选的最高 revision；包括未配对的中断写入。注册时 restoreSnapshotRevision 初始化，captureSnapshots 在原 owner 捕获边界用 nextSnapshotRevision 原子递增，之后仍由原串行 writer 写盘。每次捕获 O(1)，加载仍只处理既有常数个候选；不添加扫描历史或兼容 reader。卸载与安装失败删除该实例的序号，未初始化和 Long 溢出明确失败，不能回落到零。它是保存排序号，不是 schema version，也不替代 physics epoch。

例如旧备份 10000、新会话首个保存必须大于 10000；中断写入留下孤立 10001 时下一捕获为 10002。WorldPersistenceRevisionTest 验证真实文件写入/重载、同 tick 捕获唯一性和溢出拒绝。禁止使用重启归零 tick 或只取配对文件的旧 revision 作新保存上限。无格式迁移。

WorldUnloadEvent 的破坏性清理由 MONITOR 且 ignoreCancelled=true 的监听器执行；WorldManager、HTTP 地图缓存、RailObjectWand 与 SimpleSignals runtime 遵循这一边界，EditorText 已采用相同规则。取消事件保留仍活动的 runtime；不在 NORMAL 阶段先关再恢复。SeatModel 的离座回调、位置调整及后续清理同样只对确认接受的下车事件执行。WorldManagerUnloadTest、SeatDismountTest 通过真实 RegisteredListener 验证取消过滤；真实 Paper/Folia 生命周期仍需游戏内验收。


### 第三批：时刻表编译与rail visual退休（CURRENT）

时刻表JS与宿主共享JVM，每次单独Graal Context；生产单次预算60秒，同时最多两个执行。sandbox是执行生命周期owner，所有HTTP同步和命令异步入口共用该限制；超时/close调用Context.close(true)实际取消guest，不能只完成等待future。关闭先拒绝新入口，逐项取消已登记执行；finally释放许可，取消的deadline从scheduler队列移除。512KiB源码和8MiB生成结果限制保留，stdout/stderr合计1MiB且只计数丢弃，不积累打印内存；超限即使guest捕获写异常也不能成功。没有独立进程/heap隔离，不宣称限制guest总内存。回归用短内部期限运行真实Graal顶层/main循环，验证取消、输出预算、名额恢复与关闭异步命令。

RailVisualBootstrap索引键为(exact CoreWorld.runtimeId, rail UUID)，不能只按rail UUID定位；复制世界或同持久化world UUID重载仍各有runtime。unregisterRail要求world，RailWorldFactory返回前的构造失败由其资源栈调用retireWorld；WorldManager后续安装失败和卸载均调用retireWorld，按准确条目退休显示owner。清理返回聚合CompletionStage，已有未完成删除仍参与结果；不在world owner等待entity区域操作，失败需观察/报告。移除全局索引仅证明引用退休，不等于后端实体已经删除，Paper/Folia删除需另验。物理轨道及磁盘UUID不改变。


### 集成目标分支的呈现服务清理

`DefaultModelPresentationService.beginHostCleanup()` 与 `DefaultEditorTextService.beginHostCleanup()` 在 registry lock 内禁止新 handle、捕获当前 outstanding 并开始 close，返回覆盖全部真实 handle cleanup 的 CompletionStage。`PlatformCleanup.awaitCompletion(stage)` 将其加入当前非阻塞屏障；不能只观察 close() 的同步返回。

呈现 service 和 native backend 使用同一个独立 cleanup dispatcher：业务 dispatcher 停止后，呈现 reconcile → entity/region destroy → 完成回调仍可推进；直到屏障完成才停止呈现 dispatcher。后者 drain 失败时设置 unsafeToFinishShutdown 并保留共享 runtime，不得仅记录错误后继续释放 packet owner。

该接线适配目标分支显式 world runtime，不恢复已删除的 Koin/WorldParentContext；最终保存沿目标 WorldManager 的 physics stop、manual control release/retire、snapshot revision 与串行 writer 契约。
