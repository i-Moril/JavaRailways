# Core 事件总线契约

## 适用场景与用途

触发：新增/修改 `Event`、publisher、`@HandlesEvent` handler，或 plugin/service 注册 listener。

用途：两套 process-wide bus 是同步 dispatcher，不是 scheduler。契约防止错误线程 side effect、重复注册、泄漏 listener，以及把 exception isolation误当 rollback。

## Owner、签名与 lifecycle

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
- priority 固定 `HIGHEST → HIGH → NORMAL → LOW → LOWEST`。同 declared type/priority 保持插入序；不同 supertype不承诺总顺序。
- callback `Throwable` 被记录后继续后续 listener；无 cancellation、无 state rollback。
- `close()` 幂等，只移除该次 registration；已在执行的 callback 可完成。

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
