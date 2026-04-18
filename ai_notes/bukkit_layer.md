---
title: Bukkit 层导航
importance: medium
L: 1
D: 1
---

# Bukkit 层导航

## 入口
- 插件入口对象：`work.art1st.mineRails.bukkit.BukkitPlugin`
- 启用阶段会启动依赖注入、注册插件、初始化视距与世界管理，并注册已有世界。

## 目录导航
- `bukkit.logic`：世界相关接入与工厂
- `bukkit.render`：渲染、模型、动画、分块与视距管理
- `bukkit.extension`：把 Core 对象扩展到 Bukkit 表达形式
- `bukkit.nms`：平台实体或底层适配
- `bukkit.rail`：与建轨或轨道交互直接相关的入口

## 快速判断改动位置
- 和 Bukkit 世界生命周期有关：先看 `logic`
- 和显示效果、模型、分块可见性有关：先看 `render`
- 和列车、转向架、轨道在 Bukkit 侧的数据附着有关：先看 `extension`
- 和底层平台对象有关：先看 `nms`

## 使用原则
- Bukkit 层优先做运行时接入与适配。
- 如果逻辑可以抽到平台无关层，优先考虑放回 `Core` 或 `Basic`。
