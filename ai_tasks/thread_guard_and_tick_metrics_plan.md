# Thread Guard / Tick Metrics 方案

## 目标

围绕下面 4 个需求建立一套统一机制：

1. 确保复杂计算都不在主线程
2. 确保调用 Bukkit 非异步 API 的地方都在主线程
3. 允许查询一个异步 Train Tick 的耗时
4. 允许查询主线程里每一个 Tick 花了多少时间在我们的计算上

---

## 设计原则

- **先观测，后强制**：先把耗时和线程上下文看清楚，再逐步把 guard 从 warn 升级为 fail
- **分层处理**：
  - `ThreadGuard` 负责“当前线程是否合法”
  - `TickMetrics` 负责“这一拍到底花了多少时间”
- **Core 不直接依赖 Bukkit**：主线程判断能力通过 provider / bridge 注入
- **只在边界加 Guard**：不要给所有叶子函数乱塞判断，重点放在真实入口和高风险边界
- **异步 tick 与主线程影响分开统计**：主线程真正计算时间、主线程等待异步完成时间要分开看

---

## 一、线程上下文模型

建议建立统一的线程上下文概念：

- `MAIN_THREAD`
- `ASYNC_TICK_WORKER`
- `UNKNOWN_BACKGROUND`

### 建议新增组件

#### 1. `ThreadGuard`
职责：
- 判断当前线程身份
- 提供 assert / require 风格的运行时校验

建议 API：

```kotlin
object ThreadGuard {
    fun isMainThread(): Boolean
    fun isAsyncTickThread(): Boolean
    fun currentContext(): ThreadContext

    fun ensureMainThread(operation: String)
    fun ensureOffMainThread(operation: String)
    fun ensureAsyncTickThread(operation: String)
}

enum class ThreadContext {
    MAIN_THREAD,
    ASYNC_TICK_WORKER,
    UNKNOWN_BACKGROUND
}
```

#### 2. `ThreadGuardPlatformBridge`
职责：
- 让 Core 能问“现在是不是平台主线程”，但不直接依赖 Bukkit

建议 API：

```kotlin
interface ThreadGuardPlatformBridge {
    fun isMainThread(): Boolean
}
```

Bukkit 层提供实现：
- `BukkitThreadGuardBridge` -> `Bukkit.isPrimaryThread()`

#### 3. `AsyncTickThreadMarker`
职责：
- 由 `TickManagerImpl` 在 worker 线程执行 `tick()` 时设置 ThreadLocal 标记

建议形式：

```kotlin
internal object AsyncTickThreadMarker {
    fun <T> runInAsyncTickContext(block: () -> T): T
    fun isAsyncTickThread(): Boolean
}
```

---

## 二、复杂计算不在主线程（需求 1）

### 现状判断
现在已有 `@Heavy` 注解，但它只是“提醒”，不是可执行约束。

### 目标
把 `@Heavy` 变成“高成本入口”的明确标记，并最终做到：

- 复杂计算入口在主线程调用时可被发现
- 这些入口的调用点逐步迁移到异步
- 最终可以从 warn 升级到 fail

### 建议做法

#### Phase A：保留 `@Heavy`，先加运行时 guard
对真正的高成本入口函数增加：

```kotlin
ThreadGuard.ensureOffMainThread("CoreWorld.getNearestRail")
```

适合加 guard 的是**根入口**，例如：
- `CoreWorld.getNearestRail(...)`
- 曲线最近点查询根入口
- RailCurveFactory 的大计算入口
- 未来的路径搜索 / 拓扑搜索 / 批量几何计算入口

不要在每个内部小函数都加，避免噪音过大。

#### Phase B：清理当前仍在主线程执行的 heavy 调用点
重点排查这些当前已经 `@OptIn(Heavy::class)` 但大概率仍在主线程运行的入口：
- `RailWand`
- `OperationWand`
- `TrainCommand`
- 其他编辑/查询流程

迁移方式：
- 异步线程做 heavy 计算
- 主线程只做：
  - Bukkit 世界读写
  - 对话框/消息/粒子/声音
  - 结果落地

#### Phase C：从 warn 升级为 fail
建议分阶段配置：
- 开发期：违规时 `logger.warning`
- 稳定后：违规时直接抛异常

也可以提供开关：
- `thread-guard-heavy-mode: off|warn|fail`

---

## 三、Bukkit API 只能在主线程（需求 2）

### 目标
确保所有 Bukkit 非线程安全 API 的关键边界只在主线程调用。

### 现实约束
无法自动穷举整个 Bukkit API，因此最现实的做法是：

- 识别 MineRails 自己的 **Bukkit 边界层**
- 在这些边界统一放 `ensureMainThread(...)`

### 建议重点守住的边界

#### 1. 实体 / 模型 / 渲染边界
- `TrainComponentExtension.sendTo(...)`
- `TrainComponentExtension.hideFrom(...)`
- `TrainComponentExtension.destroy()`
- `RailExtension.buildModel / updateModel / destroyModel`
- 所有 spawn / remove / teleport / metadata update 入口

#### 2. 世界交互边界
- `SeatExtension.tickAll()`
- `WorldManager.onTickStart / onTickEnd` 里与 Bukkit 交互的部分
- `RenderChunkManager` 中直接操作玩家可见性/区块票据的部分

#### 3. 命令 / UI / 物品边界
- dialog callback
- inventory / item meta 读写
- particle / sound / entity spawn

### 建议策略
对以上边界方法直接加：

```kotlin
ThreadGuard.ensureMainThread("TrainComponentExtension.sendTo")
```

### 推荐严格度
- **Bukkit 主线程违规**：建议直接 fail
- 因为这类错误不是“性能差一点”，而是可能状态错乱、并发污染、甚至 Paper 警告/崩溃

可选开关：
- `thread-guard-bukkit-mode: off|warn|fail`
- 默认建议：`fail`

---

## 四、异步 Train Tick 耗时观测（需求 3）

### 目标
允许查询“某个异步 tick 对象 / 某辆车 / 某个 train controller”本次和最近一段时间的耗时。

### 建议做法
在 `TickManagerImpl.preTick()` 的 worker 执行处采集每个 `TickingObject.tick(deltaT)` 的耗时。

### 建议新增接口

#### `TickProfiled`
给需要单独观测的 tick 对象一个稳定标识：

```kotlin
interface TickProfiled {
    val profileCategory: String
    val profileId: String
}
```

示例：
- `RealisticManualController`
  - `profileCategory = "train-controller"`
  - `profileId = train.id`
- 动画 runtime 也可挂自己的 category/id

### 建议新增统计对象

#### `TickMetricsRegistry`
负责保存最近 N tick 的统计结果。

建议记录：
- `lastDurationNanos`
- `avg20`
- `avg100`
- `max100`
- 可选 `p95`

建议形式：

```kotlin
data class AsyncTickMetricSnapshot(
    val category: String,
    val id: String,
    val lastNanos: Long,
    val avg20Nanos: Double,
    val avg100Nanos: Double,
    val max100Nanos: Long,
    val p95Nanos: Double?
)
```

### `TickManagerImpl` 侧需要记录两层数据

#### A. 单对象耗时
- 每个 `TickingObject` 的 `tick()` 花多久

#### B. 整个 async phase wall time
- 从开始提交到所有 worker 完成，这一拍总共卡了多久

因为：
- A 告诉你“谁慢”
- B 告诉你“这一拍会不会拖主线程等它”

### 查询方式建议
先不急着做命令系统，建议先做程序内接口：

```kotlin
interface TickMetricsView {
    fun snapshotAsyncObject(category: String, id: String): AsyncTickMetricSnapshot?
    fun snapshotTopAsyncObjects(limit: Int): List<AsyncTickMetricSnapshot>
}
```

后续可以再挂：
- `/mr perf train <id>`
- `/mr perf top`

---

## 五、主线程每个 Tick 花了多少时间在我们的计算上（需求 4）

### 目标
知道每个 server tick 中，我们自己的系统占用了多少主线程预算。

### 必须分两类看

#### 1. 主线程自己真的在算的时间
例如：
- `preTick()` 里对象快照和任务提交
- `postTick()` 里 `commit`
- afterCommitTasks
- postTickTasks
- `SeatExtension.tickAll()`
- `AbnormalTrainMarker.tick()`

#### 2. 主线程为了我们而等待异步完成的时间
例如：
- `waitForPreTick.await()`

虽然这段时间主线程没算东西，但它一样吃 tick budget。

### 建议统计口径
每个 server tick 记录：

```kotlin
data class MainThreadTickImpact(
    val tickIndex: Long,
    val computeNanos: Long,
    val blockedWaitAsyncNanos: Long,
    val totalImpactNanos: Long,
    val phaseBreakdown: Map<String, Long>
)
```

其中：
- `computeNanos` = 主线程自己算的时间
- `blockedWaitAsyncNanos` = 等异步完成的时间
- `totalImpactNanos` = 前两者相加

### 建议 phase breakdown

#### `TickManagerImpl` 内部 phase
- `preTick.snapshot_and_schedule`
- `postTick.wait_async`
- `postTick.commit`
- `postTick.after_commit_tasks`
- `postTick.post_tick_tasks`

#### `WorldManager.onTickEnd` 额外 phase
- `seat_extension`
- `world_post_tick:<worldName>`
- `abnormal_train_marker:<worldName>`
- 可选 `train_debug_log`

这样后面看日志/面板时能快速定位到底是：
- 异步太慢
- commit 太慢
- 座位同步太慢
- 某个世界的 tick 太慢

### 查询方式建议
程序内接口先行：

```kotlin
interface MainThreadTickMetricsView {
    fun latestMainThreadTickImpact(): MainThreadTickImpact?
    fun recentMainThreadTickImpacts(limit: Int): List<MainThreadTickImpact>
}
```

后续再决定是否做命令：
- `/mr perf tick latest`
- `/mr perf tick recent 20`

---

## 六、建议新增的类/API 草图

### Core
- `Core/src/main/java/work/art1st/mineRails/core/util/ThreadGuard.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/ThreadGuardPlatformBridge.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/AsyncTickThreadMarker.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/TickMetricsRegistry.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/TickMetricsSnapshot.kt`
- `Core/src/main/java/work/art1st/mineRails/core/api/util/tick/TickProfiled.kt`

### TickManager 扩展
建议在 `TickManager` 上补只读查询能力：

```kotlin
interface TickManager {
    ...
    fun latestAsyncPhaseNanos(): Long?
    fun latestMainThreadImpact(): MainThreadTickImpact?
    fun topAsyncObjects(limit: Int): List<AsyncTickMetricSnapshot>
}
```

如果你不想把观测接口直接塞进 `TickManager`，也可以改成：
- `tickManager.metricsView`

### Bukkit
- `BukkitThreadGuardBridge`
- 可选 `PerformanceCommand` / `PerfDebugCommand`

---

## 七、落地顺序

### Phase 0：只做基础设施，不启用强制
- 建 `ThreadGuard`
- 建 `ThreadGuardPlatformBridge`
- 建 `AsyncTickThreadMarker`
- 建 `TickMetricsRegistry`
- 在 `TickManagerImpl` 接入异步耗时与主线程 phase 耗时采集

### Phase 1：加观测，不改行为
- `TickManagerImpl` 产出每 tick metrics snapshot
- `WorldManager.onTickEnd` 记录 main-thread phase
- 先通过日志 / 内部 API 能查到数据

### Phase 2：加 Bukkit 主线程硬 guard
优先在这些边界加：
- `sendTo / hideFrom / destroy`
- model spawn/update/remove
- `SeatExtension.tickAll()`
- 粒子/声音/物品/meta/dialog callback

### Phase 3：迁移 heavy 主线程调用点
重点迁移：
- `RailWand`
- `OperationWand`
- `TrainCommand`
- 其他 `@OptIn(Heavy::class)` 主线程路径

### Phase 4：开启 Heavy guard
把高成本入口从 warn 升级为 fail。

---

## 八、风险与注意事项

### 1. 不要过早把 Heavy 直接改成 fail
当前仓库里已经有一些主线程 `@OptIn(Heavy::class)` 使用点，直接 fail 会导致功能立刻不可用。

### 2. 采集本身不能太重
metrics 结构必须用：
- ring buffer
- 固定容量
- 无锁或低锁争用方案

不要为了测性能再制造性能问题。

### 3. 主线程 phase 统计口径要统一
所有耗时都建议统一用 `System.nanoTime()`，不要混 `currentTimeMillis()`。

### 4. “MineRails 在主线程花了多久”要定义清楚
建议正式口径就是：
- 主线程计算时间
- 主线程等待异步时间
- 两者之和

否则看到数字时容易误解。

---

## 九、暂不处理的内容

这份方案先不碰：
- `ThreadGuard.kt` 的具体实现代码
- 所有 `@MainThread` / `@Heavy` 调用点的实际迁移
- 命令行展示界面
- 远程监控 / Prometheus / Web 面板

先把方案定清楚，再分阶段落地。

---

## 十、建议下一步

建议下一步先做 **Phase 0 + Phase 1**，也就是：

1. 建 `ThreadGuard` / bridge / async tick marker
2. 在 `TickManagerImpl` 里接入异步耗时与主线程 phase 统计
3. 暂时只暴露内部查询接口，不先做命令

这样你能先回答两个最关键的问题：
- 哪些异步 tick 对象最慢？
- 每个 server tick 我们到底在主线程占了多少预算？
