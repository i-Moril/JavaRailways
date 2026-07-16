---
title: JS 时刻表语法
slug: /timetable/
---

# MineRails JS 时刻表语法说明

:::warning 实验性能力
本文档描述当前 JS 时刻表 DSL。C1/C2/C3/T1/T3 运行时可以执行显式 `TrainId` 或 Depot 动态配车 service 的连续 `GoTo`/`GoVia`、`takeFromDepot`/`storeAtDepot`、普通 `Stop` dwell、显式 Station/Berth call、可确认车门操作，以及共同 epoch 下的 not-before 计划时间；尚不支持自动创建下一 cycle。进度见 [ATO 路线图](architecture/ato-roadmap.md)。
:::

:::info 已确认但尚未全部实现的语义
[给定时刻表运营闭环设计](architecture/timetable-operation-design.md) 已固定整张运营闭环。`setPeriodDays(days)`、管理员 start 的共同 epoch、`at(tick)` not-before、lateness 和 Depot 动态取存车已落地；自动下一 cycle 仍以 [验收清单](architecture/timetable-operational-closure-checklist.md) 为准。
:::

JS 脚本只用于生成 MineRails 内部 `Timetable` 模型；加载后会先 dry-run 验证，通过后才写入当前世界的运行期时刻表 store。

## 文件位置与命令

推荐把脚本放在：

```text
plugins/MineRails/timetables/<name>.js
```

加载：

```text
/mr timetable load <file.js> [id]
```

示例：

```text
/mr timetable load demo.js
/mr timetable load demo.js line_a_morning
```

查看已加载时刻表：

```text
/mr timetable list
```

输出内部结构：

```text
/mr timetable dump <id>
```

实验性启动和状态管理：

```text
/mr timetable start <id> [service]
/mr timetable status <id> [service]
/mr timetable stop <id> [service]
/mr timetable replan <id> <service> <current-waypoint>
```

service 可以使用字符串 `trainId` selector，并在第一个运行动作前通过 `check()` 指定初始 WayPoint；也可以省略 selector，并把 `takeFromDepot` 作为第一步。后续 `goto` 会在前一 Mission 停稳以及可选 `stop` dwell 完成后继续。

`stop` 命令请求受控制动；已确认经路不会被强制释放，必须等列车尾部越过 release point。人工接管会让车次进入 `SUSPENDED`，释放驾驶权后进入 `REPLAN_REQUIRED`；管理员只能在列车停止、位于指定 WayPoint 且旧资源已由车尾释放后使用 `replan` 创建新 Mission。一般 `at(tick)` 时间只参与冲突优先级，不直接作为道岔切换时刻；StationCall 可以通过 `plannedDepartureTick` 明确延长发车保持。

如果省略加载命令的 `[id]`，时刻表 id 使用脚本文件名去掉 `.js` 的部分。当前命令要求由玩家执行，因为 loader 使用玩家所在的 Bukkit 世界完成路径验证。

## 入口函数

每个脚本必须定义：

```js
function main(timetable) {
  // 在这里构建时刻表
}
```

`main` 参数 `timetable` 是 MineRails 提供的 DSL 对象。

:::danger 只加载可信脚本
当前时刻表 loader 使用 GraalJS `allowAllAccess(true)`，没有沙箱或执行超时。脚本可以访问 JVM host，并且错误的死循环可能阻塞加载线程。只能加载服务器管理员完全信任的文件，不能接受玩家上传或第三方不可信脚本。
:::

## 基本示例

```js
function main(timetable) {
  timetable.setPeriodDays(1)

  timetable.service("001", ["depot1"], function(t) {
    t.at(1000).goto("a0")
    t.at(2000).stop(100)
    t.at(2200).goto("b0")
  })
}
```

含义：

- 时刻表周期为 `1` 个逻辑游戏日，即 `24000` Dispatcher Tick。
- 定义车次/服务 `001`。
- 初始候选位置为路径点池 `["depot1"]`。
- 在 `1000` tick 前往 `a0`。
- 在 `2000` tick 停留 `100` tick。
- 在 `2200` tick 前往 `b0`。

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
- 所有 `at(tick)` 和 planned departure 必须小于 `periodDays × 24000`；
- 管理员启动整张 timetable 时建立全部 service 共享的 cycle-zero epoch；
- 当前尚不会在周期结束后自动创建下一 cycle。

`setPeriod(ticks)` 暂时作为兼容入口保留，但 ticks 必须是 `24000` 的正整数倍；新脚本应使用 `setPeriodDays`。

### `timetable.service(id, builder)`

定义一个 service，不指定初始列车/位置。

```js
timetable.service("001", function(t) {
  t.at(1000).goto("a0")
})
```

### `timetable.service(id, trainSelector, builder)`

定义一个 service，并指定列车选择器。

```js
timetable.service("001", ["depot1"], function(t) {
  t.at(1000).goto("a0")
})
```

`trainSelector` 当前支持三种形式：

| 写法 | 含义 |
|---|---|
| 省略或 `null` | 任意列车，`AnyTrain` |
| 字符串 | 指定列车 id，`TrainId` |
| 字符串数组 | 路径点池，`WayPointPool` |

示例：

```js
// 任意列车
timetable.service("001", function(t) {})

// 指定列车 id
timetable.service("002", "train-uuid-or-id", function(t) {})

// 从路径点池选车；当前 validator 会把第一个路径点作为初始位置用于 dry-run
timetable.service("003", ["depot1", "depot2"], function(t) {})
```

## Service 内 API

在 `service(..., function(t) { ... })` 中，`t` 是 service builder。

### `t.at(tick).takeFromDepot(depotId, options?)`

从 home depot 中原子选择并保留一列兼容的 `AVAILABLE` 列车。它必须是 service 的第一步，并且不能同时声明 `trainSelector`。

```js
timetable.service("001", function(t) {
  t.at(0).takeFromDepot("north", {
    tags: ["metro"],
    minimumCars: 4,
    maximumCars: 8,
    minimumSpeedMetersPerSecond: 16.5,
    capabilities: ["doors"]
  })
  t.at(1000).goto("central-1")
})
```

规则：

- 当前必须声明为 `t.at(0).takeFromDepot(...)`，并作为 service 的第一步；
- `tags` 和 `capabilities` 是必须全部满足的字符串数组；
- 编组上下界和最低速度均为严格筛选条件，不会退化选择不兼容车辆；
- 候选先按稳定 train ID 排序，再使用 timetable/cycle/service 派生 seed 可复现选择；
- 被选列车必须仍停在其 directed StablingPoint；
- 无匹配 `AVAILABLE` 列车会触发当前 World 的 ATO Stall；
- 未知 option、非法范围、缺失 Depot/StablingPoint 会导致 loader 失败。

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
/mr depot assign <train> <depot> <point> [tagsCsv] [capabilitiesCsv]
/mr depot list [depot]
```

### `t.at(tick).goto(wayPointId)`

在当前 timetable cycle 的指定 tick 或之后前往一个路径点。若前序动作导致晚点，条件满足后立即执行并记录 lateness；不会跳过动作。

```js
t.at(1000).goto("a0")
```

规则：

- `tick` 必须是大于等于 0 的整数。
- 同一个 service 内，`at` 的 tick 不应倒退。
- `wayPointId` 必须是已存在路径点。
- 如果 validator 能确定当前位置，会验证当前位置到目标路径点是否可达。

### `t.at(tick).goto([wayPointId1, wayPointId2, ...])`

按顺序经过多个路径点。

```js
t.at(2200).goto(["a1", "a2", "b0"])
```

等价于一次 `GoVia`：

- 当前点 -> `a1`
- `a1` -> `a2`
- `a2` -> `b0`

规则：

- 数组不能为空。
- 数组元素必须是非空字符串。
- 每一段都会 dry-run 验证可达性。

### `t.at(tick).stop(ticks)`

停留一段时间。

```js
t.at(2000).stop(100)
```

规则：

- `ticks` 必须是大于 0 的整数。
- 运行时会在前一 Mission 停稳后按 Dispatcher Tick 计时；它不是 Station berth 或车门操作。

### `t.at(tick).call(stationId, berthId, options?)`

安排一次显式 StationBerth 停站：

```js
t.at(1000).call("central", "platform-1", {
  minimumDwellTicks: 100,
  plannedDepartureTick: 1400,
  stoppingOffsetMetersBefore: 1.5,
  doorSide: "left"
})
t.at(1500).goto("next-waypoint")
```

规则：

- `stationId` 和 `berthId` 必须显式指定且已注册；C2 不自动选台。
- call 后必须直接配对一个 `goto`，并且 berth 必须位于该 Mission 经路前缀上。
- `minimumDwellTicks`、`plannedDepartureTick` 和 `stoppingOffsetMetersBefore` 不能为负。
- `plannedDepartureTick` 不能早于 call 的 `at(tick)`。
- `doorSide` 只接受 `none`、`left`、`right`、`both`。C3 会通过平台 operation gateway 执行开关门；`none` 保持无车门路径。
- 未知 options 会导致 loader 严格失败。
- 列车头部是固定停车对齐参考点；berth 所有权在车尾越过 release boundary 后释放。

### `t.check(wayPointId)`

声明/校验当前运行位置已经到达某个路径点。

```js
t.check("a0")
```

用途：

- 检查路径点是否存在。
- 更新 validator 的“当前位置”，后续 `goto` 会从这个点开始验证经路。

示例：

```js
timetable.service("001", function(t) {
  t.check("depot1")
  t.at(1000).goto("a0")
  t.check("a0")
  t.at(2000).goto("b0")
})
```

## 路径点要求

时刻表中引用的是 RailObjectWand 创建的 WayPoint id。

可用命令查看：

```text
/mr waypoint list
/mr waypoint info <id>
```

WayPoint 本身不带方向；经路验证会尝试 AB/BA 方向组合，并选择可达路线中最短的一条。方向限制后续会由独立运营 metadata 表达。

## 编辑器补全与类型检查

仓库提供 `types/timetable.d.ts`。示例脚本可以在文件开头引用它：

```js
// @ts-check
/// <reference path="../../types/timetable.d.ts" />
```

并为入口参数添加 JSDoc：

```js
/** @param {MineRailsTimetableBuilder} timetable */
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

加载脚本时会验证：

- 脚本是否存在。
- 是否定义了 `function main(timetable)`。
- DSL 参数类型是否正确。
- timetable id 是否非空。
- `periodDays` 是否为正整数且换算 Tick 不溢出。
- service id 是否非空、是否重复。
- `tick` 是否非负、是否倒退。
- `stop(ticks)` 是否大于 0。
- 引用的路径点是否存在。
- 可确定起点时，`goto` / `goVia` 经路是否可达。

验证失败时，加载不会写入新的时刻表，也不会影响已经加载的旧时刻表。

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
  timetable.service("001", ["depot1"], function(t) {
    t.at(2000).goto("a0")
    t.at(1000).goto("b0")
  })
}
```

### 路径点不存在

```js
function main(timetable) {
  timetable.service("001", ["depot1"], function(t) {
    t.at(1000).goto("missing_waypoint")
  })
}
```

### 参数类型错误

```js
function main(timetable) {
  timetable.service("001", ["depot1"], function(t) {
    t.at(1000).goto(123) // 错误：goto 只接受字符串或字符串数组
  })
}
```

## 完整示例

仓库中的可检查示例位于 `examples/timetables/demo.js`。下面展示同一类完整脚本：

```js
const depot = ["depot1", "depot2"]
const outbound = ["a0", "a1", "b0"]
const inbound = ["b0", "a1", "a0", "depot1"]

function main(timetable) {
  timetable.setPeriodDays(1)

  timetable.service("001", depot, function(t) {
    t.at(1000).goto(outbound)
    t.check("b0")
    t.at(4000).stop(200)
    t.at(4500).goto(inbound)
  })

  timetable.service("002", depot, function(t) {
    t.at(3000).goto(outbound)
    t.check("b0")
    t.at(6000).stop(200)
    t.at(6500).goto(inbound)
  })
}
```

## 当前限制

当前 loader 和 C3 Dispatcher 仍不会：

- 在周期结束时自动创建下一 cycle。
- 从 Depot 按车辆能力动态选择、取出或存入真实列车。
- 从 WayPointPool 或 `AnyTrain` 自动选择真实列车。
- 根据整张时刻表预先计算线路容量或会让关系；运行时只处理已触发 Route Section 的原子冲突。
- 执行周期循环。
- 提供 MANUAL_ROUTED 人工被动进路模式。
- 处理会让、越行或晚点优化等高级运营规则。
- 持久化已加载时刻表或活动 epoch；重启后需要重新加载并由管理员启动。
- 限制脚本的 host access、执行时间、内存或死循环。

这些会在后续 Phase 中继续实现。在沙箱完成前，“支持 JavaScript”不表示可以安全执行不可信脚本。
