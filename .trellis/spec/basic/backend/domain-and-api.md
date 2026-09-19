# 领域、API 与运行逻辑

## World-local 状态归属

`BasicWorldData` 汇集 `RailMap`、WayPoint/Destination、Depot、Line、Timetable、Dispatcher、ATO/provider registry、Station、rail metadata 与 train speed profile。新增运营状态应明确 owner 和 lifecycle：通常挂到对应 world，而不是 singleton；store 输出按 stable ID 排序或 immutable snapshot，避免把可变 map 暴露给 HTTP/platform。

`WayPoint` 只保存 rail UUID 与 AB 弧长；方向在 route/destination 层显式表达。不要存 live `RailLocation` 或借展示顺序推断 AB/BA。证据：`Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/route/RoutePlanner.kt`。

## Line、route 与 revision

- `Line` 的 UP/DOWN station pattern 独立、有序、不可重复，color 固定 `#RRGGBB`；不要把 diagram-only line 当作 infrastructure `LineId`。
- `LineStore` mutation 使用 expected revision，并以 `LineRevisionConflict` fail-fast，供 HTTP `If-Match` 类 contract 使用；写 API 时必须回传新 revision。
- `RoutePlanner` 按 `RailView` 和 arc-length weight 搜索，应用 infrastructure direction restriction，输出 immutable `ExpandedRoute`。不可达返回 `RouteBuildResult.Failure`，不要返回部分 route。
- 方向和弧长转换只在明确 boundary 做一次；BA view 的 arc length 是 total minus AB arc。

## Curve 与 safety

`RailCurveFactory` 只接受 `Anchor`/options 并返回 success/failure，不读取 Bukkit。它通过 `ThreadGuard.ensureOffMainThread` 拒绝在 simulation/main thread 做重几何。先构建并校验端点位置、切线、roll，再由 platform transaction 提交。

几何模板 token（`AUTO`、`STRAIGHT`、`FIVE_SEGMENT`、`S_CURVE`）属于 contract，不随意改名。新增算法必须测试精确端点、最小半径、平行轨、grade/superelevation 和 invalid input；不要只比较采样点数量。

`SpeedSafetyMonitor` 在 published-frame worker boundary 计算 request，由 simulation owner drain/apply。不要在 processor 中直接调用 Bukkit 或立即执行 platform derail side effect。证据：`RailCurveFactory.kt`、`SpeedSafetyMonitor.kt` 及对应 tests。

## Scenario: 跨轨道基础设施 footprint

### 1. Scope / Trigger

当应用计划租约、运行时进路或基础设施编辑覆盖一根以上轨道，或经过道岔、菱形交叉等共享冲突节点时，必须使用本契约。它防止用“一个跨轨道区间”引入不存在的全局里程，也防止只锁两侧轨道而漏掉共享节点冲突。

### 2. Signatures

```kotlin
RailIntervalResource(railId: UUID, startAbMeters: Double, endAbMeters: Double)
StableInfrastructureResource(typeId: String, resourceId: String)
InfrastructureFootprint(resources: Collection<InfrastructureResource>)
```

### 3. Contracts

- 每个 `RailIntervalResource` 只属于一根 `railId`，起止位置使用该轨道 canonical AB 弧长米制坐标，与列车运行方向无关。
- 跨轨道范围展开成同一个 `InfrastructureFootprint` 内的多个 rail interval；申请、替换、释放和编辑许可都以整个 footprint 为原子单位。
- 经过道岔、diamond 或其他共享 conflict node 时，必须在相同 footprint 中加入相应 `StableInfrastructureResource`。相邻轨道区间不会隐式代表节点资源。
- 区间为闭区间；`[0, 20]` 与 `[20, 40]` 在共同端点发生 overlap。

### 4. Validation & Error Matrix

- `startAbMeters`/`endAbMeters` 非有限值、起点小于 0 或终点早于起点 -> 构造失败。
- rail 不存在或终点超过 rail 长度 -> `INVALID_RESOURCE`，整个请求不写入。
- stable type 未注册、resource ID 不存在 -> `INVALID_RESOURCE`，整个请求不写入。
- 任一成员冲突或 topology revision 过期 -> typed denial，footprint 不得部分持有。

### 5. Good/Base/Bad Cases

- Good：rail A `[80, lengthA]` + turnout stable resource + rail B `[0, 35]`，三者在一个 footprint 中原子申请。
- Base：范围完全位于 rail A 时，只包含 rail A 的一个 interval。
- Bad：构造一个虚拟的“rail A 80 m 到 rail B 35 m”单区间，或只放 rail A/rail B 两个 interval 却遗漏中间 diamond resource。

### 6. Tests Required

- 验证同 rail 的闭区间端点 overlap，不同 rail 的 interval 不 overlap。
- 验证多 interval + stable node footprint 任一成员失败时没有部分 lease/claim。
- 验证只与共享 node 冲突但 rail intervals 不重叠的两条路线仍被节点资源互斥。
- 验证 BA 行进输入先转换到 canonical AB 坐标，不能把沿行进方向的距离直接写入 interval。

### 7. Wrong vs Correct

```kotlin
// Wrong: 不存在跨 rail 的全局弧长；也遗漏 turnout 共享资源。
InfrastructureFootprint(listOf(RailIntervalResource(railA, 80.0, 135.0)))

// Correct: 每根 rail 一个 AB 区间，共享节点作为独立资源，一起原子处理。
InfrastructureFootprint(listOf(
    RailIntervalResource(railA, 80.0, lengthA),
    StableInfrastructureResource("javarailways:turnout", turnoutId),
    RailIntervalResource(railB, 0.0, 35.0)
))
```

## Timetable 与 extension

- `TimetableValidator` 在 runtime 前验证 ID、period、step order、route、Station/Depot 引用、line speed 和 provider；错误保留 `[timetable/service] step#n` 上下文。
- `TimetableServiceEditorRow` 是 authoring metadata，generated `steps` 仍是执行权威；不要让展示字段绕开 runtime validation。
- `TimetableExtensionCall.typeId` 必须匹配 namespaced pattern，`version > 0`；registry 对同一 type/version 重复注册 fail-fast。
- condition provider 必须 deterministic/non-blocking；action ticket 的 snapshot/cancel 在 main/simulation contract 下运行。simulation 只复制显式 opt-in 的 pure provider，缺失 provider 返回 unavailable/error，不执行生产插件副作用。
- provider 参数 shape 变化遵循 shared persistence policy；行为需要并存才保留多版本，旧形状迁移优先 upcast。

证据：`Basic/src/main/java/work/art1st/javarailways/basic/api/timetable/TimetableExtension.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/timetable/Timetable.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/timetable/TimetableDispatcher.kt`。
