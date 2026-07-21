---
title: JS 时刻表语法
slug: /timetable/
---

# MineRails JS 时刻表语法

WebUI 的 JS 编辑器是**只读的导入/预览界面**：脚本不会在浏览器或 Bukkit 进程中执行。后端应把源代码通过 stdin 交给独立 `ScriptWorker`，并把 timetable ID 作为唯一命令行参数。Worker 成功时 stdout 只有 `SavedTimetableV1` JSON；失败时退出码非零并在 stderr 报错。

## 沙箱

脚本运行于 GraalJS 24.2.2 的最小权限 Context。仅标注导出的 DSL 方法可见；禁止 host class lookup、文件与网络 IO、创建线程、native access 和 polyglot access。因此不能使用 `Java.type`、读取服务器文件、发起请求或调用 Bukkit API。脚本仍是普通 JavaScript，可以使用变量、函数、数组以及 `if/else`。不要编写无限循环；进程调用方仍应设置超时并在超时后终止 Worker。

## 文件位置、加载与 WebUI

服务器只读取以下目录中的普通 `.js` 文件，不接受 WebUI 上传或在线修改：

```text
plugins/MineRails/timetables/<name>.js
```

管理员在游戏内编译、验证并加载：

```text
/mr timetable load <file.js> [timetable-id]
```

加载过程异步启动独立 Worker，不阻塞 Bukkit/Folia simulation context。只有 Worker 编译成功且当前世界验证通过后，结构化时刻表才会写入 store。WebUI 的“JS 时刻表”页面会以相同沙盒编译文件，随后只读显示源代码、验证结果和运行图；页面没有上传、编辑或发布 JS 的接口。

## 入口和周期

```js
function main(timetable) {
  timetable.setPeriodDays(1)
}
```

必须定义 `main(timetable)` 并调用 `setPeriodDays`（正整数）。兼容入口 `setPeriod(ticks)` 只接受 24000 的正整数倍。

## Service

```js
timetable.service("001", "train-001", t => {
  t.check("depot")
  t.at(1000).goto(["a", "b"])
  t.at(2000).stop(100)
  t.at(2200).reverse()
  t.at(3000).halt()
})
```

selector 为字符串 train ID 或 `null`。省略 selector 的动态 service 应以 `t.at(0).takeFromDepot(...)`（兼容别名 `depot(...)`）开始，并可用 `storeAtDepot` 返回车库。`check`、`at(...).goto/stop/reverse/halt/call` 对应当前持久化模型。`goto` 接受一个 ID 或非空 ID 数组。Depot 与 call options 的完整静态类型见 `types/timetable.d.ts`。

普通 JS 条件在**构建时**决定输出：

```js
if (isExpress) t.at(1000).goto("express-platform")
else t.at(1000).goto("local-platform")
```

## 结构化运行时条件

运行时条件不会执行 JS 回调逻辑。脚本只构造可持久化的条件树和扩展动作：

```js
const clear = timetable.predicate("signals:clear", { section: "A" })
t.at(1200).whenCondition(clear,
  a => a.action("station:announce", { text: "Departing" }),
  a => a.action("station:announce", { text: "Waiting" }))
```

条件可用 `all(...)`、`any(...)`、`not(...)` 组合。Service 也可以声明运行期条件启用：

```js
const demand = timetable.predicate("operations:demand", { minimum: 2 })
timetable.service("S2", "train-002", {
  lineId: "metro",
  activation: {
    type: "conditional",
    condition: demand,
    falseBehavior: "SKIP_CYCLE"
  }
}, t => {
  t.check("depot")
  t.at(100).goto("central@one")
})
```

首版分支只接受 `action(typeId[, version], JSON parameters)` 扩展动作，以保持路线构建确定性；是否有对应 provider 由 Bukkit 导入阶段验证。普通 `if/else` 与 `whenCondition` 不同：前者构建时执行，后者保存为 `CONDITIONAL` 供运行时判断。

## 导入语义

Worker 只做确定性的语法执行和模型编码，不访问世界状态。Bukkit 侧导入者必须解码 `SavedTimetableV1`，按当前世界验证 waypoint、depot、station、line 和 extension provider，验证全部通过后才替换 store 中的数据。WebUI 不获得修改脚本或直接把脚本结果写入 Bukkit store 的能力；显式加载仍只能通过游戏内管理员命令完成。

完整示例见 `examples/timetables/demo.js`。
