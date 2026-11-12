# Core runtime 与 epoch publication 契约

## 适用场景与用途

触发：修改 `PhysicsCoordinator`、`PhysicsTicket`、`CommitBuffer`、`RailwayWorldFrameSnapshot`、published-frame processor、generic `TickManagerImpl`、mailbox 或 unload barrier。

用途：保证 worker active state 只在完整 epoch 后可见，consumer等待同一 ticket，且物理状态、结构拓扑版本和列车 frame 来自同一次一致发布；失败 epoch 不继续 Dispatcher/render，慢 consumer 不形成逐 Tick backlog。

## Owner、签名与 lifecycle

每个 `CoreWorld` 拥有一个 `PhysicsCoordinator`；generic `TickManagerImpl` 是另一条独立路径。`CoreTrain.setController` 只注册 coordinator。

### 每World领域执行归属（2026-10-19）

`CoreWorld.executionOwner: WorldExecutionOwner`与该world的coordinator持有同一个可重入串行入口，创建在attach以前，未attach的bootstrap协调器也可使用。`owner.run(action)`只取得本World锁，不取得进程级读写门禁，不创建线程，也不赋予Bukkit/线程分类权限。现有`processBetweenFrames`与`withStructuralMutation`先执行原`ensureMainThread`，再取得owner并复用帧间准入；两个合法host调用会串行执行，其他World的host和physics仍可推进。

锁序为owner→短stateLock；等待ticket和领域callback仍在stateLock外。physics workers/coordinator publication不取得host owner，继续使用topology→state顺序。只持owner不等于已取得物理帧间准入；凡需禁止在途/后续physics与管理提交交错的操作，仍须复用frame boundary。`close/beginShutdown`保持原来的终态及quiesce边界，owner不是新的World.close或保存协议。

`PhysicsCoordinator.closeAndAwaitTermination()`是实现层宿主拆卸入口：先沿原`close`禁止epoch并取消，再在state/topology锁外等待自身coordinator、physics worker和fallback executor真正退出；`TickManagerImpl.closeAndAwaitTermination(runtimeId)`对generic worker做相同确认。不能从相关worker或持stateLock的完成回调调用，不能把失败ticket/CLOSED视为callback退出。取消线程的interrupt临时吸收并最终恢复，未退出时低频日志说明所属World/executor并保留其依赖；原`close()`保持非阻塞语义。该边界不等待Bukkit平台效果，也不承诺强杀同JVM不退出的第三方callback。

低频多World入口使用`WorldExecutionOwner.runTogether(owners, action)`，去重后按owner创建时固定的序号取锁，不按可能重复的列车/World显示名找锁。它必须从单World作用域外进入，或重入已经全部持有的owner集合；不能在持A时同步升级取得未持有的B。目录管理mutex更不能先阻塞、后检查当前owner；平台管理入口在等待其mutex之前执行`ensureNoOwner`，具体LIVE目录规则由[Bukkit lifecycle](../../bukkit-plugin/backend/lifecycle-and-threading.md)拥有。

`runWithoutOwners(action)`是目标平台同步等待所需的释放/恢复能力：保存当前实际持有的owner列表和各自重入次数，逆序释放，finally按原顺序与次数恢复。恢复同一个列表对象，让原词法scope的finally继续清理同一份记账；不能替换成新列表而遗留已经释放的内层owner。当前生产没有`runOutsideSimulationContext`调用，不宣称所有区域等待都已借此改造；新增真正同步等待时才按需使用。

例如A的帧间callback被barrier暂停，B仍能完成其自身epoch；同一A上的第二个callback必须等待。A→B嵌套且暂停恢复后，退出B再暂停只能释放A。错误做法是给每次owner.run增加全局read锁：一项LIVE管理write等待A时会连带挡住无关SIM的新read；正确做法是普通owner只管本World、管理事务只协调其真实有关集合。

普通单World协调成本为本次锁和持有列表操作；多World取锁为W个不同owner的O(W log W)排序及实际等待，暂停恢复按持有owner和重入次数处理。不新增每epoch世界扫描、第二物理循环或无界待办。回归须覆盖同World互斥、真实异World推进、混合重入暂停、异常恢复、wrong-thread在owner被占用时仍立即拒绝，以及既有shutdown/failure矩阵。owner与scope均为运行态，不涉及格式版本或开发数据兼容。

诊断出口使用各类命名的JDK JUL logger：联结处理异常及generic tick错误携带原Throwable，delta clamp/overload为WARNING且保持既有限频和Stall计数。Core不直接写文件，也不因日志改变ticket成功/失败或epoch顺序；平台文件writer和关闭规则归[Bukkit错误日志](../../bukkit-plugin/backend/error-logging.md)。

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

`World.maximumPhysicsDeltaSeconds`公开所属coordinator的不可变单步上限，单位秒；live与隔离模拟均消费
同一真实配置值。它不是当前delta或固定控制周期，控制器不得在制动证明中自行假定50 ms或硬编码默认200 ms。
Wall-clock delta首 epoch 50 ms，默认 clamp 200 ms；drop计入 `droppedDeltaMillis`，不补跑 catch-up epochs。`stepSimulation(deltaMillis)` 要求 finite、`0 < delta <= maxPhysicsDeltaMillis` 且 `automaticFallback=false`。


### 手动物理超时恢复

`PhysicsCoordinator.resetStall(): CompletableFuture<Boolean>` 在 coordinator executor 上排在失败 epoch 完整退出之后执行。
仅本 World 的 overload STALL 可恢复；已有进程级 `RailwaySimulationHalt`、shutdown 和 CLOSED 保护继续生效。
正常 RUNNING 返回 false；实际恢复返回 true。调用方异步消费结果，不经拒绝 STALLED 的帧间提交入口。

超时发生于 controller/processor 已完成、train frame 已发布而 world frame 尚未提交时，保留该完整候选。
手动恢复按 topology read lock → state lock 发布候选并创建新的成功边界 ticket；原失败 exact ticket 永远保持失败，
不重跑失败 epoch，不重置列车位置、controller 或碰撞决定。恢复清空 overload 时间窗和旧墙钟起点，暂停时长不计入下一帧 delta。
恢复发布复用物理 epoch 的锁顺序，不在 coordinator executor 等待 World owner，避免 unload 持 owner 等待 executor 的反向等待。
关闭时排队的 reset future 必须明确失败，不能因 executor 取消而永久 pending。
`PhysicsOverloadException` 标识超时 epoch 及其后被拒绝的 wake；宿主沿 cause 链判断迟到错误，不能因为 reset 已将当前状态改为 RUNNING 而把旧超时升级成进程级故障。

Bukkit 管理入口为 `/jr physics status [world]` 与 `/jr physics reset <world>`，使用 `javarailways.admin`；
ATO reset 仍只管理 ATO，不兼任物理 reset。

## Epoch 顺序与结果矩阵

```text
publish PreTickEvent once on the coordinator physics context
→ snapshot controllers（profileId stable order）
→ synchronous previous-epoch collision prepass（先标记双方，再启动worker；规则见train physics）
→ workers tick(deltaMillis)
→ await all workers
→ flip controller/train CommitBuffer
→ publish TrainFrameSnapshot
→ notify controller onPhysicsStatePublished outside publication locks
→ process frames per train；completeEpoch once
→ build one shared RailwayOccupancySnapshot
→ capture candidate RailwayWorldFrameSnapshot under topology read lock
  （同时捕获与该root一致的immutable active turnout continuations）
→ run registered RailwayEpochProcessor synchronously in registration order
→ revalidate candidate topology/occupancy/train frames/active continuations and publish it
→ queue this epoch's frozen collision decisions for the immediately following owner boundary
→ complete exact PhysicsTicket
→ publish PostTickEvent for that committed frame outside commit locks
```

Pre/Post同时携带World实例、epoch及实际delta，Post携带本次已提交frame，完整通知合同归[事件总线](./event-bus.md#world来源与物理epoch通知current2026-10-18)。bootstrap与合并唤醒不额外通知；未提交的失败epoch无Post。ticket完成保持现有物理发布成功语义，等待者可能先于Post listener完成继续运行；不能用await断言通知已消费或平台效果已完成。普通listener失败被总线隔离，不能把已经成功提交的epoch改判失败。扩展callback不进入stateLock或topology提交锁。

| 条件 | 结果 | consumer行为 |
| --- | --- | --- |
| 多次 wake 且已有 in-flight | 返回同一 `PhysicsTicket` | capture一次，await该对象 |
| ticket success | processor、occupancy与同 epoch world frame 均完成 | 可读取该 ticket 对应的一致 world frame，并运行 Dispatcher |
| worker/processor/publication failure | coordinator `STALLED`，ticket exceptional | 不消费该epoch mailbox、不继续Dispatcher |
| controller reverse请求 | 进入帧间owner队列，不在worker换向 | 执行完成后下一physics epoch正常计算movement |
| stationary/no controller | 仍参与 pending state/frame publication | 不从controller存在性推断可见性 |
| deterministic step参数非法 | `require/check`失败，无新epoch | 修正caller输入 |

Generic TickManager 的 `postTick()` 只 await workers并drain `postTickTasks`；平台之后才 `drainPendingCommits()`。不存在独立的 after-commit 阶段队列：提交请求的 mailbox 由 `drainPendingCommits()` 消费，若将来需要平台提交后回调，应由拥有真实完成时机的宿主提供，而不是在 TickManager 中保留第二个阶段名。

world frame的capture与publication是两个阶段，但不是两个可独立观察的revision。capture持有topology read lock并保存当时同epoch的train frames、共享occupancy实例、topology revision和live turnout continuation snapshot；processor只读该value。最终publication再次持有read lock，要求topology revision未变、occupancy仍为同一实例、按train ID排序的当前published frames与candidate相等，且active-continuation snapshot仍是同一对象。任一不匹配都令ticket失败，不发布candidate。processor耗时计入`wallNanos`、`coordinatorNanos`与overload窗口；它不是不受physics预算约束的异步队列。

最终成功路径先持world topology read lock，再持coordinator `stateLock`；确认仍为`RUNNING`且`inFlight === ticket`后，才执行candidate复核/发布、更新metrics与latest-completed、清除in-flight并完成该ticket。失败及overload终态转换也只允许同一running ticket执行，因此close或stall先取得状态边界后，迟到epoch不能再发布世界帧、替换诊断metrics或覆盖终态。不得倒置为state→topology：topology transaction的失败路径已有write-lock→stall/state顺序。

关闭门禁也覆盖最终world frame之前的Core发布，而不是只在world publication拒绝迟到结果。controller buffers、无controller列车的状态以及完整train frame/mailbox，均按topology read lock→stateLock顺序验证同一running ticket后发布。只有本轮train frames已经发布，才在锁外调用`notifyPhysicsStatePublished()`；hook可直接读取本次完整`train.publishedFrame`。不能在仅flip buffers、frame尚未更新时通知，否则消费方会把上个epoch位置与新控制状态混合。published-frame processors仍在锁外执行，返回后occupancy重建与candidate capture再次经过同序门禁。任一门禁发现close/stall或ticket已撤销就结束该epoch，不得先发布列车、最后才拒绝世界帧。只做锁外状态检查仍会留下检查后关闭的竞态。

这些门禁不新增图或列车扫描；每controller新增一次短状态锁边界，另外的frame/capture锁区执行原有Core发布工作。第三方controller hook、published-frame processor及railway-epoch processor均不在stateLock内，不能用把整个epoch锁住的方式使关闭等待任意扩展代码。已发布部分不回滚，锁外回调已经开始或随后返回的外部效果也不撤销；这里保证的是close取得状态边界后，不再由后续Core发布阶段翻转buffer、推进train frame/mailbox或重建occupancy。

`beginShutdown(): PhysicsTicket`在原`stateLock`内永久关闭wake入口并返回`inFlight ?: latestCompleted`；它自身不等待、不取消在途physics epoch，也不把status改成CLOSED/STALLED，已经捕获的正常epoch仍可完成最终publication。尚未开始的帧间操作Future明确失败，不能在最终存档后执行。重复调用在RUNNING时返回同一稳定边界，`start()`不可重新开启；新main/simulation wake明确拒绝。timer在同一锁内完成eligibility检查与request分配，shutdown要么捕获已经分配的timer epoch，要么让该wake静默no-op，不存在检查后再越过gate启动的窗口。

宿主在锁外成功await这一个ticket以后才捕获/写最终快照，最后close；在途epoch失败时该ticketexceptional，后续capture/beginShutdown因STALLED拒绝，不能回退到上一个成功epoch假装可以保存。此边界只停止新的physics epoch，不是所有编辑、generic tick、Bukkit区域任务的全局冻结；平台仍负责unloading门禁和各资源释放。

### 非物理操作统一在帧间执行（TARGET，2026-10-14用户确认）

迁移前重命名、主线程换向、controller切换、创建/删除、维护摆位、外部脱轨请求和基础设施编辑没有统一执行阶段。用户确认这些操作应由协调器安排在两个完整物理epoch之间执行。六批生产迁移现已完成（CURRENT）；保留本节标题锚点供历史任务链接定位。不能把已有`@MainThread`、generic after-commit队列或一次ticket等待当作帧间排他。

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

`ensureBetweenFrames`同时检查现有simulation/main身份和等待成功后建立的线程局部词法作用域；它不调度任务，也不新增一套线程分类。implementation层`withStructuralMutation`与process共享同一准入/作用域实现，现有结构操作嵌套时不重复drain。第5批将该已有方法开放给平台同步复合操作复用，不移入core.api、不新增并行执行器或alias；host的`processBetweenFrames`仍拒绝嵌套处理。

`CoreWorld.renameTrain`现在是受该阶段检查的同步apply方法。Bukkit命令将controller/Depot状态校验及rename一起提交，完成后经已有entity scheduler反馈玩家。name与名称索引在执行时更新，已发布train/world frame、occupancy及physics epoch保持不动；下一正常epoch会捕获新名称。coordinator比较上一frame的名称，即使列车静止也向既有latest mailbox提交新frame，不新增metadata revision或dirty字段、不重建每次rename的世界索引。Future成功与下一frame发布是两个不同的时点。

外部入口已逐批迁移：controller生命周期与换向、创建/删除及维护摆位、外部脱轨与选岔/拓扑编辑，以及碰撞联结和插件解编。宿主阶段本身不自动保护任意插件写入；每个生产入口仍须遵守其owner的阶段检查或显式提交规则。

第3a批已迁移控制器换向请求及同步换向apply，见[controller请求契约](./train-controller.md#帧间换向请求current第3a批)和[列车换向](./train-physics.md#帧间换向current第3a批)。真实换向在通知监听器前立即刷新既有共享占用及world frame，沿用最近完整physics epoch，不另分配epoch或重跑signal processor。每次实际换向有一次低频世界索引构建；不引入批量dirty tracker。控制器生命周期与手动接管见[控制器生命周期](./train-controller.md#控制器生命周期current第3b批)，创建/删除和维护摆位见[列车owner发布](./train-physics.md#帧间创建删除与维护摆位current第4批)，基础设施见[拓扑事务](./rail-topology-and-occupancy.md)，联结/解编见[结构事务](./train-physics.md#联结流程的职责边界current)。实际LINK与结果消费是一项owner操作，成功通知前完成共享观测；监听器排入的rename留给下一捕获批次。

#### 正常请求与执行流程

外部命令或插件提交操作请求，由所属world的协调器保存待执行操作及其结果。宿主在模拟owner线程处理帧间阶段：先关闭新epoch准入并等待已捕获的完整ticket（包括信号阶段和world publication），再依次处理请求和现有运营/结构操作，完成必要的状态发布后释放准入。主线程wake与timer fallback使用同一个准入门禁。

协调器负责阶段和顺序，操作仍由各自owner校验和修改状态；这里的“事件”是待执行操作，不意味着每个动作都新增一组公开EventBus通知。现有MainRailEventBus插件通知仍由宿主模拟owner线程派发，不搬到physics worker，也不通过伪造线程marker执行Bukkit回调。线程Guard的整体分类另批讨论。

下列为当前流程伪代码，具体实现签名见上方：

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

仅等待`CoreWorld.awaitCurrentPhysicsEpoch()`不构成结构排他：捕获的ticket完成后，timer仍可能立即分配下一epoch。implementation-only的`PhysicsCoordinator.withStructuralMutation(action: () -> T): T`与上述`processBetweenFrames`共享同一个私有`withFrameBoundary`准入及exact-ticket实现。联结/解编请求由同一帧间队列执行，决策、准备、提交和成功通知均在词法作用域内；同步维护及拓扑复合操作复用该门禁。内部结构方法不升级成公开API，不保留await-only编辑路径。

正常流程是主线程进入作用域，在`stateLock`内确认RUNNING且未shutdown、增加临时准入计数并捕获`inFlight ?: latestCompleted`；释放锁后等待这一个ticket，成功后再次确认可用，再在锁外执行action。`finally`在短锁区递减计数。等待、插件监听器、extension初始化与几何计算均不能持有`stateLock`，否则会阻塞旧epoch完成或把扩展代码放入协调器全局锁。计数只表达词法作用域持有关系，不是任务队列或第二套事务状态。

作用域持有期间，timer wake静默不分配epoch；已启动coordinator的显式wake只复用已有或最新完成ticket，不声称推进了一步。`captureCurrentTicket`仍可观察被等待的真实ticket，既有in-flight epoch照常完成。未启动、shutdown、STALLED或CLOSED的显式wake仍按原合同拒绝。作用域取得或等待失败则不执行action；action异常原样传播，仍释放临时准入。释放不修改永久`acceptingWakes`、status或ticket，因此不能撤销shutdown、复活close/stall或把失败ticket换成旧成功值。

例如解编listener主动触发timer wake，新的物理任务不能读取准备中的cars；解编成功或被拒绝后，下一次合法wake才可产生新epoch。此边界只防止新physics epoch与该结构操作并发，不提供任意插件副作用回滚、平台渲染冻结或全仓所有编辑互斥，也不承诺close时任意已开始action会被中断。既有wall-clock delta/clamp/drop规则不变，不补跑暂停期间的欠账。准入检查/释放为O(1)，不新增图/列车扫描；唯一阻塞仍是等待实际在途ticket和执行原结构操作。

回归使用注入clock及现有timer seam验证作用域内epoch不前移、显式wake复用ticket、结束后可推进；真实worker用例检查回调开始时ticket已完成，并确定性验证回调期间其他线程可取得状态锁。等待在锁外由源码审查确认，该用例不保证每次调度都实际阻塞在await。异常与shutdown/stall/close不能破坏原生命周期；真实split决策触发timer验证生产入口确实持有作用域。验证结果归任务记录；并发源码检查不等于现场压力测试。

### 其他发布边界

`close()`撤销ticket并中断executor，**不是quiesce**：它不回滚此前已经flip的单列车frame，也不撤销processor已执行的外部副作用；消化中断的worker/processor也可能稍后返回。即使失败ticket已完成，也不证明所有失败worker退出，不能拿失败结果捕获最终快照或把abort等价为成功shutdown边界。

active continuation不是每tick重扫全图。`RailView.activeRail`的成功引用变化在world-local selection lock内推进一个`Long` runtime revision；Core按`(topologyRevision, selectionRevision)`缓存immutable snapshot。普通未变化epoch在`O(1)`内复用同一对象；仅拓扑或选择变化时遍历当前`P`个turnout port，验证live target仍属于该entry的`legalContinuations`，以`O(P log P)`稳定排序并发布新map。选择在processor期间发生即使最终切回原支路也会推进revision，因此candidate在publication复核时被拒绝，而不会把旧占用与新道岔方向拼接。

列车物理值直接由`Train.publishedFrame`提供，不再定义`TrainSnapshot`摘取模型。消费方一次捕获`TrainFrameSnapshot`，从中读取head/tail各自facing、speed、cars.size及derailReason；不能用live getter补字段或覆盖speed。`CoreTrain`发布时从同一`DerailState`取得derailed/reason。控制tick、感知计数与frame的revision/physicsEpoch各有语义，不能为替换类型而互换。

故障报告可保留采样时的不可变frame引用，后续物理更新不会改写该现场；保存历史现场不等于额外物理owner。初始化或不支持完整帧的Train不能被伪造为零车长参加自动准入。删除旧摘取类型只迁移源码使用方，不引入旧API兼容壳或持久化版本。

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
- controller hook回归：真实moving epoch与显式controller.commit均须让hook看到本轮frame的head/tail/speed/body；保存的前帧保持不变。hook不得进入state/topology锁，close/stall仍阻止后续Core发布。
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

## 核心故障的全局停止（CURRENT，2026-10-20）

`RailwaySimulationHalt`拥有整个进程铁路模拟的终态准入门禁。Core不变量/共享发布失败、未知physics worker失败，以及存续world消费已关闭运营runtime时，记录首个原始故障并停止所有world的新physics/generic推进与帧间操作准入。它不关闭Minecraft服务器、不销毁列车、不释放占用/claim，也不提供自动恢复或重试。之后创建的coordinator同样不能绕过终态门禁。

全局标记立即可见；各coordinator在自己的执行器内完成ticket及未执行操作的失败收尾，不在故障world持锁时同步取得其他world的锁。已进入的worker允许退出，失败批次等待所有已提交worker，再停止后续Core发布；新controller计算和publication阶段均检查全局门禁。不把ticket失败当作任意平台callback已经退出的证明，正常卸载仍须等待executor实际终止。

只有具有明确无共享写入失败合同的拒绝才可局部处理：碰撞联结在提交前的已知准入拒绝使用`CouplingRejectedException`；它只停碰撞双方，未知准备异常及提交后错误触发全局停止。运动求解已定义的`PhysicsException`沿原列车脱轨路径记录并保留占用；JS guest错误由平台停止该车厢所属列车。不能按任意异常类/线程把未知错误降级成局部故障。既有超时预算overload是独立world的运行预算终态，保持其明确的非不变量合同。

generic批次收尾分开等待与业务动作：`TickManagerImpl.finishPendingAsyncTick()`只等待捕获批次及记录结果，不执行postTick队列或平台commit。宿主阶段失败时调用它，禁止下一preTick覆盖旧barrier；重复preTick而前批未消费是核心故障。正常postTick仍按原顺序等待、执行队列。此批按用户要求仅作静态源码核对，未运行测试、编译或服务器验收。

### Halt 后的资源与定义操作边界

世界资源 registry 在新 claim、claim 扩展、依赖声明、编辑许可申请及既有 claim/permit 的作用域进入处检查进程 halt。
保留查询、释放、回滚、已开始 mutation 的最终化和 close，不能因 halt 释放真实占用或阻断失败收尾。
宿主 signal/inspection/route-planning source 的正常替换及新 world 注册拒绝 halt；正常替换在开始及实际提交前检查 halt，覆盖拿到许可后才发生的故障；
卸载/停用清理直接走原有 retirement 路径，不给所有 World owner 调用统一加门禁。
故意触发 halt 的回归使用 `IsolateGlobalHalt` 隔离进程，禁止重置生产终态。

最后一次提交门禁是进入不可逆提交的准入点：其前发生的 halt 拒绝提交；其后发生的 halt 允许已入场事务完成原子提交/失败回滚，
不在写盘与 publication 交换之间插入终态检查导致半提交。这不授权任何后续新操作。
