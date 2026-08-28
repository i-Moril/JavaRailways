# 持久化与测试

## 当前 snapshot 边界

`CoreWorld` 分开拥有：

- `SavedInfrastructureV2`：`formatVersion`、`infrastructureRevision`、rails；
- `SavedRuntimeV1`：`formatVersion`、同一 `infrastructureRevision`、`physicsEpoch`、trains。

`infrastructureRevision` 用于文件配对，`physicsEpoch` 用于发布时序，两者都不是 schema version。`CoreWorld.load` 必须先校验 version/revision，再 materialize rail connections 和 trains；失败信息应包含可定位的 train/rail identity。

`WorldSnapshotCodec` 使用 `ignoreUnknownKeys = false`、`encodeDefaults = true`。保持 strict current decode；新增可选/默认字段不自动升版，breaking change 只升级其所属独立边界并按任务合同决定 migration。

当前 extension decode 仍通过保存的 JVM class name 和 `Class.forName()`；这是已记录风险，不得复制到新 extension registry。新边界遵循共享规范中的 stable `typeId`。

证据：`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`、`Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt`、`ai_tasks/persistence_schema_evolution_rules.md`。

## 测试 fixtures

`Core` 启用 `java-test-fixtures`。跨模块测试复用 fixture，而不是在 Basic/Bukkit 复制内部构造流程：

- train/rail/world builders 位于 Core test/support 源集；
- async worker contract 通过 `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt` 安装与生产 scheduler 相同的 marker；
- `Basic/build.gradle.kts`、`BukkitPlugin/build.gradle` 已用 `testFixtures(project(":Core"))`。

新增 fixture 只暴露稳定测试意图，不要让 consumer test 依赖更多内部 mutable state。

## 测试模式

- Physics：显式创建/关闭 `PhysicsCoordinator`，控制 clock/delta，断言 ticket epoch、publication 与 failure；参考 `PhysicsCoordinatorTest.kt`。
- Persistence：固定 JSON invalid/version fixture + encode/decode/materialize round-trip；参考 `WorldPersistenceRoundTripTest.kt`。
- Topology/rail/train：覆盖 AB/BA、split/connect、occupancy、derail/collision、immutable snapshot，不只测 happy path。
- Thread：使用 marker/guard 验证错误线程 fail-fast，测试结束关闭 executor/coordinator，避免泄漏 daemon 以外线程。

禁止：

- 为通过旧 fixture 全局忽略未知字段；
- 只做 new writer → new reader 自洽 round-trip；
- 用 sleep 猜测 epoch 完成而不等待 ticket/latch；
- 在 consumer module 直接复制 Core 内部 test builder。
