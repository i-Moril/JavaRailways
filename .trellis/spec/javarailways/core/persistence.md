# Core persistence 契约

## 适用场景与用途

触发：修改Core infrastructure/runtime JSON、rail/train DTO、extension payload、revision/epoch pairing或restore。

用途：只给独立document boundary编号，strict decode后完整预校验再materialize；版本、revision、epoch与extension identity不得混用。通用政策见[共享持久化规范](../shared/persistence.md)。

## Owner、独立 boundary 与精确字段

`CoreWorld`拥有两个文档：

```kotlin
data class SavedInfrastructureV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val rails: List<CoreRail.SavedRailV1>
)
data class SavedRuntimeV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val trains: List<SavedTrainV1>
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`。

- infrastructure current `formatVersion=2`；runtime current `formatVersion=1`。
- `infrastructureRevision>=0`仅配对同批documents；`physicsEpoch>=0`仅记录capture publication时序，均非schema version。
- `SavedRailV1/SavedTrainV1`是outer文档nested DTO，不自动独立升版。
- runtime可缺失；存在时version exact current且revision等于infrastructure。

`WorldSnapshotCodec`固定 `ignoreUnknownKeys=false`、`encodeDefaults=true`。writer只写current。

## Decode、prevalidation、materialization

```text
bytes/text → read boundary version → optional pure upcast（仅任务明确支持）
→ strict current DTO → all identity/reference/business prevalidation
→ create fresh world → load all disconnected rails → restore connections
→ load trains with identity context → attach ephemeral runtime separately
```

| 条件 | 结果 | target state |
| --- | --- | --- |
| unsupported/future version | fail-fast含version | 不materialize |
| runtime/infrastructure revision mismatch | fail-fast | 不materialize |
| runtime absent | infrastructure-only world | 无trains |
| train location missing rail/invalid placement | `CorruptedDataException`并包装train name/id | failed train side effects清理 |
| duplicate train ID/name/namespace | fail-fast | fresh/unchanged |
| additive default field | current reader读旧fixture，不升boundary version | writer输出default |
| breaking shape/unit/meaning | 升最小boundary；decode边界单向upcast | business只见current |
| unknown extension type/version | stable registry fail-fast | 禁止反射/忽略 |

Rail connection orphan的遗留容错不得扩展到新引用；目标contract是prevalidation显式分类并由migration/strict policy决定。`physicsEpoch`不能被解释为restart后coordinator sequence自动续接。

## 项目内正确示例

codec在decode后再次验证document version：

```kotlin
json.decodeFromString(CoreWorld.SavedRuntimeV1.serializer(), data).also {
    require(it.formatVersion == CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION) {
        "Unsupported core runtime snapshot version: ${it.formatVersion}"
    }
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt`。它确保future writer不会被current business logic误读。

## 禁止实现

- 用revision/epoch代替schemaVersion；
- 每个nested DTO机械V2；
- 为通过旧fixture全局`ignoreUnknownKeys=true`；
- business层分支处理V1/V2；
- reader按存档FQCN `Class.forName`；
- decode过程中启动DI/executor或留下partial train/world。

## 必需测试与验证

- fixed current/future/corrupt/unknown-field JSON，不只writer→reader round-trip。
- additive field：fixed old fixture、default、round-trip、version不变。
- breaking migration（仅明确支持）：每级pure upcaster、missing step/future fail、writer current-only。
- `WorldPersistenceRoundTripTest.kt`：version/invalid JSON/train identity。
- `CoreTrainReversedPersistenceTest.kt`：AB/BA exact placement与failed load cleanup。
- `SplitWorldSnapshotTest.kt`：revision pairing与完整resource close。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.persistence.*' \
  --tests '*CoreTrainReversedPersistenceTest' --tests '*RailLocationPersistTest'
```
