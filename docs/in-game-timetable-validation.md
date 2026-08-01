---
title: 游戏内时刻表运营验收清单
slug: /timetable/in-game-validation
---

# 游戏内时刻表运营验收清单

本清单用于 Paper 测试服上的人工验收。它补充自动测试，不替代 `./gradlew test`。建议使用独立测试世界、模型包备份和至少两列可驾驶列车。

:::danger 先备份
Depot Reset 会移动列车并重建运行期资源账本；door failure 项会故意触发 World Stall。不要直接在生产世界执行。
:::

## 0. 测试记录

- [ ] 记录 JavaRailways commit、Paper 版本和 Java 版本。
- [ ] 记录测试世界 UUID、timetable ID、Depot ID、列车 ID 和模型包名。
- [ ] 清空 `plugins/JavaRailways/debug/` 中旧报告，或先复制到归档目录。
- [ ] 确认测试开始时 `/jr ato status <world>` 为 `RUNNING`。

## 1. 基础设施准备

1. 使用 `/jr wand railobject` 获取 RailObjectWand。
2. 在测试环线创建以下 directed Destination，并通过 `/jr waypoint info <id>` 核对 Rail、AB 弧长、接近方向和角色：
   - [ ] 至少两个 `DEPOT_STABLING_POINT`，属于同一 Depot，配置足够的 usable length。
   - [ ] 至少一个 `STATION_BERTH`，显式配置 Station ID、Berth ID、door side 和接近方向。
   - [ ] 至少两个 `REGULAR` Destination/WayPoint，作为中间保护点和终点。
3. 双向站台分别创建两个相反接近方向的 berth，不复用同一个 Destination。
4. 验证经路：

```text
/jr route validate <depot-destination> <line-end>
/jr route validate <line-end> <stabling-destination>
```

- [ ] 正方向经路可达。
- [ ] 故意使用错误方向的 Destination 时 validator 报不可达。

## 2. 车辆登记

把列车停止并准确对齐 directed StablingPoint 后执行：

```text
/jr depot assign <train> <depot> <point> metro doors
/jr depot list <depot>
```

如果使用逗号分隔的多个值：

```text
/jr depot assign <train> <depot> <point> metro,local doors
```

- [ ] 每列车只属于一个 home Depot。
- [ ] `model`、tags、car count、length、maximum speed 和 capabilities 与模型一致。
- [ ] 列车不在指定 directed StablingPoint、方向错误或仍在移动时，登记失败。
- [ ] 两列车不能登记到同一个已占用 StablingPoint。

## 3. 闭环 timetable 准备

通过结构化 Timetable API、持久化快照或后续 WebUI 准备 `closure` 时刻表。它至少应包含：

- 周期为 1 个逻辑游戏日；
- `loop` service 在 Tick 0 从 `north` Depot 动态取车；
- 筛选 `metro` tag、至少 1 节、最低速度 5 m/s 和 `doors` capability；
- Tick 2000 经 `central-exit`、`line-end` 到达 `central@one`，最短停站 100 Tick并开左门；
- Tick 22000 存回 `north-1` 或 `north-2`。

检查并启动：

```text
/jr timetable dump closure
/jr timetable start closure
/jr timetable status closure
```

- [ ] 缺失周期、未知扩展、周期外 Tick、缺失 Destination/Berth 或不可达经路会在结构化模型验证阶段失败，且 World 不 Stall。
- [ ] 正确模型显示共同 epoch、`cycle=0` 和已分配 train ID。

## 4. 正常自动运营

- [ ] `takeFromDepot` 只选择 tags、编组、速度、capabilities 全部兼容的 `AVAILABLE` 列车。
- [ ] 列车离库后，原 StablingPoint 只在车尾进入 outbound route 后变为空闲。
- [ ] RouteSection 冲突时列车进入 `WAITING_ROUTE`，不改变已确认道岔且 World 不 Stall。
- [ ] StationBerth 冲突时列车进入 `WAITING_BERTH`，不抢占 berth 且 World 不 Stall。
- [ ] `destination` 的 Tick 作为线路出发下界；列车不会提前发车，晚点时立即继续且不跳过动作。
- [ ] BERTHED 后只开门；minimum dwell 和下一次显式 `at` 都满足后才关门，关门确认后才执行下一条线路。
- [ ] `status` 中 scheduled、late、head、tail、berthHeld、operation state 与 snapshot error 均与现场一致。
- [ ] 到 Depot 后整列进入停车边界，RollingStock 才恢复 `AVAILABLE`。
- [ ] 最终 storage RouteSection 在存车期间仍保持确认；下一次离库后由车尾越过原 release boundary 才释放。

## 5. 多 cycle 与确定性配车

一个逻辑游戏日为 24000 Dispatcher Tick，不读取 Bukkit daylight time。

- [ ] `/time set`、睡觉或 daylight cycle 改变不影响 timetable elapsed Tick。
- [ ] period 边界自动出现 `cycle=1`，无需再次 start。
- [ ] cycle-0 仍占用的列车不会被 cycle-1 抢占。
- [ ] 有第二列兼容车辆时，重叠 cycle 使用另一列 `AVAILABLE` 车辆。
- [ ] 相同 timetable、cycle、service 和车辆池在重新搭建的相同场景中产生相同配车结果。
- [ ] `/jr timetable stop closure` 后不再生成下一 cycle，当前资源仍只按车尾释放。

## 6. MANUAL_ROUTED 与 FULL_MANUAL

1. 坐入 timetable 列车驾驶座并使用已绑定钥匙取得控制权。

- [ ] Dialog 显示 `MANUAL_ROUTED`。
- [ ] 人工手柄是唯一速度来源；ATO 不再驱动速度。
- [ ] Dispatcher 仍申请 timetable RouteSection、设置道岔并按车尾释放。
- [ ] MANUAL_ROUTED 不自动开关门、不施加 dwell hold、不自动授予发车；司机自行执行平台动作。
- [ ] 接管前若 door ticket 已 `FAILED`、意外 `CANCELLED` 或身份变化，接管被拒绝，原 controller/run 保持不变并由 Dispatcher 处理故障。

2. 点击“关闭时刻表进路辅助”。

- [ ] 模式变为 `FULL_MANUAL`，run 为 `SUSPENDED`。
- [ ] 不再申请新的 RouteSection或改变新道岔。
- [ ] 接管前已有资源继续按车尾释放，不会立即清空。

3. 释放驾驶权。

- [ ] run 进入 `REPLAN_REQUIRED`，不会自动恢复 ATO。
- [ ] 列车停止并位于明确 Destination/WayPoint 后，管理员使用：

```text
/jr timetable replan closure loop <current-waypoint> [cycle]
```

- [ ] replan 成功产生更高 Mission revision；位置、方向、资源或人工 session 条件不满足时拒绝且不猜测恢复点。

## 7. World Stall 故障注入

每项应在独立场景执行。记录 `/jr ato status`、`/jr ato dump` 和 `plugins/JavaRailways/debug/ato-stall-*.json`。

### 7.1 无可用车辆

使用不存在的 tag 或占用所有兼容列车后启动下一 cycle。

- [ ] 产生 `DepotAllocationFailure`。
- [ ] 不跨 Depot 取车、不放宽筛选、不抢占运行中列车。

### 7.2 无兼容停车位

占用 allow-list 内全部停车位，或让 usable length/allowed tags 不兼容。

- [ ] `storeAtDepot` 产生 `DepotAllocationFailure`。
- [ ] 不出现部分 reservation 或错误 occupancy。

### 7.3 Door/model 故障

在模型包备份后，故意移除对应 door action/behavior，或让 operation ticket 失败、取消或超时。

- [ ] 产生 `TrainOperationFailure`。
- [ ] DepartureHold、berth 和已确认经路保持；列车不发车。
- [ ] 不进入旧的自动恢复或 `MANUAL_INTERVENTION` 流程。

### 7.4 MANUAL_ROUTED 保护破坏

制造 route conflict 使下一 Section 未确认，然后人工越过保护点；另测错误经路和已确认道岔被外部改变。

- [ ] 分别产生 `ManualRoutedSafetyViolation`。
- [ ] realistic manual controller 在 Stall 后施加紧急制动。

### 7.5 身份与基础设施

仅在可恢复测试服执行：替换活动 controller、删除活动 route Rail/Destination，或破坏资源 ledger。

- [ ] 分别产生 `ControllerIdentityViolation`、`InfrastructureInvariantViolation` 或 `ResourceIdentityViolation`。
- [ ] Stall 只影响所属 World，另一测试 World 继续运行。

## 8. Depot Reset 与恢复

Stall 后不要先执行 ATO reset。先执行：

```text
/jr depot reset north confirm
# 或
/jr depot reset-all confirm
```

- [ ] 非 Stall 状态命令被拒绝。
- [ ] 缺少精确 `confirm` 时只显示用法，不执行。
- [ ] capacity、train、Destination、envelope 或 ledger preflight 失败时，所有列车位置和 occupancy 保持不变。
- [ ] 将两个 StablingPoint 放在相邻 Rail 且让实际车体跨接缝重叠时，Reset 以 `TRAIN_ENVELOPE` 拒绝，即使目标点位于不同 Rail。
- [ ] 用反向或脱轨列车制造后续列车定位失败时，回滚后方向、脱轨标志和位置均与 Reset 前一致。
- [ ] 成功后所有列车位于唯一兼容 directed StablingPoint。
- [ ] RollingStock 为 `AVAILABLE`，occupancy、controller、RouteSection、StationBerth 和 timetable run 一致。
- [ ] 生成 `plugins/JavaRailways/debug/depot-reset-*.json`，包含 assignment 与释放数量。
- [ ] Reset 成功后 `/jr ato status <world>` 仍为 `STALLED`。

管理员检查现场和两类报告后，最后执行：

```text
/jr ato reset <world>
```

- [ ] ATO reset 不移动列车、不修复脱轨，也不恢复旧 epoch/run；FAILED run、旧 controller 和遗留 operation/Section/Berth 资源会被清理，随后可重新导航或接管健康列车。
- [ ] 重新 load/start timetable 后才建立新的共同 epoch。

## 9. 重启边界

- [ ] 服务器重启后活动 epoch 不自动恢复。
- [ ] 已加载 timetable 需要按当前持久化策略重新 load/start。
- [ ] 不存在重启后静默继续旧 cycle、旧 operation ticket 或旧人工 session 的情况。

## 10. 验收记录

建议每项记录：

```text
时间：
commit：
world UUID：
timetable / cycle / service：
train / depot / point / station / berth：
执行命令：
预期：
实际：
ATO Stall report：
Depot Reset report：
截图或录像：
结论：PASS / FAIL
```
