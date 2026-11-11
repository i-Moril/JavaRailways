# 停车点后缀纠正部署 — 2026-10-31

用户明确要求提交并部署，沿既有任务，不归档、不push。功能提交6563e4f989a6e0a01f06cbd0432d443ac7183089。仅提交本批14个源/测试/规范/记录文件，无关heap dump保留。

## 门禁与工件

冻结的前端335项/57文件、TypeScript/Vite及真实App fixture后缀/完整via检查已通过；本轮5个前端文件hash复验不变，独立范围复核仍适用。部署构建:BukkitPlugin:shadowJar/:WebUI:test在13s成功，WebUI49项0失败/错误/跳过。没有重复后端3029门禁。

逐entry比较实际线上旧JAR（fb928ce1...）和候选：唯一内容变化是webui/JavaRailways-WebUI.jar，所有其他entry完全一致，因此已上线后端/信号修复保留。嵌入17资源与当前dist逐字节一致，JS为assets/index-Lr1DZJIQ.js。

- 主JAR SHA256：73f95e3b6dd4c4cf1aff8db54d0c0ab137026b9306d47f71e9f9183a86990eb8。
- 内嵌/管理WebUI SHA256：866f8b5c6749fcba11b09aaeb32f73e64194cca1c495965ac31d88c61f8193d8。
- 既有SimpleSignals.jar保持0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39。

## 数据与恢复

候选校验当前7份编辑文档和6条程序库记录，停服后的13份仍与预检hash完全一致；无数据/格式迁移。08:11:01 UTC正常保存全部世界，MCSManager status0/PID0，安装工具另确认目标JVM退出。

完整外部恢复点：/opt/mcsmanager/backups/javarailways/waypoint-suffix-20261031-nqs2r3xl；归档SHA256 f0725450de8179fe0bb3d98c6de0c483feb25ad7fa5370cd56abca7de60d694c，145项插件数据清单。复用既有工具，仅改变恢复目录prefix；归档/清单和安装审计保留于恢复点。

08:12:08 UTC源/候选hash守卫原子替换唯一JavaRailways.jar，逐项核验数据和所有其他插件JAR不变。未回退。暂存/详细日志：本地和服务端/tmp/jr-deploy-20261031-waypoint-suffix。

## 实际运行

正常启动08:12:46 UTC Done（27.970s），status3/PID777019。实际控制台08:13:26 ATO world: RUNNING；08:13:29 SimpleSignals installed，revision117/topology1，regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1。信号JSON与停服快照逐项一致。

Bridge health200/ok。主JAR、嵌入和管理sidecar匹配；服务器localhost/公网17资源逐字节一致，工作区另核公网17资源通过。匿名模拟POST仍401。

无新增插件ERROR/SEVERE。JavaRailways新增1109bytes，4条既有HTTP/启动physics WARNING；SimpleSignals错误日志未增长。WebUI新增262bytes/1条位置流HTTP409 WARNING，源码确认train streaming要求先加载地图。公开rail-map GET200后，以正确Accept:text/event-stream读取SSE，已收到真实trains事件/1列车，确认当前流正常；不单凭日志未继续增长推断恢复。此前无Accept头的普通GET200/空体不构成SSE验证，证据已区分。

本轮确认构建/实际安装、数据保留、ATO/信号、资源交付与公开列车流。纠正后的交互由本地真实App fixture验证，未宣称线上登录编辑/游戏客户端验收。任务保持in_progress、completedAt=null。

回退前应正常停服、先为最新玩家编辑另建恢复点，再核实本次归档并恢复匹配旧JAR；不运行中覆盖数据，不丢弃后续编辑。机器记录见waypoint-suffix-deployment-2026-10-31.json。
