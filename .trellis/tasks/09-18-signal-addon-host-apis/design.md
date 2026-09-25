# Stage 1 design

## Authoritative contracts

- Core topology/publication: [`versioned-operational-topology.md`](../../spec/javarailways/core/versioned-operational-topology.md)
- Basic target route/resource kernel: [`route-resource-admission.md`](../../spec/javarailways/basic/route-resource-admission.md)
- Basic registry and release primitives: [`resource-allocation.md`](../../spec/javarailways/basic/resource-allocation.md)
- SimpleATO routing, inspection and authority: [`operational-routing-and-authority.md`](../../spec/javarailways/simpleato/operational-routing-and-authority.md)
- Current protection-region implementation, retained only for migration evidence: [`protected-route-admission.md`](../../spec/javarailways/basic/protected-route-admission.md)

The earlier design made Basic own a universal `AuthoritySection` partition and recursive contributor closure. That target is superseded. Completed generic mechanisms remain valid; protection-region/fixed-block policy moves to a concrete signal-system plugin.

## Module boundaries

```text
Core
  physical topology + immutable world/train observation

Basic
  generic resource registry + mandatory physical footprint
  atomic claim + device preparation + grant/release/edit guard

SimpleATO
  operating route + resource inspection scheduler
  ATO/ATP + most-restrictive constraint arbitration

Independent signal-system plugin
  blocks/protection regions/interlocking/aspects
  optional JS/YAML + persistence + authoring/rendering adapter
```

`SimpleATOBukkit` is the Bukkit adapter/common runtime for SimpleATO. A concrete system such as a Japanese-signalling plugin is separately compiled and may use or ignore any optional scripting helpers; it is not forced to encode its interlocking through one host JS engine.

## End-to-end working model

```text
Core publishes TopologyState(revision) + coherent train observation
  -> SimpleATO selects one frozen route
  -> inspection/constraint arbitration chooses a desired authority boundary
  -> SimpleATO submits only route + train/stamp + boundary
  -> host asks authenticated signal-plugin contributors for resource requirements
  -> Basic validates the route against the same topology/observation
  -> Basic derives rail + turnout + diamond mandatory resources
  -> Basic validates union(mandatory resources, plugin contributions)
  -> one atomic claim
  -> generic resource handlers prepare claimed equipment
  -> READY publishes a grant
  -> SimpleATO reduces grant, following, stops and plugin limits by minimum authority/speed
  -> authoritative tail progress shrinks the claim queue
```

Neither the ATO caller nor a plugin submits a replacement physical footprint. Plugins only contribute resources and stricter hold/restriction semantics. Basic never calls a recursive protection-region/provider closure and never decides a signalling system's route policy.

The exact target request/result, route-metre boundary, authenticated contribution and runtime-only preparation descriptor are frozen in
[`route-resource-admission.md`](../../spec/javarailways/basic/route-resource-admission.md); this task design does not copy
that owner contract. The public ATO intent contains no additions or descriptors. Every contribution has one exact stable identity and explicit tail-release boundary. A duplicate
mandatory identity can only extend its hold and cannot replace Basic's physical preparation.

```text
SimpleATO produces frozen route + coherent train/stamp + operational scan horizon
SimpleATO submits desired authority without signal-system resource knowledge
host invokes each registered signal-policy contributor once for that request
Basic validates coherent state and route -> compiles mandatory physical floor
Basic validates contributions/descriptors -> exact-identity union -> occupancy query
Basic atomically claims union -> prepares -> publishes grant only after READY
```

## Route compilation and physical floor

The request carries one frozen route, coherent observation stamp, target train and desired authority boundary. Basic verifies route start at the authoritative train tail and walks the requested range once. The same projection produces:

1. canonical AB intervals for every traversed rail slice;
2. stable direction-independent turnout identities and route-ordered selections;
3. route-bound diamond/shared-conflict identities from the catalog tied to the same topology root;
4. occupancy-query geometry, preparation requirements and release boundaries.

Items 1-3 form a non-removable footprint. Plugin contributions are validated through the namespaced resource registry and unioned by exact identity. Occupancy geometry and release/preparation metadata never form alternate footprints.

This division gives Basic a physical safety floor while allowing different interlocking models. A protection-region plugin can validate entry/exit and request enough route plus flank resources to clear a throat. A route-table plugin can request approach locking and sectional-release resources. An incomplete policy may cause a restrictive stop or deadlock, but it cannot omit traversed physical conflict resources or widen authority beyond Basic's grant.

## Resource preparation and lifecycle

Claims and physical readiness are distinct. Basic claims the complete union before asking registered handlers to prepare movable or delayed equipment. `PREPARING` retains the reservation and publishes no grant; `READY` commits the grant; `ABORTED_SAFE` removes only the new request/delta; `UNSAFE` or an unprovable abort retains protection and stalls the world.

Route contributors are authenticated lifecycle-bound policy entry points, not another resource catalog. They run once per admission
and use the signal plugin's own index; SimpleATO never sees their identities or descriptors. Provider preparation uses one handler
collection on the existing resource-type registration. Basic owns the platform-neutral ticket states and one deterministic composite
batch; Bukkit handlers receive only attempt/world/resource/descriptor values. Complete preflight precedes side effects, while any
exception after start is unprovable and retains the claim under Stall. Handler polling occurs only through a pending admission handle,
not from every physics tick.

Contributor membership is frozen while any route state can request a farther suffix. Planned add/remove is refused during that
window; forced contributor loss stalls every affected world while retaining host-owned claims. This uses no generation token,
takeover or recovery state machine.

A planned add-on disable must first unregister its infrastructure owner. The host refuses that step while the requester has
a pending route reservation or any resource left in its route claim; an otherwise empty route state does not block disable.
Bukkit cannot cancel an already-fired disable event, so forced disable retains those host-owned resources and stalls each
affected world through the existing coordinator. This lifecycle deliberately adds no drain, takeover, session token or
cross-instance recovery protocol. The same rule applies when the disappearing plugin owns a registered resource type used
by another requester's active or pending route; provider ownership cannot bypass the disable guard.

Current in-memory turnout switching is immediate and has no fictional rollback. Future animated turnouts, movable bridges or other delayed devices use the same generic preparation contract; their default/resting state belongs to the resource implementation, not the train.

The current route state owns one append-only operating route and one monotonic tail cursor; its committed grant is initially absent. The first request establishes that state after resource reservation succeeds, and `READY` installs the committed grant and its ordered release queue without replacing the cursor. Later requests compile and prepare only the farther authority suffix while existing resources continue tail release. At an ordinary stop after authority reached the old route end, the next forward mission may preserve every committed segment and append a connected traversal under a higher operating-route revision. Pending/rejected continuation keeps the old route binding; `READY` retains the same grant, owner and cursor while committing the longer progress path, release suffix and authority. A safe abort before the first grant removes the now-unused route state; after a grant exists, even an emptied resource queue retains the route and cursor until explicit end. Modified prefixes, disconnected continuation and reversal remain rejected replans rather than whole-claim replacement.

## Resource inspection and reactive constraints

`InspectionDistance(train)` tells SimpleATO when to inspect/request a resource; it is not a signal-update tick callback. SimpleATO independently computes current braking distance each physics tick. The inspection declaration's cache and invalidation rules remain to be reviewed before implementation.

One shared route perception must avoid a second forward scan. A plugin publishes immutable restrictions; SimpleATO combines them with Basic grant, following, operational stops and infrastructure speed constraints. Plugin code can only keep or tighten the result. The first reviewed reactive slice now routes Basic monitored-area changes synchronously to their static Java/Kotlin source before the Core epoch completes; unchanged areas do not invoke plugin code. Concrete plugin-internal downstream dependency propagation and the separate inspection-distance cache/perception scheduler remain later work.

The railway safety loop is independent of the Minecraft main thread and serializes complete epochs:

```text
physics N
  -> publish immutable world observation plus body placement and ordered head/tail movement traversal
  -> Basic derives ordered ENTER/LEFT changes with area and boundary identity
  -> signal plugins update occupancy, interlocking and dependent restrictions
  -> SimpleATO-visible restrictions for physics N+1 are committed
  -> epoch N completes and physics N+1 may start
```

This is a direct in-epoch safety handoff, not an asynchronously drained occupancy-event queue. Core's existing merged
`sweptSpans` remain useful for overlap queries but cannot prove boundary order or repeated occurrences; the observation
contract therefore also needs ordered directed traversal evidence for the train head and tail. Crossing a complete short
area in one epoch emits `ENTER(entryBoundary)` followed by `LEFT(exitBoundary)` rather than a synthetic pass event.

Final area membership cannot use the same swept envelope: a train may have crossed an area earlier in the epoch but be
fully outside when the epoch ends. Core therefore also publishes conservative epoch-end `occupiedIntervals`. The three
facts stay separate: placement answers “still inside”, swept spans answer “touched during this epoch”, and ordered
head/safety-rear traversal answers “which boundary was crossed, in what direction and order”. A future approximate rear
may lag the exact train tail and delay `LEFT`, but must never lead it or release early.

Only presentation crosses into the Bukkit scheduler asynchronously. A signal plugin publishes immutable per-object visual
state with a monotonic revision; its Bukkit adapter keeps at most the newest pending revision and applies it on the proper
platform thread. A stalled Minecraft thread may delay or skip obsolete lamp/model transitions, but never delays logical
signal evaluation. A resource whose physical preparation genuinely requires a Bukkit-side action stays `PREPARING` until
the adapter reports completion, so that dependency can stop the affected train without stopping unrelated railway physics.

## Public service capabilities

Route-admission value names and semantics are frozen by the Basic owner spec. The generic request/progress/end service is
now exposed through an authenticated plugin/world binding on the existing Bukkit infrastructure service; production callers
and the remaining public surfaces still arrive in separate reviewable batches:

- immutable railway observation subscription/read service;
- namespaced infrastructure resource registration and validation;
- one topology-bound route-resource request for both first authority and farther same-route authority, plus pending-attempt progress;
- generic infrastructure preparation handler registration;
- namespaced train restrictions and dependency-change publication;
- non-exclusive applied-plan rail/node/resource dependencies and edit permits.

Raw claim mutation, mutable Core trains/rails, Basic internal cursors and complete registry implementations are not public add-on APIs. Bukkit `ServicesManager` performs platform discovery; platform-neutral DTOs live in documented Core/Basic/SimpleATO API packages.

## Applied timetable and edit locking

SimpleATO compiles every statically reachable automatic route and required signal-plugin configuration before apply. It pins topology, timetable, signalling package and referenced-object revisions, computes explicit persistent rail IDs, stable structural-node keys and runtime resource IDs, then atomically installs those non-exclusive dependencies with the applied plan.

Draft save/dry-run publishes no dependencies. Apply enables service; replacement atomically replaces the complete declaration; unapply enters draining and blocks new departures/grants until live work clears. Every topology, resource, signal or operational-object editor must obtain a common edit permit and return the owning plan/revision plus the intersecting rail/node/resource identities when blocked. Runtime occupancy conflicts remain waits, not apply-time structural failures.

## Migration

Keep the already implemented generic code:

- revisioned `TopologyState` and frozen-route projection;
- stable turnout resource identity and route-bound resource catalog;
- occupancy index/query geometry;
- atomic registry, preparation lifecycle and multi-turnout validation;
- monotonic cursor, release queue and same-route suffix extension;
- applied-plan dependency/edit coordination.

Migrate or delete Basic-specific policy:

- `ProtectionRegionDefinition`, catalog, graph cut and window resolver;
- Basic-owned fixed-block/`AuthoritySection` partition;
- `PROTECTION_REQUIRED` as a Basic admission failure;
- recursive route-resource contributor closure and Basic-selected clearance expansion.

During Stage 1, current protection tests remain regression evidence, while Basic's protection
implementation stays explicitly transitional and is not exposed as the generic host contract.
Stage 2 moves its policy and persistence boundary into `JapaneseSignals` and then removes the
transitional Basic types; neither stage adds a runtime compatibility layer without separate approval.

### Dispatcher ownership prerequisite

The first ownership batch is complete: the live dispatcher is constructed in
`SimpleATOWorldData`, ticked through that SimpleATO extension and serialized by
`SimpleATORuntimeSnapshotV1`. `BasicWorldData` and `BasicRuntimeSnapshotV2` contain no dispatcher
field. This keeps the frozen-route selector, admission client, restriction adapter and their future
production caller on the same side of the module dependency. The dispatcher still consumes
transitional Basic timetable/value types and the legacy allocator until the following batches.

Use the following reviewed migration sequence instead:

1. **Implemented.** Add one world-local `SimpleATOWorldData` extension and make it the sole owner of the dispatcher.
   Move the dispatcher implementation, checkpoint DTO/codec, tests and Bukkit/isolated-simulation
   composition together. `BasicWorldData` loses its dispatcher field in the same batch. The moved
   dispatcher may still consume transitional Basic timetable, station, depot and controller types
   through the existing `SimpleATO -> Basic` dependency; no Basic-to-SimpleATO bridge or forwarding
   accessor is added. This first batch is behavior-preserving and continues to use the legacy route
   allocator.
2. **Implemented.** Move the `SimpleATO` controller and its ATO-owned mission/constraint runtime into the SimpleATO
   module, updating factories, train-controller lifecycle and tests without compatibility aliases.
   Low-level Basic route validation/resource services and temporarily shared ATO value/safety
   primitives remain in Basic; Basic contains no concrete controller or forwarding alias.
3. Give the production dispatcher one coherent route-selection/admission context per published
   observation. Automatic and `MANUAL_ROUTED` runs select a topology-bound frozen route, drive the
   existing per-train admission client and apply its restriction snapshot to the one controller.
   `FULL_MANUAL` creates no new automatic request, while already-held claims continue normal tail
   release.
4. After parity tests cover dispatcher priority, stops, station calls, retries, manual modes and tail
   release, remove `RouteSectionAllocator` and the remaining dispatcher-specific RailWand edit guard.

The ownership move includes persistence rather than leaving a Basic-owned checkpoint behind. The
target runtime files are three coherently paired documents: Core runtime, Basic runtime without
dispatcher state, and SimpleATO runtime containing the dispatcher checkpoint. Restore order is Core,
Basic infrastructure/runtime, then SimpleATO runtime so timetable, rolling-stock and physical train
references exist before dispatcher recovery. Capture validates the same infrastructure revision and
physics epoch across all three documents.

This is a breaking change to the current outer runtime file and Basic runtime document. Existing
development runtime snapshots are preserved by the packaged pair migrator planned in
`09-24-explicit-rail-endpoint-connections`. That migration creates recovery copies, extracts the old
embedded Dispatcher checkpoint into the new SimpleATO document, validates the complete target pair,
supports dry-run and makes an interrupted two-file apply recoverable before normal world loading.

Do not add a nullable fallback field, forwarding `BasicWorldData.dispatcher`, dual writer or runtime
V1/V2 business branch. The normal loader and writer handle only the current model after the one-time
migration boundary has completed.

## Failure and threading

All validation failure is atomic and does not widen authority. Missing resource indexes, stale revisions, contributor failure or invalid contributed resources fail closed. Existing committed grants remain host-owned through physical release, so unloading a signal plugin cannot drop resources beneath a train.

Physics workers consume immutable observations and constraint snapshots. The serialized railway coordinator owns the
post-physics occupancy/signal phase and does not wait for Minecraft presentation work. Route claim and equipment preparation
execute on their documented railway owner; only platform actions are dispatched to Bukkit/Folia threads. Add-on callbacks
are bounded and are never invoked once per world resource every tick merely because time advanced.
