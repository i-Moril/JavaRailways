# SimpleATO 持久化契约

## 范围与 owner

`SimpleATOInfrastructureSnapshotV1` 是 SimpleATO 第一个独立 infrastructure 持久化边界，保存
canonical Destination definitions、Line definitions 与`lineRevision`。
轨道公共运行方向由 Basic 独立保存，SimpleATO document 不包含方向列表。
它与最终 Basic infrastructure format 2 一起构成一次 ownership extraction；该独立边界从 V1 开始。
Station、StationBerth、Depot stabling point和Basic WayPoint都是从Destination role原子materialize的投影，
不单独持久化为第二个owner。Line 的业务 JSON shape沿用`SavedLineV1`，Destination沿用
`SavedOperationalDestinationV1`。

恢复必须先完整解析并预校验全部Destination与Line：重复identity、missing rail、越界arc、与Basic standalone WayPoint/RailObject/Depot point碰撞、Line missing Station都在任何写入前拒绝。然后通过`OperationalDestinationService`依次materialize Destination及其投影；异常时逆序移除已完成项。只有Destination/Station全部成功后才恢复Line store。目标SimpleATO stores必须fresh。

`SimpleATORuntimeSnapshotV1` 是 SimpleATO 独立的 runtime 持久化边界，保存 rolling-stock ledger、Timetable
definitions 与`SavedDispatcherV1` checkpoint。最后一个真实 SimpleATO runtime V1 原本只有 Dispatcher；新增
`timetables`与`rollingStock`都具有明确的空列表默认值，因此同一 V1 reader 可以无转换读取原文档，不升级版本。
Basic runtime 删除原字段属于 breaking change，单独从真实 V2 升为最终 V3。两个writer都只输出current model，
没有双写。

rolling-stock ledger在写入任何point或record前一次性校验完整batch：train ID、非空assignment ID与occupied point分别唯一，home Depot和point均存在，point属于home Depot且容纳该车辆，并且`AVAILABLE`/`RESERVED`/`IN_SERVICE`的point与assignment组合合法。任一失败保持整个ledger和所有point occupancy不变。

`SavedDispatcherV1.formatVersion = 1` 与 `SimpleATORuntimeSnapshotV1.formatVersion = 1` 分别属于各自的
独立边界；infrastructure revision 和 physics epoch 只用于配对一致性，不是 schema version。

## 捕获、文件配对与恢复

Bukkit 的外层 `InfrastructureFileV2` 原子封装 Core、Basic、SimpleATO 三个 current infrastructure document；
它们必须具有相同 `infrastructureRevision`。新增第三个根字段是 breaking root-shape change，因此 outer infrastructure
从format 1升为format 2；runtime root shape未变。

Bukkit 的外层 `RuntimeFileV2` 原子封装三个 current runtime document：

1. Core runtime：物理列车和 epoch；
2. Basic runtime V3：只保留revision/epoch配对metadata；
3. SimpleATO runtime V1：rolling-stock ledger、Timetable definitions 与 Dispatcher checkpoint。

捕获时三个 document 必须具有相同 infrastructure revision 与 physics epoch。基础设施恢复顺序固定为 Core → Basic → SimpleATO，
使轨道与standalone WayPoint先存在；SimpleATO内部再按Destination/Station/Depot point materialization → Line validation/restore排序。运行时恢复顺序同样固定为 Core → Basic → SimpleATO：physical train先存在，SimpleATO infrastructure已materialize Depot points，然后SimpleATO runtime先原子恢复rolling-stock ledger，再恢复 timetable store，
再恢复 Dispatcher logical checkpoint。active run 只恢复为 recovery-required/disabled 状态；checkpoint 不重建 claim、锁、
ticket、队列或 controller。

## 版本与迁移规则

正常 reader/writer 只处理 current `InfrastructureFileV2`、`BasicInfrastructureSnapshotV2`、
`SimpleATOInfrastructureSnapshotV1`、`RuntimeFileV2`、`BasicRuntimeSnapshotV3` 和
`SimpleATORuntimeSnapshotV1`。outer infrastructure 因首次增加 SimpleATO 根字段从 V1 升为 V2。

基础设施 migration boundary 只接受最后一个真实 source：outer `InfrastructureFileV1` +
`BasicInfrastructureSnapshotV1`，其中没有 SimpleATO infrastructure document；它直接输出最终 V2/V2/V1 pair，
一次将 Destination、Line 交给 SimpleATO，将 `allowedDirection` 输出到 Basic 的稀疏方向列表。如果 source Core 仍为 format 1，纯转换器也会把其嵌入 rail
metadata 拆到相同最终 owners；Core 与 Basic 同时声明 metadata 时拒绝，不猜测合并。

当前除纯内存转换外，已提供[已审计开发服的独立离线迁移](../../bukkit-plugin/backend/offline-development-migration.md)，仅支持空列车/空运营数据的真实旧源；
09-24的未来端点格式和自动迁移仍未实现，不能由 runtime loader 或临时 Bukkit command 代替。不得加入 runtime fallback、nullable
Dispatcher、双写或业务层版本分支。

用户明确当前方向字段开发稿从未部署。本次删除 SimpleATO 中的方向列表并直接修正其 current fixture，
仍保持 format 1；Basic format 2 增加默认空列表。正常 reader 不读取旧 SimpleATO 方向字段，
不新增该未部署字段的迁移、兼容测试或 schema version。已有历史纯转换器只修正最终输出归属。

外层`RuntimeFileV2`的root fields未改变，因此 ownership extraction 不升级 outer runtime；Basic runtime 的字段删除
升级其最小边界，SimpleATO runtime 的默认空列表是同 V1 的兼容新增。旧 outer runtime V2 中 Basic V2 的
Timetable/rolling-stock 数据仍须由 09-24 配对迁移工具搬到 SimpleATO V1；正常 runtime reader 不执行该搬运。

## 验证

```bash
./gradlew :SimpleATO:test --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*SimpleATOInfrastructureSnapshotTest'
./gradlew :BukkitPlugin:test --tests '*WorldSnapshotExtensionDataTest'
git diff --check
```
