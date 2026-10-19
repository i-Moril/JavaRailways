# Core 事件总线契约

## 适用场景与用途

触发：新增/修改 `Event`、publisher、`@HandlesEvent` handler，或 plugin/service 注册 listener。

用途：两套 process-wide bus 是同步 dispatcher，不是 scheduler。契约防止错误线程 side effect、重复注册、泄漏 listener，以及把 exception isolation误当 rollback。

## 保留与接入目标（TARGET）

用户于2026-10-10确认保留`PreTickEvent`、`PostTickEvent`和`RenderableEvent`，后续应考虑正式接入，而非作为无调用残留删除。当前三者只有声明、没有真实接线；以下目标不表示已实现，也不冻结新签名或发布位置。

接入前须明确Pre/Post指宿主循环还是物理epoch、世界范围、数据和访问线程；后置通知不得把“物理结果已发布”承诺成“平台呈现已完成”。`RenderableEvent`是分组接口，没有独立publisher并非缺陷；具体事件按真实生命周期定义，live对象访问仍遵守线程约束。复用现有总线和发布事实，核对与观察服务的重叠，不新增重复计算或第二份状态。不得仅因暂无consumer就取消扩展能力，也不得将尚未接线的声明宣传为可用hook。

## Owner、签名与 lifecycle

解编已接入`TrainSplitEvent`与`TrainSplitSuccessEvent`，联结两阶段事件仍待实现。提交前允许插件单向拒绝及指定两侧方向，完整提交后才通知成功；相关接口/事件标记`ApiStatus.Experimental`（unstable）。业务流程、冲突、异常及测试规则由[train physics](./train-physics.md#解编事务)拥有，不增加公开通用取消协议。

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
- 错误通过以`TypedRailEventBus`命名的JDK JUL logger携带原Throwable报告，不直接写stderr。平台可订阅Core命名logger写自己的错误文件；Core不拥有writer或Bukkit依赖。文件机制归[Bukkit错误日志](../../bukkit-plugin/backend/error-logging.md)。
- `close()` 幂等，只移除该次 registration；此前已捕获派发快照中的 callback 仍可执行，关闭不等待在途 callback。

### 注册变更与并发派发

`TypedRailEventBus`先在锁外反射提取handler，再用同一个registry monitor串行执行register的bucket查找/添加、token close、unregister和空bucket清理。这样cleanup不能在register取得bucket后、添加handler前把bucket移出map。map使用`ConcurrentHashMap`，各priority列表使用`CopyOnWriteArrayList`；publish只读取这些并发容器和迭代快照，不取得registry monitor，callback也在该锁外执行，可关闭自己或注册其他listener。

token的`isClosed`是原子可见的close标记，不是派发/清理完成屏障；`unregister(listener)`按listener对象身份移除所有匹配handler，但不将其既有token标记为closed。token close只移除自己保存的handler身份，旧token不能删除后来注册的handler。多handler的register/close与并发publish之间不承诺一次派发的全有或全无，跨priority/type也不是单个全局snapshot。

注册反射和CopyOnWrite数组复制属于低频生命周期成本；close/unregister还遍历事件bucket清理空列表。publish沿事件继承图查找bucket并遍历匹配handler；本次同步不增加其registry锁等待。并发回归使用局部bus与有界barrier/future验证返回的live token仍可派发；它是调度压力回归，没有确定性强制旧实现的丢listener交错，不能据此宣称旧实现必红或生产触发已复现。

注册 owner 必须执行 `start/register/retain token → stop/close/clear token`；测试在 `finally` close。

## Event payload 与结果矩阵

| 事件/条件 | thread/payload | 结果与 side effect |
| --- | --- | --- |
| `CollisionEvent(epoch,worldId,trainAId,trainBId,relativeSpeed)` | physics，stable IDs/value | 默认 `DERAIL`；`requestLink()` 原子改为 `LINK`；decision 下一 epoch应用 |
| `PassSignalEvent` | physics，含 live `Train/Signal` | listener只生成 stable request；不得 mutate/platform I/O |
| main `DerailEvent` | published state 后 main context | 每次 derail 只发一次 |
| `TrainSwitchDirectionEvent` | main直接或 physics→postTick | 方向确实变化才发 |
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

该设计让 listener 只能请求明确 decision，不获得 live train mutation 权限。

## 禁止实现

```text
错误：register(listener) 后丢弃 token；physics listener 等待 Bukkit region task
正确：owner 保存 EventRegistration；listener 发布 stable request，由目标 owner消费
```

不得依赖 handler signature错误时抛异常、重复注册自动去重、listener exception取消事件，或把声明但无 publisher 的 event 当可用 hook。

## 必需测试与验证

- `RailEventBusTest.kt`：bus segregation、wrong thread、inheritance/interface、priority、failure isolation、close幂等。
- `CollisionEventTest.kt`/`PhysicsCoordinatorTest.kt`：pair每 epoch一次、LINK、下一 epoch decision。
- `CoreTrainDerailEventTest.kt`：physics publication后 main event 且只一次。
- lifecycle/reload test：enable两次不得产生双重 callback，disable后不再触发。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.api.event.*' \
  --tests '*CoreTrainDerailEventTest' --tests '*PhysicsCoordinatorTest'
```
