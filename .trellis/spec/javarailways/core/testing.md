# Core testing 契约

## 适用场景与用途

触发：新增Core test、跨模块fixture、thread/topology/physics/persistence regression，或修改`java-test-fixtures`。

用途：测试必须观察真实publication/barrier与cleanup，不用sleep、reflection或same-helper自洽掩盖错误。

## Fixture owner 与签名

Core `java-test-fixtures`导出：

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/train/TrainTestUtils.kt`：world/rail/train、AB/BA/loop builders；
- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt`：安装与production worker相同marker。

真实worker helper：

```kotlin
object AsyncTickTestSupport {
    fun <T> run(block: () -> T): T =
        AsyncTickThreadMarker.runInAsyncTickContext(block)
}
```

consumer test通过`testFixtures(project(":Core"))`复用；production不得依赖fixture。`createTestWorld(physicsCoordinator: PhysicsCoordinator? = null)`在受保护的setup中创建TickManager和默认PhysicsCoordinator；Koin初始化在资源分配前。创建失败只释放helper拥有的coordinator及tick/scheduler，保留原异常并附suppressed；显式传入的coordinator仍由caller拥有。成功返回后caller在`finally/use`按physics→runtime binding→tick关闭并清world/global registrations。nullable默认值使默认coordinator不会在进入函数前分配，Kotlin无参/具名及JVM一参调用保持可用。

## 测试矩阵与 assertion points

| 主题 | 必需断言 |
| --- | --- |
| physics | exact ticket epoch、before/after publication、delta单位/clamp、failure→Stall、finally close |
| thread | wrong thread fail-fast；annotation本身不调度；marker lexical cleanup |
| curve/topology | AB/BA、endpoint/frame、turnout、body occupancy、transaction reader、rollback |
| train | head/tail/bogie/car spacing、reverse own epoch、collision next epoch、destroy全cleanup |
| render | value-only、defensive copy、revision stale、coalesce/capacity、无live补读 |
| event/global registry | handler顺序/failure、token close、test后无registration、slot不依赖order |
| persistence | hand-written fixed JSON、strict unknown/future、orphan/duplicate、failure无partial state |

## 结果/资源矩阵

| 测试动作 | 正确结束状态 | failure时仍必须 |
| --- | --- | --- |
| coordinator test | awaited ticket或明确exception | `close()` |
| process-wide bus registration | token closed | `finally close` |
| ThreadGuard host registration | exact token在finally关闭且可重复close | 不用全局clear，不污染后续test |
| world materialization失败 | 无partial registered train | close created resources |
| concurrent barrier | latch/future确定时序；锁阻塞测试证明具体线程已进入对应锁队列 | 不用固定sleep或仅凭task started猜测 |

实际行为回归由`TrainUniformMotionTest`等待真实`stepSimulation`的exact ticket后验证head/tail/每车published frame的位移和速度；50 ms用例必须把m/s换算成实际位移并检查每个car/bogie frame，而不只检查controller/train标量speed。停止/解除controller的下一epoch必须证明上一epoch非零速度已清除；换向用例先验证只换向epoch的空traversal/零速度，再验证下一移动epoch按新rail facing tangent发布反号速度；曲线用例检查bogie切线、刚体chord与角速度，distance-only placement检查不会继承旧速度。不能以local累加值或live field充当frame actual。`CoreTrainDerailEventTest`从真实尽头行驶失败触发coordinator发布/排队，先检查physics完成但main尚未drain时无事件，再检查重复drain及下一idle epoch仍只一次。listener内记录值、断言放在listener外，避免总线异常隔离吞掉assertion。`ImmutableSnapshotTest`捕获后实际改变live对象位置/lifecycle和列车摆位，再验证旧值不变。

`CoreTrainAsymmetricTurnoutTest`的移动回归必须等待真实coordinator ticket，再从同一已发布epoch检查车头、车尾、body traversal和脱轨状态；不能在`moveTrainForward`之后、`commit`之前读取旧公开值。重复移动还要在每个已发布epoch验证车尾端与已解算转向架重新锚定，并在最终epoch检查累计实际位移。`CoreTrainMultiCarDiagnosticTest`只使用公开列车/car/frame状态：command-style relocation先证明commit前不可见、maintenance publication后出现固定fixture位置，再通过真实50 ms epoch验证两车都前移且固定编组间距保持。默认offset用fixture的明确数值断言，不以被测公式生成expected，也不反射`CommitBuffer`。

`CoreTrainPublicationBoundaryTest`的reverse/derail回归分别观察pending mutation、`publishPhysicsState`翻转live buffer和`publishFrame`替换atomic frame三步：前两步保留原frame identity，最后一步检查新revision、传入epoch及方向/脱轨值，同时保留旧frame验证不变。`commit`必须同时推进buffer与frame且保持最近physics epoch。运营换向交换车头/车尾及rail facing，但保留车厢稳定物理渲染方向；脱轨reason属于live API，当前frame只有`derailed`，不能假设frame携带reason。这里直接验证内部发布原语，coordinator真实epoch与事件排队由已有集成回归负责。

这些用例不替代其他未覆盖场景：disabled环线仍明确跳过，其余persistence fixture及RailWand完整split/merge事务集成继续跟踪；Basic observation集合复制与有序bodyTraversal已由[Basic测试契约](../basic/testing.md)所列回归覆盖，Basic整模块审查仍未完成。不能因为补了资源关闭就宣称全部publication断言已充分。清理失败必须在没有主失败时暴露，有主失败时作为suppressed附着，不能覆盖原始断言。拓扑锁阻塞回归捕获具体reader线程，并通过内部只读诊断确认该线程已进入`ReentrantReadWriteLock`等待队列后再检查future未完成；单纯在read调用前countDown仍可能在线程被抢占时产生假阳性。

`CoreTrainPersistenceStateTest`的零距离回归必须用关闭timer fallback的真实coordinator，逐次有界等待exact ticket，并记录controller实际执行次数、frame epoch/revision递增、固定摆位和car/bogie零速度；generic TickManager的pre/postTick不能证明physics controller被执行。恢复用例保留source/restored比较，同时检查fixture独立给定的head/tail/car位置。

`WorldPersistenceRoundTripTest`保留真实Koin child scope的source→JSON→load集成；`SplitWorldSnapshotTest`、`CoreTrainPersistenceStateTest`、`CoreTrainReversedPersistenceTest`与`SavedRailConnectionsTest`使用显式dependency。各测试在创建world以前就拥有tick/coordinator，并覆盖source设置、loaded world构造及断言失败；按physics→world runtime binding→tick顺序释放。`use`负责保留主异常和suppressed清理异常，共享Koin parent不由单项fixture关闭；load失败释放binding但不关闭caller-owned tick/coordinator，fixture必须关闭这些显式dependency。

换向恢复同时保留source/restored bogie精确比较及独立固定位置/BA方向断言；数值容差遵循`Curve.TOLERANCE`的摆位求解合同。失败恢复通过已有`WorldParentContext.attachWorld`捕获实际目标world，验证binding释放、train ID/name索引、rail车端及mailbox无失败列车，并完成下一真实epoch检查world frame/occupancy为空；此单列车fixture不证明多列车world load整体回滚。endpoint恢复的8类拒绝输入、4种端部组合和2种active选择均保留。

`RailConnectTest`与`CoreTrainTailBogieTest`的原7项用例使用局部`withWorld`组合显式dependency的`use`：在创建world前纳入tick/physics owner，成功返回后登记binding，按physics→binding→tick释放，setup或断言失败同样保留主异常和suppressed。helper返回`Unit`以保证表达式体仍被JUnit发现；原连接、尾架、支路、换向及间距断言保持。`TrainSlopeJointMotionTest`两项沿用共享helper，在成功返回后以局部`use`关闭上述三类资源，setup失败由共享helper负责；原坡道运动断言保留。这些局部清理不代表全部fixture已经审计，RailWand完整事务集成继续单独跟踪。

## 项目内正确示例

`Phase`类并发测试应采用exact future/ticket；Core fixture的marker只包lexical block：

```kotlin
AsyncTickTestSupport.run {
    controller.tick(50.0)
}
```

来源：`Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt`及SimpleATO `SimpleATOTest.kt`。它验证runtime guard而不把测试线程永久标记为worker。

## 禁止实现

- `Thread.sleep`证明锁/epoch完成；
- 只累加local distance、不断言train published location；
- disabled test算绿色coverage；
- reflection写private registry作为新fixture contract；
- 同一serializer/helper同时生成expected token与actual；
- 忘记关闭world/coordinator/executor/event token。

## 必需验证

```bash
./gradlew :Core:test :Core:testFixturesClasses
./gradlew :Core:dokkaGeneratePublicationHtml
# Core contract consumers
./gradlew :Core:test :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test
git diff --check
```

修改单主题时可先跑focused tests，但交付前必须运行`./gradlew :Core:test :Basic:test --rerun-tasks`。
