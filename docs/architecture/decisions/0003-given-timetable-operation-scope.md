---
title: ADR-0003 给定时刻表运营范围
slug: /architecture/decisions/given-timetable-operation-scope
---

# ADR-0003：给定时刻表运营范围

- 状态：Accepted
- 日期：2026-07-14

## 背景

ATO Phase A—C3 和 D1 已能执行显式列车 Mission、动态 RouteSection、StationCall、车门和人工接管，但当前 timetable 时间主要参与冲突优先级，service 固定使用 TrainId，Station/Depot 也缺少统一创建和运营配置入口。

当前产品目标不是自动排图、容量优化或无人干预恢复，而是可靠执行管理员给定的周期时刻表。运营方需要车辆段随机配车、存取车、人工驾驶时的进路辅助，以及发生模型或运行时异常后的保守停机。

## 决策

1. timetable 以正整数 Minecraft 游戏日声明周期，一个逻辑日固定为 24000 Dispatcher Tick，不读取 world daylight time。
2. 管理员启动整张 timetable 时建立全部 service 共享的 epoch；`at(tick)` 是 cycle 内计划时间，动作不得提前，晚点后立即执行。
3. service 不固定绑定车辆编号。它通过 Depot 和车辆能力 selector 在每个 cycle 原子选择 AVAILABLE 列车，并使用稳定 seed 进行可复现随机分配。
4. 没有匹配列车或兼容存车位属于无法满足给定时刻表的运行时异常，触发 World-local Stall。
5. Destination 成为统一运营路由锚点，角色为普通目标、directed StationBerth 或 Depot StablingPoint；Station/Depot/RailObject 保持被动。
6. 管理员车辆段 reset 是与 ATO reset 分离的原子维护操作。容量不足时不部分提交，成功后仍保持 Stall，等待人工检查。
7. timetable 列车取得人工控制后默认进入 MANUAL_ROUTED：司机控制速度，Dispatcher 只推进给定时刻表的进路申请与车尾释放。钥匙 UI 可关闭辅助进入 FULL_MANUAL/SUSPENDED。
8. door 模型/action 错误、无匹配车辆、无存车位、保护点越界、经路偏离和内部一致性异常触发 World-local Stall；正常资源冲突、晚点和 loader failure 不 Stall。
9. 第三方 ATO Registry、自动排图、晚点优化和性能索引不属于当前运营闭环前置条件，继续延期。

## 后果

### 正面

- 时间、车辆、Destination、人工驾驶和异常策略形成一个可验收闭环；
- 时刻表不依赖固定车辆编号，车辆段可在周期之间重新分配列车；
- 所有安全相关资源仍保持原子分配和车尾释放；
- 故障处理简单保守，不需要先实现复杂恢复状态机；
- D2 插件 Registry 不阻塞当前产品目标。

### 代价

- 无可用列车也会停止当前 World 的全部自动运营，而不是只让单个 service 等待；
- MANUAL_ROUTED 第一版只监测保护点越界，不提供完整人工 ATP；
- 服务器重启后需要管理员重新建立 timetable epoch；
- Depot reset 是显式维护特权，必须绕过正常车尾释放并提供独立审计；
- 现有 WayPoint、RailDestination 和 StationBerth 需要兼容迁移层。

## 关联文档

- [给定时刻表运营闭环设计](../timetable-operation-design.md)
- [运营闭环验收清单](../timetable-operational-closure-checklist.md)
- [自动驾驶与运营协同设计](../automatic-train-operation.md)
- [ATO 实施路线图](../ato-roadmap.md)
