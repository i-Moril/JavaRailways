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

公开的`moveTrainForward(deltaS)`只有距离、没有时间基准，因此不得从`deltaS`猜测速度；该路径移动几何时把本次
car/bogie速度按0发布。`TrainController.moveControlledTrain`对`CoreTrain`改用implementation-only
`moveTrainForwardFromController(deltaS, speedMetersPerSecond)`，其中speed是command完成本次`tick(deltaT)`后的
当前m/s值。内部CoreTrain方法不进入`core.api`；protected controller seam是子类合同，非Core `Train`才回退公开distance调用。
基类、Basic realistic manual与SimpleATO ATO均使用该入口，覆盖`tick`的新controller不得绕开。

controller移动head bogie后，Core用其**新位置**的train-local facing tangent乘当前speed，得到首个head bogie速度；
随后沿有序编组传递tail/head端速度。car刚体角速度使用实际head→tail bogie chord `r`计算
`omega = r × (vTail - vHead) / |r|²`，单位为rad/s；car中心和下一车连接点速度再用
`v(point) = vHead + omega × offset`求得。括号是合同的一部分：Kotlin infix `cross`的优先级不能让表达式退化成
`(vHead + omega) × offset`。bogie/car velocity与geometry各自维护dirty状态和CommitBuffer槽位，避免
motion-only epoch翻转未写入的geometry槽；两类状态仍在同一个epoch barrier发布。consumer不得按位移或train标量速度重算。

零速controller epoch、collision/derail拒绝移动、运动异常、无controller的下一physics epoch及只发布换向的epoch均把
整列active motion清零，防止上一epoch非零速度残留。`deltaS == 0`但controller仍报告非零当前speed时保留**epoch结束瞬时速度**
语义：这可表达本tick末才建立的速度，不表示本tick发生了位移；collision/reverse等明确冻结分支仍强制为0。无时间基准的
公开/manual移动、`setLocation`/maintenance placement、存档placement恢复与主线程reverse/derail始终发布0。换向后的下一次
移动从反向后的active rail facing tangent重新注入速度。

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

完整删除由`CoreTrain.destroy()`拥有：deferred structural task → remove controller → destroy head/tail/cars/extensions → `world.removeTrain(this)`。`World.removeTrain`只删index并退休process-local render ownership proof，不能独立代表destroy。

每个runtime `CoreTrain`另有不持久化的publication stream UUID；每个`CoreCar`用原子引用保存当前stream owner，供平台region线程在不读取live train集合时拒绝迟到render task。构造完整成功后绑定cars；link把被并入cars转移到survivor后再移除旧train；split的新`CoreTrain`构造负责取得其cars；任何`CoreWorld.removeTrain`按exact旧token退休仍属于该train的cars，因此已经转移的cars不会被旧owner清空。该token不改变link/split事务语义，也不是公开`core.api`、epoch或schema version。

## 构造与失败清理

`CoreTrain`构造在main thread完成。先通过`CoreWorld.validateNewTrainIdentity(id, name)`检查ID/name唯一性及交叉命名空间冲突，再计算head/tail两个位置；任一位置无法放置时抛`CannotCreateTrainException`，此时尚未注册任何`TrainEnd`。`addTrain`复用同一身份校验，仍允许同一实例的重复添加（world加载链会调用）。校验查world索引，不额外遍历列车。

随后依次创建注册head/tail、添加world索引、初始化train extensions、生成初始published frame。该阶段失败时，构造器仅撤销自己已完成的注册：移除controller，分别destroy已创建的车端，只在本次`addTrain`成功后移除world索引。清理逐项尝试，失败附加到原异常的`suppressed`后继续，最终重抛原异常。不能在身份拒绝后无条件`removeTrain`，否则可能删除同ID的原列车及其frame。

构造未返回时，传入cars仍由调用方拥有；此清理不调用完整`destroy()`或`discardFailedLoad()`，不销毁cars/bogies。已返回列车的加载恢复失败仍由[持久化加载边界](./persistence.md)处理。`TrainExtension`没有通用destroy/rollback协议，本边界不承诺撤销extension任意外部副作用；正常的列车销毁及结构变更也不能用此局部清理替代。

## 存档摆位恢复与当前缺口

`restorePersistedPlacement`先验证保存的位置，再恢复derived car state和车端；不重新摆车来覆盖损坏输入。它复用body publication的`placementSegments(savedTail, savedHead)`：沿tail→head的active path一次展开，不能从head反向沿当前道岔选择重算，因为两个方向的选择可以不对称。

单调cursor按operating顺序从车尾向车头扫描各车tail/head bogie，要求exact `RailView`身份（包含facing）、位置落在该segment且距离不倒退；累计segment弧长得到统一路径距离。车内两bogie间距按配置的**轨道弧长**校验，不能拿曲线两点的欧氏弦长代替；首尾bogie到saved endpoint的offset也以同一距离校验，误差均不超过`Curve.TOLERANCE`（5 mm）。除已有路径展开外只顺序访问一次segments/bogies，校验在load执行，不增加正常tick扫描。失败交回既有failed-load清理，不改变调用方的合法精确位置。

**仍未实现完整相邻车钩几何验证。** SavedCar只保存bogie及配置，没有记录当时以head或tail恢复车体中心的求解锚点；现有运动/换向与restore使用锚点不同，合法曲线还原也可能产生大于5 mm的车钩残差。因此路径顺序及车内间距正确仍不能证明相邻车相接，内部某车平移而保持顺序的损坏输入可能被接受。解决中心还原的一致性后才能关闭此缺口；不得加宽车钩容差或声称当前load已完成整编组预校验。

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
- 从`deltaS`推测速度，或由Bukkit/其他consumer补算Core car/bogie速度；
- 用单位车身方向代替实际bogie chord计算角速度（会丢失杆长并破坏rad/s单位）；
- 用整epoch `sweptSpans`判断epoch末列车是否仍占用一个监测区；
- structure mutation直接改cars/map而无serialized task、prevalidation与rollback；
- 用`world.removeTrain`代替destroy；
- 将构造失败清理放在调用方取得列车之后，或让失败构造销毁调用方传入的cars；
- 未验证/未测试的split实现当成熟API。

## 必需测试与验证

- `CoreTrainTailBogieTest.kt`、`CoreTrainAsymmetricTurnoutTest.kt`：turnout/reverse/tail spacing。
- `TrainSlopeMotionTest.kt`、`TrainSlopeJointMotionTest.kt`、`CoreCarPositioningRegressionTest.kt`：3D geometry。
- `TrainUniformMotionTest.kt`：真实50 ms coordinator epoch同时断言0.5 m位移、car/bogie frame的m/s速度、停车清零及换向后tangent符号。
- `PhysicsCoordinatorTest.kt`：stationary collision、pair once、next-epoch decision。
- `CoreTrainDerailEventTest.kt`、`CoreTrainPublicationBoundaryTest.kt`：publication before event/getter。
- `CoreTrainConstructionTest.kt`：直接构造/存档加载的ID、name及交叉命名空间拒绝不污染world/rail索引；tail无法放置不注册head；extension初始化失败清理车端和controller、保留原列车及调用方cars，并允许同身份成功重试。
- `CoreTrainPersistedPlacementTest.kt`：固定损坏车端JSON；脱节/重排/错误rail或facing、bogie弧长及两端offset拒绝并保持原world/rail/frame/mailbox；合法直线/曲线AB与BA、非对称道岔精确位置保留。相邻车钩缺口不能计作已覆盖。
- 新link/split/destroy变更必须断言cars、ends、controller、world indexes和extensions全量cleanup。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.train.*' --tests '*PhysicsCoordinatorTest'
```
