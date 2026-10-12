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
- `CoreTrain.setController(value)`负责unregister old/register new；replace由serialized simulation owner在barrier下协调。
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
| reverse requests偶数 | 抵消，`preparePhysicsTick=true` | 正常movement |
| reverse requests奇数 | reverse一次，`preparePhysicsTick=false` | 本epoch不movement |
| invalid/nonfinite/negative speed或distance | 新API必须明确reject/result并保持旧state | 不得新增silent ignore |
| worker exception | ticket failure/Stall path | 不直接platform fallback |

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
- reverse：两次抵消、一次只发布方向且不移动、下一epoch恢复。
- registration：replace/remove/destroy后coordinator中无旧profile。
- `CoreTrainPublicationBoundaryTest.kt`：active speed/geometry在publish前不可见。

```bash
./gradlew :Core:test --tests '*PhysicsCoordinatorTest' --tests '*CoreTrainPublicationBoundaryTest' --tests '*TrainUniformMotionTest'
```
