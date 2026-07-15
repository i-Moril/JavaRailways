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

## 下一阶段：Phase C1 Mission 垂直切片

目标是让一个已加载时刻表能够选择一列明确的列车，提交一段不可变 Mission，并让 SimpleATO 停在指定 WayPoint。此阶段不包含车站、车门或插件 ATO Registry。

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
- Mission 当前只完成提交和激活契约，尚不生成运行时停车或 movement-authority 约束。

### C1.3 最小 Dispatcher

已完成底层 `RouteSectionAllocator`：按调用顺序无抢占地原子检查全部道岔资源、应用实际分支、返回 Confirmed Route Window，并支持幂等确认和车尾通过后的显式释放。冲突不会部分改变 Section 中的其他道岔。

剩余工作：

- 将当前只保存初始化快照的 `TimetableDispatcher` 扩展为明确的 service 运行状态存储。
- 第一版只选择 `TrainSelector.TrainId`，把 `GoTo`/`GoVia` 编译为 Via/Stop Mission point。
- 从时刻表生成 Planned Route，并根据列车已发布的 head/tail 位置动态触发 Route Section 分配。
- 时刻表时间只决定冲突列车的优先级；实际位置决定何时尝试分配。
- 原子锁定一个 Section 的全部道岔，把变更排入 physics epoch 边界，然后发布 Confirmed Route Window。
- 分配失败或仍在等待时，把 movement authority 保持在入口保护点；未确认 Section 不触发 Stall。
- 列车尾部完全越过 release point 后释放 Section 和相关资源。
- 增加 `/mr timetable start <id>`、`status <id>` 和 `stop <id>`。
- Dispatcher 不直接设置速度；SimpleATO 不发送道岔申请，只验证 Confirmed Route Window。
- 实际创建并挂载 SimpleATO，使 ATO 不再只存在于单元测试。

### C1 验收

- 旧 revision 被拒绝且不影响当前 Mission。
- 不存在的 WayPoint 和不可达 Route 在列车移动前失败。
- 列车沿预期 Route 运行并在目标 WayPoint 容差内停止。
- 未确认 Section 分配失败时，列车停在保护点且不触发 Stall。
- 同一 Section 的全部道岔要么一起分配成功，要么一个都不占用。
- 实际道岔与 Confirmed Route Window 矛盾时触发所属 World 的 ATO Stall。
- 列车尾部越过 release point 后释放 Section；车头刚通过时不得提前释放。
- 一个 World 的 Mission 失败不影响其他 World。

## Phase C2 Station 与停站状态

C1 完成后再引入 `Station`、`StationBerth` 和 `StationCall`：

- 第一版以列车头部作为停车对齐参考点；其他编组参考点等出现实际需求后再扩展。
- physics 侧报告 `APPROACHING`、`BERTHED`、`DEPARTED` 的不可变通知。
- `BERTHED` 必须同时满足位置容差和停止速度阈值，并且一次停站只报告一次。
- 停站后由 `DepartureHoldConstraint` 保持，不由 Station RailObject 直接操作列车。
- 暂不执行开关门，只验证停准、停留计时和发车授权。

## Phase C3 主线程操作网关

- 定义 `TrainOperationGateway`、语义操作和可确认的 operation ticket。
- Bukkit 层将开关门等操作映射到模型 action 或 `behavior.js`。
- 关门确认成功前不得解除 DepartureHold；失败或超时进入明确的人工处理状态。
- 增加完整的 `BERTHED → DWELLING → READY_TO_DEPART → DEPARTED` 状态机测试。

## Phase D 插件与控制权生命周期

- 建立带命名空间 ID 和可关闭 registration 的 ATO/RailObjectRule Registry。
- 时刻表持久化 `atoTypeId` 和版本化 options，不持久化 JVM 类名。
- 定义 Provider/ATO 插件卸载策略。
- 将人工驾驶接管接入 Dispatcher 状态，明确 `SUSPENDED`、`CANCELLED` 和恢复行为。
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
- 在 C1 中同时实现 Station、车门和第三方 ATO Registry。

## 已确认决策

- C1 采用“Dispatcher 拥有 Planned Route 和道岔分配、SimpleATO 只验证 Confirmed Route Window”的边界。
- SimpleATO 不发送道岔申请；Dispatcher 根据列车实时位置和制动距离主动触发 Route Section 分配。
- 时刻表时间用于冲突优先级，不直接决定安全相关的切换时刻。
- 未确认 Section 通过移动授权在保护点前停车；只有已确认分支被破坏才进入 World-local Stall。
- 第一版逻辑保留在 TimetableDispatcher 内部；未来可迁移到独立 RouteManager/Interlocking，而不改变 SimpleATO 边界。

## 后续阶段开工前需要确认

1. C2 第一版是否固定使用列车头部对齐 StationBerth。
2. C3 operation ticket 的超时是否直接进入人工处理状态。
3. 人工接管结束后默认取消车次，还是允许重新规划并恢复 Mission。
