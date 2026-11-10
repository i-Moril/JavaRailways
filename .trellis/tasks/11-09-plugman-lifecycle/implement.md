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
