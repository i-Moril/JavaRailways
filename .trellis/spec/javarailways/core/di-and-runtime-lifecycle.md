# Core runtime lifecycle 契约

## 适用场景与用途

触发：创建/load/unload `CoreWorld`、注册controller/frame processor/event listener、启动/关闭Physics/Tick/render owner。

用途：让所有runtime resource在first wake前完成composition，并按逆序成对释放；persistence decode保持无global DI依赖。

## Owner、签名与启动顺序

`CoreWorld`不持有任何DI scope或world-parent attachment：运行时组件由composition显式注入，persistence只使用显式
`SnapshotDependency`。构造成功只表示Core-owned资源（rails/trains/索引）已就绪，平台sidecar仍由各自owner管理。

标准startup：

```text
register process Plugin slots / retain ThreadGuard host-registration token
→ create TickManager + PhysicsCoordinator
→ CoreWorld create/load（attach coordinator）
→ attach all world/train/car extension data并initialize
→ restore Basic/platform sidecars
→ materialize ephemeral signals/render metadata
→ register published-frame processors/listeners
→ start PhysicsCoordinator
→ allow first wake
```

`CoreWorld.load(infrastructure,runtime,SnapshotDependency)`只依赖显式dependency；JSON decode和DTO validation不得读取全局service registry。

## Register/close ownership矩阵

| resource | register owner | teardown（逆序） |
| --- | --- | --- |
| `TrainController` | `CoreTrain.setController` | replace/remove/destroy/derail |
| `PublishedTrainFrameProcessor` | world composition | unload时coordinator close前unregister |
| Event listener | plugin/service token owner | platform bridge清除前`EventRegistration.close` |
| generic `TickingObject` | service/extension | deferred unregister/destroy |
| PhysicsCoordinator | world composition | `beginShutdown()`→成功await exact ticket→save→`close()` |
| TickManager/render owner | platform world owner | processor/coordinator后close；render先于tick |
| Plugin slot | process startup | 当前不释放；same class reload替换 |

## Lifecycle结果矩阵

| 条件 | 必须结果 |
| --- | --- |
| first wake前composition未完成 | 不start、不wake；startup失败并cleanup已创建resources |
| world unload | 禁止新dispatch，beginShutdown原子停止wake/capture exact ticket，成功await后final snapshot，逐项unregister/close |
| in-flight close | ticket exceptional；consumer不执行post-epoch side effects |
| extension/processor startup失败 | reverse-order cleanup；world不进入manager可见map；render→physics→tick |
| CoreWorld constructor/load失败 | 撤销本world已登记的Core资源；不关闭caller-owned dependency |
| persistence decode | pure codec/DTO/prevalidation；不attach DI或start executor |
| test world结束 | unregister global/world state并finally close coordinator/TickManager |

Physics正常shutdown由coordinator原状态锁拥有：只永久关闭新wake，不提前改变RUNNING，因此在途epoch仍可发布；timer检查与分配在同一锁内。await/save在锁外，失败ticket不能退回旧成功结果；`close`仍是中断/撤销而非quiesce。其详细顺序由[runtime publication](./runtime-publication.md)拥有。

当前宿主正常卸载已使用该physics边界；保存/teardown失败也会保留首个异常、继续逐项释放其余资源并移除exact owner map绑定。
Bukkit world factory在CoreWorld成功返回后登记render；返回前失败按render→physics→tick逆序清理。factory返回后、manager发布完成前失败和正常unload都让service、physics、render先停止使用world，最后关闭tick；每项失败隔离并追加suppressed。manager发布后的启动失败还必须按exact world identity同时撤掉owner map与UUID lookup，不得遗留已关闭world或删除同UUID replacement。对象构造内部产生的listener仍由该对象在抛出前撤销。

## 项目内正确示例

Isolated world只传显式snapshot dependency：

```kotlin
CoreWorld.load(
    coreInfrastructure,
    runtime = null,
    dependency = CoreWorld.SnapshotDependency(
        tickManager,
        physics
    )
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`。该模式保证相同snapshot可在Bukkit外恢复。

## 禁止实现

- physics已start后才attach safety processor或required train extension；
- unload只remove map；
- codec读取global service registry；
- 把`CoreWorld`当`AutoCloseable`统一清理所有外部资源；
- event/processor/controller token依赖GC；
- test依赖daemon thread自动退出或global registry顺序。

## 必需测试与验证

- startup integration：first epoch可见所有required processors/extensions；失败启动无线程/registration残留。
- unload integration：final ticket/snapshot成功后processor不再调用，三个coordinator executors与TickManager关闭。
- `WorldPersistenceRoundTripTest.kt`：load使用explicit dependency，不读取全局service registry。
- `PhysicsCoordinatorTest.kt`：attach一次、start幂等、close幂等、in-flight close failure，以及beginShutdown捕获正常/失败ticket、永久gate、idle/bootstrap和timer准入。

```bash
./gradlew :Core:test --tests '*WorldPersistenceRoundTripTest' --tests '*PhysicsCoordinatorTest'
./gradlew :Basic:test :BukkitPlugin:test
```


## 热关闭的物理静止屏障

`PhysicsCoordinator.stopAndAwait(timeoutMillis = 10_000)` 在当前 `beginShutdown` 入口封住新 epoch，等待准确 in-flight ticket 及所有 owned executors。成功后可捕获最终 ticket；失败/超时进入 STALLED，禁止将旧成功帧作为新最终快照。现有 close 保留取消语义，closeAndAwaitTermination 保留完整退出等待。不得从 worker 或 state lock 内等待自身。

当前 CoreWorld 不持有 Koin world context；合并热重载修复不得恢复 JavaRailwaysKoin/WorldParentContext 或相应释放路径。`PluginRegistry.unregister(owner)` 保留稳定 identifier 槽位且不保留退休 Class/classloader，扩展继续按 plugin.identifier 定位，不把 active plugins 列表下标当 identifier。
