# 当前进度总览

## 编译状态（截至 2026-04-24）

| 模块 | 状态 | 说明 |
|------|------|------|
| Core | ✅ 编译通过，测试通过 | 持久化已迁移到 ConfigLib，WorldParentContext 替代 Scope |
| ConfigLib | ✅ 编译通过 | Persistence 接口独立为独立模块 |
| Basic | ✅ 编译通过 | RailMap per-World 改造，ConfigLib 依赖，lombok 修复 |
| BukkitPlugin | ❌ 有已知语法错误 | RailExtension.kt:25 有重复声明语法错误（HEAD 已存在） |

## 已完成的功能性改动

### 持久化重构
- `Persistence.kt` / `PersistentBy` / `Loads` 从 `Core.api.util.persistence` 迁移到 `ConfigLib`
- `WorldParentContext` 接口替代 Koin `Scope` 作为 CoreWorld 构造参数
- `KoinWorldParentContext` 实现运行时 Koin 子 scope 绑定
- `CoreRail.SavedRailV1` 曲线序列化改为存储 `SavedPrimeCurveV1`，运行时通过 `Loads<Unit>` 重建
- `RailLocationWithFacing.SavedRailLocationWithFacingV1` 序列化格式更新
- `TrainPersistence` 中 `CoreBogie.load()` 依赖从 `Unit` 改为 `CoreWorld`

### RailMap per-World 改造
- `RailMap` 从 `object` 全局单例改为 per-World 实例，通过 `World.railMap` 扩展属性访问
- `RailDestination` 挂载到具体 `RailView`
- `PathFinder` 改为接收 `RailMap` 实例，显式传入起始 `RailView`
- `BasicPlugin` 作为 Basic 层 Plugin 实例注册到 `PluginRegistry`
- `RailWorldFactory` 在创建 World 时注册 `RailMap` 扩展数据

### 构建配置修复
- `Basic/build.gradle.kts` 添加 `ConfigLib` 依赖和 lombok 依赖
- `Basic` JVM target 统一为 17（与 Kotlin 保持一致）
- `BukkitPlugin/build.gradle` Kotlin JVM target 设为 21，processResources exclude 手动 plugin.yml 避免 duplicate
- `ConfigLib/build.gradle.kts` 添加 serialization plugin，JVM target 从 25 降为 17

## 已知问题

- `BukkitPlugin/RailExtension.kt:25-26`：语法错误（`private models Collection<>` 无效语法 + `var models` 重复），**HEAD 中已存在，非本次改动引入**，需修复后方可完整编译

## 下一步优先事项

见 `next_steps.md`