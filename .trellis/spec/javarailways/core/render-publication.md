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
    val sweptSpans: List<SweptRailSpanSnapshot>
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainFrameSnapshot.kt`。

`revision` per-train monotonic、`>=0`；`physicsEpoch>=0`（initial为0）；speed finite且`>=0 m/s`；cars/bogies/spans由`List.copyOf`。`RenderableFrameSnapshot`内联position/direction/normal/velocity共12个finite `Double`。

`RenderableStateSnapshot(Vector3dc..., World)` 是live legacy state，不得跨线程。

## Publication/consumer矩阵

| 条件 | Core结果 | platform结果 |
| --- | --- | --- |
| 完整epoch flip | `publishFrame(epoch)`生成new revision | await exact ticket后drain |
| 同train新revision且old pending | mailbox替换，`COALESCED` | 只apply新frame |
| stale/equal revision | `DROPPED_STALE` | 无side effect |
| capacity满且新train | 淘汰最老train frame并计metric | 不阻塞publisher |
| stationary unchanged | coordinator不逐epochoffer | 保持last applied state |
| rename/metadata command | owner显式`publishCurrentTrainFrame` | revision guard apply |
| ticket failure | 可能已有partial flip | consumer不得drain/apply为成功epoch |

`CoreTrain.commit()`只flip Core pending state并生成frame，不调用Bukkit extension，也不自动offerworld mailbox。generic `TickManager.postTick`同样不等于platform commit。

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
