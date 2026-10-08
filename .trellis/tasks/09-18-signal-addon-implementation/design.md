# Stage 2 design

## Status and ownership

2026-10-07 用户批准 JS 规则目标，随后将统一包修正为元数据 `pack.yml` 加 `model/`、`logic/`、`signal/`：
文件相对路径派生稳定 namespaced modelId、logicId、signalType，玩家可见的一等 signal 文件引用模型与逻辑；
跨包引用两阶段解析。复用 GraalJS，但不依赖 `BukkitPlugin` 私有实现。
规则在物理快照之后的同步信号阶段读取完整分支树，扫描距离逐分支累计，不设节点／分支／时间／内存／整波预算；
包作者承担脚本性能责任。扫描遇到同一有向状态时仅停止该分支并把原因交给 JS，不采用 DAG 拒环、SCC 或 fixed-point。
这些拆分边界仍是 `TARGET`；SimpleSignals 的当前主信号规则已经是 JS-only catalog type，精确契约归
[JS 规则 owner 文档](../../spec/simple-signals/backend/js-signal-rules.md)，不改变既有资源／ATO owner。

2026-10-05 新决定取代下文早期默认进路分支：无匹配列车请求时保护信号保持停车。
出口放行还须验证一列车长度的空间，不能仅看资源授权；双向信号结果须与共享资源身份分离。
本轮分批落实，当前已批准边界见[修正批次](research/signal-comparison-corrections.md)。

This is a `TARGET` design with two ordered deliverables. The first concrete signal-system module, plugin and product name is `SimpleSignals`. Stage 1 public APIs are complete and archived; this task consumes them without adding parallel resource, inspection, restriction, applied-plan dependency or unload machinery.

```text
JavaRailways main plugin JAR
  Core + Basic + SimpleATO
    physical world + generic resource safety + operations/ATO/ATP
  BukkitPlugin -> SimpleATOBukkit
    one JavaPlugin lifecycle + embedded SimpleATO platform adapter

SimpleSignals
  independently installed signalling/interlocking model + its UI/persistence/customization
```

`SimpleSignals` is the first reference consumer, not a new universal layer. Other signal plugins can implement route tables, realistic interlocking, different block systems or no protection regions while sharing the same Basic physical safety floor and SimpleATO constraint pipeline.

## Deliverable 1: SimpleATO extraction

`SimpleATO` owns platform-neutral automated operating policy, ATO/ATP constraints, Destination/Station/Depot/Line/Timetable, rolling-stock assignment, operational route selection, applied timetable state and persistence. Generic pathfinding implementation, longitudinal dynamics, actuation primitives and physical/resource validation shared with manual driving remain in Basic; operating costs/constraints and the selected route belong to SimpleATO.

用户已批准轨道公共方向归 Basic，取代此前将所有运营方向归 SimpleATO 的决定。迁移直接移动现有稀疏存储，
不重写搜索器、不复制存储或为手动驾驶增加强制限制；规划仍由 SimpleATO 组合显式限制后调用 Basic 一次。
用户确认这些格式从未部署，因此直接修正当前DTO，Basic保持format2、SimpleATO保持format1，
不增加旧方向字段的搬迁转换或兼容reader。已有历史metadata转换只更正输出owner，独立09-24世界格式任务不扩入本批。
具体机制归[方向契约](../../spec/javarailways/basic/operating-directions.md)。
`BasicWorldData.operatingDirections` 保存唯一状态；SimpleATO、宿主只读 API 和信号插件读同一份公共配置。
变化回调在世界创建时接到已有信号 epoch，不增加另一个监听框架。

`SimpleATOBukkit` is a separate Gradle source/test module but not a separately installed plugin. `BukkitPlugin` directly composes it and includes its classes in the JavaRailways shadow JAR, so there is one `JavaPlugin`, one descriptor and one server-plugin lifecycle. The adapter module owns only platform behavior specifically required by SimpleATO: startup wiring and commands/diagnostics/presentation belonging to the operational stack. It does not own generic host rendering or Folia scheduling, does not provide a presentation runtime to independent signal plugins, does not use Bukkit service discovery to find its own host, and does not own protection regions, fixed blocks, national signal aspects or a mandatory JS engine.

`BukkitPlugin` keeps RailWand, manual driving, the final host world composition and other low-level tools. It calls the adapter module at explicit lifecycle points rather than delegating lifecycle ownership. Each migrated slice moves its contract, implementation, tests and composition together; the old production owner is then removed.

### Operational route restrictions

Basic owns the deterministic search over one immutable `TopologyState` and a physical-neutral restriction contract.
Before search, SimpleATO reads Basic's public operating directions and composes them with contributions compiled by installed owners. A contribution
may forbid a directed canonical rail span or replace free traversal through several spans with one or more complete,
physically valid paths. Basic validates these values, builds one query-local graph and represents each complete path as
one atomic macro edge. Dijkstra therefore cannot splice the first half of one table route to the second half of another.

The interface contains no region, block, signal, interlocking or resource identity. SimpleSignals may use the same
authored protection-region boundaries for its private route table, but only the neutral spans and complete paths cross
the module boundary. Plugins are invoked only while compiling immutable contributions before search; graph expansion
does not call plugin code. The selected macro expands back to a normal frozen route for Basic's independent physical
validation and later resource admission.

The host exposes owner/world replacement through `RoutePlanningRestrictionSourceService`. It shares the existing signal
source registry, world binding and unified infrastructure-owner unregister gate. SimpleATO captures sources in stable ID
order, invokes each exactly once for a query and combines them with operating direction before calling Basic. Route-planning
callbacks are configuration, not live train restrictions: forced disable removes them without stalling solely for their
presence, while frozen routes, claims and applied-plan dependencies retain their existing safety lifecycle.

## Deliverable 2: SimpleSignals

### Domain model

Conceptual shapes, not frozen public Java names:

- `SignalDefinition`: stable ID, logical section/entry binding, optional model reference and rail/ground placement, plus plugin policy configuration. Display placement does not replace the logical entrance.
- `RegionBoundary`: stable ID, canonical rail position, direction and entry/exit role.
- `ProtectionRegion`: stable ID, boundary set and definition revision; its disposable resolved snapshot additionally records the topology revision.
- `FixedBlock`: stable ID/revision and one complete ordered directed physical traversal; its length,
  occupancy geometry and route-local reverse index are derived against one exact topology root.
- `SignalDependency`: occupancy/grant/section/downstream-signal dependency used for reactive invalidation.
- `SignalState`: restrictive limit, model variant and diagnostics derived for one train/direction.
- `SignalWorldRuntime`: immutable definition/index snapshots plus current dependency graph and render handles.

Stable persisted `kind/type` values represent capabilities; Java/Kotlin class names and `V1/V2` implementation names are not persisted polymorphic identities.

### Protection-region authoring and indexing

当前边界 Wand 每次放置有向边界都自动从内侧尝试识别完整区域，
不要求预先选中区域或进入修复模式。补全旧区域时通过保留的边界关系识别原区域，沿用其身份与配置；
定义暂时不完整时保留配置并禁止 ATO 驶入，恢复须重新校验。工作方式、作者状态与搜索上限见
[工具契约](../../spec/simple-signals/backend/section-authoring-wands.md)。轨道结构编辑另由下面的事务通知驱动。

轨道重验采用用户确认的事务驱动方案：轨道编辑事务给出受影响轨道，SimpleSignals 利用轨道到区间的关联索引
取出候选并去重，再在编辑后的拓扑上重新验证。删除需要旧关联，新增连接需要包含接入的既有轨道；
不可用对象仍保持修复依赖。重验在 worker 执行，相关旧结果须在新拓扑被运营使用前停止放行，
无关区间不能跟随停用。实现机制由[区间局部重验合同](../../spec/simple-signals/backend/section-topology-revalidation.md)维护；
批次验证结果见 `implement.md`。
用户已批准跨层替换运行时的世界 topology revision 失效判断：以事务影响集合及各对象轨道依赖作为有效性依据，
不重置未受影响占用、不重新规划未受影响冻结路线、不丢弃已有授权；真正受影响的数据在重验前不得放行。
世界版本继续标识不可变快照；只有编辑候选提交仍以 exact root 防止并发覆盖，不增加各轨道的版本计数。

On create/update/load, the plugin captures one immutable `TopologyState` root, cuts directed traversal at every declared boundary and flood-fills from the explicitly selected interior side. It rejects an unbounded escape, multiple candidate interiors, invalid facing, missing rail positions and unsupported overlap before publishing the new definition snapshot.

The definition stores boundaries, not a second authoritative subgraph. Derived interior membership, rail-to-region reverse lookup and entry/exit route indexes are disposable caches. Definition edits replace their publication; rail transactions invalidate only intersecting dependencies and rebuild affected geometry outside the per-tick path. The captured topology revision identifies the compilation input, not a world-wide expiry condition.

The first release treats one region as one throat/interlocking area. It does not model nested/overlapping regions or internal temporary stopping points. If a future signal system needs those features, that plugin may use another model without changing Basic.

### Runtime route decision

SimpleATO's existing shared perception and inspection scheduler report which indexed signal resources are entering inspection range on the frozen route. `SimpleSignals` evaluates only those resources and its declared downstream dependencies; it does not add another train-forward scanner.

For a protection-region entry, the plugin:

1. matches each visit on the frozen route to a valid entry, exit and continuous interior traversal; repeated visits do not become different physical resources;
2. proves the requested boundary is far enough for the authoritative train tail to clear the plugin's exit condition;
3. chooses any farther territory required by its own policy—such as a whole following fixed block or an exact moving-block boundary;
4. adds plugin-owned resources such as flank protection, approach locking or block tokens;
5. submits the route, boundary and additions to Basic.

Basic independently derives every traversed rail, turnout and diamond resource over that range. The plugin cannot delete them. A region ID is not automatically claimed as one exclusive token; conflicts come from overlapping physical resources and explicit additions. This allows two disjoint paths within a large throat when the plugin policy and actual conflict resources permit it.

The current runtime slice compiles each complete region window into an entry-triggered minimum authority end. The end is
normally the region exit; when the exact frozen route contains one directly adjacent fixed block it becomes that block's
exit. A route fork has already been resolved by the frozen route, so multiple same-coordinate matches mean conflicting
block definitions rather than alternative branches. The binding does not recursively follow later blocks, does not claim
the region identity and does not yet choose a moving-block boundary. The executable mechanism and failure contract live in
the owner spec: [protection-region authority transition](../../spec/simple-signals/backend/protection-region-authority-transition.md).

Basic either claims the complete union or none. A confirmed Basic grant is necessary but not alone sufficient for a permissive signal: the plugin may apply a stricter speed/stop, and SimpleATO takes the most restrictive result with following, operational stops and infrastructure limits.

### Fixed blocks and transitions

用户现已批准单线单闭塞的双向控制。通过默认关闭的 `bidirectional` 字段，复用既有匹配、
入口检查和 Basic 资源生命周期；两端使用同一 block token 和占用状态。
现已解除共享主信号的旧限制，各入口独立绑定规则和显式模型；反向路径、拒绝条件、编辑影响和验证统一见
[双向固定闭塞合同](../../spec/simple-signals/backend/bidirectional-fixed-block.md)。
工具/测距和单线多闭塞协调不随本批扩展。

方向一致性与原低速降级曾通过独立复核及受影响模块的离线门禁。用户现已取消低速降级：
方向失配与在线结构编辑失效统一保留区间配置并禁止 ATO 驶入，修复并重新校验后恢复；现已实现并通过离线验证。
删除 `disabled-block-speed-kmh` 和仅服务于该降级的逻辑，不保留旧策略开关。
Basic 保存公共运营方向，
宿主只提供一次读取相关轨道方向的公开接口；方向修改从原有信号更新流程通知受影响闭塞，
不逐 tick 轮询所有定义。SimpleSignals 对整条闭塞路径校验方向，并明确拒绝有效单向闭塞的反向申请。
SimpleSignals 将方向失配作为区间不可用原因，路线校验和资源准入必须明确拒绝，不能把它从索引中移除后当作无信号线路通过。
资源仲裁和车尾释放不另建实现，也不因区间不可用而丢弃已持有资源。
用户明确要求手动驾驶保持原样，本批只约束 ATO，不加入手动限速、自动制动或 ATS，也不删除现有手动安全行为。
完整机制与验收归[双向固定闭塞合同](../../spec/simple-signals/backend/bidirectional-fixed-block.md)，
在线拓扑修改的失效与恢复归[局部重验合同](../../spec/simple-signals/backend/section-topology-revalidation.md)，
不能将历史低速门禁作为新策略的验证证据，也不能据在线验证推断启动恢复或坐标迁移已完成。
实现证据见[实施记录](implement.md)；[研究记录](research/fixed-block-direction-policy.md)保留改动前的源码依据，不代表当前模型或真实服务器验证。

Fixed blocks are plugin-owned definitions. Entering one normally adds its whole block resource and holds it until the train tail clears the plugin-defined boundary. Protection regions and fixed blocks may implement a common plugin-local read surface—occupancy, length, grant state and downstream limit—but this interface is not a Basic `AuthoritySection`.

The current foundation compiles each block only on load/authoring. It validates the ordered traversal against one exact
`TopologyState`, derives whole-block Basic occupancy geometry with directed entry/exit boundaries, sums its physical length,
and publishes a rail-to-block reverse index together with the other world definitions. `fixedBlocks` was added to the
sidecar with an explicit empty default and did not independently bump its version. Directional-entry rules use the same unpublished schema 1; the owning contract records the current shape and version correction.
Protection-region observation remains a later batch.

Fixed-block route admission now uses one plugin-owned `simplesignals:fixed-block` resource type and the existing host
provider/contributor seam. On each new or extended admission, SimpleSignals projects the frozen route once into route metres plus
canonical AB coordinates, discovers only blocks indexed by rails on that route, and matches the requested authority prefix against
each directed block traversal. A route may start or stop inside a block; a platform does not have to coincide with a block boundary.
Whole-block resource ownership is separate from movement authority: a train may hold the token while its authority ends at an internal stop.
The current partial-block batch keeps one stable block identity and extends its release position through the existing grant queue.
Signal declarations represent actual authored entrances, not clipped resource-overlap starts. Internal starts create no entrance declaration/floor;
the existing head-forward inspection scheduler excludes real entrances already behind the head. Resource contribution and held claims remain independent,
so this simplification requires no change to Basic's frozen-route origin, first bootstrap, incremental request range or release queue.
The reviewed repeated-fixed-block batch distinguishes inspection visits by stable declaration ID plus route trigger position, while dirty
notifications keep the stable ID. Each visit retains its own entry floor; the concrete fixed-block source projects the nearest active visit
to one restriction per signal. Resource contributions coalesce only windows covered by the current requested prefix, using the original
block token and the latest covered exit. No occurrence-specific resource ID, public field or second release queue is introduced.
Repeated complete-region selection and Basic's repeated turnout/diamond handling are covered by the bulb-line batch below;
fixed-block matching alone must not be presented as proof of that device lifecycle or return-to-depot support.
Basic already retains resources and rebuilds the route coordinate at stopped reversal. Shared block-token correspondence is now the approved
bidirectional single-block batch above; broader return-to-depot behavior must not be described as complete merely from that slice. Basic independently adds the physical rail, turnout and diamond
floor and atomically claims the union. The exact current matching, authority and release bounds belong in the SimpleSignals owner spec;
the current partial-endpoint, repeated-fixed-block and continuous bulb-line behavior is recorded in the respective owner contracts.

The first common live section slice now covers fixed-block occupancy only. On world installation,
SimpleSignals groups every compiled block area into one host `SignalEpochSource`; Basic derives the
baseline and changed occupants plus directed ENTER/LEFT crossings in the coherent physics epoch.
The plugin maps changed area IDs to immutable section observations containing the block identity,
definition revision, compiled length and conservative occupants. Before the first baseline the
snapshot is unknown, and an unchanged epoch does not invoke plugin code or replace the snapshot.
Catalog signal types map this state plus downstream outputs through JavaScript to an opaque model variant and speed; changed outputs invalidate only
the same source's current route declarations, whose complete active set produces route-position speed restrictions through SimpleATO's existing
most-restrictive pipeline. A placed primary stores only its reusable catalog `signalType`; that catalog type selects one model and one JavaScript
logic definition. Occupancy, section length, branch selection and downstream signal limits are projected as immutable same-epoch input, while speeds
and model variants remain script output rather than a hard-coded national vocabulary. Protection regions join the same private read
surface only when concrete interlocking rules need them, because a branching region has no useful
single physical length.

Live fixed-block geometry editing now uses the host's low-frequency atomic source replacement. The
plugin compiles the full candidate first and acquires the existing edit permit over the changed definitions' old/new geometry
and structural dependency consumers; unrelated definitions do not enlarge the permit. The impact contract lives in
[the owner spec](../../spec/simple-signals/backend/definition-edit-impact.md). Without acquiring a cross-world simulation lock,
the host prevalidates the complete source union, then a
SimpleSignals callback rechecks the exact topology root, commits the staged sidecar and swaps one prebuilt runtime-state reference containing both the
definition publication and section occupancy runtime; the host finally installs the already compiled
area state. Host prevalidation never invokes the callback, while callback failure preserves the old
source and publication. Deleting the final block installs an empty epoch-source set without removing
the plugin's route-planning registration. This restores runtime authoring on non-operational rails
without a rollback state machine, placeholder area or persistence compatibility path.

Fixed blocks and protection regions are alternative section boundaries in this model: after all candidate catalogs compile,
the world runtime rejects any positive-length overlap between a block occupancy interval and a derived protection-region
interior before its single publication swap. A shared cut point is allowed. This is a low-frequency cross-catalog validation;
it is never repeated per tick. The foundation deliberately leaves fixed-block-to-fixed-block overlap unspecified until the
semantics of opposite-direction definitions are frozen.

The protection-region to fixed-block transition follows the
[owner code-spec](../../spec/simple-signals/backend/protection-region-authority-transition.md): it joins route-local windows
only after the exact route is frozen and feeds one expanded prefix to Basic's atomic admission. Moving-block territory remains
a later plugin-policy boundary; the region object stores no successor and does not recursively call one.

Protection-region signal selection follows the separate
[signal and route-display owner contract](../../spec/simple-signals/backend/protection-region-signal-selection.md). One region does not expose a
single occupancy bit: each authored fixed route has its own occupancy/resource/turnout projection. Default routes are removed.
A current signal selects only the real train request at its entrance and remains restrictive until Basic publishes a grant covering
the complete region floor and the physical continuations agree. A fixed-route signal requires the same conditions for its own
matching current visit. No request means stop for both signal forms and no route for indicators.
Presentation-only indicators and distant previews share the selection snapshot and differ only by player placement and model.
Request information comes from immutable Basic admission observations, not owner-ID parsing or registry polling.

### Reviewed bulb-line traversal batch (CURRENT, follow-up audit fixes)

A bulb line is a continuous route, not a stopped reversal. The same turnout may therefore require
different selections at two route positions. Preserve its physical identity and the existing host
claim/release lifecycle: clear the first use before claiming and preparing the next. An initial
lookahead that reaches both uses must not leave the train waiting before it can clear the first.
Basic may reject with one optional neutral safe retry boundary; it must not silently shorten an
exact-boundary request or interpret a protection region. SimpleATO uses its compiled request floors
to retreat that boundary outside any indivisible traversal, then makes at most one local retry.
Protection windows remain occurrence-local, while the shared entry selector uses existing directed
tail-LEFT boundary facts to finish a visit. No second resource queue, per-tick progress stream,
occurrence-specific resource identity or persisted field is introduced. The owner contracts are
[Basic admission](../../spec/javarailways/basic/route-resource-admission.md),
[SimpleATO request floors](../../spec/javarailways/simpleato/operational-routing-and-authority.md) and
[protection selection](../../spec/simple-signals/backend/protection-region-signal-selection.md).

The independent follow-up audit reproduced a short-grant deadlock when a later request crosses a
still-held turnout. The approved correction uses the same neutral retry ceiling for both repeated
uses within one request and a future use held by the preceding traversal. This permits authority to
grow before the blocked use while retaining the first claim. No SimpleATO runtime branch is needed.

The subsequent replacement correction prepares independent Basic geometry while retaining physical
continuity for unchanged areas, and moves the plugin's unchanged-clearance handoff from edit commit
to the first serialized new signal callback. Old captured callbacks may finish without waiting for
the commit; the new full source baseline preserves real movement events for unchanged geometry.
One world-local last-processed section reference and worker-canonicalized unchanged route references
avoid replacement chains or copying mutable progress on edit workers. The owner mechanisms belong
in [Basic occupancy](../../spec/javarailways/basic/railway-area-occupancy.md),
[signal epochs](../../spec/javarailways/simpleato/reactive-signal-epochs.md) and
[protection selection](../../spec/simple-signals/backend/protection-region-signal-selection.md).
A genuinely cold or changed baseline remains restrictive and cannot infer a visit from equal
physical coordinates. This batch does not change storage, national rules, grants or resource release.

Focused runtime tests plus Dispatcher wiring regressions passed. The final offline affected-module
gate passed with 1363 tests in 252 suites: 1356 passed, 7 existing skips, zero failures/errors.
Java consumer compilation, plugin JAR isolation and diff checking passed. The real Core turnout
test drives coherent physical observations; no Minecraft/Folia end-to-end smoke was performed.
Those historical passing tests did not cover the three independently reproduced audit cases above.
The held-turnout prefix correction subsequently passed 99 focused tests and the offline affected-module
gate (1366 tests reported, 1359 passed, 7 existing skips, no failures/errors; Core/WebUI results up-to-date).
The replacement correction passed independent review and production-class composition regressions
for both handoff orderings, first-frame LEFT, repeated edits, changed geometry and failed commit.
The final offline affected-module gate reports 1377 tests in 253 suites, 1370 passed, 7 existing skips,
no failures/errors; Core/WebUI reports were up-to-date. Java consumer compilation and plugin JAR
isolation passed. Constructed physical frames/admission values are not a live Folia smoke test.

### Inspection and reactive updates

Stage 2 consumes the reviewed declaration, single-scan, activation and fail-closed contract from
[`operational-routing-and-authority.md`](../../spec/javarailways/simpleato/operational-routing-and-authority.md) rather than defining a second cache model here. The concrete signal plugin compiles
its per-train/route inspection declarations and owns any downstream reverse-dependency propagation; SimpleATO activates matching
declarations with preparation lead times; SimpleATO converts them using current train motion, activates declarations through its
one shared perception, and owns current-speed braking. The plugin does not reevaluate all signals or run
arbitrary JS every tick. Scripts receive immutable views and append restrictions/model choices to an evaluation-local decision;
they never mutate the Minecraft world or Basic registry directly.

The removed built-in providers historically used a precompiled one-way invalidation graph. The current JS-only runtime
`TARGET` must not generalize that implementation restriction into a rail-topology DAG. Loading or editing a signal prepares a
read-only forward observation plan from the existing section/topology indexes; an epoch fills occupancy, downstream output,
turnout selection and protection-route readiness from one coherent post-physics frame. The plan presents all candidate continuations
as a tree-like view, and the logic definition's scan distance is accumulated independently on each branch. Runtime evaluation does
not run Dijkstra, query live Core topology, invoke another ATO scan, or flatten the selected continuation into the only visible path.

The rule callback executes synchronously inside the serialized signal phase after the physical frame has been captured and before
that epoch's signal publication is completed. Package discovery, parsing, source compilation and observation-plan preparation may
run off-tick; the actual `evaluate` call is not delegated to an asynchronous pool. Graal contexts are used serially under their owner;
this task does not require one context per signal or a bounded context pool.

While expanding one branch, the scanner tracks the complete directed scan state required to distinguish direction, entry and selected
protection-route context. Repeating that state creates a terminal cycle leaf: that branch is not expanded further, its termination
reason is exposed to JS, and sibling branches continue. The cyclic edge does not create a recursive downstream-output dependency.
The compiler marks the segment between the first occurrence and the repeated state; signal occurrences on that segment expose
same-epoch physical facts and the cycle terminal, but neither current-wave nor previous-wave downstream signal output. This makes
the result independent of which root is evaluated first instead of using an old permissive value to break the recursion. Acyclic
dependencies outside that segment may still consume an already determined current-wave downstream output.
No SCC iteration, fixed-point convergence, watchdog or stale permissive fallback is added. Other terminal reasons such as distance
limit, track end, unresolved route or unavailable topology are likewise visible through the owner contract without freezing their
source-level field names here. Output changes still use existing reverse indexes to wake affected upstream rules, and unchanged
validated output still stops propagation.

### Rendering and customization

独立道岔 Wand 已完成，作为后续录制当前进路的操作前提；信号规则预设/模板暂时搁置。
工具由 BukkitPlugin 负责，复用现有 `InfrastructureResourceService.selectTurnout`，不移动或复制 Basic 的安全切换逻辑。
该批只选择来向和出口、显示真实连接及拒绝原因；区域通路预览与录制由后续 SimpleSignals 工具完成。
边界和验证见[道岔工具实施说明](research/turnout-wand-batch.md)。

现已接入保护区域当前通路预览：沿只读道岔选择得到一份实际通路结果，相关事件异步更新，
不申请资源、不自动切换道岔、不保存进路。预览与未来录制共用这份结果；本批范围和合同见
[预览批次](research/current-route-preview-batch.md)与[预览规范](../../spec/simple-signals/backend/current-route-preview.md)。

现已在已有预览上增加显式录制命令：保存显示过的完整路径，首次建立固定进路表、之后追加，
不设置默认进路、不申请资源、不重新寻路。范围见[录制批次](research/current-route-recording-batch.md)，
冻结时点、校验及失败语义见[录制契约](../../spec/simple-signals/backend/current-route-recording.md)。

当前批次优先完成 SimpleSignals 自有的闭塞和保护区域 Wand，直接复用已有定义事务。
独立信号机放置、测距和规则预设不作为这两个工具的前置条件，见[本批实施边界](research/section-wands-batch.md)。

SimpleSignals 自己负责信号模型包、模型操作对象、RailObjectWand 模式、命令、权限，以及可选的 JS/YAML 配置。
显示侧读取不可变的信号结果，只在外观或摆放状态变化时更新模型。显示绑定将通过已实现的通用公开接口复用主插件的模型系统，
不另写一套渲染器，也不把信号模型伪装成列车。

JS 目标包的作者目录固定分为元数据 `pack.yml`、`model/`、`logic/` 和 `signal/`。`model/` 保存外观资产；
每个 `logic/<path>.yml` 与同 stem 的 `.js` 保存逻辑元数据和源码；每个 `signal/<path>.yml` 是玩家可见
的一等类型，引用一个 namespaced modelId 和一个 logicId。三类 ID 均由包名与安全相对路径派生；
跨包 signal 引用两阶段解析。`model/**/*.yml` 通过 BukkitPlugin 公共普通模型 compiler materialize；同包 model import
只贡献目标 parts 的 identity-position group，不继承目标 default、variants 或 placement，首批不支持跨包 model import。
既有 `manifest.yml/models/parts` loader 也复用同一 compiler，但两种目录发现与身份合同仍保持独立。
现有世界 `FixedBlockSignalDefinition.signalId`／`ProtectionSignalDefinition.signalId` 表示已放置逻辑节点；必填的
`signalType` 表示上述 catalog signal 类型，不能按同名、模型或路径猜绑定。catalog 来自文件包还是未来附属插件不进入世界定义。
这是未发布 current model，保持版本1且不读取此前 foundation 布局，不能为开发提交建立兼容代际。

[主插件模型契约](../../spec/bukkit-plugin/backend/model-presentation-api.md)描述按 ID 查询轨道曲线、
构造普通模型、安排目标线程更新和清理实体；[信号模型绑定合同](../../spec/simple-signals/backend/model-presentation.md)
描述 SimpleSignals 如何解析模型包、计算摆放位置、映射外观，并在完整信号结果可见后提交显示变化。
目前已经实现按 UUID 查询不可变轨道曲线、公开不可变普通模型定义、内部构造和完整场景应用，
以及公开 `ModelPresentationService`、`ModelHandle`、目标线程调度和清理完成通知。
SimpleSignals 已接通目录发现、设备创建、完整信号状态到显示变化，以及卸载清理。用户批准将实际模型对象
与逻辑配置替换分开，只更新增删、换模型或摆放变化的设备，保留其他对象及显示序号；完整并发/失败合同归上述信号模型契约。
用户已确认 `default`、轨道附着/地面摆放、方向相关偏移，以及选择预设时
复制为独立实例参数的规则；具体语义统一放在信号模型契约，不改变宿主模型定义或内部 `Unavailable` 状态。
首批已完成无文件读取、无实体操作的预设应用和摆放计算，输出既有 `ModelPose`，并通过真实曲线测试。
完整信号包已按[模型包契约](../../spec/simple-signals/backend/model-packages.md)实现目录加载与校验；
设备磁盘字段已批准：三类设备共用可选的模型引用与轨道/地面摆放，删除显示器的重复位置字段，
复用已有定义编译、revision、编辑许可及 sidecar 事务。具体形状、校验和测试只在信号模型契约维护。
模型存档经用户批准直接修正尚未发布的 schema 1；删除默认进路与两端独立规则保留，误升的2/3按用户要求恢复为1，
不保留旧草稿字段或人为增加中间版本；
任意柱高等模型实例参数推迟，未来使用独立于灯色的字段。生产绑定的离线服务调用测试
不等于真实 Folia/客户端显示验证已经完成。
SimpleATOBukkit 不参与这条流程。

前一批经用户确认，已实现主信号与附属信号机的后端关系。附属设备保存直接主信号引用和独立模型摆放，
复用已有主信号结果及模型对象生命周期，不成为新的规则节点、检查声明或资源。
存档新增默认空集合，复用现有管理员草稿和定义提交；精确字段、索引、失败行为和测试统一归
[模型显示合同](../../spec/simple-signals/backend/model-presentation.md#主信号与附属信号机)。
工具、测距、样式编辑界面及轨道可视化下一批处理；双向入口由上述单闭塞批次提供，单线多闭塞运行支持仍推迟。

普通模型值契约已经审查并实现：附属插件捕获安全路径下的 YAML，主插件公共 compiler 展开模型包并返回不可变容器/物品树，
以完整场景选择恢复或替换外观。创建时建立动画/场景索引，提交前完成全部校验，再更新旧、新命中部件的并集。
具体变换、动画匹配、复位、资源键和零插值规则只在上述主插件契约中定义。
本批不改变旧列车动画或包格式；附属插件使用公开 handle 提交值，由主插件调度，不能直接调用内部普通入口。

第一批管理员编辑功能已经提供 inspect/export/validate/apply 命令，操作独立的当前格式 JSON 草稿，
复用既有配置修改事务。单独验证不会预留资源，因此不能保证后来真正应用时仍允许编辑。
命令、版本、文件和线程行为见[编辑命令契约](../../spec/simple-signals/backend/authoring-commands.md)。
交互式 Wand 与真实平台显示验证仍为后续批次。

主插件不规定 `return "YG"` 这样的规则接口，Java/Kotlin 代码也不写死灯色与速度的对应表。
规则提供经过校验的非负有限限速值；外观名称不能为空，也不能只包含空白字符。插件自己的自定义接口可以提供
`setSpeedLimit(...)`、`setModelVariant(...)` 等 evaluation-local setter；没有执行预算，完整树形只读输入和
终止原因由 [JS owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)维护，任务文档不另行冻结字段名。
规则失败时发布不可用状态，对应的安全限速为零，不自动编造一个外观名称。

## Timetable apply and editing

SimpleATO already compiles selected automatic timetable routes into immutable candidates before service start and installs the host's static applied-plan dependencies before clocks become visible. Stage 2 reuses signal-plugin route matching/binding for those selected routes and collects the corresponding definition/resource edit dependencies in the same preparation. It does not add a separate signal-audit framework or repeat configuration validation already completed at authoring time. Transient red aspects, occupancy and live claim conflicts are waits rather than apply failures; partial fixed-block traversal and repeat visits are not intrinsically invalid, and the plugin does not repeat pathfinding.

Reviewed implementation seam: the existing `InspectionSource` may provide a pure
`routeInfrastructureDependencies: (RouteSnapshot) -> Collection<StableInfrastructureResource>` callback
(empty for sources without additional resources). `InspectionRuntime` collects and deduplicates these
identities from already selected routes without binding a train, inventing an observation stamp or
executing a signal update. SimpleSignals reuses `FixedBlockRouteMatcher.matchRoute` and returns its
registered fixed-block identities, including internal starts/stops and repeated visits; protection regions
reuse one publication-owned route-binding compiler to validate complete windows and optional authored route tables,
then return no artificial region resource identity. Dispatcher first installs a non-exclusive union of the new
physical route dependencies and the previous plan's complete dependencies, then collects current plugin
resources and atomically replaces that guard with the final declaration. Callback/topology/identity
failures restore the previous declaration (or remove the first-apply guard) before publishing clocks;
the existing registry validates identities and arbitrates
against overlapping edit permits. No extra registry, claim, persistence field or compatibility constructor
is introduced. Exact owner contracts and tests are recorded in the SimpleATO and SimpleSignals specs.

Reviewed ordering correction: asynchronous edits can finish between an unprotected collection and
installation without changing token identity or topology revision. The user approved establishing
selected-route protection before final matching, retaining old dependencies during replacement and
restoring them on failure. Reuse the existing registry and rollback bookkeeping, not generation tokens,
claims or a second lock service. Keep the installed guard tracked if restoration fails and use the
existing infrastructure-failure safety path. Deterministic Dispatcher + real matcher/registry regressions
verify both edit orderings and checkpoint restore; injected failures cover first/replacement recovery.
An identical final declaration reuses the installed guard. Live Folia thread scheduling remains unverified.

After rechecking revisions, SimpleATO extends the existing atomic declaration of exact rail/node/runtime-resource edit dependencies with referenced SimpleSignals resources and installs the applied plan. Replacement atomically replaces the complete declaration. Unapply enters `DRAINING`, stops new departures/grants and removes dependencies only after live runs/claims clear or explicit administrative recovery succeeds. These non-exclusive edit dependencies are not train resource claims.

Plugin definitions and operational objects referenced by an applied timetable are represented by their owning plugin's stable runtime-resource IDs in the dependency declaration. Editors submit the exact affected rail/node/resource identities to the common host edit permit before mutating topology, regions, blocks, signals or referenced operational objects.

SimpleSignals definition authoring uses one plugin-local patch transaction rather than exposing mutable catalogs. A patch checks an expected
world-definition revision, creates one complete revision+1 candidate, and rejects empty/redundant/conflicting operations before host coordination.
The runtime prepares fresh region, fixed-route and fixed-block catalogs against one exact immutable topology without changing the live source. The prepared
value retains its base publication identity and old/new exact rail impacts, including boundaries, resolved interiors and route traversals.

The manager acquires the existing host edit permit for the union of old and new rail impacts and rechecks the exact topology root. Before entering
the publication callback, its worker encodes the complete sidecar, validates/copies the old primary and writes same-directory staging files. The
callback rechecks the base publication and topology, atomically renames the staged primary, then swaps the already-built plugin and host source
references. Backup rotation and staging cleanup happen after publication. Rejected patches, catalog validation, permit conflicts/staleness and
persistence failures leave the old source unchanged; no second lock service, topology-wide lease or authoring token exists.

Online editing has one cross-module latency rule. Preparation may perform work proportional to definition or topology size, but it runs on an
asynchronous worker. Final commit is scoped to the affected world and may only perform stale/permit checks, an already-staged atomic rename and
immutable reference swaps. It must not hold the cross-world simulation lock, rebuild a graph/index, encode a document, perform an ordinary file
write, wait for a future or invoke Bukkit side effects. An in-progress signal epoch therefore finishes with its captured old immutable source and
the next epoch establishes the new geometry baseline. Rail editing follows the same rule for curves, exact diamond geometry, normalized topology,
whole-world undo values and the rail-render sidecar. The worker stages the complete new render file and an old-primary recovery copy under one
per-world writer slot. The owner transaction only checks the captured file identity and renames the staged file; a later transaction failure restores
the old primary before releasing the infrastructure edit permit. A queued edit whose captured render records are no longer current fails stale rather
than overwriting a disjoint edit, while other worlds and physics continue independently.

## Persistence and lifecycle

SimpleATO and every signal plugin own separate persistence boundaries. SimpleSignals stores schema-version-1 world definitions in
`<dataFolder>/worlds/<world UUID>-definitions.json`, with a same-directory `.bak`. It persists only authored region boundaries, complete
fixed-route traversals and fixed-block traversals using namespaced IDs, canonical UUIDs/AB metres and `ViewDirection`; derived interiors,
occupancy geometry, indexes and compiled restrictions are rebuilt.
The strict current codec rejects unknown fields, versions and wrong world identity. Missing files start as an unwritten empty revision 0;
an invalid primary may be read from a valid backup without overwriting either input, while no valid candidate leaves the world unavailable.
Startup publishes no usable route source until decode, topology binding and complete compilation have all succeeded. A corrupt/orphaned
candidate installs the same stable source ID in fail-closed form so new operational route searches are rejected instead of treating the
plugin as unrestricted; it does not publish a usable local runtime. Runtime code sees only the current domain model and the writer emits only
schema version 1 through same-directory temp/backup/atomic replace. Default-route removal and independent directional-entry rules
correct the same unpublished format directly; the mistaken version increments to 2/3 have been restored to 1 without compatibility branches.
Host world and model-package versions are unchanged. No old development format reader
or migration branch exists; a future topology migration remains a separate dry-run-first tool with a recovery point.

At enable/load, the plugin remains restrictive until host capabilities, persisted definitions, topology bindings and derived indexes validate. Missing required host services prevents enable. Planned disable first un-applies the plugin's plans, publishes empty restrictions where required and calls the single host owner-unregister operation. That operation succeeds only when no plugin-owned pending request, live claim, referenced type, applied-plan dependency, active inspection binding or non-empty restriction remains. If Bukkit has already forced disable, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds. Renderer and authoring resources clean up idempotently after the safety gate succeeds.

## Migration from Basic

The domain migration has moved the authored definitions, graph cut, catalog/index and route-window
matching into `SimpleSignals`, deleted the unused Basic single-window request/claim path and retained
generic projection, turnout/diamond identity, claim, preparation, cursor/release and suffix extension
in Basic. The planning-only plugin world runtime now loads its own strict sidecar, publishes candidate region/table pairs atomically and
registers either one compiled fixed-route source or one rejecting fail-closed source for each available host world load. The remaining
reviewable batches are:

1. connect the completed plugin-local authoring transaction to Wand/commands and user-facing diagnostics;
2. connect the plugin-generated route boundary/additions through the generic contributor API.

Development data migration, if required, is a separate auditable one-time tool with dry-run and recovery point—not a permanent runtime compatibility path.

## Failure behavior and complexity

- Region derivation is proportional to the affected topology subgraph during create/update/load, never per tick.
- Route matching is linear in the relevant frozen-route range plus indexed resource hits.
- Current JS dependency invalidation remains proportional to its affected downstream-to-upstream chain. The JS
  `TARGET` prepares only the declared-distance branch tree and uses reverse indexes for dirtying; a repeated directed scan state
  terminates that branch and reports the reason instead of rejecting the whole rail loop or trying to settle an SCC.
- Basic admission is one physical projection plus additions and one atomic claim.
- Missing indexes, stale revisions, invalid script output, plugin exception, forced owner loss or preparation uncertainty cannot widen authority.

## 进路自动生成与鼠标替换：已实现批次

- 新保护区域闭合后，在现有编辑 worker 中，按区域内部的合法有向连接检查入口—出口组合。
  距离权重采用实际轨道长度；每个可达组合保存一条最短进路，等长候选使用稳定顺序选择。
  生成区域与进路表复用现有候选校验和原子编辑发布，不先保存区域再以另一事务补表。
- 多通路警告关联有向入口与出口，不关联内部道岔；警告表示还有其他路径可供选择，
  不表示最短进路被禁止使用。无需把所有可能路径枚举并写入存档。
- 鼠标左键选入口，Shift + 左键选出口并预览，右键保存当前完整预览。
  替换对应组合的路径时沿用原进路身份，避免无故丢失固定进路信号绑定；
  仍经过既有编辑许可及候选校验，失败保留旧定义。录制不自动切换道岔或申请列车资源。
- 只在新区域创建时自动生成；既有区域修复不覆盖玩家录制结果。
- 内部算法为每入口一次 Dijkstra，替代通路通过暂时排除已选路径的连接后检查可达性识别，不枚举所有路径。
  实际复杂度和生命周期见[自动进路合同](../../spec/simple-signals/backend/automatic-protection-routes.md)。
- 共用视觉规范先统一节点、方向、区间、选中与警告的含义及显示生命周期。
  业务定义保留在各所属模块，平台显示实现归 Bukkit 层。全工具的 TextDisplay 替换与
  资源包样式制作不并入进路逻辑批次；不得为规范先创建通用业务注册框架。

## 已完成修复批次：保护区域出口车尾净空

本批实现用户已经批准的净空要求，不重复删除默认进路。区域、入口和出口由 SimpleSignals 解释；
SimpleATO 只执行中立的“入口以前须具备到某位置的连续可行驶空间”要求，Basic 的资源仲裁与真实车尾释放不变。
既有冻结路线匹配在绑定时编译入口与出口，控制器复用同一次感知及当前完整约束判断；
需要的感知长度在扫描前并入既有前视范围，不调用第二个扫描器。

对出口位置 x 和权威米制列车长度 L，车头可到达的位置至少为 x+L；授权还须覆盖既有控制器安全余量。
不能把不足的路线终点裁短后当成净空成立。运营停车点、前方红灯、跟车安全间距和其他当前限制都须参与判断，
取得后继闭塞 token 本身不证明可驶出。只排除当前入口自身的信号与本净空等待限制，不能忽略其他停车要求。
入口保持停车时仍正常申请资源；下游条件变化后由下一次控制计算重新判断，不依赖 Dispatcher 再次推进。

控制器判断与共享保护信号之间需要最小的不可变结果交接，按既有路线、入口 occurrence 和计算周期校验，
只在结果改变时唤醒相关信号，避免逐周期执行全图规则。具体接口与复杂度在实现前核对真实接缝，
在[所属合同](../../spec/simple-signals/backend/protection-region-authority-transition.md)记录，不在任务中复制第二份 API 合同。
这是进入前按当前状态作出的证明，不保证后续紧急停车或故障不会留下车尾；资源仍由 Basic 保留到实际清除。
独立检查以真实Core直线连续移动发现车钩解算毫米级抖动，因此同一既有绑定共享一个向上取整、回落带迟滞的保守长度。
上界不小于有效本帧车身跨度；缺帧不使用缓存证明。目的仅是抑制无意义热路径重编，不增加资源仲裁或历史存储；参数与算法归所属合同。

## 当前修复批次：双向闭塞两端独立规则

只在SimpleSignals内解除“区间只能绑定一条规则”的实现限制，保留共享物理占用、block身份和宿主资源生命周期。
定义、混合依赖编译、响应式状态和ATO检查投影按同一入口方向选取；模型和附属显示继续按主信号ID读取。
不建立第二份占用区、每列车规则图或第二条感知路径。未配置端与已配置但失效端必须区分。
具体方向字段、声明身份、错误和复杂度只在[双向闭塞合同](../../spec/simple-signals/backend/bidirectional-fixed-block.md)维护；
准确代码影响见[研究](research/directional-fixed-block-signals.md)。未发布格式直接修正并保持schema 1；误升版2/3已恢复，无运行时旧开发reader。

## Open decisions

- JS 技术栈、同步信号阶段、包目录、完整分支树、逐分支扫描距离、无通用预算及遇环停止均已批准。
  待实现批次只需确定不影响这些语义的具体类拆分、未冻结的 YAML/API 字段、跨包错误诊断和串行 context 生命周期；
  以[所属 owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)为准，不恢复 flat-array、context-pool、watchdog 或 SCC 方案。
- Whether WebUI remains a host sidecar, moves beside SimpleATO, or becomes independently deployable.

These decisions do not block module extraction, protection-region migration or the Java/Kotlin signalling core. The scaffold now fixes the Java package root as `work.art1st.javarailways.simplesignals`; persisted type IDs and script API identifiers remain separate decisions for their owning domain batches.
