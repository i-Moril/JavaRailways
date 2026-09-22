# Basic 保护区域与进路准入可执行契约（迁移期实现记录）

> **Status: TRANSITIONAL / SUPERSEDED TARGET.** 本页记录当前已经实现的保护区域 catalog、window、编译与释放机制，便于安全迁移和回归测试；它不再定义长期模块边界。目标路线资源合同见[路线资源准入](./route-resource-admission.md)：Basic 只拥有通用资源安全内核和不可删除的物理资源底线，保护区域、固定闭塞、联锁策略及 `PROTECTION_REQUIRED` 迁入具体信号系统插件。后续代码不得仅依据本页继续扩大 Basic 的保护区域领域模型。

## 1. 状态、范围、触发条件与Owner

- **Status**：`TRANSITIONAL`；region catalog、graph cut、内部frozen-route validation、route-bound非结构资源索引、单window资源收集和atomic turnout admission已存在，通用contributor closure、grant publication与release/recovery尚未实现。
- **Current implementation owner**：Basic当前仍保存`ProtectionRegionDefinition`与catalog/window代码；这只描述迁移起点。目标owner是具体信号插件，Basic只保留路线验证、物理资源底线、唯一claim集合与执行机制；Core拥有结构拓扑和物理观察。
- **Non-goals**：本页不定义信号aspect、JS策略、玩家编辑UI或替SimpleATO选择运营路径。

触发：实现或修改保护区域定义、自动/`MANUAL_ROUTED`进路准入、道岔/菱形交叉资源解析、出口净空、进路回滚、尾部释放或`TimetableDispatcher`迁移。

本页以下内容是旧Stage 1合同和当前实现语义，不得作为新API目标。旧设计让Basic保存固定闭塞/保护区域current model并发布统一`AuthoritySection`；新设计会把这些定义、校验、持久化和观察面迁入具体信号插件。迁移期间仍需按本页测试现有行为，避免在移动代码时无意破坏已实现机制。

设计目标：一个玩家定义的保护区域通常覆盖一个完整咽喉。SimpleATO提交已选且绑定topology revision的route plan、列车身份和期望authority boundary；Basic从同一观察解析列车实际占用包络与当前进度，在一次验证中证明冻结路径与同一版本拓扑一致，并把沿途资源收集到唯一的`InfrastructureFootprint`。保护区域只增加本区域的路径资源、“尾部必须越过出口”的释放条件和出口后的最小净空前沿；它不查看下一个区域，SimpleATO也不按资源具体类型写特判。若完成当前净空前沿前又遇到固定闭塞、移动闭塞或另一个保护区域，同一次验证继续前进并把资源加入同一集合。活跃grant不被抢占；公平性由确定性排序与aging处理。

## 2. 模块工作方式

```text
Basic definition store publishes boundary definition for topology revision T
  -> catalog resolves and caches one interior component for (T, regionRevision)
  -> SimpleATO submits one topology-bound frozen route + desired authority boundary
  -> Basic resolves the authoritative train envelope from the same observation
  -> Basic captures one provider-catalog revision
  -> Basic validates the route once while collecting built-in and provider resources/obligations
  -> validation ends at a valid authority endpoint satisfying every obligation
  -> one transaction checks authoritative occupancy geometry
  -> that transaction claims the one resource set and applies all selections
  -> admitted grant expands authority
  -> occupancy publication drives ordered tail release while the live grant retains its route cursor
```

任何验证失败都发生在selection/claim之前。当前即时道岔写入在完整预解析后只是内存选择，不构造不存在的可恢复失败；若这一不变量被意外异常打破，则保留claim并Stall world。未来真实异步设备由自己的handler报告安全中止或不安全状态。编辑/观察adapter消失不删除Basic定义，也不能释放可能已有列车进入的host-owned grant。

## 3. 内部机制与关键算法

### 3.1 boundary graph cut

当前实现先从所有boundary引用的rail出发，以BFS捕获其完整连接分量，并在捕获前后各核对一次topology revision。resolver把每根rail按排序后的boundary AB位置切成`SegmentKey(railId,index)`；boundary的`insideFacing`选择切点一侧作为inside seed，另一侧作为outside segment。

从第一个inside seed做BFS：只有segment触及rail A/B端点时才沿该端连接到邻rail，内部cut不能被跨越。所有boundary的inside segment必须属于同一visited set，所有outside segment必须不属于该set；否则分别是`DISCONNECTED_INTERIOR`或`OUTSIDE_RECONNECTS`。visited segments再还原成canonical AB slices并缓存。区域重叠只比较同rail slices的exact overlap，容差内接触不算内部重叠。

迁移到`TopologyState`后，segment表示改为edge slices，但cut、inside/outside seed、唯一连通分量和outside不可回连的算法语义保持不变；缓存key至少包含topology与region revision。

### 3.2 route验证与唯一资源集合

当前内部`TopologyRouteProjector`已按route顺序推进单一edge traversal：它把每个facing-local rail segment通过`railProjection`换算为canonical AB及edge distance，验证同edge连续性或结构节点入/出port，并由合法continuation派生同一revision-local turnout node/from/to traversal及稳定turnout resource。turnout使用`typeId = "javarailways:turnout"`；resource ID把全部persistent `RailEndpointRef`先按rail UUID和endpoint排序，再把每个port编码为`<rail UUID>@<A|B>`并以`|`连接。该ID不包含遍历方向或topology revision；相同port集合在正反遍历及无结构变化的rebuild中相同，split、merge或reconnect改变port集合时产生新资源。重复port是无效拓扑输入并fail closed。`TopologyNodeId`只服务本次验证，不能进入claim、lease、持久化或跨revision cache；diamond使用独立binding，不套用turnout编码。

edge方向由segment facing与rail slice的AB朝向组合确定，不能用投影起终点的数值差推断，否则合法的零长度segment没有确定方向。不可变occurrence列表只是一次调用内的验证状态，不是第二份资源模型，也不读取live selection或搜索候选路径；`ProtectionRegionWindowResolver`复用它而不再通过region adjacency独立解释连接。同一pass再从route-bound index收集diamond等非结构资源。Basic只验证调用方已选路径；port canonicalization对每个遇到的turnout为`O(p log p)`，其中`p`为该节点端口数，不扫描world或未经过的节点。

当前transitional `ProtectedRouteRequestValidator`从catalog取得同一topology root上的临时route occurrences和window。窗口存在时，它把`entry..clearUntil`与occurrences相交，按facing换算为每根rail的canonical AB interval，再按rail UUID排序并合并重叠或容差内相邻区间；`entry..exit`内的turnout resource加入同一个`InfrastructureFootprint`。`clearUntil`以前但窗口外的turnout返回`PROTECTION_REQUIRED`，同一turnout重复出现返回`INVALID_REQUEST`。窗口为空时，route没有turnout才返回`NOT_REQUIRED`。资源收集为`O(n log n)`最坏复杂度，其中`n`是截取范围内的route occurrences；它不读取live rail或扫描world。

物理资源收集现已下沉到不依赖`ProtectionRegion`的内部`PhysicalRouteResourceCompiler`：输入是已投影冻结路线与显式路线范围，输出唯一footprint、道岔准备顺序、occupancy geometry、tail-release metadata和progress path。`ProtectedRouteRequestValidator`只保留上述窗口覆盖检查并作为过渡适配器调用该编译器；这不是新的公开API，也未完成通用route intent或caller迁移。

该validator目前证明rail intervals、turnout resources与route-bound resources，并把三者合入同一个`InfrastructureFootprint`。每个受保护的`ProjectedTurnoutTraversal`同时派生一个具体`InfrastructurePreparationRequirement`：`ResolvedTurnoutSelection(resource, fromPort, toPort, routeDistanceMeters)`；它保持route顺序和行驶方向，但不保留revision-local `TopologyNodeId`或mutable `RailView`。footprint与唯一有序`preparations`列表一起封装成不可变`CompiledRouteAdmission`，且每项requirement的稳定resource必须在该footprint内并至多出现一次。一个保护区域内连续经过多个道岔时，每个道岔贡献各自稳定resource和route-ordered preparation；不会压缩成单个“区域道岔”或取`single()`。该名称表示验证完成但尚未取得资源；只有atomic registry成功记录owner与footprint后才存在live claim。`ProtectedRouteClaimService`在resolution中保留整个admission，只把`admission.resources`作为唯一claim集合提交给atomic registry；claim成功后再消费同admission的preparation元数据。

同一次验证还把物理查询范围编译为一个normalized `OccupancyQueryGeometry`。它由`entry..clearUntil`的已选路线interval、每个相关turnout全部port的零长度端点interval，以及每个route-bound resource binding的全部anchors组成；同rail重叠或容差内相邻interval会合并。turnout在当前Core模型中是端点间的零长度selectable continuation，因此检查全部port既能捕获跨在未选支路端点上的列车，也不重新引入固定`lockingDistance`。diamond两臂不改变拓扑连接，必须从同一个retained catalog snapshot加入全部anchors，不能只检查route命中的一臂。该geometry只用于authoritative occupancy查询，不进入`InfrastructureFootprint`、claim identity或持久化。

当前claim seam已把该geometry接入同一epoch的authoritative occupancy query，并在registry mutation前排除请求列车自身、拒绝全部其他物理阻挡。它在atomic claim后启动完整有序的基础设施准备批次；start在无side effect前拒绝时撤销新claim，未来异步handler只有确认`ABORTED_SAFE`后才允许撤销新claim，状态不确定则保留资源并Stall。validator仍未用完整train envelope实现多contributor closure，也尚未实现grant publication和release/recovery。因此automatic/`MANUAL_ROUTED`调用者暂不得迁移到它并宣称完整准入。后续能力必须扩展同一次验证和同一个最终资源集合，不能增加caller footprint、第二份physical footprint，或在claim后重新投影route来恢复准备操作。

验证从当前进度向`desiredAuthorityBoundary`扫描，并维护单调不减的`minimumClaimUntil`。普通rail interval与受控资源直接加入唯一集合；遇到`RouteResourceContributor`时，把其选定traversal的资源加入该集合，并记录selection、occupancy检查数据和clearance obligation。保护区域入口至少把义务延长到`regionExitDistance + occupiedLengthMeters + clearanceMargin`。若尚未达到该义务就遇到另一个contributor，继续验证并取更远的minimum；只有到达不早于所有义务、且当前territory capability认可的authority endpoint才允许结束。固定闭塞的合法endpoint是已获取整段的出口；移动闭塞可以认可route上的精确距离。该过程是沿有限冻结route的单调fixed point，不允许contributor反向缩短范围，也不允许保护区域自己查询“下一区域”。

通过后，同一次route pass已经得到唯一`InfrastructureFootprint`，另附有序infrastructure preparations和tail-release entries。turnout/diamond的占用范围从authoritative binding临时查询，只用于准入检查，不成为可申请或持久化的第二资源集合。验证必须记录已处理的`(resourceId, traversal occurrence)`；route循环、重复贡献或无法到达满足义务的合法authority endpoint时typed拒绝，不能无限迭代。

```text
minimum = max(currentProgress, desiredAuthorityBoundary.distance)
cursor = currentProgress
while cursor < minimum or not canTerminateAuthorityAt(cursor):
    occurrence = route.next(cursor) ?: deny(INSUFFICIENT_EXIT_CLEARANCE)
    if occurrence was already processed: deny(CYCLIC_RESOURCE_DEPENDENCY)
    add direct rail and route-bound resources for occurrence
    for contribution in contributorsAt(occurrence):
        validate contribution against topology/region/observation revisions
        add its resources to the one claim set
        record its occupancy checks, selections and clearance obligation
        minimum = max(minimum, contribution.minimumClaimUntil.distance)
    cursor = occurrence.end
return one validated claim only after the full loop succeeds
```

`AuthoritySection`与`RouteResourceContributor`是两种独立capability：前者提供信号/ATO使用的区间观察和固定区间边界，后者在路线验证时向同一个claim集合增加资源和净空义务。保护区域通常同时实现两者；普通固定闭塞至少实现前者，只有确实需要预先锁定资源时才实现后者。移动闭塞territory则按route interval判断精确authority endpoint。调用方按capability消费，不能用`if (resource is ProtectionRegion)`分支复制规则。

`RouteResourceContributor`仍是Basic编译器内部能力；外部扩展通过受限的`RouteResourceProvider` SPI注册稳定provider identity、revision及其route-bound/section bindings。Basic只在单调扫描实际到达已注册binding时调用对应provider，不向所有provider广播每次申请。provider收到的是同一epoch的不可变列车准入视图，以及Basic已经裁剪到该provider所属section/binding的局部有向traversal；它不获得后续资源catalog、claim service、mutable train或world对象。保护区域的内置contributor同样只能根据本区域出口、局部选定路径和列车占用长度返回本区域资源与`minimumClaimUntil`，不能查找下一区域；这不把Basic拥有的保护区域定义转交给外部SPI。

provider返回标准化contribution：已注册binding的稳定资源identity、只能向前推进的最小claim前沿，以及需要由已注册handler执行的preparation intent。它不能调用registry、递归申请资源、改变冻结路线、缩短已有前沿或直接修改世界。Basic校验provider namespace与revision；资源与preparation必须引用捕获快照中该provider已注册的binding，且每项preparation必须引用本次唯一claim集合中的资源。Basic始终从binding snapshot取得并校验完整occupancy geometry；provider不能在请求时另交一份anchors，从而省略未选支路或共享冲突的另一侧。`minimumClaimUntil`则必须是当前冻结route上不早于局部traversal终点的前向boundary；它可以位于binding之后，否则无法表达“出口后再容纳完整列车”。通过后Basic才把结果适配为内部`RouteResourceContributor`输出。对外SPI不是第二份caller footprint，最终仍只有`CompiledRouteAdmission.resources`被registry消费。

出口后的前沿由同一次route validation而非region解释。若前沿落入普通固定闭塞，必须把该闭塞的整个不可分割区间加入资源集合；一段仍不足时继续加入后续整段，最终authority不能早于最后一个已获取区间的出口。若前沿落入移动闭塞范围，则按冻结route加入精确到前沿的interval，并要求有效MA不早于该前沿。若途中进入另一保护区域，则加入其资源和新的净空义务。三种情况共享同一owner与一次原子claim，但保留各自的资源粒度和释放规则；这不是获取整条运营路线。

### 3.3 route-bound resource catalog

`RouteBoundResourceCatalog`是Basic内部、world-local的完整索引，不是provider registry或第二张拓扑图。一个published snapshot保留它据以验证的`TopologyState`根、按stable resource identity唯一化的bindings，以及按rail UUID分组并按anchor起点排序的反向索引。空世界也必须发布针对当前root的complete empty snapshot；“尚未构建”和“确认没有binding”不能都用空map表示。

一个binding只包含最终会进入claim的`StableInfrastructureResource`和一组定位该资源的canonical rail anchors。anchor复用Core `RailSliceRef`值类型，但必须满足`startAbMeters < endAbMeters`，因此没有行驶方向语义；它只用于判断路线是否经过该共享资源，不会作为第二个claim资源自动提交。diamond binding必须恰有两个不重复的anchors；两侧命中都返回同一个`javarailways:diamond` identity。stable `resourceId`的生成/持久化策略属于后续placement/update批次，本批不猜测兼容格式。

重建时先捕获一个topology root，验证每个anchor位于该root中一段且仅一段rail projection内，再构造candidate maps；完成后再次确认root identity，只有全部成功才一次替换published snapshot。route validation捕获一个catalog snapshot并要求它与region/projector使用同一topology root；查询结束后再次确认两者仍current。任一检查失败都拒绝本次准入，不读取live rail或临时做几何检测。

当前runtime实现让`ProjectedTopologyRoute`保留本次验证使用的不可变`TopologyState`根引用；catalog查询要求route、catalog snapshot与current topology三者对象身份相同，不能只比较revision数值。查询时，每个`ProjectedRouteOccurrence`先换算为无方向canonical AB范围；同rail索引按anchor起点排序并保存prefix maximum end，通过二分跳过不可能重叠的前缀，再顺序读取到anchor起点越过occurrence终点。闭区间在`Curve.TOLERANCE`内接触按命中处理以fail safe。相同resource的route-distance命中先合并；离开anchor后再次命中同一resource视为重复受控资源并返回`INVALID_ROUTE`，不能静默去重一个循环进路。

### 3.4 admission与release状态机

当前Core在完整physics epoch后构建一次`RailwayOccupancySnapshot`，并把同一immutable bucket index
同时交给collision与`RailwayWorldFrameSnapshot`；Basic observation只保留该同epoch引用，不从train
spans重建第二份world index。validator已把已选路线interval、全部turnout endpoint点及route-bound
resource全部anchors合并为一个immutable query geometry；构建时按rail分组、排序和合并，最坏为
`O(i log i)`，其中`i`是本次编译产生的interval数。准入随后调用
`blockingTrainIds`做bucket-local candidate lookup与exact overlap；该安全查询无candidate cap，结果排除
明确指定的train、去重并按train ID稳定排序，每个合并interval只访问覆盖bucket与local candidates。
`ProtectedRouteClaimService`保留request stamp完全匹配的同一`RailwayObservation`，并在registry mutation前
直接查询`CompiledRouteAdmission`中的这一份geometry；它不能从claim footprint重建或再次扫描route。

准入顺序固定为`validate revisions -> occupancy -> atomic claim -> prepare infrastructure -> publish grant`。准入前的物理占用检查继续使用唯一的全局`OccupancyQueryGeometry`；正常尾部释放不再为每个资源复制geometry或维护占用detector。prefix shrink只能删除车尾已经越过完整清除边界的队列前缀，不能跳过队首或添加原计划外资源。

当前内部实现已在生成`CompiledRouteAdmission`的同一次validation pass中编译有序
`ResolvedTailReleaseEntry`。最终`InfrastructureFootprint`中的每个resource必须恰好对应一个entry；entry
只保存stable claimed resource与`tailClearRouteMeters`。该阈值表示车尾沿同一冻结路线至少到达哪个完整
清除坐标后，资源才有资格释放。rail interval取其在冻结路线上的最后claimed extent终点，turnout取最后
traversal位置，route-bound resource取最后conflict extent终点；同一rail/resource重复出现时取最大最终
路线距离，因此第一次经过不会提前释放。release metadata只是唯一footprint的生命周期索引，不是第二个
claim，也不保存逐资源occupancy geometry或运行时状态。

`tailClearRouteMeters`必须被视为已经完整编译的车尾清除边界。当前过渡compiler只使用最后claimed/
conflict route extent；明确的运营安全余量来源、单位与组合规则仍未冻结，不能把`Curve.TOLERANCE`暗中当作
安全余量。未来margin必须在创建entry前由明确owner合入该阈值，queue本身不得再次添加或猜测margin。

`CompiledTailReleaseQueue`是immutable纯helper。它只接收同一冻结路线上的车尾route progress，从队首开始
读取连续满足阈值的entry并返回immutable eligible prefix；遇到首个未清除entry立即停止，不扫描后续项，
也不自行pop。registry提供internal同步removal-only shrink：调用方必须提交eligible prefix中的准确resource
identities，registry在锁内验证exact claim/owner、pending状态和完整membership，并保持remaining footprint
原顺序；部分清除保持同一个logical owner revision，全部清除删除claim。该操作不重验topology/provider或
冲突，因为删除不会制造新冲突；重叠interval按身份独立删除。

`LiveClaimTailRelease`把两者串成一个internal同步生命周期owner。它持有创建claim时的平台owner identity、
准确logical owner、remaining immutable queue suffix和maximum accepted authoritative tail route progress。
有效progress先由pure queue得到连续prefix；非空prefix必须先得到registry预期的`REPLACED`或`RELEASED`，随后
才更新本地进度与suffix。非法或倒退progress、owner/claim/pending/closed等任何unexpected shrink结果都会
锁存fail-closed结果，queue与maximum progress保持原值，后续调用不会把异常当成routine retry。最后一个prefix
成功删除后进入completed，重复调用是无registry side effect的幂等no-op。它不负责从列车物理位置投影route
progress；该输入必须已由未来grant lifecycle针对同一冻结route证明authoritative，后者也负责把fail-closed
转换成Stall。若返回了与预期不同的successful mutation status，claim可能已变更；结果会用
`claimMayHaveChanged=true`明确标记这一诊断状态，但仍不推进本地queue。

同一次validation还把已验证`ProjectedTopologyRoute`精简成immutable
`CompiledRouteProgressPath`并挂到`CompiledRouteAdmission`。path按冻结路线顺序只保存每个occurrence的
rail、facing、canonical AB上下界、累计route起点和片段长度；它不是第二条可寻路路线、footprint或occupancy
geometry。每次初次准入提交的冻结路线必须从同epoch observation中的真实车尾开始；Basic在claim前
只验证第一个occurrence的route start与该物理车尾一致，并把`RouteProgressCursor`保守初始化为路线距离`0`。
调用方不提交初始route coordinate或occurrence index，因此重复经过同一rail不能把初始游标伪造到后一次经过。
route plan变化只能在安全运营边界结束旧进路后重新准入，不跨route version搬运调用方进度。把已验证cursor
保留到live grant并持续消费后续observation仍属于下一批生命周期接线。

cursor每次更新先按rail、facing、canonical AB边界与`Curve.TOLERANCE`匹配当前occurrence，并依据AB/BA方向
换算route distance；因此相邻或零长度片段的共享边界确定地保留在较早occurrence，不会提前推进。当前项不匹配
时才按路线顺序向后扫描，取第一个匹配occurrence并推进index；它从不向前跳过仍匹配的重复rail，也从不回扫。
匹配结果低于已接受route distance，或没有当前/后续匹配时，均保留原state并锁存fail closed；后续调用不再
扫描suffix，不得把任一失败解释为路线完成。
`ProtectedRouteClaimService.resolve`现在把该已验证cursor连同`CompiledRouteAdmission`交给后续transaction，
不再在claim前验证后丢弃。world-local service按logical `ownerId`持有internal live-grant record，其中保留精确
platform owner、logical owner、train identity与`ProtectedRouteTailRelease`控制器；terminal admission result只返回
claim结果，不把该生命周期交给caller。成功`ACQUIRED`在finalize后安装一个record；`UNCHANGED`保留现有项，
失败不安装新项。当前资源队列全部释放不等于live grant结束：record与冻结路线cursor继续存在。只有精确
platform/logical owner的显式release/end删除record；registry已有资源时必须返回`RELEASED`，资源已由车尾全部
释放时允许以controller的empty证明把`NOT_FOUND`解释为成功结束。错误platform/revision或active queue下的
`NOT_FOUND`都必须保留record并返回冲突/不一致结果。

受保护进路runtime不通过整份claim replace滚动资源窗口。当前service处理初次acquire、显式release、车尾
prefix release与同一冻结路线的内部suffix extension。SimpleATO改变route plan时只能在
站台等安全运营边界结束旧进路后重新准入。suffix extension只追加本次新增资源；配置lease replacement属于
独立底层能力，live claim不需要整份replacement入口。

main owner显式调用`consumeObservation`驱动当前控制器。service先对一次observation的train list建立唯一
`trainId` lookup，再按logical owner稳定顺序驱动所有live-grant项；controller接收stamp及已解析的nullable train，
但仍自行验证world/topology/epoch/train identity。它把物理tail经cursor投影后调用`LiveClaimTailRelease`；重复
epoch为幂等no-op，registry shrink成功后才提交本地cursor。`Completed`只表示当前已提交资源队列为空，不删除
live-grant record。之后每个新epoch仍验证、投影并提交同一个cursor，但不访问registry。错误stamp、train消失、
cursor或release失败均锁存并恰好一次触发Stall，service保留fail-closed项并立即停止当前publication的后续owner
dispatch。控制器不创建第二个claim或footprint；`LiveClaimTailRelease`只负责当前已提交资源队列。
当前仍没有自动接入Core/Bukkit publication、持久化/reload恢复、provider-disable接管或clearance-margin策略。

resource-acquisition/live-grant owner只有在delta registry reservation已经finalize且设备已经READY后，才能把对应
release entries安装到现有控制器。控制器先验证suffix的确定性队尾顺序，并拒绝任何clear boundary位于或早于
已接受tail progress的entry；失败不改变queue。active queue采用immutable merge，empty queue采用revival，二者
都保留同一个cursor、最大tail progress和live-grant identity。控制器不持有pending handle，也不调用registry
finalize/rollback。service持有唯一pending handle，并复用初次admission的preparation ticket状态机；release controller
仍只处理已提交队列。

### 3.5 同一冻结路线的rolling suffix extension

rolling extension只延长一个已经READY的live grant，不能创建第二个claim、第二个tail-release controller或新的
logical owner revision。SimpleATO决定何时需要更远authority以及期望的新边界；Basic只接受与现有grant**完全
相同**的frozen route identity、topology root/revision和列车身份，从既有已验证前沿继续编译suffix。route revision、
有序traversal或topology binding任一不同都不是extension，而是必须在安全运营边界结束旧进路后重新准入的replan。
新desired boundary必须严格超过该grant当前已提交的authority frontier；相等的no-op或后退都是typed invalid
request。调用方仍不提供footprint、preparation或释放队列。

当前内部实现保留初次验证得到的`ProjectedTopologyRoute`、committed frontier和曾提交过的controlled-resource
identity集合。extension先按value完全比较route，再直接复用该投影；region catalog只校验新window及其revision，
不会重新project或扫描旧prefix。compiler只查询`(frontier, desired clearUntil]`的rail interval、turnout和route-bound
binding，并复用初次编译的progress path。公开extension request/result名称与SimpleATO调用点仍留到API/Dispatcher批次。

Basic在旧authority不变的前提下完成以下单一事务：

```text
validate exact grant/route/topology/observation binding
  -> compile only (committed frontier, desired farther boundary] and contributor closure
  -> reject a seam that is discontinuous, moves backward or repeats a controlled resource
  -> derive delta resources, delta occupancy geometry, delta preparations and delta tail-clear entries
  -> reject authoritative physical blockers before registry mutation
  -> atomically reserve only delta resources under the existing grant
  -> prepare only delta requirements
  -> ready: commit the reserved delta, append its queue entries, publish the farther grant boundary
  -> safe failure: remove only the delta reservation; keep the current claim, queue and authority
  -> unsafe failure: retain the safest reservation, do not expand authority, Stall
```

suffix compiler必须带入最后一个已提交occurrence作为只读seam context，以证明连续性和处理跨边界的同一resource，
但不得从route起点重跑完整validation。一个正确的committed authority frontier必须是安全切点，因此suffix不得再次
包含prefix已经使用过的turnout、diamond或其他不可分割controlled-resource identity；遇到这种情况说明frontier或route
本身无效，而不是要求extension合并两次经过。rail interval按本次新增范围产生新identity；它可以与同一grant尚未释放的
旧interval几何相邻或重叠，registry仍按exact identity分别持有和释放，不能按overlap擅自合并。
物理占用检查只查询本次suffix新覆盖的路线和受控资源geometry，仍复用request observation中的Core occupancy snapshot；
不得因是extension而跳过列车包络、全部道岔port或route-bound resource anchors，也不得重查已提交prefix。

suffix entries必须天然位于当前已提交队尾之后；若当前队列已空，则以live-grant record仍在推进的cursor progress作为
保守下界。Basic在mutation前验证该关系。无需创建新controller或搬运cursor；现有cursor和已经释放的队列前缀保持
不变。pending delta及其准备状态由资源获取/live-grant owner持有，不暴露给`LiveClaimTailRelease`。只有registry
reservation成功finalize后，该owner才把已经验证的delta entries安装为同一live grant的新**已提交**释放队列。

extension与tail prefix release由同一个resource-acquisition/live-grant owner驱动，registry自身原子串行化单次
reserve、shrink和finalize操作；extension pending期间`LiveClaimTailRelease`仍只看到并处理旧的已提交队列，cursor
持续推进。suffix不得重复controlled resource，rail interval又按exact identity独立持有，因此释放旧prefix不会撤销
delta reservation。一个grant同一时刻只保留一个pending extension handle；第二个请求在前一个terminal前返回busy。

设备准备完成后只需确认该pending handle仍属于当前live grant；冻结路线不会在原地改变，受claim与edit guard覆盖的
资源也不能在准备期间被编辑。finalize只提交该handle的delta reservation，再由同一个live-grant owner安装预校验的
committed release entries并发布更远boundary；tail release本身不持有pending handle。安全失败只删除本次delta
reservation；此时旧claim可能已因正常车尾释放而缩短，这是正确状态，不能
尝试恢复已释放资源。无法证明设备已进入安全失败状态或delta reservation无法安全撤销时，保留现有最安全占用并Stall。

这是同进程、main-owner管理的操作。调用方持有并轮询当前pending handle，不需要request-history、terminal-result缓存或
模拟网络重试的幂等层；request ID若保留，只用于日志诊断。具体公开类型名和failure enum token留到API批次冻结，但至少
区分stale route/topology/observation、backward/no-op boundary、busy、resource conflict、physical occupancy、preparation
failure和world stalled。普通拒绝或安全失败都不扩大authority；pending reservation也不是可行驶授权。

suffix validation对本次新增扫描长度与新增binding数线性，registry conflict检查只处理`delta resource count`；已排序的
delta entries验证队首边界后直接追加，成本为`O(d)`，其中`d`是extension entries。不得为每次延长重算route prefix、
重新排序existing queue、复制controller、扫描全world资源或使用whole-claim replace。

编译时先按rail建立已排序claim interval索引，再单次遍历route occurrences，以二分跳过不可能重叠的
resource前缀并只处理实际overlap；复杂度为`O(r log r + n log r_rail + o)`（`r`为claim rail
resources，`n`为窗口内occurrences，`r_rail`为当前rail上的resource数，`o`为实际overlap数）。resource
bindings同样只做一次收集，最终entry排序为`O(s log s)`；运行时inspection成本为`O(k + 1)`，其中`k`是
本次实际满足阈值的连续队首数量，不读取目标列车interval，也不扫描未清除队首之后的资源。后续若rolling window显著增大，可在不改变合同的情况下把rail occurrence
索引进一步复用到validation的其他派生结果；不得退回resource乘occurrence的全扫描，也不得为优化增加
第二份authoritative footprint。

route-progress path编译为`O(n)`时间和空间，其中`n`是已验证route occurrence数；初始车尾验证只检查第一个
occurrence，为`O(1)`。cursor通常只检查当前
occurrence，成本为`O(1)`；跨越片段时只顺序扫描实际跨过的suffix，整个未失败生命周期累计为摊销`O(n)`。
终止性不匹配最多扫描剩余suffix一次，随后锁存fail closed为`O(1)`；不建立bucket、interval tree或全路线
反向查询。每次world observation dispatch额外用`O(t)`时间和空间构造一次train lookup（`t`为该观察的列车数），
再以`O(c)`稳定遍历active controllers（`c`为当前claim owner数）；不会退化为每个controller重扫全部列车。

## 4. 目标签名与合同

以下是进入实现前仍需字段审查的目标shape，不是当前完整公开API。当前冻结路径
`RouteSnapshot`已经携带自己的`topologyRevision`，`ProtectedRouteRequest`不得再复制该字段；
catalog、projector和claim检查全部读取`request.route.topologyRevision`。当前过渡请求已携带完整
`RailwayStateStamp`；stamp的topology revision必须与route一致，不能把不同epoch的occupancy与
topology拼接。

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

// CURRENT transitional registration adapter; target authority is the Basic-owned store/catalog.
fun interface ProtectionRegionProvider {
    fun resolve(region: ProtectionRegionRef): ProtectionRegionDefinition?
}

data class RouteResourceRequest(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val routePlan: RoutePlanRef,
    val desiredAuthorityBoundary: RouteBoundaryRef
)

// TARGET capability shape; concrete public value-type names remain an API-batch decision.
interface RouteResourceProvider {
    val providerId: String
    val revision: Long
    fun contribute(context: RouteResourceContributionContext): RouteResourceContribution
}

data class RouteResourceContributionContext(
    val observationStamp: RailwayStateStamp,
    val train: TrainAdmissionView,
    // Basic-clipped traversal of only this provider's matched section/binding.
    val traversal: RouteContributorTraversal
)

data class RouteResourceContribution(
    val resources: List<InfrastructureResource>,
    val minimumClaimUntil: RouteBoundaryRef?,
    val preparationIntents: List<RouteResourcePreparationIntent>
)

internal data class RouteBoundResourceBinding(
    val resource: StableInfrastructureResource,
    // canonical AB metres, positive length, no direction meaning
    val anchors: List<RailSliceRef>
)

internal data class RouteBoundResourceCatalogSnapshot(
    val topology: TopologyState,
    val bindings: Map<StableInfrastructureResource, RouteBoundResourceBinding>
)

internal data class CompiledRouteAdmission(
    val request: RouteResourceRequest,
    val claimUntil: RouteBoundaryRef,
    val resources: InfrastructureFootprint,
    val preparations: List<InfrastructurePreparationRequirement>,
    val tailReleaseEntries: List<ResolvedTailReleaseEntry>,
    val routeProgressPath: CompiledRouteProgressPath
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

Basic service是world-local，因此platform-neutral request不重复携带world ID；Bukkit facade显式接收world ID并路由到对应service。当前provider registration是迁移期输入adapter，不是保护区域的authoritative owner；目标API由Basic对definition执行create/update/delete并原子发布catalog，SimpleATOBukkit只是调用者。adapter disable只停止对应编辑/观察入口，不能隐式删除Basic定义。已准入live claim由host lifecycle owner持有，不能因为请求插件disable而直接释放。逻辑requester仍进入grant snapshot和诊断。

上述签名仍是概念shape：最终API必须让`RoutePlanRef`解析为绑定一个topology revision的冻结traversal。公开请求只描述路线选择和目标，不增加第二份resource intent。`CompiledRouteAdmission.resources`是准入事务唯一消费的资源集合；preparations和tail-release entries是操作/生命周期元数据，不是其他资源集合。调用方提供`trainId`，Basic从`observationStamp`对应的authoritative observation临时解析长度、方向和occupied intervals，不相信调用方自由填写的列车长度。

`RouteResourceContributor`是Basic内部验证能力，不定义另一种footprint。内置规则直接实现该能力；外部`RouteResourceProvider`的不可变返回值先由Basic校验和适配，再进入同一内部流程。两者都只能向当前验证过程增加标准`InfrastructureResource`、延长`claimUntil`并附加操作/释放元数据。资源绑定提供的physical occupancy geometry仅用于查询authoritative occupancy；它不进入request、claim identity或持久化模型。外部SPI的具体Java类型名、provider registration facade和preparation-handler类型仍未冻结，上述代码是目标capability shape，不是当前可调用API。

当前过渡API的唯一拓扑绑定也遵守同一规则：`RouteSnapshot.topologyRevision`是路径选择时捕获的
版本，`RouteSnapshot.revision`是路径本身的版本。projector直接比较前者与传入`TopologyState.revision`；
不存在额外的`expectedTopologyRevision`参数，也不存在request层的重复字段。这样stale拒绝来自冻结
路径本身，避免caller提交两份互相矛盾的拓扑版本。

旧的公开`ProtectedRouteRequestResolver -> ProtectedRouteResolution.Resolved(footprint)` seam已经删除；
`ProtectedRouteClaimService`只接受Basic内部validation结果。validator异常映射为`VALIDATION_FAILED`，不得
fallback到caller resources或partial rail-only claim。

`request.owner`是调用方计划revision，不承担release过程中的registry CAS revision。目标grant API仍将为每个
成功grant分配独立稳定`grantId`和host-owned internal claim owner；当前internal removal-only prefix shrink保持
该claim的exact logical owner/revision不变，只缩减footprint。因此尾部逐段释放不会伪造新的调用方计划
revision，cancel也不会因内部shrink而失去目标。

## 5. 不变量、生命周期、线程与版本

### 5.1 区域拓扑

- 一个boundary位于一根rail的canonical AB坐标；`ProtectionBoundaryDefinition.position.facing`表示从边界外侧进入内部的本地方向，不表示线路运营方向，也不再保存第二份方向字段。
- `ProtectionRegionDefinition`是Basic-owned current model，只表达region identity、目标topology revision与boundary集合；SimpleATOBukkit提交编辑命令，但不是definition owner。Basic由此推导的内部rail slices、连通分量与索引membership属于catalog内部缓存，不回填definition，也不接受caller提交第二份subgraph；否则boundary与subgraph会形成两份可能冲突的事实来源。SimpleATO需要的占用、reservation、限速和向前观察以后通过统一`AuthoritySection`行为接口提供，而不是读取Basic内部子图。
- 固定闭塞定义同样是Basic-owned current model，以stable ID、topology binding和有向边界/extent描述一个不可分割区间；精确public shape留到API批次冻结。Basic把固定闭塞和保护区域编入同一个authority partition，创建、更新和加载时原子拒绝任何受控extent重叠，并返回冲突stable IDs与位置；不得由SimpleATO在两个独立store之间事后补偿。
- `ProtectionRegionCatalog`在一次rebuild开始时捕获一个public Core `TopologyState`根；内部`ProtectionRegionTopology`保留该根，并只从edge的有序rail slices及`Terminus`/`Turnout`/`LoopAnchor`transition派生endpoint adjacency和region membership索引。catalog publication与window resolver始终使用同一根，返回前以根身份和revision检查currentness；旧根可被既有读者安全保留，但不得与新根混合。该索引可按`(topology revision, region revision)`丢弃重建，不是第二张authoritative铁路图，也不再读取live `RailView.nextRails`。
- boundary必须离rail两端均大于`Curve.TOLERANCE`；端点可能连接多个分支，不能作为唯一有向half-edge，必须拒绝而不是猜测。
- 解析器在临时拓扑视图中于所有boundary处切断穿越，不修改Core live topology。每个boundary的inside half-edge必须进入同一个连通分量，outside half-edge不得仍能到达该分量；该唯一分量就是区域内部。
- 同一区域不得有重复或歧义boundary。不同区域内部子图必须不相交；相邻区域可以在同一物理位置使用相反inside half-edge，只要切分后的内部仍是不同分量。
- region revision在boundary集合、位置、inside方向或语义发生变化时递增。definition同时固定`topologyRevision`；拓扑变化后必须重新验证/发布definition，不能只沿用旧region revision。
- graph cut与内部membership在definition create/update、region revision或topology revision变化时计算并缓存；不得在每次列车tick或每次信号aspect查询时重新遍历完整world graph。
- 一条新自动进路必须从区域外进入并完整离开。同一路径在同一request内不得重复进入同一region；循环进路必须重新规划或拆成后续request。
- 一个region应覆盖一个完整咽喉。区域内部没有正常的独立授权停车点；故障/紧急停车保持尚未清除的claim。若一个几何咽喉确实存在能容纳整列车、且列车停下后不侵入两侧冲突资源的普通等待区段，玩家才可把两侧定义成两个region。

### 5.2 路径窗口与覆盖

- `route`是一次解析使用的冻结、有方向路径。`routeDistanceMeters`从路径起点递增；boundary的`position`仍使用单根rail canonical AB坐标，两者不得混用。
- `route.revision`与`route.topologyRevision`语义独立；前者改变不要求Core结构变化，后者改变也不能由Basic自动改写旧route。
- route-bound resource catalog必须持有与region validation相同的`TopologyState`对象；只有revision数值相等但root不同也不得混用。
- complete empty catalog表示已经针对当前root确认没有route-bound resource；unavailable、构建失败或旧root snapshot必须fail closed，不能当作empty。
- binding identity在snapshot内唯一；所有anchors必须是canonical AB正长度、包含于现有rail projection。diamond必须有两个不重复anchors，不能产生任何跨线continuation。
- entry必须以该boundary的`insideFacing`跨入内部；exit必须反向跨出另一个boundary。entry到exit之间的每个route segment都必须在该region内部。
- 每个沿route遇到的turnout、diamond或其他controlled resource必须能由`TopologyState`和host resource index完整派生并受有效保护覆盖；道岔已经处于目标方向也不能豁免。没有任何资源贡献且无需claim的route可返回`NOT_REQUIRED`。
- region identity本身不是一把排他的全区锁。claim只包含实际路径rail intervals和共享冲突资源，使同一咽喉中不冲突的进路仍可并行；region仍可向SimpleATO暴露类似闭塞区间的occupancy/reservation观察。
- 普通闭塞区间与保护区域的空间不得重叠；Basic在统一authority partition的create、update与load publication前原子验证该authoring约束，SimpleATO不得通过独立store或运行时重叠claim补救错误配置。

### 5.3 净空义务与区域划分

- validation从同一`RailwayStateStamp`中的authoritative train/consist状态读取占用长度、方向和intervals；这些只是本次检查的物理状态，不形成另一种resource envelope。保护区域的最小尾部净空不得短于出口距离加实际occupied length与明确margin。
- region exit到closure终点之间必须是同一冻结route上的连续路径，并纳入occupancy检查与tail-release queue。不能用“列车头已过exit”代替“列车尾已过exit”。
- region自身只验证入口/出口方向、内部单次连续穿越、所选内部路径及资源、tail-clear释放条件与最小净空前沿；它不判断出口后采用固定闭塞还是移动闭塞，也不查询下一个section。该选择由同一次route validation根据遇到的capability完成。
- `safeHoldingInside`不是definition字段。某个位置能否结束authority，是验证结果的性质：固定闭塞中它必须是完整区间边界，移动闭塞中可以是由该territory认可的精确route位置；两者都要求整列车占用落在已获取资源内，并且没有尚未满足的clearance obligation。
- 若前一区域的净空义务穿过后一区域入口，同一次validation必须把后一区域资源加入同一集合；前一区域不感知后一区域，caller也不提交手工composite window。若冻结route终止、循环或权限边界使closure无法完成，则整次请求失败且不产生claim。
- 运营停车点只有在满足上述可结束条件时才能成为本次authority boundary；停车前不跨越一个已经安全终止的运营停车点预申请另一侧独立进路。host按车尾原子缩小既有资源集合。当前grant发布后不追加资源；未来滚动取得只允许沿同一冻结路线验证并原子追加suffix，不得加入route plan以外的资源。
- 同一grant内各组件按自己的安全边界释放，但始终由同一个train-owned claim持有：保护区域资源按对应conflict/release envelope尾部清除，固定闭塞整段在车尾清除后释放，移动闭塞interval可以随车尾单调缩短。任何组件都不能因另一个组件已清除而提前释放自己的冲突资源。

### 5.4 route验证与资源收集

caller和add-on只提交自己选择的topology-bound有序edge traversal。Basic沿frozen route做一次线性校验，证明edge连续且每个turnout continuation合法，并在相同pass中把rail intervals、turnout、diamond和其他共享冲突资源加入一个`InfrastructureFootprint`；任何无法解析的binding都fail closed，Basic不得自行搜索替代路径。

验证过程可以产生三类非资源元数据：需要执行的有序infrastructure preparations、整次准入的authoritative occupancy query，以及按车尾可安全释放顺序排列的tail-release entries。它们分别描述操作、验证和生命周期，不能包装成第二个claim/physical footprint。

邻接rail interval不会隐式表达turnout/diamond冲突；不得用一个虚构全局里程interval跨越多根rail。

### 5.5 transaction与生命周期

准入只在world owner的post-publication阶段执行；事务期间不能推进physics或并发修改topology。对外可见的grant只能在claim完成且整个基础设施准备批次进入`READY`后发布。

当前claim seam先保留一次`latestObservation()`返回的确切`RailwayObservation`，要求request stamp与
其world、physics epoch、topology revision完全相等，且request train存在于同一观察。路线验证前检查
route/stamp/current topology，验证后再次检查current topology和latest observation stamp；随后只在保留
观察的occupancy index上查询`CompiledRouteAdmission.occupancyGeometry`，排除request train自身，并在
提交registry前再次检查topology与latest stamp。任一阻挡结果以稳定train ID排序并返回
`PHYSICALLY_OCCUPIED`，不得调用registry；空白train ID或在其他status携带occupying IDs均为非法result。

```text
validate immutable route and collect one resource set
  -> recheck observation/topology/region revisions
  -> check authoritative occupancy geometry for each resource
  -> acquire the complete resource set
  -> start the complete ordered infrastructure-preparation batch
  -> retain its ticket plus claim mutation token while PREPARING
  -> poll until the complete batch is physically READY
  -> finalize the claim
  -> publish admitted grant / expand authority
```

claim与物理准备是两个阶段：footprint claim先阻止其他进路或编辑取得冲突资源，但并不证明道岔、可动桥或未来其他设备已经到位，也不能产生grant。`CompiledRouteAdmission.preparations`保存route validation为**本次滚动准入及其最小closure**派生的唯一有序准备列表；它绝不枚举整条运营路线上的全部资源或提前准备尚未进入当前claim horizon的设备。每项携带一个已存在于本次唯一claim footprint中的稳定resource，同一resource当前至多出现一次。当前一项准备对应一个resource的假设足以表达道岔；若未来设备必须由多个resource共同标识，应先重新讨论模型，不能暗中把第二份footprint塞进requirement。

列车何时请求下一段、从哪个route cursor开始编译、向前扫描多远，以及哪个boundary触发下一次滚动准入，属于未来SimpleATO客户端与admission compilation合同，不属于Basic preparation actuator。actuator只执行已经编译完成的当前batch，不读取完整运营route、不自行lookahead，也不扩展claim或选择下一批requirement。

通用preparation actuator的start只允许两种结果：未产生任何准备side effect的`Rejected`，或带batch ticket的`Accepted`。
snapshot是不可变值，状态为`PREPARING`、`READY`、`ABORTED_SAFE`或`UNSAFE`。`abort()`只要求resource-owned
handler结束本次准备并进入它自己定义的安全状态，不规定恢复旧状态、恢复默认状态或由列车执行复位；异步abort期间仍可
报告`PREPARING`，直到handler报告`ABORTED_SAFE`或`UNSAFE`。actuator必须在第一个side effect前解析完整列表并拒绝不支持的
requirement。composition层以后可加入动画、故障和timeout policy，但Basic不硬编码转换时长，也不在本阶段公开handler
注册或持久化合同。

`ProtectedRouteClaimService.acquire`返回内部start result：请求可能立即得到terminal typed result，也可能得到pending handle。
handle拥有registry claim mutation和ticket，只能在main owner轮询。`PREPARING`保持pending；`READY`才完成claim并返回成功；
`ABORTED_SAFE`才删除本次新acquire并返回`PREPARATION_FAILED`。snapshot/actuator异常或`UNSAFE`均保留claim reservation、
禁止扩大authority并Stall。ticket是同进程对象，不增加随机identity、状态转换历史或网络式幂等协议。过晚abort若设备已经
`READY`则为terminal no-op并正常完成；terminal admission重复poll/abort必须幂等。

Core把同一个物理continuation分别保存在两个到达方向的`RailView.nextActiveRail`中，因此一次
`fromPort -> toPort`提交必须同时设置`from-arriving -> to-departing`和
`to-arriving -> from-departing`。端点映射固定为：到达A端使用BA view、到达B端使用AB view；
departing view是对应到达view的`reversed`。第一版即时`RailView` preparation actuator只处理`ResolvedTurnoutSelection`；它先类型检查整批requirement，再在第一次写入前解析并验证全部live view，然后按冻结路线顺序直接设置两个方向的selection并返回已终态`READY` ticket。当前`RailView.selectNextRail`只是内存赋值，没有动画、机械反馈或可恢复失败，因此不保存旧selection、不实现虚构的逆序补偿，也不通过测试注入人为制造该失败面；未知requirement或预解析失败则`Rejected`且没有live write。已对位selection不写入。若完整预解析后的写入意外抛出异常，claim service按未知物理状态保留claim并Stall，而不是宣称已经恢复。未来真实异步设备仍通过通用ticket报告`PREPARING`、`ABORTED_SAFE`或`UNSAFE`，其安全状态和恢复策略由该设备handler拥有。

claim registry为admission mutation返回内部opaque rollback token。pending preparation期间token保持有效；
platform owner cleanup不得拆除正在提交的claim。成功提交后销毁token。未来异步handler只有报告
`ABORTED_SAFE`后才能用token删除本次新claim；`UNSAFE`或状态未知时
不得销毁token，当前claim与rollback reservation继续保留，以覆盖不确定的物理状态。registry close会使未完成token失效，调用方必须Stall而不能报告普通commit failure。
registry不提供受保护进路的整份claim replace；初次acquire rollback与同grant delta append是两个明确的内部操作。

provider/plugin disable只停止新grant并使相关自动authority fail closed。已进入或可能进入的live claim转为host-owned recovery state，直到authoritative occupancy证明尾部清除；未进入的grant也只能由host在确认没有列车越过entry后取消。world unload期间没有运动，reload必须先恢复/重建claim与限制，再允许新departure或编辑。

一次validation开始时必须捕获一个不可变provider-catalog snapshot及其revision，并在claim前再次确认currentness。provider注册、解绑或binding更新只能原子发布新snapshot；不能让一次closure前半段使用旧provider、后半段使用新provider。provider回调是准入owner上的有界纯计算，不得阻塞等待I/O、scheduler或world thread，也不能保留context留待异步读取。provider回调抛错、返回越界位置/未知资源、在调用期间改变revision或在准备完成前卸载时，Basic拒绝新authority并fail closed；已经提交的claim及编译完成的释放元数据转由host生命周期持有，不能依赖provider仍在线才能安全释放。provider回调本身始终无side effect；如果contribution包含preparation intent，只有Basic在原子claim成功后才能调度独立注册的设备handler。

## 6. 校验与错误矩阵

| 条件 | 结果 | selection/claim/authority |
| --- | --- | --- |
| route没有任何需要claim的resource/contribution | `NOT_REQUIRED` | 全不变；route可继续 |
| controlled resource没有有效保护区域 | `PROTECTION_REQUIRED` | 全不变；即使道岔已对位 |
| `clearUntil`以前存在窗口外turnout | `PROTECTION_REQUIRED` | 全不变；不得只claim窗口内子集 |
| 同一turnout resource在一个窗口内重复出现 | `INVALID_REQUEST` | 全不变 |
| host route validator抛错 | `VALIDATION_FAILED` | 全不变；不接受caller fallback |
| traversal断裂、方向错误或turnout continuation非法 | `INVALID_ROUTE` | 全不变；Basic不换路 |
| route-bound resource index缺失或coverage不一致 | topology/configuration fault，准入fail closed | 全不变；不从live对象猜测 |
| catalog尚未完成、root不是route验证使用的同一对象或查询期间被替换 | `STALE_TOPOLOGY` / resource-index unavailable | 全不变；empty不能代替unavailable |
| binding重复identity、anchor越界/零长度，或diamond不是两个不重复anchors | catalog update rejected | 保留旧snapshot；不发布partial index |
| 同一route离开anchor后再次命中同一resource | `INVALID_ROUTE` | 全不变；不把循环静默去重 |
| observation physics epoch不是latest published | `STALE_OBSERVATION` | 全不变；重新观察后再解析 |
| topology revision不匹配或解析中变化 | `STALE_TOPOLOGY` | 全不变 |
| request train不在完全匹配的observation中 | `INVALID_REQUEST` | 全不变；不得查询其他epoch或live train |
| region不存在、revision不匹配或definition未针对当前topology验证 | `STALE_REGION` | 全不变；等region/topology事件，不逐tick重试 |
| provider snapshot过期、provider回调异常或返回越界/未知资源 | provider/configuration failure | 全不变；不忽略该contribution，也不退化到无扩展规则继续授权 |
| entry/exit方向错误、路径离开内部后重入、同一occurrence重复贡献 | `INVALID_ROUTE` | 全不变 |
| closure到route末端仍未满足净空、找不到合法authority endpoint或检测到循环 | `INSUFFICIENT_EXIT_CLEARANCE` / `CYCLIC_RESOURCE_DEPENDENCY` | 全不变 |
| 任一physical locking interval被列车占用 | `PHYSICALLY_OCCUPIED` | 全不变；返回train IDs |
| footprint与live claim冲突 | `RESOURCE_CONFLICT` | 全不变；返回owner/revision/resource |
| claim成功、preparation batch start被安全拒绝 | `PREPARATION_FAILED` | 无preparation side effect，claim前状态恢复，authority不扩大 |
| ticket为`PREPARING` | pending | 新claim与rollback reservation保持，authority不扩大 |
| ticket为`READY` | `ACQUIRED` | claim完成；之后才允许上层发布authority |
| ticket为`ABORTED_SAFE` | `PREPARATION_FAILED` | resource handler确认安全中止且新claim删除，authority不扩大 |
| ticket/actuator异常或`UNSAFE` | `WORLD_STALLED` | claim及rollback reservation保留，authority fail closed |
| 相同request/revision重复提交 | `UNCHANGED` | 返回原grant，不重复切换 |

`UNCHANGED`只表示同一platform owner已经持有完全相同owner revision与footprint，是幂等重试；它
不会重新应用道岔，也不是修复claim之外selection漂移的机制。所有手动/编辑路径必须由同一claim guard
阻止这种漂移。

具体enum可以扩展当前`ProtectedRouteClaimStatus`或由更高层`ProtectedRouteAdmissionResult`包装，但上述failure identity不得压缩成日志字符串。

## 7. 性能、复杂度与退化行为

- region definition注册或revision变化时做一次graph cut，复杂度为捕获连通分量的`O(V + E)`；不得在每次列车tick或信号查询重跑。
- boundary在同rail内先排序；重复检测为排序后的相邻比较。region overlap通过rail ID索引后做slice exact comparison，不扫描无关rail。
- route-bound anchors按rail分组并按canonical start排序，同时维护prefix maximum end；每个route occurrence先以二分跳过不可能命中的前缀，再扫描起点不晚于occurrence终点的candidate，成本为`O(log a + c)`，其中`a`是该rail的anchor数、`c`是二分后仍可能重叠的candidate数。不得每次准入扫描全部bindings；若实测出现大量嵌套anchor导致`c`显著大于真实命中数，再以区间树替换内部索引，不改变catalog合同。
- 一次route admission对实际扫描到closure终点的冻结traversal做单调线性validation和资源收集；每个route occurrence至多处理一次，因此复杂度为`O(scanned edges + bound resources)`。occupancy复用Core每epoch已构建的32 m bucket index；合并后的每个查询interval成本为`O(covered buckets + unique local candidates)`，candidate按span去重后exact check，不扫描无关rail或全world trains。禁止`allTrains × allRegions`及Basic重复建索引。
- occupancy query geometry对本次收集到的`i`个interval执行按rail分组、排序及线性合并，最坏为`O(i log i)`；route-bound resource直接复用同一catalog snapshot的完整anchors，道岔端点直接投影自同一`TopologyState`，不得为补全geometry做live rail或全catalog扫描。
- unchanged stale region/topology是配置事件驱动的拒绝，不逐tick重算；资源冲突才按明确调度策略重试。
- candidate/occupancy索引报告不完整、provider异常或revision变化时fail closed，不退化到live图扫描并继续授权。
- provider lookup使用按rail/section binding建立的反向索引；只调用本次closure实际命中的provider，成本计入`bound resources`。同一`(providerId, resourceId, traversal occurrence)`至多求值一次，不允许每个provider重新扫描完整route，也不在每tick无请求时执行provider回调。provider回调不得通过阻塞I/O、异步回调或平台调度器把这个有界计算变成不可预测的长操作。

## 8. Good / Base / Bad cases

- Good：玩家用一个region覆盖完整车站咽喉；两条路径没有共享rail/conflict node，因此不争用region ID并可并行grant。
- Good：复杂咽喉内两条路径没有共享rail/conflict node；虽然属于同一保护区域，两者不争用region ID，可以并行grant。
- Good：区域A出口后的净空穿过区域B入口；同一次validation把B的资源加入同一claim并延长minimum，A和B互不查询对方。
- Good：外部平交道口provider只在路线命中自己的binding时读取局部traversal和列车长度，返回共享资源及准备要求；SimpleATO和保护区域均无需认识平交道口类型。
- Good：两条不相连rail各有一个diamond anchor；任一方向经过任一anchor都向同一claim集合加入相同resource identity，但topology仍不存在跨线continuation。
- Base：直线route没有controlled resource且不进入保护区域，返回`NOT_REQUIRED`，SimpleATO不存在时仍可手动驾驶。
- Base：当前topology root的complete empty catalog查询得到零命中；这与catalog尚未构建不同。
- Bad：caller因为道岔已经对位而省略保护；仍返回`PROTECTION_REQUIRED`，不得把当前selection当成授权。
- Bad：把`safeHoldingInside=true`写进区域definition后无条件在该点截断authority；必须由当前route、列车包络与未完成义务共同推导。
- Bad：用最新topology搭配上一physics epoch的occupancy；返回`STALE_OBSERVATION`，不得自行拼接快照。
- Bad：resource catalog过期时把它当empty继续授权；必须拒绝并等待同一root的完整snapshot。
- Bad：插件disable回调直接释放一列尚在咽喉内列车的claim；必须转host recovery并保持限制。
- Bad：保护区域拿到完整resource catalog并自行搜索出口后的闭塞/区域；它只能返回本区域贡献和最小前沿，后续发现属于Basic中央closure。

## 9. 必需测试及断言点

- region graph tests：多入口咽喉得到唯一内部；inside seeds不连通、outside回连、内部重叠和歧义boundary被拒绝；合法相邻区域允许共享物理边界。
- route validator/projector tests：零/一/多turnout、route-bound diamond resource、正反向stable identity、BA canonical转换、零长度segment的deterministic方向、already-aligned turnout、非法continuation、断裂route、resource index缺失、route离开/重入和重复occurrence。
- route-bound catalog tests：complete empty与unavailable区分、同root publication、旧root拒绝、invalid anchor保留旧snapshot、同rail二分边界、AB/BA命中相同diamond identity、两侧anchor命中同一identity、相邻occurrence合并及离开后重入拒绝。
- closure tests：精确最小列车长度、短1个容差单位、断开route、出口后固定闭塞整段提升、出口后移动闭塞精确interval/MA下限、连续两个保护区域、贡献链扩展、合法authority endpoint选择与循环检测；断言每个occurrence至多处理一次且失败不产生claim。
- provider SPI tests：只调用route实际命中的provider；传入同epoch不可变train view和裁剪后的局部traversal；多个provider贡献进入同一footprint；越界前沿、未知/跨namespace资源、异常、revision切换和disable均fail closed且无partial claim；provider不能通过公开类型取得registry或mutable world。
- epoch tests：physics epoch、topology revision、region revision任一stale均fail closed；解析前后revision变化不穿透。
- route binding tests：route revision与topology revision可取不同值；projector只从route字段拒绝stale topology，request没有第二个可冲突的revision。
- turnout identity tests：正反向及无结构变化的topology rebuild得到同一resource；revision-local node ID不同也不改变resource identity。
- transitional route-validation tests：AB/BA window clipping、跨rail canonical intervals、唯一resource set、turnout stable resource、窗口前/clearance内未保护turnout拒绝、null window的`NOT_REQUIRED`与`PROTECTION_REQUIRED`。
- transaction tests：resource conflict、physical occupancy、即时turnout handler整批预解析/双向对位/直接READY、连通双道岔按冻结路线顺序准备、第二个道岔解析失败时第一个仍保持原选择、未知requirement全批拒绝、跨多次poll的未来异步batch ticket、READY finalize、异步abort保持PREPARING、ABORTED_SAFE精确acquire rollback、abort幂等及READY竞争、snapshot/abort异常和UNSAFE；断言physical preparation、claim和authority三者满足矩阵。
- release tests：未进入取消、头部过exit但尾部未过、tail clear、单region prefix shrink、train消失、provider disable、world reload recovery。
- rolling extension tests：同route/topology suffix seam、只查询delta occupancy并只准备delta、conflict保持旧claim/authority、
  同grant第二个pending请求返回busy、pending期间旧prefix继续release、成功后同controller队尾追加、重复controlled
  resource拒绝、安全preparation failure只撤销delta且不恢复已释放prefix、unsafe abort保留reservation并Stall。
- dispatcher tests：automatic和`MANUAL_ROUTED`只能调用新service；不存在接受raw planned section的overload；`FULL_MANUAL`不申请新grant。
- performance tests：一次admission只遍历一次frozen route；unchanged `STALE_REGION`不逐tick重算；occupancy按rail索引查询，不做`allTrains × allRegions`。

## 10. 迁移、未决事项与Wrong/Correct

迁移按`TopologyState region cache -> topology-bound frozen route -> single-pass route validation/resource collection -> atomic admission -> tail release/recovery -> caller migration`推进。前一层合同测试通过前不得开放后一层public service。

实现前仍需冻结`RoutePlanRef`完整字段、edge/slice边界语义、clearance margin来源、closure终点boundary引用、grant的
持久化/恢复边界、provider registration/preparation-handler的具体公开类型、provider disable后的可观察状态，以及extension public API名称、字段和typed failure。
不会引入caller-supplied第二资源DTO或physical footprint。diamond stable ID来源、几何检测owner和持久化格式留到
placement/update批次，必须在新增持久化边界前单独审查；runtime catalog实现不得先发明兼容路径。通用contributor、
单调closure、same-route/delta-only append、authoritative train observation和无partial mutation已经冻结。

### Wrong

```kotlin
val regionA = acquire(regionA)
waitFor(regionB) // 已进入后才发现还需要B，形成hold-and-wait
```

### Correct

```kotlin
val validated = validator.validate(request, latestObservation)
val claimMutation = registry.beginAcquireClaim(validated.resources) // 一次原子获取唯一集合
if (!claimMutation.result.successful) return claimMutation.result.toAdmissionResult()

return when (val started = actuator.start(validated.preparations)) {
    is Rejected -> rollbackClaim(claimMutation)
    is Accepted -> PendingAdmission(claimMutation, started.ticket)
}
```

```text
Wrong: TopologyState.nodes += Diamond; route can now turn across the geometric crossing
Correct: Basic catalog binds two rail anchors to one stable conflict resource; connectivity is unchanged

Wrong: stale or missing catalog == empty catalog
Correct: only Complete(snapshotForExactTopologyRoot, emptyBindings) means there are no bindings

Wrong: protectionRegion.findResourcesAfterExit(route, train).acquireRecursively()
Correct: protectionRegion contributes local resources plus minimumClaimUntil; Basic alone scans farther and atomically claims the closure

Wrong: SimpleATO knows every custom resource type and constructs their resource list
Correct: SimpleATO submits route intent; Basic invokes only matched built-in/external providers and validates one final claim set
```

这里的原子性是对外行为合同：失败时不会发布grant或扩大authority。只有无side effect的start拒绝或handler明确报告`ABORTED_SAFE`时才撤销新claim；物理状态不确定时系统保留资源并Stall，而不是虚构补偿成功。
