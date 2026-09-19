# Core rail topology 与 occupancy 契约

## 适用场景与用途

触发：修改 `Rail/RailView` connection、active branch、split/merge/remove、spatial query、`SweptOccupancyIndex` 或 `RailIntervalReleaseDetector`。

用途：structural mutation必须等待physics并以world write transaction发布；占用与release使用direction-independent AB区间，避免只看head、读到半成品topology或过早释放Basic资源。面向寻路与跨模块消费的唯一immutable图、结构节点归一化和overlay边界由[版本化铁路拓扑](./versioned-operational-topology.md)定义。

## Owner、类型与 transaction

- `CoreWorld`拥有rail map、`RailSpatialIndex`、`ReentrantReadWriteLock`。
- `CoreRail`拥有canonical curve与AB/BA views；迁移期间`RailView.nextRails`是Core内部physical connection编辑模型，发布给consumer的authoritative连通结构是同一transaction产生的`TopologyState`；`nextActiveRail`是runtime selected branch，不进入structural state。
- Basic `allowedDirection`/route allocation不是Core physical topology。
- structural owner调用 `CoreWorld.railTopologyTransaction(action)`：先await current `PhysicsTicket`，再持write lock；expensive curve build在transaction外。
- outer structural transaction 释放 write lock 前只递增一次 `topologyRevision`；nested transaction 不重复递增。直接 add/remove rail 也递增一次。
- transaction exception不自动rollback，并保守递增 `topologyRevision`；上层必须在第一次mutation前capture undo并恢复Core + Basic metadata/destination。
- `removeRail`检查target及affected rails的full train placement envelope，修复links/spatial index后移除。
- `publishWorldFrame` 持 topology read lock 同时捕获 `topologyRevision` 与已发布列车 frames，因此不会观察到 structural transaction 的中间状态。

Canonical interval签名（`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailIntervalOccupancy.kt`）：

```kotlin
data class RailInterval(
    val railId: UUID,
    val minArcLengthInAbMeters: Double,
    val maxArcLengthInAbMeters: Double
)
```

字段finite且`min <= max`；overlap默认 tolerance `Curve.TOLERANCE`。

## Occupancy、release与结果矩阵

`SweptRailSpan(trainId,railId,minArcAB,maxArcAB)` 在完整epoch后形成immutable index；默认32 m bucket、query最多32 train candidates，exact overlap后返回 `SweptOccupancyQueryResult(collidingTrainId,checkedCandidates,candidateLimitReached)`。

| 条件 | 结果 | mutation |
| --- | --- | --- |
| topology edit有in-flight physics | await exact ticket后进write transaction | await前不改rail |
| nested structural transaction | 由 outer transaction 统一发布 | topology revision 只递增一次 |
| affected rail有body occupancy | fail-fast | rail/link/index不变 |
| action中途失败 | revision 仍递增，上层rollback到capture | 不依赖world自动rollback |
| world frame 与 topology edit 并发 | reader 等待 write transaction 完成 | frame 携带完成后的 revision，不暴露中间图 |
| release detector未见过占用 | `BEFORE_OCCUPANCY` | 不release |
| overlap出现 | `OCCUPIED` | 保留claim |
| 曾占用后完全分离 | `RELEASED`（terminal） | owner可release |
| occupancy query达candidate cap | `candidateLimitReached=true`并记录metric/log | 不伪装完整无碰撞 |

## 项目内正确示例

`RailIntervalReleaseDetector.update` 只在“先占用、后分离”时释放：

```kotlin
state = when {
    overlaps -> IntervalOccupancyState.OCCUPIED
    state == IntervalOccupancyState.OCCUPIED -> IntervalOccupancyState.RELEASED
    else -> state
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailIntervalOccupancy.kt`。它解决列车尚未进入保护区时被错误当作已清空的问题。

## 禁止实现

- consumer直接修改 `mutableNextRails/activeRail` 或无barrier调用`selectNextRail`；
- transaction内运行heavy curve solver；
- 失败后仅修Core map、不恢复Basic metadata/destination；
- occupancy只看head、direction或单个bogie；
- release时强制把turnout切回旧branch；
- `getNearestRail`在main/per-Tick调用；coarse index候选仍须exact check。

## 必需测试与验证

- `CoreWorldRailTopologyTransactionTest.kt`：明确latch/barrier验证reader不可见中间态（不新增sleep猜测）。
- `CoreTrainAsymmetricTurnoutTest.kt`：body occupancy、active branch fallback。
- `SweptOccupancyIndexTest.kt`：bucket、tolerance、candidate cap、stable nearest ordering。
- `RailIntervalReleaseDetectorTest.kt`：`BEFORE→OCCUPIED→RELEASED`且方向无关。
- split/merge tests断言rollback后rail map、links、index和Basic projection一致。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.rail.*' --tests '*CoreWorldRailTopologyTransactionTest'
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.rail.*'
```
