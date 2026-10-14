# Core render publication 契约

## 适用场景与用途

触发：增加train/car/bogie render字段、生成`TrainFrameSnapshot`、修改Core mailbox或Bukkit frame consumer。

用途：physics只发布完整value frame；平台按revision latest-wins消费，不补读live train，避免mixed epoch、stale region task和slow renderer反压physics。

## Owner 与精确frame

Core拥有value capture/publication；platform adapter拥有entity/viewer/location scheduler。跨线程frame：

```kotlin
data class TrainFrameSnapshot private constructor(
    val trainId: String,
    val trainName: String,
    val revision: Long,
    val physicsEpoch: Long,
    val speedMetersPerSecond: Double,
    val derailed: Boolean,
    val reversed: Boolean,
    val cars: List<CarFrameSnapshot>,
    val head: RailPositionSnapshot,
    val tail: RailPositionSnapshot,
    val occupiedIntervals: List<RailInterval>,
    val bodyTraversal: List<RailTraversalSegmentSnapshot>,
    val sweptSpans: List<SweptRailSpanSnapshot>,
    val headTraversal: List<RailTraversalSegmentSnapshot>,
    val tailTraversal: List<RailTraversalSegmentSnapshot>
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainFrameSnapshot.kt`。

公开列车读取入口为 `Train.publishedFrame: TrainFrameSnapshot?`。CoreTrain 返回其已经原子发布的完整帧，
其他不能提供完整帧的实现返回 null；读取为常数时间，不调用曲线、车身求解或渲染操作。
消费方一次捕获该帧并校验 train ID、physics epoch；不得分次读取“最新车头”和“最新车身”。
例如 SimpleATO 的入口净空检查沿 `bodyTraversal` 累加当前保守车身长度，保留同一 rail 的多次 occurrence，
再与当前控制感知核对；缺帧/空车身不能作为长度为零放行，也不能用 cars.size 或车厢长度之和替代。

`revision` per-train monotonic、`>=0`；`physicsEpoch>=0`（initial为0）；speed finite且`>=0 m/s`；cars/bogies/spans由`List.copyOf`。`RenderableFrameSnapshot`内联position/direction/normal/velocity共12个finite `Double`。

`occupiedIntervals`是epoch末保守占用；`bodyTraversal`是同一时点从保守车尾到车头的有序持续位置，供
tail-started route装配，不能从无序interval反推。仅尚未对齐、随后将立即relocate的construction initial
frame允许为空；该frame不可用于自动准入，第一次正常commit严格生成完整路径。`sweptSpans`是按rail合并的direction-independent
整epoch占用包络；不能用它恢复边界通过顺序。
`headTraversal`与`tailTraversal`只描述该physics epoch内两端实际向前经过的有向rail片段，按经过
occurrence保序且不按rail ID合并。每段保存stable rail UUID、`ViewDirection`及canonical AB米制
起止弧长；AB数值不减，BA数值不增。静止、reverse-only或maintenance relocation的epoch为空。
所有collection均由frame defensive copy。

`RenderableStateSnapshot(Vector3dc..., World)` 是live legacy state，不得跨线程。

## Publication/consumer矩阵

| 条件 | Core结果 | platform结果 |
| --- | --- | --- |
| 完整epoch flip | `publishFrame(epoch)`生成new revision | await exact ticket后drain |
| 同train新revision且old pending | mailbox替换，`COALESCED` | 只apply新frame |
| stale/equal revision | `DROPPED_STALE` | 无side effect |
| capacity满且新train | 淘汰最老train frame并计metric | 不阻塞publisher |
| stationary unchanged | coordinator不逐epochoffer | 保持last applied state |
| 帧间rename | 只更新live名称及索引，下一正常epoch发布frame并offer（即使静止） | revision guard apply |
| ticket failure | 可能已有partial flip | consumer不得drain/apply为成功epoch |

movement轨迹构造复用列车物理解算已经生成的head/tail有序`RailSegment`；body placement沿已提交
train-local path从车尾走到车头一次。设两端本epoch分别经过`H`、`T`个rail occurrence，车体跨越`B`个
occurrence，附加时间与空间均为`O(H + T + B)`。不得从
`sweptSpans`、最终head/tail位置或world topology重新寻路；这些替代做法会丢失重复rail occurrence、
方向或实际选择的turnout branch。任何正长度segment都必须保留，不能套用geometry overlap tolerance，
否则很短的移动仍可能跨过边界却没有证据。

`CoreTrain.commit()`只flip Core pending state并生成frame，不调用Bukkit extension，也不自动offerworld mailbox。generic `TickManager.postTick`同样不等于platform commit。

重命名的阶段入口由[runtime publication](./runtime-publication.md#非物理操作统一在帧间执行target2026-10-14用户确认)定义：名称操作完成后，旧immutable frame仍保存旧名称，直到下一物理epoch正常发布。不得为立即刷新名称另行调用`publishMetadataFrame/commit/publishCurrentTrainFrame`替换碰撞依赖的物理观测。coordinator用上一frame名称与当前名称的差异决定offer，无需额外dirty字段。

## 项目内正确示例

mailbox拒绝旧revision：

```kotlin
val current = pending[frame.trainId]
if (current != null && current.revision >= frame.revision) {
    droppedStaleFrames++
    return OfferResult.DROPPED_STALE
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/tick/LatestTrainFrameMailbox.kt`。它保证delayed worker/platform task不能覆盖较新视觉状态。

## 禁止实现

- 将`RenderableStateSnapshot`、JOML live refs或World放进跨线程队列；
- frame缺字段时platform再读live train/car；
- 把有序traversal按rail ID合并、排序，或从`sweptSpans`反推；
- physics worker创建entity、选viewer或调用region scheduler；
- slow renderer形成每Tick无界queue；
- 不检查revision就apply location task；
- 把任意`commit()`解释成自动render。

## 必需测试与验证

- `LatestTrainFrameMailboxTest.kt`：defensive copy、stale、coalesce、capacity、metrics、concurrent highest revision。
- `PhysicsCoordinatorTest.kt`：frame epoch/revision、stationary suppression、terminal frame、slow consumer。
- `CoreRenderCommitDecouplingTest.kt`：car/bogie不进generic mailbox，Core commit无platform side effect。
- Bukkit frame tests：stable ID mapping、region ownership、revision guard。

```bash
./gradlew :Core:test --tests '*LatestTrainFrameMailboxTest' --tests '*PhysicsCoordinatorTest' \
  --tests '*CoreRenderCommitDecouplingTest' --tests '*ImmutableSnapshotTest'
./gradlew :BukkitPlugin:test --tests '*Frame*' --tests '*Render*'
```
