---
title: Phase C3 TrainOperationGateway 验收清单
slug: /architecture/train-operation-validation-checklist
---

# Phase C3 TrainOperationGateway 验收清单

本清单固定车门操作、operation ticket 和发车保持的已实现边界。Phase C3 的操作协调只在平台主线程运行，不把 Bukkit 或可变列车模型对象带入 physics worker。

> Phase C3 当前把 operation failure/timeout 留在 `MANUAL_INTERVENTION`。给定时刻表运营闭环已决定把 door/model 故障升级为 World-local Stall；该变更以 [T6 验收项](./timetable-operational-closure-checklist.md) 为准，完成前本清单仍描述当前行为。

## C3-A 操作契约

- [x] **C3-A01** `TrainOperation` 使用平台无关的语义操作；StationCall 只发出 `OpenDoors` / `CloseDoors`，不引用模型动画名。
- [x] **C3-A02** 每个 request 携带稳定 request、service、call、train 和 Mission revision 身份。
- [x] **C3-A03** Gateway 显式返回 accepted ticket 或 rejected reason；ticket 只允许 `PENDING → SUCCEEDED|FAILED|CANCELLED`。
- [x] **C3-A04** Dispatcher 只在主线程执行和轮询 Gateway；异常被转换为运营失败，不进入 physics worker。
- [x] **C3-A05** stop/failure 会取消尚未完成的 ticket；取消后的迟到完成回调不能恢复成功。

## C3-B 停站状态机

- [x] **C3-B01** 有效开门侧执行 `BERTHED → DOORS_OPENING → DWELLING`；`NONE` 保持无车门兼容路径。
- [x] **C3-B02** minimum dwell 从开门确认后开始计算；计划发车 Tick 仍是下界。
- [x] **C3-B03** dwell 完成后执行 `DOORS_CLOSING → READY_TO_DEPART`。
- [x] **C3-B04** 只有关门 ticket 成功且后续经路确认后才解除 `DepartureHoldConstraint`。
- [x] **C3-B05** 每次 StationCall 最多发出一次开门和一次关门 request。
- [x] **C3-B06** berth 仍只在车尾越过 release boundary 后释放，operation 成败不强制释放资源。

## C3-C 失败策略

- [x] **C3-C01** rejected、failed、cancelled、ticket 身份错误或 Gateway 异常进入 `MANUAL_INTERVENTION`。
- [x] **C3-C02** pending ticket 超时后取消并进入 `MANUAL_INTERVENTION`；默认超时为 100 Dispatcher Tick。
- [x] **C3-C03** 人工处理状态保留 DepartureHold、berth 和已确认经路，不自动发车，也不触发 World-local ATO Stall。
- [x] **C3-C04** 管理员仍可用 timetable stop 请求受控停车；Phase C3 不实现自动恢复或静默重试。

## C3-D Bukkit 适配

- [x] **C3-D01** Bukkit World 创建时为 Dispatcher 注入 `BukkitTrainOperationGateway`。
- [x] **C3-D02** adapter 把语义左右侧映射到模型 `behavior.js` 的 `door(side, scene)`；无 behavior 时回退到同名 door action。
- [x] **C3-D03** `BOTH` 同时要求左右侧动作，任一缺失都拒绝 operation。
- [x] **C3-D04** ticket 在所有声明的延迟 action 到达最晚启动时刻后才确认，而不是在刚排队时确认。
- [x] **C3-D05** `/mr timetable status` 显示 operation ticket ID、状态和人工处理原因。

## 验收证据

- `TimetableStationCallDispatcherTest` 覆盖开门、dwell、关门、经路授权、完整发车、超时和关门失败。
- `BukkitTrainOperationGatewayTest` 覆盖语义映射、异步确认、取消后迟到回调和拒绝路径。
- 原有 C2 tests 持续覆盖 `DoorSide.NONE` 的无车门兼容状态机、berth 冲突和车尾释放。
- 完整 Gradle test、文档构建、模型配置校验和 TypeScript 检查必须持续通过。
