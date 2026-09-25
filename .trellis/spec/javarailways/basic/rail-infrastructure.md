# Basic rail infrastructure 契约

## 适用场景与用途

触发：修改rail authoring/operating metadata、split/merge/replacement、显式endpoint连接、turnout topology或Core topology synchronization。

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
- `snapshotWithRevision()`在同一把lock内复制完整metadata并读取store-local runtime revision。revision只在
  `put/remove/replace`产生实际内容变化后递增；missing remove、等值put/replace和失败不递增。fresh restore建立
  revision 0基线。该值不是Core topology revision、physics epoch或持久化schema version；当前SimpleATO仅把它
  用作运行方向overlay的保守失效键，因此其他metadata变化也允许触发重新选路。

## Topology transaction矩阵

| structural operation | metadata结果 | 其他owner |
| --- | --- | --- |
| split | parent lineage、outer grade/turnout、assembly lane/role映射到children | Destination/WayPoint按arc迁移 |
| selected lane detach | parallel group dissolve/detach | Core connections同步 |
| merge | 仅same lineage且metadata equivalent | 重建assembly；destination arcs合并 |
| reversed replacement | endpoint metadata/grade lock物理方向转换 | approach direction也转换 |
| delete | remove metadata | operational destinations先处理 |
| failure任一步 | rollback Core topology + metadata + projections | 不留partial |

Basic不拥有按距离搜索并自动连接轨道的策略。RailWand把玩家选中的endpoint、批内明确相邻段及replacement旧→新endpoint映射
交给Core的`PreparedRailConnectionPlan`；Basic只将计划公布的准确既有endpoint/physical identity转换为edit impact。
道岔几何维护继续由RailWand在编辑前验证边界frame、占用与route lock；不得通过附近endpoint搜索猜测第三条支路。

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
| 显式endpoint不存在、不重合、非对称或容量超限 | plan capture失败，无mutation |
| topology root、rail实例或endpoint state过期 | apply前拒绝，不重新选择 |
| replacement成功 | 旧connection顺序与active branch通过显式映射保留 |

## 禁止实现

- 只改Core topology或只迁Basic metadata；
- Bukkit RailWand按距离搜索附近endpoint或推断turnout stem/branch；
- 直接mutable Core topology绕过barrier/transaction；
- merge unrelated lineage或不同metadata；
- speed/safety值塞回authoring一层；
- additive default字段机械升版。

## 必需测试与验证

- `RailInfrastructureMetadataTopologyTest.kt`：split/merge/lineage/assembly/grade/turnout/additive old JSON。
- `PreparedRailConnectionPlanTest.kt`：显式选择、standalone断开、stale拒绝、deterministic apply。
- `TurnoutTopologyTest.kt`与RailWand geometry policy tests：显式fixture、occupancy/allocation、closure与active preservation。
- 失败test断言Core links、metadata snapshot与Destinations全不变。

```bash
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.rail.*'
./gradlew :BukkitPlugin:test --tests '*RailWand*'
```
