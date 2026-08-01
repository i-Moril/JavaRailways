# 并行列车物理与 Swept Occupancy 实现计划

## 1. 目标

在不引入主线程碰撞 Reconcile 的前提下，将列车物理改为可配置线程池并行计算，并满足：

1. 正常时由 Server TickStart 唤醒物理 Coordinator。
2. Server 主线程过载时，由定时器以约 50ms 周期唤醒 Coordinator。
3. Coordinator 使用相邻两个 physics tick 起点的实际时间间隔作为本次 `deltaT`。
4. Server TickEnd 必须捕获并等待当时正在运行的 Physics Ticket；不能用旧 epoch 继续提交。
5. Coordinator 每次只允许一个 in-flight epoch，主线程和定时器唤醒必须合并，不能排队。
6. 完整 physics epoch wall time 必须小于 50ms；10 秒内累计 3 次超限后进入 `STALLED`，不自动恢复，也不继续提交旧状态。
7. 使用 swept occupancy + Rail 弧长分桶检测碰撞，允许一个 physics tick 的检测延迟和少量近似。
8. CollisionEvent 每个 pair 只发送一次；默认结果为脱轨，监听器可请求下一 tick 联挂。
9. Worker 数量由 config 明确配置；无效配置直接启动失败，不静默修正。

## 2. 明确不做的内容

- 不做主线程碰撞仲裁或全局物理 Reconcile。
- 不计算精确碰撞时刻、冲击点或连续刚体碰撞。
- 不为了追赶落后的物理时间无限补算 step。
- 不在物理过载后自动降频、自动减少 Worker 或继续使用旧 epoch。
- 不使用对象地址、`hashCode()` 或 `identityHashCode()` 决定 CollisionEvent 所有者。
- 不让 CollisionEvent 监听器直接修改两列车结构或直接调用 `connect()`。
- 不把 TrainController 留在旧 TickManager 里作为兼容兜底路径。

## 3. 总体架构

### 3.1 分离通用 Tick 与列车物理

保留 `TickManagerImpl` 处理动画等通用 `TickingObject`。新增独立的列车物理运行时：

- `PhysicsCoordinator`：单线程状态机、Ticket、时间测量、Worker 调度、occupancy 发布和过载判断。
- `PhysicsWorkerPool`：固定大小线程池，按 batch 计算列车。
- `PhysicsEpoch`：不可变的已发布物理状态和 occupancy index。
- `PhysicsTicket`：代表一个明确的 epoch；完成时携带已发布 epoch 或明确异常。

`CoreTrain.setController()` 改为向列车物理运行时注册/注销 Controller，不再注册到通用 `TickManagerImpl`。

### 3.2 Epoch 数据流

```text
Published Epoch N
    ↓
Coordinator 启动 Epoch N+1，测量相邻 physics tick 起点间隔
    ↓
W 个 Worker 只读 Epoch N，分别写自己的局部结果
    ↓
Coordinator 收集结果，构建 OccupancyIndex N+1
    ↓
原子发布 Epoch N+1
    ↓
完成 PhysicsTicket(N+1)
```

Worker 不修改 Published Epoch，也不直接修改 Bukkit 对象。

### 3.3 Core 发布与 Bukkit 渲染分离

Physics Epoch 必须在后台完整发布，不能依赖主线程逐列车 `commit()` 才成为下一次物理计算的输入，否则定时器无法在主线程过载时独立推进。

因此需要把现有 `CommitBuffer` 语义拆成：

- physics published state：Coordinator 发布后立即可被下一 physics epoch 读取；
- Bukkit render snapshot：主线程 TickEnd 读取最新已完成 epoch，更新可见模型；
- Bukkit 渲染不参与 physics epoch 是否完成的判定。

`TrainEnd.location`、Controller speed、列车脱轨/碰撞状态必须进入不可变 physics state，不能继续暴露半提交字段。

## 4. Coordinator 唤醒与 Ticket 语义

### 4.1 唯一入口

主线程与定时器统一调用：

```kotlin
fun requestStep(source: PhysicsWakeSource, nowNanos: Long): PhysicsTicket
```

规则：

1. `STALLED`：立即返回失败 Ticket/抛出明确的 `PhysicsStalledException`，不返回旧 epoch 成功结果。
2. 已有 in-flight Ticket：返回同一个 Ticket，不创建新 epoch。
3. 当前不满足启动条件：返回当前已完成 epoch 对应的 completed Ticket。
4. 满足启动条件：创建一个新 epoch 和唯一 Ticket。
5. 不维护待执行 wake 队列；运行期间的重复 wake 只合并到当前 Ticket。

### 4.2 Server TickStart

`WorldManager.onTickStart()`：

1. 记录 main heartbeat。
2. 调用 `requestStep(MAIN_THREAD, System.nanoTime())`。
3. 不在 TickStart 阻塞主线程。

### 4.3 Server TickEnd

`WorldManager.onTickEnd()`：

1. 通过 Coordinator 的原子快照接口捕获 TickEnd 当时的 in-flight Ticket；若没有 in-flight，则捕获 latest completed Ticket。
2. 只捕获一次，不写等待状态变化的循环。
3. 等待该 Ticket 完成。
4. Ticket 成功后读取最新已发布 epoch，并执行 Bukkit render commit。
5. Ticket 失败时异常上抛/进入明确的插件错误状态；禁止继续提交旧 epoch。

这保证主线程过载时，如果定时器已经启动新物理 epoch，TickEnd 会等待该正在运行的 Ticket，而不是使用 TickStart 时较旧的 Ticket。

### 4.4 定时器接管

Coordinator 维护 `lastMainWakeNanos`。定时器只在 main heartbeat 超过阈值后请求 step：

```text
main wake 正常：主线程驱动
main wake 超时：timer fallback 驱动
```

建议初始常量：

- 主线程 heartbeat 超时：75ms；
- fallback timer 周期：50ms；
- 同时最多一个 in-flight epoch。

恢复正常后，下一次 Main TickStart 自然接管；不额外补算错过的 wake。

## 5. Physics deltaT

Coordinator 在真正创建新 epoch 时记录 `physicsTickStartNanos`。

```kotlin
deltaTMillis = (currentPhysicsTickStartNanos - previousPhysicsTickStartNanos) / 1_000_000.0
```

约束：

- deltaT 来自相邻两个 physics tick 起点的真实间隔，不使用 Server MSPT 代替。
- 正常状态下 physics tick 通常与 Server Tick 同步，deltaT 接近 50ms。
- fallback 状态下由定时器维持约 50ms 间隔。
- 第一 physics tick 使用明确的 50ms bootstrap delta。
- 不对 deltaT 静默 clamp，不无限追赶历史时间。
- 大 deltaT 的穿透风险由 swept occupancy 覆盖。

统一使用 `System.nanoTime()`，不用 `currentTimeMillis()` 计算物理间隔。

## 6. Worker 调度

### 6.1 线程池

新增必填配置：

```yaml
physics-worker-threads: 4
```

规则：

- 必须大于 0；否则插件启动失败。
- 使用配置值创建固定线程池，不静默钳制。
- 若配置值明显超过机器可用处理器数量，只输出明确 warning，不自动改值。
- Coordinator 使用独立单线程执行器。
- World unload/plugin disable 必须显式 shutdown timer、Coordinator 和 Worker pool。

### 6.2 Batch，而非一列车一个任务

每个 epoch：

1. 按稳定 train ID 获取活跃 Controller 快照。
2. 分成不超过 Worker 数量的 batch。
3. 每个 Worker 顺序处理一个 batch。
4. 每列车仍记录独立 metrics，但不提交独立 Runnable。

复杂度目标：

```text
Worker wall time ≈ total physics work / workerThreads + batch imbalance
```

## 7. Physics State

新增不可变快照，至少包含：

```kotlin
data class TrainPhysicsState(
    val trainId: String,
    val status: TrainPhysicsStatus,
    val speed: Double,
    val cars: List<CarPhysicsState>,
    val head: RailLocationSnapshot,
    val tail: RailLocationSnapshot,
    val sweptSpans: List<SweptRailSpan>,
    val pendingCollision: CollisionPair? = null
)

enum class TrainPhysicsStatus {
    ACTIVE,
    PENDING_COLLISION,
    DERAILED
}
```

要求：

- Worker 只写自己列车的 next state。
- 对其他列车的读取全部来自 Published Epoch。
- 未激活/无 Controller 的静止列车仍存在于 epoch 和 occupancy index 中，但复用上一 epoch 状态与 spans，不执行动力学。
- 道岔 active rail、信号和其它会影响物理路径的状态按 epoch 快照读取；本 epoch 中的外部修改从下一 epoch 生效。

## 8. Swept Occupancy

### 8.1 Span

```kotlin
data class SweptRailSpan(
    val trainId: String,
    val railId: UUID,
    val minArcAB: Double,
    val maxArcAB: Double
)
```

生成规则：

- 弧长统一转换到 Rail 的 AB 坐标。
- 区间使用闭区间，并在精确比较时加入 `Curve.TOLERANCE`。
- 在列车移动/跟车解算过程中顺便记录经过的 Rail 和弧长范围，避免事后重新做曲线搜索。
- span 覆盖上一 published state 到 next state 之间扫过的占用范围。
- 跨 Rail/道岔时生成多个 span。

### 8.2 固定分桶

默认桶长：

```text
32m
```

键：

```kotlin
data class OccupancyBucketKey(val railId: UUID, val bucketIndex: Int)
```

Coordinator 使用 Worker 局部输出构建新的只读索引：

```text
时间：平均 O(K × H)
空间：O(K × H)
```

其中 `K` 是 span 总数，`H` 是 span 平均覆盖桶数。

### 8.3 Worker 查询

Worker 在计算 next state 前，使用自己上一 epoch 的 swept spans 查询 Published OccupancyIndex：

1. 跳过同 train ID。
2. 对桶候选做精确区间相交检查。
3. 使用稳定 `CollisionPair(minTrainId, maxTrainId)` 去重。
4. 每列车每 epoch 最多接受一个碰撞。
5. 每列车最多检查 32 个候选；超过部分不继续扫描，并记录明确 metrics/warning，不做额外推测性处理。

静止列车不主动查询；活跃列车发现碰撞后会通过 pair claim 同时为双方写入 collision inbox。

## 9. Collision Claim 与事件

### 9.1 Pair Claim

每个 epoch 拥有独立的并发 Claim Set：

```kotlin
ConcurrentHashMap.newKeySet<CollisionPair>()
```

任何发现方都可以 claim；只有 `add(pair) == true` 的线程发送事件。不能要求 `self.id == pair.first` 才发送，因为另一列车可能静止或没有 Worker。

### 9.2 CollisionEvent

新增明确的异步 Opt-In 标记；事件在发现碰撞的 physics worker 上同步发送。

事件使用不可变 payload，并提供默认脱轨、显式请求联挂的 resolution：

```kotlin
enum class CollisionResolution { DERAIL, LINK }

class CollisionEvent(
    val epoch: Long,
    val worldId: String,
    val trainAId: String,
    val trainBId: String,
    val relativeSpeed: Double
) {
    fun requestLink()
}
```

规则：

- 默认 `DERAIL`。
- listener 只能调用 `requestLink()`，不能直接 connect。
- 各 listener 异常逐个记录并继续；不能让异常阻止 Ticket 完成。
- 事件状态和 collision inbox 必须先建立，再调用 listener。
- 不提供超时自动改判、重试或主线程回调兜底。

### 9.3 下一 epoch 应用 Resolution

检测 epoch：

```text
双方 next status = PENDING_COLLISION
创建 CollisionResolution/ConnectIntent
```

下一 epoch：

- `DERAIL`：双方进入 `DERAILED`。
- `LINK`：验证 pair 仍存在、两车未被其它 intent 消费、端部方向兼容，然后由一个特殊 pair task 执行 connect。
- LINK 验证失败：按默认结果进入 `DERAILED`，记录明确原因。

存在 ConnectIntent 的两列车从普通 batch 中移除，只交给一个 Worker，避免 `A.connect(B)` 与 `B.connect(A)` 并发执行。

## 10. 物理过载与 STALLED

### 10.1 测量范围

Physics MSPT 使用完整 epoch wall time：

```text
epoch 创建
→ Worker batch 完成
→ occupancy index 构建完成
→ epoch 发布/Ticket 完成
```

目标：严格小于 50ms。

### 10.2 判定

- 单次 `wallTime >= 50ms`：ERROR 日志并记录 overload 时间点。
- 保留最近 10 秒 overload 时间点。
- 10 秒窗口内累计 3 次：进入 `STALLED`。

### 10.3 STALLED 语义

- 不再创建新 epoch。
- 当前/后续 Ticket 不返回旧 epoch 的成功结果，而是明确失败。
- 不自动降低 Worker、不自动降频、不自动清空列车、不自动恢复。
- TickEnd 不允许在 Ticket 失败后继续提交旧物理状态。
- 后续是否提供人工恢复命令不属于第一阶段；第一阶段要求重启/明确重建运行时。

## 11. Metrics

扩展现有 Tick Metrics：

```kotlin
data class PhysicsEpochMetrics(
    val epoch: Long,
    val source: PhysicsWakeSource,
    val deltaMillis: Double,
    val wallNanos: Long,
    val workerMaxNanos: Long,
    val workerTotalNanos: Long,
    val coordinatorNanos: Long,
    val activeTrainCount: Int,
    val totalTrainCount: Int,
    val spanCount: Int,
    val bucketReferenceCount: Int,
    val candidateChecks: Int,
    val collisionClaims: Int,
    val overloadCountInWindow: Int
)
```

保留每列车耗时指标，但采集应在 batch 内完成，不为每列车创建 Future/Runnable。

## 12. 主要文件变更范围

### Core API

- `Core/src/main/java/work/art1st/javarailways/core/api/physics/World.kt`
  - 暴露只读 physics runtime/metrics 所需接口，或由 `CoreWorld` 内部持有。
- `Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt`
  - 与通用 TickManager 解耦；输出 next physics state。
- `Core/src/main/java/work/art1st/javarailways/core/api/event/CollisionEvent.kt`
  - 不可变 payload、resolution 和异步 Opt-In。
- `Core/src/main/java/work/art1st/javarailways/core/api/util/annotation/`
  - 新增明确的异步事件 Opt-In 标记。

### Core 实现

- 新增 `core/physics/tick/PhysicsCoordinator.kt`
- 新增 `core/physics/tick/PhysicsEpoch.kt`
- 新增 `core/physics/tick/PhysicsTicket.kt`
- 新增 `core/physics/rail/SweptOccupancyIndex.kt`
- 新增 `core/physics/train/TrainPhysicsState.kt`
- 修改 `CoreWorld.kt`：持有 physics runtime，提供全列车稳定快照。
- 修改 `CoreTrain.kt`：移除当前 swept 前视碰撞主路径，生成 next state/collision inbox/connect intent。
- 修改 `CoreCar.kt`、`CoreBogie.kt`、`TrainEnd.kt`：物理发布状态与 Bukkit render snapshot 分离。
- 修改 `EventManager.kt`：异步事件 listener 异常隔离；保持调用线程同步分发。

### Bukkit

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/JavaRailwaysConfig.kt`
  - 新增必填 `physics-worker-threads`。
- `BukkitPlugin/src/main/resources/config.yml`
  - 增加配置和注释。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt`
  - 创建 Coordinator、Worker pool、fallback timer；移除列车物理的单线程 executor。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt`
  - TickStart main wake；TickEnd 捕获并等待当前 Ticket；然后渲染最新已发布 epoch。
- World unload/plugin disable：严格关闭全部 physics executor/timer。

## 13. 分阶段实施

### Phase 1：Coordinator、Ticket、配置与状态机

- 增加 `physics-worker-threads` 配置并严格校验。
- 实现 Coordinator 单 in-flight 状态机。
- 实现 main/timer 双唤醒合并。
- 实现 TickEnd 捕获当前 Ticket 并等待。
- 实现相邻 physics tick 起点 deltaT。
- 此阶段可先用一个 batch 执行现有 Controller，验证 pacing，不立即启用多 Worker。

### Phase 2：Physics Epoch 与发布状态

- 引入不可变 `TrainPhysicsState`/`PhysicsEpoch`。
- 修复 `TrainEnd.location` 半提交问题。
- 将 physics publish 从 Bukkit 主线程 commit 中拆出。
- 保证 fallback timer 可在主线程过载时连续推进 epoch。

### Phase 3：Batch 并行

- Controller 从通用 TickManager 迁移到 PhysicsCoordinator。
- 按配置线程数划分 batch。
- 保留每列车 metrics，移除每列车 submit/latch。
- 验证所有 Worker 只读 Published Epoch。

### Phase 4：Swept Occupancy

- 移动过程中生成 canonical AB spans。
- Coordinator 构建 32m 分桶索引。
- Worker 查询上一 epoch occupancy。
- 加入候选上限、pair claim 和 collision inbox。
- 删除旧的并发实时 `peekTrains(2 * deltaS)` 碰撞主逻辑。

### Phase 5：异步 CollisionEvent 与联挂 Intent

- 新增异步 Opt-In 和 immutable payload。
- 默认 DERAIL，listener 可 `requestLink()`。
- 下一 epoch 处理 ConnectIntent。
- pair task 独占两列车，验证失败明确脱轨。

### Phase 6：过载 Stall 与完整指标

- 完整 epoch wall time 统计。
- 10 秒滑动窗口内 3 次超限进入 STALLED。
- Ticket 失败传播，禁止旧 epoch commit。
- 不实现自动恢复和自动调参。

## 14. 测试计划

### Coordinator 单元测试

1. Main 和 timer 同时 wake 只创建一个 epoch。
2. 运行期间重复 wake 返回同一个 Ticket，不排队。
3. TickEnd 捕获 timer 已启动的当前 Ticket并等待。
4. Ticket 完成后发布 epoch，再允许下一 epoch。
5. deltaT 等于相邻 physics tick 起点的 monotonic 间隔。
6. 第一 Tick deltaT 固定为 50ms。
7. Worker 数量严格使用配置值。
8. 无效 Worker 配置直接失败。
9. 单次超过 50ms 记录 overload。
10. 10 秒内第 3 次超限进入 STALLED。
11. STALLED 后不返回旧 epoch 成功 Ticket，不自动恢复。

### Occupancy 单元测试

1. 同 Rail 部分重叠。
2. 完全包含。
3. 端点相等和 tolerance。
4. AB/BA 方向归一化。
5. 跨 Rail 多 span。
6. 高速交换位置仍由 swept span 检出。
7. 静止列车在 index 中可被活跃列车检测。
8. 一个 pair 双方发现只 claim 一次。
9. 候选超过 32 后停止扫描并记录指标。

### Collision/Link 测试

1. 无 listener 默认 DERAIL。
2. listener `requestLink()` 生成下一 epoch intent。
3. CollisionEvent 每 pair 只发送一次。
4. listener 异常不会阻止 Ticket 完成，其余 listener 继续。
5. pair task 不与普通列车 batch 并行处理同一列车。
6. connect 验证失败明确转 DERAIL。
7. 静止列车无 Worker 时仍能收到 pending collision 状态。

### 集成与压力测试

- 50/100/200/500 列、每列 5 节。
- 静止列车与活跃列车混合。
- 正常 20 TPS main wake。
- 人工阻塞主线程，验证 timer fallback 和 TickEnd 等待当前 Ticket。
- 人工放慢 Worker，验证 overload window 和 STALLED。
- 验收要求：目标服务器硬件上 500 列目标负载的 physics epoch p95 < 50ms，且不存在重复 epoch、旧 epoch commit 或 wake backlog。

## 15. 实施约束

- 每阶段先补测试，再替换旧路径。
- 不静默吞掉配置、Ticket、listener 或联挂错误。
- 只在必须保护 Coordinator 完成性的 listener 边界捕获异常，并记录完整上下文。
- 不添加“出错后继续旧数据”“自动降线程”“自动降物理 TPS”等隐式兜底。
- 迁移完成后删除旧列车物理调度和旧碰撞路径，不长期保留双实现。
