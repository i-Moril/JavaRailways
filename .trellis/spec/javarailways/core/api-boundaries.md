# Core API 边界契约

## 适用场景与用途

触发：修改 `Core/src/main/java/work/art1st/javarailways/core/api/`、`Core/build.gradle.kts` Dokka 配置，或让 Basic/Bukkit/Tunnel 使用 Core 能力。

用途：阻止 implementation object、live mutable state 或平台类型成为新的跨模块承诺。`core.api.*` 是 `1.0-SNAPSHOT` 候选面，不是 binary compatibility 保证；新 consumer 仍必须以它为首选边界。

## Owner、类型与 dependency contract

- owner：Core 定义 API；consumer 只消费。Core production 不得 import Basic、Bukkit、Paper 或 Minecraft。
- Dokka publication 只保留 `work.art1st.javarailways.core.api.*`；普通 `public` implementation 不成为 API。
- 认可的跨线程 value 类型：`RailPositionSnapshot(UUID, ViewDirection, Double)`、`RailTraversalSegmentSnapshot`、revision-rooted `TopologyState`及其`RailCrossingPoint/AtGradeRailCrossing`、`ActiveRailContinuationsSnapshot`、`RailObjectRef(namespace,id)`、`RailObjectSnapshot`、`TrainSnapshot`、`Vector3Snapshot`、`RenderableFrameSnapshot`、`CarFrameSnapshot`、`TrainFrameSnapshot`、`RailwayOccupancySnapshot`、`RailwayWorldFrameSnapshot`。`RailwayEpochProcessor`是同步value-only callback，不暴露live Core对象。
- live 类型：`World`、`Train`、`Rail`、`RailView`。`World.allTrains` 与 `RailView.nextRails` 不得当 immutable collection 缓存。
- `@MainThread`、`@Heavy`、`@Concurrent`、`@Pure` 只表达 contract，不调度；只有实现显式调用 `ThreadGuard.ensure*` 才 runtime fail-fast。

`RailPositionSnapshot` 的真实签名和单位（`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailPositionSnapshot.kt`）：

```kotlin
data class RailPositionSnapshot(
    val railId: UUID,
    val facing: ViewDirection,
    val arcLengthInAbMeters: Double
)
```

`arcLengthInAbMeters` 必须 finite、`>= 0`；它不保留 live `Rail`。完整 render frame contract 归[render publication](./render-publication.md)。

`RailwayWorldFrameSnapshot(physicsEpoch,topologyRevision,trains,occupancy,activeContinuations)`是世界级一致发布，不是若干独立getter的容器。它要求所有train frame与occupancy属于同一physics epoch、train ID唯一、集合不可变并按稳定ID排序。`occupancy`与Core collision共享一次构建的immutable bucket index，consumer只做局部区间查询，不从`trains`重建world index。`activeContinuations`只包含turnout port的live有向选择，使用stable `RailEndpointRef`，并与frame的`topologyRevision`一致；map缺项表示该entry当前没有选中出口。`topologyRevision`仅表示runtime structural topology，`selectionRevision`仅表示process-local live branch变化，两者都不得当作持久化schema version或Basic infrastructure revision。

`RailwayEpochProcessor.process(frame)`在同一世界的physics coordinator线程串行执行。输入是尚未公开的完整候选world frame；callback返回前ticket不能完成，callback抛错则该frame不发布并令coordinator Stall。processor不能调用Bukkit、保留live Core对象或把安全处理排入下一epoch；完整顺序由[runtime publication](./runtime-publication.md)拥有。

`AtGradeRailCrossing`只携带按UUID稳定排序的两项`RailCrossingPoint(railId,arcLengthAbMeters)`；弧长为canonical AB米制位置。它是所属immutable `TopologyState`的几何观测，不是persistent资源身份、可跨线port或独立持久化边界。consumer必须与同一topology root的rail projection一起使用。

## 边界结果矩阵

| 场景 | 必须结果 | 禁止结果 |
| --- | --- | --- |
| API 能表达 consumer 需求 | 只 import `core.api.*` | cast 为 `CoreWorld/CoreTrain` |
| API 缺少能力 | 增加最小 interface/service/value DTO，写明 owner/thread/lifecycle | 暴露 implementation map/location |
| 跨线程读取 | capture 已认可 value DTO，collection defensive copy | 仅凭 `Snapshot` 名称传 `RenderableStateSnapshot` |
| API 变更 | 搜索所有 consumer，生成 Dokka并运行 downstream tests | 只因 Core compile 成功即合并 |
| 平台 side effect | stable request/value → adapter scheduler | Core 接收 Bukkit/Paper 类型 |

API KDoc 必须写 identity、单位、调用线程、blocking、live/value、failure/result、注册与释放；无法写清这些字段的类型不得新增到候选面。

## 项目内正确示例

`RailPositionSnapshot.capture` 在边界复制 stable identity 与 AB 米制弧长，而不保留 live rail：

```kotlin
fun capture(location: RailLocation, facing: ViewDirection): RailPositionSnapshot =
    RailPositionSnapshot(
        railId = location.rail.id,
        facing = facing,
        arcLengthInAbMeters = location.arcLengthIn(ViewDirection.AB)
    )
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailPositionSnapshot.kt`。它解决 worker/platform 跨阶段读取同一位置时混入后续 live topology 的问题。

## 禁止实现

```text
错误：public fun state(): CoreTrain / RailLocationWithFacing / MutableMap<...>
正确：public fun state(): TrainSnapshot / RailPositionSnapshot / immutable result
```

不得以现有 implementation leakage 作为新增签名先例；相关修复由 `.trellis/issues/` 跟踪。

## 必需测试与验证

- `Core/src/test/java/work/art1st/javarailways/core/api/physics/ImmutableSnapshotTest.kt`：断言 snapshot 不保留 live World/Rail/Train/Car。
- `Core/src/test/java/work/art1st/javarailways/core/physics/tick/LatestTrainFrameMailboxTest.kt`：断言 collection defensive copy。
- API 改动搜索 `Basic/src/main`、`BukkitPlugin/src/main`、`TunnelBridgeBuilder/src/main` 的 imports。

```bash
./gradlew :Core:test :Basic:test
./gradlew :Core:dokkaGeneratePublicationHtml
./gradlew :BukkitPlugin:test :TunnelBridgeBuilder:test
```
