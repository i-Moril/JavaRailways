# Stage 2: SimpleATO extraction and SimpleSignals

## Goal

Finish separating the operational ATO stack from the JavaRailways host, then implement the first independently deployable Java/Kotlin signal-system plugin using only the completed Stage 1 observation, resource, restriction, inspection, applied-plan dependency and lifecycle contracts.

The result must prove that protection regions and fixed blocks are optional policy supplied by a concrete signal system rather than universal Basic/SimpleATO concepts.

## Confirmed facts

- Stage 1 is complete and archived. It provides the Basic physical-resource floor, plugin-contributed resources, one claim/preparation/grant lifecycle, suffix extension, tail release, compiled inspection bindings, namespaced restrictive outputs, applied-plan edit dependencies and one owner-level unload gate.
- `SimpleATO` owns its controller, mission/constraint policy, world coordinator/diagnostics, frozen-route/authority client, inspection runtime, signal epoch runtime, restriction adapter, timetable dispatcher, manual navigation and SimpleATO runtime snapshot. The legacy `AtoProvider` registry/boundary switching path has been deleted; signal extensions use inspection plus namespaced restrictive outputs. Destination, Station, Depot, Line, Timetable and rolling-stock assignment also belong to SimpleATO. Basic owns public rail operating-direction authoring/storage/persistence; SimpleATO consumes that configuration rather than retaining a second owner.
- `SimpleATOBukkit` now exists as an independent Gradle source/test module embedded into the JavaRailways main plugin JAR. It is not a second deployable Bukkit plugin and has no separate descriptor, `JavaPlugin` lifecycle or internal service-discovery hop.
- Basic's transitional protection-region catalog, graph-cut resolver, route window and old admission seam have been removed. SimpleSignals now owns the definitions, graph cut, catalog/index and route-window matcher; generic topology projection, resource identities, claims, preparation and release remain in Basic.
- Basic derives a non-removable physical footprint and allows signal plugins only to add validated resources or stricter holds/restrictions.
- SimpleATO owns operating-route selection, resource inspection scheduling and the single most-restrictive ATO/ATP pipeline.
- Generic longitudinal dynamics, actuation contracts and physical safety mechanisms shared by manual driving and automation remain in Basic; SimpleATO owns the automated operating policy that consumes them.
- Each independent signalling system will be a separately compiled Java/Kotlin plugin. It may choose its own JS, YAML or other customization layer; no common script engine is mandatory.
- The Java/Kotlin signal runtime validates and transports a rule-selected speed limit plus opaque model variant; it must not hard-code a national aspect vocabulary or map `R/Y/YG/G`-style names to fixed speeds. Only unavailable/invalid rule state has a host-owned zero-speed safety meaning.
- Operational route selection now uses one SimpleATO seam over Basic's immutable-`TopologyState` planner. Mission, Station, Line, Timetable and route diagnostics all compose operating directions with owner/world registered neutral restriction sources before search; the legacy live-`RailView` planner has been deleted.

## Requirements

### A. SimpleATO module extraction

- Move the remaining automated operating policy, Destination, Station, Depot, Line, Timetable, rolling-stock assignment, operational route planning, timetable apply state and their persistence into platform-neutral `SimpleATO`.
- 用户已批准新的方向归属：轨道长期允许运行方向由 Basic 保存，SimpleATO 与信号插件读取，取代原先的 SimpleATO 所有权。
  用户确认格式从未部署，直接修正当前存档，Basic保持2、SimpleATO保持1，不新增旧方向格式兼容或搬迁转换。
  具体机制见[方向契约](../../spec/javarailways/basic/operating-directions.md)。
- Keep generic longitudinal dynamics, train-actuation primitives and physical safety floors shared with manual driving in Basic; do not move them merely because SimpleATO consumes them.
- Keep generic graph algorithms and physical/resource validation in Basic; do not move concrete signalling policy into SimpleATO.
- Keep `SimpleATOBukkit` as the Bukkit/Paper adapter module for platform behavior specifically owned by SimpleATO, such as startup wiring and ATO diagnostics/presentation. `BukkitPlugin` directly constructs/calls it and embeds it in the main plugin JAR; there is one deployable JavaRailways plugin and one Bukkit lifecycle. Generic host rendering/Folia scheduling remains in `BukkitPlugin`, while each independent signal plugin owns its signal presentation and may consume a separately reviewed public host platform API if one is needed.
- Leave low-level `RailWand`, manual driving and host world/composition behavior in `BukkitPlugin`.
- Remove old Basic/BukkitPlugin owners as each slice moves; never keep two writable production implementations or add runtime compatibility wrappers for development data without separate approval.
- Converge operational route selection on the immutable normalized `TopologyState` before exposing an external restriction contract. Basic owns the generic deterministic graph search, while SimpleATO owns composition of operating direction, line/train policy and independently installed plugin constraints for one planning query.
- External planning constraints must not mutate Core topology, create physical connectivity or bypass Basic validation. They may only restrict a query-local graph and contribute validated complete physical traversal macros; the selected result is expanded to a normal frozen route and independently validated by Basic. Basic must not expose a generic `RouteChoiceZone`/protection-area abstraction: a concrete signal plugin keeps its own region/interlocking model and compiles it into neutral query-graph primitives.
- Temporary live occupancy or a denied resource claim does not silently remove a route from the planning graph. The default response is to wait; replanning is an explicit higher-level action at a safe boundary.

### B. Independent `SimpleSignals` plugin

- Create a separately deployable Java/Kotlin plugin/artifact with its own descriptor, lifecycle, persistence boundary, commands/tools and rendering.
- Depend on JavaRailways/SimpleATO public contracts at compile time and host services at runtime; do not shadow host classes.
- Own its fixed-block definitions, protection-region boundaries/interiors, interlocking policy, signal definitions/aspects, renderer state, authoring and optional script/configuration runtime.
- Publish model/aspect changes as immutable per-object presentation revisions. The adapter may skip obsolete pending visual revisions but must converge to the newest value without delaying the railway safety epoch.
- Migrate the current protection-region domain code out of Basic: definition, catalog, graph-cut/interior derivation, route-window matching, authoring validation, persistence and diagnostics. Reuse Basic topology/resource APIs instead of copying the physical graph or claim registry.
- Allow one player-authored region to protect a simple turnout or a complete station throat. Do not synthesize one stop/protection boundary per turnout.
- At authoring/update time, cut the immutable directed topology at region gates, derive the selected bounded interior and reject leaks, ambiguity, invalid directions and unsupported overlap. Cache/index the result by topology and plugin-definition revision; do not flood-fill each tick.
- At runtime, match only regions/blocks reached by the train's frozen route and shared perception. The plugin chooses the requested authority boundary and additive resources needed by its policy; Basic independently restores all traversed rail/turnout/diamond resources.
- For a protection-region entry, request enough frozen-route range for the train to leave the region and satisfy the plugin's exit policy. If the following territory is a fixed block, the plugin may require that whole block; in moving-block territory it may choose an exact route boundary. Basic does not make this policy choice.
- A region ID must not be an exclusive whole-region lock by default. Physical path/conflict resources and explicit flank/approach resources determine conflicts, allowing disjoint paths through one throat when the concrete policy permits it.
- Implement fixed blocks as plugin-owned authority/resource policy. Protection regions and blocks may expose a common plugin-local section observation API, but Basic and SimpleATO do not own or persist that abstraction.
- Allow an operational route to start or stop inside a fixed block, including a platform inside that block. Holding the whole block resource does not require granting movement to its physical exit; stopping or finishing a mission must not release a block still occupied by the train.
- Keep block-resource intersection separate from an actual entrance signal. An internal route start must not manufacture an entrance declaration or entry authority floor; real passed signals are excluded by the existing head-forward inspection scheduler, while held resources remain managed by the existing grant/release queue.
- Do not reject a timetable operation merely because it visits the same block or protection region more than once. Stopped reversal separates forward and return traversal coordinates while retaining occupied resources; separate visits within a frozen route keep distinct positions but reuse the same physical resource identity.
- Derive every permissive aspect from a confirmed Basic grant plus current SimpleATO authority. Plugin output can only keep or tighten speed/authority; missing, stale, corrupt or disabled state is restrictive.
- Use SimpleATO's existing shared route perception, inspection scheduler and reviewed declaration contract while keeping dynamic braking distance host-owned per tick. Do not invoke unchanged signal policy every tick, add a second forward scan or move the concrete plugin's downstream dependency graph into SimpleATO.
- Do not add a second per-train route scanner, world-wide per-signal tick loop or `allTrains × allSignals` lookup.
- Support diamond conflicts through Basic's route-bound physical resources without treating a diamond as a switch or topology continuation.
- Release resources through the host grant/cursor lifecycle. Planned plugin disable must first remove its applied-plan dependencies and then pass the single owner-unregister gate; the gate rejects disable while that owner still has pending requests, live claims, referenced resource types, active inspection bindings or non-empty restrictions. If Bukkit has already forced the plugin off, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds instead of widening authority.

#### Direction-aware blocks — implemented validation, revised failure policy pending

The user identified that opposite-facing traversal must not silently bypass a single-direction
block's resource policy. Commit `5e375e79` was the bidirectional baseline that retained this bypass.
The earlier correction, including low-speed fallback, was implemented and verified offline.
The user has now replaced that fallback with refusal of ATO admission for every invalid block,
including known operating-direction mismatch. This policy change is approved but not yet implemented.
Historical verification remains in [the implementation checklist](implement.md); live Folia/client validation remains pending.

- An effective single-direction block declares normal acquisition lookahead only at its entrance
  for approaching trains travelling in the permitted direction. Reverse entry from its exit must
  explicitly refuse resource admission, not return an empty contribution or rely only on a displayed stop aspect.
- Check the block's declared directionality against the operating directions of every traversal,
  including mixed canonical AB/BA paths. The user confirmed exact allowed-direction-set equality:
  a single-direction block requires every traversed rail to permit only the authored path direction;
  a bidirectional block requires every traversed rail to permit both directions. An unset track
  policy permits both directions and therefore mismatches a single-direction block. Direction is
  relative to the block path: `rail A AB -> rail B BA` requires A's policy AB and B's policy BA,
  not identical AB/BA enum values. Any mismatching traversal disables the whole block, not just
  that rail or one entrance. A mismatch retains the authored definition and devices but makes the
  whole block unavailable to ATO until corrected and revalidated, just like structural invalidity.
  It must not disappear from route validation or allow admission through an empty resource contribution.
- Known direction mismatch no longer permits low-speed passage. Basic's physical-resource validation
  and all stricter restrictions remain in force.
- Existing claims must not disappear because normal block control is disabled. Reuse the current
  edit guards and grant/tail-release lifecycle; do not introduce a second resource owner or an
  ad-hoc release-on-disable path.
- Public rail operating directions are owned by Basic. SimpleSignals uses the neutral public read/change
  seam rather than importing private world state, duplicating that policy, or polling all blocks every tick.
- Remove the low-speed fallback and its `disabled-block-speed-kmh` configuration; do not retain
  a compatibility switch or a second selectable failure policy.
- The user explicitly keeps manual driving unchanged. This refusal applies only
  to ATO; manual drivers control speed and maintain visual separation themselves. Do not add a manual
  limiter, automatic braking or an ATS integration in this correction, and do not remove existing
  Basic manual safety behavior. More systematic manual protection is deferred.

#### Model presentation and placement

These behaviors are approved. Package loading, placement persistence, production signal/model binding,
per-device handle retention and unload cleanup are implemented and verified offline. Real Folia/client
presentation remains unverified. The owning [model presentation contract](../../spec/simple-signals/backend/model-presentation.md)
defines the implemented flow and its verification boundary.

- SimpleSignals model packages must explicitly define `default` for initial missing signal state or `Unavailable`; an unknown explicit model variant remains a configuration error. This is plugin-local presentation policy, not a new requirement on host/train/rail models; the signal safety state keeps its existing name and semantics.
- Support rail-attached and ground/world-position placement. Rail coordinates retain their AB/BA direction to interpret offsets; ground placement uses a world pose without rail offsets. Placement and named left/right presets are independent of signal aspect variants and logical control boundaries.
- A future Wand can edit per-device offsets, rail coordinates and direction. Changing attachment direction retains offset values and recomputes placement; rotating the model in place preserves its placement point.
- Models may default to following rail slope/cant and players may override that choice, with world-upright as the fallback. Selecting a preset copies the effective offset, rotation and tilt choice into independent instance parameters. Later preset edits/removal do not move existing devices; adopting new parameters requires explicitly reapplying a preset. Do not add runtime field-inheritance tracking.
- The approved [model-package format](../../spec/simple-signals/backend/model-packages.md) provides multiple models and local shared parts. Static scenes are named without author time fields; imports merge offsets and Euler angles as train imports do, while explicit containers provide hierarchical assembly. Load and validate the whole package into immutable host values outside the signal tick.
- All three device kinds use one optional model reference plus rail/ground placement. Backend-only devices need no display position; remove the route indicator's duplicate standalone position. Reuse the current sidecar and authoring transaction, with old/new attachment rails included in the existing edit permit. The user explicitly approved correcting the unpublished schema-1 draft without intermediate versions or old-field aliases; the exact current shape belongs in the model presentation contract.
- Arbitrary model instance parameters such as mast height are deferred. Later they must be separate from signal aspect `modelVariant`; do not reserve empty fields or add parameter evaluation now. Static model IDs can distinguish preconfigured heights in the meantime.

#### Guided authoring and measurement

The following player-facing requirements are confirmed. The user deferred tools, measurement,
style-editing UI and track overlays to the next batch. The backend primary/attached-signal relationship
is complete offline. Bidirectional single-track single-block control is also implemented and verified offline,
with two true entrances sharing one block token and one primary signal result; see the
[bidirectional block contract](../../spec/simple-signals/backend/bidirectional-fixed-block.md).
尚未决定的交互不阻塞已经确认的独立批次。用户已批准先实施独立道岔 Wand 并提交；
本批边界和测试见 [道岔工具实施说明](research/turnout-wand-batch.md)。其他工具分批推进。

下列操作由用户确认。
它们取代上一轮名称输入、确认页面、单独连接模式以及创建闭塞时同时放置信号机的建议。

- 闭塞工具选择两个分界点后尝试生成区间，不要求玩家填写名称、指定出入口方向或打开确认页。
  内部稳定 ID 仍由插件生成。区间路径确定后，逐段读取 Basic 的公共运营方向，自动推导闭塞方向与出入口朝向；
  不改写轨道方向，也不放宽既有整条路径一致性校验；方向失配统一按不可用处理，禁止 ATO 驶入。
- 闭塞区间禁止经过道岔，包括经过道岔直向。两处分界点之间若需要经过道岔，拒绝创建，
  不按当前道岔状态选择支路，也不自动截短区间。此规则约束闭塞定义本身，Wand 和现有定义导入入口须统一校验。
- 未选择分界点时，左键现有分界点进入接续放置状态，沿用轨道创建模式的操作习惯。
  不设单独连接模式；选取两个相连的现有分界点时直接尝试在两点之间创建闭塞区间。
- 分界点可以配置地面显示子模型；它是分界点的建造显示，不是额外的列车控制信号、占用区或资源。
- 进路表采用录制当前通路的方式：玩家调整道岔后选择入口和出口，保存当前实际连接构成的完整路径。
  保存后的进路不会随之后的道岔切换而改变。录制功能之前必须先提供玩家可操作的道岔切换工具，
  使用主插件提供的独立道岔 Wand，复用现有手动道岔选择和占用检查，不并入 RailWand 的建造模式。
- 进路录制必须有通路可视化：明确显示所选入口和出口目前是否连通，并沿实际通路高亮轨道及方向。
  无法到达所选出口时显示实际到达的位置和原因，不只弹出“失败”；道岔切换后按真实状态更新预览。
  “通路连通”只表示物理连接成立，不表示列车已取得资源或允许运行，不能把预览当成授权。
- 信号机使用独立 Wand 放置和编辑，不在创建区间时同时放置。放置前左键闭塞区间完成绑定，
  再右键轨道或方块选择摆放位置；绑定状态可用物品附魔光泽标示，Wand 设置选择待放置信号机模型。
  当前信号与进路显示器左键绑定保护区域入口；固定进路信号选择入口后，从该入口的已录制进路中选一条。
  中继和附属信号机左键绑定对应的主信号机，之后同样右键轨道或方块放置；不要求玩家手填对象 ID。
  生存模式直接消耗具体信号机物品是未来需求，本批不实现物品消耗或经济系统。
- 设置独立删除模式，沿用轨道工具的创建/删除分工。删除区间分界点时，同时移除该点两侧相接的闭塞区间；
  也能只删除某个闭塞区间。删除后不再被任何闭塞或保护区域使用的分界点自动清理。
  若闭塞分界点与保护区域边界重合，必须先删除闭塞分界点及其相邻闭塞，保留保护区域边界和区域本身；
  后续删除保护区域边界须走保护区域编辑与校验，不能因重合位置的一次删除而连带移除整个咽喉或进路表。
  删除闭塞时，同时删除该闭塞所属的逻辑信号、绑定信号机和中继信号机及其模型；
  不保留未绑定设备，不影响其他闭塞或保护区域所属的信号。
- 保护区域编辑允许暂时不完整：删除边界或为新增侧线调整边界时，保留原区域身份、其余边界、进路表、信号机及摆放，
  区域降为不可用，待玩家补齐缺口后重新校验并恢复。不可用区域仍拥有其配置，不能被孤立点清理或设备清理误删。
  仅补齐边界不代表原进路全部仍然有效；不再适用的进路和设备绑定保留供修改，重新通过校验的部分才恢复使用。
  此规则不改变明确删除闭塞时的连带删除行为，也不解除已应用时刻表和运行占用的防编辑保护。
- 创建保护区域边界和闭塞分界点时可以互相吸附到已有位置；吸附使用相同的轨道坐标，
  但位置重合不合并两者的删除职责，不要求新增共用坐标对象。
  吸附须遵守真实轨道定位，不能把空间相近但不相连的轨道误认为同一点。
- 保护区域边界在创建时即带有方向，方向指向保护区域内部，沿用现有有向边界含义。
  不要求额外点击区域内轨道来推导边界朝向；普通闭塞的方向仍按前述规则自动推导。
- 保护区域自动生成：放置有向边界后尝试识别区域，满足完整性与既有校验时自动生成，
  不要求玩家执行“完成”操作。区域必须由玩家明确放置的边界界定；遇到未标记的轨道尽头视为尚未完成，
  不能把轨道尽头自动当作区域边界。
  每次放置都从新边界向内尝试补全区域，不要求先选中旧区域，也不增加单独的修复/编辑状态。
  补全原有区域时自动沿用其身份、进路表和设备绑定，不能因为这次放的是新边界就另建区域并丢弃原配置。
  识别由编辑触发并在异步 worker 中执行，不进入每 tick 的信号更新；不能为每次点选重复重建整份世界索引。
- 提供可配置的保护区域最大范围，口径为从起始边界沿轨道到最远位置的距离。
  道岔各支路分别累计到起点的轨道距离，不把各支路长度相加，也不使用空间直线距离。
  在搜索过程中检查并在超限时停止，避免缺少边界时继续扩展到远处路网。
  新建时超限不生成区域；已有区域重算超限则保留配置并标记不可用。不把搜索截断处充当玩家边界，并向玩家说明超限原因。
- 闭塞区间和保护区域不得有内部重叠；共享边界位置不算重叠。
  创建、编辑和删除仍经过原有编辑许可与候选校验；事务失败保留旧状态。
  经许可保存的暂时不完整区域是明确的不可用配置，不等于保存失败留下的残缺数据，不能继续用于放行。
- An added signal machine references a selected primary logical signal associated with that section and reuses its result,
  rather than creating another signal rule or running another occupancy/downstream scan. Repeaters
  also belong to the primary signal. Each display keeps its own model, style, position and facing;
  a repeater's display position is not automatically another train-control boundary.
- Support the user's single-track example of two signal machines displaying the same signal.
  Bidirectional admission is now approved through `bidirectional = true` on the block; missing/false
  keeps existing directed behavior. Reverse entry uses the same physical resource and shared rule result,
  not a second direction-specific token or rule. A reverse-facing display alone still does not enable this control.
- Provide a separate measurement tool showing both the straight-line distance and the along-track
  distance between two selected rail positions. Path choice at branches must be discussed rather than silently assumed.
- Like RailWand, allow editing the signal Wand's default style for future placement and editing the style
  of an already placed signal. Default-setting changes must not silently restyle existing devices;
  existing per-device placement/preset-copy behavior remains applicable.

Two further UX topics remain open: protection-region route authoring and device binding, and clearer
editor visualization. User-proposed visualization candidates are directed `▴` TextDisplays colored
by speed limit, dedicated resource-pack items for stopping points/anchors, and differently colored
TextDisplay bars for fixed-block extents. These are proposals, not a frozen rendering/API design.
Multiple displays now have confirmed shared-output semantics. This does not require every entrance
signal on a physical section to share one result. The current one-rule-per-section runtime is an
implementation limit, not a permanent constraint on bidirectional blocks. A bidirectional
physical block must use the same resource identity from both ends; reuse Basic's existing arbitration
and tail-release lifecycle rather than adding another direction lock for a single block. The authoring
scope for independently computed directional rules remains open; the current approved single-block
scope intentionally shares one result. These extension boundaries are recorded in the
[owner spec](../../spec/simple-signals/backend/index.md#后续单线支持的边界目标尚未实现).

### C. Timetable, editing and persistence

- Reuse the existing signal-plugin route matching/binding during selected-route timetable compilation before apply and collect its exact edit dependencies. Do not add a parallel signal-audit framework, repeat authoring-time validation, perform another path search, or reject transient red aspects/occupancy/resource waits as an invalid timetable.
- Extend the existing atomic applied-plan dependency declaration with the signal plugin's exact referenced runtime resource IDs. Draft save/dry-run publishes no dependencies; draining unapply blocks new work and removes dependencies only after live grants clear.
- Route every covered topology, region, block, signal and operational-object edit through the common edit guard with owner/revision diagnostics.
- Store each plugin's data in its own independently versioned sidecar using stable namespaced IDs, rail UUIDs, canonical AB positions/directions and explicit references.
- Decode/migrate only at the plugin persistence boundary; business logic sees the current model and writers emit only the current version. No reflection from stored class names and no unreviewed runtime compatibility path.
- Provide in-game create/edit/delete/inspect/validate/dry-run operations and recovery points for destructive topology-related migration.
- All online infrastructure editing uses prepare/commit separation. Geometry, graph/index compilation,
  document encoding, validation and ordinary file writes run on an asynchronous worker. The final
  commit is scoped to the affected world, performs only stale checks plus bounded state/file swaps,
  and never holds a cross-world simulation lock. This applies equally to signal definitions,
  tracks, topology nodes and other resource definitions; describing an edit as low-frequency does
  not permit an `O(world)` rebuild or blocking I/O on the Paper/Folia owner thread.

## Acceptance criteria

- [x] 轨道公共方向及其中立 API、通用转换和基础设施存档归 Basic；SimpleATO 不再保留方向存储或转发兼容别名。
  该迁移批次当时保持寻路、方向判断、降级限速、编辑保护和手动行为不变；降级限速现由本任务的新失效策略取代。
  当时直接修正未部署当前存档，不升版、不新增方向兼容路径。

- [ ] `SimpleATO` contains the platform-neutral operational stack and Basic has no production owner for the migrated state.
- [ ] `SimpleATOBukkit` is a separate Gradle source/test module embedded in the JavaRailways main plugin JAR, has no independent Bukkit descriptor/lifecycle, and does not become the owner of any concrete signalling system.
- [ ] Signal presentation is applied through target-aware latest-only dispatch: an unchanged or superseded model revision creates no replay backlog and cannot delay logical signal evaluation.
- [x] Placement tests prove directed rail offsets, model/player tilt selection, in-place rotation and ground placement without a rail lookup. Existing instance parameters survive preset changes, with explicit reapplication adopting the new values; calculation creates no entities or signalling side effects.
- [ ] 闭塞创建无名称输入、方向输入或确认页；成功后方向和两端朝向逐段自动推导，失败保留有效旧定义。
- [ ] 闭塞路径经过道岔时拒绝，无论直向、侧向或运行方向；Wand 与定义导入使用同一约束，失败不截短区间或修改原定义。
- [ ] 左键现有分界点可接续创建，选取两个相连分界点直接尝试生成区间，无单独连接模式。
- [ ] 分界点地面子模型可配置；独立信号Wand先绑定闭塞再放置，显示绑定状态且不重复创建逻辑区间。
- [ ] 信号Wand按设备类型绑定入口、已录制进路或主信号机，统一右键放置，无须手填ID；附属设备仍复用主信号输出。
- [x] 主插件提供独立道岔 Wand，玩家可切换未被占用锁定的道岔，复用现有手动选择接口并显示拒绝原因；成功操作不产生行车授权。
  已通过离线工具/命令及真实 Core/宿主服务组合测试，完整 Folia 交互仍待验证。
- [ ] 录制前能看到真实连通路径、行进方向及出口；未连通时标出实际到达位置和原因。道岔切换更新预览，保存进路不随后续切换改变。
- [ ] 删除模式支持删分界点及其相邻区间、只删区间，并清理不再使用的分界点；重合位置先删闭塞分界点，保护区域边界和区域保持不变。
- [ ] 删除闭塞同时移除其逻辑信号和所有附属信号设备，不留下未绑定模型或损坏引用；无关区间和保护区域的设备保持不变。
- [ ] 删除保护区域边界造成缺口时，保留区域身份、其余边界、进路表及设备，标记不可用；补齐后重新校验，恢复有效内容，失效进路或绑定保留待修改。
- [ ] 保护区域边界和闭塞分界点可互相吸附；允许边界接触，但拒绝内部重叠、错误轨道吸附和受运营保护的修改。
- [ ] 保护区域边界创建时记录指向内部的方向，无须额外选择内部位置；该交互不改变普通闭塞自动推导方向的规则。
- [ ] 每次放置有向边界都自动尝试补全区域，无“完成”操作或预先选择旧区域的要求；补全旧区域保留身份和配置。识别在编辑时异步进行，未完成的边界不产生错误区域。
- [ ] 未标记的轨道尽头不能完成区域；最大范围可配置，搜索超限及时停止并提示，不生成被截断的区域。
- [ ] 最大范围按起始边界到最远位置的沿轨道距离检查；分支共享的前段计入各自距离，各支路长度不相加，弯曲线路不使用直线距离代替。
- [x] Additional signal machines and repeaters reference their primary signal and reuse its output without duplicating signal evaluation, creating another block resource, or adding a control boundary at every display position.
- [x] Attached devices round-trip in the existing sidecar and admin workflow, reject invalid/orphan references atomically, update only related models, and retain unaffected handles through edits and unload cleanup; verified offline, not on a live Folia/client.
- [x] A bidirectional single block matches the same physical path from both ends, produces actual entrance checks,
  denies opposing claims atomically and retains the same token during an internal stopped reversal until tail clearance.
  Reverse protection-region successors and edit protection use the same geometry; the direction-policy correction below supersedes the old single-direction bypass.
  Verified with real Basic class composition and the 295-test module gate; live Folia and a complete depot mission remain unverified.
- [x] Correct the single-direction bypass: reverse admission is explicitly refused while direction-consistent
  control is active; held claims are preserved and direction edits invalidate only relevant cached checks
  outside the tick hot path. The earlier low-speed failure policy is superseded by the pending criterion below.
- [x] Multi-rail direction validation checks every traversal relative to the authored path. One
  mismatching rail disables the whole block; unset/bidirectional rails mismatch a single-direction
  block, and a bidirectional block requires all its rails to permit both directions. Mixed canonical
  AB/BA paths with matching per-rail policy remain valid.
- [ ] 运营方向失配与结构失效统一禁止 ATO 驶入，保留区间和设备配置；不允许以低速或空资源贡献继续通行。
  删除 `disabled-block-speed-kmh` 及失配专用的低速逻辑，覆盖正反向、多轨道和无模型/无信号规则的闭塞。
  已有资源仍按原生命周期保留和释放，手动驾驶行为不变；修复并校验通过后恢复可用。
- [ ] The separate rail measurement tool reports straight-line and along-track distances for the selected positions/path without modifying infrastructure or acquiring train resources.
- [ ] Tool defaults affect future signal placement, while instance-style editing changes only the selected device and preserves the existing logical control binding.
- [ ] The `SimpleSignals` JAR installs beside the host, is absent from host JARs and imports only documented public contracts.
- [ ] Protection-region/fixed-block classes, persistence and authoring are absent from Basic's target API and owned by `SimpleSignals`.
- [ ] One region protects both a simple turnout and a multi-turnout throat without internal per-turnout stop boundaries.
- [ ] Region validation rejects leaks, ambiguity, bad gate direction and unsupported overlap outside the runtime tick.
- [x] Basic still inserts every traversed rail, turnout and diamond resource when the plugin supplies no or incomplete additions.
- [ ] Plugin-added flank/approach/block resources join the same atomic claim; denial leaves no partial ownership or wider authority.
- [x] Disjoint route footprints through one region may coexist when policy allows; overlapping rail/conflict/additional resources cannot.
- [ ] Fixed-block, protection-region and moving-block transitions choose their authority boundary in the signal/ATO layer without a Basic contributor closure.
- [ ] ATO stops before an unavailable grant and applies plugin speed limits/model variants only as stricter outputs.
- [x] Unchanged signal state does not execute every signal script/update every tick; dependency changes propagate to affected upstream resources.
- [x] Diamond crossings prevent conflicting passages without cross-line routing or runtime geometry scans.
- [x] Disable, failure, world unload/reload and stale-state tests prove authority never widens and live claims are not dropped beneath trains.
- [x] Timetable apply/replace/unapply and draft/draining tests prove atomic applied-plan dependency replacement, edit blocking and separation from transient live claims/occupancy waits. The lifecycle regressions use real Basic dependency/edit permits with scripted admission completion; physical tail release and live Folia validation are not established by those fixtures.
- [ ] A deliberately paused signal-definition commit cannot delay another railway world or an
  already-running physics epoch; old epochs finish with the old immutable source and the next epoch
  establishes the new baseline.
- [ ] Rail create/replace/split/merge/remove prepares curve, crossing and normalized topology
  candidates away from the platform owner thread; final publication pauses only the affected world
  and contains no complete topology rebuild, persistence write or Bukkit wait.
- [x] Plugin persistence round-trips current fixtures and rejects/preserves corrupt or orphaned input with actionable diagnostics.
- [ ] Unit, integration, packaging and Folia smoke checks pass.
- [x] Mission, Line, Station and Timetable use one immutable-topology planning path; registered restrictions have identical semantics for all callers and no legacy `RailView` planner remains in production.
- [x] A signalling plugin can compile its private interlocking/region model into neutral suppressed traversals plus complete physical traversal macros, without permitting hybrid paths assembled from portions of different table routes; Basic neither defines the region nor accepts any expanded traversal absent from physical topology. SimpleSignals restores strict current-model world definitions and compiles them against the exact host topology before publishing one usable planning source per world load; missing starts empty, while corrupt or orphaned input installs only a rejecting fail-closed source and no usable runtime.

## Out of scope

- Requiring every signalling system to use `SimpleSignals` protection-region model, aspects, scripts or persistence.
- Multiple concrete national signalling systems in the first Stage 2 implementation.
- Certification-grade communication, redundant control channels or train-integrity proving.
- Permanent runtime compatibility for arbitrary pre-release Basic/Signal data.
- Final WebUI module ownership during the extraction and first reference-plugin batches.
- Multi-block single-track direction coordination is explicitly deferred to [TODO.md](../../../TODO.md).
  Following capacity, direction switching and integration with resource admission must be discussed
  later; do not add placeholder group state, a scheduling framework or persistence fields now.

## Dependency

- `09-18-signal-addon-host-apis` is complete and archived. Stage 2 must consume its public contracts rather than reopening their ownership model.
- The separate `09-24-explicit-rail-endpoint-connections` world-format migration does not block new Stage 2 runtime work. Any need to import pre-release protection definitions must be added to that auditable one-time migrator instead of becoming a runtime compatibility path.

## Deferred decisions

- Exact plugin-local JS/YAML API, script budget and model-variant vocabulary. These are reviewed after the Java/Kotlin safety/runtime boundary exists.
- Guided authoring interaction/API, measurement path selection, independently computed directional signal rules,
  protection-region route/device editing and editor visualization. Confirmed player-facing requirements are recorded above;
  these unresolved details must be reviewed before the authoring implementation batch.
- Arbitrary model instance parameters. Production model binding is complete offline; actual Folia/client presentation remains to be verified.
- Guided/shunting workflow details and WebUI packaging.

## Blocking open questions

建造交互：工具分工已确认，信号机使用独立 Wand，测距也独立。
删除顺序和绑定设备的连带删除行为已确认，见上方要求。
闭塞禁止经过道岔已确认，不提供按道岔状态选择闭塞路径的交互。
保护区域边界在创建时携带方向已确认，额外选择内部位置的建议已撤销。
自动生成、仅使用玩家边界以及可配置最大范围已确认。现有 resolver 允许在未标记尽头停止并成功返回，
自动建造接入时必须补足完整性检查，不能直接把现有 resolve 成功当成边界已放齐。
最大范围按起始边界到最远位置的沿轨道距离计量已确认；累计所有轨道片段长度的建议已撤销。
保护区域缺口期间保留配置并降为不可用已确认，删除边界连带删除整个区域的建议已撤销。

区间失效统一禁止 ATO 驶入已确认，包括运营方向失配，取消低速降级及其配置项。
轨道编辑处理方案是在既有防编辑保护允许施工时接受轨道修改，
对闭塞同样保留定义和设备，标记不可用并停止 ATO 放行；闭塞内新增道岔必须调整边界或移除道岔才能恢复，
不能自动转换为保护区域。结构失效与运营方向失配采用相同的拒绝准入行为。
原有区域新增未设边界的侧线时采用同样的不可用行为；内部连接变化若仍符合边界和不重叠规则，则重新校验后继续使用。
实施时需保证失效对象仍参与路线校验/准入阻断，新增侧线不能因没有旧入口信号而绕过检查；
相关旧解释应在新拓扑被运营使用前停止放行，重算在 worker 进行，无关区间不因某个对象失效而整体不可用。
当前 `SimpleSignalsWorldRuntime.prepareDefinitions` 会在 region/block/table 编译失败时拒绝整份候选，
`ProtectionRegionCatalog.publish` 也在任一区域失败时直接拒绝，因此逐对象保留配置与可用性尚需实现，不能描述为已有行为。
新边界自动补全区域，不引入先选区域的编辑步骤，已确认。
录制当前通路、先提供道岔操作工具及连通状态/完整通路预览已确认。
独立道岔 Wand 已确认，由主插件提供；现有 RailWand 道岔模式继续负责建造。
宿主已经提供 `InfrastructureResourceService.selectTurnout`，不需要为此重复实现一套资源锁或行车授权。
保护区域信号和附属信号的左键绑定、右键放置操作已确认，见上方要求。
信号规则预设/模板选择暂时搁置，不实施该建议、不预留字段，也不作为道岔工具的前置条件。
测距选路及其余可视化细节仍需后续讨论。

None for the route-restriction foundation. Sources compile immutable neutral contributions before search; no plugin callback is evaluated during graph expansion. Owner/world registration now shares the existing signal lifecycle, and the plugin restores its strict current-model route-table sidecar before publishing the compiled source; invalid persisted policy leaves a rejecting source installed. Authoring and runtime route/resource policy remain later reviewable batches.

Placement, package loading, the JR four-light demo, device persistence and production model binding
are complete offline. The backend primary/attached-signal relationship is implemented and verified offline. Tools, measurement,
style-editing UI and track visualization are explicitly deferred to the next batch. Bidirectional
single-block control is complete offline; broader direction coordination and protection-region authoring remain separate decisions.

The earlier direction-aware block correction was verified offline, but its 20 km/h fallback is now
superseded by the approved, not-yet-implemented refusal policy above. Manual driving remains unchanged. Historical prerequisites remain in
[the bounded research](research/fixed-block-direction-policy.md); current behavior belongs in the
[owning contract](../../spec/simple-signals/backend/bidirectional-fixed-block.md), with actual verification
in [the implementation checklist](implement.md). This bounded completion does not finish Stage 2,
the guided tools, multi-block single-track coordination, a complete depot mission or live presentation validation.
