# Basic rail infrastructure 契约

## 适用场景与用途

触发：修改rail authoring/operating metadata、split/merge/replacement、显式endpoint连接、turnout topology或Core topology synchronization。

用途：Basic metadata与Core physical topology必须在同一上层transaction一致迁移；metadata区分authoring、turnout endpoint、speed与safety，不让adapter复制物理策略。公共运行方向由 Basic 的独立稀疏存储保存，不塞入本 metadata store，见[方向契约](./operating-directions.md)。

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

业务代码只使用上述分组模型，不保留平铺constructor/getter/custom copy或`RailMetadata`别名。
更新某一组时使用嵌套`copy`并保留其余组，例如只修改authoring不能把speedLimits重新折算为单一有效速度，
否则会丢失限速类别和原始数值。authoring缺失仍用null表达；读取旧geometry默认值的调用方显式提供默认值。
既有一次性迁移在自身decode边界将旧设计速度转换为SUBGRADE；有效限速在无设计速度或低于设计速度时转换为CURVE，
业务模型不提供旧形状转换入口。序列化仍是相同四组字段，本批不改变schema/version或新增兼容窗口。

- authoring：design speed m/s、geometry、assembly、split lineage、A/B grade locks、automatic curve speed policy。
- operating：只保存A/B turnout endpoint metadata；不得保存运行方向。
- speed与derail归[speed safety](./speed-and-derail-safety.md)。
- assembly role token：`LEADING_TANGENT/CURVE/TRAILING_TANGENT`。
- `RailSplitMetadata(operationId,segmentIndex 0..1,parent?,sourceAssemblyRole?)`；`sourceAssemblyRole=null`是additive default，不升boundary version。
- store `replace(removals,additions)`在一个lock内；`snapshot()`按UUID stable sort；`restore()`只fresh。

## Topology transaction矩阵

| structural operation | metadata结果 | 其他owner |
| --- | --- | --- |
| split | parent lineage、outer grade/turnout、assembly lane/role映射到children | Destination/WayPoint按arc迁移；Basic公共方向一并迁移 |
| selected lane detach | parallel group dissolve/detach | Core connections同步 |
| merge | 仅same lineage且metadata equivalent | 重建assembly；destination arcs合并 |
| reversed replacement | endpoint metadata/grade lock物理方向转换 | Basic公共方向和SimpleATO停车点接近方向分别转换 |
| delete | remove metadata | operational destinations和Basic公共方向先处理 |
| failure任一步 | rollback Core topology + metadata + projections | 不留partial |

Basic不拥有按距离搜索并自动连接轨道的策略。RailWand把玩家选中的endpoint、批内明确相邻段及replacement旧→新endpoint映射
交给Core的`PreparedRailConnectionPlan`；Basic只将计划公布的准确既有endpoint/physical identity转换为edit impact。
道岔几何维护继续由RailWand在编辑前验证边界frame、占用与route lock；不得通过附近endpoint搜索猜测第三条支路。

## 手动选岔的执行边界（CURRENT，第5批）

`ManualTurnoutSelectionRuntime.select(requester, request)`是同步Basic apply，不负责排队。
由composition注入`ensureBetweenFrames`及`publishSelection`；先确认阶段，再在同一作用域内完成版本/端口校验、
临时资源claim、物理锁闭区间检查、双侧选择与finally释放。不能把“等待当前ticket”注入当成完整排他。
拒绝过期拓扑、非法端口、资源冲突或物理占用时不改变选择；成功变化后发布当前共享观测，不产生route grant。
Basic保持既有锁闭区间算法与成本，不直接依赖Core实现或引入另一套仲裁；Bukkit负责异步请求及真实阶段进入。
回归必须验证拒绝后选择/claim不变、成功后短暂claim释放、阶段外调用被拒绝，以及真实在途worker完成前不扳道。

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
