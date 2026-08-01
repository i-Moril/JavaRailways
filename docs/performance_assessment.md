# JavaRailways 性能评估（2026-07-07）

> 评估方式：基于当前代码的静态审查，没有跑真实 profiler / 火焰图。
> 优先级按 **调用频率 × 单次开销 × 数据规模扩张风险** 排序。

## 结论摘要

当前代码里，**真正最重的不是列车动力学公式本身，而是 Bukkit 侧的渲染/碰撞实体更新**。

### 最高优先级热点

1. **车厢平台碰撞模型 `PlatformModel`**  
   - 开销等级：**极高**  
   - 优化空间：**极大**
2. **车厢/部件模型每 tick 常驻更新 `CarExtension` / `CompositeModel` / `DisplayModel`**  
   - 开销等级：**极高**  
   - 优化空间：**很大**
3. **每 tick 的玩家视距扫描 `RenderChunkManager.render()`**  
   - 开销等级：**高**  
   - 优化空间：**很大**
4. **列车跟车解算中的固定 50 次二分 `RailSegments.searchRailLocation()`**  
   - 开销等级：**高**  
   - 优化空间：**中到大**
5. **编辑/命令路径里的全量轨道扫描 + 反复磁盘读取**  
   - 开销等级：**中到高**  
   - 优化空间：**很大**

---

## 1. 平台碰撞模型：当前最重的运行时热点

涉及文件：
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/PlatformModel.kt`
- `models/s100/parts/s100/floor_platform.yml`
- `models/s100/manifest.yml`
- `models/s100/cars/s100/*.yml`

### 为什么它重

`PlatformModel` 会为每个平台单元生成：
- 1 个 `Chicken`
- 1 个 `Shulker`

也就是 **每个 cell = 2 个实体**。

而 `models/s100/parts/s100/floor_platform.yml` 配的是：
- `rows: 3`
- `columns: 18`

即：
- **54 个 cell / 车厢**
- **108 个实体 / 车厢**

S100 默认编组在 `models/s100/manifest.yml` 里是 **5 节车**，而各车定义都引用了 `floor_platform`。这意味着仅平台碰撞层就大约有：

- **5 × 108 = 540 个随车移动实体**

并且 `PlatformModel.updateState()` 里会 **每 tick 逐个 teleport** 这些实体。

### 评估

这是当前代码里**最容易把主线程拖慢**的一块，尤其在：
- 多列车同屏
- 长编组
- 多玩家围观
- 车速较高、持续更新时

### 是否还有大幅优化空间

**有，而且很大。**

### 优化建议

1. **先砍平台实体数量**  
   不要 1m × 1m 这么密；优先评估能否改成更粗的碰撞网格。
2. **做“近处才启用”的碰撞平台**  
   没有玩家靠近时，不必维持整套平台实体。
3. **视觉模型和碰撞模型分离**  
   视觉可常驻，碰撞可按玩家附近/乘客附近动态启停。
4. **给平台做 LOD**  
   远处列车直接不维护完整平台碰撞。

---

## 2. 车厢模型一旦建出来，就会每 tick 常驻更新

涉及文件：
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/CarExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/CompositeModel.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/DisplayModel.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/ModelTreeBuilder.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainComponentExtension.kt`

### 为什么它重

`CarExtension.commit()` 里只要模型已经 build 过，就会持续 `updateModel()`。

而 `updateModel()` 会递归更新整棵模型树：
- 车体
- 转向架
- 各种 `ItemDisplay`
- `SeatModel`
- `InteractionModel`
- `PlatformModel`

`DisplayModel.updateState()` 里每个显示实体基本都会 `teleport()`；`CompositeModel.updateState()` 还会更新 root entity。

也就是说：
- **模型一旦被某个玩家看见过并建模成功**
- 即使之后附近已经没有玩家
- **模型仍会继续每 tick 更新**

这会让“曾经进入视野”的列车长期保留运行时成本。

### 评估

这是第二大的运行时热点，而且和车模复杂度直接正相关。复杂车包、长编组、多列车时会非常明显。

### 是否还有大幅优化空间

**有，很大。**

### 优化建议

1. **把“是否建模/是否更新”改成按观众驱动**  
   附近没人看时：
   - 销毁模型，或
   - 至少暂停 `updateModel()`
2. **复用现有但未真正用起来的观众 TTL 思路**  
   当前已有 `ModelAudienceTracker`，但没有形成完整生命周期管理。
3. **只更新真正可见的模型**  
   靠 `RenderChunkManager` 或 chunk/玩家移动事件维护活跃集，而不是所有已 build 模型都更新。
4. **降低不必要的实体层级**  
   某些纯静态小部件可考虑合并，减少 `Display` 实体数量。

---

## 3. `RenderChunkManager.render()` 是典型的“每 tick 全量扫一遍”

涉及文件：
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/chunk/RenderChunkManager.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/chunk/RenderChunkAreaIterator.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/chunk/ViewDistanceManager.kt`

### 为什么它重

当前逻辑是：
- 每 tick
- 遍历 `world.players`
- 对每个玩家按视距找周围 chunk
- 再枚举 chunk 中的所有 renderable
- 再做一次精确距离判断
- 再 `sendTo(player)`

默认视距来自 `player.viewDistance * 16`。如果玩家视距是 10 chunk，半径大约 160 格，圆形覆盖 chunk 数大约就是 **300+** 量级/玩家/tick。

而且这里还有几个问题：
- `distance()` 用的是开方距离，不是平方距离
- 每 tick 都会构造 `LinkedHashSet`
- 对已经 build 好的车厢模型，`sendTo()` 基本已经没有新增价值
- 轨道段在 `RailVisualBootstrap` 里已经 `ensureBuilt()`，render 阶段再反复 `sendTo()` 的收益更低

### 评估

这是很典型的**可扩张性瓶颈**：
- 玩家数上来会线性放大
- 轨道和列车数量上来也会继续放大

### 是否还有大幅优化空间

**有，很大。**

### 优化建议

1. **不要每 tick 全量扫玩家视野**  
   改成：
   - 玩家跨 chunk 时更新
   - renderable 跨 chunk 时更新
   - 玩家切换世界/视距时更新
2. **维护 audience 集合**  
   谁已经看过、谁正在看，增量更新。
3. **把 `distance()` 改成平方距离比较**
4. **把“懒建模”和“每 tick 扫描可见性”拆开**  
   建模是生命周期问题，不该靠每 tick 重扫解决。

---

## 4. 跟车解算的二分搜索在热路径上，而且固定跑满 50 次

涉及文件：
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailSegment.kt`
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailLocationWithFacing.kt`

### 为什么它重

`CoreCar.updateByPrevCar()` 会调用 `railSegments.searchRailLocation(...)` 来求当前车厢相对前车的位置。

而 `RailSegments.searchArcRange()` 当前实现是：
- 每次搜索固定做 **50 次二分**
- 只有误差小到 `1e-12` 才提前返回
- **没有使用调用方传入的容差提前停止**

这意味着：
- 每节非头车厢
- 每 tick
- 都会做一轮高频曲线求值 + 向量计算 + 二分搜索

对长编组列车，这个成本会稳定累积。

### 评估

这是核心物理热路径里最值得盯的一块。它不像渲染那样夸张，但在列车数量和编组长度变大后会明显放大。

### 是否还有大幅优化空间

**有，中到大。**

### 优化建议

1. **把搜索容差真正传进二分循环**  
   达到 `Curve.TOLERANCE` 或业务容差就提前停，不要默认跑满 50 次。
2. **利用上一 tick 的结果做 warm start**  
   列车相邻 tick 位移很小，完全可以用上次弧长作为初值。
3. **做同 rail / 小位移 fast path**  
   很多时候不用完整跨段搜索。
4. **顺手清掉热路径小分配**  
   例如 `orderedCars.drop(1)` 这类每 tick 小对象创建，收益不如算法级优化大，但可以一起收。

---

## 5. 编辑/命令路径里存在“全量扫描 + 反复读文件”

涉及文件：
- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/RailWand.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/persistence/RailRenderMetaStore.kt`

### 为什么它重

#### 5.1 `CoreWorld.getNearestRail()`

当前做法是：
- 遍历所有 rail
- 对每条 rail 求 `curve.getNearestPoint(position)`
- `getNearestPoint()` 又会遍历整条曲线的采样流

这是典型的 **全量几何扫描**。

#### 5.2 `RailWand.findLookedAtRail()`

更重：
- 先 `RailRenderMetaStore.load(world)`
- 也就是 **每次调用都读磁盘 + 反序列化 JSON**
- 再对每条 rail 的 `curve.stream()` 做全量射线近似匹配

对编辑工具来说，这会直接体现在：
- 点选迟滞
- 删除/编辑模式卡顿
- 轨道数上来后交互越来越肉

### 是否还有大幅优化空间

**有，很大。**

### 优化建议

1. **给 `RailRenderMetaStore` 做 world 级内存缓存**  
   不要每次工具操作都重新读 JSON。
2. **给 rail 做空间索引**  
   例如按 chunk / AABB / 采样包围盒索引，再做精确求交。
3. **把 look-at 检测先缩小候选集**  
   先按 chunk / 包围盒筛，再对少量曲线做精算。

---

## 6. `TickManagerImpl` 有额外调度/分配开销，但不是第一优先级

涉及文件：
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt`

### 现状

每次 `preTick()` 都会：
- 把 `tickingObjects` / `tickingSynchronizableObjects` 拷贝成 `toList()`
- 为每个对象 `scheduler.submit { ... }`
- 建 `CountDownLatch`
- 用并发集合收集错误和成功对象

### 评估

这部分确实有额外成本，但它的瓶颈前提是：
- ticking object 数量很多，或者
- 单 tick 内活跃动画/控制器很多

按当前代码看，它更像**中等级别的框架开销**，不是眼下最值得优先处理的第一热点。

### 是否还有大幅优化空间

**有，但优先级低于渲染/碰撞/几何搜索。**

### 优化建议

1. 只在注册表变更时重建快照列表  
2. 用批处理而不是“一对象一任务”  
3. 复用 tick 内部临时容器

---

## 哪些重逻辑目前不太值得优先动

### 1. `TrainDynamics.step()`
涉及文件：
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainDynamics.kt`

评估：
- 只是对车厢做几次 `sumOf`
- 复杂度是线性的
- 算法简单，常数不大

**结论：不是当前大头，优化空间有限。**

### 2. `StandardRailCurve.generatePoints()`
涉及文件：
- `Basic/src/main/java/work/art1st/javarailways/basic/curve/StandardRailCurve.kt`

评估：
- 代码里已经明确把旧的 O(n²) 方式改成了单次推进积分 O(n)
- 这块主要发生在建轨/编辑时，不是持续 tick 热点

**结论：目前已经比较合理，不是优先级最高。**

### 3. `RailCommand.connectNearbyEndpoints()`
涉及文件：
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/RailCommand.kt`

评估：
- 已经从 O(n²) 轨道两两比较，改成了 bucket 近邻搜索
- 虽然仍是命令路径重逻辑，但方向是对的

**结论：当前实现已经比常见的朴素写法好很多，暂时不用优先重构。**

---

## 建议的优化优先级

### P1（最建议先做）
1. **平台碰撞 `PlatformModel` 减实体 / 近场启用**
2. **车厢模型做 audience 驱动的 build / unload / pause update**
3. **移除 `RenderChunkManager.render()` 的每 tick 全量扫描模式**

### P2
4. **优化 `RailSegments.searchRailLocation()`：基于容差提前退出**
5. **给轨道查询和 RailWand 交互做空间索引 + 内存缓存**

### P3
6. **整理 tick 框架的小分配和调度常数**
7. **再考虑物理热路径上的对象分配清理**

---

## 一句话结论

如果只看“还有没有大幅优化空间”，答案是：**有，且主要集中在 Bukkit 实体层，而不是动力学公式层。**

最该优先处理的是：
- **平台碰撞实体数量过多**
- **车模 build 后永久每 tick 更新**
- **每 tick 扫描所有玩家视野**

这三块处理完，整体性能体感大概率会比继续抠 `TrainDynamics` 或小型数学函数明显得多。
