---
title: 给定时刻表运营闭环验收清单
slug: /architecture/timetable-operational-closure-checklist
---

# 给定时刻表运营闭环验收清单

本清单对应 [给定时刻表运营闭环设计](./timetable-operation-design.md)。只有源码、自动测试、Bukkit 适配和运维诊断同时具备证据后才能勾选。

## T1 周期时钟

- [x] **T1-01** DSL/TypeScript 支持正整数 `setPeriodDays(days)`，按 `days × 24000` 严格检查溢出。
- [x] **T1-02** 管理员启动整张 timetable 时原子建立全部 service 共享的 epoch。
- [x] **T1-03** `at(tick)` 不早于当前 cycle 的计划 Tick；晚点动作立即执行并报告 lateness。
- [x] **T1-04** StationCall 到达/发车 Tick 只有一个一致事实来源，早到保持、晚到执行完整 minimum dwell。
- [ ] **T1-05** period 到达后创建下一 cycle run，不抢占前一 cycle 的列车或资源。
- [x] **T1-06** `/time set` 和睡眠跳时不改变 timetable 逻辑时间轴。

证据：`TimetableScriptLoaderTest` 覆盖周期声明和缺失周期；`TimetableValidatorTest` 覆盖周期边界；`TimetableDispatcherTest` 覆盖共同 epoch、not-before 和 lateness；`TimetableStationCallDispatcherTest` 覆盖配对 movement Tick 的发车下界。时钟只读取 Dispatcher 单调 Tick，不访问 Bukkit daylight time。T1-05 与 Depot cycle assignment 一起实施。

## T2 Destination 与 Station

- [x] **T2-01** 稳定 Destination 支持 `REGULAR`、`STATION_BERTH`、`DEPOT_STABLING_POINT` 角色。
- [x] **T2-02** Destination 保存 AB 弧长和接近方向，并可作为 Mission 路由目标。
- [x] **T2-03** 创建 Station Destination 时严格要求 Station/Berth ID 和开门侧，其余字段使用显式默认值。
- [x] **T2-04** 一个 Station 可聚合多个 directed berth；双向站台使用两个独立 Destination。
- [x] **T2-05** 旧 WayPoint/RailDestination 有明确 adapter 或迁移失败诊断。
- [x] **T2-06** Destination/Station/StablingPoint 保持被动，不直接操作列车、车门或道岔。

证据：`OperationalDestinationTest` 覆盖 directed Mission route、Station 多 berth 聚合、RailObject 注册/删除和 legacy WayPoint 同步替换；RailObjectWand 的 Destination Dialog 提供角色、方向、Station/Berth/door 和 Depot/StablingPoint 字段，旧 WayPoint 首次保存时迁移到新 store。

## T3 Depot 与动态配车

- [ ] **T3-01** 每列车有唯一 home depot 和预解析 RollingStockRecord。
- [ ] **T3-02** StablingPoint occupancy/reservation 与 service train assignment 主线程原子更新。
- [ ] **T3-03** `takeFromDepot` 严格匹配 tags、编组、速度和 capabilities。
- [ ] **T3-04** 候选列车按稳定 ID 排序后使用 timetable/cycle/service seed 可复现随机分配。
- [ ] **T3-05** 没有匹配 AVAILABLE 列车时触发所属 World Stall，不放宽筛选或跨 Depot 取车。
- [ ] **T3-06** `storeAtDepot` 原子选择筛选后的空闲兼容停车点。
- [ ] **T3-07** 列车停稳、整列进入边界且旧资源车尾释放后才变为 AVAILABLE。
- [ ] **T3-08** 无兼容存车位时 World Stall，不部分提交 occupancy。

## T4 管理员 Reset

- [ ] **T4-01** `/mr depot reset <depot> confirm` 和 `reset-all` 与 `/mr ato reset` 分离。
- [ ] **T4-02** reset 只允许在 Stall 中执行，并先等待当前 physics ticket。
- [ ] **T4-03** 全部列车到 StablingPoint 的分配先原子预检；容量不足不修改任何状态。
- [ ] **T4-04** 提交阶段停止 run/controller、取消 ticket、重建资源账本和 occupancy，再移动列车。
- [ ] **T4-05** reset 生成结构化报告且完成后保持 Stall，必须由管理员另行 ATO reset。

## T5 人工驾驶模式

- [ ] **T5-01** timetable 列车取得钥匙后默认进入 `MANUAL_ROUTED`，ATO 不再输出速度。
- [ ] **T5-02** MANUAL_ROUTED 继续执行 timetable GoTo/GoVia 的 Section 申请、确认和车尾释放。
- [ ] **T5-03** MANUAL_ROUTED 不自动执行车门、dwell hold 或 departure grant。
- [ ] **T5-04** 越过未确认保护点、偏离 PlannedRoute 或破坏 confirmed turnout 时 World Stall。
- [ ] **T5-05** 钥匙 UI 可关闭时刻表进路辅助并进入 `FULL_MANUAL/SUSPENDED`。
- [ ] **T5-06** FULL_MANUAL 不申请新 Section，旧资源仍只按车尾释放。
- [ ] **T5-07** 释放人工 session 后只能从明确 Destination/WayPoint 显式 replan。

## T6 Stall 统一

- [ ] **T6-01** door behavior/action 缺失、失败、取消或超时进入 World Stall。
- [ ] **T6-02** 活动基础设施消失、资源身份矛盾、脱轨和非法 controller replacement 进入 World Stall。
- [ ] **T6-03** 正常 route/berth 冲突、晚点、计划等待和 loader failure 不 Stall。
- [ ] **T6-04** Stall 不自动恢复、换车、跳站或移动列车，并输出稳定 ID 诊断。

## T7 端到端验收

- [ ] **T7-01** 两个以上 cycle 从 Depot 动态取车、运行、停站、存车并无车辆重复分配。
- [ ] **T7-02** 相同 epoch/配置产生相同随机配车结果。
- [ ] **T7-03** 晚点列车保持动作顺序和 minimum dwell，不提前发车。
- [ ] **T7-04** MANUAL_ROUTED 可完成计划经路，FULL_MANUAL 不再申请新道岔。
- [ ] **T7-05** 无车、无停车位、door failure 和保护点越界分别产生 World-local Stall 证据。
- [ ] **T7-06** 原子 depot reset 后所有列车位置、occupancy、controller 和资源账本一致。
- [ ] **T7-07** 完整 Gradle test、Dokka/Docusaurus、TypeScript 和模型配置检查通过。
