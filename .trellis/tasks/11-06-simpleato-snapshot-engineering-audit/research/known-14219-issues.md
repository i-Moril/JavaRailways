# 已确认：Tick 14219 全局模拟故障

基线：`b89b0d5d`，2026-11-06。此前已用复制的真实测试服基础设施、5 交路时刻表、5 车 S100 配置进行本地回放；真实 Core/Basic/SimpleATO/SimpleSignals，不替代资源授予逻辑。临时探针只观察请求、状态和帧。

## K01 / P1：ATO 发布读取旧物理帧

`Core/.../physics/tick/PhysicsCoordinator.kt:676-700` 先 publishPhysicsBuffers，再 notifyPhysicsStatePublished，最后 publishFrame。`SimpleATO/.../api/ato/AbstractATO.kt:198-204,227-235` 在通知中从 TrainSnapshot.capture 读取 train.publishedFrame，因此保存的是上一 epoch 的位置。其速度另从 train.speed 覆盖，形成不同来源字段。

证据：`/tmp/jr-delay-14219-diagnostic/replay-head-timing.log:1972`，Tick 14052 5 号车 ATO head 精确等于上一 Tick 的 Core head，不等于当前 head；相差约 0.742343m，不是浮点舍入。

## K02 / P1：调度器跨快照比较丢弃有效帧

`SimpleATO/.../timetable/TimetableDispatcher.kt:1423-1435,3739-3744` 从 ATO 副本拿位置，再用 `run.train.publishedFrame.takeIf { it.head == snapshot.head }` 取车体；不相等时不传帧。`inspection/EntryClearance.kt:19-23` 因此返回空车长。位置相等本身也不能证明 epoch 相同（停车时不同帧可相同）。当前列车已具有效车体数据，却被处理成缺失。

## K03 / P1：空车长扩大资源请求到全路线

`SimpleATO/.../inspection/InspectionRuntime.kt:648-663` 的 `end ?: routes.single().totalLengthMeters` 将无数据转换成整条路线请求。引入提交 `6911087b`，标题 `fix(signals): require full-train exit clearance before entry`。需求是保证整列驶出防护区域，不是申请整个 Mission。

`replay-length.log:1952-1954`：Tick 14052 5 号车请求到 3763.125728m，被 `simulation/new-timetable/1` 拒绝；真实车体长度 89.19999m，检查层 length=null。1 号车在葛西临海公园，5 号车仍未至越中岛。Tick 14219 5 号车距终点约 2971m，仍以约9.87m/s运动/制动，并非该行意味着已停车死锁。

正常滚动请求存在于 `TimetableDispatcher.kt:3435-3478`，但后续 floor adjustment 覆盖其边界。停车时旧/新 head 相同、车长恢复，窗口恢复，造成走停相关的请求差异。

## 全局停止与显示

`replay-head-timing.log:2015` 再次复现 `DelayExceeded(simulatedTicks=14219, observedDelayTicks=1219, delayedRun=new-timetable/3)`。触发者是 105P/3 号车，不是截图中的 108X/5 号车。105P 计划八丁堀到达12978，实际14190；14219开始计划13000的后续任务。入站前被5号车占用所阻挡，13254获得完整申请。正常冲突与错误扩大申请分别贡献多少延误尚未对照量化。

Basic 返回 typed blockers（OccupyingTrain），但 `TimetableDispatcher.kt:3705-3710` 只拼接 code/detail，页面看不到阻挡列车。

## 验证限制

临时日志/输入位于 `/tmp/jr-delay-14219-diagnostic/`、`/tmp/jr-berth-diagnosis-Iyu6jVBU/`；可能被清理，因此关键数值已摘录在本文。当前用户要求审查，不执行反事实修复，不声称上述修复足以消除所有延误。
