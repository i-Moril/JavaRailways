# API、物理与 runtime

## API 边界

- 候选公开面位于 `work.art1st.javarailways.core.api.*`；`Core/build.gradle.kts` 的 Dokka suppress regex 隐藏其余 package。不要把普通 `public` 实现类视为稳定 API。
- API 输入输出优先使用 interface、immutable snapshot、stable ID 和明确值对象。`World.kt` 明确 `allTrains` 是 live view，调用方不能假设并发安全 snapshot。
- `Rail.kt` 固定 persistent UUID、owner `World`、immutable `PrimeCurve` 和 AB/BA `RailView`；新增 geometry API 必须写单位与方向基准。
- 平台或 Basic 缺能力时，优先扩展恰当 API；不要为了一个 consumer 暴露完整 `CoreWorld`、`CoreTrain` 或内部 mutable collection。

## Physics 发布

`PhysicsCoordinator` 的 contract 是每个 world 最多一个 in-flight epoch：重复 wake 合并到同一 `PhysicsTicket`，worker 完成后统一 publish state/frame，再重建 occupancy。维护以下顺序：

```text
controller prepare/tick on workers
  → publishPhysicsState
  → publish immutable TrainFrameSnapshot
  → run published-frame processors
  → rebuild SweptOccupancyIndex
  → complete exact PhysicsTicket
```

- 不在 worker 中直接执行平台副作用；跨边界 payload 必须是 value-only snapshot。
- 不让慢 consumer 形成逐 tick 无界 backlog。`CoreWorld.drainPublishedTrainFrames()` 使用 latest-per-train mailbox；相关修改必须保持 coalescing/capacity metrics。
- wall-clock wake 的 delta 可 clamp；`stepSimulation` 使用 caller-supplied deterministic delta。不要把 missed wall time 变成补跑 epoch debt。
- overload/failure 进入 `STALLED` 后不能返回旧成功 epoch；consumer 捕获一次 ticket 并等待该 ticket。

证据：`Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt`、`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`、`Core/src/main/java/work/art1st/javarailways/core/physics/tick/LatestTrainFrameMailbox.kt`、`Core/src/test/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinatorTest.kt`。

## 事件与线程

`MainRailEventBus` 与 `PhysicsRailEventBus` 是同步总线；listener 在 publish 调用线程立即执行，注册返回 `EventRegistration` 并应在 lifecycle stop 时 `close()`。`TypedRailEventBus` 对每个 listener 异常隔离，但这不授权错误线程访问。

- physics event listener 必须显式接受 async contract，线程安全且不访问 Bukkit。
- main event 的“main”在平台 bridge 下代表 simulation serialization，不等于拥有任意 Folia entity/location。
- 线程/阻塞语义应写进 KDoc；`@MainThread`、`@Heavy` 等只表达约束。

## Topology 与 mutation

- `CoreWorld.railTopologyTransaction` 在进入 write lock 前等待 current physics epoch；expensive curve construction 应在 transaction 外完成。
- rail removal 会检查受影响 rails 的 train occupancy，并修复 active/next links；不要直接操作内部 maps 跳过不变量。
- `getNearestRail` 是 `@Heavy` 且要求 off-main-thread；类似全量/几何查询应复用 spatial index 或明确 worker boundary。

测试优先复现 epoch、方向、占用与 topology 边界，而不是只断言单个 getter。
