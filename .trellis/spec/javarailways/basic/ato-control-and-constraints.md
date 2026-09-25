# Basic ATO control primitives 与 constraints 契约

## 适用场景与用途

触发：修改`AbstractATO` tick skeleton、`TrainActuator`、`AtoSafetyProfile`或constraint math/owner。具体`SimpleATO` controller运行时见[SimpleATO controller](../simpleato/controller-runtime.md)。

用途：为迁移期SimpleATO和Basic诊断/安全代码提供共享的控制骨架和值类型；所有速度决策经owner-scoped constraints取最小许可值，actuator用SI单位生成value movement；exception只Stall owning world并freeze。Basic不再包含具体`SimpleATO` controller或其可写mission/constraint状态。

## Tick owner、签名与单位

`AbstractATO.tick(deltaT: Double)`由Core physics worker调用，`deltaT`毫秒。固定顺序不可绕过：process commands → `plan(deltaSeconds)` → `validateBeforeMovement()` → actuator → Core move → active speed/distance；exception→`AtoStallReport`→world Stall→freeze。publication仍由AbstractATO原子保存；跨模块subclass只能一次取得protected immutable `PublishedAtoState`来构造自己的公开快照，不能分次读取可能跨epoch的字段，也不能改写内部publication reference。

```kotlin
interface TrainActuator {
    val maximumSpeedMetersPerSecond: Double
    fun step(
        currentSpeedMetersPerSecond: Double,
        demand: ControlDemand,
        deltaTimeSeconds: Double
    ): ActuationStep
    fun freeze(): ActuationStep = ActuationStep(0.0, 0.0)
}
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/TrainActuator.kt`。`ActuationStep(speedMetersPerSecond,distanceMeters)`字段finite且`>=0`；Kinematic实现constant accel/decel并用平均速度积分距离。

`ConstraintOwnerType`稳定token：`SYSTEM_SAFETY/SIGNALLING/OPERATIONS/ADVISORY`。`ConstraintManager.replace(owner,values)`原子替换，同owner duplicate `ConstraintKey(namespace,value)`拒绝；`permittedSpeedMetersPerSecond`取所有owner最小值。

## Constraint输入/结果矩阵

| constraint | 输入字段/单位 | permitted result |
| --- | --- | --- |
| `CurrentSpeedLimitConstraint` | `maximumSpeedMetersPerSecond>=0` | 当前固定上限 |
| `RouteSpeedLimitConstraint` | distance to lower limit m、target m/s | reaction + service braking curve |
| `StopConstraint` | remaining m、optional margin m | 到目标0 m/s；station可margin=0精确停车 |
| `FollowConstraint` | gap m、leading speed m/s | 扣safety distance/reaction后braking curve |
| `MovementAuthorityConstraint` | remaining authority m | 到authority前0 m/s |
| `DepartureHoldConstraint` | key | 恒0 m/s |

无active Mission时，`SimpleATO.plan`即使有line/infrastructure speed cap也必须输出zero demand。constraint是当前Tick value；owner replace empty即remove，不跨Tick累积stale map。

## 项目内正确示例

Kinematic actuator显式用seconds并返回trapezoid distance：

```kotlin
return ActuationStep(
    speedMetersPerSecond = nextSpeed,
    distanceMeters = (currentSpeedMetersPerSecond + nextSpeed) * 0.5 * deltaTimeSeconds
)
```

来源：`TrainActuator.kt`。例如0→1 m/s用1 s应移动0.5 m，而不是1 m。

## Failure与side effect

| 条件 | 结果 |
| --- | --- |
| invalid/nonfinite speed/distance/profile | constructor/`require`失败；不movement |
| duplicate key同owner | replace失败；旧owner set保持 |
| provider/plan/invariant exception | first world Stall report，speed/distance freeze 0 |
| coordinator已Stalled | 每Tick直接freeze，不再plan/move |
| operational cap无authority | cap只限制速度，不授权越过protection point |

## 禁止实现

- provider/controller直接写train speed；
- 修改其他owner constraints；
- 把speed cap当movement authority；
- stop目标出现立即强制0而不算braking；
- mutable/stale map跨Tick累积；
- worker exception继续movement或影响其他world。

## 必需测试与验证

- `PhaseBConstraintTest.kt`：`sqrt(v²+2ad)` braking、station zero margin、atomic replace/min wins、actuator 0.5 m、freeze。
- SimpleATO `SimpleATOTest.kt`：no mission zero、line/infrastructure layers、follow/stop/authority、Stall freeze。
- 每种constraint覆盖base(远目标)、boundary(0 distance)、bad(nonfinite/negative)。

```bash
./gradlew :Basic:test --tests '*PhaseBConstraintTest'
./gradlew :SimpleATO:test --tests '*SimpleATOTest'
```
