---
title: 持久化设计
slug: /architecture/persistence
---

# 持久化设计

## Core 世界快照

Bukkit 世界卸载时，`WorldPersistence` 将 `CoreWorld.SavedWorldV1` 写入：

```text
plugins/MineRails/worlds/<bukkit-world-uuid>.json
```

保存过程先写同目录 `.tmp` 文件，再尝试原子替换目标文件；文件系统不支持原子移动时退化为普通替换。

快照当前包含：

- rails；
- trains 和 cars；
- train/car 上实现 `SerializableExtensionData` 的扩展数据。

JSON 使用 `encodeDefaults = true` 和 `ignoreUnknownKeys = true`。读取损坏快照时，该 Bukkit 世界不会注册 MineRails 功能，日志会要求管理员修复或移走损坏文件，不会静默创建空世界覆盖原数据。

:::warning 类型兼容性
扩展数据反序列化当前依赖保存的 JVM 类名和反射 serializer。移动或删除扩展数据类可能破坏旧快照，发布前必须设计迁移策略。
:::

## 当前未进入 Core 快照的数据

`BasicWorldData` 本身目前没有随 `CoreWorld` 保存，因此 WayPoint、运行期 TimetableStore 和 Dispatcher 状态尚未形成长期持久化契约。

轨道显示 metadata 由 Bukkit 层的 `RailRenderMetaStore` 单独保存。列车模型快照在 `snapshot` 策略下另存到：

```text
plugins/MineRails/model-snapshots/<sha256>.json
```

## 列车模型更新策略

- `follow-package`：存档保存模型名，恢复时使用当前模型包重新构建。
- `snapshot`：存档引用 resolved 模型树 hash，已有列车不自动跟随模型包变化。

无论采用哪种策略，稳定 scene 可以持久化；进行中的动画进度不持久化。

## 演进规则

1. 持久化结构使用显式版本类型，例如 `SavedWorldV1`。
2. 新字段应提供兼容默认值；删除或重命名字段需要迁移测试。
3. 保存前不得删除最后一份可读快照。
4. 模型包 `config-version`、插件发布版本和世界快照版本是三套不同版本，不能互相代替。
