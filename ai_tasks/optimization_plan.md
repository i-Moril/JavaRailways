# 物理计算优化方案

## P0: 转向架配对 — 全量遍历 → 二分搜索

**现状**：`findTailBogieByHead` / `findHeadBogieByTail` 对搜索范围内每个采样点做全量遍历（O(n)）。
**优化**：距离误差函数在短区间内单调，可用二分搜索替代（O(log n)）。
**收益**：4 节列车从 ~133K 降到 ~150 次距离检查/tick。

```kotlin
// CoreCar.kt:174-217 — 全量遍历替换为二分搜索
private fun findTailBogieByHead(headBogieNewLocation: RailLocationWithFacing): RailLocationWithFacing {
    val railSegments = headBogieNewLocation.peekCurve(-2 * bogieDistance)
    return railSegments.searchRailLocation(bogiePairingTolerance()) { candidate ->
        distanceError(connectorPosition(headBogieNewLocation), candidate)
    }.reversed ?: headBogieNewLocation.forward(-bogieDistance) ?: throw BogieException(tailBogie)
}
```

## P1: 自适应采样 + 线段插值

**现状**：所有曲线统一用固定步长采样（如 5mm），直线段浪费大量点。
**优化**：`RawPoint` 增加 `segmentType` 字段，直线段只存端点，通过线性插值取任意弧长位置。

```kotlin
enum class SegmentType { DISCRETE, LINE_START, LINE_END }

data class RawPoint(
    val position: Vector3dc,
    val tangent: Vector3dc,
    val normal: Vector3dc,
    val arcLength: Double,
    val segmentType: SegmentType = SegmentType.DISCRETE
)
```

- 200m 直线只需 **2 个点**（LINE_START + LINE_END），而非 40,000 个
- `getPointAt(arcLength)`：LINE_START 到 LINE_END 之间用线性插值（position）/ 常量（tangent/normal）
- **向后兼容**：默认 DISCRETE 保持现有行为
- 后续可扩展 `ARC_START/ARC_END`（存 center + radius + startAngle）

## P2: 弧长查询缓存

**现状**：`getIdxAt(arcLength)` 做二分搜索，同一弧长被连续多 tick 重复查询。
**优化**：LRU 缓存（大小 ~16）避免重复二分。

## P3: 对象池化

**现状**：`getRailLocationAt()` 每调用 new `RailLocationWithFacing`，`forward()` / `reversed` 链创建更多。
**优化**：per-thread 对象池回收复用，消除 GC 压力。

## P4: 渲染 Dirty Flag

**现状**：`updateModel()` 每 tick 遍历整个 `CompositeModel` 树设 transform。
**优化**：车身/overlay 等不动的部件仅在 yaw 变化时更新；车轮每 tick 更新（旋转动画）。

## P5: 物理计算频率降采样

**现状**：每 tick（20Hz）都做完整物理计算。
**优化**：直线匀速段可降至 5Hz，仅在加速/制动/曲线/道岔时全频。

| 场景 | 频率 |
|------|------|
| 直线匀速 | 5Hz |
| 曲线 | 20Hz |
| 加速/制动 | 20Hz |
