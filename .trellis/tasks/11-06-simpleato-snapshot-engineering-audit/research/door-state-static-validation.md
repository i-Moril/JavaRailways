# Tick18402车门状态静态验证

## 已确认因果

- 原完整输入`/tmp/jr-berth-diagnosis-Iyu6jVBU/full.js`：106P在kasaipark终到，arrival包含OPEN_DOORS；之后Tick18400独立任务只有REVERSE；115N始发`doorSide=NONE`。
- `StationStopProgramRuntime`对TERMINAL明确不启动departurePreparation，因此106P JSON中虽有CLOSE_DOORS，该动作不会执行。
- 成功开门把`ActiveRun.stationDoorsSecured`设为false；NONE只跳过本次门动作。运行期`beginStopProgram`因此在Tick18402以`doorless stop cannot clear previously open-door safety evidence`失败。该fail-safe必须保留。
- F9实施前，`TimetableValidator.validate`对station action只校验结构/引用，没有沿确定程序传播车门状态；当时原输入返回success=true，这是本轮已修复的静态验证缺口。

## 最小实现边界

- owner：SimpleATO TimetableValidator；复用现有`TimetableServiceTraversal`、完整step地址和有限分支证明。
- 状态：UNKNOWN、SECURED、OPEN。只有实际执行的OPEN/CLOSE改变事实；TERMINAL忽略departurePreparation。无门停站在输入非OPEN时按runtime的`doorless=true`记为SECURED；有门程序则从UNKNOWN开始折叠，不误用进站前状态证明程序结束时已关门。
- 独立`OpenDoors`在显式给出LEFT/RIGHT/BOTH时证明OPEN。`doorSideOverride=null`会在运行期继承`confirmedDoorStop`，可能解析为NONE并直接跳过：已知OPEN因此仍为OPEN，其他输入则保守降为UNKNOWN。
- 已知OPEN进入doorless停站、移动或其他现有运行期要求关门的动作时输出可定位错误；至少覆盖真实`terminal open -> reverse -> origin NONE`，错误定位在origin NONE而非Reverse。开门换向后继续有门始发是合法模式。UNKNOWN不报成确定错误。
- 错误应告诉用户在哪个service/step之前插入显式CLOSE_DOORS，而不是等运行期Stall。
- 不自动改作者时刻表，不执行终到departurePreparation，不删除运行期检查，不增加持久化字段或兼容路径。

## 必需回归

1. 真实简化模式静态失败，消息含service/step、此前开门和需要CLOSE_DOORS。
2. origin NONE前显式CLOSE_DOORS后通过，不限定它位于Reverse之前还是之后。
3. 初始UNKNOWN的合法NONE停站不误报；terminal OPEN→Reverse→有门ORIGIN、普通STOP/ORIGIN门程序和现有validator测试保持通过。
4. 终到departurePreparation里的CLOSE_DOORS不能错误证明已关门。
5. `SelectStop`使用既有候选展开传递同一份门事实和`stopPrograms`；运行时`Conditional`本就在进入service有限证明前被拒绝，不为它额外建第二套门遍历。

## 实施与聚焦验证（2026-11-07）

- `DoorProof`直接并入既有`validateService`的`State/process/walk`，共用候选展开、4096工作量上限和continuation memo；已删除独立的`validateDoorState/DoorValidationState/sequence`。错误沿用`[timetable/service] step#...`地址，确定冲突后把门事实降为UNKNOWN，避免后续动作重复刷同一根因。
- 有效门侧与Dispatcher保持一致：`StationStopPrograms.doorSide` 优先，仅在其为 null 时才回退调用点的 side。`BeginStationStop`的回退值来自目标`StationBerthRole`；`DestinationOptions`先使用步骤覆盖、再回退目标站台；`CallStation`先使用步骤覆盖、再回退解析站台。此优先级直接对应 `TimetableDispatcher.beginStopProgram` 的 `programs.doorSide ?: side`。
- 顶层`Reverse`按实际运行语义保留OPEN且不拒绝；真实简化链在后续`BeginStationStop`的有效`DoorSide.NONE`处给出一次错误。TERMINAL只应用arrival，故其departurePreparation中的CLOSE_DOORS没有错误地证明安全。
- 测试覆盖跨`runSegments`的106P终到开门→Reverse→115N无门始发、显式CLOSE只消除该门错误、初始UNKNOWN+NONE合法，以及程序`doorSide`覆盖步骤/站台侧的优先级。`SelectStop`在候选展开前只消费一次进入时的OPEN冲突，然后让所有候选继承UNKNOWN；其候选`CallStation`传入`action.stopPrograms`，与Dispatcher生成`StationCallSpec`的语义一致。
- 聚焦命令：`./gradlew :SimpleATO:test --tests '*TimetableValidatorTest' --tests '*TimetableStationCallDispatcherTest' --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle`；最终独立强制重跑结果为19+56项通过、0失败、0错误、0跳过。完整`:SimpleATO:test`独立强制重跑809项全部通过。

## 测试分层调整

- 确定OPEN后直接移动/存车现在由`TimetableValidator`在作者边界拒绝；Dispatcher运行期门禁仍必须独立保留并测试UNKNOWN输入。
- ordinary door的运行期测试使用已确认站台LEFT与`OpenDoors(null)`：静态只能得到UNKNOWN，Dispatcher则会在运行期解析为真实LEFT开门，因此仍分别验证movement gate与错配ticket取消。
- terminal OPEN→Store的原作者链保留静态拒绝断言；运行期Store兜底另用实际到达后继承LEFT的`OpenDoors(null)`建立OPEN状态，防止测试退化成只测validator。

## 原始输入验收

`/tmp/jr-authority-fixed-mcUBF5/replay.log` 显示原始完整输入在模拟前得到
`VALIDATION success=false`，错误精确为 `[new-timetable/4] step#29 ... DoorSide.NONE ... CLOSE_DOORS`。
脚本随后的JavaExec非零是因为其对validation success执行`check`，是本次预期的静态拒绝，不是模拟运行期失败。
