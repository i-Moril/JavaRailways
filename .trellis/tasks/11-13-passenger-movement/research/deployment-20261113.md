# 乘客移动验证：PlugManX 测试服部署

用户明确要求使用 PlugManX 部署并使用 `/run` 下的 SSH agent。当前分支 `feat/passenger-movement`，部署代码提交 `82130c6d02df7675a3d5de14af8e181f715efd38`。通过 `/run/host-ssh-agent/agent.sock` 执行 `git ls-remote`，远端 `main` 为 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，与功能分支起点一致；未推送。

## 范围与恢复点

- 目标：`user@192.168.1.230` 的 JavaRailwaysTest，实例 `836082a48c4f48fcb1fea4e83c57dc96`。
- JVM PID 在整个部署前后均为 `1081210`，没有重启 JVM。
- 只原子替换 `plugins/JavaRailways.jar`，保留原属主与权限；其他 11 个插件 JAR 和 PlugManX 配置逐字节哈希未变。
- 旧 JAR SHA256：`16d735cd55e453169db30233cc4569a09d4497bde5a25a9d447394c3bf56b001`。
- 新 JAR SHA256：`8c666c4a1dc826ded68df53b147aff7a1621073ae0437fa8f38197a738af8d3d`，与已测试本地构建相同。与旧 JAR 比较，50 个条目改变、26 个新增、无删除，限于 Bukkit 实现及 Kotlin 元数据。
- 恢复目录：`/opt/mcsmanager/backups/javarailways/passenger-movement-82130c6d-20261113-zok7tfyo`。
- `recovery.zip` 包含所有插件 JAR、JavaRailways/SimpleSignals/TunnelBridgeBuilder/Decorations/SimpleDisplays 数据目录及 PlugManX 配置，共 193 个文件；不包含 Minecraft 世界区块或玩家存档，不能当作整服恢复点。
- 归档 SHA256：`79a5717df11b630615fe8eec7dc3a72437e279148dfe3427f6d7cc39cf898d0f`；清单 SHA256：`9a3002f73951ba68048829ecc9b79baae8f3a8982ae3d98b8f0bba335a43ac7c`。逐条校验 ZIP 内容与前后两次清单一致，之后才请求热重载。归档目录 0700，归档/清单/操作证据 0600。

普通账号备份时无法读取已有快照，随后实际检查确认 Java 进程以 root 运行：`model-snapshots` 的 18 个文件、`train-package-snapshots` 的 5 个文件均为 root:root/0600，JAR 为 root:root/0644。使用该账号已有的免密 sudo 完成备份、保留属主的安装和证据记录，没有 chmod/chown 现有插件数据。MCS 认证仅通过已有官方配置在控制脚本进程内使用，未输出或提交凭据。

## 实际热重载与依赖故障

PlugManX 为 3.2.1，原配置 `reloadDependentsMode: REQUIRED_ONLY` 未变。

1. 10:30:41 UTC，第一次 `plugman reload JavaRailways`。PlugManX 先卸载 Decorations、TunnelBridgeBuilder、SimpleSignals，才请求主插件 GentleUnload。SimpleSignals 存在活动基础设施依赖，旧运行时因此报告 ATO stall。
2. 主插件拒绝立即卸载并开始准备。PlugManX 尝试恢复 SimpleSignals，因旧 provider 仍被引用而启用失败，另外两个依赖没有自动恢复。错误发生在 JAR 替换之前，完整日志留存 `prepare-console.log`。
3. 10:30:43，出现 `JavaRailways unload preparation completed`，确认 READY 后才原子安装新 JAR。
4. 第二次同一命令完成，10:31:33 出现 `JavaRailways has been reloaded`。未把准备就绪或命令队列回执当成加载成功。
5. 用 PlugManX 执行 `reload SimpleSignals`、`load TunnelBridgeBuilder`、`load Decorations`，三者均实际加载启用成功。没有替换其 JAR。最终通过 `plugman info` 分别确认主插件及这三个插件为 Enabled。

这是已有级联卸载顺序与活动 SimpleSignals 依赖的实服边界，本轮没有扩大乘客功能代码去修复它。不能将整个部署过程写成“无错误”。未执行 ATO reset、时刻表 reset/reload-all、重规划、资源重载或运行数据手工修改。

## 最终检查与边界

- `jr help` 实际返回命令帮助；`jr ato status world` 与 `jr physics status world` 均为 RUNNING。
- `jr issues world` 为 0 个当前问题。
- `simplesignals inspect world`：definitionRevision=117，5 regions、5 tables、14 blocks、8 fixed-block signals、4 protection signals、1 route indicator。attachedSignals=0 仅作为实际观测记录，不据此证明客户端信号实体显示正常。
- 8765 `/health` 为 HTTP200 `{ok:true}`；29002 `/health` 为 HTTP200 `{status:"ok"}`。
- 公开线路地图 `/api/v1/worlds/world/rail-map` 为 HTTP200，113 rails、31 markers。首次误用 UUID 请求得到明确的 `world not found`/400；改用 worlds API 返回的 id `world` 后成功，没有修改服务。
- 保存的部署前后 checkpoint 均为 5 列车、1 时刻表、5 runs；前者状态为 1 DWELLING/3 APPROACHING/1 RUNNING，后者为 1 DWELLING/3 APPROACHING/1 DEPARTING，没有 FAILED。这里只核对持久化 checkpoint，不替代实时调度或实际行车验收。
- 第二次主插件重载后保留既有明文 HTTP、Folia 单 tick worker 提示；恢复 SimpleSignals 时另有 TruffleAttach 优化运行时不可用警告。最终没有新增启用失败、世界恢复失败或当前世界问题。

完整操作脚本、控制台片段和 JSON 回执保存于恢复目录；仓库副本为 `deployment-20261113.json`，不包含插件配置或数据正文。部署和服务检查完成，真实客户端挂载高度、WASD、Shift/跳跃限制、随车插值及跨平台交接仍未验收，任务保持 in_progress。
