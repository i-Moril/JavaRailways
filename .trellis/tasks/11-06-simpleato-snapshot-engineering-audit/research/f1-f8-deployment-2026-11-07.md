# F1–F8 测试服部署

2026-11-07 UTC；用户在确认 Tick 14219 修复未进入上一部署后明确要求部署。工作提交为 `31e4cf1791a676f263e1afb630aa40be51fc8471`，未推送 Git 远程。`TODO.md`、旧 deployment research、`ai_tasks` 草稿和 `tmp.1` 均未进入提交或部署包。

## 构建与候选

- 从提交 `31e4cf17` 的干净副本构建，不从脏工作树打包。
- `:BukkitPlugin:shadowJar`、Java signal API consumer、SimpleSignals plugin JAR 和 `verifySignalModuleBoundaries` 通过；此前同一提交边界的最终复核见 `f1-f8-final-check.md`。
- 候选 `JavaRailways.jar` SHA256：`fad98c461994b14e12c9336983f12d3608e3048adb511715d39932f556bcf6ef`，大小 `67491237` bytes。
- 内嵌 WebUI JAR 保持 `d837a58b2d84f8aa4ba935bb6256eef7bd9f06d1487f9afe530c89273199d722`；主脚本保持 `0205481b4c5a756a1a79f66bfa27e65f4226587be36e77fb974a775922fa97a9`。

## 停服、恢复点与替换

- 目标：JavaRailwaysTest，MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96`，使用既有宿主 SSH agent；无凭据复制或配置修改。
- 部署前已安装主 JAR SHA256 为 `e5b8433cd1c21f11d23d24286465586eccbd7eb121114a6ae4a042fc647f73b4`，对应上一 `c914b1a3` 部署。当前编辑器文档 SHA256 为 `1c6375bae2c3d590a7d130136c7cf060d406ab7b19b695d7242627885b219b21`。
- 在线玩家 `__ART1st__` 已收到重启通知。MCSManager 正常 stop；日志确认 `Saved all worlds`，旧 PID `960260` 退出，status/PID 均归零。
- 首次以普通用户创建恢复包时，读取既有 root-only `model-snapshots` 被拒绝，工具在备份和替换前停止，旧 JAR 未改变。随后使用服务器既有免交互 sudo 完整读取同一恢复边界，未跳过受保护文件。
- 恢复目录：`/opt/mcsmanager/backups/javarailways/f1-f8-20261107-s2m00jxu`；`recovery.zip` 包含 150 个文件，SHA256 `c74a6c2341e949dba439f06eb23b2228f5d0a4f73e2a780cb92903e228e6c8d9`。
- 仅原子替换 `plugins/JavaRailways.jar`；其余 149 个清单文件保持不变。SimpleSignals JAR 仍为 `36657ce6a312e4498aba656060e831b8a0f4beaa6677ecab4cc3d6d715d2f29c`，TunnelBridgeBuilder 仍为 `831a0c25179c9c98f2a7135256080c9f402713b18215781976ab2eb5fce3d722`，编辑器文档哈希保持不变。

## 启动与健康检查

- MCSManager 正常 open；新 PID `961920`，服务器 `Done (27.432s)`。
- `jr ato status world`：`RUNNING`。
- SimpleSignals：installed，`definitionRevision=117`，regions 5、tables 5、blocks 14、fixedBlockSignals 8、protectionSignals 4、routeIndicators 1。
- bridge `127.0.0.1:8765/health` 返回 `{"ok":true}`；本地及公开 WebUI health 返回 `{"status":"ok"}`。
- 已安装主 JAR、当前编辑器文档和 WebUI 主脚本在启动后重新读取，哈希均与部署清单一致。
- 启动日志中的 `com.dfsek.seismic` Unsafe 反射错误为此前已记录的第三方既有错误；本轮未发现新的 JavaRailways/SimpleSignals 启动错误。

本记录证明精确提交已安装、服务启动及基础领域/HTTP 健康，不代替用户重新运行全局模拟。Tick 14219 是否在测试服真实输入中消失仍需新的 WebUI 全局模拟验收。

## SimpleSignals ABI 修复部署

用户首次重新运行全局模拟后，`10:47:23` 的第一次请求触发
`NoSuchMethodError: PerceptionSnapshot.getTrain(): TrainSnapshot`。主 JAR 已删除旧 `TrainSnapshot`，但上述部署错误地
保留了按旧返回类型编译的 `SimpleSignals.jar`；物理 worker 因此 halted。`10:50:17` 的第二次请求（WebUI trace
`46e26ef2-04fc-48d2-9d20-1783fd1c697a`）重新抛出锁存的 `PhysicsStalledException`，不是第二个独立业务故障。

用户批准前向修复后，从同一提交 `31e4cf17` 的干净构建部署匹配的 `SimpleSignals.jar`：

- 旧信号 JAR：`36657ce6a312e4498aba656060e831b8a0f4beaa6677ecab4cc3d6d715d2f29c`；新信号 JAR：`e9da012b4656cbdd60cf76453a4024fd78411fc96abad54f03d7479668f0a9ee`。
- 字节码检查确认新 JAR 调用 `PerceptionSnapshot.getTrain(): TrainFrameSnapshot`。
- 在线玩家已收到通知；MCSManager 正常 stop，日志确认 `Saved all worlds`。因物理协调器已 halted，关闭时另报一次 world unregister 清理失败；没有跳过世界保存。
- 新恢复目录：`/opt/mcsmanager/backups/javarailways/signal-abi-20261107-q6pg7712`；150 文件恢复包 SHA256 `69f538d328047b12bf2f0846586770919b32c0c566c27a4bc8114001ea07fd49`。
- 只原子替换 `plugins/SimpleSignals.jar`；主 JAR、TunnelBridgeBuilder 和编辑器文档哈希保持不变。
- 新 PID `963419`，`Done (26.454s)`；ATO `RUNNING`，SimpleSignals installed / definition revision 117，bridge 与内外 WebUI health 正常。新启动日志未再出现 `NoSuchMethodError`。

这次修复恢复了匹配的跨插件 ABI，并清除了重启前锁存的 physics halt；仍需用户再次执行全局模拟来验证完整运行结果。
