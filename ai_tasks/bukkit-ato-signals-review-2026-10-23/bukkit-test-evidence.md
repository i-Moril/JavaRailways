> 分轮证据记录。早期覆盖数量/“未修改仓库”描述对应当时进度；最终问题状态与覆盖以 [review.md](review.md) 为准。本轮仅新增本目录文档。

# BukkitPlugin remaining tests: static continuation

Scope: merged READ entries from /tmp/jr-review-*-coverage.txt and /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv, then read all remaining 97 test/helper files in full. All 127 BukkitPlugin src/test files now have READ coverage in the union. Exact new file ranges in /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv. No repo changes, builds, tests, server or GUI execution. BukkitPlugin status remained clean at completion. RailVisualLifetimeTest was inadvertently reread by directory batch but is excluded from the 97 new-file count.

## Confirmed P2 engineering: world fixtures are never closed and remain globally registered

Same family as parent's RailMapPreviewServiceTest finding; merge, do not count as a new runtime defect for each file.

New exact creation sites:
- util/HeavyRailQueriesTest.kt:18 (entire test has no teardown).
- control/TrainControlManagerDirectionTest.kt:41,58 (AfterEach only closes ThreadGuard registration).
- tool/RailObjectDirectionDrawingTest.kt:105 (helper creates a world and returns only a rail; no retained fixture or teardown).
- tool/DirectionRestrictionTargetLookupTest.kt:59 (same helper ownership loss).
- tool/RailWandTurnoutGeometryPolicyTest.kt:132 (six test uses; returned TurnoutFixture is not closeable and no teardown).
- persistence/WorldSnapshotExtensionDataTest.kt:46 (first codec test creates a world only to generate a saved rail, without teardown).

Contract and real resource chain: Core testFixtures TrainTestUtils.kt:28-31 expressly says caller closes physics/tick manager; 37-44 allocates scheduled executor, TickManagerImpl and PhysicsCoordinator. PhysicsCoordinator.kt:201 registers itself in RailwaySimulationHalt. RailwaySimulationHalt.kt:11,29-35 stores coordinators in a strong concurrent set; PhysicsCoordinator.close unregisters at890 and shuts down executors at902+. Thus this is not merely a missing stylistic use block: tests retain world coordinator graphs across cases and later global-halt events include stale worlds. No runtime/thread exhaustion was measured; do not claim every unused executor starts a thread. Use TestWorldFixture or equivalent finally cleanup, including helper constructors that currently discard world ownership.

## Test effectiveness and boundaries

Read operation gateways use real JS and real Core split/reverse/destroy with controlled effect outlets; this is valuable integration coverage, not proof of Paper entity execution. WorldManagerUnload tests use deterministic callback latches and finally releases; WorldTickRunner/Registry and control isolation use bounded waits. Do not generalize the earlier ATO no-timeout test finding to these files.

WorldConstructionResources asserts reverse cleanup and suppressed failures. OrdinaryModel and model backend tests verify failure cleanup, immutable complete selections, appearance reset and partial construction. ConfigUIRegistry tests verify rejected writes retain dirty state, notification failures do not retry committed writes, and invalid batched registration does not partially publish. Migration tests include fixed input shapes, rejected versions, backup/gzip byte identity and output preconditions. ModelManagerReload covers bad-type retention, cross-package captured values, stale candidate reuse and source isolation. None of these observations constitutes dynamic passing results.

Some bounded limitations: RenderAudienceIndex concurrent watcher test repeats timing-sensitive scheduling (50 rounds) without forcing the target interleaving, so it is stress-style rather than deterministic race proof. Several TrainBehaviorRuntime tests close only at normal method end; failed assertions skip JS-runtime close, a lesser failure-path hygiene gap (normal close is present). SimulationTrainOperationGateway Fixture.close closes physics/tick/gateway sequentially, so an earlier teardown exception can skip later close; this is not the confirmed never-close family. Tests of packet metadata/viewer policy do not exercise network/client interpolation; display smoothing constants alone cannot prove visual acceptance.

No additional production defect established by this test continuation. Existing module findings remain owned by parent/production reviewers. Source-level inspection only.
