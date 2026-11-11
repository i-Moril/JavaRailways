# 实施与验证

## 前置

- [x] 用户同意两批方向及创建任务，基线干净。
- [x] 阅读 BukkitPlugin/TunnelBridgeBuilder 规范和 schema 政策。
- [x] 核实本地 Paper、Cloud、PacketEvents 源码，完成 PRD 收敛。
- [x] 用户阅读计划及 Cloud/Folia 解释后，批准实施非 Cloud 部分。
- [x] task.py start；按 auto dispatch 执行分工实施和独立完整差异审查。

## 第一批：同实例启停

1. 验证 Paper 命令复用、Folia disabled scheduler；在 research 记录可行路径。
2. 修复网络 runtime 重建、partial load/enable cleanup、幂等 close 和失败汇总。
3. 清理 Seat 静态索引/回调，恢复 listener 注册。
4. 停止 Wand task，保存在线玩家状态，清理工具预览。
5. 建立 simulation/physics 停止、控制会话释放、最终保存与实体 owner cleanup 屏障。
6. 修复命令入口 gate、迟到回调和 service 失效语义。

## 第二批：卸载重载

1. 验证在线玩家网络重新注入和旧 handler 撤销。
2. 核对 DI scope、事件总线、PluginRegistry、静态实例；不全局停止其他 owner 的共享实例。
3. 修复 TunnelBridgeBuilder 重绑定，验证硬依赖及 API Class identity。
4. 在隔离服务器执行 PlugMan/PlugManX × Paper/Folia 矩阵；记录版本、命令、日志。未执行或未通过的组合不得声明兼容。

## 验证命令

- ./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar
- 改动 Core/Basic 时追加 :Core:test :Basic:test。
- 改动 add-on 时执行 :TunnelBridgeBuilder:test :TunnelBridgeBuilder:shadowJar。
- 覆盖重复启停、部分启动失败、迟到回调、最终保存失败、退役实体和真实保存恢复；mock 调用次数不能替代服务器验证。
- git diff --check，确认无无关修改。

## 当前状态

非 Cloud 代码已实施，四个受影响模块测试、两个 JAR 打包与 Core Dokka 均通过；详细结果见 validation.md。未启动服务器，不自动提交、推送或部署。完整热启停验收仍等待后续 Cloud 方案及隔离服务器矩阵。

## 2026-11-09 实施范围确认

用户明确批准：先修改 Cloud 命令管理器以外的部分，只在当前独立工作区实施。CloudCommandRegistrar、Cloud 依赖和命令接入方案均保持原样；因此本批不能宣称完整 PlugMan 兼容。命令是已知剩余阻塞项。其余 runtime、保存、owner cleanup、工具、静态注册和服务 lifecycle 按前述范围实施。禁止操作现有开发服、提交或推送。


## 本批已完成

- [x] 网络 runtime 重新创建、部分初始化清理和幂等关闭。
- [x] 禁用前准备、旧 dispatcher 代次 fencing、owner cleanup barrier 和最终保存失败保护。
- [x] physics 停止与准确 ticket 等待；world/parent DI scope 释放；PluginRegistry 稳定槽位注销。
- [x] Seat、控制会话、Wand/预览和在线 PDC 生命周期。
- [x] 主插件 service 关闭、TBB generation fencing 与重新获取服务。
- [x] 独立 review 修复 operation service admission/close 竞态、Wand 部分清理失败跳过其他玩家保存。
- [ ] 实际 PlugMan/PlugManX × Paper/Folia 操作验收：本批未运行。
- [ ] Cloud 命令管理器：用户明确排除，未修改。

## 2026-11-10：独立 PacketEvents 实施

- [x] 改为 compileOnly + `depend: packetevents`，排除 EntityLib 传递 PacketEvents，删除 PacketEvents relocation；EntityLib 保留内嵌 relocation。
- [x] 删除 PacketEntityRuntime.load 和所有共享网络 API 的生命周期/注入清理调用；在 onEnable 检查 API 已初始化、未终止、版本至少 2.12.1。
- [x] EntityLib 不启用 tick/update/BStats；自身 entities/callbacks/tick resources 清理不涉及共享 API。
- [x] PacketEntityMotion 捕获注册时 EventManager，按精确 listener 注销；注册中途失败也保留可注销 identity。
- [x] 开发服 runServer/runFolia 独立下载 PacketEvents 2.12.1，安装说明保存 BukkitPlugin/README.md；历史渲染计划追加替代说明。
- [x] 新增共享 API ownership、3 次重启、partial EntityLib setup、未初始化/已终止/旧版本拒绝、foreign listener 保留、partial listener registration 等测试。
- [ ] 真实 Paper/Folia 在线玩家 + PlugMan/PlugManX 矩阵仍未执行。

## 2026-11-10：Folia warning / full-stop resource cleanup

- [x] 启用后首次 global callback 读取实际 TickRegions scheduler 的 getTotalThreadCount；1 时警告，读取失败标记未知；不从 CPU 数量/配置默认值推断。
- [x] 仅 Folia && server.isStopping 在 PlatformCleanup batch 跳过 owner dispatch；不越过 ownership，不提交已停止区域任务，保留本地清理、physics 静止与最终快照 writer。
- [x] 热禁用继续完整 owner barrier 和在线 Wand PDC flush；停服依赖现有 owner-side mutation-time persistState，不依赖后续 PlayerQuitEvent。
- [x] Seat hitbox / Interaction / RailObject marker 的 nonpersistent 在 spawn consumer 内设置，RailWand 同类初始化把 flag 提前。
- [x] 测试诊断1/多线程/不可用，以及停服清理不访问原生 owner、local cleanup 正常、scope 恢复不污染热禁用。
- [ ] 真实 Folia 停服/在线玩家/PlugManX 验收仍未执行。


## 2026-11-10：仅 PlugManX 的 1 / 3 / 5

- [x] README 明确 REQUIRED_ONLY/ALL 级联配置、reload JavaRailways 操作、忽略列表限制及手工卸载顺序；TBB/Dev 已有硬依赖，未改外部配置或引入重载集成。
- [x] 全部四处生产 customClick 经 playerDialogCallback 绑定创建时 dispatcher；Wand/Dev 构造点同步，TrainControlDialog 不再动态读取新 dispatcher。
- [x] 配置 response primitive 在回调当场复制，session/PDC/UI/控制动作仅在 captured owner gate 内执行；旧代和排队回调拒绝，新一轮回调可执行。
- [x] Sidecar 正常/强制等待均确认退出后释放 owner，超时/终止异常保留 Process 并报告 PID，interrupt 恢复标志；root 仅成功后清空 sidecar。
- [x] 独立审查后补齐通用 Process 异常 PID 上下文及断言；Bukkit261测试与TBB34测试均通过，两个 shadowJar 打包通过。
- [ ] Cloud 本轮明确不改；跨类加载器失败标记（第4项）明确暂缓。
- [ ] 实际 Paper/Folia + PlugManX、客户端旧 Dialog 点击和真实子进程退出验收未执行。


## 2026-11-10：暂缓第4项，保留明确警告

- [x] 不增加跨实例运行标记；只强化现有非空 shutdownFailures 的 SEVERE 汇总，列出失败组件及 PlugManX 仍可能继续加载的风险和后续处理。逐项异常、关闭流程、成功路径、同实例失败保护均保持原样。
- [x] 独立审查无发现；Bukkit261项测试及shadowJar通过，git diff --check通过。未提交或部署。


## 2026-11-11：Folia PlugManX GentleUnload 两次请求

用户明确要求支持 threaded-regions.threads=-1 自动选择单 tick worker 的情况，并授权先接入 GentleUnload，不自动发起第二次卸载。仅修改本独立工作区，不提交/部署/操作实服。

最小行为差异：Folia 上 PlugManX 3.2.1 第一次 unload 请求通过 GentleUnload 启动异步准备并返回 false，释放 global tick worker；准备期间重复请求仍拒绝。等待真实 owner cleanup、嵌套任务和最终保存完成且全部成功后标记 ready，提示再次执行原命令。第二次请求才返回 true；PluginDisableEvent/onDisable 复用准备结果，避免重复等待。失败保持拒绝并记录原因。普通 Paper/Bukkit、正常停服以及外部 PacketEvents 所有权保持原契约。独立 disable 不经过 PlugManX GentleUnload，不能宣称覆盖。

预期边界：BukkitPlugin composition、PlatformCleanup 非阻塞完成接口、局部 GentleUnload 状态与可选 API adapter、必要的 plugin dependency metadata、生产路径测试和 README。优先不引入硬依赖或内嵌 PlugManX；任何反射适配仅针对已核对的 3.2.1 接口，不能更改 enabled、借用其他插件调度身份或绕过线程 ownership。异步准备不能简单把含 Bukkit 操作的整个 prepareShutdown 丢给异步线程；global/entity/region affinity 必须保留，等待点必须让 tick worker 返回调度器。异步期间正常停服或非 GentleUnload 禁用应有显式安全处理和测试。

验证：单 worker 队列第一次返回后才能执行 cleanup 的生产逻辑测试；重复/ready/failed/超时/嵌套任务/迟到回调/直接禁用及缺失 PlugManX 的场景。执行 Bukkit tests + shadowJar，检查 PlugManX 未内嵌；下游 compile/test 按实际变更边界。实服验证未执行不能声称已通过。

本批实施与独立审查完成：GentleUnload / 非阻塞 owner barrier / 精确注册释放 / 文档规范 / 269 项 Bukkit 测试与打包通过。实服验收待后续授权；未提交和部署。

集成到用户明确指定的 feat/simpledisplays-train-integration：保留显式world runtime并接入目标呈现服务cleanup stage。受影响模块与JAR/边界检查完成；记录一个目标已有 ProgramExpressionsTest 下标失败，未扩大范围修改。主工作区原有工作不纳入提交。


## 2026-11-11：Paper 描述文件迁移完成

用户确认最新 `8e40af9d` 所在分支实施。主插件唯一 paper-plugin.yml、现代 Cloud 保留、原有模块边界同步；配套打包通过。隔离 Folia 单worker中七插件启动和一次GentleUnload两请求重载通过，重载前后 `jr help` 均成功，临时实例已停；未验收真实客户端补全，未部署或提交。详情见 [迁移验证](research/paper-plugin-migration-20261111.md)。
