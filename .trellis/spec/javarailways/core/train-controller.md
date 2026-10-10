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

## 输入/结果矩阵

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
