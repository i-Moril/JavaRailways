# Depot stabling points as standalone simulation origins — 2026-10-30

User asks that simulation start choices include depot stabling tracks. Continue the same task/worktree at baseline5e674d12; no new task, commit/deploy/archive, persistence or compatibility change.

## Verified source facts before edits

- `OperationalDestinationService.register` creates a depot ledger point for DEPOT_STABLING_POINT and also mirrors the same canonical destination identity into Basic.wayPoints and SimpleATO.destinations. A depot point's canonical ID is depotId@stablingPointId; it is not an unrelated depot aggregate ID.
- Current bridge metadata iterates all Basic.wayPoints with no destination-role filter. The resolver also has no role filter. A directed depot stabling point therefore already qualifies by its own approachDirection, or by its rail's declared operating direction.
- A stabling point without either declared direction is excluded by the accepted direction contract. The wand defaults to UNRESTRICTED and supports an explicit approachDirection for every role. This is an eligibility rule, not proof of a depot-specific exclusion.
- The sidecar preserves the bridge's metadata fields, and ProgramSimulationDialog reads programSimulationWayPoints directly, with no additional depot exclusion.
- Standalone wrappers use TakeFromWaypoint(real identity). Factory seeding and Dispatcher waypoint acquisition have no depot-role exclusion. No TakeFromDepot conversion, live stock cloning or depot inventory reservation is needed for an isolated function scenario.

## Smallest implementation boundary

Current behavior already includes directed depot points. A proposed pure extraction of metadata enumeration was withdrawn after parent review because it changes no user-visible behavior and cannot explain the reported missing points. Production helper/bridge source remains unchanged while the parent checks actual configured directions and published candidate metadata.

Add real depot registration and isolated execution regressions covering point direction, rail direction, undirected points, removed points, explicit in-place reversal and preserved source ledger/trains. Do not duplicate the metadata enumeration in tests as a substitute for its producer. Any newly discovered Factory/Dispatcher failure must be reported to the parent before widening implementation ownership. Do not claim a previously nonexistent role filter was fixed.

## Validation

Parent-serialized focused Bukkit factory tests passed; see the final checkpoint below. No live server data was modified by this worker.

## Restore and actual-data follow-up

Parent read the development server's persisted infrastructure: kasaidpt@1 through @6 each have approachDirection=BA, DEPOT_STABLING_POINT role, canonical displayName and usableLength120; their rails have no Basic operating-direction entry. Thus the configured-direction filter alone does not explain those specific points being absent. No live authenticated metadata was obtained in this worker; source conclusions do not claim verified live response content.

`SimpleATOInfrastructureSnapshotV1.restore` restores the entire destination list through OperationalDestinationService.register, including its Basic mirror. `toWayPoint()` copies destination ID/displayName/rail/arc directly. Basic capture intentionally excludes destination-owned waypoints, and restore rejects colliding standalone Basic IDs instead of silently retaining old names. A canonical destination displayName therefore produces the same canonical Basic mirror displayName after restore.

Added Bukkit regressions:

- Point-own BA overrides rail AB, or an undirected point uses rail AB, for actual depot standalone execution with reverseTrain false and true. Each call measures the true7-tick wait and leaves source depot ledger/trains intact.
- Current-format Basic empty snapshot + six manually specified SimpleATO depot destinations (matching the parent's observed role/direction/usable length shape) decode and restore before capture. The test verifies all six mirrors resolve canonical IDs and point-owned BA, then checks target restore and actual call completion.
- Undirected and subsequently removed depot points fail resolver/capture, maintaining the no-fallback contract.

These tests passed in parent execution; no production backend change is proposed absent a reproduced backend defect. Frontend ID visibility/search can be the user-visible fix if candidate metadata is already correct.


## Validation checkpoint — 2026-10-30

Parent ran the focused `:BukkitPlugin:test --tests '*IsolatedTimetableSimulationFactoryTest'` gate with the shared Gradle/JDK setup. Gradle succeeded in48s. The test XML reports30 tests,0 failures,0 errors,0 skipped, including all4 newly added instances. This confirms the real current-format restore, direction resolution, isolated seed/acquisition/execution and rejection regressions above; it is not a live browser or game acceptance result.

Parent also confirmed the actual saved Basic wayPoints list is empty, consistent with the manually specified restore fixture and destination-owned mirror contract. The server remains unchanged; no endpoint/token access workaround was used. Backend production sources are unchanged and test source remains frozen after this gate.
