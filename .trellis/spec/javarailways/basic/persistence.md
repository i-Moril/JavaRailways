# Basic persistence 契约

## 适用场景与用途

触发：修改Basic infrastructure/runtime、rolling-stock ledger、diagnostic/provider payload或Core/Basic/SimpleATO split pairing。Timetable JSON与runtime归SimpleATO。

用途：strict decode current document后做全identity/reference预校验，再materialize fresh world；schema version、revision、physics epoch、provider/diagnostic version各自独立。通用规则见[共享持久化](../shared/persistence.md)。

## Owner、独立 documents 与精确字段

```kotlin
data class BasicInfrastructureSnapshotV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val destinations: List<SavedOperationalDestinationV1>,
    val wayPoints: List<SavedWayPointV1>,
    val railMetadata: List<SavedRailInfrastructureMetadataV1> = emptyList()
)
```

Runtime `BasicRuntimeSnapshotV3(formatVersion=3,infrastructureRevision,physicsEpoch,rollingStock)`不包含Timetable或Dispatcher。删除 Line 字段后 Basic infrastructure 为format 2；两个独立boundary各自使用`encodeDefaults=true/ignoreUnknownKeys=false`，writer current-only。

Line、Timetable DTO/codec、runtime store与Dispatcher checkpoint由[SimpleATO persistence](../simpleato/persistence.md)拥有。

Diagnostic独立：`AtoStallReportJson.schemaVersion=2`、`DepotResetReportJson.schemaVersion=1`。SimpleATO 的 `lineRevision`是edit revision；`infrastructureRevision`配对files；`physicsEpoch`配对publication；都非schemaVersion。

## Split pairing与restore pipeline

Bukkit outer infrastructure要求Core+Basic+SimpleATO revision相同；runtime要求revision和physicsEpoch都相同。capture在同一server revision、awaited exact physics ticket后产生六个value payload；background writer只写capture结果。

```text
strict decode current
→ prevalidate all duplicate identities、cross-store collisions、rail/station/point/provider refs
→ require fresh target
→ materialize canonical Basic Destinations/roles/WayPoints/metadata
→ validate Line StationId references and materialize SimpleATO Lines
→ materialize Basic RollingStock
→ restore SimpleATO Timetables before Dispatcher checkpoint
→ rebuild ephemeral speed signals/providers separately
```

## Validation/error矩阵

| 条件 | result | target state |
| --- | --- | --- |
| unsupported/future/unknown field/invalid JSON | fail-fast | untouched |
| orphan destination/waypoint/metadata rail | fail before materialize | fresh |
| duplicate destination/waypoint/metadata或cross-store ID | fail before materialize | fresh |
| SimpleATO Line missing Basic Station | fail prevalidation | fresh SimpleATO line store |
| runtime duplicate train/orphan point | fail prevalidation | fresh |
| Core/Basic/SimpleATO pair mismatch | outer load拒绝 | 不选partial file |
| checkpoint active run | recovery record + disabled service | 无live execution |
| additive default field | fixed old fixture直接decode，不升版 | writer输出current |
| breaking shape/unit/meaning | 升最小独立boundary；pure decode upcast（仅明确支持） | business只current |
| unknown extension type/version | Unavailable/fail per contract | 禁止FQCN反射 |

## 项目内正确示例

Infrastructure capture排除Destination派生WayPoint：

```kotlin
val destinationIds = destinations.mapTo(HashSet()) { it.id }
val standaloneWayPoints = data.wayPoints.all()
    .filterNot { it.id in destinationIds }
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshot.kt`。这保证canonical operational stop不会以两份权威记录重复落盘。

## 禁止实现

- materialize后才检查后续Line/runtime引用；
- 中途failure留下partial stores；
- revision/epoch/provider version当schema version；
- nested DTO机械升V2；
- 为旧数据全局ignore unknown；
- business层长期V1/V2分支；
- extension FQCN反射；
- 只做current writer→reader round-trip。

## 必需测试与验证

- infrastructure/runtime各有hand-written current/old/future/unknown/corrupt/orphan/duplicate/collision fixtures。
- 每个failure断言target所有stores fresh/unchanged。
- pairing tests覆盖main/backup highest matching pair和missing half。
- Timetable snapshot与Dispatcher recovery测试属于SimpleATO。
- additive field固定旧JSON+default+round-trip+version不变。

```bash
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.persistence.*'
./gradlew :SimpleATO:test --tests 'work.art1st.javarailways.simpleato.persistence.*'
./gradlew :BukkitPlugin:test --tests '*SplitWorldSnapshot*' --tests '*WorldPersistence*'
```
