---
title: 给定时刻表运营闭环设计
slug: /architecture/timetable-operation-design
---

# 给定时刻表运营闭环设计

本文固定 MineRails 当前优先目标：管理员启动一张已验证的时刻表后，系统在共同周期时间轴上动态取车、申请经路、执行停站和存车；不实现容量优化、晚点恢复或全局自动排图。异常采用保守的 World-local Stall，由管理员检查、重置车辆段并重新启动。

> 本文描述已确认的目标契约。尚未落地的部分按 [运营闭环验收清单](./timetable-operational-closure-checklist.md) 分切片实施；当前运行行为以结构化 `Timetable` 模型及源码为准。

## 1. 范围

### 1.1 目标

- timetable 周期以整数 Minecraft 游戏日声明；
- 管理员手动启动整张 timetable，并建立所有 service 共享的 epoch；
- service 从指定车辆段按能力动态选择列车，不固定绑定车辆编号；
- timetable 能显式取车、运行、停站和存车；
- ATO 和人工驾驶都可使用 Dispatcher 的原子 RouteSection 分配；
- Station、Depot 和 Destination 保持被动值/基础设施对象，不直接执行列车行为；
- 运行时异常进入 World-local Stall，不自动恢复。

### 1.2 暂不实现

- 自动生成或优化时刻表；
- 车辆周转优化、全局容量计算、会让或越行求解；
- 根据晚点自动跳站、缩短 dwell 或换车；
- 无管理员参与的故障恢复；
- 第三方 ATO Registry（原 Phase D2，继续延期）；
- 未经 profiler 证明必要的 Phase E 索引。

## 2. 周期与共同时间轴

### 2.1 单位

一个逻辑游戏日固定为：

```text
TICKS_PER_GAME_DAY = 24000
periodTicks = periodDays × 24000
```

`periodDays` 必须是正整数，乘法必须检查溢出。该时间轴来自 Dispatcher 的单调逻辑 Tick，不读取 Bukkit world daylight time，因此不受睡觉、`/time set` 或天气影响。

目标 DSL：

```js
timetable.setPeriodDays(2)
```

脚本和类型声明只提供 `setPeriodDays`。loader 必须拒绝缺失或非法周期以及周期外的 `at(tick)`。

### 2.2 启动 epoch 与 cycle

管理员执行整张 timetable 的 start 时，Dispatcher 原子捕获：

```text
epochDispatcherTick
cycleIndex = 0
cycleStart = epochDispatcherTick + cycleIndex × periodTicks
```

同一 timetable 的全部 service 共享 epoch，不能由逐个 service start 产生不同时间基准。周期到达后创建下一 cycle 的 service run；前一 cycle 尚未完成时不抢占其列车或资源。

第一版不持久化活动 epoch。服务器重启后由管理员重新 load/start，并产生新的 epoch。

### 2.3 `at(tick)` 与晚点

- 显式 `at(tick)` 是相对当前 cycle start 的计划 Tick；
- `wait()`、`storeAtDepot()` 等普通动作不得早于计划 Tick；
- 以 `destination()` 收尾的线路把它解释为出发 Tick，列车不得提前发车；
- `pass()` / `destination()` / `reverse()` / `halt()` 可省略 `at()`；第一条隐式线路使用 Tick 0，后续沿用最近显式 Tick，使 minimum dwell 成为主要发车下界；
- 前序动作未完成时保持等待；
- 条件满足但计划 Tick 已经过期时立即执行，并记录非负 lateness；
- 晚点不跳过动作、不压缩安全 dwell、不触发 Stall。

经路设置仍由实时位置、制动距离和安全余量触发。计划 Tick 控制普通动作最早执行时刻、线路出发下界和冲突优先级，不能成为道岔安全切换的唯一依据。

线路导航由一个链式计划提交：

- `at(departureTick).pass(...)` 按顺序把不停车目的地加入导航队列；
- `destination("target", options?)` 追加最终停车目的地并提交整条线路；
- 最终目标可以是普通 WayPoint，也可以是 StationBerth Destination；
- 每个 pass 目标经过后从当前导航队列推进移除，不产生停车业务；
- 两类目标都可使用停车偏移、车门和 minimum dwell options；StationBerth 还执行 StationCall 与 berth 资源流程；
- destination 到达后只开门；minimum dwell 与下一次显式 `at` 都满足后才关门，关门成功后才执行下一条线路；
- `reverse()` 在关门成功且列车停稳后交换运营头尾，并把反向后的 facing 作为下一 Mission 的起始方向；
- `halt()` 在关门成功后执行模型包 `power-off` action，并以 action ticket 成功作为 service 完成条件；
- Dispatcher 不通过提高速度强求准点。

## 3. Destination 统一运营入口

### 3.1 角色

创建 Destination 时选择一个明确角色：

```text
REGULAR
STATION_BERTH
DEPOT_STABLING_POINT
```

目标值保存稳定 ID、显示名、Rail ID、AB 基准弧长和接近方向。Destination 是路由锚点；角色附加值分别创建/关联被动 StationBerth 或 Depot StablingPoint。现有 WayPoint 和 RailDestination 在迁移期由 adapter 解析到统一 Destination 引用，不一次性删除旧存档字段。

### 3.2 Station Berth

选择 `STATION_BERTH` 时必须提供：

- `stationId`；
- `berthId`；
- 相对接近方向的 `doorSide`。

以下字段可以使用严格全局默认值：

- 头部停车偏移；
- 停车位置容差；
- usable length；
- default dwell；
- release distance。

一个 Station 可以聚合多个 directed Destination/Berth。同一物理站台双向使用时创建两个 directed Destination；不开启“同一点按运行方向动态解释左右侧”的隐式模式。开门侧始终相对 Destination 的接近方向。

### 3.3 被动边界

Destination、StationBerth 和 StablingPoint 不调用 Train、controller、door action 或 turnout。Dispatcher/allocator 解析这些值并执行行为，保持 RailObject 被动原则。

## 4. Depot、停车点与车辆能力

### 4.1 值模型

```text
Depot
  id
  displayName
  stablingPointRefs[]

StablingPoint
  destinationRef
  usableLength
  optional allowedTags
  occupancy

RollingStockRecord
  trainId
  homeDepotId
  model/package
  tags
  carCount/length
  maximumSpeed
  capabilities
  lifecycleState
```

每列车恰好属于一个 `homeDepotId`。StablingPoint 同一时刻最多由一列车占用；占用、reservation 和 timetable service assignment 必须在主线程原子更新。

### 4.2 取车

目标 DSL 语义：

```js
t.at(0).takeFromDepot("north", {
  tags: ["metro"],
  minimumCars: 4,
  maximumCars: 8,
  capabilities: ["doors"]
})
```

到达计划 Tick 后：

1. 从指定 Depot 枚举处于 AVAILABLE 且实际位于其 StablingPoint 的列车；
2. 严格匹配 tags、编组和能力；
3. 使用 `timetableId + cycleIndex + serviceId` 派生的稳定 seed 进行可复现随机选择；
4. 原子 reserve 列车和其当前位置；
5. 将本 cycle service 临时绑定到该 trainId；
6. 后续 movement 从实际 StablingPoint Destination 起算。

**没有任何匹配的空闲列车时立即触发 World-local Stall。** 不等待其他 cycle 释放车辆，也不静默放宽筛选条件。

### 4.3 存车

目标 DSL 语义：

```js
t.at(22000).storeAtDepot("north", {
  stablingPoints: ["north-1", "north-2"]
})
```

Dispatcher 在筛选集合中原子选择一个兼容且未占用的 StablingPoint，提交到该 Destination 的 Mission。存车只有同时满足以下条件才完成：

- 列车停止并按头部目标停准；
- 整列车位于停车边界内；
- 车尾越过所有旧 RouteSection/StationBerth release boundary；
- StablingPoint reservation 仍属于该 service/train；
- controller 和 operation ticket 已安全结束。

完成后列车变为 `AVAILABLE`，可被后续 cycle 取用。没有兼容停车位时属于运行期资源异常并触发 World-local Stall；不部分存车或挪动其他车辆。

## 5. 管理员车辆段 Reset

`/mr ato reset` 不移动列车或修复脱轨；它在解除 Stall 前清理 FAILED run、旧 controller 及其遗留 operation/Section/Berth 资源。物理重置使用独立破坏性命令：

```text
/mr depot reset <depot> confirm
/mr depot reset-all confirm
```

Reset 必须在主线程执行以下原子预检：

1. 目标 World 已进入 Stall；
2. 枚举全部目标列车及其 home depot；
3. 为每列车计算唯一且兼容的 StablingPoint；
4. 验证轨道、方向、RollingStock 记录与实际编组长度、模型和停车空间；
5. 任一列车无位置时拒绝整个 reset，不修改任何对象。

预检成功后进入维护提交：

- 先捕获每列车的位置、方向、脱轨标志和已发布 swept state，再把列车放入预计算 StablingPoint 的未发布状态；
- 根据实际车体在每段 Rail 上的完整包络检查跨 Rail 重叠；任一定位或包络检查失败时，在修改资源账本前恢复并发布所有旧列车状态；
- 实际包络全部互不重叠后，才发布全部新列车位置；
- 全部位置提交成功后停止 timetable runs 和 controllers，并取消 operation tickets；取消只有在前后 snapshot 的 ticket/request 身份一致且后续状态明确为 `CANCELLED` 时才算成功，已有 `FAILED`/意外 `CANCELLED` 或 snapshot/cancel 异常会终止本次提交并报告 `PARTIALLY_COMMITTED`；
- 关闭并重建 Dispatcher 的 route/berth/depot 资源账本；
- 重建 occupancy 与 AVAILABLE 状态；
- 写出结构化 reset 报告。

正常预检、列车定位和实际包络检查失败不会留下部分列车状态；回滚会恢复方向、脱轨标志和 swept occupancy，而不仅是车头坐标。账本提交开始后的未预期内部异常无法安全伪装成回滚成功，必须报告 `PARTIALLY_COMMITTED` 并保持 Stall，并记录异常发生前已经完成的 run removal、Section/Berth release 和 timetable stop 数量，等待管理员检查后再次执行 Reset。Reset 成功后 World 也**仍保持 Stall**；管理员检查列车和道岔后，显式执行 `/mr ato reset`，再启动新的 timetable epoch。这是唯一允许绕过正常车尾释放的维护边界。

## 6. 人工驾驶模式

### 6.1 `MANUAL_ROUTED`

活动 timetable 列车取得钥匙控制后默认进入被动进路模式：

- 人工 controller 唯一负责牵引、制动和方向；
- ATO 不输出速度或控制需求；
- Dispatcher 继续执行整条 timetable 的 GoTo/GoVia 计划；
- Dispatcher 根据实时位置申请、确认并按车尾释放 RouteSection；
- 可在计划发车 Tick 前准备经路，但不驱动列车；
- 不自动执行 Station door、dwell hold 或 departure grant；
- timetable 保留列车 assignment 和计划进度。

manual controller 当前不消费 SimpleATO movement authority。因此第一版采用监测式保护：司机必须在未确认 protection point 前停车；头部越过未确认保护点、偏离 PlannedRoute、反向导致 route progress 无法保守解释或破坏 confirmed turnout 时触发 World-local Stall。

### 6.2 `FULL_MANUAL`

钥匙界面提供“时刻表进路辅助”开关。关闭后：

- 不申请新的 RouteSection；
- 已确认资源仍按已发布车尾位置释放；
- timetable run 进入 `SUSPENDED`；
- 司机可离开计划经路，但不得改变仍被锁闭的 confirmed turnout；
- 恢复 ATO 必须释放人工 session，并由管理员从明确 Destination/WayPoint 执行 replan。

切换到 FULL_MANUAL 不强制释放资源。若司机选择的自由经路与仍持有的 Section 冲突，只能等待释放或执行维护 Reset。人工接管或模式切换前若有活动 operation ticket，Dispatcher 必须确认 ticket/request 身份、拒绝尚未处理的 `FAILED`/意外 `CANCELLED`，并在取消后以同一身份 snapshot 确认 `CANCELLED`；任何异常都会拒绝控制权转移并保留原控制器和运行状态。

## 7. World-local Stall 矩阵

### 7.1 必须 Stall

- ATO、Planner、Provider、Dispatcher 或 Gateway 未处理异常；
- confirmed turnout mismatch；
- 活动 Rail、Destination、StationBerth、StablingPoint 或资源所有权非法消失；
- Mission revision、operation ticket 或 allocation 身份矛盾；
- door `behavior.js`/action 缺失、rejected、failed、cancelled 或超时；
- Depot 没有匹配的 AVAILABLE 列车；
- 存车没有兼容空闲 StablingPoint；
- MANUAL_ROUTED 越过未确认保护点或偏离 PlannedRoute；
- timetable 管理列车脱轨；
- controller 未经过显式 transfer 协议被替换。

Stall 停止当前 World 的全部自动运营协调，不影响其他 World。系统不自动 reset、换车、跳站或移动列车。

### 7.2 不 Stall

- 结构化 timetable 模型校验失败；
- 正常 RouteSection 或 StationBerth 冲突；
- 列车早到、晚点或等待计划 Tick；
- 管理员显式进入 MANUAL_ROUTED/FULL_MANUAL；
- 管理员命令参数错误；
- 维护 Reset 预检失败且尚未修改状态。

## 8. 线程与所有权

- timetable epoch、cycle、车辆分配、Depot occupancy、RouteSection、berth、manual mode 和 reset plan 全部由 World-local Dispatcher 在主线程拥有；
- physics worker 只运行 controller、发布不可变 TrainSnapshot 和 StationCall 通知；
- Depot selector 不在 physics worker 读取 Bukkit/model 对象；RollingStockRecord 在主线程预解析；
- reset 先等待当前 physics ticket，提交位置后重建发布状态；
- 随机选择只在稳定排序后的候选列表上使用确定 seed；
- 所有 selector、allocation、operation 和 reset failure 都输出稳定 ID 诊断。

## 9. 实施顺序

1. **T1 周期时钟**：`setPeriodDays`、共同 epoch、cycle run、not-before 与 lateness；
2. **T2 Destination 角色**：统一路由锚点、Station/Berth 创建和旧 WayPoint adapter；
3. **T3 Depot**：RollingStockRecord、StablingPoint、原子取车/存车、确定性随机；
4. **T4 Reset**：原子预检、维护提交、结构化报告；
5. **T5 Manual routed**：钥匙 UI 模式、仅经路 Dispatcher、保护点/偏离监测；
6. **T6 Stall 统一**：door、Depot、脱轨、身份和基础设施异常；
7. **T7 端到端验收**：多 cycle、动态配车、停站、存车、人工接管和故障注入。
