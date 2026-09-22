# SimpleATO 运营选路与行车授权契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`TRANSITIONAL`；`SimpleATO` Gradle 模块已经创建，并拥有第一批手动导航/时刻表展示工具；ATO、路线、时刻表 model/persistence、Dispatcher 与大多数运营对象仍在 Basic。本页描述的完整选路与授权合同仍是迁移目标。
- **Owner**：SimpleATO 拥有运营路线选择、运行方向、任务/时刻表决策和列车保护约束合成。
- **Inputs**：Core 发布的不可变 `TopologyState` 与同 epoch 物理观察；SimpleATO 自己发布的运营 overlay/plan revision；Basic 的通用资源准入结果；已安装信号系统插件发布的资源检查与限制结果。
- **Outputs**：一份绑定 topology revision 的冻结路线、资源准入请求，以及交给控制器的统一 authority/stop/speed constraint 结果。
- **Non-goals**：本页不定义 Core 图构建、Basic 资源推导/claim、Bukkit 渲染、信号模型、JS API 或持久化 schema。

触发：迁移或修改 ATO mission、route planning、运行方向、固定/移动闭塞选择、保护区域交互、时刻表 dispatch、MA/stop/speed constraint 合成。

## 2. 模块工作方式

一次正常自动行车决策按以下流程工作：

```text
coherent Core observation + TopologyState(revision)
  -> load one immutable operational-overlay/plan revision
  -> choose the operating route to the current operational target
  -> freeze one ordered directed traversal bound to topology revision
  -> submit that route, train identity and observation stamp to Basic
  -> Basic validates and either atomically grants resources or denies
  -> convert the result plus route-local observations into constraints
  -> reduce all candidates through the existing train-protection pipeline
```

SimpleATO 决定“列车准备走哪条路”，Basic 只证明“这条路在当前物理拓扑上是否合法且资源能否安全取得”。Basic 拒绝后 SimpleATO 可以在新的 observation/overlay revision 上重算，但 Basic 不替它换路；SimpleATO 也不得通过第二份 caller-authored resource list 指挥 Basic 跳过资源发现。

SimpleATO不识别保护区域、固定闭塞或某一国家信号制式的具体规则。它把一条冻结路线和期望authority boundary交给通用准入；Basic自行加入不可删除的物理资源底线。具体信号系统插件可以为同一intent增加自己计算出的注册资源和更严格限制，但不能删改Basic的rail/turnout/diamond资源，也不能绕过最终grant。新增信号制式不应要求SimpleATO增加保护区域类型分支。

没有 SimpleATO 时，不产生自动路线grant。Basic 可以保留经过物理锁和 active-claim 检查的显式手动道岔操作，但该操作不产生 movement authority。

## 3. 内部机制与关键算法

### 3.1 运营 overlay 与选路

运行方向、站台/停车点、线路和通用运营限速是SimpleATO拥有、并引用Core edge/rail slice的运营overlay，不是另一张可独立修改的物理连通图。固定闭塞、保护区域、信号边界和联锁表由具体信号系统插件拥有；SimpleATO只消费它们通过公共检查/限制接口给出的结果。路线起终点落在edge内部时，选路查询使用临时virtual split；不得为了站台或信号永久切分`TopologyState`。

选路输入固定为一个 topology root、一个运营 overlay/plan revision、列车属性和当前运营目标。算法只在 Core 声明的合法 continuation 中搜索，再按方向、线路、车辆能力、停站与计划约束过滤或计权。通用最短路实现可以来自共享算法包，但运营 cost、约束和候选选择由 SimpleATO 拥有。结果必须是一条明确的有向 traversal；不得只给目的地让 Basic 自行寻找可行路线。

当前已实现的第一批内部选路器直接读取一次不可变`TopologyState`和一份绑定相同topology revision的运行方向overlay。它先用`railProjection`把起终点的stable rail UUID + canonical AB米位置解析为edge距离；起终点落在edge内部时只创建查询局部的部分edge traversal，不改写或复制published topology。随后以“到达结构节点的入口port”为Dijkstra状态，以实际edge米数为权重：turnout只展开`legalContinuations`允许的出口，loop anchor只允许从另一port继续，terminus不展开。每个方向的edge traversal还必须满足所经过物理rail的运行方向约束。相同距离的候选按固定起始方向、deterministic edge ID和port次序展开，因而相同输入得到相同路线。

目标正好位于共享结构节点的另一条incident edge起点时，搜索只有在入口port→目标port continuation合法后才生成该目标rail的零长度末段标记；它不会为了得到非零segment多跑一圈。该标记保留目标port与方向，使Basic后续线性投影仍可验证turnout/loop-anchor transition。搜索成功后，选路器沿每个edge的有序`railSlices`裁剪查询局部首尾区间，并按实际行进方向转换成递增的AB或BA局部弧长，生成现有不可变`RouteSnapshot`。

当前Core builder保证每条物理rail完整地、恰好投影为一个`TrackEdgeSlice`，当前Basic projector也要求单一无歧义projection；本选路器与这一**当前实现不变量**一致，遇到零个或多个projection会拒绝位置。它不是允许未来partial/multi-edge rail projection的通用API保证；若Core以后放宽builder，Core位置查询、Basic projector和本选路器必须在同一契约批次一起修改。snapshot精确保留输入topology revision及调用方给出的route ID/revision；运行方向overlay revision随内部成功结果返回，供后续准入客户端保持coherent input。当前这一类型仍为SimpleATO internal：尚未迁移production Dispatcher，也尚未加入线路、车辆能力和时刻表cost，因此不构成冻结public API。

当前内部`FrozenRouteSelector`是观测与上述纯选路器之间的唯一接缝。一次调用显式接收同一个不可变
`TopologyState`、一个`RailwayObservation`、一个运行方向overlay、稳定train ID、运营目标及route
identity/revision；先要求observation与overlay的topology revision都等于该topology root，再只从这份
observation解析具名列车。脱轨列车作为无效权威起点被拒绝；成功时将该列车的tail rail、canonical AB
位置和facing作为planner起点，并只调用一次既有Dijkstra。返回值保留原observation stamp、overlay revision
和生成的`RouteSnapshot`，供后续Basic准入客户端提交同一批输入。stale observation、stale overlay、列车缺失、
列车无效和planner拒绝分别是typed internal结果；planner的`INVALID_LOCATION`/`UNREACHABLE`等原始reason不被
抹平。该接缝不读取live train、不申请资源，也不为测试暴露Basic内部progress cursor；首segment的rail、facing
和局部起点必须精确对应权威tail，Basic随后仍独立验证这一不变量。

### 3.2 冻结路线与准入客户端

冻结路线保存其生成时读取的 `topologyRevision`、独立的 `routeRevision` 和有序 traversal。SimpleATO 向 Basic 提交路线、列车 ID、同一 observation stamp 与期望 authority boundary；不提交 caller-authored route progress/occurrence index、可替代物理底线的 raw `InfrastructureFootprint`、可信列车长度或已枚举的 turnout/diamond 列表。Basic 从同一 observation 的权威列车尾验证冻结路线起点，并自行初始化进度。信号系统插件可以附加自己注册的资源要求，但这些要求只增加最终 union。

SimpleATO本身只认识“当前可用grant边界”和“外部限制”，不把普通固定闭塞、保护区域或移动闭塞编成一个Basic-owned `AuthoritySection`模型：

- 固定闭塞插件可以把完整区间作为附加资源并只在整段可用时允许进入；
- 保护区域插件可以验证自己的入口/出口与内部路线，选择足以驶离区域的boundary并附加侧面防护资源；
- 移动闭塞在没有不可分割区间时允许按路线与制动/跟车约束形成更精细的 authority。

同一具体信号插件负责验证自己定义间的重叠与组合。Basic不理解这些定义，只验证路线物理底线、附加资源合法性和原子冲突。最终列车限制仍通过一个共享管线取最严格结果。

长路线不一次申请全部资源。一个live grant接近当前authority boundary时，SimpleATO可以为**同一份**frozen route选择
更远的desired boundary并提交extension intent；何时触发、向前看多远以及按固定/移动闭塞如何选目标属于SimpleATO运营
调度与信号插件策略。Basic不决定lookahead policy，只验证同一route/topology上的suffix、推导其物理底线、原子保留delta并准备新增设备。pending或denied
extension不改变当前authority constraint；原grant的车尾释放不因准备delta而暂停。只有Basic完成delta提交、原controller
队尾追加并发布新committed grant boundary后，SimpleATO才从已提交grant视图生成更宽的authority constraint。

extension不会把路线“原地改写”。topology、route revision、有序traversal、mission target或运营plan要求改变时，SimpleATO
必须把它视为replan，并在站台等安全运营边界结束旧grant后重新准入；不得用extension、caller footprint或whole-claim
replace绕过该边界。同一grant已有pending extension时，SimpleATO轮询该handle而不是并行提交另一个extension；这是同进程
生命周期约束，不需要保存request历史或模拟网络重试。Basic的typed stale/conflict/preparation结果由既有dispatcher策略消费。

### 3.3 单一列车保护管线

车辆跟随、运营停车点、未获准进路、固定闭塞、信号限速、临时限速和线路限速都产生不可变 constraint candidates，随后由现有 ATO/ATP 控制骨架做一次最严格结果归约。MA 是该共享约束状态的输出/诊断视图，不是与 `FollowConstraint` 并行的第二套扫描器或控制器。

每个 controlled train 在一个成功 physics epoch 中最多执行一次前方路线 perception。区间、车辆和信号插件从这次扫描及 world-level 索引读取数据；任何策略都不得为了自己的信号显示或资源判断再做一次完整路线遍历。Basic 的 grant/denial 作为候选输入，不在 SimpleATO 中复制资源冲突计算。

### 3.4 资源检查距离与响应式更新

每个可被ATO提前申请的资源通过信号插件提供`InspectionDistance(train)`语义和`update(train)`检查语义。检查距离不是每tick运行任意脚本的理由：依赖路线、资源定义、信号配置或低频列车能力（例如长度、制动等级、车型）的部分按对应revision缓存；当前速度产生的实时制动距离由SimpleATO自己的控制数学每tick计算。有效检查距离取缓存的资源声明与动态制动距离加安全余量中的较大值。

SimpleATO的一次共享route perception负责判断列车何时进入资源检查范围。首次进入、占用/claim/grant变化、相关配置revision变化或依赖的下游信号状态变化时，调度该资源的`update(train)`；输入和依赖均未变化时资源保持`IDLE`，不重复执行插件代码。信号插件必须显式发布其依赖变化，不能通过每tick全世界轮询来弥补缺失依赖。

具体信号插件可以在Java/Kotlin中实现完整规则，并自行选择JS、YAML或其他配置层。SimpleATO只提供检查调度与最严格限制归约，不规定所有插件必须共用一个JS运行时。

## 4. 接口与数据合同

以下仅为目标概念 shape；完整类型名和 public package 必须在 API 实现批次冻结：

```kotlin
data class FrozenOperationalRoute(
    val topologyRevision: Long,
    val routeRevision: Long,
    val traversal: List<DirectedTrackEdgeRef>
)

data class AutomaticRouteIntent(
    val requestId: String,
    val ownerId: String,
    val trainId: UUID,
    val observation: ObservationStamp,
    val route: FrozenOperationalRoute,
    val desiredAuthorityBoundary: RouteBoundaryRef
)
```

`observation`中的 physics epoch 必须与选路所读列车状态一致；`route.topologyRevision`是唯一 topology 绑定，request 不再复制第二个 expected revision。Basic 返回 typed grant/denial；SimpleATO 只消费结果，不读取或修改 Basic 内部 claim registry。

运营 overlay 引用优先保存 stable rail UUID + canonical AB interval 或独立 stable operational ID；revision-local node/edge ID 只可作为已解析 runtime reference，不能默认作为跨重启身份。

## 5. 不变量、生命周期、线程与版本

- 同一次选路只能组合一个 topology root、一个 coherent physics observation 和一个已应用运营 plan/overlay revision；不得拼接“最新”组件。
- topology、overlay、mission 或 route target 改变后，旧 route 不会被就地改写；生成新 `routeRevision` 并重新准入。
- Basic denial 不扩大 authority。现有安全 authority 保持或收紧，直到新 grant 成功。
- 同一路线extension pending、conflict或安全preparation failure时保持旧authority；只有Basic在delta preparation ready后
  完成claim提交、队尾追加与新grant boundary publication，SimpleATO才消费该边界。pending期间旧资源继续按车尾释放。
  SimpleATO不拥有delta rollback或tail queue。
- live grant 的 resource lifecycle 由 Basic 持有到目标列车进入并由车尾完全清除；SimpleATO mission replacement、插件 disable 或 route replan 不能直接提前释放。
- applied timetable 在生效前完成路线/信号配置校验，并通过 host lease 锁定所引用基础设施；draft 保存不产生 lease。
- physics worker 只消费不可变输入并产出 constraint/result；拓扑编辑、资源 claim、Bukkit side effect 按各 owner 的线程合同执行，SimpleATO callback 不阻塞 physics publication。

## 6. 校验与错误矩阵

| 条件 | SimpleATO 行为 | authority/资源 |
| --- | --- | --- |
| topology/observation/overlay revision 不一致 | 放弃本次计算，等待 coherent state 重算 | 不申请；authority 不扩大 |
| 找不到满足方向和运营约束的路径 | mission/plan typed failure | 不申请 |
| 冻结 traversal 被 Basic 判定断裂或非法 | 将 route 标记失效；仅在输入 revision 改变后重算 | Basic 无 mutation |
| 具体信号插件判定路线缺少所需保护/闭塞定义 | 报告插件自己的稳定配置错误 | 不逐 tick 重试；不请求 permissive grant |
| 物理占用或资源冲突 | 按 dispatcher 排队/重试策略处理 | Basic 无 partial claim |
| grant 成功 | 将 grant boundary 转为 constraint candidate | 不绕过 Basic 扩大边界 |
| provider/模块失效或约束无法定位 | fail closed，撤销其放宽效果并保持/施加限制 | live claim 交由 host recovery |
| route 有 no-controlled-resource 且无需 claim | 接受 `NOT_REQUIRED` 并继续普通约束计算 | 不创建空 claim |

稳定配置错误由 topology/overlay/plan 事件驱动重试；暂态占用冲突才按明确调度策略重试。不得把所有 denial 都变成每 tick 全量重新寻路。

## 7. 性能、复杂度与退化行为

- 当前内部选路器每次查询从已发布`TopologyState`构造revision-local node→incident-edge只读索引，随后执行Dijkstra。令`P`为可到达入口port状态数、`C`为这些port之间实际展开的合法continuation数、`R_q`为所有候选edge方向过滤实际检查的physical rail slice次数、`S_out`为结果rail slice数，则时间复杂度为`O((P + C) log P + R_q + S_out)`，空间复杂度为`O(P + E)`；不能把高阶turnout的port-pair展开错误地只计成一个edge。它不从live rails重建图、不读取当前道岔选位，也不修改topology；后续批量选路可以按topology root缓存incident-edge索引，但缓存不得跨revision复用。
- 当前单次`FrozenRouteSelector.select`按`trainId`线性查找一次不可变observation列车列表，成本为`O(T)`；选路是mission/target/revision事件驱动，不得逐tick、逐信号或每次authority extension调用。未来Dispatcher在同一observation批量为多列车选路时，应在该次调度上下文只建立一次`trainId -> RailwayTrainObservation`索引并复用，使批量查找为`O(T + Q)`；不得让每个selector各自复制索引或引入跨observation缓存生命周期。
- 一旦路线冻结，前方 perception 对实际扫描长度为线性；一个 controlled train、一个 physics epoch 只做一次，constraint 归约对候选数线性。
- world occupancy、区间状态和 Basic grant 状态应先按 rail/resource 建索引；禁止 `allTrains × allSections` 或每个信号各自 lookup 全世界。
- unchanged stable denial 使用缓存的 typed result 与 revision key；相关 revision 不变时不重复昂贵解析。
- 索引缺失、provider 异常或 revision 在计算中改变时 fail closed；不得退化到另一套 live graph/资源扫描并继续授权。

## 8. Good / Base / Bad cases

- Good：列车去往运营停车点，SimpleATO 选择一条包含道岔和 diamond 的 traversal；Basic 从同一 topology root 验证并收集资源，SimpleATO 从 grant 生成唯一 authority constraint。
- Good：保护区域出口后的列车净空进入固定闭塞；信号插件的route-decision层选择包含所需固定区间的boundary与addition，Basic只推导物理底线并原子claim最终union，region对象本身不递归查询后继。
- Base：纯直线移动闭塞路线没有 controlled resource；Basic 返回 `NOT_REQUIRED`，SimpleATO 仍用车辆/停车/速度约束控制列车。
- Bad：SimpleATO 同时提交 route 与自己猜测的 turnout/diamond footprint；两份事实可能漂移，必须只提交 route。
- Bad：每个信号脚本重新遍历前方路线和所有列车；必须消费一次 perception 与索引化 section observation。
- Bad：Basic 因资源冲突自行换另一条路后授权；运营选择只能回到 SimpleATO 产生新 route revision。

## 9. 必需测试与断言点

- route selection：方向限制、edge 内 virtual endpoint、零/一/多 turnout、纯环、无路线，且输入相同得到确定 traversal。
- observed frozen-route seam：成功路线从同一observation的具名列车tail及其facing精确开始；observation/overlay
  topology mismatch、列车缺失/脱轨和planner的invalid target/unreachable均保持各自typed rejection。
- revision binding：route 与 topology revision 独立；任何 observation/topology/overlay stale 都不发请求、不扩大 authority。
- admission client：请求没有可替代Basic物理底线的第二份资源清单；合法addition、conflict、`NOT_REQUIRED` 和 grant 分支均保持规定 lifecycle。
- rolling extension：只沿同一frozen route发出更远boundary；同一grant最多一个pending handle，期间旧资源继续释放且
  pending/denied不扩大authority，route/topology/plan改变走replan而非extension。client lookahead policy用独立策略测试，
  但其数值尚未冻结。
- signal-plugin integration：固定闭塞、保护区域和移动闭塞插件结果都只能收紧限制；附加资源只增加Basic最终union，不能删除物理底线。
- inspection scheduling：静态/低频检查距离按revision缓存，动态制动距离每tick由host计算；无依赖变化时插件`update(train)`保持IDLE。
- control pipeline：车辆、停车、信号和 grant candidates 只做一次 perception，并归约到最严格 speed/authority。
- retry：稳定配置错误 revision 不变时不逐 tick 重算；占用释放事件允许重新准入。
- migration parity：迁移前后同一 fixture 的 route、stop/speed constraint、dispatcher priority 与 manual-mode 行为一致。

## 10. 迁移、未决事项与 Wrong / Correct

迁移顺序是：先稳定 Core `TopologyState` 与 Basic route-validation seam，再把当前 Basic 中的运营 route producer、mission/constraint owner 和 Dispatcher client 整体迁入 SimpleATO，最后迁移 Destination、Station、Depot、Line、Timetable 及其持久化/composition。每批只允许一个可写 owner。

尚未冻结：完整 public type/package、rolling extension公开字段及typed failure、client lookahead policy、路线 cost policy、
overlay持久化边界、信号插件检查/限制接口、具体插件的JS setter/capability/预算，以及WebUI是否独立成模块。这些
问题必须在对应实现批次单独审查；本页不替它们选择兼容格式。

```text
Wrong: SimpleATO selects a destination; Basic searches and chooses the physical route
Correct: SimpleATO submits one explicit topology-bound traversal; Basic only validates and claims it

Wrong: MA and FollowConstraint each scan and control the train independently
Correct: one perception pass produces candidates; one train-protection pipeline reduces them

Wrong: Basic and SimpleATO both own mutable copies of the operational graph
Correct: Core owns TopologyState; SimpleATO owns disposable revision-bound overlay projections
```
