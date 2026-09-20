# Stage 2 design

## Packaging

The first Stage 2 deliverable physically extracts two modules before adding signal-package runtime features:

- `SimpleATO` owns platform-neutral ATO, operating direction, Destination/Station/Depot/Line/Timetable, rolling-stock assignment, operational route planning, and their persistence. Generic pathfinding remains in `Basic`.
- `SimpleATOBukkit` produces the separately deployable Bukkit/Paper plugin JAR and owns Bukkit service lookup/lifecycle, signal and ATO rendering, `RailObjectWand`, commands, permissions, resources, and platform interaction.

`SimpleATOBukkit` has a compile-only host dependency resolved at runtime through Bukkit services. After extraction, remove the corresponding host-owned entry points so there is no parallel implementation. WebUI packaging and the exact signal JavaScript API remain deferred and do not block this module boundary.

## Domain model

- `SignalDefinition`: stable ID, rail position, controlled direction, type/style, and policy references.
- `RegionBoundary`: stable ID, rail position, controlled direction, region ID, and entry/exit capability.
- `ProtectionRegion`: stable ID, validated bounded interior topology, boundary set, and composition group.
- `RegionRoute`: entry, exit, frozen path, complete resource bundle, exit-clearance envelope, and conflict identity.
- `DiamondResourceRef`: stable host resource identity bound to both at-grade crossing traversals without changing graph connectivity.
- `SignalAspect`: restrictive-to-permissive value derived from confirmed state.
- `SignalWorldRuntime`: observations, requests, allocations, authorities, aspects, and renderer handles for one world.

Stable persisted `kind/type` values describe capability; class names and `V1/V2` implementation labels are not persisted polymorphic identifiers.

Player authoring cuts the directed topology at every boundary in a draft region, flood-fills the explicitly selected interior, and rejects unbounded, leaking, or ambiguous results before commit. Protection signals bind to entry boundaries; pure indicators observe state without creating additional authority limits. The route compiler does not add automatic per-turnout stops inside an active region.

Host topology edits detect diamond candidates outside the physics tick and publish stable route-bound conflict resources. Signal consumes the host index while validating a route: each line retains its original edge continuity, no cross-line turn is present, and both passages claim the same diamond resource.

## Runtime flow

For each coherent host observation, the host aggregates rail-indexed occupancy once. Signal updates only regions/resources touched by changed occupancy, evaluates pending route-resource requests, reconciles confirmed allocations, and publishes immutable constraint candidates into the host's single train-protection pipeline. Basic's generic route compiler folds direct resources and contributor obligations along the frozen route until it reaches a complete authority boundary satisfying all tail-clearance obligations. A request is granted only when that complete closure can commit atomically; denial leaves no partial claims and keeps authority outside the entry. A protection region contributes only its own resources and minimum clearance obligation; it does not inspect the next region, and Signal does not special-case region chains. The host combines confirmed candidates with vehicle following, exposes the consolidated authority boundary, and computes one control demand. Signal derives aspects from confirmed state and submits rendering changes only when their value changes. Every derived value records the host epoch/topology revision used to compute it.

## Timetable apply lifecycle

SimpleATO compiles an automatic timetable into an immutable applied-plan candidate before any service may start. Compilation enumerates every statically reachable service route, resolves stable infrastructure references, verifies complete protection/interlocking coverage and required signal-package health, computes exit-clearance/resource footprints, and records topology, signalling, timetable, and package revisions. Structural errors are accumulated and returned together.

Commit is atomic: after rechecking those revisions, SimpleATO acquires the complete shared infrastructure lease set through the host and installs the compiled plan, or installs/acquires nothing. Occupied track and live route conflicts are deliberately excluded from apply validation because they are transient runtime arbitration inputs. Replacing a plan acquires the new lease set and swaps the applied revision before releasing leases no longer referenced, preventing an unlocked gap. Unapply stops new departures/grants, drains or administratively resolves live runs, then releases the plan leases.

Editable timetable persistence and applied-plan persistence are separate boundaries. Saving or validating a `DRAFT` never changes the current applied revision or lease set. Only an explicit apply command may transition the selected draft revision to `APPLIED`. Unapply first records `DRAINING`, preventing new cycles, departures, and route grants; after all live runs/claims are safely released—or an explicit administrative recovery completes—it records `UNAPPLIED` and releases the shared leases.

## Failure behavior

Signal begins restrictive until its world data validates and current host observations arrive. Missing services prevent enable. Invalid world data disables signalling for that world with diagnostics. Disable/unload follows the Stage 1 contract for restrictions and claims and requires explicit administrative recovery when safe automatic cleanup cannot be proven.

## Persistence

Use a Signal-owned atomic primary/backup sidecar. Decode and migration produce only the current in-memory model. Writers emit only the current format. Topology changes are handled by dry-run migration with a recovery point, or rejected when references cannot be mapped safely.

## Rendering and authoring

Renderers consume immutable aspect snapshots and own their spawned Bukkit resources. Authoring edits a draft, validates references/conflicts, shows a dry-run result, and commits atomically to the Signal store before rendering/runtime activation.
