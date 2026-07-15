---
title: Phase C2 Station 待验证项目
slug: /architecture/station-validation-checklist
---

# Phase C2 Station 待验证项目

本文是 Phase C2 的实施验收清单。复选框只能在对应自动化测试或可重复的 Bukkit 集成验证通过后勾选；仅完成类型定义或人工阅读代码不算通过。

## 已确认边界

- [x] 第一版以及可预见的后续版本统一使用**列车头部**作为 `StationBerth` 停车对齐参考点。
- [x] `Station` 是不带单一轨道位置的运营聚合体；`StationBerth` 是具体、被动的 RailObject。
- [x] 第一版 `StationCall` 显式指定 berth，不实现自动选台或站台改发。
- [x] Phase C2 不执行开关门、模型 action 或 `behavior.js`；这些属于 Phase C3。
- [x] Station、Berth 和其他 RailObject 不直接设置速度、移动列车或解除发车保持。

## C2-M：模型与 World 隔离

- [x] **C2-M01** `StationId`、`StationBerthRef`、`StationCall` 和 `DwellPlan` 拒绝空 ID、负数时间、非有限距离等非法输入。
- [x] **C2-M02** `Station` 不持有 live Train；`StationBerth` 使用稳定 RailObject ref 和明确的 World-local 身份。
- [x] **C2-M03** Station/Berth 集合在构造时防御性复制，对外只暴露不可变视图。
- [x] **C2-M04** 不同 World 可以使用相同 station/berth ID，注册、查询、移除和运行状态互不影响。
- [x] **C2-M05** berth 的允许方向、停车偏移、位置容差、可用长度、默认 dwell 和语义开门侧均有显式单位与校验。

证据：`StationModelTest` 覆盖非法值、集合防御性复制、DwellPlan、RailObject 注册/移除和两个独立 World 使用相同 ID。

## C2-R：StationCall 解析

- [x] **C2-R01** Station 或 berth 不存在时，在列车移动前返回结构化失败。
- [x] **C2-R02** berth 方向与列车计划接近方向不符时拒绝 Mission，不静默反向或改发其他 berth。
- [x] **C2-R03** 到 berth 的 Planned Route 不可达时拒绝 Mission，且不改变当前有效 Mission。
- [x] **C2-R04** 停车目标按“berth 位置减去停车偏移”解析，参考点始终为列车头部。
- [x] **C2-R05** 配置可用长度时，使用列车物理总长度验证列车可容纳性，不以 carCount 代替长度。
- [x] **C2-R06** StationCall 和 Route revision 乱序到达时拒绝旧任务。

证据：`StationCallResolverTest` 覆盖缺失引用、方向限制、不可达路线、停车偏移、列车长度和移除状态；`StationCallRuntimeTest` 覆盖 stale/mismatched revision。

## C2-N：physics 通知与线程边界

- [x] **C2-N01** `APPROACHING`、`BERTHED`、`DEPARTED` 通知只包含不可变 ID、revision、epoch、速度和位置误差，不持有 live World/Rail/Train。
- [x] **C2-N02** physics worker 只发布通知；TimetableDispatcher 在完整 physics epoch 发布后消费。
- [x] **C2-N03** 同一个 call 的状态严格单调，旧 revision 或旧 epoch 通知不会回退状态。
- [x] **C2-N04** 当前 StationCall 的 berth 首次进入有效 route perception 时只报告一次 `APPROACHING`。
- [x] **C2-N05** 每个 StationCall 最多报告一次 `BERTHED` 和一次 `DEPARTED`。

证据：`StationCallRuntimeTest` 从 physics Tick 产生并在主线程边界 drain 通知，验证 revision/epoch 单调和每种通知只出现一次。

## C2-B：停准与保持

- [x] **C2-B01** 位置在容差内但速度高于 stopped threshold 时不得报告 `BERTHED`。
- [x] **C2-B02** 速度为零但列车头部位置误差超限时不得报告 `BERTHED`。
- [x] **C2-B03** 只有“头部位置误差在容差内”且“速度低于阈值”同时满足时报告 `BERTHED`。
- [x] **C2-B04** `BERTHED` 生效时在 physics Tick 边界建立 `DepartureHoldConstraint`，不存在一 Tick 的无保持窗口。
- [x] **C2-B05** dwell 期间其他较宽松的 movement authority 不得覆盖 DepartureHold。
- [x] **C2-B06** 已激活并观察到的 berth 被移除时触发所属 World 的 ATO Stall；尚未激活的引用缺失只导致 Mission 解析失败。

证据：`StationBerthDetector` 的纯函数测试覆盖位置/速度联合判定；`StationCallRuntimeTest` 覆盖停准、DepartureHold 优先于宽松 movement authority，以及移除目标只 Stall 所属 World。

## C2-D：dwell 与发车

- [ ] **C2-D01** `DwellPlan` 的最早发车 Tick 为“BERTHED Tick + resolved minimum dwell”。
- [ ] **C2-D02** 存在计划发车 Tick 时，发车授权不得早于 minimum dwell 和计划发车时间两者的较晚值。
- [ ] **C2-D03** dwell 完成但下一 Route Section 未确认时继续保持停车，不触发 Stall。
- [ ] **C2-D04** dwell 完成且下一段经路确认后，在同一 route-state 更新中解除 DepartureHold 并延伸 movement authority。
- [x] **C2-D05** 列车获得发车授权并由头部离开 berth 容差区后只报告一次 `DEPARTED`。
- [ ] **C2-D06** Phase C2 全流程不调用车门、模型 action 或 `behavior.js`。

## C2-O：berth 所有权与释放

- [ ] **C2-O01** 同一 berth 同时只能由一个活动 StationCall 持有；分配无抢占且重复确认幂等。
- [ ] **C2-O02** berth 冲突只使后续 service 等待，不部分改变 Route、不触发 World-local Stall。
- [ ] **C2-O03** 车头离开但车尾仍在 berth 有效范围时不得释放 berth 所有权。
- [ ] **C2-O04** 只有已发布 tail 位置越过 berth release boundary 后才释放；stop/failure 不强制释放。
- [ ] **C2-O05** service 切换 Mission 时，旧 berth 所有权保持到车尾安全释放。

## C2-I：集成与运维

- [ ] **C2-I01** `TimetableDispatcher` 状态覆盖 `APPROACHING → BERTHED → DWELLING → READY_TO_DEPART → DEPARTING → DEPARTED`。
- [ ] **C2-I02** `/mr timetable status` 显示 call、station、berth、dwell 和 berth 所有权状态。
- [ ] **C2-I03** JS DSL 和 TypeScript 类型支持显式 station/berth call，并由 loader 严格校验。
- [ ] **C2-I04** 一个 World 的 StationCall 失败或 Stall 不影响其他 World、手动列车或 Core 物理。
- [ ] **C2-I05** 现有 WayPoint Mission、Route Section 分配、车尾释放和 C1 命令行为无回归。

## 完成条件

Phase C2 只有在以上条目全部有自动化证据、`./gradlew test`、文档站检查和 `git diff --check` 均通过后才标记完成。涉及真实 Bukkit 命令或 DSL 的条目还必须至少有一项可重复的适配层集成测试。
