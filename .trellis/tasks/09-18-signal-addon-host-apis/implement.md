# Stage 1 implementation plan

## Preparation

- [ ] Load Core, Basic, BukkitPlugin, shared cross-layer, persistence, and threading specs.
- [ ] Inventory current route-allocation, physics-publication, controller-constraint, service-registration, world-unload, and topology-edit callers.
- [ ] Inventory and remove runtime assumptions that derive one stop/release boundary per turnout, including `single()` turnout paths.
- [ ] Inventory every `RailView.selectNextRail` caller and route turnout mutation through either protected automatic allocation or the separately guarded manual operation.
- [ ] Write contract tests for an external compile-only consumer before moving implementations.

## Pre-implementation design gate

- [x] Freeze protection-region graph-cut, direction-boundary and non-overlap contracts.
- [x] Freeze one authoritative immutable topology state, normalized node/edge model, overlay boundary and snapshot semantics.
- [x] Freeze SimpleATO path/resource intent versus Basic exact validation/physical footprint responsibilities.
- [x] Document module working model plus topology normalization, graph-cut, intent-validation and tail-release internal mechanisms.
- [x] Freeze coherent observation/region/topology revision and host-derived physical footprint contracts.
- [x] Freeze generic route-resource closure, no-hold-and-wait admission, turnout rollback and fail-closed lifecycle pseudocode.
- [x] Freeze tail-clear, compiled-plan prefix-release and provider-disable recovery behavior.

## Next reviewable batches

- [x] Region catalog and graph resolver: runtime provider registration, authoritative definition validation, unique interior derivation and overlap diagnostics. No turnout mutation.
- [ ] Versioned topology contract and migration seam: normalized nodes/edges and rail projection are implemented as a pure internal builder; immutable `CoreWorld` publication and the legacy adjacency adapter remain. No SimpleATO policy migration.
- [ ] Overlay/region migration: resolve protection membership from `TopologyState`, key caches by topology/region revision, then remove the duplicated authoritative adjacency assumption.
- [ ] SimpleATO route intent: move operating route selection onto topology plus overlays and emit complete ordered typed intent. No Basic claim mutation.
- [ ] Intent validator/plan compiler: coherent observation stamp, authoritative train envelope, exact traversal/resource coverage, generic contributor closure, safe authority-boundary selection, host-derived claim/physical-lock footprints and turnout selections. No registry mutation and no Basic path search.
- [ ] Admission transaction: occupancy validation, one atomic claim, all-turnout apply/rollback and typed results. No Dispatcher migration.
- [ ] Release/recovery: entry observation, tail-clear slices, atomic footprint shrink, provider-disable and reload fail-closed behavior.
- [ ] Caller migration: remove raw automatic allocation path and move automatic/`MANUAL_ROUTED` Dispatcher flows behind the service; retain `FULL_MANUAL` semantics.

## Implementation

- [x] Add immutable epoch/revision observation DTOs and coherent publication access.
- [x] Add atomic shared infrastructure leases plus one edit-permit/diagnostic service covering applied-plan leases and live route claims.
- [x] Add direction-aware boundary, bounded-region, frozen route-window and exit-clearance public contracts.
- [ ] Replace the transitional single-window seam with a generic `RouteResourceRequest`/contributor closure; add typed `PROTECTION_REQUIRED` and provide no raw planned-section automatic overload.
- [ ] Generalize route resources from turnout-owner keys to all-or-nothing rail, turnout, fixed-crossing, and clearance claims.
- [ ] Remove turnout-derived protection-point synthesis from route compilation and reject automatic mission activation when any controlled resource lacks complete explicit region coverage.
- [ ] Centralize turnout mutation and add the physical-lock/active-claim checked manual operation that produces no automatic authority.
- [ ] Replace turnout-derived release assumptions with region-entry/tail-exit and generic conflict-envelope release tracking.
- [ ] Add route-bound diamond conflict resources plus placement/update spatial-candidate, exact-geometry detection, and rail/edge interval reverse indexing; do not create topology nodes or cross-line continuations.
- [ ] Add namespaced revisioned train restriction contracts and enforcement adapter.
- [ ] Add Bukkit public service interfaces, internal implementations, lifecycle registration, and cleanup.
- [ ] Migrate `TimetableDispatcher` and `SimpleATO` integration to the shared services.
- [ ] Add topology edit guards for active external claims.
- [ ] Replace dispatcher-specific RailWand/auto-connect guards with the common edit guard and cover protection-region, signal, topology migration, and administrative mutation paths.
- [ ] Document a minimal external consumer and supported failure/lifecycle behavior.

## Validation

- [ ] Focused Core tests for immutable publication, epochs, occupancy, diamond resource binding/no-connectivity-change, detection/update, and controller enforcement.
- [ ] Focused Basic tests for no-partial atomic claims, conflicts, zero/one/multi-turnout throat paths, contributor closure across adjacent regions, cyclic/unfinishable closure, stale revisions, tail release, and timetable migration.
- [ ] Tests for uncovered/partially covered/already-aligned turnout denial, no mutation on `PROTECTION_REQUIRED`, revision-driven rather than per-tick retry, no-controlled-resource routes, and guarded manual selection.
- [ ] Tests for overlapping shared timetable leases, atomic lease acquisition/release, edit denial diagnostics, reload reconstruction, and no partial mutation across every supported infrastructure editor.
- [ ] Bukkit tests for service registration/lookup, plugin disable, world unload, and target-aware dispatch.
- [ ] Compile-only external consumer test imports public packages only.
- [ ] Run `./gradlew :Core:test :Basic:test :BukkitPlugin:test`.
- [ ] Run `./gradlew :BukkitPlugin:shadowJar` and inspect service/API packaging.
- [ ] Run `git diff --check`.

## Rollback points

- Keep resource model, restriction model, and Bukkit service exposure in reviewable commits.
- Do not remove the old timetable path until parity tests pass against the shared service.
- Do not declare the Stage 2 dependency ready until disable/failure semantics are tested.
