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
  contributions, preparation, route admission, applied-plan edit dependencies and the unified
  owner lifecycle;
- `SignalEpochSourceService`: complete nonempty signal-source sets installed per plugin and world;
- `InspectionSourceService`: host-route-bound inspection providers plus authenticated dirty
  declaration publication;
- `RoutePlanningRestrictionSourceService`: owner/world-scoped route-table and traversal restrictions
  compiled once before immutable-topology search;
- `RailTargetService` and `PlayerOperationService`: lower-level Bukkit interaction capabilities.

The returned services are host-owned. Do not retain a world-bound admission service across world
unload/reload and do not assume that a reused world UUID revives an old binding.

The fixture installs SimpleATO's platform-neutral `InspectionSource`. Its binding provider receives
the exact host-selected train and `RouteSnapshot`; add-ons must not reconstruct that route from a
separate observation. The provider returns one complete immutable `InspectionDeclarationBinding`.
SimpleATO owns activation and invokes the handler only for newly active or explicitly dirty
declarations, using its existing perception scan and dynamic braking lookahead. A newly compiled
binding also receives one baseline callback, possibly with an empty declaration list; it must publish
one complete snapshot bound to the new route, even when that snapshot contains no restrictions.

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
   exact production route; publish dependency changes through `markInspectionDeclarationsDirty`.
6. Install a complete route-planning source set when the plugin restricts path choice. Each source
   compiles neutral forbidden spans or complete atomic paths from one supplied topology snapshot;
   it is not called from Dijkstra and does not edit the physical graph.
7. Publish timetable or other applied-plan dependencies only to protect their referenced rail,
   topology-node and runtime-resource identities from conflicting edits. These declarations are
   not train claims and do not prevent trains from acquiring the resources.
8. Before changing any declared rail, topology node or runtime resource, acquire one edit permit
   for the exact impact and keep it open through the complete mutation and persistence transaction.
   A denied result has no permit; inspect its field-wise blockers instead of performing any part of
   the edit. Closing a granted permit is idempotent.

`SignalEpochSource` callbacks run inside the serialized railway epoch. They must not call Bukkit,
perform blocking I/O, defer safety work asynchronously or mutate live Core/Basic objects. An empty
`SignalEpochResult` means "no restriction changed"; withdrawing an existing restriction requires a
higher-revision snapshot whose restriction list is empty.

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
2. remove the add-on's applied-plan dependencies;
3. close observation or other passive subscriptions;
4. call `InfrastructureResourceService.unregisterOwner(plugin)` once.

That final call checks both infrastructure and signal ownership before deleting either side. It
rejects removal while the add-on has a pending request, held route resources, a resource type used
by another route, an applied plan, an active contributor route state or a nonempty signal
restriction. Unrelated trains and installed sources whose retained streams are empty do not block
removal. Do not close provider/contributor registrations first as an attempted unload shortcut.

`SignalEpochSourceService` intentionally has no owner-unregister method, and an empty source
replacement is not a second plugin-unload path.

## Forced disable and failures

`PluginDisableEvent` cannot be cancelled. If an add-on disappears without completing the normal
gate, JavaRailways removes dead callbacks but keeps host-owned claims and applied-plan protection.
Only worlds where that add-on still has active infrastructure state or a nonempty restriction are
stalled fail-closed. Idle source registrations and unrelated trains do not stall a world.

Validation failure before claim mutation changes no resource ownership. Failure after an external
preparation side effect may be unprovable; in that case JavaRailways retains the safe claim and
stalls rather than widening authority.

## Presentation boundary

The Stage 1 host API does not define signal aspects, model variants or a Bukkit rendering queue.
Immutable revisioned presentation, per-object latest-only Folia dispatch and model application
belong to the concrete signal-system plugin. The existing JavaRailways latest-only helper is an
internal host rendering mechanism, not a `SimpleATOBukkit` or add-on API. If concrete add-ons later
need a shared host platform service, that contract must be reviewed and exposed from the public
`bukkit.api` boundary. Safety callbacks must not perform presentation work directly.
