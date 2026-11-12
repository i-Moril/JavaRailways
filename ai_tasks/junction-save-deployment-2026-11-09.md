# 测试服接点存档修复与部署

2026-11-09 UTC。用户在提交实现后要求“现在处理服务器存档的问题”，授权实际修复；沿用不创建 Trellis task 的选择。

## 来源与目标

- 测试服：`user@192.168.1.230`，JavaRailwaysTest，MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96`。
- 实例目录：`/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`。
- 线上原主 JAR SHA256：`a4806284f6270b7dda56c914f51f457574797ebd85af80228f11aa1156f1d675`，匹配既有 `29e828e3` 部署清单。维护前 ATO 因 `s100-2` 脱轨处于 `STALLED`。
- 候选来自提交 `e580908827b6181c83c14fbcd1874013027b05ea`，tree `1cc557b5b40729bd0380b7b0df525b20489fb816`。构建前逐一核对干净副本中 3190 个 tracked 文件与提交 blob 相同，未打包并行显示模块等未提交改动。
- 新主 JAR：67,590,503 bytes，SHA256 `4051ce76e493b0926ca7766168c9cc9269b242202768ee706d753321c888857e`。
- 完整前端构建、shadowJar、Java signal API consumer 和 `verifySignalModuleBoundaries` 通过。内嵌 WebUI SHA256 仍为 `888fbb0a744d34a91f48699fb5a8716a4476e4b3d32502f46b839833315f2397`，与线上原产物完全一致。
- SimpleSignals、TunnelBridgeBuilder 源码相对部署基线没有变更，保留原 JAR：`e9da012b4656cbdd60cf76453a4024fd78411fc96abad54f03d7479668f0a9ee`、`831a0c25179c9c98f2a7135256080c9f402713b18215781976ab2eb5fce3d722`。

## 停服与恢复点

维护前在线玩家 0。MCSManager 正常 stop；19:02:46 完成全部世界和玩家保存，旧实例进程退出，manager status/PID 均为 0，Bridge/WebUI listener 已关闭。未强杀进程。

外部恢复目录：`/opt/mcsmanager/backups/javarailways/junctions-20261109-d01zep8u`。

- `recovery.zip`：160 文件，包含 JavaRailways、SimpleSignals、TunnelBridgeBuilder 完整插件数据目录与全部已安装插件 JAR；不是 Minecraft region 文件备份。
- 归档 SHA256：`0a08503472bb4b56a890dbbd2087b76ec5df0bc7fe81f15ab7fb73fa041aab0b`。
- `backup-manifest.json` SHA256：`d46387ba0cd1e9465279e2f30c8781cbebbbe5f79c3a3274fe259d8040f4fca4`。
- 归档内所有文件逐一与停服源清单核对，备份前后源哈希相同；下载后的独立副本也逐一验证。

停服时复现既有 SimpleSignals model/editor 清理与 `DefaultModelPresentationService.close` 异常，世界/玩家保存及正常进程退出均完成。本次未夹带并行显示生命周期修复。

## 存档迁移与安装

从这次停服恢复包提取独立副本，使用 `-PmigrationJunctionsOnly` 先 dry-run，再输出全新候选目录。6 个世界的当前与 `.bak` 共 12 对存档，全部经过严格解码和真实 Core/Basic/SimpleATO restore；包含非空列车和时刻表 checkpoint。

主世界当前 revision=455002、epoch=2048352；备份 revision=455001、epoch=2047429。两份各含 113 rails、5 trains、88 个接点组，其中 1 个未完成组。每份修复：删除 4 条错误有向通路，补回 2 条遗漏有向通路，修正 4 处 active；有向通路 244 → 242。

- `62a466ff:B ↔ 7f41ca18:A` 保留接点成员关系，合法出口为空，表示缺少公共腿。
- 删除同侧 `7f41ca18:B ↔ e11eeffc:A`，补回 `0c47adc1:A ↔ 7f41ca18:B`。
- 逐 rail 验证曲线 JSON、UUID 及其他非连接字段不变；Basic/SimpleATO 文档、revision/epoch 保持原值；全部 12 份 runtime 原始 bytes 不变。

19:06:05 原子安装仅替换 3 文件，保留原 mode/uid/gid，并验证恢复边界其余 157 文件未变：

- `plugins/JavaRailways.jar`。
- `plugins/JavaRailways/worlds/2952ad2c-41ce-40bc-b83b-1b578ae7af12-infrastructure.json`：SHA256 `4977e4589321167fdae5f7a39e6d8921039c20b9d1007627a16e0d1dc0ddcff5` → `444d6c6b6806c1d75577a6684eec85e62750246e46ad9956de951233278491ff`。
- 同名 `.bak`：SHA256 `bb76e8c4e6adb2964f0008961b52c467ed0cc66d51fa285ec0a2fa9e0cee5721` → `70d8f4fb3482f43c9be4db02e1205e4d056dc017405de23156550d8c544dbb71`。

没有重置时刻表、清空 checkpoint 或重新摆车。启动后正常自动保存产生的新 epoch/runtime 不属于迁移改写。

## 启动与实际验收

MCSManager 正常 open，新实例 manager PID `1012702`，19:07:00 `Done (27.607s)`。

- `jr ato status world`：`RUNNING`。
- `simplesignals inspect world`：installed，definitionRevision=117、topologyRevision=1；regions 5、tables 5、blocks 14、fixedBlockSignals 8、protectionSignals 4、routeIndicators 1。
- Bridge `127.0.0.1:8765/health`、WebUI `127.0.0.1:29002/health`、公开 `mc.art1st.work:29002/health` 与首页均 HTTP 200。
- JS/CSS 资源 HTTP 200 且 SHA256 与构建清单一致。已安装主 JAR 与保留的两个 addon JAR 均再次验哈希。
- 通过产品公开的地图和列车 SSE 接口确认：113 rails、31 markers、5 trains；`s100-1` 至 `s100-5` 均 `derailed=false`、速度为 0。没有重新发车或完整线路行驶验收。
- 最后读取线上当前与 `.bak`，确认必填成员均已保存、未完成接点无通路、错误同侧通路不存在、遗漏公共腿通路已恢复。
- 新启动未出现 JavaRailways/SimpleSignals error 或 ABI 异常；第三方 `com.dfsek.seismic` 的既有 Unsafe 反射 error 仍在启动日志中。

曾尝试从进程环境定位受管 Bridge 凭据进行只读核对；自动审批以未授权非标准凭据探测为由拒绝继续该方式。随后停止该方式，使用产品明确公开的地图/SSE 接口完成上述实际验收，无需获取凭据。没有通过其他途径重试凭据探测。

## 审计文件

本地：`/tmp/jr-junction-server-20261109-ewh84xo4`。远端同名目录保存 `install.json`、`install-plan.json`、`public-map-verification.json`、`post-deployment-audit.json` 与迁移报告；长期恢复点为上述 `/opt/mcsmanager/backups/javarailways/` 目录。

恢复时必须配套使用旧 JAR 与该恢复点的完整插件数据，不能将旧 reader 与新接点存档混用。未推送 Git，也未自动提交此部署记录或其他 dirty 工作。
