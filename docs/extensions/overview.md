---
title: 扩展插件开发概览
slug: /extensions/overview
---

# 扩展插件开发概览

:::danger API 尚未稳定
MineRails 当前没有发布独立的 API artifact，也没有承诺二进制兼容。不要把所有 `public` 类视为扩展 API；直接依赖实现包可能在任意提交中失效。
:::

MineRails 的目标是允许扩展插件读取铁路世界、监听事件并为 World、Train、Car 或 Rail 注册扩展数据。当前源码中已经存在这些基础机制，但缺少稳定的 Bukkit API facade 和发布流程。

## 计划公开的层次

| 层次 | 候选包 | 用途 |
|---|---|---|
| Core API | `work.art1st.mineRails.core.api.*` | 平台无关的轨道、列车、曲线、事件和扩展数据 |
| Basic API | `work.art1st.mineRails.basic.api.*` | 基础运营系统和 UI 配置 API |
| Bukkit API | `work.art1st.mineRails.bukkit.api.*` | 获取世界、模型行为和平台服务；尚未建立 |

## 当前扩展机制

`PluginRegistry` 在创建带扩展槽位的宿主之前注册 MineRails 内部插件，并为每个插件分配整数 identifier。`Extendable` 使用该 identifier 保存扩展数据。扩展数据若需要进入 train/car 快照，必须实现可序列化约定并提供稳定的扩展 ID。

事件通过全局 `EventManager` 注册：

```kotlin
class MyListener : EventListener {
    @HandlesEvent
    fun onEvent(event: TrainEvent) {
        // 先确认该事件的调用线程，再访问平台 API。
    }
}
```

这段代码只展示事件模型，不是当前可复制的外部插件完整配置。

## 在正式发布 SDK 前仍需完成

1. 建立 `bukkit.api` facade，并通过稳定服务获取 API，而不是访问 `BukkitPlugin.INSTANCE`。
2. 消除候选 API 对 `CoreTrain`、`CommitBuffer` 等实现类的引用。
3. 发布版本化 Maven artifact、`sources.jar` 和 `javadoc.jar`。
4. 提供 Java/Kotlin 两套可编译示例插件。
5. 定义实验、稳定、弃用和移除政策。
6. 对公开 API 的线程、异常、所有权和持久化语义补齐 KDoc。

在以上事项完成前，`BukkitPluginDev` 只能作为同仓库测试代码参考，不能视为第三方扩展模板。
