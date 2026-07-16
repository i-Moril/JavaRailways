---
title: ATO 实施路线图
slug: /architecture/ato-roadmap
---

# ATO 实施路线图

本文只维护尚未完成的工作。已经落地的契约和设计理由见 [自动驾驶与运营协同设计](./automatic-train-operation.md)。

## 已完成基线

- Phase A：线程归属事件总线、RailObject 稳定引用和生命周期、不可变快照、revisioned command queue。
- Phase B：owner-scoped 约束、固定减速度 Planner/Actuator、SimpleATO、线路限速、目标与线路末端停车、前车跟随、单一可选 Provider、道岔移动前校验。
- Phase B 运维：World-local Stall、结构化 Stall dump、`/mr ato status|dump|reset`；不自动恢复列车位置。
- Phase C1：加权 Planned Route、不可变 Mission、动态 Route Section 分配、movement authority、车尾释放、时刻表冲突优先级和显式列车 service 执行。
- Phase C2：显式 Station/Berth call、停准通知、dwell、发车授权、berth 所有权和严格 JS/TypeScript DSL。
- Phase C3：主线程 `TrainOperationGateway`、可确认车门 ticket、开关门状态机、超时人工处理和 Bukkit 模型适配。

## 已完成：Phase C1 Mission 垂直切片

已加载时刻表现在可以选择一列明确的列车，提交不可变 Mission，并让 SimpleATO 停在指定 WayPoint。此阶段不包含车站、车门或插件 ATO Registry。

### C1.1 经路值模型（已完成）

- `RoutePlanner` 已改为按弧长加权的 Dijkstra，而不是按 RailView 数量选择路线。
- 已覆盖不同长度分支、环路、方向限制和不可达目标。
- 已增加不可变 `PlannedRoute`、`RouteSection`、`TurnoutRequirement` 和 `RouteAllocationTiming`。
- `PlannedRouteCompiler` 会重新解析稳定 RailView 引用、验证相邻段拓扑，并把连续道岔选择编译到原子 Section。
- 当前默认把一个 ExpandedRoute leg 作为单一 Section；调用方提供保护点距离后可切分为连续 Section。
- 分配触发距离使用 service braking distance、设置/确认时间、reaction time 和安全余量的保守公式。

### C1.2 Mission 契约（已完成）

- 已增加不可变 `AtoMissionRequest`、`AtoMission`、稳定 `WayPointRef`、Via/Stop point 和连续 PlannedRoute leg。
- `AtoMissionResolver` 在主线程完成目标解析和 Route 编译，失败结果区分 `TargetUnresolved` 与 `RouteUnavailable`。
- `SimpleATO.submitMission` 使用严格单调 revision，返回 `Accepted` 或 `StaleRevision`。
- 多个已接受 Mission 在 physics Tick 边界只激活最新 revision，并一次性清理旧 Mission owner 的约束。
- Mission Stop point 生成运行时停车约束；Dispatcher 发布按 Mission revision 隔离的 movement authority。

### C1.3 Dispatcher（已完成）

- `RouteSectionAllocator` 无抢占地原子检查全部道岔资源、应用实际分支并返回 Confirmed Route Window；冲突不会部分改变其他道岔。
- `TimetableDispatcher` 已建立等待、运行、停留、受控停车、完成和失败状态，并保持显式列车占用。
- 第一版只执行 `TrainSelector.TrainId`，要求 `Check` 给出初始 WayPoint；`GoTo`/`GoVia` 编译成 Via/Stop Mission point，完成后继续处理后续动作。
- Dispatcher 创建或复用 SimpleATO；未确认 Section 时 movement authority 终止于入口保护点，确认后才延伸。
- Dispatcher 使用 physics 发布的 head/tail `TrainSnapshot` 投影 Mission route progress，按制动距离、设置时间、reaction time 和安全余量动态触发后续 Section。
- 同 Tick 冲突申请按时刻表动作 Tick 和稳定 service id 排序；时间只决定优先级，不直接触发道岔。
- Confirmed Route Window 通过 command queue 在 physics Tick 边界发布；SimpleATO 持续验证仍占用的实际道岔。
- 只有车尾完全越过 release point 才释放 Section；stop/failure 只执行受控停车，不越权释放。
- 一个运行完成后可执行 dwell 和后续 `GoTo`/`GoVia` Mission，并报告 `COMPLETED`。
- Bukkit 每次 physics 发布后驱动 Dispatcher，并提供 `/mr timetable start|status|stop <id> [service]`。

### C1 验收（已完成）

- 旧 revision 被拒绝且不影响当前 Mission。
- 不存在的 WayPoint 和不可达 Route 在列车移动前失败。
- 列车沿预期 Route 运行并在目标 WayPoint 容差内停止。
- 未确认 Section 分配失败时，列车停在保护点且不触发 Stall。
- 同一 Section 的全部道岔要么一起分配成功，要么一个都不占用。
- 实际道岔与 Confirmed Route Window 矛盾时触发所属 World 的 ATO Stall。
- 列车尾部越过 release point 后释放 Section；车头刚通过时不得提前释放。
- 一个 World 的 Mission 失败不影响其他 World。

## 已完成：Phase C2 Station 与停站状态

完整验收项单独维护在 [Phase C2 Station 待验证项目](./station-validation-checklist.md)。实现分为四个可独立验收的切片：

### C2.1 Station 值模型（已完成）

- 引入 World-local `Station`、被动 `StationBerth`、稳定引用、`StationCall` 和 `DwellPlan`。
- `Station` 是运营聚合体，不强制拥有单一 RailLocation；一个 Station 可以包含多个 berth。
- StationCall 第一版必须显式指定 berth，不自动选台或改发。
- berth 保存接近方向、头部停车偏移与容差、可用长度、默认 dwell 和语义开门侧。

### C2.2 停站感知（已完成）

- 第一版以及可预见的后续版本统一以**列车头部**作为停车对齐参考点。
- physics 侧发布 `APPROACHING`、`BERTHED`、`DEPARTED` 的不可变、revisioned 通知。
- `BERTHED` 必须同时满足头部位置容差和停止速度阈值，并且一次 StationCall 只报告一次。
- 通知不持有 live World/Rail/Train，由 Dispatcher 在完整 physics epoch 后消费。

### C2.3 停留与发车授权（已完成）

- Dispatcher 执行 `APPROACHING → BERTHED → DWELLING → READY_TO_DEPART → DEPARTING → DEPARTED`。
- `BERTHED` 后由 `DepartureHoldConstraint` 保持；dwell 与计划发车条件满足且下一段经路确认后才解除。
- 同一 berth 无抢占地分配给一个活动 StationCall，并在列车尾部越过 release boundary 后释放。
- Station 和 StationBerth 保持被动，不直接操作列车、约束、车门或道岔。

### C2.4 Bukkit 与 DSL 接入（已完成）

- 为显式 station/berth call 增加 JS DSL、TypeScript 类型、严格 loader 校验和状态诊断。
- 暂不执行开关门、模型 action 或 `behavior.js`；这些属于 Phase C3。

## 已完成：Phase C3 主线程操作网关

完整验收项见 [Phase C3 TrainOperationGateway 验收清单](./train-operation-validation-checklist.md)。

- 已定义平台无关 `TrainOperationGateway`、语义操作、稳定 request 和可确认 operation ticket。
- Bukkit 层把开关门映射到模型 `behavior.js` 的 `door(side, scene)` 或模型 action，并等待声明的延迟效果到达最晚启动时刻。
- Dispatcher 已执行 `BERTHED → DOORS_OPENING → DWELLING → DOORS_CLOSING → READY_TO_DEPART`；关门确认和后续经路授权同时满足后才解除 DepartureHold。
- rejected、failed、cancelled、异常或 100 Tick 超时进入 `MANUAL_INTERVENTION`，保留 DepartureHold 和资源，不触发 ATO Stall，也不自动重试。

## Phase D 插件与控制权生命周期

### D1 人工接管与重新规划（已完成）

完整验收项见 [Phase D1 控制权转移与重新规划验收清单](./control-transfer-validation-checklist.md)。

- `TrainControlManager` 在安装人工 controller 前让活动车次进入 `SUSPENDED`，释放驾驶权后进入 `REPLAN_REQUIRED`。
- 接管取消 pending operation ticket 和活动 StationCall 流程，但 RouteSection/StationBerth 仍只按原车尾 release boundary 释放。
- 管理员必须在列车停止、人工 session 结束、旧资源清空后，从明确 WayPoint 执行 `/mr timetable replan`。
- replan 创建新 ATO 和新 Mission revision，目标保持为被中断 movement 的原目标；不自动恢复旧 Mission。

### 下一切片：D2 插件 Registry

- 建立带命名空间 ID 和可关闭 registration 的 ATO/RailObjectRule Registry。
- 时刻表持久化 `atoTypeId` 和版本化 options，不持久化 JVM 类名。
- 定义 Provider/ATO 插件卸载策略。
- 提供 Java/Kotlin 示例扩展后再讨论稳定 API。

## Phase E 索引与性能

只在 profiler 或负载测试证明当前扫描成为瓶颈后实施：

- 按 RailView 和弧长排序的静态 RailObject 索引；
- occupancy nearest-ahead 查询；
- perception/provider/planning 分阶段指标；
- 缓冲区复用和静态候选缓存。

## 暂不实施

- 坡度、jerk 或复杂 `BrakeModel` SPI；
- 全局 topology revision；
- ATO 自动故障恢复或自动移动列车复位；
- 在 RailObject 内直接执行列车、车门或道岔行为；
- 在 C2 中同时实现车门操作、自动选台和第三方 ATO Registry。

## 已确认决策

- C1 采用“Dispatcher 拥有 Planned Route 和道岔分配、SimpleATO 只验证 Confirmed Route Window”的边界。
- SimpleATO 不发送道岔申请；Dispatcher 根据列车实时位置和制动距离主动触发 Route Section 分配。
- 时刻表时间用于冲突优先级，不直接决定安全相关的切换时刻。
- 未确认 Section 通过移动授权在保护点前停车；只有已确认分支被破坏才进入 World-local Stall。
- 第一版逻辑保留在 TimetableDispatcher 内部；未来可迁移到独立 RouteManager/Interlocking，而不改变 SimpleATO 边界。
- C2 的 StationBerth 停车参考点长期固定为列车头部，不提前引入编组中心、指定车厢或驾驶室参考点。
- C2 的 StationCall 显式指定 berth；Station/RailObject 保持被动，车门操作留到 C3。

## 已确认的后续决策

- 人工接管结束后不取消整条车次，也不复用旧 Mission；管理员从明确 WayPoint 重新规划到被中断 movement 的原目标。
