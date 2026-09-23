# Basic 路线资源准入契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`TARGET`。当前 `ProtectedRouteClaimService`、保护区域 catalog/window 与部分编译器仍是迁移期实现；它们不能继续冻结为 Basic 的长期领域模型。
- **Implementation progress**：内部 `PhysicalRouteResourceCompiler` 已能从投影后的冻结路线和显式路线范围推导完整物理底线及准备、占用检查、释放元数据；`CompiledRouteAdmission.withAdditions` 已能合并经过资源所有者校验的附加项。`RouteResourceAdmissionEngine`把首次申请及同冻结路线后续申请的校验、delta claim、设备准备、grant更新和车尾释放串成Basic内部流程，并由集成测试覆盖。Bukkit生产composition现已用`RouteResourceAdmissionRuntime`把它绑定到每世界唯一registry、Core topology/live turnout actuator、认证addition validator和同epoch observation tail-release输入；物理Coordinator只在这些owner attach完成后启动，world卸载成对关闭。外部插件现可从现有`InfrastructureLeaseService`取得绑定插件身份和确切world runtime的`RouteResourceAdmissionService`；request、pending/terminal handle inspection及grant end均使用同一认证身份，旧binding不会在同UUID world reload后复活，plugin disable也不会删除host-owned claim。当前route-bound catalog只发布完整空集，等待diamond detector提供真实bindings；Dispatcher/SimpleATO caller与grant constraint仍未接通。`ProtectedRouteRequestValidator`仍作为保护窗口策略适配器调用物理编译器；不能把已公开但尚无生产caller的runtime误称为列车已经使用新授权流程。
- **Owner**：Basic 拥有通用资源注册、冻结路线验证、不可省略的物理资源推导、原子 claim、设备准备、grant 生命周期与车尾释放。
- **Caller**：SimpleATO提供冻结路线与列车/观察身份；不需要信号策略时由SimpleATO提交无addition的intent，需要插件策略时由该信号插件的认证准入协调器提交同一路线和自己的addition。SimpleATO不冒充插件来源。
- **Non-goals**：Basic 不定义保护区域、固定闭塞、信号显示、联锁表、侧面防护规则、最远瞭望策略或国家信号制式。

触发：修改自动进路申请、route-bound resource、turnout/diamond claim、外部资源注册、设备准备、rolling extension、grant 或车尾释放。

## 2. 模块工作方式

一次正常申请只有一份冻结路线和一份最终 claim：

```text
SimpleATO selects one frozen route and desired authority boundary
  -> a signalling plugin may attach additional registered resource requirements
  -> Basic validates route/topology/observation coherence
  -> Basic derives the non-removable physical resource floor from the route
  -> Basic validates and unions caller additions with that floor
  -> Basic checks authoritative occupancy and atomically claims the union
  -> registered resource handlers prepare the claimed resources
  -> Basic retains one frozen-route state with a tail cursor and nullable committed grant
  -> READY publishes or grows that grant; denial publishes no wider authority
  -> the same cursor and committed release queue shrink the claim behind the train tail
```

Basic proves physical consistency; the signalling plugin decides signalling policy. A protection-region plugin may inspect its own definition and the submitted route, choose an authority boundary beyond the region exit, and add flank-protection or approach resources. Another plugin may implement route tables and sectional release without defining a `ProtectionRegion` at all.

## 3. 内部机制与关键算法

### 3.1 不可删除的物理资源底线

Basic projects the requested route prefix/suffix against the exact retained `TopologyState` root. In one route-order pass it derives:

- one canonical AB rail interval for every traversed physical rail slice;
- one stable, direction-independent resource for every traversed turnout;
- every route-bound shared physical conflict, such as an at-grade diamond, matched by the catalog bound to the same topology root;
- route-ordered turnout selections and physical occupancy query geometry from the same projection.

This derived set is the **mandatory physical footprint**. The request cannot replace it, remove members from it, or supply an alternative turnout/diamond identity. Basic may merge adjacent intervals of the same rail but must not infer a cross-rail global interval.

### 3.2 插件附加资源

A signalling plugin may add stable resources that it owns or is allowed to request, for example a flank turnout, an approach-lock resource or a whole-block token. These additions are data, not executable callbacks inside Basic's route scan. Basic validates each identity through the resource registry and checks that any requested preparation capability is registered for that resource.

The plugin remains responsible for computing why the addition is needed and how far the desired authority must extend. Basic does not call a chain of region/provider resolvers, ask one region to inspect its successor, or synthesize protection coverage. The final footprint is:

```text
mandatoryPhysicalFootprint(frozenRoute, requestedRange)
  union validatedAdditionalResources
```

The union is unique by exact resource identity and is committed once. Occupancy query geometry, preparation requirements and release entries are metadata for validation/operation/lifecycle; none is a second claim footprint.

当前纯合并步骤从已编译的物理footprint复制资源身份与释放元数据，逐项调用由world-local资源owner提供的无副作用校验函数；任一项失败直接返回typed rejection，不触碰registry。新身份追加到唯一footprint，附加准备descriptor保留为待provider派发的要求；若身份已在物理底线中，只取原释放边界与附加边界的较大值，禁止附加descriptor覆盖物理准备。最后按车尾清除位置排序唯一释放队列。时间与临时空间均随本次footprint及additions线性增长（排序释放队列除外）；空additions直接复用原结果。此步骤不验证occupancy、不claim、不让当前仅支持即时道岔的actuator执行新增准备，后续准入服务必须在原子claim前接入真实registration validation并在claim后接入对应provider。

当前Basic内部申请由`RouteResourceAdmissionEngine`拥有：读取一份当前observation和同revision的拓扑root。没有route state时，投影调用方冻结路线并编译`[0, desiredAuthority]`物理范围，先确认路线从权威车尾开始；已有route state时，核对申请身份与路线，只编译已提交authority至新boundary的suffix。两者共用后续资源校验、占用查询、registry reservation、设备准备与结果流程。宿主构造world owner时绑定附加资源校验；请求本身不能替换该函数。registry mutation使用宿主持有的token，不使用发起申请的插件token；插件退出不能直接删除列车仍持有的资源。准备票据未READY时只返回同一pending handle，不扩大grant；READY时重新读取最新完整observation，要求拓扑仍为原root、物理车尾仍符合冻结路线，且该观测中的申请范围没有其他列车。正常跨越physics epoch时，grant与释放控制器绑定通过复核的新stamp；复核失败时不能发布旧授权，也不能把已准备的设备擅自视为安全中止，而是保留claim并stall。资源handler明确报告安全中止时才撤销本次新增claim。整个前claim阶段无registry mutation；附加资源提供者异常按`INVALID_ADDITION`拒绝。该内部owner仍不能单独注册为公开`RouteResourceAdmissionService`；Bukkit的认证provider校验及生产catalog绑定仍待后续批次。

### 3.3 申请、准备和释放

Basic first completes route/resource/occupancy validation, then atomically claims the final footprint. Claim ownership prevents conflicting claims but does not mean a movable resource is ready. Registered resource handlers prepare only already-claimed resources and report `PREPARING`, `READY`, `ABORTED_SAFE` or `UNSAFE` according to the generic preparation contract. A grant is published only after all requirements are `READY`.

The current route state owns the frozen route and one monotonic tail cursor, independently of its nullable committed grant. Basic creates this state only once the first validated request has successfully reserved resources; it is not a per-train object allocated for every idle train. The cursor is initialized from the authoritative train tail and reused when the first `READY` creates the committed release queue. If this first attempt safely aborts and no grant or reservation remains, Basic discards the unused route state. After a grant exists, the route state remains until explicit safe route end even when its resource queue becomes empty.

Committed resources enter one route-ordered release queue attached to that route state. Each observation advances the same monotonic frozen-route cursor from the authoritative train tail. Basic atomically removes only the eligible queue prefix, then advances a head index in the retained queue; normal releases do not copy and revalidate the entire remaining queue. A signalling plugin may supply a stricter release boundary for its added resource; it cannot release a mandatory physical resource earlier than Basic's physical clear boundary.

The same request operation also grows an existing grant along that route. Basic compiles only the farther suffix, derives its physical floor, and unions additions for that request. Exact identities still held by the grant are not claimed or prepared again. If an addition asks to hold one of them longer, Basic validates that its new tail-clear boundary is no earlier than the committed one, then temporarily places that identity after ordinary eligible entries in the **same** release queue while the new delta is pending. Other old resources remain releasable by the tail. `READY` checks the latest coherent observation and suffix occupancy, commits only the new delta, then publishes the later hold boundaries and authority together; `ABORTED_SAFE` removes only the delta, restores old boundaries and immediately releases any restored entry already passed by the accepted tail. Unsafe or unprovable completion retains the safest reservation and stalls. If the old queue and registry claim are already empty, the route state keeps its cursor and acquires the suffix under the same grant identity. A changed route is a replan at a safe operational boundary, not a whole-claim replacement.

## 4. 冻结的公开请求合同

下一实现批次必须在 `work.art1st.javarailways.basic.api.route` 提供下列等价的不可变 value
contract；允许调整 Kotlin 文件拆分，但不得改变字段语义、增加 raw footprint overload，或把 provider
callback 塞进 intent。`RouteSnapshot`、`RailwayStateStamp`、`InfrastructureOwner` 与
`StableInfrastructureResource` 复用现有 Basic API value，不建立平行模型。

```kotlin
data class RouteResourceIntent(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: RouteSnapshot,
    val desiredAuthority: RouteBoundary,
    val additionalResources: List<AdditionalResourceRequirement>
)

data class RouteBoundary(
    // metres from the first point of route.segments, increasing in traversal order
    val distanceFromRouteStartMeters: Double
)

data class AdditionalResourceRequirement(
    val resource: StableInfrastructureResource,
    val releaseAfterTailPasses: RouteBoundary,
    val preparation: ResourcePreparationDescriptor? = null
)

data class ResourcePreparationDescriptor(
    val kindId: String,
    val arguments: Map<String, PreparationScalar>
)

sealed interface PreparationScalar {
    data class Text(val value: String) : PreparationScalar
    data class Integer(val value: Long) : PreparationScalar
    data class Decimal(val value: Double) : PreparationScalar
    data class Flag(val value: Boolean) : PreparationScalar
}

interface RouteResourceAdmissionService {
    @MainThread
    fun request(intent: RouteResourceIntent): RouteAdmissionResult

    @MainThread
    fun progress(handle: RouteAdmissionHandle): RouteAdmissionResult

    @MainThread
    fun endGrant(grantId: RouteGrantId): Boolean
}

enum class RouteAdmissionFailureCode {
    WORLD_UNAVAILABLE, OWNER_MISMATCH, STALE_OBSERVATION, TRAIN_NOT_FOUND,
    STALE_TOPOLOGY, INVALID_ROUTE, INVALID_BOUNDARY, INVALID_INFRASTRUCTURE,
    INVALID_ADDITION, INVALID_PREPARATION, PHYSICALLY_OCCUPIED, RESOURCE_CONFLICT,
    ROUTE_STATE_MISMATCH, REQUEST_BUSY, PREPARATION_ABORTED_SAFE, WORLD_STALLED
}
```

`requestId` 是 namespaced caller correlation ID，没有 retry/persistence 语义。`owner` 是logical
route/grant owner及其non-negative plan revision；Bukkit/service binding在字段外提供认证过的platform owner，
因此修改value不能冒充另一个插件。`trainId`是exact observation内的nonblank Core stable ID。stamp world必须等于world-local
service；physics epoch必须仍能解析到准入所用immutable observation；stamp topology revision必须等于
`route.topologyRevision`。request不复制第二个expected-topology字段。

`RouteBoundary`只在包含它的intent及其唯一`route`内解释，不重复保存route identity/revision。有限且非负的
distance从`RouteSnapshot.segments`第一个点起，以冻结路线行进方向递增，单位为实际米；即使某一segment沿BA
行驶，它也不是canonical rail AB坐标。`desiredAuthority`不得超过`route.totalLengthMeters`。每一个
`releaseAfterTailPasses`都必须显式提供，并落在`[0, desiredAuthority]`。它表示命名资源
至少保持到权威**车尾**加Basic提供的非负安全余量通过该路线位置。不存在隐式/default释放点。

caller不提交raw rail interval或turnout/diamond selection。`additionalResources` defensive copy，并且必须按
exact `(typeId, resourceId)`唯一。若addition identity也出现在Basic物理底线，它不形成第二份资源，只能把该资源
的释放边界向后延长；不得替换identity、缩短Basic物理clear boundary或覆盖Basic推导的turnout/物理准备target。
非路线/flank资源可以带一个optional provider-owned `preparation`；缺省descriptor表示只claim、不准备。

`ResourcePreparationDescriptor`是小型runtime request value。`kindId`为namespaced stable identifier；argument
key非空，map defensive copy并按key稳定排序，decimal必须finite，value只能是上面四种scalar。Basic将它作为
opaque data，只派发给拥有该resource type且声明该preparation kind的registration。descriptor不含mutable
train/world、callback、JVM class name或serialized JSON。它**不是持久化schema**，不产生兼容或restore承诺；
以后若持久化资源定义或in-flight recovery，必须另审独立schema boundary。

Resource registration是namespaced、world-local并绑定认证platform owner。registration定义stable identity、
authenticated intent caller与preparation descriptor validation、可选preparation handler和lifecycle ownership；
它不允许直接修改Basic registry。请求者不必拥有resource type：例如JapaneseSignals可以请求Basic拥有的侧面防护
道岔，但Basic的turnout registration必须按请求者身份、resource identity与descriptor明确接受，否则返回
`INVALID_ADDITION`或`INVALID_PREPARATION`。

当前Bukkit适配器复用已有的唯一`typeId → provider`注册表，不另外建立进路资源catalog。注册表跨world保存
provider，但每次校验都指向已注册的确切world ID；这是迁移期实现，Basic目标中的world-local准入服务仍未完成。
`exists(worldId, resourceId)`只证明资源存在，绝不自动许可进路申请。provider必须显式实现进路校验，
由资源owner检查认证requester、world、resource ID与可选preparation descriptor，返回允许或
`INVALID_ADDITION`/`INVALID_PREPARATION`。Bukkit在simulation owner执行回调，不在注册表锁内调用provider；
回调后复查world/registration/owner仍有效，失效或异常一律拒绝且不修改claim。当前只形成待准入服务使用的
校验入口，尚未把它误称为已经完成的Basic授权或设备准备。由于此注册尚无provider准备执行器，即使owner
同意，带非空preparation descriptor的附加项目前也返回`INVALID_PREPARATION`；必须先接入真正的准备handler
并证明其生命周期，才能允许这类请求进入后续claim。

### 4.1 Result与准备协议

`RouteAdmissionResult`是`request`和`progress`共用的sealed value result，只有三种顶层结果：

- `Granted(RouteGrantSnapshot)`：union已原子claim，全部准备`READY`；snapshot含grant ID、owner、train、route
  identity/revision、observation stamp与committed authority boundary；topology revision仅从stamp读取，不复制第二字段；
- `Pending(RouteAdmissionHandle, RouteGrantSnapshot?)`：本次新增资源已保留但准备未完成；尚无committed grant时
  snapshot为null，已有grant时返回仍有效的旧snapshot；
- `Rejected(RouteAdmissionFailure)`：typed failure；前置校验拒绝不修改资源，准备安全中止只撤销本次新增资源，unsafe或无法证明的设备状态保留保护并Stall。

`endGrant`只结束已经由车尾释放清空、没有pending申请的route state；它不强制释放资源，也不是plugin disable
cleanup。未知、未清空或不属于该认证service binding的grant返回false。

`RouteAdmissionFailure`必须携带上面一个稳定code、非空诊断detail，以及仅在占用/冲突时出现的immutable blocker
identity列表；不得用exception class作为公开错误类型。`progress`只读取一个由同一service产生的opaque handle，
未完成时返回带**同一个handle**的`Pending`，完成后返回`Granted/Rejected`终态；调用方保留handle期间重复读取终态幂等，
不重放request或再执行provider。完成的handle不由world service永久强引用；调用方不再保留时，其终态缓存可被回收。handle是进程内runtime
能力，不持久化，也不模拟网络丢包/retry。

preparation provider只接收immutable `resource + descriptor + attemptId`，在world owner thread快速启动并返回
现有`PREPARING/READY/ABORTED_SAFE/UNSAFE` ticket语义。它不接收train、route、claim registry、Core world或其他
mutable Basic object；设备如何达到target、READY后是否自行恢复默认状态，由resource owner管理。Basic不在route
scan过程中调用provider，也不允许provider追加资源。所有addition已完成纯数据校验并形成final union后Basic才
claim；claim成功后才按确定顺序启动准备。

### 4.2 冻结的校验与提交顺序

```text
require service open and authenticated platform owner
resolve exact retained observationStamp; resolve trainId in that observation
require route topology == observation topology == current TopologyState root
validate route identity, segment values, connectivity, legal continuations and tail start
validate desiredAuthority and every explicit tail-release boundary in route metres
compile Basic physical floor + physical preparation/occupancy/release metadata once
validate unique additions against world-local registrations and preparation kinds
merge by exact identity; duplicate physical identities may only extend release
query authoritative same-epoch occupancy geometry
atomically claim the complete union
start ordered physical/provider preparation; publish grant only after all READY
```

本次新增资源的校验、编译与占用查询在reservation前不修改其claim或设备状态；已有grant的独立车尾释放仍可按已发布观测推进。registry conflict/claim failure不扩大claim、改变device selection或authority。
preparation只在claim之后开始，并遵循第6节的retained-claim failure规则。

## 5. 不变量、生命周期、线程与版本

- Route, observation and route-bound resource catalog must refer to the same topology root/revision.
- Delayed preparation may cross physics epochs. `READY` is not permission to publish the original observation's authority: recheck the current coherent frame and route-start/occupancy evidence, then bind the grant to that validated frame. If the safety evidence is lost after preparation, retain the claim and Stall rather than inventing a safe device abort. A Stall caused by another pending attempt also forbids later `READY` publication; retain those pending claims and notify the world Stall owner only on the first local failure.
- The mandatory physical footprint is always present in the committed union, regardless of caller additions.
- Validation/preparation failure never publishes a wider grant. Unsafe or unprovable device state retains the safest claim and stalls that world rather than releasing protection.
- Signal-plugin disable prevents new policy grants. Existing live grants and release metadata are host-owned until physical clearance or explicit safe recovery; plugin unload cannot drop them beneath a train.
- Runtime claims are not persistence schema. Stable resource definitions belong to the plugin or infrastructure owner that defines them; Basic persists only a boundary explicitly approved later.
- Registry mutation and resource preparation obey the world owner thread; physics observations crossing threads are immutable values.
- `RouteResourceAdmissionService` is world-local. Callers invoke it on the documented world/main owner only after the
  exact successful observation publication; provider handlers may return `Pending` but may not block that owner thread.
- Registration belongs to one authenticated platform-plugin owner. Disable/unregister rejects new additions immediately
  but does not delete host-owned live claims, release queues or copied descriptors. Provider disappearance during pending
  preparation is unprovable, so Basic retains the safest reservation and stalls the world.

## 6. 校验与错误矩阵

| Condition | Result | Mutation |
| --- | --- | --- |
| world unavailable/service closed | `WORLD_UNAVAILABLE` | none |
| authenticated owner and intent owner differ | `OWNER_MISMATCH` | none |
| exact observation epoch no longer retained | `STALE_OBSERVATION` | none |
| train absent from exact observation | `TRAIN_NOT_FOUND` | none |
| observation/topology/catalog mismatch | `STALE_TOPOLOGY` | none |
| disconnected or illegal frozen traversal | `INVALID_ROUTE` | none |
| route-boundary identity/range invalid | `INVALID_BOUNDARY` | none |
| mandatory physical resource cannot be resolved | `INVALID_INFRASTRUCTURE` | none |
| duplicate addition or unknown/unauthorized resource | `INVALID_ADDITION` | none |
| preparation kind/arguments rejected by owner registration | `INVALID_PREPARATION` | none |
| occupancy intersects compiled physical geometry | `PHYSICALLY_OCCUPIED` | none |
| final union conflicts with another claim | `RESOURCE_CONFLICT` | none |
| authenticated requester or logical owner differs from the active route state | `OWNER_MISMATCH` | no new reservation |
| train or frozen route differs from the active route state | `ROUTE_STATE_MISMATCH` | no new reservation |
| the active route state already has a pending request | `REQUEST_BUSY` | no new reservation |
| all validation passes | claim union, then prepare | one atomic claim |
| preparation still running | `Pending` | claim/reservation retained; no grant |
| preparation proves safe abort | typed denial | only this request/delta is removed |
| preparation becomes unsafe/unprovable | `WORLD_STALLED` | safest claim retained |

表中`none`指本次请求不新增reservation、设备切换或authority；已有grant按权威车尾进行的独立释放仍可发生。

There is no Basic `PROTECTION_REQUIRED`: missing protection-region coverage is a configuration/policy error reported by the concrete signalling plugin before it asks for a permissive route. Basic still denies any request whose physical route or resource union is invalid.

### 6.1 同一路线的后续请求

Basic按logical owner ID找到当前route state。若不存在，`request`建立第一次申请；若存在，必须与该state的
authenticated requester、logical owner及plan revision、stable train ID、`routeId`、`routeRevision`、topology revision
和ordered segments完全相同。它可以使用更新的successful physics epoch，但topology revision必须不变。已提交
grant存在时，新desired boundary必须严格晚于committed boundary且不超过route length；Basic只编译该suffix。
新增addition遵循相同校验；已经由该grant持有的identity只能单调延后release boundary，不能改变已committed
preparation descriptor。一个route state同一时刻只允许一个pending request。route/topology/plan改变必须在
安全运营边界replan，不得用新请求覆盖现有route state或whole-claim replacement。

Denied或pending后续请求保持旧committed grant/authority，旧tail release继续。只有delta原子reserve、全部delta
preparation READY、既有resource boundary单调更新、release entries追加成功后，Basic才发布更远committed
boundary。safe abort只删除新delta；unsafe或无法证明的abort保留最安全reservation并stall world。

已持有资源的保持时间不是新的claim身份：准备期间只暂缓它自己的释放，不能因此堵住队列里其他已清除的资源。若安全中止时车尾已经经过其旧释放点，恢复旧边界后必须立即按当前已接受的车尾进度释放它；否则旧资源会无故长期占用。待处理的新增delta始终不进入可释放队列，直到设备`READY`并完成registry提交。普通车尾释放不重排队列；只在延长申请/完成/安全中止这几个低频边界重排剩余队列。

## 7. 性能、复杂度与退化行为

Let `R` be projected route slices, `B` route-bound catalog hits, `A` additional resources, `H` resources in the final claim, `I` occupancy-query intervals, `C` bucket-selected swept-span candidates and `M` resources in all other claims/edit permits. Projection is linear in `R`; rail-indexed catalog lookup reads route occurrences plus binary-search/candidate scans, then merges/sorts hits. Physical interval and release ordering can cost `O((R+B+A) log(R+B+A))`. Occupancy comparison is worst-case `O(C×I)` after bucket selection. The current registry checks requested resources against other held resources pairwise, worst-case `O(H×M)`; it does not yet have a conflict index. These are request-time costs, not unchanged per-tick work. Basic must not invoke every installed signal plugin, scan all regions or execute arbitrary JS during this pass.

Observation dispatch builds one train-ID lookup only when at least one live grant exists. With grants, ordinary cursor checks start at the current occurrence and queue inspection starts at the current head; releasing `K` eligible resources costs `O(K)` locally. The registry keeps the live claim in one lock-protected ordered identity set: after validating the complete release batch, it deletes those `K` identities in place instead of rebuilding the other `H` resources. Its average shrink cost is `O(K+P)`, where `P` is the pending-record count currently scanned to find this owner. With fixed `P`, releasing `H` resources one by one is cumulatively average `O(H)`; this is not a bound on conflict lookup or explicit diagnostic snapshots, which still copy `O(H)` resources on read. Completed admission handles remain repeatable while held by callers without a world-lifetime terminal-result table.

一次延长申请可读取该grant尚未释放的`H`个身份，把新增资源与已持有资源按exact identity分开；只有开始延长、完成或安全中止时才对剩余队列排序，成本为`O(H log H)`，不是每tick成本。按logical owner ID直接定位live grant，不为每列车延长遍历所有live grants。正常无pending extension时，前述车尾逐项释放仍从队首推进，不因这一功能退化为每次重建整个队列。

Unchanged state performs no request compilation by itself. SimpleATO/resource inspection decides when a request is needed. Missing indexes, stale revisions or resource-handler failure fail closed; Basic must not fall back to a live-world full scan that silently grants authority.

## 8. Good / Base / Bad cases

- **Good**：a Japanese-signalling plugin requests a throat route, adds a flank turnout and holds it to a later boundary; Basic also inserts all traversed rails, turnouts and diamond conflicts before one atomic claim.
- **Good**：a different interlocking plugin submits route-table resources and approach locking without creating any Basic protection region.
- **Base**：a straight route has only mandatory rail intervals and no plugin additions.
- **Good**：a long claim releases one resource at a time; older diagnostic snapshots remain unchanged, while the registry removes only the named live identities.
- **Good**：the first request waits for a device with `committedGrant = null`; on `READY`, the route state installs its first grant and release queue using the already-established cursor.
- **Good**：after all committed resources have been tail-released, another request along the same frozen route reacquires a farther suffix without inventing a second route cursor.
- **Bad**：a plugin submits only its region ID and expects Basic to discover region policy or scan successor regions.
- **Bad**：an initial safe abort leaves an owner permanently busy with an empty route state, or a later safe abort deletes an earlier committed grant.
- **Bad**：each tail-clear event reconstructs an immutable footprint of every still-held resource; a long route then pays quadratic cumulative release work.
- **Bad**：a plugin omits a traversed turnout or supplies another identity; Basic still derives the canonical turnout resource.
- **Bad**：Basic treats plugin additions as authoritative replacement footprint or lets JS directly mutate a claim.

## 9. 必需测试与断言点

- Route projection covers AB/BA, zero/one/multiple turnouts, loops and route-bound diamonds with deterministic identities.
- Omitting every caller addition still produces the complete mandatory physical footprint.
- A caller cannot remove/replace a derived rail, turnout or diamond resource.
- Valid additions join the same atomic footprint; unknown/unauthorized additions and conflicts leave no partial claim.
- Occupancy geometry and preparation/release metadata never become alternate claim sets.
- Immediate and delayed preparation publish no grant before `READY`; unsafe failure retains the safest claim.
- First pending request has one route cursor and no committed grant; safe abort after successful rollback removes the unused route state, allowing a fresh request from a fresh observation.
- The same public `request(intent)` accepts a farther boundary on the identical frozen route, rejects a mismatched owner/train/route and a concurrent pending attempt, and keeps the same cursor and grant ID after successful growth.
- Delayed `READY` on a newer safe epoch rebinds the grant stamp; changed topology root, missing/moved train tail or newly occupied route publishes no grant and retains the claim under Stall.
- Two pending attempts cannot publish a grant after either one has stalled the world; the second claim stays retained and the Stall callback fires once.
- Tail release and same-route extension retain the existing cursor, release only a contiguous eligible prefix and never shorten plugin-specified holds.
- Pending extension may defer an already-held identity while other old resources release; READY commits the new hold and delta before widening the same grant, while safe abort restores the old hold and releases it if the accepted tail has passed. An emptied old queue can reacquire the next suffix without creating a new grant ID.
- Many sequential single-resource releases preserve the remaining claim order and owner; snapshots taken before shrink/append/rollback stay detached, while pending append forbids delta release and permits old-prefix shrink.
- Plugin disable/reload tests prove existing grants remain fail-closed and releasable without executing plugin policy again.

## 10. 迁移、未决事项与 Wrong / Correct

Migration keeps the implemented generic pieces—topology projection, canonical turnout identity, route-bound catalog, occupancy query, atomic registry, preparation, cursor/release queue and suffix extension—and removes protection-region assumptions from their public target. `ProtectionRegionDefinition`, region graph-cut/catalog/window resolution, fixed-block partitioning and `PROTECTION_REQUIRED` move to `JapaneseSignals` or a helper library owned by that plugin.

Still open for later API batches: optional administration UI/policy for restricting cross-plugin resource use,
persistence/recovery formats and plugin-local script identifiers. The runtime contract already permits a provider to validate
an authenticated request from another plugin, which is required for a signal plugin to request Basic-owned flank turnouts.
The signal plugin submits that intent under its own platform identity; SimpleATO supplies the frozen route and later consumes
the committed grant, but does not repackage the plugin's addition under a SimpleATO identity. `JapaneseSignals` does not change the
physical-floor invariant or turn the runtime descriptor into a persisted format.

```text
Wrong: the caller supplies the complete footprint and Basic trusts omissions
Correct: Basic derives the non-removable physical floor and only accepts additive resources

Wrong: Basic owns one universal protection-region/fixed-block model
Correct: each signalling plugin owns its policy model and compiles it into generic additions and restrictions

Wrong: Basic invokes a recursive provider closure to decide signalling policy
Correct: the signalling plugin decides the requested boundary/additions; Basic validates and claims one union
```
