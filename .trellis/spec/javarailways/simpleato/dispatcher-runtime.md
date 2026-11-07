# SimpleATO Dispatcher runtime 契约

## 适用场景与用途

触发：修改Timetable clock/run states、train acquisition、route/berth/depot operation、door/reverse/halt/extension、control transfer或checkpoint recovery。

用途：每 world Dispatcher 在 successful physics publication 后推进确定性 state；每个 transition 等待对应 resource/ticket/publication，不因 terminal state 过早删 runtime 或释放 tail-held 资源。

## Owner、入口与state

`SimpleATOWorldData.dispatcher` 是每个 world 的唯一可写 owner。`TimetableDispatcher` 拥有 mission revision、clocks/runs、每run admission client、非独占StationCall registry、operation/extension tickets、storage reservation、recovery checkpoints。Basic 不保留 dispatcher 字段或转发 accessor。Dispatcher构造时必须提供非空`TimetableRouteAdmissionContextProvider`和world-local `TrainRestrictionSnapshotProvider`；Bukkit production每Tick只取一次coherent observation/context并使用认证的world-local `RouteResourceAdmissionService`，隔离仿真组合同一个Basic runtime。每次建立或延长admission route时，Dispatcher低频编译inspection声明，把冻结route与Mission progress的坐标映射绑定给controller；逐physics epoch的inspection激活/dirty消费、snapshot同步和constraint replacement由controller在规划运动前完成，不等待下一次Dispatcher tick。无coherent observation时保持fail-closed，不存在legacy allocator fallback。入口：

```kotlin
fun beginTimetable(world: World, timetableId: String): TimetableEpochStartResult
fun scheduleInitialCycle(timetableId: String): Boolean
fun startService(world: World, timetableId: String, serviceId: String): TimetableServiceStartResult
@MainThread fun tick(world: World)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt`。

修改controller或运行状态的同步入口在Core world上要求协调器帧间阶段；production宿主的Dispatcher tick已处于该阶段，
命令将整项操作排队，而不把这些内部方法改成异步setter。隔离simulation启动时的beginTimetable与scheduleInitialCycle也由同一
帧间作用域包住，不依赖尚未开始的host tick来drain自身请求。完整创建/摆位的发布迁移另见后续批次，不能由入口guard推断其已完成。

`beginTimetable`先validation和blocking-run check，然后把validation已经得到的全部物理route交给Basic编译，
先通过world-local `InfrastructureDependencyService`安装新物理路线与旧计划完整依赖的非独占并集，
再通过同一world的`InspectionRuntime.collectRouteInfrastructureDependencies`收集插件引用的注册资源，
最后以同一稳定owner、更高revision原子替换为新计划完整rail/node/resource dependency
（与已安装并集相等时直接复用）；只有最终安装成功后才执行atomic initial
positions并发布shared epoch。dependency失败不发布clock；initial-position失败则删除新声明，replacement场景以更高的
runtime owner revision恢复旧声明。收集或最终安装失败也复用这个恢复流程；已安装保护必须先记入账本，
恢复失败则保留其身份并使用既有基础设施错误停止路径。这个步骤不再次寻路。
`scheduleInitialCycle`只能紧随begin，manual activation不自动启动。
静态收集不调用列车inspection bind/update，不检查当前灯色或占用，不产生claim；其接口、复杂度和失败合同见
[静态资源依赖](./operational-routing-and-authority.md#35-已选时刻表路线的静态资源依赖)。
scheduled cycles按period recurrence；当前时刻表校验拒绝依赖运行时条件的 activation，计划条件必须在编译时折叠。

每个timetable使用由其ID确定性生成的稳定owner ID；revision只是在当前world runtime内单调递增的mutation序号，不是
schema version，也不写入checkpoint。clock仍存在、checkpoint recovery run仍待管理员处理，或任一run尚未满足
`canBeReplaced`（包括Basic grant/berth/storage/停止条件）时，dependency必须保留。停止future cycles后只在上述状态全部
drain时删除。普通无固定编成catalog的checkpoint restore先从当前timetable定义重新validation、编译并安装clock/run涉及的全部dependency，全部成功
后才发布恢复的clock与`recoveryRequiredRuns`；任一失败回滚本次已装声明并拒绝restore，不静默恢复成可运行状态。Bukkit
必须在Basic world registry attach后调用SimpleATO restore；隔离仿真使用同一registry语义。直接的ad-hoc/manual
`startService`不等同于“应用完整时刻表”，不在这条静态dependency生命周期内。

### 停止自动发车与解除编辑保护

`stopFutureCycles(timetableId)`删除自动发车clock，不删除已启动车次，也不释放列车资源。
`releaseDrainedAppliedPlans`复用已有状态判断，不引入独立的draining状态机：

```text
没有该时刻表的clock
且没有该时刻表的recoveryRequiredRuns
且其所有run均canBeReplaced
  -> 调用dependency service.remove(准确的owner/revision)
  -> 仅成功后删除Dispatcher记录
```

因此，`COMPLETED`但仍持有Basic授权的列车继续阻止计划替换和强制删除；
`forceDiscardTimetable`不能用来绕过live admission。没有新自动发车，不等于禁止显式人工恢复/启动。
若删除静态依赖返回失败，保留Dispatcher与registry中的保护，并进入已有基础设施错误停止路径，
不能先删本地记录再假定编辑已安全。checkpoint恢复的旧车次只作为待人工处理记录，
即使其clock已停止也继续保留静态依赖，直到显式清理该记录。

这些判断只读已有clock/run/recovery状态，不重新寻路、不重新收集插件资源，也不新增资源队列。

Run state token包含WAITING_MISSION/TIME/ROUTE、RUNNING、WAITING_BERTH、station door+dwell states、STORING/HALTING/REVERSING/WAITING_EXTENSION_ACTION、manual states、STOPPED/COMPLETED/FAILED。Private实现可重构，以下gates不可移除。

## Transition矩阵

| transition | 前置 | 成功后 |
| --- | --- | --- |
| start run | validator success；train物理可用 | install ATO/run；无partial failure |
| RUNNING | mission revision已physics publish | 可推进route progress |
| route conflict/physical lock | retry tick | `WAITING_ROUTE`+authority at protection，不Stall |
| invalid resource/controller identity | invariant failure | world Stall，本Tick停止后续runs |
| berth | exact non-exclusive call registration | submit station target; movement still requires rolling Basic grant |
| shared station-program dwell | 实际到站publication（STATION取已记录BERTHED tick） | STOP最低发车时间从到站起算，开门完成不重置；非program旧station状态机另按其显式动作语义 |
| depart | door-close SUCCEEDED + next route confirmed | grant departure；berth仍tail-held |
| halt | model `power-off` SUCCEEDED | terminal gate |
| reverse | ATO preparation revision published | main reverse；下一Tick确认 |
| extension action | start exactly once | 后续Ticks只inspect ticket |
| terminal replace/remove | Basic grant ended、berth/storage clear且ATO stopped | runtime才可replace |

Train acquisition以live stopped train location/speed/derail/controller/run/geometry为authority；RollingStock只补metadata。First mission confirmed后Depot reservation才IN_SERVICE。Storage full envelope+old resources clear才handoff。

普通目的地完成、Stop路径点到达通知和mission完成的提前到达判定均使用单倍`missionStopToleranceMeters`，
不能隐含放大为两倍；mission最终Stop的`offsetMetersBefore`须从目标进度中扣除。暂时零速但尚未进入既有容差时，
不推进后续动作，允许原控制器继续接近。这里仍是既有进度/零速完成判定，不新增绝对停车误差验证器；
真实授权越界检查独立保留，真实精度回归须直接断言实际车头坐标，不能用任务完成状态替代。

下一 Mission 的首段若与实际停稳车头同轨同向，且起点差异在既有停车容差内，复用
`ObservedAtoMissionOrigin` 将首段设为真实车头位置；允许停车偏差不意味着可以将车头截到标记。
确认首段起点前的欠停后，先保留这段到标记的真实距离，再考虑其他轨道出现次数；不能先在整条路线
搜索包含车头的段，否则绕行后再次经过同轨时可能误选后段并跳过环线。
显式换向后的路线段归属也不用停车容差扩张，段内直接使用实际位置，段外仍走既有连接路线逻辑。
通用投影和冻结路线的规则由 [ATO policy runtime](ato-policy-runtime.md#真实位置到-mission-累计距离) 维护。

`scheduleStationCall` 先从当前 Mission 取包含目标站台的选定前缀，再交给 `StationCallResolver`。
该前缀的总长就是这个站台在 Mission 上的距离，由 `ResolvedStationCall` 初始化一次并传给全部
目标消费者，不能在 controller 激活时再次投影，也不能在下游重复求和 getter 后比较两份距离。
完整 producer/consumer 合同见[已选停站目标只计算一次](stations.md#已选停站目标只计算一次)。
前缀选择接受真实段内标记，不将段外站台截回段端点；站台在 Mission 中途也适用。
真正的“已越过停车位置”业务判断继续使用原停车容差，车门、授权和站停生命周期不变。
`TimetableDispatcherTest` 的真实双轨环线回归验证欠停不会跳过首个 occurrence；它检查提交的完整
路线和不瞬移，不把仅安装 Mission 描述为已经完成绕圈行驶。

自动运行接受普通mission或目的地停车后，Dispatcher用既有`stopKey(run)`发布零速限制，保持到下一条行车任务成功安装；
普通`Stop(ticks)`同样保持停车，而不是只倒计时。限制跨停留、等待时刻、换向和非行车动作保留；
新GoTo/StoreAtDepot安装成功后解除，失败与controller交接沿用既有清理/停车路径。不新增状态字段；正常转换只在入口设置，
存车完成仍等待台账/资源条件时允许重设同键零速值，不创建新限制或重启动作，不重新扫描路线或修改授权。
停车曲线在容差内仍可能许可接近实际目标；不能依赖“已进入容差”隐式保持零速。

`StoreAtDepot` 的物理移动必须调用与 GoTo/GoVia 相同的 `startMovementPlan` 安装入口。存车分支只负责先取得
精确的 `DepotStorageReservation`，并在公共入口成功返回其 `MissionRuntime` 后记录
`storageReservation/storageRuntime`；不能另行调用 `submitMissionForRun`、重复安装 `currentRuntime`、
重复发布初始授权，或自行拼一套 StationCall 离站逻辑。这样终点仍有已停靠 StationCall 时，公共入口在同一控制器
命令批次提交新 Mission 与 `grantStationDeparture`，避免新 Mission revision 与尚未获准离站的旧 call revision 并存。
公共入口失败时，存车分支撤销刚取得的同一物理或 Depot 预约，保留已有 Stall/停车结果，不把失败解释为已开始存车。

状态显示也沿用普通生命周期：旧 StationCall 尚在离站时保留 `DEPARTING`；收到离站通知后，若当前 runtime 就是
`storageRuntime`，转为 `STORING` 并显示准确的存车点，而不是普通 `RUNNING`。这只区分存车运行的运营显示，
不新增第二套控制状态或授权管线。`TimetableStationCallDispatcherTest` 必须覆盖终到开门、显式关门、
`DEPARTING -> STORING`、最终完成，以及无 ATO Stall；全局回放还应确认旧 StationCall 被清除且 Mission revision 一致。

Control transfer：pending operation先cancel+confirm；`MANUAL_ROUTED`继续通过同一Basic admission申请前方资源，但人工controller不消费ATO constraint，因此Dispatcher用同一committed boundary执行既有越界保护。`FULL_MANUAL`不发new request，但已有pending handle继续progress到终态，既有claim仍由Basic按车尾释放。release后`REPLAN_REQUIRED`；stale topology或route-state mismatch也直接进入该状态，不在旧route上循环重试。

滚动申请窗口按当前发布速度计算，不再直接使用最高速度的感知距离；申请资格阈值仍保持稳定，避免减速时阈值后退。
窗口、分批和尾段规则由[滚动授权契约](operational-routing-and-authority.md#33-单一列车保护管线)唯一维护。
显式StationCall登记成功即提交目标，不设置目的站台零速限制，也不在调度循环提前返回等待站台独占。
`StationCallRegistry`只保存生命周期/编辑保护引用；车头、车身、道岔和信号资源仍由原Basic准入决定。
APPROACHING/DEPARTING可暂时显示准入拒绝原因，成功或已有许可可继续使用后恢复相应站台运动提示；
不能让旧PHYSICALLY_OCCUPIED消息一直残留，也不能因此擦掉开关门或停站任务诊断。

新inspection binding的首次Basic申请/推进/扩权先等待controller成功publication：必须已检查exact冻结route，且
实际perception horizon覆盖新声明的最大提前时间。Dispatcher只读取这一结果，不执行插件update。0信号限速不是拒绝
申请的条件，否则会形成“尚未授权所以红灯、红灯所以无法申请”的循环。`MANUAL_ROUTED`仍走既有显式Basic边界保护，
不等待已脱离列车的ATO产生新检查publication；本改动不新增手动控制器的信号管线。

普通停车点不结束grant。下一条同向mission保留旧frozen route物理前缀，从已记录运营终点精确复用已有后缀，只追加真正新增路径；完全容纳的任务不增加物理route revision或重复申请。ActiveRun的InfrastructureOwner、grant identity和Basic tail cursor保持不变。显式continuation准备只重新绑定原committed boundary，不能授予候选全长；详见[运营目标与授权路线分离](terminal-route-completion.md)。存车terminal可把仍tail-held的同一client/grant移交给下一forward service。折返在调用Core reverse前暂停该grant的tail release；物理反向发布后先立即用新tail到新head的body-only route rebase同一claim/grant并重建cursor和释放点。下一份反向mission复用预先规划的路线，但按已经算出的新head mission progress裁掉被车身覆盖的前缀，再作为普通后缀扩展；不重新寻路。停车目标与authority end分别决定：未被信号策略接管的终点按全局安全距离准备真实余量；具体信号source可提供完整区间剩余路径，但不能用扩大控制标量代替真实授权。transient rejection等待既有retry tick，重试时用当Tick的新observation stamp重新构造selection；pending只progress handle，禁止重提intent。

admission client还拥有该ATO中`simpleato:resource-admission`来源的单调restriction revision，因此外部持有的同一ATO连续执行多个service时，即使上一grant已经结束，也必须把已清空route state的client交给下一run复用；不得新建client并从revision 0重新发布。Dispatcher自己创建且随run释放的ATO不需要跨controller继承。

停车余量与完成检查的完整合同见[全局安全距离与真实完成检查](terminal-route-completion.md#当前实施目标全局安全距离与真实完成检查)。运营目标不随余量移动。完成和门操作等状态转换先核对实际发布车头与Basic真实授权；越界保留claim并走既有失败路径，不以任务进度的容差证明安全。

Checkpoint只保存logical clock/diagnostics；restore active runs→`recoveryRequiredRuns`且disable service，不重建live
claims/queues/tickets/controller。Applied dependency不是新增持久化状态：restore从持久化的timetable ID和当前已锁定定义重编译，
在其成功前不发布clock/recovery state。

## 项目内正确示例

Tick入口看到world Stall即不继续normal allocation：

```kotlin
@MainThread
fun tick(world: World) {
    dispatcherTick++
    val coordinator = world.basicWorldData.atoCoordinator
    if (coordinator.isStalled) {
        // cleanup terminal bookkeeping only; no new operational work
        return
    }
}
```

来源：`TimetableDispatcher.kt`（主体含cleanup后return）。该gate防止同一Tick首个invariant failure后其他列车仍切turnout。

## 禁止实现

- physics ticket前调用tick；
- terminal立即清tail-held resources；
- conflict直接Stall；
- door/action每Tick重start或仅queue即success；
- physical selection只看Depot ledger；
- transfer丢ticket/locks；
- checkpoint restore声称继续active execution。

## 必需测试与验证

- `TimetableDispatcherTest.kt`：clock/At/lateness/cycles、physical acquisition、route retry/authority/tail release、sameTick Stall、reverse/halt/manual/replan/storage。
- `TimetableStationCallDispatcherTest.kt`：door/dwell/berth/operation failure gates。
- `TimetableExtensionRegistryTest.kt` + Dispatcher action tests（SimpleATO）：condition unavailable/error、start once/ticket。
- `DispatcherSnapshotTest.kt`：dependency先恢复、active checkpoint→recovery required/disabled，以及失败时无partial clock。
- `TimetableDispatcherSharedAdmissionTest.kt`：production automatic/manual mode、pending、跨epoch retry/extension、ordinary-stop append、stored-run transfer和stale topology。
- 同一SharedAdmission测试覆盖停止自动发车后的pending/已授权分支：terminal但Basic尚未确认结束时，准确的rail/resource依赖仍阻止真实Basic编辑许可，替换/强制删除也不能清除它；确认结束后才解除保护，跨完整发车周期仍不自动启动。另覆盖dependency删除失败后的保护保留/停运、checkpoint待恢复记录在clock删除后继续保护并仅由显式清理解除。
  这里的准入返回由测试控制，静态资源source只提供一个中立身份，不能将其描述成真实物理车尾释放或SimpleSignals完整匹配测试；实际插件匹配/编辑组合见SimpleSignals模块的`FixedBlockTimetableDependencyTest`与`ProtectionRegionTimetableDependencyTest`。

```bash
./gradlew :SimpleATO:test --tests '*TimetableDispatcherTest' \
  --tests '*TimetableStationCallDispatcherTest' --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*TimetableExtensionRegistryTest'
```

### Extension action 停止时的终态检查

停止 run 时先读取扩展 ticket 快照。SUCCEEDED、FAILED、CANCELLED 已是终态，直接清理该 run 的扩展动作引用；后续程序由 continuation cursor 保存，停止 run 不继续执行后续动作；只有 PENDING 调用 cancel 并要求确认 CANCELLED。快照读取失败、取消异常或未确认取消仍走既有失败路径，不能丢弃活动 ticket。回归覆盖 ticket 同步完成但 Dispatcher 尚未下一次 poll 的停止窗口。

### 强制重置拒绝前保持状态（CURRENT）

forceDiscardTimetable 在同一 world owner 内先检查全部 selected runs：只要仍有未 ended 的 admissionClient，就拒绝整批，保留所有 run/controller/clock；不能边遍历边丢弃后再因后一项拒绝。Bukkit reset 命令先调用成功的 discard，再释放手动会话、清 controller 和 reset coordinator，整个流程仍在一次帧间 owner 操作内。扫描 O(selected runs)，无新公开接口。TimetableDispatcherSharedAdmissionTest 以先启动 idle run、后启动有 grant 的 run 验证拒绝后两者 controller 身份与 statuses 全部不变。

### 固定编成事件后的 checkpoint：仅诊断恢复

包含固定 Split/Couple catalog 的时刻表不能在恢复时假定原始 source composition 仍存在：拆分后的实际
container 可能只剩一个单元，完成联挂后的原 container 也可能已经退役。此类 checkpoint 不重新验证原始
Check→事件脚本并伪造“已应用”路线证明；恢复原定义和原 run diagnostics 到独立的 recovery-only map，
`applicationState(id)` 明确返回 `RECOVERY_REQUIRED`，`recoveryRequiredTimetableDefinitions()` 提供诊断定义。
不创建 `AppliedTimetablePlan`、live run、controller、mission、grant 或队列。已保存的真实 Core/Depot
recovery fences 和单元归属仍是物理保护，不重放事件，也不构造历史 `TrainFrameSnapshot`。

结构性 catalog 的全部 duty 都禁用自动发车，清除 pending activation；手动 start、initial cycle schedule 和
fresh apply 在显式 recovery handling 前也拒绝。保留 disabled diagnostic clock，使同一 current checkpoint
边界可再次保存恢复要求；已禁用的 `stopFutureCycles` 不删除该恢复标记。管理员显式
`forceDiscardTimetable` 可移除逻辑诊断与 clock，之后状态为 `UNAPPLIED`，必须重新验证并 apply，
且此动作不解除 Core/Depot 物理 fence 或单元归属。不增加持久化字段或版本，也不新增旧数据兼容 reader。

无固定编成 catalog 的普通时刻表保持原有 dependency-first 恢复合同。此例外只意味着结构性恢复记录没有
可运行的 applied route proof，不允许用诊断定义或车身 fence 替代将来 fresh apply 的完整静态路线证明。

真实回归：`TimetableFormationDispatcherTest` 在实际调度拆分后保存 Core/operational 文档并加载新 world，
分别覆盖 HOLD 与延迟取车输出。原 source composition 已不存在仍可恢复诊断，全部 duty 禁用，无 live replay，
保存的物理位置/单元归属和恢复 fence 保留；再次保存恢复及显式逻辑清理也不能偷偷解除物理保护。

### 固定会合重联

两条 duty 必须停在同一 cycle 的同一固定 Couple 事件；等待另一方不会消费事件或重新提交动作。
Dispatcher 从 applied plan 的 `(event, approaching candidate, stationary candidate)` 取得独立接触可行性证明和
输出路线证明。首先保持两方零速并取得真实 body grant，安装 approaching 方的 Physical FormationContact
mission 与零 authority，等待实际 controller/inspection publication。之后重新向 Core 取得 fresh host proof，
与静态几何模板核对，再于同一 owner callback 封存完整单元变更并提交 contact admission。

Pending 只推进原 handle；READY 后同一个 admission client 消费接触限速/终点，控制器执行实际接近，
Core 碰撞与 Basic 精确参与者许可共同决定物理联挂。没有测试或 Dispatcher 直接调用 link。
完整 approaching body、桥接段和 stationary body 均保留编辑保护，HOLD 输出也不得省略接触路径。
物理输出使用与 Split 相同的 held-output 安装/台账/恢复流程；原 ATO 转给另一 duty 时仅移除原 duty 的
`timetable-line` cap，外部限速和安全约束保持。

封存前操作员停止可取消准备，保留两方零速、source grant 和编辑保护；封存后停止进入显式 recovery，
不能释放已经参与物理事件的资源。结构操作期间拒绝控制权转移。接触模板可来自 exact initial pair，
或独立静态证明覆盖的全部来源停车窗口组合；不能把 onward route 存在当作能联挂的证明。

### 定时分支与真实停车点选择

`TimetableProgramCursor` 是 immutable continuation：根步骤索引加完整 `then/else` 子路径构成地址。
每个子节点保留自己的绝对 Tick；条件只在父 Tick 到达时求值一次。异步 extension、movement 保存启动时
的 successor cursor，完成时恢复该值，不通过根索引重建或展平子动作。诊断区分 active address 与 next
address；mission 运行中 next 已指向后继，不能拿它给当前失败定位。

结构性证明同时绑定来源 duty 命名空间下的精确条件历史与实际已提交停车点历史；拆分输出继承历史，
重联输出合并两方历史，矛盾历史不能合并。Split 的输出 candidate 来自已验证的显式映射，不在运行时
拼接 lineage 猜测。每次消费结构路线前核对其目标、途经点、车站属性绑定及物理拓扑和实际车身。
GoVia 在一次实际输出解算后按已证明的距保留路线末端距离切成各 leg，不制造中间 TrainFrame 或在重复
经过的轨道上用第一个投影猜测途经点。

SELECT_STOP 是一次 movement 和 StationCall。候选必须已有完整 incoming/onward 证明，并以真实输出
长度解析 eligibility。一次只登记一个候选call引用；不同列车可登记同一berth，该引用不排斥其他调用且不是物理授权。独立零速
限制在真正 Granted 前保持，StationCall 此时不提交给控制器。Pending 只观察原 handle；AVAILABLE
候选在明确、安全且已结束的冲突拒绝后释放自己的 tentative key，再尝试下一候选。不得释放原 source
grant、已占用旧站台或仍在准备的候选。候选尚未提交时不把拒绝的 `retryAtOrBefore` 转成较短 grant，
否则会提前锁定本该尝试替代的候选。首个真实 grant 后锁定选择，后续按普通 rolling admission 处理。
短窗口的首个grant可能早于远端站台空闲，因此AVAILABLE_PRIORITY只保证首次物理承诺前遇真实拒绝时尝试替代；
不是远端空闲站台预订，也不能在已经进入所选路径后自动切换候选。

操作员在尚未提交时停止：没有 pending 可立即释放 tentative berth；存在 pending 则保留其身份到安全
settlement，再释放未到访的call引用，Basic grant 仍按自己的生命周期保留。unsafe settlement
不得清理后继续。`selectedBerthRef/selectedBerthCommitted` 描述选择；`berthRef/berthHeld` 描述实际
StationCall，二者可分别是下一个候选和仍被车尾占用的上一个站台。`berthHeld`保留既有诊断字段名，表示call引用未释放，不证明独占资源。

固定输出以条件、等待或 SELECT_STOP 开始时，先绑定实际完整车身的 body grant，安装独立零速限制，
退役已消费的 mission（保留同一 ATO 与外部约束），再对已经发布的实际输出执行普通 cursor。不能等待
Core structural fence 先释放才提交选中 movement：该 fence 的释放本就依赖选中路线的准入。执行 cursor
与提交 movement 本身不解除 fence；Basic 输出 grant 与 handoff 共同控制解除。未来 acquisition Tick
仍须等待；条件不能在发布前用原 source 的车长、car count 或位置求值。

Structural source preparation waits for a coherent stationary publication, not merely a zero final
speed. The final braking frame can still record head/tail traversal or renderable velocity. Keep the
existing formation stop cap and retry through the source-admission waiting path until these are all
zero; do not downgrade Basic's stationary-source requirement or submit an irreversible event early.
The real isolated coupling→movement→split factory regression exercises this boundary.

A branch selected against a published child can reverse a still-held body before choosing its first corridor. Dispatcher first suspends the exact Basic output grant, then marks that output's handoff as reversing. After the prepared Core reversal it records the actual new direction in the pending depot owner in the same serialized callback. The handoff remains paused until Basic accepts the exact reversed-body rebase; only then may ordinary cursor execution select departure and request movement. Failed ownership/rebase transitions retain holds and enter recovery. No condition is evaluated against the unsplit parent's length, and no sibling grant or owner is released by this reversal.

## 共享停站程序与两个执行入口（CURRENT）

同一时刻表每站一份作者定义，经 ScriptWorker 用当前运行线的静态计划上下文展开为 `StationStopPrograms`。ORIGIN 以 `BeginStationStop` 建立边界；编译期校验 ORIGIN 结构与角色，但不比较 destinationId 与当前 waypoint。运行期进入 ORIGIN 程序或 `runSegment` 时也不把计划始发点重新解释为车头必须精确命中的物理门禁：不同编组长度原地折返后车头位置会改变，而这些边界本身不产生运动。初次取车和显式 `Check` 仍校验真实有向路径点；后续 Mission 从换向后的实际车头和朝向执行路线投影、方向与资源准入，车门等操作继续执行自身的实际状态门禁。STOP/TERMINAL 随目标动作携带 stopPrograms。实际寻路、接近及停车继续由运行线负责，车站程序不执行到达运动。旧 arrivalSteps/arrivalProgram 执行链与 ARRIVAL 分支游标已删除；编辑／DSL／snapshot 只按当前模型做通用校验，不保留旧输入专项识别或回退执行。

`StationStopPrograms` 包含 stationId、callId、role、arrival、departurePreparation、preparationLead、plannedDepartureTick、destinationId、doorSide、minimumDwellTicks。时间为 timetable tick，1 day=24000 tick。正常停车确认后启动 arrival；ORIGIN 不执行 arrival，PASS 不建立停站程序。只有STOP使用minimum：从真实到站起算到关门完成的总预算，开门时间已包含，晚到仍适用。STATION使用已记录的BERTHED tick，不用下一tick创建程序的时间；DESTINATION使用实际检测停车tick。截止创建后固定，开门完成不重置。Dispatcher与timetable时间分别比较，不混合epoch。

默认准备时序等价于`max(plannedDeparture, actualArrival + minimum) - preparationLead`，且必须等arrival **主序列**完成，不等待arrival非阻塞子任务；最终发车仍等待全部后代。ORIGIN不加minimum；TERMINAL永不启动准备入口，缺省保持开门，亦无minimum。自定义程序可以提前关门，但不能提前发车；不新增门当前状态查询、prepared close或动作提交前复核。既有关门时长查询仅用于准备估计，真实操作ticket继续决定是否完成。查询超时从查询发出时计，不从早一tick的到站计。

`preparationLead` 是有界数字表达式：`{value:N}`、既有数字 operation，或 `{doorCloseTicks:true}`。缺省使用实际列车关门耗时，允许 ADD 等组合额外准备时间；不是按参考车辆或统一20tick冻结。`StationPreparationLead` 只解析数值时序，不开放动态动作、条件或一般JS。动态叶子通过现有 TrainOperationGateway 只读查询当前列车及实际门侧，同车并行动画、整列各车均取最长完成时间，绝不在查询时关门。每站查询一次，pending有界超时，失败/非法值保持停车，停止取消查询；普通固定表达式、无门停站、终到不查询。准备仍等待 arrival主分支，最终发车仍等真实操作ticket及所有既有门禁。编辑/编译/运行快照均写当前表达式字段；旧数值仅在离线工具中保留为字面量，不能把所有手填20猜成自动值。

`StationStopProgramRuntime` 对每个任务持有独立程序计数器、计时 gate、operation/extension ticket。普通动作等待真实完成；ASYNC 当 Tick 创建并推进子任务，父继续执行；AWAIT 等待指定任务及其全部后代。主序列必须 END；子任务不能逃逸到下一站。开放/关闭门与换向竞争同一物理操作所有权，不允许多个任务同时启动互斥物理操作。相对等待从任务实际执行 Tick 开始，绝对等待过期则立即通过；等待不中断停车保持。

测量观察`recordCommittedTicketCompletions(dispatcherTick)`只poll当前pending操作并记录首次Succeeded tick，不推进PC或修改doorsSecured。正常`tick`消费ticket后，END/自然结束沿用已完成工作时间，纯AWAIT取父任务与已完成子树的最大工作tick；后继真实动作、新ASYNC、相对等待与绝对等待按实际解释器执行tick计，不回溯。没有提交后观察证据时仍以正常消费tick计。每个新操作清除上个操作的观察值；操作失败/取消继续走原失败路径。`allTasksCompletedAtDispatcherTick`仅在原allTasksComplete为真时有效，聚合所有任务最新工作时间。Dispatcher的ready事件将该时间减cycleStartDispatcherTick转为时刻表时间，不拿事件派发时间冒充实际完成。仅独立测量订阅的run启用提交后观察；owner/帧间检查保留，世界循环和最终发车gate不变，不增加Basic API或精确关门状态机。

真正提交下一行车任务（含存车、接近联挂）前，公共 gate 要求两个适用入口及全部后代完成，时间和最小停站满足，门已确认关闭，后续物理授权已确认。主序列 END 不能直接开车或绕过这些 gate。到达主序列完成仅准许启动准备，不代表发车准备或子任务完成。终点有 departureTick 时它只门禁后续交路动作，无时刻则在到达及所有后代完成后立即交接；它不隐式关门。开门终到不能直接 MOVE、STORE 或接近联挂；交路须先明确关门。

换向使用原有 ATO 准备、Core 真实反向 publication、同 grant body rebase 及下一 Tick 确认，门关闭证据在准备过程保留；反向失败不能抹掉停车保持。停止/世界关闭枚举所有任务 ticket，只有 pending 调 cancel 并检查确认；终态 ticket 直接清理。关闭在 `world.executionOwner` 串行阶段进行，不能依赖 Bukkit 主线程 guard，不能丢弃未确认操作或授权。checkpoint 仍只恢复诊断与人工处理要求，不恢复活跃任务、ticket 或 grant。

任务树有界，创建与完整 join 按任务/步骤规模线性；每 Tick 仅推进活跃任务及其就绪动作，等待不阻塞宿主线程。计划分支在编译时折叠，运行时不重新扫描车长或车型。模型、worker、Java 编辑边界一致拒绝 station scope 的移动/编组/出入库及无效任务引用，失败保留停车并走既有错误路径。

例：到达【开门】【非阻塞：等待40tick后更新显示】【结束】；准备【关门】【结束】。开门主任务结束后，准备可在 due 时开始；实际发车仍等显示子任务及安全 gate。终到相同到达程序保持门开，后续交路若要移动须显式关门；不能因主任务已 END 就自动起车。

验证：`StationStopProgramRuntimeTest` 覆盖阻塞/立即派生、后代join、任务本地等待和取消，以及晚到、开门不重计、自定义提前关门仍等截止；`TimetableStationCallDispatcherTest` 覆盖真实BERTHED/非零epoch、起终点不加minimum、准备与主线程顺序、开门终到禁止后缀存车、换向及停止。`BrowserGeneratedStationProgramTest` 用当前前端生成器fixture通过真实Graal和snapshot/domain链路验证首尾显式0与中间STOP值。纯测试和隔离浏览器不等同于游戏客户端验收。持久化使用当前未发布边界的约定版本1，不为本次开发重构递增；编辑数据仅离线迁移，不新增任意旧开发reader。

## 显式换向与路线起点方向

ATO 不根据目标位置自行换向；未显式 Reverse 的身后目标必须沿当前朝向有可行路径，否则拒绝，不能反向种车或倒行来满足任务。`requiredNextStartDirection` 表达尚未消费的原路径点 route-key 方向，不是另一条轨道上的物理 head 局部 AB/BA。显式换向先使用已有 pending 方向，否则取当前 head 方向，再取反；连续换向必须保留同一锚点基准。GoTo/GoVia、选择停车点和 StoreAtDepot 的 movement plan 均传递该方向，缺 pending 时沿用实际 head 的既有默认。不得用新车头所在轨道的局部 enum 覆盖原锚点。

必需回归：LIVE/SIM 两模式下无显式换向拒绝身后不可达目标且不改车体/控制器；局部 AB 朝向相反的相接轨道上一次和两次原地换向保持占用；真实物理模拟跨接缝换向后存车完成。测试须检查实际 mission/frame/终点及错误，不以静态 source 中出现 reverse 字符串代替执行证据。

## 程序只读列车信息

程序的显示／扩展动作可携带独立 immutable trainInfo 绑定（TrainInfoField NAME/CAR_COUNT/DIRECTION），绑定与 literal values/call.parameters 互斥；TimetableExtensionCall 和 condition 不承载此字段。持久化 DTO 对应显式 enum/map，保持当前格式版本，capture/restore 不丢失绑定。

Dispatcher 在每条动作开始时共用 resolver 从实际 ActiveRun 绑定列车的一个最新 publishedFrame 读取 trainName、cars.size、reversed；方向输出 FORWARD/REVERSED。信息读取不再查询登记单元数；总车厢数直接来自该帧。解编交接与换向后重新读取，不复用编译时或旧列车缓存。扩展仍静态检查 provider 存在；仅显式动态参数延后到执行时 validate(resolved)，校验通过才 start，错误保留 provider 原因。literal 扩展的既有编译校验不变。程序显示动作作用于当前整列车，仍遵守 display JSON 限制和运营显示上下文；原生时刻表显示动作的显式槽位选择仍用于连续显示方案。
