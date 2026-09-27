# Basic train operation 契约

## 适用场景与用途

触发：增加door/cab/model/announcement operation、实现`TrainOperationGateway/Ticket`、修改timeout/cancel/control-transfer gate或simulation operation。

用途：Basic发platform-independent semantic request；ticket必须表达真实effect completion而非queue insertion，failure时保留DepartureHold/berth并Stall。

## Owner、签名与identity

operation sealed variants：`OpenDoors(DoorSide!=NONE)`、`CloseDoors(DoorSide!=NONE)`、`SetCabLights(Boolean)`、`ExecuteModelAction(actionName)`、`Announce(messageId)`。

```kotlin
data class TrainOperationRequest(
    val requestId: String,
    val serviceRunId: String,
    val stationCallId: String,
    val trainId: String,
    val missionRevision: Long,
    val operation: TrainOperation
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/operation/TrainOperation.kt`。文本stable/trimmed/no-control；revision`>=0`。

`TrainOperationGateway.execute(request): Accepted(ticket)|Rejected(reason)`在main/simulation context。`TrainOperationTicket`公开原request，`snapshot(): TrainOperationTicketSnapshot`、`cancel()`；state token `PENDING/SUCCEEDED/FAILED/CANCELLED`。snapshot含`ticketId/requestId/state/message?`，只有FAILED可带nonblank message。

## Ticket与Dispatcher矩阵

| adapter/ticket条件 | Dispatcher结果 | safety state |
| --- | --- | --- |
| Rejected | operation failure并world Stall | hold/berth保持 |
| Accepted但`ticket.request != input` | identity violation Stall | 不继续 |
| snapshot ticket/request identity变化 | identity violation Stall | 不继续 |
| PENDING未超时 | 保持wait state | hold保持 |
| SUCCEEDED | effect按adapter定义完成 | 才进入下一transition |
| FAILED/timeout/unexpected CANCELLED | world Stall | 不grant departure |
| `cancel()`重复 | 幂等 | 不得后来变SUCCEEDED |
| control transfer有pending ticket | cancel并inspect确认 | 无法确认则拒绝transfer |

Bukkit adapter可从simulation main发起，再切Folia region-owned model action；atomic state发布completion。Basic不得感知scheduler。Simulation gateway按logical ticks完成且不执行platform/render side effect；无duration implementation明确Rejected。

## 项目内正确示例

Simulation ticket使cancel阻止后续success：

```kotlin
override fun cancel() {
    if (pending.compareAndSet(true, false)) cancelled.set(true)
}
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/SimulationTrainOperationGateway.kt`。`snapshot`先检查cancelled，因此后续tick不能改为SUCCEEDED。

## 禁止实现

- `execute`返回success只表示已queue；
- ticket snapshot改request/ticket ID；
- timeout/failure后releasehold或berth；
- transfer直接丢pending ticket；
- Basic调用Bukkit model/entity；
- simulation执行真实renderer/model action。

## 必需测试与验证

- `TimetableStationCallDispatcherTest.kt`：open success后才dwell、close success+route后才departure、failed/timeout/cancel/identity mismatch保留hold并Stall。
- `SimulationTrainOperationGatewayTest.kt`：duration、zero duration、reject missing、cancel idempotent/no success。
- `BukkitTrainOperationGatewayTest.kt`：region dispatch、completion定义、atomic cancel race、request identity。

```bash
./gradlew :SimpleATO:test --tests '*TimetableStationCallDispatcherTest' --tests '*SimulationTrainOperationGatewayTest'
./gradlew :BukkitPlugin:test --tests '*BukkitTrainOperationGatewayTest'
```
