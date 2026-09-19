# Stage 2 design

## Packaging

Create a separate Signal Gradle subproject that produces its own Bukkit/Paper plugin JAR. It has its own descriptor, commands, permissions, resources, configuration, persistence directory, and lifecycle. The host dependency is compile-only and resolved at runtime through Bukkit services.

## Domain model

- `SignalDefinition`: stable ID, rail position, controlled direction, type/style, and policy references.
- `RegionBoundary`: stable ID, rail position, controlled direction, region ID, and entry/exit capability.
- `ProtectionRegion`: stable ID, validated bounded interior topology, boundary set, and composition group.
- `RegionRoute`: entry, exit, frozen path, complete resource bundle, exit-clearance envelope, and conflict identity.
- `DiamondNodeRef`: stable host topology-node identity for an at-grade, fixed-connectivity shared conflict.
- `SignalAspect`: restrictive-to-permissive value derived from confirmed state.
- `SignalWorldRuntime`: observations, requests, allocations, authorities, aspects, and renderer handles for one world.

Stable persisted `kind/type` values describe capability; class names and `V1/V2` implementation labels are not persisted polymorphic identifiers.

Player authoring cuts the directed topology at every boundary in a draft region, flood-fills the explicitly selected interior, and rejects unbounded, leaking, or ambiguous results before commit. Protection signals bind to entry boundaries; pure indicators observe state without creating additional authority limits. The route compiler does not add automatic per-turnout stops inside an active region.

Host topology edits detect diamond candidates outside the physics tick and publish stable fixed-connectivity nodes. Signal consumes those nodes as route conflicts: each line continues through the crossing, no cross-line turn is present, and both passages claim the same diamond resource.

## Runtime flow

For each coherent host observation, the host aggregates rail-indexed occupancy once. Signal updates only regions/resources touched by changed occupancy, evaluates pending entry-to-exit requests, reconciles confirmed allocations, and publishes immutable constraint candidates into the host's single train-protection pipeline. A request is granted only when its entire frozen resource bundle and exit-clearance envelope can commit atomically; denial leaves no partial claims and keeps authority outside the entry. Regions without enough space between them for the complete train are submitted as one composite transaction. Signal does not run another per-train route scan. The host combines those candidates with vehicle following and its existing bounded route-local observations, exposes the consolidated authority boundary, and computes one control demand. Signal derives aspects from confirmed state and submits rendering changes only when their value changes. Every derived value records the host epoch/topology revision used to compute it.

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
