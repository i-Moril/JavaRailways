# Core 逐文件审查清单

基线：`8f1d845d`，2026-10-10。全部条目已静态阅读并核对所属契约/测试；不是全部通过或全部修复。C1已读且源码未变化的runtime路径纳入本轮闭合，新增分区补足此前未覆盖的全部文件。结论见[整模块报告](./core-module-review.md)。

清单由当前`rg --files Core/src/main/java Core/src/test Core/src/testFixtures`逐项闭合；136个生产文件、49个测试源文件、5个fixture，另有build/Dokka/spec/相关文档。计数只用于发现遗漏，不代表审查百分比。

## 生产源码

以下均相对`Core/src/main/java/work/art1st/javarailways/core/`。

### 曲线、轨道、拓扑（43）

```text
api/physics/curve/Curve.kt
api/physics/curve/CurveException.kt
api/physics/curve/PrimeCurve.kt
api/physics/rail/Rail.kt
api/physics/rail/RailIntervalOccupancy.kt
api/physics/rail/RailLocation.kt
api/physics/rail/RailObject.kt
api/physics/rail/RailObjectLifecycleState.kt
api/physics/rail/RailObjectRef.kt
api/physics/rail/RailObjectSnapshot.kt
api/physics/rail/RailPositionSnapshot.kt
api/physics/rail/RailView.kt
api/physics/rail/RailwayOccupancySnapshot.kt
api/physics/rail/Reversible.kt
api/physics/rail/ViewDirection.kt
api/physics/rail/topology/ActiveRailContinuationsSnapshot.kt
api/physics/rail/topology/RailTopologyChange.kt
api/physics/rail/topology/TopologyState.kt
physics/rail/AtGradeRailCrossingIndex.kt
physics/rail/CoreRail.kt
physics/rail/PreparedRailConnectionPlan.kt
physics/rail/PreparedRailCreationBatch.kt
physics/rail/PreparedRailTopologyUpdate.kt
physics/rail/PreparedRailWorldSnapshotPlan.kt
physics/rail/RailGeometryBreakpoints.kt
physics/rail/RailLocationWithFacing.kt
physics/rail/RailLocationWithoutFacing.kt
physics/rail/RailSegment.kt
physics/rail/RailSpatialIndex.kt
physics/rail/RailTopologyEditor.kt
physics/rail/RailView.kt
physics/rail/SweptOccupancyIndex.kt
physics/rail/curve/CurveExt.kt
physics/rail/curve/PrimeCurveGenerator.kt
physics/rail/curve/PrimeCurveImpl.kt
physics/rail/curve/ReversedCurve.kt
physics/rail/persistence/PersistentRail.kt
physics/rail/persistence/PersistentRailLocation.kt
physics/rail/persistence/PersistentRailLocationWithFacing.kt
physics/rail/topology/CoreTopologyInputAdapter.kt
physics/rail/topology/TopologyInput.kt
physics/rail/topology/TopologyStateBuilder.kt
util/BinarySearch.kt
```

### 列车、控制、render值/trait（33）

```text
api/control/ControlCommand.kt
api/control/TrainController.kt
api/physics/exception/BogieException.kt
api/physics/exception/CannotConnectRailException.kt
api/physics/exception/CannotCreateTrainException.kt
api/physics/exception/CannotMoveForwardException.kt
api/physics/exception/CannotSplitException.kt
api/physics/exception/Collision.kt
api/physics/exception/InvalidActiveRailException.kt
api/physics/exception/PhysicsException.kt
api/physics/exception/RailException.kt
api/physics/train/Bogie.kt
api/physics/train/Car.kt
api/physics/train/Seat.kt
api/physics/train/Train.kt
api/physics/train/TrainExtension.kt
api/physics/train/TrainFrameSnapshot.kt
api/physics/train/TrainSnapshot.kt
api/render/DynamicRenderable.kt
api/render/Renderable.kt
api/render/RenderableExtension.kt
api/render/RenderableFrameSnapshot.kt
api/render/RenderableStateSnapshot.kt
control/signal/Signal.kt
physics/train/CoreBogie.kt
physics/train/CoreCar.kt
physics/train/CoreTrain.kt
physics/train/TrainEnd.kt
physics/train/TrainFactory.kt
physics/train/TrainPersistence.kt
util/trait/TrainComponentTrait.kt
util/trait/render/DynamicRenderableTrait.kt
util/trait/render/ExtendableRenderableTrait.kt
```

### 事件、线程、扩展、codec、DI与基础工具（49）

```text
api/event/CollisionEvent.kt
api/event/DerailEvent.kt
api/event/Event.kt
api/event/PassSignalEvent.kt
api/event/PostTickEvent.kt
api/event/PreTickEvent.kt
api/event/RenderableEvent.kt
api/event/TrainEvent.kt
api/event/TrainSwitchDirectionEvent.kt
api/event/processor/EventListener.kt
api/event/processor/EventRegistration.kt
api/event/processor/MainRailEventBus.kt
api/event/processor/PhysicsRailEventBus.kt
api/event/processor/TypedRailEventBus.kt
api/threading/ThreadContext.kt
api/threading/ThreadGuard.kt
api/threading/host/ThreadGuardHost.kt
api/threading/host/ThreadGuardPlatformBridge.kt
api/threading/host/ThreadGuardRegistration.kt
api/util/Destroyable.kt
api/util/WeakReferenceWrapper.kt
api/util/annotation/AsyncEventApi.kt
api/util/annotation/Concurrent.kt
api/util/annotation/Heavy.kt
api/util/annotation/MainThread.kt
api/util/annotation/Pure.kt
api/util/exception/CorruptedDataException.kt
api/util/persistence/LoadPersistentDataException.kt
api/util/persistence/PersistenceException.kt
api/util/plugin/Extendable.kt
api/util/plugin/Extension.kt
api/util/plugin/ExtensionData.kt
api/util/plugin/ExtensionDataContainer.kt
api/util/plugin/Plugin.kt
api/util/plugin/PluginRegistry.kt
api/util/plugin/SerializableExtensionData.kt
api/util/tick/Synchronizable.kt
api/util/tick/TickManager.kt
api/util/tick/TickMetrics.kt
api/util/tick/TickingObject.kt
di/JavaRailwaysKoin.kt
di/NoOpWorldParentContext.kt
di/WorldParentContext.kt
persistence/WorldSnapshotCodec.kt
util/AsyncTickThreadMarker.kt
util/CommitBuffer.kt
util/IsolatedSimulationThreadMarker.kt
util/ObjectPools.kt
util/ThreadGuardBridgeRegistry.kt
```

### CoreWorld、runtime/publication与整合（11）

```text
api/physics/RailwayEpochProcessor.kt
api/physics/RailwayWorldFrameSnapshot.kt
api/physics/World.kt
api/physics/WorldElement.kt
physics/CoreWorld.kt
physics/RailTopologyHealth.kt
physics/tick/LatestTrainFrameMailbox.kt
physics/tick/PhysicsCoordinator.kt
physics/tick/PublishedTrainFrameProcessor.kt
util/LatestTickCommitMailbox.kt
util/TickManagerImpl.kt
```

## 测试源码：逐文件处置（49）

路径相对`Core/src/test/`。这些是建议，尚未删除/改写测试；normal test实际执行206项、跳过6项，不能把源码阅读或disabled/stress当已执行覆盖。

- `java/work/art1st/javarailways/core/api/event/CollisionEventTest.kt`： 保留默认DERAIL/显式LINK业务决策，不等同联结integration。

- `java/work/art1st/javarailways/core/api/event/RailEventBusTest.kt`： 保留继承/token/thread/failure；补同type priority/签名/并发注册边界。

- `java/work/art1st/javarailways/core/api/physics/ImmutableSnapshotTest.kt`： 保留并增强：capture后修改live对象；单位校验去掉无用world fixture。

- `java/work/art1st/javarailways/core/api/physics/TopologyStateApiTest.kt`： 保留：revision-local节点/edge、immutable/read索引契约。

- `java/work/art1st/javarailways/core/api/threading/ThreadGuardTest.kt`： 保留身份token/线程marker；增强退出、nested/failure恢复。

- `java/work/art1st/javarailways/core/control/train/MockTrainController.kt`： 保留测试helper；永不finished command不能代表completion覆盖。

- `java/work/art1st/javarailways/core/persistence/SplitWorldSnapshotTest.kt`： 保留materialization/rail身份/topology；改finally关闭。

- `java/work/art1st/javarailways/core/persistence/WorldPersistenceRoundTripTest.kt`： 保留实际恢复；加强独立预期/fixed bad input/finally cleanup。

- `java/work/art1st/javarailways/core/physics/CoreWorldActiveContinuationsPublicationTest.kt`： 保留：active选择与structural revision分离；可增强往返切换。

- `java/work/art1st/javarailways/core/physics/CoreWorldAtGradeRailCrossingTest.kt`： 保留：几何交叉不产生跨线continuation、fault/恢复及增量观测。

- `java/work/art1st/javarailways/core/physics/CoreWorldRailTopologyTransactionTest.kt`： 保留并改写两个sleep reader：确定性barrier/finally。

- `java/work/art1st/javarailways/core/physics/CoreWorldThreadGuardTest.kt`： 保留结构入口wrong-thread及owner范围。

- `java/work/art1st/javarailways/core/physics/CoreWorldTopologyStatePublicationTest.kt`： 保留：旧root不可变、事务/no-op/失败publication。

- `java/work/art1st/javarailways/core/physics/PreparedRailCreationBatchTest.kt`： 保留：prepared capture、几何root和batch身份。

- `java/work/art1st/javarailways/core/physics/rail/PhysOvalConnectionTest.kt`： 保留：oval多车跨接头物理行为。

- `java/work/art1st/javarailways/core/physics/rail/PreparedRailConnectionPlanTest.kt`： 保留：连接准备、stale与物理校验。

- `java/work/art1st/javarailways/core/physics/rail/PreparedRailWorldSnapshotPlanTest.kt`： 保留：snapshot restore严格校验、选择保留与失败不提交。

- `java/work/art1st/javarailways/core/physics/rail/RailConnectTest.kt`： 保留：连接方向/结构；补fixture资源清理。

- `java/work/art1st/javarailways/core/physics/rail/RailDirectionRestrictionTest.kt`： 保留：Core方向中立；删除同用例内重复一行断言。

- `java/work/art1st/javarailways/core/physics/rail/RailIntervalReleaseDetectorTest.kt`： 保留生命周期；伪reverse的重复update并入持续占用。

- `java/work/art1st/javarailways/core/physics/rail/RailLocationPeekCurveTest.kt`： 保留：跨轨/反向peek与距离边界。

- `java/work/art1st/javarailways/core/physics/rail/RailLocationPersistTest.kt`： 保留：canonical AB恢复；补固定损坏引用并整理无用cast。

- `java/work/art1st/javarailways/core/physics/rail/RailTopologyEditorTest.kt`： 旧split/merge专用测试随废实现删除；现行prepared覆盖保留。

- `java/work/art1st/javarailways/core/physics/rail/SweptOccupancyIndexTest.kt`： 保留：候选排序/上限/blocking；补collision跨bucket容差。

- `java/work/art1st/javarailways/core/physics/rail/curve/PrimeCurveSurgeryTest.kt`： 保留：split/concatenate保持、LINE/DISCRETE差异；非纯roundtrip。

- `java/work/art1st/javarailways/core/physics/rail/curve/PrimeCurveTest.kt`： 合并/改写回显断言；补独立坐标/frame、稀疏LINE投影和坏输入。

- `java/work/art1st/javarailways/core/physics/rail/topology/TopologyStateBuilderTest.kt`： 保留：归一化、纯环、对称/coverage/方向、确定性。

- `java/work/art1st/javarailways/core/physics/tick/LatestTrainFrameMailboxTest.kt`： 保留coalescing/stale/concurrency/capacity；增强tail/swept/bogie隔离。

- `java/work/art1st/javarailways/core/physics/tick/PhysicsCapacityStressTest.kt`： 保留opt-in headless容量工具；本轮未执行性能matrix。

- `java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinatorTest.kt`： 保留真实epoch/barrier/publication/collision/failure；无限await改有界。

- `java/work/art1st/javarailways/core/physics/train/CoreCarPositioningRegressionTest.kt`： 保留normal回归；relocation补发布后目标/间距/finite断言。

- `java/work/art1st/javarailways/core/physics/train/CoreRenderCommitDecouplingTest.kt`： 保留：CoreCar/Bogie不进入generic mailbox的能力边界。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainAsymmetricTurnoutTest.kt`： 改写publish时序；检查本次移动的实际推进/脱轨，而非旧值。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainDerailEventTest.kt`： 保留wrong-thread；通知用真实coordinator触发，不复制生产queue分支。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainLinkTest.kt`： 保留端点清理；补车序/方向/frame/失败事务及换属主。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainMultiCarDiagnosticTest.kt`： 改写private buffer反射/公式自洽；用真实发布和独立数值。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainPersistenceStateTest.kt`： zero-distance项改真实PhysicsCoordinator；保留位置恢复。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainPublicationBoundaryTest.kt`： 保留AB/BA/placement/reverse/derail；补finally和speed边界。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainReversedPersistenceTest.kt`： 保留精确bogie恢复；失败项增加world/rail索引cleanup断言。

- `java/work/art1st/javarailways/core/physics/train/CoreTrainTailBogieTest.kt`： 保留尾架、支路、reversed间距/压缩修正；补fixture关闭。

- `java/work/art1st/javarailways/core/physics/train/DummyAsset.kt`： 空helper零引用，删除候选。

- `java/work/art1st/javarailways/core/physics/train/TrainConfigurationLoadTest.kt`： 随无生产consumer的prototype构建链删除，不保留反射填map框架。

- `java/work/art1st/javarailways/core/physics/train/TrainNameTest.kt`： 保留双索引/metadata；补invalid name失败保持。

- `java/work/art1st/javarailways/core/physics/train/TrainSlopeJointMotionTest.kt`： 保留短/长编组crossing；可参数化fixture。

- `java/work/art1st/javarailways/core/physics/train/TrainSlopeMotionTest.kt`： 保留实际位移/高度断言。

- `java/work/art1st/javarailways/core/physics/train/TrainUniformMotionTest.kt`： 重写本地distance空跑为真实ticket位移/速度；6项disabled另恢复。

- `java/work/art1st/javarailways/core/util/LatestTickCommitMailboxTest.kt`： 保留对象身份合并/并发/容量，不与train mailbox语义混同。

- `java/work/art1st/javarailways/core/util/TickManagerImplTest.kt`： 保留真实tick→commit交接/metrics；加强完整manager生命周期。

- `kotlin/work/art1st/javarailways/core/api/util/plugin/ExtensionDataContainerTest.kt`： 保留required/nullable/slot；增强扩容/host隔离。

## Fixtures（5）

- `Core/src/testFixtures/java/work/art1st/javarailways/core/physics/rail/RailTopologyTestFixtures.kt`：完整检查；train/rail helper补资源所有权与失败可诊断性，AsyncTick helper保留lexical marker边界。

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/RailTestUtils.kt`：完整检查；train/rail helper补资源所有权与失败可诊断性，AsyncTick helper保留lexical marker边界。

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/rail/topology/TopologyTestFixture.kt`：完整检查；train/rail helper补资源所有权与失败可诊断性，AsyncTick helper保留lexical marker边界。

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/train/TrainTestUtils.kt`：完整检查；train/rail helper补资源所有权与失败可诊断性，AsyncTick helper保留lexical marker边界。

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt`：完整检查；train/rail helper补资源所有权与失败可诊断性，AsyncTick helper保留lexical marker边界。

## 构建与文档

完整核查Core build/Dokka输入及全部Core契约：

- `Core/build.gradle.kts`：JDK17、dependencies、normal/stress分离、Dokka白名单与warning策略。
- `Core/src/main/dokka/Module.md`、`CorePackages.md`。

- `.trellis/spec/javarailways/core/index.md`。

- `.trellis/spec/javarailways/core/api-boundaries.md`。

- `.trellis/spec/javarailways/core/curve-geometry.md`。

- `.trellis/spec/javarailways/core/di-and-runtime-lifecycle.md`。

- `.trellis/spec/javarailways/core/event-bus.md`。

- `.trellis/spec/javarailways/core/extension-data.md`。

- `.trellis/spec/javarailways/core/persistence.md`。

- `.trellis/spec/javarailways/core/rail-topology-and-occupancy.md`。

- `.trellis/spec/javarailways/core/render-publication.md`。

- `.trellis/spec/javarailways/core/runtime-publication.md`。

- `.trellis/spec/javarailways/core/testing.md`。

- `.trellis/spec/javarailways/core/train-controller.md`。

- `.trellis/spec/javarailways/core/train-physics.md`。

- `.trellis/spec/javarailways/core/versioned-operational-topology.md`。

相关跨层规范：shared documentation-ownership/versioning/persistence/quality、指南与Core/Bukkit/Basic对应线程、持久化/寻路/基础设施契约，以及`ai_tasks/persistence_schema_evolution_rules.md`；只用于核对Core边界，不表示其他模块完成。

相关网页：`docs-site/docs/architecture/overview.md`、`modules.md`、`threading-model.md`、`persistence.md`、`rendering.md`，`docs-site/docs/extensions/api-status.md`、`api-reference.md`，以及`docs-site/docs/performance_assessment.md`与列车模型schema/reference的Core消费说明。只核查与Core实现对应内容，不宣称整站审查结束。

导航：`.codeabs/dirs/Core*.md`中rail/curve/train/API/control/render/event/runtime/tests/fixture相关地图，核查路径和职责描述；记录过期/TODO，不把导航当实现事实，不修改既有地图。

其他边界：本轮`find Core/src -type d -empty`未发现空源目录；不据此推断整个仓库无空目录。用户模型/生成物、docs-site既有修改均未清理。

## 验证边界

- 本轮强制重跑Core/Basic normal tests及Core HTML/Javadoc：成功；详细数字和warning见模块报告。
- 六条临时诊断使用实际Core类，结果见报告；不成为正式实现或长期测试。
- 未执行opt-in stress matrix、宿主实体/游戏客户端验证；其余静态发现并非全部已有运行复现。
- 无Core生产/测试文件改动；全项目任务仍in_progress，下一完整模块为Basic。
