# Core train physics 契约

## 适用场景与用途

触发：修改 consist geometry、bogie/head/tail movement、collision、derail、reverse、link/split/destroy。

用途：保证rigid consist沿同一active path解算，worker mutation经epoch发布；碰撞、方向与结构变更不把partial geometry暴露给consumer。

联结与解编是保留的正式能力（用户于2026-10-10确认），不能仅因暂无生产caller而作为原型删除。`splitBefore`的失败原子性与稳定car ID语义已收口；`linkWith`的事务与方向问题仍须单独修复。此决定不改变默认碰撞DERAIL行为，也不授权自动联结。

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

运动失败的脱轨原因保留异常类型与消息，并附列车ID和本次请求距离（米）；它沿原有frame→Main事件链发布，不因诊断改动而重试或改变失败分类。
`BogieException(Bogie)`在构造时复制转向架已发布位置的rail ID、facing与弧长为字符串，不持有live bogie；该位置不是失败时的active位置，不能用来声称精确故障坐标。比如请求100 m越过线路尽头时，诊断同时说明请求距离和此前已发布位置，而不是只留下异常类名。正常移动不构造这些诊断。
原因文本供人阅读，不是可解析的稳定错误协议；既有脱轨事件回归须验证上下文可见、frame先发布、事件只发一次，不能仅检查抛异常。

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

## 解编事务

`Train.cars`是稳定的编组/持久化顺序，reverse不重排该列表；`Train.isReversed`是公开的当前运营方向状态，`Train.operatingCars`只是`cars + isReversed`的只读派生，不是第二份可变顺序。`splitBefore(carId,newId)`在after-commit main-thread task中执行，并先等待捕获到的exact physics ticket完成，再读取或修改编组。它在稳定`cars`中的目标car之前切分，原列车保留目标之前的稳定前缀，新列车取目标及其后缀。目标未知、在当前编组内不唯一或是第一辆时拒绝，最后一辆之前可以合法分割出单车列车。两列均继承原`isReversed`；reverse前后传入同一`carId`必须得到同一稳定连接面，Core不根据当前运营head偷换保留方。

事务先验证目标car和world ID/name命名空间，为两个非空cars集合解决head/tail，并预计算原列车的body traversal、occupancy与immutable frame。随后按原slot顺序调用各`TrainExtension.splitBefore(carId,newId)`得到专属新实例，并用这些结果构造新列车。任一边界、身份、extension prepare/initialize或新列车构造失败时，原列车cars/方向/controller/extensions、frame、car publication owner、world map及rail车端保持原样；新构造注册由构造器既有失败清理撤销。

Core没有能撤销任意插件外部副作用的通用协议，因此`TrainExtension.splitBefore`是prepare-like边界：不得修改原extension或发布外部效果，必须返回尚未attach的独立实例。新实例保留同一slot identity，只在新列车构造期间initialize；该次initialize仍属于prepare，只能写返回extension自身，不得修改两列共享的cars或发布平台效果。这是extension实现者的合同，不通过猜测原extension内部状态实现伪回滚。成功后新`CoreTrain`取得转移cars的publication stream ownership，原列车以预计算结果更新两端、占用和frame，不销毁任何car。新列车没有controller，因此分离cars/bogies的运动状态在提交时清零；两列结构frame沿用切分前已完成的同一physics epoch，不能发布“新车speed为0但车体仍携带旧非零速度”的混合帧，也不能把新车留在epoch 0。新列车构造成功后的commit-only区间不再调用可扩展代码或几何求解：只应用预计算字段、清零分离侧运动、翻转`CommitBuffer`，更新rail端队列、原子publication引用和latest-wins mailbox。

运营层今后可以在车辆/编组元数据中声明固定可分割连接面，并在调用Core前加上运用、车型或玩家权限检查。本批只提供物理层稳定car ID分割原语，不实现SimpleATO元数据或规则。

## 三维刚体约束与局部距离求解

CoreCar 拥有车内和车间几何求解。正常运行时 controller 先移动首节车的前转向架，CoreCar 固定这个新位置，求后转向架，再按运营顺序逐车满足车钩约束；后车纠偏不能反推或重建领车目标。显式摆位与恢复采用同一车体中心规则。

车内约束使用连接点 `location.position + location.normal * floorHeight` 的三维直线距离，等于 `bogieAOffset - bogieBOffset`；不能用轨道弧长代替。车体中心统一从运营前转向架连接点，沿后→前的连接点弦方向减去相应前端配置偏移。正反向仅选择不同端点和偏移，不改变配置距离的意义。车间约束为两个车体端点的三维距离等于两端 connector length 之和，纵向投影仅用于辨认正确侧。

### 本次解算的路径与候选

每次解算捕获一个有序 RailSegments，并以累计弧长查询其中候选位置；内层车内求解复用外层同一条前向路径，不重新读取反向道岔选择。运行时先按上一位置加 deltaS 预测；后续车的内层轴距预测复用上一 tick 的轨道间距。若必须向后纠正，范围严格限制在上一转向架所在 RailView 的当前轨道内，以直接弧长定位，不能跨 reverse turnout。

私有 `solveDistance(maximumDistance, prediction, tolerance, sample, condition)` 先验证预测候选的实际残差；不满足时从附近扩张区间，初始半径为 max(0.01m, |残差|)，每次加倍至捕获路径边界。找到异号区间后最多迭代60次：割线落在区间中间80%内时采用割线，否则取中点，保持区间收缩。候选连同已计算的几何和残差保存，命中时复用结果。只有有限残差满足精度才能成功，步长很小或耗尽次数不能视为收敛。该局部根搜索依赖目标附近连续、可包围的距离条件，不是任意自交线路上的全局最近根证明。用户明确将轴距接近曲线直径的极紧曲线排除本次支持范围；不为其非单调窄根区间增加特殊扫描，找不到可验证的根则明确失败。

内层轴距容差为1e-6m，外层车钩为1e-5m，均小于最终公开几何容差 Curve.TOLERANCE=5mm；严格内层精度减少锚点换向后的中心误差。在有限线路末端，先用实际连接点距离求出最后可行尾转向架候选，不能只按名义轴距弧长裁剪，否则外层试探可能越过可解区间。

候选几何计算不修改 CommitBuffer 或 dirty 标志；成功应用后才设置 geometryPhysicsDirty，运动状态保持原有独立 dirty/publication 合同。无包围区间、不满足残差或不能到达目标时抛既有 BogieException，运行入口沿 PhysicsException→derail 边界处理，不将求解失败标为成功；这不新增整个编组运动的事务回滚保证。

### 计算成本、边界与验证

每次曲线坐标查询仍为 O(log P)，候选路径定位为 O(K)，P为轨道采样点数、K为本次跨越段数。轴距搜索与车钩搜索仍可能嵌套；预测命中可减少次数，最坏成本包含扩张和60次区间修正，不能宣称单车严格 O(1)。所有缓存只属于本次求解，不跨 tick 或拓扑更新保留。

直线匀速时预测通常直接满足残差；R30/6m轴距反例必须求真实弦长；S弯、坡道、normal/floorHeight、非对称偏移、零车钩、换向及有限轨道末端均须验证。新增 `CoreTrainRigidGeometryTest` 用真实 epoch 检查实际连接点间距、两端配置偏移、车钩长度/侧、精确领车目标及加载中心一致性，不能通过扩大5mm容差掩盖误差。

## 存档摆位恢复

`restorePersistedPlacement`先验证保存的位置，再恢复derived car state和车端；不重新摆车来覆盖损坏输入。它复用body publication的`placementSegments(savedTail, savedHead)`：沿tail→head的active path一次展开，不能从head反向沿当前道岔选择重算，因为两个方向的选择可以不对称。

单调cursor按operating顺序从车尾向车头扫描各车tail/head bogie，要求exact RailView身份（包含facing）、位置落在该segment且距离不倒退。累计弧长只证明路径顺序及首尾bogie到saved endpoint的沿轨offset；车内间距按上述三维连接点距离校验。随后按相同前连接点锚定规则计算各车中心，校验相邻车体端点距离与车钩前后侧；各项允许误差不超过 Curve.TOLERANCE（5mm）。内部某车整体平移而保持轴距/顺序的损坏输入必须因车钩不符而拒绝。

除既有路径展开外，只顺序访问一次segments/bogies/cars；这些校验在load执行，不增加正常tick扫描。失败沿既有failed-load清理，合法保存的bogie/车端精确位置保留；这不代表整个多列车world materialization失败可原子回滚。

存档字段及格式版本不变，也不保留旧弧长近似的运行时兼容分支；如需保留旧开发数据，应另行提供可审计、dry-run且保留恢复点的离线迁移。错误做法是用按弧长放置的旧数据证明新刚体合法，或先移动保存位置再静默接受；正确做法是在当前模型边界校验实际几何，并明确拒绝不一致输入。

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
- 让split在身份、端点、extension或新列车构造可失败阶段前修改原编组，或将extension副作用伪装成Core可回滚的事务。

## 必需测试与验证

- `CoreTrainTailBogieTest.kt`、`CoreTrainAsymmetricTurnoutTest.kt`：turnout/reverse/tail spacing。
- `CoreTrainSplitTest.kt`：正/反方向与末节解编，非法边界、身份、端点、extension prepare/initialize失败时的原状态保持，slot和publication ownership。
- `TrainSlopeMotionTest.kt`、`TrainSlopeJointMotionTest.kt`、`CoreCarPositioningRegressionTest.kt`：3D geometry。
- `TrainUniformMotionTest.kt`：真实50 ms coordinator epoch同时断言0.5 m位移、car/bogie frame的m/s速度、停车清零及换向后tangent符号。
- `PhysicsCoordinatorTest.kt`：stationary collision、pair once、next-epoch decision。
- `CoreTrainDerailEventTest.kt`、`CoreTrainPublicationBoundaryTest.kt`：publication before event/getter。
- `CoreTrainConstructionTest.kt`：直接构造/存档加载的ID、name及交叉命名空间拒绝不污染world/rail索引；tail无法放置不注册head；extension初始化失败清理车端和controller、保留原列车及调用方cars，并允许同身份成功重试。
- `CoreTrainPersistedPlacementTest.kt`：固定损坏车端JSON；脱节/重排/错误rail或facing、bogie连接点距离及两端offset拒绝并保持原world/rail/frame/mailbox；合法直线/曲线AB与BA、非对称道岔精确位置保留。内部车厢整体平移而保持顺序/轴距时，因相邻车钩不符而拒绝。
- 新link/split/destroy变更必须断言cars、ends、controller、world indexes和extensions全量cleanup。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.train.*' --tests '*PhysicsCoordinatorTest'
```
