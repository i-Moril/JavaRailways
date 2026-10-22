# SimpleATO station 与 berth 契约

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

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/station/Station.kt`。

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
val area = stoppingArea(key.berthRef)
areaOwners[area]?.let { current ->
    return StationBerthAllocationResult.Conflict(key.berthRef, current)
}
allocations[key] = key
allocatedAreas[key] = area
areaOwners[area] = key
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/station/StationBerthAllocator.kt`。它确保后到列车等待而不抢占已停靠列车。

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
./gradlew :SimpleATO:test --tests '*Station*Test'
./gradlew :SimpleATO:test --tests '*TimetableStationCallDispatcherTest'
```


## B1：物理停车区与长度marker（CURRENT foundation）

canonical `DestinationRole.StationBerthRole`增加`stoppingAreaId`（默认berthId）、
`minimumTrainLengthMeters`/`maximumTrainLengthMeters`（默认null，非空须finite正数且min<=max）；
`StationBerth`只投影这些值，不拥有第二份配置。`StationStoppingAreaRef(stationId, areaId)`表示共享的一列车逻辑容量。
例如同一站的短编组marker与长编组marker可共用platform-one，同时只允许一个allocation。
不同station的同名area互不占用；共享必须显式配置，不能按坐标接近或显示名称推断。

resolver在实际length已知时分别检查usableLength及marker min/max，边界包含等号；
marker域拒绝为`TRAIN_LENGTH_INELIGIBLE`，平台包络长度拒绝仍为`TRAIN_TOO_LONG`。
静态未知length不伪造车长；Dispatcher实际调度传Core当前车厢总长度。该筛选不取代Basic admission和完整物理包络。

allocator仍保持exact key→marker以及key→取得时area；先检查同一marker或area已有owner，再同时提交索引。
`release(key)`按取得时area精确释放，不能重新查询可能已经变更的marker映射；`isAllocated(area)`只读查询持有状态。
配置编辑通过Dispatcher的`hasStationStoppingAreaAllocation`检查和DestinationService预校验，见[destinations](destinations.md)。
因此marker改名/转区不能制造第二份容量；旧revision释放不得删除新owner。

这些索引操作为O(1)，解析canonical area查同world StationStore；修改和分配在main owner下，内部锁保证索引一致。
area busy为等待/Conflict，不preempt、不猜测最早释放时间；canonical配置在held期间拒绝变化。
`StationBerthAllocatorTest`覆盖共享/独立area、idempotency、陈旧key和取得时area释放，
`StationCallResolverTest`覆盖min/max边界，`TimetableStationCallDispatcherTest`覆盖真实持有期间配置拒绝。
动态可用站台选择、多个分支后继及变化后重联的实际车长复算仍需B3/B4，不能把本批marker筛选称为完整动态平台调度。

## B4：有限站台选择与单次逻辑分配

`StationStopSelection(stationId,candidates,policy)`防御复制1..64个唯一berth候选；每个
`StationStopCandidate(berthId,priority)`的priority非负。排序为priority、stable berthId，不能以当次占用顺序
改变同优先级结果。`FIXED_PREFERENCE`等待最高优先级的合格候选；`AVAILABLE_PRIORITY`可跳过逻辑容量冲突。
不合格候选与暂时繁忙不同：实际长度、方向、marker生命周期及保留路线检查沿用`StationCallResolver`，
不存在第二套按carCount猜长度的筛选器。

`StationStopSelector.select(selection,owner,missionRevision,resolutions)`要求每个候选有一个当前解析结果，
成功结果必须匹配准确serviceRun/call/revision/berth，再进入同一`StationBerthAllocator`。
`allocateChoice`在原lock内检查同call owner的既有选择，并最多提交一个新allocation；并发retry不能同时取得两个候选。
既有选择仍合格时返回原key；revision或候选不匹配返回`ExistingSelectionMismatch`，保留原hold，不自行释放。
返回`Selected(resolved,key)`、`Waiting(conflicts,rejections)`或`NoEligibleCandidate(rejections)`，诊断列表不可变。

此结果只是逻辑分配，不是Basic行车授权。调用方必须在解析前验证该实际输入状态的保留incoming/onward路线，
选定后仍使用真实准入与停车管线。准入未提交时的备选重试只能在pending已结束后释放准确的tentative key；
已提交/占用的站台与源站tail claim不得由此helper回收。所有候选繁忙要保留可观察等待，不能伪装成不合法配置。

`StationStopSelectionTest`使用真实Core车长与StationCallResolver，覆盖长度/方向、共享area、固定等待/可用优先、
无合格/全部忙、不可变候选、同owner重试、旧revision及同owner并发原子性。helper测试不代表Dispatcher动态选站
或Basic冲突退让已经联调通过；这些证据单独记录。

### 结构变化后的实际起点

`StationCallRequest.origin`使用已有`AtoMissionOrigin`。普通`WayPointRef`仍解析canonical waypoint；
`Physical`来源必须同时提供实际车长、已经解析的retained route及captured topology，禁止重新搜路。
物理origin只作为不可变rail/facing/AB坐标端点验证，不能注册为临时站点，也不能替换为名义停车标。
解析共用原有站台生命周期、长度、方向、offset及门侧校验。retained route仍须使用该origin身份和
该call的synthetic station target身份，并验证准确两端几何；上游从已证明的完整路线截取站台prefix。
`StationCallResolverTest`验证真实物理来源不搜路、不注册，缺少证据、错方向及坐标漂移明确拒绝。
