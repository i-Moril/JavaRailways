# Independent signal system

> **Updated deployment boundary (2026-09-27):** the child implementation task is authoritative.
> `SimpleATOBukkit` remains an independent Gradle source/test module, but it is embedded into the
> JavaRailways main plugin JAR and has no second Bukkit descriptor or lifecycle. `JapaneseSignals`
> remains the independently installed signal-system plugin. Earlier statements below that describe
> `SimpleATOBukkit` as separately deployed, as the generic signal/rendering owner, or as the owner
> of protection-region policy are decision history rather than the current plan.

## Goal

Deliver an independently installable Signal add-on without making signalling rules, signal persistence, or signal rendering part of the JavaRailways main plugin.

The current brainstorm broadens that extraction into three named modules: platform-neutral `SimpleATO` owns automatic-operation and signalling policy, `SimpleATOBukkit` owns its Bukkit adapter, authoring tools, rendering, script/package runtime, and managed UI integration, and `SimpleATOWebUI` is a separately built frontend bundled and served by SimpleATOBukkit. Basic retains the generic authority-section infrastructure layer containing fixed-block and protection-region definitions. The Basic-domain cut, WebUI packaging, and minimum host/add-on seam are agreed. Fine-grained JavaScript indication interoperability is deliberately deferred to Stage 2 package-runtime design and does not block the Stage 1 refactor.

The work is split into two independently reviewable stages:

1. JavaRailways exposes the stable host APIs and shared safety/resource contracts required by an add-on.
2. A separate Signal plugin implements signalling/interlocking policy, Signal-specific persistence, authoring adapters, rendering, and integration through those APIs.

## Background and confirmed facts

- Core already publishes train head/tail locations and direction-independent occupied rail intervals.
- `SimpleATO` already supports movement-authority, stop, speed-limit, and following constraints.
- `RouteSectionAllocator` currently belongs to `TimetableDispatcher` and locks turnout selections rather than complete track resources.
- The current route model derives an approach stop boundary and local locking interval for every turnout. This does not naturally express one player-placed signal protecting an entire station throat.
- Geometrically crossing rails with different rail IDs are not a shared topology/resource node today, so rail-indexed occupancy does not by itself make an at-grade diamond crossing mutually exclusive.
- `CoreRail.signals`, `CoreWorld.signals`, and Basic's `RailObjectStore` do not form one public add-on contract.
- Bukkit already has an add-on pattern based on interfaces in `bukkit.api.*`, Bukkit `ServicesManager`, and consumer-side `compileOnly` dependencies.
- The current `Signal` abstraction uses Core implementation types and has no versioned persistence or aspect model.
- Existing immutable `TrainSnapshot`, `RouteSnapshot`, and epoch-stamped `PerceptionSnapshot` provide the basis for a JavaScript-facing value contract without exposing live Java world, rail, train, or Bukkit objects.
- Japanese technical guidance demonstrates that the extension contract must cover more than one main aspect: main signals may expose stop/caution/reduced-speed/speed-restriction/proceed/high-speed-proceed indications; distant, passing, and repeating signals depend on another signal; route indicators are separate from the main aspect; and guided/shunting movements are distinct authority cases. ATS/ATC speed supervision remains an authoritative train-control function rather than display-script policy.

## Requirements

- Stage 1 must finish and publish its tested contract before Stage 2 depends on it.
- Dependencies must remain one-way: `SimpleATO` may depend on Core/Basic public APIs, and `SimpleATOBukkit` may depend on SimpleATO plus BukkitPlugin public services; Basic and BukkitPlugin must not depend back on the optional modules.
- The complete operations domain moves from Basic into SimpleATO as one coherent boundary: destinations, stations, depots, lines, timetables, rolling-stock allocation, operational route planning, and their persistence.
- Operating direction and its persistence move into SimpleATO. Basic retains physical topology and physical/design constraints, not an operating-direction policy.
- The current destination-aware `PathFinder`, destination-index `RailMap`, and operational `RoutePlanner` move into SimpleATO. Basic must not keep a parallel pathfinding implementation.
- BukkitPlugin is reduced to lower-level railway/platform behavior such as RailWand, core train/rail rendering, manual driving, world integration, and generic public services. A committed turnout topology edit is announced through a public event/service so optional SimpleATOBukkit can create or refresh suggested protection-region entrances without a direct RailWand dependency.
- SimpleATOBukkit owns RailObjectWand and the Bukkit-facing commands, rendering, Signal/operations persistence adapters, and authoring flows. Fixed blocks and protection regions are edited through Basic APIs; destinations, signals, ATO, and timetables use their corresponding SimpleATO APIs.
- SimpleATOWebUI is a separate frontend build module so its toolchain and tests remain isolated, but it is not a separately installed server plugin or independently versioned runtime. SimpleATOBukkit bundles its production assets, serves its API/static content, and owns compatibility and lifecycle; administrators install only SimpleATOBukkit.
- Signal models and policy are extensible through stable package/provider contracts: YAML describes static model/configuration data, sandboxed JavaScript may derive restrictive policy/aspect outputs from immutable snapshots, and Java plugins may register providers through public services. Persisted data uses stable namespaced kind IDs and never reflectively loads a class from a save string.
- Signal package output supports named indication channels rather than one indivisible aspect string, so one installation can combine a main aspect, route indicator, speed indicator, and auxiliary indication without enumerating every visual combination as a separate aspect.
- Distant, passing, repeating, and other explicitly dependent signals may observe only explicitly bound signal results through a validated acyclic dependency graph. Automatic-block main signals instead receive a route-relative forward observation compiled from topology and the selected route up to that signal's declared scan distance; JavaScript cannot perform arbitrary signal lookup or graph traversal.
- SimpleATO owns versioned authority modes and enforced target-speed profiles. JavaScript may render or further restrict a confirmed `NORMAL`, `RESTRICTED`, `SHUNT`, or future authority mode, but cannot create that authority mode or relax its profile.
- Physical occupancy, resource grants, route validity, topology revision, and braking enforcement remain host-authoritative. Script or model extensions may contribute more restrictive speed/stop/aspect decisions but may not declare occupied infrastructure free, bypass a denied resource grant, or directly mutate topology/turnouts.
- The Signal plugin must be a distinct deployable JAR with its own lifecycle and persistent-data ownership.
- JavaRailways must remain usable when Signal is absent.
- JavaRailways must own generic rail topology, immutable train/occupancy observations, atomic shared-resource arbitration, and enforceable train-control constraints.
- Basic must own fixed-block and protection-region definitions, their non-overlapping authority partition, topology validation, occupancy/admission snapshots, and resource contribution. Signal owns interlocking/signalling policy, signal aspects, signal models, authoring UX, and Signal-specific persistence.
- Replace automatically generated per-turnout protection/stop boundaries with player-authored, direction-aware protection-region boundaries. The bounded interior subgraph is an interlocking region; a train may pass an entry only after one atomic request has obtained every resource required by its frozen entry-to-exit route and proved that the train can clear the exit.
- Protection regions and fixed blocks are mutually exclusive members of one authority-partition layer. They may meet at a directional boundary but their controlled track extents may never overlap. A protection region itself occupies the authority-segment position that an ordinary block would otherwise occupy, while using route-specific entry-to-exit resource allocation internally.
- Authoring or loading a fixed block whose track extent overlaps a protection region is invalid and must be rejected with the conflicting stable IDs and locations. The compiler must not silently clip, split, or reinterpret either player-authored object.
- Fixed blocks and protection regions implement one public authority-section contract. Signal policies consume the same traversal-scoped section snapshot and decision surface for either implementation and must not require a `FIXED_BLOCK` versus `PROTECTION_REGION` branch for ordinary occupancy, availability, stop, speed-limit, or lookahead behavior.
- A protection region may hold multiple non-conflicting traversal grants concurrently. Its common section state is therefore evaluated for a concrete directional entry, selected exit, route revision, and subject train/grant; it is not a single region-wide occupied/free boolean.
- The ordinary JavaScript section surface is minimal: traversal-conflicting train presence, usable section length, and host-computed `canEnter`. Raw reservation ownership and detailed availability enums remain host/provider diagnostics rather than required ordinary policy inputs.
- Each signal model/package YAML declares `scanDistanceMeters` together with the JavaScript policy that interprets the resulting forward observation. A placed signal instance references that model and cannot override the distance independently. The policy may iterate every relevant section boundary within that distance; each boundary provides cumulative distance and the committed effective speed limit, while whether v1 also exposes a same-package semantic indication/model channel remains an explicit API decision. There is no declared signal count and no secondary record-count truncation: consecutive short sections contribute every boundary inside the model-declared distance.
- A fixed block and a selected entry-to-exit traversal through a protection region contribute the same section/boundary shape to the forward observation. An unresolved, denied, invalid, or stale protection-region route terminates the observation at its entrance with a fail-safe zero-speed target; a confirmed route continues from its selected exit without requiring a script branch on section kind.
- Forward observations resolve stable handles at placement/rebind/topology-update time and route-specific slices when a turnout/protection exit is selected. A changed downstream result dirties only policies whose cached forward observation contains it, and evaluation proceeds until outputs stabilize; runtime JavaScript performs no topology search.
- Failed region requests must hold no partial resources. Adjacent regions without enough space to hold the complete train outside either region must be merged or acquired as one transaction so cross-region hold-and-wait cycles cannot form.
- Model an at-grade diamond crossing as a stable topology conflict node similar to a turnout, but with immutable connectivity: it contributes a shared exclusive resource and never permits switching between the crossing lines.
- Detect or refresh diamond-crossing nodes during rail placement/topology updates using spatial candidates plus exact geometry checks. Runtime operation consumes the stored node/resource and must not repeat global geometry intersection work per tick.
- Timetable routing and Signal must use one authoritative resource-arbitration service; they must not maintain independent turnout locks.
- Train protection must use one perception/constraint pipeline. MA and following protection must not perform independent forward scans or maintain competing permission state.
- Signal failure, disable, missing state, or stale state must fail safe for constraints it owns. Cleanup semantics must be explicit and tested.
- Persistent references must use stable IDs plus canonical AB arc lengths and directions. Add-on data must not serialize Java/Kotlin class names or Core implementation objects.
- Cross-thread communication must use immutable values with an explicit physics epoch/revision. Bukkit side effects must return to the correct Folia owner.
- Correctness is defined against the plugin's documented simulation model and supported operating assumptions, not railway-certification requirements. The design must prefer bounded deterministic work over realism that does not improve gameplay safety.
- Runtime work must reuse the existing per-train braking-horizon scan, aggregate world occupancy once per physics publication, update interlocking on relevant state transitions, and render only changed aspects. It must avoid train-by-region Cartesian scans and duplicate per-train route traversal.
- An automatic timetable may start only from an applied plan compiled against one coherent topology/signalling revision. Apply validates every statically reachable automatic route, protection coverage, signal/interlocking health, resource references, and exit-clearance assumptions; structural signalling errors fail the complete apply operation rather than becoming runtime waits.
- Successful timetable apply atomically publishes non-exclusive dependencies over its exact persistent rail IDs, stable topology-node keys and runtime resource IDs. These dependencies prevent intersecting topology, rail, turnout/diamond, protection-region, signal, and referenced operational-object edits while allowing other timetables to declare the same dependencies and allowing runtime route grants to arbitrate train movements independently.
- Draft validation/apply, configuration leases, and runtime route claims are distinct lifecycles. Transient occupancy or another train's live route claim does not make timetable apply fail; it is handled when a service requests its runtime route.
- Timetables have an explicit `DRAFT` versus `APPLIED` lifecycle. Drafts may be saved, edited, and dry-run validated without publishing dependencies or starting automatic services. Only explicit apply may install a compiled plan and publish dependencies; unapply first enters a draining state that blocks new departures/grants before removing them.
- Signal lookahead is compiled when a signal is placed, rebound, or affected by a topology/route revision. Runtime policy evaluation reads bounded pre-resolved handles/arrays and must not perform topology search, label scans, or world-wide signal lookup.
- JavaScript uses imperative-looking setters such as `setSpeedLimit(...)` and `setModelVariant(...)`, but they record one evaluation-local transactional decision. Signal `setSpeedLimit(value)` means the target speed at that signal boundary and is enforced through a distance-aware approach curve; zero therefore means stop at the signal and no separate ordinary `requireStopAtEntry()` command is exposed. No setter mutates live physics, interlocking, persistence, or Bukkit rendering state before the complete callback has succeeded and its revisions have been validated.

## In scope

- Stage 1 host API and resource-control improvements in Core, Basic, and BukkitPlugin.
- Stage 2 independently packaged Signal add-on.
- Player-authored authority-section/interlocking foundations, train authority integration, owner-specific persistence, authoring, rendering, lifecycle, and failure behavior required by the agreed MVP. Basic persists fixed-block/protection-region infrastructure; Signal persists only its operational and presentation data.
- Focused documentation and compatibility tests proving that the add-on consumes public APIs only.

## Out of scope

- Folding Signal implementation classes into the main JavaRailways runtime.
- Supporting arbitrary development-era Signal save formats through permanent runtime compatibility paths.
- WebUI signalling panels unless separately added to the Stage 2 MVP.
- Full prototype-specific national signalling rule sets in the first release.
- Certification-grade communications, train-integrity proving, redundant onboard/wayside systems, and real-world worst-case braking assurance.

## Acceptance criteria

- [ ] Stage 1 has a reviewed, documented, and tested public contract usable by an external plugin without imports from `core.*` implementation, `basic.*` implementation, or `bukkit.*` internal packages.
- [ ] Existing timetable and ATO behavior uses the shared resource/authority contract without regressing current route safety.
- [ ] Player-authored directional boundaries compile a bounded interior subgraph and an entry-to-exit resource bundle; unavailable bundles stop the train outside the entry and leave no partial claims.
- [ ] An at-grade diamond crossing is detected on placement/update, persists as an immutable-connectivity topology conflict node, blocks simultaneous conflicting passages, and does not create a route from one crossing line onto the other.
- [ ] Stage 2 builds a separate Signal JAR and declares JavaRailways as its host dependency without shadowing the host plugin.
- [ ] Installing Signal adds the agreed signalling behavior; omitting it leaves JavaRailways operational.
- [ ] Signal reload/disable/world-unload behavior releases or retains resources according to documented fail-safe rules, with no silent authority expansion.
- [ ] Signal configuration survives restart through a versioned Signal-owned persistence boundary and rejects corrupt/orphan references with actionable errors.
- [ ] Cross-stage integration tests cover occupancy, conflicting routes, turnout locking, authority enforcement, tail release, host/add-on lifecycle, and restart restoration.
- [ ] A short-block fixture reproduces `G - YG - Y - Y - R - occupied - G`: S2 can distinguish the downstream `Y,Y` case from `Y,R` through the host-built forward observation, without a declared signal count or runtime graph search.
- [ ] Consecutive short sections may produce an arbitrary number of forward entries up to the signal's declared scan distance, including repeated `YY` operation; encountering a protection region either continues along its confirmed selected traversal or terminates fail-safe at its entrance.
- [ ] A signal model's package-owned `scanDistanceMeters` deterministically controls the final included forward boundary by cumulative route distance; placed-signal persistence stores the stable model reference rather than an independent distance override, and normal completion at that distance is not reported as truncation or failure.
- [ ] Applying an automatic timetable either atomically installs one revision-pinned compiled plan plus its explicit rail/node/runtime-resource dependencies, or changes nothing and reports every structural route/signalling error. Editing an intersecting identity reports the owning timetable/revision, while applied timetables may declare overlapping dependencies.
- [ ] Saving or validating a draft never mutates applied-plan dependencies; only an explicit successful apply publishes them, and unapply removes them only after new work is blocked and live runs/claims are safely drained or administratively resolved.

## Key decisions

- Use one parent task and two ordered child tasks.
- Keep deployment and Signal/operations persistence ownership in a separate Signal plugin; Basic retains the persistence boundary for its authority-section infrastructure definitions.
- Put only reusable mechanisms and public services in JavaRailways; signalling policy stays in Signal.
- Use Bukkit `ServicesManager` as the add-on discovery boundary, matching the existing add-on pattern.
- Treat MA as the consolidated permission boundary produced by the shared constraint pipeline, not as a second detection engine beside `FollowConstraint`.
- Use the existing dynamic following constraint as the game-grade continuous-separation mechanism. The first Signal release adds player-authored protection regions and interlocking through the same constraint pipeline; it does not implement a second moving-authority controller.
- Protection belongs to a player-authored region entry, not to each turnout. Turnouts and diamond crossings contribute resources inside a route window; they do not independently choose approach stop points.
- Use all-or-nothing entry-to-exit allocation plus exit-clearance admission to prevent interlocking resource deadlock. Deterministic queuing/aging handles starvation separately.
- Compile one route-oriented authority sequence from non-overlapping ordinary fixed blocks and protection regions. Region admission atomically includes the downstream receiving-block/clearance claim required for the full train to leave the region; that cross-boundary reservation does not make the authored areas overlap.
- Keep rail connectivity in Core and operational path selection in SimpleATO. Extract a policy-free graph-search primitive into Core only after a second non-operational consumer proves the need; it must not depend on destinations, operating direction, timetables, signals, or interlocking state.
- Build WebUI separately as `SimpleATOWebUI`, then bundle its version-matched production assets into SimpleATOBukkit. Do not create a second independently deployed server component for the initial release.
- Use a command-style JavaScript DSL backed by an atomic decision builder rather than requiring scripts to return one aspect string. Commit the validated result once, merge safety outputs by the most restrictive rule, and render only when the committed model variant changes.
- Expose signal-boundary target-speed and model-variant commands through the same transactional decision builder whether the protected section is a fixed block or protection-region traversal; scripts never mutate the section implementation directly.
- Treat `scanDistanceMeters` as versioned signal-package policy configuration coupled to its JavaScript logic, not as player-authored placed-signal state.
- Without SimpleATO or a validated protection region covering every controlled resource, the host rejects automatic and route-assisted turnout requests. It does not reconstruct a per-turnout fallback boundary. Routes containing no turnout/diamond/other controlled resource remain operable, while explicit manual turnout operation uses a separate physical-lock-checked path and grants no automatic authority.
- Treat timetable infrastructure protection as non-exclusive rail/node/runtime-resource dependencies published by an applied compiled timetable, not as runtime exclusive route ownership. Runtime entry-to-exit grants remain short-lived and exclusive only where resource conflict requires it.
- Use explicit `DRAFT`, `APPLIED`, and draining/unapplied lifecycle states. Draft persistence is independent from the revision-pinned applied-plan record and never locks infrastructure.

## Deferred Stage 2 decisions

- Finalize whether a downstream signal snapshot exposes only its committed effective speed limit, or also a same-package semantic indication/model channel for rules that cannot be reconstructed from speed alone. This does not affect module extraction, resource arbitration, authority-section contracts, or Stage 1 public host services.
- Finalize whether the first signal-package release implements only normal main/distant/repeating/route-indicator operation while reserving generic restricted/shunting authority modes in the API, or also implements guided/shunting movement workflows. The refactor need only preserve a typed extension point.

## Task map

- `09-18-signal-addon-host-apis`: Stage 1, depends only on the existing JavaRailways modules.
- `09-18-signal-addon-implementation`: Stage 2, blocked until the Stage 1 public contract is complete.
