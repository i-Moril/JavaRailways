# SimpleATO station 与 berth 契约

## 适用场景与用途

触发：修改Station/Berth/Call/Dwell、call resolver、call registry、door gate或berth release。

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
| train length > usable length | `TRAIN_TOO_LONG` | 不登记call |
| mission revision mismatch | `MISSION_REVISION_MISMATCH` | active call不变 |
| berth已有其他call | 允许登记并提交本站停车目标 | 不产生行车许可，继续真实滚动准入 |
| idempotent same key | 返回同一key | 不重复增加引用数 |
| head在target tolerance且speed exact 0 | ATO发布`BERTHED` notification | main才进入operation/dwell |
| door open/close未success | 保留hold/berth | 不grant departure |
| head离开但tail仍occupy | berth保持 | detector release后才free |

## 非独占调用登记（CURRENT）

`StationCallRegistry.register(StationBerthRegistrationKey): StationBerthRegistrationKey`只保存精确的
`StationBerthOwner(serviceRunId, trainId, callId)`、berth和missionRevision；它不是线路资源分配器。
同一站台允许多个不同call同时登记。Dispatcher解析并登记目标后立即提交StationCall，
ATO停准、门操作、dwell、任务与车尾释放仍保持原有规则。物理能否接近/进入唯一由Basic的滚动准入和统一约束决定。
删除旧独占allocator、Conflict/Busy结果、站台pending零速键和等待提交的调度分支，不保留兼容兜底。

实现使用精确key集合和berth引用计数，普通登记/释放/是否引用查询均为O(1)。同key重试幂等；
释放一个call不能删除另一个call的引用，旧revision也不能释放当前key。`registerChoice`在同一锁内按owner查找，
最多登记一个候选，已有选择错revision或不在候选中返回ExistingMismatch，不静默替换；该低频查询为O(活动call数)。
所有修改仍在world串行owner，内部同步保持登记与计数一致。无新持久化状态。

配置编辑仍通过Dispatcher的`hasStationBerthAllocation`检查“是否存在任何活动引用”。
`berthHeld`沿用原诊断字段名，只表示本call仍登记，不是独占站台或物理占用的证明。
正常车尾检测、取消/停止、pending选站清理和Depot Reset按各自既有安全边界释放准确key；
提交ATO失败先释放本次key再走已有失败路径，不删除其他call或Basic资源。

例如前车仍在目的站台，后车可以先沿空闲前缀行驶，但扩展请求一旦碰到前车车身或资源就拒绝；
后车在已授权边界前停车，前车真实车尾清出后再续申请。不能用登记成功替代物理授权，也不能因登记失败另造整段等待系统。

## 禁止实现

- station ID自动选择berth或用display/order猜direction；
- 用call登记替代Basic准入，或因别人登记了远端站台而把整段行程锁死；
- approximate nonzero speed判BERTHED；
- door ticket仅queued就开始dwell/grant departure；
- mission replacement或terminal立即release；
- head过点即release而不看occupied intervals/tail。

## 必需测试与验证

- `StationModelTest.kt`：identity/range、defensive copy、dwell overflow、store/RailObject lifecycle。
- `StationCallResolverTest.kt`：所有failure分类、direction、length、route/revision。
- `StationCallRuntimeTest.kt`：approach/berthed/departed monotonic notification。
- `StationCallRegistryTest.kt`：同站台多call、幂等/精确释放、最后一个引用释放前保持编辑保护。
- `RollingStationAdmissionTest.kt`：真实Core物理帧、HandleTrainActuator和Basic准入下，前车停站、后车启动/安全停车/车尾清出后恢复及过期错误清除。
- `TimetableStationCallDispatcherTest.kt`：door/dwell/route/tail gates与failure hold。

```bash
./gradlew :SimpleATO:test --tests '*Station*Test'
./gradlew :SimpleATO:test --tests '*TimetableStationCallDispatcherTest'
```


## 历史停车区设计与当前边界

先前本页将共享stoppingAreaId、marker长度上下界及单列车逻辑容量记为CURRENT，但当前StationBerth/registry源码不提供这些字段。
本轮不实现这些历史设计，也不通过另一层容量预约恢复已删除的站台互斥。当前长度筛选以真实车长及usableLength为准；
任何后续marker/物理停车区功能必须单独明确需求，不能将这里的历史描述当作已有API或持久化格式。

## 有限站台选择与单次登记

`StationStopSelection(stationId,candidates,policy)`防御复制1..64个唯一berth候选；每个
`StationStopCandidate(berthId,priority)`的priority非负。排序为priority、stable berthId，不能以当次占用顺序
改变同优先级结果。两种policy都先选择最高优先级合格候选；`FIXED_PREFERENCE`遇实际准入拒绝等待该候选，
`AVAILABLE_PRIORITY`在首次物理grant之前可因真实准入拒绝尝试下一个候选，不查看另一套逻辑容量锁。
不合格候选与暂时繁忙不同：实际长度、方向、marker生命周期及保留路线检查沿用`StationCallResolver`，
不存在第二套按carCount猜长度的筛选器。

`StationStopSelector.select(selection,owner,missionRevision,resolutions)`要求每个候选有一个当前解析结果，
成功结果必须匹配准确serviceRun/call/revision/berth，再进入同一`StationCallRegistry`。
`registerChoice`在原lock内检查同call owner的既有选择，并最多提交一个新登记；并发retry不能同时取得两个候选。
既有选择仍合格时返回原key；revision或候选不匹配返回`ExistingSelectionMismatch`，保留原hold，不自行释放。
返回`Selected(resolved,key)`、`ExistingSelectionMismatch(held)`或`NoEligibleCandidate(rejections)`，诊断列表不可变。

此结果只是调用登记，不是Basic行车授权。调用方必须在解析前验证该实际输入状态的保留incoming/onward路线，
选定后仍使用真实准入与停车管线。准入未提交时的备选重试只能在pending已结束后释放准确的tentative key；
已提交/占用的站台与源站tail claim不得由此helper回收。所有候选繁忙要保留可观察等待，不能伪装成不合法配置。

`StationStopSelectionTest`使用真实Core车长与StationCallResolver，覆盖长度/方向、其他call不阻塞、
无合格候选、不可变候选、同owner重试、旧revision及同owner并发原子性。helper测试不代表Dispatcher动态选站
或Basic冲突退让已经联调通过；这些证据单独记录。

### 结构变化后的实际起点

`StationCallRequest.origin`使用已有`AtoMissionOrigin`。普通`WayPointRef`仍解析canonical waypoint；
`Physical`来源必须同时提供实际车长、已经解析的retained route及captured topology，禁止重新搜路。
物理origin只作为不可变rail/facing/AB坐标端点验证，不能注册为临时站点，也不能替换为名义停车标。
解析共用原有站台生命周期、长度、方向、offset及门侧校验。retained route仍须使用该origin身份和
该call的synthetic station target身份，并验证准确两端几何；上游从已证明的完整路线截取站台prefix。
`StationCallResolverTest`验证真实物理来源不搜路、不注册，缺少证据、错方向及坐标漂移明确拒绝。
