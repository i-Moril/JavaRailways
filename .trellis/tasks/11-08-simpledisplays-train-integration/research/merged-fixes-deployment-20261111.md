# 合并修复及独立依赖部署（2026-11-11UTC）

用户授权提交并部署，并在测试服安装最新版PacketEvents/PlugManX。源码基线：`ad65d6b711db0ff776213b300d6d3242d8f827b0`；源码已提交且工作区原先干净。

## 官方资产来源

- PacketEvents2.14.0：官方GitHub最新稳定release v2.14.0，发布2026-09-23；选择Spigot版。https://github.com/retrooper/packetevents/releases/tag/v2.14.0；官方GitHubasset SHA256校验一致。
- PlugManX3.2.1：官方GitHub最新稳定release v3.2.1，发布2026-09-20；该release无GitHubassets，使用其直接链接的Modrinth项目，版本j22EUw2Y的Bukkit/Folia版。https://github.com/Test-Account666/PlugManX/releases/tag/v3.2.1；Modrinth官方SHA1/SHA512逐项一致。
- 安装默认config.yml中的reloadDependentsMode已经是REQUIRED_ONLY；未忽略JavaRailways及其附属插件。自动加载/卸载/重载保持默认关闭。

## 安装清单与恢复点

- `plugins/JavaRailways.jar` SHA256 `a3b2ac13221fcd1e685e4d6319a3ef6641ee248492cf2ed814e616cfdb46b520`
- `plugins/TunnelBridgeBuilder.jar` SHA256 `b6c56122bf4862fe693c3dfc8a7c3688965553903620bbc4b5d6f088646b5fbd`
- `plugins/packetevents-spigot-2.14.0.jar` SHA256 `060087c58ec268eae7dd0eb1f17ac3bea1eaadda1693414141ff64e506091bd7`
- `plugins/PlugManX-3.2.1.jar` SHA256 `660a48c5975ab1eae67a9273e8eb0fdb607f34d7644730e51041d3f8bdfedcf2`
- `plugins/PlugManX/config.yml` SHA256 `16b7db30aaa05a69625e69bf813e8c2734df88b73eb3b60861420e1a61b3727d`

保留其他8个既有插件（含Decorations/SimpleDisplays/SimpleSignals），车包、资源包、物理与世界存档未被安装程序替换。TBB同时更新以包含新的服务租约和代次清理。构建产物重新核对通过：/tmp/jr-merged-deploy-20261111/artifacts-check.log。主JAR不包含PacketEventsclass，运行时使用外部服务；编译最低基线仍为2.12.1，不通过版本下限推测新版ABI，另检查实际启动。

正常manager停服，旧PID1042003退出后建立187文件恢复点 `/opt/mcsmanager/backups/javarailways/displays-20261111-ymwyfytm`，recovery.zip SHA256 `f07e968ecb44718160a762c431b9b1b539520a14d21593f8ebd4a742721d0066`。精确清单校验后原子写入5项文件；安装程序验证只有这些文件发生变化。工具/回执/清单在测试服 `/tmp/jr-merged-deploy-20261111`。新插件数据目录已纳入后续备份范围。

启动后验证记录待追加；不以本次正常启动替代PlugManX在线玩家热重载/真实客户端显示矩阵。


## 首次启动失败与修正

08:49:54首次主插件启用失败：BukkitRailTargetService构造时捕获worldManager，但composition在WorldManager创建前构造该service，触发lateinit未初始化。附属插件随后ClassNotFound是主插件已禁用的后果，不能当作独立依赖缺失。PacketEvents2.14.0与PlugManX3.2.1均已启用。

正常manager停服后，最小修正仅将railTargetService创建移动至WorldManager之后、服务发布之前；保留本代对象捕获，不恢复动态全局lookup。静态复查确认没有更早消费者，已有RailTargetService定向回归与shadowJar/模块边界通过（startup-fix-check.log，18s）。代码提交0d47741fe25f060fb2761d2400fad473c408930f。

重试仅换主JAR，SHA256840ad44b280d6e47c85f44497189600b9204353179ccdbbe555ba9ca6d7337ba，其他11个插件JAR保留。197文件二次备份 `/opt/mcsmanager/backups/javarailways/displays-20261111-v6rr4t59` SHA25607fa467985dd051919bfc546ca89528d8164a4077c06e61b06c13911fa8a2eb6；第一次部署前的完整恢复点仍保留。重试清单/回执位于原stage的retry/。启动后验证待追加。


## 最终启动与健康验证

08:53:43 PacketEvents2.14.0和修正后的JavaRailways正常启用；08:53:48Decorations确认SimpleDisplays服务可用，SimpleSignals与TBB随后正常启用。PlugManX3.2.1正常启用且只读 `plugman list -v` 正确列出12个插件。08:53:51 Done30.304s；08:54:39资源重载成功（1列车包/3轨道包）；08:54:41 ATOworldRUNNING。Bridge8765 `/health`与WebUI29002 `/`本机HTTP200。最终PID1044129、MCS状态3。12个插件JAR及PlugManX配置哈希全部匹配计划，reloadDependentsMode为REQUIRED_ONLY。

保留的已知启动日志：TerraUnsafe字段错误、TruffleAttach warning、PlugManX自身Folia/Paper实验性提示；重载后没有新增ERROR/Exception。PacketEvents2.14.0已通过本次真实启用与资源重载，但尚未以在线客户端覆盖所有封包/显示路径。未执行 `/plugman reload JavaRailways`，不宣称完整热重载矩阵通过；当前为正常重启后的运行状态。没有推送Git、手改世界存档或改资源包。
