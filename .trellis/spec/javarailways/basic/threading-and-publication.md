# Basic threading 与 publication 契约

## 适用场景与用途

触发：main/plugin提交revisioned command、physics worker更新控制器、Dispatcher读取publication、对外`RailwayObservation`、safety request或world fail-close。

用途：只在Tick boundary激活immutable request，并在Core完整epoch后发布value state；Dispatcher不读取worker active field，也不在failed epoch分配/释放资源。

## Owner 与线程流程

```text
SimpleATO/main producers
→ RevisionedCommandQueue提交immutable envelope
physics Tick boundary
→ drain strict-monotonic revisions并激活
worker
→ SimpleATO plan/constraints → Basic actuator/move/invariant
Core complete epoch
→ onPhysicsStatePublished写volatile value state/notifications
main owner await exact PhysicsTicket success
→ publish RailwayObservation
→ 显式驱动world-local route-grant tail release（stable owner order，首个Stall即停止）
→ apply safety requests、drain frames、Dispatcher.tick(world)
```

`RevisionedCommandQueue<C>`真实contract：`offer(revision,command): ACCEPTED|STALE`；`enqueue(command)`原子分配next revision；`drain()`返回immutable revision-ordered list。revision必须`>=0`且strictly greater；overflow fail。

```kotlin
if (revision <= latestAcceptedRevision) return CommandOfferResult.STALE
pending.addLast(RevisionedCommand(revision, command))
latestAcceptedRevision = revision
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/command/RevisionedCommandQueue.kt`。Queue只安全传envelope，不使mutable payload线程安全。

## Publication与failure矩阵

| 条件 | physics结果 | main结果 |
| --- | --- | --- |
| stale revision | 不入queue、不改active | 返回`STALE/StaleRevision` |
| accepted request | 下个physics boundary激活 | publish前仍读old state |
| worker正常 | active movement/constraints完成 | exact ticket后读`SimpleAtoPublishedState` |
| coherent world frame | 同epoch train frames + topology revision已冻结 | 投影并发布一个`RailwayObservation` |
| worker/controller exception | SimpleATO保存first report并锁住Basic interlock，ATO/manual controller freeze | ticket/coordination failure后不继续Dispatcher |
| same world后续failure | 不覆盖first report | 保留首份诊断 |
| other world | 独立coordinator | 不受Stall影响 |

SimpleATO `AtoWorldCoordinator.stall(report): Boolean`只允许CAS `RUNNING→STALLED`，并锁住同world Basic `WorldSafetyInterlock`；`reset()`仅admin在train/resource recovery完成后调用并同时释放interlock。Depot reset成功也保持Stalled，管理员另行reset。

Core frame shape/epoch成功语义由[Core runtime](../core/runtime-publication.md)拥有；本页只定义Basic消费顺序。

## 对外 observation contract

`RailwayObservation.fromCore(worldId, frame)`只接受一个完整`RailwayWorldFrameSnapshot`，并生成`RailwayStateStamp(worldId, physicsEpoch, topologyRevision)`。`physicsEpoch`与`topologyRevision`是两个独立runtime计数器，都不是schema version，也不能跨world比较。投影保留head/tail facing、canonical AB位置、速度、epoch-end保守`occupiedIntervals`、有序tail-to-head `bodyTraversal`、完整`sweptSpans`、同epoch有序head/tail movement traversal及同一Core frame的`ActiveRailContinuationsSnapshot`，移除render car state和所有live `World/Train/Rail`引用；observation同时保留frame的同epoch `RailwayOccupancySnapshot`引用，供准入、释放和只读consumer做局部查询，不再重建第二份world swept index。Basic不复制或解释active continuation；signal consumer以`entry RailEndpointRef`查询当前selected exit。movement traversal是后续Basic边界检测的输入，`bodyTraversal`供tail-started路线装配；两者都不在投影时按rail合并或从topology重建。当前区间成员关系必须使用`occupiedIntervals`。

Bukkit adapter在exact physics ticket成功后的serialized simulation context同步发布；listener不在physics worker运行，必须快速返回，且不能直接执行entity/location/block/region side effect。world unload移除latest snapshot，plugin disable移除owner subscriptions。慢listener不得反向阻塞physics worker，也不得要求Core为每个subscriber保留逐epoch backlog。

当前Bukkit生产composition在`DefaultRailwayObservationService.publish`返回本次新建的同一observation后，直接调用
world-local `RouteResourceAdmissionRuntime.consumeObservation`；这条host安全链不借用普通插件listener，也没有第二个
队列或第二次Core frame投影。runtime内部一次建立train ID lookup并按logical owner稳定顺序驱动车尾释放；
fail-closed controller触发SimpleATO `AtoWorldCoordinator` Stall；该coordinator锁住Basic interlock，随后Dispatcher入口读取同一coordinator状态并停止本tick mutation。
外部listener异常仍按订阅合同隔离，不能代替这条host安全链。

## 禁止实现

- main直接写ATO active mission/constraint/perception；
- queue传mutable list/map后producer继续修改；
- worker访问Bukkit、blocking I/O或返回live collections；
- epoch完成前Dispatcher allocate/release；
- failed ticket后消费frame/safety request；
- 从不同epoch的live getters与topology revision重新拼装observation；
- 在observation listener中直接执行Bukkit world side effect或长时间阻塞；
- Stall后同一Dispatcher Tick继续其他run mutation；
- recovery完成前reset latch。

## 项目内正确示例

SimpleATO `AbstractATO`只在publication callback构造对外state：

```kotlin
final override fun onPhysicsStatePublished() {
    publishedState = AtoPublishedState(
        trainSnapshot = captureTrainSnapshot(atoTick),
        lifetimeTravelDistanceMeters = activeLifetimeTravelDistanceMeters,
        controllerState = capturePublishedControllerState()
    )
    afterPhysicsStatePublished()
}
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/ato/AbstractATO.kt`。它避免Dispatcher在worker计算中看到mission与geometry的混合版本。

## 必需测试与验证

- `PhaseAFoundationTest.kt`：100 producers得到`0..99` revisions、stale rejection、defensive copy。
- SimpleATO `SimpleATOTest.kt`：tick后commit前published state不变，commit后mission revision/state同时更新。
- SimpleATO `AtoPolicyTest.kt`：world Stall独立、first failure/reset和Basic interlock桥接。
- Bukkit tick-order test：await exact ticket后才safety/frame/Dispatcher；failure无后续side effect。
- `RailwayObservationTest.kt`与Bukkit service tests：一致stamp、稳定排序、defensive copy、owner disable/world unload cleanup。

```bash
./gradlew :Basic:test --tests '*PhaseAFoundationTest' --tests '*WorldSafetyInterlockTest'
./gradlew :SimpleATO:test --tests '*AtoPolicyTest' --tests '*SimpleATOTest'
./gradlew :BukkitPlugin:test --tests '*WorldManager*' --tests '*BukkitThreadGuardBridgeTest'
```
