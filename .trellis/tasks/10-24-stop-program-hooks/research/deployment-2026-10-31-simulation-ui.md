# 模拟错误、界面超时和S100灯光修复部署

用户明确授权提交并部署。产品提交`0cadbe8fc84555e289280ca71c1e74417d6c7feb`，48个任务文件；保留无关`java_pid454886.hprof`，未push。原任务继续in_progress，不归档。

## 候选与现网基线

最终前端351、Bukkit679、WebUI54测试及TypeScript/Vite构建、跨层独立复审和真实本机UI均通过。界面1.001秒确实发送1001毫秒，非法输入禁用，三个入口携带同请求预算；函数请求无周期。包内网页JS为`index-ki5t5GHk.js`。

主插件shadowJar成功，开发服使用Mojang映射产物。额外尝试reobfJar遇到已有TinyRemapper不支持Java25的major69限制；没有使用失败的reobf文件，重新单独shadowJar成功，实际候选为已校验的all.jar。

- 旧主JAR：`9ddb673eb444d7a55f9334939fb25512eec6a30075c448d837a000fe6746cb16`。
- 新主JAR：`2dc9b48b9e1cf62b8619ffc638762cee811d6e011db70c9089578c64c7e3e01f`。
- 内嵌及实际管理WebUI：`1f2d8347d4a53d9a0dac8b4516305cdeccfd272ad20b9a2824475586e0516777`。
- SimpleSignals保持`0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39`。

只读核对服务器实际train-model-update-policy为follow-package。WORLD_TRAIN和TRAIN_TEMPLATE捕获及LIVE执行均取当前包的逐车脚本/动作，故无需迁移固定包快照或世界引用。源actions.yml精确匹配仓库历史5af28d84（SHA8a332417...），behavior.js匹配de65ea14（SHA41796ee4...），不是未知自定义差异。更新两个源文件即可；模型几何、控制参数及所有快照不做离线改写。

## 保存、恢复点与安装

MCSManager目标JavaRailwaysTest，instance836082a48c4f48fcb1fea4e83c57dc96，部署前PID779750。10:35:35 UTC正常stop日志确认Saved all worlds；MCS状态0/PID0、原PID消失及8765/29002关闭后才创建恢复包。

外部恢复点`/opt/mcsmanager/backups/javarailways/simulation-ui-20261031-w5rzf65s`，archive SHA`e9bfe6299a56e627a68e0ae697f603a961c96d3be85146309018d657409b7cb0`，覆盖三个插件完整数据和全部原插件JAR，共154文件。目录0700、归档0600，每项hash核对，backup前后源清单一致。

临时工具`/tmp/jr-deploy-20261031-simulation-ui/deploy.py`，SHA`45191b305f0f57fe9b44d0d9ef9495920ed3539a48aa646c60441ba0978f1bc0`。独立review及4个故障测试通过：成功只替3文件并保留权限；第二文件replace后故障完整回滚；backup后其他数据变化零写拒绝；原PID存活拒绝。工具不发送kill，不启动服务器，不是runtime迁移路径。首次控制调用发现PATH没有node，改为服务器现有Node20绝对路径并复跑4测试通过，未改变服务器配置。

10:36:18.849661 UTC安装完成，仅替换：

- plugins/JavaRailways.jar
- plugins/JavaRailways/trains/s100/actions.yml（SHA1992fbec6164b1cde0a62fd48bf37b46f6ec2e628633dd98e880788f1087ce8f）
- plugins/JavaRailways/trains/s100/behavior.js（SHA9a9d6f38c74040b292376d0700d48d4a81e12061e954bbaff14c30186ed0fb38）

安装前再次核对目标PID/MCS/端口/源候选hash/恢复包；各文件原子替换、fsync并保留uid/gid/mode，其余151份文件byte一致。没有世界、编辑器草稿或配置迁移。

## 新实例验收与限制

10:37:02 UTC Done(26.915s)，MCS状态3、新PID782079。10:37:19只读控制台ATO world: RUNNING；SimpleSignals installed，definitionRevision117/topology1，regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1。

bridge health200/ok。实际主/内嵌/managed WebUI哈希一致；服务器本地与公网17项资源全部逐字节匹配；工作区网络另核公网17项及真实SSE列车流，收到trains事件/1列车。部署bundle含executionTimeoutMillis和中英文现实秒文案；地图31marker。匿名simulate401，响应含AUTH_REQUIRED和requestId。23项草稿/信号定义/其他JAR文件仍与恢复包hash一致。

新安装后JavaRailways、WebUI、SimpleSignals错误均0。原实例停服期间仍有13条JR和1条Signals清理错误，原因为DefaultModelPresentationService.close及SimpleSignalsWorldRuntimeManager.clearLocal的IllegalStateException/CompletionException；早于本次安装，单独保留，并未修复此既有关闭问题。Terra的theUnsafe启动错误仍1条，属于此前已记录的第三方问题。

本次证明正常保存/恢复点、精准切换、来源/产物一致、实际服务状态、公开资源与位置流。未取得用户具体出库程序/参考车辆/起点反转信息，未重放其dpt→shinkiba@3流程；不宣称游戏客户端或已登录编辑器功能验收完成。领域诊断详细文本、FOLLOW_PACKAGE仅模型变化但脚本不变时菜单重建，以及多阶段timeout等边界见simulation-errors-2026-10-31.md。

本地和服务器stage均为`/tmp/jr-deploy-20261031-simulation-ui`；机器报告见simulation-ui-deployment-2026-10-31.json。回退应再次正常保存/停服，为后续编辑建立新恢复点后恢复匹配旧JAR与两个模型源；不能覆盖后续玩家存档。
