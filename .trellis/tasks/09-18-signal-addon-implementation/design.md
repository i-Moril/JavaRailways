# Stage 2 design

## Status and ownership

This is a `TARGET` design with two ordered deliverables. The first concrete signal-system module, plugin and product name is `SimpleSignals`. Stage 1 public APIs are complete and archived; this task consumes them without adding parallel resource, inspection, restriction, applied-plan dependency or unload machinery.

```text
JavaRailways main plugin JAR
  Core + Basic + SimpleATO
    physical world + generic resource safety + operations/ATO/ATP
  BukkitPlugin -> SimpleATOBukkit
    one JavaPlugin lifecycle + embedded SimpleATO platform adapter

SimpleSignals
  independently installed signalling/interlocking model + its UI/persistence/customization
```

`SimpleSignals` is the first reference consumer, not a new universal layer. Other signal plugins can implement route tables, realistic interlocking, different block systems or no protection regions while sharing the same Basic physical safety floor and SimpleATO constraint pipeline.

## Deliverable 1: SimpleATO extraction

`SimpleATO` owns platform-neutral automated operating policy, ATO/ATP constraints, operating direction, Destination/Station/Depot/Line/Timetable, rolling-stock assignment, operational route selection, applied timetable state and persistence. Generic pathfinding implementation, longitudinal dynamics, actuation primitives and physical/resource validation shared with manual driving remain in Basic; operating costs/constraints and the selected route belong to SimpleATO.

`SimpleATOBukkit` is a separate Gradle source/test module but not a separately installed plugin. `BukkitPlugin` directly composes it and includes its classes in the JavaRailways shadow JAR, so there is one `JavaPlugin`, one descriptor and one server-plugin lifecycle. The adapter module owns only platform behavior specifically required by SimpleATO: startup wiring and commands/diagnostics/presentation belonging to the operational stack. It does not own generic host rendering or Folia scheduling, does not provide a presentation runtime to independent signal plugins, does not use Bukkit service discovery to find its own host, and does not own protection regions, fixed blocks, national signal aspects or a mandatory JS engine.

`BukkitPlugin` keeps RailWand, manual driving, the final host world composition and other low-level tools. It calls the adapter module at explicit lifecycle points rather than delegating lifecycle ownership. Each migrated slice moves its contract, implementation, tests and composition together; the old production owner is then removed.

### Operational route restrictions

Basic owns the deterministic search over one immutable `TopologyState` and a physical-neutral restriction contract.
Before search, SimpleATO composes its operating direction with contributions compiled by installed owners. A contribution
may forbid a directed canonical rail span or replace free traversal through several spans with one or more complete,
physically valid paths. Basic validates these values, builds one query-local graph and represents each complete path as
one atomic macro edge. Dijkstra therefore cannot splice the first half of one table route to the second half of another.

The interface contains no region, block, signal, interlocking or resource identity. SimpleSignals may use the same
authored protection-region boundaries for its private route table, but only the neutral spans and complete paths cross
the module boundary. Plugins are invoked only while compiling immutable contributions before search; graph expansion
does not call plugin code. The selected macro expands back to a normal frozen route for Basic's independent physical
validation and later resource admission.

The host exposes owner/world replacement through `RoutePlanningRestrictionSourceService`. It shares the existing signal
source registry, world binding and unified infrastructure-owner unregister gate. SimpleATO captures sources in stable ID
order, invokes each exactly once for a query and combines them with operating direction before calling Basic. Route-planning
callbacks are configuration, not live train restrictions: forced disable removes them without stalling solely for their
presence, while frozen routes, claims and applied-plan dependencies retain their existing safety lifecycle.

## Deliverable 2: SimpleSignals

### Domain model

Conceptual shapes, not frozen public Java names:

- `SignalDefinition`: stable ID, directed rail position, model/style and plugin policy reference.
- `RegionBoundary`: stable ID, canonical rail position, direction and entry/exit role.
- `ProtectionRegion`: stable ID, boundary set and definition revision; its disposable resolved snapshot additionally records the topology revision.
- `FixedBlock`: stable ID, directed extent and plugin-local policy.
- `SignalDependency`: occupancy/grant/section/downstream-signal dependency used for reactive invalidation.
- `SignalState`: restrictive limit, model variant and diagnostics derived for one train/direction.
- `SignalWorldRuntime`: immutable definition/index snapshots plus current dependency graph and render handles.

Stable persisted `kind/type` values represent capabilities; Java/Kotlin class names and `V1/V2` implementation names are not persisted polymorphic identities.

### Protection-region authoring and indexing

On create/update/load, the plugin captures one immutable `TopologyState` root, cuts directed traversal at every declared boundary and flood-fills from the explicitly selected interior side. It rejects an unbounded escape, multiple candidate interiors, invalid facing, missing rail positions and unsupported overlap before publishing the new definition snapshot.

The definition stores boundaries, not a second authoritative subgraph. Derived interior membership, rail-to-region reverse lookup and entry/exit route indexes are disposable caches keyed by `(topologyRevision, signalDefinitionRevision)`. A topology or definition change invalidates and rebuilds affected indexes outside the per-tick path.

The first release treats one region as one throat/interlocking area. It does not model nested/overlapping regions or internal temporary stopping points. If a future signal system needs those features, that plugin may use another model without changing Basic.

### Runtime route decision

SimpleATO's existing shared perception and inspection scheduler report which indexed signal resources are entering inspection range on the frozen route. `SimpleSignals` evaluates only those resources and its declared downstream dependencies; it does not add another train-forward scanner.

For a protection-region entry, the plugin:

1. matches the frozen route to one valid entry, one exit and one continuous interior traversal;
2. proves the requested boundary is far enough for the authoritative train tail to clear the plugin's exit condition;
3. chooses any farther territory required by its own policy—such as a whole following fixed block or an exact moving-block boundary;
4. adds plugin-owned resources such as flank protection, approach locking or block tokens;
5. submits the route, boundary and additions to Basic.

Basic independently derives every traversed rail, turnout and diamond resource over that range. The plugin cannot delete them. A region ID is not automatically claimed as one exclusive token; conflicts come from overlapping physical resources and explicit additions. This allows two disjoint paths within a large throat when the plugin policy and actual conflict resources permit it.

Basic either claims the complete union or none. A confirmed Basic grant is necessary but not alone sufficient for a permissive signal: the plugin may apply a stricter speed/stop, and SimpleATO takes the most restrictive result with following, operational stops and infrastructure limits.

### Fixed blocks and transitions

Fixed blocks are plugin-owned definitions. Entering one normally adds its whole block resource and holds it until the train tail clears the plugin-defined boundary. Protection regions and fixed blocks may implement a common plugin-local read surface—occupancy, length, grant state and downstream limit—but this interface is not a Basic `AuthoritySection`.

When the protection-region exit leads into a fixed block, the plugin can extend the requested authority through that block according to its signal rules. When it leads into moving-block territory, it may choose an exact frozen-route boundary based on SimpleATO braking/following state. The region object does not recursively call its successor; the plugin's route-decision layer sees the ordered route-local observations and constructs one intent.

### Inspection and reactive updates

Stage 2 consumes the reviewed declaration, single-scan, activation and fail-closed contract from
[`operational-routing-and-authority.md`](../../spec/javarailways/simpleato/operational-routing-and-authority.md#34-%E8%B5%84%E6%BA%90%E6%A3%80%E6%9F%A5%E8%B7%9D%E7%A6%BB%E4%B8%8E%E5%93%8D%E5%BA%94%E5%BC%8F%E6%9B%B4%E6%96%B0) rather than defining a second cache model here. The concrete signal plugin compiles
its per-train/route inspection declarations and owns any downstream reverse-dependency propagation; SimpleATO activates matching
declarations through its one shared perception and owns current-speed braking. The plugin does not reevaluate all signals or run
arbitrary JS every tick. Scripts, if the plugin offers them, receive immutable bounded views and return requests/restrictions/model
variants through plugin-controlled APIs; they never mutate the Minecraft world or Basic registry directly.

### Rendering and customization

The plugin owns its signal models, renderer handles, RailObjectWand mode, commands, permissions and optional JS/YAML format. Rendering consumes immutable state snapshots and updates only when the model variant or visible state changes. Bukkit/Folia target ownership, latest-only dispatch and cleanup are handled in the plugin's own adapter code, unless a separately reviewed generic host API is introduced later.

There is no host-wide `return "YG"` contract. A plugin-local customization API may expose operations such as `setSpeedLimit(...)` and `setModelVariant(...)`, but the exact names, budgets and available observations are frozen in a later review.

## Timetable apply and editing

SimpleATO already compiles selected automatic timetable routes into immutable candidates before service start and installs the host's static applied-plan dependencies before clocks become visible. Stage 2 asks every required signal plugin to validate those selected routes against one topology, timetable, train-assignment and plugin-definition revision. Structural faults are accumulated as actionable diagnostics; transient occupancy/live claim conflicts are not apply failures, and the plugin does not repeat pathfinding.

After rechecking revisions, SimpleATO extends the existing atomic declaration of exact rail/node/runtime-resource edit dependencies with referenced SimpleSignals resources and installs the applied plan. Replacement atomically replaces the complete declaration. Unapply enters `DRAINING`, stops new departures/grants and removes dependencies only after live runs/claims clear or explicit administrative recovery succeeds. These non-exclusive edit dependencies are not train resource claims.

Plugin definitions and operational objects referenced by an applied timetable are represented by their owning plugin's stable runtime-resource IDs in the dependency declaration. Editors submit the exact affected rail/node/resource identities to the common host edit permit before mutating topology, regions, blocks, signals or referenced operational objects.

## Persistence and lifecycle

SimpleATO and every signal plugin own separate persistence boundaries. `SimpleSignals` uses atomic primary/backup writes and fixed current-model fixtures. Decode/migration upcasts at that boundary; runtime code sees only the current model, and writers emit only the current version. Topology migrations are dry-run first and create a recovery point.

At enable/load, the plugin remains restrictive until host capabilities, persisted definitions, topology bindings and derived indexes validate. Missing required host services prevents enable. Planned disable first un-applies the plugin's plans, publishes empty restrictions where required and calls the single host owner-unregister operation. That operation succeeds only when no plugin-owned pending request, live claim, referenced type, applied-plan dependency, active inspection binding or non-empty restriction remains. If Bukkit has already forced disable, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds. Renderer and authoring resources clean up idempotently after the safety gate succeeds.

## Migration from Basic

The domain migration has moved the authored definitions, graph cut, catalog/index and route-window
matching into `SimpleSignals`, deleted the unused Basic single-window request/claim path and retained
generic projection, turnout/diamond identity, claim, preparation, cursor/release and suffix extension
in Basic. The remaining reviewable batches are:

1. establish the plugin-owned world runtime and persistence/composition around the current region model;
2. add authoring and diagnostics;
3. connect the plugin-generated route boundary/additions through the generic contributor API.

Development data migration, if required, is a separate auditable one-time tool with dry-run and recovery point—not a permanent runtime compatibility path.

## Failure behavior and complexity

- Region derivation is proportional to the affected topology subgraph during create/update/load, never per tick.
- Route matching is linear in the relevant frozen-route range plus indexed resource hits.
- Dependency invalidation is proportional to affected resources/edges; a cycle or non-stabilizing plugin dependency fails restrictive with diagnostics.
- Basic admission is one physical projection plus additions and one atomic claim.
- Missing indexes, stale revisions, invalid script output, plugin exception, forced owner loss or preparation uncertainty cannot widen authority.

## Open decisions

- Exact plugin-local script/YAML surface and execution budget.
- Whether WebUI remains a host sidecar, moves beside SimpleATO, or becomes independently deployable.

These decisions do not block module extraction, protection-region migration or the Java/Kotlin signalling core. The scaffold now fixes the Java package root as `work.art1st.javarailways.simplesignals`; persisted type IDs and script API identifiers remain separate decisions for their owning domain batches.
