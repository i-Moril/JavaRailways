# 第二批修复（2026-10-23）

> 已修复项的触发范围、原示例纠错及测试证明边界，见 [fixed-findings-reaudit.md](fixed-findings-reaudit.md)；本页测试数字为当时运行记录。

范围来自本会话此前划定并获用户授权的第二批 12 组问题。保留第一批及既有未提交修改；不创建 Trellis 任务、不提交或部署。本批不是所有剩余审查问题，且不包括源码目录迁移/Wand 架构重构。

## 实施与对应验证

- BUK-01：从全部已解码磁盘候选的最高保存序号继续分配，每 exact world 实例独立，不使用重启归零 tick。WorldPersistenceRevisionTest 验证高序号备份、孤立中断写入、同 tick 唯一和溢出失败。
- BUK-02 / SS-3：WorldManager、SimpleSignals runtime、HTTP map、RailObjectWand 的卸载清理遵循 MONITOR/ignoreCancelled；EditorText 已符合。WorldManagerUnloadTest 通过 Bukkit RegisteredListener 验证取消时 runtime 保留。
- BUK-03：forceDiscardTimetable 先预检全部 run，命令 discard 成功后才释放控制器。TimetableDispatcherSharedAdmissionTest 验证早先 idle run 和后续 grant run 在拒绝时均保留 controller 身份与完整 statuses。
- BUK-05：sidecar 拒写抛错；装饰及撤销先保存再修改显示，拒绝不会创建成功 undo。RailRenderMetaStorePreparedSaveTest 验证损坏 bytes 不变、拒绝发生在 transform 前及 writer 释放。
- BUK-09：模型构造追踪未转交资源，失败逆序清理并保留原 cause/清理异常；CarExtension 在车体、全部 bogie、场景准备成功后发布字段。ModelTreeBuilderTest 验证实际模型树构造失败；ModelConstructionTest 直接验证 helper 的资源与异步清理，未调用真实 CarExtension 聚合构造。
- BUK-13：端点候选来自物理 rails，保留 split/assembly 内部端点隐藏。RailWandEndpointDiscoveryTest 验证未装饰轨道仍有两个可编辑端点。
- BUK-14：损坏 PDC 绑定持久化为不可写，切换、交互保存及退出不覆盖原 bytes；新物品可正常写入。AbstractWandCorruptStateTest 覆盖真实 AbstractWand 状态路径，包括未先读取就直接保存、退出清除 binding 后的迟到保存；缺少目标 binding 时先 decode，不能默认 writable=true。
- BUK-15：原世界、CoreWorld、编辑 token、config session、目标对象身份共同约束回调；在捕获/提交输入前拒绝，领域写入再核对。DialogRendererNumberTextTest 验证失效时不读输入、不改配置、不调用保存；RailObjectDestinationEditorTest 调用生产身份谓词验证重载同 UUID 世界、CoreWorld/会话/目标实例替换及删除。
- BUK-18：离座回调及位置/清理操作只在确认接受下车后执行。SeatDismountTest 通过真实 RegisteredListener 覆盖取消与接受两种情况。
- SS-1：固定进路加入自身入口依赖。SimpleSignalsWorldRuntimeManagerTest 真实 JS catalog 验证只有 readiness 变化时开放/撤回。
- SS-2：移除与局部复用合同冲突的全局 revision 相等要求；保留 per-device topology guard。真实 JS/epoch 测试验证无关编辑后照常更新、相关几何修改仍不可用。
- ATO-01：已撤销的空限制保留 revision 防旧消息，但不再因换路线恢复停车。TrainRestrictionConstraintAdapterTest 覆盖换路线、陈旧 revision 拒绝及异常来源丢失仍停车。

## 检查状态

- 保存/模型/下车/ATO 定向检查通过；信号运行时定向检查通过。
- 独立静态复核完成；主会话收尾额外补齐旧回调的短路求值，以及 Wand 缺 binding 时绕过 decode 的保存旁路，并加对应回归。
- 最终全量检查成功：`:BukkitPlugin:test :SimpleATO:test :SimpleSignals:check :Core:test :Basic:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries`，`BUILD SUCCESSFUL`。
- 测试报告：BukkitPlugin 567、SimpleATO 382、SimpleSignals 650、Core 505、Basic 390，全部 0 failures/errors/skipped。最终运行中 Bukkit/SimpleSignals 实际执行，SimpleATO/Core/Basic 使用 Gradle up-to-date 结果（SimpleATO 全量在本批先前执行，Core/Basic 未改生产代码）。
- WebUI 后端 12 项（up-to-date）、前端 21 文件/108 项通过。
- `:BukkitPlugin:shadowJar` 成功；最终全量阶段已重建，随后独立打包检查为 up-to-date。包内 WebUI JAR 的 public/index.html、Cloud relocation 和 15 个非空服务声明已检查。没有部署；旧 reobf JAR 不作为本次产物。
- 日志：`/tmp/jr-second-final.log`、`/tmp/jr-second-package.log`、`/tmp/jr-second-frontend.log`。早期测试夹具的 ID、admission callback、Paper plugin server/pluginMeta 初始化失败均已修正；最终无失败。
- `git diff --check` 通过。

## 验证边界

未启动真实 Paper/Folia 或做客户端验收。模型失败通过实际树 builder 与 cleanup helper 验证，不等同真实后端实体故障注入；CarExtension 的字段发布顺序经静态复核。Dialog 输入 gate 使用生产 callback 路径，世界/会话/目标判定调用生产身份谓词，并保留入口先验证 token/world/CoreWorld 再读旧对象的短路顺序；这仍非客户端端到端测试。无 schema 升版或新增旧开发数据兼容路径。
