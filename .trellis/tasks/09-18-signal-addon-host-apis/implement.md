# Stage 1 implementation plan

## Preparation

- [x] Load Core, Basic, BukkitPlugin, shared cross-layer, persistence, and threading specs.
- [x] Inventory current route-allocation, physics-publication, controller-constraint, service-registration, world-unload, and topology-edit callers. See `research/production-caller-inventory.md`.
- [x] Inventory and remove runtime assumptions that derive one stop/release boundary per turnout, including `single()` turnout paths. Generic route admission now compiles zero/one/multiple turnouts from the frozen traversal, and the dead `RouteSection`/turnout-protection partition was deleted after Dispatcher migration.
  - [x] Inventory the three Dispatcher `turnoutRequirements.single()` authority/release paths; removal belongs to the atomic caller-migration batch, not a mechanical `first()`/loop rewrite.
- [x] Inventory every `RailView.selectNextRail` caller and route turnout mutation through either protected automatic allocation or the separately guarded manual operation. Basic production now has one bidirectional live-selection primitive: claimed automatic preparation and the temporary-claim manual service both use it; remaining direct calls are Core structural maintenance or test setup.
  - [x] Inventory the two production writers: protected `ImmediateTurnoutPreparationActuator` and transitional `RouteSectionAllocator`. Writer migration remains.
- [ ] Write contract tests for an external compile-only consumer before moving implementations.

## Pre-implementation design gate

- [x] Require explicit user/design review before adding compatibility, automatic repair, legacy-data interpretation, or migration behavior; default to strict current-model validation.
- [x] Freeze the original protection-region graph-cut, direction-boundary and non-overlap contracts. **Superseded as a Basic target; retained as migration evidence for the first signal plugin.**
- [x] Freeze the original Basic ownership of fixed-block/protection-region definitions. **Superseded: concrete signal plugins own these definitions and policy.**
- [x] Freeze one authoritative immutable topology state, normalized node/edge model, overlay boundary and snapshot semantics.
- [x] Create the target SimpleATO owner spec for operational overlays, frozen-route selection, admission-client behavior and the single train-protection pipeline; keep Core and Basic mechanisms in their own specs.
- [x] Freeze SimpleATO topology-bound route versus Basic validation and single claim-resource-set responsibilities.
- [x] Document module working model plus topology normalization, graph-cut, single-pass frozen-route validation/resource collection and tail-release internal mechanisms.
- [x] Freeze coherent observation/region/topology revision and authoritative resource-validation contracts.
- [x] Freeze the original generic route-resource contributor closure. **Superseded by mandatory physical footprint plus additive plugin resources; preparation and fail-closed lifecycle remain.**
- [x] Freeze tail-clear, compiled-plan prefix-release and provider-disable recovery behavior.
- [x] Freeze same-frozen-route rolling suffix extension: SimpleATO selects the farther boundary;
  Basic validates only the suffix, atomically appends delta resources, prepares only delta
  requirements, keeps prefix release active while preparation is pending and expands authority only
  after the delta is committed.
- [x] Freeze the revised ownership boundary: Basic derives non-removable rail/turnout/diamond resources; signal plugins may only add resources and stricter holds/restrictions; Basic owns no universal protection-region or fixed-block policy.

## Next reviewable batches

- [x] Establish the real `SimpleATO` Gradle module and migrate the dependency-safe manual-navigation/timetable-dump owner slice, its tests and Bukkit composition imports without a compatibility wrapper. Controller, Dispatcher, operational models and persistence remain transitional in Basic.
- [x] Region catalog and graph resolver: runtime provider registration, authoritative definition validation, unique interior derivation and overlap diagnostics. No turnout mutation.
- [x] Versioned topology contract and migration seam: normalized nodes/edges, rail projection, immutable `CoreWorld` publication, public read API and the current `RailView` capture adapter are implemented. Raw adjacency encapsulation and SimpleATO policy migration remain.
- [x] Overlay/region migration: protection membership/window validation now derives from one retained public `TopologyState` root per catalog rebuild; topology and region revisions fail stale, and the live copied-adjacency source has been removed.
- [ ] SimpleATO frozen route: move operating route selection onto topology plus overlays and emit one ordered traversal bound to that topology revision. No caller-supplied resource list and no Basic claim mutation.
  - [x] `RouteSnapshot` now carries the producer-captured topology revision independently from its route revision; `ProtectedRouteRequest` and the projector consume that single binding without a duplicate expected-revision field. Full topology/overlay route selection remains.
  - [x] Add a SimpleATO-internal deterministic shortest-route selector over one immutable `TopologyState`: query-local edge splitting, actual-metre weights, legal turnout/loop continuations, revision-bound operating-direction filtering and AB/BA physical rail projection now emit the existing `RouteSnapshot`. Production Dispatcher/caller migration and additional line/vehicle/timetable costs remain.
  - [x] Add the SimpleATO-internal observation seam around that selector: require one coherent topology,
    observation and operating-direction overlay, resolve the named authoritative train, derive the route
    start and facing from its published tail, and preserve typed stale/train/planner rejection. Production
    Dispatcher wiring, public API and resource admission remain separate.
  - [x] Build one train-ID index per immutable observation in `FrozenRouteSelectionContext`; do not
    retain a cross-observation cache. Core now publishes the actual ordered tail-to-head body placement,
    and the Dispatcher mission seam prepends it to the already-selected head-forward mission route.
    This removes the second Dijkstra and direction-metadata revision from runtime admission identity.
    Basic still validates the assembled traversal before any claim. Production admission wiring remains.
- [ ] Route validation and claim assembly: coherent observation stamp, authoritative train envelope, exact traversal validation, one mandatory physical footprint plus validated plugin contributions, and route-ordered turnout selections. Occupancy geometry is validation evidence, not a second footprint. No Basic path search or recursive protection-policy closure.
  - [x] Basic internal route projection validates one frozen `RouteSnapshot` against `TopologyState` in one linear pass, emits immutable edge occurrences and derives turnout node/from/to traversals; protection-window validation reuses this projection. Public request shape, route-bound diamond index and closure compilation remain.
  - [x] Turnout traversals derive one direction-independent `javarailways:turnout` resource identity from the sorted persistent endpoint set; revision-local topology node IDs remain validation-only. Route validation and diamond identities remain.
  - [x] Transitional route validation clips canonical rail intervals, adds turnout resources and rejects uncovered turnouts. The projection/identity work is retained; uncovered-turnout policy and contributor-closure target are superseded by the mandatory physical floor plus additive plugin resources. Automatic callers are not migrated yet.
  - [x] Freeze the Basic-owned route-bound resource catalog contract: complete snapshots bind one exact topology root, rail-indexed anchors map either traversal side to one stable resource, and stale/unavailable indexes fail closed. Runtime catalog and diamond detection remain.
  - [x] Implement the Basic route-bound resource catalog runtime: atomically publish complete/complete-empty snapshots for one exact topology root, index canonical rail anchors, merge adjacent hits, reject leave-and-reenter routes, and add resolved resources to the validator's single footprint. Diamond placement detection remains.
  - [x] Preserve validation output as one immutable internal `CompiledRouteAdmission` containing the unique footprint plus route-ordered, direction-specific turnout selections derived from the same projected traversals. This is a pre-claim execution plan; the claim service submits only its footprint, while the selections become one ordered infrastructure-preparation batch after claim acquisition.
  - [x] Attach one normalized physical occupancy query geometry to the compiled admission: selected route intervals, every relevant turnout port as a zero-length topology-derived endpoint, and every anchor of a matched route-bound resource. This is validation evidence rather than a second footprint; no legacy fixed locking distance is restored.
  - [x] Extract the mandatory physical-floor compiler from the protection-window validator. Given an already projected frozen route and an explicit route range, it now derives rail intervals, turnout and route-bound resources, ordered preparations, occupancy geometry, release metadata and the progress path without any protection-region dependency. The old validator remains a transitional policy adapter; public route intent and plugin additions remain separate batches.
- [x] Admission transaction: occupancy validation, one atomic claim, complete immediate-turnout prevalidation/application through generic preparation, and typed results. No Dispatcher migration.
  - [x] Publish Core's once-per-epoch swept-occupancy index through the immutable world frame and retain that exact snapshot in Basic observation; arbitrary interval queries return deterministic complete blocker IDs without rebuilding a second Basic index.
  - [x] Bind each transitional protected-route request to one exact observation stamp, reject stale/missing train state, and query the compiled occupancy geometry before atomic registry mutation; blockers return deterministic `PHYSICALLY_OCCUPIED` train IDs.
  - [x] After a successful claim, resolve every stable turnout port to both live directional views and validate the complete immediate batch before applying its selections in frozen-route order. Protected-route acquire rollback uses an opaque registry token only when a resource-owned preparation ticket later reports `ABORTED_SAFE`; `UNSAFE` or an unprovable abort retains the safest claim and returns `WORLD_STALLED` through the injected Stall owner. The current immediate turnout handler has no fictional selection-failure compensation, and live claims expose no whole-footprint replacement path.
  - [x] Generalize post-claim physical work to one internal ordered infrastructure-preparation batch: pending admission retains its claim mutation until `READY` or resource-owned `ABORTED_SAFE`; abort does not prescribe restoration or a default state, while actuator failure and `UNSAFE` retain the safest claim reservation and Stall. Turnout selection is the first immediate requirement: the live `RailView` handler resolves and validates every bidirectional continuation before applying the complete batch, then reports `READY` without a fictional runtime failure/selection-rollback path. Duration, timeout, public handler registration and persistence remain outside this batch.
  - [x] Verify the same admission path on one genuinely connected two-turnout route inside one protection region: validation emits both stable turnout resources in the canonical footprint, both route-ordered selections, all incident-port occupancy evidence and one ordered release queue. The immediate live actuator resolves the complete batch before its first write; an invalid second turnout leaves the first turnout unchanged, while success aligns both. No production change or single-turnout special case was required.
- [ ] Release/recovery: tail-clear queue, atomic footprint shrink, provider-disable and reload fail-closed behavior.
  - [x] Compile one immutable tail-clear queue entry per claimed resource in the same validation
    pass; the live release owner now scans the head prefix and advances it only after registry shrink.
    Explicit clearance-margin source, grant integration, provider-disable and reload recovery remain.
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
  - [x] Implement same-route suffix compilation and append-only registry mutation from the frozen
    contract. Reserve and prepare only delta resources, keep this owner's prefix release active,
    append naturally ordered entries to the existing controller after success, and retain the
    current authority on every non-success result.
    - [x] Add the internal registry delta reservation: validate only a nonempty unique suffix delta,
      reserve it on the exact existing platform/logical owner without changing owner revision,
      reject conflicts/already-held identities/another pending append atomically, allow established
      prefix shrink while pending, forbid delta shrink, and finalize or roll back only that delta
      against the claim's then-current remaining prefix.
    - [x] Separate the shared live-grant lifecycle from committed-resource tail release. The service
      retains exact platform/logical owner identity plus one frozen-route cursor after the current
      resource queue empties, continues projecting later coherent observations without registry work,
      and removes the record only on authenticated explicit end. Pending delta belongs to the future
      resource-acquisition/live-grant owner and is not exposed to `LiveClaimTailRelease`.
    - [x] Add atomic installation of already-finalized and physically-ready committed suffix release
      entries on the existing controller. It validates immutable queue order and accepted tail progress,
      appends to an active queue or revives an empty queue without replacing the cursor.
    - [x] Retain the initial validated route projection and authority frontier in the live grant;
      compile only the farther suffix, query only its occupancy geometry, reserve and prepare only
      its delta, then finalize and install entries on the same controller. Prefix release remains
      active while pending, including a registry proof when only pending delta remains. Public
      SimpleATO request wiring and host-collected plugin-resource contributions remain separate later batches.
- [ ] Caller migration: remove raw automatic allocation path and move automatic/`MANUAL_ROUTED` Dispatcher flows behind the service; retain `FULL_MANUAL` semantics.
  - [x] Align the transitional `RouteSectionAllocator` with the immediate in-memory turnout seam: resolve connections, conflicts and physical locks for the complete batch before the first selection write, then apply directly without capturing previous selections or claiming fictional apply-failure rollback. Dispatcher migration remains pending.

## Implementation

- [x] Add immutable epoch/revision observation DTOs and coherent publication access.
- [x] Replace the rejected applied-plan lease model with explicit non-exclusive dependency declarations: persistent rail IDs,
  stable endpoint-based topology-node keys and runtime resource IDs remain separate from live claim footprints. Edit permits use
  the same three categories and return field-wise intersections; claims retain their existing operational arbitration. Remove the
  old lease API without compatibility wrappers and rename the host service/registry accordingly.
- [x] Add direction-aware boundary, bounded-region, frozen route-window and exit-clearance public contracts.
- [ ] Replace the transitional single-window seam with a generic route intent: Basic derives the mandatory physical footprint and unions validated additive plugin resources; provide no raw planned-section or replacement-footprint automatic overload.
  - [x] Freeze the public route-intent contract before implementation: reuse one topology-bound `RouteSnapshot`, stable
    train ID and coherent observation stamp; use route-metre desired/release boundaries; accept only unique additive stable
    resources; define the runtime-only namespaced preparation descriptor, validation/result order, owner/thread/lifecycle
    semantics and same-route extension relation. This item changes no runtime API.
  - [x] Implement the frozen immutable route-intent request values: validate namespaced request/preparation identities,
    route-metre boundaries and unique additions; defensively copy additions and deterministically ordered scalar preparation
    arguments. Admission services, registry mutation and caller wiring remain later batches.
  - [x] Implement typed generic admission result and service contracts: opaque process-local pending/grant identities,
    immutable committed-grant snapshots, structurally checked conflict diagnostics, one result type shared by submit/progress and
    the world-local main-thread service boundary. Runtime admission implementation and caller wiring remain later batches.
  - [x] Add a pure compiled-admission merge for caller additions: validate each addition through a supplied owner check,
    preserve the physical footprint/preparation, extend rather than shorten a duplicate physical resource's hold, and retain
    added preparation metadata. Registration, provider dispatch, atomic claim and production caller wiring remain later batches.
  - [x] Reuse the existing Bukkit resource-provider registration for world-aware route-addition validation: every
    provider implements route-use validation explicitly; the resource owner must approve requester, resource and preparation values,
    and a disappearing/throwing provider cannot authorize an addition. Non-null preparation is still rejected until
    a real handler exists; generic admission service wiring remains.
  - [x] Join the existing Basic route projection, physical compiler, additive merge, occupancy index,
    atomic initial claim, preparation ticket and tail-release controller in one internal first-admission
    owner. Integration tests cover approved addition, no-partial denials, pending/ready/safe-abort/unsafe
    transitions, host-held claim ownership after requester unload and physical tail release.
    It is not the public service: extension, production
    route-bound catalog publication, authenticated Bukkit provider binding and caller migration remain.
  - [x] Bind that engine to the production world lifecycle without changing Dispatcher behavior: one Bukkit
    world record now owns the existing coordination registry plus one Basic route-admission runtime, authenticates
    additions through the existing provider registry, uses the live turnout actuator, and consumes the exact
    observation returned after each successful physics ticket before Dispatcher. Physics starts only after
    composition; unload and failed startup close the runtime and registry together. The route-bound catalog is
    complete-empty until diamond bindings exist, and public request/caller/grant-constraint wiring remains.
  - [x] Expose that world runtime through the existing Bukkit `InfrastructureResourceService` as one
    plugin/world-bound `RouteResourceAdmissionService`. Request, pending/terminal progress and explicit
    grant end all retain the authenticated plugin identity; plugin disable and exact world-runtime
    replacement invalidate the binding without releasing host-owned train claims. Replace the external
    compile fixture's superseded protection-region request with the generic route intent. Dispatcher and
    train-protection consumption remain separate.
  - [x] Close the minimal add-on disable lifecycle without a tombstone or recovery state machine: planned owner
    unregister rejects pending reservations and nonempty route claims but removes empty route state; an already-fired
    independent Bukkit disable retains active host-owned route resources and stalls each affected world with precise
    plugin/world/route/train diagnostics. Host/server shutdown continues through ordinary world/service close.
  - [x] Apply the same disable guard to resource-provider ownership without a second owner table: at unregister/disable,
    inspect existing claims, committed release queues and pending compiled requests for the provider's registered type IDs.
    Another requester's use therefore rejects planned unregister or retains resources and Stalls on forced provider loss.
  - [x] Implement corrected resource ownership before generic delayed preparation: remove contributed resources and descriptors
    from the ATO intent, add authenticated Bukkit route contributors, collect each once per admission, and validate their copied
    output through the existing resource owners. Provider validation receives contributor identity rather than ATO identity;
    no old intent constructor, requirement type or failure-code alias remains. Contributor membership is frozen while any route
    state exists, and forced contributor loss stalls affected worlds instead of silently weakening later suffix requests.
  - [x] Expose the existing preparation state/ticket as a platform-neutral Basic API and add provider-owned Bukkit handlers
    without a second catalog. Each resource type registration owns its unique namespaced handler kinds; pre-claim validation
    rejects missing kinds, while the post-claim actuator resolves the complete physical/provider batch before its first side
    effect and aggregates child tickets into the existing pending admission. Provider start/snapshot failure or forced owner
    loss retains the claim and Stalls; handlers cannot inspect trains/routes or change claims, and no old three-argument
    registration overload remains. Focused Basic/Bukkit tests and the independent Java consumer cover pending-to-ready flow.
  - [x] Review the internal first-admission and release hot paths: delayed `READY` now rechecks the latest
    coherent observation/topology/occupancy before publishing a grant, while ordinary epoch advancement
    rebinds the grant to the newer stamp. Release keeps one queue plus a head index, avoids empty-world
    train indexing and redundant linear membership checks, and retains tests for sequential release and
    suffix installation. This review identified the then-remaining quadratic registry shrink cost.
  - [x] Remove that registry shrink cost without changing public claim snapshots: store only live claim
    resources in one lock-protected ordered identity set, validate a complete release batch before
    in-place deletion, and materialize immutable footprints only on explicit snapshot reads. Pending
    initial rollback still checks an immutable original footprint; pending append protects its delta
    while prefix release continues. Regression tests cover detached snapshots, order, repeated releases,
    append rollback and delta-only evidence. With fixed pending-attempt count, sequential release is
    average linear in the number of resources; pending-owner lookup and conflict scanning remain separate.
  - [x] Complete the internal generic same-route extension: retain the first grant's frozen projection,
    cursor and ID; compile/occupancy-check only the farther suffix; reserve and prepare only new
    identities; defer any longer hold requested for an already-held identity in the same release queue
    while other old resources continue tail release. Fresh `READY` validation commits delta and the
    later authority together; safe abort removes only delta, restores old holds and releases any now
    overdue resource. An empty old queue reacquires the suffix under the same live grant. Focused and
    full Core/Basic tests cover pending, ready, conflict, abort, unsafe, old-prefix release, resource-idle
    extension and changed occupancy. Production catalog/provider binding and caller migration remain.
  - [x] Simplify the generic request lifecycle before production binding: expose one request operation
    for first authority and same-route growth, with route state (frozen route and one tail cursor)
    separate from its nullable committed grant. Reuse the same pending-attempt path and only branch
    where an existing grant changes the range or registry mutation; remove the unpublished `admit`/
    `extend` API split rather than add compatibility wrappers. Verify a first safe abort removes an
    unused route state, while later safe abort preserves the earlier grant, and a resource-idle route
    still accepts its next suffix. Update the Basic owner spec and focused integration tests.
  - [x] Delete superseded contributor-closure, Basic-owned `AuthoritySection`/fixed-block and provider-callback target prose
    from the transitional protection spec. Retain only current implementation mechanisms and migration regression evidence;
    the generic target remains solely in `route-resource-admission.md`.
  - [x] Audit the superseded recursive provider-closure target and retain only the bounded non-recursive implementation. The old
    closure existed as design prose, not as a separate runtime implementation to delete: `PhysicalRouteResourceCompiler` has no
    plugin callback; `DefaultInfrastructureResourceService.collectRouteContributions` snapshots the registered contributors and invokes
    each once; `RouteResourceAdmissionEngine.request` validates and merges that one returned list after physical compilation.
    `InfrastructureResourceProvider` can only approve/reject an exact returned resource, `RouteResourceIntent` carries no contribution,
    and SimpleATO imports no contributed-resource type. No closure compatibility API remains. The transitional protection-region
    catalog/graph/window types stay for the separately tracked Stage 2 migration.
- [x] Keep the existing protection-region definitions, graph-cut validation and catalog/window resolution explicitly transitional in Basic during Stage 1; keep fixed-block policy out of Basic's generic host contract and hand protection implementation/persistence migration to the Stage 2 `JapaneseSignals` task. `protected-route-admission.md` now marks the complete catalog/graph/window surface `TRANSITIONAL`, the generic route intent and admission service contain no protection-region type, and the separate Stage 2 task owns code/persistence migration. No runtime compatibility path was added.
- [x] Generalize route resources from turnout-owner keys to one all-or-nothing claim containing rail intervals, canonical turnout identities, route-bound fixed crossings and the requested clearance rail slices. `PhysicalRouteResourceCompilerTest` proves a two-turnout route plus diamond and post-exit clearance produces one footprint, one matching release entry per identity and the complete occupancy evidence without a protection region; authenticated plugin resources join that same union rather than a second claim.
- [x] Remove turnout-derived protection-point synthesis from route compilation. `PlannedRoute` now carries only the validated expanded route; the dead `RouteSection`, `TurnoutRequirement`, turnout interval helper and mission-section projection were removed with their legacy allocator. Concrete signal plugins decide whether their configuration is complete, while `PhysicalRouteResourceCompiler` continues deriving Basic's non-removable rail/turnout/crossing resource floor directly from the frozen topology projection.
- [x] Centralize turnout mutation and add the physical-lock/active-claim checked manual operation that produces no automatic authority. The public Bukkit service accepts only topology revision plus stable ports, temporarily claims the exact turnout resource, waits for the current physics epoch, checks every turnout leg's configured locking interval, applies the shared bidirectional selector and releases the temporary claim on every return path. Focused Basic/Bukkit tests cover success, route-claim conflict, physical occupancy, stale topology and no retained claim; the compile-only external consumer imports the new contract.
- [x] Replace turnout-derived release assumptions with generic per-resource hold boundaries and physical clear envelopes.
  - [x] Add internal generic tail-clear queue metadata without per-resource occupancy state;
    eligible-prefix inspection now belongs to the live release owner.
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
- [x] Add route-bound diamond conflict resources plus placement/update spatial-candidate, exact-geometry detection, and rail/edge interval reverse indexing; do not create topology nodes or cross-line continuations.
- [x] Add namespaced revisioned train restriction contracts and enforcement adapter. The first public
  `SimpleATO` API accepts complete immutable per-source/per-train snapshots with strict revisions,
  coherent observation and compact frozen-route binding, route-relative targets and explicit empty
  withdrawal. Its internal adapter maps them into the existing Basic constraint manager in `O(S + R)`
  and fails closed on source/world/topology/route invalidation. Production controller/Bukkit service
  binding remains in the later service and dispatcher items; this batch does not claim runtime wiring.
- [ ] Implement the reviewed reactive railway-epoch boundary without binding safety progress to Bukkit's main thread:
  - [x] Publish ordered directed head/tail movement evidence plus the epoch-end ordered tail-to-head body placement in addition to merged swept occupancy. Core reuses the actual movement paths for boundary events and its committed train-local path for Dispatcher route assembly; Basic projects both unchanged. Merged swept occupancy remains the separate query index.
  - [x] Add generic monitored-area boundaries and derive ordered boundary-bearing `ENTER`/`LEFT` changes, including both changes for a complete same-epoch crossing; do not add `PASSED_THROUGH`. Plugin-owned areas are explicit per-rail canonical intervals plus inward-facing boundaries; Basic compiles rail-local boundary and interval indexes, consumes only consecutive coherent observations, uses a new epoch-end conservative `occupiedIntervals` publication for final membership, and keeps whole-epoch `sweptSpans` separate for collision/admission. Multi-rail AB/BA, exact-boundary rear clearance, administrative relocation and same-epoch pass-through are covered without assigning fixed-block or protection-region policy.
  - [ ] Consume occupancy changes, recompute dirty signal dependencies and publish next-epoch restrictions before permitting the following physics epoch; do not add a cross-epoch safety-event backlog.
    - [x] Add the platform-neutral synchronous epoch slice: Core captures but does not publish the coherent world frame until registered value-only processors return and revalidation succeeds; Basic exposes its indexed monitored-area tracker through one public serialized engine; SimpleATO binds each area to one namespaced Java/Kotlin source in the source snapshot captured for that epoch, invokes only dirty/baseline owners, validates every complete restriction result before deterministic publication, and propagates callback/publication failure so Core stalls. Same-epoch ENTER+LEFT remains one callback. Production composition, controller application, concrete plugin dependency propagation and presentation remain pending, so the parent item stays incomplete.
    - [x] Correct the source lifecycle prerequisite before Bukkit wiring: each world will keep one empty host processor registered before physics starts, while `SignalEpochRuntime` atomically replaces a fully validated immutable source state during runtime. One epoch captures exactly one state; a new state starts with a fresh occupancy baseline, invalid replacement retains the old state, and an empty state has no per-train work. Production plugin registration/unregister barriers remain in the Bukkit service batch.
  - [ ] Bridge only immutable revisioned presentation state to Bukkit/Folia and coalesce each object's pending visual update to its newest revision.
  - [ ] Complete the remaining inspection cache-key and fail-closed invalidation review before coding resource inspection; keep dynamic braking distance in the SimpleATO tick and avoid duplicate forward scans or unchanged per-tick plugin callbacks.
- [ ] Add Bukkit public service interfaces, internal implementations, lifecycle registration, and cleanup.
- [ ] Migrate `TimetableDispatcher` and `SimpleATO` integration to the shared services.
  - [x] Add the platform-neutral per-train SimpleATO admission client without changing production Dispatcher. It submits the
    exact frozen selection through Basic's single request operation, owns at most one pending handle, publishes a 0 m
    fail-closed authority before the first grant, advances only from `Granted.committedAuthority`, retains the previous
    boundary for pending/denied extensions, and requires explicit successful lifecycle end before accepting a changed route.
    The client feeds the existing namespaced `TrainRestriction` adapter/constraint manager and knows no resource identities.
    Production composition, retry policy and removal of the legacy Dispatcher allocator remain unchecked.
  - [ ] Preserve existing development runtime files through the packaged world-pair migration task:
    recovery copies, dry-run, current-pair validation and recoverable replacement move the exact
    Dispatcher checkpoint into SimpleATO. Do not retain a runtime compatibility reader, nullable
    fallback or dual writer. The current target schemas now exist, but the one-time migrator is not
    implemented yet; until it is, old development runtime files fail closed in the normal loader.
  - [x] Move `TimetableDispatcher`, its checkpoint contract/tests and all live composition into one world-local
    `SimpleATOWorldData`; remove `BasicWorldData.dispatcher` in the same batch. Keep behavior and the legacy allocator unchanged,
    and allow only the existing transitional `SimpleATO -> Basic` dependency. The implementation, checkpoint DTO/codec,
    isolated simulation and tests now live in SimpleATO; Bukkit captures/restores Core, Basic runtime V2 and SimpleATO runtime
    V1 as one coherent runtime V2 document and restores them in that order. The normal loader contains no old-format fallback;
    the separately planned packaged migration remains responsible for old development snapshots.
  - [x] Move the `SimpleATO` controller and ATO-owned mission/constraint runtime into the SimpleATO module without aliases or a
    Basic forwarding facade; update Bukkit factories, isolated simulation and controller/dispatcher tests together. The concrete
    controller now owns its mutable mission queue, constraint manager, provider state and publication state in SimpleATO; shared
    Basic value/safety primitives remain transitional to avoid reversing the module dependency. AbstractATO exposes one protected
    immutable publication snapshot so the cross-module subclass keeps the original atomic read without exposing mutable state.
  - [x] Wire automatic and `MANUAL_ROUTED` production runs to one observation-scoped frozen-route selection and the existing
    admission client/restriction adapter. Preserve `FULL_MANUAL` no-request behavior and continue release of existing claims.
    - [x] Add the production prerequisites without running a second allocator: the concrete controller owns the existing
      restriction adapter and applies it to its sole constraint manager; Core publishes the ordered body placement needed to
      assemble Basic's tail-started route from the already-planned mission. Runtime admission identity no longer includes a
      Basic metadata revision.
    - [x] Freeze the constant-time rolling-boundary rule without adding a second admission lifecycle or route scan. The admission
      client exposes only its Basic-backed committed boundary and pending flag; a stateless planner reuses the controller's existing
      perception horizon, requests only after remaining authority reaches the existing allocation trigger, and caps the result at
      the frozen route end. The assembled route records its initial mission progress so later head progress maps onto the same
      tail-started coordinate without another projection. Production requests, retry timing, stale-route replacement and mission
      transitions remain in the parent migration item.
    - [x] Correct the ordinary-stop lifecycle before production wiring: once authority reaches the current operating-route end,
      SimpleATO may preserve its complete segment prefix and append the next forward mission under the route's own monotonic
      revision. Basic reprojects the candidate but compiles/occupancy-checks/reserves/prepares only the farther authority range;
      pending or rejected preparation leaves the old binding/grant/release active, while READY keeps the same grant ID, owner and
      tail cursor and commits the longer progress path, release suffix and boundary together. Modified prefixes, disconnected
      appends and reversal are rejected without mutation. Dispatcher production composition remains unchecked.
    - [x] Bind Bukkit production to one authenticated Basic admission service and one observation/context read per Dispatcher
      Tick. Automatic and `MANUAL_ROUTED` runs now assemble from the preplanned mission without another search or allocator,
      use rolling request/progress/retry semantics, and apply Basic authority through the controller adapter. `FULL_MANUAL`
      submits no new request but progresses an existing pending handle; stale topology/route mismatch requires explicit replan.
      Ordinary-stop and same-direction stored-service continuation keep the same owner/grant/tail cursor. A complete frozen-route
      grant publishes an empty Basic truncation while the mission Stop constraint retains terminal braking.
  - [x] Prove the migrated priority, stop/station-call, transient retry, stale-route re-selection, manual-mode and tail-release
    behavior; make the admission context mandatory; run isolated simulation through a real Basic admission runtime; delete
    `RouteSectionAllocator`, Confirmed Route Window state and the final dispatcher-specific RailWand allocation guard.
    The rolling low-water mark uses train maximum speed so braking toward an existing authority cannot make the extension trigger
    retreat; Dispatcher also retains only mission runtimes still referenced by an active operational boundary, because Basic now
    owns resource-tail history. Depot Reset report schema V2 removes the obsolete always-zero RouteSection release count.
  - [x] Support stopped reversal without replacing the grant or claim: suspend the old tail cursor before Core changes direction,
    retain every claimed resource, immediately validate a body-only route from the new tail to the reversed head, rebuild the
    cursor and release boundaries, then append only the untravelled suffix of the already-planned return mission. Do not invent
    a stopping overlap: timetable/signal configuration owns the separation between stop target and authority end. Prove that an
    invalid rebase releases nothing and that the isolated out-and-back timetable completes through the real Basic runtime.
- [ ] Add topology edit guards for active external claims.
  - [x] Define the edit-coordination contract without treating track or topology nodes as runtime resources:
    `TopologyEditImpact` declares exact rail IDs, stable topology-node keys and runtime resource IDs; the registry checks each
    category against applied-plan dependencies, live claims and held permits under one lock.
  - [ ] Add an exact plan/apply phase to RailWand topology edits so every editor knows its real impact before mutation, then hold
    one common permit across topology plus metadata/persistence side effects. The rejected spatial broad-phase candidate union,
    full-rail footprint compiler, incident-resource graph scan and guessed RailWand guard were removed rather than retained as a
    compatibility path.
    - [x] Add exact replacement/removal impact resolution and wire RailWand split, merge and delete. The caller supplies only
      replaced source rails; immutable topology reverse indexes derive only their changed connection peers, source-endpoint
      nodes/turnouts, source-edge deterministic loop anchors and source-rail diamonds. The plan binds the exact topology root plus
      every affected existing rail instance, and holds the common permit through one outer Core transaction spanning topology,
      metadata/render persistence, player-state persistence and undo-record side effects.
    - [x] Delete proximity auto-connect and wire turnout create, selected-anchor connect, endpoint rebuild, grade smoothing and
      geometry rebuild through a Core-owned explicit plan/apply. Standalone rails remain disconnected; callers supply only selected
      endpoints, batch-internal adjacency and old-to-new replacement mappings. The plan binds the prepared batch/root, records exact
      endpoint state plus prepared crossings and applies without target re-selection. Basic maps only those finalized identities into
      the common edit impact; nearby candidates are excluded.
    - [x] Plan prospective crossings for whole-world snapshot undo. Bukkit captures current/target snapshots; Core performs the
      sole physical diff, reuses the prepared geometry pipeline with persisted stable UUIDs (including same-ID replacement),
      validates explicit saved connections and currentness, and keeps target topology/crossings local to preparation. Basic maps
      only the exact plan into the common permit using current-valid identities: removed diamond resources and the existing rail
      participants of prospective crossings, never target-only identities. Preparation is async; apply revalidates on the player
      owner and holds the permit through the outer Core transaction plus metadata/render/player side effects. Snapshot-plan
      occupancy replaces the old broad duplicate check; live route claims are covered by the common registry permit and frozen
      operational routes retain only the separate `hasLiveRouteOn` replacement guard.
- [ ] Cover remaining protection-region, signal, topology migration, and administrative mutation paths with the common edit guard.
- [ ] Document a minimal external consumer and supported failure/lifecycle behavior.

## Validation

- [ ] Focused Core tests for immutable publication, epochs, occupancy, diamond resource binding/no-connectivity-change, detection/update, and controller enforcement.
- [ ] Focused Basic tests for no-partial atomic claims, conflicts, zero/one/multi-turnout paths, mandatory physical floor, additive plugin resources, stale revisions, tail release and timetable migration.
- [ ] Migration tests prove protection-region/fixed-block ownership leaves Basic without changing generic topology projection, claims, preparation or release behavior.
- [ ] Tests prove omitted/replaced turnout or diamond resources are restored from Basic derivation, while plugin configuration failures remain restrictive without a Basic `PROTECTION_REQUIRED` policy.
- [ ] Inspection tests prove the reviewed declaration/invalidation behavior, host-owned dynamic braking distance, no duplicate forward scan and no unchanged per-tick plugin callback.
- [ ] Tests for overlapping applied-plan dependencies, atomic apply/replace/remove, field-wise edit denial diagnostics, and no partial mutation across every supported infrastructure editor. Test in-process disable/world reload separately from restart recovery; do not require reconstruction of in-flight claims without an approved persistence contract.
- [ ] Bukkit tests for service registration/lookup, plugin disable, world unload, and target-aware dispatch.
- [ ] Compile-only external consumer test imports public packages only.
- [ ] Run `./gradlew :Core:test :Basic:test :BukkitPlugin:test`.
- [ ] Run `./gradlew :BukkitPlugin:shadowJar` and inspect service/API packaging.
- [ ] Run `git diff --check`.

## Rollback points

- Keep resource model, restriction model, and Bukkit service exposure in reviewable commits.
- Do not remove the old timetable path until parity tests pass against the shared service.
- Do not declare the Stage 2 dependency ready until disable/failure semantics are tested.
