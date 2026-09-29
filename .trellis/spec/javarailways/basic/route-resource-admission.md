# Basic 路线资源准入契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`CURRENT`。Basic 中的旧保护区域、单窗口 request/claim/validator 已删除；本页是唯一通用路线资源准入合同。
- **Implementation progress**：内部 `PhysicalRouteResourceCompiler` 从投影后的冻结路线和显式路线范围推导完整物理底线及准备、占用检查、释放元数据；`CompiledRouteAdmission.withContributions` 合并经过资源所有者校验的附加项。`RouteResourceAdmissionEngine`把首次申请及同冻结路线后续申请的校验、delta claim、设备准备、grant更新和车尾释放串成Basic内部流程，并由集成测试覆盖。Bukkit生产composition用`RouteResourceAdmissionRuntime`把它绑定到每世界唯一registry、Core topology、认证contributor/provider和同epoch observation tail-release输入；同一准备批次可驱动即时道岔及provider注册的异步资源handler，全部`READY`前不发布grant。外部插件从`InfrastructureResourceService`取得绑定插件身份和确切world runtime的`RouteResourceAdmissionService`；production Dispatcher使用该service提交自动和`MANUAL_ROUTED`路线，并把committed grant编译为控制器限制。
- **Owner**：Basic 拥有通用资源注册、冻结路线验证、不可省略的物理资源推导、原子 claim、设备准备、grant 生命周期与车尾释放。
- **Caller**：SimpleATO只提供冻结路线、列车/观察身份和目标authority；它不提交、解释或枚举具体信号资源。
  Bukkit宿主在同一次准入内调用已认证的route-resource contributor，由信号插件根据通用路线上下文产生附加资源。
- **Non-goals**：Basic 不定义保护区域、固定闭塞、信号显示、联锁表、侧面防护规则、最远瞭望策略或国家信号制式。

触发：修改自动进路申请、route-bound resource、turnout/diamond claim、外部资源注册、设备准备、rolling extension、grant 或车尾释放。

## 2. 模块工作方式

一次正常申请只有一份冻结路线和一份最终 claim：

```text
SimpleATO submits one frozen route and desired authority boundary
  -> the host asks registered signalling contributors for requirements for that route
  -> Basic validates route/topology/observation coherence
  -> Basic derives the non-removable physical resource floor from the route
  -> Basic validates and unions contributor results with that floor
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

Route-bound catalog publication is all-or-nothing for one exact`TopologyState`root. For an`AtGradeRailCrossing`, Basic derives one`javarailways:diamond`identity from the ordered rail UUID pair and exact AB crossing-position bits, then creates one positive anchor on each rail by extending the independent`DIAMOND_ANCHOR_HALF_LENGTH_METERS=5 mm`around the crossing and clipping to that rail's projection. The short anchor is an interval-query marker, not the Core geometry-detection tolerance or a signalling protection distance. Both traversal sides therefore resolve the same identity and occupancy geometry; unrelated topology revisions preserve it when rail UUIDs and crossing positions are unchanged.

The catalog validates all anchors, builds one start-sorted interval index per rail and stores prefix maximum endpoints. Querying one route occurrence binary-searches the first prefix that can overlap, scans only candidates whose starts remain within range, converts matches back to route metres and merges adjacent hits of the same identity. Leaving and later re-entering one resource is rejected. Runtime publication also builds an O(1) set of Basic-owned turnout/diamond identities for registry existence checks; external provider lookup remains reserved for plugin-owned types. This mapping/index work happens once per topology root, while admissions only perform indexed lookups.

### 3.2 插件附加资源

A signalling plugin may contribute stable resources that it owns or is allowed to request, for example a flank turnout, an approach-lock resource or a whole-block token. SimpleATO never constructs these values. The host invokes each registered contributor once per new/extended admission, outside Basic's physical route scan; a contributor uses its own index to return only requirements intersecting or protecting that request. Basic validates each identity through the resource registry and checks that any requested preparation capability is registered for that resource.

The plugin remains responsible for computing why the addition is needed. Desired authority is already chosen through the generic inspection/constraint path; contribution cannot widen it. Basic does not recursively ask one resource to inspect its successor or synthesize protection coverage. The final footprint is:

```text
mandatoryPhysicalFootprint(frozenRoute, requestedRange)
  union validatedContributorResources
```

The union is unique by exact resource identity and is committed once. Occupancy query geometry, preparation requirements and release entries are metadata for validation/operation/lifecycle; none is a second claim footprint.

当前纯合并步骤接收宿主收集并复制的contribution batch，而不是公开ATO intent中的字段。宿主先收集所有contributor结果，再从已编译的物理footprint复制资源身份与释放元数据，逐项调用world-local资源owner的无副作用校验函数；任一项失败直接返回typed rejection，不触碰registry。新身份追加到唯一footprint；若身份已在物理底线中，只取原释放边界与附加边界的较大值，禁止附加准备覆盖物理准备。最后按车尾清除位置排序唯一释放队列。时间与临时空间随物理footprint及本次contribution总量线性增长（排序释放队列除外）。

当前Basic内部申请由`RouteResourceAdmissionEngine`拥有：读取一份当前observation和同revision的拓扑root。没有route state时，投影调用方冻结路线并编译`[0, desiredAuthority]`物理范围，先确认路线从权威车尾开始；已有route state时，核对申请身份与路线，只编译已提交authority至新boundary的suffix。普通停车点后的下一段任务可以把新traversal严格追加到已经完整授权的旧路线末端：旧segments必须逐项保持不变、route ID/topology/owner/train不变，并使用operating-route自身的更高revision；其他route变化仍拒绝。两种增长方式共用后续资源校验、占用查询、registry reservation、设备准备与结果流程。宿主构造world owner时绑定附加资源校验；请求本身不能替换该函数。registry mutation使用宿主持有的token，不使用发起申请的插件token；插件退出不能直接删除列车仍持有的资源。准备票据未READY时只返回同一pending handle，不扩大grant或切换route binding；READY时重新读取最新完整observation，要求拓扑仍为原root、物理车尾仍符合已提交前缀，且该观测中的申请范围没有其他列车。正常跨越physics epoch时，grant与释放控制器绑定通过复核的新stamp；复核失败时不能发布旧授权，也不能把已准备的设备擅自视为安全中止，而是保留claim并stall。资源handler明确报告安全中止时才撤销本次新增claim。附加资源提供者异常按`INVALID_CONTRIBUTION`拒绝。Bukkit现已把这个owner绑定为world-local、认证过调用方身份的`RouteResourceAdmissionService`；生产Dispatcher/SimpleATO caller迁移仍待后续批次。

### 3.3 申请、准备和释放

Basic first completes route/resource/occupancy validation, then atomically claims the final footprint. Claim ownership prevents conflicting claims but does not mean a movable resource is ready. Registered resource handlers prepare only already-claimed resources and report `PREPARING`, `READY`, `ABORTED_SAFE` or `UNSAFE` according to the generic preparation contract. A grant is published only after all requirements are `READY`.

The current route state normally owns one append-only operating route and one monotonic tail cursor, independently of its nullable committed grant. Basic creates this state only once the first validated request has successfully reserved resources; it is not a per-train object allocated for every idle train. The cursor is initialized from the authoritative train tail and reused when the first `READY` creates the committed release queue. If this first attempt safely aborts and no grant or reservation remains, Basic discards the unused route state. After a grant exists, the route state remains until explicit safe route end even when its resource queue becomes empty. A forward continuation extends the same cursor occurrence list only after preparation becomes `READY`; it never replaces the cursor or rewrites an accepted prefix.

Committed resources enter one route-ordered release queue attached to that route state. Each observation advances the same monotonic frozen-route cursor from the authoritative train tail. Basic atomically removes only the eligible queue prefix, then advances a head index in the retained queue; normal releases do not copy and revalidate the entire remaining queue. A signalling plugin may supply a stricter release boundary for its added resource; it cannot release a mandatory physical resource earlier than Basic's physical clear boundary.

The same request operation also grows an existing grant along that route. Basic compiles only the farther authority suffix, derives its physical floor, and unions contributions collected for that request. Exact identities still held by the grant are not claimed or prepared again. If a contribution asks to hold one of them longer, Basic validates that its new tail-clear boundary is no earlier than the committed one, then temporarily places that identity after ordinary eligible entries in the **same** release queue while the new delta is pending. Other old resources remain releasable by the tail. `READY` checks the latest coherent observation and suffix occupancy, commits only the new delta, then publishes the later hold boundaries and authority together; for an append-only continuation it also commits the longer route binding and progress path in that same serialized step while retaining grant ID, logical owner and cursor state. `ABORTED_SAFE` removes only the delta, restores old boundaries and leaves the old route binding authoritative. Unsafe or unprovable completion retains the safest reservation and stalls. If the old queue and registry claim are already empty, the route state keeps its cursor and acquires the suffix under the same grant identity. Modified prefixes, disconnected append and unrelated route changes remain replans outside this operation; none uses whole-claim replacement.

A stopped reversal uses an explicit two-step boundary instead of the append operation. `suspendReleaseForReversal` first freezes only tail release while retaining the same grant and complete registry claim. After Core publishes the changed train direction, `rebaseAfterReversal` requires a new route beginning at the authoritative new tail and a desired boundary equal to the observed head. Basic compiles that body range, requires every mandatory and contributed resource to be covered by the already-held claim, and performs no registry acquisition. It then replaces the route-relative cursor and rebuilds one release entry for every retained identity: an unchanged rail interval keeps its exact old identity even if only part overlaps the reversed body, its boundary is the final new-route overlap, and identities with no new-route evidence receive boundary 0. Only after all validation succeeds does Basic publish the new route binding and resume release under the same grant ID. Failure leaves release suspended and every resource held.

Basic treats the submitted authority boundary exactly. It does not infer a stopping overlap from ATO tolerance, timetable semantics or train type. The signalling/timetable caller must keep its operational stop target far enough before the requested authority end when an overlap is required; a reversed body extending outside the retained claim is rejected rather than covered retroactively.

## 4. 冻结的公开请求合同

当前 `work.art1st.javarailways.basic.api.route` 提供下列等价的不可变 value contract；后续允许调整
Kotlin 文件拆分，但不得改变字段语义、增加 raw footprint overload，或把 provider callback 塞进 intent。
`RouteSnapshot`、`RailwayStateStamp`、`InfrastructureOwner` 与
`StableInfrastructureResource` 复用现有 Basic API value，不建立平行模型。

```kotlin
data class RouteResourceIntent(
    val requestId: String,
    val owner: InfrastructureOwner,
    val trainId: String,
    val observationStamp: RailwayStateStamp,
    val route: RouteSnapshot,
    val desiredAuthority: RouteBoundary
)

data class RouteBoundary(
    // metres from the first point of route.segments, increasing in traversal order
    val distanceFromRouteStartMeters: Double
)

data class ContributedRouteResource(
    val resource: StableInfrastructureResource,
    val releaseAfterTailPasses: RouteBoundary,
    val preparation: ResourcePreparationDescriptor? = null
)

fun interface RouteResourceContributor {
    // Called by the authenticated host, never by SimpleATO.
    fun contribute(intent: RouteResourceIntent): Collection<ContributedRouteResource>
}

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
    fun suspendReleaseForReversal(grantId: RouteGrantId): RouteGrantReversalResult

    @MainThread
    fun rebaseAfterReversal(intent: RouteResourceIntent): RouteGrantReversalResult

    @MainThread
    fun endGrant(grantId: RouteGrantId): Boolean
}

enum class RouteAdmissionFailureCode {
    WORLD_UNAVAILABLE, OWNER_MISMATCH, STALE_OBSERVATION, TRAIN_NOT_FOUND,
    STALE_TOPOLOGY, INVALID_ROUTE, INVALID_BOUNDARY, INVALID_INFRASTRUCTURE,
    INVALID_CONTRIBUTION, INVALID_PREPARATION, PHYSICALLY_OCCUPIED, RESOURCE_CONFLICT,
    ROUTE_STATE_MISMATCH, REQUEST_BUSY, PREPARATION_ABORTED_SAFE, WORLD_STALLED
}
```

`requestId` 是 namespaced caller correlation ID，没有 retry/persistence 语义。`owner` 是logical
route/grant owner及其non-negative plan revision；Bukkit/service binding在字段外提供认证过的platform owner，
因此修改value不能冒充另一个插件。`trainId`是exact observation内的nonblank Core stable ID。stamp world必须等于world-local
service；physics epoch必须仍能解析到准入所用immutable observation；stamp topology revision必须等于
`route.topologyRevision`。request不复制第二个expected-topology字段。

`RouteResourceIntent`不含resource identity、resource type、preparation descriptor或provider callback。
SimpleATO只需理解冻结路线、列车身份和所需authority。`RouteBoundary`只在包含它的intent及其唯一`route`内解释，
不重复保存route identity/revision。有限且非负的
distance从`RouteSnapshot.segments`第一个点起，以冻结路线行进方向递增，单位为实际米；即使某一segment沿BA
行驶，它也不是canonical rail AB坐标。`desiredAuthority`不得超过`route.totalLengthMeters`。每一个contribution的
`releaseAfterTailPasses`都必须显式提供，并落在`[0, desiredAuthority]`。它表示命名资源
至少保持到权威**车尾**加Basic提供的非负安全余量通过该路线位置。不存在隐式/default释放点。

ATO caller不提交raw rail interval、turnout/diamond selection或任何附加资源。Bukkit宿主按稳定注册顺序调用
route-resource contributors，每个contributor每次申请最多调用一次；调用发生在物理编译之后、registry mutation之前，
不是physics-tick callback。宿主把全部返回值复制为immutable contribution batch，并要求最终结果按exact
`(typeId, resourceId)`唯一。若contributed identity也出现在Basic物理底线，它不形成第二份资源，只能把该资源
的释放边界向后延长；不得替换identity、缩短Basic物理clear boundary或覆盖Basic推导的turnout/物理准备target。
非路线/flank资源可以带一个optional provider-owned `preparation`；缺省descriptor表示只claim、不准备。

descriptor不是ATO API的一部分，而是**contributor与resource owner之间**的小型runtime request value。
`kindId`为namespaced stable identifier；argument
key非空，map defensive copy并按key稳定排序，decimal必须finite，value只能是上面四种scalar。Basic将它作为
opaque data，只派发给拥有该resource type且声明该preparation kind的registration。descriptor不含mutable
train/world、callback、JVM class name或serialized JSON。它**不是持久化schema**，不产生兼容或restore承诺；
以后若持久化资源定义或in-flight recovery，必须另审独立schema boundary。

Resource registration是namespaced、world-local并绑定认证platform owner。registration定义stable identity、
authenticated contributor与preparation descriptor validation、可选preparation handler和lifecycle ownership；
它不允许直接修改Basic registry。contributor不必拥有resource type：例如SimpleSignals可以贡献Basic拥有的侧面防护
道岔，但Basic的turnout registration必须按contributor身份、resource identity与descriptor明确接受，否则返回
`INVALID_CONTRIBUTION`或`INVALID_PREPARATION`。

当前Bukkit适配器复用已有的唯一`typeId → provider`注册表，不另外建立进路资源catalog。注册表跨world保存
provider，但每次校验都指向已注册的确切world ID；对外准入service binding仍绑定一个确切world runtime。
`exists(worldId, resourceId)`只证明资源存在，绝不自动许可进路申请。provider必须显式实现进路校验，
由资源owner检查认证contributor、world、resource ID与可选preparation descriptor，返回允许或
`INVALID_CONTRIBUTION`/`INVALID_PREPARATION`。Bukkit在simulation owner执行回调，不在注册表锁内调用provider；
回调后复查world/registration/owner仍有效，失效或异常一律拒绝且不修改claim。公开intent不携带caller
additions；宿主收集认证contribution并直接交给同一次准入，不保留旧constructor或运行时兼容路径。

### 4.1 当前contributor与provider preparation合同

caller contributions已经从ATO intent移到认证contributor；通用ticket状态位于platform-independent Basic API，
Bukkit在同一个resource provider registration中注册实际handler：

```kotlin
enum class ResourcePreparationState { PREPARING, READY, ABORTED_SAFE, UNSAFE }

data class ResourcePreparationSnapshot(
    val state: ResourcePreparationState,
    val detail: String = ""
)

interface ResourcePreparationTicket {
    @MainThread fun snapshot(): ResourcePreparationSnapshot
    @MainThread fun abort()
}
```

```java
RouteResourceContributorRegistration registerRouteResourceContributor(
    Plugin owner,
    RouteResourceContributor contributor
);

interface InfrastructureResourcePreparationHandler {
    String kindId();
    ResourcePreparationTicket start(
        UUID attemptId,
        UUID worldId,
        StableInfrastructureResource resource,
        ResourcePreparationDescriptor descriptor
    );
}

InfrastructureResourceProviderRegistration registerResourceProvider(
    Plugin owner,
    String typeId,
    InfrastructureResourceProvider provider,
    Collection<InfrastructureResourcePreparationHandler> preparationHandlers
);
```

contributor registration只是少量信号策略入口的lifecycle/authentication列表，不复制资源定义或建立第二个resource
catalog。每次request按稳定顺序调用每个contributor一次；插件必须用自己的route index查找相关定义，禁止宿主逐资源
调用。ATO和Basic都不解释返回资源的领域含义。任一route state仍可能申请后续suffix时，宿主冻结完整contributor
membership：planned register/unregister/registration close均拒绝。forced contributor disable保留既有claim并Stall
相关world，防止之后的申请静默少执行一套信号策略；不增加membership revision、session token或恢复协议。

当前非递归边界由调用结构本身保证：`PhysicalRouteResourceCompiler.compile`只从已验证路线与
`RouteBoundResourceCatalog`编译物理底线，不持有任何插件回调；
`DefaultInfrastructureResourceService.collectRouteContributions`快照当前注册列表并对每项调用一次；
`RouteResourceAdmissionEngine.request`只消费这一份有界列表，通过`withContributions`校验后合并一次。
`InfrastructureResourceProvider.validateRouteContribution`只能批准或拒绝单个已返回资源，不能生成后续贡献。
因此Basic的物理扫描、contributor收集与resource-owner验证是三个单向阶段，不存在回调到物理
编译或再次收集的closure/fixed-point路径。

一个type registration同时拥有existence/route-use validator及零个或多个preparation kind；不建立第二个handler
catalog。`kindId`必须namespaced且在该type内唯一。没有handler的provider传空collection；不保留旧三参数注册
overload。pre-claim validation要求descriptor已被provider允许且exact kind已有handler，否则
`INVALID_PREPARATION`且无mutation。`attemptId`是每个resource preparation的process-local UUID，只用于handler
关联自己的异步状态；它不是request ID、retry key或持久化字段。handler只接收上面的immutable值，不接收train、route、
registry或Core world。

claim成功后的batch仍只有一个Basic preparation ticket。宿主先解析完整物理+provider requirement列表及其确切
registration/handler，确认全部存在后才允许第一个side effect；然后按已编译顺序启动。provider `start`必须快速、
非阻塞，在需要Bukkit region/entity side effect时自行交给target-aware scheduler并立即返回ticket。任何start异常都
无法证明该handler未产生side effect，因此保留完整claim并Stall；只有完整preflight在首个side effect前失败，才可按
现有`Rejected`路径安全撤销claim。

组合ticket按以下规则合并子ticket，不建立独立恢复状态机：

```text
snapshot all child tickets in deterministic order
if any UNSAFE -> UNSAFE
if abort was requested or any child is ABORTED_SAFE:
    abort every still-PREPARING child once
    if any child remains PREPARING -> PREPARING
    else -> ABORTED_SAFE
if every child is READY -> READY
else -> PREPARING
```

`abort()`只向仍在准备的resource请求安全中止；READY handler及资源默认/静止状态仍由resource owner管理，Basic
不恢复旧值或设置默认值。planned provider unregister及单独registration `close()`都必须在该type不再出现于claim、
release queue或pending compiled request后才成功。forced provider disable保留资源并Stall；在世界已Stall或owner
disabled后不得再调用该plugin的ticket。timeout、duration、ticket persistence、restart recovery及设备默认状态不在本批次。

令`C`为已注册contributor数、`P`为它们返回的requirement总数。一次新/扩展申请的收集为`O(C + P)`加各插件
内部索引查询；preflight/start及每次显式`progress(handle)`最多访问`O(P)`个requirement/ticket。不会从physics tick
遍历全部provider或resource，也不会在无pending handle时轮询handler。

### 4.2 Result与准备协议

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

preparation provider只接收由认证contributor生成的immutable `resource + descriptor + attemptId`，在world owner thread快速启动并返回
现有`PREPARING/READY/ABORTED_SAFE/UNSAFE` ticket语义。它不接收train、route、claim registry、Core world或其他
mutable Basic object；设备如何达到target、READY后是否自行恢复默认状态，由resource owner管理。Basic不在route
scan过程中调用provider，也不允许provider追加资源。所有contribution已完成纯数据校验并形成final union后Basic才
claim；claim成功后才按确定顺序启动准备。

### 4.3 冻结的校验与提交顺序

```text
require service open and authenticated platform owner
resolve exact retained observationStamp; resolve trainId in that observation
require route topology == observation topology == current TopologyState root
validate route identity, segment values, connectivity, legal continuations and tail start
validate desiredAuthority and every explicit tail-release boundary in route metres
compile Basic physical floor + physical preparation/occupancy/release metadata once
collect each authenticated route-resource contributor once
validate unique contributions against world-local registrations and preparation kinds
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
- The mandatory physical footprint is always present in the committed union, regardless of contributor results.
- Validation/preparation failure never publishes a wider grant. Unsafe or unprovable device state retains the safest claim and stalls that world rather than releasing protection.
- Planned signal-plugin disable first calls the Bukkit owner-unregister check. Basic classifies a requester as active only
  when its route has a pending reservation or its registry claim still contains resources; an empty committed `RouteState`
  awaiting `endGrant` is safe to discard during unregister. The same Bukkit call also checks the plugin's signal sources;
  every infrastructure and non-empty signal-restriction check completes before either registration set is removed. Unrelated
  trains and installed sources with only empty retained streams do not block unregister. Active state rejects planned unregister. Because Bukkit's
  `PluginDisableEvent` is already non-cancellable, forced requester loss retains every host-owned claim/reservation and
  latches the affected world through the existing Stall owner with world, plugin, route owner, train, pending and held-count
  diagnostics. The same check follows provider ownership: a plugin cannot unregister while one of its registered stable
  resource types remains in another requester's claim, release queue or pending compiled request. Forced provider loss
  retains those resources and stalls the affected world. Neither path drains, inherits, restores or transfers state.
- Runtime claims are not persistence schema. Stable resource definitions belong to the plugin or infrastructure owner that defines them; Basic persists only a boundary explicitly approved later.
- Registry mutation and resource preparation obey the world owner thread; physics observations crossing threads are immutable values.
- `RouteResourceAdmissionService` is world-local. Callers invoke it on the documented world/main owner only after the
  exact successful observation publication; provider handlers may return `Pending` but may not block that owner thread.
- Registration belongs to one authenticated platform-plugin owner. A successful planned unregister proves that requester
  has no pending route request and no resources remaining in its host-owned registry claim before removing its provider,
  explicitly removed infrastructure dependencies and empty route state. Infrastructure dependencies are one
  non-exclusive edit-protection mechanism: timetable selected-route dependencies and active frozen-route
  dependencies use distinct owners and lifecycles in the same registry. They never claim resources or block trains.
  Forced disable rejects new calls immediately, does not delete host-owned live claims,
  release queues or copied contribution data, and stalls each world with requester-owned pending/held route resources. Provider
  disappearance during pending preparation is unprovable, so Basic retains the safest reservation and stalls the world.

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
| contributor failure, duplicate contribution or unknown/unauthorized resource | `INVALID_CONTRIBUTION` | none |
| preparation kind/arguments rejected by owner registration | `INVALID_PREPARATION` | none |
| occupancy intersects compiled physical geometry | `PHYSICALLY_OCCUPIED` | none |
| final union conflicts with another claim | `RESOURCE_CONFLICT` | none |
| authenticated requester or logical owner differs from the active route state | `OWNER_MISMATCH` | no new reservation |
| train differs, or route changes anything except a strict forward append at the fully authorized old end | `ROUTE_STATE_MISMATCH`/`INVALID_BOUNDARY` | no new reservation |
| the active route state already has a pending request | `REQUEST_BUSY` | no new reservation |
| all validation passes | claim union, then prepare | one atomic claim |
| preparation still running | `Pending` | claim/reservation retained; no grant |
| preparation proves safe abort | typed denial | only this request/delta is removed |
| preparation becomes unsafe/unprovable | `WORLD_STALLED` | safest claim retained |

表中`none`指本次请求不新增reservation、设备切换或authority；已有grant按权威车尾进行的独立释放仍可发生。

There is no Basic `PROTECTION_REQUIRED`: missing protection-region coverage is a configuration/policy error reported by the concrete signalling plugin before it asks for a permissive route. Basic still denies any request whose physical route or resource union is invalid.

### 6.1 同一 operating route 的后续请求

Basic按logical owner ID找到当前route state。若不存在，`request`建立第一次申请；若存在，authenticated requester、
logical owner、stable train ID、`routeId`和topology revision必须不变。通常`routeRevision`与ordered segments完全相同；
普通停车点接续下一mission时，只有在committed boundary已经到达旧route length后，才接受更高operating-route revision，
而且新segments必须以旧segments为逐项完全相同的前缀并至少追加一项。Basic重新投影完整候选路线以验证连接和方向，
但物理资源、占用geometry和准备只编译旧committed boundary到新desired boundary的新增范围。它可以使用更新的successful
physics epoch，但topology revision必须不变。新desired boundary必须严格晚于committed boundary且不超过候选route length。
新增contribution遵循相同校验；已经由该grant持有的identity只能单调延后release boundary，不能改变已committed
preparation descriptor。一个route state同一时刻只允许一个pending request。route/topology/plan改变必须在
安全运营边界replan；折返只能使用显式的暂停释放与原地rebase，不能伪装成append或whole-claim replacement。

Denied或pending后续请求保持旧committed grant/authority/route binding，旧tail release继续。只有delta原子reserve、全部delta
preparation READY、既有resource boundary单调更新、cursor path与release entries追加验证全部成功后，Basic才以同一grant ID
发布更长route revision和更远committed boundary。safe abort只删除新delta；unsafe或无法证明的abort保留最安全reservation并stall world。

已持有资源的保持时间不是新的claim身份：准备期间只暂缓它自己的释放，不能因此堵住队列里其他已清除的资源。若安全中止时车尾已经经过其旧释放点，恢复旧边界后必须立即按当前已接受的车尾进度释放它；否则旧资源会无故长期占用。待处理的新增delta始终不进入可释放队列，直到设备`READY`并完成registry提交。普通车尾释放不重排队列；只在延长申请/完成/安全中止这几个低频边界重排剩余队列。

## 7. 性能、复杂度与退化行为

Let `R` be projected route slices, `B` route-bound catalog hits, `G` registered route contributors, `A` contributed resources, `H` resources in the final claim, `I` occupancy-query intervals, `C` bucket-selected swept-span candidates and `M` resources in all other claims/edit permits. Projection is linear in `R`; rail-indexed catalog lookup reads route occurrences plus binary-search/candidate scans, then merges/sorts hits. Each new/extended admission calls each of the normally few registered contributors once, so collection is `O(G+A)` plus their indexed lookups; it must not call every resource instance or execute per-resource JS. Physical interval and release ordering can cost `O((R+B+A) log(R+B+A))`. Occupancy comparison is worst-case `O(C×I)` after bucket selection. The current registry checks requested resources against other held resources pairwise, worst-case `O(H×M)`; it does not yet have a conflict index. These are request-time costs, not unchanged per-tick work.

Observation dispatch builds one train-ID lookup only when at least one live grant exists. With grants, ordinary cursor checks start at the current occurrence and queue inspection starts at the current head; releasing `K` eligible resources costs `O(K)` locally. The registry keeps the live claim in one lock-protected ordered identity set: after validating the complete release batch, it deletes those `K` identities in place instead of rebuilding the other `H` resources. Its average shrink cost is `O(K+P)`, where `P` is the pending-record count currently scanned to find this owner. With fixed `P`, releasing `H` resources one by one is cumulatively average `O(H)`; this is not a bound on conflict lookup or explicit diagnostic snapshots, which still copy `O(H)` resources on read. Completed admission handles remain repeatable while held by callers without a world-lifetime terminal-result table.

一次延长申请可读取该grant尚未释放的`H`个身份，把新增资源与已持有资源按exact identity分开；只有开始延长、完成或安全中止时才对剩余队列排序，成本为`O(H log H)`，不是每tick成本。按logical owner ID直接定位live grant，不为每列车延长遍历所有live grants。正常无pending extension时，前述车尾逐项释放仍从队首推进，不因这一功能退化为每次重建整个队列。

折返是停车边界上的一次性低频操作。新body route只编译一次；已持有rail interval按rail建立排序索引，每个route occurrence二分定位第一个可能重叠项并只扫描实际候选，stable identity按map查找，最后对`H`个新释放项排序。因此成本为`O(R log H + K + H log H)`，其中`K`是实际区间重叠数；正常physics tick不承担该重建成本。

Unchanged state performs no request compilation by itself. SimpleATO/resource inspection decides when a request is needed. Missing indexes, stale revisions or resource-handler failure fail closed; Basic must not fall back to a live-world full scan that silently grants authority.

## 8. Good / Base / Bad cases

- **Good**：SimpleATO submits only route/train/authority; a Japanese-signalling contributor adds a flank turnout and later hold without SimpleATO knowing either identity or descriptor.
- **Good**：a different interlocking contributor emits route-table resources and approach locking without creating any Basic protection region.
- **Base**：a straight route has only mandatory rail intervals because every contributor returns empty.
- **Good**：a long claim releases one resource at a time; older diagnostic snapshots remain unchanged, while the registry removes only the named live identities.
- **Good**：the first request waits for a device with `committedGrant = null`; on `READY`, the route state installs its first grant and release queue using the already-established cursor.
- **Good**：after all committed resources have been tail-released, another request along the same frozen route reacquires a farther suffix without inventing a second route cursor.
- **Good**：an ordinary stop retains the last claimed interval, then appends the next forward mission under the same grant/cursor; pending or rejected preparation keeps the old route binding.
- **Good**：a stopped reversal freezes release, keeps the exact claim and grant ID, then places a new cursor at the reversed tail and recalculates old-resource release points without reacquisition.
- **Bad**：SimpleATO constructs a protection-region ID, preparation kind or signal-system-specific argument.
- **Bad**：an initial safe abort leaves an owner permanently busy with an empty route state, or a later safe abort deletes an earlier committed grant.
- **Bad**：each tail-clear event reconstructs an immutable footprint of every still-held resource; a long route then pays quadratic cumulative release work.
- **Bad**：a plugin omits a traversed turnout or supplies another identity; Basic still derives the canonical turnout resource.
- **Bad**：Basic treats plugin contributions as authoritative replacement footprint or lets JS directly mutate a claim.

## 9. 必需测试与断言点

- Route projection covers AB/BA, zero/one/multiple turnouts, loops and route-bound diamonds with deterministic identities.
- An intent with every contributor returning empty still produces the complete mandatory physical footprint.
- Neither caller nor contributor can remove/replace a derived rail, turnout or diamond resource.
- Valid contributions join the same atomic footprint; contributor failure, unknown/unauthorized resources and conflicts leave no partial claim.
- Occupancy geometry and preparation/release metadata never become alternate claim sets.
- Immediate and delayed preparation publish no grant before `READY`; unsafe failure retains the safest claim.
- First pending request has one route cursor and no committed grant; safe abort after successful rollback removes the unused route state, allowing a fresh request from a fresh observation.
- The same public `request(intent)` accepts a farther boundary on the identical frozen route or a strict forward continuation after the old route end, rejects a mismatched owner/train/modified prefix/disconnected append/reversal and a concurrent pending attempt, and keeps the same cursor and grant ID after successful growth.
- Reversal suspension stops observation-driven release before the physical direction changes; a valid rebase keeps grant/claim identity, proves retained coverage of the complete reversed body and resumes from a new-tail cursor, while an uncovered resource or invalid route releases nothing.
- Delayed `READY` on a newer safe epoch rebinds the grant stamp; changed topology root, missing/moved train tail or newly occupied route publishes no grant and retains the claim under Stall.
- Two pending attempts cannot publish a grant after either one has stalled the world; the second claim stays retained and the Stall callback fires once.
- Tail release and same-route extension retain the existing cursor, release only a contiguous eligible prefix and never shorten plugin-specified holds.
- Pending extension may defer an already-held identity while other old resources release; READY commits the new hold and delta before widening the same grant, while safe abort restores the old hold and releases it if the accepted tail has passed. An emptied old queue can reacquire the next suffix without creating a new grant ID.
- Many sequential single-resource releases preserve the remaining claim order and owner; snapshots taken before shrink/append/rollback stay detached, while pending append forbids delta release and permits old-prefix shrink.
- Plugin disable/reload tests prove existing grants remain fail-closed and releasable without executing plugin policy again.

## 10. 迁移、未决事项与 Wrong / Correct

The implemented generic pieces—topology projection, canonical turnout identity, route-bound catalog, occupancy query, atomic registry, preparation, cursor/release queue and suffix extension—remain in Basic. Protection-region definitions, graph cut, catalog/index, route-window matching and diagnostics are owned by `SimpleSignals`; Basic has no `PROTECTION_REQUIRED` branch or protection-specific request/claim path.

Still open for later API batches: optional administration UI/policy for restricting cross-plugin resource use,
persistence/recovery formats and plugin-local script identifiers. The runtime contract already permits a provider to validate
an authenticated request from another plugin, which is required for a signal plugin to request Basic-owned flank turnouts.
The host authenticates each contributor independently from the SimpleATO route caller; SimpleATO supplies the frozen route and later
consumes the committed grant, but does not repackage a plugin contribution under its own identity. `SimpleSignals` does not change the
physical-floor invariant or turn the runtime descriptor into a persisted format.

```text
Wrong: the caller supplies the complete footprint and Basic trusts omissions
Correct: Basic derives the non-removable physical floor and only accepts additive resources

Wrong: Basic owns one universal protection-region/fixed-block model
Correct: each signalling plugin owns its policy model and contributes generic resources and restrictions

Wrong: SimpleATO constructs resource identities or preparation arguments for each signal system
Correct: the host authenticates contributors; Basic validates their bounded results and claims one union
```
