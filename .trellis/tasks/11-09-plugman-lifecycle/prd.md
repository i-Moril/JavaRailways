# PlugMan / PlugManX 启停兼容

## 目标

用户希望通过 PlugMan 和 PlugManX 启用、关闭和重新加载 JavaRailways，不产生异常世界数据或遗留运行资源。2026-11-09 用户同意先修复同实例启停、再验证卸载重载的两批方案。本任务包含正式实施前的落盘计划审阅。

## 范围与约束

- R1：同实例 disable → enable 可重复执行；部分启动失败可以安全清理。
- R2：同版本 JAR 的 unload → load / reload 释放旧实例，恢复同一份世界数据；验证命令、在线玩家网络连接和依赖插件。
- R3：关闭先阻止新修改，再停止并等待计算，保存最终一致状态，清理实体/监听器/任务/执行器/HTTP/子进程/静态索引；失败汇总能定位组件与恢复材料。
- R4：Paper 与 Folia 分别验证。Folia 不得通过跨 region 操作实体或持锁等待掩盖清理问题；没有通过的平台组合不能宣称兼容。
- R5：保持现有磁盘 schema、严格 decoder、备份和恢复机制，不新增旧开发数据 runtime 兼容路径。
- R6：TunnelBridgeBuilder 不继续使用上一代服务或类身份，明确硬依赖重载顺序。

不含跨版本 JAR 热升级、任意第三方 add-on 改造、现有开发服/生产服部署、存档迁移、自动提交或推送。

## 已确认事实

基线为干净工作树 3d544b98da62888862f460c917651a989bc62f11。

- F1（R1）：BukkitPlugin.kt:114-127,261-263 只在 onLoad 创建 packet runtime，disable 清空后 enable 必然失败。
- F2（R1/R3）：render/model/SeatModel.kt:312-322 的静态座椅索引、回调、registered 缺少统一关闭入口。
- F3（R3/R4）：render/chunk/RenderChunkManager.kt:55-67、util/PlatformDispatcher.kt:112-124 的 owner cleanup 没有完成屏障；最后一个 observer 隐藏时也会销毁模型，不能推断正常关闭必然残留。
- F4（R3）：logic/WorldManager.kt:218-247 等待物理 ticket 并写最终快照；BukkitPlugin.kt:238-254 在世界注销后才停止驾驶控制。
- F5（R1/R2/R4）：command/CloudCommandRegistrar.kt:14-17 每次创建 Cloud manager。本地 cloud-paper 2.0.0-beta.17 sources 的 ModernPaperBrigadier:122-123 注册 COMMANDS handler；本地 Paper API 1.21.11 sources 的 JavaPlugin:272-285 在首次 enable 后关闭 lifecycle registration，在 onDisable 前设置 enabled=false。
- F6（R1/R2）：本地 packetevents-spigot 2.12.1 sources 的 SpigotPacketEventsBuilder:105-140,242-268,308-319 包含 load/init/terminate、已有玩家映射和移除网络 handler；实际重新注入仍需服务器验证。
- F7（R3）：tool/AbstractWand.kt:63-77,216-220 在退出时写回玩家内存状态；热关闭时在线玩家不一定退出，工具预览与周期任务也需要关闭。
- F8（R6）：TunnelBridgeBuilderPlugin.kt:19-32 在 enable 捕获 RailTargetService / PlayerOperationService，主插件重启不会自动刷新旧引用。

## 验收

- AC1（R1）：同实例连续至少 3 次启停，命令、座椅、驾驶、WebUI 可再次工作，无重复监听或回调。
- AC2（R2/R6）：记录两种管理插件和服务器的实际版本，分别执行 disable/enable、unload/load、reload；依赖插件按正确顺序重新取得服务。
- AC3（R3/R5）：含轨道、列车、时刻表、手动接管和 Wand 状态的 fixture 保存重载后满足现有恢复语义；不要求所有 runtime 状态逐字节相等。
- AC4（R3/R4）：在线/乘坐玩家、跨 region 列车关闭后无孤立实体、遗留模型、旧任务/线程、端口/子进程；清理失败不能报告成功。
- AC5（R1/R3/R5）：覆盖启动中途失败、快照写失败、退役实体、迟到回调；损坏输入和恢复点不被空世界覆盖。
- AC6：受影响模块测试和打包通过；静态审查、单元测试、真实 Paper/Folia 集成验收分别记录。

## 技术验证边界

Folia 禁用阶段 owner cleanup、Cloud 命令恢复、PacketEvents 在线玩家重新注入和跨类加载器依赖重载均为实施验证项。若平台/管理器没有安全路径，报告具体限制并复审范围，不反射更改 enabled 或越过线程检查。

## 2026-11-09 实施范围确认

用户明确批准：先修改 Cloud 命令管理器以外的部分，只在当前独立工作区实施。CloudCommandRegistrar、Cloud 依赖和命令接入方案均保持原样；因此本批不能宣称完整 PlugMan 兼容。命令是已知剩余阻塞项。其余 runtime、保存、owner cleanup、工具、静态注册和服务 lifecycle 按前述范围实施。禁止操作现有开发服、提交或推送。

## 2026-11-10：PacketEvents 部署变更

用户明确要求 JavaRailways 不再内嵌 PacketEvents，改为独立安装。此要求取代 F6 和原网络 runtime 重建方案中 JavaRailways 自建/终止 PacketEvents 的部分：独立 `packetevents` 插件拥有 API 和网络注入生命周期；JavaRailways 只拥有自身监听器与重定位 EntityLib 资源。

新增验收：发布 JAR 不含任何 PacketEvents 类/服务实现；plugin.yml 声明硬依赖 `packetevents`；未初始化、已终止或低于 2.12.1 时 fail-fast；重复启停与部分初始化失败不调用共享 API 的 load/init/terminate/uninject、不注销其他插件 listener。EntityLib 保持内嵌；不更改 Cloud，不部署服务器。
