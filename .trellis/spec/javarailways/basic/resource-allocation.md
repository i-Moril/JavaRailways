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
- live runtime claim：同一resource上互斥；raw acquire/replace/release只供受保护进路实现，Bukkit add-on service仅暴露read-only diagnostics；
- edit permit：独占并跨完整mutation transaction持有，与overlap的lease、claim和其他permit冲突。

`InfrastructureOwner(ownerId, revision)`稳定标识logical owner与其revision；platform plugin token只用于lifecycle ownership，不进入持久化identity或公开diagnostic。acquire/replace/release必须匹配platform owner与expected revision，replace保持ownerId并严格增加revision。plugin disable释放该plugin直接拥有的lease/permit；受保护进路的live claim由host token持有，requester disable后按[protected route admission](./protected-route-admission.md)转入fail-closed recovery，不能在列车清除前释放。world unload与service close必须先由composition layer处理相同的recovery/restore语义。

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

`ProtectedRouteClaimService`现为host-side admission seam，并未替换旧`RouteSectionAllocator`调用者。service消费Basic内部route validation得到的唯一资源集合，在registry mutation前用同一观测的occupancy index查询已编译geometry，然后原子claim并启动同一admission中的完整有序`InfrastructurePreparationRequirement`批次。claim只建立逻辑排他性，不等于设备已经物理就绪；通用main-thread batch ticket报告`PREPARING/RESTORING/READY/RESTORED/UNSAFE`，pending admission handle持有claim mutation token，只有`READY`才完成claim，只有`RESTORED`才恢复精确claim前状态。当前live actuator只处理turnout requirement，但会在任何side effect前拒绝未知类型。执行未完成时不会返回成功claim结果，也不存在grant；任一异常、ticket identity/state违约或`UNSAFE`均保留最安全的新旧资源保留并Stall world。当前仍缺通用contributor closure、grant/release/recovery和调用方迁移，因此不得声称所有自动/`MANUAL_ROUTED`进路已强制经过保护区域。后续迁移必须删除可绕过该validation的automatic overload。

同一个`CompiledRouteAdmission`现在还携带validation同期生成的有序tail-release entries：唯一footprint中的每个resource恰好出现一次，entry只附加已经完整编译的车尾清除路线阈值，不复制occupancy geometry。immutable queue helper从队首返回连续eligible prefix但不自行pop；future grant owner必须先完成registry原子shrink再丢弃该前缀。当前过渡compiler只使用最后claimed/conflict route extent，明确的clearance-margin来源仍未冻结；它尚未调用registry replace/shrink，因此不能把当前状态描述成live claim已经自动尾部释放。

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
- public consumer fixture：能构造多rail footprint并申请lease，但不能导入raw `InfrastructureClaimRequest`。

```bash
./gradlew :Basic:test --tests '*RouteSectionAllocatorTest' --tests '*TimetableDispatcherTest' --tests '*TimetableStationCallDispatcherTest'
```
