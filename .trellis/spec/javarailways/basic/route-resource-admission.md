# Basic 路线资源准入契约

## 1. 状态、范围、触发条件与 Owner

- **Status**：`CURRENT`。Basic 中的旧保护区域、单窗口 request/claim/validator 已删除；本页是唯一通用路线资源准入合同。
- **Implementation progress**：内部 `PhysicalRouteResourceCompiler` 从投影后的冻结路线和显式路线范围推导完整物理底线及准备、占用检查、释放元数据；`CompiledRouteAdmission.withContributions` 合并经过资源所有者校验的附加项。`RouteResourceAdmissionEngine`把首次申请及同冻结路线后续申请的校验、delta claim、设备准备、grant更新和车尾释放串成Basic内部流程，并由集成测试覆盖。Bukkit生产composition用`RouteResourceAdmissionRuntime`把它绑定到每世界唯一registry、Core topology、认证contributor/provider和同epoch observation tail-release输入；同一准备批次可驱动即时道岔及provider注册的异步资源handler，全部`READY`前不发布grant。外部插件从`InfrastructureResourceService`取得绑定插件身份和确切world runtime的`RouteResourceAdmissionService`；production Dispatcher使用该service提交自动和`MANUAL_ROUTED`路线，并把committed grant编译为控制器限制。
- **Owner**：Core 拥有每世界唯一的资源 registry、稳定资源身份契约及 claim/编辑许可的原子仲裁。Basic 拥有冻结路线验证、不可省略的物理资源编译、设备准备、grant 生命周期与车尾释放，并驱动 Core registry 完成资源申请和释放；宿主负责认证 provider/contributor 的注册与生命周期。
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
  -> Basic checks authoritative occupancy and requests an atomic union claim from the Core world registry
  -> registered resource handlers prepare the claimed resources
  -> Basic retains one frozen-route state with a tail cursor and nullable committed grant
  -> READY publishes or grows that grant; denial publishes no wider authority
  -> the same cursor and committed release queue shrink the claim behind the train tail
```

Basic proves physical consistency; the signalling plugin decides signalling policy. A protection-region plugin may inspect its own definition and the submitted route, choose an authority boundary beyond the region exit, and add flank-protection or approach resources. Another plugin may implement route tables and sectional release without defining a `ProtectionRegion` at all.

## 3. 内部机制与关键算法

### 3.1 不可删除的物理资源底线

Basic projects the requested route prefix/suffix against one current `TopologyState` root. A frozen route uses persistent rail IDs, direction and AB positions; its captured world revision is provenance, not an expiry rule. Projection validates those actual rails and connections on the selected root, without reusing revision-local node/edge IDs from an older graph. In one route-order pass it derives:

- one canonical AB rail interval for every traversed physical rail slice;
- one stable, direction-independent resource for every traversed turnout;
- every route-bound shared physical conflict, such as an at-grade diamond, matched by the catalog bound to the same topology root;
- route-ordered turnout selections and physical occupancy query geometry from the same projection.

This derived set is the **mandatory physical footprint**. The request cannot replace it, remove members from it, or supply an alternative turnout/diamond identity. Basic may merge adjacent intervals of the same rail but must not infer a cross-rail global interval.

Route-bound catalog publication is all-or-nothing for one exact`TopologyState`root. For an`AtGradeRailCrossing`, Basic derives one`javarailways:diamond`identity from the ordered rail UUID pair and exact AB crossing-position bits, then creates one positive anchor on each rail by extending the independent`DIAMOND_ANCHOR_HALF_LENGTH_METERS=5 mm`around the crossing and clipping to that rail's projection. The short anchor is an interval-query marker, not the Core geometry-detection tolerance or a signalling protection distance. Both traversal sides therefore resolve the same identity and occupancy geometry; unrelated topology revisions preserve it when rail UUIDs and crossing positions are unchanged.

The catalog validates all anchors, builds one start-sorted interval index per rail and stores prefix maximum endpoints. Querying one route occurrence binary-searches the first prefix that can overlap, scans only candidates whose starts remain within range, converts matches back to route metres and merges adjacent hits of the same identity. Separate visits remain separate route-position hits; they do not create new physical identities. A no-preparation diamond can appear repeatedly in one request: the unique identity remains held through its last requested clearance point. Runtime publication also builds an O(1) set of Basic-owned turnout/diamond identities for registry existence checks; external provider lookup remains reserved for plugin-owned types. This mapping/index work happens once per topology root, while admissions only perform indexed lookups.

### 3.2 插件附加资源

A signalling plugin may contribute stable resources that it owns or is allowed to request, for example a flank turnout, an approach-lock resource or a whole-block token. SimpleATO never constructs these values. The host invokes each registered contributor once per new/extended admission, outside Basic's physical route scan; a contributor uses its own index to return only requirements intersecting or protecting that request. Basic validates each identity through the resource registry and checks that any requested preparation capability is registered for that resource.

The plugin remains responsible for computing why the addition is needed. Desired authority is already chosen through the generic inspection/constraint path; contribution cannot widen it. Basic does not recursively ask one resource to inspect its successor or synthesize protection coverage. The final footprint is:

```text
mandatoryPhysicalFootprint(frozenRoute, requestedRange)
  union validatedContributorResources
```

The union is unique by exact resource identity and is committed once. Occupancy query geometry, preparation requirements and release entries are metadata for validation/operation/lifecycle; none is a second claim footprint.

当前纯合并步骤接收宿主收集并复制的contribution batch，而不是公开ATO intent中的字段。宿主先收集所有contributor结果，再从已编译的物理footprint复制资源身份与释放元数据，逐项调用world-local资源owner的无副作用校验函数；任一项失败直接返回typed rejection，不触碰registry。新身份追加到唯一footprint；若身份已在物理底线中，只取原释放边界与附加边界的较大值，禁止附加准备覆盖物理准备。最后按车尾清除位置排序唯一释放队列。时间与临时空间随物理footprint及本次contribution总量线性增长（排序释放队列除外）。

contributor看到完整请求前缀，因此延长时可能再次返回车尾已经通过的历史附加资源。engine先检查重复身份、请求释放范围及资源owner许可，
再用既有释放控制器的accepted tail progress过滤“不在当前持有集合且释放点已经通过”的附加项，不重新claim历史token。
仍在持有集合的资源绝不通过这个分支丢弃，仍执行不得缩短释放点/修改准备参数的检查；尚在车尾前方的资源仍正常申请。
该过滤只在显式准入申请时执行，复用当前持有map与既有cursor，不建立历史资源表、第二个队列或信号领域特判。
已经过去的非法provider、重复项或越过请求终点的释放点仍拒绝，不能借“历史”绕过认证和boundary校验。

当前Basic内部申请由`RouteResourceAdmissionEngine`拥有：读取一份当前observation和与之相符的拓扑root。
没有route state时，投影调用方冻结路线并编译`[0, desiredAuthority]`物理范围，先确认路线从权威车尾开始；
已有route state时，核对申请身份、路线和局部有效性，只编译已提交authority至新boundary的suffix。
普通停车点后的下一段任务可以把新traversal严格追加到已经完整授权的旧路线末端：旧segments必须逐项保持不变，
route ID、owner和train不变，并使用operating-route自身的更高revision；其他route变化仍拒绝。
两种增长方式共用后续资源校验、占用查询、registry reservation、设备准备与结果流程。

宿主构造world owner时绑定附加资源校验；请求本身不能替换该函数。registry mutation使用宿主持有的token，
不使用发起申请的插件token；插件退出不能直接删除列车仍持有的资源。
准备票据未READY时只返回同一pending handle，不扩大grant或切换route binding；READY时重新读取最新完整observation，
要求路线没有被相交事务标记失效、物理车尾仍符合已提交前缀，且该观测中的申请范围没有其他列车。
无关编辑可以改变世界root，不要求因此重新准备设备。正常跨越physics epoch时，grant与释放控制器绑定通过复核的新stamp。
复核失败时不能发布旧授权，也不能把已准备的设备擅自视为安全中止；claim继续保留，无法证明设备安全时stall。
资源handler明确报告安全中止时才撤销本次新增claim。附加资源提供者异常按`INVALID_CONTRIBUTION`拒绝。
Bukkit将此owner绑定为world-local、认证过调用方身份的`RouteResourceAdmissionService`，生产Dispatcher通过该服务申请。

### 3.3 申请、准备和释放

Basic first completes route/resource/occupancy validation, then atomically claims the final footprint. Claim ownership prevents conflicting claims but does not mean a movable resource is ready. Registered resource handlers prepare only already-claimed resources and report `PREPARING`, `READY`, `ABORTED_SAFE` or `UNSAFE` according to the generic preparation contract. A grant is published only after all requirements are `READY`.

The current route state normally owns one append-only operating route and one monotonic tail cursor, independently of its nullable committed grant. Basic creates this state only once the first validated request has successfully reserved resources; it is not a per-train object allocated for every idle train. The cursor is initialized from the authoritative train tail and reused when the first `READY` creates the committed release queue. If this first attempt safely aborts and no grant or reservation remains, Basic discards the unused route state. After a grant exists, the route state remains until explicit safe route end even when its resource queue becomes empty. A forward continuation extends the same cursor occurrence list only after preparation becomes `READY`; it never replaces the cursor or rewrites an accepted prefix.

Committed resources enter one route-ordered release queue attached to that route state. Each observation advances the same monotonic frozen-route cursor from the authoritative train tail. Basic atomically removes only the eligible queue prefix, then advances a head index in the retained queue; normal releases do not copy and revalidate the entire remaining queue. A signalling plugin may supply a stricter release boundary for its added resource; it cannot release a mandatory physical resource earlier than Basic's physical clear boundary.

The same request operation also grows an existing grant along that route. Basic compiles only the farther authority suffix, derives its physical floor, and unions contributions collected for that request. Exact identities still held by the grant are not claimed or prepared again. If a contribution asks to hold one of them longer, Basic validates that its new tail-clear boundary is no earlier than the committed one, then temporarily places that identity after ordinary eligible entries in the **same** release queue while the new delta is pending. Other old resources remain releasable by the tail. `READY` checks the latest coherent observation and suffix occupancy, commits only the new delta, then publishes the later hold boundaries and authority together; for an append-only continuation it also commits the longer route binding and progress path in that same serialized step while retaining grant ID, logical owner and cursor state. `ABORTED_SAFE` removes only the delta, restores old boundaries and leaves the old route binding authoritative. Unsafe or unprovable completion retains the safest reservation and stalls. If the old queue and registry claim are already empty, the route state keeps its cursor and acquires the suffix under the same grant identity. Modified prefixes, disconnected append and unrelated route changes remain replans outside this operation; none uses whole-claim replacement.

A stopped reversal uses an explicit two-step boundary instead of the append operation. `suspendReleaseForReversal` first freezes only tail release while retaining the same grant and complete registry claim. After Core publishes the changed train direction, `rebaseAfterReversal` requires a new route beginning at the authoritative new tail and a desired boundary equal to the observed head. Basic compiles that body range, requires every mandatory and contributed resource to be covered by the already-held claim, and performs no registry acquisition. It then replaces the route-relative cursor and rebuilds one release entry for every retained identity: an unchanged rail interval keeps its exact old identity even if only part overlaps the reversed body, its boundary is the final new-route overlap, and identities with no new-route evidence receive boundary 0. Only after all validation succeeds does Basic publish the new route binding and resume release under the same grant ID. Failure leaves release suspended and every resource held.

暂停成功至重建成功之间，普通 `request` 返回 `ROUTE_STATE_MISMATCH`，不调用贡献者、准备设备、推进旧车尾游标或扩大授权。该检查由 Basic 执行，不能仅依赖运营调用方自律。

Basic treats the submitted authority boundary exactly. It does not infer a stopping overlap from ATO tolerance, timetable semantics or train type. The signalling/timetable caller must keep its operational stop target far enough before the requested authority end when an overlap is required; a reversed body extending outside the retained claim is rejected rather than covered retroactively.

### 3.4 连续灯泡线与分次道岔使用

灯泡线没有停车折返，不重置冻结路线、grant或车尾游标。完整路线允许多次经过同一道岔，但一次物理申请范围只准备一次该道岔。
编译器按路线顺序检查本次经过的道岔：若身份已在该grant的剩余释放队列中，或已在本次范围出现，则在任何claim/准备之前
返回`REQUEST_RANGE_TOO_LONG`，并给出`retryAtOrBefore`：最早受阻道岔位置减去`2 * Curve.TOLERANCE`，不早于本次范围起点。
这个数值间隔只避免端点容差把受阻使用仍纳入申请，
不是信号停车余量；`nextDown`不足以避开现有`end + tolerance`判断。

Basic不擅自缩短grant。调用方可提交一个不超过提示的新exact boundary；SimpleATO还必须用已有检查声明的floor把它退到
不能被截断的区间入口外，Basic本身不认识保护区域。若没有能够增长的安全前缀，保持原授权并等待，不越过入口或强行切换设备。

后续申请不再查询“整条路线曾用过哪些设备”的累计集合，而只查询既有释放队列中的当前持有身份。
旧使用仍持有时，同样返回上述上限，允许先申请到下一次道岔使用之前；不能只返回无位置提示的`REQUEST_BUSY`，
否则旧授权太短时列车无法前进到足以释放第一次占用的位置。车尾完成原释放后，同一稳定道岔身份可以重新claim，
按第二次所需状态启动原有准备handler，全部READY才扩大同一grant。这适用于相同和不同目标；不为同目标另造跨次数保持策略。
菱形交叉没有待切换状态，允许合并持有到本次已请求范围中最后一次通过，不增加第二份准备票据。

区间过短、停车位置或列车长度使旧使用无法清除时，等待本身不能证明该路线可运行；不得用提前切换、释放仍占用资源或
伪装折返来“解决”几何上不足的净空。灯泡线闭塞/方向身份与具体信号策略仍由信号插件负责。

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

data class ActiveRouteAdmissionSnapshot(
    val owner: InfrastructureOwner,
    val trainId: String,
    val route: RouteSnapshot,
    val state: ActiveRouteAdmissionState, // PREPARING | GRANTED
    val requestedAuthority: RouteBoundary,
    val committedAuthority: RouteBoundary?,
    val releasedThroughRouteMeters: Double = 0.0
)

data class ActiveRouteAdmissionUpdate(
    val owner: InfrastructureOwner,
    val current: ActiveRouteAdmissionSnapshot? // null表示此owner不再有当前准入状态
)

enum class RouteAdmissionFailureCode {
    WORLD_UNAVAILABLE, OWNER_MISMATCH, STALE_OBSERVATION, TRAIN_NOT_FOUND,
    STALE_TOPOLOGY, INVALID_ROUTE, INVALID_BOUNDARY, INVALID_INFRASTRUCTURE,
    INVALID_CONTRIBUTION, INVALID_PREPARATION, PHYSICALLY_OCCUPIED, RESOURCE_CONFLICT,
    ROUTE_STATE_MISMATCH, REQUEST_BUSY, REQUEST_RANGE_TOO_LONG,
    PREPARATION_ABORTED_SAFE, WORLD_STALLED
}

```

`RouteAdmissionFailure.retryAtOrBefore: RouteBoundary?`只随`REQUEST_RANGE_TOO_LONG`出现；该code必须带值，其他code禁止带值。
提示属于被拒绝intent的route metres，不是authority。constructor另有原有`code`、`detail`和防御性复制的`blockers`。

`InfrastructureResourceService.activeRouteAdmissions(worldId)`从同一个world-local engine返回按logical owner稳定排序的
不可变当前观测。它直接读取真实pending/committed route state，不解析claim owner字符串；`PREPARING`仍保留已经提交的旧grant边界，
不能把正在准备的后缀误当成旧前缀失去授权。`GRANTED`必须带committed boundary，失败/结束的尝试不保留。`requestedAuthority`限定本次请求覆盖的路线前缀，调用方不能把
同一完整route中更远但尚未申请的基础设施视为当前请求。返回值不含handle、grant ID、requester、registry或live train/world，
只用于process-local只读消费，没有持久化或重启语义。

`releasedThroughRouteMeters`记录成功完成registry shrink后已移除队列前缀的最远释放位置：它是成功释放事件证明的
车尾进度下界，不是原始车尾坐标，也不表示此前所有重叠资源都已释放。较长资源仍可保留，Basic继续拒绝与其冲突的新申请。
不在此API新增安全余量策略；资源仍保持到各自已编译的释放位置，尚未移除的队列项不能推进这个值。
路线折返重建释放队列时，该位置重新从新路线起点计量。

宿主可在绑定engine时提供`publishAdmissionUpdate`接收器。每个owner仅在不可变当前值实际变化时发布，
空状态发布`current=null`。宿主把它交给现有signal epoch runtime，不让插件在physics callback中调用Bukkit service或轮询完整列表。
读列表仍是低频诊断接口；变化发布不扫描其他owner，也不引入第二套claim registry。

`requestId` 是 namespaced caller correlation ID，没有 retry/persistence 语义。`owner` 是logical
route/grant owner及其non-negative plan revision；Bukkit/service binding在字段外提供认证过的platform owner，
因此修改value不能冒充另一个插件。`trainId`是exact observation内的nonblank Core stable ID。stamp world必须等于world-local
service；physics epoch必须仍能解析到准入所用immutable observation。route记录创建时的拓扑版本，
observation记录物理观测使用的拓扑版本，两者不必因无关线路编辑保持数值相等。真实路线依赖必须仍有效，
且物理观测与准入所用当前图必须一致。request不复制第二个expected-topology字段。

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
它不允许直接修改Core世界资源registry。contributor不必拥有resource type：例如SimpleSignals可以贡献侧面防护
所需的Core物理道岔资源，但Basic准入使用的turnout registration必须按contributor身份、resource identity与descriptor明确接受，否则返回
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
identity列表；不得用exception class作为公开错误类型。`REQUEST_RANGE_TOO_LONG`另外必须携带一个`retryAtOrBefore`，
其他code不得携带；它是同一个被拒绝intent路线上的安全申请上限，不是grant、pending handle或持久化恢复字段。
`progress`只读取一个由同一service产生的opaque handle，
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
resolve a coherent current observation and TopologyState root
require the existing route state has no intersecting topology invalidation
validate route identity, segment values, connectivity, legal continuations and tail start
validate desiredAuthority and every explicit tail-release boundary in route metres
compile Basic physical floor + physical preparation/occupancy/release metadata once
collect each authenticated route-resource contributor once
validate unique contributions against world-local registrations and preparation kinds
merge by exact identity; duplicate physical identities may only extend release
reject if an intersecting topology transaction occurred during this validation
query authoritative same-epoch occupancy geometry
atomically claim the complete union
start ordered physical/provider preparation; publish grant only after all READY
```

本次新增资源的校验、编译与占用查询在reservation前不修改其claim或设备状态；已有grant的独立车尾释放仍可按已发布观测推进。registry conflict/claim failure不扩大claim、改变device selection或authority。
preparation只在claim之后开始，并遵循第6节的retained-claim failure规则。

## 5. 不变量、生命周期、线程与版本

- The current observation and physical catalog must be coherent with the root used for compilation. A frozen route's historical revision need not equal that root: validate its actual stable rail traversal and retain indexed invalidation for its lifetime.
- Core transaction notifications invalidate only route states whose rail dependencies intersect the reported impact. An affected state keeps its claims and pauses release; it cannot extend authority or publish a pending grant. An unrelated edit does not restart preparation, discard the grant or reset the tail cursor. Initial validation also tracks intersecting edits until the existing route state takes ownership; it does not allocate a Core per-route token.
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
| incoherent observation/catalog, or an intersecting edit invalidated this route | `STALE_TOPOLOGY` | no new resources; existing claims retained |
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
| one requested range includes the same turnout twice | `REQUEST_RANGE_TOO_LONG` plus exact retry ceiling | no claim or preparation |
| the next turnout use is still held from the preceding traversal | `REQUEST_RANGE_TOO_LONG` plus exact retry ceiling | retain old claim and grant; no new claim or preparation |
| all validation passes | claim union, then prepare | one atomic claim |
| preparation still running | `Pending` | claim/reservation retained; no grant |
| preparation proves safe abort | typed denial | only this request/delta is removed |
| preparation becomes unsafe/unprovable | `WORLD_STALLED` | safest claim retained |

表中`none`指本次请求不新增reservation、设备切换或authority；已有grant按权威车尾进行的独立释放仍可发生。

There is no Basic `PROTECTION_REQUIRED`: missing protection-region coverage is a configuration/policy error reported by the concrete signalling plugin before it asks for a permissive route. Basic still denies any request whose physical route or resource union is invalid.

### 6.1 同一 operating route 的后续请求

Basic按logical owner ID找到当前route state。若不存在，`request`建立第一次申请；若存在，authenticated requester、
logical owner、stable train ID与`routeId`必须不变，且本路线未被相关事务标记失效。通常`routeRevision`与ordered segments完全相同；
普通停车点接续下一mission时，只有在committed boundary已经到达旧route length后，才接受更高operating-route revision，
而且新segments必须以旧segments为逐项完全相同的前缀并至少追加一项。Basic重新投影完整候选路线以验证连接和方向，
但物理资源、占用geometry和准备只编译旧committed boundary到新desired boundary的新增范围。它可以使用更新的successful
physics epoch，也允许无关编辑导致world topology revision增加。新desired boundary必须严格晚于committed boundary且不超过候选route length。
新增contribution遵循相同校验；已经由该grant持有的identity只能单调延后release boundary，不能改变已committed
preparation descriptor。一个route state同一时刻只允许一个pending request。修改路线既有内容、其实际依赖或计划必须在
安全运营边界replan；折返只能使用显式的暂停释放与原地rebase，不能伪装成append或whole-claim replacement。

Denied或pending后续请求保持旧committed grant/authority/route binding，旧tail release继续。只有delta原子reserve、全部delta
preparation READY、既有resource boundary单调更新、cursor path与release entries追加验证全部成功后，Basic才以同一grant ID
发布更长route revision和更远committed boundary。safe abort只删除新delta；unsafe或无法证明的abort保留最安全reservation并stall world。

已持有资源的保持时间不是新的claim身份：准备期间只暂缓它自己的释放，不能因此堵住队列里其他已清除的资源。若安全中止时车尾已经经过其旧释放点，恢复旧边界后必须立即按当前已接受的车尾进度释放它；否则旧资源会无故长期占用。待处理的新增delta始终不进入可释放队列，直到设备`READY`并完成registry提交。普通车尾释放不重排队列；只在延长申请/完成/安全中止这几个低频边界重排剩余队列。

## 7. 性能、复杂度与退化行为

Let `R` be projected route slices, `B` route-bound catalog hits, `G` registered route contributors, `A` contributed resources, `H` resources in the final claim, `I` occupancy-query intervals, `C` bucket-selected swept-span candidates and `M` resources in all other claims/edit permits. Projection is linear in `R`; rail-indexed catalog lookup reads route occurrences plus binary-search/candidate scans, then merges/sorts hits. Each new/extended admission calls each of the normally few registered contributors once, so collection is `O(G+A)` plus their indexed lookups; it must not call every resource instance or execute per-resource JS. Physical interval and release ordering can cost `O((R+B+A) log(R+B+A))`. Occupancy comparison is worst-case `O(C×I)` after bucket selection. The current registry checks requested resources against other held resources pairwise, worst-case `O(H×M)`; it does not yet have a conflict index. These are request-time costs, not unchanged per-tick work.

Observation dispatch builds one train-ID lookup only when at least one live grant exists. With grants, ordinary cursor checks start at the current occurrence and queue inspection starts at the current head; releasing `K` eligible resources costs `O(K)` locally. The registry keeps the live claim in one lock-protected ordered identity set: after validating the complete release batch, it deletes those `K` identities in place instead of rebuilding the other `H` resources. Its average shrink cost is `O(K+P)`, where `P` is the pending-record count currently scanned to find this owner. With fixed `P`, releasing `H` resources one by one is cumulatively average `O(H)`; this is not a bound on conflict lookup or explicit diagnostic snapshots, which still copy `O(H)` resources on read. Completed admission handles remain repeatable while held by callers without a world-lifetime terminal-result table.

一次延长申请可读取该grant尚未释放的`H`个身份，把新增资源与已持有资源按exact identity分开；只有开始延长、完成或安全中止时才对剩余队列排序，成本为`O(H log H)`，不是每tick成本。按logical owner ID直接定位live grant，不为每列车延长遍历所有live grants。正常无pending extension时，前述车尾逐项释放仍从队首推进，不因这一功能退化为每次重建整个队列。

分次道岔检测在已有物理编译遍历中同时查询当前持有身份和发现本次范围重复，额外时间与空间均为本次道岔数线性；
实际持有查询复用本次申请已经建立的`remainingEntries` map的key view，不复制另一份历史集合或再遍历准备项。
没有每tick全路线重复检测、历史设备表或新的释放控制器。过长申请在claim前拒绝；调用方的有界重试至多增加一次申请编译。

折返是停车边界上的一次性低频操作。新body route只编译一次；已持有rail interval按rail和起点排序，并记录每个前缀的最大终点。每个route occurrence对这个单调的最大终点数组二分，扫描到起点超出body范围为止，再逐项判断真实重叠。滚动申请保留的区间可能嵌套，例如`[0,100]`与`[20,30]`；不能对起点排序结果的原始终点二分，否则查询`[50,60]`会漏掉仍占用的长区间。所有保留身份保持原样，只更新最后重叠位置对应的释放边界。stable identity按map查找，最后对`H`个新释放项排序。成本为`O(R log H + K + H log H)`，其中`K`是扫描候选数而非真实重叠数；极端嵌套时可达`O(RH)`候选扫描。正常physics tick不承担该重建成本，不添加通用区间树。

Unchanged state performs no request compilation by itself. SimpleATO/resource inspection decides when a request is needed. Missing indexes, stale revisions or resource-handler failure fail closed; Basic must not fall back to a live-world full scan that silently grants authority.

## 8. Good / Base / Bad cases

- **Good**：SimpleATO submits only route/train/authority; a Japanese-signalling contributor adds a flank turnout and later hold without SimpleATO knowing either identity or descriptor.
- **Good**：a different interlocking contributor emits route-table resources and approach locking without creating any Basic protection region.
- **Base**：a straight route has only mandatory rail intervals because every contributor returns empty.
- **Good**：a long claim releases one resource at a time; older diagnostic snapshots remain unchanged, while the registry removes only the named live identities.
- **Good**：the first request waits for a device with `committedGrant = null`; on `READY`, the route state installs its first grant and release queue using the already-established cursor.
- **Good**：after all committed resources have been tail-released, another request along the same frozen route reacquires a farther suffix without inventing a second route cursor.
- **Good**：an ordinary stop retains the last claimed interval, then appends the next forward mission under the same grant/cursor; pending or rejected preparation keeps the old route binding.
- **Good**：a signal-plugin block token is retained while stopping inside that block; continuation extends its release point, and a later full-prefix contribution for an already tail-cleared block is validated but not reacquired.
- **Good**：a stopped reversal freezes release, keeps the exact claim and grant ID, then places a new cursor at the reversed tail and recalculates old-resource release points without reacquisition.
- **Good**：a bulb line first receives an exact prefix before its second turnout traversal; after the first tail clearance, the same turnout is claimed and prepared for the return traversal under the same grant.
- **Good**：an initial grant ends shortly after the first turnout; while that use remains held, a retry can grow authority up to just before the second use, allowing the tail to clear the first.
- **Bad**：one long request prepares both turnout selections, or a historical-device set rejects reuse even after physical release.
- **Bad**：a held future turnout rejects every oversized extension with only `REQUEST_BUSY`, trapping the train at an old short grant despite available safe track ahead.
- **Bad**：SimpleATO constructs a protection-region ID, preparation kind or signal-system-specific argument.
- **Bad**：an initial safe abort leaves an owner permanently busy with an empty route state, or a later safe abort deletes an earlier committed grant.
- **Bad**：each tail-clear event reconstructs an immutable footprint of every still-held resource; a long route then pays quadratic cumulative release work.
- **Bad**：a plugin omits a traversed turnout or supplies another identity; Basic still derives the canonical turnout resource.
- **Bad**：Basic treats plugin contributions as authoritative replacement footprint or lets JS directly mutate a claim.

## 9. 必需测试与断言点

- Route projection covers AB/BA, zero/one/multiple turnouts, loops and route-bound diamonds with deterministic identities.
- Bulb-line tests cover an initially oversized request and exact retry ceiling, no mutation on rejection, first tail clearance, second asynchronous preparation and unchanged grant identity. Start one rolling case with the first grant only shortly past the first turnout: while that use remains held, an oversized extension must return a ceiling and its exact safe-prefix retry must succeed without switching or releasing the turnout. A ceiling that cannot grow authority leaves the old grant unchanged; a genuinely pending request remains `REQUEST_BUSY`. A competing train must still be denied while the physical resource is held. Repeat-diamond tests retain one identity through the last requested crossing.
- An intent with every contributor returning empty still produces the complete mandatory physical footprint.
- Neither caller nor contributor can remove/replace a derived rail, turnout or diamond resource.
- End-to-end admission on the two rails of one at-grade crossing must derive the same Basic diamond identity: the second claim conflicts while the first holds it, tail release permits later acquisition, and the crossing still creates neither a route continuation nor a selectable turnout.
- Two selected routes whose physical and contributed footprints are disjoint may hold grants concurrently even when an external signalling policy groups them in one region; an overlapping request must report the actual rail/turnout/diamond/additional resource, never an implicit region token.
- Valid contributions join the same atomic footprint; contributor failure, unknown/unauthorized resources and conflicts leave no partial claim.
- Occupancy geometry and preparation/release metadata never become alternate claim sets.
- Immediate and delayed preparation publish no grant before `READY`; unsafe failure retains the safest claim.
- First pending request has one route cursor and no committed grant; safe abort after successful rollback removes the unused route state, allowing a fresh request from a fresh observation.
- The same public `request(intent)` accepts a farther boundary on the identical frozen route or a strict forward continuation after the old route end, rejects a mismatched owner/train/modified prefix/disconnected append/reversal and a concurrent pending attempt, and keeps the same cursor and grant ID after successful growth.
- Reversal suspension stops observation-driven release before the physical direction changes; a valid rebase keeps grant/claim identity, proves retained coverage of the complete reversed body and resumes from a new-tail cursor, while an uncovered resource or invalid route releases nothing.
- 环线分次申请产生嵌套保留身份后折返：新stationary epoch只释放无body证据的身份，包住body的长区间必须保留至新车尾到达真实清除点。暂停期间普通扩展被拒绝，grant/claim和准备次数不变，释放保持暂停。
- Delayed `READY` on a newer safe epoch rebinds the grant stamp; an unrelated topology transaction still permits completion, extension and tail release. A transaction that actually intersects the route, a missing/moved train tail or a newly occupied requested range publishes no grant and retains the claim under Stall.
- A frozen route captured before an unrelated edit may make its first request after actual-current-topology validation; a now-missing rail or illegal connection is rejected. An intersecting edit during contributor execution cannot slip through the claim boundary.
- Two pending attempts cannot publish a grant after either one has stalled the world; the second claim stays retained and the Stall callback fires once.
- Tail release and same-route extension retain the existing cursor, release only a contiguous eligible prefix and never shorten plugin-specified holds.
- Extension filters only non-held contributions whose release boundary has passed the accepted tail cursor; held resources remain protected, while malformed/duplicate/unauthorized historical contributions still reject without registry mutation.
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

## Fixed formation contact and output ownership

`prepareFormationChange` binds exact live source grants, ordered car identities and output directions under one authenticated requester. It freezes their ordinary extension/reversal/end and tail release without releasing or transferring any registry owner. Only an unchanged unsealed event can abort. Core decision guards reject unmatched manual structural operations, including a held split child. Physical success installs separate Core output fences before publication; a coherent observation advances to `OUTPUTS_HELD`.

`requestCouplingApproach` requires the sealed host proof's exact body+contact traversal, contact boundary, positive speed cap and unchanged stopped target. It runs normal physical compilation and occupancy queries excluding only the requesting train, preserves retained preparations, acquires actual missing delta and requires explicit provider/contributor formation authorization. No peer occupancy or signal restriction is bypassed. A READY grant includes `maximumSpeedMetersPerSecond` and purpose-bound `couplingContactAuthority`; its actual route is published to domain owners. Core's actual solved collision contact must match the proof before the prepared link is admitted.

`bindFormationOutputs` validates every exact stopped output as one batch: unique owner, actual tail initialization, full ordered published body prefix, body-only authority, inherited source coverage and complete provider clearance declarations on the **complete selected frozen route**. Null clearance explicitly means not needed; missing/duplicate/unauthorized declarations reject. The operating coordinator owns ledger commit and every mission/disposition installation. Binding alone keeps all Core fences.

Before the first output fence release, `resumeFormationOutput` revalidates the exact stopped binding pose: direction, head/tail, ordered body traversal and complete car/bogie geometry must still match the immutable frame captured at binding. An intervening owner reversal or maintenance placement rejects without releasing the hold or any claim, even before the next observation consumption.

`requestFormationOutput` admits a farther output authority using its authoritative cursor occurrence+distance, compiling the current-tail-to-authority resource floor and head-forward occupancy. It cannot reacquire a historical prefix behind the tail, borrow another output's new exclusive delta, change retained preparations or infer provider permission. Pending READY is revalidated; safe abort rolls back only the exact reservation, while unsafe outcomes retain protection. `resumeFormationOutput` releases only that output's exact Core fence after READY forward authority; `REQUEST_BUSY` distinguishes safe waiting for preparation/forward authority or a peer still using shared originals.

Forward output extensions retain the exact committed grant ID and owner, including across delayed preparation. PREPARING returns the unchanged previous committed grant; READY updates its boundary/observation without resetting the consumer restriction stream. A moving tail may trim the safely protected resource prefix while preparation waits, but cannot add unreserved resources or alter preparation/clearance evidence.

Original resources shrink only after **all** outputs have complete-route last-use/tail evidence and no actual body still overlaps their rail intervals. Each output's exclusive delta has its own release queue. An output whose tail permanently clears every remaining original can detach independently while a peer remains HOLD: its existing owner/grant/cursor and exact exclusive queue become an ordinary route, without release/reacquire. Its old group stops interpreting that train's later direction or composition, so another prepared split can execute independently. Ordinary requests, reversals and successor formation floors must reject overlap with remaining old-group originals; those resources do not become the detached route's queue merely because the registry owner is the same.

The same registry owner may therefore contain an ordinary queue plus independently retained aggregate originals. A final own-queue shrink returning REPLACED is accepted only when the actual remaining footprint equals the engine's exact protected aggregate remainder plus any exact pending delta. No generic permissive remainder flag exists. Requester/provider activity remains visible for aggregate originals even after a detached source container or route no longer exists. Final group completion never overwrites a newer detached route.

`CouplingApproachAdmissionTest` exercises real straight/curved low-speed contact and retained owners; `FormationOutputAdmissionTest` exercises two-output conjunctive release, wrong-tail rejection, physical/resource contention, repeated prefix-safe extensions, independent HOLD detachment and a second real prepared split. These are focused runtime tests, not scheduled live-world acceptance.

### 零长度车钩的接触边界

Formation contact 的 occupancy 校验仅在私有编译调用显式携带当前 Core host proof 时，允许已封存 stationary
参与者在认证 contactHead 终点发生边界接触。检查当前 peer 的零速、位置和 body traversal，并逐项检查
其 swept span 与 movement query 的接近区间：只能在 contact rail 的终端边界相遇，不能穿入 peer body。
其他列车、其他位置的交叠和普通 output movement 仍被拒绝。完整 route 随后仍必须与 host 的 body+approach
路径完全一致，准备完成后再次核验；不能通过 submitted formationContext 自行要求豁免。

碰撞许可必须按 Core 的已发布帧语义识别最后一步运动：非零 head/tail traversal 可以伴随步末零速度。
不能仅因 approaching speed=0 拒绝一次实际制动到接触点的联挂；仍要求有限非负速度且不超过 grant cap、
非零记录位移、完整有效 host proof、精确参与者、静止目标和匹配 contactHead。没有位移的停放帧不因此获得许可。

`CouplingApproachAdmissionTest` 使用真实 Core 碰撞验证 connector=0 与0.3 的联挂，并对两种长度验证
错误 boundary、policy 拒绝和第三列车阻挡。Dispatcher 实际事件测试进一步覆盖零长度车钩、HOLD和继续运行。

After structural output publication, SimpleATO retires the consumed mission through `retireMissionForFormation()`: enqueue requires the current controller, a structural fence, stopped physical evidence and no active/queued station call. Retirement executes on the command consumer, preserving the controller and external restrictions; a later mission revision submitted before command consumption survives. It does not impersonate reverse preparation or release infrastructure.

Held-body equality normalizes only adjacent contiguous pieces of the same oriented rail occurrence and drops zero-length pieces; it preserves ordered revisits and compares traversal coordinates at the existing 1e-8 metre body precision, while head/tail and car poses remain exact. Structural coupling and a later stopped physics publication may segment the same body differently. Completed unsafe preparation remains available as cached terminal evidence after RECOVERY_REQUIRED, without advancing or restarting any preparation; safe abort removes only the new delta, whereas unsafe abort retains all reservations.

A still-fenced output bound only to its exact body may select its first departure by appending an immutable directed suffix: same route ID, increasing revision, exact occurrence prefix, old authority at body end, and no previously acquired output delta. Basic recompiles and reserves the suffix while retaining the body grant; safe abort leaves that grant and cursor unchanged. Only successful installation extends the cursor and replaces the clearance proof. Resume still waits for any moving sibling to clear retained originals. A later deferred sibling may select its own departure; resources already released must be reacquired. After departure, an exact immutable-prefix append is also allowed once authority reaches the old route end. Existing original last-use may extend while still retained; if this output had certified a resource clear, it cannot re-enter while a moving sibling still relies on that resource. Exclusive last-use boundaries are recomputed without shortening, and cursor/release evidence change only after READY. Newly required or previously released resources go through ordinary delta reservation; route replacement remains forbidden.

A structurally fenced output with no forward authority, pending preparation or acquired output delta may suspend release for an explicit reversal. Its selected route may already contain a suffix; suspension retains all original claims and blocks resume and resource release. Only the exact stopped reversal is accepted: swapped head/tail with reversed facing, reversed ordered body traversal, and unchanged canonical car/bogie positions, directions and normals. Core reversal changes operating direction, not physical render orientation. Rebase compiles a body-only route under the same owner/grant, requires inherited coverage and unchanged preparation obligations, and atomically replaces its cursor, held frame, clearances and expected output orientation. The operating coordinator updates the exact pending ledger owner in the same reversal callback; no historical source identity or physical membership changes.

### 非零车钩的完整接触保护

`requestCouplingApproach` 的可行驶路线仍严格是接近车实际车身加到 `contactHead` 的位移；
但联挂后发布的完整车身还覆盖两车钩间的连接段。只保护前者会让非零车钩的真实输出在
`bindFormationOutputs` 时出现未预约的物理区间。Basic 因此同时消费 Core 本次 inspection 的
`proof.protectedTraversal`，不能通过放宽 output body 覆盖检查忽略这段间隙。

完整保护路径使用独立派生 route ID，经过同一个 `TopologyRouteProjector`、
`PhysicalRouteResourceCompiler`、贡献资源校验与 formation provider 授权。区间、道岔选择、平面交叉
以及设备准备都由普通编译器生成，不手工添加 rail interval。新增物理 delta 仍归原接近车 owner，
原 stationary source 的预约保持不动；pending READY 时重新检查原 stopped sources、Core 几何和
同一完整保护路径的资源/准备要求、provider 授权。拓扑依赖同时包含接触路线和完整保护路径的所有
rails，所以贡献者执行期间改变连接段或目标车身轨道也会拒绝本次准入。

内部 `FormationRouteCompilation.Compiled.policyIntent` 保留 host 派生保护路径，供贡献与授权统一消费。
其 `intent`、`projection`、`routeProgressPath`、公开 grant 的 authority 以及实际 movement occupancy
仍对应原来的接触路线；保护路径的长度不能变成更远的行车许可。完整资源的释放元数据在结构操作
期间被冻结，实际输出绑定后由既有 output 车身和 route clearance 接管。

实际 movement occupancy 继续只容许旧规则中的精确目标接触边界。完整保护区间额外检查第三车；
对目标车也只接受同一 proof 中捕获的实际 stationary `occupiedIntervals` 所覆盖的交叠，并核对当前
目标零速、方向和完整车身未变。不能把整辆目标从所有 movement/occupancy 检查中排除。

`CouplingApproachAdmissionTest` 对零/非零车钩及曲线真实接触断言 movement authority 不变，普通
contributor 和 provider 均看到完整保护路径；在 connector bridge 中单独放置第三车会拒绝且不改
claims。非零车钩真实联挂后进一步绑定完整实际 output body，再执行下面的人工交还流程。

### 普通联挂结束后交回无绑定路线的人工控制

`finishFormationForManual(handle: FormationChangeHandle, outputGrantId: RouteGrantId): FormationChangeResult`
是已实现的 Basic 资源收尾能力，供一次真实完成的普通联挂使用；普通联挂的 SimpleATO 入口、配置和
运营记录收尾仍需单独组合，不能因这个接口已经存在而声称玩家流程已经完成。Basic 只结束自己的
结构预约和移动保持，不改 Depot 任务归属、不安装控制器，也不授予新的行车路线。

调用方先等待 Core 真正发布联挂结果，再通过 `bindFormationOutputs` 绑定实际完整车身；输出保持停车，
预先消费或停用原接近任务。随后调用此接口，Basic 在原来的串行 world owner、两个完整物理帧之间，
先检查真实输出与绑定时的停车证据，结束本事件的资源预约和全部 source/output admission state，
最后释放它持有的精确 Core `FormationMovementHold`。Core 完整已发布车身的实际占用始终存在；后续
人工驾驶使用原有未绑定运营路线的物理占用与 ATP。接口不计算、不伪造一小段前方授权来触发放行。

允许的状态严格是 `Couple` 的 `OUTPUTS_BOUND`：一个输出、精确 output grant、冻结路径只包含
车尾到车头的实际车身、已授权距离仍在车头，且没有 pending preparation、前方扩权、已获得的
exclusive delta、折返或已经放行的输出。单纯 body grant 不会因此获得正的可行驶授权。
当前 coherent observation 的 frame revision 必须与当前 Core output frame 相同；方向、车头、
车尾、归一化有序车身以及每车和转向架完整 pose 必须与绑定证据相同，并保持零速、未脱轨。

精确资源身份保存在既有 formation 的 originals 中。清理按原 owner 对这些身份调用
`shrinkClaimWithEvidence`，逐项从该事件的 originals 移除；不能直接 `releaseClaim(owner)`，因为
同一 owner 可能还为较早的独立 formation group 保留资源。清理只触及该事件被冻结的 source routes
及实际绑定 output route。所有 route 必须仍属于同一 authenticated requester、handle，且没有 pending
请求；最后才释放 Core hold，并将 phase 标为 `COMPLETED`。这些 handle、grant 和计数都是本进程运行
状态，不新增持久化字段或 schema version。

未知或外部 handle、错误 grant、Split、多输出、前方准备和改变后的停车证据返回
`ROUTE_STATE_MISMATCH`，不改变 claims、route state 或 hold；不支持此能力的 service 明确返回
`WORLD_UNAVAILABLE`。世界已经 Stall 时也不执行收尾。移除资源或发布 admission 清理时异常进入既有
`RECOVERY_REQUIRED`，已清理部分不会重新声称仍有预约，未完成记录及 output hold 保留以供恢复；
失败不会调用正常放行。此操作按涉及的车/转向架、source owner、资源和现有 route 数量执行一次，
不新增物理 tick 扫描、路线搜索或无限路线游标。

例如，两车真实低速接触后，普通联挂的唯一输出可以在完整车身绑定期间交回人工驾驶；错误地传入
原 approach grant 或在绑定后维护摆位则仍保留全部保护。计划继续按时刻表行驶的输出应继续通过
`requestFormationOutput` 和 `resumeFormationOutput` 获得真实后续路线，不能用这个接口清掉它的预约。
同样，body-only grant 不能直接转成无限制人工 grant：普通 grant 的车尾游标只能沿它冻结的路径推进。

`CouplingApproachAdmissionTest` 使用真正的 Core 碰撞联挂验证：正确停车输出收尾后 source/output
admission 消失、事件预约清空、精确 hold 解除，然后新手动接管测试控制器能够再次移动实际双车编组；错误
approach grant 与摆位后的输出均拒绝，原 claims 和 hold 保持。源编组的运营 assignment 交接及玩家
命令、真实手柄动力学与客户端操作尚不在这个 Basic 协议测试的验收范围内。测试控制器使用当前
commanded speed 计算本 tick 位移，不能在写 `speed` 后读取它的上一 epoch 公开值来代替当前指令。
