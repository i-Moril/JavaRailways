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

Bukkit adapter可从simulation main发起，再切Folia region-owned model action；atomic state发布completion。Basic不得感知scheduler。SIM gateway使用同一逐车JS/动作逻辑，以逻辑时钟推进声明effects，不执行platform/render side effect；缺少实际行为输入在准备或操作边界明确失败。不得用配置最大时长替代真实执行。具体逐车捕获、方向与生命周期归[Bukkit模拟行为](../../bukkit-plugin/backend/simulation-car-behavior.md)。

## 项目内正确示例

LIVE/SIM gateway共用已有ticket的原子PENDING终态转换，取消不能被迟到完成翻为SUCCEEDED：

```kotlin
status.updateAndGet { current ->
    if (current.state == TrainOperationTicketState.PENDING)
        current.copy(state = nextState, message = message)
    else current
}
```

来源：BukkitPlugin内部`operation/ModelOperationTicket.kt`，两种效果adapter共同消费，Basic公共request/result/state不变。门/模型动画操作成功必须对应本次固定车厢集合全部已接受动画播放完成；声音仍在发出时结束其effect。可见投影需确认最终frame提交，隐藏/模拟继续真实逻辑播放时钟，不能因无实体立即成功。SIM真实行为owner由目标world资源栈关闭，不能只删完成回调。

## 禁止实现

- `execute`返回success只表示已queue；
- ticket snapshot改request/ticket ID；
- timeout/failure后releasehold或berth；
- transfer直接丢pending ticket；
- Basic调用Bukkit model/entity；
- simulation执行真实renderer/model action。

## 必需测试与验证

- `TimetableStationCallDispatcherTest.kt`：open success后才dwell、close success+route后才departure、failed/timeout/cancel/identity mismatch保留hold并Stall。
- Bukkit内部 `SimulationTrainOperationGatewayTest.kt`：真实JS持久状态/独立目标、方向/换向、多延迟全部启动、取消、错误、car destroy及结构变化；原SimpleATO duration-only gateway与tests已删除。
- `BukkitTrainOperationGatewayTest.kt`：region dispatch、completion定义、atomic cancel race、request identity。

```bash
./gradlew :SimpleATO:test --tests '*TimetableStationCallDispatcherTest'
./gradlew :BukkitPlugin:test --tests '*SimulationTrainOperationGatewayTest'
./gradlew :BukkitPlugin:test --tests '*BukkitTrainOperationGatewayTest'
```

## 只读关门耗时

TrainOperationGateway 保留execute的SAM接口，并提供 estimateDoorCloseDurationTicks(trainId,side):CompletableFuture<Long>。它在simulation边界捕获当前实际车厢及相对门侧，平台owner只读同一门动作计划/动画时间数据；并行各车取最大值，启动延迟和播放时长分别按20tick/s向上取整再相加，避免把0.06秒延迟加0.06秒播放误估为3tick（实际需4tick）。NONE为0。缺失配置/计划/动画明确失败，不能猜测20tick；查询不接受/播放任何效果，不改变persistent JS状态。时刻表消费者负责有界等待和取消；耗时只决定提前何时准备，不能代替实际关门ticket。
