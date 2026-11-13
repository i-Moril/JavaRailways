# Display metadata 增量：开发分支 rebase 与热部署

用户要求最新开发分支 rebase 后部署。沿用本会话不创建新任务的选择，目标为 `feat/passenger-movement` 和 JavaRailwaysTest；未推送。

## 分支与构建

- `main` 的修复提交为 `4b102dcd8b5c9f14efe8959d0cea9502cce35007`。实时查询远端 `main` 仍为 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，本地包含该基线；远端没有 passenger 分支。
- 开发分支原 HEAD `52fa87f6ba7e0e48a30f4672d8eac1715cc52281`，rebase 后源码 HEAD `c63268b987688abe7864f7ba61051e3816600a43`。7 个提交无冲突重放，`git range-diff 9e398aeb..52fa87f6 main..HEAD` 七项均为 `=`。
- 执行 `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test --tests '*TextDisplayBackendTest' --tests '*DisplayAppearanceBackendTest' --tests '*TrainVisibilitySimulationTest' --tests '*Passenger*' --tests '*Seat*' --tests '*ServerPhysicsStartupTest' :BukkitPlugin:shadowJar verifySignalModuleBoundaries`。
- 68 项测试全部通过，0 failures/errors/skips；打包、模块边界检查和 `git diff --check` 通过。测试堆设置复用既有 `/tmp` init script，没有改仓库构建配置。
- 此前 main 完整 BukkitPlugin 测试为 937 项、935 通过、1 跳过、1 个既有 S100 ZIP/source 一致性失败；本次不把聚焦测试称为完整套件通过。
- 部署 JAR 为 `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`，62,867,699 bytes，SHA256 `9f6969570a0e0f01464cbe4b4feea790b5f0e299b2191a2a7963c2a438fa87da`。

## 恢复点与替换范围

- 目标：`user@192.168.1.230`，实例 `836082a48c4f48fcb1fea4e83c57dc96`，使用 `/run/host-ssh-agent/agent.sock`；JVM PID 始终为 `1081210`，没有重启 JVM。
- 原主 JAR SHA256 `c0725cc57e23cc95f356196806abec99a284f0e48cdbf9b3bf6ab4dbc93179bd`。与新 JAR 比较只有 `EntityLibDisplayVisual.class` 和 `PassengerManagedDisplayEntity.class` 变化，无新增/删除；后者的 `javap -c -p` 指令输出完全一致。
- 恢复目录：`/opt/mcsmanager/backups/javarailways/metadata-delta-c63268b9-20261113-9jj3bqgk`。恢复包包含全部插件 JAR、JavaRailways/SimpleSignals/TunnelBridgeBuilder/Decorations/SimpleDisplays 数据和 PlugManX 配置，共195文件；不含 Minecraft 世界区块或玩家存档，不作为整服恢复点。
- ZIP 每个条目均与备份前清单逐字节核对，备份后再次核对源文件未变。归档 SHA256 `335d1d58f5d7c505ded39a7b15034c7c260516d54339a91a0e62d48f7bc7043e`，清单 SHA256 `2595230ca476fc76bcd1f9f449e7e07b03278e6c9bace01c071e8b8266a1aa8c`；目录0700、归档与证据0600。
- 只原子替换 `plugins/JavaRailways.jar`，保留属主和权限。其他12个插件 JAR 和 PlugManX 配置哈希未变。

## 热加载与验收（UTC）

1. 12:38:27 按 Decorations、TunnelBridgeBuilder、SimpleSignals 顺序卸载附属插件。SimpleSignals 卸载触发既有基础设施依赖消失保护，日志明确记录 ATO world stalled。
2. 12:38:41 首次请求卸载主插件，12:38:42 确认 GentleUnload 准备完成。
3. 12:39:17 安装新 JAR，12:39:18 第二次请求正式卸载。
4. 将主插件及 SimpleSignals、TunnelBridgeBuilder、Decorations 按依赖顺序一起排入控制台队列，12:39:24 全部加载完成后才启动 physics tick。
5. 实际 `plugman info` 确认四个插件 Enabled，`jr help` 正常返回，ATO/physics 均 RUNNING，0个当前问题。physics epoch 从495推进至518，最终再次从1384推进至1406。
6. 桥接8765及WebUI29002的 health 均HTTP200；公开线路地图113 rails、31 markers。SimpleSignals definition revision117、14 blocks；不把 attachedSignals=0 当作客户端渲染验收。
7. 加载后观察窗口内没有 JavaRailways ERROR，未出现世界tick停止或ATO再次stalled。保留1次 TruffleAttach 警告和1次 physics epoch overload 警告；最终服务仍正常运行，不隐去环境警告。

## 列车恢复与客户端验收边界

部署前 physics 活动控制器为5/5，部署后两次检查均为0/5。按照 `simpleato/persistence.md` 的既有恢复合同，active run 的 checkpoint 只恢复为 recovery-required/disabled，不自动重建 Basic 授权、锁、任务ticket或controller，因此加载后不能宣称已恢复行驶。控制台 `jr timetable status <id>` 返回 Player only，未借用玩家身份绕过该边界。

本次没有执行全局时刻表重载、列车复位、ATO/physics手工reset或发车操作。恢复发车与实际弯道流量、插值、屏幕画面和乘客表现仍需现场验收；服务健康不替代上述验收。

完整部署脚本、控制台片段及回执已归档至恢复目录，仓库JSON副本为同目录 `metadata-delta-deployment-20261113.json`，不含认证凭据或业务配置正文。
