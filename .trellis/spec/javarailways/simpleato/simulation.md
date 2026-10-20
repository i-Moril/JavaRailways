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
| operation无simulation duration | Rejected；不执行platform effect |

Registry在源World稳定边界捕获准备值：`TimetableConditionSimulationFactory.prepareSimulation(): TimetableConditionSimulationPreparation`与action对应接口返回脱离live状态的准备对象；其`create(targetWorld)`每次创建目标provider。`TimetableExtensionRegistry.prepareSimulation(): TimetableSimulationExtensions`保留builtin语义，遇到已注册而无准备器的领域provider明确失败，不再silent filter。一次capture创建两个目标时分别创建registry/provider；不得把已经创建的registry或live provider闭包作为共享输入。未发布运行API直接替换旧`createSimulationRegistry`，无持久化字段或版本变化。

Bukkit capture仍canonicalize static active turnout，要求每service profile且same selector same profile；并捕获真实速度安全配置、准备好的扩展及`PreparedSimulationFeature`。宿主从实际domain注册发现required owner IDs，缺准备器须在capture拒绝，不能以空贡献集合宣称完整支持。prepared feature只在目标`SimulationDomainTarget(world, domain, routeAdmission)`上安装：使用真实signal/inspection/planning与本地Basic provider/contributor/preparation端点，新建可变实例，返回所属注册的close结果。这是当前部件的安装接线，不是独立Environment或任意对象clone/DI框架；完整SimpleSignals安装归后续子任务。

SIM领域stamp使用目标CoreWorld已有的`runtimeId`（UUID字符串），不是固定`"isolated"`。每target准备的信号定义须使用该target身份并针对其root编译，才能满足真实source对definition World UUID与observation stamp相等的合同；不能沿用LIVE定义的worldId后删除该检查。LIVE继续使用既有Bukkit World UUID。没有新增身份层、存档字段或版本。

### 实际与模拟共用领域阶段

`OperationalWorldRuntime`具体装配同一Core/Basic/SimpleATO链，持有新建的restriction store、inspection、SignalEpochRuntime、Basic数据、安全interlock、ATO coordinator及Dispatcher。实际平台仍拥有profile、Bukkit效果、诊断和service适配；模拟使用目标CoreWorld唯一的资源registry、本地Basic admission及其admission→SignalEpochRuntime更新，不共享LIVE callback或JS实例。

静态设施restore后两模式都调用真实`OperationalSpeedLimitSignalMaterializer`生成AB/BA限速及release transition，并在physics启动前安装`SpeedSafetyMonitor`。物理成功后的`afterPublication`复用原次序：发布完整observation并消费admission释放→发布pending物理脱轨通知→消费同epoch速度脱轨请求→平台frame回调→Dispatcher→generic postTick。列车当前frame epoch不等于request的observed epoch时丢弃该速度请求；失败ticket不得调用这条消费阶段。具体Core成功事实由[发布规范](../core/runtime-publication.md)拥有。

create失败逆序释放已经安装的feature注册及自有域/registry/pools；某项close失败继续尝试其他资源，保留原异常并附suppressed。普通close也只释放目标自己的资源：在目标owner内先取消physics/generic pools并确认真实callback退出，再逆序关闭feature注册、local admission、共同领域runtime和runtime binding；中断等待后仍完成安全终止并恢复interrupt。失败ticket不是worker退出证明，不能在HTTP取消的`use`清理时先卸载仍被callback使用的资源；不退出的第三方callback会保留依赖并低频日志，不强杀或超时释放。不尝试在运行中移除Core禁止动态卸载的静态epoch processor；其停止随coordinator。门/模型操作仍沿当前duration-only gateway，完整逐车行为归后续子任务，不把本批共同域接线称为full Feature模拟。

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
- `SimulationTrainOperationGatewayTest.kt`：logical duration/reject/cancel。
- Bukkit factory tests：revision/profile/provider opt-in/canonical turnout。

```bash
./gradlew :SimpleATO:test --tests 'work.art1st.javarailways.simpleato.timetable.simulation.*'
./gradlew :BukkitPlugin:test --tests '*IsolatedTimetableSimulation*'
```
