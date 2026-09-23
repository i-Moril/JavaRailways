# Basic resource allocation 契约

## 适用场景与用途

触发：修改turnout/route section/berth ownership、movement authority、release detector、applied-plan lease、live claim、edit permit或manual control resource semantics。

用途：一个world的allocator先预校验完整resource set再原子提交；conflict不preempt，失败不留下partial ownership，release由full physical occupancy/tail驱动而非run状态或head位置。

## Owner、keys与结果

`RouteSectionAllocator(world)`是尚未迁移的Dispatcher seam，拥有旧Basic运营lock；physical `RailView.nextActiveRail`仍由Core topology持有。调用在successful current physics epoch后。它不是目标共享进路服务，新调用方不得依赖它。

```kotlin
data class RouteSectionAllocationKey(
    val owner: RouteSectionOwner,
    val routeId: String,
    val routeRevision: Long,
    val sectionIndex: Int
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/route/RouteSectionAllocator.kt`。Result：

- `Confirmed(ConfirmedRouteWindow)`；
- `Conflict(resource,heldBy)`；
- `PhysicallyLocked(resource,occupyingTrainIds)`；
- `Invalid(reason)`。

`TurnoutRequirement`字段为canonical `from/selectedNext` refs、route distance m、protection m(default20)、locking m(default5)，要求`locking<=protection`。`lockingIntervals(world)`为三legs构造direction-independent canonical AB `RailInterval`。

## Atomic allocation矩阵

| 条件 | result | turnout/ownership |
| --- | --- | --- |
| same key再次request | same `Confirmed` | idempotent |
| section不存在/stale view/branch disconnected | `Invalid` | 无mutation |
| same resource在section选不同branch | `Invalid` | 无mutation |
| resource已有owner | `Conflict` | 不preempt、不切任何turnout |
| 要切branch且locking interval occupied | `PhysicallyLocked`含train IDs | selections不变 |
| all prechecks通过 | 按冻结requirement顺序直接apply selections→record window | 一次确认 |
| release(key) | 只释放ownership，幂等Boolean | 不自动reset turnout |

旧allocator在第一次写入前解析全部live view/branch、拒绝重复资源的不同选择、检查现有owner并完成需要切换的全部locking interval占用查询。当前`RailView.selectNextRail`只是main-thread同步内存赋值，因此预检成功后直接按section冻结顺序写入；没有可触发的异步设备失败，也不捕获`previousSelection`或宣称能补偿恢复。若未来设备可能处于`PREPARING/UNSAFE`，只能迁入共享`InfrastructurePreparationActuator`生命周期，不能在此旧seam加入测试注入失败或另一套rollback。

Dispatcher按absolute timetable priority tick + timetable/service/cycle stable order调用；allocator自身不排序。conflict/physical lock按world retry ticks，movement authority截在protection point，不Stall；identity/invariant disappearance才world Stall。

Release必须由`RailIntervalReleaseDetector`观察`BEFORE→OCCUPIED→RELEASED`且runtime head超过clear progress。mission replacement保留old runtime至tail clear。Berth/Depot同原则。

Manual：`MANUAL_ROUTED`仍分配/验证/release；`FULL_MANUAL`不申请new route但old resources tail-release；release control后`REPLAN_REQUIRED`，仅在无old locks/berth、stopped、explicit origin时replan。

## Shared infrastructure lease 与 edit guard

`InfrastructureLeaseRegistry`是world-local原子registry，现阶段与尚未迁移的`RouteSectionAllocator`并存；不得把两者并存误写成进路资源统一已经完成。它区分三类持有：

- applied-plan lease：共享、长生命周期，可彼此重叠，不预留turnout方向，也不阻止正常live claim；
- live runtime claim：同一resource上互斥；受保护进路runtime使用acquire、append、shrink/release，不提供整份claim replace；add-on service只暴露read-only diagnostics；
- edit permit：独占并跨完整mutation transaction持有，与overlap的lease、claim和其他permit冲突。

`InfrastructureOwner(ownerId, revision)`稳定标识logical owner与其revision；platform plugin token只用于lifecycle ownership，不进入持久化identity或公开diagnostic。claim acquire/append/shrink/release必须匹配platform owner与expected logical owner；内部footprint变化不伪造新的caller plan revision。plugin disable释放该plugin直接拥有的lease/permit；路线资源准入产生的live claim由host token持有，requester disable后按[route resource admission](./route-resource-admission.md)转入fail-closed recovery，不能在列车清除前释放。world unload与service close必须先由composition layer处理相同的recovery/restore语义。

每个请求携带authoritative `topologyRevision`。Registry在resource validation前后各读取一次当前revision；任一次不匹配返回`STALE_TOPOLOGY`。rail区间由world验证存在与长度；stable resource由唯一namespaced provider验证。missing返回`INVALID_RESOURCE`，provider异常返回`RESOURCE_PROVIDER_FAILED`。所有非成功结果保持registry不变，并只在`RESOURCE_CONFLICT`中返回blocker owner/revision/resource diagnostics。

## 跨轨道 infrastructure footprint

`RailIntervalResource(railId,startAbMeters,endAbMeters)`表示恰好一根rail上的canonical AB闭区间，单位米且`0 <= start <= end`。它不存在跨rail的全局里程。沿route跨越多根rail时，必须在同一个`InfrastructureFootprint`中放入每根rail各自的interval；申请、替换和编辑许可都以整个footprint为原子单位。

如果路径经过turnout、diamond或其他共享conflict node，还必须加入对应`StableInfrastructureResource(typeId,resourceId)`。两侧相邻rail intervals不会隐式表达共享节点冲突。例：

```kotlin
InfrastructureFootprint(listOf(
    RailIntervalResource(railA, 80.0, lengthA),
    StableInfrastructureResource("javarailways:turnout", turnoutId),
    RailIntervalResource(railB, 0.0, 35.0)
))
```

同rail闭区间端点相交即overlap，例如`[0,20]`与`[20,40]`冲突；不同rail interval永不因数值相同而overlap。BA行进范围必须先转换成每根rail的canonical AB坐标。

turnout的方向无关资源身份由[路线资源准入](./route-resource-admission.md)唯一规定；这里仅要求
footprint使用其`StableInfrastructureResource`，不得改用revision-local `TopologyNodeId`。diamond资源
由独立binding提供。

## 路线资源准入

目标物理资源底线、附加资源、transaction和release/lifecycle合同见[route resource admission](./route-resource-admission.md)。当前保护区域实现细节只作为迁移记录保留在[protected route admission](./protected-route-admission.md)。本节只保留与通用registry直接相关的约束。

目标`RouteResourceIntent`描述一个自身携带topology revision的冻结route plan、列车身份、同一观察stamp、期望authority boundary与可选附加注册资源；request本身不再复制第二个topology revision。SimpleATO提供有序traversal，具体信号插件决定自己的boundary与附加资源；Basic从authoritative observation读取列车实际占用，并从请求范围自行推导不可删除的物理资源底线。Basic不执行保护区域/固定闭塞的递归contributor closure。

request不携带可替代Basic推导的raw `InfrastructureFootprint`或可信列车长度。SimpleATO从Core的authoritative `TopologyState`选择路径并提交有序edge traversal；Basic validator证明它在同一revision内连续、方向正确且每个continuation合法，再通过拓扑节点与绑定同一topology root的route-bound resource catalog，把rail intervals、turnouts、fixed crossings与其他shared conflicts加入物理底线。经过验证的插件addition只可增加资源。physical occupancy geometry只用于检查，不形成第二个footprint。非法或断裂traversal返回`INVALID_ROUTE`；保护区域覆盖不足由具体信号插件作为配置错误处理，不是Basic的`PROTECTION_REQUIRED`。验证前后均核对topology root，任何失败都不改变registry。

`ProtectedRouteClaimService`现为host-side transitional admission seam，并未替换旧`RouteSectionAllocator`调用者。service消费Basic内部route validation得到的唯一资源集合，在registry mutation前用同一观测的occupancy index查询已编译geometry，然后原子claim并启动同一admission中的完整有序`InfrastructurePreparationRequirement`批次。claim只建立逻辑排他性，不等于设备已经物理就绪；通用main-thread batch ticket报告`PREPARING/READY/ABORTED_SAFE/UNSAFE`，pending admission handle持有claim mutation，只有`READY`才完成claim，只有资源handler确认`ABORTED_SAFE`才删除本次新claim。`abort()`不规定恢复旧状态或默认状态。当前live actuator只处理turnout requirement：它在任何side effect前解析并验证完整的双向selection批次，然后即时写入并返回`READY`；当前内存选择没有虚构的运行时失败或旧selection恢复路径。执行未完成时不会返回成功claim结果，也不存在grant；任一snapshot/actuator异常或`UNSAFE`均保留当前claim reservation并Stall world。当前仍缺目标公开intent、附加资源API、grant/recovery和调用方迁移，因此不得声称自动/`MANUAL_ROUTED`流程已全面迁移。后续迁移必须删除可绕过mandatory physical footprint的automatic overload。

同一个`CompiledRouteAdmission`还携带validation同期生成的有序tail-release entries：唯一footprint中的每个resource恰好出现一次，entry只附加已经完整编译的车尾清除路线阈值，不复制occupancy geometry。释放控制器从当前队首扫描连续eligible prefix，但不先出队；必须先完成registry原子shrink，再推进队首索引。当前过渡compiler只使用最后claimed/conflict route extent，明确的clearance-margin来源仍未冻结；service已有显式observation驱动入口，但尚未自动接入publication。

Registry现已提供internal同步、只减不增的live-claim shrink primitive。调用方提交platform owner、完全匹配的
`InfrastructureOwner`以及非空、无重复的**准确资源身份**集合；它不是重建一份remaining footprint。registry在
同一把锁内拒绝closed、missing claim、owner不匹配和pending admission，证明每个待释放身份当前确实被该
claim持有，然后从内部保序身份集合中删除这些身份。若仍有资源，同一claim继续持有相同platform owner和
logical owner revision并返回`REPLACED`；若为空则删除claim并返回`RELEASED`。所有待释放身份须在首个删除前
验证完毕；任一失败都不产生partial mutation。lease与edit permit仍保留不可变footprint。对外
`claimSnapshots()`只在显式读取时构造新的不可变`InfrastructureFootprint`，不能把内部可变集合泄露给调用方。

初次申请等待设备准备时，registry另外保留不可变的原始footprint作为完成/安全回滚的核对依据，并要求当前claim
仍是最初取得的对象、owner和有序资源内容均未变。待处理的同路线append保存已验证的delta身份集合；旧prefix
仍可逐项shrink，但delta本身不能提前释放。由于delta与原资源互斥且始终保留，shrink后仅剩delta可由剩余
数量等于delta数量证明，不需要复制全部剩余资源。rollback append只删除该delta，不恢复已经释放的prefix。

Shrink不读取topology revision、不调用resource provider、不做overlap blocker查询，也不修改infrastructure
preparation；删除既有身份不可能产生新冲突。它按`InfrastructureResource.equals`匹配身份，而不是按
`overlaps`匹配范围，所以同一rail上两个不同但重叠的`RailIntervalResource`可以共存：释放其中一个后，
另一个仍留在claim中继续保护其覆盖范围。

令`K`为本次释放的身份数、`P`为当前pending mutation/append记录数、`H`为该claim尚持有的身份数。
内部保序集合使shrink的平均成本为`O(K+P)`，不再随`H`重建footprint；`P`固定时，`H`个资源逐项释放的
累计平均成本为`O(H)`。显式诊断快照仍需`O(H)`复制。当前按owner查找pending记录仍线性扫描`P`，这是
单独的索引优化点，不能把整个registry的所有操作都称为常数时间。哈希集合依赖资源身份稳定的`equals/hashCode`；
物理冲突检测仍按`overlaps`扫描持有者资源，未在本次改变。

内部`LiveClaimTailRelease`只拥有当前已提交资源队列的释放状态。它保留registry、platform owner identity、
完全匹配的`InfrastructureOwner`、一次校验的有序queue及其head index、已接受的最大authoritative tail route
progress。每次advance从当前head只检查连续eligible prefix；非空prefix先提交给`shrinkClaim`，只有remaining非空得到
`REPLACED`、remaining为空得到`RELEASED`后，才同时推进最大进度与head index，不重新复制/校验remaining queue。仅进度有效但队首未
清除时可以单独推进最大进度。非有限/负数、相对已接受值倒退，或任何非预期registry结果都会锁存同一个
fail-closed结果，本地queue/progress保持原值，后续调用不重试shrink。正常failure result保证claim未变；若
registry违反预期返回另一种successful status，则可能已经只减了claim，owner仍必须保留本地状态并Stall，不能
把这一invariant mismatch当作成功；`FailedClosed.claimMayHaveChanged`会明确标记这种已返回successful
status的诊断情况，而普通failure为false。全部释放后进入幂等completed状态，后续调用不再访问registry。
它仍接受并保存后续调用方已投影好的authoritative route progress，供已清空队列之后的安全suffix安装判断；
它不拥有live grant、cursor或pending extension。

live-grant owner可在registry delta已经finalize且全部设备已经READY后，向同一个
`LiveClaimTailRelease`安装一组committed suffix entries。安装只做本地immutable queue校验：suffix自身非空、
无重复且有序；首项必须严格位于历史已安装队尾之后；每个tail-clear boundary必须严格晚于当前已接受的最大
tail progress。全部校验在修改本地状态前完成。原队列仍有资源时只合并尚未通过head的旧队尾与新suffix；原队列已空时重新
建立queue。两种情况均不重置最大progress或外层frozen-route cursor。该方法不访问registry、不保存reservation
handle，也不执行preparation、finalize或rollback。

通用准入的延长还可能要求把**已经持有**的附加资源保持到更远的车尾位置；这种身份不能再作为registry delta重复申请，也不能套用上面的“新suffix全在旧队尾之后”检查。`InitialRouteAdmissionService`只在申请延长时对尚在队列中的同一identity暂缓释放，并在同一队列内重排剩余项；其他旧资源仍按队首释放。设备`READY`且最新观测复核成功后，服务先提交新增delta，再把旧identity的新保持边界与新增entries一起安装，最后扩大原grant。`ABORTED_SAFE`先撤销delta，再恢复旧边界并按已接受的车尾进度释放到期项。只有这几个延长生命周期边界复制/排序剩余队列；普通车尾推进仍从当前head扫描，不重建队列。若旧队列已空，live grant继续保留cursor，同一grant的下一段用新claim acquire，而不是对不存在的claim执行append。

`CompiledRouteAdmission`现保留一次性从已验证冻结路线编译的`CompiledRouteProgressPath`。它只按路线
顺序保存cursor所需的rail、facing、canonical AB边界和累计route distance，不建立按rail反向查询的第二份
索引。每次初次准入的冻结路线必须从同epoch observation的物理车尾开始；Basic只验证首个occurrence
的route start并把cursor初始化为`0`，caller不提供初始route coordinate或occurrence index。之后cursor先匹配
当前occurrence，只在不匹配时向后顺序扫描并
选择第一个匹配项，从不回退。匹配结果低于已接受route distance或未匹配到当前位置都会锁存fail closed并
保留原state。

`ProtectedRouteClaimService`内部按logical owner ID持有live-grant record，包含精确platform/logical owner、train与
`ProtectedRouteTailRelease`。成功acquire在READY/finalize后安装，UNCHANGED保留原项。service显式消费后续
`RailwayObservation`，一次建立train ID lookup并按owner稳定顺序驱动控制器；controller仍校验stamp/train identity，
把tail投影为候选进度，registry shrink成功后才提交cursor。Completed只表示当前资源队列为空：record继续消费后续
observation并推进cursor但不访问registry。只有精确owner的显式release/end删除record；registry已无资源时必须由该
record证明queue为空才把`NOT_FOUND`解释为`RELEASED`。错误owner或active queue下的`NOT_FOUND`不能被掩盖。

当前仍没有自动publication订阅、持久化/reload恢复、provider-disable takeover或clearance margin。内部
same-route suffix extension已经沿同一冻结路线验证并原子追加；公开SimpleATO调用接口仍未冻结。route plan变化
在安全运营边界结束旧claim后重新准入，不通过whole-claim replace实现。

目标registry为同一冻结路线的rolling extension提供internal append-only mutation，而不是复用通用replace。调用方提交
完全匹配的platform owner、logical owner、现有grant identity以及由Basic compiler生成的非空、无重复delta identities；
不得提交whole remaining footprint。registry原子验证claim存在、owner精确匹配、没有另一个pending extension，且delta
资源有效、未被本claim按exact identity持有、与其他claim不冲突。全部通过才建立只属于当前grant的delta reservation；
任一失败都不改变existing held set。

pending handle属于resource-acquisition/live-grant owner，只允许提交或撤销自己的delta reservation，不能删除、替换或恢复existing resources。prefix shrink仍可同时
删除旧claim中的准确身份；它不会触碰delta reservation。准备成功后finalize把delta提交到同一claim并保持logical owner
revision；安全失败只撤销delta。若无法证明设备已安全中止，或handle无法只撤销自己的delta，则保留最安全reservation并
由上层Stall。如果pending期间旧prefix已经全部清除，grant record仍由delta reservation保活，不构造空footprint；安全失败
撤销最后一份reservation后grant进入resource-idle但仍保留route cursor，直到显式end。pending delta不进入
`LiveClaimTailRelease`；finalize后live-grant owner才安装对应的已提交release entries。整个过程不增加caller plan revision，也不使用generic claim replace伪造一次新owner。

registry的内部shrink结果会额外证明“mutation后仅剩当前owner的pending append delta”。因此旧committed queue
释放最后一个资源时，即使通用mutation status因delta仍在而为`REPLACED`，`LiveClaimTailRelease`也只在该证据为真时
把自己的旧队列标记为空；普通`REPLACED`仍是fail-closed invariant mismatch。pending期间release不暂停，delta也不进入
release queue。设备`READY`后，live-grant owner先重新校验suffix仍晚于当前tail progress，再finalize reservation并安装
release entries；`ABORTED_SAFE`只rollback该delta。

| append条件 | registry结果 | existing claim / delta |
| --- | --- | --- |
| owner/grant不匹配、已有pending extension、空或重复delta | typed invalid/busy | existing不变；无delta |
| delta与其他claim冲突或provider/topology失效 | typed conflict/stale/provider failure | existing不变；无delta |
| reserve delta成功 | pending handle | existing继续持有；delta reservation不可授权 |
| pending期间prefix shrink | normal shrink；最后一个旧资源以pending-delta-only证据完成旧队列 | 只删除已清除existing identities；delta不变 |
| finalize成功 | appended | 同一owner/revision持有existing + delta |
| preparation安全失败并撤销成功 | aborted | 只删除delta；existing保留当时尚未由车尾释放的部分 |
| unsafe abort或撤销invariant失败 | stalled/retained | 保留能证明安全的existing + delta reservation |

具体internal方法名和result类名尚未冻结；上述原子性、同owner revision、只处理delta以及prefix release不暂停是实现合同。

## 项目内正确示例

Allocator先检查所有owner conflict，再apply：

```kotlin
resourceOwners[requirement.from]?.let { currentOwner ->
    return RouteSectionAllocationResult.Conflict(requirement.from, currentOwner)
}
// Only after every requirement is resolved:
resolved.forEach { it.from.selectNextRail(it.selectedNext) }
```

来源：`RouteSectionAllocator.kt`。这防止第二个turnout冲突时第一个已经被切换。

## 禁止实现

- 边查边切、失败留partial；
- preempt已有resource；
- 用一个虚构的rail interval表达跨rail范围，或遗漏共享conflict node；
- add-on直接申请raw live claim绕过route validation和mandatory physical footprint；
- caller提交可替代物理底线的raw footprint/列车长度，或Basic替caller选择路径/执行保护区域策略；
- topology revision只在validation前检查一次，允许检查期间的structural edit穿过；
- release时复位branch；
- 只看head或direction；
- worker调用allocator；
- conflict直接Stall；
- manual takeover立即清locks或FULL_MANUAL自动恢复ATO。

## 必需测试与验证

- `RouteSectionAllocatorTest.kt`：完整batch在首个写入前检查conflict/physical lock、idempotent、release不reset；不构造同步内存selection的虚构apply failure。
- `TimetableDispatcherTest.kt`：protection-point stop/retry、stable priority、short claim tail release、sameTick Stall停止后续allocation、manual modes。
- `TimetableStationCallDispatcherTest.kt`：berth/old mission tail release。
- `InfrastructureLeaseRegistryTest.kt`：shared leases、exclusive claims、held edit permit、owner revision、双重topology check、provider failure和无partial mutation。
- `InfrastructureLeaseRegistryTest.kt`：claim shrink按准确身份原子删除、保留剩余顺序与owner revision，支持重叠interval，并在missing/wrong owner/pending/closed时保持不变。
- `LiveClaimTailReleaseTest.kt`：先shrink后出队、单调进度、重叠identity独立释放、unexpected result锁存
  fail closed、全部释放后的幂等completed、active queue追加、empty queue恢复和非法suffix原子拒绝。
- registry append tests：只保留delta、与其他claim冲突时existing不变、owner/grant/pending校验、finalize保持logical
  owner revision、安全失败只撤销delta、pending期间prefix shrink继续释放旧资源、unsafe abort保留reservation。
- public consumer fixture：能构造多rail footprint并申请lease，但不能导入raw `InfrastructureClaimRequest`。

```bash
./gradlew :Basic:test --tests '*RouteSectionAllocatorTest' --tests '*TimetableDispatcherTest' --tests '*TimetableStationCallDispatcherTest'
```
