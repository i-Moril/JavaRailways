# Stage 1 design

## Pre-implementation contract freeze

保护区域及进路准入的authoritative实现合同见[`protected-route-admission.md`](../../spec/javarailways/basic/protected-route-admission.md)。后续实现必须先满足其中的region graph cut、coherent observation stamp、host-derived footprint、composite no-hold-and-wait、claim→turnout commit→grant顺序、rollback failure Stall和host-owned recovery规则；本task design不再用原则性文字覆盖这些细节。

实现顺序固定为：region catalog/graph resolver → immutable resolved plan → atomic admission transaction → tail-release/recovery → Dispatcher与`MANUAL_ROUTED`迁移。前一层合同测试未通过时不得开始后一层迁移。

## Public service surface

The exact type names may be refined during implementation, but the contract should have three focused capabilities rather than one unrestricted facade:

- `RailwayObservationService`: immutable, epoch-tagged world/train/topology observations after physics publication.
- `RouteResourceService`: atomic claim, confirm, inspect, and release operations for region route windows, rail intervals, turnout selections, fixed crossing nodes, and exit-clearance envelopes.
- `TrainRestrictionService`: namespaced, revisioned movement-authority, stop, and speed constraints enforced by the active train protection/controller layer.
- `InfrastructureLeaseService`: shared revisioned configuration leases and mutation guards for infrastructure referenced by applied operational plans.

Bukkit-facing discovery belongs in `bukkit.api.*` and `ServicesManager`. Platform-neutral DTOs and mechanisms belong in `core.api.*` or `basic.api.*`. Service implementations remain internal.

## Topology and resource model

A request includes a stable request ID, owner ID, world ID, coherent observation stamp, train ID where applicable, direction-aware entry/exit boundaries, a frozen route, and an exit-clearance envelope. Rail intervals, turnout selections, fixed crossing/conflict nodes and release slices are host-derived outputs rather than caller input. Validation completes before mutation. Commit records the complete claim before applying all turnout selections, then publishes the grant; a rejection publishes no authority and records no partial ownership.

Protection stop location belongs to the route-window entry. The host no longer derives competing authority limits for each turnout inside the window. When no independent protection region exists, the host must not silently recreate the removed per-turnout stop policy; the caller receives an explicit unavailable/unprotected result according to the final operating contract.

Automatic admission is capability-shaped rather than plugin-presence-shaped. `RouteResourceService` accepts an automatic request only as a validated `ProtectedRouteWindow` carrying the stable region/entry/exit IDs, topology and region revisions, frozen path, and exit-clearance envelope. The complete resource claim is a host-derived output, never caller input. There is no automatic overload equivalent to today's `allocate(owner, plannedRoute, sectionIndex)`. Before mutation, the service recomputes/validates that every controlled resource on the frozen traversal is covered exactly once; absent or partial coverage returns `PROTECTION_REQUIRED`, including when a required turnout is already aligned.

Route compilation no longer inserts boundaries from each `TurnoutRequirement.protectionDistanceMeters`. It partitions automatic authority only at explicit registered region boundaries and rejects automatic activation when a turnout, diamond, or other controlled resource lies outside all validated windows. A route with no controlled resource needs no protection request and remains valid.

Turnout mutation is centralized behind the host service. The public read model may expose selected connectivity, but automatic callers cannot directly call a mutable `selectNextRail` path. A distinct manual operation checks physical train occupancy and active route claims before applying an operator-selected branch; it creates no route grant or movement authority. `MANUAL_ROUTED` remains automatic route assistance and therefore requires the same protected window as fully automatic operation, whereas `FULL_MANUAL` creates no new route request.

A diamond node connects each incoming rail only to its same-line continuation and exposes no selectable branch. Both continuations refer to one exclusive conflict resource. Detection runs in topology-edit transactions: spatial lookup produces candidates, exact geometry and vertical-clearance checks distinguish an at-grade conflict from non-conflicting or grade-separated geometry, and the committed node advances topology revision.

Release uses the train's complete occupied intervals and region boundary progress. A resource becomes releasable only after the intended train was observed entering and later fully clears the relevant conflict/release envelope; the window completes only when the tail crosses its exit. Administrative cancellation and plugin-disable behavior are separate explicit operations.

No request may hold one region while waiting for a resource omitted from its original claim set. The admission check proves exit clearance. If adjacent regions lack enough intermediate capacity for the train, callers submit one composite request and the service commits both regions atomically. Deterministic request ordering/aging prevents starvation but does not preempt a live grant.

## Applied-plan infrastructure leases

An infrastructure lease is a long-lived shared configuration/read lease, not a runtime route grant. Its owner key includes the timetable/plan ID and applied revision; its immutable footprint uses stable rail intervals and object/node IDs. One rail interval always belongs to exactly one rail and uses that rail's canonical AB arc-length coordinates. A range crossing multiple rails expands to one interval per traversed rail in the same atomic footprint. If that traversal uses a turnout, diamond, or other shared conflict node, the footprint also includes its stable node resource; adjacent rail intervals alone do not express the shared conflict. Multiple applied timetables may lease the same resource. Trains do not consume these leases, and leases do not reserve a turnout direction or prevent ordinary runtime arbitration.

Applying a compiled operational plan validates the whole requested footprint against one topology/signalling revision, then atomically records every lease or none. Infrastructure mutation obtains an exclusive edit permit from the same service; it is denied if any applied-plan lease or live route claim overlaps the target. The diagnostic returns each blocking owner and revision. Existing direct guards such as `hasRouteAllocationOn` become adapters to this common edit guard so RailWand, auto-connect, topology migration, protection-region editing, signal deletion, and administrative APIs cannot diverge.

Normal world reload restores applied plans first, revalidates their pinned references, and reconstructs leases before enabling edits or automatic departures. Missing/corrupt references leave the plan unapplied/restrictive with diagnostics; they do not silently drop the lock and continue automation.

## Restriction model

There is one train-protection pipeline. The existing route perception pass discovers route-local objects and vehicles once. World-level interlocking/resource state is computed once per published world state and contributes immutable candidates without repeating that traversal.

Vehicle following, stop boundaries, unconfirmed routes, signal/interlocking limits, line speeds, and station stops are inputs to one constraint arbitration step. The result is one permitted-speed demand and one consolidated authority boundary for diagnostics and external publication. MA is therefore an output/view of the shared constraint state, not a second controller or scanner running beside `FollowConstraint`.

Each externally supplied candidate has a namespaced owner, target train, monotonically increasing revision, observation epoch/topology revision, type, route-relative or stable rail position, and lifecycle. The controller evaluates the most restrictive active result. Unknown position, stale topology, missing owner heartbeat where required, or invalid conversion fails closed according to the documented service result.

The number of constraint candidates is not the primary performance risk. Route traversal, spatial/occupancy queries, and duplicated policy evaluation are. Implementations should reuse the existing scan and indexed world snapshots, then reduce a small candidate set without rescanning.

## Migration

`TimetableDispatcher` becomes a client of the shared route-window resource and restriction mechanisms. Its current request ordering remains the timetable priority policy; the shared allocator provides stable ordering/aging hooks but does not invent cross-client preemption.

The migration removes `PlannedRouteCompiler`'s turnout-derived protection points and replaces `TimetableDispatcher`'s `single()`/protection-distance authority calculations with explicit region-entry distances. Mission activation validates full coverage before dispatch. A missing region is a stable infrastructure denial, not a transient conflict: it is retried only after the region/topology revision changes. If previously valid coverage becomes stale, new grants stop immediately; live grants remain locked until safe tail release unless the topology fault requires the train-protection pipeline to fail closed.

The current split between `TimetableValidator`, direct `TimetableStore.add`, dispatcher startup validation, and dispatcher-specific edit checks becomes an explicit compile/apply seam. SimpleATO owns timetable compilation and applied-plan state; the host lease service owns only stable footprint locking and edit arbitration. Draft saving performs no lease mutation. Apply revalidates revisions and commits the compiled plan plus lease acquisition atomically from the caller's perspective.

## Threading

Physics workers publish immutable observations. Allocation and topology mutation execute in the documented simulation/main owner after publication. Bukkit entity/block/render work uses target-aware platform dispatch. Add-on callbacks must not block the physics worker.

## Compatibility

The new service is additive for external consumers. Existing internal call sites migrate in the same stage so there is only one resource owner. No runtime path is added for arbitrary old development data.
