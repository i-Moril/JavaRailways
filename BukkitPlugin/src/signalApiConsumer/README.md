# External signal add-on API consumer

`SignalApiConsumerPlugin.java` is a compile-only proof that an independently packaged Bukkit
plugin can use the current JavaRailways host APIs without importing implementation packages or
shadowing host classes. The fixture is compiled by:

```text
./gradlew :BukkitPlugin:compileSignalApiConsumerJava
```

The APIs are currently experimental (`1.0-SNAPSHOT`). This guide describes the current source
contract; it is not a binary-compatibility promise.

## Dependency and service discovery

An add-on compiles against the JavaRailways API artifacts as `compileOnly` dependencies and declares
JavaRailways as a Bukkit/Paper plugin dependency. It must not include JavaRailways, Core, Basic or
SimpleATO classes in its own JAR.

At enable time, load required platform services from Bukkit's `ServicesManager` and fail immediately
when a required service is absent. The fixture currently proves these services:

- `RailwayObservationService`: immutable coherent world/train observations and subscriptions;
- `InfrastructureResourceService`: the exact immutable topology root, custom resources, route
  contributions, preparation, route admission, infrastructure edit dependencies and the unified
  owner lifecycle;
- `SignalEpochSourceService`: complete nonempty signal-source sets installed per plugin and world;
- `InspectionSourceService`: host-route-bound inspection providers plus authenticated dirty
  declaration publication;
- `RoutePlanningRestrictionSourceService`: owner/world-scoped route-table and traversal restrictions
  compiled once before immutable-topology search;
- `RailTargetService` and `PlayerOperationService`: lower-level Bukkit interaction capabilities;
- `ModelPresentationService`: ordinary model creation, latest-state submission and asynchronous cleanup.

The returned services are host-owned. Do not retain a world-bound admission service across world
unload/reload and do not assume that a reused world UUID revives an old binding.

The fixture installs SimpleATO's platform-neutral `InspectionSource`. Its binding provider receives
the exact host-selected train and `RouteSnapshot`; add-ons must not reconstruct that route from a
separate observation. The provider returns one complete immutable `InspectionDeclarationBinding`.
SimpleATO owns activation and invokes the handler only when the complete active declaration set
changes or one active declaration is invalidated, using its existing perception scan and dynamic
braking lookahead. Every callback receives the complete active set and must publish one complete
snapshot; a newly compiled binding also receives a baseline callback, possibly with an empty set.

## Rail geometry lookup

`RailTargetService.findCurve(worldId, railId)` returns the existing immutable `PrimeCurve` without
exposing a live world or rail object. It can be called off the Bukkit owning thread. A missing rail
returns an empty `Optional`; an unregistered, unloading or stopped world throws `IllegalStateException`.

Arc positions are metres from the rail's A endpoint toward B. Sample with `curve.getPointAt(s)`;
BA travel reverses the facing direction, not the curve's stored coordinates. The fixture's `sampleRail`
method demonstrates this public lookup and range checks. Lookup uses two ID maps and the Core world's
local topology read lock; it does not scan rails, search for a nearest point or perform pathfinding.

A retained curve remains readable after rail deletion or world unload, but does not prove that the
rail still exists or authorizes movement. A lookup already in progress during unload may return that
old immutable curve. Use the public model presentation service to display a model; the internal
ordinary-model builder is not an add-on API.

## Ordinary model definitions

The `api.model` package exposes immutable `ModelDefinition`, `ModelPart.Group`/`Item`, `ModelPose`,
`ModelScale`, `ItemAppearance` and `ModelAnimation` values. `ordinaryModelDefinition()` demonstrates
constructing a pre-expanded item/group tree and exact scene frames using only public types; this
is compile-time evidence, not a renderer invocation. Lists are defensively copied and constructors
reject invalid numeric values, item keys, frame times and ambiguous scene declarations.

Scene selection uses exact animation and scene names. The host's internal ordinary path applies a
complete selection, including restoration of baseline transforms, item components and natural
brightness when a scene is no longer selected. It does not load packages or start train animations.
Use `ModelPresentationService.create(owner, worldId, objectId, definition)` to obtain a `ModelHandle`.
Creation only registers the model; the first valid `submit(new ModelPresentation(...))` schedules
entity creation. Submission supplies one complete pose and animation-scene map with a nonnegative,
increasing per-handle revision. Invalid selections are rejected without replacing valid pending state;
older or equal revisions and closed handles do not revive old models. Submission never waits for rendering.

The host merges pending display states and uses its existing nearby-viewer index. Moving the root
position deletes the old model on its owning thread, then builds at the new position; add-ons do not
teleport or mutate native display entities themselves. `close()` is idempotent and schedules deletion.

## Runtime flow

1. Read one `RailwayObservation` and retain its complete `RailwayStateStamp`; do not combine a train
   from one epoch with a topology revision read elsewhere.
2. Register namespaced custom resource types and their optional preparation handlers.
3. Register one route contributor when the signal policy needs additive resources. The contributor
   is called once for a route request and may only add validated resources and hold boundaries;
   Basic independently restores the mandatory rail, turnout and diamond footprint.
4. Install the complete `SignalEpochSource` set for each world. A nonempty set receives only
   baseline/changed monitored areas and returns complete restriction snapshots synchronously. An
   empty replacement clears only this owner's epoch sources after retained restrictions are
   withdrawn; it preserves inspection, route-planning and infrastructure ownership.
5. Install a complete `InspectionSource` set. The host invokes each provider only after selecting an
   exact production route. A same-ID `SignalEpochSource` returns changed declaration IDs with its
   epoch result, and the host invalidates only matching current bindings without a Bukkit callback.
6. Install a complete route-planning source set when the plugin restricts path choice. Each source
   compiles neutral forbidden spans or complete atomic paths from one supplied topology snapshot;
   it is not called from Dijkstra and does not edit the physical graph.
7. Publish timetable or other infrastructure dependencies only to protect their referenced rail,
   topology-node and runtime-resource identities from conflicting edits. These declarations are
   not train claims and do not prevent trains from acquiring the resources.
8. Before changing any declared rail, topology node or runtime resource, acquire one edit permit
   for the exact impact and keep it open through the complete mutation and persistence transaction.
   A denied result has no permit; inspect its field-wise blockers instead of performing any part of
   the edit. Closing a granted permit is idempotent.

`SignalEpochSource` callbacks run inside the serialized railway epoch. They must not call Bukkit,
perform blocking I/O, defer safety work asynchronously or mutate live Core/Basic objects. An empty
`SignalEpochResult` means "no restriction or inspection declaration changed"; withdrawing an
existing restriction requires a higher-revision snapshot whose restriction list is empty.

Inspection binding and update callbacks follow the same serialized, non-blocking rule. A new or
changed inspection source cannot be installed permissively while a train already owns a live route:
if the host cannot refresh it before another physics epoch, the existing world fail-closed boundary
stalls instead of reusing an undersized perception window.

Route admission receives a topology-bound frozen route, train ID, coherent observation stamp and
desired route-metre authority boundary. `Pending` is progressed through its opaque handle. A grant
can end only after its resource queue is empty; `endGrant` is not a force-release operation.

## Normal removal

Planned removal is an explicit pre-disable operation while the add-on is still enabled:

1. publish higher-revision empty snapshots for every retained nonempty restriction stream;
2. remove the add-on's infrastructure dependencies;
3. close observation or other passive subscriptions;
4. call `InfrastructureResourceService.unregisterOwner(plugin)` once.

That final call checks both infrastructure and signal ownership before deleting either side. It
rejects removal while the add-on has a pending request, held route resources, a resource type used
by another route, an infrastructure dependency, an active contributor route state or a nonempty signal
restriction. Unrelated trains and installed sources whose retained streams are empty do not block
removal. Do not close provider/contributor registrations first as an attempted unload shortcut.

`SignalEpochSourceService` intentionally has no owner-unregister method, and an empty source
replacement is not a second plugin-unload path.

### Unload add-ons before JavaRailways

Stop producing model updates, then call `ModelPresentationService.closeOwner(plugin)`. Its
`CompletionStage<Void>` includes models already closed individually but still awaiting entity deletion.
Observe successful completion before disabling JavaRailways. Do not block a physics or region thread
with `join()` or `get()`, and do not create new models while preparing to unload.

Add-ons must unload before the host. Their disable event requests model cleanup automatically, but
returning from `onDisable` does not prove queued region-thread deletions have finished. Plugin dependency
ordering alone does not provide this completion guarantee. Once the host is disabled, its scheduler
cannot be relied upon to delete models. Bypassing the required order must be diagnosed, not reported
as successful cleanup. This display lifecycle does not release train claims or bypass the resource gate above.

## Forced disable and failures

`PluginDisableEvent` cannot be cancelled. If an add-on disappears without completing the normal
gate, JavaRailways removes dead callbacks but keeps host-owned claims and infrastructure dependencies.
Only worlds where that add-on still has active infrastructure state or a nonempty restriction are
stalled fail-closed. Idle source registrations and unrelated trains do not stall a world.

Validation failure before claim mutation changes no resource ownership. Failure after an external
preparation side effect may be unprovable; in that case JavaRailways retains the safe claim and
stalls rather than widening authority.

## Presentation boundary

Signal aspects, model-variant interpretation, packages and placement configuration belong to the
concrete signal-system plugin. The public host model service only handles ordinary immutable model
values, latest-state scheduling, nearby viewers and cleanup. Its scheduling helpers and model manager
remain private; SimpleATOBukkit is not a presentation intermediary. Safety callbacks may submit values
but must not perform entity operations or wait for rendering.
