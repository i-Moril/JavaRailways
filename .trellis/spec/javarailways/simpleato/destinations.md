# SimpleATO operational destination 契约

## 适用场景与用途

触发：创建/replace/remove operational stop，迁移rail delete/split/merge/replacement，或capture Destination/WayPoint。

用途：`OperationalDestination`是唯一canonical operational route anchor；WayPoint、StationBerth与Depot point是derived/projection，必须在main-thread service transaction同步，避免三份权威数据漂移。

## Owner、identity、坐标与role

```kotlin
data class OperationalDestination(
    val id: String,
    val displayName: String,
    val railId: UUID,
    val arcLengthInAbMeters: Double,
    val approachDirection: ViewDirection? = null,
    val role: DestinationRole = DestinationRole.Regular
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/destination/OperationalDestination.kt`。

- `railId`是persistent Core rail UUID；arc finite、`>=0 m`且始终canonical AB。
- `approachDirection=null`表示destination不额外限制，仍受rail operating direction。
- role token：`REGULAR/STATION_BERTH/DEPOT_STABLING_POINT`。
- role-owned ID固定`<owner>@<point>`；component禁止`@`/control char；general destination ID还禁止`/`。只用`OperationalDestinationIds`生成/解析。
- `toWayPoint()`只派生id/displayName/railId/AB arc；WayPoint本身无direction。

`SimpleATOWorldData.destinations`是唯一canonical writable store。`OperationalDestinationService(world)`是mutation的main-thread owner；它原子维护SimpleATO Station投影、SimpleATO Depot投影及Basic通用WayPoint route-anchor投影。Basic WayPoint不是第二个Destination owner。`service[id]`提供只读查询，返回当前不可变`OperationalDestination`值或`null`。

## Transaction与结果矩阵

| 操作/条件 | 结果 | rollback/side effect |
| --- | --- | --- |
| register duplicate destination/WayPoint ID | fail-fast | 所有stores不变 |
| rail missing/AB arc超length | `IllegalArgumentException` | 不materialize role |
| valid role | 先materialize Station/Depot role，再WayPoint+Destination | 后续失败移除role/WayPoint |
| replace new失败 | restore old destination及derived records | 不留下半替换 |
| rail delete | remove destination、derived WayPoint/role及standalone WayPoints | 再删Core rail |
| split/merge | 按几何映射rail UUID与AB arc | role records同批重建 |
| reversed replacement | 保持physical point和approach direction | 必要时翻`ViewDirection` |
| capture | Destination落盘；same-ID derived WayPoint排除 | standalone WayPoint仍保存 |
| `service[id]` | 返回当前destination或`null` | read-only，不改变任何store |

## 项目内正确示例

Service在最后两次写入失败时清已materialize role：

```kotlin
try {
    basicData.wayPoints.add(destination.toWayPoint())
    data.destinations.add(destination)
} catch (failure: Exception) {
    basicData.wayPoints.remove(destination.id)
    if (stationRole != null) data.stations.removeBerth(stationRole.berthRef)
    if (depotRole != null) data.depots.removeStablingPoint(depotRole.pointRef)
    throw failure
}
```

来源：`OperationalDestination.kt`。它避免Station marker存在但route anchor缺失。

## 禁止实现

- adapter分别写Destination/WayPoint/Berth；
- persistence保存live `RailLocation`或裸BA arc；
- 手拼`owner@point`；
- replacement只改UUID不改arc/direction/role；
- operational creation绕过service直接`WayPointStore.add`（same-ID会overwrite）。

## 必需测试与验证

`OperationalDestinationTest.kt`必须覆盖canonical ID/invalid token、directed route、role materialization、duplicate/rollback、rail delete、reversed replacement、split/merge及standalone WayPoint migration。Assertion同时检查Destination、WayPoint、Station/Depot role与RailObjectStore，而非单一store。

```bash
./gradlew :SimpleATO:test --tests '*OperationalDestinationTest'
./gradlew :SimpleATO:test --tests '*SimpleATOInfrastructureSnapshotTest'
./gradlew :Basic:test --tests '*BasicInfrastructureSnapshotTest'
```


## B1：保持停车区配置与实际allocation一致（CURRENT）

Station角色新增`stoppingAreaId`（默认本berthId）和可选正finite的`minimumTrainLengthMeters`、
`maximumTrainLengthMeters`；min不能大于max。这些字段只由canonical Destination保存，
`registerStationBerth`原样投影到同world StationStore，基础设施snapshot使用明确默认值，不增加独立marker版本。

正常新增/替换先校验新角色area，移除先校验旧角色area。Service查询Dispatcher
`hasStationStoppingAreaAllocation(StationStoppingAreaRef)`；已held时抛`IllegalStateException`，
发生在任何Destination/WayPoint/Station projection mutation以前。替换为另一个已held area也在remove旧值以前拒绝；
同rail批量删除先检查所有受影响area，避免先删除一部分再遇到持有的marker。

例如T持有central/platform-one时，不能把central@short改为new-area、删除该marker，或在已held area新增另一个alias。
允许在无关联且未held的area编辑。此边界保护逻辑容量；不会通过改配置释放已占物理资源。
查询只读、O(1)；canonical mutation仍是main-thread service事务，allocator保留取得时area，确保exact release不漂移。

`TimetableStationCallDispatcherTest`使用真实Dispatcher持有station allocation后检查replace/remove/register均拒绝，
并断言旧Destination、WayPoint与area ownership保留；现有roundtrip/projector测试仍覆盖三个新字段。
错误做法是只在UI隐藏编辑按钮或释放时重新解析area；正确做法是Service预校验加allocator取得时identity。
尚未证明真实游戏中的多marker竞争与动态选择，不能用该配置拒绝单测代替B4 integrated acceptance。
