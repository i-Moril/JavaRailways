# 编组参数前置：提交计划与验证记录（已批准，独立验证完成）

这是CR-030的参数前置，不是完整碰撞联结交付。按以下两笔拆分，分别验证实际选定tree；
当前完整工作树包含冻结联结草稿，其门禁不能替代每笔提交基线验证。
用户于2026-10-12回复“继续”，批准按此两笔计划执行；不再重复请求同一计划确认。

## 1. `fix(bukkit): resolve dynamics profiles from each car source`

已提交：`b075dfb6`。独立基线tree `ee0accafa95c3b946e90261a5669f49b2c8f9021`与正式提交tree完全相同；
Bukkit全量379项全部执行通过，零failure/error/skipped，1分47秒、25 tasks全部执行。
隔离副本复用相同package.json/lock对应的已安装node_modules独立副本，仅跳过WebUI依赖安装任务，前端构建实际执行。

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/CarExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/persistence/TrainPackageSnapshotStore.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainExtension.kt`：仅删除旧列车级resolver。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlManager.kt`：仅逐车解析。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/TrainCommand.kt`：仅逐车解析。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt`：仅逐车解析。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/SimulationControlProfiles.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/extension/train/CarControlSourceTest.kt`
- `.trellis/spec/bukkit-plugin/backend/car-behavior.md`：仅来源合同，实时resolver注入说明随第二笔。

不包含新constructor参数、TrainExtension联结草稿或旧link测试caller迁移。

## 2. `fix(control): prepare consist dynamics before structural changes`

独立基线基于第一笔`b075dfb6`，代码冻结tree `f93a030f2729e8ee1e00e4487957c9adcd499f35`验证通过：
Core351、Basic393、SimpleATO354、Bukkit379，共1477项全部执行，零failure/error/skipped，2分37秒、48 tasks全部执行。
SimpleATOBukkit、SimpleSignals及TunnelBridgeBuilder的测试源码编译，以及Core/SimpleATO Dokka实际执行通过；
本轮没有运行这三个consumer的测试或shadowJar，不混用此前全WIP的2003项结果。
验证后只补本文件和implement的结果记录；最终提交补丁需确认与该已测版本仅有这两份文档差异。

- `Core/src/main/java/work/art1st/javarailways/core/api/control/ControllerConsistState.kt`
- `Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt`：仅prepare API及imports，不包含联结stop草稿。
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`：仅现有split的prepare/install接线。
- `Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreTrainSplitTest.kt`：仅新state测试及imports，保留HEAD extension方法签名。
- `Basic/src/main/java/work/art1st/javarailways/basic/control/ConsistDynamics.kt`
- `Basic/src/main/java/work/art1st/javarailways/basic/control/TrainDynamicsExecutor.kt`
- `Basic/src/main/java/work/art1st/javarailways/basic/control/RealisticManualController.kt`
- `Basic/src/main/java/work/art1st/javarailways/basic/control/HandleTrainActuator.kt`
- `Basic/src/test/java/work/art1st/javarailways/basic/control/TrainDynamicsTest.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/ato/AbstractATO.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/api/ato/AbstractATOConsistTest.kt`：独立基线使用HEAD `linkWith`测试extension签名，不提前提交`prepareLink`草稿。
- 第一笔的`TrainControlManager.kt`、`TrainCommand.kt`、`RailWorldFactory.kt`：仅四处live resolver构造接线。
- `.trellis/spec/javarailways/core/train-controller.md`
- `.trellis/spec/javarailways/basic/train-longitudinal-dynamics.md`
- `.trellis/spec/javarailways/simpleato/controller-runtime.md`
- `.trellis/spec/bukkit-plugin/backend/car-behavior.md`：实时resolver注入说明。
- `.trellis/tasks/10-09-stage2-code-review/implement.md`：仅本批记录，不能将未完成碰撞草稿改成完成。
- 本计划文件。

若选定tree编译/验证不能独立通过，先修正本批分界，不把旧联结事务整批塞入提交。
联结prepare/install调用点继续留在草稿，后续与真实碰撞入口一起交付。

## 不纳入上述提交

已知旧碰撞草稿：Core train-physics合同、任务prd/design及旧联结记录、TrainExtension接口及Bukkit实现、
PhysicsCoordinator、CoreTrain联结实现、CoreTrainConstructionTest/CoreTrainLinkTest及其他extension签名迁移、
TrainLinkEvent/TrainLinkSuccessEvent/TrainEndSide、CarFrameRevisionGateTest旧caller迁移。

无关既有改动：`TODO.md`、`docs-site`子模块、`.trellis/tasks/09-24-explicit-rail-endpoint-connections/`、
`ai_tasks/current_signal_rules_research_handoff.md`及`models/`下既有资源包文件。保留，不暂存、不回退。

不push、不部署，不归档整体审查任务或Stage2。
