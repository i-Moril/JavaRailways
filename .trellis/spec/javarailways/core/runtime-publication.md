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
→ synchronous previous-epoch collision prepass（先标记双方，再启动worker；规则见train physics）
→ workers tick(deltaMillis)
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
| controller reverse请求 | 进入帧间owner队列，不在worker换向 | 执行完成后下一physics epoch正常计算movement |
| stationary/no controller | 仍参与 pending state/frame publication | 不从controller存在性推断可见性 |
| deterministic step参数非法 | `require/check`失败，无新epoch | 修正caller输入 |

Generic TickManager 的 `postTick()` 只 await workers并drain `afterCommitTasks/postTickTasks`；平台之后才 `drainPendingCommits()`。`scheduleMainThreadAfterCommitTask` 的历史命名不表示 platform commit 已执行。

world frame的capture与publication是两个阶段，但不是两个可独立观察的revision。capture持有topology read lock并保存当时同epoch的train frames、共享occupancy实例、topology revision和live turnout continuation snapshot；processor只读该value。最终publication再次持有read lock，要求topology revision未变、occupancy仍为同一实例、按train ID排序的当前published frames与candidate相等，且active-continuation snapshot仍是同一对象。任一不匹配都令ticket失败，不发布candidate。processor耗时计入`wallNanos`、`coordinatorNanos`与overload窗口；它不是不受physics预算约束的异步队列。

最终成功路径先持world topology read lock，再持coordinator `stateLock`；确认仍为`RUNNING`且`inFlight === ticket`后，才执行candidate复核/发布、更新metrics与latest-completed、清除in-flight并完成该ticket。失败及overload终态转换也只允许同一running ticket执行，因此close或stall先取得状态边界后，迟到epoch不能再发布世界帧、替换诊断metrics或覆盖终态。不得倒置为state→topology：topology transaction的失败路径已有write-lock→stall/state顺序。

关闭门禁也覆盖最终world frame之前的Core发布，而不是只在world publication拒绝迟到结果。每个controller先按topology read lock→stateLock顺序验证同一running ticket并调用纯Core的`publishPhysicsBuffers()`，释放两锁后调用`notifyPhysicsStatePublished()`，保持原逐controller“flip→hook”顺序。随后无controller列车翻转、train frame/mailbox发布在同序门禁内完成；published-frame processors仍在锁外执行，返回后occupancy重建与candidate capture再次经过同序门禁。任一门禁发现close/stall或ticket已撤销就结束该epoch，不得先发布列车、最后才拒绝世界帧。只做锁外状态检查仍会留下检查后关闭的竞态。

这些门禁不新增图或列车扫描；每controller新增一次短状态锁边界，另外的frame/capture锁区执行原有Core发布工作。第三方controller hook、published-frame processor及railway-epoch processor均不在stateLock内，不能用把整个epoch锁住的方式使关闭等待任意扩展代码。已发布部分不回滚，锁外回调已经开始或随后返回的外部效果也不撤销；这里保证的是close取得状态边界后，不再由后续Core发布阶段翻转buffer、推进train frame/mailbox或重建occupancy。

`beginShutdown(): PhysicsTicket`在原`stateLock`内永久关闭wake入口并返回`inFlight ?: latestCompleted`；它自身不等待、不取消在途physics epoch，也不把status改成CLOSED/STALLED，已经捕获的正常epoch仍可完成最终publication。尚未开始的帧间操作Future明确失败，不能在最终存档后执行。重复调用在RUNNING时返回同一稳定边界，`start()`不可重新开启；新main/simulation wake明确拒绝。timer在同一锁内完成eligibility检查与request分配，shutdown要么捕获已经分配的timer epoch，要么让该wake静默no-op，不存在检查后再越过gate启动的窗口。

宿主在锁外成功await这一个ticket以后才捕获/写最终快照，最后close；在途epoch失败时该ticketexceptional，后续capture/beginShutdown因STALLED拒绝，不能回退到上一个成功epoch假装可以保存。此边界只停止新的physics epoch，不是所有编辑、generic tick、Bukkit区域任务的全局冻结；平台仍负责unloading门禁和各资源释放。

### 非物理操作统一在帧间执行（TARGET，2026-10-14用户确认）

迁移前重命名、主线程换向、controller切换、创建/删除、维护摆位、外部脱轨请求和基础设施编辑没有统一执行阶段。用户确认这些操作应由协调器安排在两个完整物理epoch之间执行。本节区分首批已实现能力与后续迁移目标；不能把已有`@MainThread`、generic after-commit队列或一次ticket等待当作已满足全部目标。

#### 第一批已实现：协调阶段及重命名（CURRENT）

`PhysicsCoordinator`提供以下implementation层入口，宿主和隔离simulation复用同一实现：

```kotlin
fun <T> submitBetweenFrames(action: () -> T): CompletableFuture<T>
fun <T> processBetweenFrames(action: (PhysicsTicket) -> T): T
fun drainBetweenFramesOperations()
fun ensureBetweenFrames(operation: String)
```

提交可以来自其他线程或发生在`start()`以前，只排队，不执行action。宿主在模拟owner调用`processBetweenFrames`：复用现有准入计数关闭新epoch入口、捕获并等待exact ticket，再把这一个ticket交给owner callback。callback显式调用一次`drainBetweenFramesOperations`，随后执行观察发布、Dispatcher和generic postTick；阶段结束才释放准入。等待和所有action/Future完成回调均位于`stateLock`外，等待时间与操作执行时间在宿主metrics中分别记录。隔离simulation在每个确定性step后进入同一阶段，不需要平台scheduler。

队列和待执行集合由`stateLock`保护；drain一次取出有限FIFO批次，不反复追赶后来提交的请求。成功/失败结果在该操作执行结束时完成；异常不会阻断同批其它独立操作。尚未开始的捕获批次仍登记在待执行集合，shutdown、stall（包括epoch失败/overload）和close都会使其Future异常完成，通知在锁外执行；已开始操作沿用现有非quiesce生命周期语义。正常操作失败由自己的owner保持状态，框架不承诺rollback。每次process最多drain一次，owner回调中提交的操作进入下一批，不得自等它的Future。

`ensureBetweenFrames`同时检查现有simulation/main身份和等待成功后建立的线程局部词法作用域；它不调度任务，也不新增一套线程分类。内部`withStructuralMutation`与process共享同一准入/作用域实现，现有结构操作嵌套时不重复drain。

`CoreWorld.renameTrain`现在是受该阶段检查的同步apply方法。Bukkit命令将controller/Depot状态校验及rename一起提交，完成后经已有entity scheduler反馈玩家。name与名称索引在执行时更新，已发布train/world frame、occupancy及physics epoch保持不动；下一正常epoch会捕获新名称。coordinator比较上一frame的名称，即使列车静止也向既有latest mailbox提交新frame，不新增metadata revision或dirty字段、不重建每次rename的世界索引。Future成功与下一frame发布是两个不同的时点。

其余操作的外部入口尚未迁移；宿主把Dispatcher/postTick放入阶段只保证这些内部调用不与新physics epoch交错，不表示玩家控制、手动扳道等直接入口已受保护。后续完整迁移目标如下。

第3a批已迁移控制器换向请求及同步换向apply，见[controller请求契约](./train-controller.md#帧间换向请求current第3a批)和[列车换向](./train-physics.md#帧间换向current第3a批)。真实换向在通知监听器前立即刷新既有共享占用及world frame，沿用最近完整physics epoch，不另分配epoch或重跑signal processor。每次实际换向有一次低频世界索引构建；本批不引入批量dirty tracker或通知队列，后续维护操作的批量发布仍按其owner接口实现。控制器生命周期与手动接管已完成第3b批迁移，见[控制器生命周期](./train-controller.md#控制器生命周期current第3b批)；创建、删除、维护摆位及基础设施入口仍继续逐批迁移。

#### 正常请求与执行流程

外部命令或插件提交操作请求，由所属world的协调器保存待执行操作及其结果。宿主在模拟owner线程处理帧间阶段：先关闭新epoch准入并等待已捕获的完整ticket（包括信号阶段和world publication），再依次处理请求和现有运营/结构操作，完成必要的状态发布后释放准入。主线程wake与timer fallback使用同一个准入门禁。

协调器负责阶段和顺序，操作仍由各自owner校验和修改状态；这里的“事件”是待执行操作，不意味着每个动作都新增一组公开EventBus通知。现有MainRailEventBus插件通知仍由宿主模拟owner线程派发，不搬到physics worker，也不通过伪造线程marker执行Bukkit回调。线程Guard的整体分类另批讨论。

下列为流程伪代码，尚未冻结新API名称：

```text
submit operation -> coordinator queue + Future<result>

host processes world:
  coordinator enters between-frames scope
    close epoch admission before capturing and awaiting current ticket
    callback receives exact completed ticket
    callback explicitly drains one queued batch on simulation owner
    run dispatcher and existing after-commit operations within the same scope
    publish boundary changes required by physical edits
  release admission
next physics epoch captures controllers and computes from the resulting state
```

等待与操作本体继续位于`stateLock`外。复用并推广现有`withStructuralMutation`的准入计数，不新增第二把控制epoch启动的锁、另一套ticket或独立事件线程。只处理本批捕获的请求集合，后续提交进入下一批；不添加人为数量上限。调用者使用Future观察成功或失败，不能在物理worker或正在处理该队列的owner上阻塞等待尚未执行的请求。处于帧间阶段的内部复合流程可以同步调用内部apply方法，避免把一次接管拆成多个有间隙的请求。

#### 状态发布与生命周期

- 纯名称修改更新名称及名称索引，供owner查询；标记后续正常train frame/mailbox应发布新名称。不得仅为名称变化在物理计算期间替换完整frame，也不为每次重命名重建全world occupancy。操作Future成功表示名称修改完成，不表示下一physics frame已经发布。
- 换向、摆位、编组变化、创建/删除以及基础设施变更会改变下一步物理输入。批次结束前必须形成与修改结果一致的frame、occupancy及必要的world观测，随后才开放下一epoch。复用已有builder并按批次汇总更新，不能只换一个train frame后让旧world观测继续参与联结；具体增量/完整重建方式在对应批次根据实际owner接口实现。
- controller接管、替换和移除在捕获下一epoch controller列表前完成；旧worker不能与接管交错。手动驾驶接管涉及的驾驶端对齐、控制参数及controller安装作为一次owner操作处理。
- 控制器发起的换向也进入帧间流程，迁移后删除仅为执行换向而跳过整轮movement的worker分支。停车准入、运营等待、方向及几何约束仍由现有业务规则校验，不借迁移自行增删运营规则。
- 操作持有exact world/train实例，在执行时验证仍存活且属于目标world；controller来源请求另验该controller仍拥有目标train。排队不提前占用名称或修改world索引。
- 正常拒绝或准备失败只完成本操作的异常结果，继续处理独立请求；既有操作若留下无法恢复的物理/拓扑部分修改，沿用其fault/Stall合同，不伪造通用rollback。shutdown/close不得遗留永不完成的操作Future，未执行请求明确失败；不得在最终存档后继续执行。

本阶段不处理平台entity/render任务，也不改变physics运动计算、物理原因脱轨或已规定的信号阶段。提交前/成功通知的插件异常规则继续由各操作合同拥有。

#### 成本与必需验证

提交及准入管理为每操作O(1)协调开销；队列执行为本批操作本身的成本之和。名称修改不扫描列车几何。几何、拓扑和占用更新沿用对应owner的实际成本，不能因为统一排队就声称整批O(1)。队列不存在普通tick对全world额外重建的要求。

必须以确定性barrier验证：上一epoch在途时请求不修改状态；关闭准入后timer不能插入下一epoch；请求执行结束后下一worker看到修改后的完整状态。另覆盖FIFO、失败结果、关闭时未执行Future终结、owner回调嵌套提交不自等，以及不调用平台的确定性simulation驱动。功能验收包括重命名后正常LINK、控制器替换后旧controller不再tick、换向不在worker执行、删除/摆位后的共享occupancy与world观测一致。仅队列单测通过不能宣称调用链已经迁移。

迁移采用统一边界基础设施→重命名→controller生命周期与换向→创建/删除和摆位→外部脱轨与基础设施编辑→联结/解编收口。每批迁移生产caller并验证后才删除旧入口/特判，不保留未发布开发API的长期兼容alias；无持久化格式或版本变化。`await ticket -> immediate edit`仍允许fallback穿插，不得作为新入口示例。

### 结构变更的临时物理准入（CURRENT）

仅等待`CoreWorld.awaitCurrentPhysicsEpoch()`不构成结构排他：捕获的ticket完成后，timer仍可能立即分配下一epoch。当前解编使用implementation-only的`PhysicsCoordinator.withStructuralMutation(action: () -> T): T`包住决策、准备、提交和成功通知；它与上述`processBetweenFrames`共享同一个私有`withFrameBoundary`准入及exact-ticket实现。联结草稿也复用此结构入口。内部结构方法不升级成公开API，其余maintenance入口仍须逐批迁移。

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
