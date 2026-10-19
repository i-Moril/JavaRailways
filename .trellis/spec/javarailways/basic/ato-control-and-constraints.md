# Basic 自动/手动共用物理控制 primitive

## 状态与 owner

Basic拥有`TrainActuator`、`ControlDemand`、`ActuationStep`和`GradeAwareBrakingEnvelope`。这些值和算法只描述
物理执行或制动能力，不选择ATO策略。`AtoSafetyProfile`、`AbstractATO`、planner、constraint类型/manager与world ATO
coordinator属于SimpleATO；确定性的恒加减速actuator也只作为SimpleATO测试fixture存在。

生产物理执行使用`control/HandleTrainActuator.kt`和共享纵向动力学，不使用旧的
`controller.command.AccelerateCommand`／`BasicControlCommand`原型。它们没有生产或测试调用，已在代码审查中删除；
Basic不再需要Lombok依赖；Core也不再保留`ControlCommand`解释器（见[Core TrainController](../../core/train-controller.md)）。

Basic另外拥有`WorldSafetyInterlock`：一个world-local、线程安全、只从false锁存到true的通用fail-closed latch。
SimpleATO coordinator在首次Stall时锁住它，管理员完成恢复后才reset；Basic手动控制器每tick先读取它，锁住时施加紧急制动。
因此SimpleATO故障仍会阻止手动列车继续运行，但Basic不依赖SimpleATO类型。

## 执行机制与单位

```kotlin
interface TrainActuator {
    val maximumSpeedMetersPerSecond: Double
    fun step(currentSpeedMetersPerSecond: Double, demand: ControlDemand, deltaTimeSeconds: Double): ActuationStep
    fun freeze()
    fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope
}
```

所有输入输出使用SI单位并要求finite/non-negative。运动学测试actuator用平均速度积分距离；生产
`HandleTrainActuator`把需求转换为真实手柄和共享纵向动力学。坡度包络保守计算前方可制动速度，完整机制见
[列车纵向动力学](./train-longitudinal-dynamics.md)。

`freeze()`只调整执行器内部制动手柄状态并返回`Unit`；运动冻结（0 m/s、0 m位移）由controller在同一seam提交。
`brakingEnvelope`没有测试专用默认实现：每个执行器都必须显式提供制动能力，非空`segments`必须返回非空包络，
空segments由调用方处理，不调用此方法；执行器拒绝空segments，返回类型非nullable。测试执行器使用`GradeAwareBrakingEnvelope.constant`提供确定性能力，使约束路径与生产一致。

## 禁止实现与测试

- Basic actuator不得选择mission、provider、信号aspect或运营方向；
- SimpleATO不得复制纵向动力学或让Basic反向依赖其coordinator；
- interlock不得因普通mission拒绝而自动锁住，也不得由controller tick自动reset。

```bash
./gradlew :Basic:test --tests '*WorldSafetyInterlockTest'
./gradlew :SimpleATO:test --tests '*AtoPolicyTest' --tests '*KinematicTrainActuatorTest' --tests '*SimpleATOTest'
```
