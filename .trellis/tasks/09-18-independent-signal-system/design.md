# Independent signal system design

## Architecture boundary

The task uses the following agreed module boundary. Fine-grained signal-package indication interoperability is a deferred Stage 2 policy-runtime decision and does not block this extraction design.

```text
Core
  physics / topology / occupancy / extension primitives
   |
Basic
  physical topology / authority sections / resource arbitration / manual railway mechanisms
   |
SimpleATO
  operational domain / ATO / timetable / signalling and interlocking policy

BukkitPlugin
  world adapter / RailWand / manual driving / generic render and operation services
   |
SimpleATOBukkit
  RailObjectWand / ATO and signal commands / package runtime / signal rendering / UI bridge
       |
SimpleATOWebUI
  separately built frontend, bundled and served by SimpleATOBukkit
```

All arrows are consumer-to-provider dependencies only; the base modules publish events/services and never link back to optional SimpleATO modules.

```text
Signal add-on
  operational routes / signal aspects / Signal persistence / rendering
             |
             | Bukkit ServicesManager public contracts
             v
JavaRailways host
  immutable rail + train observations
  atomic route-resource arbitration
  enforceable movement-authority / speed / stop constraints
  topology lifecycle notifications and platform dispatch
             |
             v
Core physics publication and Bukkit/Folia side effects
```

The safety source of truth is the confirmed resource allocation plus the resulting train-control authority. A displayed aspect is a projection of that state; it is not an independent permission for movement.

The target is a game-grade safety model: atomic conflict prevention, conservative handling of unknown state, deterministic braking targets, and bounded work. It does not model certification-grade communications or every real railway failure mode.

## Ownership

- Core owns platform-neutral topology, rail coordinates, train placement, occupancy intervals, physics epochs, generic controller attachment, and extension primitives.
- Basic owns reusable physical topology/geometry, physical/design rail metadata, fixed-block and protection-region definitions, the unified authority partition, resource arbitration, train dynamics/safety calculations, and manual-operation mechanisms.
- SimpleATO owns operating direction, destinations, stations, depots, lines, timetables, rolling-stock allocation, operational routes and pathfinding, constraint enforcement policy, signal/interlocking policy contracts, and their platform-neutral persistence schemas.
- BukkitPlugin exposes generic world, topology-event, player-operation, render, and platform-dispatch services while retaining RailWand and manual driving.
- SimpleATOBukkit owns RailObjectWand, ATO/signal commands, signal packages, rendering, WebUI integration, and all Bukkit-facing SimpleATO lifecycle/persistence adapters.
- SimpleATOWebUI owns frontend source, frontend-only dependencies, build tooling, and UI tests. Its production assets are embedded into SimpleATOBukkit; it has no independent server lifecycle, persistence boundary, or installation artifact.

## Pathfinding boundary

The current Basic pathfinding code is operational rather than generic:

- `PathFinder` searches for a destination label through `RailMap` and applies `allowedDirection`.
- `RailMap` is a destination index, not the physical rail graph itself.
- `RoutePlanner` expands routes between operational waypoints and reads destinations and waypoints from `BasicWorldData`.

These services move to SimpleATO with the rest of the operations domain. Core remains the source of rail connectivity through `RailView` and neighboring views; SimpleATO decides which direction is operable, what the operational goal is, and how an operating route is selected.

Do not retain or create a parallel generic pathfinder in Basic. If a future second consumer needs a reusable search algorithm, extract only a policy-free primitive into Core, parameterized by a goal predicate, neighbor provider, and edge-cost function. It must know nothing about destinations, operating direction, signals, turnouts, timetables, or persisted operations state.

## Protection-region and topology model

The MVP removes the automatic "one turnout, one approach stop boundary" operating model. Players author direction-aware boundary gates through SimpleATOBukkit, which submits edits to the Basic-owned protection-region store. Basic cuts every gate from the directed topology and requires one validated, bounded interior subgraph. A route window has one entry boundary, one exit boundary, a frozen path through that interior, the complete resource claim set for that path, and enough downstream clearance for the whole train to leave the region.

Turnouts remain selectable topology nodes and stable resources. An at-grade diamond crossing does not change connectivity and therefore is not a topology node: both crossing lines retain their original route edges, no turn between lines is introduced, and route-bound anchors on both passages share one exclusive resource identity. Candidate diamonds are found only when rails are placed or topology is updated, using the rail spatial index followed by exact curve/clearance validation; accepted resource bindings are indexed by rail/edge interval and referenced at runtime.

Allocation is all-or-nothing. A denied request acquires nothing and remains outside the entry boundary. A granted train never waits for a newly discovered resource inside the region because a generic compiler follows the frozen route, folds every resource contribution, and extends a monotonic minimum-claim boundary until the complete train clears every encountered obligation. A region never inspects its successor and SimpleATO never builds a hand-written composite window; adjacent contributors are naturally included in one closure before entry.

RailWand commits generic topology only. A public topology-change event carries the affected stable rail/node references and revision; SimpleATOBukkit may create editable protection-boundary suggestions, but accepted create/update/delete operations go through the Basic authority-section API and store. Removing or changing those definitions never mutates the base rail topology.

### Composition with fixed blocks

Fixed blocks and protection regions are two mutually exclusive segment kinds in one route-oriented authority partition and sequence:

```text
FixedBlock -> ProtectedRouteWindow(entry, selected exit, claim set) -> FixedBlock
```

A directional protection entry or exit is a hard authority boundary. A player-authored fixed block may meet that boundary but may not cross it or share any controlled track extent with the region. Fixed-block and protection-region definitions that overlap are invalid authoring state: placement/update/load validation rejects them and reports the conflicting stable IDs and boundary locations. The compiler never silently clips or splits either definition.

The protection region itself fills one segment position in the authority sequence, similar to an ordinary block. Its internal admission semantics are richer: the authoritative claim is the frozen entry-to-exit route window and its complete resource set, not necessarily one region-wide exclusive occupied bit. This preserves concurrent non-conflicting paths through a complex throat while keeping the protection region disjoint from every fixed block. Physical train intervals inside the region remain the occupancy source for conflict checks and tail-clear release; no overlapping fixed-block definition is required.

For one selected route, SimpleATO compiles the authority-segment sequence and direct upstream/downstream boundary links. A segment has a stable ID, kind (`FIXED_BLOCK` or `PROTECTED_ROUTE`), route distance, occupancy summary, host-computed movement availability, target-speed facts, and revision. A protected-route segment additionally exposes region/entry/exit IDs, allocation state, alignment, and exit-clearance state. Ordinary signal JavaScript receives its own traversal-scoped section plus the next boundary's committed effective entry-speed limit; it does not traverse the sequence or inspect raw downstream reservation state.

If no route/exit has been selected at a region entry, the protection-region segment is unavailable and the entry signal remains restrictive. Once the complete route is granted and aligned, the lookahead continues from the region's selected exit into the adjacent downstream fixed block. Granting the protected route atomically reserves or otherwise proves the downstream receiving clearance required for the complete train; this is a generic resource closure across adjacent, non-overlapping authority segments, not overlapping area ownership. Authority may end only at a complete section boundary after the train envelope fits inside acquired resources and every clearance obligation is satisfied. `safeHoldingInside` is therefore derived, never a persisted region flag.

Internal signals may indicate route/resource state, but they are not automatically additional authority stop points after entry. If operationally independent stopping or allocation is desired inside a large throat, the authored partition must create a real authority-section boundary there; the compiler still decides from the current route, train envelope and unresolved obligations whether that boundary can terminate the grant.

### Common authority-section contract

Basic stores fixed blocks and protection regions in one non-overlapping authority partition and exposes one platform-neutral `AuthoritySection` contract. The implementation differences stay behind this boundary:

- A fixed block resolves one ordinary directional traversal and claims its whole block resource.
- A protection region resolves one directional entry-to-selected-exit traversal and claims the frozen path's resource set, allowing another non-conflicting traversal in the same region.

The common runtime value is an immutable, traversal-scoped `AuthoritySectionSnapshot`, not a mutable implementation object and not one region-global occupied flag. The host/provider snapshot may contain detailed state for arbitration, diagnostics, and WebUI:

- Stable section ID, revision, directional entry boundary, selected exit boundary, and selected-route revision.
- Subject relationship (`NO_SUBJECT`, `OWN_GRANT`, `OTHER_GRANT`) for the current leading/candidate train context.
- Movement availability (`AVAILABLE`, `HELD_FOR_SUBJECT`, `DENIED`, `INVALID`, `STALE`) plus a stable denial-reason code.
- Occupancy relationship (`CLEAR`, `OCCUPIED_BY_SUBJECT`, `OCCUPIED_BY_OTHER`, `MIXED`, `UNKNOWN`) derived from physical train intervals.
- Reservation/allocation and tail-release phase, effective speed limit, route distance/length, and host-enforced target-speed facts.
- A route-relative forward observation of following section boundaries, distances, and committed outputs, ending at the signal's declared `scanDistanceMeters`.

`kind` and the detailed availability/reservation enums may be retained for diagnostics, WebUI, and specialized Java providers, but ordinary JavaScript signal logic must not need them. `canEnter()` is the deliberately collapsed safety result for the current traversal context: fixed-block occupancy/reservation and protection-region route/resource conflicts are already resolved by their providers. An empty protection region with no confirmed entry-to-exit grant therefore reports `canEnter() == false`; `hasTrain() == false` alone is never interpreted as permission.

The Basic side owns section definitions, route resolution, admission, atomic claims, release, and snapshots. The SimpleATO/JavaScript side receives only a bounded `SectionView` over compiled snapshot handles. A proposed ergonomic surface is:

```text
SectionView
  id(), revision(), entry(), exit()
  hasTrain()                    // conflicts with this traversal, not any train in the whole region
  lengthMeters()                // usable distance from this entry to its selected exit
  canEnter()                    // collapsed host-authoritative admission result
  denialReason()                // optional diagnostics

SignalView
  id(), direction(), parameter(name)
  protectedSection()
  forward()                     // immutable, distance-bounded route-relative observation
  approachTrain()
  dependency(alias)             // YAML-declared signal dependency only
  currentModelVariant(channel)
  setSpeedLimit(value)          // target speed at this signal boundary; zero means stop here
  setModelVariant(variant)
  setModelVariant(channel, variant)
  diagnostic(code, fields)

ForwardBoundaryView
  index(), distanceMeters()
  effectiveSpeedLimit()
  modelVariant(channel)         // candidate Stage 2 field; not required by Stage 1 refactor

ForwardObservation
  boundaries()                  // every boundary within this signal's declared scan distance
  scanDistanceMeters(), terminalReason()
```

The write-looking methods append to the current evaluation-local decision buffer. Reads use stable/interned handles from `SignalObservationPlan`; iterating `forward().boundaries()` walks one immutable snapshot array and never searches the live topology. Every returned view belongs to the same epoch/topology/operations revision as the root signal context.

This makes one ordinary policy work unchanged for both implementations:

```javascript
function update(signal) {
  const current = signal.protectedSection();
  if (current.hasTrain() || !current.canEnter()) {
    signal.setSpeedLimit(0.0);
    signal.setModelVariant("R");
    return;
  }

  const boundaries = signal.forward().boundaries();
  const next = boundaries[0];
  const second = boundaries[1]; // present automatically when it lies inside the horizon
  const maximum = brakingEntrySpeed(next.effectiveSpeedLimit(), current.lengthMeters());
  const indication = chooseDeclaredIndication(maximum, next, second);
  signal.setSpeedLimit(indication.speedMetersPerSecond);
  signal.setModelVariant(indication.modelVariant);
}
```

For a fixed block, `canEnter()` is derived from its single block claim. For a protection region, the same call is derived from the selected entry-to-exit grant and conflict set. `hasTrain()` is also traversal-scoped for a protection region, so a train on a non-conflicting path does not falsely occupy this view. The script does not branch on the section kind.

### Signal target speed and zero

The current ATO model deliberately distinguishes a current speed cap from a route-relative target. `CurrentSpeedLimitConstraint(0)` permits zero immediately wherever that constraint is active. `RouteSpeedLimitConstraint(distanceToStart, targetSpeed)` and `StopConstraint(remainingDistance)` instead permit a braking-curve speed while approaching and reach the target speed at the named boundary after the applicable margins.

Signal-package `signal.setSpeedLimit(value)` is defined as the target speed at that signal's route-relative location, never as a current speed cap at the observing train. The host materializes it as a distance-aware route target for every relevant approaching train. Consequently `setSpeedLimit(0)` already means stop at the signal and `requireStopAtEntry()` is removed from the ordinary signal API. Lower-level current-speed and point-stop constraints remain distinct for departure holds, station berthing, manual intervention, and other non-signal uses.

### Distance-bounded forward observations

Each boundary signal publishes one committed target speed after physical, authority, system-safety, and policy constraints are merged. That scalar is sufficient for one continuous braking target, but it is not a lossless summary of a discrete multi-aspect rule: two downstream situations may give the immediate next signal the same `Y` indication/limit while requiring the current signal to display different indications. A policy must therefore be able to inspect later downstream boundaries when necessary.

Each signal model/package YAML declares a finite positive `scanDistanceMeters` beside the JavaScript policy that consumes it; it does not declare a signal count. A placed signal stores the stable model kind ID and cannot override this value independently. The host constructs the forward observation through every section boundary whose cumulative route distance lies within the model value. A line containing many short sections therefore yields more entries than a line with long sections, without a second record-count cap or special truncation state. JavaScript iterates one immutable array, while the host separately provides or validates the braking envelope. Missing, stale, failed, or not-yet-computed route state remains fail-safe restrictive, but reaching the declared scan distance is ordinary successful completion.

A protection region is not a traversal barrier merely because its implementation differs. When the evaluated signal/subject has a confirmed entry-to-exit traversal, that traversal contributes its usable route length and the observation continues from the selected exit. When the exit is unresolved or the route is denied, invalid, or stale, the region entrance becomes a terminal zero-speed boundary. Ordinary scripts never branch on `FIXED_BLOCK` versus `PROTECTION_REGION`.

A committed downstream limit/indication change dirties only signals whose cached distance-bounded observation includes that signal. A work queue propagates changes until published outputs no longer change; unchanged values stop propagation. This is event-driven rather than per-tick. Directed route-relative cycles are bounded by traveled distance and detected from the compiled boundary graph; a non-converging or over-budget strongly connected component falls back to zero/fail-safe state.

## Signal extension packages

Static signal geometry, materials, aspect mappings, sounds, and declared capabilities belong in strict YAML. JavaScript is an optional deterministic policy layer evaluated from immutable snapshots and allowed to emit only validated, restrictive decisions such as aspect choice, lower speed limits, and stop requests. It cannot own occupancy, resource grants, turnout mutation, or direct Bukkit rendering. Java providers implement the same versioned contract for integrations or policies that exceed the script budget. Script contexts are compiled per package/runtime, not per signal or per tick, and failures make the affected scope restrictive.

### Proposed JavaScript policy API v1

The JavaScript surface is snapshot-in/transactional-decision-out rather than a collection of live world query methods. A package registers a namespaced policy ID and an evaluation function. YAML binds one signal model to that policy, declares its allowed model variants and custom parameters, and explicitly names the protected section, resource indicators, true signal dependencies, and operations fields the policy observes. The host-built forward observation and these declarations create the dependency index used to invalidate cached results.

One immutable `SignalPolicyContextV1` contains only JSON-like values:

- Contract metadata: API version, physics epoch, topology revision, operations revision, and evaluation reason.
- Signal identity/configuration: stable signal ID, package kind ID, signal role, facing direction, declared parameters, allowed indication channels/values, and bound entry/region IDs.
- Protection/interlocking facts: region and entry state, selected exit, route-request/grant state, host-issued authority mode, route alignment, occupancy/release state, exit-clearance result, and a stable denial-reason enum. It does not expose a method that can grant or release resources.
- Declared resource facts: only resources named by the package/placed signal, including kind, stable ID, availability/ownership summary, and turnout selected-connection identity where relevant.
- Approaching-train facts: optional stable train ID, distance to entry, speed, physical length, direction, model/tags, and whether this train owns the current route grant. No live Train/Car references are exposed.
- Operations facts for that train: optional service-run, line, destination, current/next station, and schedule-state identifiers. The package must declare which of these it reads.
- Current-section facts: traversal-conflicting train presence, usable length, the collapsed host-authoritative `canEnter` result, an optional denial reason, and the host-enforced target-speed facts needed to validate the policy output.
- Forward-boundary facts: a route-relative array containing every boundary up to the signal's declared scan distance, with cumulative distance and committed effective speed limit. Stage 2 may add a same-package indication/model field without changing the Stage 1 observation/resource/restriction service boundary. Its cardinality follows section lengths rather than a declared count, and it has no separate record-count truncation. It exposes no downstream raw occupancy or reservation state and cannot initiate graph traversal.
- Declared dependent indications: resolved results of explicitly bound principal signals, only for roles whose semantics are genuinely dependent (for example distant, passing, and repeating signals). The loader validates this explicit indication-dependency graph and rejects cycles. Ordinary automatic-block main signals consume the distance-bounded route-relative forward observation; same-package committed indication/model state is a deferred Stage 2 contract detail.

During one callback, the runtime exposes command-style functions backed by a fresh `SignalPolicyDecisionBuilderV1`:

- `setModelVariant(variant)` selects the default/main YAML-declared model variant; `setModelVariant(channel, variant)` selects an independently declared channel such as main, route, speed, or auxiliary. These control presentation only and never grant movement.
- `setSpeedLimit(metersPerSecond)` adds or replaces this policy's target speed at its own signal boundary; the host constructs the distance-aware approach constraint, and zero is a stop at that boundary. Scripts cannot name arbitrary rail intervals or create current-speed constraints at runtime.
- `diagnostic(code, fields)` records a structured, rate-limited diagnostic; scripts do not receive a general logging or messaging handle.

These calls do not mutate the live world. The JavaScript prelude should implement the common setters against an evaluation-local JavaScript decision buffer, so several setters require only ordinary JavaScript property updates and the completed decision crosses the JavaScript/Java boundary once. If the callback completes, the builder freezes one `SignalPolicyDecisionV1`; the host validates the complete result and its epoch/topology/operations revisions, then atomically replaces the previous decision owned by that signal policy. If the callback fails or becomes stale, the partial builder is discarded and fail-safe state is retained. Java providers consume the same context and produce the same decision value, even if their source-level API uses a Kotlin/Java builder.

The host validates every decision. A script may make operation more restrictive, but `stopAtEntry = false`, a high speed value, or a permissive indication cannot override denied/stale resource state, physical speed limits, following protection, movement authority, or the host-issued authority mode. Invalid indications/scopes, non-finite values, exceptions, budget exhaustion, mixed revisions, and stale output select the package's declared fail-safe indications and retain/add the conservative stop restriction.

Evaluation is change-driven and cached per placed signal plus the currently granted/leading approach train. It runs when one of the YAML-declared dependencies changes, not every physics tick and not through a train-by-signal Cartesian scan. Package code is compiled once per package generation. The public package SDK includes JSON Schema, `signal-policy.d.ts`, examples, validation fixtures, and a Java provider interface consuming and producing the same two versioned value types.

### Runtime lookup and commit cost

Placing/rebinding a signal or changing relevant topology compiles a `SignalObservationPlan`: stable/interned IDs or integer handles for its protected section, route-relative forward links, bound resources, operations fields, and true dependent signals. A selected turnout/protection exit resolves a cached distance-bounded slice without a JavaScript topology query. Reverse indexes include section-to-observing-signals, downstream-signal-to-observing-signals, and resource-to-observing-signals. A route/turnout change invalidates only affected plans/slices against the new topology revision.

Runtime evaluation does not search the graph. It receives the current-section snapshot and one already-built forward array. Work is `O(number of boundaries inside this signal's declared scan distance)` over small immutable records; long sections produce few records and consecutive short sections produce more. The configured distance provides the topology-traversal bound, and the ordinary sandbox callback budget remains the only script-execution bound; there is no extra short-section record cap.

When one section changes occupancy/admission or one boundary publishes a different committed output, reverse indexes identify the exact local policies or upstream observation windows to dirty. Duplicate work is coalesced within an epoch and unchanged output stops propagation. Work is proportional to the actually affected declared-distance windows/SCC, not to `train count × signal count`; route-relative loops are still bounded by cumulative scan distance and non-converging policy cycles fail safe.

Signal JavaScript never runs on a physics worker or a Bukkit/Folia region thread. A restrictive host state change (lost authority, new occupancy, stale topology) takes effect immediately without waiting for JavaScript. A more permissive visual/policy result is accepted only after its callback succeeds and its revisions are still current. Committing one decision diffs the previous decision: constraints are replaced under stable owner keys, and Bukkit rendering is scheduled only when a model-variant channel actually changes.

Do not copy the current per-train `behavior.js` lifecycle by allocating one Graal context per placed signal. Signal packages compile once per package generation and execute through a bounded context pool or equivalent serialized package runtime. Performance tests must count script invocations and prove zero evaluation for unchanged dependencies, bounded dirty fan-out for block transitions, no physics-thread waiting, and no render operation for an unchanged model variant.

The first version intentionally exposes no filesystem, network, clock, randomness, threads, Java class lookup, Bukkit API, live world query, topology mutation, turnout command, allocation command, persistence write, or arbitrary constraint-construction API.

### Validation against Japanese signalling patterns

The bounded policy mechanism can model the common Japanese wayside patterns after the additions above:

- Two- through multi-indication main signals map naturally to a `main` indication channel. Operator/line-specific fixed speeds live in YAML configuration, while the script selects an indication from host-computed block/authority and target-speed facts.
- Automatic block, home, and starting signals read occupancy, route grant, turnout alignment, and exit clearance from SimpleATO. Their displayed indication never substitutes for interlocking authority.
- Distant, passing, and repeating signals use declared acyclic signal dependencies or the host target-speed lookahead; they do not scan the topology or recursively call another script.
- Route indicators use a separate `route` indication channel derived from the selected, confirmed exit. Speed and auxiliary indicators similarly use independent channels, avoiding a combinatorial aspect ID list.
- ATS pattern supervision and ATC cab speed control map to the host-enforced target-speed profile and existing ATO constraint pipeline. JavaScript may choose a visual/cab indication from that profile but cannot calculate the sole safety curve.

One important case is deliberately outside restrictive-only script authority: a guided/call-on or shunting signal can permit a movement that ordinary main authority would prohibit, usually under a lower speed and a separately protected route. That permission must be a typed, host-issued authority mode backed by interlocking/resource checks and an enforced speed profile. JavaScript can display the corresponding auxiliary/main indication only after receiving the confirmed mode. This preserves the safety boundary while allowing Japanese-style guided and shunting packages later.

For a conventional progression such as `G - YG - Y - R - occupied block - G`, the red signal sees its own protected section as unavailable and commits zero; upstream policies combine their section lengths with the bounded downstream outputs. For the short-block case `G - YG - Y - Y - R - occupied block - G`, S2 may inspect both S3 and S4: `Y,Y` can map to `YG`, while `Y,R` can remain `Y`, even if S3's own speed/indication is identical in both cases. These are direct reads of precompiled handles, not runtime topology searches. Propagation stops when upstream committed outputs are unchanged.

## WebUI packaging

`SimpleATOWebUI` is a separate build module to isolate the frontend dependency graph, generated assets, and UI tests from Kotlin/Bukkit compilation. A production build produces versioned static assets that SimpleATOBukkit embeds and serves alongside its WebUI API.

This is a source/build boundary, not a deployment boundary. SimpleATOBukkit owns authentication/authorization, API compatibility, asset serving, startup/shutdown, and the version match between backend and bundled frontend. The initial release does not require administrators to install or operate a separate web service.

## Data flow

1. JavaRailways publishes an immutable world observation for a completed physics epoch and aggregates occupancy once.
2. Signal updates only affected regions/resources and evaluates entry-to-exit requests from stable rail references.
3. Signal submits one atomic claim set containing the frozen route's turnout selections, diamond/conflict nodes, rail resources, and exit-clearance envelope.
4. The host validates topology revision, bounded-region identity, occupancy, conflicting claims, required turnout selections, and exit clearance.
5. A confirmed allocation permits Signal to publish an authority/constraint for the affected train.
6. ATO/ATP enforces the most restrictive active constraint.
7. Signal derives aspects and schedules Bukkit rendering only when an aspect changes, on the correct location owner.
8. Tail-clear observations release resource claims; stale revisions and uncertain state fail closed.

Pure indication signals observe route/resource state but do not create extra authority boundaries. A configured protection signal is a region gate and therefore the sole stop boundary for its window; internal turnouts do not add competing stops.

## Persistence and compatibility

Signal stores its operational and presentation data in a separate versioned world sidecar using stable namespaced IDs, rail UUIDs, canonical AB arc lengths, and directions. Fixed-block and protection-region definitions remain in the Basic infrastructure persistence boundary and are referenced by stable IDs; they are not duplicated into the Signal sidecar. Compatible fields with defaults do not require a version bump. Breaking field, type, semantic, or structure changes upgrade only the affected independent persistence boundary and are handled by an auditable one-way migration.

## Rollout and rollback

- The extraction uses an auditable one-time migration with a recovery point for Basic/BukkitPlugin operational data that moves to SimpleATO/SimpleATOBukkit; no permanent runtime compatibility path is added for development-era formats.
- SimpleATO must be loadable and testable without Bukkit. BukkitPlugin must remain loadable without SimpleATOBukkit.
- Disabling SimpleATO/SimpleATOBukkit must not silently widen an active train authority. The exact stop/hold and administrative recovery behavior is part of the revised Stage 1 contract.
