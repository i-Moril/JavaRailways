# Basic world-local runtime 契约

## 适用场景与用途

触发：构造/查找`BasicWorldData`、新增world store/registry、注册`RailObject`、创建/卸载Basic world data。

用途：所有Basic mutable state必须world-local且显式attach，防止跨world污染或test fallback进入production。Destination/Station的world-local owner见[SimpleATO规范](../simpleato/index.md)。

## Owner、字段与lifecycle

`BasicWorldData`是每个Core `World`唯一composition container：

```kotlin
data class BasicWorldData(
    val railMap: RailMap = RailMap(),
    val wayPoints: WayPointStore = WayPointStore(),
    val safetyInterlock: WorldSafetyInterlock = WorldSafetyInterlock(),
    val railObjects: RailObjectStore = RailObjectStore(),
    ...
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`。

ATO coordinator、Destination/Station/Depot/Line/Timetable store、rolling-stock ledger、extension registry 与 Dispatcher 都不属于该container；`SimpleATOWorldData`是它们的唯一world-local owner。旧ATO provider registry已经删除。`WorldSafetyInterlock`只是Basic物理控制使用的通用fail-close latch，不携带ATO诊断。Basic不保存运营镜像store，也不反向查找SimpleATO。

通用资源运行时由Bukkit composition为每个world另建一条成对生命周期记录：同一记录持有唯一
`InfrastructureCoordinationRegistry`和`RouteResourceAdmissionRuntime`，后者复用前者完成自动进路claim、设备准备、
grant与车尾释放。它不是第二份资源表，也不进入`BasicWorldData`持久化。创建顺序必须是注册registry、attach
route runtime与published-frame processor，最后才启动`PhysicsCoordinator`；卸载按逆序关闭runtime/registry、
coordinator与tick owner。attach失败不得把半构造world放入`WorldManager.railWorlds`。

`BasicPlugin.INSTANCE`只是Core extension slot identity；`Plugin.identifier`不持久化。Production accessor missing plugin/data必须抛错，不隐式创建。Bukkit factory与test fixture显式`world.registerExtensionData(plugin,data)`。

## RailObject lifecycle

- `RailObjectStore.register(obj)`要求`ACTIVE`，`RailObjectRef`world内唯一；duplicate→`IllegalStateException`且原object保持。
- concrete owner先发布`REMOVED`再`unregister(ref)`；否则fail且registration保持。
- `objectsOnRail(UUID)`只返回ACTIVE；`get(ref)` consumer仍检查lifecycle。
- same live ref不得解析成不同object；perception必须Stall而非shadow。

真实unregister顺序：

```kotlin
check(registered.lifecycleState == RailObjectLifecycleState.REMOVED)
check(byRef.remove(ref, registered))
byRail[registered.location.rail.id]?.remove(registered)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/provider/RailObjectStore.kt`。它避免by-ref与by-rail indexes分裂。

## 结果矩阵

| 条件 | 结果 | state |
| --- | --- | --- |
| world无Basic data | required accessor失败 | 不创建singleton fallback |
| duplicate RailObjectRef | register失败 | old object仍唯一 |
| generic ACTIVE object unregister | check失败 | 两个indexes不变 |
| snapshot/discovery | stable ID排序/value copy | 不暴露mutable map |

## 禁止实现

- singleton map存world mutable state；
- 在`BasicWorldData`增加Dispatcher forwarding字段或Basic→SimpleATO accessor；
- 为infrastructure dependency API与自动进路分别创建两个`InfrastructureCoordinationRegistry`；
- accessor fallback到`TestBasicWorldDataRegistry`；
- `BasicPlugin.INSTANCE`持有world data；
- 从Basic增加Destination/Station mirror store或SimpleATO forwarding字段；
- 因底层`ConcurrentHashMap`就声称跨store transaction原子。

## 必需测试与验证

- `RailObjectStoreTest.kt`：duplicate ref、ACTIVE unregister failure与双索引一致性。
- world creation/unload integration：attach一次、restore后first wake、fixture unregister与Core resources close。

```bash
./gradlew :Basic:test --tests '*RailObjectStoreTest'
./gradlew :SimpleATO:test --tests '*StationModelTest'
./gradlew :BukkitPlugin:test --tests '*RailWorldFactory*'
```
