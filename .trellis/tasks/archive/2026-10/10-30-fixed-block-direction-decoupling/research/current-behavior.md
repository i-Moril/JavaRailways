# 当前行为与规划证据

## 范围

静态源码、测试和仓库规范研究，没有运行构建／测试或远端／游戏内验收。基线 `dev/signal-system`，HEAD `a59606ba6de0df9a134ff37fe9559bf64b6de680`；行号用于该基线定位。

## 闭塞创建、匹配和运行

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionTopology.kt:123-130`：只接受方向全 null、全等于 traversal.facing 或全等于反向；backward 时自动倒转保存路径。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockDefinition.kt:34-46`：保存 `bidirectional = false`。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockCatalog.kt:65-75`：已有反向几何，entryTraversals 是否包含反向入口依赖 flag。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionAuthoringActions.kt:53-59`：创建提示区分单／双向；blockEnds 已可描述两端。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockDirectionPolicy.kt:14-39`：整块方向失配和反向 window 拒绝。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockRouteMatcher.kt:101-132`：按旧 flag 跳过反向；窗口已有 `reversed`、`enteredAtBoundary`。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockRouteResourcePolicy.kt:96-132`：旧策略通过后按 block ID 合并资源，释放点是最后已申请出口。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockInspectionSource.kt:33-91`：绑定、依赖收集、终端补全都检查整块方向；内部起步 floor 与真实入口已分开。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SectionOccupancyRuntime.kt:296-308,341-373`：blockControls 可覆盖两端状态，方向变化按候选索引处理。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt:1354-1356`：额外 admission validation 检查 unavailable territory，不是通用运营方向验证。

## 信号入口与扫描

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/FixedBlockSignalEntranceResolver.kt:13,31-43,71-76`：0.75m 入口吸附和向内 facing 匹配；reverse 受 flag 限制。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/FixedBlockSignalCompiler.kt:37-39`：单向 block 不可绑定 reverse signal。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandBackend.kt:355-368`：rebind 按 flag 过滤。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalObservationGraphPublication.kt:80-96`：仅双向 block 建立 reverse section。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/FixedBlockSignalDefinition.kt:6-7`：FORWARD/REVERSE 相对作者路径，与单轨 AB/BA 不同。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/ProtectionRouteDiscovery.kt:24-28`：保护区域逐轨允许方向生成进路，不要求整区统一。本批不改写该算法。

## Basic 和 SimpleATO 的既有能力

- `Basic/src/main/java/work/art1st/javarailways/basic/rail/OperatingDirectionStore.kt`：唯一公共稀疏方向存储及完整子集读取；所有权见 `.trellis/spec/javarailways/basic/operating-directions.md`。
- `Basic/src/main/java/work/art1st/javarailways/basic/api/route/planning/OperatingDirectionOverlay.kt:21-38`：单向的相反方向转换成 forbiddenTraversals。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/routing/RoutePlanningRestrictionRuntime.kt:34-63`：方向和其他选路限制在搜索前组合。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/routing/FrozenMissionRouteAssembler.kt:23-29`：运营末端独立于完整授权末端。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/routing/TerminalRouteMargin.kt:20-64`：新增余量检查禁行；已有路径够长时直接返回，不能用作完整路径验证器。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/inspection/InspectionRuntime.kt:109-154`：source 接管时合并其后缀；未接管才使用宿主余量 failure。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt:3378-3402`：补全后检查总长覆盖停点加安全距离；新合同必须另外证明实际前缀方向合法。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockRouteMatcher.kt:32-69`：终端补全仅裁剪当前闭塞的已编译后缀，不另行选分支。
- `Basic/src/main/java/work/art1st/javarailways/basic/api/route/RouteResourceIntent.kt:111-159`：通用冻结路线／请求边界，无闭塞类型字段；本批不改该合同。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/admission/RouteAuthorityAdmissionClient.kt:400-440`：真实 Basic 边界通过现有 authority source 发布为 MovementAuthorityRestriction；同一 snapshot 可增加独立方向限制。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/restriction/TrainRestrictionConstraintAdapter.kt` 和 `api/constraint/AtoConstraint.kt`：已有 movement authority 转约束及全局 safetyDistance，不需要新制动器。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/signal/SignalEpochRuntime.kt:76-82,151-177`：方向按 rail 合并／路由；无 source 快速返回须检查自动路线自身失效接线。

## 存档和测试

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitions.kt:150-166,248-265`：保存 flag，专用 Boolean serializer，current schema 1，严格未知字段。
- 当前 fixture 位于 `SimpleSignals/src/test/resources/`，包括 `directional-fixed-block-signals.json`、`fixed-block-without-bidirectional.json` 和设备定义样例。删除字段时更新 current fixture，不新增旧 reader。
- `.trellis/spec/simple-signals/backend/index.md:95-120`：本边界按未发布 schema 1 管理；仍须遵守版本前置门槛，开发服文件不是发行证据。
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockStopContinuationTest.kt` 已使用真实 Basic claim 和 cursor 验证相向冲突、停车换向。
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/block/StoppedReturnToDepotIntegrationTest.kt:76-84,120-128,321-325`：已有真实 50ms Dispatcher，但 fixture 全双向。需新增混合方向场景，不能据此宣称当前需求已覆盖。
- 根 `build.gradle:47-55` 的 `verifySignalModuleBoundaries` 和 `SimpleSignals/build.gradle:115` 的 `verifyPluginJar` 是现有验证任务；`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/signal/SimpleSignalsSimulationFeatureFactory.java` 是实际 Java consumer。

## 要替换的旧规范与已否决方向

以下 CURRENT 条款需在实现通过后同步更新，不得覆盖本任务已确认需求：

- SimpleSignals `bidirectional-fixed-block.md`、backend index、section wand 中的独立 flag、整块失配、reverse 仅限双向 block 合同。
- SimpleATO terminal completion 中旧方向政策、完整物理授权与实际运行限制的关系。
- Basic operating-directions 文档里 SimpleSignals 以方向判断整块有效的消费方式；存储和 API 不改。

本次会话已撤回“必须修改 Basic 授权接口”的建议。不引入 Basic/Core 新字段、多闭塞方向锁或手动 ATS。旧任务中有关低速降级和旧方向模块归属的研究不是本批目标合同。
