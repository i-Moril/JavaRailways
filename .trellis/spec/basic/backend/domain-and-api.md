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

## Timetable 与 extension

- `TimetableValidator` 在 runtime 前验证 ID、period、step order、route、Station/Depot 引用、line speed 和 provider；错误保留 `[timetable/service] step#n` 上下文。
- `TimetableServiceEditorRow` 是 authoring metadata，generated `steps` 仍是执行权威；不要让展示字段绕开 runtime validation。
- `TimetableExtensionCall.typeId` 必须匹配 namespaced pattern，`version > 0`；registry 对同一 type/version 重复注册 fail-fast。
- condition provider 必须 deterministic/non-blocking；action ticket 的 snapshot/cancel 在 main/simulation contract 下运行。simulation 只复制显式 opt-in 的 pure provider，缺失 provider 返回 unavailable/error，不执行生产插件副作用。
- provider 参数 shape 变化遵循 shared persistence policy；行为需要并存才保留多版本，旧形状迁移优先 upcast。

证据：`Basic/src/main/java/work/art1st/javarailways/basic/api/timetable/TimetableExtension.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/timetable/Timetable.kt`、`Basic/src/main/java/work/art1st/javarailways/basic/timetable/TimetableDispatcher.kt`。
