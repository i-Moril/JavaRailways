---
title: JS 时刻表语法
slug: /timetable/
---

# JavaRailways JS 时刻表语法说明

:::warning 实验性能力
本文档完整描述当前 JS 时刻表 DSL。运行时支持显式 `TrainId`、Depot 动态配车或普通路径点取车、`pass(...).destination(...)` 链式线路、`takeFromDepot`/`takeFromWaypoint`/`storeAtDepot`、非线路 hold、`Reverse`、`Halt`、Station/Berth 运营流程、扩展动作、结构化条件，以及共同 epoch 下的 not-before 计划时间和 period 自动 cycle。运营闭环仍以 [设计](architecture/timetable-operation-design.md) 和 [验收清单](architecture/timetable-operational-closure-checklist.md) 为准。
:::

JS 只负责生成 `SavedTimetableV1`，不会成为运行时脚本。每次编译都在独立 Worker JVM 的受限 GraalJS Context 中完成；Bukkit 随后恢复内部 `Timetable`、针对命令发起时所在世界执行 dry-run，通过后才替换运行期 store 中的同 ID 时刻表。加载不会自动启动 service。

## 文件位置与命令

推荐把脚本放在：

```text
plugins/JavaRailways/timetables/<name>.js
```

加载：

```text
/jr timetable load <file.js> [id]
```

示例：

```text
/jr timetable load demo.js
/jr timetable load demo.js line_a_morning
```

查看已加载时刻表：

```text
/jr timetable list
```

输出内部结构：

```text
/jr timetable dump <id>
```

实验性启动和状态管理：

```text
/jr timetable start <id> [service]
/jr timetable status <id> [service]
/jr timetable stop <id> [service]
/jr timetable replan <id> <service> <current-waypoint> [cycle]
```

service 的第二参数可以是玩家指定的列车名、`timetable.takeFromDepot(...)` 或 `timetable.takeFromWaypoint(...)` selector。显式列车名需在第一条线路前通过 `check()` 指定初始位置；动态 selector 会生成内部取车步骤。线路使用 `[at(departureTick).]pass(...).destination(target, options?)` 一次提交，不能用逐段移动与独立等待拼接运营语义。

`stop` 命令先禁用匹配 timetable/service 的后续自动 cycle，再对当前所有匹配 cycle run 请求受控制动；已确认经路不会被强制释放，必须等列车尾部越过 release point。重新显式 start service 会重新启用该 service 的周期调度。人工接管会让车次进入 `SUSPENDED`，释放驾驶权后进入 `REPLAN_REQUIRED`；管理员只能在列车停止、位于指定 WayPoint 且旧资源已由车尾释放后使用 `replan` 创建新 Mission。`status` 会显式输出 operation ticket 的 snapshot 错误。一般 `at(tick)` 是 not-before 时间；StationCall 的最短停站由 `minimumDwellTicks` 控制，下一条定时动作可以继续延长发车保持。

World Stall 后可执行显式确认的 Depot Reset：

```text
/jr depot reset <depot> confirm
/jr depot reset-all confirm
```

命令会先等待当前 physics ticket，再全量预检列车、directed StablingPoint、实际编组长度、容量和 Dispatcher ledger。任何 preflight 失败都不会移动列车或修改 occupancy。位置提交前会用实际车体计算跨 Rail 包络；定位或包络检查失败会在修改 ledger 前恢复旧位置、方向、脱轨标志和 swept occupancy。全部包络互斥后才发布位置，随后停止相关 run/controller、取消 operation ticket并重建资源账本；ticket 必须通过 snapshot 确认为 `CANCELLED`，取消或快照异常不会被忽略。账本提交开始后的未预期异常会明确报告 `PARTIALLY_COMMITTED`，不会声称已完整回滚。报告写入 `plugins/JavaRailways/debug/`；Reset 结束后 World **仍保持 Stall**，管理员检查后必须另行执行 `/jr ato reset <world>`。

取得 timetable 列车钥匙控制权时默认进入 `MANUAL_ROUTED`：司机手柄是唯一速度来源，Dispatcher 仍申请计划 RouteSection 并按车尾释放，但不会自动开关门、执行 dwell hold 或授予发车。钥匙 Dialog 可通过“关闭时刻表进路辅助”进入 `FULL_MANUAL/SUSPENDED`；此后不申请新 Section。任一人工模式释放后均进入 `REPLAN_REQUIRED`，不会自动恢复 ATO。

如果省略加载命令的 `[id]`，时刻表 id 使用脚本文件名去掉 `.js` 的部分。当前命令要求由玩家执行，因为 loader 使用玩家所在的 Bukkit 世界完成路径验证。

## 入口函数

每个脚本必须定义：

```js
function main(timetable) {
  // 在这里构建时刻表
}
```

`main` 参数 `timetable` 是 JavaRailways 提供的 DSL 对象。

:::info 独立 Worker 沙箱
脚本不在 Bukkit 或浏览器进程中执行。插件按次启动 `ScriptWorker` JVM，限制为 64 MiB heap、128 MiB metaspace、3 秒执行时间、512 KiB 源码和 8 MiB结果；超时会强制终止。GraalJS 只暴露标有 `@HostAccess.Export` 的 DSL 方法，并禁止 Host class lookup、文件/网络 IO、创建线程、native access 和 polyglot access。Worker 不继承 Bukkit 环境变量。WebUI 只能查看服务器脚本、验证结果和只读运行图，不能上传、编辑或加载脚本。
:::

## 基本示例

```js
function main(timetable) {
  timetable.setPeriodDays(1)

  timetable.service("001", "train-001", function(t) {
    t.check("depot1")
    t.at(1000).pass("a0").destination("b0", {
      minimumDwellTicks: 100,
      doorSide: "LEFT"
    })
  })
}
```

含义：

- 时刻表周期为 `1` 个逻辑游戏日，即 `24000` Dispatcher Tick。
- 定义由 `train-001` 执行的车次/服务 `001`。
- `check("depot1")` 声明并验证第一次移动的初始位置。
- `1000` tick 是整条线路的最早出发 Tick。
- 列车通过 `a0`，不在该 pass 点停车。
- `b0` 是最终停车目的地；到达后按 destination options 执行开门和最短停站。

## 顶层 API

### `timetable.setPeriodDays(days)`

声明正整数逻辑游戏日周期。一个逻辑游戏日固定为 `24000` Dispatcher Tick，不受睡觉或 Bukkit `/time set` 影响。

```js
timetable.setPeriodDays(2)
```

规则：

- `days` 必须是大于 0 的整数；
- loader 严格检查 `days × 24000` 溢出；
- 每个脚本必须显式调用；
- 所有显式 `at(departureTick)` 必须小于 `periodDays × 24000`；
- 管理员启动整张 timetable 时建立全部 service 共享的 cycle-zero epoch；
- period 边界为全部 service 自动创建使用同一 cycle 起点的下一 cycle run；
- 前一 cycle 仍活动时不会被抢占；动态服务必须取得另一列实际停止且未被控制的列车，否则 Stall。

`setPeriod(ticks)` 暂时作为兼容入口保留，但 ticks 必须是 `24000` 的正整数倍；新脚本应使用 `setPeriodDays`。

### `timetable.diagramLine(lineId, options)`

声明仅供 WebUI 运行图使用的线路站序，不参与 Dispatcher、寻路、资源申请或运行时调度：

```js
timetable.diagramLine("metro", {
  upStations: ["south", "central", "north"],
  downStations: ["north", "central", "south"]
})
```

上下行数组必须非空且各自不能包含重复站 ID。运行图按站 ID 合并不同站台，因此同一张图中每个车站只占一条横线。service 通过纯展示字段 `diagramLineId` 关联到对应运行图；它与世界基础设施中的 `LineId` 无关。

### `timetable.service(id, builder)`

定义动态取车 service。取车来源是 `service` 的第二参数，而不是 callback 内的普通动作；取车可以安排在周期内任意非负 tick，到点前不会提前占用列车。

```js
timetable.service(
  "001",
  timetable.takeFromDepot("north", 800),
  function(t) {
    t.at(1000).destination("a0")
  }
)
```

### `timetable.service(id, trainSelector, builder)`

定义一个 service，并指定玩家命名的列车。列车名在世界内区分大小写且唯一；UUID 仅作为内部持久化身份。

```js
timetable.service("001", "train-001", function(t) {
  t.check("depot1")
  t.at(1000).destination("a0")
})
```

可执行的列车选择模型只有两种：

| 写法 | 约束 |
|---|---|
| 省略或 `null` | 无列车 selector；包含列车动作的 service 将验证失败 |
| `timetable.takeFromDepot(...)` | 在指定 tick 从 Depot 动态配车 |
| `timetable.takeFromWaypoint(...)` | 在指定 tick 从一个或多个普通路径点接管停车列车 |
| 字符串 | 指定玩家列车名；第一次移动前必须有 `check()` |

裸字符串数组 selector 已移除，并会在 DSL 解析边界直接报错；多个取车路径点必须通过 `timetable.takeFromWaypoint([...], tick)` 声明。

### `timetable.service(id, trainSelector, options, builder)`

四参数形式可关联 WebUI 线路，并控制 service 激活方式：

```js
const demand = timetable.predicate("operator:demand", { minimum: 2 })

timetable.service("001", "train-001", {
  diagramLineId: "metro",
  lineDirection: "UP",
  activation: {
    type: "conditional",
    condition: demand,
    falseBehavior: "SKIP_CYCLE"
  }
}, function (t) {
  t.check("depot1")
  t.at(1000).destination("central@one")
})
```

`lineDirection` 在设置 `diagramLineId` 时可选 `UP` 或 `DOWN`，对应运行图中独立维护的上行/下行站序。旧脚本的 `lineId` 仍作为兼容别名接受，但新脚本应使用 `diagramLineId`；单独设置 `lineDirection` 而没有运行图 ID 会失败。

`activation.type` 支持：

| 类型 | 含义 |
|---|---|
| `scheduled` | 默认，按 timetable 周期自动创建 run |
| `manual` | 只允许管理员显式启动 |
| `conditional` | 每个 cycle 通过结构化条件判断是否启动 |

条件激活的 `falseBehavior` 支持 `SKIP_CYCLE`（本周期跳过）和 `WAIT_UNTIL_TRUE`（本周期等待条件成立）。条件 provider 必须声明支持 `SERVICE_ACTIVATION` scope；否则 Bukkit 世界验证会拒绝加载。

## 初始位置约束

`timetable.setInitialPosition(trainSelector, positionSelector)` 为当前 timetable epoch 声明一对列车/初始位置。位置 selector 可匹配 WayPoint ID，也可匹配 Depot ID；匹配 Depot 时会展开为该 Depot 下尚未使用的 StablingPoint。字符串是全长正则表达式，不需要手写 `^` 和 `$`：

```js
timetable.setInitialPosition(
  timetable.and("s100-.*", timetable.not(".*-maintenance")),
  timetable.or("yard@1", "yard@2")
)
```

规则：

- 每次调用从尚未被前面约束选中的列车名和 WayPoint ID 中确定性匹配一对；
- `and(...)`、`or(...)`、`not(...)` 用于组合 selector；
- 已经位于合法目标的配对优先，否则按列车名/UUID和 WayPoint ID 排序选择；
- WayPoint 方向优先使用其 OperationalDestination `approachDirection`，否则使用下方 Rail 的 `allowedDirection`；两者都缺失会 Stall；
- timetable start/reset 时全量应用；任一放置或占用检查失败会回滚全部移动并触发 `TimetableInitialPositionFailure`；
- tags selector 已在持久化模型中预留，但当前 DSL 尚未开放。

重置命令：

```text
/jr timetable reset <id> confirm
```

若 active/resource-holding run 无法安全完成停车，可由管理员显式执行破坏性的强制重置：

```text
/jr timetable reset <id> force confirm
```

强制重置会立即撤销人工/ATO controller，取消操作票，释放 RouteSection、StationBerth 和停车位 reservation，丢弃该 timetable 的全部 run/clock，然后应用原子初始位置并启动新 epoch；不会等待车尾通过释放点。

## Service 内 API

在 `service(..., function(t) { ... })` 中，`t` 是 service builder。

### `timetable.takeFromDepot(depotId, options?, tick)`

构造一个 service train selector，在指定 tick 从 Depot 的 StablingPoint 中选择一列物理上实际停准、停止且未被其他 service 控制的列车。DepotStore 的 RollingStock 登记状态不参与车辆是否可取的判断。

```js
timetable.service(
  "001",
  timetable.takeFromDepot("north", {
    tags: ["metro"],
    minimumCars: 4,
    maximumCars: 8,
    minimumSpeedMetersPerSecond: 16.5,
    capabilities: ["doors"]
  }, 800),
  function(t) {
    t.at(1000).destination("central-1")
  }
)
```

无筛选 options 时可写为 `timetable.takeFromDepot("north", 800)`。

规则：

- 只能作为 `service` 第二参数，不能在 callback 内调用；运行时只在到达指定 tick 后分配车辆；
- `tags` 和 `capabilities` 是必须全部满足的字符串数组；
- 编组上下界和最低速度均为严格筛选条件，不会退化选择不兼容车辆；
- 候选先按稳定 train ID 排序，再使用 timetable/cycle/service 派生 seed 可复现选择；
- 被选列车必须仍停在其 directed StablingPoint；
- Depot 内没有物理上停准且可用的列车会触发当前 World 的 ATO Stall；
- dry-run 只从满足 selector 的 RollingStock 及其兼容 StablingPoint 展开候选路线，不会因不兼容停车点产生伪失败；
- 未知 option、非法范围、缺失 Depot/StablingPoint 会导致 loader 失败。

### `timetable.takeFromWaypoint(wayPointIdOrList, tick)`

构造一个 service train selector，从普通路径点接管一列已停车、未被其他 service 占用的列车，适用于车站站台、折返线和普通存车线。

```js
timetable.service(
  "platform-start",
  timetable.takeFromWaypoint("central@platform-1", 6000),
  t => t.at(6200).destination("next-station@1")
)

timetable.service(
  "siding-start",
  timetable.takeFromWaypoint([
    "central@siding-1",
    "central@siding-2"
  ], 8000),
  t => t.at(8200).destination("central@platform-1")
)
```

规则：

- 参数必须是非空字符串或非空字符串数组，数组顺序是路径点优先级；
- 只能作为 `service` 第二参数，tick 不要求为 0；
- 候选列车必须停在 directed waypoint、处于停止状态、未脱轨且未被活动 service 或外部 controller 占用；
- DepotStore 中 `AVAILABLE`/`RESERVED` 的 RollingStock 不能绕过 Depot 状态机，应使用 `takeFromDepot`；已由前一 completed service 带到站台且仍为 `IN_SERVICE` 的车辆可以连同原 assignment 一起接管；
- 同一路径点有多个候选时按稳定 train ID 选择；
- 到点后没有合格列车会触发当前 World 的 `TrainAcquisitionFailure` Stall，不会等待或回退到其他未声明路径点。

### `t.at(tick).storeAtDepot(depotId, options?)`

为运行中的动态配车 service 原子保留一个兼容空闲停车点，并以其 directed Destination 创建最终 Mission：

```js
t.at(22000).storeAtDepot("north", {
  stablingPoints: ["north-1", "north-2"]
})
```

规则：

- 必须是 service 最后一步，并返回该 service `takeFromDepot` 的 home Depot；
- `stablingPoints` 是可选的严格 StablingPoint ID allow-list；省略时使用 home Depot 全部停车点；
- 停车点必须未占用、未保留，长度和 allowed tags 必须兼容；
- 无兼容空闲停车点会触发当前 World 的 ATO Stall，且不会部分修改 occupancy；
- 只有列车停准、完整车身进入停车边界、旧 StationBerth/RouteSection 已由车尾释放后，才会原子地从 reservation 转为 occupancy 和 `AVAILABLE`；
- 最终 storage RouteSection 不会提前释放；它在存车期间保持确认，并在下一次离库时继续由车尾越过原 release boundary 后释放。

列车和 home depot 目前由管理员在列车停止并准确位于 directed StablingPoint 后登记：

```text
/jr depot assign <train> <depot> <point> [tagsCsv] [capabilitiesCsv]
/jr depot list [depot]
```

### `[t.at(departureTick).]pass(...).destination(target, options?)`

一次链式调用提交一条完整运营线路：零个或多个不停车 pass 目标，以及恰好一个最终停车 destination。

```js
t.at(1000)
  .pass("a1")
  .pass(["a2", "junction"])
  .destination("central@platform-1", {
    arrivalTick: 1880, // 仅供 WebUI 绘图
    minimumDwellTicks: 100,
    stoppingOffsetMetersBefore: 1.5,
    doorSide: "LEFT"
  })
```

也可以省略 `at()`：

```js
t.pass("a1").destination("central@platform-1", {
  minimumDwellTicks: 100
})
```

规则：

- `departureTick` 是整条线路的 not-before 出发 Tick，而不是到达 Tick；省略时第一条线路使用 0，后续沿用最近显式 Tick。
- `pass()` 接受一个稳定 Destination/WayPoint ID 或非空 ID 数组；列车经过这些点但不产生停车业务。
- `destination()` 接受恰好一个最终停车目标，并立即提交此前累计的全部 pass。
- 有未提交 pass 时不能开始另一条 `at()` 或执行其他动作；脚本结束时仍有 pass 也会失败。
- `arrivalTick` 是可选的绘图到达时刻，必须不早于本段 `departureTick`；它只控制 WebUI 运行图斜线终点，不参与运行时调度。
- `minimumDwellTicks`、`stoppingOffsetMetersBefore` 不能为负；`doorSide` 接受大小写形式的 `none/left/right/both`。
- 若 target 是 StationBerth Destination，Bukkit 在 Worker 结束后根据当前世界角色把统一 destination options 解析为 StationCall，执行 berth 分配、停准、开关门、dwell 和车尾释放。
- 普通 Destination 使用同一停车偏移、开门和 minimum dwell 语义，但不申请 StationBerth 资源。
- 每个 pass 段和最终 destination 段都会进行方向感知的 dry-run；任何一段不可达都会拒绝加载。

内部持久化仍可能把这条线路规范化为 `DESTINATION_OPTIONS + DESTINATION`，这是结构化运行模型，不是面向脚本作者的 `goto/call` API。

### `[t.at(tick).]wait(ticks)`

`wait()` 只等待指定时长，不带 Destination、StationBerth、开门或停车偏移语义；它不能代替 `destination(..., { minimumDwellTicks })`：

```js
t.at(2000).wait(100)
```

它也可以省略 `at()` 并沿用最近一次显式 Tick。车站和普通终点停站必须使用 `pass(...).destination(...)`，不能用逐段移动与 `wait()` 拼接。

### `[t.at(tick).]reverse()` 与 `[t.at(tick).]halt()`

```js
t.at(3000).reverse()
t.at(3200).destination("return-platform")
t.at(22000).halt()
```

- 两者都可省略 `at()`，并沿用最近一次显式 departure Tick。
- `reverse()` 要求列车已经停稳；Dispatcher 会执行真实列车换向，等待一个 dispatcher tick，再让下一条 Mission 使用相反起步方向。
- `halt()` 通过当前车辆模板的 operation gateway 执行 `power-off` 动作；模板不支持或 operation 失败会明确 Stall 当前 run。
- 终点站换向应放在前一条 `destination()` 之后。车站状态机会先完成停准、最短停站和关门，再推进到 `reverse()`，因此它表达“到站后、下一次发车前换向”。

### `t.at(tick).action(typeId, version?, parameters)`

调用插件注册的结构化扩展动作：

```js
t.at(2100).action("operator:chime", { text: "Departing" })
t.at(2200).action("operator:set_display", 2, { destination: "Central" })
```

`parameters` 必须是 JSON 兼容对象。Bukkit 加载阶段会检查 provider、版本、参数 schema 和允许的 scope；JS 不能直接取得插件对象或执行任意 Java 方法。

### 构建期 JavaScript `if / else`

Worker 执行的是普通 JavaScript，因此可以使用变量、函数、数组和条件语句决定最终生成哪些结构化步骤：

```js
const express = true
if (express) {
  t.at(1000).pass("a").destination("express-platform")
} else {
  t.at(1000).pass(["a", "local-1"]).destination("local-2")
}
```

构建期条件只在 Worker 编译时运行一次；加载完成后不会随世界状态变化。脚本没有 Bukkit、文件、网络、环境变量或当前世界数据访问能力。

### 结构化运行期条件

先在 timetable builder 上构造条件树：

```js
const late = timetable.predicate("javarailways:lateness_at_least", { ticks: 200 })
const suitable = timetable.all(
  timetable.predicate("javarailways:train_model", { models: ["s100"] }),
  timetable.not(timetable.predicate("operator:maintenance", { active: true }))
)
```

可用方法：

- `predicate(typeId, parameters)`；
- `predicate(typeId, version, parameters)`；
- `all(...conditions)`；
- `any(...conditions)`；
- `not(condition)`。

使用 `whenCondition` 把条件保存到 runtime branch：

```js
t.at(2100).whenCondition(
  late,
  actions => actions.action("operator:announce", { text: "Delayed" }),
  actions => actions.action("operator:announce", { text: "On time" })
)
```

这里的回调只构造扩展动作列表，不会在运行时再次执行 JavaScript。首版 conditional branch 有意只允许 `action(...)` 扩展动作，核心移动和安全动作不能放入分支，以保持路线验证确定。

### `t.check(wayPointId)`

声明/校验当前运行位置已经到达某个路径点。

```js
t.check("a0")
```

用途：

- 检查路径点是否存在。
- 更新 validator 的“当前位置”，后续链式线路会从这个点开始验证经路。

示例：

```js
timetable.service("001", function(t) {
  t.check("depot1")
  t.at(1000).destination("a0")
  t.check("a0")
  t.at(2000).destination("b0")
})
```

## 路径点要求

时刻表可以引用 RailObjectWand 创建的 legacy WayPoint，也可以引用结构化 StationBerth、Depot StablingPoint 和普通 Operational Destination 的稳定 destination id。

可用命令查看：

```text
/jr waypoint list
/jr waypoint info <id>
```

legacy WayPoint 本身不带方向；Operational Destination 可以声明 approach direction。经路验证会遵守轨道方向限制和 destination 进站方向，并在合法候选中选择最短路线。

## 编辑器补全与类型检查

仓库提供 `types/timetable.d.ts`。示例脚本可以在文件开头引用它：

```js
// @ts-check
/// <reference path="../../types/timetable.d.ts" />
```

并为入口参数添加 JSDoc：

```js
/** @param {JavaRailwaysTimetableBuilder} timetable */
function main(timetable) {
  // ...
}
```

运行静态检查：

```bash
cd docs-site
npm run check-dsl-types
```

JavaScript 的 `number` 类型不能表达“必须是整数”，因此 tick 的整数约束仍由运行时 DSL 校验。

## Dry-run 验证内容

加载分为两层：

1. **Worker 编译边界**
   - 文件必须位于 `plugins/JavaRailways/timetables/`，名称只能包含安全文件名字符，符号链接会被拒绝；
   - 源码、结果、内存和执行时间不得超过沙箱限制；
   - 必须定义 `function main(timetable)`；
   - `Java.type`、Host class、IO、网络、线程、native/polyglot access 不可用；
   - DSL 参数必须可转换为当前 `SavedTimetableV1`，JSON parameters 必须兼容。
2. **Bukkit 世界 dry-run**
   - timetable/service ID、周期和 tick 顺序；
   - line、Waypoint、Destination、Station/Berth、Depot/StablingPoint 和 Train 引用；
   - 动态 selector、取存车顺序、车型标签/能力和编组约束；
   - StationCall 与下一条 movement 的配对及站台是否位于路线；
   - `pass` / `destination` 每段的方向与可达性；
   - extension condition/action provider、版本、参数 schema 和 scope；
   - conditional activation 与 runtime branch 的结构合法性。

验证失败时不会写入新时刻表，也不会影响同 ID 的已加载版本，更不会自动启动 service。WebUI 预览使用同一 Worker 和世界验证，但始终只读。

## 错误示例

### 缺少入口函数

```js
// 错误：没有 main
function build(timetable) {}
```

会提示缺少：

```text
function main(timetable) {...}
```

### tick 倒退

```js
function main(timetable) {
  timetable.service("001", "train-001", function(t) {
    t.check("depot1")
    t.at(2000).destination("a0")
    t.at(1000).destination("b0")
  })
}
```

### 路径点不存在

```js
function main(timetable) {
  timetable.service("001", "train-001", function(t) {
    t.check("depot1")
    t.at(1000).destination("missing_waypoint")
  })
}
```

### 参数类型错误

```js
function main(timetable) {
  timetable.service("001", "train-001", function(t) {
    t.check("depot1")
    t.at(1000).pass(123).destination("a0") // 错误：pass 只接受字符串或字符串数组
  })
}
```

## 完整示例

仓库中的可检查示例位于 `examples/timetables/demo.js`。下面展示同一类完整脚本：

```js
const outboundPasses = ["a0", "a1"]
const inboundPasses = ["a1", "a0"]

function main(timetable) {
  timetable.setPeriodDays(1)

  timetable.service("001", "train-001", function(t) {
    t.check("depot1")
    t.at(1000).pass(outboundPasses).destination("b0", { minimumDwellTicks: 200 })
    t.at(4500).pass(inboundPasses).destination("depot1")
  })

  timetable.service("002", "train-002", function(t) {
    t.check("depot2")
    t.at(3000).pass(outboundPasses).destination("b0", { minimumDwellTicks: 200 })
    t.at(6500).pass(inboundPasses).destination("depot2")
  })
}
```

## 当前限制

- JS 是**导入期模型生成器**，不是每 tick 执行的运行时脚本；世界状态判断必须使用结构化 condition provider。
- WebUI 只读展示服务器文件，不提供上传、编辑、发布或加载按钮；显式加载只能由游戏内管理员执行。
- conditional branch 首版只接受插件扩展动作，不能把 `pass/destination`、`reverse`、Depot 或其他核心安全动作放进分支。
- Worker 是受限 GraalJS Context 加独立 JVM、内存/时间/输出限制，不是操作系统容器。它显著隔离 JVM 故障和 Host API，但服务器管理员仍应审查脚本来源。
- Dispatcher 不会预先解决整张时刻表的容量、会让、越行或全局晚点优化；运行时只处理实际申请的原子 Route Section 冲突。
- 脚本文件本身由服务器管理员管理；结构化加载结果按现有世界持久化流程处理，但活动 service 是否运行仍由显式 `start` 和 runtime snapshot 决定。
