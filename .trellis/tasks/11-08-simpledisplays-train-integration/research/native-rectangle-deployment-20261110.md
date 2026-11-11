# 原版字符矩形修正部署 — 2026-11-10 UTC

用户明确授权更改、提交及部署。SimpleDisplays 提交 `fe2898d45d5ee7673860f74d7c0a7b154e264d78`；JavaRailways/Decorations 提交 `c1508bc919f9f371b378e9ea29d1c969fbcbdcb6`。无Git推送。

部署目标仍为 JavaRailwaysTest，`192.168.1.230`，MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96`，Folia26.1.2。此次保留主 JavaRailways JAR及其他7个插件；只更换SimpleDisplays、Decorations、3个S100模板/说明文件、2个外置样式文件、config.zip和resourcepack.zip，共9项。线上car源文件和config.zip内全部12个car定义/副本均逐字节保留，无17.2/0.4→本地17.8/0.1的覆盖。

源码/测试源码编译与JAR边界检查通过；真实SDL/Graal对3模板×2语言的绘制诊断通过，非JUnit统一测试。上线前两仓库最终静态审查及源/ZIP一致性检查通过。矩形采用原版U+2588与独立X/Y缩放和墨迹中心补偿；移除旧矩形字形，保留校准后的圈点，恢复42/58分栏与文字层级。英文整段旋转仍未实现，不声称完全复刻DEMO。

## 恢复与安装

- 停服前在线1人。正常stop于18:21:22完成全部世界/玩家保存，旧PID1028217退出，manager状态/PID归0；未强杀。
- 恢复点：`/opt/mcsmanager/backups/javarailways/displays-20261110-zhgk86t0`。
- recovery.zip包含186个插件JAR/数据文件（包括铁路存档，非Minecraft region备份）；SHA256 `b9b7adfe661a2ce8260a9d6463bb535a8e409f55ed59591fa62c7f58bacec087`。成员、逐文件hash及备份期间源稳定性均验证。
- 9项原子安装后完整inventory与预期delta一致。保留8个插件JAR，源候选与安装hash一致；无世界存档手改、列车重建或时刻表重置。
- 完整部署审计与候选目录（本地及远端）：`/tmp/jr-native-rect-deploy-20261110`。包含old/new/retained哈希、安装和恢复receipt、post-deployment-audit.json。

## 上线结果

18:23:04启动完成（28.481s），SimpleDisplays/Decorations启用成功，新API服务可用且真实模板编译通过。Bridge8765和WebUI29002本机HTTP200，18:23:58 ATO world RUNNING，新PID1030660。18:23:30公开事务式 `jr resources reload` 成功：1个列车包、3个轨道包；审计时重载后无新增ERROR/Exception/失败记录。

最终复核9个部署文件、8个保留插件、线上car源及归档内12个car定义通过。服务端资源包以原实际包为基础，只修改字体/显示相关素材并删除6类废弃矩形PNG，其他custom资源与pack元数据保留。新包SHA256 `c6a74332e408def67947bc4d9d73b9fb24444aeb4641a8962876aa169ef9c462`，本地副本 `/tmp/jr-native-rect-deploy-20261110/resourcepack-after.zip`。server.properties的空resource-pack设置不变，未另建自动分发服务。

现有Terra Unsafe警告/启动physics clamp不属于本轮修复。此次健康检查和真实SDL输出不能替代客户端GPU画面、物理朝向、文字可读性验收；统一测试和完整Paper/Folia双后端矩阵仍待后续。8:3侧显本轮仍只更新模板，未新增挂载。

回滚时正常停服后，按此恢复点restore匹配的旧2个JAR和模板/config/资源，保留后续世界存档；不要让旧SDL与要求scaleX/Y的新版Decorations混装。恢复前校验receipt和archive hash。
