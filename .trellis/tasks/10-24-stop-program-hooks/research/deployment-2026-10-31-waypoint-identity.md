# 路径点仅保留 ID：提交与部署（2026-10-31）

用户明确要求“提交并部署”。产品提交 `daabfa4e4517af0d5071bacd8418548d528e73cf`，89个任务文件；无关 `java_pid454886.hprof` 保留，未 push。部署至既有 JavaRailwaysTest：user@192.168.1.230，MCSManager instance836082a48c4f48fcb1fea4e83c57dc96。任务继续 in_progress、completedAt=null，不归档。

## 产物与验证

现网基线与上一轮实际部署一致：旧主JAR `73f95e3b6dd4c4cf1aff8db54d0c0ab137026b9306d47f71e9f9183a86990eb8`，SimpleSignals `0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39` 保持。

新主JAR `9ddb673eb444d7a55f9334939fb25512eec6a30075c448d837a000fe6746cb16`；内嵌与实际管理的WebUI `410a87220a53744d29a007afc1f872ee09eca702843485a8bd4aab93f19e71ab`；JS `assets/index-CZpHHYPb.js`。上一轮冻结源上的后端3061、前端335、迁移12、构建/浏览器/独立审查保持有效，本轮未改产品代码，没有无理由重跑全部门禁。

服务器附带 temurin25 是JRE，无jdk.compiler，首次源码启动校验器失败，尚未停服。使用原样 `ValidateInfrastructure.java` 在本地 `javac --release21` 预编译，再由临时启动器核验源码/class SHA后以参数列表调用真实JRE25，classpath固定新JR。manifest、backup/install工具守卫与审计同时绑定launcher、class、源码指纹；不改已提交迁移工具或产品runtime。只读文档校验器亦预编译后与WebUI同一classloader运行，避免source launcher跨loader访问package-private验证器。9项临时部署脚本故障测试及独立增量审查通过。

最终部署helper SHA `3224e40292509bbcf629c7493aa90c5324c141ddc565f140923b4aa5fc8279f9`。服务器本地在线副本预检与最终停服副本都由真实新JAR codec接受全部12份基础设施；编辑器校验接受8份文档、6条程序（用户已比上一批多编辑一份文档），无草稿迁移。

## 正常保存、恢复点与精准切换

09:15:39 UTC日志确认 Saved all worlds；MCS status0/PID0、原PID777019消失，8765/29002端口均关闭。首次普通用户备份读root-owned model snapshot权限不足，未创建恢复包或修改数据；改用服务器现有sudo权限完成备份，未放宽文件权限。

完整外部恢复点 `/opt/mcsmanager/backups/javarailways/waypoint-identity-20261031-5_dkk06_`，archive SHA `fd1dfc44d53131a29efd3ee637a7c7d5b66396e286879055eb16649dbbf0e7ee`，146项插件数据清单，包含三组插件数据及全部原插件JAR。归档与清单校验、备份前后源inventory一致。

停服副本 dry-run→实际current codec→独立候选→再次current codec，两个世界主/备分别删除31字段，共62；其余10份基础设施byte no-op。版本/revision/epoch、其他文本字节和站/库/线路名称保留。09:18:49 UTC源/候选、归档、工具与报告hash守卫完成安装，仅替换world主基础设施、对应backup和JavaRailways.jar三份文件；保留uid/gid/mode，其他数据和全部其他JAR逐项一致。没有运行时兼容或专门旧字段拒绝路径。

## 实际运行与外部交付

09:19:41 UTC Done（27.996s），MCS status3、新PID779750。实际只读控制台09:21:00 ATO world: RUNNING；09:22:08 SimpleSignals installed，definitionRevision117/topology1，regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1。

Bridge health200/ok。全部12份保存文件共62个路径点记录，独立显示名剩余0。实际公开地图31个marker均只有ID而无label，包含etchujima@U、shiomi@D、shinkiba@3、kasaidpt@1。14份编辑器/程序文件与两份信号定义hash保持；其他插件JAR不变。实际管理的sidecar与内嵌产物一致。服务器localhost与公网17项资源逐字节一致，工作区网络另核公网17项全部一致；正确Accept:text/event-stream的公开位置流收到trains事件/1列车。匿名simulate仍401。

## 错误的时间边界与未解决事项

必须区分旧实例停服和新实例：09:15:38、早于安装的模型展示清理记录为JavaRailways13条SEVERE、SimpleSignals1条SEVERE，异常类型IllegalStateException/CompletionException，栈指向DefaultModelPresentationService.close及SimpleSignalsWorldRuntimeManager.clearLocal。这些错误不能算新版本启动失败，也不能从部署报告中隐去；属于旧实例关闭作业的问题，保留待后续排查。

第一次验收按“部署前日志offset以后都不能有ERROR”失败；定位时间后改为同时报告全部错误及其停服/安装后分组。新插件安装后JavaRailways/SimpleSignals/WebUI错误均0；JR仅4条既有HTTP/启动physics warning。第三方Terra的theUnsafe启动ERROR在当前及前两份归档启动日志均各1条，对应插件未替换，单独记为既有问题。

直接导出原始错误日志和完整启动脚本的请求被自动审批拒绝后，没有尝试其他正文复制；只在服务器返回白名单Java路径、异常类型/时间及代码方法名等摘要，完成安全替代检查。此前完整世界存档复制拒绝也未重试；本次转换/解码全部在服务器本地副本上执行，没有将世界数据导出。

本轮证明实际安装、保存/恢复、真实codec、ATO/信号、公开地图协议、资源交付和位置流；未宣称线上已登录编辑器或游戏客户端验收。上线后的玩家操作仍待用户验证。

本地/服务器stage `/tmp/jr-deploy-20261031-waypoint-identity`。机器审计见 waypoint-identity-deployment-2026-10-31.json。回退应正常停服，先为后续玩家编辑另建恢复点，再从已校验外部归档恢复匹配旧JAR及旧数据；不得把新格式存档交给旧插件，不得运行中覆盖或丢弃后续编辑。
