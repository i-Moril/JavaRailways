# JavaRailways WebUI 运行线、交路与时刻表模拟改进计划

## 1. 核心结论

运行线和交路只是 authoring/editor 模型，用于让玩家组织和调整运行图；它们不是新的运行时模型。

完整数据流应为：

```text
WebUI EditorDocument
├── 唯一运行线实例
├── 交路
├── 初始化/取车/折返/回库函数
└── 展示数据
             │
             ▼ WebUI 确定性代码生成
Generated JS + generation manifest
             │
             ▼ 现有 ScriptWorker 沙盒编译
SavedTimetableV1 + CompilationTrace
└── 每条交路生成一个 TimetableService
             │
             ▼ normalize / restore / validate / dispatch
现有 TimetableDispatcher
```

因此：

- 不新增运行时 `TrainRun`、`VehicleDuty` 或 `DutyElement`。
- 不要求 Dispatcher 理解运行线或交路。
- 不修改 `TimetableService` 的核心执行语义。
- 运行线和交路的唯一归属、连续性等规则先在 EditorDocument 中校验，再由生成的 JS 和现有 validator 验证编译产物。
- WebUI 不另写一套 steps 编译器，而是确定性生成 JS，复用 ScriptWorker 作为唯一 DSL 编译器。
- 函数通过生成的 JS 在 ScriptWorker 编译期展开为现有 `TimetableStep`、`TimetableAction` 和 `initialPositions`。
- EditorDocument 是可编辑源数据，Generated JS 是构建产物，发布内容是编译后的 `SavedTimetableV1`。
- Basic 时刻表模拟器直接模拟编译产物，以尽量保持模拟与真实运行一致。

已经完成的边界修正继续有效：

- `arrivalTick` 是展示数据，不进入核心运行时动作。
- `diagramLines` 是展示数据，不进入核心 `Timetable`。
- `diagramLineId` 是展示分组，不是基础设施 `LineId`。
- 旧 JS `lineId`、旧 snapshot 和旧 WebUI 草稿保持兼容。

---

## 2. 编辑模型中的术语和关系

### 2.1 运行线

运行图上从起点到终点的一条具体车次，是唯一运行线实例。

```ts
interface AuthoringRun {
  id: string;
  calls: AuthoringCall[];
  routeHints: string[];
  serviceType: 'PASSENGER' | 'DEADHEAD';
  diagramLineId?: string;
}
```

规则：

- 一条具体运行线在一个草稿中最多属于一个交路。
- 草稿允许暂时没有交路。
- 发布前所有运行线必须分配到且只分配到一个交路。
- 调图是把运行线从交路 A 原子移动到交路 B，不是共享引用。
- 复制运行线必须创建新的运行线 ID。
- 可以复用运行线模板，但模板每次生成新的运行线实例。
- 经过运营线路的回送必须由用户显式建立为一条 `DEADHEAD` 运行线。

运行线不声明加速度、制动等级等复杂车辆性能要求。

### 2.2 交路

交路是在编辑器中对多条唯一运行线和连接函数的有序编排。

```ts
interface AuthoringDuty {
  id: string;
  selector: AuthoringTrainSelector;
  elements: AuthoringDutyElement[];
}

type AuthoringDutyElement =
  | { type: 'RUN'; runId: string }
  | { type: 'FUNCTION'; call: AuthoringFunctionCall };
```

关系：

```text
实体列车/selector → 交路 → 多条唯一运行线
```

一个交路编译成一个 `TimetableService`：

- `AuthoringDuty.id` → `TimetableService.id`
- duty selector/取车函数 → `trainSelector` 或首个取车 action
- duty elements → 有序 `TimetableStep`
- duty activation → `TimetableServiceActivation`

### 2.3 唯一归属

`AuthoringDuty.elements` 是运行线归属的唯一权威来源。编辑器加载后建立派生索引：

```ts
Map<runId, dutyId>
```

不要同时保存 `run.dutyId` 和 `duty.elements[].runId`，避免双向状态不一致。

---

## 3. Authoring 文档与编译产物分离

### 3.1 EditorDocument

WebUI 仓库为草稿保存独立版本化的可编辑源格式：

```ts
interface TimetableAuthoringDocumentV1 {
  formatVersion: 1;
  id: string;
  periodDays: number;
  runs: AuthoringRun[];
  duties: AuthoringDuty[];
  initialization: AuthoringInitializationCall[];
  diagramLines: DiagramLineV1[];
  arrivalOverrides: ArrivalOverrideV1[];
}
```

它只用于：

- WebUI 的版本化草稿和已发布源 revision。
- 交路编辑器。
- 确定性 JS 代码生成。
- 编译错误定位。
- 模拟结果映射和运行图展示。

它由 WebUI 仓库持久化，不进入世界存档、`TimetableStore` 或 Dispatcher。Bukkit 只保存编译后的 `Timetable` 及必要的源 revision/hash。

### 3.2 编译结果

```kotlin
data class AuthoringCompilationResult(
    val generatedSource: String,
    val sourceHash: String,
    val savedTimetable: SavedTimetableV1,
    val trace: TimetableCompilationTrace,
    val issues: List<CompilationIssue>
)
```

`SavedTimetableV1` 是现有运行时边界。Generated JS 和 trace 由 WebUI 仓库随 revision 归档，只用于重现构建、模拟报告和错误定位。

Generated JS 不是 EditorDocument 的唯一源数据，也不需要反向解析。用户若手工修改生成代码，应复制为独立高级 JS 脚本，不再承诺返回结构化交路编辑器。

### 3.3 Generation manifest 与 CompilationTrace

```kotlin
data class CompiledStepOrigin(
    val serviceId: String,
    val stepIndex: Int,
    val dutyId: String,
    val elementId: String,
    val runId: String? = null,
    val functionCallId: String? = null
)

// 生成器可在 JS 中发出不产生运行时动作的 origin 作用域：
// t.origin("duty:l1-a/run:l1-up-01", () => { ... })
```

用途：

- WebUI generation manifest 记录 EditorDocument 元素到生成源码范围的映射。
- ScriptWorker 的 origin trace 记录生成 step 到 duty/run/function 的映射。
- 将 Basic validator 的 step 错误映射回运行线或函数。
- 将模拟延误映射回运行图上的运行线。
- 在 WebUI 中点击模拟事件定位对应编辑元素。
- 发布时只提交 `SavedTimetableV1`，运行时模型不理解 EditorDocument 或 trace。

---

## 4. 编译规则

### 4.1 发布前 authoring 校验

- 所有运行线恰好出现在一个交路中。
- 同一运行线不能出现在两个交路中。
- 所有运行线引用和函数引用存在。
- 交路不得跨 timetable 周期边界。
- 交路内运行线计划时间不得重叠。
- 前一运行线终点与后一运行线起点必须由函数正确连接。
- 车辆位置、朝向和停稳状态连续。
- 所有自定义函数都有模拟实现。

草稿允许上述结构暂时不完整，但必须显示 issue；发布必须零 error。

### 4.2 运行线编译

运行线编译成现有步骤片段：

```text
计划始发/站停/通过/终到
        ↓
DESTINATION_OPTIONS / STATION_DESTINATION_OPTIONS
DESTINATION
STOP / REVERSE 等现有动作
```

运行线只产生自己的运行步骤，不包含取车、跨运行线折返或回库。

### 4.3 交路编译

```text
AuthoringDuty
├── FUNCTION acquire
├── RUN l1-up-01
├── FUNCTION turnback
├── RUN l1-down-01
└── FUNCTION store
        ↓
TimetableService
├── TAKE_FROM_DEPOT
├── ...l1-up-01 steps
├── ...turnback steps
├── ...l1-down-01 steps
└── STORE_AT_DEPOT
```

编译器负责：

- 拼接步骤。
- 维持 tick 单调。
- 展开函数。
- 生成 `CHECK`、取车和回库动作。
- 检查函数前后状态。
- 生成 source map。
- 调用现有 `TimetableValidator` 验证最终产物。

### 4.4 初始化编译

已决定：初始化函数固定显示和编辑在对应交路的开头。编译器统一收集所有交路开头的初始化函数，编译结果汇总到现有：

```kotlin
Timetable.initialPositions
```

必须区分：

- `PlaceInitial`：epoch 启动/重置时改变车辆位置，编译到 `initialPositions`。
- `AcquireFromWaypoint`：运营时取得已存在车辆，编译到 service 取车 action。
- `AcquireFromDepot`：运营时从 depot 分配车辆，编译到 service 取车 action。

多个交路的初始化声明冲突时编译失败。

---

## 5. 函数系统

### 5.1 编排函数/宏

用户自定义函数优先作为编译期宏：

```js
function turnAtIdamachi(...) {
  return duty.sequence(
    duty.moveTo(...),
    duty.reverse(...),
    duty.moveTo(...)
  )
}
```

要求：

- 只在 WebUI/ScriptWorker 编译阶段执行。
- 最终展开为静态 `TimetableStep`、`TimetableAction` 或 `initialPositions`。
- Dispatcher 和模拟器不直接执行任意 JS。
- 编译产物不依赖原始函数继续存在。

### 5.2 注册式运行时函数

确实需要运行时执行的插件动作继续使用版本化 descriptor：

```kotlin
data class AuthoringFunctionDescriptor(
    val typeId: String,
    val version: Int,
    val parameterSchema: JsonObject,
    val inputStateSchema: JsonObject,
    val outputStateSchema: JsonObject,
    val simulationSupported: Boolean
)
```

它必须提供：

- 参数校验。
- 输入/输出状态投影。
- 编译为现有 action 的实现。
- 模拟实现。

没有模拟实现按 error 处理，阻止完整模拟通过和发布。

### 5.3 状态契约

```kotlin
data class AuthoringVehicleState(
    val location: String?,
    val facing: ViewDirection?,
    val stopped: Boolean,
    val availableTick: Long
)
```

函数编译投影：

```kotlin
data class FunctionCompilationProjection(
    val resultingState: AuthoringVehicleState,
    val minimumDurationTicks: Long,
    val compiledSteps: List<SavedTimetableStepV1>,
    val initialPositions: List<SavedInitialPositionV1>
)
```

状态只服务于编译校验；最终仍输出现有 runtime DTO。

---

## 6. 交路编辑器

### 6.1 推荐布局

```text
┌────────────────┬──────────────────────┬──────────────────┐
│ 未分配运行线   │ 当前交路             │ 属性与校验       │
├────────────────┼──────────────────────┼──────────────────┤
│ L1-UP-03       │ 初始准备/取车        │ selector         │
│ L1-DOWN-04     │ L1-UP-01             │ 函数参数         │
│ ...            │ 折返函数             │ 输入/输出状态    │
│                │ L1-DOWN-01           │ 时间余量         │
│                │ 回库函数             │ 模拟延误         │
└────────────────┴──────────────────────┴──────────────────┘
```

### 6.2 操作

- 创建、删除和重命名交路。
- 创建和编辑唯一运行线。
- 将未分配运行线拖入交路。
- 将运行线从交路 A 原子移动到交路 B。
- 插入取车、折返、站内连接、等待和回库函数。
- 调整交路元素顺序。
- 复制运行线时生成新 ID。
- 显示运行线所属交路、前序和后序。
- 显示位置、方向、时间余量和编译 issue。
- 支持撤销跨交路移动。

线路回送不得隐藏在普通函数中；若需要经过运营路线，用户必须创建 `DEADHEAD` 运行线。

---

## 7. 车辆 selector 与模拟 profile

selector 仅提供普通用户容易理解的：

- name 筛选。
- tag 筛选。

不要求用户声明：

- 加速度等级。
- 制动等级。
- 动力参数。
- 最低性能类别。

完整模拟开始前：

1. 汇总 authoring/编译产物中每一种不同 selector。
2. 要求用户为每个 selector 选择一个车辆模板或世界中已有车辆。
3. 从选定对象捕获 immutable simulation profile。
4. 缺少任意 selector 映射时不允许开始完整模拟。

模拟报告必须记录每个 selector 实际使用的模板/世界车辆。

---

## 8. Basic 时刻表模拟

### 8.1 输入与职责

完整模拟器位于 Basic 的运营/服务层，但输入是编译后的现有 `Timetable`，而不是 WebUI authoring 模型：

```text
AuthoringDocument
    ↓ compile
SavedTimetableV1 / Timetable + sourceMap
    ↓
Basic TimetableSimulationEngine
```

这样模拟验证的是实际将要发布和调度的产物。

模拟负责：

- service/交路执行顺序。
- 车辆 selector 和取得车辆。
- 站台、区间、折返线和 depot 资源。
- 取车、折返、等待、回库。
- 延误传播。
- 计划与模拟到发时刻。

source map 在模拟完成后把 service/step 事件映射回 duty/run/function。

### 8.2 快速运营模拟

建议目录：

```text
Basic/src/main/.../timetable/simulation/
```

特征：

- 确定性虚拟时钟。
- 优先事件队列。
- 无事件区间直接跳到下一事件。
- 不创建 Bukkit 实体。
- 不读取实时可变世界对象。
- 使用 immutable infrastructure/profile snapshot。

### 8.3 详细动力学

现有 `BukkitPlugin/http/IsolatedLineSimulator.kt` 的纯计算部分不应继续由 HTTP 层拥有。建议拆分：

```text
Basic
├── TimetableSimulationEngine
├── TravelTimeEstimator 接口
├── 运营事件和资源模拟
└── SimulationReport

Core、Basic 纯逻辑包或独立模块
├── TrainDynamics 纯计算
└── DetailedTravelTimeEstimator

BukkitPlugin
└── 捕获世界、线路和车辆 immutable snapshot
```

Basic 不依赖 Bukkit 实体或 HTTP 实现。

### 8.4 加速方式

“自定义 tickrate”表示提高模拟吞吐量，不改变逻辑 tick 时长：

- 运营层优先使用离散事件跳跃。
- 详细动力学保持固定 `0.05s` 积分步长。
- worker 可以在一次批处理中执行大量逻辑 tick。
- 提高吞吐量不得改变模拟结果。

### 8.5 与真实 Dispatcher 共享规则

长期逐步抽取：

```text
TimetableTransitionEngine
├── Clock
├── InfrastructureGateway
├── TrainOperationGateway
├── TravelTimeEstimator
└── EventSink
```

生产注入真实 World/ATO adapter；模拟注入 VirtualClock 和内存 gateway。第一版可以先实现独立模拟器，但必须使用同一个编译产物和共享 validator，并逐步减少规则漂移。

---

## 9. 到达时刻、手动覆盖和缓存

- 计划到达时刻默认采用最新有效模拟结果。
- 用户可以对单个到达时刻设置手动覆盖。
- WebUI 必须明确显示“模拟值”和“手动值”。
- 提供恢复模拟值操作。
- 拖动运行图到达点创建/更新手动覆盖。
- 拖动发车点修改运行线计划发车时刻。
- `arrivalTick` 继续只属于展示/authoring 数据，`restore()` 不将它放入核心动作。

缓存键至少包含：

- 线路/拓扑 revision。
- 车辆 profile。
- selector 到 profile 的映射。
- 区间和路径。
- 控制配置。
- 编译产物摘要。
- 模拟器版本。

任一输入变化时使对应缓存失效。

---

## 10. 模拟报告

```kotlin
data class SimulationEvent(
    val serviceId: String,
    val stepIndex: Int?,
    val plannedTick: Long?,
    val actualTick: Long,
    val delayTicks: Long,
    val cause: DelayCause?,
    val blockingResource: String?
)
```

通过 source map 在 WebUI 中转换为：

- dutyId。
- runId。
- functionCallId。

延误原因至少包括：

- `INSUFFICIENT_RUNNING_TIME`
- `INSUFFICIENT_DWELL_TIME`
- `TURNBACK_TIME`
- `LOCATION_MISMATCH`
- `DIRECTION_MISMATCH`
- `PLATFORM_CONFLICT`
- `ROUTE_CONFLICT`
- `DEPOT_UNAVAILABLE`
- `TRAIN_UNAVAILABLE`
- `PREVIOUS_RUN_DELAY`
- `CUSTOM_OPERATION`
- `UNSIMULATABLE_FUNCTION`

报告包括：

- 每条运行线计划与模拟到发时刻。
- 每条交路累计延误和最大延误。
- 延误传播链。
- 资源冲突和阻塞对象。
- 最小可行区间、停站和折返时间。
- 可用缓冲时间。
- selector 使用的模拟车辆及 profile。

第一版只给出问题和建议，不自动修改时刻表。

---

## 11. 旧格式处理

已决定不为只有 flat `TimetableService` 的旧时刻表提供交路编辑器兼容或迁移：

- 不实现 flat steps 到运行线/交路的自动反向推断。
- 不实现手动划分迁移向导。
- 不把旧格式兼容作为第一版或后续版本的验收要求。
- 新交路编辑器只打开和保存 WebUI 仓库中的 EditorDocument。
- 现有 `TimetableService` 继续作为新 EditorDocument 的最终编译产物，不新增 runtime duty schema。
- 自动生成 JS 保持只读；手工修改时复制为独立高级 JS 脚本，并放弃结构化往返保证。

已有旧数据若需要结构化编辑，由用户在新编辑器中重新建立；不为此增加编译器和 UI 复杂度。

---

## 12. 近期 WebUI 改进

完整交路编辑器之前可先实施：

1. 完成展示 `arrivalTick` 链路。
2. Graph 区分到达点和发车点。
3. 支持 `DoorSide.NONE`。
4. JS 编译结果另存为底层草稿。
5. 增加基础初始位置编辑。

在交路模型稳定后实施：

1. 运行线跨交路原子移动。
2. 复制运行线并生成新 ID。
3. 整体平移运行线时刻。
4. 重复生成 N 条运行线。
5. 复制交路结构并生成新的运行线实例。
6. 批量修改停站时间。

不要在现有 flat steps UI 上继续堆叠复杂批量操作。

---

## 13. 分阶段实施

### 阶段 0：ADR 和 EditorDocument schema

- 固化运行线唯一归属规则。
- 定义 `TimetableAuthoringDocumentV1`。
- 定义 generation manifest 和 ScriptWorker compilation trace。
- 明确 EditorDocument、Generated JS、presentation 和 runtime 边界。

### 阶段 1：确定性 JS 生成与编译协议

- WebUI 将一个 duty 生成一个 `timetable.service(...)`。
- 运行线生成对应的 DSL steps 调用片段。
- 函数生成 steps 调用或 `setInitialPosition(...)`。
- 生成前检查归属、时序、位置、方向和周期边界。
- Generated JS 通过受认证源码编译接口进入现有 ScriptWorker。
- ScriptWorker 支持 origin trace，编译后继续调用 normalizer 和现有 `TimetableValidator`。
- 相同 EditorDocument 和生成器版本必须产生字节一致的 JS。

### 阶段 2：交路编辑器

- 未分配运行线池。
- 交路列表和元素编辑。
- 跨交路原子移动。
- 函数插入和参数编辑。
- generation manifest 和 compilation trace 驱动错误定位。

### 阶段 3：Basic 快速模拟器

- 接收编译后的 `Timetable`。
- selector/profile 映射。
- VirtualClock 和事件队列。
- 车辆和基础设施资源。
- 延误传播和 SimulationReport。

### 阶段 4：详细动力学和缓存

- 抽离纯动力学计算。
- 定义 `TravelTimeEstimator`。
- 捕获 immutable snapshot。
- 实现 revision/profile/compiler/simulator 版本化缓存。

### 阶段 5：调图工具

- 模拟结果覆盖运行图。
- 到达时刻手动覆盖。
- 复制、平移、周期生成。
- 显式调整建议。

---

## 14. TDD 与验收

### Authoring/编译测试

- 同一运行线不能属于两个交路。
- 草稿允许未分配，发布拒绝任何未分配运行线。
- 交路不得跨周期边界。
- 复制运行线生成新 ID。
- 一个 duty 稳定生成一个 `timetable.service(...)`，并编译为一个 `TimetableService`。
- 相同 EditorDocument 和生成器版本产生字节一致的 JS。
- 函数经 ScriptWorker 展开后不在运行产物中保留可执行 JS。
- 初始化函数编译到 `initialPositions`。
- 运行线回送编译为普通运行步骤。
- 展示字段不进入核心 runtime model。
- generation manifest 和 compilation trace 能精确定位 service/step 来源。

### 模拟测试

- 模拟直接消费编译后的 `Timetable`。
- 缺少 selector/profile 映射时拒绝模拟。
- 无冲突方案零延误。
- 运行、停站或折返时间不足产生可解释延误。
- 多 service 资源冲突报告阻塞资源。
- 延误通过 source map 正确映射到运行线和交路。
- 同输入生成确定性相同结果。
- 批量加速不改变逻辑结果。
- 不可模拟函数产生 error。

### WebUI 测试

- 一条运行线只能位于一个交路。
- 跨交路拖动执行原子移动。
- 未分配运行线清晰可见。
- 到达点和发车点独立编辑。
- 模拟值与手动覆盖明确区分。
- 缓存失效后不展示过期模拟结果。

---

## 15. 设计护栏

- 运行线和交路只存在于编辑/编译模型。
- 不新增运行时 duty 模型。
- 不要求 Dispatcher 理解运行线归属。
- 一个交路编译为一个现有 `TimetableService`。
- 一条具体运行线最多属于一个交路。
- 不把运行线模板和实例混为一谈。
- 仅 ScriptWorker 沙盒执行生成 JS；浏览器、Dispatcher 和模拟器不执行 JS。
- 不把 `arrivalTick` 放回核心动作。
- 不把 `diagramLineId` 与基础设施 `LineId` 耦合。
- 不让 Basic 依赖 Bukkit 实体或 HTTP 实现。
- 不通过增大物理积分步长加速。
- 不静默反向推断旧 flat steps 的运行线边界。

---

## 16. 已确认设计决策

1. 草稿允许运行线没有交路；发布时所有运行线必须唯一分配。
2. 第一版不允许交路跨 timetable 周期边界。
3. selector 仅使用 name/tag；模拟时用户为每一种 selector 指定车辆模板或世界车辆。
4. 经过运营路线的回送必须由用户手动建模为运行线。
5. 到达时刻默认采用模拟结果，允许手动覆盖并积极缓存。
6. 自定义函数缺少模拟实现时按 error 处理。
7. 运行线和交路是编辑模型，最终编译为现有 `TimetableService`，不进入 runtime schema。
8. 初始化函数在对应交路的开头编辑；生成器统一汇总为顶层 `initialPositions` 调用，初始化冲突按 error 处理。
9. EditorDocument 由 WebUI 的版本化仓库持久化；Bukkit 只保存编译后的 Timetable 及必要的源 revision/hash。
10. WebUI 确定性生成只读 JS，复用现有 ScriptWorker 编译为 `SavedTimetableV1`；生成 JS 是构建产物，不作为结构化编辑的唯一源数据。
11. 不兼容或迁移旧 flat `TimetableService` 到交路编辑器；旧数据需要结构化编辑时由用户重新建立 EditorDocument。

## 17. 实施进度

已完成第一批基础能力：

- 新增 `TimetableAuthoringDocumentV1`、唯一运行线、交路、selector 和内置函数的前端类型。
- 新增发布前结构校验：运行线唯一归属、未分配、时间范围、交路重叠、位置连续和初始化位置约束。
- 新增确定性 JS 生成器；一个交路生成一个 `timetable.service(...)`。
- ScriptWorker 新增 `t.origin(...)`，输出 presentation-only `compilationTrace`。
- Basic snapshot codec 可传输 trace，但 `restore()` 不把它放入核心运行时。
- WebUI 新增独立版本化 EditorDocument 仓库 API。
- WebUI/Bukkit 新增受认证的生成源码编译接口，继续复用 ScriptWorker、Normalizer 和 Validator。
- WebUI 新增交路编辑器初版：运行线、未分配状态、交路、初始化/换向/等待/回库函数、生成 JS、编译和发布。
- 完成展示到达时刻从行编辑到 Saved DTO 的链路。
- Graph 区分到达点与发车点并分别编辑。
- WebUI 支持 `DoorSide.NONE`。
- Basic 新增确定性虚拟时间模拟引擎，消费编译后的 `Timetable`，要求每种结构化 selector 映射模拟车辆，并支持旅行/操作时间 adapter、最短停站、周期边界、动态取车起点解析、资源竞争和延误传播报告。
- 未注册的运行时函数不再按零耗时兜底；模拟会终止对应交路并报告 error。
- 纯列车动力学已移至 `basic.control`，线路预览和时刻表详细时分估算共享同一实现。
- 详细时分缓存已设置容量边界，避免路径/profile 组合无限增长。
- 交路编辑器支持原子跨交路移动运行线和有界撤销/重做；编译产物与完整文档快照绑定，避免发布过期结果。
- EditorDocument 增加独立后端结构校验和更完整的前端语义校验，同时仍允许保存未完成草稿。

后续仍需：

- 将世界基础设施和车辆快照 adapter 接到 Basic 模拟器，并提供完整时刻表模拟 API。
- 增加 selector/profile 映射 UI、模拟报告叠加及带 revision/version 的持久缓存。
- 继续拆分 `DutyEditor`，完善交路函数参数和拖放体验。
- 完成批量复制、平移和周期生成。

## 18. 待讨论事项

当前列出的架构决策已全部确认；实施过程中发现新的产品或模型分歧时，再逐项补充讨论。
