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

`build(from,to,options): RailCurveBuildResult` 与 `buildParallel(...): RailParallelCurveBuildResult` 都是小型 sealed 结果：
`Success` 持有非空 `curve`/`curves`、非空 `selectedTemplate`、可空 `selectedReason` 与break fractions；`Failure` 持有非空`error`、
可选template/reason。两种结果不再用独立nullable字段表达成功/失败，也不提供公开构造/copy到达矛盾状态；消费端用
exhaustive `when` 而不是`!!`或`listOfNotNull`兜底。`isSuccess`/nullable`curve`访问器只用于兼容既有测试读取。
两入口都`ThreadGuard.ensureOffMainThread`；`@Heavy`不调度。

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

### 最终弧长与工程分界

`CURRENT`（2026-10-15，CR-040）：几何生成/测量与必要的RailWand消费使用下述一致的距离/分界规则，不改Core查询、授权或配置UI。水平求解器中的station用于计算纵断面，不能直接当作有坡度轨道的最终三维米制弧长。输出必须从零开始、有限且严格递增，保留原XYZ/frame和已有接头去重规则；平面曲线不能因改参数化无故损失原解析长度。

```text
水平求解/采样及工程接点 -> 应用高度/偏移得到最终曲线
  -> 同步源位置到最终弧长的对应关系 -> 按该lane的真实分界切分
  -> 从最终几何测量水平半径/坡度等 -> 原有准备/编辑事务
```

工程分界必须随生成、平行偏移与纵断面重建逐次映射，不得将中心线旧比例直接乘以改变后的总长度。比如直线10米、圆弧20米、直线10米，偏移仅令圆弧变为30米，则第一分界仍应在10米，而不是旧比例25%乘新总长50米得到12.5米。应复用采样的源/目标对应关系，不按空间最近点猜分界，不新建运行期轨道映射索引。

实现表示：单曲线`logicalBreakFractions`对应自己的最终弧长，平行构建结果使用`logicalBreakFractionsByLane: List<List<Double>>`，与输出curves逐项对应，不能再保存一份所有lane共享的比例。RailWand纵断面重建后，在切分前将该lane的分界再次映射到rebuilt曲线；不把临时映射写入世界存档。

`RailCurvePartitioner.withElevationArc`在原水平样本上累积`hypot(Δstation, Δy)`，仅替换弧长，保留XYZ与frame。常坡结果为`L_horizontal * sqrt(1 + grade²)`，平面保留原水平解析桩距，不改用曲线采样弦长近似。变坡按既有采样段线性高差近似，精度随已有采样步长决定，不宣称解析积分精确。

`horizontalStations`逐相邻样本以`sqrt(max(0, (ds-|dy|)*(ds+|dy|)))`累计水平行程，避免对多段变坡一次整体投影。要求ds>0，且高差不得显著大于物理弧长；只对`max(1e-10, ds*1e-10)`范围的浮点舍入允许负根号量归零。它假设样本间高差线性，零水平行程的段不贡献水平曲率。FIVE_SEGMENT将原求解接点映射到最终弧长；测量用水平station差求水平转角曲率。

`mapStations(source, target, stations)`要求两组样本同数量、从零开始、有限且严格递增；分界坐标须非负有限，上界只允许1e-8米的累计舍入误差并裁到末点，其他越界拒绝。二分查找源坐标所在样本段，在对应目标段线性插值，不跨段拟合。`remapBreakFractions(sourceCurve,targetCurve,fractions)`只适用于offset/rebuild这些保持源stream样本顺序和数量的生产者，不适用于任意两条曲线；LINE的stream可能包含虚拟采样，必须由生产者保持对应关系。映射为空直接返回空，不为无工程分界曲线额外采样。

既有生成器省略不足0.01米的前后切线；该规则由`ConstraintSolver.generatedStraightLength`集中解释，生成与映射都消费实际生成长度。接点取“实际leading长度”和“实际水平总长减实际trailing长度”，不能用含已省略小段的理论总长定位，也不能靠放大映射越界容差吞掉差值。本批保留既有省略与XYZ行为，不新增不足容差的CoreRail。

水平半径使用水平单位切线的转角与对应水平行程，不用三维弧长作分母；同一水平圆弧改变坡度，不应改变显示的水平半径。具体速度/脱轨阈值的三维动力学含义不能仅凭此几何修正推导，本批不擅改其物理模型。

新增工作只在已有worker生成/重建/测量阶段，不增加每tick重算。单条N个采样、B个分界的转换/投影为O(N)，映射为O(N+B log N)，暂存O(N+B)；L条并行lane总体随各lane采样数之和增长，不宣称常数成本。非法对应样本或不物理的ds/dy明确拒绝，失败沿用原build/重建准备异常路径，不改世界或发布部分结果。回归须以解析常坡长度、独立水平半径及实际切线/曲线接点为预期，覆盖平面、坡道FIVE_SEGMENT/S_CURVE、偏移lane和非均匀坡度重建，不能只断言fraction排序或沿用同一helper计算expected。

无存档字段或版本变化，不自动重新解释/重写旧世界曲线；旧数据是否需要一次性离线修复另按实际迁移输入处理。离线门禁与旧码红灯证据见审查任务记录，不代表旧存档已修复或真实Folia/客户端验收。

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
