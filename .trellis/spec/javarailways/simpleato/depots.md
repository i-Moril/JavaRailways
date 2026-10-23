# SimpleATO depot 与 rolling stock 契约

## 适用场景与用途

触发：配置Depot/stabling point、登记RollingStock、take/store、reservation、tail release或Depot Reset。

用途：`SimpleATOWorldData.depots`的`DepotStore`原子维护logical ledger，但Dispatcher仍以live stopped train geometry作为physical selection authority；storage/reset在完整preflight后提交，避免persisted occupancy冒充真实列车位置。

## Owner、类型与单位

`DepotStore`是SimpleATO world-local main-thread owner，在一个lock内维护Depot/points/rollingStock/assignments/storage reservations。`RollingStockRecord`字段：stable train/home depot/model package IDs；tags/capabilities sets；`carCount>0`；`lengthMeters>0`；`maximumSpeedMetersPerSecond>0`。

State token：`AVAILABLE/RESERVED/IN_SERVICE`。`DepotTrainSelector`同时匹配home depot、required tags/capabilities、min/max cars、minimum speed m/s。

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

取消 reservation 必须在同一 lock 内先校验完整 token，再校验对应 runtime 的状态和 assignment 身份，最后删除 ledger。返回 false 时不得删除已有有效 reservation；storage cancellation 同样先匹配完整 token，再修改账本。回归覆盖同 assignment ID 的错误 token，以及已进入服务状态后拒绝取消仍能正常释放。

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
