# Timetable Studio 编辑流程与异步隔离

## 已实现范围与负责模块

CURRENT。`App.tsx` 管认证、世界、页面导航和世界引用数据；`DutyEditor` 是时刻表、交路、校验三个页面共用的唯一文档、history 和 recovery owner。`DutyManagement`、`AuthoringValidation`、`TimetableWorkspace` 是受控视图。此契约覆盖交路编排、运行线工具、真实校验/模拟结果及图上时间编辑，共享停站程序、无方向事件点及交路时间轴使用下述当前模型。

## 一次正常编辑如何完成

用户选择世界和线路，在时刻表创建指定方向的运行线，通过 Reset 或停站表填写时刻（时间输入只在失焦或 Enter 后提交）；交路页按时间轴引用这些唯一运行线及程序库函数。所有接受的文档变更进入同一个 `useDocumentHistory`。校验页先展示 `generateTimetableSource(document).issues`，再通过现有 `api.compileSource` 获得服务器编译和世界校验结果。有效编译允许发布；每个交路完成车型映射后，现有 `api.simulateTimetable` 返回报告。页面切换只切换受控视图，不重建文档 owner。

## 保证正确性的内部机制

- 世界引用数据整体带 `world` 身份；选中世界不匹配时向页面提供稳定空引用，而非上一个世界的线路/站点。effect 清理取消旧请求的 state/error/busy 写回；线路集合更新也检查来源世界。
- 编辑器 owner 使用 owner/world 身份和递增 epoch。世界改变、文档加载/删除会使旧 owner 失效；异步加载、保存、正式脚本操作在写回前检查 epoch。加载还检查请求起始文档快照，避免覆盖请求期间的新编辑。
- 保存允许服务器成功保留请求起始快照，但不得用该快照覆盖期间的新编辑；saved baseline 记录实际保存内容，新编辑仍显示未保存。删除成功后，同一 owner 的新编辑必须保留并解除已删除文档/正式脚本 ID 的关联；不同 owner 的响应不能写入当前文档。
- 编译绑定文档生成输入；模拟绑定世界、生成输入、车型映射和阈值。输入改变后旧结果不展示；Reset 写回还检查世界/文档快照。Reset 失败不改变时刻或 history。
- 交路 ID 在失焦/Enter 时提交，空名和重复 ID 在事务前拒绝。运行线分配复用 `moveRunToDuty` 原子移动，不能同时留下两个 owner。
- 图点来自 `authoringGraphTimetable` 的两步一段投影：初始 CHECK 后，每段 options 在奇数 step、destination 在偶数 step。先用 graph service index 取真实 run ID，再映射到 call，禁止把投影 step 当作运行时 step。节点菜单保存 runId、callId、事件和当时有序 `[call.id,status]` 签名；站序或 STOP/PASS 状态改变时立即隐藏/禁用旧菜单并关闭平移，不能用过期 stepIndex 修改别的站。
- Graph 只负责选择、点击/Enter/Space 激活和画布平移缩放；不再拖动节点改时刻，也不提供方向键微调。选点回传屏幕坐标供浮动菜单定位，移动视图时关闭菜单。
- `CommittedNumberInput` 使用原始字符串本地草稿，保留逐键输入的 `-` 等中间值；用 `stringMode` 避免 `Number('-')` 的 NaN 把负号清掉。输入不写文档；失焦/Enter 解析并提交一次，最后提交值防止 Enter 加失焦重复写入。Escape 恢复已提交值；fieldKey 包含 owner/world/document/run/call/event 身份，更换身份销毁旧草稿。复制间隔/数量各自记录合法性，合并禁用条件，不能让一个合法字段清除另一个非法字段。
- `shiftAuthoringGraphSuffix` 先还原有效到达覆盖并找到实际有时刻的锚点，再计算绝对目标或偏移对应的 delta。ARRIVAL 移动本次到达、同站发车和后续调用；DEPARTURE 保持同站到达，只移动发车及后续；PASS 的两个投影点归并到一次通过事件。被移动的有效到达值写入 calls，并仅清除这些到达的旧覆盖；之前的 call 和覆盖不变。提交前校验受影响时刻与未移动的前一事件、最小停站及周期，非法时不提交，零偏移返回原文档。

## 接口、数据与单位

```ts
Graph.onSelectPoint?: (point: GraphPoint, anchor: {x:number;y:number}) => void
Graph.onDismissPoint?: () => void
editAuthoringGraphTime(document, runId, {kind, stepIndex}, tick): TimetableAuthoringDocumentV1
shiftAuthoringGraphSuffix(document, runId, point, mode: 'OFFSET'|'ABSOLUTE', value): GraphSuffixShift
// GraphSuffixShift: document, delta, affectedEvents, callId, kind, anchorTick, targetTick
// Invalid time/point throws before document update; parent displays the error.
```

时间单位是 tick，每个 timetable day 为 24000 tick。菜单提交安全整数，范围 `0 <= tick < periodDays * 24000`。后续平移允许正负偏移或绝对目标，但不把非法目标偷偷夹到另一个时刻。没有选择 callback 的图保持只读。

`AuthoringCommandState` 按已有命令记录禁用原因；空字符串代表当前可执行。ribbon 和编辑器命令处理器使用同一状态，不能只把按钮灰显而让 handler 绕过前置条件。browser HTTP 继续只经 `api.ts`；请求 shape 和 DTO 未改变。

## 生命周期与必须保持的规则

单点菜单提交到达时写 `arrivalOverrides`，发车时写对应 run call 的 `departureTick`；PASS 的离开投影也编辑通过到达时刻。有效 arrival override 参与到发和最小停站校验。节点后续平移和复制整批各形成一次文档/history 更新；本地输入草稿、预览、busy、报错不进入 history；交路 referenceVehicle 为文档字段，变更进入 history。

Reset 仅重生选中运行线的时刻，始发 Tick 来自该运行线原有首站 departureTick，没有另一个始发输入；缺少始发时刻则提示先填写。线路模拟重置的停站间隔来自运行选项中的独立“模拟用停站时间”输入，由玩家手填；全局最短停站只参与文档校验，不得作为模拟生成参数。独立 Copy run ribbon 打开间隔/数量弹窗，复制使用 effective run，按 D、2D、…、ND 平移并生成全新 run/call ID，不分配交路。运行选项不再混入复制设置。线路和方向共同限制选中运行线，删除后不得选中其他线路 inspector。

本地未保存草稿沿用已有 owner/world 隔离和 shape；已保存文档另用同样隔离的最后打开文档指针恢复，详见下文。不新增旧格式迁移链或持久 panel 偏好。

## 校验与失败处理

- 有 authoring error：展示真实 issue 和路径，阻止编译；没有 issue 也不代表世界校验已执行。
- 没有当前有效编译：禁用发布；完整模拟先编译当前输入，编译或世界校验失败则停止，并显示真实原因。
- 缺少车型、车型不存在或相同 selector 映射不同 profile：阻止完整模拟；输入变化立即隐藏旧报告。
- 图点不存在、step 奇偶不符、后续平移锚点缺少实际时刻、时间越界、后续事件跨越前一时刻或不足最小停站：拒绝事务，保留原文档。
- Reset API 失败、文档变化或返回时刻超周期：保留原文档；复制整批任一越界时也不提交。
- 服务器错误显示原错误详情；报告只显示返回字段和真实 trace，不生成虚构检查/轨迹。已知 UI 状态双语展示，未知运行状态保留原值。

## 成本与退化行为

键入只更新字段本地草稿；提交时才更新文档。单点更新扫描运行线/call/覆盖。后续平移对每个 call 查找有效覆盖，当前实现成本 O(calls × overrides + runs)，并线性校验事件顺序；仅保留一份预览文档，不创建逐字符 history。交路分配扫描现有元素。复制 UI 限制 1–100 份，生成成本为 copies × calls。生成/快照比较继续复用现有扫描和有界 history，不新增持久缓存。世界引用未就绪时提供空状态并禁用新建。

## 具体情形

- A 交路移动 AP102 到 B：A 引用删除、B 引用加入同一次文档事务；Undo 恢复唯一归属。
- 把已手工覆盖到达 2100 的站发车改到 2000：拒绝，不能仅检查原始 arrivalTick。
- B 世界 metadata 尚未返回又切回 A：旧 B 请求即使完成也不得覆盖 A 的线路或错误提示。
- 删除已保存文档期间修改时刻表 ID：成功删除后保留新内容，显示未保存并清除旧 documentId；下一次保存必须创建新文档。

## 必需测试与实际验证边界

纯事务测试见 `authoringGraphEditing.test.ts`，覆盖单点/后续平移、有效覆盖、PASS、非法 step/越界/事件顺序/最小停站、零偏移和 Undo。图选择/只读/去除拖动语义见 `graph.test.ts`、`Graph.test.tsx`；数值解析与受控视图见 `CommittedNumberInput.test.tsx`、`authoringViews.test.tsx`、`auth.test.ts`。浏览器另验输入未提交时图/history 不变、Enter 加失焦去重、Escape、逐键负数、节点菜单定位、后续平移前缀不变、复制字段独立合法性和 Reset 保留始发时刻，并保留世界迟到响应等既有门禁。组件/纯函数测试不证明实际服务器编译、世界模拟或发布质量。

## 迁移、非目标与反例

当前未发布格式保持版本1；旧 per-call 到站定义须经离线审计迁移。顶部 JS timetables/Simulation 两个独立页面已删除；正式 JS 保存/编译及校验页完整模拟仍保留。地图拓扑编辑、完整轨迹叠加和持久模拟缓存仍需独立任务。

错误做法：每个页面创建自己的 document/recovery；把任何无错误列表渲染为“全部世界检查通过”；迟到响应无条件 `setValue(capturedDocument)`；删除成功却保留已删除的 documentId。
正确做法：受控视图共享一个 owner；结果区分未执行、当前结果与阻塞原因；响应先核对 owner 和输入，保留期间的新编辑并正确更新持久身份。

## 共享站内程序（顺序子集）

每个时刻表拥有独立程序库，存储于 `timetable-programs/<world>/<documentId>/<programId>.json`，程序文件只含 `formatVersion:1`、`id`、`name`、`definition` 和可选 `estimatedDurationTicks`；没有程序 revision、contentIdentity 或 `boundPrograms` 快照。事件、交路 PROGRAM 和站停程序只引用本时刻表内的程序 ID。程序编辑与时刻表共享同一 document 锁。程序库页面从其他时刻表导入时执行一次性复制；目标内 ID 冲突时只重命名副本，不建立跨时刻表引用。删除仍被事件或交路 PROGRAM 引用的程序返回 409；删除时刻表级联删除其程序目录。文档或程序输入变化会使旧编译/模拟失效。

`estimatedDurationTicks` 是程序库的普通计划字段：缺省未知，0 为时间点，非负安全整数；`PROGRAM` 耗时直接读取本地程序记录，`MANUAL` 和 `SIMULATED` 语义不变。只有被交路引用且完整模拟成功时才产生图作业的测量耗时，未引用作业保持未知。程序定义、参数与事件等待仍使用当前 `StationProgramDefinition`，不增加旧开发格式 runtime reader。editor document 和程序文件都保持 `formatVersion:1`，本次为未发布开发格式的一次性迁移，不升版。

### 扩展动作与来源定位

顺序程序增加 EXTENSION，静态绑定 namespaced typeId/positive version，parameters 仅为整个 JSON_OBJECT literal 或整个 JSON_OBJECT 参数引用。嵌套内容是 JSON 数据，不做内部参数替换、不解释 JavaScript。注册描述的 schema 是说明数据，不假设完整标准 JSON-Schema；UI 只选择世界 metadata 中的已注册动作，真实编译继续由运行时 registry/provider.validate 决定可用性。

JSON 对象允许{}；拒绝空文本/非对象、不有限数字和超出安全整数范围的整数值。三端统一深度16、节点4096、单个 key/string8192 UTF-16 单元上限。内容身份先校验再归一有限 IEEE754 数值，1/1.0 和正负零等价；生成的定义及参数用 JSON.parse(quoted JSON) 保留 __proto__ 等合法 JSON key，不能直接用会改变这些字段语义的 JS 对象 literal。JSON 输入只在 blur/Ctrl+Enter 提交，Escape 丢弃，身份改变/卸载禁止旧回调写文档。

来源导航先验证当前文档输入及实际 trace，再匹配稳定 call/program/step。交路页显示本时刻表程序步骤只读，可直接打开程序库对应步骤编辑；不提供就地修改的第二 owner。无法在当前绑定找到的步骤、不同调用及过期编译 trace 不得猜测定位。ScriptWorker 保留 captured timed builder 的 Tick，并在完整展开前严格检查表达式原始 keyset，不能让 nullable decode 把额外的 parameterId:null 当作字段不存在。

### 交路重联／解编任务

重联／解编由交路时间轴直接创建 FORMATION 任务，不再是玩家程序步骤，也没有 FORMATION_EVENT 程序参数。任务只存 `{id,type:'FORMATION',eventId}`；文档 `formationEvents` 目录保存共享 `tick`、可选 `duration`／`stationId` 及固定参与交路、输出、会合地点与接近速度（显示 km/h，协议 m/s）。重联双方引用同一声明与时刻，协调交路编辑，另一方只读并可跳转到所属任务。协调交路必须仍是参与方。

接收交路由玩家通过既有新建操作手动创建，输出仅选择已有交路或显式 HOLD；允许一部分继续原交路，不自动生成新交路。接收来源为 `{type:'TAKE_FROM_FORMATION_OUTPUT',eventId,outputId}`，不另存一份取得时刻，运行时从共享作业时刻进入现有真实输出交接；UI 显示来源交路、任务与输出部分。调整输出及接收来源在同一次文档/history 事务完成；不能悄悄抢占已从另一作业接收车辆的交路，显式换来源先释放旧声明。删除来源或解除分配保留明确的缺失来源诊断，不自动取得另一列车。重命名同步参与方及输出引用。未指定或尚未测得耗时保持未知，不伪装成零时长。

校验每个参与方恰好引用一次、被消耗参与者结束、接收互反引用、时序与来源依赖环。生成仍使用已有 formationEvent、takeFromFormationOutput 及直接 split/couple DSL，外包真实 task 起止标记供测量；目录的编辑时刻、耗时与车站字段先从物理声明剥离。规划耗时／显示站点不进入执行源与输入哈希，已知重叠独立阻止编译。保存后端及本地恢复同步当前开发格式，保持版本 1，不增加旧开发数据 runtime reader。当前物理解编规则仅支持从实际运行车头数完整单元，范围 1..Int32 上限；保留现有一分为二操作；复杂分割通过多次解编组合，不增加选端 UI。浏览器编辑与脚本编译验证不等于真实游戏重联运行验收。

## 单文档跨线与连续显示计划

当前同一 duty 可引用不同基础设施线路和不同运行图的 RUN，每 RUN 必须有明确 lineId。`RunConnectionEditor` 对相邻 RUN 的到达/发车作一次 document/history 事务，校验使用 arrivalOverride 后的实际计划值与最小停站。连续显示配置由时间轴选中 RUN 的 `RunDisplayPlansEditor` 编辑，不再提供独立的运营车次/交路图面板。一个显示计划以有序 RUN 和完整单元槽位组织，关联多个交路时仍保留各实际 service/run 身份，不复制运行任务。

显示计划复用当前 `operationalTrips` 权威，不另造隐式上下文或第二份存储。创建/关联必须来自实际已分配的 RUN，用户显式选单元，禁止重复段和重叠槽位；新身份按现有计划命名空间分配数字字符串，不改已有ID。共享默认值、全部有序段、单元和失效引用都在 RUN 详情中可编辑，删除最后一段同时删除该计划。完全无可解析 RUN 的计划在交路页警告中可查看和删除，不能随旧面板移除而隐藏。`displayPlans.ts` 负责不可变文档变换，每次既有history事务保留无关字段；打开、保存不自动排序或重建计划。

默认显示 JSON 草稿的有效性回传到 DutyEditor；无效时禁用保存/编译/正式脚本/模拟/发布，并递增草稿 revision，拒绝同文档哈希下已过期的异步结果。字段提交仍经 blur/Ctrl+Enter，Escape 恢复。生成 JS 时，命名车初始 PROGRAM 前使用明确初始位置或首 RUN 起点建立一次 CHECK。编译器在合适边界初始化新车次，具体动作、wire 和运行时生命周期由 [SimpleATO 规范](../../javarailways/simpleato/through-running-and-unit-displays.md) 定义。

正式编译图按 runSegment 投影并保留原 service/step 地址。单 RUN Reset 使用精确 timetable/service/runId 选线；首 tick 不变。`operationalTrips` 为当前 editor/recovery 格式的可选空默认字段，不新增版本或业务层旧格式兼容。

## 线路最高速度

线路页维护 `LineV1.maximumSpeedMetersPerSecond`，显示单位 km/h；run options 仅选择基础设施线路。生成、校验和 Reset 使用当前线路集合中被文档引用的速度，文档 writer 只写当前编辑模型字段，不再保存独立的 `lineSpeedLimits` 配置。运行时限速快照及缺省行为见 [SimpleATO 线路契约](../../javarailways/simpleato/lines.md)。

生成输入哈希包含被引用的线路速度，线路速度改变会使旧编译和模拟失效。Reset 的异步响应除检查文档和 owner 外还须检查生成输入，避免按旧速度计算的时刻覆盖新线路设置下的编辑。

## 已保存文档的重开与草稿恢复

CURRENT：`authoringResume.ts` 保存 owner/world 隔离的最后文档 ID；内容仍以服务器 `editor-documents` 文件为准。成功打开、保存文档或保存/重命名正式脚本后同步记住 ID；明确新建或成功删除后清除指针。账号与世界共同作为 `DutyEditor` 的 React key，切换时卸载旧 owner。

重新进入先检查本地未保存草稿，有实际未保存修改则优先恢复。否则读取服务器文档列表，再 GET 记住的文档。列表确认文档不存在才清除指针，网络失败保留指针并显示错误；没有指针时不猜测打开列表中的其他文档（demo 模式的示例自动打开除外）。浏览器清理本地存储后仍可通过文档选择器手动打开服务器文件。

列表和 GET 属于同一个初始化操作，开始前捕获 owner epoch、操作序号及文档编辑 revision；列表更新只检查 owner 仍挂载，保证请求期间编辑不会导致文档选择器变空；自动 GET、缺失指针清理和内容写回还需检查操作和编辑 revision。期间编辑、手动加载、新建、切换身份或卸载，都会使自动恢复响应失效，不能用迟到响应覆盖新内容。edit revision 同时防止修改后 Undo 回到相同 JSON 时被误判为未编辑。

未保存恢复写入读取最新的完整状态快照；只在真实卸载/beforeunload/认证失效时运行生命周期保存，并保留250ms编辑防抖。不得在每次依赖变化的 effect cleanup 中把旧文档与新的 saved baseline 混合，否则保存或加载成功后可能复活旧草稿。成功加载和保存时同步更新最新快照，避免 state 尚未提交就卸载的间隙。

最后文档指针是独立、可丢弃的导航记录（version 1）；浏览器存储损坏或不可写不能中断服务器保存。没有修改既有编辑文档或草稿 schema 版本。测试应覆盖 owner/world、损坏和拒绝存储，dirty 优先、缺失/网络失败、列表与 GET 两阶段迟到响应；后端需重建服务实例并在同一磁盘目录读取此前保存的内容。浏览器另验真实页面保存、服务重启重新登录和未保存草稿恢复。

## 共享停站、事件点与交路时间轴（CURRENT）

文档 `stationPrograms` 按 stationId 保存一次到达/准备定义，调用保存有序 `viaIds`，终点 `departureTick` 可空。终点发车时刻只表示后续交路动作最早开始时刻，不触发准备；跨 RUN 冲突检查用有效到达加最小停站与终点 departure 的最大值。缺省程序与运行 gate 见 dispatcher-runtime，积木上下文、草稿门禁见 program-blocks。

`events?: TimedOperation[]`为运行图所属定时作业，使用与交路程序作业相同的时刻、程序调用、可选车站/标记与规划耗时模型。可无程序保留纯时间锚点，WAIT_EVENT只引用此集合，生成前仍将tick+offsetTicks解析为绝对等待。缺失或越周期产生authoring issue、返回空source，不抛渲染异常。

交路页使用页面专属顶部 ribbon：添加作业程序、重联、解编、撤销/重做与保存；校验页不显示命令 ribbon，编译/模拟由校验面板拥有。交路页中央复用渲染后的运行图选择 RUN 和运行图作业 EVENT：点击运行线把该 RUN 原子移入当前交路，点击带程序的 EVENT 添加唯一引用；不再用下拉菜单选择。右侧栏分“交路”和“初始位置”两个输入区，分别承载交路 ID、选择器/模拟参考车辆以及初始路径点/取得时刻等字段。

交路 `referenceVehicle?: {type:'WORLD_TRAIN'|'TRAIN_TEMPLATE',id}`持久保存于duty，模拟由此取参数，不限制实际运营列车。元素支持RUN、交路所属PROGRAM、运行图作业EVENT及共享编组作业FORMATION引用。PROGRAM复用TimedOperation字段，program必需；EVENT／FORMATION只存{id,type,eventId}，不复制时刻、参数或耗时，分别引用运行图作业和编组目录。来自编组输出的交路不重新指定模拟车辆。初始位置仍为duty.initialPosition，不作为执行任务。当前未发布格式仍为1；旧PROGRAM.call结构只由离线迁移处理，不在运行时兼容。

`DutyTimeline` 取运行线始发时刻及有效终点到达+最小停站、可选终点发车的最大值显示区间；程序支持拖动开始、手动区间拖动右边界及等价数值输入，pointerup 才提交一次 history。普通任务右键移除只删除引用；编组任务仅协调交路可删除，其目录与参与方引用一并移除并保留接收方缺失来源诊断。编组时刻和耗时当前通过详情字段编辑。RUN 时刻仍由运行线编辑器唯一维护；函数本体在本时刻表程序库编辑，调用直接引用本地程序记录。编组不再使用折叠区的事件 ID 表单。

同一交路的全部任务共用一条横向轨道，不再逐任务分行。已知正时长区间按真实 tick 比例绘制，短任务的命中范围不能伪装成更长的占用区间；零时长点与未知时长任务分别标记，未知不能当作零时长。独立的紧凑任务标签复用相同选择、双击导航和右键移除入口，任务条负责拖动开始时刻，右边界手柄负责调整手动耗时；下方 inspector 仍是唯一编辑位置。已知重叠在共享轨道上标记实际交集并保留既有诊断，不通过增加任务行掩盖冲突；本次布局改动不自行改变调度或规划耗时语义。

手动耗时只影响规划显示，不进入 source/hash，不引入等待或超时。模拟耗时取当前输入+车型报告中同 serviceId/taskId 的实际 END−START，仅完整成功模拟并有结束值时展示，未知不伪造零。改变手动耗时或显示模式不使有效模拟失效，修改执行输入则隐藏旧结果。完整模拟按钮可以先编译；异步编译/模拟均检查 owner、草稿 revision 和执行输入。

任务按指定 tick 排序，相同时刻保持元素顺序；运行时等待指定时刻及前序真实完成。已知静态时序冲突仍遵循编译器单调约束；已知规划区间重叠产生 authoring error 阻止编译，同时保留时间轴警告及交集标记供修正。未知测量不能据此证明无冲突。
生成器为起点发出无显式时刻的 beginStationStop，避免把“准备提前量”插入已前进的计划游标；准备开始由执行器调度。目的地携带具体 role、stationProgram、plannedContext 和 departureTick；条件在 worker 按可静态确定的上下文选分支。缺省与自定义共享程序都必须通过所有当前步骤验证后生成；不保留旧格式专项判断、生成门禁或迁移提示。

`arrivalAuthoring.test`、`dutyTimeline.test`、`DutyTimeline.test` 与 `programBlocks.test` 覆盖共享停站、终点时间、任务区间、模拟未知及草稿映射；Java TimetableProgramHttpTest、SharedStationProgramValidatorTest 和 EditorDocumentRestartTest 检查程序归属、锁与持久边界。浏览器另验时间轴拖动/调整长度、右键移除、程序库导航、保存重开；真实执行计时另由 isolated simulation/dispatcher 测试证明。

## 可见运行线与交路编号

新运行线、复制运行线和新交路在既有ID字段使用递增数字字符串。各自命名空间独立；按当前文档同类数字ID最大值加1分配，批量复制先分配整批且一次写入history，不舍入大整数。已保存的自定义ID保持原值，内部call/element/program身份不改；不引入持久计数器或迁移。分配需保留当前模型中的已引用ID，包括悬空的交路RUN、到达覆盖、运营车次分段和编组参与/输出引用，避免新建意外改变引用含义。

## 独立时间点操作入口

运行图标题操作区提供显式添加时间点按钮，填写当前文档内的车站、时刻和可选标记；无站点禁用。新增和列表/图点编辑复用同一对话框，取消不写领域文档，保存一次history。图空白右键不承担创建行为；对象右键、菜单键或ShiftF10提供该事件的编辑/删除，普通点击/Enter编辑。菜单及编辑草稿属于瞬时UI，文档/世界/线路/页面切换关闭，动作重新核对打开时事件快照，避免旧菜单覆盖新内容。

事件仍为文档级上下行共享数据，不复制给每条运行线。删除被WAIT_EVENT引用的事件保留引用并产生现有issue；不得通过悄悄删除等待动作或重用悬空事件ID掩盖问题。保存仍遵循周期内整数tick校验，未改变schema或调度语义。

运行图所属定时作业提供与复制运行线一致的间隔/数量批量复制，入口同时存在于事件列表和图上对象菜单。复制保留源作业的程序、参数、标记和耗时，分配新的数字ID，按 D、2D、…、ND 平移；任一份越周期或失败时整批不写入，不自动分配到交路。交路所属只读作业不提供复制入口。

## 定时作业归属与复用（CURRENT）

共用TimedOperation字段：id、tick、可选stationId/label/duration/program；program为{programId,arguments}。程序体和已采用快照仍由库/绑定定义持有；不复制为作业的可编辑程序体。

运行图创建的作业只在运行图编辑，交路保存EVENT引用，引用元素id为该次执行taskId。未引用作业不自行生成执行；多个交路引用各自列车执行。交路创建的PROGRAM只在交路编辑，绑定站点后运行图投影只读，可跳转所属交路；站点是显示锚点，不意味着移动或动态停靠。移除交路引用不删除运行图原作业。缺失事件或引用纯标记报issue；不得silent跳过或虚构执行。

共享字段组件、resolver、验证与投影复用同一套参数/时间/耗时规则；外围唯一document owner负责采用程序快照和history。图上区间采用手动耗时或当前完整成功模拟；同一图作业的所有引用测量齐全且相同才展示唯一模拟耗时，未引用、未完成或不同则未知，交路自身仍按各自报告展示。站点、标记、规划耗时不影响生成输入hash；解析后的时刻/程序参数和执行顺序会影响。编辑归属、菜单及草稿必须保留文档/世界/线路/页面隔离。

### 共享定时作业参数的窄布局

TimedOperationFields 的调用参数使用可缩到容器宽度的190px最小网格轨道，保留14px行距／18px列距。JSON编辑器在该网格内必须 min-width:0、width:100%，不能由全局220px最小宽度吞掉列间距；ID_LIST 的默认170px最小宽度只在此参数上下文解除。交路任务与运行图所属定时作业复用同一组件和规则，不复制窄布局实现。程序绑定快照列表显式保留12px纵向间距，长名称可换行，采用更新按钮不被挤压；这些布局修正不改变参数提交／验证和采用快照的行为。


## 运行图线宽与运行线颜色

默认无选择的运行线按1.25px绘制并保留各自颜色和标签；明确选中的运行线2px，其余淡化运行线1px，不再把“无选择”当作所有线都选中，也不叠加描边光晕。运行线使用约12px透明命中带，视觉线本身不拦截指针；运行线/标签/停靠点图层位于定时作业之上，确保停靠点可选取。标签背景只覆盖文字，放在起点正上方或正下方并用同色1px线连接。定时作业菱形视觉半径4px，持续线使用与运行线相同的1.25px宽度；添加定时作业入口在时刻表页面顶部工具栏。空白处单击清除运行线焦点，拖动平移不清除；空焦点不得被自动回退到同范围第一条运行线。节点命中半径和键盘入口保持原合同。交路页运行图使用 run.color 优先、其次所属基础设施 line.color，和主运行图一致。

`AuthoringRunV1.color?:string`仅为编辑显示属性，值为完整`#RRGGBB`（大小写均可），省略表示自动配色；恢复自动应删除字段，不写null。运行选项中提供独立颜色控件与恢复按钮，经同一文档history更新，保存、复制、Undo/Redo及重开保留。浏览器和Java保存端都验证形状；新增有明确缺省的可选字段不改变formatVersion，不增加迁移或runtime颜色DTO。

Graph从authoring投影旁的runId→color映射获取显示覆盖，不能把颜色加入运行时任务或编译动作。完整编辑文档保留颜色，生成执行程序与模拟semanticInput忽略它；纯着色不能改变模拟参数。`graphServiceColor`统一图线与选色器的自动值：用户颜色优先，其次既有线路颜色，再按主题和当前投影的serviceIndex选palette。必须使用过滤后相同索引，禁止色块写死默认蓝色，与实际自动线色不一致。

验证包含未选/选中线宽、颜色优先级、两主题、运行线复制和文档history、无效颜色、真实HTTP保存并重启读取；浏览器另检查两个运行线独立着色、自动色块一致、恢复自动与Undo/Redo、保存请求和刷新。原生颜色选择对话框与浏览器事件测试应区分，不将本地存储夹具称为真实服务器编译验收。
