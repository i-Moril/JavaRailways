# SimpleATO depot 与 rolling stock 契约

## 适用场景与用途

触发：配置Depot/stabling point、登记RollingStock、take/store、reservation、tail release或Depot Reset。

用途：`SimpleATOWorldData.depots`的`DepotStore`原子维护logical ledger，但Dispatcher仍以live stopped train geometry作为physical selection authority；storage/reset在完整preflight后提交，避免persisted occupancy冒充真实列车位置。

## Owner、类型与单位

`DepotStore`是SimpleATO world-local main-thread owner，在一个lock内维护Depot/points/rollingStock/assignments/storage reservations。`RollingStockRecord`字段：stable train/home depot/model package IDs；tags/capabilities sets；`carCount>0`；`lengthMeters>0`；`maximumSpeedMetersPerSecond>0`。

State token：`AVAILABLE/RESERVED/IN_SERVICE`。`DepotTrainSelector.matches(record)`只匹配required tags/capabilities、min/max cars、minimum speed m/s；取车地点由实际`stablingPointRef.depotId`匹配selector depot，不能用home affiliation代替。

Depot/DepotStore/rolling-stock ledger、`DepotResetCoordinator`和`DepotResetDispatchParticipant`均由SimpleATO拥有。Basic只提供物理列车、资源与Stall协调能力，不保存或转发Depot状态。

```kotlin
fun allocate(
    selector: DepotTrainSelector,
    assignmentId: String,
    stableSeed: String
): DepotTrainAllocationResult
```

结果：`Allocated`、`NoMatchingTrain(depotId,selector,availableTrainCount)`、`AssignmentConflict`。来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/depot/Depot.kt`。

候选先按train ID稳定排序，再以SHA-256 stable seed选index。`reservePhysicallySelected`只更新已由Dispatcher物理选择的optional metadata；store不是physical availability authority。

## Acquisition/storage/reset矩阵

| 条件 | result | mutation |
| --- | --- | --- |
| selector无matching AVAILABLE | `NoMatchingTrain` | ledger不变 |
| assignment ID已用 | `AssignmentConflict` | 不另占point |
| physical train stopped且geometry/owner合法 | Dispatcher选择；可`reservePhysicallySelected` | metadata如存在→RESERVED |
| first mission confirmed | `confirmInService` | RESERVED→IN_SERVICE |
| train tail未进入outbound | `releaseDeparture=false/不调用` | original occupancy保持 |
| storage无compatible empty point | `NoCompatiblePoint` | 无partial reservation |
| full envelope进入且old route/berth清 | `completeStorage` | reservation→occupancy，stock→AVAILABLE原子 |
| `planReset`失败 | `Failure(reason)` | 零mutation |
| `applyReset`前state变化 | false | 不commit |

Depot reset只允许运营层已Stall，Core协调器仍须可用；不提供Core-STALLED恢复路径。完整reset在帧间owner作用域执行，所有depot/train/route/berth/placement/envelope preflight在第一次ledger mutation前。
先捕获全部列车的Core摆位快照，再暂存位置并检查选中列车之间及与未移动列车的实际包络重叠，最后一次`CoreWorld.publishOwnerChanges`提交。
发布前失败恢复全部prepared快照，包含当前`setLocation`失败的列车，保留原frame/world/mailbox及车体、转向架运动值；不能只恢复已经摆位成功的列表。
Core发布开始后失败保持Core STALLED并报告`TRAIN_PUBLICATION/PARTIALLY_COMMITTED`，不反向回滚已发布数据。摆位成功后才commitDispatcher/Depot；此后异常同样报告`PARTIALLY_COMMITTED`并保持运营Stall；成功也保持运营Stall等待admin reset。Core准备、发布和复杂度见[帧间维护摆位](../core/train-physics.md#帧间创建删除与维护摆位current第4批)。

## 项目内正确示例

Reset planning是纯full matching入口：

```kotlin
@MainThread
fun planReset(requestedDepotIds: Set<DepotId>? = null): DepotResetPlanningResult =
    synchronized(lock) { /* build deterministic matching without mutation */ }
```

来源：`Depot.kt`。它让preflight failure可断言所有RollingStock与point snapshots完全不变。

## 禁止实现

- 只看ledger occupancy就选train；
- 未校验location/speed/derail/controller/active run/envelope；
- head进入就release departure或complete storage；
- greedy first-fit reset；
- preflight中途写ledger；
- reset成功自动清Stall。

## 必需测试与验证

- `DepotStoreTest.kt`：filter/determinism、assignment conflict、storage atomic/no partial、full matching/reset no mutation、cancel exact restore。
- `TimetableDispatcherTest.kt`：unregistered physical train可选、first mission gate、tail departure、storage handoff/failure Stall。
- `DepotResetCoordinatorTest.kt`：Stall precondition、preflight no mutation、mid-placement rollback、cross-rail overlap、PARTIALLY_COMMITTED。

```bash
./gradlew :SimpleATO:test --tests '*DepotStoreTest' --tests '*DepotResetCoordinatorTest' \
  --tests '*TimetableDispatcherTest'
```


## B1：跨库与固定动车组单元账本（CURRENT foundation）

### 正常运行及权威边界

`DepotStore`与其固定单元账本共用一把锁；Core仍是物理车厢、顺序、朝向和结构发布的唯一owner。
列车从A出库后可以预约B的空点，完整包络及Dispatcher旧资源清空后，`completeStorage`一次将B预约变为占用，
清除本次assignment及其单元claims，再变为AVAILABLE。下一次从B取车按B实际储存位置筛选；home affiliation不变。
取消操作先比较完整预约token和当前state，再删除，不能在比较失败时先remove正确的assignment。

### 类型与接口

`FixedEmuUnit(id, homeDepotId, carIds, controlCompatibilityKey)`永久登记有序stable Core car IDs；
private data constructor与public Collection constructor防御复制为不可修改列表，禁止通过`copy`或Java List修改成员。
`FixedEmuUnitAssignment(unitId, trainId, assignmentId)`保存当前运营归属，不保存另一份物理编组。

```kotlin
registerFixedUnit(unit: FixedEmuUnit, physicalCarIds: List<String>)
fixedUnitsForFormation(physicalCarIds: List<String>): List<FixedEmuUnit>
validateFixedUnitSplit(physicalCarIds: List<String>, beforeCarId: String): Pair<List<FixedEmuUnit>, List<FixedEmuUnit>>
claimFixedFormation(trainId: String, physicalCarIds: List<String>, assignmentId: String): List<FixedEmuUnitAssignment>
reservePhysicallySelected(trainId: String, pointRef: StablingPointRef,
    selector: DepotTrainSelector, assignmentId: String, physicalCarIds: List<String>? = null): DepotTrainAllocation?
allocate(selector: DepotTrainSelector, assignmentId: String, stableSeed: String,
    physicalCarsByTrain: Map<String, List<String>> = emptyMap()): DepotTrainAllocationResult
```

Dispatcher提供当前Core car IDs。显式登记的编组必须具有AVAILABLE rolling-stock metadata；完整单元claim和metadata/point预约
在同一个锁内提交，不能将登记不完整或已有其他owner的失败当作“optional metadata缺失”继续运行。
没有任何登记成员的普通列车保留原有physical-only路径；只含某个单元一部分的列车明确拒绝。
确认出库与车尾释放出库点不释放单元claims；完成入库、合法取消和成功Reset才清除对应claims。

`RollingStockRecord.modelPackageId`可为null，表示聚合编组没有单一声明车型；车型谓词对此返回false，不能继承某个幸存源车型。
混合编组的tags/capabilities取保守交集、速度取最小值；分割继承当前聚合分类，不重建历史单元分类。持久化字段仍必需，null原样往返。

`/jr depot register-unit <train> <unitId> [controlFamily]`由Bukkit帧间命令边界读取完整Core cars、stock home；
未显式提供family时使用非空model package，二者均无值时在mutation前拒绝。
要求AVAILABLE、无assignment/controller、停止的已发布帧。声明的family不同则不能组成同一运营编组；family相同仍不是实际
动力/制动兼容证明，后续结构操作必须经过真实actuator/safety preflight。

### 内部机制、生命周期及复杂度

登记先拒绝unit ID或car ID重叠，再检查成员在提供的Core顺序中连续，允许整个单元反向。
编组解析逐个完整单元消费car序列；不完整、内部乱序、未知cars、不同声明family均拒绝。
claim前完整检查所有成员、train/assignment一对一归属与已有metadata assignment；全部通过才写入claims，失败零mutation。
分割只返回完整单元之间的合法边界，尚不执行Core split/couple。此检查不是结构发布成功的证明。

Reset默认及显式scope只选择在目标depot有实际stabling ref或storage reservation的列车；没有任何储存关联的在途列车不会
因home depot被选入。候选点仍用完整二分匹配，不改为greedy；提交验证scope成员和目标点仍相符，只清理选中列车的准确
占用/预约（必要时包含其scope外旧出库点），不清除无关列车资源。最后一个home depot点不能在仍有登记home stock/unit时移除。

unit解析/claim为车厢数与登记成员数的线性扫描；登记包含成员交集和连续窗口检查，restore按unit/physical formation检查，
最坏与unit数、全车厢数和unit长度乘积相关，不在每tick执行。Reset为候选边构建加增广路径匹配，最坏O(VE)。
无法证明完整成员或唯一owner时直接拒绝，不猜测编组或增加库存。

### 拒绝矩阵、恢复与测试

- 重叠cars、部分unit、内部split、声明family冲突或重复assignment：`IllegalArgumentException`，账本不变。
- 旧token/错误state取消：false，不删除真实预约；在途或recovery-required claims保留。
- 外库AVAILABLE/RESERVED可恢复；重复点、重复assignment、未知home/point、单位与stock归属不一致均在提交前拒绝。
- `restoreOperationalLedger`先预校验stock、unit definitions/claims及实际Core成员，再发布两种账本；不重建动作ticket或运行allocation。
  已登记且assigned的物理编组必须包含全部unit claims；claimed编组必须有对应stock行，防止把遗漏字段当作可用库存。

`CrossDepotStoreTest`覆盖A→B→B、取消失败无mutation、foreign roundtrip和physical Reset范围；
`FixedEmuUnitLedgerTest`覆盖防御复制、完整/反向成员、原子claim、内部split拒绝、真实depot claim生命周期及恢复遗漏；
`DepotResetCoordinatorTest`继续验证真实摆位/包络/发布和partial commit。不以这些单测替代跨库游戏验收或计划重联验收。

### 格式与剩余边界

runtime字段的默认值及版本规则见[persistence](persistence.md)。本批不增加任意旧开发格式loader。
错误做法是按home depot回收所有在途列车或把一次split当作新造资产；正确做法是按实际storage关联选择Reset，
并保留固定单元及cars身份。调度重联、解编发布、双方资源交接、所有输出assignment和循环库存证明仍属于后续批次。


### 结构事务的账本提交与独立 HOLD

`prepareFixedFormationChange`只接收`SOURCES_HELD`，保留原stock/unit owners。Core发布且Basic确认全部输出停车后，
`commitFixedFormationChange(...): FixedFormationChange`替换stock/unit owners，返回新的`OUTPUT_OWNERS_COMMITTED`不可变token，
保留未完成事务。旧token不可继续commit/finish；已恢复的事务不可自动commit/finish。
`finishFixedFormationChange(token, physicalFormations, physicalDirections, installedOutputOwners, pendingContinuingOwners=emptyList())`是协调器内部边界：
确认每个输出mission/disposition及grant已安装；pending集合只能包含准确的CONTINUE_DUTY输出。finish删除整组事件，
将HOLD及尚未出发的CONTINUE_DUTY分别保存为独立owner/membership/direction/disposition，允许已出发输出独立存车或再次结构操作。
`validatePendingFormationOutput(owner, physical, directions)`在Basic resume前返回准确的不可变token；同一串行owner回调成功resume后，
`releasePendingFormationOutput(token, physical, directions)`只删除该输出。两次检查均校验该输出实际成员/方向、metadata和完整单元claims，
不再校验旧整组。永久HOLD、过期token和恢复出的pending记录均拒绝自动释放；恢复时二者都重新施加独立Core停车保护。
该流程不转移Basic基础设施claim，也不凭兼容family证明实际动力兼容。

A continuing formation output may reverse while its structural fence remains held. Capture its
exact current pending-output token before reversal; immediately after the authorized Core reverse
in the same owner callback, replace only the persisted owner's `reversed` value. Train, assignment,
canonical car order, fixed-unit claims, stock/home metadata and other held outputs remain unchanged.
Reject stale/copied tokens, HOLD disposition, recovered output, duplicate physical membership or a
missing direction flip before mutation. The handoff skips only the reversing output until Basic
rebases its retained body grant, then validates/releases against the new exact owner. No new saved
format or automatic recovery replay is introduced.
