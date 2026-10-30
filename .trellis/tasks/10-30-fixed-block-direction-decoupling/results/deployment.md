# 提交与测试服部署记录

用户在代码交付后明确要求“提交并部署”。2026-10-30 已部署至 JavaRailwaysTest（192.168.1.230，实例 `836082a48c4f48fcb1fea4e83c57dc96`）。没有推送远端。

## 提交与已部署功能保留

- `188c24c6`：本次闭塞方向解耦实现、测试和规范。
- `220e705e`：合入开发服实际源 `4c19f7f4`，保留原分支尚未包含的程序编辑、路径点剪枝、列车放置和函数仿真功能。两个测试冲突适配了现有任务计时与明确方向／路径点规则，生产代码自动合并后复核。
- 当前 `dev/signal-system` 已快进至该集成版本。相对于实际部署基线 `4c19f7f4`，Basic、Core、WebUI 和 Bukkit 生产目录没有差异；本次生产改动仍限 SimpleATO／SimpleSignals。

隔离构建工作树为 `.worktree/fixed-block-direction-deploy`。1309 项源文件和构建输入的 SHA256 在门禁期间固定并于结束后逐项复验，无差异，见 [deployment-source-contract.json](deployment-source-contract.json)。部署清单绑定最终提交、旧／新工件、source／candidate sidecar 及安装脚本 hash，见 [deployment-manifest.json](deployment-manifest.json)。

## 最终验证

最终完整门禁 **BUILD SUCCESSFUL in 1m54s**，59 项任务中3项执行、56项沿用同一集成树前次通过且输入未变的结果。最终 JUnit 报告 **3001项，0失败、0错误、0跳过**：

- Core 538；Basic 435。
- SimpleATO 657；SimpleATOBukkit 2。
- SimpleSignals 657；BukkitPlugin 663；WebUI后端49。
- 独立实际前端构建成功；前端53个测试文件、323项全部通过。
- Java consumer、SimpleSignals插件JAR和根模块边界检查通过。
- 一次性转换工具13项测试、实际Kotlin codec正反向校验通过；停服安装操作脚本11项本地真实文件操作正反向自测通过，见 [migration-review.md](migration-review.md)。

最终命令在隔离工作树运行，使用共享缓存的JDK25／Gradle9.0.0，离线依赖，保留现有模块toolchain：

```bash
JAVA_HOME=/workspace/minecraft/JavaRailways/.gradle-user-home/jdks/eclipse_adoptium-25-amd64-linux.2 \
GRADLE_USER_HOME=/workspace/minecraft/JavaRailways/.gradle-user-home \
./gradlew :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:check \
  :BukkitPlugin:test :WebUI:test :BukkitPlugin:shadowJar \
  :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries \
  --init-script /workspace/minecraft/JavaRailways/.trellis/tasks/10-30-fixed-block-direction-decoupling/results/deployment-test-process.gradle \
  --continue --offline --no-build-cache --no-parallel --max-workers=2 \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m' \
  -x :WebUI:installFrontend --console=plain
```

归档后 init-script 位于本文同目录的 [deployment-test-process.gradle](deployment-test-process.gradle)，重跑时使用其当前绝对路径。原始完整日志见 [deployment-integrated-final-validation.log](deployment-integrated-final-validation.log)，计数见 [deployment-final-test-results.json](deployment-final-test-results.json)。

### 测试进程内存对照

最初1GiB、按类fork的完整ATO测试在34项站点调度类的最后一例发生worker OOM。单例同样1GiB通过，整类同样1GiB复现。该测试文件相对服务器源未修改；将实际已安装旧JavaRailways JAR置于测试classpath之前、保留相同测试和其余依赖后，整类同样在1GiB复现OOM。该对照使用旧运行时工件，并非旧分支全套重新构建。

最终同类34项和全部657项ATO测试在2GiB、按类隔离的测试worker中通过，未改产品配置或删减用例。测试进程配置单独保存在上述init-script，不加入构建配置。具体内存保留来源尚未定位，不声称修复了产品内存问题。对照日志／XML及单例、整类结果保留在本目录 `deployment-ato-*` 和 `deployment-installed-runtime-*` 中。

## 恢复点与精确转换

11:56:21 UTC 正常 MCSManager stop 保存全部世界及玩家数据；status0、PID0，旧PID753535已退出，没有强制终止。

完整外部恢复目录：

```text
/opt/mcsmanager/backups/javarailways/function-simulation-20261030-36g_n9f3
```

复用现有服务端部署工具，因此目录仍使用原工具的prefix；这是本轮新建的恢复点。`plugins-and-data.tar.gz` SHA256为 `3a5c2b49d87b5ecf82d90a390c85c2c87abf054baa5ab9e53afe8067eec77bae`，142项数据文件清单；包含三个相关插件的数据目录与旧JAR，保存数值owner／ACL／xattrs，归档及清单root-only。完整归档下载后再次校验hash，从中精确提取本次两文件，不把在线副本当作恢复点。

从归档副本用最终集成JAR运行dry-run、实际current Kotlin codec、独立候选apply和hash比对：只删除primary13个、backup12个 `fixedBlocks[*].bidirectional` Boolean成员，共25个；其他字节保持不变。primary revision63／13个闭塞、backup revision62／12个闭塞；各8台闭塞信号。schema1、ID、轨道遍历顺序、入口、placement与其他字段未改。

实际安装只替换 `JavaRailways.jar`、`SimpleSignals.jar` 及上述primary／backup；owner和mode保留。安装前后全量inventory证明其他插件JAR和其他142项中的非目标数据不变。前置JAR工具和独立审查的sidecar安装脚本均检查停止状态、恢复归档、源hash及候选hash，失败不应启动。证据见 [deployment-recovery.json](deployment-recovery.json)、[migration-recovery-dry-run.json](migration-recovery-dry-run.json)、[migration-recovery-apply.json](migration-recovery-apply.json) 和两个 `deployment-*-install-audit.json`。

最终工件：

- JavaRailways.jar：`efd212fbe1b4b68c871f24010c523692a3ab19f3f2988f08e78ff367988dd0b3`，67055558 bytes。
- SimpleSignals.jar：`4951ea121c979b42213b108de2d9d1df4d810ffa43e2d6f78a234ebe1903ef99`，1924707 bytes。
- 原TunnelBridgeBuilder.jar保持 `831a0c25179c9c98f2a7135256080c9f402713b18215781976ab2eb5fce3d722`。

## 实际运行核验

正常 MCSManager open 后，12:00:04 UTC Done（28.552s）；最终status3，新PID758259。

- `jr ato status world`：`ATO world: RUNNING`。
- `simplesignals inspect world`：installed，definitionRevision63、topologyRevision1，regions6、tables6、blocks13、fixedBlockSignals8、protectionSignals2、routeIndicators0、attachedSignals0。
- 两个已安装JAR、两个sidecar与候选hash完全一致，其他插件JAR仍一致。
- Bridge `/health` HTTP200、`ok:true`；WebUI首页及引用JS/CSS在服务器本地和工作区LAN均HTTP200，hash逐项匹配构建产物。
- 抽取的WebUI sidecar SHA256 `f4cd3ba53c9003ccfc867ec5508e8b7720019b270779f0050cdba11e7696ae81`，与主JAR中嵌入产物一致；已检查index及其引用资源齐全，并检查原有函数仿真／唯一列车放置类保留。
- SimpleSignals errors.log没有增长。JavaRailways日志新增1109 bytes，全部为4条WARNING：既有非loopback／明文HTTP提示及启动时352ms physics overload／delta clamp；没有新增插件ERROR。服务器仍有既存Terra `sun.misc.Unsafe` ERROR，未把该错误写成新插件故障或宣称全服零错误。

机器可读证据见 [deployment-live-verification.json](deployment-live-verification.json)、[deployment-manager-status.json](deployment-manager-status.json)、[deployment-lan-verification.json](deployment-lan-verification.json)、[deployment-package.json](deployment-package.json)。

本轮确认了实际部署、存档恢复、ATO／信号运行状态和HTTP资源交付。未进行游戏客户端驾驶、视觉或新增折返场景的现场操作验收；混合方向完整折返的行为证据来自真实Core/Basic/Dispatcher离线50ms测试。

## 回退与保留

回退前先正常停服并为回退时的最新数据另建恢复点，核对上述归档hash后恢复匹配的旧JAR与旧sidecar；不能向新插件只恢复旧字段，也不能把停服恢复档覆盖到运行中的服务器。若用户后来编辑过世界，应先保存并审阅差异，不能直接丢弃。

原五项无关未跟踪文件保留，本轮没有推送或扩大到其他任务归档。迁移工具不参与插件启动，不新增运行时兼容路径或schema代际。
