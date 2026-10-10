# Core DI 与 runtime lifecycle 契约

## 适用场景与用途

触发：创建/load/unload `CoreWorld`、Koin attach、注册controller/frame processor/event listener、启动/关闭Physics/Tick/render owner。

用途：让所有runtime resource在first wake前完成composition，并按逆序成对释放；persistence decode保持无global DI依赖。

## Owner、签名与启动顺序

`WorldParentContext`只有runtime attach责任：

```kotlin
fun interface WorldParentContext {
    fun attachWorld(world: CoreWorld)
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/di/WorldParentContext.kt`。`KoinWorldParentContext`在parent scope下创建`world_$world` child并declare `CoreWorld`；test/isolated simulation使用`NoOpWorldParentContext`。

标准startup：

```text
register process Plugin slots / retain ThreadGuard host-registration token
→ create TickManager + PhysicsCoordinator
→ CoreWorld create/load（attach DI + coordinator）
→ attach all world/train/car extension data并initialize
→ restore Basic/platform sidecars
→ materialize ephemeral signals/render metadata
→ register published-frame processors/listeners
→ start PhysicsCoordinator
→ allow first wake
```

`CoreWorld.load(infrastructure,runtime,SnapshotDependency)`只依赖显式dependency；JSON decode和DTO validation不得从Koin取service。

## Register/close ownership矩阵

| resource | register owner | teardown（逆序） |
| --- | --- | --- |
| `TrainController` | `CoreTrain.setController` | replace/remove/destroy/derail |
| `PublishedTrainFrameProcessor` | world composition | unload时coordinator close前unregister |
| Event listener | plugin/service token owner | platform bridge清除前`EventRegistration.close` |
| generic `TickingObject` | service/extension | deferred unregister/destroy |
| PhysicsCoordinator | world composition | `beginShutdown()`→成功await exact ticket→save→`close()` |
| TickManager/render owner | platform world owner | processor/coordinator后close |
| Plugin slot | process startup | 当前不释放；same class reload替换 |

## Lifecycle结果矩阵

| 条件 | 必须结果 |
| --- | --- |
| first wake前composition未完成 | 不start、不wake；startup失败并cleanup已创建resources |
| world unload | 禁止新dispatch，beginShutdown原子停止wake/capture exact ticket，成功await后final snapshot，逐项unregister/close |
| in-flight close | ticket exceptional；consumer不执行post-epoch side effects |
| extension/processor startup失败 | reverse-order cleanup；world不进入manager可见map |
| persistence decode | pure codec/DTO/prevalidation；不attach DI或start executor |
| test world结束 | unregister global/world state并finally close coordinator/TickManager |

Physics正常shutdown由coordinator原状态锁拥有：只永久关闭新wake，不提前改变RUNNING，因此在途epoch仍可发布；timer检查与分配在同一锁内。await/save在锁外，失败ticket不能退回旧成功结果；`close`仍是中断/撤销而非quiesce。其详细顺序由[runtime publication](./runtime-publication.md)拥有。

当前宿主正常卸载已使用该physics边界；这不代表全部lifecycle已达标。保存/teardown异常后的owner map与各资源独立释放、factory partial-init cleanup及Koin child scope处置仍有审查待修项；不能把本页要求当成这些错误路径已全部实现。

## 项目内正确示例

Isolated world显式传无DI context而不是让codec访问Koin：

```kotlin
CoreWorld.load(
    coreInfrastructure,
    runtime = null,
    dependency = CoreWorld.SnapshotDependency(
        tickManager,
        NoOpWorldParentContext,
        physics
    )
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`。该模式保证相同snapshot可在Bukkit外恢复。

## 禁止实现

- physics已start后才attach safety processor或required train extension；
- unload只remove map；
- codec调用global Koin；
- 把`CoreWorld`当`AutoCloseable`统一清理所有外部资源；
- event/processor/controller token依赖GC；
- test依赖daemon thread自动退出或global registry顺序。

## 必需测试与验证

- startup integration：first epoch可见所有required processors/extensions；失败启动无线程/registration残留。
- unload integration：final ticket/snapshot成功后processor不再调用，三个coordinator executors与TickManager关闭。
- `WorldPersistenceRoundTripTest.kt`：load使用explicit dependency且不需要Koin decode。
- `PhysicsCoordinatorTest.kt`：attach一次、start幂等、close幂等、in-flight close failure，以及beginShutdown捕获正常/失败ticket、永久gate、idle/bootstrap和timer准入。

```bash
./gradlew :Core:test --tests '*WorldPersistenceRoundTripTest' --tests '*PhysicsCoordinatorTest'
./gradlew :Basic:test :BukkitPlugin:test
```
