# Basic 保护区域与进路准入可执行契约

## 1. 范围与触发条件

触发：实现或修改保护区域定义、自动/`MANUAL_ROUTED`进路准入、道岔/菱形交叉资源解析、出口净空、进路回滚、尾部释放或`TimetableDispatcher`迁移。

本页定义Stage 1 host contract。保护区域的玩家编辑、持久化、信号显示和闭塞区间策略属于SimpleATO/SimpleATOBukkit；Basic只消费由已注册provider提供的authoritative runtime definition，并以Core拓扑和同一铁路观察验证它。

设计目标：等待请求不持有任何资源；一次请求预先解析完整集合并原子获取，因此不存在“持有区域A等待区域B”的hold-and-wait死锁。活跃grant不被抢占；公平性由确定性排序与aging处理。

## 2. 目标签名

以下是进入实现前冻结的目标shape。当前`CompositeProtectedRouteRequest`只有`topologyRevision`；接入物理占用前必须升级为完整`RailwayStateStamp`，不能把不同epoch的occupancy与topology拼接。

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

data class CompositeProtectedRouteRequest(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: RouteSnapshot,
    val windows: List<ProtectedRouteWindow>
)

data class ResolvedProtectedRoutePlan(
    val request: CompositeProtectedRouteRequest,
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
    fun admit(request: CompositeProtectedRouteRequest): ProtectedRouteAdmissionResult
    fun cancel(
        grantId: ProtectedRouteGrantId,
        expectedRequester: InfrastructureOwner
    ): ProtectedRouteAdmissionResult
}

interface BukkitRouteResourceService {
    fun admit(
        requesterPlugin: Plugin,
        worldId: UUID,
        request: CompositeProtectedRouteRequest
    ): ProtectedRouteAdmissionResult
}
```

Basic service是world-local，因此platform-neutral request不重复携带world ID；Bukkit facade显式接收world ID并路由到对应service。Basic内的catalog是world-local registration registry；Bukkit层用plugin owner注册provider并在disable时停止新解析。已准入live claim由host lifecycle owner持有，不能因为请求插件disable而直接释放。逻辑requester仍进入grant snapshot和诊断。

`request.owner`是调用方计划revision，不承担release过程中的registry CAS revision。每个成功grant获得独立稳定`grantId`和host-owned internal claim owner；prefix shrink只增加`claimRevision`。因此尾部逐段释放不会伪造新的调用方计划revision，cancel也不会因内部shrink而失去目标。

## 3. 请求、区域和资源合同

### 3.1 区域拓扑

- 一个boundary位于一根rail的canonical AB坐标；`ProtectionBoundaryDefinition.position.facing`表示从边界外侧进入内部的本地方向，不表示线路运营方向，也不再保存第二份方向字段。
- boundary必须离rail两端均大于`Curve.TOLERANCE`；端点可能连接多个分支，不能作为唯一有向half-edge，必须拒绝而不是猜测。
- 解析器在临时拓扑视图中于所有boundary处切断穿越，不修改Core live topology。每个boundary的inside half-edge必须进入同一个连通分量，outside half-edge不得仍能到达该分量；该唯一分量就是区域内部。
- 同一区域不得有重复或歧义boundary。不同区域内部子图必须不相交；相邻区域可以在同一物理位置使用相反inside half-edge，只要切分后的内部仍是不同分量。
- region revision在boundary集合、位置、inside方向或语义发生变化时递增。definition同时固定`topologyRevision`；拓扑变化后必须重新验证/发布definition，不能只沿用旧region revision。
- graph cut与内部membership在provider registration、region update或topology revision变化时计算并缓存；不得在每次列车tick或每次信号aspect查询时重新遍历完整world graph。
- 一条新自动进路必须从区域外进入并完整离开。同一路径在同一request内不得重复进入同一region；循环进路必须重新规划或拆成后续request。

### 3.2 路径窗口与覆盖

- `route`是一次解析使用的冻结、有方向路径。`routeDistanceMeters`从路径起点递增；boundary的`position`仍使用单根rail canonical AB坐标，两者不得混用。
- entry必须以该boundary的`insideFacing`跨入内部；exit必须反向跨出另一个boundary。entry到exit之间的每个route segment都必须在该region内部。
- 每个沿route遇到的turnout、diamond或其他controlled resource必须被恰好一个提交window覆盖；道岔已经处于目标方向也不能豁免。没有controlled resource的route允许`NOT_REQUIRED`。
- region identity本身不是一把排他的全区锁。claim只包含实际路径rail intervals和共享冲突资源，使同一咽喉中不冲突的进路仍可并行；region仍可向SimpleATO暴露类似闭塞区间的occupancy/reservation观察。
- 普通闭塞区间与保护区域的空间不得重叠，这一authoring约束由SimpleATO在应用配置时验证；Basic不得通过同时创建两套重叠claim来补救错误配置。

### 3.3 出口净空与组合申请

- resolver从同一`RailwayStateStamp`中的authoritative train/consist状态计算最小尾部净空。caller给出的`clearUntil`可以更远，不能更近。
- exit到`clearUntil`之间必须是一条连续的冻结路径，并纳入occupancy检查与release slice。不能用“列车头已过exit”代替“列车尾已过exit”。
- 如果净空进入另一个保护区域，或到下一停止点之间不足以容纳完整列车，请求必须把相关windows放进同一个composite transaction。独立申请返回`INSUFFICIENT_EXIT_CLEARANCE`，不能先持有前一区域再等待后一区域。
- composite初次获取是全量原子的。列车尾依次清除release slice后，host可用原子replace只缩小剩余footprint；不得在持有旧claim时通过replace增加先前未解析的资源。

### 3.4 footprint推导

caller和add-on不得提交`InfrastructureFootprint`。host resolver沿frozen route做一次线性扫描，并从缓存的region membership、Core topology与conflict envelope推导：

1. `claimFootprint`：路径及出口净空覆盖的每根rail各自的canonical AB `RailIntervalResource`，以及每个turnout、diamond和其他共享冲突节点的`StableInfrastructureResource`；
2. `physicalLockFootprint`：节点所有相关legs上用于占用校验的locking intervals，不由caller提供；
3. 需要应用的完整turnout selection集合；
4. 按尾部可安全释放顺序排列的release slices。

邻接rail interval不会隐式表达turnout/diamond冲突；不得用一个虚构全局里程interval跨越多根rail。

### 3.5 transaction与生命周期

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

## 4. 校验与错误矩阵

| 条件 | 结果 | selection/claim/authority |
| --- | --- | --- |
| route没有controlled resource且windows为空 | `NOT_REQUIRED` | 全不变；route可继续 |
| controlled resource未覆盖、部分覆盖或重复覆盖 | `PROTECTION_REQUIRED` | 全不变；即使道岔已对位 |
| observation physics epoch不是latest published | `STALE_OBSERVATION` | 全不变；重新观察后再解析 |
| topology revision不匹配或解析中变化 | `STALE_TOPOLOGY` | 全不变 |
| region不存在、revision不匹配或definition未针对当前topology验证 | `STALE_REGION` | 全不变；等region/topology事件，不逐tick重试 |
| entry/exit方向错误、路径离开内部后重入、同region重复进入 | `INVALID_ROUTE` | 全不变 |
| clearance过短、断开或进入未包含的后续区域 | `INSUFFICIENT_EXIT_CLEARANCE` | 全不变 |
| 任一physical locking interval被列车占用 | `PHYSICALLY_OCCUPIED` | 全不变；返回train IDs |
| footprint与live claim冲突 | `RESOURCE_CONFLICT` | 全不变；返回owner/revision/resource |
| claim成功但turnout apply失败且rollback成功 | `TURNOUT_COMMIT_FAILED` | selection恢复，claim释放，authority不扩大 |
| turnout rollback失败 | `WORLD_STALLED` | claim保留，authority fail closed，停止后续准入 |
| 相同request/revision重复提交 | `UNCHANGED` | 返回原grant，不重复切换 |

具体enum可以扩展当前`ProtectedRouteClaimStatus`或由更高层`ProtectedRouteAdmissionResult`包装，但上述failure identity不得压缩成日志字符串。

## 5. Good / Base / Bad cases

- Good：两个相邻区域之间没有容纳整列车的空间，请求一次提交两个window；resolver合并turnout、diamond、rail与净空资源，一次成功或一次失败。
- Good：复杂咽喉内两条路径没有共享rail/conflict node；虽然属于同一保护区域，两者不争用region ID，可以并行grant。
- Base：直线route没有controlled resource且不进入保护区域，返回`NOT_REQUIRED`，SimpleATO不存在时仍可手动驾驶。
- Bad：caller因为道岔已经对位而省略window；仍返回`PROTECTION_REQUIRED`，不得把当前selection当成授权。
- Bad：用最新topology搭配上一physics epoch的occupancy；返回`STALE_OBSERVATION`，不得自行拼接快照。
- Bad：插件disable回调直接释放一列尚在咽喉内列车的claim；必须转host recovery并保持限制。

## 6. 必需测试及断言点

- region graph tests：多入口咽喉得到唯一内部；inside seeds不连通、outside回连、内部重叠和歧义boundary被拒绝；合法相邻区域允许共享物理边界。
- resolver tests：零/一/多turnout、diamond fixed connectivity、BA canonical转换、already-aligned turnout、重复覆盖、route离开/重入和同region二次进入。
- clearance tests：精确最小列车长度、短1个容差单位、净空进入相邻区域、组合申请与断开route；断言失败不产生claim。
- epoch tests：physics epoch、topology revision、region revision任一stale均fail closed；解析前后revision变化不穿透。
- transaction tests：resource conflict、physical occupancy、第二个turnout apply失败、rollback成功/失败；断言selection、claim和authority三者满足矩阵。
- release tests：未进入取消、头部过exit但尾部未过、tail clear、composite prefix shrink、train消失、provider disable、world reload recovery。
- dispatcher tests：automatic和`MANUAL_ROUTED`只能调用新service；不存在接受raw planned section的overload；`FULL_MANUAL`不申请新grant。
- performance tests：一次admission只遍历一次frozen route；unchanged `STALE_REGION`不逐tick重算；occupancy按rail索引查询，不做`allTrains × allRegions`。

## 7. Wrong vs Correct

### Wrong

```kotlin
for (window in request.windows) {
    acquire(resolve(window)) // 前一区域成功后等待后一区域，可能死锁
    applyTurnout(window)
}
```

### Correct

```kotlin
val plan = resolver.resolveComplete(request, latestObservation)
val claim = registry.acquireClaim(plan.claimFootprint) // 一次原子获取完整集合
if (!claim.successful) return claim.toAdmissionResult()

val previous = snapshotSelections(plan.turnoutSelections)
return applyAllOrRollback(plan, previous, claim)
```

这里的原子性是对外行为合同：失败时不会发布grant或扩大authority。若补偿rollback本身失败，系统保留资源并Stall，而不是伪装成“什么都没发生”。
