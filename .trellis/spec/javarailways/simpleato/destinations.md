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

`SimpleATOWorldData.destinations`是唯一canonical writable store。`OperationalDestinationService(world)`是mutation的main-thread owner；它原子维护SimpleATO Station投影、Basic Depot投影及Basic通用WayPoint route-anchor投影。Basic WayPoint不是第二个Destination owner。`service[id]`提供只读查询，返回当前不可变`OperationalDestination`值或`null`。

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
    if (depotRole != null) basicData.depots.removeStablingPoint(depotRole.pointRef)
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
