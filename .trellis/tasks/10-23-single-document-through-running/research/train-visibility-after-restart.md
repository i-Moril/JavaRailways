# Research: train visibility after restart

- Query: Why does WebUI show a train while the Minecraft world shows no train model after deployment?
- Scope: internal source analysis; live metrics supplied by main agent
- Date: 2026-10-23

## Findings

### Concrete initial-frame loss

A restored stationary train has a queued initial frame that becomes obsolete before the Bukkit visual consumer first runs:

1. `Core/.../physics/train/TrainPersistence.kt:25-36`: `SavedTrainV1.load` reconstructs the physical train, restores persisted placement, calls creation `publish()`.
2. `Core/.../physics/train/TrainCreation.kt:94-98`: creation publication invokes `CoreWorld.publishOwnerChanges`.
3. `Core/.../physics/CoreWorld.kt:1273-1288`: owner publication builds and installs the exact immutable current frame and enqueues it in the visual mailbox.
4. `BukkitPlugin/.../logic/WorldManager.kt:299-318`: restored train/car Bukkit extensions are installed, but this does not apply a frame or register a car in the render index.
5. `Core/.../physics/tick/PhysicsCoordinator.kt:690-704`: every completed epoch creates a NEW published train frame, even if stopped; `shouldPublishMotionFrame` at lines 533-545 suppresses replacement mailbox publication for stationary unchanged trains.
6. `BukkitPlugin/.../logic/WorldManager.kt:541-553`: visual drain occurs AFTER completed physics publication. At lines 646-648 it drains the old initial mailbox frame and rejects it because `train.publishedFrame !== trainFrame`.
7. No region task reaches `CarExtension.applyPublishedFrame` (307-332), so no `RenderChunkManager.update` registration occurs. A later player join cannot discover this unindexed car.

This directly matches the supplied live counters: physics active=0/1, visual mailbox peak=1, visual region tasks=0, audience candidates/drains/processed=0, packet platform created=0. WebUI can still show the train because physical world publication is present. Source proof supports the mechanism; this research did not execute a reproduction test or modify the server.

This code path already exists in baseline `f045499d`; `git diff --stat f045499d 62e9f831 -- Core Basic BukkitPlugin` shows no Core/Basic production changes and only Bukkit HTTP/script-worker production changes. The latest deployment restart can expose an existing defect without a render source regression in the feature commit.

### Render restore and visibility contracts

- `CarExtension.kt:93-103` restores `carModelName`, model/package hashes and resource type. It intentionally builds models lazily when an audience is first exposed.
- `TrainComponentExtension.kt:107-129` handles first send/build; failed first build remains retryable.
- `RenderChunkManager.kt:123-164` refreshes player audiences on join/move/teleport/world change; `update` at 324-342 installs car position in the spatial index. Without this update, player refresh has nothing to select.
- `EntityLibDisplayRenderBackend.kt:25-64` constructs and spawns display wrappers, independently of Core logical train existence.

### Diagnostic surfaces

- `/jr issues <world>`; HTTP authenticated `/api/v1/worlds/{world}/issues` via `WebUiApplication.java:113` and `TimetableHttpBridge.kt:192-205`.
- Model build exceptions report source `javarailways:train-model`, code `MODEL_UNAVAILABLE:<carId>` in `CarExtension.kt:359-366`; missing model snapshot message explicitly says pinned model snapshot unavailable.
- `/jr perf <world>`: visual mailbox, region pending/tasks, audience processed/candidates, packet/backend counters; `JavaRailwaysCommand.kt:1307-1344`.
- No model load error is expected if the lost initial-frame path prevents indexing/build entirely.

### Related specs and tests

- `.trellis/spec/javarailways/core/render-publication.md`: immutable current-frame consumption and stationary visual suppression.
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md:110-132`: stream ownership guards and latest-wins visual pipeline.
- `Core/.../PhysicsCoordinatorTest.kt:728-745` tests stationary suppression, but first drains initial mailbox before epochs; therefore does not cover retained initial frame crossing first epoch then Bukkit identity rejection.
- Existing Bukkit frame tests cover stream transfer and stale tasks; research found no restored stationary train integration test exercising this initial visual drain timing.

## Caveats / Not Found

- Read-only research only; no Gradle, production source edits, deployment, data writes, or runtime commands performed.
- Live counters came from main session, not independently collected here.
- Follow-up correction: the simulation-owner queued-frame identity check is unnecessary when queued entries serve as refresh notifications and the consumer reads the current instance’s own complete snapshot. Existing downstream runtime-stream/revision checks remain relevant to already-scheduled car tasks. See `render-refresh-fix.md` for the implemented boundary and red/green regression evidence.
- No external references were needed. Memory registry was searched for context, but conclusions above come from current source, not memory-derived facts.
