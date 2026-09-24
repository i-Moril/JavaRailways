# Production caller inventory

This inventory records the remaining production seams before the new route-admission lifecycle can
replace the transitional timetable allocator. It describes current callers; it does not authorize a
public API shape or a compatibility wrapper.

## 1. Automatic allocation and turnout mutation

- `TimetableDispatcher` owns one world-local `RouteSectionAllocator`. Allocation enters through
  `allocate(candidate, allocator)` and releases through `releasePassedSections`.
- `RouteSectionAllocator.allocate` prevalidates the complete legacy turnout batch and then calls
  `RailView.selectNextRail` directly. This path does not call `ProtectedRouteClaimService`.
- `ImmediateTurnoutPreparationActuator` is the only new protected-admission writer. It resolves the
  complete bidirectional selection batch before calling `selectNextRail`.
- These are the only two production `selectNextRail` callers. The legacy allocator must disappear
  when automatic and `MANUAL_ROUTED` Dispatcher flows migrate; the preparation actuator remains the
  protected automatic writer. A separately guarded explicit manual operation is still required for
  `FULL_MANUAL` and must not publish automatic authority.

## 2. Remaining one-turnout assumptions

`TimetableDispatcher` still calls `turnoutRequirements.single()` in three distinct lifecycle paths:

1. approach/protection confirmation near the first unconfirmed section;
2. authority/protection-distance calculation for an allocated section;
3. release gating after the occupancy detector reports released.

These are not harmless presentation assumptions: they determine stop/authority/release behavior.
They must be removed together with the Dispatcher migration and replaced by the Basic grant boundary
and compiled release queue. Converting `single()` to `first()` or looping the old protection-distance
formula would preserve the wrong model.

## 3. Observation publication and consumption

- `WorldManager.tickEnd` waits for the exact completed physics ticket, reads
  `CoreWorld.latestWorldFrame()`, and calls `DefaultRailwayObservationService.publish` once for that
  world/epoch.
- The observation service stores one latest immutable observation per world and invokes listeners in
  stable registration order. Duplicate publication of the same epoch is a no-op after stamp checks.
- `ProtectedRouteClaimService.consumeObservation` already performs the required world-local tail
  release dispatch and builds one train-ID lookup for its live grants, but no Bukkit composition root
  constructs or subscribes this service yet.
- Future wiring must subscribe exactly one world-local route-resource owner to the observation
  service. It must not make every signal, train, or grant an independent listener.

For route creation, isolated `FrozenRouteSelector.select` currently performs one linear train lookup.
When Dispatcher batches multiple selections for the same observation, its dispatch context should
build one train-ID index and share it; this is event-driven selection, not a per-tick cache.

## 4. Existing controller constraint path

The production ATO remains `Basic.controller.SimpleATO`. One scan updates built-in route speed,
route-end and following constraints, then mission, station-call and provider constraints; finally
`MinimumConstraintPlanner` selects the most restrictive result. The new Basic grant is not yet a
candidate source. Caller migration must add the committed grant boundary to this one pipeline and
remove the old Dispatcher-derived authority, not create another MA/Follow scanner.

## 5. Bukkit services and lifecycle

- `BukkitPlugin` currently creates and registers `RailwayObservationService` and
  `InfrastructureResourceService` during enable.
- `WorldManager.registerRailWorld` creates the world-local infrastructure coordination registry; world unload removes the
  latest observation and closes/removes that registry after the physics coordinator and snapshot
  boundary are handled.
- Plugin disable unregisters Bukkit services, stops/unregisters worlds, then closes observation and
  infrastructure resource services.
- There is no registered route-resource/admission service, no world-local `ProtectedRouteClaimService`
  composition, and no provider-disable/reload takeover for its live grants. Those must be added as one
  lifecycle slice; live claims cannot be silently released merely because the requesting plugin is
  disabled.

## 6. Topology editors and edit guards

- RailWand create/connect/split/merge/delete/restore paths execute `CoreWorld.railTopologyTransaction`
  directly. Their current safety checks query physical occupancy, signals/objects and the legacy
  `TimetableDispatcher.hasRouteAllocationOn` guard.
- `RailAutoConnector` also consults the legacy Dispatcher allocation guard.
- `InfrastructureResourceService.acquireEditPermit` exists, checks applied-plan dependencies, live claims and other
  edit permits atomically, but none of these editors currently call it.
- Migration must first compute the full affected resource footprint, acquire one permit, perform the
  complete topology plus persistence mutation, and close the permit afterward. Replacing only one
  RailWand command would leave unsafe bypasses.

## 7. Ordered migration batches

1. Finish Basic route validation/closure and freeze the route-resource public request/result only
   after its remaining fields are decided.
2. Add the world-local Bukkit route-resource composition and one observation subscription, including
   unload/disable fail-closed ownership.
3. Add the SimpleATO admission client and feed committed grant boundaries into the existing single
   constraint pipeline.
4. Migrate automatic and `MANUAL_ROUTED` Dispatcher allocation/release, then delete the three
   `single()`-based authority/release calculations and the legacy allocator writer.
5. Add the separately guarded `FULL_MANUAL` turnout operation.
6. Route every topology editor through the common edit permit and only then remove
   `hasRouteAllocationOn` adapters.

The migration must preserve a single writer at every step. The legacy allocator is not removed until
the new Dispatcher path has parity tests, but no compatibility adapter may claim that both paths own
the same automatic train.
