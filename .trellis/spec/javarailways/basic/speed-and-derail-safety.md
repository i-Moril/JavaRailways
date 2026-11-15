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

`RailSpeedCalculator.calculate(curve,requestedLimitMetersPerSecond,allowableCantDeficiencyMeters,derailLateralAccelerationMetersPerSecondSquared,physics)`从final tangent/normal求最小 operating/derail。标准gauge `1.435 m`、gravity `9.80665 m/s²`。straight无meaningful horizontal curvature时derail threshold为null。`RailSpeedPhysics.curveSpeedLimitMultiplier`是全局运营速度倍率（默认1.0），乘在几何运营速度上，不改变独立derail公式；自动结果不得超过derail阈值，最终effective仍取CURVE/SUBGRADE最小值。人工输入不乘倍率；已有人工CURVE作为原始配置保留，物理配置改变后由独立安全监测使用刷新后的脱轨阈值。

`OperationalSpeedLimitSignalMaterializer`在每rail AB/BA entry各建一个direction-filtered signal，namespace固定`javarailways.speed-limit`；metadata empty也materialize release transition。Signal runtime-only，restore后重建。

### 自动限速刷新（CURRENT）

自动CURVE及curveSafety是几何和当前物理配置的派生快照，已保存值不是自动限速的权威来源。`RailDerivedSpeedMetadata`根据authoring的允许欠超高、自动标志与实际curve计算；不得把已保存自动CURVE再乘倍率。人工CURVE和SUBGRADE原值保留，无水平曲率的自动轨道删除CURVE并清空派生脱轨阈值。

真实world构造在Basic infrastructure恢复后、默认限速Signal materialize及world发布前刷新；几何编辑（包括拆分、合并）使用相同公式。运行阶段不每tick遍历几何；隔离模拟使用来源世界已刷新的基础设施快照。无authoring的metadata不猜测自动输入。持久化形状不变，不增加旧数据reader或迁移。

回归覆盖倍率1.0/1.5、独立derail上限、物理配置变化后加载、重复刷新不累计、人工与SUBGRADE保留、直线release及Signal/静态查询一致。

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
- 建造/编辑时接受超过当前自动可达速度的人工请求；加载刷新时不得以此改写既有人工CURVE输入；
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
