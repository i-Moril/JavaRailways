# SimpleATO 持久化契约

## 范围与 owner

`SimpleATOInfrastructureSnapshotV2` 是 SimpleATO 独立的 infrastructure 持久化边界，保存canonical Destination definitions、Line definitions 与`lineRevision`。Station、StationBerth、Depot stabling point和Basic WayPoint都是从Destination role原子materialize的投影，不单独持久化为第二个owner。Line 的业务 JSON shape沿用`SavedLineV1`，Destination沿用`SavedOperationalDestinationV1`。

恢复必须先完整解析并预校验全部Destination与Line：重复identity、missing rail、越界arc、与Basic standalone WayPoint/RailObject/Depot point碰撞、Line missing Station都在任何写入前拒绝。然后通过`OperationalDestinationService`依次materialize Destination及其投影；异常时逆序移除已完成项。只有Destination/Station全部成功后才恢复Line store。目标SimpleATO stores必须fresh。

`SimpleATORuntimeSnapshotV2` 是 SimpleATO 独立的 runtime 持久化边界，保存 rolling-stock ledger、timetable definitions 与`SavedDispatcherV1` checkpoint。rolling-stock从Basic runtime迁入是两个内部document的breaking shape change，因此Basic runtime升为format 4，SimpleATO runtime升为format 2。两个writer都只输出current model，没有旧reader、默认值兼容或双写。

rolling-stock ledger在写入任何point或record前一次性校验完整batch：train ID、非空assignment ID与occupied point分别唯一，home Depot和point均存在，point属于home Depot且容纳该车辆，并且`AVAILABLE`/`RESERVED`/`IN_SERVICE`的point与assignment组合合法。任一失败保持整个ledger和所有point occupancy不变。

`SavedDispatcherV1.formatVersion = 1` 与 `SimpleATORuntimeSnapshotV2.formatVersion = 2` 分别属于各自的
独立边界；infrastructure revision 和 physics epoch 只用于配对一致性，不是 schema version。

## 捕获、文件配对与恢复

Bukkit 的外层 `InfrastructureFileV2` 原子封装 Core、Basic、SimpleATO 三个 current infrastructure document；
它们必须具有相同 `infrastructureRevision`。新增第三个根字段是 breaking root-shape change，因此 outer infrastructure
从format 1升为format 2；runtime root shape未变。

Bukkit 的外层 `RuntimeFileV2` 原子封装三个 current runtime document：

1. Core runtime：物理列车和 epoch；
2. Basic runtime V4：只保留revision/epoch配对metadata；
3. SimpleATO runtime V2：rolling-stock ledger、Timetable definitions 与 Dispatcher checkpoint。

捕获时三个 document 必须具有相同 infrastructure revision 与 physics epoch。基础设施恢复顺序固定为 Core → Basic → SimpleATO，
使轨道与standalone WayPoint先存在；SimpleATO内部再按Destination/Station/Depot point materialization → Line validation/restore排序。运行时恢复顺序同样固定为 Core → Basic → SimpleATO：physical train先存在，SimpleATO infrastructure已materialize Depot points，然后SimpleATO runtime先原子恢复rolling-stock ledger，再恢复 timetable store，
再恢复 Dispatcher logical checkpoint。active run 只恢复为 recovery-required/disabled 状态；checkpoint 不重建 claim、锁、
ticket、队列或 controller。

## 版本与迁移规则

正常 reader/writer 只处理 current `InfrastructureFileV2`、`BasicInfrastructureSnapshotV3`、
`SimpleATOInfrastructureSnapshotV2`、`RuntimeFileV2`、`BasicRuntimeSnapshotV4` 和 `SimpleATORuntimeSnapshotV2`。不得加入 runtime V1 fallback、nullable Dispatcher、双写或业务层
V1/V2 分支。旧开发存档由计划中的一次性 world-pair migrator 在正常加载前完成 dry-run、恢复点、
Dispatcher checkpoint 拆分、目标 pair 验证和可恢复替换。

外层`RuntimeFileV2`的root fields未改变，因此rolling-stock owner迁移不升级outer version；只升级实际发生breaking change的Basic与SimpleATO runtime documents。

## 验证

```bash
./gradlew :SimpleATO:test --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*SimpleATOInfrastructureSnapshotTest'
./gradlew :BukkitPlugin:test --tests '*WorldSnapshotExtensionDataTest'
git diff --check
```
