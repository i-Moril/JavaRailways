# Basic resource allocation 契约

## 适用场景与用途

触发：修改turnout/route section/berth ownership、movement authority、release detector、applied-plan lease、live claim、edit permit或manual control resource semantics。

用途：一个world的allocator先预校验完整resource set再原子提交；conflict不preempt，失败不留下partial ownership，release由full physical occupancy/tail驱动而非run状态或head位置。

## Owner、keys与结果

`RouteSectionAllocator(world)`拥有Basic运营lock；physical `RailView.nextActiveRail`仍由Core topology持有。调用在successful current physics epoch后。

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
| all prechecks通过 | apply all selections→record window | 一次确认 |
| apply中途异常 | 尝试恢复全部previous selections→`Invalid` | 不记录window |
| release(key) | 只释放ownership，幂等Boolean | 不自动reset turnout |

Dispatcher按absolute timetable priority tick + timetable/service/cycle stable order调用；allocator自身不排序。conflict/physical lock按world retry ticks，movement authority截在protection point，不Stall；identity/invariant disappearance才world Stall。

Release必须由`RailIntervalReleaseDetector`观察`BEFORE→OCCUPIED→RELEASED`且runtime head超过clear progress。mission replacement保留old runtime至tail clear。Berth/Depot同原则。

Manual：`MANUAL_ROUTED`仍分配/验证/release；`FULL_MANUAL`不申请new route但old resources tail-release；release control后`REPLAN_REQUIRED`，仅在无old locks/berth、stopped、explicit origin时replan。

## Shared infrastructure lease 与 edit guard

`InfrastructureLeaseRegistry`是world-local原子registry，现阶段与尚未迁移的`RouteSectionAllocator`并存；不得把两者并存误写成进路资源统一已经完成。它区分三类持有：

- applied-plan lease：共享、长生命周期，可彼此重叠，不预留turnout方向，也不阻止正常live claim；
- live runtime claim：同一resource上互斥；受保护进路runtime使用acquire、append、shrink/release，不提供整份claim replace；add-on service只暴露read-only diagnostics；
- edit permit：独占并跨完整mutation transaction持有，与overlap的lease、claim和其他permit冲突。

`InfrastructureOwner(ownerId, revision)`稳定标识logical owner与其revision；platform plugin token只用于lifecycle ownership，不进入持久化identity或公开diagnostic。claim acquire/append/shrink/release必须匹配platform owner与expected logical owner；内部footprint变化不伪造新的caller plan revision。plugin disable释放该plugin直接拥有的lease/permit；受保护进路的live claim由host token持有，requester disable后按[protected route admission](./protected-route-admission.md)转入fail-closed recovery，不能在列车清除前释放。world unload与service close必须先由composition layer处理相同的recovery/restore语义。

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

turnout的方向无关资源身份由[保护区域与进路准入](./protected-route-admission.md)唯一规定；这里仅要求
footprint使用其`StableInfrastructureResource`，不得改用revision-local `TopologyNodeId`。diamond资源
由独立binding提供。

## 受保护进路解析与准入

完整region cut、目标签名、错误矩阵、transaction伪代码和release/lifecycle合同见[protected route admission](./protected-route-admission.md)。本节只保留与通用registry直接相关的约束。

`RouteResourceRequest`描述一个自身携带topology revision的冻结route plan、列车身份、同一观察stamp、当前进度和期望authority boundary；request本身不再复制第二个topology revision。SimpleATO只提供有序traversal；Basic从authoritative observation读取列车实际占用，在一次route validation中收集唯一的claim资源集合并满足所有clearance obligation。保护区域入口只增加自己的路径资源及“尾部必须越过出口”的最小净空义务，不读取下一个区域；若义务范围内遇到另一保护区域或其他contributor，同一次validation继续把它的资源加入同一原子claim。

request不携带raw `InfrastructureFootprint`、受控资源清单或可信列车长度。SimpleATO从Core的authoritative `TopologyState`选择路径并提交有序edge traversal；Basic validator证明它在同一revision内连续、方向正确且每个continuation合法，再通过拓扑节点与绑定同一topology root的route-bound resource catalog，把rail intervals、turnouts、fixed crossings与其他shared conflicts加入唯一资源集合。physical occupancy geometry只用于检查，不形成第二个footprint。非法或断裂traversal返回`INVALID_ROUTE`；受控资源没有有效保护覆盖返回`PROTECTION_REQUIRED`。验证前后均核对topology root，任何失败都不改变registry。

`ProtectedRouteClaimService`现为host-side admission seam，并未替换旧`RouteSectionAllocator`调用者。service消费Basic内部route validation得到的唯一资源集合，在registry mutation前用同一观测的occupancy index查询已编译geometry，然后原子claim并启动同一admission中的完整有序`InfrastructurePreparationRequirement`批次。claim只建立逻辑排他性，不等于设备已经物理就绪；通用main-thread batch ticket报告`PREPARING/READY/ABORTED_SAFE/UNSAFE`，pending admission handle持有claim mutation，只有`READY`才完成claim，只有资源handler确认`ABORTED_SAFE`才删除本次新claim。`abort()`不规定恢复旧状态或默认状态。当前live actuator只处理turnout requirement：它在任何side effect前解析并验证完整的双向selection批次，然后即时写入并返回`READY`；当前内存选择没有虚构的运行时失败或旧selection恢复路径。执行未完成时不会返回成功claim结果，也不存在grant；任一snapshot/actuator异常或`UNSAFE`均保留当前claim reservation并Stall world。当前仍缺通用contributor closure、grant/release/recovery和调用方迁移，因此不得声称所有自动/`MANUAL_ROUTED`进路已强制经过保护区域。后续迁移必须删除可绕过该validation的automatic overload。

同一个`CompiledRouteAdmission`还携带validation同期生成的有序tail-release entries：唯一footprint中的每个resource恰好出现一次，entry只附加已经完整编译的车尾清除路线阈值，不复制occupancy geometry。immutable queue helper从队首返回连续eligible prefix但不自行pop；world-local claim service持有的释放控制器必须先完成registry原子shrink再丢弃该前缀。当前过渡compiler只使用最后claimed/conflict route extent，明确的clearance-margin来源仍未冻结；service已有显式observation驱动入口，但尚未自动接入publication。

Registry现已提供internal同步、只减不增的live-claim shrink primitive。调用方提交platform owner、完全匹配的
`InfrastructureOwner`以及非空、无重复的**准确资源身份**集合；它不是重建一份remaining footprint。registry在
同一把锁内拒绝closed、missing claim、owner不匹配和pending admission，证明每个待释放身份当前确实被该
claim持有，然后按原footprint顺序过滤这些身份。若仍有资源，以相同platform owner和相同logical owner
revision替换`Held`并返回`REPLACED`；若为空则删除claim并返回`RELEASED`。任一失败都不产生partial mutation。

Shrink不读取topology revision、不调用resource provider、不做overlap blocker查询，也不修改infrastructure
preparation；删除既有身份不可能产生新冲突。它按`InfrastructureResource.equals`匹配身份，而不是按
`overlaps`匹配范围，所以同一rail上两个不同但重叠的`RailIntervalResource`可以共存：释放其中一个后，
另一个仍留在claim中继续保护其覆盖范围。

内部`LiveClaimTailRelease`只拥有当前已提交资源队列的释放状态。它保留registry、platform owner identity、
完全匹配的`InfrastructureOwner`、immutable remaining queue suffix和已接受的最大authoritative tail route
progress。每次advance只检查连续eligible prefix；非空prefix先提交给`shrinkClaim`，只有remaining非空得到
`REPLACED`、remaining为空得到`RELEASED`后，才同时推进最大进度并替换本地queue suffix。仅进度有效但队首未
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
tail progress。全部校验在修改本地状态前完成。原队列仍有资源时合并两个immutable queue；原队列已空时重新
建立queue。两种情况均不重置最大progress或外层frozen-route cursor。该方法不访问registry、不保存reservation
handle，也不执行preparation、finalize或rollback。

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

当前仍没有自动publication订阅、持久化/reload恢复、provider-disable takeover、clearance margin或same-route
suffix extension。未来extension只沿同一冻结路线验证并原子追加；route plan变化在安全运营边界结束旧claim后
重新准入，不通过whole-claim replace实现。

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

当前尚未完成pending append与尾部释放的service级协调。尤其当旧committed queue的最后一个资源准备释放、
registry中仍有pending delta reservation时，`shrinkClaim`可能返回`REPLACED`；释放控制器不能在没有可验证
pending证据时把它无条件当作正常完成，否则会掩盖registry中真正存在的未描述资源。后续集成必须明确决定
finalize/install时序或提供可验证的协调证据；本批次不暂停释放，也不放宽严格registry结果校验。

| append条件 | registry结果 | existing claim / delta |
| --- | --- | --- |
| owner/grant不匹配、已有pending extension、空或重复delta | typed invalid/busy | existing不变；无delta |
| delta与其他claim冲突或provider/topology失效 | typed conflict/stale/provider failure | existing不变；无delta |
| reserve delta成功 | pending handle | existing继续持有；delta reservation不可授权 |
| pending期间prefix shrink | normal shrink result；最后一个旧资源的协调仍待service集成 | 只删除已清除existing identities；delta不变 |
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
- add-on直接申请raw live claim绕过protected route validation；
- caller提交自选raw footprint/列车长度、Basic替caller选择路径、保护区域自行查询下一区域，或让列车持有一个region后才发现必须申请下一个region；
- topology revision只在validation前检查一次，允许检查期间的structural edit穿过；
- release时复位branch；
- 只看head或direction；
- worker调用allocator；
- conflict直接Stall；
- manual takeover立即清locks或FULL_MANUAL自动恢复ATO。

## 必需测试与验证

- `RouteSectionAllocatorTest.kt`：conflict全不变、physical lock、idempotent、release不reset、apply rollback。
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
