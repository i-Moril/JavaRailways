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
- [x] Freeze generic route-resource closure, no-hold-and-wait admission, ordered infrastructure preparation and fail-closed lifecycle pseudocode.
- [x] Freeze tail-clear, compiled-plan prefix-release and provider-disable recovery behavior.
- [x] Freeze same-frozen-route rolling suffix extension: SimpleATO selects the farther boundary;
  Basic validates only the suffix, atomically appends delta resources, prepares only delta
  requirements, keeps prefix release active while preparation is pending and expands authority only
  after the delta is committed.

## Next reviewable batches

- [x] Establish the real `SimpleATO` Gradle module and migrate the dependency-safe manual-navigation/timetable-dump owner slice, its tests and Bukkit composition imports without a compatibility wrapper. Controller, Dispatcher, operational models and persistence remain transitional in Basic.
- [x] Region catalog and graph resolver: runtime provider registration, authoritative definition validation, unique interior derivation and overlap diagnostics. No turnout mutation.
- [x] Versioned topology contract and migration seam: normalized nodes/edges, rail projection, immutable `CoreWorld` publication, public read API and the current `RailView` capture adapter are implemented. Raw adjacency encapsulation and SimpleATO policy migration remain.
- [x] Overlay/region migration: protection membership/window validation now derives from one retained public `TopologyState` root per catalog rebuild; topology and region revisions fail stale, and the live copied-adjacency source has been removed.
- [ ] SimpleATO frozen route: move operating route selection onto topology plus overlays and emit one ordered traversal bound to that topology revision. No caller-supplied resource list and no Basic claim mutation.
  - [x] `RouteSnapshot` now carries the producer-captured topology revision independently from its route revision; `ProtectedRouteRequest` and the projector consume that single binding without a duplicate expected-revision field. Full topology/overlay route selection remains.
  - [x] Add a SimpleATO-internal deterministic shortest-route selector over one immutable `TopologyState`: query-local edge splitting, actual-metre weights, legal turnout/loop continuations, revision-bound operating-direction filtering and AB/BA physical rail projection now emit the existing `RouteSnapshot`. Production Dispatcher/caller migration and additional line/vehicle/timetable costs remain.
- [ ] Route validation and claim assembly: coherent observation stamp, authoritative train envelope, exact traversal validation, one canonical resource set, generic contributor closure, safe authority-boundary selection and turnout selections. Occupancy geometry is validation evidence, not a second footprint. No registry mutation and no Basic path search.
  - [x] Basic internal route projection validates one frozen `RouteSnapshot` against `TopologyState` in one linear pass, emits immutable edge occurrences and derives turnout node/from/to traversals; protection-window validation reuses this projection. Public request shape, route-bound diamond index and closure compilation remain.
  - [x] Turnout traversals derive one direction-independent `javarailways:turnout` resource identity from the sorted persistent endpoint set; revision-local topology node IDs remain validation-only. Route validation and diamond identities remain.
  - [x] Route validation clips canonical rail intervals from entry through exit clearance, adds protected turnout resources to the one final set, and rejects uncovered turnouts before registry mutation. Projection is temporary validation state; diamond/occupancy/selection/contributor closure remains, so automatic callers are not migrated yet.
  - [x] Freeze the Basic-owned route-bound resource catalog contract: complete snapshots bind one exact topology root, rail-indexed anchors map either traversal side to one stable resource, and stale/unavailable indexes fail closed. Runtime catalog and diamond detection remain.
  - [x] Implement the Basic route-bound resource catalog runtime: atomically publish complete/complete-empty snapshots for one exact topology root, index canonical rail anchors, merge adjacent hits, reject leave-and-reenter routes, and add resolved resources to the validator's single footprint. Diamond placement detection remains.
  - [x] Preserve validation output as one immutable internal `CompiledRouteAdmission` containing the unique footprint plus route-ordered, direction-specific turnout selections derived from the same projected traversals. This is a pre-claim execution plan; the claim service submits only its footprint, while the selections become one ordered infrastructure-preparation batch after claim acquisition.
  - [x] Attach one normalized physical occupancy query geometry to the compiled admission: selected route intervals, every relevant turnout port as a zero-length topology-derived endpoint, and every anchor of a matched route-bound resource. This is validation evidence rather than a second footprint; no legacy fixed locking distance is restored.
- [x] Admission transaction: occupancy validation, one atomic claim, complete immediate-turnout prevalidation/application through generic preparation, and typed results. No Dispatcher migration.
  - [x] Publish Core's once-per-epoch swept-occupancy index through the immutable world frame and retain that exact snapshot in Basic observation; arbitrary interval queries return deterministic complete blocker IDs without rebuilding a second Basic index.
  - [x] Bind each transitional protected-route request to one exact observation stamp, reject stale/missing train state, and query the compiled occupancy geometry before atomic registry mutation; blockers return deterministic `PHYSICALLY_OCCUPIED` train IDs.
  - [x] After a successful claim, resolve every stable turnout port to both live directional views and validate the complete immediate batch before applying its selections in frozen-route order. Protected-route acquire rollback uses an opaque registry token only when a resource-owned preparation ticket later reports `ABORTED_SAFE`; `UNSAFE` or an unprovable abort retains the safest claim and returns `WORLD_STALLED` through the injected Stall owner. The current immediate turnout handler has no fictional selection-failure compensation, and live claims expose no whole-footprint replacement path.
  - [x] Generalize post-claim physical work to one internal ordered infrastructure-preparation batch: pending admission retains its claim mutation until `READY` or resource-owned `ABORTED_SAFE`; abort does not prescribe restoration or a default state, while actuator failure and `UNSAFE` retain the safest claim reservation and Stall. Turnout selection is the first immediate requirement: the live `RailView` handler resolves and validates every bidirectional continuation before applying the complete batch, then reports `READY` without a fictional runtime failure/selection-rollback path. Duration, timeout, public handler registration and persistence remain outside this batch.
- [ ] Release/recovery: tail-clear queue, atomic footprint shrink, provider-disable and reload fail-closed behavior.
  - [x] Compile one immutable tail-clear queue entry per claimed resource in the same validation
    pass and add a pure head-prefix inspector. It never pops before a future registry shrink;
    explicit clearance-margin source, grant integration, provider-disable and reload recovery remain.
  - [x] Add an internal synchronous removal-only registry shrink that atomically removes exact
    resource identities while preserving the remaining claim order and logical owner revision.
  - [x] Connect queue prefix inspection and exact registry shrink through one internal live-claim
    lifecycle owner. It retains exact ownership, immutable suffix and monotonic accepted tail
    progress; local state advances only after the expected atomic shrink result, while invalid or
    unexpected results latch fail closed. Route-position projection, Stall wiring, grant
    persistence, provider-disable and reload recovery remain.
  - [x] Compile one immutable ordered progress path from the already validated frozen route and
    retain it in `CompiledRouteAdmission`. Every initial admission route must start at the
    authoritative same-epoch train tail; Basic verifies the first occurrence and initializes the
    cursor at zero before claim mutation. The request carries neither progress nor occurrence index.
  - [x] Retain the host-initialized cursor in an internal tail-release controller after immediate or
    pending acquire success. It creates no second claim or footprint. Explicit coherent
    observations project the named train tail and drive atomic prefix release; wrong stamp/epoch,
    missing train, cursor failure or shrink failure latch fail closed and Stall exactly once.
    Automatic observation subscription, persistence/reload recovery, provider-disable takeover and
    clearance margin remain.
  - [x] Make the world-local claim service own controllers by logical owner after acquire READY/finalize.
    One explicit observation dispatch builds one train lookup, drives owners in stable order, removes
    only explicitly ended live grants and stops after the first fail-closed Stall. Resource queue
    completion retains the same frozen-route cursor and continues coherent progress projection without
    registry work. Exact platform/logical owner authentication is therefore still available after the
    registry footprint becomes empty. Whole-claim replacement is not part of this lifecycle.
  - [ ] Implement same-route suffix compilation and append-only registry mutation from the frozen
    contract. Reserve and prepare only delta resources, keep this owner's prefix release active,
    append naturally ordered entries to the existing controller after success, and retain the
    current authority on every non-success result.
    - [x] Add the internal registry delta reservation: validate only a nonempty unique suffix delta,
      reserve it on the exact existing platform/logical owner without changing owner revision,
      reject conflicts/already-held identities/another pending append atomically, allow established
      prefix shrink while pending, forbid delta shrink, and finalize or roll back only that delta
      against the claim's then-current remaining prefix. Suffix compilation, preparation and service
      integration remain unimplemented.
    - [x] Separate the shared live-grant lifecycle from committed-resource tail release. The service
      retains exact platform/logical owner identity plus one frozen-route cursor after the current
      resource queue empties, continues projecting later coherent observations without registry work,
      and removes the record only on authenticated explicit end. Pending delta belongs to the future
      resource-acquisition/live-grant owner and is not exposed to `LiveClaimTailRelease`.
    - [x] Add atomic installation of already-finalized and physically-ready committed suffix release
      entries on the existing controller. It validates immutable queue order and accepted tail progress,
      appends to an active queue or revives an empty queue without replacing the cursor. Suffix
      compilation, preparation, service integration, and verifiable coordination between a pending
      delta and release of the last old resource remain unimplemented.
- [ ] Caller migration: remove raw automatic allocation path and move automatic/`MANUAL_ROUTED` Dispatcher flows behind the service; retain `FULL_MANUAL` semantics.
  - [x] Align the transitional `RouteSectionAllocator` with the immediate in-memory turnout seam: resolve connections, conflicts and physical locks for the complete batch before the first selection write, then apply directly without capturing previous selections or claiming fictional apply-failure rollback. Dispatcher migration remains pending.

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
  - [x] Add internal generic tail-clear queue metadata and immutable eligible-prefix inspection
    without per-resource occupancy state or live-claim mutation.
  - [x] Add exact-identity atomic live-claim shrink without topology revalidation, conflict lookup,
    preparation changes, or public API changes.
  - [x] Add the internal tail-release lifecycle owner that performs shrink-before-pop, preserves
    overlapping identities independently, rejects decreasing progress, and completes idempotently.
  - [x] Attach a once-compiled ordered progress path to each validated admission. Its
    host-initialized cursor performs canonical AB/BA conversion, stays on the earlier matching
    tolerance boundary, advances only to the earliest forward match, never scans backward, and
    latches an unmatched suffix fail closed. Basic verifies the frozen route starts at the same
    observation's physical train tail before claim mutation and initializes progress at zero.
  - [x] Carry that cursor through admission preparation and expose an internal tail-release
    controller only on successful acquire completion. The controller creates no second
    claim or footprint; it consumes explicitly supplied coherent observations, invokes release on
    the existing registry claim, makes repeated epochs/completion idempotent and translates all
    observation/projection/release invariant failures into one latched Stall callback.
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
