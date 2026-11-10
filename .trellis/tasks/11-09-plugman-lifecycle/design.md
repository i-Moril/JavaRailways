# 设计

## 最小边界

根因是 composition root 按一次 JVM 启动组织资源，而管理器会重复启停或更换类加载器。主要修改 BukkitPlugin 生命周期、WorldManager、模型/工具 owner、公开 service consumer；仅在证明必要时扩展 Core 物理静止或 DI scope 生命周期能力。

保留对外字段/API 和磁盘契约，不把整个 BukkitPlugin 重写为框架。

## 资源生命周期

- 插件加载周期：Paper/Cloud lifecycle handler、平台探测、首次网络预加载。命令 manager 不能每次 enable 重新注册 lifecycle handler，关闭又会移除旧 handler，故只复用不足以保证恢复。本轮依用户要求不修改 Cloud，后续单独设计命令接入；新类加载器创建新 manager。
- 单次 enable：网络实例、世界/tick/physics、控制器、工具/预览、HTTP、脚本执行器、sidecar。成功初始化后登记清理，失败逆序释放；关闭幂等。
- 静态注册：Seat 索引、registered 与 callbacks 由 enable owner 释放；旧 service 不能解析到下一代 runtime。

PacketEvents 首次保留 onLoad 预加载；再次 enable 创建全新已加载 API，不复用 terminated 实例。检查 load/init 未完成时已注入 handler 的撤销，以及 EntityLib 静态 API 清理。

## 停止与数据

入口拒绝新操作 → 停止 simulation 和异步 producer → 等待计算并释放驾驶接管 → 捕获 Core/Basic 一致快照 → writer 完成最终保存 → 所有模型/原生实体/工具预览释放 → 静态注册、控制器和网络库关闭。

核实 physics fallback 是否会在 capture 与 close 间继续推进，不能只等待一个旧 ticket。控制会话释放时世界必须仍存在；保存遵循现有 dispatcher 恢复语义。

保存失败保留唯一可恢复材料；汇总错误后继续释放其他资源，不能把 cleanup 调用返回当作全部成功。

## Paper / Folia

本地 JavaPlugin.setEnabled 在 onDisable 前置 enabled=false，故不能依赖在 onDisable 新提交 region 任务再等待。先验证 server scheduler 和 PlugMan 调用语义；禁止借用其他插件身份、反射更改 enabled、绕过 owner guard、阻塞当前 region 等待自己。

Paper 已有实体 owner 时同步删除；Folia 必须验证每个 cleanup 的执行路径和完成结果。若管理器直接 disable 无法安全完成，记录阻塞证据并复审范围。

## 依赖插件

TunnelBridgeBuilder 服务失效时拒绝新操作和旧异步结果，同实例主插件恢复后重新取得服务。unload/load 涉及 API Class identity，硬依赖插件须按顺序共同重载，不保留旧 Rail 或强转新旧类。

## 预期文件

- BukkitPlugin.kt、render/backend/PacketEntityRuntime.kt：启动关闭及失败资源 owner。
- logic/WorldManager.kt：停止、最终保存、world cleanup 完成结果。
- render/chunk/RenderChunkManager.kt、extension/train/*Extension.kt、render/model/SeatModel.kt、util/PlatformDispatcher.kt：实际需要的显式销毁、owner 完成通知、静态清理。
- tool/AbstractWand.kt、RailWand.kt、RailObjectWand.kt：在线状态、预览和周期任务。
- command/CloudCommandRegistrar.kt：命令生命周期及执行入口。
- TunnelBridgeBuilderPlugin.kt 和最小 service consumer：失效重绑定。
- Core physics/DI：只在证明确有缺口后扩展最小释放能力。

## 回滚

集成使用独立测试目录和复制 fixture，不操作现有开发服/生产存档。保持 schema，源码回滚仅涉及任务文件；保留命令日志、前后快照和实体/任务/连接统计。

## 2026-11-09 实施范围确认

用户明确批准：先修改 Cloud 命令管理器以外的部分，只在当前独立工作区实施。CloudCommandRegistrar、Cloud 依赖和命令接入方案均保持原样；因此本批不能宣称完整 PlugMan 兼容。命令是已知剩余阻塞项。其余 runtime、保存、owner cleanup、工具、静态注册和服务 lifecycle 按前述范围实施。禁止操作现有开发服、提交或推送。

## 已实施的失败边界

services/HTTP/dispatcher 停止失败时不写最终世界快照；physics 静止失败也不捕获旧状态。保存/资源清理失败会记录汇总，并拒绝同实例再次启用，要求重启服务器后恢复。PlatformCleanup 对嵌套 owner 任务等待真实完成，超时后禁止该 batch 的晚到回调执行。Folia 任意 region 调用线程和正常整服 shutdown 的 owner 可执行性仍需独立实服验收。

## 2026-11-10：独立 PacketEvents（替代此前内嵌方案）

用户要求改为独立安装 PacketEvents。最小行为差异是 JavaRailways 不再拥有网络库实例，而只拥有其上的 EntityLib 和 motion 资源。修改 BukkitPlugin/build.gradle（compileOnly、排除传递依赖、硬依赖、开发服下载）、PacketEntityRuntime/BukkitPlugin 生命周期（仅 enable 时取得已初始化的外部 API）、PacketEntityMotion（保存注册时的 EventManager，精确注销自己的 listener）、相关测试和 BukkitPlugin/README.md。EntityLib 仍内嵌并重定位，默认不启用其 tick/update/BStats，也不使用 ViewerEngine，因此 init 本身不注册 PacketEvents listener/执行器。

禁止调用外部 PacketEvents 的 setAPI/load/init/terminate/uninject/clearBuildCache/unregisterAllListeners；不改变 Cloud、持久化 schema、服务器部署与第三方插件。保留已有生命周期改动。单元测试与 JAR 清单验证资源归属和独立依赖；实际 Paper/Folia 与管理器矩阵仍单独验收。

## 2026-11-10：Folia 单工作线程诊断与停服清理边界

本轮只在 Bukkit 适配层添加诊断和真实停服分支，不更改 Cloud 或线程体系。Folia 启用后首次 global callback 反射读取实际 TickRegions scheduler 的 worker count；只有确认为 1 时警告，无法读取时明确标记未知，不能用 CPU 数或自动配置值代替。BukkitPlugin 根据 Folia + Bukkit.isStopping 区分停服与热禁用。停服仅允许跳过已证明非持久化的原生实体 owner 操作；世界快照、physics 静止、writer、监听器、执行器和本地资源仍必须释放。Wand PDC 属于持久化边界，不能未经论证静默丢弃；等待独立资源审计后确定最小分支。预期涉及 BukkitPlugin、隔离的 Folia runtime 诊断、PlatformCleanup/Dispatcher 和必要的原生 spawn 初始化；相关测试覆盖诊断与清理模式，实服验收仍独立。
