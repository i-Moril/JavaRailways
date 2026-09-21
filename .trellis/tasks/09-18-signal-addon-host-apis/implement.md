# Stage 1 implementation plan

## Preparation

- [ ] Load Core, Basic, BukkitPlugin, shared cross-layer, persistence, and threading specs.
- [ ] Inventory current route-allocation, physics-publication, controller-constraint, service-registration, world-unload, and topology-edit callers.
- [ ] Inventory and remove runtime assumptions that derive one stop/release boundary per turnout, including `single()` turnout paths.
- [ ] Inventory every `RailView.selectNextRail` caller and route turnout mutation through either protected automatic allocation or the separately guarded manual operation.
- [ ] Write contract tests for an external compile-only consumer before moving implementations.

## Pre-implementation design gate

- [x] Require explicit user/design review before adding compatibility, automatic repair, legacy-data interpretation, or migration behavior; default to strict current-model validation.
- [x] Freeze protection-region graph-cut, direction-boundary and non-overlap contracts.
- [x] Freeze Basic ownership of fixed-block/protection-region definitions and their single non-overlapping `AuthoritySection` partition; SimpleATO owns only section consumption and signalling policy.
- [x] Freeze one authoritative immutable topology state, normalized node/edge model, overlay boundary and snapshot semantics.
- [x] Create the target SimpleATO owner spec for operational overlays, frozen-route selection, admission-client behavior and the single train-protection pipeline; keep Core and Basic mechanisms in their own specs.
- [x] Freeze SimpleATO topology-bound route versus Basic validation and single claim-resource-set responsibilities.
- [x] Document module working model plus topology normalization, graph-cut, single-pass frozen-route validation/resource collection and tail-release internal mechanisms.
- [x] Freeze coherent observation/region/topology revision and authoritative resource-validation contracts.
- [x] Freeze generic route-resource closure, no-hold-and-wait admission, turnout rollback and fail-closed lifecycle pseudocode.
- [x] Freeze tail-clear, compiled-plan prefix-release and provider-disable recovery behavior.

## Next reviewable batches

- [x] Region catalog and graph resolver: runtime provider registration, authoritative definition validation, unique interior derivation and overlap diagnostics. No turnout mutation.
- [x] Versioned topology contract and migration seam: normalized nodes/edges, rail projection, immutable `CoreWorld` publication, public read API and the current `RailView` capture adapter are implemented. Raw adjacency encapsulation and SimpleATO policy migration remain.
- [x] Overlay/region migration: protection membership/window validation now derives from one retained public `TopologyState` root per catalog rebuild; topology and region revisions fail stale, and the live copied-adjacency source has been removed.
- [ ] SimpleATO frozen route: move operating route selection onto topology plus overlays and emit one ordered traversal bound to that topology revision. No caller-supplied resource list and no Basic claim mutation.
  - [x] `RouteSnapshot` now carries the producer-captured topology revision independently from its route revision; `ProtectedRouteRequest` and the projector consume that single binding without a duplicate expected-revision field. Full topology/overlay route selection remains.
- [ ] Route validation and claim assembly: coherent observation stamp, authoritative train envelope, exact traversal validation, one canonical resource set, generic contributor closure, safe authority-boundary selection and turnout selections. Occupancy geometry is validation evidence, not a second footprint. No registry mutation and no Basic path search.
  - [x] Basic internal route projection validates one frozen `RouteSnapshot` against `TopologyState` in one linear pass, emits immutable edge occurrences and derives turnout node/from/to traversals; protection-window validation reuses this projection. Public request shape, route-bound diamond index and closure compilation remain.
  - [x] Turnout traversals derive one direction-independent `javarailways:turnout` resource identity from the sorted persistent endpoint set; revision-local topology node IDs remain validation-only. Route validation and diamond identities remain.
  - [x] Route validation clips canonical rail intervals from entry through exit clearance, adds protected turnout resources to the one final set, and rejects uncovered turnouts before registry mutation. Projection is temporary validation state; diamond/occupancy/selection/contributor closure remains, so automatic callers are not migrated yet.
  - [x] Freeze the Basic-owned route-bound resource catalog contract: complete snapshots bind one exact topology root, rail-indexed anchors map either traversal side to one stable resource, and stale/unavailable indexes fail closed. Runtime catalog and diamond detection remain.
  - [x] Implement the Basic route-bound resource catalog runtime: atomically publish complete/complete-empty snapshots for one exact topology root, index canonical rail anchors, merge adjacent hits, reject leave-and-reenter routes, and add resolved resources to the validator's single footprint. Diamond placement detection remains.
  - [x] Preserve validation output as one immutable internal `CompiledRouteAdmission` containing the unique footprint plus route-ordered, direction-specific turnout selections derived from the same projected traversals. This is a pre-claim execution plan; the claim service submits only its footprint, while turnout mutation and rollback remain in the admission-transaction batch.
  - [x] Attach one normalized physical occupancy query geometry to the compiled admission: selected route intervals, every relevant turnout port as a zero-length topology-derived endpoint, and every anchor of a matched route-bound resource. This is validation evidence rather than a second footprint; no legacy fixed locking distance is restored.
- [ ] Admission transaction: occupancy validation, one atomic claim, all-turnout apply/rollback and typed results. No Dispatcher migration.
  - [x] Publish Core's once-per-epoch swept-occupancy index through the immutable world frame and retain that exact snapshot in Basic observation; arbitrary interval queries return deterministic complete blocker IDs without rebuilding a second Basic index. Admission integration remains.
- [ ] Release/recovery: entry observation, tail-clear slices, atomic footprint shrink, provider-disable and reload fail-closed behavior.
- [ ] Caller migration: remove raw automatic allocation path and move automatic/`MANUAL_ROUTED` Dispatcher flows behind the service; retain `FULL_MANUAL` semantics.

## Implementation

- [x] Add immutable epoch/revision observation DTOs and coherent publication access.
- [x] Add atomic shared infrastructure leases plus one edit-permit/diagnostic service covering applied-plan leases and live route claims.
- [x] Add direction-aware boundary, bounded-region, frozen route-window and exit-clearance public contracts.
- [ ] Replace the transitional single-window seam with a generic `RouteResourceRequest`/contributor closure; add typed `PROTECTION_REQUIRED` and provide no raw planned-section automatic overload.
- [ ] Add Basic-owned fixed-block definitions and merge them with protection regions in one revisioned authority-partition catalog, with atomic overlap validation and common traversal-scoped snapshots.
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
- [ ] Authority-partition tests for fixed-block/protection-region adjacency, directional overlap rejection, atomic create/update/load failure, common snapshot semantics, and no duplicate SimpleATO section store.
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
