# Core runtime 与 epoch publication 契约

## 适用场景与用途

触发：修改 `PhysicsCoordinator`、`PhysicsTicket`、`CommitBuffer`、`RailwayWorldFrameSnapshot`、published-frame processor、generic `TickManagerImpl`、mailbox 或 unload barrier。

用途：保证 worker active state 只在完整 epoch 后可见，consumer等待同一 ticket，且物理状态、结构拓扑版本和列车 frame 来自同一次一致发布；失败 epoch 不继续 Dispatcher/render，慢 consumer 不形成逐 Tick backlog。

## Owner、签名与 lifecycle

每个 `CoreWorld` 拥有一个 `PhysicsCoordinator`；generic `TickManagerImpl` 是另一条独立路径。`CoreTrain.setController` 只注册 coordinator。

```kotlin
class PhysicsTicket internal constructor(
    val epoch: Long,
    internal val completion: CompletableFuture<PhysicsEpochMetrics>
) {
    fun await(): PhysicsEpochMetrics = completion.get()
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt`。

Lifecycle：`attachWorld` 仅一次 → composition完成后 `start()` → wake/capture/await → unload停止新work并final save → unregister processors → `close()`。`close()`幂等并使 in-flight ticket exceptional；`STALLED/CLOSED` 不返回旧成功 ticket。

Wall-clock delta首 epoch 50 ms，默认 clamp 200 ms；drop计入 `droppedDeltaMillis`，不补跑 catch-up epochs。`stepSimulation(deltaMillis)` 要求 finite、`0 < delta <= maxPhysicsDeltaMillis` 且 `automaticFallback=false`。

## Epoch 顺序与结果矩阵

```text
apply prior collision decisions
→ snapshot controllers（profileId stable order）
→ workers preparePhysicsTick/tick(deltaMillis)
→ await all workers
→ flip controller/train CommitBuffer
→ publish TrainFrameSnapshot
→ process frames per train；completeEpoch once
→ build one shared RailwayOccupancySnapshot
→ publish RailwayWorldFrameSnapshot(physicsEpoch, topologyRevision, trains, occupancy)
→ complete exact PhysicsTicket
```

| 条件 | 结果 | consumer行为 |
| --- | --- | --- |
| 多次 wake 且已有 in-flight | 返回同一 `PhysicsTicket` | capture一次，await该对象 |
| ticket success | processor、occupancy与同 epoch world frame 均完成 | 可读取该 ticket 对应的一致 world frame，并运行 Dispatcher |
| worker/processor/publication failure | coordinator `STALLED`，ticket exceptional | 不消费该epoch mailbox、不继续Dispatcher |
| reverse request奇数 | 本epoch只发布方向，`preparePhysicsTick=false` | movement下一epoch恢复 |
| stationary/no controller | 仍参与 pending state/frame publication | 不从controller存在性推断可见性 |
| deterministic step参数非法 | `require/check`失败，无新epoch | 修正caller输入 |

Generic TickManager 的 `postTick()` 只 await workers并drain `afterCommitTasks/postTickTasks`；平台之后才 `drainPendingCommits()`。`scheduleMainThreadAfterCommitTask` 的历史命名不表示 platform commit 已执行。

`RailwayWorldFrameSnapshot` 是一个 value-only 一致副本：所有 `TrainFrameSnapshot.physicsEpoch` 必须等于 world frame 的 `physicsEpoch`，列车按稳定 ID 排序且集合 defensive copy。每个train frame同时携带三种不能混用的物理事实：`occupiedIntervals`是epoch结束时的保守车体占用，`sweptSpans`是整个epoch扫过的合并包络，head/tail traversal是带方向和顺序的边界穿越证据。`topologyRevision` 是该次发布持有 topology read lock 时捕获的 runtime structural revision；它不是 schema version、配置 revision 或可独立拼接到其他 epoch 的“最新值”。平台 service 必须先等待 exact `PhysicsTicket` 成功，再发布该 world frame；不得把旧列车 frame 与新 topology revision 重新组合。

## 项目内正确示例

`TickManagerImpl` 将 register 延迟到 serialized post-tick mutation：

```kotlin
override fun register(obj: TickingObject) {
    scheduleMainThreadPostTickTask {
        tickingObjects.add(obj)
    }
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt`。它避免 worker snapshot迭代时直接改 owner set；physics controller不要复用此路径。

## 禁止实现

- sleep 或第二次 `captureCurrentTicket()` 猜原 ticket完成；
- 分别读取 live train、latest topology revision 后自行组装跨 epoch observation；
- failed ticket 后 drain frame/safety request或调用Basic Dispatcher；
- worker调用Bukkit、blocking I/O或等待platform scheduler；
- 把 processor注册到generic TickManager；
- unload只从map移除world而不unregister/close processor、coordinator、TickManager。

## 必需测试与验证

- `PhysicsCoordinatorTest.kt`：injected clock、fallback关闭、coalesced ticket、clamp、Stall、simulation delta、finally close。
- `PhysicsCoordinatorTest.kt`：exact ticket 完成后 world frame 的 epoch、topology revision、稳定排序和不可变集合一致。
- `CoreTrainPublicationBoundaryTest.kt`：commit前不可见、epoch后可见。
- `TickManagerImplTest.kt`：`postTick`不执行platform commit、deferred registration、metrics。
- processor failure test：ticket exceptional后consumer无side effect。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.tick.*' \
  --tests 'work.art1st.javarailways.core.util.*' --tests '*CoreTrainPublicationBoundaryTest'
```
