# 执行计划

状态：用户于 2026-10-17 批准最终规划及“名称独立保存”解释；任务已进入 in_progress，trellis-implement 已实现，trellis-check 已完成修正及最终验证；本地完成，用户已明确要求提交；隔离提交候选验证通过，未部署。

1. 实现不可变名称元数据与公共物理边界解析：复用 rail/cut 判同，覆盖普通闭塞、正式/草稿保护边界、pending；旧字段默认空，当前 schema 不变。
2. 接通名称 patch、复制、候选归并/删除清理、staged save 与 editImpact；只改 definitionRevision，不改几何/设备 ID 和 revision。先验证生命周期与事务。
3. 现场命名：设置入口、一次性选点、文本保存/清除/取消、完整 guard、中文英文提示；复用 native Paper dialog。
4. 统一现场和进路/入口标签，保留精确 routeId 的回调身份，补重名/未命名位置与多路径独立行。
5. 运行聚焦测试及模块质量检查，派发 trellis-check 全范围检查并修复；同步 specs。提交和部署由后续明确授权处理。

## 有意义的测试
- 独立名称解析测试：相同轨道位置/真实端点连接同步；重合交叉和 turnout 不合并；分裂继承、合并冲突、重复名字。
- SimpleSignalsWorldDefinitionStoreTest：固定旧 JSON 缺失新增字段、Unicode/重复名称 roundtrip、严格无效输入、schema 不变。SimpleSignalsDefinitionDraftsTest 覆盖导入/复制/草稿。
- SimpleSignalsWorldRuntimeManagerTest / edit impact：名称替换、清空、过期/拒绝/保存失败无变化；影响只含名字涉及的轨道；其他 patch 保留名字；边界正式化/修复/删除最后引用清理。比较原几何/进路/设备/映射。
- SectionWandHandlerTest：进入命名、点击、输入、取消/清名、未命中不创建、重复提交、上下文失效，原手势回归。
- SectionOverlayDrawingTest / RuntimeSectionOverlayBackendTest / SignalWandHandlerTest / RoutePreviewWandHandlerTest：现场名字、两端标签、重名精确回调、多路径及本地化。按实际测试类增补专用测试。

## 验证命令
使用现有 Gradle runtime，依照环境配置选择可用缓存；已知共享缓存为 /workspace/.gradle。

```sh
GRADLE_USER_HOME=/workspace/.gradle ./gradlew --offline --no-build-cache --no-parallel --max-workers=1 --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1536m -Dfile.encoding=UTF-8' -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :SimpleSignals:test :SimpleSignals:jar :SimpleSignals:verifyPluginJar verifySignalModuleBoundaries
git diff --check
```

聚焦测试先运行新增行为对应类，完整验证一轮通过后除非有新修改不重复。无 Core/Basic 产品代码更改时不额外全仓回归；若实现扩大模块范围，先更新计划和对应质量门槛。

## 所有权与现场限制
- 只修改 SimpleSignals 与相关 .trellis/spec/simple-signals/backend 文档，本任务文件。
- 工作区有大量其他会话未提交改动；先快照 dirty paths/diff，不能回滚、覆盖或提交不属于本任务的修改。
- SimpleSignalsWorldRuntime.kt、SectionWandHandler 等共享热点修改需基于实时内容接续。
- 不操作开发服、不安装模型、不启动/停止服务；回滚依靠本任务局部 diff，不能整文件覆盖其他会话改动。
- task.py start 之前需最新最终摘要获得用户明确批准；上下文 manifests 必须可读且无占位行。

## 最终结果

2026-10-17：59 套 / 622 项全部通过（零失败、错误、跳过）。最终命令 `:SimpleSignals:check :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`，日志 `/tmp/boundary-check-final.log`；详见 `research/check-validation.md`。规格已同步。真实客户端验收另记，未部署；后续用户授权提交，隔离候选57套/614项通过，见 research/commit-candidate-validation.md。
