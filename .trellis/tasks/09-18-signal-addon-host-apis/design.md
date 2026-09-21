# Stage 1 design

## Pre-implementation contract freeze

Core版本化铁路图合同见[`versioned-operational-topology.md`](../../spec/javarailways/core/versioned-operational-topology.md)，SimpleATO选路、授权与rolling-extension客户端合同见[`operational-routing-and-authority.md`](../../spec/javarailways/simpleato/operational-routing-and-authority.md)，Basic保护区域、准入及同路线suffix-extension合同见[`protected-route-admission.md`](../../spec/javarailways/basic/protected-route-admission.md)，registry delta append合同见[`resource-allocation.md`](../../spec/javarailways/basic/resource-allocation.md)。后续实现必须先建立唯一`TopologyState`，再满足region graph cut、coherent observation stamp、SimpleATO frozen route、Basic一次路线验证与唯一资源集合、claim→infrastructure preparation→grant顺序、rollback failure Stall和host-owned recovery规则；本task design不再用原则性文字覆盖这些细节。

实现顺序固定为：版本化topology state与旧图adapter → overlay projection与region catalog迁移 → SimpleATO topology-bound frozen route → Basic route validation/claim assembly → atomic admission transaction → tail-release/recovery → Dispatcher与`MANUAL_ROUTED`迁移。前一层合同测试未通过时不得开始后一层迁移。

`SimpleATO` Gradle模块现已作为迁移目标真实建立。第一批只直接迁移无Basic production caller的手动导航parser/factory与timetable JSON dumper，并让Bukkit composition依赖新owner；不增加兼容wrapper，也不移动controller或Dispatcher。由于factory仍消费Basic timetable model和destination API，这只是依赖安全的垂直切片，完整运营依赖闭包仍待后续批次迁移。

## End-to-end working model

```text
Core structural edit
  -> normalize rails into immutable TopologyState(revision)
  -> SimpleATO projects operational overlays and selects a route
  -> SimpleATO emits one frozen route bound to the same revision
  -> Basic validates the route and collects one claim resource set
  -> Basic atomically claims resources and awaits the ordered infrastructure-preparation batch
  -> SimpleATO converts the grant into authority/stop/speed constraints
  -> Core occupancy publication drives Basic tail release
```

三个non-trivial机制各有唯一owner spec：Core[版本化铁路拓扑](../../spec/javarailways/core/versioned-operational-topology.md)定义二度链归一化、rail-slice位置映射和原子publication；SimpleATO[运营选路与行车授权](../../spec/javarailways/simpleato/operational-routing-and-authority.md)定义运营overlay、对Basic `AuthoritySection`观察的消费、冻结路线生成、准入客户端和单一列车保护管线；Basic[保护区域与进路准入](../../spec/javarailways/basic/protected-route-admission.md)定义固定闭塞/保护区域authority partition、boundary graph cut、冻结路线线性验证、route-bound resource catalog、唯一资源集合及尾部释放状态机。本页只说明它们如何串联，不复制算法细节。

## Public service surface

The exact type names may be refined during implementation, but the contract should have three focused capabilities rather than one unrestricted facade:

- `RailwayObservationService`: immutable, epoch-tagged world/train/topology observations after physics publication.
- `RouteResourceService`: validate, atomically claim, confirm, inspect, and release routes containing rail intervals, turnout selections, route-bound crossings, and clearance obligations.
- `TrainRestrictionService`: namespaced, revisioned movement-authority, stop, and speed constraints enforced by the active train protection/controller layer.
- `InfrastructureLeaseService`: shared revisioned configuration leases and mutation guards for infrastructure referenced by applied operational plans.

Bukkit-facing discovery belongs in `bukkit.api.*` and `ServicesManager`. Platform-neutral DTOs and mechanisms belong in `core.api.*` or `basic.api.*`. Service implementations remain internal.

## Topology and resource model

Core/host publishes one immutable `TopologyState(revision)`. Its structural graph contains turnout, terminus, and deterministic loop-anchor nodes; ordinary degree-2 rail chains become edges with lossless rail-slice projection. A diamond does not change connectivity, so it remains a stable shared resource bound to two rail/edge intervals and does not split either route edge. Runtime turnout selection, occupancy, claim and signal state remain separate. Platform, block, protection-region, signal, speed and operating-direction data are overlays over edges/subgraphs. A topology snapshot is only a read-only handle to one published state, not a copied graph with its own lifecycle.

A request includes a stable request ID, owner ID, world ID, coherent observation stamp, train ID, desired authority boundary, and one frozen route plan carrying its topology revision. Every initial admission freezes that route from the authoritative train tail in the same observation; Basic verifies the route start and initializes host-owned progress at zero, so callers provide neither a progress coordinate nor an occurrence index. SimpleATO owns operating route selection and submits only that ordered traversal. Basic resolves the authoritative train envelope from the same observation, validates the route against the same topology revision, collects rail intervals and route-bound conflict resources during that same pass, then folds clearance obligations until a complete safe authority boundary is reached. Turnout occupancy geometry is temporary validation evidence attached to the authoritative binding, not a second resource footprint. Validation completes before mutation. Commit records the one complete resource set before applying all turnout selections, then publishes the grant; a rejection publishes no authority and records no partial ownership.

正常运行不以整份claim replace滚动资源窗口。当前runtime lifecycle只有初次acquire和车尾驱动的prefix release；后续同一冻结路线上的滚动取得将作为“验证suffix后原子追加到既有claim与释放队列”的独立seam设计，当前不冻结接口。SimpleATO若改变route plan，只能在站台等安全运营边界结束旧进路后创建新admission，Basic不在运行中替换或自行重规划路线。底层registry的通用claim replacement与配置lease replacement保留为独立机制，不等于受保护进路的正常生命周期。

Protection stop location belongs to the route-window entry. The host no longer derives competing authority limits for each turnout inside the window. When no independent protection region exists, the host must not silently recreate the removed per-turnout stop policy; the caller receives an explicit unavailable/unprotected result according to the final operating contract.

Automatic admission is capability-shaped rather than plugin-presence-shaped. `RouteResourceService` accepts a topology-bound frozen-route request, not a region-specific special operation. There is no caller-authored footprint beside that route and no automatic overload equivalent to today's `allocate(owner, plannedRoute, sectionIndex)`. Before mutation, the service proves that the traversal is continuous and legal, collects every controlled node and route-bound conflict resource from host indexes into one resource set, and rejects absent or partial protection coverage with `PROTECTION_REQUIRED`, including when a required turnout is already aligned. Basic never searches for an alternative route on denial.

Route compilation no longer inserts boundaries from each `TurnoutRequirement.protectionDistanceMeters`. It partitions automatic authority at explicit `AuthoritySection` boundaries and rejects automatic activation when a turnout, diamond, or other controlled resource lacks valid protection coverage. A route with no controlled resource or contribution remains valid without a protection region.

Turnout mutation is centralized behind the host service. The public read model may expose selected connectivity, but automatic callers cannot directly call a mutable `selectNextRail` path. A distinct manual operation checks physical train occupancy and active route claims before applying an operator-selected branch; it creates no route grant or movement authority. `MANUAL_ROUTED` remains automatic route assistance and therefore requires the same protected window as fully automatic operation, whereas `FULL_MANUAL` creates no new route request.

A diamond resource binds anchors on two otherwise unchanged route edges to one exclusive conflict identity. The graph contains no cross-line continuation and no selectable diamond branch. Basic's world-local route-bound resource catalog publishes complete snapshots bound to an exact `TopologyState` root; unavailable or stale snapshots reject admission instead of behaving like empty catalogs. Detection runs in topology-edit transactions: spatial lookup produces candidates, exact geometry and vertical-clearance checks distinguish an at-grade conflict from non-conflicting or grade-separated geometry, and the committed binding advances topology revision.

Release uses tail progress projected onto the same frozen route. Validation compiles one ordered entry per claimed resource at that resource's final claimed/conflict extent; a resource becomes eligible only when the tail reaches that fully compiled clear boundary, and only a contiguous queue prefix may shrink. The pure queue inspection does not pop entries: the grant owner drops the prefix only after atomic registry shrink succeeds. Tail-progress monotonicity, explicit clearance-margin source, administrative cancellation, and plugin-disable behavior remain separate grant-lifecycle work.

No request may hold one region while waiting for a resource omitted from its original claim set. During the same route-validation pass, each protection-region traversal adds its selected-path resources and extends the minimum claim boundary beyond its exit using the authoritative train envelope. Validation continues through every later contributor reached before all obligations are satisfied; a region never queries its successor and SimpleATO never assembles a region-specific composite request. A cycle, route end, or inaccessible complete authority boundary fails before any claim. Deterministic request ordering/aging prevents starvation but does not preempt a live grant.

## Applied-plan infrastructure leases

An infrastructure lease is a long-lived shared configuration/read lease, not a runtime route grant. Its owner key includes the timetable/plan ID and applied revision; its immutable footprint uses stable rail intervals and object/resource IDs. One rail interval always belongs to exactly one rail and uses that rail's canonical AB arc-length coordinates. A range crossing multiple rails expands to one interval per traversed rail in the same atomic footprint. If that traversal uses a turnout, diamond, or other shared conflict resource, the footprint also includes its stable resource ID; adjacent rail intervals alone do not express the shared conflict. Multiple applied timetables may lease the same resource. Trains do not consume these leases, and leases do not reserve a turnout direction or prevent ordinary runtime arbitration.

Applying a compiled operational plan validates the whole requested footprint against one topology/signalling revision, then atomically records every lease or none. Infrastructure mutation obtains an exclusive edit permit from the same service; it is denied if any applied-plan lease or live route claim overlaps the target. The diagnostic returns each blocking owner and revision. Existing direct guards such as `hasRouteAllocationOn` become adapters to this common edit guard so RailWand, auto-connect, topology migration, protection-region editing, signal deletion, and administrative APIs cannot diverge.

Normal world reload restores applied plans first, revalidates their pinned references, and reconstructs leases before enabling edits or automatic departures. Missing/corrupt references leave the plan unapplied/restrictive with diagnostics; they do not silently drop the lock and continue automation.

## Restriction model

There is one train-protection pipeline. The existing route perception pass discovers route-local objects and vehicles once. World-level interlocking/resource state is computed once per published world state and contributes immutable candidates without repeating that traversal.

Vehicle following, stop boundaries, unconfirmed routes, signal/interlocking limits, line speeds, and station stops are inputs to one constraint arbitration step. The result is one permitted-speed demand and one consolidated authority boundary for diagnostics and external publication. MA is therefore an output/view of the shared constraint state, not a second controller or scanner running beside `FollowConstraint`.

Each externally supplied candidate has a namespaced owner, target train, monotonically increasing revision, observation epoch/topology revision, type, route-relative or stable rail position, and lifecycle. The controller evaluates the most restrictive active result. Unknown position, stale topology, missing owner heartbeat where required, or invalid conversion fails closed according to the documented service result.

The number of constraint candidates is not the primary performance risk. Route traversal, spatial/occupancy queries, and duplicated policy evaluation are. Implementations should reuse the existing scan and indexed world snapshots, then reduce a small candidate set without rescanning.

## Migration

First introduce `TopologyState` beside the current Core edit model. Structural transactions publish both through one revision boundary while consumers migrate; the legacy `RailView.nextRails` adapter is then internalized so it cannot remain a second mutable authority. Protection-region graph caches become disposable derivations keyed by topology/region revision. Only after route and region consumers use the shared state may the duplicated protection adjacency be removed.

Route selection and operating constraints move with Destination, Station, Depot, Line, Timetable and service planning into SimpleATO. A reusable platform-neutral shortest-path implementation may temporarily remain in a shared package, but its inputs are `TopologyState` and overlays; it does not own policy or resource claims. SimpleATO submits one topology-bound frozen route; Basic validates that traversal and collects the one canonical resource set used for enforcement.

`TimetableDispatcher` becomes a client of the shared route-window resource and restriction mechanisms. Its current request ordering remains the timetable priority policy; the shared allocator provides stable ordering/aging hooks but does not invent cross-client preemption.

The migration removes `PlannedRouteCompiler`'s turnout-derived protection points and replaces `TimetableDispatcher`'s `single()`/protection-distance authority calculations with explicit region-entry distances. Mission activation validates full coverage before dispatch. A missing region is a stable infrastructure denial, not a transient conflict: it is retried only after the region/topology revision changes. If previously valid coverage becomes stale, new grants stop immediately; live grants remain locked until safe tail release unless the topology fault requires the train-protection pipeline to fail closed.

The current split between `TimetableValidator`, direct `TimetableStore.add`, dispatcher startup validation, and dispatcher-specific edit checks becomes an explicit compile/apply seam. SimpleATO owns timetable compilation and applied-plan state; the host lease service owns only stable footprint locking and edit arbitration. Draft saving performs no lease mutation. Apply revalidates revisions and commits the compiled plan plus lease acquisition atomically from the caller's perspective.

## Threading

Physics workers publish immutable observations. Allocation and topology mutation execute in the documented simulation/main owner after publication. Bukkit entity/block/render work uses target-aware platform dispatch. Add-on callbacks must not block the physics worker.

## Compatibility

The new service is additive for external consumers. Existing internal call sites migrate in the same stage so there is only one resource owner. No runtime path is added for arbitrary old development data.
