# Basic persistence 契约

## 适用场景与用途

触发：修改Basic infrastructure/runtime、diagnostic/provider payload或Core/Basic/SimpleATO split pairing。运营对象、rolling-stock ledger、Timetable JSON与runtime归SimpleATO。

用途：strict decode current document后做全identity/reference预校验，再materialize fresh world；schema version、revision、physics epoch、provider/diagnostic version各自独立。通用规则见[共享持久化](../shared/persistence.md)。

## Owner、独立 documents 与精确字段

```kotlin
data class BasicInfrastructureSnapshotV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val wayPoints: List<SavedWayPointV1>,
    val railMetadata: List<SavedRailInfrastructureMetadataV1> = emptyList(),
    val operatingDirections: List<SavedOperatingDirectionV1> = emptyList()
)
```

Infrastructure format 2 是相对最后一个真实 Basic format 1 的一次 breaking transition：Destination/Line 被迁到
SimpleATO 的第一个 infrastructure document，旧 metadata 中的 `allowedDirection` 改由 Basic 独立稀疏方向列表保存。
用户已明确这一开发稿从未部署，因此本次直接修正归属：Basic 保持 format 2，SimpleATO 保持 format 1 并删除方向字段。
Basic 新列表有空列表默认值；不增加读取旧 SimpleATO 方向字段、搬迁转换或双写兼容。

Runtime `BasicRuntimeSnapshotV3(formatVersion=3,infrastructureRevision,physicsEpoch)`只保留 split pairing 需要的
revision/epoch，不包含运营 ledger、Timetable 或 Dispatcher。最后一个真实 Basic runtime format 2 同时保存
Timetable 与 rolling stock；最终删除这两组字段是一次 breaking transition，因此只升级一次到 format 3。
writer 与正常 reader 均 current-only。

Destination、Station、Depot、rolling-stock ledger、Line、Timetable DTO/codec、runtime store与Dispatcher checkpoint由[SimpleATO persistence](../simpleato/persistence.md)拥有。

Diagnostic独立：`AtoStallReportJson.schemaVersion=3`、`DepotResetReportJson.schemaVersion=2`。ATO诊断版本3删除已废弃的`activeProviderId`。SimpleATO 的 `lineRevision`是edit revision；`infrastructureRevision`配对files；`physicsEpoch`配对publication；都非schemaVersion。

## Split pairing与restore pipeline

Bukkit outer infrastructure要求Core+Basic+SimpleATO revision相同；runtime要求revision和physicsEpoch都相同。capture在同一server revision、awaited exact physics ticket后产生六个value payload；background writer只写capture结果。

一次性迁移的真实 source/target 是：

```text
InfrastructureFileV1 + BasicInfrastructureSnapshotV1 + no SimpleATO infrastructure
  → InfrastructureFileV2 + BasicInfrastructureSnapshotV2 + SimpleATOInfrastructureSnapshotV1
```

source Basic V1 中的 Destination、Line 和 `operating.allowedDirection` 在 migration boundary 内一次拆分到最终
owners。正常 codec 只读取 current document。

```text
strict decode current
→ prevalidate all duplicate identities、cross-store collisions、rail/station/point/provider refs
→ require fresh target
→ materialize Basic standalone WayPoints/metadata/public operating directions
→ materialize canonical SimpleATO Destinations及其Station/Depot/WayPoint投影
→ validate Line StationId references and materialize SimpleATO Lines
→ materialize SimpleATO RollingStock
→ restore SimpleATO Timetables before Dispatcher checkpoint
→ rebuild ephemeral speed signals/providers separately
```

## Validation/error矩阵

| 条件 | result | target state |
| --- | --- | --- |
| unsupported/future/unknown field/invalid JSON | fail-fast | untouched |
| orphan waypoint/metadata rail | fail before materialize | fresh |
| duplicate waypoint/metadata或cross-store ID | fail before materialize | fresh |
| 方向记录非法UUID、重复rail或指向缺失轨道 | fail before any Basic materialize | fresh |
| SimpleATO Destination orphan/collision或Line missing Station | fail prevalidation | fresh SimpleATO stores与Basic投影 |
| runtime duplicate train/orphan point | fail prevalidation | fresh |
| Core/Basic/SimpleATO pair mismatch | outer load拒绝 | 不选partial file |
| checkpoint active run | recovery record + disabled service | 无live execution |
| additive default field | fixed old fixture直接decode，不升版 | writer输出current |
| breaking shape/unit/meaning | 升最小独立boundary；pure decode upcast（仅明确支持） | business只current |
| unknown extension type/version | Unavailable/fail per contract | 禁止FQCN反射 |

## 项目内正确示例

Basic capture由composition显式传入SimpleATO canonical destination ID，排除其派生WayPoint：

```kotlin
BasicInfrastructureSnapshotV2.capture(
    world,
    revision,
    projectedWayPointIds = world.simpleAtoWorldData.destinations.all().mapTo(HashSet()) { it.id }
)
```

Basic只接受待排除的通用derived WayPoint identity，不查询或拥有Destination；SimpleATO snapshot单独保存canonical Destination。这保证operational stop不会以两份权威记录重复落盘。

`SavedOperatingDirectionV1` 只保存轨道 UUID 和唯一许可 `ViewDirection`；没有记录表示两向许可。
capture 从 `BasicWorldData.operatingDirections` 取得稀疏快照并拒绝孤儿记录。
restore 在写入任何 WayPoint、metadata 或方向之前完整验证全部记录，再恢复到空方向存储；
具体存储与通知机制见[轨道公共运行方向](./operating-directions.md)。

WayPoint 在预校验阶段解析 UUID、构造值并检查非空白 ID；metadata 与方向先解析 UUID，再按 UUID 检查重复和引用。大小写不同但表示同一 UUID 的两条 metadata 必须拒绝，不能在 `associate` 时覆盖。所有检查通过后才把已解析值写入三个空存储；后面的非法记录不能使前面的合法 WayPoint 提前落入目标。不添加通用回滚、额外弧长规则或旧数据 reader，当前 format 2 不变。两个固定非法 JSON 回归均须断言三个存储保持为空，并证明修正输入后可在同一目标正常恢复。

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
