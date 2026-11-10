# Lifecycle 与线程归属

## Composition 顺序

`BukkitPlugin.kt` 的阶段边界：

- `onLoad`：检测 platform capabilities、创建 dispatcher、读取 physics/global config；此阶段不创建或初始化 PacketEvents。
- `onEnable`：enable packet/runtime、创建 render backend、启动 Koin/Basic/plugin registry、安装 `ThreadGuard` bridge、注册 world/service/listener/control、加载模型、初始化 Wand/Cloud、注册 worlds，最后启动 WebUI sidecar 与 `WorldManager`。
- 自身 `PluginDisableEvent` 在 enabled=false 前调用准备关闭；`onDisable` 幂等兜底准备并完成网络、DI、注册和日志释放。先失效 services/入口，再静止 dispatcher/physics，释放控制会话并保存世界，等待 owner cleanup 后释放自己的 packet runtime 资源；独立 PacketEvents 继续运行。

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


## 热启停资源契约

### 1. 触发范围

BukkitPlugin、WorldManager、dispatcher、模型/工具和 add-on 在同实例启停或类加载器替换时必须释放单次 enable 资源。Cloud 命令管理器仍是独立未解决项，本轮不宣称完整 PlugMan 兼容。

### 2. 签名

- `PhysicsCoordinator.stopAndAwait(timeoutMillis = 10_000)` 永久停止新 epoch，成功后允许读最终 ticket。
- `WorldManager.quiescePhysics()` 在 simulation context 中调用；控制会话必须在 world map 移除前释放。
- `PlatformCleanup.runAndAwait(timeoutMillis = 10_000, action)` 追踪跨 owner 及其嵌套任务；等待时不得持 simulation lock。
- `PaperPlatformDispatcher.stopAndAwait()` 封住旧 enable 的正常回调，新 enable 使用新 dispatcher。
- `AbstractWand.close()` 保存在线玩家绑定状态、释放预览/会话并停止周期任务；不可读 PDC 不被内存默认值覆盖。

### 3. 契约

服务在任何 world/physics lock 之前注销，consumer 先失效 lease。最终 snapshot 只从成功静止的 physics 捕获；之后每个 world 的 render/tick/DI owner 独立释放，即使保存失败也不跳过其他 cleanup。Native Display 删除通过 entity owner 调度。Seat 静态 registry 在模型销毁和 cleanup barrier 后关闭。

### 4. 校验与错误

- physics failure/timeout：禁止以旧成功 ticket 保存新 snapshot，保留现有文件并报告。
- services/HTTP/dispatcher producer 停止失败：跳过最终 snapshot，保留已有恢复材料。
- native cleanup 失败/超时：汇总错误；超时 batch 的晚到任务禁止再执行。不完整关闭后同实例再次 enable 必须失败并要求重启服务器，防止仍运行的旧回调进入新 runtime。
- 旧服务引用/回调：拒绝新操作，不能解析下一轮 WorldManager。
- Folia server stopping：仅 `platformCapabilities.folia && server.isStopping` 时使用 `PlatformCleanup.runAndAwait(skipOwnerTasks = true)`；停止提交 region/entity 动作，保留本地 teardown 与最终快照 writer，见下节。

### 5. 正常、基本和失败场景

正常：global 禁用事件中完成所有 owner cleanup，再关闭网络。基本：无世界或部分启动失败也可以重复关闭。失败：从 region A 阻塞等待 B，而 B 再向 A 提交任务可能超时；不得把这种情况宣称为任意调用线程热禁用安全。

### 6. 必需测试

`PlatformCleanupTest` 覆盖嵌套任务、退役、异常、超时后迟到任务；`PlatformDispatchLifecycleTest` 覆盖已执行任务的停止屏障与旧代回调；`SeatListenerLifecycleTest` 覆盖 callback 释放。真实 Paper/Folia 在线/乘坐玩家和 PlugMan/PlugManX 的命令矩阵是独立验收，单元测试不能替代。

### 7. 错误与正确做法

错误：在 onDisable 才向 Folia 提交删除任务，并将排队返回视为实体已删除。正确：利用 enabled 仍为 true 的禁用前窗口，追踪任务实际完成；失败明确报告，不能绕过 owner guard 或伪造 enabled。


## 独立 PacketEvents 资源归属

### 1. 触发范围

JavaRailways 依赖独立安装的 `packetevents`，EntityLib 仍重定位在主插件中。此契约替代内嵌 PacketEvents 的启动和终止方案。

### 2. 签名

`PacketEntityRuntime.enable()` 取得 `PacketEvents.getAPI()` 并绑定本插件 EntityLib；`close()` 仅释放本插件资源。`PacketEntityMotion.start()/close()` 对注册时的 EventManager 成对注册、注销同一个 listener。

### 3. 契约

`plugin.yml` 声明 `depend: [packetevents]`；编译基线为 2.12.1。启用时检查外部 API 已初始化、未终止，且版本不低于 2.12.1。禁止对共享 API 调用 `setAPI/load/init/terminate/uninject/clearBuildCache/unregisterAllListeners`。关闭只清理本插件的虚拟实体、motion listener 和状态；不得改变其他 consumer 的 listener、在线玩家协议状态或网络 handler。EntityLib 不启用 ViewerEngine、tickTickables、BStats 或更新检查。

### 4. 校验与错误

缺少依赖由插件加载器拒绝加载；API 不可用或未初始化时启用失败，不尝试自行启动 PacketEvents。部分启动失败和重复 close 仍遵循相同资源边界。独立 PacketEvents 自身的热重载不属于 JavaRailways 单独热重载验收范围。

### 5. 正常、基本和失败场景

正常：只重载 JavaRailways，复用持续运行的共享 PacketEvents。基本：无世界、没有 motion 或部分 EntityLib 初始化也可关闭。失败：依赖未就绪时不注册 listener；清理失败需报告，不能通过全局注销修复。

### 6. 必需测试

覆盖外部 API 状态校验、同实例关闭再启用、部分初始化失败；用真实 EventManager 验证自己的 listener 消失、其他 listener 保留。检查 shadow JAR 不含 PacketEvents 类且 plugin.yml 含硬依赖。实际 Paper/Folia 在线玩家热重载仍需单独验收。

### 7. 错误与正确做法

错误：JavaRailways 禁用时调用共享 API 的 `terminate()` 或 `unregisterAllListeners()`。正确：注销本插件注册的准确 listener，销毁自己的 EntityLib 实体，由独立 PacketEvents 插件管理网络库生命周期。


## Folia 单线程诊断与停服分支

### 1. 触发范围

用户接受单 tick worker 的热禁用超时限制。Folia 完整停服不再依赖已停止的区域 tick 删除临时原生实体；普通热禁用保留原有清理行为。

### 2. 签名

- `FoliaRuntimeDiagnostics.warnIfSingleWorker(logger)` 在 enable 后首个 global callback 读取 `TickRegions.getScheduler().getTotalThreadCount()`。
- `PlatformCleanup.runAndAwait(timeoutMillis = 10_000, skipOwnerTasks = false, action)` 的 skip 只用于真实 Folia 停服。
- `PlatformCleanup.skipsOwnerTasks()` 是当前 batch 的线程局部属性；dispatcher 的 location/entity immediate/delayed 操作在访问 Bukkit 或创建计数 task 前直接返回。

### 3. 契约

计数为 1 时警告；多线程不发单线程警告；未知/反射失败明确报告无法检查。不得把初次 onEnable 时未启动的 0 个线程或 CPU 数推断为 1。通过已存在的 dispatcher lifecycle gate 阻止禁用后的迟到诊断。

Folia + server.isStopping 才跳过 owner 动作；Paper 关闭、Folia 热禁用保留 barrier。跳过的是非持久化实体删除、预览和停服冗余的玩家工具最后扫描；服务、simulation/physics、JR 快照、writer、listener、HTTP、执行器和本地资源仍按顺序关闭。

所有活跃 World.spawn consumer 在实体加入世界前设置 isPersistent=false。客户端数据包实体不插入原生世界。玩家 persistent 不改动；非持久化载具仅阻止 RootVehicle 写入，不抑制玩家自身数据保存。工具物品 PDC 必须在各状态变更的玩家 owner 回调里立即写回；热禁用仍保留 close flush。不能把用户的 TBB 方块修改或 JR 快照归类为临时资源。

### 4. 校验与错误

线程数未知不阻止启用，也不能宣称已通过单线程检查。单线程热禁用仍可能 10 秒超时，不能把警告描述为修复。停服跳过 owner 动作必须不调用 owns/entity scheduler/region scheduler，也不能绕过线程检查直接执行动作。保存/producer 失败沿用原失败保护。

### 5. 正常、基本和失败场景

正常：多线程 Folia 热禁用按原 barrier 完成；停服只做本地清理和 JR 保存。基本：一线程运行时记录风险；无实体、无在线玩家也可重复关闭。失败：无法反射读取 worker 数时发独立诊断；普通热禁用等待超时仍报告关闭不完整。

### 6. 必需测试

诊断覆盖 1、多线程、0/未知/读取异常；停服 batch 直接通过生产 dispatcher 验证动作、retired callback、Bukkit ownership 和 scheduler 全部不执行，且本地 action 仍完成、batch 状态在返回后恢复。默认 batch 的正常、嵌套、退役、失败和迟到回调测试保持。运行 Bukkit 测试及 shadowJar；实服实体/playerdata 保存验收单独记录。

### 7. 错误与正确做法

错误：根据 folia=true 对所有禁用跳过清理，或在停服线程直接 remove 任意实体。正确：只有确认真实停服才丢弃 owner 动作，临时实体依靠创建时的 nonpersistent 契约不入档，业务数据仍保存。


## PlugManX 操作与 sidecar 退出确认

### 1. 触发范围

当前支持目标限定 PlugManX 3.2.1 Paper/Folia。reload 使用其硬依赖级联；本轮不解决任意管理器的同实例 Cloud 注册，也不增加跨类加载器关闭失败标记。

### 2. 签名

PlugManX 配置 `reloadDependentsMode: REQUIRED_ONLY`（或 ALL），操作 `/plugman reload JavaRailways`。TimetableWebUiSidecar.start/stop 在实例 monitor 上串行，process 为 volatile，退出观察 callback 不清除 owner。

### 3. 契约

TBB/Dev 均声明 depend JavaRailways，不能被 PlugManX 忽略。默认 reload 先卸载附属，再替换主插件，最后加载附属；独立 disable/enable 不级联，不能在旧 API Class 的 consumer 留存时宣称可以恢复服务。本仓库只更新操作指引，不自动改外部配置或重载其他插件。

sidecar stop 依次正常终止/等待、必要时强制终止/等待，并核对实际 isAlive；只有确认退出才清空 process。BukkitPlugin 的 timetableWebUiSidecar owner 也仅在 stop 成功后清空。失败仍走现有 shutdownFailures 汇总，不能据此声称失败保护已跨类加载器生效。

### 4. 校验与错误

强制后仍存活、destroy/wait 异常：保留 Process，错误包含 PID 和 cause。InterruptedException 单独处理并恢复线程中断标志，保留引用。已退出直接释放；重复成功 stop 幂等。onExit 只报告意外退出，不替 stop 判定成功。

### 5. 正常、基本和失败场景

正常：正常退出或强制退出后才能启动下一轮。基本：已退出、重复 stop 不重复终止。失败：第二次等待超时仍活着必须报告失败，保留 handle 以供后续检查/重试；不能把请求 destroyForcibly 当作进程已退出。

### 6. 必需测试

TimetableWebUiSidecarTest 对实际 stop 和 process owner 字段验证正常退出、强制退出、二次超时与后续重试、已退出、重复停止、中断标志与异常 cause、终止方法异常的 PID 上下文。实际子进程端口与服务器重载验收单独记录。

### 7. 错误与正确做法

错误：先 process=null，再 destroyForcibly 并忽略 waitFor 结果。正确：持有 Process 到确认退出；任何失败保留 owner，并让 composition root 收到失败。
