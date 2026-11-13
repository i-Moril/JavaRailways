# 完整分支部署：Operating hold 与乘客移动

用户明确选择“部署全部”，部署 `feat/passenger-movement` 的完整主插件 JAR，包含 ATO operating hold 修复及此前乘客移动提交。沿用本会话不创建新任务的选择；此记录放入当前分支已有研究目录，未改任务状态，未推送。

## 源码与构建

- 源码提交：`64d449f444d0eac7a7b85caf20a6d2c741ce1e46`。实时查询远端 main 为 `5aa13ea26280b5e8292c8c29723bc2eb52f4822a`，已验证它是该提交的祖先；远端没有 passenger 分支。
- `./gradlew --offline :BukkitPlugin:shadowJar verifySignalModuleBoundaries --no-daemon --max-workers=2` 通过。
- 候选：`BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`，62,885,627 bytes，SHA256 `48ad69e991dd6319ef280767a9a97f0b19f5e11b6d3d95807b899f692a9b8189`。
- Core 599、Basic 494、SimpleATO 856、SimpleATOBukkit 2，共 1,951 项本地测试通过；聚焦 5 套件 115 项通过。完整检查另保留 SimpleSignals 7 项既有 fixture 失败，以及 BukkitPlugin 1 项既有 S100 ZIP/source 一致性失败、1 项跳过，未将全仓库测试称为通过。信号 API consumer 编译通过。
- 未修改 ATP 曲线、停车偏移、存档格式或服务配置。

## 部署与恢复点

- 目标：`user@192.168.1.230`，JavaRailwaysTest 实例 `836082a48c4f48fcb1fea4e83c57dc96`；使用既有 `/run/host-ssh-agent/agent.sock`。
- UTC 18:21:03 通过 MCSManager 正常停服，PID `1103524` 退出。日志确认全局世界、玩家数据及 RegionFile I/O 保存完成。
- 完整实例恢复目录：`/opt/mcsmanager/backups/javarailways/operating-hold-64d449f4-20261113-ktsmg0a1`。
- `instance.tar` 共 1,147,944,960 bytes，包含 3598 个文件及链接；保留 ACL、xattr 和数字属主。tar 与停止后的源目录逐项比对、SHA256 校验及备份前后完整清单比对均通过。目录0700，归档与凭据0600。
- 归档 SHA256：`b098b63e69f4eb6fcc380af09619f4d3392f62309a891df53fb8a9db20c4d38d`。旧 JAR SHA256：`9f6969570a0e0f01464cbe4b4feea790b5f0e299b2191a2a7963c2a438fa87da`；另存 `JavaRailways-before.jar`。
- 2026-11-13T18:21:44.089053+00:00 仅原子替换 `plugins/JavaRailways.jar`，保留 UID/GID/mode；其他 3597 个文件及链接在替换阶段完整清单一致。启动后再次确认其余13个插件JAR哈希未变。
- 通过 MCSManager 正常启动；新 PID `1107713`。必要时先正常停服，再使用暂存目录 `/tmp/jr-operating-hold-deploy-20261113-qut3nf6v/deploy_current.py rollback <当前PID>` 恢复旧 JAR。完整实例归档保留作进一步恢复依据；没有自动覆盖运行后的存档。

## 加载与健康检查

- UTC 18:22:49 出现 `Done (49.737s)`，截至 2026-11-13T18:24:54.071284+00:00 MCSManager 状态3，进程仍为新PID。
- `jr help` 实际返回正常；`jr ato status world` 和 `jr physics status world` 均返回 `RUNNING`；`jr issues world` 返回0个当前问题。
- SimpleSignals installed，definitionRevision117、topologyRevision1，5 regions、5 tables、14 blocks、8 fixed-block signals、4 protection signals、1 route indicator。
- 桥接 `127.0.0.1:8765/health` 为HTTP200、`{"ok":true}`；WebUI `127.0.0.1:29002/health` 为HTTP200、`{"status":"ok"}`。
- 启动观察窗口内 JavaRailways ERROR 为0，未见 physics stalled；旧版停服期间的 `TrainPacketBundleCoordinator` 空指针未在本次启动后再次出现。没有据此宣称该独立问题已修复。
- 启动有 Terra Unsafe 与 TruffleAttach 两条 ERROR 级环境日志，已从完整备份里的旧日志确认部署前也存在。保留1次启动 physics epoch overload（429.230341ms）及1次 delta clamp 记录；未扩大本次修改范围处理。
- 本次没有发车、移动列车、重载/重置时刻表、手工reset ATO/physics或客户端验收。服务健康不代表已恢复列车运行，也不替代原ATP问题的现场复现验收。

安装凭据和最终健康记录见同目录 JSON；完整备份、文件清单、部署脚本和本次启动日志保留在恢复目录中。仓库记录不包含认证凭据或配置正文。
