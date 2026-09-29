# SimpleATO 运营选路与行车授权契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`TRANSITIONAL`；`SimpleATO` Gradle 模块已经拥有具体ATO、Destination、Station、Depot、Line、Timetable model/store/persistence、Dispatcher、统一immutable-topology运营选路/冻结路线、admission client、限制适配器与inspection scheduler/runtime，Bukkit production Dispatcher已接入Basic通用准入。共享ATO primitive仍在Basic；具体信号插件、其内部依赖传播与presentation仍是Stage 2目标。
- **Owner**：SimpleATO 拥有运营路线选择、运行方向、任务/时刻表决策和列车保护约束合成。
- **Inputs**：Core 发布的不可变 `TopologyState` 与同 epoch 物理观察；SimpleATO 自己发布的运营 overlay/plan revision；Basic 的通用资源准入结果；已安装信号系统插件发布的资源检查与限制结果。
- **Outputs**：一份绑定 topology revision 的冻结路线、资源准入请求，以及交给控制器的统一 authority/stop/speed constraint 结果。
- **Non-goals**：本页不定义 Core 图构建、Basic 资源推导/claim、Bukkit 渲染、信号模型、JS API 或持久化 schema。

触发：迁移或修改 ATO mission、route planning、运行方向、固定/移动闭塞选择、保护区域交互、时刻表 dispatch、MA/stop/speed constraint 合成。

当前迁移状态：SimpleATO的运行方向编译成Basic中立的`forbiddenTraversals`；插件source通过共用
owner/world lifecycle注册，并与运行方向在每次查询前编译、确定性合并。Mission、Station、Line、Timetable、
observation-based frozen route和Bukkit route diagnostic均经过同一个`OperationalRoutePlanner` planning seam；
legacy live-`RailView` planner与二次live compiler已经删除。

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

SimpleATO不识别保护区域、固定闭塞或某一国家信号制式的具体规则。它产生冻结路线、同一观察stamp、stable train ID
和运营上允许扫描的前方范围；Basic自行加入不可删除的物理资源底线。没有信号策略时SimpleATO按移动闭塞策略选择
boundary并提交无addition的intent；需要插件策略时，具体信号插件在该范围内选择desired boundary、产出纯数据
addition，并由其认证准入协调器提交同一冻结路线。SimpleATO不冒充插件身份或重新包装其addition，只消费Basic发布
的committed grant。插件不能删改Basic的rail/turnout/diamond资源，也不能绕过最终grant；新增制式不应要求
SimpleATO增加类型分支。

没有 SimpleATO 时，不产生自动路线grant。Basic 可以保留经过物理锁和 active-claim 检查的显式手动道岔操作，但该操作不产生 movement authority。

## 3. 内部机制与关键算法

### 3.1 运营 overlay 与选路

运行方向、站台/停车点、线路和通用运营限速是SimpleATO拥有、并引用Core edge/rail slice的运营overlay，不是另一张可独立修改的物理连通图。固定闭塞、保护区域、信号边界和联锁表由具体信号系统插件拥有；SimpleATO只消费它们通过公共检查/限制接口给出的结果。路线起终点落在edge内部时，选路查询使用临时virtual split；不得为了站台或信号永久切分`TopologyState`。

选路输入固定为一个 topology root、一个运营 overlay/plan revision、列车属性和当前运营目标。算法只在 Core 声明的合法 continuation 中搜索，再按方向、线路、车辆能力、停站与计划约束过滤或计权。通用最短路实现可以来自共享算法包，但运营 cost、约束和候选选择由 SimpleATO 拥有。结果必须是一条明确的有向 traversal；不得只给目的地让 Basic 自行寻找可行路线。

`SimpleATOWorldData.operatingDirections`是唯一可写owner：缺少rail UUID表示双向许可，存在记录表示只许可所存的单一`ViewDirection`。authoring、mission/station/line/timetable route search、初始定位fallback与持久化都读这一store；Basic planner只接收显式遍历谓词。split把一条记录复制给两个child，merge只接受相同记录，几何replacement/undo在同一RailWand transaction按UUID映射迁移。

当前已实现的第一批内部选路器直接读取一次不可变`TopologyState`和从store取得的一份绑定相同topology revision的防御性复制overlay。它先用`railProjection`把起终点的stable rail UUID + canonical AB米位置解析为edge距离；起终点落在edge内部时只创建查询局部的部分edge traversal，不改写或复制published topology。随后以“到达结构节点的入口port”为Dijkstra状态，以实际edge米数为权重：turnout只展开`legalContinuations`允许的出口，loop anchor只允许从另一port继续，terminus不展开。每个方向的edge traversal还必须满足所经过物理rail的运行方向约束。相同距离的候选按固定起始方向、deterministic edge ID和port次序展开，因而相同输入得到相同路线。

目标正好位于共享结构节点的另一条incident edge起点时，搜索只有在入口port→目标port continuation合法后才生成该目标rail的零长度末段标记；它不会为了得到非零segment多跑一圈。该标记保留目标port与方向，使Basic后续线性投影仍可验证turnout/loop-anchor transition。搜索成功后，选路器沿每个edge的有序`railSlices`裁剪查询局部首尾区间，并按实际行进方向转换成递增的AB或BA局部弧长，生成现有不可变`RouteSnapshot`。

当前Core builder保证每条物理rail完整地、恰好投影为一个`TrackEdgeSlice`，当前Basic projector也要求单一无歧义projection；本选路器与这一**当前实现不变量**一致，遇到零个或多个projection会拒绝位置。它不是允许未来partial/multi-edge rail projection的通用API保证；若Core以后放宽builder，Core位置查询、Basic projector和本选路器必须在同一契约批次一起修改。snapshot精确保留输入topology revision及调用方给出的route ID/revision。运行方向只参与计划生成；计划改变由新的route revision表达，不把metadata或direction revision带入运行中的资源申请。当前这一类型仍为SimpleATO internal：production Dispatcher从已选定的Mission route组装准入路线，不在准入时再运行该选路器；线路、车辆能力和时刻表cost仍属后续运营迁移，因此该类型不构成冻结public API。

当前内部`FrozenRouteSelector`是观测与上述纯选路器之间的唯一接缝。一次调用显式接收同一个不可变
`TopologyState`、一个`RailwayObservation`、一个运行方向overlay、稳定train ID、运营目标及route
identity/revision；先要求observation与overlay的topology revision都等于该topology root，再只从这份
observation解析具名列车。脱轨列车作为无效权威起点被拒绝；成功时将该列车的tail rail、canonical AB
位置和facing作为planner起点，并只调用一次既有Dijkstra。返回值保留原observation stamp
和生成的`RouteSnapshot`，供后续Basic准入客户端提交同一批输入。stale observation、stale overlay、列车缺失、
列车无效和planner拒绝分别是typed internal结果；planner的`INVALID_LOCATION`/`UNREACHABLE`等原始reason不被
抹平。该接缝不读取live train、不申请资源，也不为测试暴露Basic内部progress cursor；首segment的rail、facing
和局部起点必须精确对应权威tail，Basic随后仍独立验证这一不变量。

每个world的`RoutePlanningRestrictionRuntime`原子保存一份按namespaced source ID排序的不可变source binding
快照。Bukkit的`RoutePlanningRestrictionSourceService`按plugin/world整体replace某个owner的全集，并与signal epoch、
inspection source共用同一个内部owner registry、world unload和`InfrastructureResourceService.unregisterOwner`
入口，不创建第二套token或卸载状态机。一次`FrozenRouteSelector`查询先捕获source快照，依稳定顺序各调用一次，
再与运行方向restriction一次性combine；replacement若ID冲突或非法则不发布候选全集。source异常、返回错误topology
revision或合并失败映射为`INVALID_RESTRICTIONS`，不会启动Dijkstra。正常unregister直接清除这些低频规划callback；
强制disable也立即清除它们，但仅有规划source注册不会stall world，因为已选择路线已经冻结了其结果，真正active
restriction、inspection binding、claim和infrastructure dependency仍按各自既有规则fail closed。

生产Dispatcher已经拥有由mission resolver选定的head-forward路线，不能在准入时再跑一次最短路。Core观察
因此额外发布epoch末实际`bodyTraversal`，即从保守车尾到车头的有序物理路径。内部
`FrozenMissionRouteAssembler`信任Core同一原子frame内的body/head/tail一致性，只检查observation与topology
版本、mission progress范围，以及body终点能否接上裁剪后的预选路线起点；随后把body接在前面。它不重复验证完整body、
不重新投影车头occurrence、不读取运行方向、不运行Dijkstra，也不猜测道岔分支。跨rail拼接只要求前一段到达其方向终点且后一段从方向起点开始，真实continuation合法性仍由Basic证明。返回值把实际body长度记录为head-based mission
进度到Basic tail-started route-metre的固定偏移。最终连通性和拓扑合法性仍由Basic在线性准入验证中证明。

普通停车点的下一项正向mission不重新从当前车尾建立第二个route owner。Assembler把已提交operating route的segments逐项
原样保留，只允许从进度0开始把完整的新mission追加到旧末端；route ID保持不变，operating-route revision由旧revision加一，
不复用各mission彼此独立的revision序列。新mission的route-progress偏移是旧route总长。Long revision溢出、修改旧前缀、
无法连接或反向接续均返回typed rejection；最终物理continuation仍由Basic验证。这个append只产生候选value，pending期间
client和controller继续使用旧route binding，Basic `READY`后才一起切换到新长度与revision。折返本身不使用append：Dispatcher先暂停同一grant的tail release，物理折返发布后立即从新tail到新head构造body-only route，并以observed head作为初始authority调用Basic rebase。之后的反向mission仍使用既有规划结果，只按新head已有mission progress裁去与车身重复的路线前缀，再走普通append。

同一次Dispatcher处理使用一个`FrozenRouteSelectionContext`为该不可变observation建立train ID索引；普通
选择器和mission一致性接缝都从该索引解析列车，不能各自再次线性扫描train列表。context不跨observation
保存，也不缓存topology或route结果。

Dispatcher在安装inspection binding或向Basic提交准入前，把完整exact frozen route编译为同一通用registry中的
非独占infrastructure dependency，覆盖路线轨道、相关拓扑节点及Basic物理资源。它只阻止相交编辑，不是claim，
不妨碍列车取得资源。相同路线幂等；正向append用同owner更高revision原子替换；折返rebase成功前先保护旧、新
路线依赖的并集，失败或重试保留并集，成功后再收缩到新路线。顺序service只转移一个dependency owner；旧run不再
拥有释放权。只有旧binding和Basic admission生命周期结束后才删除依赖，任何set/remove失败均保留较安全旧状态并stall。
时刻表应用时的静态selected-route dependency仍使用另一owner及独立生命周期。

### 3.2 冻结路线与准入客户端

冻结路线保存其生成时读取的 `topologyRevision`、独立的 `routeRevision` 和有序 traversal。SimpleATO 向 Basic 提交路线、列车 ID、同一 observation stamp 与期望 authority boundary；不提交 caller-authored route progress/occurrence index、可替代物理底线的 raw `InfrastructureFootprint`、可信列车长度或已枚举的 turnout/diamond 列表。Basic 从同一 observation 的权威列车尾验证冻结路线起点，并自行初始化进度。信号系统插件可以附加自己注册的资源要求，但这些要求只增加最终 union。每项addition必须包含exact stable identity与显式route-metre车尾释放boundary；可选preparation descriptor只描述provider-owned target，不能携带callback或mutable train/world。完整shape由[Basic路线资源准入](../basic/route-resource-admission.md)唯一拥有。

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

普通authority extension不会改写路线。到达已完整授权的路线末端后，下一项正向mission可以形成上述严格append候选，并在
同一grant下申请更远boundary；旧前缀、owner、train、world与topology均不变。topology、旧前缀、route ID或运营plan发生其他
变化时仍是replan；折返只允许走已评审的stopped-rebase边界，不能伪装为append、caller footprint或whole-claim replace。同一grant已有
pending request时，SimpleATO轮询该handle而不是并行提交另一个请求；这是同进程生命周期约束，不需要保存request历史或模拟
网络重试。Basic的typed stale/conflict/preparation结果由既有dispatcher策略消费。

当前已实现的内部`RouteAuthorityAdmissionClient`是该生命周期的第一批平台无关consumer。每列车实例同一时刻只保存一份
完整`FrozenRouteSelectionResult.Selected`、一个nullable committed grant和至多一个pending handle；Basic的首次申请与同路线
延长都调用同一个`RouteResourceAdmissionService.request`。第一次处理冻结选择时，client先以
`simpleato:resource-admission`来源发布位于路线起点0 m的`MovementAuthorityRestriction`，因此尚无grant、首次pending或首次
拒绝（包括选择属于另一列车的调用方错误）都不会产生可用前方权限。`Granted`是唯一能改变该来源的结果：部分授权把来源替换为返回的`committedAuthority`边界；授权已到完整frozen route末端时发布更高revision空restriction，明确撤销旧截断，由同一mission的终点Stop constraint停车。extension pending及rejected不发布新restriction，继续使用旧grant边界。pending只通过原handle调用`progress`，不重放intent。

client以route ID/topology binding、train和world做常量时间预筛；完整有序traversal、revision和append-only关系仍由
Basic的现有route state权威核对。新的observation epoch可以用于同路线延长或正向mission append。pending continuation保存
候选selection与handle，但继续发布旧grant的route binding；只有`Granted`才原子替换本地selection/binding并发布新authority。
折返时client先要求Basic暂停旧cursor；新方向发布后提交body-only的new-tail-started selection与body-length boundary。Basic成功rebase后，client保留grant ID、切换restriction route binding，并把authority收紧到新head；后续rolling request把已规划反向mission从当前head progress开始的后缀追加到该body boundary。stop target与authority end是调用方配置的两个边界，SimpleATO不从stop tolerance自动推导资源余量。
route ID、train、world或topology变化返回`ROUTE_STATE_MISMATCH`或`TRAIN_NOT_FOUND`并要求调用方走安全replan。结束时，
有grant的路线必须先由Basic `endGrant`确认资源和pending均已清空，然后client才发布该来源的更高revision空snapshot；若该空
snapshot发布失败，后续显式结束只重试restriction撤销，不会对已经结束的Basic grant重复调用`endGrant`。首次安全
中止且从未产生grant的路线可直接显式结束。pending不能通过清空restriction掩盖；`WORLD_STALLED`会锁存并拒绝后续申请或结束。client不实现自动重试、
timeout、generation token、持久化或Bukkit调度，也不拥有Basic的tail-release队列。production Dispatcher在一次Tick共享一个observation/context，自动和`MANUAL_ROUTED`运行复用该client；`FULL_MANUAL`只推进既有pending并尝试正常结束，绝不发新request。

### 3.3 单一列车保护管线

车辆跟随、运营停车点、未获准进路、固定闭塞、信号限速、临时限速和线路限速都产生不可变 constraint candidates，随后由现有 ATO/ATP 控制骨架做一次最严格结果归约。MA 是该共享约束状态的输出/诊断视图，不是与 `FollowConstraint` 并行的第二套扫描器或控制器。

每个 controlled train 在一个成功 physics epoch 中最多执行一次前方路线 perception。区间、车辆和信号插件从这次扫描及 world-level 索引读取数据；任何策略都不得为了自己的信号显示或资源判断再做一次完整路线遍历。Basic 的 grant/denial 作为候选输入，不在 SimpleATO 中复制资源冲突计算。

Dispatcher 的滚动资源授权复用这次 perception 已有的距离，不维护第二个前视距离，也不重新扫描路线。首次申请的绝对边界为“冻结路线中的当前车头位置 + perception 距离”；已有 grant 时，仅当剩余授权距离降到现有 route-allocation trigger 后才计算下一边界，并截断到冻结路线末端。pending 期间不发出第二个请求。这个判断只读取路线总长、车头进度和 admission client 的 committed/pending 状态，时间与额外空间均为 `O(1)`；重试时机、拓扑变化后的重新选路和跨 mission 生命周期仍由 Dispatcher 负责。

当前第一批公开限制契约位于`simpleato.api.restriction`。一个外部信号来源为一列车发布完整不可变
`TrainRestrictionSnapshot`，其中`sourceId`和每项`restrictionId`都必须为namespaced稳定身份；revision只在
`(sourceId, trainId)`流内严格递增。snapshot绑定生成它的`RailwayStateStamp`以及冻结路线的route ID、route revision、
topology revision和总长度。远端目标统一用冻结路线起点起算的绝对米数表达，适配时才减去当前route progress，转换成
现有`CurrentSpeedLimitConstraint`、`RouteSpeedLimitConstraint`、`StopConstraint`或
`MovementAuthorityConstraint`。信号插件不接触控制器，也不创建第二套constraint归约器。

同一来源的新revision完整替换旧snapshot；更高revision的空列表是唯一的正常撤销语义，迟到或重复revision不能覆盖
已接受结果。world不匹配、topology过期、冻结路线身份/版本/长度改变、来源被明确标记不可用或当前progress越过路线范围时，
adapter以该来源的constraint key安装零速`DepartureHoldConstraint`而不是静默删除限制。每列车的adapter把全部来源转换后
通过一个`SIGNALLING` owner一次性replace，避免逐来源更新期间短暂扩大许可。physics epoch晚于生成观察是正常消费，早于生成
观察才是时间倒置。当前adapter已接入production timetable controller与Bukkit认证Basic服务，host inspection scheduler/runtime也已组合到同一控制器perception流程。具体信号插件的保护区、固定闭塞、内部依赖传播与presentation仍属Stage 2，因此不能把host接线描述成完整信号系统已经生产可用。

每次应用令`S`为该列车已发布的信号来源数、`R`为其限制总数，时间和临时空间均为`O(S + R)`。来源使用有序map在发布时
以`O(log S)`维护稳定顺序，physics tick不再排序；route binding是常量大小值对象，应用时不扫描或比较路线segment。snapshot
构造时对限制集合做一次防御性复制，之后physics只读该副本。

### 3.4 资源检查距离与响应式更新

监测区变化到同步restriction publication的已实现owner contract见[响应式信号epoch](./reactive-signal-epochs.md)。该接缝负责
area-driven dirty source；本节另行冻结资源何时进入列车检查范围。两者可以由同一信号插件组合，但不能演变成两套前方扫描器
或一个由host维护的通用信号依赖图。

#### 3.4.1 声明模型与 owner

`InspectionDistance(train)`是“这个插件资源对这列车应提前多少米开始检查”的策略语义，不是SimpleATO每tick调用的函数。
信号插件在冻结路线首次绑定、插件定义变化或列车低频能力变化时计算该语义，并提交一份完整不可变的route-local声明集。
`train`只表示不可变列车身份和低频能力视图，例如长度、车型或制动等级；当前速度不进入插件缓存键。

当前公开值类型为`simpleato.api.route.FrozenRouteBinding`、
`simpleato.api.inspection.InspectionSource`、`InspectionBindingInput`、`InspectionDeclaration`、
`InspectionDeclarationBinding`、`InspectionUpdateInput`和`InspectionUpdateHandler`。插件不自行发现或猜测
production route；host在Dispatcher选定exact `RouteSnapshot`后调用source binding provider，provider为该train/route
返回一份完整binding。`InspectionBindingInput`只提供稳定train ID和host选择的route，不暴露live train或当前速度；插件以train ID
读取自己拥有的immutable低频能力profile并把其revision写入binding。每个声明具有：

- namespaced稳定来源ID和资源/信号ID；
- 当前冻结路线binding及该资源在路线中的occurrence-specific绝对触发米数；
- 有限、非负的提前检查距离，单位为米；
- 可选的最小请求终点：nominal authority严格越过本声明触发点后，请求边界至少到达该route-metre；
- 插件定义revision和列车低频能力revision。

同一rail在环线或折返路线中可以重复出现，因此不能只用rail UUID定位声明。编译器先用路线累计距离二分定位声明的唯一
segment occurrence，并以声明携带的rail UUID和AB/BA方向做一次交叉验证；随后只需按occurrence-specific绝对触发米数排序声明，
并保存声明距离的最大值。空声明集的最大值为0。插件原子替换完整binding，SimpleATO不逐项拼接来自不同route/profile revision的声明。

最小请求终点只改变提交给Basic的请求形状，不直接扩大grant。Dispatcher先按既有滚动策略计算nominal boundary，
再读取binding时已编译、按触发米数排序的route-local floor。查找先二分定位已严格越过的触发点，用prefix maximum取得
初始最远终点，再向前单调扫过因抬高边界而新越过的重叠窗口；相邻窗口入口恰好等于当前终点时不连锁。因此请求一次
成本为`O(log D + k)`，`D`为声明数、`k`为本次实际跨过的后续要求数；没有第二次路线扫描或每tick插件回调。

职责固定为：

- SimpleATO拥有binding、激活状态、动态制动数学和现有单次route perception；
- 具体信号插件拥有定义索引、`InspectionDistance`策略和下游依赖反向传播；
- Basic只验证/claim实际提交的资源，不调度检查，也不识别固定闭塞、保护区域或某一信号制式。

#### 3.4.2 单次扫描与激活算法

当前控制器的基础perception horizon按列车最高速度的service braking、reaction time、安全距离和固定扫描余量计算。因此它
已经覆盖任何当前速度下的动态制动前视。SimpleATO只把本binding的最大插件声明距离并入该horizon：

```text
baseHorizon = existingControllerPerceptionHorizon(maximumTrainSpeed)
scanHorizon = max(baseHorizon, binding.maximumDeclaredInspectionDistance)
perception = scanForwardOnce(trainHead, scanHorizon)

dynamicLookahead = hostStoppingLookahead(currentSpeed)
candidates = declarations.binaryRange(
  currentHeadRouteMetres,
  currentHeadRouteMetres + max(binding.maximumDeclaredInspectionDistance, dynamicLookahead)
)
for each declaration in candidates:
  distance = declaration.triggerRouteMetres - currentHeadRouteMetres
  threshold = max(declaration.declaredInspectionDistance, dynamicLookahead)
active = candidates.filter(distance <= threshold)
if active != previousActive or any(active is explicitlyDirty):
  scheduleUpdate(completeActiveSet, immutableObservation, perception)
```

`perception`同时供车辆、轨道物体、运营停车点、既有constraint provider和插件更新使用；声明激活只查询排序后的绝对路线坐标，
不得再调用`peekCurve`、重新寻路或遍历完整冻结路线。使用binding最大值可能比每项分别决定扫描长度多看一段路线，这是为保持
一次扫描而接受的保守成本，不为尚未证明存在的极端短区间另建第二套窗口算法。

#### 3.4.3 缓存失效与响应式更新

以下变化使完整声明binding失效并要求插件重新编译：冻结路线binding变化、来源定义revision变化或列车低频能力revision变化。
当前速度变化只重算`dynamicLookahead`，不执行插件编译。占用、claim/grant或下游信号状态等运行变化由具体信号插件自己的
反向索引转换为“哪些稳定声明已dirty”；SimpleATO只记录并调度这些ID，不复制其依赖图。

声明首次进入有效检查范围时激活一次。此后只有显式dirty、完整binding替换、进入或离开范围才调度来源；距离正常变化而完整
active集合未变时保持`IDLE`。每次调用收到当前完整active声明集合，而不是增量列表，并必须返回一个完整restriction snapshot；
最后一个声明离开时集合为空，插件用新的空restriction snapshot清除已经越过的route-local信号限制。Basic资源仍只按其
grant/tail-release生命周期释放，不能因检查范围变化而撤销。

每份新编译binding在第一次使用时另有一次baseline update；此时active声明集合可以为空，但插件必须发布一个绑定新路线的完整snapshot，
即使其中restriction为空。这样旧路线snapshot不会因新路线暂时没有声明进入范围而留下fail-close hold。baseline成功后仍只按新激活或
离开、显式dirty更新，不转为每tick回调。

如果当前应存在的来源没有可验证的完整binding、binding与route/profile revision不符、dirty后的更新失败，或更新结果未能绑定
当前world/topology/route，SimpleATO必须在该来源上保持或安装零authority/零速限制，直到新的完整结果成功提交。失效期间不能
静默沿用旧的permissive结果，也不能退化到每tick全世界轮询。一个来源的失败不允许撤销Basic已有claim，也不改变其他来源的限制。

具体信号插件可以在Java/Kotlin中实现完整规则，并自行选择JS、YAML或其他配置层。SimpleATO只提供检查调度与最严格限制归约，
不规定所有插件必须共用一个JS运行时。

当前平台无关`InspectionRuntime`为每个`(sourceId, trainId)`保留一份编译binding、激活/dirty状态和同步handler。
完整binding替换先离线编译，成功后才替换旧session；非法替换不改变旧session。一次update按source ID稳定顺序查询scheduler，
把同一source本次新激活或dirty的声明合并为一个`InspectionUpdateInput`，并复用controller已经完成的`PerceptionSnapshot`。
handler每次必须返回恰好一个该source/列车的完整`TrainRestrictionSnapshot`，不增加第二种restriction结果模型；即使当前没有
restriction，baseline、激活集合变化和最后一个声明离开仍返回更高revision的空restriction snapshot，以明确替换旧值。

runtime在首次publisher side effect前验证本批全部callback输出的source、train、observation stamp和frozen route。回调异常、
过期binding、非法输出或publisher拒绝都会先通知注入的fail-closed owner，再向serialized caller抛出；尚未成功发布的声明重新
变为dirty，不会因失败丢失更新。source/train删除只丢弃对应session，不释放Basic claim，也不隐式撤销restriction。该runtime
不创建线程、重试器、Bukkit registry或持久化状态。

production world在创建`SignalEpochRuntime`和restriction store时同时创建一个`InspectionRuntime`。Bukkit公开的
`InspectionSourceService`与`SignalEpochSourceService`共用同一个内部plugin/world owner registry，统一走
`InfrastructureResourceService.unregisterOwner`、forced disable、world unload与host shutdown生命周期。Dispatcher维护exact
active route并调用provider；controller只跨线程读取runtime发布的immutable per-train最大距离，不复制route identity或第二套
route生命周期。新binding可能扩大horizon时，Dispatcher在controller发布覆盖该最大值的新physics perception前保持
`WAITING_ROUTE`，且在此之前不请求、progress或扩大Basic authority；下一epoch仍只执行controller原有一次`peekCurve`。
随后Dispatcher用同一published controller state中的perception、当前冻结路线head progress和host动态制动lookahead同步调用
inspection update，并把返回snapshot写入既有world `TrainRestrictionSnapshotStore`，再经唯一restriction adapter应用。

活动路线期间新增、删除或替换source会改变既有安全策略，不能靠旧perception或旧permission无缝切换。当前host没有安全的
同epoch quiesce/refresh协议，因此普通replacement在离线编译候选后经现有world coordinator立即Stall并保留旧全集；forced
plugin loss先Stall受影响world再删除失效callback。train结束删除对应session，world unload/host shutdown直接清空world-local
binding，不添加retry、持久化、兼容或第二次扫描。

## 4. 接口与数据合同

SimpleATO在无插件addition时直接构造Basic冻结的`RouteResourceIntent`；需要信号插件策略时，它把同一冻结路线与
观察输入交给插件的准入协调器，由插件构造并认证提交intent。两者都不再定义平行的`AutomaticRouteIntent`或
`FrozenOperationalRoute`。`observationStamp.physicsEpoch`必须与选路所读列车状态一致；
`route.topologyRevision`是唯一topology绑定，request不复制第二个expected revision。SimpleATO只消费
`Granted/Pending/Rejected`及其immutable snapshot/handle，不读取或修改Basic内部registry。SimpleATO不得用自己
的身份提交另一插件产生的addition。

运营 overlay 引用优先保存 stable rail UUID + canonical AB interval 或独立 stable operational ID；revision-local node/edge ID 只可作为已解析 runtime reference，不能默认作为跨重启身份。

## 5. 不变量、生命周期、线程与版本

- 同一次选路只能组合一个 topology root、一个 coherent physics observation 和一个已应用运营 plan/overlay revision；不得拼接“最新”组件。
- 普通正向mission接续只追加到已完整授权的旧route末端并递增operating-route revision；折返先暂停释放并在新tail重建route binding/cursor；其他topology、旧前缀、route ID或overlay改变走安全replan。
- Basic denial 不扩大 authority。现有安全 authority 保持或收紧，直到新 grant 成功。
- 每列车准入client在首个committed grant前保持0 m authority；只有Basic `Granted`可扩大该来源，pending/denied保持上一已发布边界。
- 同一路线extension pending、conflict或安全preparation failure时保持旧authority；只有Basic在delta preparation ready后
  完成claim提交、队尾追加与新grant boundary publication，SimpleATO才消费该边界。pending期间旧资源继续按车尾释放。
  SimpleATO不拥有delta rollback或tail queue。
- live grant 的 resource lifecycle 由 Basic 持有到目标列车进入并由车尾完全清除；普通mission append复用该grant，插件 disable 或 route replan 不能直接提前释放。
- applied timetable 在生效前完成路线/信号配置校验，并声明其引用的精确rail、topology node和运营对象依赖；
  编辑器只拒绝与这些依赖相交的修改。它不把轨道当运行资源，也不占用列车行车所需claim。
- physics worker 只消费不可变输入并产出 constraint/result；拓扑编辑、资源 claim、Bukkit side effect 按各 owner 的线程合同执行，SimpleATO callback 不阻塞 physics publication。

## 6. 校验与错误矩阵

| 条件 | SimpleATO 行为 | authority/资源 |
| --- | --- | --- |
| topology/observation不一致，或已应用plan已失效 | 放弃本次计算，等待 coherent state 重算 | 不申请；authority 不扩大 |
| 找不到满足方向和运营约束的路径 | mission/plan typed failure | 不申请 |
| 冻结 traversal 被 Basic 判定断裂或非法 | 将 route 标记失效；仅在输入 revision 改变后重算 | Basic 无 mutation |
| 具体信号插件判定路线缺少所需保护/闭塞定义 | 报告插件自己的稳定配置错误 | 不逐 tick 重试；不请求 permissive grant |
| 物理占用或资源冲突 | 按 dispatcher 排队/重试策略处理 | Basic 无 partial claim |
| grant 成功 | 将 grant boundary 转为 constraint candidate | 不绕过 Basic 扩大边界 |
| provider/模块失效或约束无法定位 | fail closed，撤销其放宽效果并保持/施加限制 | live claim 交由 host recovery |
| desired boundary不晚于现有grant或extension不属于同一冻结路线 | 修正调度或走安全replan | Basic无mutation；旧authority保持 |

稳定配置错误由 topology/overlay/plan 事件驱动重试；暂态占用冲突才按明确调度策略重试。不得把所有 denial 都变成每 tick 全量重新寻路。

## 7. 性能、复杂度与退化行为

- 当前内部选路器每次查询从已发布`TopologyState`构造revision-local node→incident-edge只读索引，随后执行Dijkstra。令`P`为可到达入口port状态数、`C`为这些port之间实际展开的合法continuation数、`R_q`为所有候选edge方向过滤实际检查的physical rail slice次数、`S_out`为结果rail slice数，则时间复杂度为`O((P + C) log P + R_q + S_out)`，空间复杂度为`O(P + E)`；不能把高阶turnout的port-pair展开错误地只计成一个edge。它不从live rails重建图、不读取当前道岔选位，也不修改topology；后续批量选路可以按topology root缓存incident-edge索引，但缓存不得跨revision复用。
- route-planning source全集在低频replace时按ID排序并防御性复制，`K`个source的replacement为`O(K log K)`；一次查询只做`O(1)`快照捕获、每source恰好一次调用和对全部中立span/path的线性合并。plugin callback不进入Dijkstra、tick或资源等待路径。
- `FrozenRouteSelectionContext`对一次不可变observation以`O(T)`建立train ID索引，随后同一Dispatcher批次的`Q`次列车解析合计`O(Q)`；选路仍只在mission/target/revision事件触发，不得逐tick、逐信号或每次authority extension调用。索引不跨observation保存。
- production mission route不调用上述Dijkstra。设Core车体跨越`B`个rail occurrence、预选mission含`M`个segment，`FrozenMissionRouteAssembler`在route session建立时做一次`O(B + M)`转换、裁剪和相邻合并；普通下一mission append复制既有`R`个segment并追加`M`项，成本`O(R + M)`且不重新寻路。authority extension复用结果，不重新装配。
- 当前准入client在首次绑定或成功append后以`O(R)`从segments计算一次restriction route length；pending/denied及普通progress/end的本地状态处理为`O(1)`。完整traversal一致性、资源编译、占用和claim复杂度仍唯一属于Basic。pending期间不轮询路线、不复制资源集合，也不重新发布未变化的旧authority。
- 一旦路线冻结，前方 perception 对实际扫描长度为线性；一个 controlled train、一个 physics epoch 只做一次，constraint 归约对候选数线性。
- 令冻结路线有`S_r`个segment、binding有`D`个声明。编译先以`O(S_r)`建立累计距离，再为每个声明以二分定位occurrence并将
  声明按触发位置排序，总时间为`O(S_r + D log S_r + D log D)`、空间为`O(S_r + D)`。每tick在声明数组中以两次二分取得前方
  候选窗口，再按每项阈值过滤；令窗口内有`A`个候选，则inspection额外时间为`O(log D + A)`，共享perception本身仍按实际扫描
  长度线性、动态制动计算为`O(1)`；成本不包含世界总资源数，也不再构造route occurrence索引。
- 若一列车当前绑定`S_i`个inspection source，本次共有`U`个需要更新的声明和`R`个publication，则平台无关runtime额外成本为
  `O(S_i + U + R)`；source在低频binding替换时写入有序map，tick不再排序。当前每source/列车最多返回一个完整snapshot，
  因此`R <= S_i`。IDLE source不调用插件代码，
  runtime不扫描世界内其他列车、资源或完整路线。
- 外部restriction adapter每次应用只线性转换当前列车的来源与限制`O(S + R)`；不得重新扫描冻结路线、排序来源或调用插件代码。
- world occupancy、区间状态和 Basic grant 状态应先按 rail/resource 建索引；禁止 `allTrains × allSections` 或每个信号各自 lookup 全世界。
- unchanged stable denial 使用缓存的 typed result 与 revision key；相关 revision 不变时不重复昂贵解析。
- 索引缺失、provider 异常或 revision 在计算中改变时 fail closed；不得退化到另一套 live graph/资源扫描并继续授权。

## 8. Good / Base / Bad cases

- Good：列车去往运营停车点，SimpleATO 选择一条包含道岔和 diamond 的 traversal；Basic 从同一 topology root 验证并收集资源，SimpleATO 从 grant 生成唯一 authority constraint。
- Good：保护区域出口后的列车净空进入固定闭塞；信号插件的route-decision层选择包含所需固定区间的boundary与addition，Basic只推导物理底线并原子claim最终union，region对象本身不递归查询后继。
- Base：纯直线移动闭塞路线没有plugin-controlled resource；Basic仍claim所申请范围的mandatory rail intervals，SimpleATO再用车辆、停车和速度constraint限制列车。
- Bad：SimpleATO 同时提交 route 与自己猜测的 turnout/diamond footprint；两份事实可能漂移，必须只提交 route。
- Bad：每个信号脚本重新遍历前方路线和所有列车；必须消费一次 perception 与索引化 section observation。
- Bad：Basic 因资源冲突自行换另一条路后授权；运营选择只能回到 SimpleATO 产生新 route revision。

## 9. 必需测试与断言点

- route selection：方向限制、edge 内 virtual endpoint、零/一/多 turnout、纯环、无路线，且输入相同得到确定 traversal。
- observed frozen-route seam：成功路线从同一observation的具名列车tail及其facing精确开始；observation/overlay
  topology mismatch、列车缺失/脱轨和planner的invalid target/unreachable均保持各自typed rejection。
- revision binding：route 与 topology revision 独立；任何 observation/topology/overlay stale 都不发请求、不扩大 authority。
- admission client：请求没有可替代Basic物理底线的第二份资源清单；合法addition、conflict、pending和grant分支均保持规定lifecycle；直线路段仍由Basic推导rail interval claim，不使用旧`NOT_REQUIRED`捷径。
- admission client lifecycle：首次immediate grant、首次pending的0 m hold、pending→ready、extension/continuation pending或denied保留旧boundary与route binding、continuation READY保留grant ID后切换binding、首次safe abort、显式end以及unrelated route必须先end均有独立断言；输入stamp/train与Basic typed失败不被重写。
- rolling extension：只沿同一frozen route或经Basic验证的严格正向append发出更远boundary；同一grant最多一个pending handle，期间旧资源继续释放且
  pending/denied不扩大authority，修改旧前缀/topology/plan走replan。折返独立证明暂停释放、同grant rebase和新head零剩余authority。client lookahead policy用独立策略测试，
  复用controller既有perception horizon，并在剩余authority到达既有route-allocation trigger时申请下一段；不另设扫描距离。
- signal-plugin integration：固定闭塞、保护区域和移动闭塞插件结果都只能收紧限制；附加资源只增加Basic最终union，不能删除物理底线。
- inspection scheduling：插件只在route/profile/definition变化时编译完整不可变声明；最大声明距离扩展现有单次perception，
  动态制动距离每tick由host计算；编译期occurrence验证、绝对路线坐标激活、首次激活、显式dirty、IDLE和stale fail-close均有独立断言。
- inspection runtime：完整binding替换失败保留旧session；多个source的callback结果在首次publication前完成校验；callback、
  validation或publisher失败通知fail-closed owner且未发布工作保持dirty；source/train移除不触碰Basic claim或伪造restriction撤销。
- control pipeline：车辆、停车、信号和 grant candidates 只做一次 perception，并归约到最严格 speed/authority。
- external restrictions：严格递增revision、不同来源取最严格结果、绝对route metre到剩余距离转换、空新revision撤销、输入集合防御性复制，以及world/topology/route/source failure的零速fail-close。
- retry：稳定配置错误 revision 不变时不逐 tick 重算；占用释放事件允许重新准入。
- migration parity：迁移前后同一 fixture 的 route、stop/speed constraint、dispatcher priority 与 manual-mode 行为一致。

## 10. 迁移、未决事项与 Wrong / Correct

迁移顺序是：先稳定 Core `TopologyState` 与 Basic route-validation seam，再把当前 Basic 中的运营 route producer、mission/constraint owner 和 Dispatcher client 整体迁入 SimpleATO；Timetable、Line、Destination与Station已完成迁移，Depot/rolling-stock及其持久化/composition已完成迁移。每批只允许一个可写 owner。

准入request/result、单位、preparation descriptor与rolling extension关系已由Basic owner spec冻结；inspection-distance的
声明、单次扫描、失效和owner语义由本页冻结；第一批route/declaration public value名称已经实现。尚未冻结：路线 cost policy、
overlay持久化边界、inspection Bukkit service名称、具体插件的JS setter/capability/预算，以及WebUI是否独立成模块。这些问题必须在对应实现批次单独审查；signal epoch
source/result已经由[响应式信号epoch](./reactive-signal-epochs.md)冻结，JS能力和WebUI仍不由本页选择兼容格式。

```text
Wrong: SimpleATO selects a destination; Basic searches and chooses the physical route
Correct: SimpleATO submits one explicit topology-bound traversal; Basic only validates and claims it

Wrong: MA and FollowConstraint each scan and control the train independently
Correct: one perception pass produces candidates; one train-protection pipeline reduces them

Wrong: SimpleATO calls every resource's InspectionDistance/update callback on every tick
Correct: the plugin compiles immutable declarations on low-frequency revision changes; one shared scan activates only matched declarations

Wrong: SimpleATO owns a universal graph describing how every signalling system propagates downstream state
Correct: each signal plugin publishes its dirty declaration IDs; SimpleATO only schedules and reduces their immutable results

Wrong: Basic and SimpleATO both own mutable copies of the operational graph
Correct: Core owns TopologyState; SimpleATO owns disposable revision-bound overlay projections
```
