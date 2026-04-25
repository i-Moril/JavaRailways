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

## 优先级 3：BukkitPlugin 渲染系统 ✅ 进行中

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
- **控制器系统**：ATO 控制器、路径跟随逻辑
- **信号系统**：道岔、信号机交互
- **测试覆盖**：当前 Core 有单元测试，可扩展到 Basic 和 BukkitPlugin

## 执行约定

- 大范围/破坏性改动前，先征询人类开发者意见
- 每阶段完成及时 commit，commit message 遵循 `[模块]` 前缀
- 当前分支为 `ai-dev`，所有改动可直接在 ai-dev 上进行