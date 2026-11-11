# 已批准的本地提交方案

仅包含独立工作区中本批拥有的文件；不推送，不改动主工作区其他WIP。用户于2026-10-19批准按以下顺序进行两次本地提交。

## docs(simulation): record isolated runtime plan and first-batch validation

- `.trellis/tasks/10-18-isolated-simulation-runtime/check.jsonl`
- `.trellis/tasks/10-18-isolated-simulation-runtime/design.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/implement.jsonl`
- `.trellis/tasks/10-18-isolated-simulation-runtime/implement.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/prd.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/research/current-state.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/task.json`
- `.trellis/tasks/10-18-simulation-behavior-features/check.jsonl`
- `.trellis/tasks/10-18-simulation-behavior-features/design.md`
- `.trellis/tasks/10-18-simulation-behavior-features/implement.jsonl`
- `.trellis/tasks/10-18-simulation-behavior-features/implement.md`
- `.trellis/tasks/10-18-simulation-behavior-features/prd.md`
- `.trellis/tasks/10-18-simulation-behavior-features/task.json`
- `.trellis/tasks/10-18-simulation-signal-features/check.jsonl`
- `.trellis/tasks/10-18-simulation-signal-features/design.md`
- `.trellis/tasks/10-18-simulation-signal-features/implement.jsonl`
- `.trellis/tasks/10-18-simulation-signal-features/implement.md`
- `.trellis/tasks/10-18-simulation-signal-features/prd.md`
- `.trellis/tasks/10-18-simulation-signal-features/task.json`
- `.trellis/tasks/10-18-world-epoch-events/check.jsonl`
- `.trellis/tasks/10-18-world-epoch-events/design.md`
- `.trellis/tasks/10-18-world-epoch-events/implement.jsonl`
- `.trellis/tasks/10-18-world-epoch-events/implement.md`
- `.trellis/tasks/10-18-world-epoch-events/prd.md`
- `.trellis/tasks/10-18-world-epoch-events/research/commit-plan.md`
- `.trellis/tasks/10-18-world-epoch-events/research/validation.md`
- `.trellis/tasks/10-18-world-epoch-events/task.json`
- `.trellis/tasks/10-18-world-runtime-isolation/check.jsonl`
- `.trellis/tasks/10-18-world-runtime-isolation/design.md`
- `.trellis/tasks/10-18-world-runtime-isolation/implement.jsonl`
- `.trellis/tasks/10-18-world-runtime-isolation/implement.md`
- `.trellis/tasks/10-18-world-runtime-isolation/prd.md`
- `.trellis/tasks/10-18-world-runtime-isolation/task.json`

## feat(core): publish world-scoped physics epoch events

- `.trellis/spec/javarailways/core/api-boundaries.md`
- `.trellis/spec/javarailways/core/event-bus.md`
- `.trellis/spec/javarailways/core/index.md`
- `.trellis/spec/javarailways/core/runtime-publication.md`
- `.trellis/tasks/10-09-stage2-code-review/implement.md`
- `.trellis/tasks/10-09-stage2-code-review/research/core-event-api-assessment.md`
- `.trellis/tasks/10-09-stage2-code-review/research/issues.md`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainReverseBehaviorListener.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/debug/TrainDebugEventListener.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceServiceTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/behavior/SimulationEventBoundaryTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/render/model/AbstractModelAnimationTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/render/model/CompositeModelVisibilityTest.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/CollisionEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/Event.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/PostTickEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/PreTickEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/TrainEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/TrainLinkEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/WorldEvent.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/processor/PhysicsRailEventBus.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/World.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/WorldExecutionMode.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt`
- `Core/src/test/java/work/art1st/javarailways/core/api/event/CollisionEventTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/api/event/WorldEventTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/tick/PhysicsCapacityStressTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/tick/PhysicsEpochEventsTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainCollisionCouplingTest.kt`
- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/train/TrainTestUtils.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt`

## 排除与后续

独立工作区没有不明归属的修改。主工作区既有WIP全部排除；提交后仍需父任务整合，不在本批直接覆盖主工作区的PhysicsCoordinator与规范修改。后续子任务未启动。
