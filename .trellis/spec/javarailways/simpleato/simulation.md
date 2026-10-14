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
- single-use；`close()`关闭physics+TickManager；create failure也cleanup。

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
| live-only extension provider | unavailable；不复制 |
| operation无simulation duration | Rejected；不执行platform effect |

Registry只复制显式实现simulation factory的providers；Bukkit capture canonicalize static active turnout，要求每service profile且same selector same profile。

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
