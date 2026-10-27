# SimpleATO timetable simulation 契约

## 适用场景与用途

触发：修改detailed travel preview、isolated timetable clone/step/results、simulation provider/operation或resource cleanup。

用途：preview确定性复用TrainDynamics；full simulation只clone static infrastructure并拥有独立workers，绝不复制live trains/tickets/locks/provider side effects。

## Detailed simulation

`DetailedTrainTravelSimulator.STEP_SECONDS=0.05`。`DetailedTrainSimulationProfile`显式携带正数
`gravityMetersPerSecondSquared`；`simulate(legs,profile,computationDeadlineNanos): DetailedTravelSimulationResult`要求legs/cars非空、length positive、final leg stop；支持optional max speed、interrupt、nano deadline、每leg最大3600 s。Result leg含from/to、length m、travel seconds、peak m/s、stopping error m。

```kotlin
val result = TrainDynamics.step(
    state, profile.train, cars, handle,
    profile.gravityMetersPerSecondSquared, STEP_SECONDS
)
state = result.state
distance += result.distance
elapsed += STEP_SECONDS
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/DetailedTrainTravelSimulator.kt`。同input应得相同result；不访问world/platform，纵向积分仍复用 Basic `TrainDynamics`。

## Isolated world owner、clone与lifecycle

`IsolatedTimetableSimulationRequest(timetable,delayThresholdTicks=1200,maximumSimulationTicks=periodTicks)`要求threshold>0、max在`1..periodTicks`。Result sealed：`Completed/DelayExceeded/Failed/PeriodEnded`，均含simulatedTicks/runs。

`IsolatedTimetableSimulationWorld.create(coreInfrastructure,basicInfrastructure,simpleAtoInfrastructure,workerThreads,basicDataFactory,timetableExtensions,dispatcherFactory,seed)`：

- Core/Basic/SimpleATO `infrastructureRevision`必须相等；
- restore Core infrastructure，attach fresh Basic data并restore Basic infrastructure；随后attach fresh SimpleATO data并restore SimpleATO infrastructure；
- 不restore runtime trains/reservations/Dispatcher；`seed`从profile assignments创建train；
- own TickManager/PhysicsCoordinator pools，`automaticFallback=false`；
- logical Tick固定50 ms：preTick→`stepSimulation(50).await`→coordinator帧间阶段（drain操作请求→publish observation→same Dispatcher→postTick）→drain platform commits；帧间阶段和production复用[同一入口](../core/runtime-publication.md#非物理操作统一在帧间执行target2026-10-14用户确认)，`automaticFallback=false`不替代阶段检查；
- single-use；`close()`先确认physics/generic worker终止，再释放自有领域资源；create failure也cleanup。

## Simulation矩阵

| 条件 | result/behavior |
| --- | --- |
| same static input/profile | deterministic result class/ticks/run values |
| live train仅存在source runtime | clone中不存在 |
| revision mismatch | create fail，无leaked pools |
| simulate第二次 | `check`失败 |
| delay>=threshold | first significant `DelayExceeded` |
| run FAILED/exception | typed `Failed(reason,failedRun?)` |
| period/max ticks结束 | `PeriodEnded` |
| interrupt | preserve interrupt，`Failed("simulation cancelled")` |
| 已注册领域extension缺准备器 | capture/prepare明确失败，列明typeId/version；不创建worker |
| 门/模型operation | 复用逐车JS和ticket完成语义，以无显示出口执行效果启动语义；观察器仅按需收集，正式模拟不保存历史；未支持操作与LIVE同样Rejected |

Registry在源World稳定边界捕获准备值：`TimetableConditionSimulationFactory.prepareSimulation(): TimetableConditionSimulationPreparation`与action对应接口返回脱离live状态的准备对象；其`create(targetWorld)`每次创建目标provider。`TimetableExtensionRegistry.prepareSimulation(): TimetableSimulationExtensions`保留builtin语义，遇到已注册而无准备器的领域provider明确失败，不再silent filter。一次capture创建两个目标时分别创建registry/provider；不得把已经创建的registry或live provider闭包作为共享输入。未发布运行API直接替换旧`createSimulationRegistry`，无持久化字段或版本变化。

Bukkit capture直接保留`saveInfrastructure(revision)`的实际active turnout选择，要求每service profile且same selector same profile；并捕获真实速度安全配置、LIVE Dispatcher的`atoAdmissionRetryTicks`标量、准备好的扩展及`PreparedSimulationFeature`。宿主从实际domain注册发现required owner IDs，缺准备器须在capture拒绝，不能以空贡献集合宣称完整支持。prepared feature只通过公开`SimulationDomainTarget`安装，内部adapter连接目标world/domain/routeAdmission：使用真实signal/inspection/planning与本地Basic provider/contributor/preparation端点，新建可变实例，返回所属注册的close结果。这是当前部件的安装接线，不是独立Environment或任意对象clone/DI框架；完整SimpleSignals安装归[信号模块](../../simple-signals/backend/simulation-runtime.md)。

SIM领域stamp使用目标CoreWorld已有的`runtimeId`（UUID字符串），不是固定`"isolated"`。每target准备的信号定义须使用该target身份并针对其root编译，才能满足真实source对definition World UUID与observation stamp相等的合同；不能沿用LIVE定义的worldId后删除该检查。LIVE继续使用既有Bukkit World UUID。没有新增身份层、存档字段或版本。

### 公开Feature安装边界

平台无关契约收敛到`work.art1st.javarailways.simpleato.api.simulation`：`PreparedSimulationFeature.id`是宿主既有小写插件owner名，`install(SimulationDomainTarget): AutoCloseable`为每个target创建新的运行资源。原实现package的同名预留接口已移除，不保留alias。公开target只提供UUID/exact TopologyState、`operatingDirections(railIds)`、`railSpeedLimits(railIds)`、`installSignalSources`、`installInspectionSources`、`installRoutePlanningSources`及resource provider/contributor注册；不暴露CoreWorld、OperationalWorldRuntime或本地admission实现。

`DefaultSimulationDomainTarget`只在目标owner中、physics启动前安装；close token由该target真终止后的资源栈调用。内部为各类source保存已安装的不可变注册列表，新增时先把现有列表加候选交给既有runtime完整replace校验，成功后才登记；失败保持原注册及source状态。每次替换成本随同类source总数增长，只发生在安装/退休，不增加tick索引或调度。signal/planning撤销token时组合剩余注册；inspection退休逐个调用既有removeSource，不能在仍有active binding的正常模拟结束时使用会拒绝计划性卸载的replaceSources。运行期planned卸载安全门不放宽。token只撤自己并可重复关闭，其他feature的source保持。

资源身份、拓扑编辑与依赖DTO归`core.api.physics.infrastructure`；Basic继续拥有准入/准备公开契约及`basic.infrastructure.admission`实现。`SimulationRouteAdmissionHost`借用所属`CoreWorld.infrastructureCoordination`，仅保留自己的`registerResourceValidator` token；关闭时先停止本地admission，再通过`releasePlatformOwner`释放自己的已完成claim，逐个撤销自己requester的静态依赖，最后关闭validator token；不关闭World唯一arbiter、不清理其他owner、不新增平行registry。pending reservation沿Core既有fail-closed规则保留，不以目标关闭伪造准备成功。真实grant因此同时阻止Core结构编辑；停止后退休释放claim，World arbiter仍可处理合法编辑。没有DI world-parent或`releaseRuntimeBinding`关闭阶段。

target方向、静态限速读取直接复用目标Basic store及exact root，速度单位m/s，present null表示没有CURVE/SUBGRADE限速。provider沿用原SimulationResourceProvider三方法exists/validate/prepare及Basic公开值；contributor走原本地准入验证、claim、设备准备和尾释放，admission update继续交同一SignalEpochRuntime，不另建信号模拟算法。

Bukkit对插件的认证/登记由`bukkit.api.simulation.SimulationFeatureService.register(Plugin, SimulationFeaturePreparer): AutoCloseable`拥有。preparer在实际源World的owner/帧间边界接收source UUID，捕获detached feature；注册目录短锁外调用各preparer，不在其中等待JS或编译。登记、返回feature.id与既有required owner集合使用同一小写owner名；disabled、重复登记、关闭服务、错ID或preparer异常明确失败，准备失败不启动target worker。注册token/owner禁用只移除该登记；已经捕获的detached feature归目标模拟，不能指回LIVE manager或provider。服务的注册/插件关闭细节归[Bukkit lifecycle](../../bukkit-plugin/backend/lifecycle-and-threading.md)。

例如同一份设施capture创建两个target时，共享只读准备值，但两target各自安装source/provider；A结束仍有active inspection时走退休入口关闭，B及同target另一feature的注册不被清掉。必须覆盖准备器锁外barrier、登记/owner禁用/服务关闭、实际capture到两个target的真实source baseline及关闭隔离、active inspection退休，并通过Java public API consumer。实现/编译与最终完整门禁分别记证据；新测试的JVM返回值必须为void/Unit，并在XML核对发现执行，不能只看BUILD SUCCESSFUL。

此接口只收敛已批准的领域安装缝隙，不新增Environment、通用克隆/DI或持久化格式/版本。完整SimpleSignals业务及运行归属由[信号模拟规范](../../simple-signals/backend/simulation-runtime.md)拥有，逐车行为安装及效果出口由[Bukkit模拟行为](../../bukkit-plugin/backend/simulation-car-behavior.md)拥有。

### 实际与模拟共用领域阶段

`OperationalWorldRuntime`具体装配同一Core/Basic/SimpleATO链，持有新建的restriction store、inspection、SignalEpochRuntime、Basic数据、安全interlock、ATO coordinator及Dispatcher。实际平台仍拥有profile、Bukkit效果、诊断和service适配；模拟使用目标CoreWorld唯一的资源registry、本地Basic admission及其admission→SignalEpochRuntime更新，不共享LIVE callback或JS实例。

静态设施restore后两模式都调用真实`OperationalSpeedLimitSignalMaterializer`生成AB/BA限速及release transition，并在physics启动前安装`SpeedSafetyMonitor`。物理成功后的`afterPublication`复用原次序：发布完整observation并消费admission释放→发布pending物理脱轨通知→消费同epoch速度脱轨请求→平台frame回调→Dispatcher→generic postTick。列车当前frame epoch不等于request的observed epoch时丢弃该速度请求；失败ticket不得调用这条消费阶段。具体Core成功事实由[发布规范](../core/runtime-publication.md)拥有。

create失败逆序释放已经安装的feature注册、operation owner、自有领域runtime/admission及pools；某项close失败继续尝试其他资源，保留原异常并附suppressed。普通close也只释放目标自己的资源：在目标owner内先取消physics/generic pools并确认真实callback退出，再逆序关闭feature注册、operation owner、local admission和共同领域runtime；中断等待后仍完成安全终止并恢复interrupt。失败ticket不是worker退出证明，不能在HTTP取消的`use`清理时先卸载仍被callback使用的资源；不退出的第三方callback会保留依赖并低频日志，不强杀或超时释放。不尝试在运行中移除Core禁止动态卸载的静态epoch processor；其停止随coordinator。门/模型操作通过下述真实逐车operation consumer接线；完整联合Feature验收由父任务拥有，不以共同域接线或单项测试替代。

## 禁止实现

- clone live train/controller/ticket/lock/reservation/dispatcher runtime；
- 复用live plugin provider instance；
- simulation调用Bukkit/render/model side effect；
- 不awaitphysics就tickDispatcher；
- reuse same isolated world；
- 忘记close pools或create failure leak。

## 必需测试与验证

- `DetailedTrainTravelSimulatorTest.kt`：determinism、speed cap、stops/groups、deadline/interrupt/invalid。
- `IsolatedTimetableSimulationTest.kt`：no live train、parallel deterministic、真实Basic admission、unsupported reversal fail-closed、delay/period/failure、single-use/close。
- Bukkit `SimulationTrainOperationGatewayTest.kt`：真实逐车JS、效果启动、reject/cancel及结构变化。
- Bukkit factory tests：revision/profile/required provider准备及实际turnout选择。

```bash
./gradlew :SimpleATO:test --tests 'work.art1st.javarailways.simpleato.timetable.simulation.*'
./gradlew :BukkitPlugin:test --tests '*IsolatedTimetableSimulation*'
```


## 真实逐车operation consumer

Bukkit HTTP factory不再注入SimpleATO的duration-only SimulationTrainOperationGateway；该类、resolver及无其他consumer的modelActionDurationTicks已删除。SimpleATO仍只消费Basic TrainOperationGateway，factory在目标operationResourceFactory中创建同模块真实逐车JS adapter并交dispatcherFactory注入，不引入Basic→Bukkit或SimpleATO→Bukkit依赖。实际逐源capture、混装方向、动作选择/拒绝、onReverse、exact car结构延续与平台排除归[Bukkit模拟行为](../../bukkit-plugin/backend/simulation-car-behavior.md)。

目标operation资源由IsolatedTimetableSimulationWorld.create的`operationResourceFactory(CoreWorld): AutoCloseable?`在创建domain前构造；成功create保留其独立关闭身份，failed create/seed同样清理。正常关闭/取消先确认physics/generic真退出，再关闭operation owner（pending、事件、Context）与其他领域资源；不能在simulate返回或ticket取消时抢先释放仍有回调使用的对象。用真实generic worker barrier验证关闭不早发生，partial installation failure关闭已创建operation owner。该参数只连接具体已存在的资源，不扩为任意DI或Environment框架。

## 交路程序任务耗时

当前 TaskStart/TaskEnd 为顶层、成对、唯一 taskId 的元数据动作，受既有节点上限约束。TaskStart 等待计划时刻及前序完成，Dispatcher 在实际推进时记录 tick；TaskEnd 不等待自己的计划 tick。HALT、STORE 和被消耗的编组交接须在真实操作成功后关闭末尾边界。失败、未完成或超出模拟周期不产生结束值。

运行快照提供 transient taskTimings；HTTP 模拟响应扁平 tasks 为 serviceId/taskId/startTick/endTick?。不能用下一任务开始或最终运行快照推断耗时。计时不写 checkpoint；恢复后没有观测到 START 的 END 只推进游标，不伪造测量。相同交路的编组交接保留本次观测历史，新的运行实例独立。手动规划耗时不参与运行执行。
