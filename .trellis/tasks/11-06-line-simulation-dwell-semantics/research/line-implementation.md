# Real line measurement implementation

## Scope and contract

Replaced `IsolatedLineSimulator`'s length/mean-grade estimator with the existing `IsolatedTimetableSimulationFactory` and its single shared physics/dispatcher loop. Deleted `DetailedTrainTravelSimulator` and estimator-only tests; shared TrainDynamics is unchanged. No commit/deployment/server mutation.

HTTP receives `measurementSource`, `firstDepartureTick`, `fixedDwellTicks`, `minimumDwellTicks`, ordered `calls` (`callId/stationId/destinationId/kind/viaIds`) plus existing profile/route/direction/cap fields. Source is the frontend's existing generator restricted to one run, with real station definitions and absolute event waits. Current plannedContext carries identity/role/direction, not arrival/departure timestamps; retiming the detached compiled model does not re-evaluate branches under invented timestamps.

`prepareLineMeasurement(saved, request, reverseOrigin)` validates one run, matching ordered calls, and only normal station/movement actions. It preserves program instructions, keeps firstDeparture absolute, applies fixedDwell only to synthetic STOP minimum, and removes unknown future schedule lower bounds. This does not modify the user's minimum or document. Optional initial reversal is a real existing Reverse action, not a reversed respawn. Measurement horizon defaults to firstDeparture+72000, bounded maximumDuration, delay threshold horizon+1 only in this measurement.

`IsolatedLineSimulator.simulate(factory, snapshot, request, workerThreads)` returns ordered integer `calls` and real `legs` metrics. It subscribes to waypoint and station-ready notifications and samples the same successful physical frames through optional `afterTick`. Ordered incoming via/call events preserve repeated occurrence identity; no station-ID map. PASS time comes from its waypoint event. For StationCall STOP/TERMINAL, waypoint only orders the identity: the sampler retains exactly one pending physical sample at explicit BERTHED state and requires its tick to equal the station-ready event's actual `arrivedTick`. No message parsing or per-tick history. Ordinary non-StationCall destination uses its actual waypoint sample and the same equality check. Travel begins at specified departure targets, not first-motion/DEPARTED, retaining resource waiting. Peak speed and traveled length come from actual controller publication; stopping error uses the arrival frame's Mission target minus offset minus actual projected progress, not a later ready frame. PASS has no stopping error field rather than a fabricated zero.

Readiness later than departure target produces typed `LineDwellInsufficientException`; HTTP maps it and insufficient fixed/global minimum to 422 `LINE_DWELL_INSUFFICIENT`. Failed/partial/horizon/cancelled execution cannot return successful calls. Subscriptions and world close on all paths. Snapshot creation captures actual required/prepared features through normal plugin hooks; no empty-signal production default is used.

## Explicit unsupported input and discovered existing limit

Nonzero ORIGIN stopping offset is explicitly rejected. Existing authoring emits initial placement/CHECK at the origin marker; origin BeginStationStop does not express a placement offset. Parent approved reporting this unrepresentable input instead of modifying placement/CHECK or inventing an anchor.

A 2m intermediate STOP offset was tried through actual execution and failed when the next Mission began: `train simulation/line-test/duty is not facing the planned route from c@one`. This is an existing continuation limitation, not an estimator result. It is not bypassed or clamped. The valid regression uses 0.1m intermediate offset and a full 2m terminal offset; these are not proof that arbitrary intermediate offsets work.

## Verification

Focused command uses `/tmp/jr-rolling-tests.gradle` for bounded test heap/fork settings and `-Pkotlin.daemon.jvmargs=-Xmx2g`, no project build config edits:

`./gradlew -I /tmp/jr-rolling-tests.gradle :BukkitPlugin:test --tests '*IsolatedLineSimulatorTest' --tests '*LineSimulationMaximumSpeedTest' --tests '*IsolatedTimetableSimulationFactoryTest.line measurement*' --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g`

Initial signal fixture failed correctly because the real official pack requires a finite static rail speed limit. Added actual SUBGRADE 10m/s metadata (line cap6); slower comparison uses SUBGRADE2. Testing the actual HTTP StationCall normalization then exposed waypoint arrival890 versus actual BERTHED915, leading to false target950 versus ready975. Source evidence: `reportWaypointProgress` uses mission stopping tolerance and `isStopped`, while the StationCall controller's independent berth confirmation produces BERTHED later; Dispatcher stores that event tick and starts programs on the next tick. The sampler now uses the actual BERTHED event anchor as described above; it does not change those physical confirmation rules or add tick tolerance.

Final focused run at 2026-11-06 14:34 UTC: BUILD SUCCESSFUL, XML confirms IsolatedLineSimulatorTest2 + LineSimulationMaximumSpeedTest2 + normalized IsolatedTimetableSimulationFactoryTest integration1 =5 tests,0 failures/errors/skips. Integration9.376s, build28s. `git diff --check` passes. Parser regression explicitly accepts absent/null/zero origin offsets and rejects nonzero; the null case was caught by independent review.

Integration runs a real same-train ORIGIN/PASS/STOP/TERMINAL sequence with official SimpleSignals feature, compares returned integer arrival ticks against normal single-service replay using the original1200 threshold, rejects station WaitDuration100 with fixed60, rejects exhausted horizon, and verifies lower local rail speed materially increases time. It uses a one-car configured test profile, not the user's S100 asset. Main session owns copied real-S100/offline102P verification and broader gates.
