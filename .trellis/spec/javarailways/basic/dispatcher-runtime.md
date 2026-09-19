# Basic Dispatcher runtime 契约

## 适用场景与用途

触发：修改Timetable clock/run states、train acquisition、route/berth/depot operation、door/reverse/halt/extension、control transfer或checkpoint recovery。

用途：每world main-thread Dispatcher在successful physics publication后推进确定性state；每个transition等待对应resource/ticket/publication，不因terminal state过早删runtime或释放tail-held资源。

## Owner、入口与state

`TimetableDispatcher`拥有mission revision、clocks/runs、world-bound route/berth allocators、operation/extension tickets、storage reservation、recovery checkpoints。入口：

```kotlin
fun beginTimetable(world: World, timetableId: String): TimetableEpochStartResult
fun scheduleInitialCycle(timetableId: String): Boolean
fun startService(world: World, timetableId: String, serviceId: String): TimetableServiceStartResult
@MainThread fun tick(world: World)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/timetable/TimetableDispatcher.kt`。

`beginTimetable`先validation、blocking-run check、atomic initial positions，再建立shared epoch；`scheduleInitialCycle`只能紧随begin，manual activation不自动启动。scheduled/conditional cycles按period recurrence；`WAIT_UNTIL_TRUE`只保留该cycle，不阻塞其他service/clock；`SKIP_CYCLE`跳过。

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
| terminal replace/remove | allocations/berth/storage clear且ATO stopped | runtime才可replace |

Train acquisition以live stopped train location/speed/derail/controller/run/geometry为authority；RollingStock只补metadata。First mission confirmed后Depot reservation才IN_SERVICE。Storage full envelope+old resources clear才handoff。

Control transfer：pending operation先cancel+confirm；`MANUAL_ROUTED`保留route assistance，`FULL_MANUAL`不取new route但tail-releaseold。release后`REPLAN_REQUIRED`；stopped、explicit origin、无old resources、owner released后新ATO/revision。

Checkpoint只保存logical clock/diagnostics；restore active runs→`recoveryRequiredRuns`且disable service，不重建locks/queues/tickets/controller。

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
- `TimetableExtensionRegistryTest.kt` + Dispatcher action tests：condition unavailable/error、start once/ticket。
- `DispatcherSnapshotTest.kt`：active checkpoint→recovery required/disabled。

```bash
./gradlew :Basic:test --tests '*TimetableDispatcherTest' --tests '*TimetableStationCallDispatcherTest' \
  --tests '*TimetableExtensionRegistryTest' --tests '*DispatcherSnapshotTest'
```
