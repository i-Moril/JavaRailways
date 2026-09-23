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

The current route state owns one frozen route and one monotonic tail cursor; its committed grant is initially absent. The first request establishes that state after resource reservation succeeds, and `READY` installs the committed grant and its ordered release queue without replacing the cursor. Later requests on the same route compile and prepare only the farther suffix while existing resources continue tail release. A safe abort before the first grant removes the now-unused route state; after a grant exists, even an emptied resource queue retains the route and cursor until explicit end. A changed route ends/replans at a safe operational boundary rather than replacing the claim in place.

## Resource inspection and reactive constraints

`InspectionDistance(train)` tells SimpleATO when to inspect/request a resource; it is not a signal-update tick callback. SimpleATO independently computes current braking distance each physics tick. The inspection declaration's cache and invalidation rules remain to be reviewed before implementation.

One shared route perception must avoid a second forward scan. A plugin publishes immutable restrictions; SimpleATO combines them with Basic grant, following, operational stops and infrastructure speed constraints. Plugin code can only keep or tighten the result. The owner of dependency tracking, activation/update timing and fail-closed invalidation is deliberately deferred to the reactive implementation review; unchanged resources must not invoke plugin code every tick.

## Public service capabilities

Route-admission value names and semantics are frozen by the Basic owner spec. The generic request/progress/end service is
now exposed through an authenticated plugin/world binding on the existing Bukkit infrastructure service; production callers
and the remaining public surfaces still arrive in separate reviewable batches:

- immutable railway observation subscription/read service;
- namespaced infrastructure resource registration and validation;
- one topology-bound route-resource request for both first authority and farther same-route authority, plus pending-attempt progress;
- generic infrastructure preparation handler registration;
- namespaced train restrictions and dependency-change publication;
- shared applied-plan infrastructure leases and edit permits.

Raw claim mutation, mutable Core trains/rails, Basic internal cursors and complete registry implementations are not public add-on APIs. Bukkit `ServicesManager` performs platform discovery; platform-neutral DTOs live in documented Core/Basic/SimpleATO API packages.

## Applied timetable and edit locking

SimpleATO compiles every statically reachable automatic route and required signal-plugin configuration before apply. It pins topology, timetable, signalling package and referenced-object revisions, computes one stable infrastructure lease footprint and atomically installs the applied plan only if the full shared lease acquisition succeeds.

Draft save/dry-run acquires no leases. Apply enables service; replacement acquires the new set before dropping no-longer-used leases; unapply enters draining and blocks new departures/grants until live work clears. Every topology, resource, signal or operational-object editor must obtain a common edit permit and return the owning plan/revision when blocked. Runtime occupancy conflicts remain waits, not apply-time structural failures.

## Migration

Keep the already implemented generic code:

- revisioned `TopologyState` and frozen-route projection;
- stable turnout resource identity and route-bound resource catalog;
- occupancy index/query geometry;
- atomic registry, preparation lifecycle and multi-turnout validation;
- monotonic cursor, release queue and same-route suffix extension;
- infrastructure leases/edit guard.

Migrate or delete Basic-specific policy:

- `ProtectionRegionDefinition`, catalog, graph cut and window resolver;
- Basic-owned fixed-block/`AuthoritySection` partition;
- `PROTECTION_REQUIRED` as a Basic admission failure;
- recursive route-resource contributor closure and Basic-selected clearance expansion.

During Stage 1, current protection tests remain regression evidence, while Basic's protection
implementation stays explicitly transitional and is not exposed as the generic host contract.
Stage 2 moves its policy and persistence boundary into `JapaneseSignals` and then removes the
transitional Basic types; neither stage adds a runtime compatibility layer without separate approval.

## Failure and threading

All validation failure is atomic and does not widen authority. Missing resource indexes, stale revisions, contributor failure or invalid contributed resources fail closed. Existing committed grants remain host-owned through physical release, so unloading a signal plugin cannot drop resources beneath a train.

Physics workers consume immutable observations and constraint snapshots. Route claim and equipment preparation execute on their documented world/main owner. Add-on callbacks are bounded, non-blocking and are never invoked once per world resource every tick.
