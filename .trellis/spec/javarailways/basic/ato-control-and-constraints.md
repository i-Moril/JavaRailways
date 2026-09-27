# Basic 自动/手动共用物理控制 primitive

## 状态与 owner

Basic拥有`TrainActuator`、`ControlDemand`、`ActuationStep`和`GradeAwareBrakingEnvelope`。这些值和算法只描述
物理执行或制动能力，不选择ATO策略。`AtoSafetyProfile`、`AbstractATO`、planner、constraint类型/manager与world ATO
coordinator属于SimpleATO；确定性的恒加减速actuator也只作为SimpleATO测试fixture存在。

Basic另外拥有`WorldSafetyInterlock`：一个world-local、线程安全、只从false锁存到true的通用fail-closed latch。
SimpleATO coordinator在首次Stall时锁住它，管理员完成恢复后才reset；Basic手动控制器每tick先读取它，锁住时施加紧急制动。
因此SimpleATO故障仍会阻止手动列车继续运行，但Basic不依赖SimpleATO类型。

## 执行机制与单位

```kotlin
interface TrainActuator {
    val maximumSpeedMetersPerSecond: Double
    fun step(currentSpeedMetersPerSecond: Double, demand: ControlDemand, deltaTimeSeconds: Double): ActuationStep
    fun freeze(): ActuationStep
    fun brakingEnvelope(segments: List<BrakingGradeSegment>): GradeAwareBrakingEnvelope?
}
```

所有输入输出使用SI单位并要求finite/non-negative。运动学测试actuator用平均速度积分距离；生产
`HandleTrainActuator`把需求转换为真实手柄和共享纵向动力学。坡度包络保守计算前方可制动速度，完整机制见
[列车纵向动力学](./train-longitudinal-dynamics.md)。

## 禁止实现与测试

- Basic actuator不得选择mission、provider、信号aspect或运营方向；
- SimpleATO不得复制纵向动力学或让Basic反向依赖其coordinator；
- interlock不得因普通mission拒绝而自动锁住，也不得由controller tick自动reset。

```bash
./gradlew :Basic:test --tests '*WorldSafetyInterlockTest'
./gradlew :SimpleATO:test --tests '*AtoPolicyTest' --tests '*KinematicTrainActuatorTest' --tests '*SimpleATOTest'
```
