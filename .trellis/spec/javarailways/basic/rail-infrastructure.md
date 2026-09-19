# Basic rail infrastructure 契约

## 适用场景与用途

触发：修改rail authoring/operating metadata、split/merge/replacement、auto-connect、turnout topology或Core topology synchronization。

用途：Basic metadata与Core physical topology必须在同一上层transaction一致迁移；metadata区分authoring、operating、speed与safety，不让adapter复制turnout policy。

## Owner 与精确metadata

`RailInfrastructureMetadata`按rail UUID存于world-local `RailInfrastructureMetadataStore`：

```kotlin
data class RailInfrastructureMetadata(
    val authoring: RailAuthoringMetadata? = null,
    val operating: RailOperatingMetadata = RailOperatingMetadata(),
    val speedLimits: SpeedLimitMetadata = SpeedLimitMetadata(),
    val curveSafety: CurveSafetyMetadata = CurveSafetyMetadata()
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/rail/RailInfrastructureMetadataStore.kt`。

- authoring：design speed m/s、geometry、assembly、split lineage、A/B grade locks、automatic curve speed policy。
- operating：optional `allowedDirection`、A/B turnout endpoint metadata。
- speed与derail归[speed safety](./speed-and-derail-safety.md)。
- assembly role token：`LEADING_TANGENT/CURVE/TRAILING_TANGENT`。
- `RailSplitMetadata(operationId,segmentIndex 0..1,parent?,sourceAssemblyRole?)`；`sourceAssemblyRole=null`是additive default，不升boundary version。
- store `replace(removals,additions)`在一个lock内；`snapshot()`按UUID stable sort；`restore()`只fresh。

## Topology transaction矩阵

| structural operation | metadata结果 | 其他owner |
| --- | --- | --- |
| split | parent lineage、outer grade/turnout、assembly lane/role映射到children | Destination/WayPoint按arc迁移 |
| selected lane detach | parallel group dissolve/detach | Core connections同步 |
| merge | 仅same lineage且metadata equivalent | 重建assembly；destination arcs合并 |
| reversed replacement | endpoint metadata/grade lock物理方向转换 | approach direction也转换 |
| delete | remove metadata | operational destinations先处理 |
| failure任一步 | rollback Core topology + metadata + projections | 不留partial |

`RailAutoConnector.connectIfAllowed(first:CoreRail,second:CoreRail,options): Result`在transaction外先awaitphysics，要求same world；检查endpoint distance/tangent、full-body occupancy、active route allocation、最多3 endpoints、turnout stem唯一、locking leg length、node grade continuity。Result包含`Outcome`及distance/angle/required/available/rail/grade context。

## 项目内正确示例

Metadata setter同时materialize ephemeral speed transition：

```kotlin
var Rail.infrastructureMetadata: RailInfrastructureMetadata
    get() = world.basicWorldData.railMetadata.getOrDefault(id)
    set(value) {
        world.basicWorldData.railMetadata.put(id, value)
        (this as? CoreRail)?.let(OperationalSpeedLimitSignalMaterializer::materializeRail)
    }
```

来源：`RailInfrastructureMetadataStore.kt`。它保证metadata change与runtime signal不会长期漂移；持久化仍只写metadata。

## Failure矩阵

| condition | result/state |
| --- | --- |
| rails different world | `require`失败，无mutation |
| occupied/active allocation | fail-fast，无connection change |
| endpoint too far/angle sharp/ambiguous turnout | typed `Outcome`，无partial |
| locking leg too short/grade discontinuity | Result含required/actual context，无partial |
| successful three-leg turnout | closure完整，active branch尽量preserve |

## 禁止实现

- 只改Core topology或只迁Basic metadata；
- Bukkit RailWand复制distance/turnout/grade/allocation检查；
- 直接mutable Core topology绕过barrier/transaction；
- merge unrelated lineage或不同metadata；
- speed/safety值塞回authoring一层；
- additive default字段机械升版。

## 必需测试与验证

- `RailInfrastructureMetadataTopologyTest.kt`：split/merge/lineage/assembly/grade/turnout/additive old JSON。
- `RailCurvePartitionTopologyTest.kt`：logical parts与Core connections。
- `RailAutoConnectorTest.kt`/`TurnoutTopologyTest.kt`：所有Outcome、occupancy/allocation、closure、active preservation。
- 失败test断言Core links、metadata snapshot与Destinations全不变。

```bash
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.rail.*'
./gradlew :BukkitPlugin:test --tests '*RailWand*'
```
