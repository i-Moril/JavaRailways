# SimpleATO Dispatcher runtime 契约

## 适用场景与用途

触发：修改Timetable clock/run states、train acquisition、route/berth/depot operation、door/reverse/halt/extension、control transfer或checkpoint recovery。

用途：每 world Dispatcher 在 successful physics publication 后推进确定性 state；每个 transition 等待对应 resource/ticket/publication，不因 terminal state 过早删 runtime 或释放 tail-held 资源。

## Owner、入口与state

`SimpleATOWorldData.dispatcher` 是每个 world 的唯一可写 owner。`TimetableDispatcher` 拥有 mission revision、clocks/runs、每run admission client、berth allocator、operation/extension tickets、storage reservation、recovery checkpoints。Basic 不保留 dispatcher 字段或转发 accessor。Dispatcher构造时必须提供非空`TimetableRouteAdmissionContextProvider`和world-local `TrainRestrictionSnapshotProvider`；Bukkit production每Tick只取一次coherent observation/context并使用认证的world-local `RouteResourceAdmissionService`，隔离仿真组合同一个Basic runtime。每次建立或延长admission route时，Dispatcher低频编译inspection声明，把冻结route与Mission progress的坐标映射绑定给controller；逐physics epoch的inspection激活/dirty消费、snapshot同步和constraint replacement由controller在规划运动前完成，不等待下一次Dispatcher tick。无coherent observation时保持fail-closed，不存在legacy allocator fallback。入口：

```kotlin
fun beginTimetable(world: World, timetableId: String): TimetableEpochStartResult
fun scheduleInitialCycle(timetableId: String): Boolean
fun startService(world: World, timetableId: String, serviceId: String): TimetableServiceStartResult
@MainThread fun tick(world: World)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt`。

`beginTimetable`先validation和blocking-run check，然后把validation已经得到的全部物理route交给Basic编译，并通过
world-local `InfrastructureDependencyService`原子安装完整rail/node/resource dependency；只有安装成功后才执行atomic initial
positions并发布shared epoch。dependency失败不发布clock；initial-position失败则删除新声明，replacement场景以更高的
runtime owner revision恢复旧声明。这个步骤不再次寻路。`scheduleInitialCycle`只能紧随begin，manual activation不自动启动。
scheduled/conditional cycles按period recurrence；`WAIT_UNTIL_TRUE`只保留该cycle，不阻塞其他service/clock；`SKIP_CYCLE`跳过。

每个timetable使用由其ID确定性生成的稳定owner ID；revision只是在当前world runtime内单调递增的mutation序号，不是
schema version，也不写入checkpoint。clock仍存在、checkpoint recovery run仍待管理员处理，或任一run尚未满足
`canBeReplaced`（包括Basic grant/berth/storage/停止条件）时，dependency必须保留。停止future cycles后只在上述状态全部
drain时删除。checkpoint restore先从当前timetable定义重新validation、编译并安装clock/run涉及的全部dependency，全部成功
后才发布恢复的clock与`recoveryRequiredRuns`；任一失败回滚本次已装声明并拒绝restore，不静默恢复成可运行状态。Bukkit
必须在Basic world registry attach后调用SimpleATO restore；隔离仿真使用同一registry语义。直接的ad-hoc/manual
`startService`不等同于“应用完整时刻表”，不在这条静态dependency生命周期内。

Run state token包含WAITING_MISSION/TIME/ROUTE、RUNNING、WAITING_BERTH、station door+dwell states、STORING/HALTING/REVERSING/WAITING_EXTENSION_ACTION、manual states、STOPPED/COMPLETED/FAILED。Private实现可重构，以下gates不可移除。

## Transition矩阵

| transition | 前置 | 成功后 |
| --- | --- | --- |
| start run | validator success；train物理可用 | install ATO/run；无partial failure |
| RUNNING | mission revision已physics publish | 可推进route progress |
| route conflict/physical lock | retry tick | `WAITING_ROUTE`+authority at protection，不Stall |
| invalid resource/controller identity | invariant failure | world Stall，本Tick停止后续runs |
| berth | exact non-preemptive allocation | conflict→WAITING_BERTH+zero demand |
| dwell | BERTHED publication + door-open SUCCEEDED | earliest departure clock开始 |
| depart | door-close SUCCEEDED + next route confirmed | grant departure；berth仍tail-held |
| halt | model `power-off` SUCCEEDED | terminal gate |
| reverse | ATO preparation revision published | main reverse；下一Tick确认 |
| extension action | start exactly once | 后续Ticks只inspect ticket |
| terminal replace/remove | Basic grant ended、berth/storage clear且ATO stopped | runtime才可replace |

Train acquisition以live stopped train location/speed/derail/controller/run/geometry为authority；RollingStock只补metadata。First mission confirmed后Depot reservation才IN_SERVICE。Storage full envelope+old resources clear才handoff。

Control transfer：pending operation先cancel+confirm；`MANUAL_ROUTED`继续通过同一Basic admission申请前方资源，但人工controller不消费ATO constraint，因此Dispatcher用同一committed boundary执行既有越界保护。`FULL_MANUAL`不发new request，但已有pending handle继续progress到终态，既有claim仍由Basic按车尾释放。release后`REPLAN_REQUIRED`；stale topology或route-state mismatch也直接进入该状态，不在旧route上循环重试。

滚动授权的高水位复用controller已有的前方感知距离，低水位按列车最大速度和设备准备时间保守计算，不能按当前速度逐Tick缩短。否则列车因当前授权边界减速时，续申请阈值会同时后退，最终可能停在边界前而不再申请。每次仍只扩展到一个感知窗口，不一次占用完整长路线。

新inspection binding的首次Basic申请/推进/扩权先等待controller成功publication：必须已检查exact冻结route，且
实际perception horizon覆盖新声明的最大提前时间。Dispatcher只读取这一结果，不执行插件update。0信号限速不是拒绝
申请的条件，否则会形成“尚未授权所以红灯、红灯所以无法申请”的循环。`MANUAL_ROUTED`仍走既有显式Basic边界保护，
不等待已脱离列车的ATO产生新检查publication；本改动不新增手动控制器的信号管线。

普通停车点不结束grant。下一条同向mission只把旧frozen route前缀追加到新route revision，ActiveRun的InfrastructureOwner、grant identity和Basic tail cursor保持不变。存车terminal可把仍tail-held的同一client/grant移交给下一forward service。折返在调用Core reverse前暂停该grant的tail release；物理反向发布后先立即用新tail到新head的body-only route rebase同一claim/grant并重建cursor和释放点。下一份反向mission复用预先规划的路线，但按已经算出的新head mission progress裁掉被车身覆盖的前缀，再作为普通后缀扩展；不重新寻路。停车目标与authority end由时刻表/信号配置分别决定，Dispatcher不自动增加overlap。transient rejection等待既有retry tick，重试时用当Tick的新observation stamp重新构造selection；pending只progress handle，禁止重提intent。

admission client还拥有该ATO中`simpleato:resource-admission`来源的单调restriction revision，因此外部持有的同一ATO连续执行多个service时，即使上一grant已经结束，也必须把已清空route state的client交给下一run复用；不得新建client并从revision 0重新发布。Dispatcher自己创建且随run释放的ATO不需要跨controller继承。

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

```bash
./gradlew :SimpleATO:test --tests '*TimetableDispatcherTest' \
  --tests '*TimetableStationCallDispatcherTest' --tests '*DispatcherSnapshotTest'
./gradlew :SimpleATO:test --tests '*TimetableExtensionRegistryTest'
```
