# 第三批修复（2026-10-23）

用户在六项处理方式确认后授权执行；沿用本会话不创建Trellis任务的选择。保留既有未提交工作，本批不提交、推送或部署。

## 变更边界

- BUK-06：限时由脚本执行owner管理，生产预算60秒，超时和sandbox关闭必须真正取消Graal Context，释放编译容量；打印输出有界。改动sandbox、scriptworker及相关回归，不增加独立进程架构或新的用户配置。
- BUK-20：registerResourceProvider返回句柄只对应本次准确注册实例；旧句柄不能注销同owner/provider对象的新登记。修改宿主service和API合同/测试，不引入generation编号。
- BUK-21：宿主内部帧间队列的Future不直接交给调用方；保持公开签名，外部完成/取消自己的等待结果不改变道岔请求的实际执行。修改同一service和API合同/测试，不改Core队列语义。
- BUK-04：rail visual索引身份包含exact world，世界卸载/安装失败退休它拥有的条目并接入既有异步清理。修改索引、lifetime使用者、RailWorldFactory构造回滚及WorldManager清理入口；不更改物理rail身份、几何和持久化格式。
- BUK-22：模型资源加载/重载准备快照时检查活动引用栈，发现环报告路径并拒绝该资源，正常复用允许；不改变schema/hash或增加旧格式reader。
- ATO-06：仅明确完整时刻表对象图在交给Store/Dispatcher或捕获保存前必须冻结、之后通过新对象替换编辑；不新增运行时复制或可变编辑API。

ATO-04已撤下，不修改其缺省泊位分支。本批不是重新全模块审查，也不涉及Wand架构或源码目录迁移。测试验证只对本批实际执行结果作声明。

## 实施与复核

- 脚本production默认60秒，内部短期限仅供测试；Context取消覆盖顶层eval和main，shutdown逐项尝试并保留失败，finally归还两编译许可。打印合计1MiB，源码/结果保持512KiB/8MiB。取消deadline从队列移除；仍是同JVM，没有heap隔离。
- provider句柄以准确注册对象比较，既有资源活动门禁保留。道岔结果返回独立dependent future；外部修改的是自己的等待视图，实际队列和发布由宿主拥有。
- rail visual索引为(runtimeId,railId)，所有注销调用包括BukkitPluginDev及两个undo方法引用已更新。WorldManager卸载/安装失败与factory返回前的构造失败均退休索引；聚合异步删除结果受观察，不在owner阻塞等待区域任务。
- 模型环检查使用实际对象身份的活动路径及visited集合，独立于持久化按名称收集的map。独立复核发现的“同名普通零件先收集、内联同名零件含环”旁路已用真实YAML回归封住；正常重复和diamond复用允许，失败reload保留旧资源。
- ATO-06仅更新模型、extension参数、保存DTO的KDoc及spec。producer在validation/交付/捕获前冻结完整可达对象图，原列表/JSON底层引用之后不可修改；编辑新建对象并走显式替换流程。没有新增复制代码或schema变化。
- 修正docs-site独立submodule中的脚本文档：不再声称独立worker JVM、3秒和固定heap；同步实际WebUI读写/编译/发布能力。

## 验证

- 定向回归10类82项全通过，BukkitPluginDev编译通过；该次包含同名inline环回归，后补factory构造资源回滚测试交由最终全量执行。
- 文档站 `npm --prefix docs-site run check` 成功，覆盖typecheck、模型/schema校验、Docusaurus build及Pagefind；使用已有依赖，未改package/lock文件。
- 插件包检查通过：包含本批ScriptExecution/world-key类、内嵌WebUI index、Cloud relocation及15个非空service声明。使用新shadowJar，不以旧reobf包作为产物。
- 最终全量 `BUILD SUCCESSFUL`，退出码0：`:BukkitPlugin:test :SimpleATO:test :SimpleSignals:check :Core:test :Basic:test :WebUI:test :BukkitPlugin:compileSignalApiConsumerJava verifySignalModuleBoundaries :BukkitPluginDev:compileKotlin :BukkitPlugin:shadowJar`。
- 最终报告计数：BukkitPlugin 582、SimpleATO 382、SimpleSignals 651、Core 505、Basic 390、WebUI 12；均0 failures/errors/skipped。首次全量的上述非Basic测试及打包已完成，但整体在Basic test编译阶段收到SIGTERM取消；最终收尾这些任务为up-to-date复用，Basic测试本次实际执行、模块边界检查再次执行。没有把被取消的整体构建当成成功。
- 定向日志 `/tmp/jr-third-focused.log`；中断日志 `/tmp/jr-third-final-interrupted.log`；最终日志 `/tmp/jr-third-final.log`；文档日志 `/tmp/jr-third-docs.log`。首次沙箱内Gradle因本地文件锁网络初始化限制未启动，测试在获准沙箱外使用本机JDK25运行。
- 根仓及docs-site的diff whitespace检查通过。docs-site仅修改手写脚本文档，作为独立submodule的未提交变更保留；未提交gitlink、生成文档或产物。
- 六项按约定处理完成：BUK-04/06/20/21/22有代码修复；ATO-06以所有权契约结案，未加防御复制。

## 验证边界

未运行真实Paper/Folia或客户端。脚本测试使用真实Graal及内部短期限，不需每例等待生产60秒；接口测试驱动真实service与Core帧间队列。visual lifetime测试检查真实资源owner和异步完成，但未做实体后端删除故障现场验证；factory测试为真实清理组件/构造资源栈组合，不是完整Bukkit启动。未执行旧版本红→新版本绿探针，历史测试数字与本次执行/复用结果分别记录。未修改持久化格式或新增旧数据reader。


## 用户后续授权提交与暂存版本检查

用户在实现结束后明确要求本地提交。三批修复、相关测试/spec、repo-local审查skill及本目录记录纳入同一主仓提交；docs-site脚本文档先在独立子模块本地提交，主仓记录其引用。不推送或部署。

SimpleSignals预先存在的问题列表清理代码、对应测试和spec段落不属于本轮：仅暂存SS-1/SS-2修改，保留这些原有修改在工作区。Basic/Core其他审查记录改动、独立task/research文件及attach临时文件也排除。原有路线工具overlay修复已由另一提交完成，作为当前父提交基线保留。

从暂存区导出独立源码目录验证，除三个按hunk选择的Signal源码/测试/spec以外，本轮文件与此前全量测试通过的工作区逐文件一致。导出版本执行`:SimpleSignals:test`成功，650项、0 failures/errors/skipped；不是含原有问题列表新增测试的651项。该独立构建实际编译Core/Basic/SimpleATO/Bukkit等依赖，前端未变，复用此前生成的静态资源并跳过npm安装/构建。日志`/tmp/jr-review-staged-signals.log`。暂存diff空白检查通过。
