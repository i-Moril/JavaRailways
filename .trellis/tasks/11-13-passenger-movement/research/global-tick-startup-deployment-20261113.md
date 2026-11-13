# Global tick 启动门控修复与热重载部署

用户要求先在 `main` 修复，仅让 `feat/passenger-movement` rebase，然后继续热重载部署测试服。不创建新的 Trellis 任务，未推送。

## 分支与实现

- 实时查询远端 `main` 为 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，与本地基线一致；远端没有 passenger 分支。
- `main` 修复提交：`9e398aeb9edf05c70564b6b82f85b9da70930f14`。
- passenger 原 HEAD `c382022e` 的 6 个提交无冲突重放到新 main，源码构建 HEAD 为 `985d6640bcfdf38a0ecd1bf5ca11d248b96a2154`。`git range-diff` 显示六项均为 `=`，乘客功能内容未改动。其他分支未修改。
- `onEnable` 完成 composition 后直接排入现有 global scheduler；首个实际 callback 启动物理，后续 callback 推进 tick。移除 native `isReady` 与等待 `ServerLoadEvent.STARTUP` 的分支；保留单次初始化、迟加载 World、stop 取消及迟到 callback 不复活的保护。
- 测试服 Folia 26.1.2 build 8 的提交为 `62dc0f2`。实际字节码中 private `isReady` 只有 getter 读取，没有写入。官方区域线程补丁在原版主循环之前转交 RegionizedServer 后无限休眠，导致原循环的赋值不执行。Paper global scheduler 由正常 tick 驱动，因此无需新增平台分支。

上游源码：[Folia 区域线程补丁](https://github.com/PaperMC/Folia/blob/62dc0f257a4f5de1ef2eae8cf1627156a769c67f/folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch#L8307-L8325)、[Paper global scheduler 驱动](https://github.com/PaperMC/Paper/blob/b4682bfef616ac62e73cc96046dacdf4a6f53eeb/paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch#L1100-L1105)。

## 自动化验证

- main 执行 `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test :BukkitPlugin:shadowJar --continue`。打包成功；完整测试 935 项，933 通过、1 失败、1 跳过、0 errors。启动门控 5 项全部通过。
- 唯一失败为既有 `CarAnimationPlaybackTest.kt:85` 的 S100 包与解包源码全文一致性断言。已核验包、行为源码、测试文件与基线逐字节一致，未将完整测试报告为绿色。
- rebase 后执行 `./gradlew --offline --init-script /tmp/jr-passenger-test-heap.gradle :BukkitPlugin:test --tests '*ServerPhysicsStartupTest' --tests '*Passenger*' --tests '*Seat*' :BukkitPlugin:shadowJar`，37 项全部通过，无跳过。
- `git diff --check` 通过。测试堆设置复用既有 `/tmp` init script，未改变仓库构建配置。
- 主 JAR：`BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`，SHA256 `c0725cc57e23cc95f356196806abec99a284f0e48cdbf9b3bf6ab4dbc93179bd`。
- 与实服旧 JAR 比较：20 个 class 条目变化，无新增/删除，均来自 BukkitPlugin 与 WorldManager.kt 中声明的类。相同源码文件中的其他生成类因行号变化而改变；GraalJS、乘客代码和其他模块没有产物差异。

日志：`/tmp/jr-main-global-tick-startup.log`、`/tmp/jr-rebased-global-tick-startup.log`。

## 部署与恢复点

- 目标：`user@192.168.1.230` 的 JavaRailwaysTest，实例 `836082a48c4f48fcb1fea4e83c57dc96`；使用 `/run/host-ssh-agent/agent.sock`。
- JVM PID 始终为 `1081210`，没有重启 JVM。仅原子替换 `plugins/JavaRailways.jar`，保留原属主及权限；其他 12 个插件 JAR 与 PlugManX 配置哈希不变。
- 原 JAR SHA256：`8c666c4a1dc826ded68df53b147aff7a1621073ae0437fa8f38197a738af8d3d`。
- 恢复目录：`/opt/mcsmanager/backups/javarailways/global-tick-startup-985d6640-20261113-d01j0p4n`。
- 备份 195 个文件，包括全部插件 JAR、JavaRailways/SimpleSignals/TunnelBridgeBuilder/Decorations/SimpleDisplays 数据与 PlugManX 配置；不含 Minecraft 世界区块或玩家存档，不作为整服恢复点。
- ZIP 每个条目与备份前/后清单逐字节校验通过。归档 SHA256 `90003e760f69452c4243ef729e416dd5345e087fca2ee7714dff0a1e0e76205e`；清单 SHA256 `65206426b5f7a443005cf0731e4300d7b46403582d0f7984cb554fb881e8f2a3`。目录 0700、归档和证据 0600。

操作顺序（UTC）：

1. 11:19:46，通过 PlugManX 先卸载 Decorations、TunnelBridgeBuilder、SimpleSignals，实际成功。
2. 11:20:01，第一次 `plugman unload JavaRailways` 暂缓卸载；11:20:02 确认 `JavaRailways unload preparation completed`。
3. 11:20:18 原子安装新 JAR；11:20:20 第二次同一 unload 命令完成正式卸载。
4. 将主插件、SimpleSignals、TunnelBridgeBuilder、Decorations 的 load 命令按依赖顺序一起排入控制台队列，全部在 11:20:43 前启用；11:20:44 才出现 `Physics ticks started after server startup`。已核验附属插件组装先于首个 physics callback。

没有改变 JVM 参数、插件配置或其他插件产物，没有执行 ATO/physics 手工 reset。

## 实服验收与边界

- 部署前虽显示 ATO RUNNING，`jr perf world` 没有 physics 样本。部署后 physics epoch 实测从 206 增至 229。
- `jr help` 返回实际帮助，主插件及三个附属插件均为 Enabled；ATO/physics 为 RUNNING，`jr issues world` 为 0。
- 桥接/WebUI 的 8765/29002 health 均 HTTP 200；公开线路地图仍为 113 rails、31 markers。SimpleSignals definitions revision 117；attachedSignals=0 不作为客户端信号渲染验收。
- 11:21:08 执行原先卡住的 `jr timetable reload-all confirm`，11:21:10 实际完成。诊断记录为 `GLOBAL_RELOAD_SUCCEEDED`，scripts=1、worlds=3、discardedRuns=5、placements=5、epoch=101202382；请求至成功约 2.30 秒。该命令按既有行为复位了列车。
- 11:22:03 最终核对：physics epoch 1531 → 1553，ATO/physics 仍 RUNNING，0 个当前问题，两个 health 仍 HTTP 200；未出现世界 tick 停止、全局 simulation halt 或插件 enable 失败。
- TruffleAttach 警告仍出现 2 次，JS 编译与全局时刻表重载均完成；未隐藏此警告或声称其根因已经修复。日志也记录 JVMCI 未启用的解释执行提示。
- 重载复位期间记录一次 `onReverse failed: car behavior reset`，本轮观察窗口内共 3 条 physics epoch overload 警告。均保留原始证据；最终未进入 STALLED，不扩大本修复去修改车厢行为或物理阈值。
- 本轮实际运行验收为 Folia 热重载。Paper 与冷启动的调度顺序经上游源码核对，未另启独立 Paper/冷启动服务器；客户端乘客移动与视觉效果未验收。

完整脚本、控制台片段、回执已归档到恢复目录。仓库 JSON 副本为 `global-tick-startup-deployment-20261113.json`，不含认证信息、插件配置正文或业务脚本正文。
