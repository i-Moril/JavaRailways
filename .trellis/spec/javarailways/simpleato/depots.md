# 车辆段、停车点与列车任务归属

## 范围与 owner

SimpleATO 拥有 Depot/StablingPoint 定义、时刻表选车及具体任务归属；Core 拥有实际列车、固定单元、车序和物理位置，Basic 拥有路线资源准入和车尾释放。

本轮移除独立车辆库存登记：不再有 `RollingStockRecord`、home depot、`AVAILABLE/RESERVED/IN_SERVICE` 或根据库存登记摆车的 reset。固定单元合同归 [Core composition](../core/train-composition.md)。实施与验证证据见显示集成任务的 `rolling-stock-removal-20261111.md`。

## 一次普通取车与入库

`TakeFromDepot` 从指定车辆段的实际停车点查找停稳列车，核对方向、车体、脱轨、已有任务与控制器，再检查选车条件。列车不需要提前登记，也不需要声明永久所属车辆段。

任务选择列车后持有其真实身份，单元 claims 只表示当前任务归属。开始行驶通过既有 Basic route admission；位置或库存标记均不能代替资源授权。

`StoreAtDepot` 根据动作指定的目标车辆段与停车点选取目标。到达前需要保证其他任务不会同时把同一停车点当作自己的目标；这是一项具体操作预约，不是一份车辆库存记录。Dispatcher 使用同一条入库流程，不再按登记/未登记车辆分流。物理是否空闲仍需查询实际列车和路线资源；逻辑预约不构成行车授权。

完整列车到达且旧路线资源可安全结束后，操作释放准确预约和任务归属。不能仅因车头到达就释放车尾仍占用的物理资源。

## 数据与查询接口

- `Depot(id, displayName, stablingPointRefs)` 定义车辆段。
- `DepotStablingPoint(ref, destinationId, usableLengthMeters?, allowedTags)` 定义实际 directed Destination 和可选适用条件。
- `StablingPointSnapshot` 只提供停车点定义，不保存可独立修改的物理占用副本。
- `TrainCandidateFacts(modelPackageId?, tags, maximumSpeedMetersPerSecond?, capabilities)` 是一次 owner 查询产生的不可变值，不含车辆段、位置、车数、长度或库存状态。
- `SimpleATOWorldData.candidateFacts: (Train) -> TrainCandidateFacts?` 由宿主注入；Core frame 提供实际 carIds、车数及各车长度。
- `DepotTrainSelector.matches(facts, carCount)` 使用查询值与实际车数。未知最高速度不能满足最小速度条件；未知标签/能力不能满足非空要求。

空闲车可能没有 actuator。宿主应复用已绑定车型/控制配置的只读 resolver，不得为了选车创建 ATO 或挂载临时控制器。SimpleATO 不依赖 Bukkit，worker 只消费捕获的 detached facts 与 Core frame。

`TrainCandidateFacts.fromCars(carFacts)` 从准确 car ID 到属性叶子的不可变映射汇总；叶子不能再嵌套成员。
所有车的车型相同才返回该车型，标签/能力取交集，最高速度在全部已知时取最小值，否则为未知。
实际宿主按每车现有配置快照与 SNAPSHOT/FOLLOW_PACKAGE 策略查询，不能重联后只读取主列车配置。
隔离模拟捕获逐车值并按模拟车 ID 重映射，重联或解编后沿实际车厢再次汇总。
静态 `FormationOutputMetadata` 同样保留准确叶子，重联合并、解编取输出子集；它还验证实际车数和配置长度，
运行时结果必须与预检证据相同。均匀车型 profile 或测试可用四参数构造器提供均匀事实，捕获时展开到准确成员。
例如 A 型和 B 型重联时车型为未知；再次拆成纯 A 后必须恢复 A，不能永久沿用混编汇总，也不能沿用主列 A 冒认整列。

用户确定筛选以后以列车 metadata 为来源，目前 WebUI 暂停编辑入口。尚无 metadata 声明时不能把车型名当 tags，也不能把动作名存在当成实际能力证明。保存、编译和执行现有非空条件时必须真实核对，不能静默忽略。

## 成员与任务归属

`DepotStore` 的固定成员 provider 只读 Core composition；不存在第二份可登记成员表。`FixedEmuUnit(id, carIds, controlCompatibilityKey?)` 是成员投影，不能用它定义 home depot。

`claimFixedFormation(trainId, physicalCarIds, assignmentId)` 先验证所有完整单元、成员顺序和唯一任务归属，再一次写入 claims；失败无部分写入。`releaseFixedFormation` 仅释放准确 train/assignment，未完成结构保护不得被无关取消清除。

普通成员解析和取车不要求控制族标注。现有时刻表重联的声明兼容检查仍保留：缺失或冲突不能伪装成通过；相同声明不是完整动力/制动兼容证明。控制声明按单元 ID 保存，失去实际 Core 单元的孤儿声明不进入快照。

## 重联与解编

Core 提交实际组成；Basic 保留源资源和新结果的停止保护，并验证实际输出。SimpleATO 将明确输出绑定到相应任务或 HOLD 状态，不以车辆库存记录作为前提。

现有 `FixedFormationChange` 描述源/输出的准确任务身份、carIds、方向和处理阶段。`prepareFixedFormationChange` 在不可逆操作前验证来源；`commitFixedFormationChange` 对账已发布 Core 输出并更新准确任务 claims；`finishFixedFormationChange` 核对所有任务处理已安装。后续条件未就绪时列车保持停止，不撤回已完成的物理重联或解编。

HOLD 或尚未放行的结果仍需要明确归属，不能在没有 ActiveRun 的时候被另一任务错误取走。保留已有未完成事件和结果保护；不能因为删除库存资料就删去这些约束，也不引入新的通用车辆生命周期管理层。

底层允许内部单元拆分，具体组成规则见 Core 合同。当前以完整单元槽位编排的领域动作仍校验其声明边界，不把底层能力混同为新增玩家命令。

## 重置与 initialPositions

摆位唯一规则是时刻表的 `initialPositions`。使用现有 timetable apply/reset 与 `TimetableInitialPositioner`，不保留 `/jr depot assign/list/reset/reset-all` 的库存登记和独立摆位路径。

初始位置只在明确的 apply/reset 边界应用，正常周期推进不反复搬车。保留现有控制权、停车与资源清理检查；没有初始位置规则时，不根据所属车辆段猜测目标。

`TimetableInitialPositioner(world, maintenance)`要求调用方已经通过公开`World.withPhysicsStalled`取得该World的活跃`PhysicsMaintenance`。这是物理帧停顿，不检查或依赖ATO的`isStalled`；正常启动和全局reload分别在自己的调用边界建立作用域，隔离模拟复用同一Dispatcher启动流程。退出作用域是否允许继续物理推进由Core维护合同决定，不由批量摆位函数复位ATO或物理故障。

SimpleATO按既有selector顺序分配互不重复的列车与目标，优先保留已经满足的位置。位置值统一用`RailPositionSnapshot`的canonical AB米制弧长，朝向仍依次取Destination、Basic线路配置、既有AB默认。对需要移动的每一列车调用公开`maintenance.inspectTeleport`，全部得到完整候选后才比较最终布局：参与搬移的列车使用候选`occupiedRailIntervals`，其余使用已发布的`occupiedIntervals`，重用`RailInterval.overlaps`的闭区间与容差规则。只检查至少包含一列本批列车的配对，不把无关旧重叠升级为本批错误。

所有候选通过后，SimpleATO逐车调用`maintenance.teleport`；Core仅负责单车几何安装与停帧期的一致发布，不接收时刻表或批量选车规则。候选和维护作用域的生命周期、几何求解与发布失败规则归[Core维护摆位](../core/train-physics.md)。SimpleATO不再导入`CoreWorld`、`CoreTrain`、`CoreRail`或内部位置、快照、包络、发布方法。

目标互相重叠、占用未搬移列车、第二列车无法完整摆放或selector重复使用列车，都在任何TP之前返回`Failure`并沿用原ATO故障诊断；旧几何、train/world帧和mailbox不变。两车交换位置则按最终目标比较，允许旧位置被同批另一列车接替。选择及配对沿用既有成本：每项规则扫描候选，最终布局最坏进行列车对及各自区间的重叠比较；只在apply/reset执行，不增加普通tick扫描。`TimetableInitialPositionerTest`覆盖上述成功与失败场景；无持久化格式或版本变化。

## 拒绝与失败行为

- 列车移动、脱轨、位置/方向错误、被其他任务控制：不能作为取车来源。
- 重复任务归属、部分单元、成员交错或 carId 冲突：准备阶段拒绝，既有归属不变。
- 停车点已被其他操作预约或实际占用、车体不适配：不能选为本次目标。
- 非空属性要求没有对应候选事实：不匹配；不自动生成假资料。
- 源保护未清、结构结果与实际 Core 不符、任务绑定未安装：保持停车/资源保护并报告操作错误。
- 重置候选失败：不发布部分摆位；物理提交后失败继续遵守既有 fail-closed 处理，不猜测回滚。

## 持久化、成本与例子

车辆事实只在任务选择、验证或模拟捕获时查询，不持久化成车辆属性表。运行时 snapshot 只保存已有任务 claims、控制声明、未完成结构操作与受保护输出；具体字段归 [持久化合同](./persistence.md)。恢复时与实际 Core 组成对账，不自动重放不可逆结构动作。

候选选择与实际停车点和列车数有关；成员解析沿当前完整单元/车辆扫描，逐车属性捕获及汇总随成员数线性增长。
属性 resolver 是低频、无副作用查询，不在每个物理 tick 构造控制器。静态模拟可保留自己的 detached 工作快照，不得反写为 live 世界库存。

例如列车 A 在车辆段 P，时刻表可以从 P 取车并回送 Q；无需登记 home=P。另一任务不能同时取 A，也不能与 A 的当前入库操作同时取得同一个目标；物理防冲突仍由 Basic 的资源授权保证。

## 验证与迁移

至少覆盖无登记取车/入库、竞争同一停车点、取消准确 token、重复任务拒绝、完整车尾释放、结构输出任务/HOLD 归属、保存恢复对账、未知候选条件、模拟克隆，以及 initialPositions 两阶段摆位。
属性测试还必须验证混编重联再解编恢复各自事实、精确成员变化拒绝、输入/输出映射不可修改、实际与静态汇总相等；
SIM 必须通过真实 Core link/split 验证，不能只断言数学 helper。

本项目未发布开发格式直接修正，保持约定版本，不增加旧库存运行时 reader。包含旧库存字段或旧独立成员定义的开发存档在部署前需独立、可审计、有恢复点的离线处理。本批不写服务器存档。错误做法是把 Record 换个名称后继续登记/保存同一套车辆事实；正确做法是查询已有事实，只保存真实任务与操作需要的信息。
