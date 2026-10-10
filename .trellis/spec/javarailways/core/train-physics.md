# Core train physics 契约

## 适用场景与用途

触发：修改 consist geometry、bogie/head/tail movement、collision、derail、reverse、link/split/destroy。

用途：保证rigid consist沿同一active path解算，worker mutation经epoch发布；碰撞、方向与结构变更不把partial geometry暴露给consumer。

联结与解编是保留的正式能力（用户于2026-10-10确认），不能仅因暂无生产caller而作为原型删除。正式能力定位不代表当前实现已通过验收：现有link/split的事务、方向与失败保持问题仍须修复。此决定不改变默认碰撞DERAIL行为，也不授权自动联结。

## Owner、签名、单位与 lifecycle

`CoreTrain`拥有有序cars、head/tail、epoch-end placement spans、published swept spans和controller registration。`moveTrainForward(deltaS: Double)` 仅physics worker调用，`deltaS`单位米且`>=0`：

```kotlin
override fun moveTrainForward(deltaS: Double) {
    if (activeDerailState.derailed) return
    require(deltaS >= 0.0) { "deltaS must >= 0" }
    if (world.physicsCoordinator.detectSweptCollision(this)) return
    // signal scan and consist solve follow
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`。

运动顺序：检查published swept collision → 扫active route signal并同步发event → 解head bogie/cars/tail → 写active buffers/swept spans及两端有序traversal；`PhysicsException`转physics derail。tail从train-local forward path重算，不依赖reverse branch当前selection。

每次正距离移动已经取得head的`peekCurve(deltaS)`有序segments；同一列表同时供signal扫描、swept
occupancy补全和head traversal publication使用。tail endpoint仍通过既有train-local forward search求解，
并保留该search的`RailSegments`作为tail traversal；不得从反向`RailView`或移动后的active branch重建。
publication把每个segment转换为canonical AB弧长但保留local facing与列表顺序。零距离移动显式发布
空的head/tail movement traversal，下一epoch无移动也清空前一epoch证据；这两项不是持续占用状态。

每次发布还从同一已提交train-local path生成`bodyTraversal`：它是epoch末从保守车尾到车头的有序
持续位置，不是一次移动事件，也不从无序`occupiedIntervals`反推。Dispatcher只把它接到已经选定的
head-forward mission route前面，以满足Basic“冻结路线从物理车尾开始”的合同；Dispatcher不得为此再寻路。

公开`tail`、`bodyTraversal`与`tailTraversal`的长期安全语义是“保守安全车尾”，而不是强制精确几何车尾。当前实现发布精确train-end；未来低精度模式可让安全车尾落后于真实车尾并延迟释放，但禁止让它沿行驶方向领先真实车尾。与此一致，`bodyTraversal`和`occupiedIntervals`必须覆盖epoch末真实车体；允许多占，不允许漏占。

完整删除由`CoreTrain.destroy()`拥有：deferred structural task → remove controller → destroy head/tail/cars/extensions → `world.removeTrain(this)`。`World.removeTrain`只删index，不能独立代表destroy。

## Collision、derail、reverse矩阵

| 触发 | 当前epoch结果 | 后续结果/side effect |
| --- | --- | --- |
| swept overlap首次claim | pair冻结，发布一次`CollisionEvent` | 下一epoch应用`DERAIL/LINK` |
| listener `requestLink()` | pending resolution=`LINK` | 下一epoch owner执行link transaction |
| worker physics failure | active derail pending | epoch frame flip后queue main derail reason |
| main `derail(reason)` | reason非blank，await physics后publish | frame后立即Main event，仅一次 |
| controller reverse奇数request | 本epoch只改direction/geometry | frame先发布，下一epochmovement |
| even reverse requests | 抵消 | 无direction mutation |
| `destroy()` | deferred完整cleanup | world index最后移除 |

Reverse改变operating head/tail，但保持`Car.direction`的模型/渲染朝向不变。`Car.operatingDirection`是只读派生值，
表示正的`moveTrainForward`距离对应的当前物理前进方向；换向后它必须翻转，供Basic坡度计算使用。不得把这两个方向
重新合并成一个字段。exact persisted bogie/head/tail placement restore后重建derived car state。

## 项目内正确示例

完整destroy按owner顺序清资源：

```kotlin
override fun destroy() {
    world.tickManager.scheduleMainThreadAfterCommitTask {
        removeController()
        head.destroy(); tail.destroy()
        cars.forEach { it.destroy() }
        world.removeTrain(this)
    }
}
```

来源：`CoreTrain.kt`。它避免只删world map后遗留controller、TrainEnd或render extension。

## 禁止实现

- worker直接修改published getter或发Main event；
- collision同epoch检测并立即link/derail；
- tail沿独立reverse active branch求位置；
- 从合并swept span或epoch终点反推head/tail经过顺序；
- 用整epoch `sweptSpans`判断epoch末列车是否仍占用一个监测区；
- structure mutation直接改cars/map而无serialized task、prevalidation与rollback；
- 用`world.removeTrain`代替destroy；
- 未验证/未测试的split实现当成熟API。

## 必需测试与验证

- `CoreTrainTailBogieTest.kt`、`CoreTrainAsymmetricTurnoutTest.kt`：turnout/reverse/tail spacing。
- `TrainSlopeMotionTest.kt`、`TrainSlopeJointMotionTest.kt`、`CoreCarPositioningRegressionTest.kt`：3D geometry。
- `PhysicsCoordinatorTest.kt`：stationary collision、pair once、next-epoch decision。
- `CoreTrainDerailEventTest.kt`、`CoreTrainPublicationBoundaryTest.kt`：publication before event/getter。
- 新link/split/destroy变更必须断言cars、ends、controller、world indexes和extensions全量cleanup。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.train.*' --tests '*PhysicsCoordinatorTest'
```
