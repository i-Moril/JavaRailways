# 自动时刻表初步计划

初期的自动时刻表不实现完整信号系统、闭塞和联锁；后期接入信号系统可参考 `signal_system_next_steps.md`。

目标是先实现一个可用的“低密度运营”系统：列车数量不多，运行路径由时刻表明确给出，控制器负责自动驾驶、停车、避让前车、设置道岔。

## 总体原则

1. **先做可运行 MVP，再接入信号系统**
   - 初期允许用简化安全逻辑：前方有车则减速/停车。
   - 不要求完整闭塞、进路锁闭、敌对进路检查。

2. **路径保存为展开后的完整经路**
   - 不再只保存分叉点选择。
   - 每个自动运行任务直接保存预期经过的 `RailView`/轨道段序列，以及目标路径点。
   - 好处：实现简单，调试直观；当前列车数量不多，内存开销可以接受。
   - 后续如果规模变大，再考虑压缩成“关键分叉选择 + 动态展开”。

3. **控制器只负责运行，调度器负责时刻表状态**
   - `ATOController` 负责根据当前目标、限速、前方障碍控制速度。
   - `TimetableManager` / `ServiceDispatcher` 负责发车、停站、下一任务、循环周期、失败处理。

4. **Basic 模块负责运营层数据**
   - 路径点、限速、方向限制、时刻表占用、自动运行任务都属于 Basic。
   - Core 只提供必要公共 API，不应让 Basic 直接依赖 Core 内部类。

5. **事件系统作为通知层，不作为第一版核心同步机制**
   - 当前已有 `EventManager` / `EventListener` / `@HandlesEvent`，并已有 `CollisionEvent`、`DerailEvent`、`PassSignalEvent` 等事件。
   - 但当前模块同步主要依靠 `TickManager`、`ExtensionData`、直接 API 调用和 commit/postTick 流程。
   - 自动时刻表第一版的核心运行状态应由 `TimetableDispatcher` 和 `ATOController` 的状态机维护。
   - 事件系统后续用于通知和扩展钩子，例如到站、发车、服务失败等。

## 需要补充的基础数据

### BasicWorldData

建议在 Basic 中建立世界级数据容器：

```kotlin
data class BasicWorldData(
    val railMap: RailMap,
    val railMetadata: RailMetadataStore,
    val wayPoints: WayPointStore,
    val timetables: TimetableStore,
    val dispatcher: TimetableDispatcher
)
```

当前 `World.railMap` 还走测试用 `TestRailMapRegistry`，需要改成优先从 `world.getExtensionData(BasicPlugin.INSTANCE)` 读取。

### 路径点 / 目的地

现有 `RailDestination` 只有 label 和 railView，位置固定在 `arcLength = 0`，不足以表达精确的寻路基准点。

建议新增：

```kotlin
data class WayPoint(
    val id: String,
    val displayName: String,
    val railViewRef: RailViewRef,
    val arcLength: Double,
    val tolerance: Double = 2.0,
    val allowReverse: Boolean = false
)
```

路径点是列车执行寻路动作的基准点，不代表列车一定停车；是否停车由时刻表 step 决定。`RailDestination` 可以继续作为简单标签索引，但自动时刻表应以 `WayPoint` 为主要目标。

### 展开后的经路

自动运行任务直接保存完整路径：

```kotlin
data class ExpandedRoute(
    val id: String,
    val segments: List<RouteSegment>,
    val targetStop: String?
)

data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double,
    val speedLimit: Double? = null
)
```

说明：

- `railViewRef` 应能持久化，例如 rail UUID + direction。
- `startArcLength/endArcLength` 用来表达局部区间，方便计算剩余距离和限速。
- 如果第一版嫌麻烦，也可以先只保存 `List<RailViewRef>`，目标点另存为 `StopPoint`；距离计算按整段轨道处理。

## 列车自动控制

### 初期计划

在 Basic 模块实现统一的 ATO 控制器，使用硬编码性能参数：

- 最大速度
- 加速度
- 常用制动减速度
- 紧急制动减速度
- 安全距离
- lookup 距离

功能：

1. 保存当前任务的展开经路。
2. 沿展开经路向前 lookup 一定距离。
3. 检测前方列车。
   - 前方安全距离内有其他列车：目标速度降为 0。
   - 前方列车驶离：恢复按限速/时刻表运行。
4. 检测前方限速。
   - 根据制动距离提前降速。
5. 接近目标停车点时自动制动并停车。
6. 到站后通知调度器进入停站计时。
7. 在经过分叉前设置 `activeRail`，使道岔指向预期路径。
8. 如果列车偏离预期经路，进入失败状态并停车。

MVP 可以先实现：

```text
目标速度 = min(默认最高速度, 当前/前方限速, 到停车点所允许速度, 前车约束速度)
```

制动判断：

```text
制动距离 = v² / (2 * deceleration)
如果 制动距离 + 安全余量 >= 到目标/障碍距离：
    减速
否则：
    加速或保持
```

### 前方列车检测

初期可以基于当前已有的车头/车尾对象检测，但要明确这是简化模型：

- 需要排除本车自己的 head/tail。
- 对长列车跨多段轨道的占用不完全准确。
- 后续应升级为 `TrainEnvelope` 或接入闭塞/移动授权。

### 限速与方向限制

Basic 模块需要为轨道保存 metadata，例如：

- 曲率半径或设计速度，用于自动计算限速。
- 人工限速标记。
- 允许列车行进方向。
- 是否有时刻表/自动运行任务正在使用该轨道。
- 道岔命名与状态计划。

限速牌、方向牌可以作为 Basic 的运营对象保存；渲染层可以把它们显示成牌子/模型。

不建议第一版强行把所有 Basic 运营对象塞进 Core 的 `RailObject`。可以先由 Basic 自己维护 metadata，并给 ATO 查询。

### 轨道编辑锁定

如果某条轨道正在被自动时刻表使用，应该禁止：

- 删除轨道
- 修改轨道曲线
- 修改端点
- 修改连接关系
- 修改当前自动运行依赖的道岔路径

建议提供统一检查：

```kotlin
fun BasicWorldData.canModifyRail(railId: UUID): Boolean
```

Bukkit 的 `RailCommand` 和 `RailWand` 在修改前都调用它。

### 中期 / 后期计划

1. 接入信号系统。
2. 接入固定闭塞或移动授权。
3. ATO 不再直接决定所有道岔，而是向联锁/进路系统申请。
4. 列车性能从硬编码改为列车型号配置。
5. 支持模拟手柄控制与 ATP 包裹手动驾驶。

## 时刻表定义

自动时刻表要表达两类东西：

1. **静态计划**：有哪些服务、什么时候发车、去哪、停多久。
2. **运行时状态**：当前服务进行到哪一步、对应哪列车、是否晚点/失败。

建议模型：

```kotlin
data class Timetable(
    val id: String,
    val periodTicks: Long?,
    val services: List<TimetableService>
)

data class TimetableService(
    val id: String,
    val trainSelector: TrainSelector,
    val steps: List<TimetableStep>
)

sealed class TimetableStep {
    data class At(val tick: Long, val action: TimetableAction) : TimetableStep()
    data class Check(val stopPointId: String) : TimetableStep()
}

sealed class TimetableAction {
    data class GoTo(val stopPointId: String) : TimetableAction()
    data class GoVia(val stopPointIds: List<String>) : TimetableAction()
    data class Stop(val ticks: Long) : TimetableAction()
}
```

示例语义：

```text
at(1000).goto("depart")
check("depart")
at(1500).goto("a0")
at(2000).stop(100)
at(2100).goto(["d1", "d2", "d3"])
```

运行前应支持一次 dry-run：

- 检查 stopPoint 是否存在。
- 检查路径是否可达。
- 展开所有经路。
- 检查涉及轨道是否可被自动运行占用。
- 检查同一时刻是否有明显路径冲突。

## JS 时刻表脚本方案

决定第一版直接支持 **JS 时刻表脚本**。脚本由服务器管理员编写，默认可信，因此初期不把沙箱作为核心需求。

JS 的定位不是实时控制器，而是**时刻表生成器**：

```text
JS 文件 -> 执行 main(timetable) -> 生成 Kotlin Timetable 数据 -> dry-run 验证 -> 启用运行
```

运行时每 tick 不再执行 JS，只运行已经生成并验证过的 `Timetable`、`TimetableService` 和 `ExpandedRoute`。

这样既保留 JS 的表达力，又避免把列车控制逻辑绑死在脚本解释器上。

### 为什么选择 JS

JS 适合当前需求：

- 语法接近初始设想。
- 管理员和插件开发者更容易理解。
- 方便定义数组、函数、循环、批量生成车次。
- 适合表达出入库、交路、周期运行、复用经路。

Lua 暂不作为第一目标。后续如果需要，可以在同一个 `Timetable` 数据模型上再加 Lua 生成器。

### JS 引擎

现代 JVM 默认不再自带 Nashorn，建议优先考虑 GraalJS。

需要评估：

- 依赖体积。
- Bukkit/Paper 环境兼容性。
- Gradle 打包方式。
- JS 异常堆栈是否足够可读。

### 脚本能力边界

初期不重点做安全沙箱，但仍建议只暴露一个 DSL 对象给脚本，脚本通过 DSL 生成时刻表计划。

脚本不应直接持有或操作：

- `CoreWorld`
- `CoreTrain`
- `RailView`
- Bukkit 实体
- TickManager

这不是主要出于安全考虑，而是为了：

- 保持底层 Kotlin API 稳定。
- 方便 dry-run 验证。
- 方便 dump 生成结果。
- 方便以后改用其他配置格式或编辑器。

示例：

```js
const depot = ["depot1", "depot2", "depot3"]
const aToB = ["d1", "d2", "d3"]

function main(timetable) {
  timetable.setPeriod(16000)

  timetable.service("001", depot, function(t) {
    t.at(1000).goto("depart")
    t.check("depart")
    t.at(1500).goto("a0")
    t.check("a0")
    t.at(2000).stop(100)
    t.at(2100).goto(aToB)
    t.check("d3")
  })
}
```

### DSL 建议

第一版 DSL 可以包括：

```js
timetable.setPeriod(ticks)
timetable.service(id, trainSelector, builder)

t.at(tick).goto(stopId)
t.at(tick).goto([stopId1, stopId2, ...])
t.at(tick).stop(ticks)
t.check(stopId)
```

后续可扩展：

```js
timetable.interval(prefix, trainSelector, startTick, endTick, intervalTicks, builder)
timetable.route(name, [stopId1, stopId2, ...])
t.selectDepot([stopId1, stopId2, ...])
t.repeat(count, builder)
t.reverseAt(stopId)
```

### 加载与验证流程

加载 JS 时刻表时：

1. 创建 `TimetableBuilder` DSL 对象。
2. 执行 JS 的 `main(timetable)`。
3. Builder 生成内部 `Timetable` 数据。
4. 对所有 `goto/check/stop` 做 dry-run：
   - 检查 stopPoint 是否存在。
   - 检查路径是否可达。
   - 展开经路。
   - 检查涉及轨道是否可被占用。
   - 检查明显的同一时刻路径冲突。
5. 验证通过后才能 start。

建议提供 dump 功能，把 JS 生成后的内部时刻表输出成 JSON，便于调试：

```text
/mr timetable dump <id>
```

### 脚本错误处理

虽然不做安全沙箱，但仍要做好可用性：

- JS 语法错误要显示文件名和行号。
- DSL 参数错误要提示 serviceId、step、stopId。
- dry-run 失败要列出不可达经路或冲突轨道。
- 加载失败不应影响已经运行的旧时刻表。

### 超时问题

管理员脚本默认可信，但仍可能因为误写死循环导致加载卡住。

第一版可以先不做强制超时；如果 GraalJS 集成方便，建议给脚本加载阶段加一个较宽松的超时保护。这个不是安全沙箱，而是防误操作保护。

## 事件系统与模块同步

当前 Core 已有轻量事件系统：

- `EventManager.register(listener)`
- `EventManager.unregister(listener)`
- `EventManager.fire(event)`
- `@HandlesEvent(priority = ...)`
- 支持按事件继承层级派发

当前实际使用的事件主要有：

- `CollisionEvent`
- `DerailEvent`
- `PassSignalEvent`
- `TrainEvent`

需要注意：

- `EventManager` 是全局 singleton，不是 per-world。
- 事件是同步调用，不是异步队列。
- 当前没有 cancellation。
- 当前没有 Bukkit 主线程保证，监听器不能随意操作 Bukkit API。
- `PreTickEvent` / `PostTickEvent` 文件存在，但当前没有接入 `TickManager` 派发。

自动时刻表第一版不依赖事件系统做核心同步。推荐：

- `TimetableDispatcher` 注册为 `TickingObject`，负责服务状态机。
- `ATOController` 作为列车控制器，负责自动驾驶。
- dispatcher 和 controller 之间直接通过状态/回调/API 协作。
- 事件仅用于外部通知、调试、日志、未来扩展。

后续可考虑新增事件：

- `TrainArrivedStopEvent`
- `TrainDepartedStopEvent`
- `TimetableLoadedEvent`
- `TimetableStartedEvent`
- `TimetableStoppedEvent`
- `TimetableServiceStartedEvent`
- `TimetableServiceFinishedEvent`
- `TimetableServiceFailedEvent`
- `RouteValidationFailedEvent`

如果后续要让事件系统承载更多职责，需要进一步设计：

- per-world event bus
- cancellation / result event
- 主线程派发约束
- tick 阶段事件：preTick/postCommit/postTick
- 监听器生命周期与插件卸载清理

## Wand 工具设计

WayPoint 不建议主要通过命令创建。路径点、信号机、限速牌、方向牌都属于“放置在轨道上的运营对象”，更适合通过游戏内 Wand 交互创建和编辑。

建议新增一个运营对象工具，优先命名为 **`OperationWand`**。

理由：它未来不只做信号，也会负责 WayPoint、限速牌、方向限制等运营对象。如果后续仍想保留玩家可见名称 `SignalWand`，可以让显示名叫 SignalWand，但代码结构按 OperationWand 设计。

它可以承担：

- 创建/删除/编辑 `WayPoint`
- 创建/删除/编辑限速标记
- 创建/删除/编辑方向限制
- 后续创建/删除/编辑信号机

### AbstractWand

当前 `RailWand` 已包含大量可复用机制，建议先提取公共基类或公共组件，避免 `SignalWand` 复制一套事件处理。

可抽取内容：

- Listener 生命周期：`initialized` 防重复初始化、事件注册、quit 清理入口。
- Wand 物品识别：`PersistentDataContainer` key、item type、display name、lore。
- 发放 wand item。
- per-player state 管理。
- mode 切换：Shift+F / Shift+切换物品。
- Shift+右键打开设置。
- Shift+左键清空选择。
- `PlayerInteractEvent` 分发。
- `PlayerQuitEvent` 清理 hook。
- action bar 提示。
- `getCoreWorld(player)` helper。

暂时不要抽取：

- `RailWand` 的 Anchor / Rail 创建、连接、编辑业务。
- `RailWand` 的 ConfigUI 具体 key 和 dialog 逻辑。
- `RailWand` 的 undo/redo；当前 `WandAction` 强绑定 anchor/rail，Phase2 不混做公共 undo 框架。
- 现有 `RailWand*Mode` 行为细节，先保持它们继续依赖 `RailWand`。

建议形态：

```kotlin
interface WandModeSpec {
    val displayName: String
    val description: String
}

interface WandState<M> {
    var mode: M
}

abstract class AbstractWand<S, M>(
    protected val plugin: BukkitPlugin,
    private val wandKey: String,
    private val material: Material,
    private val displayName: Component,
    private val lore: List<Component>,
    private val modes: List<M>
) : Listener
    where S : WandState<M>,
          M : Enum<M>,
          M : WandModeSpec {

    protected abstract fun createDefaultState(): S
    protected open fun clearSelection(player: Player, state: S) {}
    protected open fun showSettings(player: Player, state: S) {}
    protected abstract fun handleWandInteract(player: Player, state: S, event: PlayerInteractEvent)
    protected open fun onPlayerQuit(player: Player, state: S) {}
}
```

注意：泛型 enum 不能使用 `M.entries`，所以 `modes` 应由子类构造时传入。

`RailWand` 后续迁移到 `AbstractWand`；`OperationWand` 从一开始基于 `AbstractWand` 实现。

### OperationWand 初期交互

建议模式：

```text
WAYPOINT_CREATE     右键轨道附近创建路径点，右键已有路径点打开编辑 Dialog
WAYPOINT_EDIT       左键选择路径点，右键打开编辑
DELETE              左键选择对象，再次确认删除
SPEED_LIMIT         后续创建限速标记
DIRECTION_LIMIT     后续创建方向限制
SIGNAL              后续创建信号机
```

WayPoint 创建流程：

1. 玩家手持 `OperationWand` 右键轨道或轨道附近方块。
2. 取点击位置或 ray trace 位置。
3. `CoreWorld.getNearestRail(position)` 查找最近轨道。
4. `rail.curve.getNearestPoint(position)` 查找最近弧长。
5. 距离超过阈值则拒绝创建。
6. 根据玩家视角与轨道 tangent 点积判断 `ViewDirection.AB / BA`。
7. 创建正式 `WayPoint`。
8. 写入 `coreWorld.basicWorldData.wayPoints`。
9. 显示 WayPoint marker。
10. 打开设置 Dialog，让玩家填写路径点名称；创建模式下右键已有路径点也打开同一个 Dialog。

后续可以把 WayPoint marker 渲染成小牌子或粒子/展示实体。

WayPoint 数据第一版不要直接持有 `RailLocationWithoutFacing`，因为它的构造器偏 Core 内部实现；建议持久化为：

```kotlin
data class WayPoint(
    val id: String,
    val displayName: String,
    val railId: UUID,
    val arcLength: Double,
    val direction: ViewDirection
)
```

## Bukkit 命令建议

命令保留为管理和调试入口，不作为 StopPoint 的主要编辑方式：

```text
/mr wand rail
/mr wand signal

/mr stop list
/mr stop info <id>
/mr stop remove <id>       # 管理员兜底命令

/mr timetable load <file.js>
/mr timetable validate <id>
/mr timetable dump <id>
/mr timetable start <id>
/mr timetable stop <id>
/mr timetable status [id]
```

WayPoint 的创建/编辑优先通过 `OperationWand` 完成。

## 分步执行计划

按“可验证的纵向切片”推进，每个阶段都应有测试或游戏内命令能验证结果。

### Phase 0：确定 MVP 验收场景

最小目标：

```text
管理员创建停车点 A/B，加载一个 JS 时刻表，一列车从 A 自动开到 B，停车，然后继续下一步或结束服务。
```

暂不实现：

- 信号
- 闭塞
- 联锁
- 多车复杂冲突
- 图形化编辑
- 完整限速牌渲染

### Phase 1：BasicWorldData 与最小公共 API

状态：已完成初版。已建立 `BasicWorldData`，Bukkit 世界创建时注册该数据；`World.railMap` 优先读取 Basic 扩展数据，测试场景保留 registry fallback；Core API 已补 `World.allTrains`、`Train.headLocation/tailLocation`、`RailView.selectNextRail()`。

目标：让 Basic 有自己的运营层数据容器，不再依赖测试 registry 或 Core 内部类。

任务：

1. 建立 `BasicWorldData`。
2. 修正 `World.railMap` 获取方式，优先从 `BasicPlugin.INSTANCE` 的扩展数据读取。
3. 补充自动运行所需的最小公共 API，例如：
   - `World.allTrains`
   - `Train.headLocation`
   - `Train.tailLocation`
   - 设置道岔 active rail 的公共接口
   - 必要的前方 lookup API

验收：

- Bukkit 世界创建时会注册 `BasicWorldData`。
- 测试 world 仍可使用。
- Basic 不直接依赖 `CoreTrain` / `CoreRail` 内部实现。

### Phase 2：WayPoint 与 Wand 交互

状态：已进入第三个游戏内验证点。`StopPoint` 命名已调整为 `WayPoint`（路径点），用于列车寻路和时刻表动作引用，不代表一定停车；已新增 `AbstractWand` / `WandModeSpec` / `WandState`，并将 `RailWand` 的通用事件骨架、物品识别、发放、玩家 state 管理和模式切换迁移到 `AbstractWand`；已实现运行期 `WayPoint` / `WayPointStore`；已新增 `OperationWand`，支持右键轨道附近创建路径点并打开 Dialog 命名，右键已有路径点打开 Dialog 编辑名称，显示 marker；已支持 OperationWand 删除模式：左键选择路径点，再次左键确认删除；已新增 `/mr wand operation`、`/mr waypoint list|info|remove` 管理命令（`/mr stop` 暂留旧别名）。路径点 marker 和 RailWand 锚点均改为按玩家短生命周期渲染：玩家手持对应 Wand 时 spawn 非持久、默认不可见但对该玩家 show 的 Bukkit 实体，切走/退出时 remove；后续可替换为 packet-only renderer。`RailWand` 的 anchor/rail 业务、config、undo/redo 暂未抽取。

目标：先实现路径点，并通过新的运营对象 Wand 在游戏内创建和编辑。路径点是寻路基准点，不表示必须停车。

任务：

1. 新增 `AbstractWand`、`WandModeSpec`、`WandState`，先不改变 `RailWand` 行为。
2. 让 `RailWand` 继承 `AbstractWand`，只迁移通用事件骨架、物品识别、发放、玩家 state 管理和模式切换。
3. 保持 `RailWand*Mode` 基本不变，继续调用 `RailWand` 的 anchor/rail 业务方法。
4. 实现 `WayPoint`。
5. 实现 `WayPointStore`，第一版只做运行期 store。
6. 新增 `OperationWand`。
7. `OperationWand` 第一版支持创建 WayPoint、命名 Dialog 和显示 marker。
8. 选择/删除/编辑可分阶段补充，避免和 `AbstractWand` 提取混在一个大改动里。
9. 命令只保留管理和调试用途：
   - `/mr wand rail`
   - `/mr wand operation`
   - `/mr stop list`
   - `/mr stop info <id>`
   - `/mr stop remove <id>`

第一版行为：玩家手持 `OperationWand` 右键轨道或轨道附近方块，系统取最近轨道、方向和弧长创建路径点，并打开 Dialog 设置路径点名称；如果右键已有路径点，则打开 Dialog 编辑名称。

迁移风险：

- `RailWand` 旧 wand key 必须保持不变，否则旧物品失效。
- `OperationWand` 使用独立 key，避免两个 listener 同时处理同一物品。
- 玩家双持不同 wand 时，`SwapHandItemsEvent` 可能被多个 listener cancel；`isWandItem` 必须严格按 key 判断。
- Phase2 不要求持久化；如验收需要重启保留，另开持久化小阶段。

验收：

- `RailWand` 原有创建/连接/编辑轨道行为不受影响。
- 可以通过 `OperationWand` 在轨道上创建 A/B 两个路径点。
- 可以显示 WayPoint marker，且 marker 只在玩家手持 OperationWand 时显示。
- 可以通过命令列出路径点。
- 选择、删除、编辑如未在第一小步完成，应在后续 Phase2 子任务补齐。

### Phase 3：ExpandedRoute 与 dry-run

状态：已开始。已新增 `RailViewRef`、`RouteSegment`、`ExpandedRoute`、`RoutePlanner`，并补充公共 `Rail.getView(direction)` API；路径点已改为方向无关，`RoutePlanner` 会尝试起点/终点 AB、BA 视图组合并选择最短可达经路，方向限制后续由独立运营 metadata 表达；已新增 `/mr route validate <fromWaypoint> <toWaypoint>`，用于输出路径点之间的展开经路、总长度和轨道段列表。

目标：能从路径点 A 到 B 展开路径，但暂时不动车。

任务：

1. 实现 `RailViewRef`。
2. 实现 `ExpandedRoute` / `RouteSegment`。
3. 从起点 `WayPoint` 到终点 `WayPoint` 查找路径。
4. 计算路线总长度和到目标路径点距离。
5. 实现 dry-run 验证：
   - stop 是否存在
   - path 是否可达
   - route 是否能展开

验收：

- validate 命令或测试能输出 A -> B 经路、经过 rail 数量和总距离。

### Phase 4：Timetable Kotlin 内部模型

状态：已开始。已新增 `Timetable`、`TimetableService`、`TimetableStep`、`TimetableAction`、`TrainSelector`、`TimetableValidator` 与运行期 `TimetableStore`；validator 会检查路径点存在性、tick 顺序、stop 时长，并通过 `RoutePlanner` 展开/验证 goto/goVia 经路；已添加 Basic 单元测试覆盖路径展开和缺失路径点错误。

目标：先定稳定的内部数据模型，不急着接 JS。

任务：

1. `Timetable`
2. `TimetableService`
3. `TimetableStep`
4. `TimetableAction`
5. `TrainSelector`
6. `TimetableValidator`

验收：

- 测试里手写 Kotlin `Timetable`。
- validator 能生成所有 `ExpandedRoute`。
- 错误信息能指出 stop 不存在、路径不可达、列车选择失败等问题。

### Phase 5：JS Loader 与 DSL Builder

状态：已实现首版。Basic 已引入 GraalJS polyglot 依赖，新增 `TimetableBuilder`/`ServiceBuilder` DSL、`TimetableScriptLoader`、`TimetableDumper`；Bukkit 新增 `/mr timetable load <file.js> [id]`、`/mr timetable dump <id>`、`/mr timetable list`。加载流程为执行 `main(timetable)` → 生成 Phase 4 Kotlin 模型 → `TimetableValidator` dry-run → 验证成功后才替换/加入运行期 store；失败不会影响旧时刻表。已添加 JS 加载与语法错误单元测试。语法说明见 `docs/timetable_js_syntax.md`。

目标：JS 只负责生成 Phase 4 的 Kotlin 模型。

任务：

1. 引入 GraalJS 或选定 JS 引擎。
2. 实现 `TimetableBuilder` DSL。
3. 支持基础 DSL：

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

4. 添加命令：
   - `/mr timetable load <file.js>`
   - `/mr timetable dump <id>`

验收：

- JS 能加载并生成内部 `Timetable`。
- dump 能输出生成后的结构。
- JS 语法错误和 DSL 参数错误能清楚报告。
- 加载失败不影响已经运行的旧时刻表。

### Phase 6：ATOController 单车自动运行

目标：一列车能沿一条 `ExpandedRoute` 自动开到目标停车点。

任务：

1. 实现 `ATOController : TrainController`。
2. 使用固定性能参数：
   - maxSpeed
   - acceleration
   - deceleration
   - stopTolerance
3. 根据剩余距离自动制动。
4. 到站后停住并报告完成。

验收：

- 可以通过测试或临时命令给列车分配 route。
- 列车自动开到目标停车点并停下。
- 不明显越过目标点。
- 不可达或偏离经路时失败停车。

### Phase 7：TimetableDispatcher

目标：把 JS 时刻表和 ATO 接起来。

任务：

1. 实现 `TimetableDispatcher`。
2. 实现 `RunningService`。
3. 实现服务状态机：
   - `WAITING_START`
   - `RUNNING`
   - `DWELLING`
   - `FINISHED`
   - `FAILED`
4. 到指定 tick 发车。
5. 到站后停站。
6. 停站结束后进入下一 step。
7. 添加命令：
   - `/mr timetable start <id>`
   - `/mr timetable stop <id>`
   - `/mr timetable status [id]`

验收：

- 加载 JS 后可以 start。
- 系统自动给列车挂 ATO。
- 列车按 step 行驶、停车、继续。
- status 能显示当前服务状态。

### Phase 8：前车避让和简单多车

目标：支持低密度多车，不追尾。

任务：

1. ATO 沿当前 route lookup 前方。
2. 检测其他列车 head/tail。
3. 前方安全距离内停车。
4. 前车走远后恢复运行。

验收：

- 两列车同向运行时，后车会停车等待。
- 前车驶离后，后车恢复运行。

### Phase 9：持久化和轨道编辑锁定

目标：系统能长期使用，并避免运行中轨道被破坏。

任务：

1. 持久化：
   - stopPoints
   - railMetadata
   - loaded timetable info
2. 实现 `BasicWorldData.canModifyRail(railId)`。
3. `RailCommand` / `RailWand` 修改轨道前调用编辑锁检查。

验收：

- 重启后基础运营数据可恢复。
- 自动时刻表使用中的轨道不能被删除或修改。

### Phase 10：后续增强

后续再接入：

- 信号系统
- 固定闭塞 / 移动授权
- 联锁
- 列车性能配置
- ATP 包裹手动驾驶
- 更完善的限速牌/方向牌渲染
