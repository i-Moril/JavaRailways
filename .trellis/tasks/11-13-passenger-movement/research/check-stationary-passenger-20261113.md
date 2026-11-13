# Check: player-owned walking and stationary passenger-only output

- Branch: `feat/passenger-movement`; HEAD at review: `6e77496c`.
- Scope: the final six production-file and two test-file diffs against HEAD, plus the passenger integration against local `main`. Main owns final specs, task state and commits. No Kotlin or build configuration was changed by this check.
- Source candidate: `/tmp/jr-passenger-stationary-sources/sha256.txt`; all eight hashes matched before and after the full test/package run.

## Findings (fixed)

None. The reviewed candidate required no local source fixes.

## Findings (not fixed)

- The full module still fails `CarAnimationPlaybackTest.kt:85`: packaged S100 `behavior.js` references `../../../types/train-behavior.d.ts`, while the source references `../../../schemas/types/train-behavior.d.ts`. This is the recorded baseline failure outside passenger scope. The ZIP, source JS and test match `/tmp/jr-passenger-owner-baseline-sha256.txt` before and after verification; none was changed.
- The passenger spec is still marked TRANSITIONAL and the implementation checklist is unchecked at review time. The coordinator owns syncing them to this verified implementation while keeping client acceptance pending.

No additional behavior issue requiring a new mechanism or broader scope was identified. The previously withdrawn retained-frame and aggregate-batch-wait proposals were not reinstated or treated as requirements.

## Actual call paths reviewed

- `watch` uses the existing player entity scheduler. `tickSession` reads WASD/yaw, advances the existing geometry, and publishes membership/local position/yaw/exit together as `WalkingState`. It is the movement writer; no world runner integrates walking.
- A stationary accepted scene (`speedMetersPerSecond == 0.0`) sends a changed rider through `renderStationary` and the common carrier output/native path. There is no train dispatcher offer, whole-train render task, model traversal, retained train-frame map or new scheduler/queue in that path. An idle stopped rider emits no walking packet. The preserved native-pending exit is retried when actual confirmation permits release.
- Moving rendering captures rider states once in `dispatchPublishedTrainFrames` before region/chunk splitting. `dispatchTrainPresentation` applies each carriage and outputs only its captured riders with the accepted complete frame, inside the existing outer packet bundle. Geometry and local state have separate ownership.
- `applyPublishedFrame` still rejects stale/foreign geometry. Equal accepted revision can return the already accepted frame without repeating model traversal; `render` additionally requires the matching accepted platform scene.
- Carrier/native updates use the existing entity dispatcher after the outer bundle has been submitted. The presentation revision fences stale native targets; ending fences late work. Native exit, actual event veto, manual Shift cancellation and accepted teleport/death/quit paths were traced. Allocation and existing last-batch cleanup completion are preserved.
- Session/bundle long-held locks and the passenger-only delayed audience refresh are removed. The generic `LatestAffinityTaskDispatcher`, Core, native `SeatAuthorityMover`, geometry, collision heights and build configuration are unchanged in this round. This does not claim that every existing backend or lifecycle lock was removed.
- Composition startup/shutdown, model registration/retirement, source/platform activation, the common fixed-seat backend, and the sole production `applyPublishedFrame` caller were checked against the feature integration with `main`.

## Verification

Command:

```sh
./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle \
  -Pkotlin.daemon.jvmargs=-Xmx4g \
  :BukkitPlugin:test :BukkitPlugin:shadowJar --continue
```

- Log: `/tmp/jr-passenger-stationary-full.log`.
- Full test result: **963 tests, 961 passed, 1 failed, 1 skipped, 0 errors**. The sole failure is the unchanged S100 baseline above; the command exits 1 and is not a green module gate.
- All 42 focused cases also passed inside that full run: PassengerMovement 18, PassengerGeometry 8, PendingTrainFrames 7, CarFrameRevisionGate 1, LatestAffinityTaskDispatcher 4, TrainPacketBundleCoordinator 2, SeatAuthorityMovePlan 2.
- Type check/compilation: the exact candidate compiled successfully in `/tmp/jr-passenger-stationary-focused.log`; compilation and test compilation were up to date in the full run with all source hashes unchanged.
- Lint: no standalone Kotlin lint task/plugin is configured in the inspected Gradle files. `git diff --check` passed.
- `:BukkitPlugin:shadowJar` executed successfully. Artifact: `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar` (62,883,923 bytes), SHA-256 `95460a583e99977e9a2000fe171c79227e6a807e020359b18743436ff46cdb6e`.
- The artifact contains the new passenger state/manager classes, the WorldManager presentation wiring, `paper-plugin.yml`, and the configured `webui/JavaRailways-WebUI.jar` resource.
- All source-candidate and baseline input hash checks passed after the run; no unexplained source drift occurred in this check.

No deployment, push or commit was performed. Tests use dispatcher/carrier doubles and real Core fixture worlds; they do not establish live Craft/NMS behavior, Folia migration, first-person smoothness, client exit continuity or tick performance. Those remain client/runtime acceptance work.

## Coordinator close-out

The coordinator has synced the module spec to CURRENT source behavior and completed the local implementation checklist. Client/runtime acceptance remains pending, the known S100 baseline failure stays recorded, and the task remains in_progress rather than archived. No Kotlin changes followed this verification.
