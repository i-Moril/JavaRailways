# 限速系统、Signal、Rail Metadata 分层重构计划

## 任务状态

- 类型：跨 Core / Basic / BukkitPlugin / 时刻表模拟的分阶段重构
- 当前状态：仅设计，尚未开始实现
- 建议分为 5 组提交；每组必须独立通过测试
- 生产兼容策略：开发期数据使用一次性迁移，不保留长期 runtime compatibility path

---

## 一、背景与现状问题

当前限速系统存在以下问题：

1. `SpeedSafetyMonitor.evaluatePublishedEpoch()` 在 `WorldManager.tickEnd()` 主线程串行扫描全部列车及 swept spans。真正要求主线程的只有 `train.derail()`、控制器移除和事件发布，纯限速比较不应占用主线程。
2. `Core RailMetadata` 混合了物理、Basic 运营和 Bukkit/RailWand authoring 数据：
   - 设计速度、曲线半径、超高
   - 并行轨道和逻辑 Rail assembly
   - RailWand 分割 lineage、坡度锁
   - 道岔运营参数
   - 方向限制
   - 运营限速和脱轨速度
3. `SimpleATO` 直接读取 `rail.metadata.speedLimitMetersPerSecond`，无法表达限速开始/解除、方向差异以及未来的列车特定限速。
4. 曲线运营限速、路基限速和物理脱轨阈值混在同一组标量字段中。
5. 世界恢复时 `RailWorldFactory` 在平台线程串行重算旧 Rail metadata 限速。
6. 时刻表尚未给实际基础设施线路配置统一运营最高速度；实际运营和隔离模拟没有共享明确的线路限速解析器。

---

## 二、已经确认的产品决策

以下决策在实现过程中不得自行改变：

1. `SpeedLimitMetadata` 是限速项列表；每项至少包含 `value` 和 `type`。
2. 当前限速类型只有：
   - `CURVE`：曲线导致的运营限速。
   - `SUBGRADE`：路基/基础设施上级限速，是其他限速不能突破的硬上限。
3. 默认运营限速 Signal 必须提供 `getSpeedLimit(train)`，但当前对所有列车返回相同结果。
4. `curveSpeedLimitOffset` **不得改变默认运营限速 Signal 的限速**。
5. `curveSpeedLimitOffset` 当前只改变曲线物理脱轨速度，并且有效结果不得超过 `SUBGRADE`。
6. 日后若需要列车特定运营限速，应新增另一种 Signal 能力或子类，不得悄悄改变默认 Signal 语义。
7. 时刻表线路最高速度当前统一适用于所有列车；本轮不实现 `trainSelector`。
8. 未来可在时刻表线路限速项上增加 `trainSelector`，并使用第一条匹配规则，但不属于本任务。
9. 必须把其他 metadata 从不应耦合的层中拆出；不能只迁移三个速度字段。
10. Signal 是持久化 metadata 的运行时派生物；本轮不为 `Signal` 引入 JVM 类名或反射式多态持久化。

---

## 三、目标分层

### 3.1 Core

Core Rail 最终只负责：

- 不可变曲线几何
- RailView 拓扑
- Signal / TrainEnd 等运行时 RailObject
- 列车占用
- 物理 epoch 与帧发布

本任务完成后应删除 Core 的通用 `RailMetadata` 和 `Rail.metadata` 属性。

Core 不再持有：

- RailWand 曲线模板、半径、超高设置
- assembly / parallel 信息
- 坡度锁
- split lineage
- 道岔保护参数
- 方向限制
- 设计速度、运营限速、脱轨速度

`RailTopologyEditor` 只执行几何和拓扑替换。分割 lineage 与 authoring 校验由 Basic 层负责。

### 3.2 Basic

Basic 增加 world-local 基础设施 metadata store：

```kotlin
data class RailInfrastructureMetadata(
    val authoring: RailAuthoringMetadata? = null,
    val operating: RailOperatingMetadata = RailOperatingMetadata(),
    val speedLimits: SpeedLimitMetadata = SpeedLimitMetadata(emptyList()),
    val curveSafety: CurveSafetyMetadata = CurveSafetyMetadata()
)

class RailInfrastructureMetadataStore
```

建议职责：

```text
RailAuthoringMetadata
  - curveKind
  - curveRadiusMeters
  - automaticSuperelevation
  - maxSuperelevationMeters
  - allowableCantDeficiencyMeters
  - designSpeedMetersPerSecond
  - parallel metadata
  - logical assembly metadata
  - split lineage
  - endpoint grade locks

RailOperatingMetadata
  - allowedDirection
  - turnout endpoint protection / locking settings

SpeedLimitMetadata
  - CURVE / SUBGRADE 运营限速项

CurveSafetyMetadata
  - baseCurveDerailSpeedMetersPerSecond
```

### 3.3 BukkitPlugin

BukkitPlugin 只负责：

- RailWand UI 与输入校验
- 调用 Basic 曲线/限速服务
- 编排 Core 拓扑、Basic metadata、派生 Signal、渲染 sidecar 的事务提交
- Bukkit 展示实体和配置文件

Bukkit 渲染数据继续保存在 `RailRenderMetaStore`，不得混入 Basic 运营 metadata。

---

## 四、核心速度模型

```kotlin
enum class SpeedLimitType {
    CURVE,
    SUBGRADE
}

data class SpeedLimitEntry(
    val valueMetersPerSecond: Double,
    val type: SpeedLimitType
)

data class SpeedLimitMetadata(
    val entries: List<SpeedLimitEntry>
)

data class CurveSafetyMetadata(
    val baseCurveDerailSpeedMetersPerSecond: Double? = null
)
```

校验规则：

- `valueMetersPerSecond` 必须有限且 `> 0`。
- 同一 `SpeedLimitMetadata` 中每种 type 最多出现一次。
- 最终基础设施运营限速为所有当前条目的最小值。
- `SUBGRADE` 是硬上限；`CURVE`、未来的列车特定限速和曲线脱轨 offset 都不能突破它。
- 普通直线内部段通常只有 `SUBGRADE`。
- 五段式中间曲线段通常同时包含 `SUBGRADE + CURVE`。
- 曲线物理脱轨阈值不伪装成第三种运营限速，保存在 `CurveSafetyMetadata`。

---

## 五、分阶段实施

# Phase 1：拆除 Core RailMetadata 并迁移到 Basic

## 目标

在不改变现有限速行为的前提下，先完成分层和持久化迁移。

## 工作项

1. 在 `BasicWorldData` 增加 `RailInfrastructureMetadataStore`。
2. 在 `BasicInfrastructureSnapshot` 增加按 Rail UUID 保存的 metadata records。
3. 将所有消费者改为查询 Basic store：
   - PathFinder / RoutePlanner 的方向限制
   - RailWand 道岔几何维护 / PlannedRoute 的道岔参数
   - RailWand authoring / assembly / grade lock
   - RailVisualBootstrap 的 assembly 查询
   - RailObjectWand 的方向限制编辑
   - SpeedSafetyMonitor 的曲线安全数据
4. 将 split lineage 管理从 Core `RailTopologyEditor` 上移到 Basic/RailWand 操作层。
5. 删除 Core `RailMetadata`、其所有嵌套 metadata 类型，以及 `Rail.metadata`。
6. 保证 metadata store 与 Core Rail 删除、替换、UUID 迁移同步。

## 持久化迁移

这是破坏性变化，必须升级最小独立 Core infrastructure schema，并由
`09-24-explicit-rail-endpoint-connections`任务的独立文件迁移工具执行一次性配对迁移。
运行中插件不再提供临时`/jr migrate`命令；当前代码只保留无文件副作用的纯转换器。

迁移必须：

- 同时读取配对的 Core infrastructure 与 Basic infrastructure。
- 把旧 Core Rail metadata 写入新的 Basic records。
- 旧 `designSpeed` 迁移为 authoring design speed 和默认 `SUBGRADE`。
- 旧有效限速低于 design speed 时迁移为 `CURVE`。
- 旧 derail speed 迁移为 `CurveSafetyMetadata`。
- 输出审计摘要：Rail 数、各 metadata 类型数、孤立引用、无法分类项。
- apply 前创建不可被本次轮转覆盖的恢复点。
- dry-run 不写文件。
- runtime 只接受 current model，不长期双读旧 Core metadata。

## 验收

- Core 模块不存在 RailWand / assembly / turnout / speed metadata。
- 现有寻路、方向限制、道岔和 RailWand 行为保持一致。
- 新 Basic snapshot round-trip 完整。
- metadata 引用不存在 missing Rail。
- 固定旧 fixture 可通过迁移工具转换。

---

# Phase 2：方向相关的运营限速 Signal 与 ATO 切换

## 目标

用可扫描、可提前制动、可解除的 Signal 替代 ATO 对 Rail 标量限速的直接读取。

## Signal 设计

在 Basic 中实现：

```kotlin
class OperationalSpeedLimitSignal : Signal {
    val appliesTo: ViewDirection
    val transitionId: String
    val resultingLimits: SpeedLimitMetadata?

    fun getSpeedLimit(train: Train): Double?
}
```

语义：

- `resultingLimits != null`：通过该信号后切换到给定基础设施限速集合。
- `resultingLimits == null`：解除基础设施限速，ATO 回到线路最高速度/列车物理最高速度。
- 当前默认实现的 `getSpeedLimit(train)` 不读取列车 offset，对所有列车相同。
- `Train` 参数是未来列车特定 Signal 的扩展点。

## 双方向边界

曲线限制区间为 `S → E` 时：

```text
AB：S 应用 CURVE，E 解除 CURVE/恢复 SUBGRADE
BA：E 应用 CURVE，S 解除 CURVE/恢复 SUBGRADE
```

Core Signal 扫描必须按 `ViewDirection` 过滤，不能仅依赖 collection 反序。

## 派生策略

Signal 不直接持久化：

- 世界加载完成后从 Basic speed metadata materialize。
- RailWand topology transaction 成功时重建受影响区间的 Signal。
- undo/redo、删除、曲线重建同步重新 materialize。
- Signal ref 必须由稳定的 Rail/区间/方向/边界 identity 生成，不能使用 JVM 类名。

## ATO

1. 删除 `SimpleATO` 对 `rail.metadata.speedLimitMetersPerSecond` 的读取。
2. 扫描前方 APPLY Signal，并在其距离处生成 `RouteSpeedLimitConstraint`，确保提前制动。
3. 通过 APPLY 后保存当前基础设施限速。
4. 通过 RELEASE 后恢复上层限速。
5. ATO 初始化、恢复、传送、换向时，从当前位置对应 Basic metadata 重建活动限速，不能要求列车必须先经过入口 Signal。
6. 当前阶段解除后暂时回到 `actuator.maximumSpeedMetersPerSecond`；Phase 5 再接入线路上限。

## 验收

- AB、BA 均正确应用和解除。
- 在区间内部恢复的列车立即获得正确限制。
- 默认 Signal 对不同列车返回相同值。
- `curveSpeedLimitOffset` 不改变 Signal 返回值。
- ATO 能在入口前按服务制动率制动。
- ATO 不再直接读取 Rail 标量限速。

---

# Phase 3：curveSpeedLimitOffset 与异步超速检测

## 目标

增加列车曲线脱轨速度偏移，并把纯超速计算移出主线程。

## 列车模型

```kotlin
data class TrainSpeedProfile(
    val curveSpeedLimitOffsetMetersPerSecond: Double = 0.0
)
```

Bukkit 实际列车与隔离模拟必须从同一列车控制/模型配置得到该值，默认值为 `0.0`。

## 已确认语义

```text
effectiveCurveDerailSpeed = min(
    baseCurveDerailSpeed + curveSpeedLimitOffset,
    subgradeLimit
)
```

- 只影响曲线脱轨速度。
- 不影响默认运营限速 Signal。
- 没有 CURVE/curve safety metadata 时忽略。
- 负 offset 若允许，最终结果仍必须 `> 0`；配置加载时严格校验。
- SUBGRADE 永远是硬上限。

## 线程模型

将当前 `SpeedSafetyMonitor.evaluatePublishedEpoch()` 拆为：

```text
Physics worker / physics-owned stage
  - 读取同一 physics epoch 的速度和 swept spans
  - 查询 Basic speed/safety metadata
  - 解析 TrainSpeedProfile
  - 维护每列车 grace epoch
  - 生成带 observedPhysicsEpoch 的 DerailRequest

Main thread publish stage
  - 校验 request epoch 仍是当前已发布 epoch
  - 调用 train.derail()
  - 移除控制器、发布帧和事件
```

要求：

- skipped epoch 不推断为多次超速。
- 重复/旧 epoch 是 no-op。
- grace 状态由 physics owner 单写，不能共享普通 `HashMap` 给多个 worker。
- 可按 train 分片计算，但最终 epoch publication 保持确定性。

## 验收

- `WorldManager.tickEnd` 不再扫描全部列车和 swept spans。
- 主线程 `speed_safety` 指标只包含 request apply/event publication。
- offset 只改变 CURVE derail threshold。
- offset 无法突破 SUBGRADE。
- grace、恢复、删除列车、换 Rail 情况有固定测试。

---

# Phase 4：RailWand 限速滑块和事务维护

## 目标

让 RailWand 创建和编辑 `CURVE`、`SUBGRADE`，并保证 Core 拓扑、Basic metadata、Signal 和渲染原子一致。

## UI

创建设置和轨道编辑页增加：

```text
曲线限速 CURVE
路基限速 SUBGRADE
```

建议约定：

- CURVE 为 `0`：根据曲率、超高、允许欠超高自动计算。
- CURVE 非零：人工指定运营限速，但不得超过物理曲线安全阈值。
- SUBGRADE 必须有限且 `> 0`。
- 最终运营限制始终取所有条目的最小值。
- 调整半径、超高或设计速度时，自动 CURVE 重新计算；人工 CURVE 保持显式值并重新校验。

五段式内部 Rail：

```text
前直线：SUBGRADE
中间曲线：SUBGRADE + CURVE + CurveSafetyMetadata
后直线：SUBGRADE
```

## 一次事务必须覆盖

1. Core internal Rails
2. Basic authoring metadata
3. Basic operating metadata
4. Basic speed/safety metadata
5. 派生的两个方向 Signal
6. Rail render sidecar
7. destination / RailObject UUID 迁移
8. undo/redo snapshot

外部观察者只能看到提交前或提交后的完整状态。

## 展示

RailWand 限速显示应区分来源：

```text
80 km/h CURVE
120 km/h SUBGRADE
```

只显示实际存在的条目，不显示内部锚点或 Signal UUID。

## 验收

- 修改 SUBGRADE 同步更新整组 metadata 和 Signal。
- 自动 CURVE 随几何重算。
- undo/redo 恢复完整 metadata 和派生 Signal。
- 删除、重建后无孤立/重复 Signal。
- 并行逻辑组两方向均正确。
- topology transaction 并发测试不暴露部分状态。

---

# Phase 5：统一的时刻表线路最高速度与模拟一致性

## 当前范围

本阶段只实现每条线路一个、适用于所有列车的最高速度；不实现 `trainSelector`。

## 模型

`TimetableService` 增加实际基础设施线路引用：

```kotlin
val lineId: LineId?
```

不得复用当前 presentation-only `diagramLineId`。

时刻表增加：

```kotlin
data class TimetableLineSpeedLimit(
    val lineId: LineId,
    val maximumSpeedMetersPerSecond: Double
)
```

校验：

- 每个 lineId 最多一项。
- value 必须有限且 `> 0`。
- service 引用的 lineId 必须存在于 Basic `LineStore`。
- 需要自动运行的 service 必须能够解析线路最高速度；缺失时配置校验失败，而不是静默使用任意默认值。

## ATO 约束层级

```text
最终允许速度 = min(
    列车物理最高速度,
    当前时刻表线路最高速度,
    当前 SUBGRADE,
    当前 CURVE,
    停车 / 进路 / 跟车 / 外部命令约束
)
```

通过基础设施解除 Signal 后：

```text
恢复到 min(列车物理最高速度, 当前线路最高速度)
```

## 共享解析器

提取单一实现：

```kotlin
class LineMaximumSpeedResolver
```

以下路径必须复用：

- TimetableDispatcher 实际运营
- SimpleATO
- IsolatedTimetableSimulationFactory
- DetailedTrainTravelSimulator

不得在模拟路径复制简化规则。

## 未来扩展注释

在当前 line context 和 resolver 附近加入明确注释：

```text
Future through-running support must update currentLineId at an explicit line boundary
and re-resolve the speed limit for the line currently being operated. Never retain the
origin line's limit after the train has entered another line.
```

未来增加列车差异化时：

- 给线路限速项增加 `trainSelector`。
- 允许同一 lineId 多条有序规则。
- 使用第一条匹配规则。
- 该扩展不属于本任务。

## 验收

- 线路统一限速在实际运营和隔离模拟中一致。
- CURVE 解除后恢复线路速度，而不是直接恢复列车绝对最高速度。
- SUBGRADE 仍能压低线路速度。
- 未配置线路上限时 validator 明确报错。
- runtime 与 simulation 的 golden scenario 速度曲线一致。

---

## 六、提交分组建议

1. `refactor(metadata): move rail infrastructure metadata to Basic`
2. `feat(signals): manage operational speed limits by direction`
3. `refactor(safety): evaluate curve overspeed off the main thread`
4. `feat(rail-wand): edit layered speed limits transactionally`
5. `feat(timetable): enforce per-line operating speed`

每组提交都必须保持工作区可构建、测试通过，不允许把无法编译的跨阶段中间状态提交到分支。

---

## 七、统一验证命令

最终至少运行：

```bash
./gradlew :Core:test :Basic:test :BukkitPlugin:test :BukkitPlugin:shadowJar
```

阶段专项测试至少覆盖：

- Core Rail metadata 已完全删除的架构检查
- Basic infrastructure current round-trip
- 固定旧 snapshot migration fixture
- AB / BA APPLY 与 RELEASE Signal
- 区间内恢复和换向
- 默认 Signal 与 offset 隔离
- physics epoch / grace / stale request
- RailWand transaction 并发可见性
- CURVE / SUBGRADE 滑块校验
- timetable line speed runtime/simulation parity

---

## 八、最终完成条件

只有以下条件全部成立，任务才算完成：

1. Core 不再声明或持有通用 `RailMetadata`。
2. 所有 Rail authoring / operating / speed / safety metadata 位于 Basic。
3. BukkitPlugin 不再作为 metadata 业务模型的所有者。
4. 默认 `OperationalSpeedLimitSignal.getSpeedLimit(train)` 对所有列车一致，且不读取 `curveSpeedLimitOffset`。
5. `curveSpeedLimitOffset` 只影响曲线脱轨阈值，并受 SUBGRADE 上限约束。
6. ATO 通过方向相关 Signal 预告、应用和解除基础设施限速。
7. 主线程不再执行全量超速扫描。
8. RailWand 能编辑 CURVE 与 SUBGRADE，并事务式维护派生 Signal。
9. 时刻表每条线路有统一全列车最高速度。
10. 实际运营与隔离模拟复用同一线路限速解析器。
11. 旧 runtime compatibility path 已删除，开发数据通过可审计迁移工具转换。
12. 全部统一验证命令通过。
