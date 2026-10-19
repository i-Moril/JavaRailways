# 正式Signal Wand现场编辑与诊断部署

## 范围与来源

用户批准正式实现、验证后部署，并明确编辑页不显示绑定摘要，不使用未定义的上下行。部署目标仍为`user@192.168.1.230`上的`JavaRailwaysTest`，实例`836082a48c4f48fcb1fea4e83c57dc96`，根目录`/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`。没有操作其他服务器。

本地分支`dev/signal-system`：

- `31e26d1f`：宿主显式模型选取盒、纯几何查询及严格YAML。
- `fe588362`：只读运行诊断，保存实际求值来源，不重扫/重算。
- `bbf0a602`：Signal Wand点击接线及Folia安全方块遮挡查询。
- `4c30d9d6`：正式设置草稿、目标创建、现场编辑/换类型/点轨重绑和诊断页面。

之前未部署的`5faffa46`灯泡拆分和`d7af5f38`方向修复一并部署。没有改变信号规则、包/世界schema版本、时刻表或存档结构；不创建测试专用规则。仓库无关脏文件、原模型ZIP和docs-site不纳入。

## 离线验证

隔离候选`/tmp/javarailways-fixed-block-authority.nsoyCU`，Gradle缓存仍位于`/workspace/.gradle`。其他模块源码checksum一致，差异仅为空目录与元数据。宿主三个相关测试类29项及Java API consumer编译通过；Signals最终完整56类593项零失败/错误（47秒，含重编译和打包）。最后仅补诊断选择/终止枚举的中英文文本，再运行Wand/翻译及JAR门禁4秒通过，没有重跑全仓。完整宿主shadowJar、verifyPluginJar、verifySignalModuleBoundaries通过，真实客户端另验。

独立复核修正跨owner方块读取、附属误用主信号重绑、旧异步选轨覆盖新页、新建隐式搬移既有主信号；APPLIED后才清手工待摆放意图。删除13个仅旧菜单测试，新增少量真实行为断言；没有借测试增加运行时兜底、扫描预算或兼容路径。

## 恢复点与安装审计

发出10秒停服提示后，通过MCSManager正常停止，确认status=0且目标Java进程已退出。没有强杀。配置备份位于目录外：

`/opt/mcsmanager/backups/javarailways/signal-ui-20261017-Un1sdT/configs-and-installed-jars.tar.gz`

SHA256：`3f8fe6202d4069d1243177e3588d9774ea3a8fb92da00df47f128001c2f8c6ef`。

完整归档`plugins/JavaRailways/`、`plugins/SimpleSignals/`及两旧JAR，保留ACL/xattr/数值owner，目录0700、归档0600；tar目录可读，124个输入文件在备份前后hash一致。manifest.json记录全部摘要；无秘密的deployment-audit.cjs（SHA256 `ab76cf99e739554fc1b483abd1d2355f9bc41beda486a2bbf7409fe5b95f35f3`）与install-audit.json留在同一外部目录，没有配置同目录逐文件备份。

06:46:23 UTC仅替换以下三个文件，安装后核验其余121文件及文件总数不变：

- `plugins/JavaRailways.jar`：`be768e57e87cfb1ec48bbb3080079488676788185c921334c78ece1194fb2d73`，实际取完整`JavaRailways-1.0-SNAPSHOT-all.jar`，检查plugin.yml及新API类。
- `plugins/SimpleSignals.jar`：`fad6dd45723ad5254ebbd94fbac580c2468812edf1ac76bcd21db16a3c83d05a`，包含正式编辑器/拾取接线及中英文资源。
- `plugins/SimpleSignals/signal-packs/jr_four_light/model/head.yml`：`d3c0edc1e16c1b73f2475b12fbf13e08e970c0531cb582402421f61587920c7d`；更新前hash`c92f348c28a64073f36f7becf92b3ee687071079866a3d05ceaf3a845326ffec`与原部署源码一致，无玩家包内修改被覆盖。

没有回退、迁移或自动修复玩家定义；定义revision38及设备位置、逻辑绑定保留。此前手动给模型实例加180度补偿的情况不自动猜测，若存在需玩家撤销。建议重新领取Signal Wand开始本轮验收，以免旧物品还保存未完成手工摆放意图；未增加旧PDC专用reader。

## 客户端资源

正式四灯资源和宿主编辑标记合为独立交付ZIP，只有pack.mcmeta适配已确认的26.1.2资源格式84.0，资产来自仓库，不覆盖原用户ZIP或综合资源包：

- 本地：`/tmp/javarailways-signal-ui-20261017.Q8TTW8/jr-signals-and-editor-26.1.2.zip`。
- 远端留存：`/opt/mcsmanager/backups/javarailways/signal-ui-20261017-Un1sdT/jr-signals-and-editor-26.1.2.zip`。
- SHA256：`814f75eb4ffc9eb46394a971b26ac5d316d0be90c3825de5109c3c4878a8125e`。

外壳/暗灯自然光，亮灯单独部件15/15；共同父旋转−90适配客户端额外Y180。用户仍需安装更新后的资源包，服务器不自动替换客户端文件。

## 启动与验收边界

正常启动Folia26.1.2，06:46:58–06:47:02 JavaRailways/SimpleSignals启用；主世界明确从primary加载。06:47:03 `Done (27.002s)!`。06:47:18正式只读inspect为主世界installed、definitionRevision38/topologyRevision1、regions3/tables3/blocks6/fixedBlockSignals1/protectionSignals1，附属与表示器均0；06:47:20 ATO world RUNNING。公开bridge `/health`为`ok:true`，WebUI首页HTTP200。检查的新启动日志未见本批类加载或信号包错误；既有WebUI明文HTTP、其他插件警告不在本批修复范围。

这证明产物安装与正常加载，不代表已用真实玩家验收Dialog、模型朝向/亮度/点击或信号联动。之前固定闭塞红灯的具体现场原因仍未定位；现可从右键设备的真实只读诊断读取，不能宣称已修复。Stage 2继续in_progress，不归档整个任务。

## 后续：保护进路观测与复制诊断部署（07:37–07:39 UTC）

用户要求“先部署”，本次只替换SimpleSignals JAR，包含全部合法保护进路监测注册修复及诊断原生clipboard按钮；不实现错误文件日志，不更新宿主、信号包、资源包、配置或玩家定义。源码为`3fc32374`之后本任务尚未提交的本地修复；相关源文件见implement.md本地批次，代码提交批准另行处理。隔离候选全src checksum与工作区一致，完整SimpleSignals 594项零失败/错误/跳过，JAR及模块边界门禁通过（29秒），不重跑既有无关模块测试。

向玩家发出10秒重启提示，经MCSManager正常停服；07:37:14确认世界/玩家保存完毕，实例status=0且Java进程退出。完整归档两个插件目录和原两JAR，恢复点位于配置目录之外：

`/opt/mcsmanager/backups/javarailways/signal-observation-20261017-JLjl8d/configs-and-installed-jars.tar.gz`

归档SHA256为`ce329eaff7857d83829b86bb16295e3a6dde03a85c36401c22b643d9e2d79f75`，目录0700、文件0600，保留ACL/xattr/owner，tar可读且124个输入文件备份前后hash一致。manifest.json、deployment-audit.cjs和install-audit.json同目录留存。07:37:56安装`plugins/SimpleSignals.jar`，SHA256为`0b5a368e206bd22ea02bfd9001ab1bd5bb6742f2de66d3577a15e130fe0545a5`；其余123文件及总数不变，主JAR仍为`be768e57e87cfb1ec48bbb3080079488676788185c921334c78ece1194fb2d73`。没有使用早先revision40副本覆盖远端最新定义。

07:38:25 SimpleSignals正常启用且主世界从primary加载，07:38:26 `Done (26.489s)!`。07:38:55只读inspect显示installed、definitionRevision44/topologyRevision1、regions6/tables6/blocks6、固定与保护信号各1，表示器/附属0；ATO RUNNING。07:39公开bridge健康`ok:true`、实际WebUI29002端口HTTP200，MCS status3/PID522475。首次误探默认WebUI8080端口无服务，核实启动日志的实际端口后检查成功，不修改配置。

新启动日志未见类加载、信号包加载或same-epoch错误；但规则Unavailable不保证进入日志，因此不能仅凭日志宣称现场信号问题完全消失。07:38:27发生一次179ms物理epoch overload及202ms→200ms delta clamp，至07:39检查未再出现；保留记录，不在本批擅改物理逻辑。既有Terra Unsafe等其他插件告警仍在。客户端“复制诊断”及原红灯现场结果待玩家验收，Stage 2保持in_progress。

## 后续：错误文件与Wand连续创建部署（08:35 UTC起）

用户明确批准部署。发布前Core/Bukkit/Signals全src与隔离候选checksum一致，复用上批1833项完整受影响模块门禁及打包/API边界检查，不重复构建；代码提交仍未获明确批准，来源为`3fc32374`之后本任务未提交改动。仅替换两JAR，无配置、schema、资源包或玩家定义迁移。

10秒提示后MCSManager正常停服，08:35:25世界/玩家保存完成、status0且Java已退出。完整两个插件目录及旧两JAR归档至目录外：

`/opt/mcsmanager/backups/javarailways/error-wand-20261017-ycuJrK/configs-and-installed-jars.tar.gz`

归档SHA256=`bed031911402ac3f937133af009d66d2b482cfe9c53ced652127bd5f565c8363`；124输入文件备份前后hash一致，tar可读，ACL/xattr/owner保留，目录0700/归档0600，manifest/安装审计/无秘密脚本同目录留存。

08:35:54安装两JAR：主完整JAR=`13fb17ac31bb1223db7be5f4299d3dfafc003c406b977819d95c3bdab0d8ed6c`，Signals=`1605f4ffdbdbde7cbc4ad9c2cda7adf17f11caaad33de6c732aa59c1d41e0433`；其余122文件及文件数不变。08:36:24 `Done (26.416s)!`，bridge健康ok、WebUI29002 HTTP200。

现场确认两个`logs/errors.log`真实存在且包含module/时间/原始异常链；主插件有Bukkit和Core警告，Signals记录了一条保护入口无ready进路的异常。后者暴露分类遗漏：`PreparedBinding.selectPlan`的正常ready守卫仍抛普通IllegalArgumentException，未被预期不可用分类排除。此时正常禁行和授权安全行为不受影响；主控已启动本范围最小修复及回归，不用消息过滤或关闭记录掩盖它。首次启动另有206ms overload及217→200ms clamp，保留现场证据。历史日志不删除，最终修正产物和二次恢复点另记。

### 正常未ready分类修正与第二恢复点

独立复核确认仅固定进路not-ready和当前入口未ready两处守卫归为`ExpectedUnavailable`；原reason包装、0限制和选路/授权不变。ready却缺routeId或观察计划等实际不变量错误继续报告，不按文字过滤。扩展原有真实manager方法同时验证两类保护信号在无请求、PREPARING及兄弟进路选择时error sink空，原速度扫描断言保留；真实Graal异常记录用例仍保持。最终Signals完整595项零失败/错误/跳过，JAR与模块边界检查38秒通过，运行时/test与隔离候选checksum一致；其他模块源码不变，不重复执行1833项。

再次10秒提示后正常停服，08:41:21保存及进程退出确认。第二次完整归档两个插件目录（含首次错误文件历史）与两JAR，共126文件，位于：

`/opt/mcsmanager/backups/javarailways/error-wand-20261017-QDXnqt/configs-and-installed-jars.tar.gz`

归档SHA256=`7c8c69d460402a6d9bcd617c59fa1e675a21c7a31b05a32f0a2e3c00b3d84363`，同样保留ACL/xattr/owner、0700/0600及完整审计。08:41:44仅替换SimpleSignals.jar为`40c46158b63f2031dc5fc87599305d739ce232608118dcd18c74dadceb045059`，其余125文件和总数不变。初始恢复点ycuJrK仍保留，可恢复本批部署以前的两个JAR；QDXnqt保存第一次更新后的主JAR与误报历史。没有清空、篡改历史日志或回退玩家定义。

08:42:13 SimpleSignals启用，08:42:14 `Done (26.203s)!`。08:42:35只读inspect为主世界installed、definitionRevision45/topologyRevision1、regions6/tables6/blocks6、固定信号2/保护信号1（其余设备0）；ATO RUNNING。bridge ok、WebUI HTTP200，MCS status3/PID524532。新启动日志未见signal-rule-failed或类加载故障；Signals errors.log保持4427字节、mtime08:41:20，证明本次正常未ready没有再追加误报。主文件的Bukkit/Core WARNING已实际写入；08:42:15一次186ms overload与218→200ms clamp沿原分类写入，检查时未再出现。

未解决但保留的现场事项：第二次停服时08:41:20出现`JavaRailways was disabled before model cleanup completed`，原始cause来自`DefaultModelPresentationService.close`，经SimpleSignals model/editor cleanup completion写入错误文件。这是整服停用时附属异步显示清理与宿主停用的时序问题，不是本次JS规则故障，也不能宣称正常停服清理完全无异常。没有在部署中扩大为显示服务/停服协调器重构，后续单独审查；本次正常保存、再启用及health均已验证。历史误报与该真实清理异常都原样保留，客户端Wand普通→保护→普通仍待玩家验收。无资源包更新、版本变更、配置迁移或测试世界对象创建；Stage 2保持in_progress。
