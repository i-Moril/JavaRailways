# Core TrainController 契约

## 适用场景与用途

触发：新增/修改 `TrainController` subclass、`ControlCommand`、speed publication、controller replace/remove或reverse request。

用途：controller只在physics worker计算非负speed/movement，并在完整epoch后发布；避免把毫秒当秒、worker访问platform或结构transfer无barrier。

## Owner、真实签名与单位

```kotlin
interface ControlCommand {
    fun tick(deltaT: Double)
    val speed: Double
    val deltaS: Double
    val isFinished: Boolean
}
```

`deltaT`单位毫秒；`speed`单位m/s且`>=0`；`deltaS`单位米且`>=0`。来源：`Core/src/main/java/work/art1st/javarailways/core/api/control/ControlCommand.kt`。

`abstract class TrainController(train: Train, world: World)`：

- `tick(deltaT: Double)`由owning world `PhysicsCoordinator` worker调用。
- 无command：计算`deltaS = speed * deltaT * 0.001`；CoreTrain同时接收该epoch当前speed，其他Train仍调用公开distance API。
- 有command：先`command.tick(deltaT)`，读取并设置本次`command.speed`，再用同一post-tick speed和`command.deltaS`移动；`isFinished`后清command。
- speed经`CommitBuffer<Double>`；`publishPhysicsState()`只在所有workers结束后flip。
- profiling identity固定 `profileCategory="train-controller"`、`profileId=train.id`；同train只允许一个registered controller。
- train用weak reference；train不live时访问fail-fast。
- `CoreTrain.setController(value)`负责在帧间owner阶段同步安装、替换或移除控制器；协调器内部按确切旧实例校验并更新唯一注册表。
- protected `moveControlledTrain(deltaS,speedMetersPerSecond)`是所有override `tick`的controller子类合同：两个值必须来自同一个physics tick。CoreTrain走内部速度传播，非Core Train才回退公开distance-only调用。

`protected open onPhysicsStatePublished()`在本controller的speed/train buffers翻转后调用，用于发布controller自身值状态；它仍先于统一的train frame发布。Core内部将纯buffer翻转与hook通知拆成`publishPhysicsBuffers()`和`notifyPhysicsStatePublished()`：coordinator仅在与close同步并重验exact ticket的锁内执行前者，锁外执行后者，保留逐controller原顺序。原`publishPhysicsState()`及兼容`commit()`仍按先buffers后hook顺序调用，不改子类覆写签名。hook不在关闭锁中，不得假设close会等待其完成或撤销其外部效果；完整门禁及失败边界见[runtime publication](./runtime-publication.md)。

## 输入/结果矩阵

### 编组参数的准备与安装

`prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate>`
由 serialized simulation owner 在物理任务结束后调用。`cars`是准备后的确切车厢对象和顺序；
`counterpart`在解编或对方没有控制器时为null。默认返回空列表，不强迫无编组相关参数的控制器实现额外逻辑。

控制器用`ControllerConsistState<T>`保存当前不可变参数，并以`prepare(value)`生成更新token。
准备期间允许计算和明确失败，但不能改写当前参数或产生外部效果。解编先准备，再构造新train；
构造失败不安装更新。成功结构事务在排除physics worker的边界安装token。
token的构造和安装由Core控制，只执行已准备值的引用赋值，不接收插件提交lambda；
不能把查询、IO或可能失败的制动计算推迟到安装阶段。原控制器和控制输入不因此重建。

该机制不承诺任意线程同时读取多个state cell的原子快照；可见性依赖既有physics等待、结构锁及下一次worker提交。
准备复杂度由控制器决定，安装为O(更新数)。联结调用点仍属于CR-030碰撞事务草稿，
不能用参数token的单独测试宣称碰撞入口及接触位置处理已完成。

| 条件 | 结果 | side effect |
| --- | --- | --- |
| `deltaT >= 0`、无command | 按m/s×ms移动 | 仅active state |
| valid command | 更新command，移动`deltaS`，active speed更新 | epoch后published |
| command完成 | 本tick结果仍应用 | 随后清pending command |
| 同一待执行操作捕获偶数次reverse | 在帧间抵消 | 不改方向或快照 |
| 同一待执行操作捕获奇数次reverse | 帧间owner换向一次 | 下个physics tick正常计算movement，不插入空转epoch |
| invalid/nonfinite/negative speed或distance | 新API必须明确reject/result并保持旧state | 不得新增silent ignore |
| worker exception | ticket failure/Stall path | 不直接platform fallback |

## 帧间换向请求（CURRENT，第3a批）

`open fun reverse(): CompletableFuture<Unit>`非阻塞提交到列车所属world的帧间队列。
首次请求建立一项队列操作，随后尚未捕获的请求合并到同一计数；owner执行时取出计数并清除待处理引用，
偶数次抵消，奇数次调用`CoreTrain.reverse()`。新的请求进入下一项操作，不在owner等待自身Future。
每个调用者取得独立的Future副本，取消一个副本不取消其他调用者的请求。

执行时先检查world中仍是同一train实例、当前controller仍是请求来源；已移除或被替换的controller不能换向新控制器的列车。
这些拒绝、world关闭及队列终态通过Future异常报告；非Core train不能提供这项协调保证，明确失败而不退回worker调用。
Bukkit换向命令在Future完成后经entity scheduler告知玩家，不把“已排队”显示为“已换向”。

实际准备、发布和异常规则归[列车换向](./train-physics.md#帧间换向current第3a批)；提交和计数为O(1)，仅真实奇数次换向承担几何准备及世界占用刷新。
例如两次请求在drain以前提交，最终方向不变；一次请求执行后，紧随的physics tick直接按新方向运动。
错误做法是在worker修改方向、跳过整轮movement，或操作执行时不核对旧controller身份。
控制器安装/替换/移除及手动接管复合流程已迁移至第3b批的帧间阶段，具体约束如下。

## 控制器生命周期（CURRENT，第3b批）

外部手动接管、释放和导航请求先由所属world的协调器排队；宿主等待完整ticket以后，
在同一阶段内完成运营状态与控制器交接。内部同步`setController(value: TrainController?)`不再自行调度：
不同实例的安装先验证帧间作用域、world仍持有该train实例，以及新controller确实属于该train。
随后协调器`replaceController`核对注册表仍指向确切旧controller，更新注册表后才替换train引用。
重复设置同一引用直接返回，不产生交接。校验失败在写入前抛出，不留下半安装状态。

例如旧controller的worker尚未完成时，替换请求只排队，旧controller仍有效；帧间交接完成后，
下一次worker快照只包含新controller。移除后两者都不再tick。不能先移除旧controller，再校验新controller的归属。
注册表检查和安装为每次O(1)，不新增逐tick扫描、物理epoch或持久化版本。

物理原因脱轨不是外部控制请求：它在原physics publication屏障通过同一内部注册替换原语移除controller，
不调用受owner作用域检查的公开setter，也不排队后等待自身。外部脱轨和删除暂时复用结构门禁，
完整外部请求与观测发布迁移仍分别归后续第5批和第4批，不能据此宣称这些批次已完成。

`BetweenFramesOperationsTest`用真实阻塞worker检查阶段外替换拒绝且旧引用不变、排队期间不提前tick新controller、
exact ticket完成后的替换、下一epoch只执行新controller，以及旧请求拒绝和移除后无controller继续tick。
Basic方向输入在物理换向成功后才更新有效/显示方向；平台手动会话及Dispatcher完整操作见对应模块契约。

## 项目内正确示例

`TrainController.tick` 显式把毫秒换成秒：

```kotlin
if (pendingCommand == null) {
    moveControlledTrain(speed * deltaT * 0.001, speed)
} else {
    val cmd = pendingCommand!!
    cmd.tick(deltaT)
    speed = cmd.speed
    moveControlledTrain(cmd.deltaS, cmd.speed)
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt`。该换算防止50 ms Tick被误当50 seconds；
post-command speed与本次geometry属于同一physics epoch，不能晚一tick或由render adapter补算。

覆盖`tick`的生产controller同样必须调用该protected seam。当前Basic realistic manual和SimpleATO ATO都提交同一次
dynamics/actuator step的distance与post-step speed；ATO freeze提交0 m与0 m/s。公开distance-only入口只保留给没有时间基准的
显式移动，不能作为controller subclass的替代入口。

## 禁止实现

```text
错误：controller.tick中调用Bukkit、sleep、wall-clock补算、直接renderer
正确：immutable inputs → ControlCommand/active state；epoch后由adapter消费frame
```

不得把现有negative command silent return扩散为API约定；不得在worker对任意non-Core `Train`调用`@MainThread reverse()`。
controller子类不得直接调用`train.moveTrainForward`，否则Core只能把该次manual距离移动的frame velocity按0发布。

## 必需测试与验证

- 无command公式：给定`speed=10 m/s, delta=50 ms`断言`deltaS=0.5 m`。
- command：断言tick先于movement、finished只应用一次、invalid输入无mutation并返回明确failure。
- reverse：帧间两次抵消、一次完成后frame/world/occupancy一致，下一epoch直接按新方向运动；旧controller拒绝、取消副本及终态完成。
- registration：replace/remove/destroy后coordinator中无旧profile。
- `CoreTrainPublicationBoundaryTest.kt`：active speed/geometry在publish前不可见。

```bash
./gradlew :Core:test --tests '*PhysicsCoordinatorTest' --tests '*CoreTrainPublicationBoundaryTest' --tests '*TrainUniformMotionTest'
```
