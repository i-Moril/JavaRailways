# MineRails 可修复性能点总表（更新版）

> 更新时间：2026-07-07  
> 说明：这份清单以当前代码和最近讨论为准，修正了早期聊天记录里一些不准确的结论。  
> 范围：以 **渲染 / 交互 / 车内碰撞 / 轨道查询** 为主，补充必要的物理热路径项。

---

## 一、当前结论（纠偏版）

### 1. Display 不是“零成本”，但也不是当前最大的 runtime 热点
- `Display` 实体本身不走我们自己的 `TickManager`，也没有 AI / 碰撞。
- 但它仍然是 **服务端实体**，仍然有：
  - 位置同步
  - 可见性追踪
  - `teleport()` 带来的更新成本
- 所以 Display 的主要问题不是“实体自己 tick 很重”，而是：
  - **数量多**
  - **现在是逐个 world-space teleport**
  - **一旦 build 过就会持续更新**

### 2. `PlatformModel` 仍然是当前最重的已知热点之一
- 当前平台碰撞是 `Chicken + Shulker` 网格。
- 以 S100 五节编组估算，Platform 层级的实体数和 TP 数都很高。
- 这部分比 Display 更接近真正的“服务端实体负担”。

### 3. 车厢显示树目前不是 Passenger 树驱动，而是“逻辑树 + 实体各自 TP”
- `CompositeModel` 有 root entity。
- 但子 `Display` 并没有挂在 root 上作为 passengers。
- 现在大多数可视部件依然是 **各自 teleport**。

### 4. “发包 + 关闭反作弊”不足以直接替代 Platform
- 仅关闭第三方反作弊，不等于关闭服务端物理权威。
- 如果不保留 Platform，就必须自己接管 **车内玩家运动的服务端裁决**。
- 真正可行的替代路线是：
  - **packet 视觉**
  - + **反作弊白名单 / 区域旁路**
  - + **服务端车内局部坐标运动系统**

---

## 二、所有可修复的部分（按优先级与争议程度分组）

---

## A. 低争议、应优先做 / 已经在做的项

### A1. `RailSegment.searchRailLocation()` 按业务容差提前退出
**文件**
- `Core/src/main/java/work/art1st/mineRails/core/physics/rail/RailSegment.kt`

**问题**
- 二分搜索基本固定跑满 50 次。
- 过去只有误差接近 `1e-12` 才提前退出，远超业务需要。

**修复方向**
- 使用调用方传入的 `tolerance` 作为提前退出条件。

**状态**
- 已在 `feature/optimize` worktree 中完成。

**收益**
- 直接降低列车跟车解算热路径的无意义迭代。

---

### A2. 给 rail 查询加空间索引
**文件**
- `Core/src/main/java/work/art1st/mineRails/core/physics/CoreWorld.kt`
- `Core/src/main/java/work/art1st/mineRails/core/physics/rail/RailSpatialIndex.kt`（新增）

**问题**
- `CoreWorld.getNearestRail()` 原先是全量 rail 扫描，再对每条 curve 做重计算。
- `RailWand.findLookedAtRail()` 也存在大范围候选问题。

**修复方向**
- 建粗粒度 3D rail 空间索引（cell / chunk 风格）。
- 查询时先拿候选 rail，再做精确曲线计算。

**状态**
- 已在 `feature/optimize` worktree 中完成第一版。

**收益**
- 降低 `getNearestRail()`、ray pick 等查询的平均成本。

---

### A3. `RailRenderMetaStore` 做 world 级内存缓存
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/persistence/RailRenderMetaStore.kt`

**问题**
- 多个工具/命令路径会重复 `load(world)`。
- 过去每次都读磁盘 + 反序列化 JSON。

**修复方向**
- 按 `world.uid` 做缓存。
- 用 `lastModified + file.length` 做缓存失效判断。
- 写入时同步刷新缓存。

**状态**
- 已在 `feature/optimize` worktree 中完成。

**收益**
- 减少编辑/命令路径的 IO 和 JSON 解析开销。

---

### A4. `RailWand.findLookedAtRail()` 先筛候选再精算
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt`

**问题**
- 过去看向选轨会先拿全部 persisted rail，再逐条扫曲线采样点。

**修复方向**
- 先走 `CoreWorld.getRailsAlongRay(...)` 拿候选。
- 再对候选做原有精确计算。

**状态**
- 已在 `feature/optimize` worktree 中完成。

**收益**
- 删除/编辑模式的点选体验会更稳定。

---

### A5. `PlatformModel` 近场启用
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/PlatformModel.kt`

**问题**
- 平台碰撞实体原本是常驻生成、常驻更新。

**修复方向**
- 仅在玩家接近车厢时启用。
- 离开后 TTL 保活一小段时间，再销毁。

**状态**
- 已在 `feature/optimize` worktree 中完成第一版。

**备注**
- 这项其实更接近早先讨论里的 P1，但已经做了，保留即可。

---

## B. 低争议、已完成（feature/optimize）的项

> 说明：本组 5 项已经实现；其中 B5 目前是第一版 audience build/unload，暂未做 freeze。

### B1. `SeatModel` 重复 TP
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/SeatModel.kt`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/EntityModel.kt`

**问题**
- `SeatModel` 会走基类 `EntityModel.updateState()` 的 teleport。
- 然后 `SeatModel.updateState()` 自己又 teleport hitbox 一次，再 teleport carrier 一次。
- 相当于 seat 每 tick 有重复 TP。

**修复方向**
- 让 `SeatModel` 跳过不必要的基类 TP，避免 hitbox 双重更新。

**收益**
- 这是非常明确、几乎无争议的常数级优化。

---

### B2. 空座不保留常驻 carrier
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/SeatModel.kt`
- 或旧 `SeatExtension.kt` 路径（若仍在使用）

**问题**
- 空座位现在也维持 carrier 实体。
- 如果大多数座位是空的，这些实体和更新都属于浪费。

**修复方向**
- 空座只保留 hitbox。
- 玩家坐下时懒创建 carrier；下车后回收 carrier。

**收益**
- 对长编组/高座位数车厢很值。

---

### B3. `CompositeModel.updateState()` 已在主线程时不应再排 after-commit task
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/CompositeModel.kt`

**问题**
- 当前 root entity 更新会通过 `scheduleMainThreadAfterCommitTask(...)` 再包一层。
- 但多数调用点本来已经处在主线程 commit 阶段。

**修复方向**
- 主线程直写；只有异步场景才走调度。

**收益**
- 减少大量小任务/`FutureTask` 开销。

---

### B4. `RenderChunkManager.render()` 不要每 tick 全量扫
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/chunk/RenderChunkManager.kt`

**问题**
- 当前每 tick：
  - 遍历所有玩家
  - 扫周围 chunk
  - 收集 renderables
  - 再 `sendTo(player)`
- 这是典型的全量可见性重扫。

**修复方向**
- 改成增量 audience 管理：
  - 玩家跨 chunk 时更新
  - renderable 跨 chunk 时更新
  - 切世界 / 视距变化时更新
- 不再每 tick 全量求可见集。

**收益**
- 玩家数、列车数上来后收益很大。

---

### B5. 模型按 audience 构建与卸载，而不是 build 后永久更新
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/train/CarExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/train/TrainComponentExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/chunk/ModelAudienceTracker.kt`

**问题**
- 车模一旦 build，就会一直保留并持续更新。
- 即使附近没人看，也继续花更新成本。

**修复方向**
- 真正接入 audience / TTL：
  - 有 viewer：build + update
  - 暂时没人：freeze
  - 长时间没人：destroy/unload

**收益**
- 这是渲染生命周期上的核心优化之一。

---

## C. 中等争议，但大概率值得做的结构优化

### C1. Display 树先做 Passenger 化原型
**文件（主要涉及）**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/model/CompositeModel.kt`
- `.../DisplayModel.kt`
- `.../LodDisplayModel.kt`
- `.../ModelTreeBuilder.kt`

**问题**
- 当前 display 树是“逻辑树 + 各自 TP”。
- root entity 没真正承担“带动整棵树”的职责。

**修复方向**
- 先做 PoC：
  - root teleport
  - child 挂 passenger
  - child 只更新 local transformation
- 重点验证：
  - 是否真的能只动 root
  - passenger + display interpolation 是否稳定
  - show/hide / tracking 是否正常

**收益**
- 如果可行，能显著减少 Display 的 world-space TP 数量。

**注意**
- 这是优先于“立刻全量发包”的试验项。

---

### C2. Rail 视觉件也应该 audience 化 / packet 化候选化
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/rail/RailExtension.kt`
- `.../logic/RailVisualBootstrap.kt`

**问题**
- 当前轨道视觉构建后也比较偏常驻。
- Rail 量上来后，视觉实体数也会变成压力。

**修复方向**
- 短期：跟随 audience 生命周期。
- 中期：纳入 packet-only 视觉后端候选。

---

### C3. `RenderChunkManager` 距离判断可以统一换成 squared distance
**文件**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/chunk/RenderChunkManager.kt`

**问题**
- 当前很多位置直接用 `distance()`，会做开方。

**修复方向**
- 改成平方距离比较。

**收益**
- 单次收益小，但在高频路径中是值得的。

---

## D. 大改造项（正确方向，但不适合直接硬切）

### D1. Packet-only 纯视觉后端（混合渲染架构）
**结论**
- 我认为方向是对的，但不应该一口气全量改。
- 更合理的是：
  - **纯视觉**：逐步 packet 化
  - **交互 / 乘坐 / 极少量碰撞代理**：保留服务端实体

**建议顺序**
1. 先验证 Display Passenger 树是否足够
2. 再抽象“渲染后端”
3. 再逐步把 body / bogie / rail visuals 切成 packet renderer

---

### D2. 用 `TrainInteriorMode` 替代 Platform 网格
**这项是长期最强方案，但不是“小修小补”**。

**真实可行的定义**
- packet 提供车内视觉参考
- 反作弊做区域白名单/旁路
- 服务端自己维护玩家在车厢局部坐标系中的位置
- 不再依赖 `Chicken + Shulker` 地板网格

**注意**
- 不是“发包 + 关反作弊插件”就结束。
- 真正需要的是：
  - **服务端接管车内运动裁决**
  - 玩家 local position / local bounds / 上下车 / 车门等规则都要自己管

**什么时候值得做**
- 车内自由走动是核心体验
- Platform 实体数已经无法接受
- 愿意投入一套新的车内运动系统

---

## E. 低优先级 / 现在不该先动的项

### E1. `TrainDynamics.step()`
**原因**
- 它不是当前最大的瓶颈。
- 计算量线性且常数不大。

### E2. 大规模重写 `TickManagerImpl`
**原因**
- 它确实有小分配和调度开销。
- 但和 Platform / audience / render scanning 相比优先级更低。

---

## 三、推荐执行顺序（更新版）

### 已完成（feature/optimize worktree）
1. `RailSegment.searchRailLocation()` 容差提前退出
2. rail 空间索引
3. `RailWand.findLookedAtRail()` 候选 rail 筛选
4. `RailRenderMetaStore` 内存缓存
5. `PlatformModel` 近场启用
6. `SeatModel` 去重 TP
7. 空座懒创建 carrier
8. `CompositeModel` 主线程直写 root，不再每 tick 包 task
9. 去掉 `RenderChunkManager.render()` 的每 tick 全量扫描，改成增量可见性更新
10. `TrainComponentExtension` 的 audience build/unload 第一版

### 下一批最值得做
1. Display Passenger 树 PoC
2. rail visuals 的 audience 生命周期优化
3. `RenderChunkManager` / 车模 audience 的进一步收紧（例如 freeze、静态 rail 隐藏）
4. packet 纯视觉后端抽象
5. `TrainInteriorMode` 替代 Platform（如果继续支持车内自由走动）

---

## 四、一句话结论

> 当前最值得继续修的不是 `TrainDynamics`，而是 **Platform / Seat / audience 生命周期 / 可见性扫描 / Display 更新方式**。  
> 其中 P2（查询与二分）已经可以先收掉；中期最关键的是 **把渲染与交互成本从“按整车实体数付费”逐步转成“按可见对象 / 按车内玩家数付费”**。
