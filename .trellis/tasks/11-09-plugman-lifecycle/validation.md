# 验证记录

基线：3d544b98da62888862f460c917651a989bc62f11；工作区 /home/user/.codex/worktrees/76e6/JavaRailways。

## 2026-11-09：此前非 Cloud lifecycle 批次范围

此处记录此前批次完成时的事实：仅非 Cloud lifecycle；当时 CloudCommandRegistrar.kt 和 BukkitPlugin/build.gradle 与 HEAD byte-for-byte 一致。JavaRailwaysCommand 仅新增运行时拒绝操作的入口检查，不改变命令注册。

## 已运行

`./gradlew :Core:test :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test :BukkitPlugin:shadowJar :TunnelBridgeBuilder:shadowJar :Core:dokkaGeneratePublicationHtml --console=plain --no-parallel --max-workers=2`

结果：BUILD SUCCESSFUL。模块 JUnit XML 汇总：

- Core: tests=128, failures=0, errors=0, skipped=6
- Basic: tests=336, failures=0, errors=0, skipped=1
- BukkitPlugin: tests=244, failures=0, errors=0, skipped=0
- TunnelBridgeBuilder: tests=34, failures=0, errors=0, skipped=0

`git diff --check` 通过。两个 shadow JAR 的 plugin.yml 存在；主插件包含 webui/JavaRailways-WebUI.jar，add-on 硬依赖 JavaRailways。

## 审查

Trellis 独立全差异审查修复 operation service 关闭接纳竞态和 Wand 错误后跳过保存；进一步加入 producer drain 失败禁止最终保存及不完整关闭后拒绝同实例重启。

## 未验证与剩余限制

- 未启动任何服务器，没有 PlugMan/PlugManX × Paper/Folia 实际操作日志，不能据单元测试给完整热启停兼容声明。
- Cloud 命令管理器依用户要求未修改，仍是同实例 enable 的已知阻塞。
- Folia region A 同步禁用时，B 的嵌套 cleanup 若需要回到 A 可能超时；失败会汇总且拒绝同实例重启。整服 stopping 的 region tick 可执行性独立验收。
- 任意第三方已接纳的异步 UndoableOperation 不能通用撤销；本仓库 TBB 使用 service-generation fence，跨 API 类加载器替换要求共同重载 add-on。
- 未改变存档 schema，未操作现有服务器、部署、提交或推送。

## 2026-11-10：独立 PacketEvents 验证

本批按用户后续要求修改 BukkitPlugin/build.gradle 的 PacketEvents dependency/relocation/plugin dependency/development download 部分，取代上述历史 build.gradle 不变状态；CloudCommandRegistrar.kt 与 Cloud 依赖/relocation 配置仍保持原样。

最终命令：

```sh
./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar :TunnelBridgeBuilder:test :TunnelBridgeBuilder:shadowJar --offline --console=plain --no-parallel --max-workers=2
```

结果：BUILD SUCCESSFUL。BukkitPlugin 251 测试、TunnelBridgeBuilder 34 测试，全部通过，零失败/错误/跳过（TBB 测试任务保持 UP-TO-DATE，下游 Kotlin 重新编译成功）。本次新增 PacketEntityRuntimeTest 7 个用例：共享 API 3 轮启停、外部 listener 保留、EntityLib 部分启动失败、未初始化/已终止/版本过旧拒绝、motion 精确注销及部分注册失败可清理。共享 API fake 的 load/init/terminate/injector 均直接抛错，防止 consumer 越界管理网络实例。

JAR 实查：`BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar` 的 plugin.yml 声明 `depend: [packetevents]`；没有 `retrooper`/`packetevents` 路径、类、资源或服务实现；277 个 EntityLib class 位于 JavaRailways relocation 路径；`webui/JavaRailways-WebUI.jar` 保留。SHA-256：`684c5bce8f5de6f4e7a3ef2a7ca0486a1a5fdda40f78828d924eb59c7b1c29f2`。

`git diff --check` 通过。没有启动 Paper/Folia、运行开发服下载、提交、推送或部署。开发启动任务的下载声明已通过 Gradle 配置，但下载执行与服务器热重载仍未验证。Cloud 管理器未修改。


## 2026-11-10：Folia 诊断与停服临时资源

完成单 tick worker 警告、Folia 真正停服跳过 owner 操作、三个 spawn 后设置 persistent 的路径提前至 initializer。静态资源审计见 research/folia-shutdown-resource-audit.md；全部十处原生 spawn nonpersistent，packet entity 不入原生世界；玩家 RootVehicle 与玩家自身保存分开核实。保留 JR 快照、工具 mutation-time PDC 保存及热禁用 close flush。

实现验证命令：`./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar :TunnelBridgeBuilder:compileKotlin --console=plain --no-parallel --max-workers=2`。BUILD SUCCESSFUL，BukkitPlugin 254 tests，0 failure/error/skip，下游 Kotlin 编译和主 JAR 打包通过。独立 trellis-check 没有阻塞项。

主会话按审查建议补充“停服时 retired callback 也不能执行”的失败断言，运行 `./gradlew :BukkitPlugin:test --tests work.art1st.javarailways.bukkit.util.PlatformCleanupTest --tests work.art1st.javarailways.bukkit.util.FoliaRuntimeDiagnosticsTest --offline --console=plain --no-parallel --max-workers=2`，BUILD SUCCESSFUL。这次仅修改测试，生产 JAR 与全量通过时相同。

`git diff --check` 通过。没有执行真实 Folia/PlugManX、Anvil 或 playerdata 保存验收，没有提交、推送、部署。单 worker 热禁用仍可能超时，用户明确接受此限制，仅增加警告；本轮不宣称完整热重载验收通过。


## 2026-11-10：用户授权提交前检查

用户明确要求提交当前修改。再次执行受影响模块完整检查：`./gradlew :Core:test :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test :BukkitPlugin:shadowJar :TunnelBridgeBuilder:shadowJar :Core:dokkaGeneratePublicationHtml --offline --console=plain --no-parallel --max-workers=2`，BUILD SUCCESSFUL。JUnit XML：Core128（skip6）、Basic336（skip1）、Bukkit254、TBB34；共752，745 passed、7 skipped，零失败。最后一轮使用 Gradle up-to-date 缓存，首次扩展检查中 Bukkit 测试实际重新运行。主 JAR 无 PacketEvents entry，plugin.yml 硬依赖 packetevents，WebUI 保留。

额外执行 BukkitPluginDev:compileKotlin 发现既有错误：BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/tests/ConfigApiTest.kt:273 的 when 未覆盖 NUMBER_TEXT。该文件及 Basic ConfigValueType.kt 与 HEAD 无差异；没有将无关开发测试修复混入本次提交。业务模块所需检查均通过。没有启动服务器、推送或部署。

用户同时要求只读审查剩余热重载问题；结果单独反馈，未自动修复新发现的生命周期边界。当前任务仍 in_progress，实服验收与剩余问题未完成，不归档为已实现完整兼容。


## 2026-11-10：审查项 1 / 3 / 5 验证

基线1718739b。最终命令：`./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar :TunnelBridgeBuilder:test :TunnelBridgeBuilder:shadowJar --offline --console=plain --no-parallel --max-workers=2`。BUILD SUCCESSFUL；BukkitPlugin261 tests、TBB34 tests，零失败/错误/跳过（TBB测试为UP-TO-DATE）。新增生产 callback adapter/真实 dispatcher 跨代及排队测试1项；实际 Sidecar.stop/Process owner 失败测试6项。独立 trellis-check 未发现功能阻塞；通用异常缺少PID的小契约缺口已补充并完整复验。

另外执行 BukkitPluginDev:compileKotlin 核对新 DialogRenderer 调用，仍仅因基线 ConfigApiTest:274 缺 NUMBER_TEXT 分支失败；没有 renderer 构造签名或 import 错误。没有混修此前错误，不能宣称全仓构建通过。

第1项已作为部署操作约定和硬依赖核对完成，未修改实际服务器的 PlugManX 配置。Cloud 和跨实例关闭失败标记保持原样。无实服、真实子进程、客户端或存档验收；未提交、推送、部署。`git diff --check` 通过。


## 2026-11-10：关闭失败警告

基线af656dea。仅更改 BukkitPlugin.finishShutdown 已有失败汇总的文案；逐项异常仍记录，成功路径不输出此警告，不增加调度/广播/磁盘标记/跨实例保护。独立 trellis-check 审查通过。

`./gradlew :BukkitPlugin:test :BukkitPlugin:shadowJar --offline --console=plain --no-parallel --max-workers=2` BUILD SUCCESSFUL，261 tests、0 failure/error/skip。文案改动不新增镜像实现测试。git diff --check通过；无实服、提交、推送、部署。
