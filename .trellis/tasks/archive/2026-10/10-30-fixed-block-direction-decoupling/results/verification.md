# 首次实现验证记录

后续用户已授权提交并部署，实际部署整合版本及3001项JVM／323项前端测试见 [deployment.md](deployment.md)；本文保留原实现基线的验证记录。

2026-10-30，分支 `dev/signal-system`，代码基线 `a59606ba6de0df9a134ff37fe9559bf64b6de680`。用户要求的代码修改和独立复核已完成，改动留在工作区供审阅；未提交、推送、部署或转换世界数据。

## 最终门禁

最终完整门禁 **BUILD SUCCESSFUL**，耗时 2m49s，52 项任务中 6 项执行、46 项为 UP-TO-DATE。原始输出见 [full-validation.log](full-validation.log)，结构化计数见 [test-results.json](test-results.json)。

JUnit XML 共记录 **2330 项测试，0 失败、0 错误、0 跳过**：

- Basic：62 个测试类，427 项。
- SimpleATO：71 个测试类，613 项。
- SimpleATOBukkit：2 个测试类，2 项。
- SimpleSignals：59 个测试类，657 项。
- BukkitPlugin：137 个测试类，631 项。

最后一轮重新执行了完整 SimpleATO、SimpleSignals 测试；Basic、SimpleATOBukkit、BukkitPlugin 测试及 Java consumer 编译沿用本次实施中已通过且输入未变化的 UP-TO-DATE 结果。`SimpleSignals:verifyPluginJar`、`SimpleSignals:check` 和根任务 `verifySignalModuleBoundaries` 通过。未执行独立 Core 全量测试，不将离线结果表述为游戏内验收。

## 可复现环境与命令

Gradle 9.0.0，启动 JVM 使用本地缓存的 JDK 25，模块编译沿用现有 toolchain。依赖全部离线解析。沙箱内 Gradle 的本地通信受限，测试通过已获自动审批的本地执行完成。

实际门禁使用 `/tmp/javarailways-direction-diagnostic.gradle`；其逐字副本保存在 [test-process.gradle](test-process.gradle)，仅对 SimpleATO 的测试 worker 设置 1GiB 堆、单 worker、每个测试类重启进程。产品源码和构建配置不包含该设置，也未忽略失败或删减测试。

从仓库根目录执行：

```bash
cp .trellis/tasks/10-30-fixed-block-direction-decoupling/results/test-process.gradle /tmp/javarailways-direction-diagnostic.gradle
JAVA_HOME=/workspace/minecraft/JavaRailways/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2 \
GRADLE_USER_HOME=/workspace/minecraft/JavaRailways/.gradle-user-home \
./gradlew :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:check \
  :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries \
  --init-script /tmp/javarailways-direction-diagnostic.gradle --continue \
  --offline --no-build-cache --no-parallel --max-workers=2 \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m' \
  -x :WebUI:installFrontend -x :WebUI:buildFrontend --console=plain \
  > .trellis/tasks/10-30-fixed-block-direction-decoupling/results/full-validation.log 2>&1
```

初次完整 ATO 测试的共享 worker 出现堆内存不足，增加至 1GiB 并每40类重启仍复现。未完成的四类测试按类隔离后49项全部通过，随后完整613项同样按类隔离通过。尚未确定共享 worker 保留内存的具体原因；不声称已修复产品内存问题，也不认定为既有基线故障。

## 验收证据

- AC01、AC02：`SectionTopologyTest`、`FixedBlockCatalogTest`、`FixedBlockRouteResourcePolicyTest`、`SimpleSignalsWorldRuntimeManagerTest` 覆盖混合方向几何、AB/BA 正反路径、作者选定顺序、两端入口、重载稳定性及原几何拒绝条件。
- AC03、AC08：`StoppedReturnToDepotIntegrationTest` 的8项真实 Core/Basic/Dispatcher 离线测试均通过。新增场景以50ms步长运行，连接 AB 单向轨道和双向轨道；列车在双向部分停车、两次换向并正向出清，逆向保护后缀延伸到单向轨道，但列车不驶入该后缀。测试断言两次 rebase 保留同一 grant、逐帧同一资源 token/owner、相向申请失败，以及真实车尾越过出口后才释放。
- AC04、AC05：`RouteOperatingDirectionLimitTest`、`TerminalRouteMarginTest` 覆盖实际逆行、反向车身、缺失方向／拓扑输入，以及停车点合法但余量非法；新保护后缀和已有后缀均不能掩盖失败。`TimetableFormationDispatcherTest` 覆盖默认5m余量、body-only编组交接和跨轨道静态依赖补全。
- AC06：`RouteAuthorityAdmissionClientTest` 验证真实物理 grant 为100m时仍保持100m，独立 ATO 方向 restriction 为60m，并沿用统一停车安全距离；pending、拒绝、接续和 adoption 不丢限制。`SimpleATOTest` 覆盖滚动申请距离包含停车前视包络，避免停止在申请阈值之外。
- AC07：`FixedBlockSignalEntranceResolverTest`、`FixedBlockSignalCompilerTest`、`SignalObservationGraphPublicationTest`、`FixedBlockInspectionSourceTest` 覆盖两端唯一入口、独立扫描、相同资源与未知信号收紧；模型摆放及附属设备回归通过。
- AC09：`SignalEpochRuntimeTest` 验证无信号 source 时相关方向修改立即使旧 ATO 绑定失效、无关轨道不失效，以及修改后新建的合法绑定不被滞后通知误伤；实现复核确认使用既有 rail 索引，无逐 tick 世界扫描。
- AC10：`SimpleSignalsWorldDefinitionStoreTest`、`DirectionalSignalPersistenceTest` 使用当前固定 fixture，验证删除字段、保留两端信号、严格字段／版本、backup恢复和保存失败时旧状态。current schema 仍为1，未加入旧开发格式 reader。
- AC11：所有上述编译／测试、Java consumer 和 JAR 边界通过；`git diff -- Basic Core` 与这两个目录的未跟踪清单均为空，Basic/Core 运行代码与公开 API 没有修改。
- AC12：`RouteAuthorityAdmissionClientTest`、`SignalEpochRuntimeTest`、`FixedBlockStopContinuationTest`、`SectionOccupancyRuntimeTest`、`SectionTopologyRevalidationTest` 及真实返库回归覆盖 pending、拒绝、source丢失、拓扑不可用、换向交接和失败保留资源。

## 源码与交付边界

门禁前固定 [source-contract.json](source-contract.json) 中335个 SimpleATO/SimpleSignals 源文件及 Java consumer 文件的 SHA-256；门禁后全部匹配，文件集合和 HEAD 均未变化。最终 `git diff --check` 通过，任务 implement/check JSONL 各10项验证通过。实现与复核修正另见 [review.md](review.md)。

本轮没有安装构建产物、进入游戏、连接开发服或修改持久化 sidecar。实际开发服若仍带旧闭塞字段，部署前必须按设计做只读清单、可 dry-run 的一次性转换和恢复点；本轮未执行该条件步骤。单线多个闭塞的方向协调仍为独立后续工作。
