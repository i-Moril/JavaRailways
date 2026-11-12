# SimpleATO 持久化契约

## 范围与 owner

`SimpleATOInfrastructureSnapshotV1` 是 SimpleATO 第一个独立 infrastructure 持久化边界，保存
canonical Destination definitions、Line definitions 与`lineRevision`。
轨道公共运行方向由 Basic 独立保存，SimpleATO document 不包含方向列表。
它与最终 Basic infrastructure format 2 一起构成一次 ownership extraction；该独立边界从 V1 开始。
Station、StationBerth、Depot stabling point和Basic WayPoint都是从Destination role原子materialize的投影，
不单独持久化为第二个owner。Line 的业务 JSON shape沿用`SavedLineV1`，Destination沿用
`SavedOperationalDestinationV1`。

车站显示名称存于每站一份的 `stationMetadata[stationId].name`，停车点记录只保存车站 ID 和 berth ID 等运营字段。线路名称存于 `lines[].metadata.name`。
旧开发服快照可以使用 `scripts/station-name-migration/migrate.py` 处理提取副本，移除旧字段并保留未配置 metadata 的自定义名称。

恢复必须先完整解析并预校验全部Destination与Line：重复identity、missing rail、越界arc、与Basic standalone WayPoint/RailObject/Depot point碰撞、Line missing Station都在任何写入前拒绝。然后通过`OperationalDestinationService`依次materialize Destination及其投影；异常时逆序移除已完成项。只有Destination/Station全部成功后才恢复Line store。目标SimpleATO stores必须fresh。

`SimpleATORuntimeSnapshotV1` 是 SimpleATO 独立的 runtime 持久化边界，保存 Timetable definitions、
`SavedDispatcherV1` checkpoint、单元控制声明与任务归属，以及未完成结构操作和受保护输出。
列车成员、物理位置、车序归 Core；车型和控制属性从宿主查询，不保存车辆库存、home depot 或停车点占用副本。
当前未发布开发格式直接修正，版本保持约定值；writer 只输出 current model，没有双写或旧库存 runtime reader。

`SavedDispatcherV1.formatVersion = 1` 与 `SimpleATORuntimeSnapshotV1.formatVersion = 1` 分别属于各自的
独立边界；infrastructure revision 和 physics epoch 只用于配对一致性，不是 schema version。

## 捕获、文件配对与恢复

Bukkit 的外层 `InfrastructureFileV2` 原子封装 Core、Basic、SimpleATO 三个 current infrastructure document；
它们必须具有相同 `infrastructureRevision`。新增第三个根字段是 breaking root-shape change，因此 outer infrastructure
从format 1升为format 2；runtime root shape未变。

Bukkit 的外层 `RuntimeFileV2` 原子封装三个 current runtime document：

1. Core runtime：物理列车和 epoch；
2. Basic runtime V3：只保留revision/epoch配对metadata；
3. SimpleATO runtime V1：Timetable definitions、Dispatcher checkpoint 与任务/结构操作恢复信息。

捕获时三个 document 必须具有相同 infrastructure revision 与 physics epoch。基础设施恢复顺序固定为 Core → Basic → SimpleATO，
使轨道与standalone WayPoint先存在；SimpleATO内部再按Destination/Station/Depot point materialization → Line validation/restore排序。
运行时恢复顺序同样固定为 Core → Basic → SimpleATO：实际列车和 Core composition 先存在，
然后 SimpleATO 原子恢复任务归属及结构操作状态，重新安装独立 Core movement holds，再恢复 timetable store 与
Dispatcher logical checkpoint。active run 只恢复为 recovery-required/disabled 状态；checkpoint 不重建 Basic route claims、
锁、ticket、队列或 controller，不能自动恢复行驶。

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

外层 `RuntimeFileV2` 的 root fields 未改变。历史 ownership extraction 的旧输入仍属于独立迁移工具，
正常 runtime reader 不搬运 Basic 旧运营数据。本轮删除开发中的库存字段同样不新增兼容读取；
已有开发服存档必须在以后部署前用可 dry-run、保留恢复点的独立离线处理转成当前模型。

## 验证

```bash
./gradlew :SimpleATO:test --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*SimpleATOInfrastructureSnapshotTest'
./gradlew :BukkitPlugin:test --tests '*WorldSnapshotExtensionDataTest'
git diff --check
```


## Core 成员与任务归属的联合恢复（CURRENT）

固定成员身份及 canonical 车序由 [Core 组成](../core/train-composition.md) 保存。
`SimpleATORuntimeSnapshotV1` 不再写 `fixedUnits`，只写默认空的
`fixedUnitControlCompatibility: Map<String,String>` 与 `fixedUnitAssignments`。
前者是 unit ID 到可选控制族声明的映射，后者保存 `unitId/trainId/assignmentId`；两者均不保存车辆段或成员副本。

恢复依赖 Core 实际 cars/composition。`restoreOperationalLedger`通过只读 provider 检查 Core成员完整连续、
控制声明引用有效、claim唯一及完整formation归属，并核对未完成事件与受保护输出。
全部验证通过后才在同一DepotStore锁内发布控制声明、claims及结构恢复信息，失败时均不部分写入；
随后才恢复时刻表和recovery-required Dispatcher checkpoint，不重新开始结构动作或构造live allocation tokens。

恢复与保存仅在owner同步边界执行；unit restore检查成本与unit数量、全Core车厢及unit长度乘积相关，
不是每tick重新解码。非法成员、重复claim或不完整任务归属明确拒绝，不能把漏掉claims当作空闲列车。
`FixedEmuUnitLedgerTest`检查unit投影及联合恢复失败原子性；
现有`DispatcherSnapshotTest`继续覆盖原current空列表与recovery-required语义。

本批修正未发布开发格式，版本保持1；不新增runtime旧成员读取路径。
含旧 `fixedUnits` 的开发存档需先做离线迁移，将成员身份写入 Core composition，再转换控制标注；不能静默丢弃历史重联边界。
不能从旧重联列车容器猜测历史单元边界，也不能恢复后自动重放couple；应读取明确的stable unit/car定义、
校验当前Core成员，再由显式恢复流程处置保留的assignment。普通未标记列车的默认单元按Core合同沿用列车ID。

`SavedActiveRunCheckpointV1.unitAssignmentId: String? = null` 记录任务实际持有的归属身份，
不是新的列车身份。明确 discard/reset 时，live run 用准确 assignment 释放 claims；恢复后的 checkpoint
只有在列车停稳、没有结构保护且所有 claims 均匹配保存身份时才可释放。无法确认身份时拒绝清理，保留恢复信息。
结构恢复列车的 checkpoint 诊断可以显式删除，但它不会释放任何结构 claims、HOLD 或 movement fences；
后续新任务仍受相同保护约束。checkpoint 不授权移动或重新执行任务；清理也不更改实际车位，
摆位仍只遵循时刻表 `initialPositions`。

## Unresolved fixed-formation changes

The current runtime snapshot has default-empty `unresolvedFormationChanges` and `heldFormationOutputs` fields. A change stores fixed event identity, exact source/output assignments, ordered stable car IDs, directions, dispositions and a phase defaulting to `SOURCES_HELD`. Neither Basic handles nor Core movement-hold capabilities are serialized. These additive defaults do not change format version1.

`SOURCES_HELD` retains exact source membership/unit claims and permits actual Core composition to be either unchanged sources or the expected published outputs. `commitFixedFormationChange` atomically installs exact output claims and returns a new immutable `OUTPUT_OWNERS_COMMITTED` token while retaining the pending record. The old token becomes stale. Restoration of this committed phase requires exact published outputs and complete output unit claims; source-shaped physical data or source claims are rejected.

The coordinator calls internal `finishFixedFormationChange` with every installed output owner and the exact subset of continuing outputs still awaiting departure, after disposition/mission installation and ready grant adoption in one serialized owner operation. Finish removes the group event and stores independent `FixedFormationHeldOutput(owner, disposition)` records for permanent HOLD outputs and pending CONTINUE_DUTY outputs. The flat saved owner fields gain a disposition defaulting to HOLD; no version change is needed. A departed peer may subsequently store or change formation without invalidating the delayed peer's evidence. The coordinator validates a pending owner before Basic resume and removes only its exact immutable token immediately after success in the same owner callback. Metadata, complete whole-unit claims, ordered membership and direction are revalidated; a permanent HOLD or restored pending record cannot be released by this runtime handoff path. This ledger readiness assertion does not itself certify mission installation, stopped physical state or release Core capabilities; those remain the coordinator's responsibility.

DepotStore validates the complete incoming operational ledger before applying it. Cars and actual Core whole units are conserved; overlapping events/held owners, duplicate or missing claims, unrelated output IDs, incomplete units, wrong direction and phase-inconsistent compositions reject restoration atomically. Ordinary claims retain the normal current-train membership invariant. Held outputs reject acquisition, unrelated release, storage and timetable reset through the existing guards.

After ledger restore, SimpleATOWorldData re-establishes separate private Core formation movement holds on every actual unresolved body and independent HOLD or pending continuation output before restoring dispatcher clocks. Existing configuration-hold release cannot release them. Failed later fencing retains already-installed protection. There is no automatic structural replay, claim commit, HOLD release or output resumption.

`FixedFormationChangeTest` verifies exact phase tokens, partial finish, missing/source claims after commit and HOLD/pending continuation independence from continuing-peer storage. `FormationRecoverySnapshotTest` exercises real Core save/load before publication, after publication with source owners, after output-owner commit, after finish with only the independent HOLD body fenced, with independently pending continuation evidence, and while one committed output is genuinely moving before save. Core's current train persistence stores pose/direction and extensions, not controller or velocities: that moving checkpoint loads stopped before ticks, preserves its exact saved pose, and therefore satisfies the existing strict fence restoration. No live force-stop/replay API is needed. These tests establish ledger/recovery boundaries, not full scheduled rendezvous or live game acceptance.

## 停站程序与经停点（CURRENT）

当前未发布 Timetable 边界继续 formatVersion1。动作 snapshot 新增 `BEGIN_STATION_STOP` 和可空 `stopPrograms: SavedStationStopPrograms`，只允许挂载在停站边界上；动作字段、分支枚举与运行调用状态均以当前模型为准，strict decoder 统一处理未知输入；没有旧字段或旧枚举的专项检查。嵌套 action 使用稳定 type，包含 OPEN_DOORS/CLOSE_DOORS/END/REVERSE/WAIT_UNTIL/WAIT_DURATION/ASYNC/AWAIT/SET_DISPLAY/RESET_DISPLAY/EXTENSION。ASYNC 递归保存 steps，AWAIT 保存 taskId；tick/duration 的单位为 tick。恢复先构建当前不可变领域模型，由同一模型验证结束、深度、数量、任务先启动后等待及祖先等待环，不能绕过验证恢复可执行树。

run metadata 的 call 增加默认空 `viaIds`；保存、恢复、worker DTO 和前端 wire 保持相同顺序，不按集合排序。`StationStopProgramSnapshotTest` 与 `TimetableRunMetadataTest` 覆盖往返及非法输入；真实浏览器脚本 fixture 的 Graal 编译测试覆盖 writer→worker→snapshot→domain。

运营车次段新增 `unassignedUnits:Boolean=false`：显式项沿用正整数槽位；默认项为单 RUN、空槽位、空 defaults，
在实际运行时选择未被显式项分配的完整单元。Timetable 构造、copy、store 与 capture/restore 使用同一完整规范模型，
每 RUN 重建一个默认车次，不保存另一份 ordinary-train fallback。worker 镜像保持相同字段和归一化顺序，
并在编译 RUN 前显示动作的 BEGIN 边界前补齐默认项。当前未发布格式保持约定版本，不增加旧开发 reader；
默认项形状非法应拒绝，不能在重建时静默丢弃作者 defaults 或跨段定义。

编辑文档从 per-call arrivalProgram 改为 stationPrograms，运行引擎不反向猜测不同调用应合并为何种逻辑。一次性编辑文档转换由 `scripts/migrate_station_programs.py` 提供；它不重写 world/runtime snapshot，也不恢复 live checkpoint。旧运行定义应从审核后的当前编辑文档重新编译。完整运行 gate 与 checkpoint 限制见 dispatcher-runtime；迁移操作说明见 tasks/10-24-stop-program-hooks/research/migration.md。

## Station metadata 默认字段（2026-11-10）

SimpleATOInfrastructureSnapshotV1 增加 stationMetadata:Map<String,JsonObject>=emptyMap()，每站只存一份。缺少字段的current fixture按空map读取；capture使用StationStore，restore先校验所有station ID存在于destination角色生成的站点集合、JSON合法与界限，再materialize destinations并恢复metadata。失败沿既有逆序清理；不改runtime/outer边界或formatVersion。新增有默认值字段无需升版，无开发格式兼容reader。沿正常周期/停服世界快照保存，与line编辑一致；HTTP成功指live world已更新，不表示同步落盘。
