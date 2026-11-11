# Batch A — frame unification implementation

Scope: F1–F3/F5 only. No commit, deployment, persistent format change, speed-limit batch B or deferred audit cleanup.

## Changes

- Core coordinator now flips buffers and publishes complete train frames before controller notification. Existing topology→state close/halt gates remain; hooks remain outside both locks. After a hook closes/stalls the coordinator, no subsequent processor/world publication starts.
- Explicit `TrainController.commit()` commits its speed buffer, calls the train's one explicit commit, then notifies. Removed the obsolete combined flip-and-early-hook helper.
- Deleted `TrainSnapshot`; Core/Basic/SimpleATO/SimpleSignals/Bukkit consumers and fixtures now use `TrainFrameSnapshot`. ATO publication holds `trainFrame`; its public reader is `currentTrainFrame`.
- ATO scan captures one frame for projection, body length and perception. No physical speed override or independently sampled head comparison. Dispatcher reads actual Core frame even after manual takeover and passes that same frame into inspection.
- Inspection removes scalar length input and unknown-length route-end fallback. Required clearance geometry missing/wrong/stale/empty fails through existing route restriction error path; no Basic request is made from that failed synchronization.
- Initial diagnostic frame is owned by `AbstractATO.initialTrainFrame`, so it dies with the controller rather than accumulating in a world ID map. Stall/ATP retain historical frames, text logs select small fields. JSON shape and schema remain unchanged: initial revision = 0, current revision = report tick, perception trainRevision = perception epoch.
- Fresh Core geometry exposed an existing ordering assumption in one overrun regression: projection rejected before the usual transition check. Projection failure now first invokes the existing committed-authority validator, preserving `RouteAuthorityViolation` and its retained claim instead of masking it with a generic Mission projection message.

## Regression evidence

1. Before implementation, added equality of the ATO head with the just-published Core frame in `AbstractATOMotionPublicationTest`. Actual coordinator run failed at this new assertion (1 test / 1 failure), reproducing stale-frame publication.
2. Core focused `PhysicsCoordinatorTest`, `CoreTrainPublicationBoundaryTest`, `ImmutableSnapshotTest`: 50 tests, zero failures. New barrier test observes the complete moving frame in the hook, closes while that hook blocks, then checks no late world/frame publication.
3. Focused SimpleATO publication, explicit commit, frozen historical diagnostics, entry-clearance, controller, Dispatcher and rolling-station suite passed. `RollingStationAdmissionTest` now supplies a real inspection entry requirement on its real Core/Basic/handle-control path and asserts moving early requests do not become whole-route claims. Manual-control test checks status position against current Core frame.
4. Basic full suite: 467 tests, zero failures/errors.
5. First SimpleATO full suite: 790 tests, one failure in the overrun diagnosis expectation described above; all others passed. Focused station-call suite rerun after the ordering repair passed. Final integrated full rerun remains owned by main session after batch B.

6. SimpleSignals first full run: 677 tests, eight failures. Two protection-region tests still expected missing geometry to claim the whole route; migrated them to an explicit one-metre body frame, with a 26.01m exit-clearance expectation instead of 30m route-end fallback. Six existing manager concurrency tests timed out under the audit init script's `ActiveProcessorCount=2`. Retried with `/tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle` (3GB heap, one test fork, `ActiveProcessorCount=4`, `ForkJoinPool.common.parallelism=4`): **677 tests, zero failures/errors**. Both concurrency settings changed together; evidence establishes a passing adequate-parallelism configuration, not which individual JVM option solely caused the timeouts. No manager production/test logic or repository build configuration changed.
7. Bukkit `compileKotlin`, `compileJava`, `compileTestKotlin`, `compileSignalApiConsumerJava` and root `verifySignalModuleBoundaries` passed; the boundary task also built `shadowJar` and verified the SimpleSignals plugin JAR. This is build verification, not deployment or Bukkit runtime acceptance.
8. Repository source search found no remaining `TrainSnapshot`/`trainSnapshot` references in Core/Basic/SimpleATO/SimpleSignals/Bukkit Kotlin/Java sources. `git diff --check` passed.

Main session owns spec synchronization, final Core/Basic forced rerun, post-B integrated SimpleATO suite, local Tick 14219 replay and final review. No remote operations were performed. Code and Gradle ownership handed back after successful downstream run; batch B can edit control integration now.
