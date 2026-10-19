# Core 事件总线契约

## 适用场景与用途

触发：新增/修改 `Event`、publisher、`@HandlesEvent` handler，或 plugin/service 注册 listener。

用途：两套 process-wide bus 是同步 dispatcher，不是 scheduler。契约防止错误线程 side effect、重复注册、泄漏 listener，以及把 exception isolation误当 rollback。

## World来源与物理epoch通知（CURRENT，2026-10-18）

用户于2026-10-10确认保留`PreTickEvent`、`PostTickEvent`和`RenderableEvent`。2026-10-18进一步确认Pre/Post按每个物理epoch接入，不依赖WorldManager宿主tick，并以World实例区分实际运行和模拟。

`WorldEvent : Event`只提供`world: World`。`World.executionMode: WorldExecutionMode`在构造时确定，取`LIVE/SIMULATION`，不是schema、调度器或线程权限。列车事件从`train.world`提供World；联结前决策从`firstTrain.world`提供；碰撞直接带所属World，不再只带字符串worldId。发布入口不新增对象归属校验，不复制environment ID，也不引入全局currentWorld。

`PreTickEvent(world,epoch,deltaMillis)`与`PostTickEvent(world,epoch,deltaMillis,frame)`均为`PhysicsRailEvent + WorldEvent`，在协调器线程同步发布，使用physics线程分类。Pre在一个ticket实际进入计算、应用此前碰撞决定之前发一次；Post只在完整world frame和ticket成功提交后锁外发一次，frame就是该epoch已提交的不可变对象。bootstrap、未执行或合并到已有ticket的唤醒不新增事件；计算/processor/关闭导致未提交的失败epoch无Post。

Pre/Post沿用普通listener异常隔离，不是可以阻止发布的安全processor。Post不承诺Bukkit呈现、Dispatcher或异步消费者完成；`ticket.await()`也不等待Post listener。回调不得位于coordinator stateLock或topology提交锁内，listener不能阻塞等待自己排入的后续帧间操作。通知不授予live World的任意跨线程读写权限，读取本轮一致状态使用Post.frame。

现有换向行为和CSV脱轨平台consumer按World模式过滤SIMULATION，不对LIVE增加nullable renderer兜底。模拟逐车onReverse等完整领域行为仍由隔离模拟后续行为子任务接入，本批不声称已实现全Feature模拟。

例如正常唤醒推进epoch 7时，先通知Pre(7)，完成计算和processor后提交frame 7，再通知Post(7, frame7)。若processor抛错，则保留Pre(7)而没有Post；已有ticket合并十次唤醒也不新增十组通知。新增通知成本是每个执行epoch两次同步派发，按匹配handler数量增长，不扫描其他World；慢listener会延迟协调器。World模式只来自构造依赖，不进入存档，也不改变格式版本。外部使用方须适配新的事件签名与线程分类，不保留旧worldId或宿主tick通知兼容路径。

`RenderableEvent`继续作为分组接口保留；具体模型生命周期事件不在本批范围，没有独立publisher不是删除理由。阶段通知复用既有物理发布事实，不增加第二份观察计算或新的事件框架。

## Owner、签名与 lifecycle

解编已接入`TrainSplitEvent`与`TrainSplitSuccessEvent`，联结已接入`TrainLinkEvent`与`TrainLinkSuccessEvent`。提交前允许插件单向拒绝及指定结果，完整提交后才通知成功；相关接口/事件标记`ApiStatus.Experimental`（unstable）。业务流程、冲突、异常及测试规则由[train physics](./train-physics.md#解编事务)拥有，不增加公开通用取消协议。

内部`MainRailEventBus.publishAndReportFailures(event): Boolean`复用同一dispatcher，仅额外汇总所有handler是否正常返回：出现任一异常时仍记录并继续后续handler，最后返回false。解编owner据此拒绝提交，而普通公开`publish(event): Unit`仍忽略此汇总结果、保持原有异常隔离语义。成功事件走普通publish，不能因通知异常使已完成事务失败；不能把现有总线的“继续派发”误当成结构操作仍可提交。状态只在本次同步调用中保留，不新增线程、队列或每tick扫描。

```kotlin
object MainRailEventBus {
    fun register(listener: EventListener): EventRegistration
    fun unregister(listener: EventListener)
    fun publish(event: MainThreadRailEvent)
}

interface EventRegistration : AutoCloseable {
    val isClosed: Boolean
    override fun close()
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/event/processor/MainRailEventBus.kt`与`Core/src/main/java/work/art1st/javarailways/core/api/event/processor/EventRegistration.kt`。

- `MainRailEventBus.publish` 调 `ThreadGuard.ensureMainThread`；`PhysicsRailEventBus.publish` 调 `ensureAsyncTickThread` 并要求 `@AsyncEventApi` opt-in。
- 两者都在调用线程同步执行；registry process-wide，不是 world-local。
- `@HandlesEvent` member 必须只有 receiver + 一个对应 root subtype 参数；非法 handler当前不会注册。private handler可被反射调用。
- priority 固定 `HIGHEST → HIGH → MEDIUM → LOW → LOWEST`。同 declared type/priority 保持插入序；不同 supertype不承诺总顺序。
- callback `Throwable` 被记录后继续后续 listener；无 cancellation、无 state rollback。
- `close()` 幂等，只移除该次 registration；此前已捕获派发快照中的 callback 仍可执行，关闭不等待在途 callback。

### 注册变更与并发派发

`TypedRailEventBus`先在锁外反射提取handler，再用同一个registry monitor串行执行register的bucket查找/添加、token close、unregister和空bucket清理。这样cleanup不能在register取得bucket后、添加handler前把bucket移出map。map使用`ConcurrentHashMap`，各priority列表使用`CopyOnWriteArrayList`；publish只读取这些并发容器和迭代快照，不取得registry monitor，callback也在该锁外执行，可关闭自己或注册其他listener。

token的`isClosed`是原子可见的close标记，不是派发/清理完成屏障；`unregister(listener)`按listener对象身份移除所有匹配handler，但不将其既有token标记为closed。token close只移除自己保存的handler身份，旧token不能删除后来注册的handler。多handler的register/close与并发publish之间不承诺一次派发的全有或全无，跨priority/type也不是单个全局snapshot。

注册反射和CopyOnWrite数组复制属于低频生命周期成本；close/unregister还遍历事件bucket清理空列表。publish沿事件继承图查找bucket并遍历匹配handler；本次同步不增加其registry锁等待。并发回归使用局部bus与有界barrier/future验证返回的live token仍可派发；它是调度压力回归，没有确定性强制旧实现的丢listener交错，不能据此宣称旧实现必红或生产触发已复现。

注册 owner 必须执行 `start/register/retain token → stop/close/clear token`；测试在 `finally` close。

## Event payload 与结果矩阵

| 事件/条件 | thread/payload | 结果与 side effect |
| --- | --- | --- |
| `CollisionEvent(epoch,world,trainAId,trainBId,relativeSpeed)` | physics，World来源与stable列车IDs/value | 默认 `DERAIL`；`requestLink()` 原子改为 `LINK`；decision 下一 epoch应用；模拟同样派发 |
| `PreTickEvent` | coordinator physics，World/epoch/实际delta | 本epoch计算前一次；不是宿主tick通知 |
| `PostTickEvent` | coordinator physics，World/epoch/delta/已提交frame | 只通知成功epoch；在提交锁外，await不等待listener |
| `PassSignalEvent` | physics，含 live `Train/Signal` | listener只生成 stable request；不得 mutate/platform I/O |
| main `DerailEvent` | published state 后 main context | 每次 derail 只发一次 |
| `TrainSwitchDirectionEvent` | 帧间owner，带live Train与World | 方向确实变化并一致发布后才发 |
| 联结/解编前决策与成功事件 | 帧间owner，带所属World与原有业务对象 | 保持原有单向否决/结果选择及完整提交后通知规则 |
| listener 抛错 | publisher thread | 继续派发；保留抛错前 mutation |
| wrong bus/thread | publish入口 | `ThreadGuard` fail-fast；不派发 |

`CollisionEvent` 的真实 resolution 代码（`Core/src/main/java/work/art1st/javarailways/core/api/event/CollisionEvent.kt`）：

```kotlin
private val requestedResolution = AtomicReference(CollisionResolution.DERAIL)
val resolution: CollisionResolution get() = requestedResolution.get()
@ApiStatus.Experimental
fun requestLink() {
    requestedResolution.set(CollisionResolution.LINK)
}
```

该设计让 listener 只能请求明确 decision，不获得 live train mutation 权限。PhysicsCoordinator不再提供关闭碰撞事件的开关；模拟和实际运行复用相同检测、决策和应用代码。

## 禁止实现

```text
错误：register(listener) 后丢弃 token；physics listener 等待 Bukkit region task
正确：owner 保存 EventRegistration；listener 发布 stable request，由目标 owner消费
```

不得依赖 handler signature错误时抛异常、重复注册自动去重、listener exception取消事件，或把声明但无 publisher 的 event 当可用 hook。

## 必需测试与验证

- `RailEventBusTest.kt`：bus segregation、wrong thread、inheritance/interface、priority、failure isolation、close幂等。
- `CollisionEventTest.kt`/`PhysicsCoordinatorTest.kt`：pair每 epoch一次、LINK、下一 epoch decision。
- `WorldEventTest.kt`：8类运行事件在两种模式下保留实际World实例，同名列车不混淆来源。
- `PhysicsEpochEventsTest.kt`：普通/fallback/模拟和空World；Pre先于计算、Post携带相同已提交frame；合并、失败、关闭、listener异常和锁外callback。
- `CoreTrainCollisionCouplingTest.kt`：两种模式复用真实碰撞，默认DERAIL及listener请求LINK结果一致。
- Bukkit `SimulationEventBoundaryTest.kt`：模拟事件不进入renderer/CSV，LIVE路径仍执行原consumer。
- `CoreTrainDerailEventTest.kt`：physics publication后 main event 且只一次。
- lifecycle/reload test：enable两次不得产生双重 callback，disable后不再触发。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.api.event.*' \
  --tests '*CoreTrainDerailEventTest' --tests '*PhysicsCoordinatorTest' \
  --tests '*PhysicsEpochEventsTest' --tests '*CoreTrainCollisionCouplingTest'
```
