# 跟车修复：当前 main 全部改动热部署（2026-11-16 UTC）

用户要求编译并部署，并明确选择“部署当前 main 全部改动”。沿用“不关服”和不创建 Trellis 任务的要求。仅替换主插件，附属插件原 JAR 按依赖顺序热卸载/加载；未修改配置、模型、时刻表或服务器 JAR，未推送 Git。

## 来源与验证

- 当前分支 `main`，源码 `d46b0434`；实时远端 main 为 `da50dd56808535ea2fc4714c4ed9f98fcdae41a5`，包含于本地 HEAD。
- 除 `33a03f82` 跟车开关、`2c31d465` 平台重复旋转修复，还包含 EntityLib 更新和 `9b35c816` 自动曲线限速刷新；用户在展示差异后授权完整候选。
- `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test :BukkitPlugin:shadowJar verifySignalModuleBoundaries --continue` 通过。BukkitPlugin 970 项，969 通过、1 跳过，0 failures/errors。
- Basic 的 RailDerivedSpeedMetadataTest、RailSpeedCalculatorTest、RailInfrastructureMetadataTopologyTest 共 19 项通过。
- 候选 `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`，62,903,189 bytes，SHA256 `a58066a2a4da88d3bebcba12881d5dcef62bc8a7bb1ee0eaf3c37dd74c4cedcc`。

## 恢复点与替换

- 测试服 `user@192.168.1.230`，MCSManager JavaRailwaysTest，实例 `836082a48c4f48fcb1fea4e83c57dc96`，Folia 26.1.2-8 / Java 25。使用 `/run/host-ssh-agent/agent.sock`。
- 部署前旧 JAR SHA256 `48ad69e991dd6319ef280767a9a97f0b19f5e11b6d3d95807b899f692a9b8189`；在线 0 人，Physics/ATO 均 RUNNING。
- JVM PID 始终为 `1107713`，没有停止或重启 JVM。
- 恢复目录 `/opt/mcsmanager/backups/javarailways/passenger-main-d46b0434-20261116-vin8t_0f`。包含全部插件 JAR、JavaRailways、SimpleSignals、TunnelBridgeBuilder、Decorations、SimpleDisplays、PlugManX 数据；目录0700、归档/回执/旧JAR0600。
- `plugin-state.tar` 共171,120,640 bytes，SHA256 `aa5f79a8666fa6e1572d0a137afb58cfb68a488e98911874e1dc1a1e31d27169`；tar compare 与插件 JAR 前后哈希比对通过。此恢复点不含 Minecraft 世界区块和玩家存档，不能当作整服快照。
- 部署脚本、候选 manifest、安装回执位于 `/tmp/jr-passenger-hotdeploy-20261116/`，恢复目录另存 backup.json、installation.json 和 JavaRailways-before.jar。回执保留全部插件 JAR 哈希；替换保留原属主/权限。
- 原子替换仅影响 `plugins/JavaRailways.jar`；其余13个插件 JAR 哈希未变。实际安装哈希与候选一致；字节码包含 `passengerRootInverseRotation`，内置 config.yml 包含 `passenger-movement-enabled`。外部 config.yml 未添加该键，按新 reader 的缺省 true 启用。

## 热加载与结果

- 07:07:06 前按 Decorations、TunnelBridgeBuilder、SimpleSignals 顺序卸载附属插件；SimpleSignals 卸载引发已有基础设施依赖保护和 ATO Stall 诊断，随后主插件准备关闭。
- 07:07:39 首次请求卸载 JavaRailways；07:07:40 确认 GentleUnload preparation completed，随后执行备份、校验和原子替换。
- 07:08:28 第二次请求正式卸载旧 JavaRailways，紧接着主插件、SimpleSignals、TunnelBridgeBuilder、Decorations 按依赖顺序加载；07:08:33 全部启用并启动 physics ticks。
- 07:08:54 实际 `plugman info` 确认四个插件 Enabled；`jr help` 正常，Physics/ATO 均 RUNNING，world 为0个当前问题。
- 8765 bridge `/health` HTTP200 `{"ok":true}`；29002 WebUI `/health` HTTP200 `{"status":"ok"}`。
- physics epoch 在07:09:31为1148，07:09:45为1430；两次均 active=0/5、overloads=0，证明引擎在推进，不代表列车已发车。
- 加载时有一次5.327秒 global-region watchdog 记录，栈位于附属插件类转换/加载，随后同秒完成加载；之后控制台与引擎正常响应。保留既有 TruffleAttach 环境警告、WebUI HTTP/非loopback配置警告以及启动阶段一次 physics overload/clamp。未将这些日志隐去或扩展本次修复范围。

## 实机与回滚边界

没有发车、重置时刻表、重置ATO/physics或操纵玩家；当前活动控制器0/5。按已有恢复合同，运行恢复及站台误带/车内跟车效果仍需用户现场验收。部署与服务健康不替代客户端验收。

需要回滚时，先依附属插件优先及两阶段GentleUnload流程完成清理，再核对目标仍为本候选哈希；将恢复目录旧JAR原子放回并按依赖顺序加载。插件数据恢复必须另行审阅运行后新增状态，不直接覆盖活跃实例；本次未执行回滚。
