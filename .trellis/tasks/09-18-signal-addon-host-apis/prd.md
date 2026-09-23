# Stage 1: Signal add-on host APIs

## Goal

Provide a small, signalling-system-neutral host surface so independently compiled Java/Kotlin signal plugins can inspect trains, request resources and submit stricter train constraints without moving their protection-region, fixed-block or interlocking policy into Basic.

## Confirmed facts

- Core already publishes train head/tail/speed/occupied intervals and now has one immutable revisioned `TopologyState` with normalized edges and structural turnout/terminus/loop nodes.
- Basic already contains reusable pieces for route projection, stable turnout identity, route-bound resources, atomic claims, occupancy queries, infrastructure preparation, rolling extension and tail-cursor release.
- The current Basic protection-region catalog/window/compiler is transitional. It was implemented under an earlier ownership decision and must not become the universal signal-system model.
- A diamond crossing changes resource conflict but not route connectivity, so it remains a route-bound shared physical resource rather than a topology node.
- SimpleATO already has the target module for operating-route selection and the shared ATO/ATP constraint pipeline, although most production callers remain in Basic during migration.
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
- Expose generic resource registration/admission, pending-attempt progress, train restriction and infrastructure lease/edit-guard services through documented public packages and Bukkit discovery. Do not add a separate grant-by-ID lookup without a concrete caller.
- Let each signal-system plugin own its fixed blocks, protection regions, interlocking tables, signal aspects, authoring, persistence and optional JS/YAML layer. Basic has no `PROTECTION_REQUIRED` policy and does not execute a recursive protection-provider closure.
- Keep SimpleATO responsible for operating-route choice, inspection scheduling and most-restrictive ATO/ATP arbitration. A signal plugin may only tighten Basic/SimpleATO authority or speed, never widen it.
- Keep resource inspection distinct from per-tick braking. The exact cache keys, dependency ownership and `update(train)` scheduling are deferred for review immediately before implementing the reactive batch; avoid unchanged per-tick plugin callbacks and duplicate forward scans.
- Compute world occupancy once per published epoch using rail-indexed intervals and reuse one route perception pass per controlled train; do not add `allTrains × allResources` scans.
- Preserve timetable compile/apply requirements: validate statically reachable routes and required signalling configuration before apply, atomically acquire shared infrastructure leases for the applied revision, and block covered edits until safe unapply/replacement.
- Separate explicit manual turnout selection from automatic allocation. Manual selection checks physical occupancy and active claims, grants no movement authority and cannot bypass a live route lock.
- Follow the project persistence policy: no new runtime compatibility path or schema boundary without separate review.

## Acceptance criteria

- [ ] A compile-only external plugin consumes every required contract using public packages only and can define a custom resource without importing Basic internals.
- [ ] Disconnected, illegal or stale frozen routes are rejected without path substitution or partial mutation.
- [ ] Omitting every plugin addition still claims every traversed rail, turnout and matched diamond resource; a plugin cannot remove or replace these identities.
- [ ] Valid plugin additions enter the same atomic footprint; invalid, unauthorized, occupied or conflicting additions leave the registry unchanged.
- [ ] Zero-, one- and multi-turnout routes prepare and release without `single()` assumptions.
- [ ] A grant is not published before all claimed resource preparations are `READY`; unsafe/unprovable preparation retains the safest claim and stalls the world.
- [ ] The existing cursor/release queue and suffix extension operate on the same frozen route and never perform whole-claim replacement.
- [ ] A concrete signal plugin can implement protection regions or route-table interlocking without adding a Basic/SimpleATO resource-type branch.
- [ ] Diamonds share one conflict identity across both same-line traversals, never create cross-line continuation and require no per-tick geometry scan.
- [ ] Signal restrictions are namespaced, revisioned and most-restrictive; stale or missing plugin state never silently expands authority.
- [ ] Resource inspection uses cached declarations plus host braking math; unchanged inputs do not repeatedly run plugin code.
- [ ] Applied timetable leases overlap safely between readers, coexist with runtime claims, block every covered editor and change atomically.
- [ ] Service registration, plugin disable, world unload/reload and train removal have focused fail-closed lifecycle tests.
- [ ] Existing Core, Basic, BukkitPlugin, SimpleATO, timetable and ATO tests pass.
- [ ] Public documentation states identities, units, direction basis, snapshot/live semantics, threading, lifecycle and typed failure behavior.

## Out of scope

- A host-owned national signalling rule set or universal protection-region/fixed-block model.
- Signal aspects/models, region/block authoring, concrete interlocking algorithms and signal-plugin persistence.
- Choosing the first concrete signal plugin's final product name, its JS/YAML API or WebUI packaging during this Stage 1 batch.
- Certification-grade communication, redundancy or train-integrity proving.
- Permanent runtime compatibility for pre-release development data.

## Dependency and handoff

- Stage 1 has no dependency on a concrete signal plugin.
- Completion must provide a consumer example and contract notes for the Stage 2 signal-plugin task.
- The first Stage 2 plugin may reuse the migrated protection-region implementation, but that code and persistence boundary must belong to that plugin, not Basic.

## Absence behavior

- Without SimpleATO, Basic grants no automatic movement authority; guarded manual turnout operation may remain available.
- Without a signal plugin, SimpleATO may still provide route following/moving-block behavior using Basic's mandatory physical claims and its own following/braking constraints.
- If an installed signal plugin considers its configuration incomplete, it withholds its permissive request and publishes a restrictive result. Basic does not invent a protection region or a replacement stop rule.

## Blocking open questions

None for Stage 1. Exact public Java names, first signal-plugin naming, JS/YAML capabilities and WebUI ownership are deliberately deferred to their own review batches.
