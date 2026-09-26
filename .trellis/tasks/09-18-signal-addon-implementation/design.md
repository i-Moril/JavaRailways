# Stage 2 design

## Status and ownership

This is a `TARGET` design with two ordered deliverables. The first concrete signal-system module, plugin and product name is `JapaneseSignals`; Stage 1 public APIs must still be completed and reviewed before Stage 2 starts.

```text
JavaRailways Core/Basic/BukkitPlugin
  physical world + generic resource safety + low-level platform tools

SimpleATO + SimpleATOBukkit
  operations + ATO/ATP + inspection scheduling + Bukkit adapter

JapaneseSignals
  one concrete signalling/interlocking model + its platform UI/persistence/customization
```

`JapaneseSignals` is the first reference consumer, not a new universal layer. Other signal plugins can implement route tables, realistic interlocking, different block systems or no protection regions while sharing the same Basic physical safety floor and SimpleATO constraint pipeline.

## Deliverable 1: SimpleATO extraction

`SimpleATO` owns platform-neutral ATO/ATP, operating direction, Destination/Station/Depot/Line/Timetable, rolling-stock assignment, operational route selection, applied timetable state and persistence. Generic pathfinding implementation may remain in Basic, but its operating costs/constraints and selected route belong to SimpleATO.

`SimpleATOBukkit` is separately packaged and owns only SimpleATO platform adaptation: service discovery, Bukkit lifecycle, commands/presentation needed by the operational stack and target-aware scheduling. It does not own protection regions, fixed blocks, national signal aspects or a mandatory JS engine.

`BukkitPlugin` keeps RailWand, manual driving, host world composition and other low-level tools. Each migrated slice moves its contract, implementation, tests and composition together; the old production owner is then removed.

## Deliverable 2: JapaneseSignals

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

SimpleATO's shared perception reports which indexed signal resources are entering inspection range on the frozen route. `JapaneseSignals` evaluates only those resources and their declared dependencies.

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

The plugin owns its signal models, renderer handles, RailObjectWand mode, commands, permissions and optional JS/YAML format. Rendering consumes immutable state snapshots and updates only when the model variant or visible state changes. Bukkit/Folia target ownership and cleanup are handled in the plugin's adapter code.

There is no host-wide `return "YG"` contract. A plugin-local customization API may expose operations such as `setSpeedLimit(...)` and `setModelVariant(...)`, but the exact names, budgets and available observations are frozen in a later review.

## Timetable apply and editing

SimpleATO compiles an automatic timetable into an immutable candidate before service start. It asks every required signal plugin to validate the statically reachable routes against one topology, timetable, train-assignment and plugin-definition revision. Structural faults are accumulated as actionable diagnostics; transient occupancy/live claim conflicts are not apply failures.

After rechecking revisions, SimpleATO atomically publishes the host's explicit rail/node/runtime-resource dependencies and installs the applied plan. Replacement atomically replaces the complete declaration. Unapply enters `DRAINING`, stops new departures/grants and removes dependencies only after live runs/claims clear or explicit administrative recovery succeeds.

Plugin definitions and operational objects referenced by an applied timetable are represented by their owning plugin's stable runtime-resource IDs in the dependency declaration. Editors submit the exact affected rail/node/resource identities to the common host edit permit before mutating topology, regions, blocks, signals or referenced operational objects.

## Persistence and lifecycle

SimpleATO and every signal plugin own separate persistence boundaries. `JapaneseSignals` uses atomic primary/backup writes and fixed current-model fixtures. Decode/migration upcasts at that boundary; runtime code sees only the current model, and writers emit only the current version. Topology migrations are dry-run first and create a recovery point.

At enable/load, the plugin remains restrictive until host capabilities, persisted definitions, topology bindings and derived indexes validate. Missing required host services prevents enable. Plugin disable stops new route decisions and restrictions fail closed; committed grants/release metadata remain host-owned until safe clearance. Renderer and authoring resources clean up idempotently.

## Migration from Basic

Move, rather than duplicate, the current protection-region concepts in reviewable batches:

1. copy no mutable world state; make the code consume public `TopologyState` and Basic resources;
2. establish plugin-owned current model, catalog/index and tests;
3. move authoring/persistence/composition callers;
4. replace Basic protection-window input with plugin-generated route boundary/additions;
5. remove Basic protection definitions, `AuthoritySection` target and `PROTECTION_REQUIRED` policy;
6. retain generic projection, turnout/diamond identity, claim, preparation, cursor/release and suffix extension in Basic.

Development data migration, if required, is a separate auditable one-time tool with dry-run and recovery point—not a permanent runtime compatibility path.

## Failure behavior and complexity

- Region derivation is proportional to the affected topology subgraph during create/update/load, never per tick.
- Route matching is linear in the relevant frozen-route range plus indexed resource hits.
- Dependency invalidation is proportional to affected resources/edges; a cycle or non-stabilizing plugin dependency fails restrictive with diagnostics.
- Basic admission is one physical projection plus additions and one atomic claim.
- Missing indexes, stale revisions, invalid script output, plugin exception or preparation uncertainty cannot widen authority.

## Open decisions

- Exact plugin-local script/YAML surface and execution budget.
- Whether WebUI remains a host sidecar, moves beside SimpleATO, or becomes independently deployable.

These decisions do not block module extraction, protection-region migration or the Java/Kotlin signalling core. The name `JapaneseSignals` does not by itself freeze Java package names, persisted type IDs or script API identifiers.
