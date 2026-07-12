# MineRails JS 时刻表语法说明

本文档描述当前 Phase 5 首版 JS 时刻表 DSL。JS 脚本只用于生成 MineRails 内部 `Timetable` 模型；加载后会先 dry-run 验证，通过后才写入运行期时刻表 store。

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

如果省略 `[id]`，时刻表 id 使用脚本文件名去掉 `.js` 的部分。

## 入口函数

每个脚本必须定义：

```js
function main(timetable) {
  // 在这里构建时刻表
}
```

`main` 参数 `timetable` 是 MineRails 提供的 DSL 对象。

## 基本示例

```js
function main(timetable) {
  timetable.setPeriod(16000)

  timetable.service("001", ["depot1"], function(t) {
    t.at(1000).goto("a0")
    t.at(2000).stop(100)
    t.at(2200).goto("b0")
  })
}
```

含义：

- 时刻表周期为 `16000` tick。
- 定义车次/服务 `001`。
- 初始候选位置为路径点池 `["depot1"]`。
- 在 `1000` tick 前往 `a0`。
- 在 `2000` tick 停留 `100` tick。
- 在 `2200` tick 前往 `b0`。

## 顶层 API

### `timetable.setPeriod(ticks)`

设置周期，单位为 tick。

```js
timetable.setPeriod(16000)
```

规则：

- `ticks` 必须大于 0。
- 当前阶段只记录和验证，不实际循环调度。

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

### `t.at(tick).goto(wayPointId)`

在指定 tick 前往一个路径点。

```js
t.at(1000).goto("a0")
```

规则：

- `tick` 必须大于等于 0。
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

- `ticks` 必须大于 0。
- 当前阶段只记录停站/停留动作，不驱动车辆。

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

## Dry-run 验证内容

加载脚本时会验证：

- 脚本是否存在。
- 是否定义了 `function main(timetable)`。
- DSL 参数类型是否正确。
- timetable id 是否非空。
- `periodTicks` 是否大于 0。
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

```js
const depot = ["depot1", "depot2"]
const outbound = ["a0", "a1", "b0"]
const inbound = ["b0", "a1", "a0", "depot1"]

function main(timetable) {
  timetable.setPeriod(16000)

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

Phase 5 只实现“脚本加载 + 内部模型生成 + dry-run 验证”。当前还不会：

- 自动选择真实列车。
- 自动控制列车运行。
- 检查同一时刻线路占用冲突。
- 执行周期循环。
- 处理速度限制、方向限制、会让/越行等运营规则。

这些会在后续 Phase 中继续实现。
