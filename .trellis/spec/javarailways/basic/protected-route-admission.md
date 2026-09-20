# Basic 保护区域与进路准入可执行契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`TRANSITIONAL`；region catalog、graph cut和单window validation已存在，通用resource-contribution closure、frozen-route validator/resource projector、physical plan compiler、atomic turnout admission与release/recovery尚未实现。
- **Owner**：Basic拥有验证、资源投影与执行机制；SimpleATO拥有保护区域authoring与运营路径选择；Core拥有结构拓扑和物理观察。
- **Non-goals**：本页不定义信号aspect、JS策略、玩家编辑UI或替SimpleATO选择运营路径。

触发：实现或修改保护区域定义、自动/`MANUAL_ROUTED`进路准入、道岔/菱形交叉资源解析、出口净空、进路回滚、尾部释放或`TimetableDispatcher`迁移。

本页定义Stage 1 host contract。保护区域的玩家编辑、持久化、信号显示和闭塞区间策略属于SimpleATO/SimpleATOBukkit；Basic只消费由已注册provider提供的authoritative runtime definition，并以Core拓扑和同一铁路观察验证它。

设计目标：一个玩家定义的保护区域通常覆盖一个完整咽喉。SimpleATO提交已选且绑定topology revision的route plan、列车身份、当前进度和期望authority boundary；Basic从同一观察解析列车实际占用包络，证明冻结路径与同一版本拓扑一致，从路径派生资源，并计算完整resource dependency closure。保护区域只贡献本区域的路径资源、“尾部必须越过出口”的释放条件和出口后的最小净空前沿；它不查看下一个区域，SimpleATO也不按资源具体类型写特判。若完成当前净空前沿前又遇到固定闭塞、移动闭塞或另一个保护区域，通用编译器按各自粒度满足同一前沿，最终footprint一次原子获取。活跃grant不被抢占；公平性由确定性排序与aging处理。

## 2. 模块工作方式

```text
provider publishes boundary definition for topology revision T
  -> catalog resolves and caches one interior component for (T, regionRevision)
  -> SimpleATO submits one topology-bound frozen route + desired authority boundary
  -> Basic resolves the authoritative train envelope from the same observation
  -> compiler scans the route and folds direct resources plus contributor obligations
  -> closure ends at a valid authority endpoint satisfying every obligation
  -> Basic compiles one immutable physical plan
  -> one transaction checks occupancy, claims all resources, applies all selections
  -> admitted grant expands authority
  -> occupancy publication drives ordered tail release until grant completes
```

任何验证失败都发生在selection/claim之前。apply阶段失败时执行补偿rollback；rollback也失败则保留claim并Stall world。provider消失只停止新准入，不能释放可能已有列车进入的host-owned grant。

## 3. 内部机制与关键算法

### 3.1 boundary graph cut

当前实现先从所有boundary引用的rail出发，以BFS捕获其完整连接分量，并在捕获前后各核对一次topology revision。resolver把每根rail按排序后的boundary AB位置切成`SegmentKey(railId,index)`；boundary的`insideFacing`选择切点一侧作为inside seed，另一侧作为outside segment。

从第一个inside seed做BFS：只有segment触及rail A/B端点时才沿该端连接到邻rail，内部cut不能被跨越。所有boundary的inside segment必须属于同一visited set，所有outside segment必须不属于该set；否则分别是`DISCONNECTED_INTERIOR`或`OUTSIDE_RECONNECTS`。visited segments再还原成canonical AB slices并缓存。区域重叠只比较同rail slices的exact overlap，容差内接触不算内部重叠。

迁移到`TopologyState`后，segment表示改为edge slices，但cut、inside/outside seed、唯一连通分量和outside不可回连的算法语义保持不变；缓存key至少包含topology与region revision。

### 3.2 route验证、资源投影与resource dependency closure

validator按route顺序推进单一edge traversal，在每个结构节点验证入/出port形成合法continuation，并由此派生turnout identity与目标selection；rail/edge interval反向索引发现绑定在路径上的diamond等非结构资源。Basic只能编译调用方已选路径，不能进行候选路径搜索，也不接收caller枚举的第二份资源清单。

编译器从当前进度向`desiredAuthorityBoundary`扫描，并维护单调不减的`minimumClaimUntil`。普通rail interval与派生的受控资源直接加入footprint；遇到`RouteResourceContributor`时，将其选定traversal的资源、selection、physical locking envelope和clearance obligation合入计划。保护区域入口贡献至少到`regionExitDistance + occupiedLengthMeters + clearanceMargin`的义务。若扫描尚未达到该义务就遇到另一个贡献者，继续合入并取更远的minimum；只有到达不早于所有义务、且当前territory capability认可的authority endpoint才允许结束。固定闭塞的合法endpoint是已获取整段的出口；移动闭塞可以认可route上的精确距离。该过程是沿有限冻结route的单调fixed point，不允许贡献者反向缩短范围，也不允许保护区域自己查询“下一区域”。

通过后，同一次route pass把edge slices投影成canonical rail intervals，把turnout与绑定的diamond资源展开成共享冲突资源和physical locking legs，并按route progress生成release slices。编译器必须记录已处理的`(resourceId, traversal occurrence)`；route循环、重复贡献或无法到达满足义务的合法authority endpoint时typed拒绝，不能无限迭代。

```text
minimum = max(currentProgress, desiredAuthorityBoundary.distance)
cursor = currentProgress
while cursor < minimum or not canTerminateAuthorityAt(cursor):
    occurrence = route.next(cursor) ?: deny(INSUFFICIENT_EXIT_CLEARANCE)
    if occurrence was already processed: deny(CYCLIC_RESOURCE_DEPENDENCY)
    add direct rail and route-bound resources for occurrence
    for contribution in contributorsAt(occurrence):
        validate contribution against topology/region/observation revisions
        merge its footprint, physical locks and selections
        minimum = max(minimum, contribution.minimumClaimUntil.distance)
    cursor = occurrence.end
publish immutable plan only after the full loop succeeds
```

`AuthoritySection`与`RouteResourceContributor`是两种独立capability：前者提供信号/ATO使用的区间观察和固定区间边界，后者参与claim plan编译。保护区域通常同时实现两者；普通固定闭塞至少实现前者，只有确实需要预先锁定资源时才实现后者。移动闭塞territory则按route interval判断精确authority endpoint。调用方按capability消费，不能用`if (resource is ProtectionRegion)`分支复制规则。

出口后的前沿由通用compiler而非region解释。若前沿落入普通固定闭塞，必须获取该闭塞的整个不可分割区间；一段仍不足时继续获取后续整段，最终authority不能早于最后一个已获取区间的出口。若前沿落入移动闭塞范围，则按冻结route获取精确到前沿的interval，并要求有效MA不早于该前沿。若途中进入另一保护区域，则合入其资源和新的净空义务。三种情况共享同一owner与一次原子closure，但保留各自的资源粒度和释放规则；这不是获取整条运营路线。

### 3.3 admission与release状态机

准入顺序固定为`validate revisions -> occupancy -> atomic claim -> snapshot selections -> apply selections -> publish grant`。尾部释放不是看到“当前无车”就释放：每个release slice必须先观察到目标列车占用，再观察到目标列车完全分离，经历`BEFORE_OCCUPANCY -> OCCUPIED -> RELEASED`。prefix shrink只能删除已清除资源，不能添加原计划外资源。

## 4. 目标签名与合同

以下是进入实现前仍需字段审查的目标shape，不是当前公开API。当前`ProtectedRouteRequest`只有`topologyRevision`；接入物理占用前必须升级为完整`RailwayStateStamp`，不能把不同epoch的occupancy与topology拼接。

```kotlin
data class ProtectionBoundaryDefinition(
    val boundaryId: String,
    // canonical AB metres；position.facing是从区域外跨入区域内的方向
    val position: RailPositionSnapshot
)

data class ProtectionRegionDefinition(
    val region: ProtectionRegionRef,
    val topologyRevision: Long,
    val boundaries: List<ProtectionBoundaryDefinition>
)

fun interface ProtectionRegionProvider {
    fun resolve(region: ProtectionRegionRef): ProtectionRegionDefinition?
}

data class RouteResourceRequest(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val routePlan: RoutePlanRef,
    val currentProgressMeters: Double,
    val desiredAuthorityBoundary: RouteBoundaryRef
)

data class TrainResourceEnvelope(
    val occupiedLengthMeters: Double,
    val direction: ViewDirection,
    val occupiedIntervals: List<RailIntervalResource>
)

interface RouteResourceContributor {
    val resourceId: StableInfrastructureResource
    fun contribute(
        traversal: RouteTraversal,
        train: TrainResourceEnvelope
    ): ResourceContribution
}

data class ResourceContribution(
    val minimumClaimUntil: RouteBoundaryRef,
    val footprint: InfrastructureFootprint,
    val physicalLockFootprint: InfrastructureFootprint,
    val turnoutSelections: List<ResolvedTurnoutSelection>
)

data class CompiledRouteClaimPlan(
    val request: RouteResourceRequest,
    val claimUntil: RouteBoundaryRef,
    val claimFootprint: InfrastructureFootprint,
    val physicalLockFootprint: InfrastructureFootprint,
    val turnoutSelections: List<ResolvedTurnoutSelection>,
    val releaseSlices: List<ResolvedReleaseSlice>
)

data class ProtectedRouteGrantId(val value: String)

data class ProtectedRouteGrantSnapshot(
    val grantId: ProtectedRouteGrantId,
    val requestId: String,
    val requester: InfrastructureOwner,
    val claimRevision: Long,
    val state: ProtectedRouteGrantState
)

interface RouteResourceService {
    fun admit(request: RouteResourceRequest): ProtectedRouteAdmissionResult
    fun cancel(
        grantId: ProtectedRouteGrantId,
        expectedRequester: InfrastructureOwner
    ): ProtectedRouteAdmissionResult
}

interface BukkitRouteResourceService {
    fun admit(
        requesterPlugin: Plugin,
        worldId: UUID,
        request: RouteResourceRequest
    ): ProtectedRouteAdmissionResult
}
```

Basic service是world-local，因此platform-neutral request不重复携带world ID；Bukkit facade显式接收world ID并路由到对应service。Basic内的catalog是world-local registration registry；Bukkit层用plugin owner注册provider并在disable时停止新解析。已准入live claim由host lifecycle owner持有，不能因为请求插件disable而直接释放。逻辑requester仍进入grant snapshot和诊断。

上述签名仍是概念shape：最终API必须让`RoutePlanRef`解析为绑定一个topology revision的冻结traversal；不得再增加调用方提交的controlled-resource intent。调用方提供`trainId`，Basic必须从`observationStamp`对应的authoritative observation解析长度、方向和occupied intervals；不得相信调用方自由填写的列车长度。

`request.owner`是调用方计划revision，不承担release过程中的registry CAS revision。每个成功grant获得独立稳定`grantId`和host-owned internal claim owner；同一plan内按车尾执行的prefix shrink只增加`claimRevision`。因此尾部逐段释放不会伪造新的调用方计划revision，cancel也不会因内部shrink而失去目标。

## 5. 不变量、生命周期、线程与版本

### 5.1 区域拓扑

- 一个boundary位于一根rail的canonical AB坐标；`ProtectionBoundaryDefinition.position.facing`表示从边界外侧进入内部的本地方向，不表示线路运营方向，也不再保存第二份方向字段。
- `ProtectionRegionDefinition`只表达provider/player authored source：region identity、目标topology revision与boundary集合。Basic由此推导的内部rail slices、连通分量与索引membership属于catalog内部缓存，不回填definition，也不另行暴露public resolved-region DTO；否则boundary与caller提交的subgraph会形成两份可能冲突的事实来源。SimpleATO需要的占用、reservation、限速和向前观察以后通过统一`AuthoritySection`行为接口提供，而不是读取Basic内部子图。
- `ProtectionRegionCatalog`在一次rebuild开始时捕获一个public Core `TopologyState`根；内部`ProtectionRegionTopology`保留该根，并只从edge的有序rail slices及`Terminus`/`Turnout`/`LoopAnchor`transition派生endpoint adjacency和region membership索引。catalog publication与window resolver始终使用同一根，返回前以根身份和revision检查currentness；旧根可被既有读者安全保留，但不得与新根混合。该索引可按`(topology revision, region revision)`丢弃重建，不是第二张authoritative铁路图，也不再读取live `RailView.nextRails`。
- boundary必须离rail两端均大于`Curve.TOLERANCE`；端点可能连接多个分支，不能作为唯一有向half-edge，必须拒绝而不是猜测。
- 解析器在临时拓扑视图中于所有boundary处切断穿越，不修改Core live topology。每个boundary的inside half-edge必须进入同一个连通分量，outside half-edge不得仍能到达该分量；该唯一分量就是区域内部。
- 同一区域不得有重复或歧义boundary。不同区域内部子图必须不相交；相邻区域可以在同一物理位置使用相反inside half-edge，只要切分后的内部仍是不同分量。
- region revision在boundary集合、位置、inside方向或语义发生变化时递增。definition同时固定`topologyRevision`；拓扑变化后必须重新验证/发布definition，不能只沿用旧region revision。
- graph cut与内部membership在provider registration、region update或topology revision变化时计算并缓存；不得在每次列车tick或每次信号aspect查询时重新遍历完整world graph。
- 一条新自动进路必须从区域外进入并完整离开。同一路径在同一request内不得重复进入同一region；循环进路必须重新规划或拆成后续request。
- 一个region应覆盖一个完整咽喉。区域内部没有正常的独立授权停车点；故障/紧急停车保持尚未清除的claim。若一个几何咽喉确实存在能容纳整列车、且列车停下后不侵入两侧冲突资源的普通等待区段，玩家才可把两侧定义成两个region。

### 5.2 路径窗口与覆盖

- `route`是一次解析使用的冻结、有方向路径。`routeDistanceMeters`从路径起点递增；boundary的`position`仍使用单根rail canonical AB坐标，两者不得混用。
- entry必须以该boundary的`insideFacing`跨入内部；exit必须反向跨出另一个boundary。entry到exit之间的每个route segment都必须在该region内部。
- 每个沿route遇到的turnout、diamond或其他controlled resource必须能由`TopologyState`和host resource index完整派生并受有效保护覆盖；道岔已经处于目标方向也不能豁免。没有任何资源贡献且无需claim的route可返回`NOT_REQUIRED`。
- region identity本身不是一把排他的全区锁。claim只包含实际路径rail intervals和共享冲突资源，使同一咽喉中不冲突的进路仍可并行；region仍可向SimpleATO暴露类似闭塞区间的occupancy/reservation观察。
- 普通闭塞区间与保护区域的空间不得重叠，这一authoring约束由SimpleATO在应用配置时验证；Basic不得通过同时创建两套重叠claim来补救错误配置。

### 5.3 净空义务与区域划分

- resolver从同一`RailwayStateStamp`中的authoritative train/consist状态计算`TrainResourceEnvelope`；保护区域贡献的最小尾部净空不得短于出口距离加实际occupied length与明确margin。
- region exit到closure终点之间必须是同一冻结route上的连续路径，并纳入occupancy检查与release slice。不能用“列车头已过exit”代替“列车尾已过exit”。
- region自身只验证入口/出口方向、内部单次连续穿越、所选内部路径及资源、tail-clear释放条件与最小净空前沿；它不判断出口后采用固定闭塞还是移动闭塞，也不查询下一个section。该选择由通用compiler在继续扫描route时根据遇到的capability完成。
- `safeHoldingInside`不是definition字段。某个位置能否结束authority，是编译结果的性质：固定闭塞中它必须是完整区间边界，移动闭塞中可以是由该territory认可的精确route位置；两者都要求整列车占用落在已获取资源内，并且没有尚未满足的clearance obligation。
- 若前一区域的净空义务穿过后一区域入口，compiler必须在同一closure中纳入后一区域贡献的资源；前一区域不感知后一区域，caller也不提交手工composite window。若冻结route终止、循环或权限边界使closure无法完成，则整次请求失败且不产生claim。
- 运营停车点只有在满足上述可结束条件时才能成为本次authority boundary；停车前不跨越一个已经安全终止的运营停车点预申请另一侧独立进路。host可按车尾用原子replace只缩小已编译footprint；不得在grant发布后追加原计划外资源。
- 同一grant内各组件按自己的安全边界释放，但始终由同一个train-owned claim持有：保护区域资源按对应conflict/release envelope尾部清除，固定闭塞整段在车尾清除后释放，移动闭塞interval可以随车尾单调缩短。任何组件都不能因另一个组件已清除而提前释放自己的冲突资源。

### 5.4 route验证与footprint推导

caller和add-on只提交自己选择的topology-bound有序edge traversal，不得提交`InfrastructureFootprint`或另一份controlled-resource清单。Basic沿frozen route做一次线性校验，证明edge连续且每个turnout continuation合法，并用route-bound resource index派生diamond等非结构共享资源；任何无法解析的binding都fail closed，Basic不得自行搜索替代路径。验证通过后，从缓存的region membership、同一revision的Core `TopologyState`、resource index与conflict envelope推导：

1. `claimFootprint`：closure覆盖的每根rail各自的canonical AB `RailIntervalResource`，以及每个turnout、diamond和其他共享冲突资源的`StableInfrastructureResource`；
2. `physicalLockFootprint`：节点所有相关legs上用于占用校验的locking intervals，不由caller提供；
3. 需要应用的完整turnout selection集合；
4. 按尾部可安全释放顺序排列的release slices。

邻接rail interval不会隐式表达turnout/diamond冲突；不得用一个虚构全局里程interval跨越多根rail。

### 5.5 transaction与生命周期

准入只在world owner的post-publication阶段执行；事务期间不能推进physics或并发修改topology。对外可见的grant只能在claim与全部turnout selection成功后发布。

```text
resolve immutable plan
  -> recheck observation/topology/region revisions
  -> check physicalLockFootprint occupancy
  -> acquire complete claimFootprint
  -> snapshot all previous turnout selections
  -> apply all selections
  -> publish admitted grant / expand authority
```

apply失败时先恢复所有已修改selection。恢复成功后释放claim并返回`TURNOUT_COMMIT_FAILED`；恢复失败时保留claim、禁止扩大authority并Stall该world，不能释放资源后让未知道岔状态暴露给其他进路。

provider/plugin disable只停止新grant并使相关自动authority fail closed。已进入或可能进入的live claim转为host-owned recovery state，直到authoritative occupancy证明尾部清除；未进入的grant也只能由host在确认没有列车越过entry后取消。world unload期间没有运动，reload必须先恢复/重建claim与限制，再允许新departure或编辑。

## 6. 校验与错误矩阵

| 条件 | 结果 | selection/claim/authority |
| --- | --- | --- |
| route没有任何需要claim的resource/contribution | `NOT_REQUIRED` | 全不变；route可继续 |
| controlled resource没有有效保护区域 | `PROTECTION_REQUIRED` | 全不变；即使道岔已对位 |
| traversal断裂、方向错误或turnout continuation非法 | `INVALID_ROUTE` | 全不变；Basic不换路 |
| route-bound resource index缺失或coverage不一致 | topology/configuration fault，准入fail closed | 全不变；不从live对象猜测 |
| observation physics epoch不是latest published | `STALE_OBSERVATION` | 全不变；重新观察后再解析 |
| topology revision不匹配或解析中变化 | `STALE_TOPOLOGY` | 全不变 |
| region不存在、revision不匹配或definition未针对当前topology验证 | `STALE_REGION` | 全不变；等region/topology事件，不逐tick重试 |
| entry/exit方向错误、路径离开内部后重入、同一occurrence重复贡献 | `INVALID_ROUTE` | 全不变 |
| closure到route末端仍未满足净空、找不到合法authority endpoint或检测到循环 | `INSUFFICIENT_EXIT_CLEARANCE` / `CYCLIC_RESOURCE_DEPENDENCY` | 全不变 |
| 任一physical locking interval被列车占用 | `PHYSICALLY_OCCUPIED` | 全不变；返回train IDs |
| footprint与live claim冲突 | `RESOURCE_CONFLICT` | 全不变；返回owner/revision/resource |
| claim成功但turnout apply失败且rollback成功 | `TURNOUT_COMMIT_FAILED` | selection恢复，claim释放，authority不扩大 |
| turnout rollback失败 | `WORLD_STALLED` | claim保留，authority fail closed，停止后续准入 |
| 相同request/revision重复提交 | `UNCHANGED` | 返回原grant，不重复切换 |

具体enum可以扩展当前`ProtectedRouteClaimStatus`或由更高层`ProtectedRouteAdmissionResult`包装，但上述failure identity不得压缩成日志字符串。

## 7. 性能、复杂度与退化行为

- region definition注册或revision变化时做一次graph cut，复杂度为捕获连通分量的`O(V + E)`；不得在每次列车tick或信号查询重跑。
- boundary在同rail内先排序；重复检测为排序后的相邻比较。region overlap通过rail ID索引后做slice exact comparison，不扫描无关rail。
- 一次route admission对实际扫描到closure终点的冻结traversal做单调线性validation/plan compilation；每个route occurrence至多处理一次，因此复杂度为`O(scanned edges + bound resources)`。occupancy通过rail-indexed intervals查询，禁止`allTrains × allRegions`。
- unchanged stale region/topology是配置事件驱动的拒绝，不逐tick重算；资源冲突才按明确调度策略重试。
- candidate/occupancy索引报告不完整、provider异常或revision变化时fail closed，不退化到live图扫描并继续授权。

## 8. Good / Base / Bad cases

- Good：玩家用一个region覆盖完整车站咽喉；两条路径没有共享rail/conflict node，因此不争用region ID并可并行grant。
- Good：复杂咽喉内两条路径没有共享rail/conflict node；虽然属于同一保护区域，两者不争用region ID，可以并行grant。
- Good：区域A出口后的净空穿过区域B入口；通用compiler把B的贡献合入同一claim并延长minimum，A和B互不查询对方。
- Base：直线route没有controlled resource且不进入保护区域，返回`NOT_REQUIRED`，SimpleATO不存在时仍可手动驾驶。
- Bad：caller因为道岔已经对位而省略保护；仍返回`PROTECTION_REQUIRED`，不得把当前selection当成授权。
- Bad：把`safeHoldingInside=true`写进区域definition后无条件在该点截断authority；必须由当前route、列车包络与未完成义务共同推导。
- Bad：用最新topology搭配上一physics epoch的occupancy；返回`STALE_OBSERVATION`，不得自行拼接快照。
- Bad：插件disable回调直接释放一列尚在咽喉内列车的claim；必须转host recovery并保持限制。

## 9. 必需测试及断言点

- region graph tests：多入口咽喉得到唯一内部；inside seeds不连通、outside回连、内部重叠和歧义boundary被拒绝；合法相邻区域允许共享物理边界。
- route validator/projector tests：零/一/多turnout、route-bound diamond resource、正反向stable identity、BA canonical转换、already-aligned turnout、非法continuation、断裂route、resource index缺失、route离开/重入和重复occurrence。
- closure tests：精确最小列车长度、短1个容差单位、断开route、出口后固定闭塞整段提升、出口后移动闭塞精确interval/MA下限、连续两个保护区域、贡献链扩展、合法authority endpoint选择与循环检测；断言每个occurrence至多处理一次且失败不产生claim。
- epoch tests：physics epoch、topology revision、region revision任一stale均fail closed；解析前后revision变化不穿透。
- transaction tests：resource conflict、physical occupancy、第二个turnout apply失败、rollback成功/失败；断言selection、claim和authority三者满足矩阵。
- release tests：未进入取消、头部过exit但尾部未过、tail clear、单region prefix shrink、train消失、provider disable、world reload recovery。
- dispatcher tests：automatic和`MANUAL_ROUTED`只能调用新service；不存在接受raw planned section的overload；`FULL_MANUAL`不申请新grant。
- performance tests：一次admission只遍历一次frozen route；unchanged `STALE_REGION`不逐tick重算；occupancy按rail索引查询，不做`allTrains × allRegions`。

## 10. 迁移、未决事项与Wrong/Correct

迁移按`TopologyState region cache -> topology-bound frozen route -> route validator/resource projector -> immutable closure compiler -> atomic admission -> tail release/recovery -> caller migration`推进。前一层合同测试通过前不得开放后一层public service。

实现前仍需冻结`RoutePlanRef`完整字段、edge/slice边界语义、clearance margin来源、closure终点boundary引用、grant持久化/恢复边界及provider disable后的可观察状态。不会引入caller-supplied第二资源DTO。错误token名称可以在API提交时确定，但通用contributor、单调closure、authoritative train envelope和无partial mutation已经冻结。

### Wrong

```kotlin
val regionA = acquire(regionA)
waitFor(regionB) // 已进入后才发现还需要B，形成hold-and-wait
```

### Correct

```kotlin
val plan = compiler.compileResourceClosure(request, latestObservation)
val claim = registry.acquireClaim(plan.claimFootprint) // 一次原子获取完整集合
if (!claim.successful) return claim.toAdmissionResult()

val previous = snapshotSelections(plan.turnoutSelections)
return applyAllOrRollback(plan, previous, claim)
```

这里的原子性是对外行为合同：失败时不会发布grant或扩大authority。若补偿rollback本身失败，系统保留资源并Stall，而不是伪装成“什么都没发生”。
