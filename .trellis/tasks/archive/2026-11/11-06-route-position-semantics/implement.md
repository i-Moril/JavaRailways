# 实施计划（已获用户批准）

1. 用户批准最新规划后才启动实施；读取 owner specs，整理此前三文件草稿，保留无关改动。
2. Basic 添加端点反算漂移的红色回归并修复，验证 SimpleATO 适配；覆盖 AB/BA、多轨边、起终点、marker、路径替换。
3. 对当前调用链的停车容差和截断分类，修复 MissionRouteProgress 及必要的停站前缀/Mission 衔接，不机械替换 epsilon 或 coerceIn。
4. 验证信号边界、重复轨道、允许偏差的停车后发车和折返；检查 controller 与冻结物理路线消费端。
5. 运行模块测试和同输入离线重放，分别报告编译、单测、重放结果；不能放宽 ATP 或忽略红灯来通过。
6. Trellis check 审查、owner spec 同步；不自动提交或部署。

## 主要测试

- Basic：TopologyRoutePlannerTest；受影响时 TopologyRouteRestrictionTest、TopologyRouteProjectorTest。
- SimpleATO：OperationalRoutePlannerTest、MissionRouteProgressTest、FrozenMissionRouteAssemblerTest、TimetableDispatcherTest、TimetableDispatcherSharedAdmissionTest、TimetableStationCallDispatcherTest、RollingStationAdmissionTest、StationCallResolverTest。
- 集成：SimpleSignals/BukkitPlugin 测试与原全局离线重放。

## 执行阶段验证命令

```sh
./gradlew :Basic:test :SimpleATO:test --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g
./gradlew :SimpleSignals:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g
git diff --check
```

前轮使用 /tmp/jr-rolling-tests.gradle 限制测试进程内存，/tmp/jr-rolling-global-replay/replay.gradle 提供重放。
执行前确认临时文件和资产仍在，不把临时 runner 当成已纳入版本控制的永久测试。
重放使用原时刻表与真实资产，五服务均使用 S100，未恢复浏览器全部车辆指派状态，不能宣称运行条件完全一致。
检查完整结果及 Tick 302/5403，不只检查 JavaExec 退出码。

## Gate

用户已在最终规划后明确回复“可以，允许执行”，允许启动及派发实现。R1–R3 需求已收敛；若实现需要超出本设计的模块或行为调整，先报告并确认。

## 执行状态

步骤1–6和独立复核、spec同步已完成。模块检查通过；全局重放越过原故障点，在Tick10026因延误阈值结束，未全程通过。后续延误对照已完成并按用户要求移交新任务规划；停站时间语义下轮讨论。详细测试数量、红绿证据和后续诊断见 verification.md。Phase 3.4 提交计划已获用户确认，执行工作提交后归档记录；未部署。
