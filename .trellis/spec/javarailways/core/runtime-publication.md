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

Lifecycle：`attachWorld` 仅一次 → composition注册静态railway-epoch processors → `start()` → wake/capture/await → `beginShutdown()`停止新epoch并捕获exact ticket → 成功await后final save → `close()`/unregister processors。`RailwayEpochProcessor`在`start()`后不能增删；Stall/close后才可清理。`close()`幂等并使 in-flight ticket exceptional；`STALLED/CLOSED` 不返回旧成功 ticket。

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
→ capture candidate RailwayWorldFrameSnapshot under topology read lock
  （同时捕获与该root一致的immutable active turnout continuations）
→ run registered RailwayEpochProcessor synchronously in registration order
→ revalidate candidate topology/occupancy/train frames/active continuations and publish it
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

world frame的capture与publication是两个阶段，但不是两个可独立观察的revision。capture持有topology read lock并保存当时同epoch的train frames、共享occupancy实例、topology revision和live turnout continuation snapshot；processor只读该value。最终publication再次持有read lock，要求topology revision未变、occupancy仍为同一实例、按train ID排序的当前published frames与candidate相等，且active-continuation snapshot仍是同一对象。任一不匹配都令ticket失败，不发布candidate。processor耗时计入`wallNanos`、`coordinatorNanos`与overload窗口；它不是不受physics预算约束的异步队列。

最终成功路径先持world topology read lock，再持coordinator `stateLock`；确认仍为`RUNNING`且`inFlight === ticket`后，才执行candidate复核/发布、更新metrics与latest-completed、清除in-flight并完成该ticket。失败及overload终态转换也只允许同一running ticket执行，因此close或stall先取得状态边界后，迟到epoch不能再发布世界帧、替换诊断metrics或覆盖终态。不得倒置为state→topology：topology transaction的失败路径已有write-lock→stall/state顺序。

关闭门禁也覆盖最终world frame之前的Core发布，而不是只在world publication拒绝迟到结果。每个controller先按topology read lock→stateLock顺序验证同一running ticket并调用纯Core的`publishPhysicsBuffers()`，释放两锁后调用`notifyPhysicsStatePublished()`，保持原逐controller“flip→hook”顺序。随后无controller列车翻转、train frame/mailbox发布在同序门禁内完成；published-frame processors仍在锁外执行，返回后occupancy重建与candidate capture再次经过同序门禁。任一门禁发现close/stall或ticket已撤销就结束该epoch，不得先发布列车、最后才拒绝世界帧。只做锁外状态检查仍会留下检查后关闭的竞态。

这些门禁不新增图或列车扫描；每controller新增一次短状态锁边界，另外的frame/capture锁区执行原有Core发布工作。第三方controller hook、published-frame processor及railway-epoch processor均不在stateLock内，不能用把整个epoch锁住的方式使关闭等待任意扩展代码。已发布部分不回滚，锁外回调已经开始或随后返回的外部效果也不撤销；这里保证的是close取得状态边界后，不再由后续Core发布阶段翻转buffer、推进train frame/mailbox或重建occupancy。

`beginShutdown(): PhysicsTicket`在原`stateLock`内永久关闭wake入口并返回`inFlight ?: latestCompleted`；它自身不等待、不取消工作，也不把status改成CLOSED/STALLED，已经捕获的正常epoch仍可完成最终publication。重复调用在RUNNING时返回同一稳定边界，`start()`不可重新开启；新main/simulation wake明确拒绝。timer在同一锁内完成eligibility检查与request分配，shutdown要么捕获已经分配的timer epoch，要么让该wake静默no-op，不存在检查后再越过gate启动的窗口。

宿主在锁外成功await这一个ticket以后才捕获/写最终快照，最后close；在途epoch失败时该ticketexceptional，后续capture/beginShutdown因STALLED拒绝，不能回退到上一个成功epoch假装可以保存。此边界只停止新的physics epoch，不是所有编辑、generic tick、Bukkit区域任务的全局冻结；平台仍负责unloading门禁和各资源释放。

### 结构变更的临时物理准入（CURRENT）

仅等待`CoreWorld.awaitCurrentPhysicsEpoch()`不构成结构排他：捕获的ticket完成后，timer仍可能立即分配下一epoch。当前解编改用implementation-only的`PhysicsCoordinator.withStructuralMutation(action: () -> T): T`包住决策、准备、提交和成功通知；后续联结须复用同一边界，但旧联结入口尚未接入。本边界不升级成公开API，也不批量改变其他maintenance入口。

正常流程是主线程进入作用域，在`stateLock`内确认RUNNING且未shutdown、增加临时准入计数并捕获`inFlight ?: latestCompleted`；释放锁后等待这一个ticket，成功后再次确认可用，再在锁外执行action。`finally`在短锁区递减计数。等待、插件监听器、extension初始化与几何计算均不能持有`stateLock`，否则会阻塞旧epoch完成或把扩展代码放入协调器全局锁。计数只表达词法作用域持有关系，不是任务队列或第二套事务状态。

作用域持有期间，timer wake静默不分配epoch；已启动coordinator的显式wake只复用已有或最新完成ticket，不声称推进了一步。`captureCurrentTicket`仍可观察被等待的真实ticket，既有in-flight epoch照常完成。未启动、shutdown、STALLED或CLOSED的显式wake仍按原合同拒绝。作用域取得或等待失败则不执行action；action异常原样传播，仍释放临时准入。释放不修改永久`acceptingWakes`、status或ticket，因此不能撤销shutdown、复活close/stall或把失败ticket换成旧成功值。

例如解编listener主动触发timer wake，新的物理任务不能读取准备中的cars；解编成功或被拒绝后，下一次合法wake才可产生新epoch。此边界只防止新physics epoch与该结构操作并发，不提供任意插件副作用回滚、平台渲染冻结或全仓所有编辑互斥，也不承诺close时任意已开始action会被中断。既有wall-clock delta/clamp/drop规则不变，不补跑暂停期间的欠账。准入检查/释放为O(1)，不新增图/列车扫描；唯一阻塞仍是等待实际在途ticket和执行原结构操作。

回归使用注入clock及现有timer seam验证作用域内epoch不前移、显式wake复用ticket、结束后可推进；真实worker用例检查回调开始时ticket已完成，并确定性验证回调期间其他线程可取得状态锁。等待在锁外由源码审查确认，该用例不保证每次调度都实际阻塞在await。异常与shutdown/stall/close不能破坏原生命周期；真实split决策触发timer验证生产入口确实持有作用域。验证结果归任务记录；并发源码检查不等于现场压力测试。

### 其他发布边界

`close()`撤销ticket并中断executor，**不是quiesce**：它不回滚此前已经flip的单列车frame，也不撤销processor已执行的外部副作用；消化中断的worker/processor也可能稍后返回。即使失败ticket已完成，也不证明所有失败worker退出，不能拿失败结果捕获最终快照或把abort等价为成功shutdown边界。

active continuation不是每tick重扫全图。`RailView.activeRail`的成功引用变化在world-local selection lock内推进一个`Long` runtime revision；Core按`(topologyRevision, selectionRevision)`缓存immutable snapshot。普通未变化epoch在`O(1)`内复用同一对象；仅拓扑或选择变化时遍历当前`P`个turnout port，验证live target仍属于该entry的`legalContinuations`，以`O(P log P)`稳定排序并发布新map。选择在processor期间发生即使最终切回原支路也会推进revision，因此candidate在publication复核时被拒绝，而不会把旧占用与新道岔方向拼接。

`RailwayWorldFrameSnapshot` 是一个 value-only 一致副本：所有 `TrainFrameSnapshot.physicsEpoch` 必须等于 world frame 的 `physicsEpoch`，列车按稳定 ID 排序且集合 defensive copy。每个train frame同时携带三种不能混用的物理事实：`occupiedIntervals`是epoch结束时的保守车体占用，`sweptSpans`是整个epoch扫过的合并包络，head/tail traversal是带方向和顺序的边界穿越证据。`topologyRevision`与`activeContinuations`是该次发布持有topology/selection边界时捕获的结构和live选择；它们不是schema version、配置revision或可独立拼接到其他epoch的“最新值”。平台 service 必须先等待 exact `PhysicsTicket` 成功，再发布该 world frame；不得把旧列车 frame 与新 topology/continuation observation重新组合。

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
- railway-epoch processor阻塞测试：exact ticket保持未完成且latest world frame不前移；释放后才一起完成。
- processor failure test：ticket exceptional、coordinator Stall且latest world frame保持上一成功epoch。
- in-flight close：有界barrier/真实worker及消化中断的processor返回后仍为CLOSED，ticket exceptional，世界帧与最终metrics不前移；同时覆盖正常返回与overload路径，不用sleep猜测完成。
- workers结束后的发布前关闭：利用现有nanoTime注入及真实coordinator线程在发布前建立一次barrier，关闭后消化中断并释放；真实moving train的live位置/速度、旧train/world frame身份、空mailbox和metrics均不前移。必须验证未修实现实际失败，不能仅靠随机线程交错或重复运行证明竞态已修。
- beginShutdown：真实moving worker暂停时捕获exact ticket并保持未完成/RUNNING，释放后world/train/occupancy同epoch且实际前移；后续main/simulation/start拒绝、timer no-op、frame/metrics稳定。另验证idle/bootstrap幂等、真实timer准入控制及失败在途ticket不能返回旧成功值；timer通过internal implementation seam和注入clock测试，不靠sleep。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.tick.*' \
  --tests 'work.art1st.javarailways.core.util.*' \
  --tests 'work.art1st.javarailways.core.api.threading.*' \
  --tests '*CoreTrainPublicationBoundaryTest'
```
