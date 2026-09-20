# Stage 1: Signal add-on host APIs

## Goal

Improve JavaRailways so an independently compiled Signal plugin can observe consistent railway state, arbitrate routes with the timetable, and submit enforceable train restrictions through documented public APIs.

## Confirmed facts

- Train head, tail, speed, and occupied rail intervals are already public Core API.
- `SimpleATO` already combines movement-authority, following, stop, and speed constraints.
- `RouteSectionAllocator` is currently instantiated privately by `TimetableDispatcher`, locks only turnout source views, and cannot be shared with an add-on.
- Route release currently derives protected intervals from turnout requirements and assumes a single turnout in several paths.
- Current approach authority is derived from each turnout's default protection distance; explicit route-section protection points do not currently drive the runtime authority boundary.
- Different rail IDs that cross geometrically do not currently share an occupancy/resource identity.
- Physical connectivity currently exists as mutable `RailView.nextRails`; protection code can copy it into another adjacency snapshot, but there is not yet one normalized, immutable topology state shared by routing and validation.
- Existing Bukkit add-ons obtain `bukkit.api.*` services from `ServicesManager` and use `compileOnly(project(":BukkitPlugin"))`.

## Requirements

- Define immutable observation DTOs containing world identity, physics epoch/topology revision, rail topology references, train identity, speed, direction, and occupied intervals needed by signalling.
- Expose an owner-aware callback or subscription after a coherent physics state has been published; callbacks must document thread context and may not expose mutable Core objects across threads.
- Replace the timetable-private turnout allocator with one world-local resource-arbitration owner used by all route consumers.
- Support atomic claims over explicit rail intervals, turnout selections, route-bound crossing resources, and exit-clearance resources, with stable owner/request/revision identities.
- Introduce one authoritative, immutable, revisioned Core `TopologyState`. Normalize ordinary degree-2 chains into edges and retain stable turnout, terminus, and deterministic loop-anchor nodes; diamonds remain route-bound resources because they do not change connectivity. A snapshot is a read-only reference to one published version, not a second independently maintained graph.
- Treat platforms, ordinary blocks, protection regions, signal boundaries, speed limits, and operating direction as overlays referencing topology edges/subgraphs rather than structural connectivity nodes. Basic owns ordinary-block and protection-region definitions in one non-overlapping authority partition; SimpleATO consumes their common snapshots instead of maintaining a second section store.
- Introduce a generic direction-aware region-boundary and route-window contract. SimpleATO selects one frozen path bound to one topology revision. Basic proves that path is continuous and legal, collects one canonical claim resource set from the same topology state, validates physical occupancy against authoritative resource geometry, and either commits the complete set or commits nothing. The route is the caller's only description of what it selected; there is no second caller-authored resource list.
- Remove the timetable/runtime dependency on per-turnout protection distances and `single()` turnout assumptions. A route window's entry boundary is the sole unconfirmed-route authority limit; internal resources do not independently generate stop points.
- Represent an at-grade diamond crossing as one stable shared conflict resource anchored to both route traversals, without creating a topology node or cross-line continuation. Placement/topology-update code must use spatial candidate lookup plus exact geometry/clearance checks to create, update, reject, or remove that binding.
- Reject stale topology, occupied resources, incompatible turnout selections, conflicting owners, and invalid references without partial mutation.
- Generalize occupancy handoff and tail release so zero-, one-, and multi-turnout sections are valid.
- Expose an add-on-facing train-restriction service for movement authority, stop targets, and speed restrictions with namespaced owners and revisions.
- Reuse one route perception pass per controlled train. Vehicle, signal, route-end, turnout/interlocking, and external-provider inputs contribute immutable constraint candidates to one arbitration step; they must not trigger parallel full-route scans.
- Compute world occupancy once per published epoch using rail-indexed intervals; route/resource recomputation should be driven by occupancy, request, release, or topology transitions rather than repeated unchanged full-world work.
- Compose restrictions using the most restrictive result and define behavior for stale updates, owner removal, plugin disable, world unload, train removal, and ATO stall.
- Publish the required services through Bukkit `ServicesManager`; service interfaces and value/result types live under `bukkit.api.*` or another documented public API package.
- Migrate `TimetableDispatcher` to the shared service and retain existing route priority, turnout safety, authority, and tail-release behavior.
- Preserve the physical invariant that a turnout cannot change beneath a train, but replace fixed per-turnout approach/release policy with region-window admission and tail-clear/exit-clear release.
- Remove, internalize, or clearly deprecate competing signal registries only where necessary to make the public ownership unambiguous.
- Do not expose a raw automatic allocation operation that accepts only an unversioned planned route/section. Any automatic or `MANUAL_ROUTED` request that selects or claims a turnout, diamond, or other controlled resource must carry a frozen, topology-revision-matched route plan. Basic validates and projects that route into protection traversals and route-bound resources, folds every contributor obligation to a safe authority boundary, and returns typed denial without changing a turnout or claim when the route or protection coverage is invalid.
- Remove automatic per-turnout protection-point synthesis from route compilation. Routes with controlled resources but no complete explicit protection coverage are invalid for automatic activation; routes without controlled resources remain valid.
- Separate explicit manual turnout selection from automatic route allocation. Manual selection may proceed only when physical occupancy and active claims permit it, does not create movement authority, and cannot bypass an existing protected-route lock.
- Expose a world-local shared infrastructure-lease service. One revisioned owner can atomically acquire/release a set of stable rail intervals, topology nodes, turnout/diamond resources, protection/signal references, and referenced operational objects. Leases are compatible with other read/configuration leases but block mutation or deletion of any covered resource.
- Route every topology/infrastructure editing path through a common edit guard that reports both active runtime claims and applied-timetable leases. A failed edit performs no partial topology or persistence mutation and identifies the owning timetable/revision.

## Acceptance criteria

- [ ] A compile-only fixture plugin can consume every required contract using public packages only.
- [ ] Route planning, protection-region resolution, and resource validation consume the same published `TopologyState(revision)`; no copied adjacency remains an independently mutable authority.
- [ ] Degree-2 chains, turnouts, termini, and pure loops normalize deterministically; diamonds do not split graph edges, and rail-slice projection/resource anchors preserve canonical AB coordinates for occupancy and editing.
- [ ] A disconnected, illegal, incomplete, or stale frozen route is rejected without Basic choosing an alternative path or mutating any claim/selection; Basic validates the caller-selected route and collects every claim resource from authoritative bindings in that same pass.
- [ ] Timetable and a simulated external owner cannot acquire conflicting rail/turnout resources.
- [ ] Allocation is atomic and topology-revision aware; a failed request leaves no changed turnout or leaked claim.
- [ ] Straight, zero-turnout sections and multi-turnout sections allocate and release without `single()` assumptions.
- [ ] A denied route-window request holds no subset of its resources; an admitted train has a clear exit envelope large enough to leave the region completely.
- [ ] When one region's tail-clearance obligation reaches another region, runtime compilation includes both contributors in one atomic closure before entry; neither region inspects its successor and no caller submits a hand-built composite window.
- [ ] A protection-region ID is not used as one exclusive whole-region lock; two paths with disjoint rail intervals and conflict nodes can be admitted concurrently, while overlapping paths cannot.
- [ ] Diamond-resource tests prove immutable same-line connectivity, no cross-line turn, shared conflict identity, placement/update detection, topology-revision invalidation, and no per-tick geometry scan.
- [ ] A claim remains held until the owning train has occupied and then fully cleared its protected intervals.
- [ ] Restriction updates are namespaced, revisioned, most-restrictive, and cannot silently expand authority after a stale update or owner failure.
- [ ] Profiling/tests show one route perception pass per controlled train per physics step; adding Signal does not add a second per-train route traversal.
- [ ] Occupancy/resource processing is rail-indexed and avoids `allTrains × allRegions`; unchanged state does not produce repeated rendering or unbounded scheduler submissions.
- [ ] Service registration, lookup, world unload, plugin disable, and cleanup/fail-safe behavior have focused tests.
- [ ] Existing Core, Basic, BukkitPlugin, timetable, and ATO tests pass.
- [ ] Public API documentation states units, direction basis, snapshot/live semantics, thread ownership, lifecycle, and failure results.
- [ ] An automatic or `MANUAL_ROUTED` request across an uncovered or partially covered turnout/diamond returns `PROTECTION_REQUIRED`, leaves every selection/claim unchanged even when the turnout is already aligned, and does not retry every tick while the relevant protection/topology revision is unchanged.
- [ ] A route with no controlled resource remains usable without SimpleATO; explicit manual turnout selection remains available only through the physical-lock/active-claim checked manual operation and grants no automatic authority.
- [ ] Shared infrastructure leases can overlap across timetables, coexist with runtime route arbitration, block every covered edit path, survive/recover according to the applied-plan lifecycle, and are acquired/released atomically with no partial lease set.

## Out of scope

- Signal aspects, protection-region policy, signal models, Signal commands/tools, and Signal persistence.
- Player-facing region-boundary authoring and diamond diagnostics; Stage 1 supplies only topology/resource contracts and validation primitives.
- A JavaRailways-owned national signalling rule set.
- WebUI Signal editing.

## Dependency and handoff

- This stage has no dependency on the Stage 2 plugin.
- Completion must provide a short consumer example and API contract notes for `09-18-signal-addon-implementation`.

## Agreed absence behavior

- When SimpleATO is absent, no component requests automatic protected routes. Basic/Bukkit may expose explicit manual turnout operation only through the shared physical-lock/claim guard.
- When SimpleATO is present but a route lacks complete protection-region coverage, automatic mission activation/route assistance reports `PROTECTION_REQUIRED`; the host neither changes the turnout nor invents a stop boundary around it.
