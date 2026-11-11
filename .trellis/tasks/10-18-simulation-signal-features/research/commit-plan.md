# 历史拟议本地提交计划（暂缓）

2026-10-19用户要求先继续下一批、不必立即合并。本清单保留本批原ownership历史，暂不执行；最终提交计划需根据后续联合候选重新核定。

工作提交完成后才执行任务archive与journal；不推送、不部署、不启动子任务4。所有dirty文件均属于本批两个实现者或主会话，没有未识别WIP。精确stage时仍须再次核source和staged tree；不能whole-tree add包含后来出现的改动。

## 1. docs-site独立仓

`docs: describe per-world signal execution and simulation installation`

- `docs/architecture/threading-model.md`

基线cb9263b。只提交本文件，然后将docs新commit记录为主仓gitlink；不合入未验证内容。

## 2. 主仓：宿主准备与公开领域接口

`feat(simulation): expose owned feature preparation and target APIs`

- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`
- `.trellis/spec/javarailways/simpleato/index.md`
- `.trellis/spec/javarailways/simpleato/simulation.md`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultSimulationFeatureService.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/JavaRailwaysServiceRegistration.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/SimulationFeatureOwnerLifecycleListener.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/simulation/SimulationFeaturePreparer.java`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/simulation/SimulationFeatureService.java`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt`
- `BukkitPlugin/src/signalApiConsumer/java/example/signaladdon/SignalApiConsumerPlugin.java`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultSimulationFeatureServiceTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/JavaRailwaysServiceRegistrationTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/simulation/PreparedSimulationFeature.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/DefaultSimulationDomainTarget.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt`

16个Java/Kotlin源与测试文件，加3份对应CURRENT规范。公开API、owner登记/禁用、HTTP capture、source token退休及Java consumer在同一提交。

## 3. 主仓：信号隔离、真实模拟与本批证据

`feat(signals): isolate world engines and install simulation domain features`

- `.trellis/spec/simple-signals/backend/index.md`
- `.trellis/spec/simple-signals/backend/js-signal-rules.md`
- `.trellis/spec/simple-signals/backend/simulation-runtime.md`
- `.trellis/tasks/10-15-simplesignals-platform-split/prd.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/implement.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/prd.md`
- `.trellis/tasks/10-18-isolated-simulation-runtime/research/remaining-cross-world-locks.md`
- `.trellis/tasks/10-18-simulation-signal-features/check.jsonl`
- `.trellis/tasks/10-18-simulation-signal-features/design.md`
- `.trellis/tasks/10-18-simulation-signal-features/implement.jsonl`
- `.trellis/tasks/10-18-simulation-signal-features/implement.md`
- `.trellis/tasks/10-18-simulation-signal-features/prd.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/acceptance-evidence.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/check-findings.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/check-result.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/commit-plan.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/final-candidate.json`
- `.trellis/tasks/10-18-simulation-signal-features/research/full-gate.sh`
- `.trellis/tasks/10-18-simulation-signal-features/research/signal-execution.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/signal-simulation.md`
- `.trellis/tasks/10-18-simulation-signal-features/research/validation.md`
- `.trellis/tasks/10-18-simulation-signal-features/task.json`
- `SimpleSignals/src/main/java/work/art1st/javarailways/simplesignals/SimpleSignalsHostServices.java`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsWorldLifecycleListener.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockRouteResourcePolicy.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsSimulationFeature.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalLogicRuntime.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalPackCatalog.kt`
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalPackEpochRuntime.kt`
- `SimpleSignals/src/test/java/work/art1st/javarailways/simplesignals/SimpleSignalsHostServicesTest.java`
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntimeManagerTest.kt`
- `docs-site`

11个Java/Kotlin源与测试文件，加信号规范、父/关联任务记录、逐文件及XML证据、独立检查报告和docs-site gitlink。真实规则和资源policy复用，没有schema变更。

## 合入与收尾

用户确认后，依次完成上面3个work commit，并精确核staged tree与冻结27源相同；在dev/signal-system整合这些已测提交，保留并核对主工作区的本任务metadata副本。之后按Trellis执行本子任务归档与journal bookkeeping commit。父任务保持进行中，逐车行为与最终联合验收尚未完成。

未识别dirty文件：无。确认若出现其他会话新文件则不纳入。
