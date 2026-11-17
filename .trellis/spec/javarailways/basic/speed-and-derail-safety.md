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

### 控制周期内的整车覆盖限速查询

`basic.api.ato.nativeBodySpeedLimits(train, frame): Map<UUID, Double>`同步读取调用方已捕获的
`TrainFrameSnapshot.bodyTraversal`，按rail ID去重并读取每轨既有effective limit，返回本次新建map，单位m/s。
无有限限制的轨道不增加条目；metadata后续变动不修改已返回数值。覆盖按完整保守车身而非车头或转向架落点判断，
直到后续帧显示车尾清除，调用方才移除限制；AB/BA一致。

frame必须匹配train ID、未脱轨且bodyTraversal非空，占用轨道必须仍可解析，失败沿原check异常返回，
不从live bogie补全位置。调用由物理worker在原有决策时机执行，函数不重新捕获frame、不写metadata、
不注册信号或持久化状态。时间O(B)、临时空间O(B+U)，B为遍历片段数，U为不同rail数。
SimpleATO自动/手动路径消费同一入口，把结果接入普通参考与ATP当前目标；前方信号扫描与控制策略仍归调用方。
真实Basic设备、AB/BA短轨和车尾清除回归保留在`SimpleATO`的`NativeBodySpeedLimitsTest`。

### 实时单轨、车头上下文与限速转换

`basic.api.ato.nativeRailSpeedLimit(rail)`在原simulation/physics读取上下文同步返回既有metadata的
effective CURVE/SUBGRADE最小值，单位m/s；null表示没有有限限制。它只做一次metadata读取，不重新计算几何、
捕获frame、遍历路径、缓存或改变持久化格式；返回标量不随后续metadata改变。复杂度为O(1)查找加既有限速项最小值计算。

同包`nativeHeadSpeedLimitContext(train)`在调用时只读一次live `train.headLocation`，返回原Core API rail及物理facing，
不查询metadata或published frame。SimpleATO在前视及restriction更新之后调用，继续仅在首次、rail ID变化或facing变化时
读取单轨限制；不得为了去除internal type而改用本轮更早捕获的frame或增加每tick metadata读取。
不提供物理facing时保留原`SimpleATO requires a facing-aware train head location`异常，底层getter失败原样传播。
context中的rail仍是live引用，只允许原owner阶段使用，不作为跨线程位置快照。两项查询不注册owner、不调度或授予资源。

`basic.api.rail.OperationalSpeedLimitTransition : RailObject`由既有`OperationalSpeedLimitSignal`直接实现，
不包装或更换对象身份。`speedLimitMetersPerSecond`仍在getter调用时读取转换结果，null表示解除此前转换上限；
`appliesTo(ViewDirection)`保留原方向语义，ref/location/lifecycle仍归同一RailObject。
对象来源/ACTIVE/方向过滤和消费时机保持原调用路径：自动路径先保留原对象，PASS阶段按方向检查后更新当前上限，
当前可见转换按观察位置facing调用同一纯`appliesTo`方向判断后，才读取限速并按距离生成前方约束；
这是generic RailObjectStore对象也能实现转换能力所需的检查，不过滤或删除其感知事件。原内建静态转换已由view过滤方向。
人工路径先查询每段metadata，再枚举该段方向适用的静态对象、过滤ACTIVE、
按弧长排序，第一段取当前位置之前最后一项（release不回退到更早限制），再处理双侧包含的前方区间，最后读取动态车端。
不新增对象源、扫描、默认策略或跨tick缓存，RailObject及query异常仍在原阶段传播。

`NativeRailSpeedLimitsTest`覆盖实际metadata的双限制最小值、SUBGRADE及release、值脱离store和live head单次读取；
`OperationalSpeedLimitSignalTest`覆盖公开转换能力、原对象身份和双向过滤；SimpleATO自动PASS回归验证精确ref的
进入及release，人工回归验证当前release、方向过滤与前方目标，原逐段读取和车尾清除测试继续约束采样时序。

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
