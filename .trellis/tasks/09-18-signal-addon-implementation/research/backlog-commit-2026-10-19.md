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

事件分支合并后的组合候选另行验证，不能把上述积压候选结果直接归给组合源码。

本次不修改持久化版本、输入格式或数据兼容路径。既有离线/部署记录只按其实际范围保留；不以本轮自动测试替代客户端交互、真实行车或Folia生命周期验收。
