# 首批实施验证（模块检查通过，真实回放受阻）

用户已在最终四项摘要后明确批准；任务由 planning 切到 in_progress。实现只覆盖 F1–F5；不提交/部署。

## 基线与输入

- Git `b89b0d5d`；TODO.md 及原有未跟踪文件保持不动。
- 原审查完整 SimpleATO：788项通过；新增两项审查反例失败。详见 validation.md，不冒充修复后的结果。
- 修复后真实回放准备于 `/tmp/jr-frame-remediation-DryXuH`，独立日志，不覆盖旧证据；脚本仅本地隔离世界，所有路由申请调用原服务，代理只记录结果。
- 原基础设施 `/tmp/jr-infrastructure.json` SHA256 `ab1d2e6b6d8c961974633ce031c2fb27241bb6ac72f2d3cb0b4da57936fc5d63`。
- 原五交路脚本 `/tmp/jr-berth-diagnosis-Iyu6jVBU/full.js` SHA256 `3e33046c997116d933e35a381cc201e94a281cd2e06ee1cf55746cda151c76a9`。
- 原信号定义 `/tmp/jr-simplesignals-definitions.json` SHA256 `615c00ceeb3938f59de32f00350269066abe34b9c90962d56a850d36f1fba77a`。
- 模型 `/tmp/jr-live-s100`、信号规则 `/tmp/jr-rolling-global-replay/signal-packs` 沿用上次回放，未替代设备/授权实现。原回放禁用脱轨 monitor，但真实 ATP 保持启用；不能把该回放说成完整脱轨监测验收，整车限速另有专门真实设备回归。

## 当前结果

- A 物理帧统一：完成，详见 implementation-a.md。Core 聚焦50、Basic完整467、SimpleSignals完整677通过；Bukkit编译及Java API consumer、模块边界通过。
- SimpleATO 第一轮完整790项中1项诊断顺序失败，修复后相关类56项通过；最终全套待B完成，不将局部复验等同全套成功。
- SimpleSignals 最初2项仍认可缺车体全路线的旧fixture已改正；另6项并发超时在临时CPU/common-pool=4配置下消失。两个并发参数同时改变，不声称单个参数就是已证明根因。不改仓库构建配置。
- B 整车限速：完成；37项聚焦通过，含7项新回归。详见 implementation-b.md。
- check：完整F1–F5已静态复核，唯一发现为新测试显式coordinator未释放，已补清理；未发现未解决的本批生产问题，详见 implementation-check.md。
- 最终强制Core/Basic：541+467项通过，无失败/错误/跳过，46s。
- 第一轮最终集成ATO：797项通过；当时测试清理修正尚未重新编译。随后重新编译该测试（class时间19:32:36 UTC）并再次完整运行：797项通过、0失败/错误/跳过，最终清理代码已覆盖。
- 关联完整测试/产物边界检查：成功，2m58s；SimpleSignals 677、BukkitPlugin 728、TunnelBridgeBuilder 31、WebUI后端66。0失败/错误；Bukkit原有opt-in `AtoStoppingAccuracyExperiment.measure` 跳过1项，不伪称运行过。连同Core/Basic/ATO共3307项，其中3306通过、1跳过。
- Java API consumer、SimpleSignals plugin JAR、宿主shadowJar与模块边界检查通过；未部署。
- 编译警告：既有无效JDK自动探测、弃用API/非空断言，以及Bukkit Kotlin增量缓存无法推导一个旧符号；编译器自动完整重编译并成功，未以改业务代码规避。
- Core/Basic Dokka生成成功（30s），存在大量Undocumented以及既有构建弃用警告，不称为零警告检查。
- 本地真实回放：在Tick2002提前失败，未到Tick14219。不能宣称原场景通过，见下。

## 最终验证命令

```bash
./gradlew :Core:test :Basic:test --rerun-tasks --offline --console=plain --max-workers=1 \
  -Pkotlin.daemon.jvmargs=-Xmx2g -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle

./gradlew :SimpleATO:test :SimpleSignals:check :BukkitPlugin:test :TunnelBridgeBuilder:test \
  :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries \
  --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle

./gradlew :SimpleATO:test :BukkitPlugin:frameRemediationReplay --offline --console=plain --max-workers=1 \
  -Pkotlin.daemon.jvmargs=-Xmx2g -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle \
  -I /tmp/jr-frame-remediation-DryXuH/replay.gradle

./gradlew :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml \
  --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g
```

## 真实回放阻塞：Tick2002 外部授权出现极小负距离

原输入、延误阈值1200、真实S100动力学/ATP/Basic授权均未放宽。首轮日志
`/tmp/jr-frame-remediation-DryXuH/replay-after.log` 保留；追加只读现场字段的两轮日志分别为
`replay-tick-2002.log`、`replay-boundary.log`，未覆盖首轮证据。每轮均在2002返回Failed；
Gradle进程成功只表示探针执行完，不表示模拟成功。

- 失败列车：`simulation/new-timetable/2`，102P，hatchobori/1D发车阶段，新Mission revision9。
- Core物理epoch2001，速度0；实际制动比1、B7，所有车缸压350kPa。
- ATP：`INSUFFICIENT_DISTANCE`，`CURRENT_EMERGENCY`；目标为资源准入的
  `simpleato:committed-authority`，所需距离0、可用距离`-5.684341886080802E-14m`。
- 同时任务自身`authority/9`的距离恰为0；这不是整车原生限速目标，也不是另车占用拒绝。
- 追加观测：restriction mapping head=89.19999999999999，missionProgress=0，selected=0，
  movementAuthority=0；改变 `head+progress-selected` 的括号不能消除此例，两个表达式结果相同。
- 原始Basic grant边界=490.66575622996584；当前restriction binding路线总长=490.6657562299659。
  两者相减恰为ATP所见负值。
- 当前源码 `SimpleATO.restrictionRouteProgress` 在新旧Mission revision不一致时返回旧binding的路线总长，
  `TrainRestrictionConstraintAdapter.toAtoConstraint` 再算授权边界减进度。这条旧路线交接路径与数值证据吻合；
  不能据此把任务进度0等同物理授权0，也不能直接clamp负值或添加停车容差。
- 尚未完全确定两个路线边界值分别在哪个上游计算形成、与本批时序变化的因果关系；没有把新故障称为已证明无关旧bug。

追加原始grant观察时临时探针曾直接引用internal Selected而编译失败，已改为只读反射并重跑成功；
没有为诊断改变公开API或生产可见性。所有追加改动仅在 `/tmp` 探针。

结论：F1–F4源码与专门回归、跨模块检查通过；原Tick14219场景的端到端验收仍受阻。
保留A5未勾选，不提高阈值、不绕过ATP、不加容差、不在本批默默扩展到授权坐标/路线交接修复。
下一步应先收敛这一授权坐标问题，再重跑原场景；当前未提交、未部署、HEAD仍为b89b0d5d。
