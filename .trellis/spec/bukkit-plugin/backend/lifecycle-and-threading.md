# Lifecycle 与线程归属

## Composition 顺序

`BukkitPlugin.kt` 的阶段边界：

- `onLoad`：检测 platform capabilities、创建 dispatcher、读取 physics/global config、加载 packet runtime；需在连接注入前完成的工作放这里。
- `onEnable`：enable packet/runtime、创建 render backend、启动 Koin/Basic/plugin registry、安装 `ThreadGuard` bridge、注册 world/service/listener/control、加载模型、初始化 Wand/Cloud、注册 worlds，最后启动 WebUI sidecar 与 `WorldManager`。
- `onDisable`：先停止外部入口/sidecar/HTTP，再停止 world manager、保存并 unregister worlds，关闭 control/event/log/packet/thread bridge。每步通过有上下文日志的 cleanup 隔离异常。

新增 component 必须回答：谁创建、何时可用、谁注册、如何停止、world unload 与 plugin disable 是否都释放。不要依赖 JVM 退出回收 executor/task/registration。

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
