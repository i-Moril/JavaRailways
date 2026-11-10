# Design

> Current: [interior screen viewing distance](research/interior-distance-contract.md), with eye-to-transformed-screen distance explicitly required by the user. Integrated verification is deferred until all work is complete. [Culling assessment](research/interior-display-culling-assessment.md) is advisory; no extra culling enabled. Operational data batch remains implemented/uncommitted; PAPI is excluded.

> Completed milestones: Decorations foundation `95935639`, template compilation `84b804fc`, native/packet text backends `3aaa3af3`, static mounting `a4604a43`, dynamic sessions/basic train input `09da5fbc`. Independent SimpleDisplays XML/document JS is at `410af2e`. Current module boundaries and contracts supersede historical built-in screen/loading notes below; schema consistency remains a separate child before acceptance.

## Ownership and boundaries

SimpleDisplays owns XML compilation, data binding, icon animation, logical screen coordinates, and the public draw-result types. Its standalone plugin owns its preview command and native world placement.

JavaRailways owns train-package discovery, car installation points, category/style selection, operational data projection, car-local-to-world transforms, viewer visibility, backend-specific entities, and lifecycle cleanup. The integration loads `simpledisplays.api.DisplayService` from Bukkit's service registry only when the soft dependency is present.

SimpleATO remains the source of timetable and stopping-pattern state. It does not import Bukkit or SimpleDisplays types. Bukkit adapts immutable operational snapshots into SimpleDisplays values.

## Package model

Current slice: [screen part definitions and examples](schema/README.md). The user approved only the
reviewed part model/schema/parser shape. It is implemented without screen visuals; style resources and
new package/snapshot formats remain later work. Definition, copy and canonical-hash contracts are owned
by the [screen-part spec](../../spec/bukkit-plugin/backend/screen-parts.md).

Add `screen` to the existing part types. Screens may be inline nodes or imported reusable parts in
`body-parts`/`parts`; they reuse common `name`, parent-relative `offset`/`rotation`, nested parts and
animation structure. The required `screen` object contains `category`, `interior`, `width` and `height`.
Do not add a parallel car-root `screens` collection or separate screen IDs. Point-specific style binding
is later work: determine instance addressing from the existing part tree before freezing a binding key.

Add package-level category-to-style defaults and package-contained XML style assets. Style references use stable namespaced IDs. Package validation rejects non-positive screen dimensions, invalid poses, unsafe/missing asset paths, and unresolved defaults. Existing part-name/import rules remain authoritative; reusable part names are not globally unique screen instance IDs.

The package snapshot/pinning behavior must include everything required to reproduce the installed train's display geometry and package-owned style content. Before changing snapshot fields, confirm whether the package boundary has a released or frozen baseline. Prefer additive fields with explicit empty defaults.

## Style resolution

Resolve one style per installation point using this order:

1. runtime world/fixed-unit/category override
2. screen config.template default
3. package category default

No match means the installation point is inactive. A category is a selector and semantic convention, not an enum discriminator. Recommended names are documented, while custom names remain legal.

Compile XML once per package/style content identity and cache immutable `DisplayTemplate` instances. Runtime draw evaluation receives immutable values and elapsed animation ticks. Reload publishes a fully prepared replacement cache and does not mutate templates already used by pinned models.

## Rendering adapter

Extend JavaRailways' display abstraction with text content and text updates. Native creates Bukkit `TextDisplay` entities. EntityLib creates equivalent packet text-display wrappers. Both consume the same `TextDraw` list.

Each logical screen is a child render group under the car body transform. Draw coordinates are converted from the template's top-left coordinate system into the installation plane. The adapter applies physical fitting and the 128-units-per-block convention. Content/animation changes update text metadata, while normal train ticks update the screen group's world transform through the existing bundled car update path.

Exterior screen groups follow the car body's audience rules. Interior screen groups further filter that audience by the player's configured interior-screen viewing distance, as described below. All screen groups follow the car model's destruction completion. Plugin absence yields no screen group. JavaRailways never asks SimpleDisplays to spawn standalone Bukkit entities for train-mounted screens.

## Runtime data

Introduce a Bukkit-side immutable projection containing scalar train/service fields and an ordered list of future stops. Keep the SimpleDisplays API evolution needed for repeated/list content in the SimpleDisplays repository. JavaRailways supplies values and identifiers, while the template decides layout, highlighting, paging, and animation.

Refresh draw content when the selected style, projected operational data, or relevant animation frame changes. Avoid recompiling XML or rebuilding the entire car model for routine station progress.

## Lifecycle and failure behavior

Discover the optional service during BukkitPlugin enable after optional dependencies have loaded and before model packages are prepared. Do not retain implementation classes or assume the provider survives disable/reload.

All entity operations use the existing target-aware render ownership paths. Model rebuild, world unload, car removal, audience loss, plugin disable, and partial construction failure retire every screen visual. A failed screen creation rolls back the partially created screen group without discarding the rest of the car model unless package validation had already declared the model invalid.

## Runtime switching (confirmed option2, implemented; integrated verification deferred)

Decorations owns DisplayStyleService, keyed by world UUID / actual fixed-unit ID / category. Player commands and API share atomic captured-catalog validation. Overrides survive reverse/split/couple/model rebuild and clear on accepted world unload or plugin disable/restart. No persistence is added. Hidden metadata updates preserve unit binding; effective-selection revisions clear old output and replace only affected private templates. See [the exact contract](research/style-selection-contract.md#confirmed-fixed-unit-runtime-slice-user-selected-option2). JS-triggered switching remains future work.

## Screen visibility decision (updated 2026-11-10; implemented, integrated verification deferred)

Exterior screens are simple and use the same rendering/visibility strategy as the car body; no additional screen-specific culling policy is required for them. Interior screens initially use a configurable viewing distance, analogous to existing train/rail viewing distance. Server config supplies the setting and players can override their own interior-screen distance using a command. The screen part's interior/exterior attribute selects the policy; category names do not imply it.

Reuse the existing car audience as the candidate set and apply the effective interior-screen distance as an additional filter. Do not require a cabin occupancy box, seat membership or same-car membership. A player outside the car may receive its interior screens when within the effective viewing range and the existing car audience. The first release does not add screen-backface, camera-FOV/frustum, raycast or mesh-occlusion tests.

Existing evidence: `RenderChunkManager` spatially indexes car candidates and refreshes on player movement and moving renderables; `ViewDistanceManager` stores player-specific distance settings; `CarExtension` has a separate distance-based detail audience. Reuse these ownership and refresh patterns rather than scanning all world players per screen. The user now explicitly requires eye-to-screen distance at the fully transformed part origin. Decorations owns default32/max128 block settings and login-only player commands; exact contract is research/interior-distance-contract.md. No car-center approximation. Integrated verification is intentionally deferred.

On player entry, send the group's current state; on exit, remove its visuals for that player and stop movement/content packets. Do not broadcast metadata to excluded players. A group with no viewers should not evaluate animation frames solely for invisible output; reconstruct its current frame from the animation clock when a viewer returns. Actual backend parity and ownership must be verified in the screen-group implementation stage. A moving train must refresh stationary-player eligibility, and command changes must refresh the requesting player's audience. Capture player state on its entity owner and consume immutable car frame data, without cross-region live-player reads.

Stage 2 declares, validates and snapshots the interior/exterior screen-part metadata. Stage 5 implements distance filtering, config and player command support with screen groups. The screen-part interior field is implemented; the distance policy/config/player command are now implemented with compilation checks; integrated testing is deferred.

## XML / placeholder discussion (not yet implemented)

Source checked at independent SimpleDisplays commit `21f7686`: keep its existing XML and public
`DisplayService.compile(xml, icons)` / `DisplayTemplate.draw(values, ticks)` contracts. `<span bind>`
looks up one exact string key in the value map; absent keys use the span text fallback. This is not
currently an external PlaceholderAPI integration or arbitrary text interpolation engine. Existing
supported elements are screen/div/span/icon; list repetition is not present in the current compiler.

The user confirmed that placeholder adaptation means the Bukkit PlaceholderAPI plugin. Concrete
dependency wiring and syntax remain to be designed. A host-owned immutable train-context value map can
adapt to the current draw API; external/player-context expansion requires an explicit per-viewer
context and refresh boundary. Do not preprocess complete XML with dynamic values, duplicate
the XML parser in JavaRailways, or make a second XML dialect merely to inject scalar train values.

## SimpleDisplays JS drawing discussion (initial proposal, before implementation)

The user asks whether SimpleDisplays can run JS to generate dynamic elements. Proposed direction:
keep static XML valid and add optional scripted dynamic output, converted into the same renderer-neutral
TextDraw/frame data as existing XML. SimpleDisplays owns this capability independently of JavaRailways;
JavaRailways supplies immutable train/run/stopping-pattern data and attaches resulting visuals to parts.
This requires extending the present scalar-only rendering context/API; JS, structured stop lists and
script resources are not currently implemented in SimpleDisplays.

PlaceholderAPI's official usage accepts Player/OfflinePlayer context and expands text, so a train's
own operational data and a viewer's placeholders must remain distinguishable. Do not select a random
nearby player or embed train context in a global mutable placeholder state. The user confirmed that
one display must support different frames for different viewers; cache/execution design must preserve this capability.

Suggested script model: a bounded render callback over immutable data and elapsed ticks returns drawing
commands, without Bukkit/entity/Java/IO access or script-created timers. Prepare placeholder results in
an appropriate host context before worker drawing; third-party expansions must not be assumed safe for
arbitrary asynchronous/Folia access. Share frames for identical inputs, skip invisible output, cap output
nodes and execution, and validate a complete frame before publishing it. Exact scheduling, quotas,
failure policy and stateful versus pure scripts remain unapproved design choices.

References checked: PlaceholderAPI official usage guide
https://wiki.placeholderapi.com/developers/using-placeholderapi/ and GraalVM sandbox guide
https://www.graalvm.org/latest/security-guide/sandboxing/ . Existing JavaRailways TrainBehaviorRuntime
shows restricted GraalJS bindings, but SimpleDisplays currently has no JS runtime dependency. Do not
assume ordinary host-access restrictions provide separate-heap or process isolation.

## Per-viewer content (confirmed capability, 2026-11-09)

SimpleDisplays is a reusable library and must support different content for different viewers of the
same display instance/template. The API cannot assume one screen implies one shared content frame;
this capability applies to its independent renderer and to JavaRailways consumers.

Design consequences (interface details still to specify): separate immutable compiled templates,
display-instance/domain data, viewer inputs and produced frames. Pass explicit viewer inputs to XML/
PAPI/JS evaluation without making the compiler depend on JavaRailways types. Bukkit adapters provide
the actual viewer for PAPI expansion; any worker evaluation consumes prepared immutable values rather
than freely reading live cross-region Player objects. Keep domain/train context separate from viewer
identity. Do not require all hosts to provide a train or fabricate a player for viewer-independent data.

Mutable per-viewer content, cached placeholder results and any future script state must not leak to
other viewers. Templates may be shared; output caching may merge frames only when the relevant inputs
are equivalent, rather than keying solely by template/screen. This does not mandate one JS engine or
context per player, nor settle stateful script behavior, refresh timing or output-cache lifetime.

Backend design must honor the same capability: packet backends can isolate outgoing content by
recipient; native shared entity metadata alone cannot represent differing text for two viewers.
The native adapter must use a deliberate per-viewer visibility/entity strategy or equivalent isolated
output, rather than repeatedly overwriting one globally visible entity. Rendering/backend implementation
remains later work; no production API or backend has been modified by this discussion.

## PlaceholderAPI data-flow proposal (discussion only)

Recommended first integration: SimpleDisplays owns an optional Bukkit PlaceholderAPI adapter, while
its compiler/core accepts generic resolved viewer inputs rather than live Bukkit Player objects.
Use the actual viewer as PAPI player context. XML text may contain ordinary `%identifier_params%`
placeholders; expand text content during frame preparation, not whole XML before compilation. Existing
`bind` continues to select host-supplied domain values and is not silently redefined as a PAPI lookup.
Suggested processing: obtain raw bound/fallback or JS-produced text, then expand its PAPI text where
appropriate without recompiling XML. Interpolation applies to intended text fields, not arbitrary style
attributes/XML structure or recursively reinterpreted PAPI output.

For script logic that needs placeholder values before producing text, a proposed explicit dependency
list lets the Bukkit adapter prepare deduplicated values in advance. JS then reads an immutable snapshot.
This supports conditions/calculation without running arbitrary third-party expansions inside worker JS.
Whether to require that declaration, and the exact JS access syntax, remain author-facing decisions.
Late/dynamically constructed placeholder keys are not solved by assuming arbitrary async evaluation is
safe; they require a separate host-evaluation/scheduling design if requested.

Domain data belongs to the target display instance (e.g. the actual car/run); viewer placeholders belong
to the actual viewer. PAPI's standard `setPlaceholders(player, text)` does not carry a display/train
instance. A viewer looking at two trains must not obtain both destinations from a guessed player-bound
or nearest train. JavaRailways supplies target-specific immutable domain data separately; no global
mutable current-train context is introduced.

Cache viewer-dependent results by viewer and relevant request, with bounded lifetime and current-input
publication checks. Deduplicate repeated reads during a refresh, independently of animation-frame
updates; no blanket per-frame PAPI calls for every glyph. Shared/global results may only be shared when
semantics permit, not merely because placeholder strings match. Discard stale completions after viewer
leave, display destruction/replacement or provider changes. Concrete refresh/lifetime settings remain
unapproved. PAPI calls synchronously invoke expansion callbacks; Bukkit/Folia ownership and expansion
compatibility must be respected by the adapter, not assumed from PAPI's existence.

Suggested fallback: static XML and ordinary bindings work without PAPI. Unresolved PAPI tokens remain
literal (matching upstream replacement behavior); avoid unbounded repeated logs. PAPI expansion does
not execute its returned value as XML/JS. These are proposals, not implemented behavior.

Primary source inspected: https://github.com/PlaceholderAPI/PlaceholderAPI/blob/master/src/main/java/me/clip/placeholderapi/PlaceholderAPI.java
and replacer/CharsReplacer.java (direct onRequest call and original-token fallback). No source files,
external SimpleDisplays checkout or dependencies were changed during this discussion.

## Authorized JS implementation boundary (2026-11-09)

The user approved implementing the JS part first. The chosen library API adds an explicit JavaScript
compile operation and closeable script template with a per-call generic context, while preserving XML
compile/draw. `ctx.data` is host-provided structured domain data, viewer identity/data are explicit,
and `ctx.placeholder` delegates only to a caller-provided resolver. No actual PAPI adapter is included.
Caller-owned scheduling remains explicit; the library does not move PAPI work into arbitrary workers.

Scripts receive invocation-local mutable state, not a shared persistent global between viewers.
Templates can reuse an Engine/Source but each invocation creates a restricted Context. This initial
approach favors isolation and deterministic inputs; its per-invocation setup cost must be documented,
not described as a proven high-frequency rendering optimization. Emit existing renderer-neutral
TextDraw data; no entities are spawned by JS. Deadlines and bounded data/output protect execution
lifecycle, without claiming JVM heap/process isolation or forceful interruption of arbitrary host callbacks.

Implement in the independent SimpleDisplays sibling worktree, with API/lifecycle documentation there
as the mechanism owner. JavaRailways remains only the task planning/evidence holder in this slice.

## Implemented library milestone (2026-11-09)

The independent JS library slice above is now implemented as SimpleDisplays `db9c65e`. Earlier discussion sections describe the pre-implementation baseline and alternatives, not the current absence of JS. The implemented contract uses synchronous `render(ctx, draw)` command emission, a caller-injected synchronous placeholder resolver, fresh contexts and fixed execution/data limits. Future JR caching, asynchronous scheduling and actual PAPI integration remain proposals. See [the validation record](research/simpledisplays-js-validation.md) and the SimpleDisplays README for the actual API and limits.

## XML/document JS revision (2026-11-09)

The user superseded the standalone JS design and asked to redo from `21f7686` without a revert commit. The authoritative current slice is [XML/document JS contract](research/xml-document-js-contract.md). Earlier standalone-JS implementation/evidence is historical and not the current API. Package template wiring remains deferred.

Current implementation: SimpleDisplays `410af2e`, XML → invocation-local document/JS → existing XML layout → TextDraw. Direct parent `21f7686`; no revert. See [replacement evidence](research/xml-document-js-validation.md).

## 配置表达规范与模板声明草案

用户确认先建立同类概念的共同表达规则，再设计显示配置。参见[共同规范及逐项对照](research/config-schema-conventions.md)、[displays.yml草案](schema/displays.example.yml)和[草案schema](../../../schemas/train-model/displays-v1.schema.json)。这是设计材料；不调整已有内联部件版本、动画定义集合或正式schema。模板统一引用XML，内嵌JS由SimpleDisplays处理，无独立type/重复尺寸。下一实施批次需以审阅后的格式为准。

## 显示资源加载批次完成

包内XML原文和类别默认映射已接入PackageLoader、主包/来源车厢快照及现有Store重算。正式schema归入schemas/train-model，不保留完整task副本。维护机制见[显示模板包资源](../../spec/bukkit-plugin/backend/package-displays.md)，本轮证据见[验证记录](research/package-display-loading-validation.md)。尚未编译XML或接屏幕实体。


## 完整站点序列与真实进度（2026-11-10）

按[当前合同](research/stop-sequence-contract.md)实现：SimpleATO owns transient occurrence/arrival/departure facts，宿主只发布一份完整stops、nullable0-based stationIndex和phase。不将completed诊断等同于已经驶离；不按站名、stationId、速度或计划时刻猜本站。重复到访及合法TERMINAL/ORIGIN合并通过exact call身份映射到序列索引。actual currentRun规则保持；车辆信息切换时机留TODO，无displayRun/提前切换。只改transient接口，不改持久化边界或schema版本；现有HTTP诊断模型不随JS专用输入机械替换。


## 本侧/对侧开门接口

用户确认复用实际开门的折返与逐车安装方向语义，按[本批合同](research/door-side-display-contract.md)实现。原始开门侧由SimpleATO拥有，逐车操纵端换算由宿主复用已有helper，Decorations只比较固定安装侧。继续仅编译/静态检查，测试及服务器验收后置。
