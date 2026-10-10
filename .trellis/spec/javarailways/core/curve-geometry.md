# Core curve geometry 契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`TRANSITIONAL`；二分与插值算法是当前行为，但`PrimeCurve.fromPoints`仍信任caller，尚未在Core入口集中执行本页要求的raw-point校验。
- **Owner**：Core拥有immutable curve、弧长位置查询与rail-segment零点搜索；Basic拥有production curve authoring。
- **Non-goals**：不定义Basic曲线求解器、玩家编辑UI或最近轨道的spatial broad phase。

触发：修改 `Curve`、`PrimeCurve`、`PrimeCurveImpl`、`RailSegments.searchRailLocation`、reverse、`splitAt/concatenate`，或在持久化/route边界换算弧长。

用途：把所有轨道几何固定到 canonical AB、米制弧长和明确的 direction conversion，避免 BA 值落盘、endpoint漂移或用测试生成器代替 production authoring。

## 2. 模块工作方式

```text
canonical AB arc length s
  -> locate the containing sampled interval by monotonic arc-length search
  -> interpolate position/tangent/normal inside that interval
  -> construct a Curve.Point that still references the same immutable curve
  -> RailView converts AB/BA only at the view boundary
```

需要寻找“某个沿线路连续条件等于零的位置”时，`RailSegments`按route segment顺序检查；只对端点异号或端点已经满足的segment执行二分，找到第一个满足tolerance的位置。它不是最近点搜索，也不保证发现没有端点异号的偶数次穿零。

## 3. 内部机制与关键算法

### 3.1 按弧长定位采样区间

算法要求`PrimeCurveImpl`的point list按`arcLength`严格递增；当前构造器依赖caller满足该前提。`findSegmentStartIndex(s)`使用upper-bound式二分，返回满足`point[i].arcLength <= s`的最大左端index，并限制到`lastIndex - 1`。因此查询复杂度为`O(log n)`，且必须选择“包含s的左侧区间”，不能改成最近采样点；最近点在两点直线后半段会选择终点，使插值区间退化为零。

`s == totalArcLength`单独返回最后一点。其他位置取得相邻`lo/hi`：

- `LINE_START -> LINE_END`使用精确线性位置插值，切线和法线保持该直线frame；
- `DISCRETE`区间线性插值position、tangent和normal，归一化tangent，再用Gram-Schmidt把normal投影回tangent的垂直平面并归一化；
- 区间长度小于`1e-12`时保守返回lo，正常输入的严格单调校验应使该分支只作为数值保护。

`ReversedCurve.getPointAt(s)`只把查询转换为原曲线`totalArcLength - s`并反向tangent；canonical持久化坐标始终是AB。

### 3.2 条件零点二分

`RailSegments.searchRailLocation`按segments顺序处理。每个segment先计算两端condition：任一绝对值小于tolerance就直接返回；同号表示没有可证明的穿零并跳过；异号时最多二分50次，每轮保留仍跨零的半区间，并记录目前绝对condition最小的candidate。外层再次验证candidate满足tolerance，否则返回null。

该算法依赖condition在segment上连续，并且目标根能由端点异号证明。若condition非单调，算法只承诺返回它收敛到的一个根；若函数接触零后返回同号，当前合同不会发现它，caller不能把这种情况伪装成可靠碰撞/安全证明。

### 3.3 几何最近点

`PrimeCurveImpl.getNearestPoint(position)`直接扫描存储的相邻piecewise-linear线段，不在`stream()`虚拟采样点中取最近点。每段计算投影比例`clamp(dot(query-start, end-start) / |end-start|², 0, 1)`，比较投影距离平方；几何零长度段按start处理，不除以零。只在距离更小时替换candidate，因此等距结果保留canonical AB顺序的第一项。最后把比例换算为该段弧长，调用一次既有`getPointAt(s)`，保持position/frame/curve身份与普通弧长查询一致。

`ReversedCurve`委托original求同一几何最近点，再以`total-s`取得反向view point；不能重新按反向虚拟采样量化。按position精确定位仍使用最近点与`Curve.TOLERANCE`判断接受/拒绝，`CurveException`保留诊断message，不隐藏失败原因。

## 4. 接口、数据结构与单位

`Rail.curve: PrimeCurve` 是 canonical immutable AB curve。`Curve.Point/RawPoint` 字段为：

```kotlin
data class RawPoint(
    val position: Vector3dc,
    val tangent: Vector3dc,
    val normal: Vector3dc,
    val arcLength: Double,
    val segmentType: SegmentType = SegmentType.DISCRETE
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/curve/Curve.kt`。

- position与`arcLength`单位米；`totalArcLength`单位米。
- `SegmentType` token：`DISCRETE`、`LINE_START`、`LINE_END`。直线必须成对起止，内部精确线性插值。
- `Curve.TOLERANCE=5e-3` m；`Curve.PRECISION=1e-3` m。
- `PrimeCurve.fromPoints(points)` 返回 `PrimeCurve`；`splitAt(arcLength): Pair<PrimeCurve,PrimeCurve>`；`concatenate(next): PrimeCurve`。
- `reversed` 只改变view参数方向；canonical persisted arc仍是 AB。
- `stream/getNearestPoint/getPointAt(position)` 带 `@Heavy`；hot path不得全量采样。

Production authoring由Basic curve factory拥有；Core `PrimeCurveGenerator`仅用于测试。

## 5. 不变量、生命周期、线程与版本

- `PrimeCurve`是immutable canonical AB value；reverse是view，不复制另一份权威几何。
- raw point输入必须非空、finite、arc严格单调，tangent/normal可用；构造后point order不得变化。
- curve位置、split/concatenate和persistence均使用米制弧长；BA只在读取view时转换。
- `getPointAt`可在高频路径使用，但`stream/getNearestPoint`标记`@Heavy`，不得用全量采样代替弧长索引。

## 6. 校验与错误矩阵

| 输入/操作 | 必须结果 | failure |
| --- | --- | --- |
| `getPointAt(s)` | position/tangent/normal属于同一curve，`arcLength=s` | 越界/非法输入明确失败，不clamp猜测 |
| `reversed.getPointAt(s)` | 对应AB `total-s`，tangent反向 | endpoint/tangent不连续为测试失败 |
| `splitAt(s)` | 两段长度和、切点position/frame连续 | 非finite/越界拒绝 |
| `a.concatenate(b)` | only contiguous sampled curves；保留总长与endpoint | 不连续拒绝，不自动geometry solve |
| persistence | 保存AB arc + `ViewDirection` | 不保存裸BA view-local arc |
| nearest query | worker执行并做exact geometry check | main thread/hot tick禁止 |

Raw points进入Core前必须由authoring owner验证非空、finite、arc严格单调、tangent/normal可用；任何caller都不得绕过该输入边界。

## 7. 性能、复杂度与退化行为

- 单次`PrimeCurveImpl.getPointAt`为`O(log n)`区间查找加`O(1)`插值；不得退化成线性扫描point list。
- `RailSegments.searchRailLocation`最坏对每个待查segment执行2次端点查询和50次中点查询，即`O(segmentCount * 50 * log n)`；找到第一个满足位置后立即结束。
- 单条curve最近点为`O(n)`真实线段投影加一次`O(log n)`弧长定位，额外空间`O(1)`；不按毫米加密采样或新建几何索引，仍是离开main/hot tick的`@Heavy`操作。
- `stream()`对直线按步长生成虚拟点、对离散段读取存储点，属于`@Heavy`；spatial/nearest任务必须离开main/hot tick并先用coarse index缩小范围。
- 数值保护或50次迭代后仍不满足tolerance返回null，不得返回一个未经tolerance验证的“近似成功”。

## 8. Good / Base / Bad cases

Good：`RailPositionSnapshot.capture` 在唯一边界统一取AB弧长：

```kotlin
RailPositionSnapshot(
    railId = location.rail.id,
    facing = facing,
    arcLengthInAbMeters = location.arcLengthIn(ViewDirection.AB)
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailPositionSnapshot.kt`。该模式让方向和canonical坐标分离，reverse后仍能无歧义恢复物理点。

Base：查询`totalArcLength`直接取得最后一点，不让二分产生越界右邻点。

Bad：

```text
错误：persist(viewArc)；BA读取时假定它就是AB
正确：persist(location.arcLengthIn(AB), facing)；边界处一次转换
```

禁止仅比较sample count验证curve、在per-train Tick调用`stream()`、或用`PrimeCurveGenerator`构造production rail。

## 9. 必需测试与验证

- `PrimeCurveTest.kt`：full-length interpolation、直线后半段仍选择左侧区间、reversed arc/tangent、input copy、normal/tangent正交。
- `PrimeCurveNearestPointTest.kt`：稀疏LINE非整数与离线投影、端点clamp、多段DISCRETE三维内部点、几何零长度段、position查询容差和异常message；reverse nearest必须使用真正稀疏fixture而非恰好命中已有离散样本。
- `PrimeCurveSurgeryTest.kt`：split+concatenate endpoint、frame、总长。
- `RailLocationPersistTest.kt`：AB/BA round-trip与tangent。
- `RailSegments` tests：端点命中、异号收敛、同号null、跨多个segment选择第一个、50次后仍须满足tolerance。
- 新raw-input入口：empty/nonfinite/non-monotonic/non-orthogonal failure tests。

```bash
./gradlew :Core:test --tests '*PrimeCurveTest' --tests '*PrimeCurveSurgeryTest' --tests '*RailLocationPersistTest'
```

## 10. 迁移、未决事项与Wrong/Correct

当前机制无需持久化数据迁移。实现工作需要先把raw-point校验收敛到不可绕过的Core构造边界；在此之前不得把“strictly increasing由Core保证”描述成已实现。未来可替换point-list索引或插值实现，但必须保持canonical AB、包含区间语义、复杂度上界和现有数值测试；若要支持“同号但接触零”的搜索，必须新增不同签名/算法及明确采样或导数前提，不能静默改变当前二分合同。

```text
错误：在采样点中找距离s最近的点并把它作为插值左端
正确：二分查找满足point[i].arcLength <= s的最大左端index

错误：端点condition同号仍声称区间内没有任何根
正确：当前算法只能声称“没有可由端点异号证明的穿零”；需要切触根时使用另一份明确算法
```
