# Basic curve authoring 契约

## 适用场景与用途

触发：修改Anchor、curve template、horizontal/vertical solver、superelevation、parallel curve、logical partition或geometry measurement。

用途：heavy geometry在worker从platform-independent values构建，endpoint/frame与engineering constraints全部验证后，main transaction才提交；stable template token与SI单位不得漂移。

## Owner、输入、token与单位

`Anchor(id,position:Vector3dc,tangent:Vector3dc,worldId:String?,metadata:Map<String,String>)`是value input；roll metadata key固定`rollDegrees`。geometry默认米、m/s²、grade rise/run ratio；只有字段名明确`*KmH`时用km/h。

Template token：`AUTO/STRAIGHT/TWO_POINT_STRAIGHT/FIVE_SEGMENT/S_CURVE`，同时进入authoring metadata/persistence，rename是breaking change。

```kotlin
data class RailCurveBuildOptions(
    val template: RailCurveTemplateType = RailCurveTemplateType.AUTO,
    val radius: Double = 0.0,
    val superelevation: SuperelevationConfig? = null,
    val geometry: RailGeometryParameters = RailGeometryParameters()
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/curve/RailCurveFactory.kt`。

`build(from,to,options): RailCurveBuildResult(curve?,error?,selectedTemplate?,selectedReason?,logicalBreakFractions)`；`isSuccess` iff curve nonnull。`buildParallel`返回同形态多个curves。两入口都`ThreadGuard.ensureOffMainThread`；`@Heavy`不调度。

## Classification/validation矩阵

| 条件 | result |
| --- | --- |
| AUTO horizontal same-direction且chord aligned | STRAIGHT |
| AUTO same-direction有lateral offset | S_CURVE |
| AUTO tangent angle存在 | FIVE_SEGMENT |
| manual S_CURVE radius | exact target；不能拟合→failure |
| invalid tangent/distance/nonfinite | failure，curve null |
| straight | horizontal plan在start tangent line；vertical独立满足两端elevation/pitch |
| any success | endpoint position/tangent/normal/roll validated |
| parallel success | 每lane endpoint match与tangent continuity |
| five segment | 返回curve-region two break fractions |

`RailCurvePartitioner.partitionFiveSegment(curve,fractions)`只产`LEADING_TANGENT/CURVE/TRAILING_TANGENT`；endpoint boundary可省略tangent，sub-`Curve.TOLERANCE` part合并。

Final `RailGeometryMeasurementService.measure(curves,gaugeMeters)`重测minimum horizontal radius m、max superelevation m、max absolute grade；authoring input不能代替final measurement。

`ConstraintSolver.generateCurve`按straightA → curve → straightB顺序拼接采样；前段已经输出共享接头时，后段跳过自己的第一个点，避免同一arc出现两次。不是全量按位置去重：相同位置不同递增arc仍是合法Core输入。Core构造/load统一验证零起点、finite严格递增arc及可用frame；LINE `stream()`也只输出一次共享端点，因此reverse/vertical rebuild/parallel offset可以直接消费，不在decoder修复producer错误。几何生成/校验留在worker，查询热路径不重复扫描。

## 项目内正确示例

入口硬性拒绝main thread：

```kotlin
fun build(from: Anchor, to: Anchor, options: RailCurveBuildOptions): RailCurveBuildResult {
    ThreadGuard.ensureOffMainThread("RailCurveFactory.build")
    val prepared = prepareAnchors(from, to)
        ?: return RailCurveBuildResult.failure("端点切线或距离无效")
    // classify and solve
}
```

来源：`RailCurveFactory.kt`。这避免Bukkit/Paper main thread被solver和sampling阻塞。

## 禁止实现

- main thread直接build/measure；
- worker读取Bukkit world/entity/render mutable state；
- 用sample count代替endpoint/frame/radius/roll validation；
- 把exact radius解释为minimum；
- rename template token或混淆m/s与km/h；
- input radius/cant代替final curve measurement。

## 必需测试与验证

- `RailCurveFactoryTemplateTest.kt`：classification、manual templates、invalid、main-thread fail、endpoint/tangent/roll、left/right/reverse。
- `ConstraintSolverTest.kt`、`StandardRailCurveTest.kt`：radius/clothoid/cant gradient，以及straight-curve-straight共享接头只出现一次、arc严格递增且首尾几何保留。
- `VerticalProfileSmootherTest.kt`/`VerticalCurveRebuilderTest.kt`：hard constraints与3D arc。
- `ParallelAnchorGeometryTest.kt`：matching/continuity。
- `RailCurvePartitionerTest.kt`、`RailGeometryMeasurementServiceTest.kt`：roles/tolerance/final values。

```bash
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.curve.*'
```
