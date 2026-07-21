---
title: 持久化设计
slug: /architecture/persistence
---

# 持久化设计

## 分离的世界快照

世界状态使用两个带共同 `infrastructureRevision` 的版本化文件：

```text
plugins/MineRails/worlds/<bukkit-world-uuid>-infrastructure.json
plugins/MineRails/worlds/<bukkit-world-uuid>-runtime.json
```

基础设施文件保存 Rails、Waypoint、OperationalDestination、StationBerth 与 Depot StablingPoint。删除 Rail 会级联删除其 Destination 及派生记录；端点/半径重建会按原三维位置迁移，split/merge 会换算 AB 弧长。快照捕获和恢复都会拒绝引用缺失 Rail 的孤儿 Destination。运行期文件在同一 physics barrier 捕获 Train、规范化 Timetable、Dispatcher logical clock/cycle 和安全 ActiveRun checkpoint；无法安全热恢复的 run 作为 `RECOVERY_REQUIRED` checkpoint 保留，不序列化 live ATO、ticket、锁、Future 或 worker queue。

`WorldManager` 每 1200 server tick 在串行 simulation context 捕获不可变 DTO，JSON 编码和磁盘写入由单线程异步 writer 完成；同一世界积压时仅保留最新待写快照。世界卸载和插件关闭会等待最终快照写完。保存过程先写 `.tmp`，保留 `.bak`，再原子替换；加载会选择 infrastructure/runtime revision 匹配的最新一对。

旧单文件 `SavedWorldV1` 不再由运行时代码读取；现有测试服文件已离线迁移为分离格式，避免长期保留双格式兼容路径。

JSON 使用 `encodeDefaults = true` 和 `ignoreUnknownKeys = true`。读取损坏快照时，该 Bukkit 世界不会注册 MineRails 功能，日志会要求管理员修复或移走损坏文件，不会静默创建空世界覆盖原数据。

:::warning 类型兼容性
扩展数据反序列化当前依赖保存的 JVM 类名和反射 serializer。移动或删除扩展数据类可能破坏旧快照，发布前必须设计迁移策略。
:::

## 运营数据与恢复边界

持久化包含 directed Destination 全部角色字段、Station/Berth 默认值、Depot/StablingPoint、RollingStockRecord/稳定 occupancy、规范化 Timetable，以及 Dispatcher epoch、cycle、禁用 service 和 ActiveRun 的稳定 step/目标/dwell 检查点。

恢复不会重放 operation ticket、RouteSection lock、confirmed route window、临时 physics command 或 Bukkit/Folia task。旧活动 run 进入 `RECOVERY_REQUIRED`，管理员后续依据已恢复的 Train 实际位置显式恢复；系统不会伪造旧锁仍有效。Depot reset 报告仍单独写入运维目录，不作为下一次自动恢复指令。

轨道显示 metadata 由 Bukkit 层的 `RailRenderMetaStore` 单独保存。列车模型快照在 `snapshot` 策略下另存到：

```text
plugins/MineRails/model-snapshots/<sha256>.json
```

## 列车模型更新策略

- `follow-package`：存档保存模型名，恢复时使用当前模型包重新构建。
- `snapshot`：存档引用 resolved 模型树 hash，已有列车不自动跟随模型包变化。

无论采用哪种策略，稳定 scene 可以持久化；进行中的动画进度不持久化。

## 演进规则

1. 持久化结构使用显式版本类型，例如 `SavedInfrastructureV1`、`SavedRuntimeV1`。
2. 新字段应提供兼容默认值；删除或重命名字段需要迁移测试。
3. 保存前不得删除最后一份可读快照。
4. 模型包 `config-version`、插件发布版本和世界快照版本是三套不同版本，不能互相代替。
