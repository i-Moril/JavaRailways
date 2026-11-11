# Stage 1: Signal add-on host APIs

## Goal

Provide a small, signalling-system-neutral host surface so independently compiled Java/Kotlin signal plugins can inspect trains, request resources and submit stricter train constraints without moving their protection-region, fixed-block or interlocking policy into Basic.

## Confirmed facts

- Core already publishes train head/tail/speed/occupied intervals and now has one immutable revisioned `TopologyState` with normalized edges and structural turnout/terminus/loop nodes.
- Basic already contains reusable pieces for route projection, stable turnout identity, route-bound resources, atomic claims, occupancy queries, infrastructure preparation, rolling extension and tail-cursor release.
- The current Basic protection-region catalog/window/compiler is transitional. It was implemented under an earlier ownership decision and must not become the universal signal-system model.
- A diamond crossing changes resource conflict but not route connectivity, so it remains a route-bound shared physical resource rather than a topology node.
- SimpleATO already owns the production Dispatcher, concrete controller, operating-route admission client and shared ATO/ATP constraint pipeline; operating objects and several shared value/safety primitives remain in Basic during migration.
- Existing Bukkit add-ons discover `bukkit.api.*` services through `ServicesManager` and compile against the host without shadowing its classes.

## Requirements

- Publish immutable, coherent observation DTOs containing world identity, physics epoch, topology revision, train identity, speed, facing and occupied intervals; never expose mutable Core objects across threads.
- Keep one authoritative `TopologyState` for routing and validation. Ordinary degree-2 chains are edges; turnout, terminus and deterministic loop-anchor are structural nodes; diamond/shared crossings are route-bound resources.
- Replace timetable-private resource ownership with one world-local Basic resource kernel supporting stable rail intervals, turnouts, diamonds and namespaced plugin resources.
- Require every automatic or `MANUAL_ROUTED` request to carry one topology-bound frozen route, train ID, coherent observation stamp and desired authority boundary. Basic validates this route but never chooses an alternative.
- In one route-order pass, Basic must derive a non-removable physical resource floor for the requested range: traversed rail intervals, canonical turnout resources/selections and matched diamond/shared physical conflicts.
- Permit a signal plugin to add registered resources, preparation needs and stricter hold boundaries. Additions can only enlarge the final footprint or hold duration; they cannot replace or remove Basic's physical floor.
- Validate the union and authoritative occupancy before one atomic claim. A rejected request changes no claim, selection or authority.
- Keep claim, device preparation and grant distinct: claimed movable resources do not widen authority until every required handler reports `READY`.
- Generalize tail release and same-route suffix extension for zero, one and multiple turnouts. Release only an atomically shrunk contiguous queue prefix after the authoritative train tail clears each compiled boundary.
- Expose generic resource registration/admission, pending-attempt progress, train restriction and applied-plan dependency/edit-guard services through documented public packages and Bukkit discovery. Do not add a separate grant-by-ID lookup without a concrete caller.
- Let each signal-system plugin own its fixed blocks, protection regions, interlocking tables, signal aspects, authoring, persistence and optional JS/YAML layer. Basic has no `PROTECTION_REQUIRED` policy and does not execute a recursive protection-provider closure.
- Keep SimpleATO responsible for operating-route choice, inspection scheduling and most-restrictive ATO/ATP arbitration. A signal plugin may only tighten Basic/SimpleATO authority or speed, never widen it.
- Keep resource inspection distinct from per-tick braking. Follow the reviewed SimpleATO owner contract: cache complete low-frequency declaration bindings, keep dynamic braking host-owned, leave downstream dependency ownership with the concrete signal plugin, and avoid unchanged per-tick plugin callbacks or duplicate forward scans.
- Compute world occupancy once per published epoch using rail-indexed intervals and reuse one route perception pass per controlled train; do not add `allTrains × allResources` scans.
- Run each railway epoch as one host-thread-independent safety pipeline: physics publication, ordered boundary occupancy changes, signal/interlocking recomputation and next-epoch restriction publication must finish before another physics epoch begins. Bukkit rendering must not own or block this pipeline.
- Represent monitored-area crossings only as ordered `ENTER` and `LEFT` changes carrying stable area and boundary identities. Core must retain enough ordered head/tail traversal evidence to report a complete same-epoch enter-then-leave sequence; a synthetic `PASSED_THROUGH` change is not part of the contract.
- Keep presentation out of the Stage 1 host runtime. The separately packaged Stage 2 `SimpleATOBukkit` adapter will consume immutable revisioned presentation state and may coalesce pending updates per object to the newest revision; presentation must never delay or asynchronously replay safety changes.
- Preserve timetable compile/apply requirements: validate statically reachable routes and required signalling configuration before apply, atomically publish non-exclusive rail/node/resource dependencies for the applied revision, and block only intersecting edits until safe unapply/replacement. These dependencies never arbitrate train resources.
- Separate explicit manual turnout selection from automatic allocation. Manual selection checks physical occupancy and active claims, grants no movement authority and cannot bypass a live route lock.
- Follow the project persistence policy: no new runtime compatibility path or schema boundary without separate review.

## Acceptance criteria

- [x] A compile-only external plugin consumes every required contract using public packages only and can define a custom resource without importing Basic internals.
- [x] Disconnected, illegal or stale frozen routes are rejected without path substitution or partial mutation.
- [x] Omitting every plugin addition still claims every traversed rail, turnout and matched diamond resource; a plugin cannot remove or replace these identities.
- [x] Valid plugin additions enter the same atomic footprint; invalid, unauthorized, occupied or conflicting additions leave the registry unchanged.
- [x] Zero-, one- and multi-turnout routes prepare and release without `single()` assumptions.
- [x] A grant is not published before all claimed resource preparations are `READY`; unsafe/unprovable preparation retains the safest claim and stalls the world.
- [x] The existing cursor/release queue and suffix extension operate on the same frozen route and never perform whole-claim replacement.
- [x] A concrete signal plugin can implement protection regions or route-table interlocking without adding a Basic/SimpleATO resource-type branch.
- [x] Diamonds share one conflict identity across both same-line traversals, never create cross-line continuation and require no per-tick geometry scan.
- [x] Signal restrictions are namespaced, revisioned and most-restrictive; stale or missing plugin state never silently expands authority.
- [x] Resource inspection uses cached declarations plus host braking math; unchanged inputs do not repeatedly run plugin code.
- [x] A train that enters and leaves a short monitored area within one physics epoch produces ordered boundary-bearing `ENTER` then `LEFT` changes, and the signal runtime consumes both before the next physics epoch.
- [x] Railway physics and logical signalling continue without waiting for the Minecraft main thread; Stage 1 exposes no temporary host-owned signal renderer or presentation queue.
- [x] Applied timetable dependencies overlap safely, remain separate from runtime claims, block every covered Stage 1 editor by rail/node/resource intersection and change atomically.
- [x] Service registration, plugin disable, world unload/reload and train removal have focused fail-closed lifecycle tests.
- [x] Existing Core, Basic, BukkitPlugin, SimpleATO, timetable and ATO tests pass.
- [x] Public documentation states identities, units, direction basis, snapshot/live semantics, threading, lifecycle and typed failure behavior.

Evidence for these criteria is recorded by completed implementation item and test class in `implement.md`.
Concrete protection-region/signal authoring, its edit entrypoints and presentation remain Stage 2 work;
the Stage 1 criterion is the absence of a host resource-type branch and the independently compiled
consumer's ability to register resources, preparation, epoch and inspection policy through public APIs.

## Out of scope

- A host-owned national signalling rule set or universal protection-region/fixed-block model.
- Signal aspects/models, region/block authoring, concrete interlocking algorithms and signal-plugin persistence.
- Signal presentation DTOs, latest-only Bukkit/Folia dispatch and model application; these belong to the Stage 2 `SimpleATOBukkit` adapter and concrete signal plugin.
- Choosing the first concrete signal plugin's final product name, its JS/YAML API or WebUI packaging during this Stage 1 batch.
- Certification-grade communication, redundancy or train-integrity proving.
- Permanent runtime compatibility for pre-release development data.

## Dependency and handoff

- Stage 1 has no dependency on a concrete signal plugin.
- Stage 1 leaves the existing Basic protection-region implementation transitional; moving its policy,
  authoring and persistence into `JapaneseSignals` is Stage 2 work. Stage 1 must not expose that
  transitional implementation as the generic host contract or add a runtime compatibility wrapper.
- Stage 1 deliberately does not add a presentation seam to `BukkitPlugin`. Stage 2 creates
  `SimpleATOBukkit` and owns immutable presentation publication, per-object latest-only dispatch and
  target-aware model application without making presentation part of the safety epoch.
- Completion must provide a consumer example and contract notes for the Stage 2 signal-plugin task.
- Stage 2 may reuse the transitional protection-region algorithms, but the resulting code and
  persistence boundary must belong to that plugin, not Basic.

## Absence behavior

- Without SimpleATO, Basic grants no automatic movement authority; guarded manual turnout operation may remain available.
- Without a signal plugin, SimpleATO may still provide route following/moving-block behavior using Basic's mandatory physical claims and its own following/braking constraints.
- If an installed signal plugin considers its configuration incomplete, it withholds its permissive request and publishes a restrictive result. Basic does not invent a protection region or a replacement stop rule.

## Deferred follow-ups (do not block Stage 1)

Existing development runtime snapshots will be preserved through the packaged world-pair migrator
planned by `09-24-explicit-rail-endpoint-connections`; normal runtime code gains no long-lived
compatibility reader. Exact public Java names, first signal-plugin naming, JS/YAML capabilities and
WebUI ownership remain deliberately deferred to their own review batches.
