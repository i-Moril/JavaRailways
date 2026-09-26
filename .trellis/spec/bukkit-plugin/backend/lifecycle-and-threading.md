# Lifecycle 与线程归属

## Composition 顺序

`BukkitPlugin.kt` 的阶段边界：

- `onLoad`：检测 platform capabilities、创建 dispatcher、读取 physics/global config、加载 packet runtime；需在连接注入前完成的工作放这里。
- `onEnable`：enable packet/runtime、创建 render backend、启动 Koin/Basic/plugin registry、安装 `ThreadGuard` bridge、注册 world/service/listener/control、加载模型、初始化 Wand/Cloud、注册 worlds，最后启动 WebUI sidecar 与 `WorldManager`。
- `onDisable`：先停止外部入口/sidecar/HTTP，再停止 world manager、保存并 unregister worlds，关闭 control/event/log/packet/thread bridge。每步通过有上下文日志的 cleanup 隔离异常。

新增 component 必须回答：谁创建、何时可用、谁注册、如何停止、world unload 与 plugin disable 是否都释放。不要依赖 JVM 退出回收 executor/task/registration。

独立信号/ATO附属插件的正常停用必须先显式unapply其计划依赖，再调用唯一的
`InfrastructureResourceService.unregisterOwner`。该调用同时负责插件的基础资源注册和信号source全集；
`SignalEpochSourceService`不提供第二个owner删除入口，空source replacement也不能绕过统一检查。
该调用只是可检查的pre-disable步骤，不会也不能替Bukkit禁用插件；任一world仍有applied-plan dependency、pending
route reservation、非空host-owned claim或该插件source发布的非空restriction流时抛出诊断，并且在删除任何一侧注册前
完成全部只读检查。无关列车、仅已安装但restriction流均为空的source不阻止停用。`PluginDisableEvent`本身不可取消，
事件路径因此只处理“已经被强制禁用”：保留applied-plan dependency，移除普通provider/platform ownership和失效
signal callback，但不释放route-admission claim/reservation；只对该插件仍有实际基础资源使用或非空restriction的world
通过既有Stall回调fail-close。
检查同时覆盖插件作为requester持有的路线，以及它作为provider注册、但由其他requester持有的stable resource类型；
provider ownership不另建长期索引，而是只在低频停用边界读取applied-plan dependencies、现有claim、release queue和pending compiled request。
单个`InfrastructureResourceProviderRegistration.close()`执行同一检查，不能绕过owner unregister删除仍被使用的type。
route-resource contributor集合在任一world仍有route state时不可增删；强制失去contributor会通过既有Stall owner
fail-close该world，不能让后续suffix申请少执行一套信号策略。
空claim、无pending、仅遗漏`endGrant`的route state可直接清除。JavaRailways自身禁用、service已关闭或
`Bukkit.isStopping()`时走正常world/service close，不把服务器shutdown误报为附属插件故障。不使用永久owner tombstone、
启用周期token、自动drain、跨插件实例继承或恢复状态机。

`SignalEpochSourceService`同样绑定plugin instance和exact world runtime。非空全集replacement可删除其中部分source ID，
但删除前插件必须先对每个对应`(sourceId, trainId)`流发布更高revision的空restriction snapshot；完整owner删除只由上述
统一pre-disable入口执行。source全集在simulation context内一次替换，不与physics epoch交错。若附属插件被强制禁用，
host总是删除失效callback，但只在仍有非空owner restriction时Stall该world；world unload与JavaRailways shutdown只清理
binding，不误报故障。基础资源和signal的强制清理由同一个listener触发，不维护两套disable顺序。

## Simulation 与 physics

`WorldManager` 用 global scheduler 每 tick 驱动串行 simulation context：

```text
wake physics + generic preTick
  → capture exact PhysicsTicket and await
  → consume immutable published frames/safety requests
  → timetable dispatcher + generic postTick
  → dispatch platform commits to target owners
  → periodically capture immutable snapshots
```

只 capture 一次并等待该 ticket；不能在 physics failure 后消费旧 frame。snapshot capture 在 simulation owner 完成，JSON/IO 交给单线程 writer，per-world pending 采用 latest-only。

## Folia 线程归属

- `PlatformDispatcher.runGlobal*`：无 location 的 simulation/value 协调。
- `runAsync`：纯计算/IO；完成后不能直接触碰 Bukkit state。
- `runAt(location)`：block/location/region operation。
- `runForEntity*`：player/entity inventory、message、dialog、movement 等，并处理 retired callback。
- `BukkitThreadOwnership.ensureLocation/ensureEntity`：执行点断言，不是 scheduler。

`ThreadGuard.ensureMainThread()` 在 Bukkit bridge 下只证明 simulation serialization；对 entity/location 副作用仍要 target-aware ownership。参考 `BukkitThreadOwnership.kt`、`PlatformDispatcher.kt`、`BukkitThreadGuardBridgeTest.kt`。

异步结果回 owner 后应重查：plugin/world 是否 stopping/unloading、player/entity 是否仍有效、world 是否改变、revision 是否仍是最新。`LatestAffinityTaskDispatcher` 的 per-key one-in-flight + latest-pending 模式用于限制 region backlog；不要恢复逐 tick 无界 task queue。

## 世界 lifecycle

- world load 的 split snapshot `Missing` 可创建新 world；`Corrupted` 禁用该 world 的 JavaRailways 功能并要求修复，不能当空世界覆盖。
- unload 先标记 unloading、等待 exact physics ticket、捕获/写最终 snapshot，再 unregister safety processor、close coordinator/render/tick owner。
- platform commit 执行前重新解析 affinity；目标跨 region 时 reschedule，而不是在旧 owner 上执行。

线程相关权威证据：`WorldManager.kt`、`BukkitThreadGuardBridge.kt`、`PlatformDispatcher.kt`、`docs-site/docs/architecture/threading-model.md`。
