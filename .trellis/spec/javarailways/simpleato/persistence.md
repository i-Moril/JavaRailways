# SimpleATO 持久化契约

## 范围与 owner

`SimpleATOInfrastructureSnapshotV1` 是 SimpleATO 独立的 infrastructure 持久化边界，保存 Line definitions 与
`lineRevision`。Line 的业务 JSON shape 沿用既有 `SavedLineV1`；迁移只改变其 owner。恢复时必须在任何 Line 写入前
解析全部 Line、拒绝重复 ID，并验证每个 `StationId` 已由先恢复的 Basic Station 提供。

`SimpleATORuntimeSnapshotV1` 是 SimpleATO 独立的 runtime 持久化边界，保存 timetable definitions 与
`SavedDispatcherV1` checkpoint。新增 `timetables` 使用明确的空列表默认值，因此仍是 format 1；删除 timetable 的
Basic runtime 字段属于破坏性变化，Basic runtime 已升为 V3。Timetable 与 Dispatcher 都不得通过
`BasicWorldData` 转发访问。

`SavedDispatcherV1.formatVersion = 1` 与 `SimpleATORuntimeSnapshotV1.formatVersion = 1` 分别属于各自的
独立边界；infrastructure revision 和 physics epoch 只用于配对一致性，不是 schema version。

## 捕获、文件配对与恢复

Bukkit 的外层 `InfrastructureFileV2` 原子封装 Core、Basic、SimpleATO 三个 current infrastructure document；
它们必须具有相同 `infrastructureRevision`。新增第三个根字段是 breaking root-shape change，因此 outer infrastructure
从format 1升为format 2；runtime root shape未变。

Bukkit 的外层 `RuntimeFileV2` 原子封装三个 current runtime document：

1. Core runtime：物理列车和 epoch；
2. Basic runtime V3：rolling-stock ledger，不含 Timetable/Dispatcher；
3. SimpleATO runtime V1：Timetable definitions 与 Dispatcher checkpoint。

捕获时三个 document 必须具有相同 infrastructure revision 与 physics epoch。基础设施恢复顺序固定为 Core → Basic → SimpleATO，
使轨道、Station 等 Basic 引用先于 Line 存在。运行时恢复顺序同样固定为 Core → Basic → SimpleATO，使 physical train 与 rolling stock 先存在；SimpleATO runtime restore 内部先恢复 timetable store，
再恢复 Dispatcher logical checkpoint。active run 只恢复为 recovery-required/disabled 状态；checkpoint 不重建 claim、锁、
ticket、队列或 controller。

## 版本与迁移规则

正常 reader/writer 只处理 current `InfrastructureFileV2`、`BasicInfrastructureSnapshotV2`、
`SimpleATOInfrastructureSnapshotV1`、`RuntimeFileV2`、`BasicRuntimeSnapshotV3` 和 `SimpleATORuntimeSnapshotV1`。不得加入 runtime V1 fallback、nullable Dispatcher、双写或业务层
V1/V2 分支。旧开发存档由计划中的一次性 world-pair migrator 在正常加载前完成 dry-run、恢复点、
Dispatcher checkpoint 拆分、目标 pair 验证和可恢复替换。

## 验证

```bash
./gradlew :SimpleATO:test --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*SimpleATOInfrastructureSnapshotTest'
./gradlew :BukkitPlugin:test --tests '*WorldSnapshotExtensionDataTest'
git diff --check
```
