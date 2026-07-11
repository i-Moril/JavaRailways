# 信号系统、列车自动控制与自动时刻表后续步骤

> 记录当前代码审查后的结论与后续实现建议。本文只反映规划讨论，不代表已实现。

## 当前结论

当前代码已经具备开发信号系统的若干基础设施，但还不足以直接实现完整的闭塞、联锁、ATO 与自动时刻表。

已具备：

- 轨道拓扑：`RailView.nextRails`、`nextActiveRail`、`reversed` 可表达连接、分岔和当前进路。
- 道岔雏形：`RailView.activeRail` 可表示当前选择的分支。
- 连续弧长模型：`RailLocationWithFacing`、`forward()`、`peekCurve()` 可沿轨道查找位置。
- 列车运行：`CoreTrain.moveTrainForward()` 已支持基本运动、碰撞检测和越过信号检测。
- 事件系统：`EventManager`、`PassSignalEvent`、`CollisionEvent`、`DerailEvent` 可用于信号/安全事件。
- Tick 系统：`TickManager` 可承载闭塞管理器、联锁管理器、ATO 控制器等周期逻辑。
- 基础寻路：`Basic/pathFinding` 中已有 `RailMap`、`RailDestination`、`PathFinder`。

主要缺口：

- `Signal` 目前只是骨架，状态、类型、序列化均未完成。
- `CoreWorld` 虽有 `signals` 字段，但没有公开 add/remove/get API，也未持久化。
- 闭塞占用不能只依赖 `TrainEnd`，需要完整列车包络与轨道区间占用。
- 联锁缺少正式模型：进路、锁闭、敌对进路、保护区段、道岔锁定等均未实现。
- `PassSignalEvent` 是列车越过信号后触发，不能单独用于提前制动。
- `ATOController` 为空。
- `World.railMap` 当前走 `TestRailMapRegistry`，没有读取 `BasicPlugin` 注册的扩展数据，运行时和测试路径不统一。

## 建议的总体架构

建议在 Basic 中建立世界级数据对象：

```kotlin
data class BasicWorldData(
    val railMap: RailMap,
    val signalSystem: SignalSystem,
    val blockManager: BlockManager,
    val interlocking: InterlockingManager,
    val movementAuthorityProvider: MovementAuthorityProvider
)
```

然后将：

- `World.railMap`
- `World.signalSystem`
- `World.blockManager`
- `World.interlocking`

统一改为从 `world.getExtensionData(BasicPlugin.INSTANCE)` 读取，而不是测试专用 registry。

## 固定闭塞实现建议

第一阶段可先实现固定闭塞：

```kotlin
data class BlockSection(
    val id: String,
    val segments: List<RailSegmentRef>,
    val entrySignalId: String?,
    val exitSignalId: String?
)
```

状态：

- `FREE`
- `OCCUPIED`
- `RESERVED`
- `LOCKED`

每 tick 或 post-commit 阶段：

1. 遍历列车。
2. 根据车头、车尾和车厢长度计算列车占用包络。
3. 标记对应闭塞为占用。
4. 根据前方闭塞、保护区段和进路状态更新信号显示。

红灯条件：

- 前方闭塞被占用。
- 进路未锁闭。
- 道岔不在要求位置。
- 敌对进路已锁。
- 数据缺失或状态不明。

## 联锁实现建议

新增核心模型：

- `Route`
- `RouteRequest`
- `RouteLock`
- `SwitchRequirement`
- `ConflictRule`
- `Overlap`

进路办理流程：

1. `requestRoute(entrySignal, target)`。
2. 用 `PathFinder` 或信号图搜索 `RailView` 路径。
3. 推导需要设置的道岔方向。
4. 检查闭塞空闲、保护区段空闲、敌对进路未锁、道岔未被其他进路锁闭。
5. 成功后设置道岔、锁闭进路、预留闭塞、开放信号。
6. 列车通过后逐段释放。

需要补公共 API，避免 Basic 直接依赖 Core 内部类：

```kotlin
interface SwitchController {
    fun setActiveRail(from: RailView, to: RailView, reason: RouteLock)
}
```

## ATO / ATP 实现建议

ATO 不应只依赖 `PassSignalEvent`，而应每 tick 查询 movement authority。

基础模型：

```kotlin
data class MovementAuthority(
    val trainId: String,
    val endLocation: RailLocationWithFacing,
    val distanceToEnd: Double,
    val maxSpeed: Double,
    val reason: String
)
```

ATO/ATP 规则：

```text
如果 制动距离 + 安全余量 >= 到授权终点距离：
    制动或降低目标速度
否则：
    按线路限速/时刻表目标速度运行
```

手动驾驶也建议套 ATP wrapper：玩家输入 throttle，但 ATP 可以强制限速或紧急制动。

## 移动闭塞可行性

当前代码从物理模型上可以支撑移动闭塞原型，因为已有：

- 连续弧长位置。
- 沿轨道前探 `peekCurve()`。
- 列车速度 `Train.speed`。
- 车厢/转向架位置 `Car.bogies`、`Bogie.location`。
- Tick 系统。

但正式实现仍需补 API：

- `Train.headLocation`
- `Train.tailLocation`
- `Train.lengthMeters` 或更明确的列车包络 API
- `World.allTrains` 或 `World.forEachTrain()`
- `TrainEnvelope`
- `MovementAuthorityProvider`

移动闭塞 MVP：

```text
MA 终点 = min(
  前方列车尾部 - 安全距离,
  进路终点,
  轨道尽头,
  红灯位置
)

若当前速度制动距离 + 安全余量 >= 到 MA 终点距离：
    制动
否则：
    允许加速到限速
```

注意 Tick 顺序：移动闭塞管理器最好在列车 commit 后统一计算下一 tick 的授权，避免与控制器并发读写列车位置。

## 测试计划

单元测试建议：

1. 信号越过检测：列车越过测试信号，验证 `PassSignalEvent` 触发一次。
2. 闭塞占用：长列车跨越两个闭塞边界时，两个闭塞均为 `OCCUPIED`。
3. 红灯保护：前方闭塞有车，入口信号保持红灯，ATO 停在信号前。
4. 进路锁闭：分岔处办理一条进路后，道岔锁定，敌对进路请求失败。
5. 释放逻辑：列车通过进路后，后方闭塞逐段释放，道岔最终解锁。
6. 失效安全：目标路径不存在、道岔状态不明、闭塞数据缺失时，信号默认红灯。
7. 移动闭塞：两车同向追踪时，后车 MA 终点位于前车尾部后方安全距离处。
8. ATO 制动曲线：给定速度、制动率和 MA 距离，验证列车能停在授权终点前。

集成测试建议：

- 两列车同向追踪。
- 两列车相向会车。
- 分岔进路锁闭。
- Bukkit 中信号显示变化。
- 重启后信号、闭塞、进路配置持久化正确。

## 扩展性建议

将信号系统设计为可替换策略：

- `SignalAspectProvider`：决定信号显示。
- `BlockDetector`：固定闭塞、移动闭塞、虚拟闭塞。
- `InterlockingRule`：不同联锁规则。
- `RouteProvider`：人工进路、自动进路、时刻表进路。
- `MovementAuthorityProvider`：为 ATP/ATO 提供授权终点。
- `SignalRenderer` / `RenderableExtension<Signal>`：平台渲染扩展。

建议新增事件：

- `SignalAspectChangedEvent`
- `RouteRequestedEvent`
- `RouteLockedEvent`
- `RouteReleasedEvent`
- `BlockOccupiedEvent`
- `BlockReleasedEvent`
- `SignalPassedAtDangerEvent`

## 推荐实施顺序

1. 修正 Basic 扩展数据注入，建立 `BasicWorldData`。
2. 补最小公共 API：`World.allTrains`、`Train.headLocation`、`Train.tailLocation`。
3. 实现 `SignalSystem` 和 `SignalAspect`。
4. 实现固定闭塞和单元测试。
5. 实现联锁进路、道岔锁闭和敌对进路测试。
6. 实现 ATP movement authority。
7. 实现 ATOController。
8. 在固定闭塞稳定后，再实现移动闭塞 `BlockDetector` / `MovementAuthorityProvider`。
9. 最后接入自动时刻表。