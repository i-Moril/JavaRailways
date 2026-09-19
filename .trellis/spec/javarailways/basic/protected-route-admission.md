# Basic 保护区域与进路准入可执行契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`TRANSITIONAL`；region catalog、graph cut和单window validation已存在，typed intent、physical plan compiler、atomic turnout admission与release/recovery尚未实现。
- **Owner**：Basic拥有验证与执行机制；SimpleATO拥有保护区域authoring、运营路径与resource intent；Core拥有结构拓扑和物理观察。
- **Non-goals**：本页不定义信号aspect、JS策略、玩家编辑UI或跨多个咽喉的运营规划。

触发：实现或修改保护区域定义、自动/`MANUAL_ROUTED`进路准入、道岔/菱形交叉资源解析、出口净空、进路回滚、尾部释放或`TimetableDispatcher`迁移。

本页定义Stage 1 host contract。保护区域的玩家编辑、持久化、信号显示和闭塞区间策略属于SimpleATO/SimpleATOBukkit；Basic只消费由已注册provider提供的authoritative runtime definition，并以Core拓扑和同一铁路观察验证它。

设计目标：一个玩家定义的保护区域覆盖一个完整咽喉；一次请求只穿越一个区域。SimpleATO提交其已选路径的完整typed资源意图，Basic证明意图与同一版本拓扑完全一致、推导物理锁闭资源后原子获取；Basic不在保护区域内另行寻路。两个区域之间必须存在可容纳适用列车的普通等待区段，否则SimpleATO在应用运营计划时拒绝配置并要求合并区域，运行时不通过多区域组合申请补救错误划分。活跃grant不被抢占；公平性由确定性排序与aging处理。

## 2. 模块工作方式

```text
provider publishes boundary definition for topology revision T
  -> catalog resolves and caches one interior component for (T, regionRevision)
  -> SimpleATO submits frozen route + typed intent + one entry/exit window
  -> Basic validates observation, route, intent, region membership and clearance
  -> Basic compiles immutable physical plan
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

### 3.2 intent验证与physical plan编译

validator按route顺序同时推进edge traversal与intent，在每个结构节点比较期望transition；用stable resource identity集合拒绝重复，用遍历结束检查拒绝遗漏或额外项。通过后在同一次线性pass中把edge slices投影成canonical rail intervals，把turnout/diamond展开成共享冲突资源和所有physical locking legs，并按route progress生成release slices。Basic只能编译调用方已选路径，不能进行候选路径搜索。

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

data class ProtectedRouteRequest(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: RouteSnapshot,
    val resourceIntent: RouteResourceIntent,
    // null仅用于authoritative validator确认route没有controlled resource
    val window: ProtectedRouteWindow?
)

data class ResolvedProtectedRoutePlan(
    val request: ProtectedRouteRequest,
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
    fun admit(request: ProtectedRouteRequest): ProtectedRouteAdmissionResult
    fun cancel(
        grantId: ProtectedRouteGrantId,
        expectedRequester: InfrastructureOwner
    ): ProtectedRouteAdmissionResult
}

interface BukkitRouteResourceService {
    fun admit(
        requesterPlugin: Plugin,
        worldId: UUID,
        request: ProtectedRouteRequest
    ): ProtectedRouteAdmissionResult
}
```

Basic service是world-local，因此platform-neutral request不重复携带world ID；Bukkit facade显式接收world ID并路由到对应service。Basic内的catalog是world-local registration registry；Bukkit层用plugin owner注册provider并在disable时停止新解析。已准入live claim由host lifecycle owner持有，不能因为请求插件disable而直接释放。逻辑requester仍进入grant snapshot和诊断。

`request.owner`是调用方计划revision，不承担release过程中的registry CAS revision。每个成功grant获得独立稳定`grantId`和host-owned internal claim owner；同一区域内按车尾执行的prefix shrink只增加`claimRevision`。因此尾部逐段释放不会伪造新的调用方计划revision，cancel也不会因内部shrink而失去目标。

## 5. 不变量、生命周期、线程与版本

### 5.1 区域拓扑

- 一个boundary位于一根rail的canonical AB坐标；`ProtectionBoundaryDefinition.position.facing`表示从边界外侧进入内部的本地方向，不表示线路运营方向，也不再保存第二份方向字段。
- `ProtectionRegionDefinition`只表达provider/player authored source：region identity、目标topology revision与boundary集合。Basic由此推导的内部rail slices、连通分量与索引membership属于catalog内部缓存，不回填definition，也不另行暴露public resolved-region DTO；否则boundary与caller提交的subgraph会形成两份可能冲突的事实来源。SimpleATO需要的占用、reservation、限速和向前观察以后通过统一`AuthoritySection`行为接口提供，而不是读取Basic内部子图。
- 当前`ProtectionRegionTopologySnapshot`是从`RailView.nextRails`复制adjacency的过渡实现。迁移后它必须改为持有/查询指定revision的Core `TopologyState`，或成为按revision可丢弃的region membership缓存；不得继续充当第二张authoritative铁路图。
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
- 每个沿route遇到的turnout、diamond或其他controlled resource必须被提交的唯一window完整覆盖；道岔已经处于目标方向也不能豁免。没有controlled resource的route允许`window == null`并返回`NOT_REQUIRED`。
- region identity本身不是一把排他的全区锁。claim只包含实际路径rail intervals和共享冲突资源，使同一咽喉中不冲突的进路仍可并行；region仍可向SimpleATO暴露类似闭塞区间的occupancy/reservation观察。
- 普通闭塞区间与保护区域的空间不得重叠，这一authoring约束由SimpleATO在应用配置时验证；Basic不得通过同时创建两套重叠claim来补救错误配置。

### 5.3 出口净空与区域划分

- resolver从同一`RailwayStateStamp`中的authoritative train/consist状态计算最小尾部净空。caller给出的`clearUntil`可以更远，不能更近。
- exit到`clearUntil`之间必须是一条连续的冻结路径，并纳入occupancy检查与release slice。不能用“列车头已过exit”代替“列车尾已过exit”。
- 如果exit到下一保护区域之间不能容纳适用列车，两个区域的authoring/运营计划无效：SimpleATO必须在应用计划时拒绝并要求把连续冲突范围合并为一个region。Basic不接受多window请求，也不允许列车持有前一区域等待后一区域。
- 运营停车点终止当前进路；停车前不预先申请停车点另一侧的咽喉。一个region内部按车尾清除release slice后，host可用原子replace只缩小剩余footprint；不得在持有旧claim时增加先前未解析的资源。

### 5.4 intent验证与footprint推导

caller和add-on不得提交`InfrastructureFootprint`，但SimpleATO必须提交自己选择的有序edge traversal和完整typed controlled-transition intent。Basic沿frozen route做一次线性校验，证明edge连续且所有实际经过的turnout/diamond恰好声明一次；缺失、重复或额外资源均拒绝，Basic不得自行搜索替代路径。验证通过后，从缓存的region membership、同一revision的Core `TopologyState`与conflict envelope推导：

1. `claimFootprint`：路径及出口净空覆盖的每根rail各自的canonical AB `RailIntervalResource`，以及每个turnout、diamond和其他共享冲突节点的`StableInfrastructureResource`；
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
| route没有controlled resource且window为null | `NOT_REQUIRED` | 全不变；route可继续 |
| controlled resource没有保护窗口或窗口部分覆盖 | `PROTECTION_REQUIRED` | 全不变；即使道岔已对位 |
| intent缺失、重复、额外或与traversal不一致 | `INCOMPLETE_RESOURCE_INTENT` | 全不变；Basic不代为补齐 |
| observation physics epoch不是latest published | `STALE_OBSERVATION` | 全不变；重新观察后再解析 |
| topology revision不匹配或解析中变化 | `STALE_TOPOLOGY` | 全不变 |
| region不存在、revision不匹配或definition未针对当前topology验证 | `STALE_REGION` | 全不变；等region/topology事件，不逐tick重试 |
| entry/exit方向错误、路径离开内部后重入、同region重复进入 | `INVALID_ROUTE` | 全不变 |
| clearance过短、断开或到下一region之间没有完整列车等待空间 | `INSUFFICIENT_EXIT_CLEARANCE` | 全不变；运营计划不可应用，要求合并区域 |
| 任一physical locking interval被列车占用 | `PHYSICALLY_OCCUPIED` | 全不变；返回train IDs |
| footprint与live claim冲突 | `RESOURCE_CONFLICT` | 全不变；返回owner/revision/resource |
| claim成功但turnout apply失败且rollback成功 | `TURNOUT_COMMIT_FAILED` | selection恢复，claim释放，authority不扩大 |
| turnout rollback失败 | `WORLD_STALLED` | claim保留，authority fail closed，停止后续准入 |
| 相同request/revision重复提交 | `UNCHANGED` | 返回原grant，不重复切换 |

具体enum可以扩展当前`ProtectedRouteClaimStatus`或由更高层`ProtectedRouteAdmissionResult`包装，但上述failure identity不得压缩成日志字符串。

## 7. 性能、复杂度与退化行为

- region definition注册或revision变化时做一次graph cut，复杂度为捕获连通分量的`O(V + E)`；不得在每次列车tick或信号查询重跑。
- boundary在同rail内先排序；重复检测为排序后的相邻比较。region overlap通过rail ID索引后做slice exact comparison，不扫描无关rail。
- 一次route admission对冻结traversal做一次线性validation/plan compilation；occupancy通过rail-indexed intervals查询，禁止`allTrains × allRegions`。
- unchanged stale region/topology是配置事件驱动的拒绝，不逐tick重算；资源冲突才按明确调度策略重试。
- candidate/occupancy索引报告不完整、provider异常或revision变化时fail closed，不退化到live图扫描并继续授权。

## 8. Good / Base / Bad cases

- Good：玩家用一个region覆盖完整车站咽喉；两条路径没有共享rail/conflict node，因此不争用region ID并可并行grant。
- Good：复杂咽喉内两条路径没有共享rail/conflict node；虽然属于同一保护区域，两者不争用region ID，可以并行grant。
- Base：直线route没有controlled resource且不进入保护区域，返回`NOT_REQUIRED`，SimpleATO不存在时仍可手动驾驶。
- Bad：caller因为道岔已经对位而省略window；仍返回`PROTECTION_REQUIRED`，不得把当前selection当成授权。
- Bad：用最新topology搭配上一physics epoch的occupancy；返回`STALE_OBSERVATION`，不得自行拼接快照。
- Bad：插件disable回调直接释放一列尚在咽喉内列车的claim；必须转host recovery并保持限制。

## 9. 必需测试及断言点

- region graph tests：多入口咽喉得到唯一内部；inside seeds不连通、outside回连、内部重叠和歧义boundary被拒绝；合法相邻区域允许共享物理边界。
- intent validator tests：零/一/多turnout、diamond fixed connectivity、正反向stable identity、BA canonical转换、already-aligned turnout、缺失/重复/额外intent、route离开/重入和同region二次进入。
- clearance tests：精确最小列车长度、短1个容差单位、断开route，以及下一region前没有完整列车等待空间时计划拒绝；断言失败不产生claim。
- epoch tests：physics epoch、topology revision、region revision任一stale均fail closed；解析前后revision变化不穿透。
- transaction tests：resource conflict、physical occupancy、第二个turnout apply失败、rollback成功/失败；断言selection、claim和authority三者满足矩阵。
- release tests：未进入取消、头部过exit但尾部未过、tail clear、单region prefix shrink、train消失、provider disable、world reload recovery。
- dispatcher tests：automatic和`MANUAL_ROUTED`只能调用新service；不存在接受raw planned section的overload；`FULL_MANUAL`不申请新grant。
- performance tests：一次admission只遍历一次frozen route；unchanged `STALE_REGION`不逐tick重算；occupancy按rail索引查询，不做`allTrains × allRegions`。

## 10. 迁移、未决事项与Wrong/Correct

迁移按`TopologyState region cache -> typed intent validator -> immutable plan compiler -> atomic admission -> tail release/recovery -> caller migration`推进。前一层合同测试通过前不得开放后一层public service。

实现前仍需冻结`RouteResourceIntent`完整字段、edge/slice边界语义、minimum clearance输入、grant持久化/恢复边界及provider disable后的可观察状态。错误token名称可以在API提交时确定，但失败语义和无partial mutation已经冻结。

### Wrong

```kotlin
acquire(regionA)
waitFor(regionB) // 两区域间不能容纳整列车，持有A等待B
```

### Correct

```kotlin
val plan = resolver.resolveOneThroat(request, latestObservation)
val claim = registry.acquireClaim(plan.claimFootprint) // 一次原子获取完整集合
if (!claim.successful) return claim.toAdmissionResult()

val previous = snapshotSelections(plan.turnoutSelections)
return applyAllOrRollback(plan, previous, claim)
```

这里的原子性是对外行为合同：失败时不会发布grant或扩大authority。若补偿rollback本身失败，系统保留资源并Stall，而不是伪装成“什么都没发生”。
