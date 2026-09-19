# Core curve geometry 契约

## 适用场景与用途

触发：修改 `Curve`、`PrimeCurve`、`PrimeCurveImpl`、reverse、`splitAt/concatenate`，或在持久化/route边界换算弧长。

用途：把所有轨道几何固定到 canonical AB、米制弧长和明确的 direction conversion，避免 BA 值落盘、endpoint漂移或用测试生成器代替 production authoring。

## Owner 与精确类型

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

## 校验与结果矩阵

| 输入/操作 | 必须结果 | failure |
| --- | --- | --- |
| `getPointAt(s)` | position/tangent/normal属于同一curve，`arcLength=s` | 越界/非法输入明确失败，不clamp猜测 |
| `reversed.getPointAt(s)` | 对应AB `total-s`，tangent反向 | endpoint/tangent不连续为测试失败 |
| `splitAt(s)` | 两段长度和、切点position/frame连续 | 非finite/越界拒绝 |
| `a.concatenate(b)` | only contiguous sampled curves；保留总长与endpoint | 不连续拒绝，不自动geometry solve |
| persistence | 保存AB arc + `ViewDirection` | 不保存裸BA view-local arc |
| nearest query | worker执行并做exact geometry check | main thread/hot tick禁止 |

Raw points进入Core前必须由authoring owner验证非空、finite、arc严格单调、tangent/normal可用；任何caller都不得绕过该输入边界。

## 项目内正确示例

`RailPositionSnapshot.capture` 在唯一边界统一取AB弧长：

```kotlin
RailPositionSnapshot(
    railId = location.rail.id,
    facing = facing,
    arcLengthInAbMeters = location.arcLengthIn(ViewDirection.AB)
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailPositionSnapshot.kt`。该模式让方向和canonical坐标分离，reverse后仍能无歧义恢复物理点。

## 禁止实现

```text
错误：persist(viewArc)；BA读取时假定它就是AB
正确：persist(location.arcLengthIn(AB), facing)；边界处一次转换
```

禁止仅比较sample count验证curve、在per-train Tick调用`stream()`、或用`PrimeCurveGenerator`构造production rail。

## 必需测试与验证

- `PrimeCurveTest.kt`：full-length interpolation、reversed arc/tangent、input copy。
- `PrimeCurveSurgeryTest.kt`：split+concatenate endpoint、frame、总长。
- `RailLocationPersistTest.kt`：AB/BA round-trip与tangent。
- 新raw-input入口：empty/nonfinite/non-monotonic/non-orthogonal failure tests。

```bash
./gradlew :Core:test --tests '*PrimeCurveTest' --tests '*PrimeCurveSurgeryTest' --tests '*RailLocationPersistTest'
```
