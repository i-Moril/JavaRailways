# 路径点输入批次提交与部署 — 2026-10-31

用户明确授权提交并部署，后续询问失败是代码还是测试问题，不取消部署。沿既有任务和worktree执行，不归档、不push；无关heap dump保留。

## 提交与线上基线

- 功能提交：574975bfc959e3b43ac60f7aa65373ab70846d5d。
- 整合提交：bc7d6db1195343ae545a79beb7c3b53f78f7c583，合入dev/signal-system@6a6db614，保留实际已部署32d1c413的信号编辑、保护区域和方向修复。已安装源由实际JavaRailways hash8aac93d7...和既有部署记录核实；不是假定仍停留在4c19f7f4。
- 最终源：976f4eacc6f005eca01ddb667dbad56a0f991ef7，仅追加下述测试适配。相对线上源，后端生产目录没有改变，本批产品变化仍限前端。工作分支codex/timetable-station-programs，目录/tmp/javarailways-blockly-deploy。

## 最终门禁与失败归因

前端57文件335测试及TypeScript/Vite构建通过。后端3029项全部通过：Core538、Basic435、SimpleATO657、SimpleATOBukkit2、SimpleSignals675、Bukkit673、WebUI49，XML均0失败/错误/跳过；Java consumer、信号模块边界、插件JAR核验和shadow打包通过。

一次本地构建被开发环境重启中断，保留interrupted-gradle.log后恢复。首次完整检查只有新增depot测试失败：将rail设为单向AB却期待反转BA仍成功，违反已上线beginReverse对反向车身的方向校验。修正仅改测试：ownBA在无限向独立存车线正反均完成7tick；rail fallback AB反转必须Failed/OperatingDirectionViolation，函数未启动、列车未反转/未移动；方向优先级和源库存隔离仍保留，模拟数量未增加。独立复核通过，受影响Bukkit和之前未执行的WebUI/消费者/边界门禁重跑通过（1m47s）。其他已通过模块源输入未变，不重复执行。没有为测试放宽产品规则，详见depot-direction-integration-2026-10-31.md。

最终2067项源码/构建输入hash逐项复验无差异。既有真实App输入/历史/窄屏证据仍适用：前端产品字节未因整合或测试修正改变，生产JS为assets/index-CjPiqCNe.js。

## 数据、恢复与安装

候选实际WebUI校验器接受当前7份编辑文档和6条库记录，停服后13份仍与已验证快照完全一致。没有迁移、schema变化或运行时兼容路径。

正常MCSManager stop保存全部世界07:33:38 UTC，status0/PID0；安装工具另验证目标JVM已退出。外部恢复目录：

`/opt/mcsmanager/backups/javarailways/waypoint-input-20261031-mo6wgx67`

归档SHA256：`bfb682d2cf0429355060fd6f611aff2b69f2c0b869f254d349f8aa2e90cd7de6`；145项插件数据清单。归档含原JAR、相关三个插件的数据，保留owner/ACL/xattrs；恢复清单和安装审计root-only。

07:34:57 UTC按源hash/候选hash守卫原子替换唯一JavaRailways.jar；数据和全部其他插件JAR在安装时逐项不变。SimpleSignals.jar保持0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39。

- 主JAR：fb928ce1083b07c2c3c3a272587305b0c3815157859f75a39e244b5706675a16。
- 嵌入及管理WebUI：da2ac1f085bd9fe5a6433c55663f11d46d2d8eeaf8133e1552e649e6ec0afce2。
- 本地/远端暂存：/tmp/jr-deploy-20261031-waypoint-input。恢复工具复用既有已审计工具，仅改本次恢复目录prefix；无额外数据转换。

## 实际运行核验

正常启动07:35:36 UTC Done（26.815s），status3，新PID775639。实际控制台查询07:36:12 ATO world: RUNNING；07:36:15 SimpleSignals installed，revision117/topology1，regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1。信号JSON逐项与停服快照一致；运行期间玩家产生的既有编辑未被旧快照覆盖。

Bridge health200/ok，管理sidecar与嵌入字节一致；17资源在服务器localhost和公网逐项匹配，从工作区另核公网17资源仍匹配。正确/api/v1/worlds/world/programs/simulate匿名POST返回401（第一次检查脚本漏v1得到404，修正探测路径后通过，并非产品路由故障）。

JavaRailways新增日志1109bytes，仅4条WARNING（既有HTTP配置提示和启动physics overload/delta clamp），0新增ERROR/SEVERE；WebUI及SimpleSignals错误日志均无增长。没有新插件启动错误；不把已有Terra/Unsafe问题当成本批故障或宣称全服零错误。

构建/安装/恢复、实际ATO/信号状态、资源交付和认证门禁已验证。没有读取进程令牌，线上认证metadata候选及游戏客户端输入/操作仍待用户验收。父任务保持in_progress、completedAt=null。

回退前应正常停服并先为之后的最新编辑另建恢复点，再核实本次归档hash并恢复匹配旧插件；不得运行中覆盖存档或丢弃后续玩家编辑。部署机器记录在同目录的waypoint-input-deployment-2026-10-31.json，详细日志/源指纹在上述暂存目录。
