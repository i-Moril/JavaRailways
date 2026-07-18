# Folia 兼容性审计与适配工作清单

审计日期：2026-07-18

范围：静态审计 `BukkitPlugin/src/main/java`、`Core/src/main/java` 中与 Bukkit/Paper 线程、调度、实体/玩家/世界访问相关的代码。本文只记录适配工作，不包含代码修改。

## 结论

当前 MineRails **不兼容 Folia**。代码整体仍基于传统 Paper/Spigot 的“单 Bukkit 主线程”模型：

- `plugin.yml` 未声明 `folia-supported: true`。
- 审计时多处使用 `BukkitScheduler.runTask/runTaskTimer/runTaskAsynchronously`；当前适配分支已替换这些生产调用，但尚未完成所有操作的 region/entity ownership 审计。
- `ThreadGuard` 将 `Bukkit.isPrimaryThread()` 作为主线程判断。
- 核心 tick/render commit 通过全服 `ServerTickStartEvent/ServerTickEndEvent` 遍历所有世界和玩家。
- 大量实体、玩家、世界操作没有通过 Folia 的 `RegionScheduler` / `EntityScheduler` / `GlobalRegionScheduler` 隔离。

适配复杂度：**高**。不是简单替换 scheduler API 就能完成；首要工作是解除 Core tick 对单一主线程 commit 的依赖，再把渲染和 Bukkit 副作用交给平台调度层。

## 适配复杂度评估

| 模块 | 复杂度 | 原因 |
|---|---:|---|
| 插件元数据与构建 | 低 | 只需生成 `folia-supported: true`，但必须等代码真实兼容后再开启。 |
| 调度器抽象 | 中 | 需要统一封装 Paper/Folia 的 sync、async、timer、entity、region 调度。 |
| ThreadGuard 语义 | 高 | 当前只有 `MAIN_THREAD`；Folia 需要表达 entity/region/global ownership。 |
| Core tick / 状态发布 / render commit | 高 | 当前 `TickManagerImpl.postTick()` 串行 commit，且 Core 状态发布与 Bukkit 渲染副作用耦合；需要改为分片计算、不可变快照发布和异步消费。 |
| 渲染实体 backend | 高 | Native display backend 大量实体 spawn/teleport/passenger 操作，必须在正确 region/entity scheduler 执行。 |
| 玩家可见性/控制/座位 | 高 | 玩家、座位实体、列车实体可能跨 region，需要 entity scheduler 协调。 |
| Wand 工具 | 中高 | 定时粒子、marker、preview、在线玩家遍历都要改为 per-player/per-location 调度。 |
| 持久化/模型加载 | 中 | 主要是异步和回调线程适配，避免回调直接访问 Bukkit 对象。 |

预估工作量：**至少按多阶段重构评估**。仅完成基础 Folia 启动与简单列车渲染，工作量可能接近 1–2 周；若同时完成无主线程 commit、背压、结构事务、座位/驾驶和完整功能验证，工作量会明显更高，需在快照原型和压力测试后重新估算。

## 已确定的首要架构方向：tick 与平台副作用解耦

不在 Folia 下寻找一个新的“全局主线程”替代 Bukkit main thread。目标模型为：

```text
physics/ticking workers
        │
        ├─ 每列车或每分片计算 next state
        ▼
原子发布 immutable/versioned snapshot
        │
        ├─ Core 查询读取 published snapshot
        ├─ shared index 使用完整 epoch 或允许一帧延迟的 snapshot
        ▼
有界、latest-wins 的 visual update mailbox
        ▼
Paper/Folia PlatformDispatcher
        ▼
entity / player / region / global scheduler
```

核心原则：

1. **Core publish 不调用 Bukkit API。** `Synchronizable.commit()`、`CoreTrain.commitRenderState()`、render extension callback 不得继续组成一条主线程调用链。
2. **取消 O(N) 的单线程 render commit。** 不再由 `WorldManager.onTickEnd()` 遍历所有待渲染列车并同步执行实体更新。
3. **单列车有序，不追求全世界强确定性。** 每列车保留单写者或严格的 epoch/version 顺序；不同列车可以并行发布和渲染。
4. **渲染 latest-wins。** 同一列车积压多帧时覆盖旧帧，平台渲染落后不能反向阻塞物理。
5. **队列必须有界。** 禁止每 tick 无限制累计闭包或实体操作；应按 train/entity key 合并，并暴露 dropped/coalesced/lag 指标。
6. **结构操作仍需受控。** create/remove/split/link、轨道拓扑编辑、持久化快照等不能与其所属对象的 physics writer 任意并发，应通过 world/train command mailbox 或短事务执行。
7. **可以保留轻量 epoch 协调器。** 它只负责唤醒、边界、共享索引切换和指标，不能承担串行 Bukkit render commit；后续再依据压力测试决定是否取消全局 physics barrier。

需要优先切断的现有调用链：

```text
WorldManager.onTickEnd
  -> CoreWorld.commitPublishedTrainRenderState
  -> CoreTrain.commitRenderState
  -> CoreCar/TrainEnd.commit
  -> DynamicRenderableTrait.commit
  -> RenderableExtension.commit
  -> Bukkit entity/player/world operations
```

第一阶段验收标准：即使平台渲染消费者被人为阻塞，physics epoch 仍能继续发布；内存保持有界；恢复消费者后只应用每列车最新 snapshot。

## 实施进度

当前分支：`feat/folia-tick-decoupling`

### 2026-07-18：T2 基础发布通道已开始

已完成：

- 新增 value-only `RenderableFrameSnapshot` / `TrainFrameSnapshot`，向量以标量值复制，不保留 live JOML、World 或 Bukkit 引用。
- `CoreTrain` 持有原子发布的 `publishedFrame`，使用单列车单调 revision，并记录来源 physics epoch。
- `PhysicsCoordinator` 在 physics buffer 发布后生成完整 train frame。
- `CoreWorld` 以有界 `LatestTrainFrameMailbox` 接收 frame；同一列车只保留最新帧，拒绝 stale revision，容量耗尽时有明确淘汰指标。
- 删除 `CoreWorld.commitPublishedTrainRenderState()` 生产调用链；`WorldManager` drain frame 后，由 `CarExtension.applyFrame()` 直接消费对应 car/bogie value snapshot。
- `RenderChunkManager` 的受众距离判断使用 frame 中的新位置，不再回读 live renderable position。
- `TickManagerImpl.postTick()` 不再调用 `Synchronizable.commit()`；worker 只向有界、按对象 identity 合并的 commit mailbox 发布请求，Paper 过渡消费者位于 `WorldManager`。
- `/mr perf` 输出 visual frame 与 generic tick commit mailbox 的容量、峰值、合并和丢弃指标。
- 新增 Paper/Folia 共用的 `PlatformDispatcher`；异步查询完成回调改到 player entity scheduler，seat/teleport 后处理改到相关 entity scheduler，四个传统 repeating timer 改用 global scheduler 分发到各 player scheduler。
- `BukkitPlugin/src/main/java` 已无 `BukkitScheduler.runTask/runTaskTimer/runTaskAsynchronously` 生产调用。
- 新增 target-aware `BukkitThreadOwnership`；玩家移动/加入/传送等渲染受众事件改为校验 entity ownership，而非 `Bukkit.isPrimaryThread()`。
- 玩家状态、seat binding、控制会话和 wand 跨玩家容器开始迁移到 concurrent collections；global timer 只选择实体，实际玩家访问进入 entity scheduler。
- 增加快照集合防御性复制、并发 publisher、stale frame、coalescing、capacity eviction、连续 physics epoch、阻塞 visual consumer 和 generic commit 慢消费者测试。

尚未完成：

- `WorldManager` 仍在 Paper TickEnd 同步 drain visual frame；尚未按 Folia entity/region ownership 调度。
- `CarExtension` 已消费 car/bogie frame，但座位、玩家受众及部分模型生命周期逻辑仍需要平台线程上下文。
- Core 的显式 maintenance/test `CoreTrain.commit()` 暂时保留同步 legacy render 通知，用于兼容非 physics 命令路径。
- generic commit mailbox 目前仍由 `WorldManager` 在 Paper TickEnd 串行消费；尚未声明 entity/region affinity。
- `ThreadGuard.ensureMainThread` 尚未改造成 entity/region ownership guard；Folia region callback 仍可能触发旧断言。
- TrainControlManager 的跨玩家/跨列车复合事务虽已使用 concurrent map，但尚未建立原子 command ownership。
- mailbox 尚未接入时间/数量预算；监控已接入 `/mr perf`。

本阶段验证命令：

```bash
./gradlew :Core:test :BukkitPlugin:compileKotlin
```

## 调度开销复审（2026-07-18）

结论：snapshot/commit mailbox 降低了无界积压，但当前 Folia scheduler 迁移引入了明显的任务 fan-out；在高列车数或高玩家数服务器上，**现状不适合直接开启 Folia 声明**。

### 严重：renderable × player 调度乘法放大

调用链：

```text
每个 active car 的 TrainFrame
  -> CarExtension.applyFrame
  -> RenderChunkManager.update
  -> updateAudience
  -> world.players.forEach
  -> player EntityScheduler.execute
```

当前任务提交量近似：

```text
20 × activeCarCount × worldPlayerCount / 秒
```

示例：100 个活动车厢、50 名同世界玩家会产生约 100,000 次 entity task submission/s，尚未计算模型、座位和 show/hide 操作。这会把原有 O(car × player) 计算进一步放大为同数量级的 scheduler 入队、lambda/task 分配和跨线程唤醒，属于必须先修复的问题。

整改：

- train frame consumer 先批量更新所有 render spatial index。
- 按玩家维护 latest-wins audience mailbox/dirty flag。
- 每个玩家同一 tick 最多提交一次 entity task，在该任务中批量 reconcile 所有移动 renderable。
- 进一步按 old/new chunk 的 watcher 索引筛选受影响玩家，避免遍历整个 world player 集合。

目标复杂度从 `O(car × player)` 次调度降为每批最多 `O(player)` 次调度；内部距离计算仍可批处理或按 chunk 缩小。

### 高：驾驶输入每 tick 全局 fan-out

`TrainHandleInputListener` 每 tick 执行一次 global task，再为 `states` 中每个玩家提交 entity task。`NONE/CENTER` 状态当前不会立即从 map 移除，因此普通玩家触发过输入事件后可能长期留在扫描集合中。

近似任务量：

```text
20 × statePlayerCount + 4 × (onlinePlayerCount - statePlayerCount) / 秒
```

整改：

- 只为实际驾驶玩家创建 per-player repeating entity task。
- 方向回到 `NONE`、离开驾驶座、下线时立即 cancel/remove。
- action bar 合并到同一驾驶 task，不再每 5 tick 扫描全部在线玩家。

### 中高：两个 Wand timer 无条件调度全部玩家

- `RailWand` 每 4 tick 为全部在线玩家提交 entity task，进入任务后才检查是否持有 RailWand/SPLIT 模式。
- `RailObjectWand` 每 4 tick 为全部在线玩家提交 entity task，进入任务后才检查模式。

两者合计约 `10 × onlinePlayerCount` 次 entity submission/s。应维护 active wand viewer UUID set，只调度当前持有对应 wand 且处于需要刷新模式的玩家。

### 中：快照对象与转换分配

每个 car frame 当前大约创建：1 个 `CarFrameSnapshot`、1 个 car render state、每个 bogie 1 个 wrapper + 1 个 render state，以及每个 render state 4 个 `Vector3Snapshot`。按常见 2 bogie 估算约 18 个对象/car/physics frame，即约 `360 × activeCarCount` 个对象/s，未含 List、`associateBy` map 和 `toVector3d()` 临时对象。

整改：

- 将 train frame 改为 flat primitive arrays/packed value buffer，仍保持发布后不可变。
- `WorldManager.applyPublishedTrainFrames` 避免每帧 `associateBy`，使用稳定 car index 或持久 ID lookup。
- render consumer 减少重复 `toVector3d()`，在单次消费内复用局部值对象。

### 中低：generic commit mailbox 锁竞争

`LatestTickCommitMailbox.offer()` 使用单锁。它避免了主线程重型 commit 和无界队列，但大量并行动画会在每 tick 竞争同一 monitor。当前 animation/action 数量通常远少于 car 数，因此优先级低于 render fan-out；后续可改为分片 identity mailbox。

### 低：事件型和异步型调度

Dialog callback、异步查询 completion、teleport 后刷新、seat mount/dismount 都是事件驱动，一次事件大致对应一次旧 `runTask`，迁移到 entity/async scheduler 没有明显增加任务数量，且 ownership 更准确。

### 立即优化项

1. `PlatformDispatcher.runForEntity/runAt` 增加 ownership fast path：已处于目标上下文时直接执行，避免 Paper 主线程和 Folia 同 region 的重复入队。
2. 合并 `RenderChunkManager.updateAudience`，禁止 car × player 逐项提交任务。
3. 将驾驶输入改为 active-driver per-entity task。
4. Wand timer 只遍历 active viewer。
5. 给 `PlatformDispatcher` 增加 submitted/executed/retired/direct-fast-path 指标，并显示到 `/mr perf`。

## 发现的关键阻塞点

### 1. plugin.yml 未声明 Folia 支持

当前生成文件：`BukkitPlugin/build/generated/resourceFactory/main/plugin.yml`

未包含：

```yaml
folia-supported: true
```

对应 Gradle 配置：

- `BukkitPlugin/build.gradle`
  - `bukkitPluginYaml { ... }`

注意：这个声明只能在完成适配并通过 Folia smoke test 后添加，否则 Folia 可能允许加载但运行期崩溃。

### 2. 传统 BukkitScheduler 使用点（当前分支已完成 API 替换）

当前分支已引入 `PlatformDispatcher`，生产源码中不再直接调用传统 `BukkitScheduler`。Paper 与 Folia 共用 Paper 提供的 global/async/region/entity scheduler API；剩余工作是继续校验每项操作选择的 ownership 是否正确。原审计位置如下：

主要位置：

- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/util/BukkitAsyncTasks.kt`
  - `runTaskAsynchronously`
  - `runTask`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/control/TrainHandleInputListener.kt`
  - `Bukkit.getScheduler().runTaskTimer(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/control/TrainControlManager.kt`
  - `plugin.server.scheduler.runTaskTimer(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/control/TrainControlDialog.kt`
  - `plugin.server.scheduler.runTask(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/chunk/RenderChunkManager.kt`
  - `Bukkit.getScheduler().runTask(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/SeatModel.kt`
  - 多处 `Bukkit.getScheduler().runTask(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt`
  - `runTaskTimer(...)`
  - `runTask(...)`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailObjectWand.kt`
  - `runTaskTimer(...)`
  - `runTask(...)`

### 3. ThreadGuard 主线程模型不适配 Folia

当前实现：

- `Core/src/main/java/work/art1st/mineRails/core/util/ThreadGuard.kt`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/util/BukkitThreadGuardBridge.kt`

现状：

```kotlin
override fun isMainThread(): Boolean = Bukkit.isPrimaryThread()
```

Folia 下没有传统全服主线程。需要重新设计：

- `ensureMainThread` 是否改名为 `ensurePlatformThread` / `ensureOwnedContext`。
- 区分：
  - global context
  - region context
  - entity context
  - async worker context
- 对实体/玩家操作，需要能判断或保证运行于对应 entity scheduler。
- 对世界坐标操作，需要能判断或保证运行于对应 region scheduler。

受影响调用很多，静态搜索约 37 处 `ThreadGuard.ensureMainThread(...)`，包括：

- `logic/WorldManager.kt`
- `logic/RailWorldFactory.kt`
- `operation/BukkitTrainOperationGateway.kt`
- `render/chunk/RenderChunkManager.kt`
- `extension/train/SeatExtension.kt`
- `extension/train/TrainComponentExtension.kt`
- `extension/train/TrainExtension.kt`
- `extension/rail/RailExtension.kt`
- `debug/AbnormalTrainMarker.kt`

### 4. 世界级 tick 与 render commit 模型需要重构

核心文件：

- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/logic/WorldManager.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/TickManagerImpl.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/tick/PhysicsCoordinator.kt`

当前 `WorldManager` 使用：

- `ServerTickStartEvent`
- `ServerTickEndEvent`

并在 tick 中遍历所有 `railWorlds`，执行：

- physics wake / await
- `railWorld.tickManager.preTick/postTick`
- `coreWorld.commitPublishedTrainRenderState()`
- timetable dispatcher tick
- debug marker tick
- seat extension tick

Folia 问题：

- 全服 tick 回调中不能直接操作所有世界/玩家/实体。
- `commitPublishedTrainRenderState()` 最终会触发实体 teleport、show/hide、passenger 等操作，必须拆分到对应 region/entity scheduler。
- `TickManagerImpl.scheduleMainThreadAfterCommitTask/PostTickTask` 的名字和语义都假定一个主线程 drain queue，Folia 下需要变成 platform-owned task queue 或 per-world/per-region commit queue。

### 5. 实体 spawn / teleport / passenger 操作需要 scheduler 包装

静态搜索到至少：

- `World.spawn(...)`：约 14 处
- `Entity.teleport(...)` / `Player.teleport(...)`：约 13 处
- `addPassenger(...)`：约 4 处
- `removePassenger(...)`：约 4 处
- `showEntity(...)`：约 4 处
- `hideEntity(...)`：约 2 处

重点文件：

- `render/backend/NativeDisplayRenderBackend.kt`
  - `world.spawn(...)`
  - `entity.teleport(...)`
  - `entity.addPassenger(...)`
  - `entity.remove()`
  - `player.showEntity/hideEntity(...)`
- `render/model/CompositeModel.kt`
  - `Bukkit.isPrimaryThread()` 判断
  - `rootVisual.teleport(...)`
- `render/model/SeatModel.kt`
  - spawn Interaction hitbox/carrier
  - teleport hitbox/carrier
  - add/remove passenger
  - remove/eject entity
- `render/model/PlatformModel.kt`
  - spawn Chicken/Shulker
  - passenger mount
  - teleport carrier
- `render/model/DisplayModel.kt`
- `render/model/LodDisplayModel.kt`
- `render/model/EntityModel.kt`
- `config/model/ModelTreeBuilder.kt`
- `render/config/ModelConfig.kt`
- `extension/train/SeatExtension.kt`
- `extension/rail/RailExtension.kt`
- `tool/RailWand.kt`
- `tool/RailObjectWand.kt`

### 6. 玩家集合遍历与跨玩家操作需要改造

当前使用点：

- `render/chunk/RenderChunkManager.kt`
  - `world.players.forEach(...)`
  - `world.players.associateBy(...)`
- `control/TrainControlManager.kt`
  - `plugin.server.onlinePlayers.forEach(...)`
- `control/TrainHandleInputListener.kt`
  - `Bukkit.getOnlinePlayers().forEach(...)`
  - `Bukkit.getPlayer(...)`
- `extension/train/TrainComponentExtension.kt`
  - `Bukkit.getPlayer(...)`
- `tool/RailWand.kt`
  - `plugin.server.onlinePlayers.forEach(...)`
- `tool/RailObjectWand.kt`
  - `plugin.server.onlinePlayers.forEach(...)`

Folia 下不能在一个上下文中直接操作所有在线玩家。需要：

- 先获取玩家 UUID 列表时避免访问不安全状态。
- 每个玩家操作通过 `player.scheduler.run(...)`。
- 与某位置/实体相关的操作需要在正确 region/entity 上执行。

### 7. 座位与驾驶控制是高风险区域

相关文件：

- `render/model/SeatModel.kt`
- `extension/train/SeatExtension.kt`
- `control/TrainControlManager.kt`
- `control/TrainHandleInputListener.kt`

风险：

- 玩家骑乘 `Interaction` carrier，需要 player entity 和 carrier entity 的调度协调。
- 列车移动时持续 teleport carrier/hitbox，玩家可能处于不同 region。
- `EntityDismountEvent`、`PlayerQuitEvent`、`PlayerDeathEvent` 后的延迟恢复现在使用 `BukkitScheduler.runTask`。
- WASD 输入 timer 每 tick 扫描控制状态，当前是全局定时任务。

这部分适配难度高，建议作为独立 milestone 做 Folia 实机测试。

### 8. NativeDisplayRenderBackend 与 Packet/EntityLib backend 的取舍

当前 native backend 直接创建 Bukkit Display 实体：

- `render/backend/NativeDisplayRenderBackend.kt`

项目也有 packet/entitylib 相关 backend：

- `render/backend/EntityLibDisplayRenderBackend.kt`
- `render/backend/PacketEntityRuntime.kt`

适配策略需要决策：

1. **短期 Folia 优先**：尽量使用 packet/entitylib backend，减少真实 Bukkit Display 实体的 region 约束。
2. **完整 native 支持**：为 native backend 的每个实体操作加 entity/region scheduler。

即使使用 packet backend，玩家 show/hide、seat/platform 等真实实体仍需 Folia 调度。

## 建议的适配任务拆分

### T1：定义 Folia 兼容目标与测试矩阵

复杂度：低

产出：明确支持范围，例如：

- Folia 版本 / Minecraft 版本。
- 是否支持全部功能：轨道编辑、列车渲染、座位、驾驶、信号、时刻表、持久化。
- 是否允许 Folia 下默认禁用 native display backend。

建议 smoke tests：

1. 插件在 Folia 启动无报错。
2. 创建轨道。
3. 生成列车。
4. 列车移动并渲染。
5. 玩家进入/离开渲染范围。
6. 玩家坐上驾驶座并控制列车。
7. 跨 chunk/region 移动。
8. 世界卸载/重载后持久化恢复。

### T2：定义不可变、带版本的 Core 发布快照

复杂度：高

这是首要实现任务。建议为每列车或每 physics shard 定义完整发布单元，例如：

```kotlin
data class TrainFrameSnapshot(
    val epoch: Long,
    val trainId: String,
    val cars: List<CarFrameSnapshot>,
    val head: RailPositionSnapshot,
    val tail: RailPositionSnapshot,
    val sweptSpans: List<SweptRailSpan>
)
```

工作项：

- 明确 snapshot 的所有权：构建后不可变，消费者不得读取对应 live mutable state 补数据。
- 每列车通过 `AtomicReference`、版本槽或等价机制原子发布完整 frame。
- 将 `CommitBuffer` 的“active/published”语义收敛到 snapshot publish，避免对 position/direction/normal/motion 分别切换导致 torn frame。
- 渲染、持久化和只读查询明确各自读取 live state 还是 published snapshot；跨线程消费者只能读取 snapshot。
- epoch/version 单调递增，过期 frame 不得覆盖新 frame。

重点文件：

- `Core/src/main/java/work/art1st/mineRails/core/physics/train/CoreTrain.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/train/CoreCar.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/train/CoreBogie.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/train/TrainEnd.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/CommitBuffer.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/tick/PhysicsCoordinator.kt`

验收：并发读取 snapshot 时，不出现同一列车不同组件属于不同 epoch 的状态。

### T3：移除单一主线程 commit 与同步 render commit

复杂度：高

工作项：

- 拆除 `WorldManager.onTickEnd()` 对 `commitPublishedTrainRenderState()` 的同步依赖。
- 移除或替代：
  - `CoreWorld.pendingRenderTrainIds`
  - `CoreWorld.commitPublishedTrainRenderState()`
  - `CoreTrain.commitRenderState()` 作为全列车串行入口
  - `TickManagerImpl.postTick()` 中串行 `synchronizableObjects.forEach { commit() }`
- 将 `DynamicRenderableTrait.commit()` 的 extension 副作用改成发布纯数据 `VisualFrame`，不得调用 Bukkit API。
- 将 `scheduleMainThreadAfterCommitTask/PostTickTask` 拆成：
  - Core simulation command mailbox；
  - snapshot publish 后通知；
  - 平台 visual dispatcher。
- 物理发布路径不得等待 visual consumer 或 Folia scheduler 完成。
- 为 visual mailbox 实现按 `trainId`/`entityId` 合并的 latest-wins 与固定容量背压。
- 添加指标：publish rate、consumer lag、coalesced frames、dropped stale frames、mailbox size。

建议分两步落地：

1. 先保留现有 physics epoch barrier，但把每列车 snapshot publish 和 render 消费移出 Bukkit 主线程。
2. 压力测试后，再将串行 `snapshot.forEach { publishPhysicsState() }` 改为 worker/shard 内发布；协调器只切换共享索引和完成 epoch。

验收：暂停 visual consumer 时，physics 继续运行且 mailbox 内存有界；恢复后不会逐帧追赶旧状态。

### T4：建立结构命令与共享索引的一致性边界

复杂度：高

取消全局 commit 不等于允许所有对象自由并发写。以下操作需要显式所有权：

- create/remove train
- split/link/reverse/derail maintenance path
- controller register/unregister
- rail topology add/remove/edit
- world unload/save snapshot

建议：

- 为 world 或 train shard 建立有界 command mailbox。
- 普通列车命令发送到所属 train writer，在下一安全点执行。
- 跨列车 `link` 使用固定 ID 顺序获取所有权，或提交到 world structural executor；禁止在 physics worker 中把任务塞回“主线程”后无限等待。
- rail topology 使用 immutable/versioned graph snapshot，或短写事务加读 snapshot。
- swept occupancy index 以完整 epoch 原子替换；可接受 physics 使用上一 epoch index，以减少全局等待。
- 保存操作读取稳定 published snapshot，不遍历正在修改的 live state。

验收：并发进行 physics、列车增删/link 和保存时，无 ConcurrentModification、死锁、半完成结构或跨 epoch 混合快照。

### T5：引入平台能力检测、调度抽象与上下文 guard

复杂度：高

建议新增抽象：

- `PlatformDispatcher`
  - `runGlobal(...)`
  - `runAsync(...)`
  - `runEntity(entity, ...)`
  - `runPlayer(player, ...)`
  - `runRegion(world, x, z, ...)`
  - `runAtLocation(location, ...)`
  - `runTimerGlobal/Entity/Region(...)`
- `PlatformCapabilities`
  - `isFolia`
  - `hasRegionScheduler`
  - `supportsNativeDisplayEntityBackend`

Paper 实现可在 Bukkit main thread 直接执行或调度；Folia 实现使用 entity/player/region/global scheduler。平台 dispatcher 只消费 Core 已发布的 visual/platform operations，不能成为 Core physics barrier。

同时重构 `ThreadGuard`：

- Core 保留 async tick worker / simulation writer 判断，不再把 Core API 统一标记为 Bukkit `MAIN_THREAD`。
- Bukkit 层新增：
  - `ensureGlobalContext`
  - `ensureEntityContext(entity)`
  - `ensureRegionContext(location/world, chunkX, chunkZ)`
- 逐步替换约 37 处 `ensureMainThread`。

优先替换：

- `BukkitAsyncTasks.kt`
- `TrainHandleInputListener.kt`
- `TrainControlManager.kt`
- `RenderChunkManager.kt`
- `SeatModel.kt`
- `RailWand.kt`
- `RailObjectWand.kt`

### T6：适配渲染 backend

复杂度：高

分两条路线：

#### 路线 A：Folia 下优先 packet/entitylib backend

- 减少真实 Display 实体操作。
- 仍需处理玩家可见性和 seat/platform 真实实体。
- 可能更快达到可运行状态。

#### 路线 B：完整适配 native backend

需要包装：

- display spawn：region scheduler
- display teleport：entity scheduler
- passenger mount/dismount：entity scheduler，必要时协调 parent/passenger
- remove：entity scheduler
- player show/hide：player/entity scheduler

重点文件：

- `NativeDisplayRenderBackend.kt`
- `CompositeModel.kt`
- `DisplayModel.kt`
- `LodDisplayModel.kt`
- `ModelTreeBuilder.kt`
- `ModelConfig.kt`

### T7：适配座位、平台、驾驶控制

复杂度：高

工作项：

- `SeatModel` 的 hitbox/carrier spawn、teleport、passenger 操作全部 entity/region scheduler 化。
- `SeatInteractionListener` 的 delayed restore 从 `BukkitScheduler.runTask` 改为 player/carrier entity scheduler。
- `SeatExtension.tickAll()` 不能作为全局主线程任务直接遍历所有 seats。
- `TrainHandleInputListener` 的 held input timer 改为 per-player 或 global safe timer + player scheduler。
- `TrainControlManager.scanBoundKeys()` 改为 per-player scheduler。

重点风险：玩家骑乘跨 region 移动、列车高速跨 chunk、下车/死亡/退出时状态恢复。

### T8：适配 RenderChunkManager 与玩家可见性

复杂度：中高

当前职责：基于玩家移动和 renderable chunk 索引决定 send/hide/refresh。

需要改造：

- PlayerMove/Teleport/ChangedWorld 事件后，通过 player scheduler 处理玩家状态。
- `world.players` 遍历改为安全的 per-player dispatch。
- `dispatchSendTo/hideFrom/refreshFor` 不直接操作 Bukkit entity/player，而是进入对应 scheduler。
- Renderable 更新时，按受影响玩家分别调度。

### T9：适配 Wand 工具

复杂度：中高

重点文件：

- `tool/RailWand.kt`
- `tool/RailObjectWand.kt`

工作项：

- 定时 preview/particle/marker 刷新改为 Folia-safe timer。
- `onlinePlayers` 遍历改为 per-player scheduler。
- marker/anchor display entity spawn/show/remove 改为 region/entity scheduler。
- 异步查询回调不要直接访问 Bukkit 对象，回到 player/region scheduler。

### T10：适配异步工具与持久化回调

复杂度：中

重点文件：

- `util/BukkitAsyncTasks.kt`
- `persistence/*`
- `config/model/*`

工作项：

- 异步 supplier 禁止捕获可变 Bukkit 对象；只传快照或 UUID/坐标。
- success/failure callback 根据使用对象调度到 player/entity/region/global。
- 世界加载/卸载持久化需要明确运行上下文。

### T11：添加压力测试、Folia 测试并最后开启声明

复杂度：中

工作项：

- 增加 Core 并发测试：snapshot 原子性、版本单调性、mailbox 合并、结构命令并发。
- 增加背压测试：暂停 visual consumer 后，physics 不停顿且内存有界。
- 增加列车规模基准：比较改造前后的 physics wall time、publish time、visual lag 与平台任务数量。
- 增加 Folia run/test server 任务或脚本。
- 在 CI 或手动 smoke test 中覆盖核心流程和跨 region 移动。
- 全部通过后再添加 `folia-supported: true`。

## 推荐实施顺序

1. **不要立即加 `folia-supported: true`。**
2. 先为现有行为补 snapshot、并发与性能基线测试。
3. 定义不可变 `TrainFrameSnapshot` / `VisualFrame` 和单列车版本语义。
4. 切断 `Core commit -> RenderableExtension -> Bukkit API` 调用链。
5. 让 visual consumer 使用有界、按 key 合并的 latest-wins mailbox；证明渲染阻塞不会阻塞 physics。
6. 将 create/remove/split/link/topology/save 迁移到明确的 command ownership 模型。
7. 移除 `TickManagerImpl.postTick()` 的单线程 O(N) commit；再根据基准决定是否消除 physics 全局 epoch barrier。
8. 实现 `PlatformDispatcher` / `PlatformCapabilities`，并重构 `ThreadGuard` 上下文语义。
9. 替换传统 BukkitScheduler，适配 render backend 与玩家可见性。
10. 单独适配座位/驾驶和 wand/marker/preview。
11. 执行大规模列车压力测试与 Folia 跨 region smoke test。
12. 最后开启 `folia-supported: true`。

## 风险清单

- 只替换 `runTask` 为 `globalRegionScheduler` 不够；很多操作实际属于某个实体或 region。
- `Bukkit.isPrimaryThread()` 在 Folia 下不能表达安全访问权限。
- 列车跨 chunk/region 时，渲染实体、seat carrier、乘客玩家可能需要跨 scheduler 协调。
- 当前 `TickManagerImpl` 的 main-thread queue 不仅可能在 Folia 下造成错误上下文，还会形成 O(N) 串行 commit 瓶颈。
- latest-wins 会主动丢弃中间视觉帧，必须保证声音、门操作完成回调、乘客状态等不可丢事件不与可合并位置帧共用同一种 mailbox。
- 完全取消全局 epoch barrier 后，碰撞和 occupancy 读取可能看到不同发布时间；第一阶段应保留 epoch 边界或明确使用上一 epoch 的完整 index。
- snapshot 若包含可变 JOML 对象、Bukkit 对象或 live collection，仍可能发生 torn read；发布前必须深拷贝或使用真正不可变值。
- 结构命令与 physics writer 若没有明确所有权，split/link/remove 很容易产生死锁、丢车或半完成状态。
- `world.players` / `onlinePlayers` 遍历后直接访问玩家状态是高风险点。
- Native Display 实体 passenger 树在 Folia 下可能比 packet backend 更难稳定。

## 总体建议

本次适配以 **Core tick 与平台副作用解耦** 为前置工程，而不是先把 Bukkit `runTask` 机械替换成 Folia scheduler：

- Core physics/tick 发布不可变、带版本 snapshot。
- 不再存在统一 Bukkit 主线程上的全列车 render commit。
- 可合并视觉状态通过有界 latest-wins mailbox 消费。
- 不可丢的控制、声音、结构事务使用独立 command/event 通道。
- Paper 与 Folia 仅在 `PlatformDispatcher` 实现上分流。

达到上述基础后，可在 Folia 下默认使用 packet/entitylib 渲染 backend，先验证轨道、列车移动和基础可见性；native display passenger、座位和驾驶作为后续高风险阶段。这样不仅解决 Folia 线程模型冲突，也能避免列车数量增加时单线程 commit 持续拉长服务器 tick。
