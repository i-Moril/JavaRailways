# Inspection / admission / routing / restriction 审查

基线 `b89b0d5d`，2026-11-06。仅审查，不实施。已确认的旧帧→空车长→整路线申请链见 `known-14219-issues.md`，不重复计为本报告新发现。

## I01 — P2：正常 Dispatcher tick 无条件重建所有列车检查索引

- 位置：`SimpleATO/.../timetable/TimetableDispatcher.kt:1766`；`inspection/InspectionRuntime.kt:555–560,639–646`。
- 生产链：每次正常调度结尾调用 `retainTrains(activeIds)`；即使没有任何列车需要移除，仍然 `publishCompiledViews()`。后者对全部列车创建新的 `TrainBinding`，重新构造 entry-clearance 排序索引，并重新编译全部 authority floors。
- 影响：声明及车长都未改变时仍分配和排序；`synchronizeRoute` 对单列车的增量更新优化在完整调度链中被抵消。此处不是声称插件的 compile callback 每 tick 被调用，也不是声称已测得某个 FPS 损失。
- 合同：`operational-routing-and-authority.md` 3.4.3/7 与 `entry-clearance.md` 要求低频编译、变化局部更新；正常距离变化不重建全部索引。
- 测试盲区：`EntryClearanceRuntimeTest` 中真实车体几何的测试直接从 epoch callback 调用 `synchronizeRoute`，没有走 Dispatcher 最后的 `retainTrains`，因此身份稳定断言没有覆盖完整生命周期。
- 最小方向：无删除时直接保持现有发布值；实际删除只更新受影响条目。无需新增缓存系统。
- 验证：`/tmp/jr-simpleato-audit-az6Vw0/probes/InspectionRetainAuditProbe.kt` 对不变集合调用 retain 后断言 binding 身份；实际结果见 `validation.md`。

## 可选简化，不冒充行为缺陷

- `InspectionUpdateHandler.update` 声明返回 collection，生产调用合同却要求恰好一个完整 snapshot。可以考虑让类型直接表达单值；公共扩展 API 迁移需单独确认。
- `synchronizeRoute` 同时接受 nullable scalar length 与 nullable published frame，实际生产 Dispatcher 使用 frame，显式长度主要用于测试。应先统一生产输入，不能因为测试方便保留运行期任意缺失值兜底；但不要把静态编译阶段允许未知车长误删。
- `FrozenRouteSelectionResult.Selected` 可短暂容纳 null operating-direction limit，准入再要求非空。属于阶段模型清晰度问题，未证明错误授权，不建议为此新建复杂证明框架。
- 不将所有数值 epsilon 判为停车容差错误：路线累计距离减法确有浮点舍入；0.3m 运营停车容差、授权停车余量与 1e-6 数值精度必须区分。

## 已审范围

下列生产源文件全部阅读并追踪关键消费路径；不声称数学形式证明或跨模块全量审查。路径以 `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/` 为根：

- `admission/RouteAuthorityAdmissionClient.kt`、`admission/RollingRouteAuthorityPlanner.kt`。
- `inspection/AuthorityRequestFloors.kt`、`inspection/EntryClearance.kt`、`inspection/InspectionRuntime.kt`、`inspection/InspectionScheduler.kt`。
- `routing/DirectedRouteContinuation.kt`、`routing/FrozenMissionRouteAssembler.kt`、`routing/FrozenRouteSelector.kt`、`routing/MissionRouteProgress.kt`、`routing/OperationalRoutePlanner.kt`、`routing/RouteAllocationTiming.kt`、`routing/RouteBuildResult.kt`、`routing/RouteOperatingDirectionLimit.kt`、`routing/RoutePlanningRestrictionRuntime.kt`、`routing/TerminalRouteMargin.kt`。
- `restriction/TrainRestrictionConstraintAdapter.kt`、`restriction/TrainRestrictionSnapshotStore.kt`。
- `api/inspection/` 全部四文件、`api/route/` 全部两文件、`api/restriction/` 唯一文件；精确文件名见 `coverage.md`。

测试：对应 admission/inspection/routing/restriction 的测试均盘点；主体仅选择性阅读，重点 EntryClearanceRuntimeTest 的缺失车长断言/真实 epoch 测试、inspection snapshot 输入、route selection 与已有 rolling tests。未逐行审完全部测试；执行结果统一见 validation。没有宣称现有 passing assertions 能证明 Dispatcher 时序正确。

## 排除与边界

- Basic 持有实际资源 grant、物理占用、车尾释放；SimpleATO route authority 不应由第二套任意资源表替代。滚动申请器本身不是需要删除的过度设计。
- Mission path 与车身前缀/同向接续/折返 rebase 分别解决真实不同几何关系；不凭文件长度判为无用。
- 当前故障已是静态 spec 和测试共同认可错误兜底，修复必须同步改合同及真实 epoch 回归，不只是改一个空值分支。
