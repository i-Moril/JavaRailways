# 本批最终提交计划

本清单只包含当前批次拥有的修改；基线`8f156900`，工作分支`codex/world-runtime-isolation`。O1/O2已补齐，最终门禁证据见[验证记录](validation.md)与[候选清单](final-candidate.json)。第三批信号JS与第四批车厢行为仍未启动。

原提交清单在范围扩充后已暂停，本清单替换它。2026-10-19用户回复“可以，你可以继续”，已确认本清单，可按仓库workflow 3.4执行。仅本地提交并整合到dev/signal-system，无push或部署；未识别修改：无。临时依赖symlink不纳入提交，最终提交前移除。父任务和子任务3的规划变更与本批剩余锁证据一并提交，任务3保持planning。

## docs-site独立仓

`docs: describe world runtime isolation and validate model packages`

- docs/architecture/threading-model.md
- scripts/validate-model-packages.mjs

先提交独立仓，主仓第二笔记录gitlink；不纳入生成网站或build文件。

## 主仓第1笔

`feat(core): add per-world host ownership and termination barriers`

- .trellis/spec/javarailways/core/index.md
- .trellis/spec/javarailways/core/runtime-publication.md
- Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt
- Core/src/main/java/work/art1st/javarailways/core/physics/WorldExecutionOwner.kt
- Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt
- Core/src/main/java/work/art1st/javarailways/core/util/AwaitExecutorTermination.kt
- Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt
- Core/src/test/java/work/art1st/javarailways/core/physics/WorldExecutionOwnerTest.kt

## 主仓第2笔

`feat(runtime): isolate world scheduling and observation construction`

Bukkit调度入口与共同领域装配一并接入，保留中间版本的装配一致性；本笔包括O1/O2 observation转换隔离及生命周期回归、实际owner规范和完整验证/计划记录。

- .trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md
- .trellis/spec/docs-site/frontend/docusaurus-reference-ci.md
- .trellis/spec/javarailways/basic/threading-and-publication.md
- .trellis/spec/javarailways/simpleato/index.md
- .trellis/spec/javarailways/simpleato/simulation.md
- .trellis/tasks/10-09-stage2-code-review/implement.md
- .trellis/tasks/10-09-stage2-code-review/research/issues.md
- .trellis/tasks/10-18-isolated-simulation-runtime/check.jsonl
- .trellis/tasks/10-18-isolated-simulation-runtime/design.md
- .trellis/tasks/10-18-isolated-simulation-runtime/implement.jsonl
- .trellis/tasks/10-18-isolated-simulation-runtime/implement.md
- .trellis/tasks/10-18-isolated-simulation-runtime/prd.md
- .trellis/tasks/10-18-isolated-simulation-runtime/research/remaining-cross-world-locks.md
- .trellis/tasks/10-18-isolated-simulation-runtime/task.json
- .trellis/tasks/10-18-simulation-signal-features/check.jsonl
- .trellis/tasks/10-18-simulation-signal-features/design.md
- .trellis/tasks/10-18-simulation-signal-features/implement.jsonl
- .trellis/tasks/10-18-simulation-signal-features/implement.md
- .trellis/tasks/10-18-simulation-signal-features/prd.md
- .trellis/tasks/10-18-simulation-signal-features/task.json
- .trellis/tasks/10-18-world-runtime-isolation/check.jsonl
- .trellis/tasks/10-18-world-runtime-isolation/design.md
- .trellis/tasks/10-18-world-runtime-isolation/implement.jsonl
- .trellis/tasks/10-18-world-runtime-isolation/implement.md
- .trellis/tasks/10-18-world-runtime-isolation/prd.md
- .trellis/tasks/10-18-world-runtime-isolation/research/check-result.md
- .trellis/tasks/10-18-world-runtime-isolation/research/commit-plan.md
- .trellis/tasks/10-18-world-runtime-isolation/research/final-candidate.json
- .trellis/tasks/10-18-world-runtime-isolation/research/lock-ownership.md
- .trellis/tasks/10-18-world-runtime-isolation/research/runtime-assembly.md
- .trellis/tasks/10-18-world-runtime-isolation/research/validation.md
- .trellis/tasks/10-18-world-runtime-isolation/task.json
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceService.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultRailwayObservationService.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultSignalEpochSourceService.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/JavaRailwaysCommand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/TrainCommand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlDialog.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlManager.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/TrainResourceReloader.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldTickRunner.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/AbstractWand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/RailObjectWand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/RailWand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/RailWandTopologyEditPlan.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/TurnoutWand.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/BukkitThreadGuardBridge.kt
- BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/LiveWorldManagement.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceServiceTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultRailwayObservationServiceTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultSignalEpochSourceServiceTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/control/TrainControlWorldIsolationTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/WorldHttpRequestIsolationTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/logic/WorldManagerUnloadTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/logic/WorldTickRunnerRegistryTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/logic/WorldTickRunnerTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/util/BukkitThreadGuardBridgeTest.kt
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/util/LiveWorldManagementTest.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/SimpleATOWorldData.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/TimetableExtension.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/runtime/OperationalWorldRuntime.kt
- SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/api/timetable/TimetableExtensionRegistryTest.kt
- SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt
- docs-site

## 后续记账

工作提交整合后只归档本子任务，按workflow记录journal。父任务、信号和车厢子任务保持开放；不把本批已完成等同全feature模拟验收完成。

2026-10-19执行完成：docs-site `cb9263b`、Core `5d8cdabe`、runtime `1423116d`。dev/signal-system与主docs工作区均快进整合，产品字节与2395项通过候选一致。
