# Basic station 与 berth 契约

## 适用场景与用途

触发：修改Station/Berth/Call/Dwell、call resolver、berth allocator、door gate或berth release。

用途：每个call显式选择exact berth；停车、dwell、door completion与full-tail release各有独立gate，禁止按station猜berth或head离开即释放。

## Owner、value与单位

- `StationId`、`StationBerthRef(stationId,berthId)`稳定且禁止`/`/control char；RailObject namespace固定`station-berth`。
- `Station`至少1 berth、refs同station且unique、defensive copy；`defaultDwellTicks>=0`。
- `StationBerth`是passive RailObject：location、optional approach、stopping offset m、position tolerance `>0 m`、optional usable length `>0 m`、dwell ticks、release distance `>=0 m`、`DoorSide`。它不拥有train/constraint/turnout/platform behavior。
- berth与destination role允许`DoorSide.NONE`表达“停站但不执行开关门”；Dispatcher跳过door operation。只有表示实际动作的`TrainOperation.OpenDoors/CloseDoors`继续拒绝`NONE`。
- `StationStore`与同world `RailObjectStore`共同拥有aggregate与marker lifecycle。

Call签名：

```kotlin
data class StationCall(
    val callId: String,
    val missionRevision: Long,
    val stationId: StationId,
    val berthRef: StationBerthRef,
    val plannedArrivalTick: Long? = null,
    val dwellPlan: DwellPlan,
    val stoppingOffsetMetersBeforeOverride: Double? = null,
    val doorSideOverride: DoorSide? = null
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/station/Station.kt`。

`DwellPlan.earliestDepartureTick(berthedTick)=max(berthed+minimum,planned)`，overflow饱和`Long.MAX_VALUE`。

## Resolution/runtime矩阵

| 条件 | result/state | side effect |
| --- | --- | --- |
| origin/station/berth missing或REMOVED | typed `StationCallResolutionFailure` | 不提交ATO |
| wrong approach但无direction可达 | `BERTH_DIRECTION_UNAVAILABLE` | 与`ROUTE_UNAVAILABLE`区分 |
| train length > usable length | `TRAIN_TOO_LONG` | 不分配berth |
| mission revision mismatch | `MISSION_REVISION_MISMATCH` | active call不变 |
| berth已有owner | `Conflict`，run=`WAITING_BERTH` | zero demand、不Stall/不preempt |
| idempotent same key | `Confirmed` | 只有一个owner |
| head在target tolerance且speed exact 0 | ATO发布`BERTHED` notification | main才进入operation/dwell |
| door open/close未success | 保留hold/berth | 不grant departure |
| head离开但tail仍occupy | berth保持 | detector release后才free |

## 项目内正确示例

Berth allocator non-preemptive：

```kotlin
berthOwners[key.berthRef]?.let { current ->
    return StationBerthAllocationResult.Conflict(key.berthRef, current)
}
allocations[key] = key
berthOwners[key.berthRef] = key
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/station/StationBerthAllocator.kt`。它确保后到列车等待而不抢占已停靠列车。

## 禁止实现

- station ID自动选择berth或用display/order猜direction；
- preempt berth；
- approximate nonzero speed判BERTHED；
- door ticket仅queued就开始dwell/grant departure；
- mission replacement或terminal立即release；
- head过点即release而不看occupied intervals/tail。

## 必需测试与验证

- `StationModelTest.kt`：identity/range、defensive copy、dwell overflow、store/RailObject lifecycle。
- `StationCallResolverTest.kt`：所有failure分类、direction、length、route/revision。
- `StationCallRuntimeTest.kt`：approach/berthed/departed monotonic notification。
- `StationBerthAllocatorTest.kt`：non-preemptive/idempotent/release。
- `TimetableStationCallDispatcherTest.kt`：door/dwell/route/tail gates与failure hold。

```bash
./gradlew :Basic:test --tests '*Station*Test'
./gradlew :SimpleATO:test --tests '*TimetableStationCallDispatcherTest'
```
