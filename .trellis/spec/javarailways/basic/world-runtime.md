# Basic world-local runtime 契约

## 适用场景与用途

触发：构造/查找`BasicWorldData`、新增world store/registry、注册`RailObject`、创建/卸载Basic world data。

用途：所有运营mutable state必须world-local且显式attach，防止跨world污染、test fallback进入production或Station/RailObject store不一致。

## Owner、字段与lifecycle

`BasicWorldData`是每个Core `World`唯一composition container：

```kotlin
data class BasicWorldData(
    val railMap: RailMap = RailMap(),
    val wayPoints: WayPointStore = WayPointStore(),
    val destinations: OperationalDestinationStore = OperationalDestinationStore(),
    val depots: DepotStore = DepotStore(),
    val atoCoordinator: AtoWorldCoordinator = AtoWorldCoordinator(),
    val atoProviders: AtoProviderRegistry = AtoProviderRegistry(),
    val railObjects: RailObjectStore = RailObjectStore(),
    val stations: StationStore = StationStore(railObjects),
    ...
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`。constructor要求`StationStore`与`railObjects`为同一instance。

Line store、Timetable store、extension registry 与 Dispatcher 都不属于该container；`SimpleATOWorldData`是它们的唯一world-local owner。Basic中的Depot Reset仅依赖显式传入的
`DepotResetDispatchParticipant`，不能通过`BasicWorldData`反向查找SimpleATO。

通用资源运行时由Bukkit composition为每个world另建一条成对生命周期记录：同一记录持有唯一
`InfrastructureCoordinationRegistry`和`RouteResourceAdmissionRuntime`，后者复用前者完成自动进路claim、设备准备、
grant与车尾释放。它不是第二份资源表，也不进入`BasicWorldData`持久化。创建顺序必须是注册registry、attach
route runtime与published-frame processor，最后才启动`PhysicsCoordinator`；卸载按逆序关闭runtime/registry、
coordinator与tick owner。attach失败不得把半构造world放入`WorldManager.railWorlds`。

`BasicPlugin.INSTANCE`只是Core extension slot identity；`Plugin.identifier`不持久化。Production accessor missing plugin/data必须抛错，不隐式创建。Bukkit factory与test fixture显式`world.registerExtensionData(plugin,data)`。

## RailObject lifecycle

- `RailObjectStore.register(obj)`要求`ACTIVE`，`RailObjectRef`world内唯一；duplicate→`IllegalStateException`且原object保持。
- generic owner先发布`REMOVED`再`unregister(ref)`；否则fail且registration保持。
- `AtoProviderBoundary`由store owns removal transition。
- `objectsOnRail(UUID)`只返回ACTIVE；`get(ref)` consumer仍检查lifecycle。
- same live ref不得解析成不同object；perception必须Stall而非shadow。

真实unregister顺序：

```kotlin
if (registered is AtoProviderBoundary) registered.markRemoved()
check(registered.lifecycleState == RailObjectLifecycleState.REMOVED)
check(byRef.remove(ref, registered))
byRail[registered.location.rail.id]?.remove(registered)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/provider/RailObjectStore.kt`。它避免by-ref与by-rail indexes分裂。

## 结果矩阵

| 条件 | 结果 | state |
| --- | --- | --- |
| world无Basic data | required accessor失败 | 不创建singleton fallback |
| StationStore引用不同RailObjectStore | constructor `require`失败 | data不attach |
| duplicate RailObjectRef | register失败 | old object仍唯一 |
| generic ACTIVE object unregister | check失败 | 两个indexes不变 |
| Station remove | mark berth REMOVED→unregister→update aggregate | 不留live marker |
| snapshot/discovery | stable ID排序/value copy | 不暴露mutable map |

## 禁止实现

- singleton map存world mutable state；
- 在`BasicWorldData`增加Dispatcher forwarding字段或Basic→SimpleATO accessor；
- 为applied-plan dependency API与自动进路分别创建两个`InfrastructureCoordinationRegistry`；
- accessor fallback到`TestBasicWorldDataRegistry`；
- `BasicPlugin.INSTANCE`持有world data；
- 单独改Destination/WayPoint/Station等多store state，不走domain service；
- 因底层`ConcurrentHashMap`就声称跨store transaction原子。

## 必需测试与验证

- `StationModelTest.kt`：store identity fail、world isolation、register/remove同步RailObject lifecycle。
- `RailObjectStoreTest.kt`：duplicate ref、ACTIVE unregister failure、provider boundary store-owned removal。
- world creation/unload integration：attach一次、restore后first wake、fixture unregister与Core resources close。

```bash
./gradlew :Basic:test --tests '*StationModelTest' --tests '*RailObjectStoreTest'
./gradlew :BukkitPlugin:test --tests '*RailWorldFactory*'
```
