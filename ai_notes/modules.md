---
title: 模块职责索引
importance: high
L: 1
D: 1
---

# 模块职责索引

## 根工程
- 仓库名是 `MineRails`。
- `settings.gradle` 当前包含 `Core`、`Basic`、`Annotation`、`BukkitPlugin`、`BukkitPluginDev`、`ConfigLib`。
- 根 `build.gradle` 聚合依赖 `Core`、`Basic`、`BukkitPlugin`。

## 模块职责

### Core
- 平台无关核心层。
- 主要承载 API、物理对象、事件系统、渲染抽象、持久化抽象、插件扩展点。
- 适合放跨平台的领域模型和基础接口。

### Basic
- 基于 `Core` 的基础功能层。
- 当前可见内容集中在路径查找、控制器、标准轨道曲线。
- 适合放通用玩法算法，而不是 Bukkit 专属适配。

### BukkitPlugin
- Paper/Bukkit 运行时适配层。
- 插件入口是 `work.art1st.mineRails.bukkit.BukkitPlugin`。
- 主要承载世界接入、渲染、扩展桥接、NMS/平台对象等 Bukkit 相关内容。

### BukkitPluginDev
- 开发与测试用 Bukkit 插件工程。
- 入口与 `BukkitPlugin` 分离，便于实验或开发期运行。

### Annotation
- 注解处理与代码生成相关模块。
- 目前依赖 KSP、KotlinPoet、AutoService。

### ConfigLib
- 独立配置库。
- 与主插件模块分离维护，适合作为通用库看待。

## 依赖方向
- `Basic` 依赖 `Core`
- `BukkitPlugin` 依赖 `Core` 与 `Basic`
- 根工程聚合 `Core`、`Basic`、`BukkitPlugin`

## 工具链事实
- Kotlin 版本当前为 `2.3.0`
- `Core`、`Basic`、根工程默认使用 JVM 17
- `BukkitPlugin` 与 `BukkitPluginDev` 的 Java release 为 21，插件 API 版本为 `1.21.10`
- 项目使用 Koin 与 kotlinx serialization
