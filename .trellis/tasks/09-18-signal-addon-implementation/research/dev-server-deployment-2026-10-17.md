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
