# SimpleATO timetable model 与 validation 契约

## 适用场景与用途

触发：修改Timetable/Service/Step/Action、editor rows、activation/condition、line speed、manual DSL或`TimetableValidator`。

用途：runtime execution authority只来自validated generated steps；presentation/authoring metadata不能绕过validation，invalid timetable不安装controller/run/resource且不Stall world。

## Owner、字段、token与时间

```kotlin
data class Timetable(
    val id: String,
    val periodDays: Long = 1L,
    val services: List<TimetableService> = emptyList(),
    val initialPositions: List<TimetableInitialPosition> = emptyList(),
    val lineSpeedLimits: List<TimetableLineSpeedLimit> = emptyList()
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt`。

`periodDays>0`；一天固定`24_000` Tick；`periodTicks=Math.multiplyExact(periodDays,24000)`。`TimetableService.steps`是runtime authority；diagram line/arrival/compiler trace只presentation/authoring；旧 editorRows 模型已移除。分段 service 使用当前 `runSegments.lineId`，无分段时使用 `service.lineId` 作为 infrastructure identity。新增运行段、运营车次及显示动作契约见[停车跨线与单元显示](./through-running-and-unit-displays.md)。

Step token：`Check(wayPointId)`、`At(tick,action)`。Action包括TakeFromDepot/Waypoint、StoreAtDepot、GoTo/GoVia、Stop、Extension/Conditional、Halt、Reverse、DestinationOptions、CallStation。`At.tick`为timetable-relative action/departure tick，nonnegative且period内、非降序。`StoreAtDepot` 的静态路线校验证明到物理 StablingPoint 的经路覆盖；根据实际来源和已知属性检查适配性，不能以缺少库存登记拒绝时刻表。运行期仍须核对实际编组、点位适配与资源准入。

## 时刻表值的所有权（CURRENT，ATO-06 用户决策）

`Timetable` 是内部值模型，不是可原地编辑的对象树。producer 在 validation、交给 Store/Dispatcher 或保存 capture 前冻结完整可达对象图，包括 services/steps/GoVia.wayPointIds、condition列表及扩展 JSON 的底层嵌套map/list；之后任何保留的 mutable alias 都不得再修改。Store返回值、运行中service、capture DTO可以共享这些已冻结值。编辑必须构造新对象，通过已有显式替换/重置流程生效；data class copy 不会自动复制未改的嵌套值。

Kotlin List/Set/Map 和 JsonObject 的只读接口不自动保证底层不可变；约束由生产者及provider遵守，不增加所有构造器/getter/运行步骤的防御复制。provider只读参数，不修改其底层map。保存capture不是任意可变输入的深复制边界；入队异步编码以后源值和DTO均保持不变。本次只补合同，未声称当前平台caller已经原地修改或存档已经损坏，不新增可变编辑API和兼容路径。

## Validator gate与result

`TimetableValidator.validate(timetable)`返回`TimetableValidationResult`，汇总全部errors并保留`[timetable/service] step#n`上下文；`isSuccess=errors.isEmpty()`。结果保留`routes: List<ExpandedRoute>`，并携带不可变、逐项对齐的`routeStoppingOffsetsMeters: List<Double?>`：纯途经为null，停车片段为实际生效偏移，供静态应用按真实停点计算余量。可空列表不能用禁止null的`List.copyOf`复制；机制与边界归[终点分离契约](terminal-route-completion.md)。

| 条件 | error/result | mutation |
| --- | --- | --- |
| duplicate/blank IDs、period overflow | contextual errors | none |
| Line missing或service无line max speed | errors | none |
| AnyTrain未以唯一take action开始 | errors | none |
| explicit train movement前无Check | errors | none |
| 无movement、Stop/extension/conditional早于first movement | errors | none |
| At越period或decreasing | errors | none |
| station/destination options不匹配next movement | errors | none |
| store非home/非last，Halt非last | errors | none |
| route/direction/berth/provider/parameters unresolved | errors + failure context | none |
| valid | `isSuccess=true`并返回pre-expanded routes | Dispatcher才可begin/start |

## 确定性车门证据静态传播（CURRENT）

### 1. 状态、范围、触发与 owner

修改车门动作、`StationStopPrograms`、行车/编组前置门禁或 `TimetableValidator`时触发本契约。
owner 是 `TimetableValidator.validateService` 的既有 `State/process/walk`有限后继证明；运行期的最终安全门禁仍由
`TimetableDispatcher` 和 `StationStopProgramRuntime` 拥有。本机制只拒绝从固定时刻表就能证明的冲突，不代替运行期确认。

### 2. 正常工作流程

Validator 在扩展经路的同一次 cursor 遍历中传播车门证据。`OpenDoors/CloseDoors`、实际执行的停站程序和取车/编组
边界更新证据；行车、选站、存车或编组在消费证据前检查是否已确定为开门。冲突直接加入既有
`TimetableValidationResult.errors`，因此 WebUI 沿用现有 validation 展示，不再实现第二个前端校验器。

### 3. 内部表示与算法

`DoorProof` 只有 `UNKNOWN / SECURED / OPEN`。`OPEN` 表示固定定义已能证明开门；`SECURED` 表示已能证明关门；
`UNKNOWN` 不等于安全或不安全，只表示留给运行期门禁。处理顺序与 `State`的位置、方向、pending station call 完全相同；
`SelectStop` 也使用同一候选展开，并把 `action.stopPrograms` 传入候选 `CallStation`。禁止新增第二套 cursor、分支合流或预算实现。

### 4. 数据与转移契约

- `TimetableAction.OpenDoors(side != null)` 得到 `OPEN`；无显式 side 时，已有 `OPEN` 仍为 `OPEN`，其余变为 `UNKNOWN`，因为继承站台侧只能在运行期确认。
- `CloseDoors` 得到 `SECURED`；`Reverse` 保留当前证据，不是拒绝点。
- 停站程序的有效侧别是 `program.doorSide ?: fallbackSide`；`fallbackSide` 使用 Dispatcher 相同的 destination/call/berth 解析。
- `TERMINAL` 只计算 arrival，`ORIGIN` 只计算 departure preparation，`STOP` 按 arrival 后 departure preparation 计算。
- 有效侧别为 `NONE` 且输入为 `OPEN` 时拒绝；否则 doorless runtime 得到 `SECURED`。非 doorless 程序从 `UNKNOWN` 折叠实际 `OPEN_DOORS/CLOSE_DOORS`。
- 异步程序只在所有可见门动作结果相同时生成确定证据；混合开/关退化为 `UNKNOWN`。

### 5. 不变量、生命周期与版本

静态冲突不修改 timetable，不自动插入关门，不删除 Dispatcher fail-safe，也不安装 run/controller/resource。
首个确定冲突把该路径的证据降为 `UNKNOWN`，避免同一开门原因在后续每次移动重复报错；之后的显式开门可形成新的 `OPEN`
并报告独立冲突。不涉及存档字段或 schema version。

### 6. 校验与错误矩阵

| 条件 | 结果 |
| --- | --- |
| `OPEN -> Reverse` | 合法，保留 `OPEN` |
| `OPEN -> BeginStationStop(effectiveSide=NONE)` | `[时刻表/service] step#... 车门状态冲突`，提示在此步前显式添加 `CLOSE_DOORS` |
| `OPEN -> GoTo/GoVia/SelectStop/StoreAtDepot/Couple/Split` | 在当前地址拒绝并降为 `UNKNOWN` |
| `UNKNOWN -> doorless stop` | 不误报，后续证据为 `SECURED` |
| `program.doorSide` 与 berth/step side 冲突 | 以 `program.doorSide` 为准 |
| runtime condition 不可编辑地确定 | 由既有 condition gate 先拒绝，不启动第二套车门分支证明 |

### 7. 复杂度与退化

车门字段不新增遍历；它复用现有最多 4096 工作项、32 层 continuation 和 memo 键，因此渐进复杂度不变。
无法确定执行顺序、继承站台侧或动态编组时退化为 `UNKNOWN`，不会把未证明状态伪装成通过或错误。

### 8. 使用情形

- 正常：终到 arrival 开门，折返后在始发前显式 `CloseDoors`，静态验证通过。
- 边界：终到 arrival 开门，紧接 `Reverse`，折返本身不报错；后续 doorless origin 才报错。
- 保守：只有 `OpenDoors(null)` 且没有既知 `OPEN` 时输出 `UNKNOWN`，不根据 waypoint 猜测运行期已确认站台。
- 异常：终到 departure preparation 中的 `CLOSE_DOORS` 不执行，不得用它清除 arrival 已形成的 `OPEN`。

### 9. 必需测试

`TimetableValidatorTest` 必须覆盖：跨 `runSegments` 的 terminal-open → reverse → doorless-origin；显式关门只消除车门错误；
`UNKNOWN` 与继承侧别不误报；已知 `OPEN` 不被 `OpenDoors(null)` 清除；`program.doorSide` 的两个反向覆盖用例；
`Reverse` 地址不产生车门冲突。修改状态传播后还必须运行完整 `:SimpleATO:test`。

### 10. 迁移、待决定与错误实现

无数据迁移和待决定项。错误实现是单独新建一个约 180 行的门状态 cursor，它会与路由证明的预算、memo、`SelectStop`
和分支合流漂移。正确实现是在既有 `State` 中增加 `DoorProof`，并让 `process/walk` 成为唯一传播管线。

Manual parser/factory、timetable model、validator、runtime store、extension registry 与 JSON 展示均由 `SimpleATO` 拥有。token `+id` 表示 pass；final 必须 stop。stop JSON strict 且只允许 `minimumDwellTicks/stoppingOffsetMetersBefore/doorSide`；unknown/type error拒绝。Line、Destination、Station、Depot与任务归属均由`SimpleATOWorldData`提供，列车事实读取Core与宿主查询。validator先从canonical Destination解析route anchor与approach direction，再把方向显式传给SimpleATO共用`OperationalRoutePlanner`；它和Mission/Station/Line看到相同运行方向及plugin source，不在apply或Dispatcher阶段重新寻路。

## 项目内正确示例

Validator捕获period overflow而不是让exception逃到Dispatcher：

```kotlin
val periodTicks = runCatching { timetable.periodTicks }.getOrElse {
    errors.add("时刻表周期 Tick 溢出: ${timetable.periodDays} days")
    Long.MAX_VALUE
}
```

来源：`SimpleATO/.../timetable/Timetable.kt`。它让同一validation run继续收集其他可定位错误。

## 禁止实现

- diagram metadata直接执行；
- adapter重复或放宽SimpleATO validation；
- invalid timetable先安装controller/run再报错；
- validation failure Stall world；
- 用diagramLineId解析line cap；
- manual options允许unknown fields或final pass。

## 必需测试与验证

- `TimetableValidatorTest.kt`：所有gate、context、route expansion、line speed、invalid station approach且run/controller absent/world未Stall。
- `SimpleATO/ManualNavigationTest.kt`：`+pass`、final stop、strict JSON/type/token、station role生成CallStation及原 Dispatcher 集成行为。
- `TimetableSnapshotTest.kt`：presentation survives JSON但restore不进入runtime authority。

```bash
./gradlew :SimpleATO:test --tests '*TimetableValidatorTest' \
  --tests '*TimetableSnapshotTest' --tests '*ManualNavigationTest'
```

## 固定单元周转诊断（库存证明切片）

`CurrentUnitCirculation`在owner边界读取一个Core不可变world frame、一次Depot原子`operationalSnapshot()`与宿主候选属性；
捕获后frame身份改变则返回UNPROVEN，不重试拼接多个时刻。物理列车不需要登记；候选事实或实际停放位置未知、
未完成assignment、HOLD或结构恢复事务均不能被当作已证明空闲的列车；缺证据返回UNPROVEN，不能伪装为PROVEN。

内部`TimetableUnitCirculation`只分析真实固定交路/catalog与 Core 完整单元，有限搜索保留单元顺序、编组、实际库/点和分类。
完整编组停放占一个点，内部单元不能未经解编被分别取用；解编/重联不创造车辆，不存在home代替实际位置的规则。
允许跨多个周期的库存置换。无结构操作时，持续库净流失或强制兼容类别净流失是独立于行车耗时的矛盾；
搜索未找到见证或达到状态/深度上限仅为UNPROVEN，不能推导IMPOSSIBLE。

公开`api.timetable.TimetableCirculationSummary`分别提供`inventory/timing/capacity`的
`PROVEN/IMPOSSIBLE/UNPROVEN`、不可变`findings`及可空正整数`periodicInventoryCycles`。
Finding的`code/detail/dutyId/stepIndex/blocking`用于上层诊断；内部搜索图、物理身份和exploredStates不进入公开summary。
库存PROVEN仅说明声明分类下存在有限周期库存见证，不证明实际动力兼容、路线执行、完成时间或容量全过程。
`At.tick`是最早进入时刻，当前没有完成上界，因此timing和一般capacity仍为UNPROVEN；
真实点长度绝不适配等独立矛盾可单独给出capacity IMPOSSIBLE。仅明确`blocking=true`的finding可阻止编译，
不能把未证明的提示伪装为通过或全部转成错误。

普通列车现在默认已有 Core 单元，不能因此自动开启结构编组的循环库存准入。
Validator 始终返回实际分析的库存状态及诊断 code/detail；仅在 `formationEvents` 非空时将该分析的阻断项加入 errors。
无结构需求时公开 summary 的 findings 标为非阻断，与 validation errors 一致；直接调用分析器仍保留原始证明及分类。
例如普通单程服务仍可报告 `inventory=IMPOSSIBLE/RECURRING_DEPOT_DEFICIT`，但不会仅因默认单元新增而无法启动；
声明结构交路的同一库存矛盾仍阻断准入。此门由实际结构需求决定，不再由是否曾手动登记单元间接决定。

聚焦检查为`TimetableUnitCirculationTest`（库存与有限边界）、`CurrentUnitCirculationTest`
（实际Core publication/Depot捕获）及`TimetableCirculationSummaryTest`（公开投影和不可变性）。
此切片不等于跨周期时刻/容量验收，HTTP/UI展示也不改变证明范围。

### 时刻分支地址与独立服务启动

当前Conditional children使用`thenSteps/elseSteps: List<At>`，替换bare action列表；每个孩子携带自己的绝对最早tick。
model防御复制孩子列表；编译trace保留top-level `stepIndex`并增加默认空`branchPath[{arm,index}]`。
`TimetableServiceTraversal`是同一固定定义的只读视图；`TimetableProgramCursor`的不可变地址路径保存所选分支的continuation，
异步动作捕获准确successor值。branch tick join为两臂 authored末tick的最大值，不合并物理端点或推断完成上界。
`TimetableBranchGuard`迭代检查两臂固定事件/取车/Halt绕过与深度/节点上限；schedule校验所有嵌套tick与suffix下界。
具体移动分支/选站支持范围以静态传播和Dispatcher联合门禁为准，不能由cursor纯测试推定。

`validateForServiceStart(timetable,serviceId)`仅缩小当次服务路线检查范围，周转分析仍接收完整时刻表。
不能把A→B/B→A的完整图裁成单服务后误报持续缺车；公开`validate(timetable)`仍校验完整定义。
`CurrentUnitCirculationTest`分别断言平衡完整图在start wrapper下保持PROVEN，以及真正裁去回程后的定义为IMPOSSIBLE。

### 有限后继状态、选站与结构事件证明

普通移动校验沿同一个`TimetableProgramCursor`传播位置、方向、未完成车站调用和停车偏移；
保留路线键包含完整address与有效offset，禁止同键覆盖不同经路。条件两臂均须合法。
`SelectStop`为每个有限候选使用独立证明集合，校验完整后继（包括未来条件的两臂），
只发布全部合法候选的incoming与onward路线；失败候选不得留下局部incoming证明。
全局4096项工作与32层continuation预算必须明确失败，不能截断候选后宣称通过。
工作预算计入cursor节点、选站候选尝试及每个起点/方向的经由leg，拒绝的尝试同样计费，耗尽后停止兄弟扩展。
相同完整address、传播State和深度上下文的后继结果可复用不可变证明；发布路线数组去重，
避免连续空分支反复复制同一后继经路。

结构事件传播实际初始车体证据和不透明后继包络。`StructuralBranchTrace`同时记录
按service ID和完整address命名的条件arm及实际提交的berth选择；继承到输出，重联合并两来源历史，
冲突历史不得配对。固定事件source、output、route与storage键必须包含准确历史；
解编输出候选键从`ValidatedStructuralSource.outputCandidateKeys`取得，运行时不得重造字符串。
结构选站仅扩展普通完整后继校验已保留的候选，随后每一候选还必须通过完整车体与后续固定事件证明；
当前保守地拒绝任何结构候选失败的定义，不会悄悄舍弃失败的物理分支。

结构`GoVia`保留一份完整aggregate证明及有序`intermediateMarkers`。
每个中间标记用距保留经路末端的距离标识准确出现位置，不使用同轨首次投影。
标记必须位于完整来源区间之后，按路径严格递增，并早于最终停车窗口。
运行时只解析一次真实输出frame，再在同一条已证明经路上按这些距离分段；不得制造未来frame。

结构经路内部的CallStation仍由既有车站controller暂停并恢复同一mission，不新增结构事件或推测frame。
静态证明必须确认berth在选定经路上有唯一、方向匹配的出现位置，其停车偏移与容差窗口在完整来源区间之后、
最终目的地之前；最终Stop偏移只有在berth就是最终端点时才使用车站偏移。运行时以实际输出起点和同一已保留经路
切出车站prefix交给StationCallResolver，不重新选路。

`ValidatedStructuralOutputRoute.requireCurrent(world)`在解析实际frame前校验独立的metadata绑定；
rail topology未变化不代表waypoint或berth未变化。绑定防御保存所有经由点/终点/命名起点的坐标和方向，
以及车站几何、停车偏移/容差、长度范围、dwell、release、door、stopping-area和lifecycle/membership。
任何漂移先拒绝旧证明，再走正常重校验，不能由旧窗口反算一个看似有效的新目标。

后继证明集合合并按`ValidatedTimetableRouteKey`保留关联，再成对追加route/offset；不能只按几何去重，
因为两个动态起点可共享同一后半段而仍有不同关联键。局部ProofSink的成员访问显式使用`this`，
避免与外层validateService同名的routes/errors参数发生词法捕获，破坏候选隔离或数组对齐。

## 共享停站程序的定义与地址

停站动作仅携带 current `stopPrograms`；其 arrival／departurePreparation 由 StationStopProgramRuntime 执行，与普通 THEN/ELSE 游标分开。旧 arrivalSteps 子树、ARRIVAL cursor arm 及 per-call arrivalProgram DSL 已删除，不能从旧字段回退执行。当前编译来源中的 arrival:<callId> 是共享程序来源定位，不是旧字段别名。完整执行与等待门禁见 [Dispatcher 共享停站程序](./dispatcher-runtime.md#共享停站程序与两个执行入口current)。
