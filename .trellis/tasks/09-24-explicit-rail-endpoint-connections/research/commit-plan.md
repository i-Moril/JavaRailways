# Approved local commit plan

1. `fix(persistence): persist exact rail endpoint identities`

当前格式、全部consumer与离线转换属于同一可用变更单元，随测试与owner规范一起提交，避免中间提交留下旧工具无法输出current的问题。
只提交下列文件及本任务规划/证据；不push、不部署。受影响完整门禁结果见[验证记录](implementation-validation-2026-10-17.md)。

## Product, tests and owner contracts

- `.trellis/spec/javarailways/core/persistence.md`
- `.trellis/spec/javarailways/core/rail-topology-and-occupancy.md`
- `.trellis/spec/bukkit-plugin/backend/offline-development-migration.md`
- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/CoreRail.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/PreparedRailTopologyUpdate.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/PreparedRailWorldSnapshotPlan.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/SavedRailEndpointDecoder.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/SavedRailEndpointRef.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/CoreWorldRailTopologyTransactionTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/rail/PreparedRailWorldSnapshotPlanTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/rail/RailConnectTest.kt`
- `Core/src/test/java/work/art1st/javarailways/core/physics/rail/SavedRailConnectionsTest.kt`
- `Basic/src/test/java/work/art1st/javarailways/basic/infrastructure/PhysicalTopologyReplacementOrRemovalImpactResolverTest.kt`
- `BukkitPlugin/build.gradle`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/PreparedRailWorldUndo.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/persistence/RailInfrastructureMetadataMigrationTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/persistence/EndpointWorldMigrationTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/tool/WandActionUndoSupportTest.kt`
- `scripts/offline-world-migration/DevelopmentWorldMigration.kt`
- `scripts/offline-world-migration/RailEndpointMigration.kt`
- `scripts/offline-world-migration/README.md`

## Task records

- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/check.jsonl`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/design.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/implement.jsonl`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/implement.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/prd.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/research/commit-plan.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/research/endpoint-save-audit-2026-10-17.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/research/implementation-validation-2026-10-17.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/research/planning-before-2026-10-17.md`
- `.trellis/tasks/09-24-explicit-rail-endpoint-connections/task.json`

## Existing unrelated dirty paths — excluded

以下为会话开始已有或其他工作的路径，本批不改动、不纳入提交；目录项代表其子文件。

- `.trellis/spec/bukkit-plugin/backend/index.md`
- `.trellis/spec/javarailways/core/event-bus.md`
- `.trellis/spec/javarailways/core/runtime-publication.md`
- `.trellis/spec/simple-signals/backend/device-diagnostics.md`
- `.trellis/spec/simple-signals/backend/index.md`
- `.trellis/spec/simple-signals/backend/js-signal-rules.md`
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`
- `.trellis/tasks/09-18-signal-addon-implementation/design.md`
- `.trellis/tasks/09-18-signal-addon-implementation/implement.md`
- `.trellis/tasks/09-18-signal-addon-implementation/prd.md`
- `.trellis/tasks/09-18-signal-addon-implementation/research/dev-server-deployment-2026-10-17.md`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/event/processor/TypedRailEventBus.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt`
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalDeviceEditor.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/WandDialogs.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionSignalCompiler.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalPackEpochRuntime.kt`
- `SimpleSignals/src/main/resources/i18n/wands_en_US.properties`
- `SimpleSignals/src/main/resources/i18n/wands_zh_CN.properties`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandlerTest.kt`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntimeManagerTest.kt`
- `TODO.md`
- `ai_tasks/physics_consistency_hardening_plan.md`
- `docs-site`
- `.trellis/spec/bukkit-plugin/backend/error-logging.md`
- `.trellis/tasks/09-18-signal-addon-implementation/research/error-file-logging-discussion-2026-10-17.md`
- `.trellis/tasks/09-18-signal-addon-implementation/research/same-epoch-observation-2026-10-17.md`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/diagnostics/`
- `BukkitPlugin/src/test/kotlin/work/art1st/javarailways/bukkit/api/`
- `ai_tasks/current_signal_rules_research_handoff.md`
- `models/JavaRailways-editor-markers-26.1.2.zip`
- `models/jr-four-light-26.1.2.zip`
- `models/rails.zip`
- `models/rails/`
- `models/resourcepack.zip`
- `models/resourcepack/assets/javarailways/`
- `models/resourcepack/assets/minecraft/items/paper.json`
- `models/s100.zip`

## Approval gate

仓库workflow Phase3.4要求“Present the plan once, ask for one-shot confirmation”。用户已确认本计划；按上述清单执行本地提交，不push或部署。
