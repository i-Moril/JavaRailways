# 开发服显式轨道端点存档迁移与配套主JAR应用

日期：2026-10-17 UTC。用户在实现、测试及提交归档后明确授权“你可以修改开发服存档”。
本次将同一任务的已验证改造应用于远端开发服；没有创建新Trellis任务或扩大到信号规则、包、客户端资产及其他实例。

## 精确目标与必要配套

目标`user@192.168.1.230`，MCSManager实例`836082a48c4f48fcb1fea4e83c57dc96`（JavaRailwaysTest），
根目录`/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`。
开始时status3/PID524532；主JAR为`13fb17ac31bb1223db7be5f4299d3dfafc003c406b977819d95c3bdab0d8ed6c`，
Signals为`40c46158b63f2031dc5fc87599305d739ce232608118dcd18c74dadceb045059`。

新current reader拒绝UUID-only形状，旧reader也不能读取新字段，因此存档应用必须同时安装匹配主JAR。
发布前1014份JVM/build输入与最终2259测试所用manifest一致；完整候选JAR为`e840e71d0a0d92a848bece231256d78f70ada260c2c9564eb30221cccfe67a65`。
与实际旧安装JAR逐ZIP条目比较，新增/移除/变化均为本任务Core、undo、隔离模拟及其生成类，未引入其他在制功能；
plugin.yml与新SavedRailEndpointRef/serializer存在，离线迁移类未打入JAR。未改Signals JAR，不因部署重复完整测试。

## 正常停服与完整恢复点

通过既有MCSManager接口正常stop，确认status0/PID0及无目标Java进程后才备份；未强杀，未操作另一实例。
整个`plugins/JavaRailways/`、`plugins/SimpleSignals/`及两份旧JAR归档到配置目录外：

`/opt/mcsmanager/backups/javarailways/endpoints-20261017-97er70ia/configs-and-installed-jars.tar.gz`

归档SHA256：`b1286d7c7b8b4d4d2e350edf286f6e6d613ebbd71bcfa842f23118eaefbb3009`。
目录0700、归档0600；tar保留ACL/xattr/数值owner，目录可读性检查通过。
126个源文件备份前后hash/owner/mode一致；本地独立下载、解包后126项内容与manifest逐项相符。
同目录manifest.json记录停服来源与元数据，审计脚本、迁移日志、精确候选归档及prepared目标长期留在该外部目录。

## 使用最新停服存档迁移

本地工作目录`/tmp/javarailways-endpoint-apply-20261017`，输入为本次完整停服归档的source/plugins/JavaRailways/worlds。
没有使用之前在线取证副本覆盖远端。主世界停服current revision/epoch=115351/115390，backup=115200/115230。

已提交的`:BukkitPlugin:migrateDevelopmentWorlds -PmigrationEndpointsOnly`对该最新源先dry-run，再输出全新converted目录；
两次均通过新current严格codec与真实Core→Basic→SimpleATO恢复，共12对。使用共享离线Gradle缓存及足够内存，不改产品源码或构建配置。
独立逐项比对确认126源文件未变，12份runtime原始字节一致；curve、rail顺序、revision/epoch、所有其他outer/nested数据保持。
主世界current/.bak各106rails、234有向连接（AA28/AB85/BA85/BB36），共468条精确A/B转换；其他五世界为空且不改写。

## 应用及失败边界

10:21:08 UTC只替换3个文件：

- 主世界infrastructure.json：目标SHA256 `da8b7d9baad63da6b147d47c8fa4069ada96ae414557703fb4967735963133cc`。
- 同文件.bak：目标SHA256 `40b240edb9cd6de90190faf7bd024cecdc861a8ec318ca703aa873024ee28cc8`。
- plugins/JavaRailways.jar：目标SHA256 `e840e71d0a0d92a848bece231256d78f70ada260c2c9564eb30221cccfe67a65`。

安装前再次检查归档hash、完整停服源manifest及候选hash；通过同目录临时文件+fsync+原子rename替换每个目标，保留uid/gid/mode/xattr。
脚本异常路径会逐个恢复3份原字节并核验完整源manifest，实际没有触发回滚；跨文件应用前的prepared状态及候选保存在外部恢复点，
异常中断须先保持停服再依据该恢复点人工恢复，不能让旧JAR读取新格式。
安装后126文件总数不变，精确确认其余123文件（含全部runtime、Signals数据/JAR、sidecar、包及日志历史）原样不变。
没有在配置目录另存逐文件backup，也没有把仅含转换世界文件的输出覆盖完整worlds目录。

## 实际启动和只读验证

MCSManager正常open，新PID526654、status3。10:21:46 JavaRailways启用，10:21:50 SimpleSignals启用并明确从primary加载定义，
10:21:51 Minecraft `Done (25.556s)!`。10:22:26只读console inspect显示definitionRevision45/topologyRevision1、
regions6/tables6/blocks6、固定信号2/保护信号1，表示器与附属0；ATO world RUNNING，与停服前已有定义保持。
bridge8765/health返回200 `ok:true`，WebUI29002/health返回200 `status:ok`，首页200。
第一次HTTP探测在插件启动尚未完成时连接失败，正常完成后复核通过，不将早期探测当运行故障。
启动后再次只读检查主世界两份基础设施均为显式connectionsAtA/B形状、106rails/234引用；两JAR hash符合计划。

保留现场边界：Terra仍有既有sun.misc.Unsafe字段反射错误；10:21:52一次175.832869ms epoch overload和213.948452→200ms步长截断，
未借此修改物理配置或扩大修复。新启动日志未见本次保存/迁移或LinkageError/NoSuchMethodError/NoClassDefFoundError。
没有创建游戏对象或应用时刻表；这些检查证明实际启动与业务恢复，不代替玩家行车/客户端交互验收。

## 恢复方式

若需降级，先正常停服并另行保存应用后状态，再从上述同一完整恢复点配套恢复旧存档与旧主JAR。
不能只换回旧JAR或重新使用旧在线副本。原始外部归档和manifest不得被轮转覆盖。
