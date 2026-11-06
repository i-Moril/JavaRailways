# 运营停车目标与授权路线终点分离

## 状态与范围

CURRENT，2026-10-14实现并通过离线联合门禁，包括全局真实停车余量与通用完成检查；不代表游戏内验收；停车精度由[controller独立回归](controller-runtime.md)验收，不能用本页授权测试替代。SimpleATO拥有任务停车目标、冻结授权路线与两者的坐标映射；具体信号插件决定哪些完整物理后续路径必须纳入授权。Basic仍独立校验拓扑、物理资源底线及原子准入。本页不新增资源管理器、寻路器、持久化格式或制动算法。

## 当前实施目标：普通终点安全距离、车站终点余量与真实完成检查

CURRENT：用户在完整固定闭塞批次之后批准优先实现本节，已通过独立复核与受影响模块联合门禁，取代此前有关这两项的暂缓决定；停车精度/制动算法仍不在本批。验证证据与运行时未验收范围见任务implement.md。

### 一次移动区段停车

全局配置为`config.yml`中的`ato.safety-distance-meters`，单位米，缺省5.0，要求有限且非负。宿主加载一次后，把同一不可变值交给各world Dispatcher和真实/隔离仿真ATO安全profile。不是按车型推测的停车误差，也不是修改每车制动力。新增有默认值的配置字段不升schema版本，不增加旧格式loader或在线热切换机制。

普通非车站停车目标100米、全局值5米时，未受信号插件接管的停车终点应冻结真实路径至105米；静态应用时刻表时保护涉及的轨道/节点，实际运行仍按原滚动策略向Basic申请。停车目标仍是100米。仅增加控制标量不能代表已经取得105米授权。

先复用原冻结路径已有后缀；不足时只沿同一不可变拓扑的确定普通连接补足差额，并检查公共运行方向和现有选路限制。不选择未选道岔分支、不另跑最短路、不跨越未完整选择的replacement/保护路径。尽头、分支或其他禁止边界前无法获得所需空间时，明确拒绝应用/候选，保留旧计划和grant，不静默截短余量或移动停车点。

带提前停车偏移时，按真实停点而非定位锚点计算需求：`requiredEnd = anchorRouteDistance - stoppingOffsetMetersBefore + safetyDistanceMeters`。已有路线比这个位置更长就直接复用。验证结果的不可变`routeStoppingOffsetsMeters: List<Double?>`与`routes`逐项对齐：最终停车路线记录实际生效偏移，纯途经路线为`null`，不在途经节点额外创造停车余量。

车站沿用既有终到判定：只有停站位置与任务末端相符的terminal StationCall才以站台解析偏移替代最终Stop偏移。中间车站停稳后仍会继续驶向最终GoTo目标，不能只给中间站留空间而少算最终停车余量。静态与动态用同一判定和偏移语义，原锚点与后续任务连接位置不变。

信号插件必须能区分“未接管该终点”和“已接管，但无需额外路径”，后者包括固定闭塞的真实出口。当前批准的目标签名为：

```kotlin
fun continuation(route: RouteSnapshot, proposedMovingRoute: RouteSnapshot): Collection<RouteSegmentSnapshot>?
```

`null`表示未接管，空集合表示接管但保持当前物理末端，非空集合表示接管且给出后缀，默认是`null`。两个输入都是同一原始选择及其宿主余量候选，每个source仅调用一次；宿主余量可因尽头/分支暂不完整，只有所有source都未接管时才使用该候选，并验证是否足够。不同source的接管结果必须兼容，不按调用顺序选择一个结果。

移动余量不能自动越过固定闭塞出口、闯入新闭塞/保护区域或替调用方取得下一进路。SimpleSignals在终点未被接管时也检查候选新增范围，跨入新的固定闭塞即拒绝；已有保护路径仍受replacement/方向等规则约束。宿主先保护原路径和余量候选，再调用插件；选择完成后保护最终路径，之后才建立binding/申请资源。只允许纯不可变几何输入，不新增registry或逐tick插件扫描。

最终路径必须覆盖本次终点策略所需的真实余量（普通非车站为配置的全局距离，已解析车站终点为0.3米），不能因插件接管就跳过空间校验。例如目标138米、闭塞出口140米、安全距离5米时，不能擅自申请下一区间，也不能假装停车任务可到达；应明确拒绝这份候选/应用。

### 车站终点的固定余量

本节车站终点包括目的地角色为`DestinationRole.StationBerthRole`的停车，以及已解析且与本次任务终点对应的terminal StationCall。仅途经站台不构成停车。两条既有路径共用`AtoMissionPoint.Stop.stationStop`明确语义，不能只处理`run.stationCall`而遗漏`DispatcherDestinationStop`。

2026-10-31用户确认：terminal StationCall的名义停点100米时，合法行驶前缀与实际资源授权须覆盖100.3米；不再要求105米。名义停点包含既有站台/调用停车偏移；controller直接瞄准此点，不添加额外控制提前量。停车容差只用于接受到达。普通非车站Stop保留原有语义。

`AtoSafetyProfile.STATION_AUTHORITY_MARGIN_METERS=0.3`是固定授权余量，不读取新配置或新增存档字段；停车目标本身不因此提前。静态`ValidatedTimetableRoute.terminalStationStop`来自真实站台角色或解析后的终点StationCall；动态冻结selection携带`StationTerminalAuthority(requiredEndRouteDistanceMeters)`，不得按终点数值接近猜测。

准入仍保留Basic真实`committedAuthority`。只有该grant和合法方向前缀均已覆盖station requiredEnd时，宿主资源授权restriction才携带0.3米`stoppingMarginMeters`；部分授权、pending不足以及独立信号限制仍采用原默认全局距离。完整保护后缀可超过合法行驶范围；若方向边界在已证明station requiredEnd之后，相应方向constraint沿用同一车站0.3米余量，不能再次扣5米阻止合法站停。方向边界在requiredEnd之前则拒绝，不删除方向constraint或扩张许可。

同绑定后续任务、换向rebase、重规划及编组交接必须重新携带/清除station证明；pending、unsafe、旧grant不能借新证明提前放宽。外部红灯restriction保持独立，最严格限制继续胜出。连挂接触的独立零间隙授权不采用车站规则。

车站requiredEnd与几何路线总长、grant和方向前缀的比较沿用1e-6米数值epsilon，处理例如
`610.3 - 590 < 20.3`的浮点减法误差；不是0.3米运营停车容差。发布的授权始终是原Basic边界，不加epsilon。
`RouteAuthorityAdmissionClientTest`同时验证浮点末端可使用车站余量、真正短0.0001米仍使用全局余量，以及越出几何的声明拒绝。

Good：站台停点后有0.5米合法轨道、之后方向禁行，0.3米补全可运行、停稳并折返。Bad：只剩0.2米合法空间时仍拒绝。Base：红灯或资源未释放令授权不足，仍按真实边界及默认5米防护停车。不得用把Basic边界加大、缩小默认全局距离或平移站台定义来替代本规则。

相关controller/准入/静态与动态测试及真实隔离执行证据见当前任务`research/station-terminal-clearance-2026-10-31.md`；离线完成不等于游戏验收。

### 原始授权始终保留

即使grant覆盖完整物理路线，admission client也必须继续发布真实Basic边界，不能仅依赖运营控制边界。目标100、真实授权105且安全距离5时可到100；真实授权不足时仍由实际边界约束停车，不以补偿标量绕过。

### 任务推进之前验证实际车头

Dispatcher在完成停车及执行后续副作用前，使用本次已发布实际车头沿冻结物理路径投影，与同一grant的`committedAuthority`比较。不得使用带±0.5米clamp的mission进度、已截到运营终点的授权投影或停车点邻近兜底；几何数值误差至多沿用1e-6米，不增加运营容差。

覆盖普通/手动完成、站台通知和开门、目的地停站、终到、换向、扩展动作、归库台账，以及直接进入`advanceService`的启动/replan入口。物理阶段只排队站台通知，实际开门仍由Dispatcher负责，不在physics另造一套门操作检查。越过真实授权、离开冻结路径或超过物理末端时走既有stall/失败路径，保留claim且不再执行后续动作。换向前检查旧方向，实际反向发布至同grant rebase完成的短阶段沿原rebase车体覆盖验证，不能拿新朝向匹配旧路线误报。

正常行驶tick不增加第二次全路线投影；实际推进动作时的原始位置验证为O(R)，R为冻结路径片段数。同一未变的静止车头、拓扑根、路径、grant身份/边界与路线出现位置提示可以复用一次成功证明，但任何相关身份/边界/位置变化必须失效；不创建跨世界缓存或新调度器。余量补全仅在静态应用/新冻结候选低频执行，随原依赖和source生命周期失效。

### 本节验收

- 默认5米、显式其他值及0；非有限、负数、错误配置类型拒绝；生产/隔离运行使用同值。
- 无信号插件的真实50ms停车取得停车点之后的物理轨道资源；BA、多轨道和已有后缀接续一致；新增轨道编辑保护与失败回滚。
- 固定闭塞准确出口不被移动余量扩到下一区间；余量不能穿过其他信号边界、非法方向、分支或物理尽头。
- 完整grant仍有真实边界；pending/denied不扩大边界；真正越界时不发门操作、不推进任务/换向/扩展动作、不提交归库台账。
- 合法的运营点后误差仍在实际授权内时不误报；原三个完整固定闭塞真实回归保持通过。停车精度由独立控制器批次验证，不以更改授权停车目标让精度测试通过。

原实现只把车身与剩余mission拼成冻结路线，导致运营停车点在闭塞内部时，真实授权范围也被截在停车点。整个block token并不等于该token以外已经取得了剩余轨道的物理授权。用户已确认固定闭塞应取得信号允许通过的完整区间；不能把这种正式授权纠正误称为掩盖停车误差。

## 一次正常操作

闭塞40～140米，停车目标100米：SimpleATO先生成车身到100米的任务路径；SimpleSignals从已有闭塞有向路径提供100～140米的后续路径；SimpleATO在第一次准入前冻结扩长后的真实路径，并保护其编辑依赖；原inspection请求下界提高到区间出口，Basic按同一事务取得到140米的轨道资源与block token。ATO继续以100米为运营停车目标，不把140米变成必须驶到的位置。

停车目标未被信号插件接管时，使用前述全局距离准备真实移动余量；控制层的终点补偿不自动增加Basic授权，也不能代替完整物理路径。

## 中立接缝与调用时机

复用`InspectionSource`及其world/owner生命周期，增加可选、默认返回null的终端物理路径补全provider。不新增service或registry。补全只依赖选定的不可变`RouteSnapshot`（含拓扑版本），不要求live train、速度或人为伪造的静态列车/观测；因此同一几何操作也可用于应用时刻表前的依赖收集。输出仅包含有序、不可变的物理轨道片段，不包含claim、资源清单、控制权限或live对象。实际列车准入仍使用同一权威observation stamp。

本批接缝：

```kotlin
fun interface InspectionRouteContinuationProvider {
    fun continuation(route: RouteSnapshot, proposedMovingRoute: RouteSnapshot): Collection<RouteSegmentSnapshot>?
}
// InspectionSource.terminalRouteContinuation 默认返回null，表示未接管。
fun InspectionRuntime.completeTerminalRoute(
    route: RouteSnapshot,
    proposedMovingRoute: RouteSnapshot = route,
    movingContinuationFailure: String? = null
): RouteSnapshot
```

宿主立即复制返回集合；输入前缀、route ID/revision及拓扑来源不变，只追加兼容的物理后缀。source返回的视角与现有`RouteSegmentSnapshot`一致，弧长为该AB/BA view下递增的米数，不是无方向的canonical范围。

provider在新的任务准入候选冻结时调用：先保护原选定路径，再保护宿主准备的移动余量候选，调用补全后保护最终路径，最后建立inspection binding和Basic申请。静态时刻表应用同样逐步升级依赖，资源回调在升级之后执行；失败沿现有ledger回滚。不能在每个物理tick、Dijkstra展开或每次pending轮询重新执行。各source对同一原始候选和宿主候选求值一次，不因前一source扩长后又递归扫描后继区间。兼容非空后缀取足以满足所有来源的长度；空集合的明确终点与非空后缀冲突时拒绝，不按source顺序随意选路。

既有候选跨observation刷新只换stamp，不重写已冻结几何。provider必须纯计算、非阻塞、不调用Bukkit或源服务。来源异常、失效定义、错误方向、过期拓扑或无法解释的后续路径禁止发布可用授权；实际拓扑连续性仍由Basic原有准入校验独立证明。

## 坐标映射与下一任务

冻结路线以`FrozenMissionRoute.operationalEndRouteDistanceMeters`记录运营任务终点所在的位置，不能再用整个物理路线总长代表任务终点。车头、车尾和mission进度到授权路线的映射保持一致；任务停车、时刻表时间和站台逻辑仍以原运营目标计算。

控制器与调度器首先沿既有mission layout投影车头；若停车点之外的位置无法在该layout中表示，才沿当前任务已绑定的冻结物理路线投影，用原head/mission偏移还原真实任务进度。不能把合法的物理后缀当作离轨；新增fallback不把进度截在停车点来抹去停车误差，既有layout在±0.5米容差内的clamp行为不在本批更改。离开冻结路径或超过真实物理末端仍拒绝。复用已有不可变路线，不在正常tick新增第二次投影扫描。

下一同向任务从100米开始时，必须精确复用100～140米的既有有向后缀，只追加140米之后真正新增的路径。下一目标仍在既有后缀内时，不重复申请几何、不把同一段轨道拼两遍；任务revision与坐标映射可以更新，但不能凭空改变旧grant。存在重复rail时使用已知运营终点的route occurrence，不取几何位置的第一个匹配。下一任务在已冻结后缀内改向/分岔时明确要求replan，不能重写已提交前缀。

移动余量接续也遵守物理revision：100米停车已冻结到105米，下一目标101米虽在原路径内，但新余量需到106米，最终物理路径实际增长就必须递增一次revision；不能沿用Assembler因“任务路径容纳于原后缀”而保留的旧revision。

下一任务目标超过旧后缀时，不能因为新路线binding变长，就使旧授权失配、列车停在100米等待一个永远到不了的滚动续申请触发点。Dispatcher在保护新候选后，可以显式准备同前缀continuation：核对原物理前缀完全不变，将**原committed boundary**重新对应到候选binding，保持旧grant、claim与已提交selection不变；后续仍按原滚动阈值向Basic申请真正新增的范围。不能把这次坐标绑定当作新授权，也不能为了切换binding直接申请整条新mission或虚加epsilon资源。

这个显式准备只处理“已获授权的前缀继续使用”，不改变普通pending continuation保持旧状态的默认合同。真正的更远boundary、committed selection和grant快照仍只能在Basic Granted后更新；候选改变旧前缀或准备失败时不得发布可用权限。前缀核对在任务切换低频执行，不新增每tick路线扫描。

静止换向仍先暂停车尾释放，以实际反向车身rebase原claim；不得在rebase中补领资源。成功后根据反向任务完成其新授权路线，保留同一block token/grant身份，直到真实反向车尾越过原入口才释放。

## 区间下界、方向与安全

SimpleSignals只裁剪已编译的完整闭塞有向路径，不另行寻路或按当前道岔猜分支；两方向始终参与几何匹配，不再按闭塞单双向权限过滤。完整闭塞不跨道岔的校验保持不变。任务终点恰在出口不需要补全；恰在下一入口不自动取得无关后继区间。

完整物理后缀与实际方向许可分开：最终路径确定后，SimpleATO 验证运营／车身前缀和真实停点后的安全余量，不能因 source 接管且总长足够而吞掉非法方向。已有后缀复用也要重验。允许纯保护后缀越过本次方向边界，原 Basic grant 如实保留；准入客户端通过同一快照中的独立方向 restriction 收紧控制。具体算法、失效和测试归[实际运行方向限制](operating-direction-enforcement.md)。

编组输出的初始交接只绑定真实车身和 body-only grant。带后续移动任务的 bootstrap 必须在普通候选流程完成停车余量和方向验证后再取得更远授权，不能因为初始 admittedRoute 已有同一 missionRevision 就跳过终端准备。pending 复用已准备候选，不重复补全；准备失败保留车身授权和原资源。

已有真实入口声明在请求跨过入口时，将边界提高到完整出口。路线从闭塞内部开始，尤其停车换向后，也必须有当前区间完整出口的中立请求下界，但不能伪造车后入口信号或恢复已通过信号的限速声明。沿用现有inspection binding保存该请求要求，不另建逐tick区间扫描器。

本批使用`InspectionDeclarationBinding.authorityRequestFloors`，其中`InspectionAuthorityRequestFloor(triggerRouteDistanceMeters, minimumEndRouteDistanceMeters)`只表达路线绝对米数上的请求下界，不带信号ID、提前时间或显示状态。数值有限、非负，end不小于trigger且不超过完整冻结路线；集合随binding防御性复制，与既有声明floor合并进同一个编译索引，复用其二分/单调请求调整和过长申请回退，不新增调度session。

物理前缀不可改写，因此接续点可能把同一轨道分为相接的两段。inspection编译声明位置时，仅在其查询用的occurrence列表中归并相邻同rail、同方向且弧长相接的片段，避免接缝被误判成两次经过；不改原冻结片段和grant，不合并中间隔开或绕回的真实重复经过。

正常完整区间授权由SimpleATO与已安装的inspection/completion/contributor组合完成。底层几何matcher及直接的additive contributor仍可描述局部intent，后者本身不是独立的完整授权规划API，也不会偷偷扩大Basic请求；不能宣称任何绕过这套正式调度接线的其他调用方都会自动获得完整区间物理授权。

完整区间申请失败或pending时保持原授权，不提前发布140米可用权限。停车精度与是否越过真实授权是不同问题：100.322米在已取得140米授权时不构成授权越界，其是否满足停车精度另按运营合同判断。通用完成检查使用前述真实车头/授权规则，不能用放宽epsilon代替。

## 生命周期与依赖

补全路径必须受到原有动态冻结路线编辑保护。已应用时刻表的静态依赖也须覆盖正式策略新增的路径与注册资源，避免停车点之后的轨道在开车前被误编辑。source替换/移除、世界卸载和拓扑失效复用既有生命周期；失败不能部分发布新路线、清掉旧claim或扩大旧控制权限。没有source时使用宿主移动余量，不在插件失败时将其解释为没有source。

## 成本与失败边界

来源数K、输入片段数R、后续片段总数S：新候选低频调用成本为O(K)加provider本身的索引查询成本；复制/兼容性检查按R与S线性遍历，不新增世界级搜索或额外逐tick扫描。SimpleSignals按末端rail反向索引定位候选，只访问命中闭塞的已有有向路径。不得新增任意节点/分支/距离上限来截短正式区间。

宿主余量遍历新增片段，并逐段核对现有禁行span与replacement span；原路线片段数R、新增片段数S、限制span总数F时为O(R+S×(1+F))的低频检查，不宣称已建立O(1)限制查询索引。没有第二次Dijkstra或新增每tick世界遍历。

车头无法在运营layout表示时，有界物理投影按冻结片段线性查找，单次O(R)、额外空间O(1)；若仍处于该状态，后续周期也需作同样投影。本批没有新建位置索引或保证所有fallback均为O(1)，正常layout投影成功时不付这部分成本。

空后缀保留既有路径；不连续、冲突、非法方向或失效输入拒绝候选，保留旧运行状态/授权，不使用假控制边界代替失败的资源申请。运行时callback不得I/O。版本和epoch含义不变，无存档字段和schema升级。

## 必需回归

- 无来源使用宿主移动余量；明确空后缀保留信号策略指定的物理末端，但仍校验停车余量。有效后缀不改变停车目标；冲突source与失效输入不发布部分候选。
- 同向下一任务超出、结束于已有后缀内，精确复用路径与grant；重复rail occurrence、BA、多轨道及不一致后缀拒绝。
- 名义滚动请求落在闭塞内部时，真实轨道授权与token一起完整到出口；内部起步不伪造入口声明，却请求当前完整剩余区间。
- 真实50ms Dispatcher出库、区间内停车、换向和返库：停车点100不变，forward物理claim覆盖到140，同一token在停车/换向保留，车尾实际清除后释放，Depot台账完成。
- 额外轨道上的资源冲突必须原子拒绝，不能只拿token后开车；新增范围被编辑保护；source异常与卸载保持fail-closed。
- Java API consumer、受影响模块测试和实际JAR隔离门禁。替身准入测试与真实物理集成证据分开记录。

## 本批不做

不移动测试停车点、不放宽Basic车体覆盖容差、不盲加10米资源，不把完整区间申请变成提前取得下一咽喉进路；不顺带重写制动/jerk算法。后续精度缺陷须用真实轨迹单独证明，不能沿用“所有超调都是越权”的旧诊断。

本批没有世界存档迁移或schema变动。原固定闭塞返库用例在库前另建0～40米双向固定闭塞，库内停车点仍为20米；新增无信号策略用例则单独验证真实移动授权及越界动作阻断，不能用前者代替后者。用户先前的暂缓决定已被本页当前批次取代；停车精度由独立控制器批次处理，证据见任务当前批次。
