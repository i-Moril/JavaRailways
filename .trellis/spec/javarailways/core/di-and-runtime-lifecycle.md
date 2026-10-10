# Core DI 与 runtime lifecycle 契约

## 适用场景与用途

触发：创建/load/unload `CoreWorld`、Koin attach、注册controller/frame processor/event listener、启动/关闭Physics/Tick/render owner。

用途：让所有runtime resource在first wake前完成composition，并按逆序成对释放；persistence decode保持无global DI依赖。

## Owner、签名与启动顺序

`WorldParentContext`只有runtime attach责任；attach成功返回由world持有的幂等registration：

```kotlin
fun interface WorldParentContext {
    fun attachWorld(world: CoreWorld): WorldContextRegistration
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/di/WorldParentContext.kt`。`KoinWorldParentContext`以
`world_${world.runtimeId}`创建唯一child scope，显式link parent后declare `CoreWorld`；registration close关闭并从
Koin registry删除该child，重复close无动作。attach内部在link/declare失败时自行关闭已经创建的scope，原异常保持主异常，
清理错误进入suppressed。test/isolated simulation使用返回共享no-op registration的`NoOpWorldParentContext`。

`CoreWorld`拥有registration，但不因此实现`AutoCloseable`：`releaseRuntimeBinding()`只释放parent-context attachment，
不关闭caller-owned `PhysicsCoordinator`、`TickManager`、train或平台资源。constructor在取得registration后若coordinator attach
失败，必须立即回滚registration；`CoreWorld.load`在world已构造、但rail/train恢复失败时同样回滚。两条路径均保留原异常并
追加cleanup failure，且不越权关闭snapshot dependency中的physics/tick owner。

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
| World context registration | `CoreWorld` | services/coordinator/render不再需要DI后，TickManager前close |
| TickManager/render owner | platform world owner | processor/coordinator后close；render先于world context，tick晚于world context |
| Plugin slot | process startup | 当前不释放；same class reload替换 |

## Lifecycle结果矩阵

| 条件 | 必须结果 |
| --- | --- |
| first wake前composition未完成 | 不start、不wake；startup失败并cleanup已创建resources |
| world unload | 禁止新dispatch，beginShutdown原子停止wake/capture exact ticket，成功await后final snapshot，逐项unregister/close |
| in-flight close | ticket exceptional；consumer不执行post-epoch side effects |
| extension/processor startup失败 | reverse-order cleanup；world不进入manager可见map；render→world context→physics→tick |
| CoreWorld constructor/load失败 | 回滚已取得的world context registration；不关闭caller-owned dependency |
| persistence decode | pure codec/DTO/prevalidation；不attach DI或start executor |
| test world结束 | unregister global/world state并finally close coordinator/TickManager |

Physics正常shutdown由coordinator原状态锁拥有：只永久关闭新wake，不提前改变RUNNING，因此在途epoch仍可发布；timer检查与分配在同一锁内。await/save在锁外，失败ticket不能退回旧成功结果；`close`仍是中断/撤销而非quiesce。其详细顺序由[runtime publication](./runtime-publication.md)拥有。

当前宿主正常卸载已使用该physics边界；保存/teardown失败也会保留首个异常、继续逐项释放其余资源并移除exact owner map绑定。
Bukkit world factory在CoreWorld成功返回后立刻登记其runtime binding，再登记render；返回前失败按
render→world context→physics→tick逆序清理。factory返回后、manager发布完成前失败和正常unload都让service、physics、
render先停止使用world，再释放world context，最后关闭tick；每项失败隔离并追加suppressed。manager发布后的启动失败还必须按
exact world identity同时撤掉owner map与UUID lookup，不得遗留已关闭world或删除同UUID replacement。对象构造内部产生的listener
仍由该对象在抛出前撤销。DI scope释放不等于其他宿主资源已释放，二者保持独立owner和顺序。

### World context registration（CURRENT）

1. **范围、触发与owner**：创建任何`CoreWorld`都会调用`WorldParentContext.attachWorld`；Core拥有返回的registration，平台composition负责在world退役时触发释放。
2. **工作模型**：attach创建仅属于该`runtimeId`的binding；Core构造/load成功后交给宿主，失败则Core回滚；宿主启动失败或卸载时先撤consumer，再关闭binding。
3. **内部机制**：Koin使用`createScope("world_${world.runtimeId}", qualifier)`，随后`linkTo(parent)`并`declare(world)`；token用原子一次性gate调用`Scope.close()`，从registry删除exact child。
4. **签名契约**：`attachWorld(CoreWorld): WorldContextRegistration`；`WorldContextRegistration.close()`必须幂等；`CoreWorld.releaseRuntimeBinding()`只转发该token，不承担physics、tick、train或平台资源清理。
5. **不变量、线程与版本**：同一world最多有一个Core-owned active token；DI必须活到service/physics/render consumer退出，且早于tick owner关闭。没有schema/version/epoch含义，也不新增runtime compatibility。
6. **错误矩阵**：scope create失败时无token；link/declare失败关闭刚创建scope；coordinator attach或rail/train restore失败回滚token；cleanup失败suppressed到原异常；重复close无动作。
7. **性能与退化**：attach/close各操作一个Koin scope，为O(1)，不扫描world、train或registry全集；失败不重试、不保留tombstone，也不创建后台任务。
8. **情形**：正常world可从child解析自身；NoOp环境返回共享空token；坏snapshot释放binding但保留caller-owned dependency；发布后启动失败同时按exact identity撤owner map与UUID lookup。
9. **必需测试**：断言child可解析world、close后`getScopeOrNull`为空并可重新attach；constructor/load失败close一次且保留suppressed；Bukkit factory/unload顺序为render→binding→tick，并保护replacement binding。
10. **迁移、开放项与正误示例**：无需迁移。错误做法是使用GC、只撤UUID lookup、让`CoreWorld`通用close所有外部资源；正确做法是关闭exact registration，并由各资源owner继续负责自己的teardown。

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
- `CoreWorldRuntimeBindingTest.kt`：正常/重复释放、constructor attach失败和load失败回滚，不误关caller资源。
- `KoinWorldParentContextTest.kt`：child scope可解析world，close后registry无残留，同一runtime identity可重新attach。
- `PhysicsCoordinatorTest.kt`：attach一次、start幂等、close幂等、in-flight close failure，以及beginShutdown捕获正常/失败ticket、永久gate、idle/bootstrap和timer准入。

```bash
./gradlew :Core:test --tests '*WorldPersistenceRoundTripTest' --tests '*PhysicsCoordinatorTest'
./gradlew :Basic:test :BukkitPlugin:test
```
