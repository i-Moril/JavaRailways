# 积压改动提交收尾（2026-10-19）

## 授权与来源

用户在核对dev/signal-system未提交清单后明确要求“你应该提交这一些更改”。本次把现有积压改动按范围检查、提交，并整合已获批准且已提交的World事件分支；不重新设计领域功能，不部署、不迁移、不推送，不启动下一批World隔离实现。

起点为dev/signal-system@a76b076c。原始54项状态包含30项tracked变化及24项untracked路径；展开后82个untracked文件，其中33个是World模拟任务目录的副本，内容与codex/world-epoch-events的9ca9ebe6逐字节一致。其余原WIP与此前78文件恢复清单一致。

本次完整恢复点：`/tmp/jr-backlog-before-commit-vrk1zsyz`，含117文件的内容归档、SHA256清单、主仓库与docs-site二进制diff及status。提交前没有stash/drop、reset或删除原文件。

## 提交分组

1. 日志、信号观测、诊断复制及Wand连续创建修复，连同对应规范、真实测试和任务记录。共享的manager测试同时覆盖观测和日志，因此作为一个可独立验证的修复批次，避免拆出测试不通过的中间提交。
2. 进路表示器示例、直接加载示例的测试及Gradle资源接线。
3. 原有本地模型/资源包ZIP和对应资源JSON；保留原字节，不重打包或修改模型格式。
4. 既有TODO、研究handoff、物理计划和端点迁移部署审计；docs-site六份架构文档先在子模块内提交，再由主仓库记录gitlink。
5. 合并codex/world-epoch-events中的9ca9ebe6和1650490e。33份任务副本先保留在恢复目录，再由合并产生tracked文件，避免重复提交相同任务内容；协调器和事件规范须同时保留日志与epoch实现。

用户对本次积压提交已明确授权，不再次要求其确认同一批修改。原“代码仍待确认”文字保留为历史记录，由本节更新当前状态。

## 资源检查

41份示例/资源文件中8个ZIP全部CRC通过，成员路径均为安全相对路径，磁盘及ZIP内JSON均可解析。表示器完整ZIP的30个文件中29个与示例逐字节一致，仅README不同；独立资源包ZIP的26个文件全部与示例资源包一致。没有声称旧ZIP都已按最新模型坐标重新导出；本次提交保留现有文件。

## 当前候选检查

检查代理运行前捕获1041份产品/测试/构建输入文件，清单在`/tmp/jr-backlog-source-before.json`，集合SHA256为`092cb91a47dad5af8178f3bf4fdc6bba7064276dd2c08d51c52ef089c336da44`。

当前积压候选门禁与独立静态复核通过：Core422、Basic413、BukkitPlugin449、SimpleSignals629，共1913项，失败/错误/跳过均为0。Bukkit/Signals在完整门禁实际执行，Core/Basic随后单独强制执行835项；不是只读取旧XML。Signal API Java consumer、verifyPluginJar、verifySignalModuleBoundaries通过。完整门禁2m29s，Core/Basic强制执行19秒；日志为`/tmp/jr-backlog-gate.log`及`/tmp/jr-backlog-core-basic-rerun.log`。源码前后1041文件集合hash完全一致。

独立检查未发现产品缺陷；唯一修正是docs-site的T5-04，将未提交新授权时“保持零速”改为“保留已有授权边界”，依据TimetableDispatcher的committedAuthority路径。其余六份文档变更与当前Basic共享准入/Dispatcher语义吻合。子模块提交为`29a59d8`，只含这六份架构文档，主仓库随后记录该gitlink。

积压提交为`dfc7b0e7`（运行时）、`cd2d04bb`（表示器示例）、`abec3837`（模型资源）、`01d2c7fe`（文档及子模块引用）。事件分支已无冲突合并到工作区，组合候选另行验证，不能把上述积压候选结果直接归给组合源码。

组合验证第一次在SimpleATO的6项测试出现两个匿名内部类的ClassNotFoundException，其他已完成模块结果保留。失败后的class实际存在且javap可解析，没有类初始化错误；重建前后141个测试class逐字节相同，无源码或断言改动。保留原XML及class到`/tmp/jr-combined-simpleato-test-output-before-rebuild`，证据摘要在`/tmp/jr-combined-failure-evidence.json`。针对性重新编译后两个测试类6项通过（`/tmp/jr-combined-simpleato-rebuild.log`，1m02s）；根因尚未确定，只能记录为未再次复现的本地产物读取/类加载异常。随后继续实际执行SimpleATO及尚未完成模块的完整门禁，最终六模块完整结果如下。

本次不修改持久化版本、输入格式或数据兼容路径。既有离线/部署记录只按其实际范围保留；不以本轮自动测试替代客户端交互、真实行车或Folia生命周期验收。


## 组合候选最终结果

积压提交：dfc7b0e7（运行时）、cd2d04bb（示例）、abec3837（资源）、01d2c7fe（文档及子模块引用）。事件分支无冲突合并，独立检查确认JUL日志、逐epoch Pre/Post、成功完整frame提交后锁外Post、普通listener异常隔离、碰撞及平台SIMULATION过滤均保留。

最终Core442、Basic413、SimpleATO378、BukkitPlugin450、SimpleSignals629、TunnelBridgeBuilder31，共2343项，失败/错误/跳过均为0。Core/Basic在首轮实际通过，其他四模块在最终后续门禁完整执行通过（1m57s），并非只重跑最初失败的6项。Signal API consumer实际编译、插件JAR及模块边界通过；Core Dokka实际生成，本批新增事件属性的文档告警消除，其他既有API和离线链接告警保留。

源码1070文件集合前后完全一致，SHA256为`6dd87af9cef34dd21aa4c77bf81ce37dd4a3ab2deff42ed16745876cc0dba56a`，清单`/tmp/jr-combined-source-after.json`。最终日志`/tmp/jr-combined-gate-final.log`，最终计数`/tmp/jr-combined-verification-summary.json`。首次类加载异常和重建证据如上保留，根因仍未确认，不声称修复了源码缺陷。

```bash
GRADLE_USER_HOME=/workspace/.gradle JAVA_HOME=/workspace/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ./gradlew --offline --no-daemon --no-parallel --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=640m' -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test --rerun :BukkitPlugin:test --rerun :SimpleSignals:check :SimpleSignals:test --rerun :TunnelBridgeBuilder:test --rerun :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :Core:dokkaGeneratePublicationHtml -x :WebUI:installFrontend
```

Core/Basic已在首轮分别使用局部--rerun实际执行，最终命令复用其未变结果。World事件子任务已完成，可归档；World隔离/信号/行为三个后续子任务及Stage 2尚未完成，不随本次收尾启动或归档。无部署、迁移或推送。
