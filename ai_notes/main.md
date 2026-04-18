---
title: 项目笔记入口
importance: high
L: 1
D: 1
---

# 项目笔记入口

## 项目定位
- `MineRails` 是 Minecraft Paper/Bukkit 插件项目。
- 仓库是 Gradle 多模块工程，核心域逻辑与 Bukkit 适配分层明显。
- 本目录只记录稳定事实、索引和协作规则，不记录容易过时的具体实现细节。

## 先看哪里
- 协作准则与笔记约定：`AI_README.md`
- 模块职责索引：`ai_notes/modules.md`
- Bukkit 层目录导航：`ai_notes/bukkit_layer.md`
- 笔记更新规则：`ai_notes/update_rules.md`

## 模块分层
- `Core`：平台无关的核心 API、物理、事件、渲染抽象、插件扩展点。
- `Basic`：建立在 `Core` 之上的基础功能，如路径、控制器、标准曲线。
- `BukkitPlugin`：Paper/Bukkit 运行时适配，负责插件入口、世界接入、渲染与扩展桥接。
- `BukkitPluginDev`：开发/测试用 Bukkit 插件入口。
- `Annotation`：注解处理与代码生成基础设施。
- `ConfigLib`：独立配置相关库。

## 快速定位
- 改领域模型、事件、抽象接口：先看 `Core`
- 改路径查找、控制器、基础玩法：先看 `Basic`
- 改插件生命周期、世界接入、渲染、Bukkit 扩展：先看 `BukkitPlugin`

## 协作约定摘要
- 注释使用中文。
- 大范围改动、破坏性改动、存在多种可选方案时，需要先征询人类开发者意见。
- 任务进度写到 `ai_tasks/ai_progress/`，长期复用知识写到 `ai_notes/`。
- 笔记禁止包含个人信息、运行环境信息和具体代码逻辑。
