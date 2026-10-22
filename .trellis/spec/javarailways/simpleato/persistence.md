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


## B1 runtime新增默认字段与联合恢复（CURRENT）

`SavedRollingStockV1.stablingDepotId: String = homeDepotId`明确实际储存点所属depot。
有`stablingPointId`时按该depot恢复实际位置；home affiliation独立保留，允许home=A而physical=B。
无point表示没有physical storage association，不按home推断在库。现有current JSON缺少新字段时有明确home默认值，
writer始终写current；不增加runtime版本或旧输入业务分支。

`SimpleATORuntimeSnapshotV1`增加默认空`fixedUnits`和`fixedUnitAssignments`。
前者保存`id/homeDepotId/carIds/controlCompatibilityKey`，后者保存`unitId/trainId/assignmentId`；
这是现有runtime边界的默认字段，nested DTO不独立编号。unit car列表是不可变的永久运营成员定义，
Core runtime仍决定当前formation顺序/朝向；不能保存另一份可写Core composition。

恢复依赖Core实际cars和已恢复的Depot配置。`restoreOperationalLedger`先检查stock的state/point/assignment，
再检查unit definitions互不重叠、Core成员完整连续、声明family、claim唯一及完整formation归属；
claimed formation必须有相同assignment的非AVAILABLE stock，assigned registered stock不得遗漏claims。
全部验证通过后才在同一DepotStore锁内发布stock和unit账本，失败时两个ledger均为空；
随后才恢复时刻表和recovery-required Dispatcher checkpoint，不重新开始结构动作或构造live allocation tokens。

恢复与保存仅在owner同步边界执行；unit restore检查成本与unit数量、全Core车厢及unit长度乘积相关，
不是每tick重新解码。非法成员/重复claim/缺失stock或assignment不一致明确拒绝，不能把漏掉claims当作空闲资产。
`CrossDepotStoreTest`检查缺省字段及外库roundtrip；`FixedEmuUnitLedgerTest`检查unit DTO及联合恢复失败原子性；
现有`DispatcherSnapshotTest`继续覆盖原current空列表与recovery-required语义。

本次均为有明确默认值的新增字段，版本保持1；没有新增runtime migration或改变历史基线判断。
错误做法是依Core train容器ID猜测新unit或恢复后自动重放couple；正确做法是读取stable unit/car定义、
校验当前Core成员，再由显式恢复流程处置保留的assignment。计划重联/解编的恢复状态仍属于后续批次。

## Unresolved fixed-formation changes

The current runtime snapshot has default-empty `unresolvedFormationChanges` and `heldFormationOutputs` fields. A change stores fixed event identity, exact source/output assignments, ordered stable car IDs, directions, dispositions and a phase defaulting to `SOURCES_HELD`. Neither Basic handles nor Core movement-hold capabilities are serialized. These additive defaults do not change format version1.

`SOURCES_HELD` retains exact source metadata/unit claims and permits actual Core composition to be either unchanged sources or the expected published outputs. `commitFixedFormationChange` atomically installs exact output metadata/claims and returns a new immutable `OUTPUT_OWNERS_COMMITTED` token while retaining the pending record. The old token becomes stale. Restoration of this committed phase requires exact published outputs and complete output metadata/unit claims; source-shaped physical data or source claims are rejected.

The coordinator calls internal `finishFixedFormationChange` with every installed output owner and the exact subset of continuing outputs still awaiting departure, after disposition/mission installation and ready grant adoption in one serialized owner operation. Finish removes the group event and stores independent `FixedFormationHeldOutput(owner, disposition)` records for permanent HOLD outputs and pending CONTINUE_DUTY outputs. The flat saved owner fields gain a disposition defaulting to HOLD; no version change is needed. A departed peer may subsequently store or change formation without invalidating the delayed peer's evidence. The coordinator validates a pending owner before Basic resume and removes only its exact immutable token immediately after success in the same owner callback. Metadata, complete whole-unit claims, ordered membership and direction are revalidated; a permanent HOLD or restored pending record cannot be released by this runtime handoff path. This ledger readiness assertion does not itself certify mission installation, stopped physical state or release Core capabilities; those remain the coordinator's responsibility.

DepotStore validates the complete incoming operational ledger before applying it. Cars and registered whole units are conserved; overlapping events/held owners, duplicate or missing claims, unrelated output IDs, incomplete units, wrong direction and phase-inconsistent compositions reject restoration atomically. Ordinary claims retain the normal current-train membership invariant. Held outputs reject acquisition, release, storage and Reset through the existing depot guards.

After ledger restore, SimpleATOWorldData re-establishes separate private Core formation movement holds on every actual unresolved body and independent HOLD or pending continuation output before restoring dispatcher clocks. Existing configuration-hold release cannot release them. Failed later fencing retains already-installed protection. There is no automatic structural replay, ledger commit, AVAILABLE transition, HOLD release or output resumption.

`FixedFormationChangeTest` verifies exact phase tokens, partial finish, missing/source claims after commit and HOLD/pending continuation independence from continuing-peer storage. `FormationRecoverySnapshotTest` exercises real Core save/load before publication, after publication with source owners, after output-owner commit, after finish with only the independent HOLD body fenced, with independently pending continuation evidence, and while one committed output is genuinely moving before save. Core's current train persistence stores pose/direction and extensions, not controller or velocities: that moving checkpoint loads stopped before ticks, preserves its exact saved pose, and therefore satisfies the existing strict fence restoration. No live force-stop/replay API is needed. These tests establish ledger/recovery boundaries, not full scheduled rendezvous or live game acceptance.
