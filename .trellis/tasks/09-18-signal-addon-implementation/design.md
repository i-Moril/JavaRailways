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
- `FixedBlock`: stable ID/revision and one complete ordered directed physical traversal; its length,
  occupancy geometry and route-local reverse index are derived against one exact topology root.
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

The current runtime slice implements the bounded route-local part of that decision: one stable SimpleSignals inspection
source captures immutable region and fixed-block indexes from the same publication, matches the exact host-selected frozen
route and compiles every complete region window into an entry-triggered minimum authority end. The end is normally the
region exit; when exactly one fixed block begins at that same route coordinate it becomes the block exit. Multiple immediate
successors are rejected rather than guessed, and the binding does not recursively follow later blocks. This prevents one
Basic request from ending inside the region or its unique immediate fixed-block successor while Basic still derives and
claims the physical route prefix. It deliberately does not claim the region identity or yet choose a moving-block boundary.

Basic either claims the complete union or none. A confirmed Basic grant is necessary but not alone sufficient for a permissive signal: the plugin may apply a stricter speed/stop, and SimpleATO takes the most restrictive result with following, operational stops and infrastructure limits.

### Fixed blocks and transitions

Fixed blocks are plugin-owned definitions. Entering one normally adds its whole block resource and holds it until the train tail clears the plugin-defined boundary. Protection regions and fixed blocks may implement a common plugin-local read surface—occupancy, length, grant state and downstream limit—but this interface is not a Basic `AuthoritySection`.

The current foundation compiles each block only on load/authoring. It validates the ordered traversal against one exact
`TopologyState`, derives whole-block Basic occupancy geometry with directed entry/exit boundaries, sums its physical length,
and publishes a rail-to-block reverse index together with the other world definitions. The sidecar adds `fixedBlocks` to
schema 1 with an explicit empty default, so this backward-readable additive field does not create a new schema version.
Protection-region observation remains a later batch.

Fixed-block route admission now uses one plugin-owned `simplesignals:fixed-block` resource type and the existing host
provider/contributor seam. On each new or extended admission, SimpleSignals projects the frozen route once into route metres plus
canonical AB coordinates, discovers only blocks indexed by rails on that route, and matches the requested authority prefix against
each complete directed block traversal. Authority ending at the entry contributes nothing; authority entering but not reaching the
exit is rejected; complete traversal contributes one stable block resource held until the tail passes the route-local exit. More than
one complete occurrence of the same block in one authority prefix is rejected as an ambiguous route. Basic independently adds the physical rail,
turnout and diamond floor and atomically claims the union. This does not yet choose a farther protection-region transition boundary or install
concrete signal-system rules.

The first common live section slice now covers fixed-block occupancy only. On world installation,
SimpleSignals groups every compiled block area into one host `SignalEpochSource`; Basic derives the
baseline and changed occupants plus directed ENTER/LEFT crossings in the coherent physics epoch.
The plugin maps changed area IDs to immutable section observations containing the block identity,
definition revision, compiled length and conservative occupants. Before the first baseline the
snapshot is unknown, and an unchanged epoch does not invoke plugin code or replace the snapshot.
Injected fixed-block rules can now map this state plus downstream outputs to an opaque model variant and speed; changed outputs invalidate only
the same source's current route declarations, whose complete active set produces route-position speed restrictions through SimpleATO's existing
most-restrictive pipeline. Production still installs no concrete rule bindings. Protection regions join the same private read
surface only when concrete interlocking rules need them, because a branching region has no useful
single physical length.

Live fixed-block geometry editing now uses the host's low-frequency atomic source replacement. The
plugin compiles the full candidate first and acquires the existing edit permit over old plus new
affected rails. Inside one simulation barrier the host prevalidates the complete source union, then a
SimpleSignals callback rechecks the exact topology root, saves the sidecar and swaps one prebuilt runtime-state reference containing both the
definition publication and section occupancy runtime; the host finally installs the already compiled
area state. Host prevalidation never invokes the callback, while callback failure preserves the old
source and publication. Deleting the final block installs an empty epoch-source set without removing
the plugin's route-planning registration. This restores runtime authoring on non-operational rails
without a rollback state machine, placeholder area or persistence compatibility path.

Fixed blocks and protection regions are alternative section boundaries in this model: after all candidate catalogs compile,
the world runtime rejects any positive-length overlap between a block occupancy interval and a derived protection-region
interior before its single publication swap. A shared cut point is allowed. This is a low-frequency cross-catalog validation;
it is never repeated per tick. The foundation deliberately leaves fixed-block-to-fixed-block overlap unspecified until the
semantics of opposite-direction definitions are frozen.

When the protection-region exit coincides with exactly one fixed-block entry, the inspection compiler extends the same
requested authority through that block. The block's ordinary resource contributor then adds its token from the expanded
prefix, so physical route resources and the block token remain one atomic Basic request. When the exit leads into
moving-block territory, later plugin policy may choose an exact frozen-route boundary based on SimpleATO braking/following
state. The region object stores no successor and does not recursively call one; this is a route-local join performed only
while binding the frozen route.

### Inspection and reactive updates

Stage 2 consumes the reviewed declaration, single-scan, activation and fail-closed contract from
[`operational-routing-and-authority.md`](../../spec/javarailways/simpleato/operational-routing-and-authority.md#34-%E8%B5%84%E6%BA%90%E6%A3%80%E6%9F%A5%E8%B7%9D%E7%A6%BB%E4%B8%8E%E5%93%8D%E5%BA%94%E5%BC%8F%E6%9B%B4%E6%96%B0) rather than defining a second cache model here. The concrete signal plugin compiles
its per-train/route inspection declarations and owns any downstream reverse-dependency propagation; SimpleATO activates matching
declarations through its one shared perception and owns current-speed braking. The plugin does not reevaluate all signals or run
arbitrary JS every tick. Scripts, if the plugin offers them, receive immutable bounded views and return requests/restrictions/model
variants through plugin-controlled APIs; they never mutate the Minecraft world or Basic registry directly.

The reviewed propagation direction is a directed provider-to-consumer invalidation chain, not a fixed-point solver. Occupancy,
the currently active turnout continuation and changed downstream section output may enqueue only registered upstream consumers;
consumers never enqueue their providers. Each node runs at most once in one wave and an unchanged output ends propagation on
that branch. Core remains the authority for live directed active continuations. The immutable same-physics-epoch world frame now
exposes `entry endpoint -> selected exit endpoint?`; Basic projects that snapshot unchanged and any consumer may read it. Core
reuses the immutable value while topology/selection revisions remain unchanged and rejects publication if a selection changes
during epoch processing. This completes the prerequisite for turnout-aware dependency selection without exposing mutable `RailView`.

### Rendering and customization

The plugin owns its signal models, renderer handles, RailObjectWand mode, commands, permissions and optional JS/YAML format. Rendering consumes immutable state snapshots and updates only when the model variant or visible state changes. Bukkit/Folia target ownership, latest-only dispatch and cleanup are handled in the plugin's own adapter code, unless a separately reviewed generic host API is introduced later.

There is no host-wide `return "YG"` contract and the Java/Kotlin runtime does not contain a fixed aspect/speed table. It accepts a validated non-negative finite speed limit plus an opaque non-blank model variant selected by the installed rule. A plugin-local customization API may expose operations such as `setSpeedLimit(...)` and `setModelVariant(...)`, but the exact names, budgets and available observations are frozen in a later review. Rule failure publishes an unavailable state whose host safety limit is zero without inventing a model variant.

## Timetable apply and editing

SimpleATO already compiles selected automatic timetable routes into immutable candidates before service start and installs the host's static applied-plan dependencies before clocks become visible. Stage 2 asks every required signal plugin to validate those selected routes against one topology, timetable, train-assignment and plugin-definition revision. Structural faults are accumulated as actionable diagnostics; transient occupancy/live claim conflicts are not apply failures, and the plugin does not repeat pathfinding.

After rechecking revisions, SimpleATO extends the existing atomic declaration of exact rail/node/runtime-resource edit dependencies with referenced SimpleSignals resources and installs the applied plan. Replacement atomically replaces the complete declaration. Unapply enters `DRAINING`, stops new departures/grants and removes dependencies only after live runs/claims clear or explicit administrative recovery succeeds. These non-exclusive edit dependencies are not train resource claims.

Plugin definitions and operational objects referenced by an applied timetable are represented by their owning plugin's stable runtime-resource IDs in the dependency declaration. Editors submit the exact affected rail/node/resource identities to the common host edit permit before mutating topology, regions, blocks, signals or referenced operational objects.

SimpleSignals definition authoring uses one plugin-local patch transaction rather than exposing mutable catalogs. A patch checks an expected
world-definition revision, creates one complete revision+1 candidate, and rejects empty/redundant/conflicting operations before host coordination.
The runtime prepares fresh region, fixed-route and fixed-block catalogs against one exact immutable topology without changing the live source. The prepared
value retains its base publication identity and old/new exact rail impacts, including boundaries, resolved interiors and route traversals.

The manager acquires the existing host edit permit for the union of old and new rail impacts and rechecks the exact topology root. Before entering
the publication callback, its worker encodes the complete sidecar, validates/copies the old primary and writes same-directory staging files. The
callback rechecks the base publication and topology, atomically renames the staged primary, then swaps the already-built plugin and host source
references. Backup rotation and staging cleanup happen after publication. Rejected patches, catalog validation, permit conflicts/staleness and
persistence failures leave the old source unchanged; no second lock service, topology-wide lease or authoring token exists.

Online editing has one cross-module latency rule. Preparation may perform work proportional to definition or topology size, but it runs on an
asynchronous worker. Final commit is scoped to the affected world and may only perform stale/permit checks, an already-staged atomic rename and
immutable reference swaps. It must not hold the cross-world simulation lock, rebuild a graph/index, encode a document, perform an ordinary file
write, wait for a future or invoke Bukkit side effects. An in-progress signal epoch therefore finishes with its captured old immutable source and
the next epoch establishes the new geometry baseline. Rail editing follows the same rule for curves, exact diamond geometry, normalized topology,
whole-world undo values and the rail-render sidecar. The worker stages the complete new render file and an old-primary recovery copy under one
per-world writer slot. The owner transaction only checks the captured file identity and renames the staged file; a later transaction failure restores
the old primary before releasing the infrastructure edit permit. A queued edit whose captured render records are no longer current fails stale rather
than overwriting a disjoint edit, while other worlds and physics continue independently.

## Persistence and lifecycle

SimpleATO and every signal plugin own separate persistence boundaries. `SimpleSignals` now stores schema-version-1 world definitions in
`<dataFolder>/worlds/<world UUID>-definitions.json`, with a same-directory `.bak`. It persists only authored region boundaries, complete
fixed-route traversals and fixed-block traversals using namespaced IDs, canonical UUIDs/AB metres and `ViewDirection`; derived interiors,
occupancy geometry, indexes and compiled restrictions are rebuilt.
The strict current codec rejects unknown fields, versions and wrong world identity. Missing files start as an unwritten empty revision 0;
an invalid primary may be read from a valid backup without overwriting either input, while no valid candidate leaves the world unavailable.
Startup publishes no usable route source until decode, topology binding and complete compilation have all succeeded. A corrupt/orphaned
candidate installs the same stable source ID in fail-closed form so new operational route searches are rejected instead of treating the
plugin as unrestricted; it does not publish a usable local runtime. Runtime code sees only the current domain model and the writer emits only
schema version 1 through same-directory temp/backup/atomic replace. No old development format reader
or migration branch exists; a future topology migration remains a separate dry-run-first tool with a recovery point.

At enable/load, the plugin remains restrictive until host capabilities, persisted definitions, topology bindings and derived indexes validate. Missing required host services prevents enable. Planned disable first un-applies the plugin's plans, publishes empty restrictions where required and calls the single host owner-unregister operation. That operation succeeds only when no plugin-owned pending request, live claim, referenced type, applied-plan dependency, active inspection binding or non-empty restriction remains. If Bukkit has already forced disable, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds. Renderer and authoring resources clean up idempotently after the safety gate succeeds.

## Migration from Basic

The domain migration has moved the authored definitions, graph cut, catalog/index and route-window
matching into `SimpleSignals`, deleted the unused Basic single-window request/claim path and retained
generic projection, turnout/diamond identity, claim, preparation, cursor/release and suffix extension
in Basic. The planning-only plugin world runtime now loads its own strict sidecar, publishes candidate region/table pairs atomically and
registers either one compiled fixed-route source or one rejecting fail-closed source for each available host world load. The remaining
reviewable batches are:

1. connect the completed plugin-local authoring transaction to Wand/commands and user-facing diagnostics;
2. connect the plugin-generated route boundary/additions through the generic contributor API.

Development data migration, if required, is a separate auditable one-time tool with dry-run and recovery point—not a permanent runtime compatibility path.

## Failure behavior and complexity

- Region derivation is proportional to the affected topology subgraph during create/update/load, never per tick.
- Route matching is linear in the relevant frozen-route range plus indexed resource hits.
- Dependency invalidation is proportional to the affected one-way provider-to-consumer chain; construction must reject a dependency that would point downstream again rather than trying to settle a cycle at runtime.
- Basic admission is one physical projection plus additions and one atomic claim.
- Missing indexes, stale revisions, invalid script output, plugin exception, forced owner loss or preparation uncertainty cannot widen authority.

## Open decisions

- Exact plugin-local script/YAML surface and execution budget.
- Whether WebUI remains a host sidecar, moves beside SimpleATO, or becomes independently deployable.

These decisions do not block module extraction, protection-region migration or the Java/Kotlin signalling core. The scaffold now fixes the Java package root as `work.art1st.javarailways.simplesignals`; persisted type IDs and script API identifiers remain separate decisions for their owning domain batches.
