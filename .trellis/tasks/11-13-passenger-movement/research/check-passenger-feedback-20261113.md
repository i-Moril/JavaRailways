# Passenger feedback review — 2026-11-13

Active task: `.trellis/tasks/11-13-passenger-movement`.

Reviewed the final ten changed BukkitPlugin source/test files against the task requirements and Bukkit lifecycle/render contracts. Branch: `feat/passenger-movement`; review base: `362ff669df92ba2a025b00e106f091cc78b1dab3`. The latest user symptom is a brief boundary stall, including stationary trains; the earlier pullback interpretation is superseded.

The implementation agent owned product source and tests. This reviewer made no product edits, reported concrete issues during implementation, inspected their corrections, and ran the final full verification. The main session owns the accompanying specification updates.

## Findings (fixed)

- **Buffered presentation versus owner walking.** Previously a scene became available to walking while its older passenger packet was still inside the outer carriage frame. `PassengerMovementManager.present` now retains the session `ReentrantLock` through the existing `ViewerPacketFrame` flush. Walking and close cannot overtake that buffered target; the frame's finally block releases all retained locks even when the sender fails. The concurrent owner test pauses a real outer frame after `present`, then verifies ordered carriage/passenger and later walking payloads. No owner future is awaited while holding the frame.
- **Concrete carriage/session lock inversion.** The real inline path was session lock → native authority → audience reconcile → synchronized `CarExtension.refreshFor`, against carriage monitor → `present` → session lock. Passenger authority now reuses the existing native mover/metrics but coalesces only audience reconciliation into an existing one-tick owner callback. Its managed-identity, active, and current-vehicle checks prevent old reconciliation after release. Fixed Seat authority retains its immediate refresh path. Native positions, walking, and packet targets remain immediate.
- **Carriage-specific initial boarding.** An older whole-train scene from A could previously supply B's contact geometry even after B accepted a newer display frame. Initial contact now checks the candidate registration's own platforms; the regression uses two real car registrations with different accepted frames. Cross-platform walking still uses one complete scene and a stable carrier.
- **Allocation versus close.** Moving lifecycle work outside the shared lock initially left a spawn/publication race. The final code registers the real allocation completion in `removals` before spawn, then rechecks closed/watch/model identity before publishing the session. Close includes the in-flight allocation and rejects its late registration. The barrier regression verifies actual completion and removal, rather than merely queue acceptance.
- **Owned boundary handoff.** The final native sequence moves carrier/rider to the outside feet, calls `ServerPlayer.removeVehicle`, checks actual remaining carrier identity for event veto, then resets the connection movement baseline. It avoids the extra native DISMOUNT teleport. Ordinary Seat and lifecycle eject paths remain separate. Veto preserves the same active carrier and restrictions.
- **Outstanding boarding teleport.** The intermediate acknowledgement-takeover draft was removed. Every real acknowledgement stays on vanilla's path. The final owner-only query uses one strict cached `VarHandle` to the verified `ServerGamePacketListenerImpl.awaitingPositionFromClient: Vec3` field, without writing it, probing aliases, faking acknowledgement, or guessing a timeout. An early pending exit follows presentation until native state clears. The removed lease classes and packet interception are absent from both source and final JAR.

Additional checks confirmed that the exact complete `TrainFrameSnapshot` flows through `PendingTrainFrame` into the carriage presentation, zero-input ticks do not read future physics or send independent targets, stationary input integrates once per player tick, and stale native targets are rejected after newer walking/presentation or session release. Presentation uses captured yaw rather than reading player position on a carriage owner.

## Findings (not fixed)

- **Existing S100 test failure, outside this change.** `CarAnimationPlaybackTest.kt:85` compares the packaged `s100/behavior.js` with the working source. The ZIP references `../../../types/train-behavior.d.ts`; the source references `../../../schemas/types/train-behavior.d.ts`. Replacing only that source reference makes the byte sequences equal. All 66 tracked S100/source/ZIP/test files inspected match HEAD; no baseline repair was made.
- **Real server/client acceptance remains pending.** Unit entity/dispatcher doubles execute the production manager and geometry but do not execute Craft/NMS movement, the private-field read on a live connection, or the delayed audience callback against a running server. Native field ABI and detach/confirmation semantics were checked against cached 1.21.11 and the exact extracted Folia 26.1.2 classes. These static facts and the pending-state fixture do not prove actual client smoothness, native binding execution, or uninterrupted game movement.

No remaining task-scope source defect was found in the final diff. The specification mechanism matches the implementation; the main session was asked to include the actual per-presentation session-filter/surface-lookup cost in addition to geometry projection cost.

## Verification

Final command:

```sh
./gradlew --offline --no-daemon --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test :BukkitPlugin:shadowJar --continue
```

- Full XML: **968 tests; 966 passed, 1 failed, 1 skipped, 0 errors**. The sole failure is the unchanged S100 test above. Test heap is 2 GiB; no OOM occurred.
- `PassengerGeometryTest`: 8/8; `PassengerMovementTest`: 22/22; packet/Seat/frame tests remain passing. The final focused implementation run was 54/54 with no skips, recorded in `/tmp/jr-passenger-feedback-final-focused.log`.
- Kotlin/Java compile and shadowJar tasks succeeded/up to date for the unchanged final source. The product diff SHA-256 before and after the full run is `bc6cf8f2ba0015572dd6b20e4642ea8c092e9df7f8b67922fc7f93579626f0c4`.
- No dedicated lint task is configured in this module. `git diff --check` passes.
- JAR: `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`, SHA-256 **`29411cc6b2dafcd1c29beab6b503555f479e35299c68655fd43c5588da9cd3dc`**. Expected passenger/authority/bundle classes and `paper-plugin.yml` are present; `plugin.yml`, withdrawn mount-lease classes, and embedded PacketEvents classes are absent.
- Full log: `/tmp/jr-passenger-feedback-full.log`; XML: `BukkitPlugin/build/test-results/test`; HTML: `BukkitPlugin/build/reports/tests/test/index.html`.
- The first sandbox attempt failed before Gradle initialization because its lock service could not discover a wildcard IP. The same offline command was then run with the existing local build escalation. The startup failure is retained in `/tmp/jr-passenger-feedback-full-sandbox-startup.log` and is not counted as a test failure.

No commit, push, server mutation, or deployment was performed by this reviewer.
