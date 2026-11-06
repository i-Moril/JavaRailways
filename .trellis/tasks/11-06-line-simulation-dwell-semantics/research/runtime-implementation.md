# 停站执行层实施记录（2026-11-06）

## 最终边界

用户在实施中明确批准简化：自定义站点程序可以提前关门，但发车仍等待真实到站加最短停站及原有门/任务/授权条件。本文记录最终候选，不以早期研究中的严格关门完成边界覆盖用户决定。

- `StationStopProgramRuntime` 的最短停站截止时间只在构造时由真实到站计算一次，只有 STOP 使用 minimum；OPEN_DOORS 完成只更新门状态，不重置截止时间。
- 准备资格同时检查计划时刻减现有 lead、真实到站截止减同一 lead。两个检查保留各自原有时间坐标，等价于统一坐标下 `max(P, A + D) - lead`，没有第二倒计时或新时钟服务。
- 原有只读 preparation estimate、真实操作 ticket、任务 join、失败/取消和车门安全条件保留。原只读查询超时从发出查询的 tick 计，不从更早的实际到站 tick 计。
- Dispatcher 的 STATION 入口传入已经记录的 BERTHED tick；DESTINATION 入口使用其实际确认停车 tick；ORIGIN/TERMINAL 不增加全局 minimum。
- 有 stationPrograms 的入口不从站台基础设施 defaultDwell 恢复隐式 minimum。无 stationPrograms 的独立手动导航/API 计时路径未修改。

## 已删除的本轮额外机制

9 个 Basic/Bukkit 产品文件及 2 个播放/网关专用测试文件恢复为本任务前原文；没有改动其他 worker 文件。`PreparedTrainDoorClose`、当前状态查询、最长车厢复核、提交保护、相关 pending 状态与测试均移除；无新增门操作 API 或动画算法。

最终该 worker 的产品/测试差异只在：

1. `SimpleATO/.../timetable/StationStopProgramRuntime.kt`
2. `SimpleATO/.../timetable/TimetableDispatcher.kt`
3. `SimpleATO/.../timetable/StationStopProgramRuntimeTest.kt`
4. `SimpleATO/.../timetable/TimetableStationCallDispatcherTest.kt`

## 与真实运行线测时衔接

Dispatcher 新增可选 `observeStationProgramReady(id, cycleIndex, listener)`；瞬态事件包含 runId、cycleIndex、callId、role、arrivedTick、readyTick。每次停站在所有任务真实完成且车门关闭（TERMINAL 仅要求到达任务完成）时通知一次，在最终计划时刻/资源 gate 之前，不保存生产历史。订阅关闭及 run 生命周期沿既有 waypoint observer 清理。

既有 `TimetableWaypointProgress` 增加真实 movement 的 stepIndex/legIndex，避免重复站点只按名称聚合。运行线 adapter 由另一 worker 持有，未增加 line-specific Dispatcher 模式。

## 验证

最终简化候选命令：

```sh
./gradlew :SimpleATO:test --tests '*StationStopProgramRuntimeTest' --tests '*TimetableStationCallDispatcherTest' :BukkitPlugin:test --tests '*IsolatedTimetableSimulationFactoryTest.line measurement*' --tests '*BrowserGeneratedStationProgramTest' --offline --console=plain --max-workers=1 -I /tmp/jr-dwell-test-heap.gradle
```

XML 实际执行：StationStopProgramRuntimeTest 18/18；TimetableStationCallDispatcherTest 55/55；BrowserGeneratedStationProgramTest 1/1；均零失败、零跳过。覆盖实际到站与程序创建差一 tick、非零 timetable epoch、晚到、开门不重置、端点高 minimum 被排除、自定义早关门仍等原截止、原真实 ticket/失败/取消/资源门。

新的 BERTHED 回归使用生产 Dispatcher 和原有 KinematicTrainActuator/可控门 ticket fixture，验证真实事件记录与执行器时基衔接，不声称它单独覆盖 S100 门播放或完整动力学。真实车辆/门/信号联合验收由完整 isolated factory 测试承担。

首次默认测试堆在原 Dispatcher 测试中耗尽；临时 Gradle init 将 Test.maxHeapSize 设为 2g 后通过，仓库构建配置未修改。沙箱默认 Gradle 本地 socket 不可用，使用已授权的本地 socket 执行。Bukkit Kotlin 增量编译报告 DirtyData 缓存无法推导后自动完整编译，完整编译成功。

同轮新增真实信号 line integration 的失败是 fixture 安装时 `test:block-0` 缺少 finite static speed limit，已交给 line adapter owner 修正；不能把整条组合命令称为成功。此 worker 的最终74个定向回归通过，不代替全模块门禁、真实现场重算或部署验收。

`git diff --check` 通过。尚未提交、部署或迁移数据。Gradle 锁已释放给主会话。

## 真实完成事实与稍后观察（最终补充）

用户明确授权继续修复真实102P发现的误拒绝。离线trace精确显示：BERTHED3597、默认关门lead38、close提交3959，目标3997；3997的effect commit后原ticket已SUCCEEDED，但普通Dispatcher直到3998才poll并执行END。默认400Tick足够，不能用观察时刻3998拒绝。

最终只在3个已有产品文件内修复，没有恢复Basic/门网关/动画的新API：

- StationStopProgramRuntime记录pending ticket首次被实际观察为SUCCEEDED的dispatcherTick。观察方法不推进PC、不执行END/WAIT/新动作，不改doorsSecured、physicalOwner或发车gate。
- Dispatcher为已有readiness订阅的指定run提供窄记录hook；运行线afterTick在effect commits之后，使用既有withStructuralMutation进入合法owner/betweenFrames范围，仅采样、不drain队列、不改变tick阶段顺序。
- 下一次原解释器正常poll时仍重新检查ticket状态；END/自然子任务结束及纯AWAIT汇总真实工作时间。WaitDuration从实际执行时刻起算，WaitUntil解除、新Async和同步动作按真实执行tick更新；父任务较晚work与全部子孙任务仍取最大值。未知/未采样完成只能按实际普通poll时刻，不能推算减1。失败/取消不能被此前成功记录掩盖。
- Ready事件将完成事实转为既有timetable epoch；所有动作执行/放行仍发生原轮次。测量后额外的真实行车等待继续包含在下一段行程内。

最终测试命令同时覆盖Runtime、Dispatcher、Bukkit line integration，XML为24+56+2=82个测试，零失败、零跳过。新增覆盖：400接受/401拒绝、hook不放行/重复不覆盖、无证据不回推、失败/取消、非零epoch、WAIT/同步动作/新Async、纯join保留更晚父工作和子孙完成。Bukkit新fixture使用真实2Tick动画和相同operation gateway；将lead2改为lead1后的异常数字解析严格断言actual-target=1，不只是检查存在异常。

最终真实复制资产验收目录：`/tmp/jr-dwell-replay-20261106`。使用当前frontend rolldown生成请求与Reset源码，原S100五车、基础设施revision450097和真实SimpleSignals。firstDeparture2000、fixed/global400不变；测量到站3597/5562/7842/9484，发车2000/3997/5962/8242。`measurement-result.json`最终15:04:12生成；`reset-single-final.log`记录原1200阈值下Completed9513及四个整数到站严格相等的EXACT_COMPARE_SUCCESS。终到后真实开门任务到9513完成，不把这29Tick当作中间停站minimum。

保留所有此前日志。旧102P单独回放也在9513完成但旧计划终到8738，不能称旧排图准确。旧五交路全表仍在10928因hatchobori/2D的StationCall路线异常失败；不属于本次readiness误拒绝验收通过，不声称全局已全通过。完整模块门禁由主线程继续，未部署。

## 必要验收补测（2026-11-06 15:38 UTC）

本轮只扩展 `IsolatedTimetableSimulationFactoryTest`，未修改生产代码、tick阶段、门逻辑、容差或全局阈值；未新增生产测试hook。

- 原连续运行线fixture参数化为normal/reverse/blocked。反向场景从30m、BA原点唯一摆位，初始reversed=false，再执行prepareLineMeasurement插入的真实Reverse；普通回放观察到头尾坐标互换、占用区间集合不变，且换向发生在发车目标100之前。测量与普通交路的PASS/STOP/TERMINAL三个整数到站事件严格相等，不反向重种车辆。
- blocked场景继续使用真实SimpleSignals四灯包；测试专用PreparedSimulationFeature通过既有condition preparation获取隔离world，在真实Core registry持有[31m,80m]的exclusive claim，既有tick commit在physicsEpoch400精确释放。测量明确观察到Dispatcher的RESOURCE_CONFLICT、无route grant、车头保持30m、claim仍在。目标发车100不变，首个PASS到站比无阻塞对照至少晚300Tick，travelSeconds严格按实际到站减原目标发车计算。普通单交路回放安装同一claim/release条件，三个整数到站仍精确相等；不是伪造时间或mock整条安全链。
- profile测试证明缺assignment在调用resolver前明确拒绝；unknown profile使用受控resolver抛出异常，验证factory原样传播而不fallback，不声称此项独立覆盖真实catalog的查找实现。
- 原fixture继续验证中间0.1m、终点2m停车偏移及真实局部限速；原IsolatedLineSimulatorTest验证非零origin offset明确拒绝。额外将中间offset改为2m，实际执行因既有Mission接续问题失败，准确消息为`train simulation/line-test/duty is not facing the planned route from c@one`，测量不会返回成功结果。此为已知底层执行缺陷的fail-closed验证，不称任意中间偏移“不支持”，也未修复该缺陷。

最终命令：`./gradlew --offline -I /tmp/jr-rolling-tests.gradle :BukkitPlugin:test --tests '*IsolatedTimetableSimulationFactoryTest.line measurement*' --tests '*IsolatedTimetableSimulationFactoryTest.capture rejects missing profile*' --tests '*IsolatedLineSimulatorTest*' --max-workers=1 --console=plain`。

最终XML：Factory 5/5，IsolatedLineSimulatorTest 2/2，合计7，零失败/零跳过；时间15:38:07/15:38:26，日志`/tmp/jr-dwell-replay-20261106/focused-acceptance-verified.log`，BUILD SUCCESSFUL in27s。此前一次附加offset断言误以为错误文案包含Mission，实际已正确拒绝，修正测试匹配为准确文本后最终全过。`git diff --check`通过。Gradle锁已释放主会话；无提交、部署或远程变更。
