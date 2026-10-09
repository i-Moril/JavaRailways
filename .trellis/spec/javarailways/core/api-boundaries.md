# Core API 边界契约

## 适用场景与用途

触发：修改 `Core/src/main/java/work/art1st/javarailways/core/api/`、`Core/build.gradle.kts` Dokka 配置，或让 Basic/Bukkit/Tunnel 使用 Core 能力。

用途：阻止 implementation object、live mutable state 或平台类型成为新的跨模块承诺。`core.api.*` 是 `1.0-SNAPSHOT` 候选面，不是 binary compatibility 保证；新 consumer 仍必须以它为首选边界。

## Owner、类型与 dependency contract

- owner：Core 定义 API；consumer 只消费。Core production 不得 import Basic、Bukkit、Paper 或 Minecraft。
- Dokka publication 只保留 `work.art1st.javarailways.core.api.*`；普通 `public` implementation 不成为 API。
- 认可的跨线程 value 类型：`RailPositionSnapshot(UUID, ViewDirection, Double)`、`RailTraversalSegmentSnapshot`、revision-rooted `TopologyState`及其`RailCrossingPoint/AtGradeRailCrossing`、`ActiveRailContinuationsSnapshot`、`RailObjectRef(namespace,id)`、`RailObjectSnapshot`、`TrainSnapshot`、`Vector3Snapshot`、`RenderableFrameSnapshot`、`CarFrameSnapshot`、`TrainFrameSnapshot`、`RailwayOccupancySnapshot`、`RailwayWorldFrameSnapshot`。`RailwayEpochProcessor`是同步value-only callback，不暴露live Core对象。
- live 类型：`World`、`Train`、`Rail`、`RailView`。`World.allTrains` 与 `RailView.nextRails` 不得当 immutable collection 缓存。
- `@MainThread`、`@Heavy`、`@Concurrent`、`@Pure` 只表达 contract，不调度；只有实现显式调用 `core.api.threading.ThreadGuard.ensure*` 才 runtime fail-fast。
- `core.api.threading` 是普通 consumer 的只读线程身份契约：`ThreadContext` 仅用于诊断，`ThreadGuard` 只提供 `is*`、`currentContext` 和 `ensure*`，不公开注册、清除或自行进入 owner context 的能力。
- `core.api.threading.host` 只供可信平台宿主的启动/停用集成。`ThreadGuardHost.registerPlatformBridge` 的 first live registration wins，重复注册失败；返回的 identity token 幂等关闭且只能清理自己的 exact bridge，旧 token 不能清掉后续注册。bridge 查询非阻塞且不执行调度，volatile publication 只负责并发可见性。
- Core async-tick marker 保持 implementation-private。SimpleATO headless timetable simulation 当前直接使用 `core.util.IsolatedSimulationThreadMarker` 这一 first-party implementation seam，以拥有独立 world 为前提；它不是第三方 API，不能复制到普通附属插件、live server callback 或 Bukkit 访问。后续若要消除这项 util leakage，需要另行设计有 capability 的模拟 owner，而不是把 marker 包成公共 guard helper。

### ThreadGuard 工作方式与失败边界

正常启动时，唯一平台宿主先注册一个非阻塞 bridge并保存返回的registration；普通consumer随后只调用
`ThreadGuard.is*`／`currentContext`／`ensure*`。停用时宿主先停止依赖该判定的runtime，最后关闭registration。
Core内部用一个`volatile` current entry发布bridge；register和token close在同一个registry monitor下串行，token另用
原子closed位保证重复close无动作。查询、上下文分类和guard均为`O(1)`且不取得该monitor；register/close只在低频生命周期
使用，重复注册抛`IllegalStateException`且不替换当前bridge，无bridge时main-thread查询为false，旧token或重复close为no-op。

正确做法是先用owner scheduler进入所需线程，再让guard断言；错误做法是让consumer安装bridge、公开一个“进入主线程上下文”
helper，或用guard替代调度。可信宿主SPI不能从JVM层阻止抢先注册，因此插件加载顺序和代码审查仍是边界的一部分；一旦宿主
成功注册，其他调用者不能覆盖或用自己的token清除它。该限制不涉及存档或schema版本。

`RailPositionSnapshot` 的真实签名和单位（`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailPositionSnapshot.kt`）：

```kotlin
data class RailPositionSnapshot(
    val railId: UUID,
    val facing: ViewDirection,
    val arcLengthInAbMeters: Double
)
```

`arcLengthInAbMeters` 必须 finite、`>= 0`；它不保留 live `Rail`。完整 render frame contract 归[render publication](./render-publication.md)。

`Train.publishedFrame: TrainFrameSnapshot?` 提供已经发布的完整不可变物理帧，非阻塞、不执行几何求解；
CoreTrain 复用已有原子 publication，不另建 snapshot。不能提供帧的实现返回 null，consumer 必须视为缺少证明，
不得 cast 为 CoreTrain 或从 live car/head/tail 拼接替代帧。需要一致车身几何的 consumer 一次捕获此值，
校验列车身份与计算周期，再从同一对象读取 head、tail 和有序 bodyTraversal；帧 shape 不因新增读取入口而升级持久化版本。

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
| 线程身份检查 | consumer 用 `core.api.threading.ThreadGuard` + owner scheduler；平台宿主持有 registration token | 普通 consumer 注册/清空 bridge、进入 marker，或把 guard 当 scheduler |

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
- ThreadGuard 改动还要搜索 `SimpleATO/src/main` 与 `SimpleSignals/src/main`；普通跨模块 guard 使用只允许 `core.api.threading`。已记录的 SimpleATO isolated-simulation internal seam 不得扩散或包装 live Bukkit callback。

```bash
./gradlew :Core:test :Basic:test
./gradlew :Core:dokkaGeneratePublicationHtml
./gradlew :BukkitPlugin:test :TunnelBridgeBuilder:test
```
