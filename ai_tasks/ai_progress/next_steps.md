# 下一步计划

## 优先级 1：解除编译阻塞 ✅ 完成

### 修复 RailExtension.kt 语法错误
- **问题**：`RailExtension.kt:25-26` 有重复声明语法错误，`private models Collection<>` 是无效语法
- **参考**：`BukkitRenderable`、`BukkitTrainRenderer` 等同类实现
- **预期行为**：修复后 BukkitPlugin 完整编译通过
- **状态**：✅ 已修复 - RailExtension 正确实现 RenderableExtension 接口，修复了 initialize 方法签名和 model 引用问题

### 修复 AnimationConfig/AnimationConfigV1
- **状态**：✅ 已完成 - AnimationConfig 添加接口定义，AnimationConfigV1 实现完整的数据类结构

## 优先级 2：完成 RailMap per-World 改造（收尾） ✅

### 完善 PathFinder 使用链
- `PathFinder` 已重构为接收 `RailMap` + 起始 `RailView`，需确认调用方（ATO 控制器等）已适配
- 如有遗漏，补齐从 `World.railMap` 构造 `PathFinder` 的代码
- **状态**：✅ 已审查 - PathFinder 实现完整，目前无调用方，属于待实现功能

### RailMap 持久化（如需要）
- 当前 `RailMap` 是纯内存结构，未实现持久化
- 如有需求，可参考 `CoreWorld` 的 `SavedWorldV1` 模式，将目的地序列化纳入世界快照

## 优先级 3：BukkitPlugin 渲染系统 ✅ 完成

### RailExtension.buildModel() 实现
- **状态**：✅ 已完成 - 沿曲线生成多个 DisplayModel 形成铁轨外观
- 使用 Rail.curve API 获取几何形状（符合 requirement.md）
- ModelAudienceTracker 改为 public 以供外部使用

### 动画系统修复
- **状态**：✅ 已完成 - 修复动画变换传递问题
- `Animation.ActiveAnimation` 新增 `currentTransform` 属性
- `AbstractModel.updateState()` 现在正确传递动画变换给 `ModelState`

### 车辆渲染扩展
- `TrainComponentExtension` 使用配置化模型加载
- `TrainExtension.applyAnimation()` 支持批量应用动画
- `CarExtension.commit()` 更新 RenderChunkManager

### 远离原点时的模型可见性
- **问题**：Display 实体在 `(0,0,0)` 生成、仅靠 Transformation translation 偏移时，远离世界原点的列车不会被发送给附近玩家
- **状态**：✅ 已修复
- `CarExtension` / `BogieExtension`：在真实世界坐标 spawn 并 teleport，transform translation 归零
- `RenderChunk`：负坐标分块计算改用 `floor()`
- `RenderChunkManager`：首次注册 renderable 时也会 `addChunk`
- `TrainCommand`：创建后立刻触发 `renderChunkManager.render()`，并输出调试坐标

## 优先级 4：游戏内测试 ✅ 完成

### 命令系统实现
- **状态**：✅ 已完成 - 命令框架已建立
- `MineRailsCommand` - 主命令入口
- `RailCommand` - `/mr rail create` 轨道创建
- `TrainCommand` - `/mr train create|info|list` 列车命令
- 注册 `/minerails` 和 `/mr` 别名

### 游戏内测试的 Checklist
- [x] 轨道创建和删除 - `/mr rail create` 已实现
- [x] 列车模型构建 - CarExtension/BogieExtension 从 car_model.yml 加载配置，ItemDisplay 渲染
- [x] 座位系统 - SeatExtension 使用 Interaction 实体，玩家右键座位坐下
- [x] 游戏内列车创建 - `/mr train create` 已实现，支持指定车厢数量和长度
- [x] 手动驾驶算法 - ManualController 实现，`/mr train start|stop|throttle|reverse|info`
- [x] 轨道的可视化。指定循环节每个元素的方块/item和偏移，以及循环节的长度（rail_model.yml）。
- [x] 多节车厢定位 - `CoreCar` 转向架配对算法修复，含回归测试
- [x] 远离原点渲染 - Display 实体真实坐标 + RenderChunk 修复

### 待实现功能
- **控制器系统**：ATO 控制器、路径跟随逻辑
- **信号系统**：道岔、信号机交互
- **动画系统**：`Animation.kt` 仍有 TODO，需实现完整动画播放
- **测试覆盖**：当前 Core 有单元测试，可扩展到 Basic 和 BukkitPlugin

## 执行约定

- 大范围/破坏性改动前，先征询人类开发者意见
- 每阶段完成及时 commit，commit message 遵循 `[模块]` 前缀
- 当前分支为 `main`，所有改动可直接在 main 上进行
