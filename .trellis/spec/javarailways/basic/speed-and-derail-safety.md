# Basic speed 与 derail safety 契约

## 适用场景与用途

触发：修改rail speed metadata、geometry speed calculation、operating speed signal、train speed profile或`SpeedSafetyMonitor`。

用途：区分运营限速与derail threshold；worker只生成epoch-tagged request，main owner在exact epoch验证后执行derail，禁止worker直接platform/train side effect。

## Owner、字段与单位

`SpeedLimitType`稳定token：`CURVE/SUBGRADE`。`SpeedLimitEntry(valueMetersPerSecond>0,type)`每type最多一个；`effectiveLimitMetersPerSecond()`取最小。`CurveSafetyMetadata.baseCurveDerailSpeedMetersPerSecond` nullable positive。

```kotlin
data class RailSpeedLimits(
    val operatingLimitMetersPerSecond: Double,
    val derailSpeedMetersPerSecond: Double?
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/safety/RailSpeedCalculator.kt`。

`RailSpeedCalculator.calculate(curve,requestedLimitMetersPerSecond,allowableCantDeficiencyMeters,derailLateralAccelerationMetersPerSecondSquared,physics)`从final tangent/normal求最小 operating/derail。标准gauge `1.435 m`、gravity `9.80665 m/s²`。straight无meaningful horizontal curvature时derail threshold为null。manual operating limit也不得高于geometry/subgrade。

`OperationalSpeedLimitSignalMaterializer`在每rail AB/BA entry各建一个direction-filtered signal，namespace固定`javarailways.speed-limit`；metadata empty也materialize release transition。Signal runtime-only，restore后重建。

### 准备阶段的静态限速读取

`RailInfrastructureMetadataStore.speedLimits(topology: TopologyState, railIds: Collection<UUID>): Map<UUID, Double?>`在已有transactionLock内按请求的不同rail捕获`effectiveLimitMetersPerSecond()`，输出不可修改的脱离store值。每个请求rail须在给定topology存在；有key且null表示没有有限限制，不允许省略key。只处理U个不同rail，时间/空间O(U)，不复制全world元数据。

`SUBGRADE`由RailWand的designSpeed保存，属于物理线路设计/上级速度；CURVE更低时effective取CURVE，没有CURVE时自然回退SUBGRADE，不新建速度配置。Basic负责metadata值与subset捕获，当前world/exact-root校验及公开Java入口归[Bukkit附属API](../../bukkit-plugin/backend/interaction-render-persistence.md#静态轨道限速子集读取)，信号扫描距离计算归SimpleSignals。用户确认该速度不在运行时变化，不新增速度事件或tick读取；轨道编辑后的重准备沿现有流程，不将静态数据假装成同physics epoch观测。

回归必须断言双限制最小值、仅SUBGRADE、无metadata的显式null、缺轨道拒绝、subset去重以及store改变后旧map值和只读性不变。此查询不写存档、不改元数据形状或版本，不计算新的限速或脱轨阈值。

## Worker→main request contract

```kotlin
data class DerailRequest(
    val trainId: String,
    val reason: String,
    val observedPhysicsEpoch: Long
)
```

`SpeedSafetyMonitor.Config(derailEnabled:Boolean,toleranceMetersPerSecond>=0,graceTicks>0)`。process distinct trains可并行；same/older epoch不重复计grace；threshold取frame所有swept spans最小值并加train curve offset后受subgrade约束。

| 条件 | worker result | main apply |
| --- | --- | --- |
| disabled/derailed/no threshold | reset consecutive count | 无request |
| speed <= threshold+tolerance | count=0 | 无request |
| overspeed未满grace | count+1 | 无request |
| 达grace | enqueue一次epoch-tagged request，count=0 | await exact ticket后验证train current frame epoch相等再derail |
| stale request/frame epoch不同 | drain可见但不apply | drop/record，不derail新state |
| operating overspeed但未超derail | ATO限制速度 | 不等同derail |

## 项目内正确示例

Monitor从完整swept spans取最严格threshold：

```kotlin
val derailSpeed = frame.sweptSpans.asSequence()
    .mapNotNull { span -> effectiveCurveDerailSpeed(train.id, span.railId) }
    .minOrNull()
    ?: return TrainState(epoch, 0)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/safety/SpeedSafetyMonitor.kt`。它防止长列车head已进入宽松rail时忽略tail仍占用的严格curve。

## 禁止实现

- worker直接`train.derail`、调用Bukkit或修改world；
- same epoch重复消耗grace；
- 只看head/current rail；
- operating cap当derail threshold；
- manual curve cap超过geometry derail/subgrade；
- Signal持久化或restore后不重建release transition；
- epoch不匹配仍apply request。

## 必需测试与验证

- `RailSpeedCalculatorTest.kt`：straight/null、cant direction、operating<=requested、derail/subgrade、units。
- `OperationalSpeedLimitSignalTest.kt`：AB/BA filter、entry token、empty release/materialize idempotent。
- `SpeedSafetyMonitorTest.kt`：grace、same epoch、distinct train、swept min、offset、stale drain。
- Bukkit `WorldManager` test：await ticket + exact frame epoch gate + main derail。

```bash
./gradlew :Basic:test --tests '*RailSpeedCalculatorTest' --tests '*OperationalSpeedLimitSignalTest' --tests '*SpeedSafetyMonitorTest'
./gradlew :BukkitPlugin:test --tests '*TrainDynamicsTest' --tests '*WorldManager*'
```
