# Implementation plan — narrowed 2026-10-17

Status: in_progress; user approved the final narrowed plan on 2026-10-17. Native Trellis implement/check agents dispatched; no remote writes or deployment.

## 1. Freeze bounded evidence

- [x] 核实10月16日migration/deployment记录及192.168.1.230实际12对文件；详见research。
- [x] 定位current reader、writer、prepared target snapshot、undo及simulation consumer。
- [x] 获批后取得远端24文件独立只读副本，记录归档/逐文件hash及所有nested revision/epoch配对；在线副本不作停服恢复点。
- [x] 加载Core/Basic/Bukkit及相关测试规范、记录dirty边界；按需隔离既有未提交改动。

## 2. Core producer and shared consumer

- [x] SavedRailEndpointRef及current字段、writer、共享单端/全图验证。
- [x] CoreWorld.load先预校验完整rail图；复用decoded curves，随后直接恢复精确连接。
- [x] PreparedRailWorldSnapshotPlan、CoreWorld.buildPreparedTargetRailSnapshot、PreparedRailTopologyUpdate同步完整端点身份，消除降格。
- [x] Bukkit PreparedRailWorldUndo及IsolatedTimetableSimulationFactory同步；适配直接测试fixtures。
- [x] 固定JSON与现有真实对象测试覆盖四种端点组合、ordered/null/第二active、同轨不同端身份、非对称/缺失/重复/超度数/坏token/共点不符；确认当前runtime支持的拓扑限制，不借格式变更放宽。
- [x] load失败不创建live rail、exact restore和preserve-active undo回归。

## 3. Independent offline conversion

- [x] 扩展offlineMigration为已迁移outer2的端点转换模式，保持旧ownership工具可验证的输出语义，不打入JAR。
- [x] 固定旧UUID-only fixture，唯一候选成功、零/双候选及坏active/混合形状拒绝；current no-op、revision/epoch/runtime字节/源字节与fresh-output检查。
- [x] 真实远端独立副本dry-run、新current codec及业务restore；逐对记录身份、234有向连接的精确转换及未改数据。

## 4. Check and documentation

- [x] 定向SavedRailConnectionsTest/PreparedRailWorldSnapshotPlanTest/CoreWorldRailTopologyTransactionTest/RailConnectTest、Bukkit撤销/模拟/离线迁移测试。
- [x] ./gradlew :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :SimpleSignals:test
- [x] ./gradlew :BukkitPlugin:shadowJar；验证旧输入转换类不在JAR。
- [x] 独立trellis-check复核，按实际变化运行必要checks；更新Core持久化/rail-topology与offline-migration owner规范。
- [x] git diff --check、检查scoped diff；遵守现有提交授权，不夹带其他工作。

## Deferred actions

本批不SSH写入、不停服、不部署；远端apply需另获批准后复核新源及完整外部恢复点。


## Completion and pending commit

实现、owner规范、定向/完整门禁、真实12对副本转换及独立比对均已完成，详见[验证记录](research/implementation-validation-2026-10-17.md)。
六模块2259测试全执行通过、零skip/failure/error；源码及JAR验证已完成，不再重复运行相同检查。
用户已确认[提交清单](research/commit-plan.md)，工作提交`f90c6805`已完成，任务归档收尾。本批没有远端写入/部署。
