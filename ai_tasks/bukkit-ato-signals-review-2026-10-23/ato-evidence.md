> 分轮证据记录。早期覆盖数量/“未修改仓库”描述对应当时进度；最终问题状态与覆盖以 [review.md](review.md) 为准。本轮仅新增本目录文档。

# SimpleATO read-only review

Baseline HEAD a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2; no SimpleATO dirty files at completion check. No repo files changed, no Gradle/build/tests/server execution. Evidence is source/call-chain analysis only. Inventory/precise limitations: /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv. Continuation now completed full source reading of all 121 inventoried module files: 52 tests and 69 main/build/documentation/resource files. This is static coverage, not dynamic acceptance.

## ATO-01 [P2] Withdrawn source becomes permanent zero-speed restriction after route changes

Location: SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/restriction/TrainRestrictionConstraintAdapter.kt:128-140 (especially 133).

Trigger: a public signal source publishes a newer empty restriction snapshot on route A, then is successfully removed via planned source replacement. Later the same train gets route B. Even a newly created controller is affected.

Chain: TrainRestrictionSnapshot explicitly documents empty replacement as clearing active restrictions (api/restriction/TrainRestriction.kt). TrainRestrictionSnapshotStore.publish retains that empty stream; completeEpoch only deletes trains no longer live. DefaultSignalEpochSourceService permits planned removal once activeRestrictionStreams is empty (parent traced requireWithdrawn and removeUnusedOwner). SimpleATO.updateTrainRestrictions 723-729 imports every retained store snapshot into the adapter every physics tick. compile compares the old empty snapshot's route against B before looking at restrictions and synthesizes DepartureHoldConstraint. Removed source can no longer publish a B-bound replacement. This is not forced-unload fail-closed behavior: normal planned withdrawal succeeded.

Impact: valid next trip remains at 0 speed indefinitely while source has already withdrawn. Keep stale-revision protection/tombstone but do not reinterpret an acknowledged empty withdrawal as a geometric restriction on future routes; preserve separate unavailable-source semantics.

Test gap: TrainRestrictionConstraintAdapterTest 135-150 tests empty clearing only against same route; following test covers nonempty route mismatch. No combined withdrawal -> planned removal -> new route case located.

## ATO-02 WITHDRAWN: no supported reachable trigger

The original Check(A) -> Extension -> GoTo(B) example is invalid: Timetable.kt 467-470 rejects Extension before the first destination. discardRun (1784-1788) does omit extension cancellation, but ordinary post-movement runs retain admission and the discard guard rejects them. Full validator/Dispatcher/test reading found no supported state combining admissionEnded/null and pending extension. Do not count this as a confirmed defect or retain the original invalid trigger.

## ATO-03 [P2, public API boundary] Rejected cancellation mutates Depot ledger

Location: SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/depot/Depot.kt:607-610; same pattern 555-557.

Trigger without forged input: allocate -> confirmInService -> cancelReservation(same allocation). cancel returns false because IN_SERVICE is not RESERVED, but assignments.remove already happened. releaseDeparture(allocation) and completeStorage subsequently fail because they require the deleted assignment. A wrong/stale allocation or storage token with same assignment ID also deletes the current entry before inequality rejection; reservedByAssignmentId on the physical point remains, making it unreleasable through correct token.

Impact: valid ledger is irreversibly orphaned by an operation that reports rejection; requires reset. Public DepotStore methods are exposed; Dispatcher currently protects normal cancelReservation calls with depotReservationPending, so this is an API-contract defect, not a claim that normal dispatcher routinely calls cancel in IN_SERVICE. Existing successful cancellation test at DepotStoreTest 185-200 does not test rejected cancellation invariance.

Minimum action: validate token and lifecycle first, then exact removal plus ledger updates under current lock. For cancelStorage use exact-match conditional removal.

## ATO-04 removed from findings (2026-10-23)

Removed at the user's request. The current WebUI Reset/recalculate-times caller always supplies destinationIds through authoring.lineSimulationRoute; TimetableHttpBridge therefore uses compileResetCandidates, not the omitted-destinationIds branch discussed in the original finding. The earlier description overstated an API/default-branch observation as a user-facing preview defect. No current frontend caller of that branch was found; no repair is scheduled. This withdrawal is not a proof that arbitrary omitted-berth API input produces a continuous route.

## Additional engineering finding (public mutable alias, not normal platform mutation proven)

Depot.kt RollingStockRecord.tags/capabilities (58-63) and DepotStablingPoint.allowedTags (41-45) retain caller Set without copy. registerRollingStock stores record directly; rollingStock/allRollingStock return same record. OperationalDestination.DepotStablingPointRole.allowedTags likewise aliases the caller, and SavedRollingStockV1.capture assigns the same sets. Mutating original mutableSet after registration/capture changes selection/capability checks and prior persistence snapshots without any owner operation or validation; adding blank tags bypasses constructor validation. Compare Station/Line defensive constructors. This repeats the first-round-missed immutable-value category. Bounds: API-constructible alias demonstrated statically; did not establish actual platform callers mutate sets afterward.

## Other reviewed candidates and exclusions

- OperationalWorldRuntime intentionally does not unregister static railway-epoch processor; simulation spec explicitly keeps it until coordinator stops. Not a finding.
- Runtime snapshot restore updates ledger before dispatcher restore, but current spec only promises ledger batch atomicity and world loader discards failed world. Not reported as whole-world transaction violation without caller evidence.
- startServiceForCycle removes prior stop limits before physical origin validation (665+), a possible failed-start side effect; not promoted without proving an eligible retained ATO route can move after failure.
- Terminal-run early return skips berth/depot bookkeeping; not promoted without a confirmed supported terminal/manual relocation lifecycle.
- No runtime compatibility/version changes proposed.

## Final coverage update and independent Signals check

All 121 inventoried SimpleATO files read; 0 PARTIAL, 0 LOCATED. Production includes all 5,208 Dispatcher lines, all controller and validator lines. Tests include full 2,297-line TimetableDispatcherTest and 1,451-line TimetableStationCallDispatcherTest, alongside all other tests. Generated files excluded. No tests/build/runtime were executed. Existing assertions often exercise real Core motion/Basic admission with a kinematic actuator, and many negative/cleanup tests are substantive; this does not establish live Paper/Folia/model operation acceptance.

Independently confirmed peer Signals candidate: SignalPackEpochRuntime.evaluate 92-95 requires exact global topology revision before per-device failure handling. SectionTopologyGuard.invalidate 190+ always advances its topology but returns false for unrelated edit with no pending sections; SimpleSignalsWorldRuntime subscription 1469-1473 then schedules no publication recompile. On the next source callback (occupancy/continuation/admission/direction/readiness change), SectionOccupancyRuntime 407 calls evaluate without catching that precondition. Exception propagates through SignalEpochRuntime and PhysicsCoordinator processor.process 716 to outer catch 791-797, invoking RailwaySimulationHalt.halt and settleHalt. Do not claim an idle unrelated topology edit necessarily triggers immediate callback. This finding belongs to SimpleSignals report, not an additional ATO defect.

## Continuation correction (authoritative over earlier ATO-02)

Full Timetable validator read disproves ATO-02's original trigger: Extension before the first destination is rejected (Timetable.kt 467-470). The omission in discardRun is real, but an ordinary stopped run after movement retains Basic admission, and discardRun rejects it. Therefore withdraw the previous confirmed P2 until a supported path establishes admissionEnded/null plus a pending extension; do not present the old Check->Extension->GoTo example as valid.

New engineering finding E-02 [P3 testing]: SimpleATOTest `configured rail limit overrides actuator maximum speed` (292-302) never authorizes a Mission. It asserts zero speed after one tick, which the no-Mission hold already guarantees independently of infrastructure limits. Removing current infrastructure speed-limit enforcement would not fail this purported behavioral regression test. Upcoming-signal tests assert constraint descriptions and cover a different path. Add a moving authorized Mission and assert the configured speed bound to make this test sensitive to the advertised behavior. Static evidence only; no mutation test executed.

Falsifications: predictive speed-limit direction filtering is done by Core RailView.staticRailObjects 93-97; do not flag missing appliesTo in SimpleATO's static-signal loop. Manual physics/tickManager close in AbstractATOMotionPublicationTest and other tests performs the same resources as TestWorldFixture (no separate world registry teardown there); absence of wrapper alone is not a leak. Sequential cleanup can skip later close on exceptions, a robustness gap only, not established normal leak.

## Continuation test review: boundaries and cleanup

E-03 [P3 testing]: SignalEpochRuntimeTest 285-309 starts a non-daemon worker running production runtime.process; its handler blocks in releaseOldHandler.await (293). The main thread waits unbounded at enteredOldHandler.await (300), calls production replaceSources (302), then releases and joins. There is no finally, worker exception propagation, or local timeout. If process throws before invoking the handler, the main test waits indefinitely; if replaceSources throws after handler entry, no finally releases the worker. A regression in the exact concurrent replacement path under test can therefore hang the test instead of reporting failure and cleaning its worker. Use bounded waits plus finally release/interrupt/join and capture worker failures. This is a test robustness finding, not production deadlock evidence; external CI time limits can terminate the process but do not make its cleanup sound.

TimetableDispatcherTest 1434-1441 forcibly moves the train to its target if the manual-routed scenario does not complete within 2,000 ticks; the subsequent COMPLETED assertion therefore does not prove successful physical journey. Treat as a scope limitation of the state/assistance test, not a separate runtime finding. TimetableStationCallDispatcherTest cleanup uses assertAll over each fixture unregister/close; door cancellation failure/identity failure/timeout paths and retained physical ownership have meaningful assertions. No additional confirmed runtime defect arose from these final files.

Original-pass retained functional findings: ATO-01 and ATO-03 (2). ATO-02 withdrawn; ATO-04 removed on 2026-10-23. Later ATO-05/06 are tracked separately in deep-ato.md. Engineering findings are separately bounded: mutable public-set alias; E-02 ineffective rail-limit regression assertion; E-03 concurrent test failure cleanup.
