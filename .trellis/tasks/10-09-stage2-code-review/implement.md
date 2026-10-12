# 执行与审查计划

当前阶段：in_progress。用户于 2026-10-09 批准修复已知问题；2026-10-10进一步批准连续小提交后统一检查，不要求等待全部审查结束才修复已确认项。

## 当前执行顺序（2026-10-12用户纠正后）

继续原CR-030批次3：先完成Bukkit每车JS/动作/延迟效果/快照的完整纵向迁移，再接入collision四端联结事务及结果消费。Basic后续修复暂存，不插入该主线。此前将用户“提高效率”的要求直接解释为暂停Core转查Basic，是执行顺序误判，不是用户批准的计划调整。

当前已完成Core ownership、结构编辑epoch准入、源包精确解析及真实开发服只读取样。2026-10-12用户已确认自定义动作菜单取并集，仅操作声明该动作的车厢；整列开关门仍按全部目标汇总。主编组依次按插件指定、碰撞前唯一静止方、稳定train ID字典序选择。两项不再待决定；继续Bukkit完整纵向迁移，再接四端联结，不切换模块。

## 已记录、后续待处理：Basic完整审查与首批修复（2026-10-12）

- 已完成96个production Kotlin、53个测试源文件、2个fixture的首轮静态审查；构建与两份Dokka输入由主会话审查。整模块七维报告见[Basic报告](./research/basic-module-review.md)，不能把此次完整静态覆盖写成模块无缺陷；此前暂缓Core并非用户批准，当前已恢复原顺序。
- 已提交：`0cde154e`完整审查记录；`5080c691` CR-037/038资源释放与换向生命周期修复；`5aa07434` CR-039恢复预校验修复。均已通过下述验证、待用户检查，不回滚既有修复。
- Basic剩余：CR-040坡道弧长、水平半径与重建后的分段映射；CR-041嵌套配置UI及快照语义；其余API/旧代码/文档/弱测试归既有台账。暂不实施，后续恢复Basic时再按优先级处理，不将其插入当前CR-030主线。
- 本轮源码修改：`PhysicalRouteResourceCompiler`用起点排序+前缀最大终点修正嵌套保留区间检索，保留exact identity与正常释放路径；`RouteResourceAdmissionEngine`拒绝暂停期间普通扩展。现有engine测试类增加两条行为回归，无新测试框架。
- `BasicInfrastructureSnapshot`在mutation前构造/验证WayPoint及解析metadata UUID，按UUID查重，复用解析结果写入。现有snapshot测试类新增两个手写JSON回归，失败三store为空、修正后同target恢复成功；新用例按registry→physics→binding→tick释放。既有该类旧fixture清理未混入本批。
- 首次loop回归失败来自测试actuator对空准备批抛异常，改成既有READY fixture后，未修改production的第二次执行确实在stationary epoch后`claimSnapshots.single()`失败：资源提前清空。修复后完整engine类通过；没有把fixture错误当作缺陷复现证据。
- 冻结源码执行`:Core:test :Basic:test :Basic:dokkaGeneratePublicationHtml --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，缓存`/workspace/.gradle`。`BUILD SUCCESSFUL`，1分14秒，16tasks全部执行。新鲜XML：Core55suites/349项、Basic59suites/389项，全部执行，零失败/error/skipped。Basic较基线增加4项；Dokka仍有既有warning，模块没有独立lint任务，scoped diff-check通过。没有部署/游戏/Folia/性能profiling。
- 独立只读复核：两组修复均无阻塞。资源检查确认prefix-max检索和候选过滤、exact身份、普通释放路径、暂停guard顺序与真实engine回归均正确；恢复检查确认当前fresh-world串行composition的预校验不遗漏后续setter输入失败，不承诺任意自定义回调副作用回滚。两份owner spec同步实际机制及复杂度，不增加版本或兼容路径。
- 提交拆为完整审查记录、资源生命周期修复、恢复边界修复，最后统一交用户检查。完整审查任务与Stage2不归档。
- 上下文manifest 26+26校验通过；准入规范超过32KiB注入上限，工具提示会截断，后续agent必须主动完整读取所需章节，不能依赖注入尾部包含算法/复杂度。该文档拆分属于维护事项，不混入本批源码修复。

## 当前实施批次：CR-030混合朝向与结构事件（2026-10-11已批准开始，2026-10-12恢复）

用户已批准开始实现此前确认的四端部联结、停车接触且无吸附、插件决策及联结/解编两阶段事件。按依赖分批验证与提交，不在此实现未来SimpleATO的时刻表联结业务，不改Seat或旧tick事件接线：

1. [x] 混合安装朝向基础：复用每车`CoreCar.isReversed`与整列`CoreTrain.isReversed`，安装朝向由两者异或派生，不新增平行运行时状态。贯通首尾bogie选择、加载校验、解编、运动和换向；在当前`SavedCarV1`增加默认false的`installedReversed`，列车保存时写入派生值。不改格式版本或增加旧reader，固定缺字段输入验证默认。独立检查、Core/Basic强制重跑及直接consumer验证通过，证据见文末。此批不宣称联结事务或新事件已接入。
2. [x] 两阶段结构事件及解编接入：公开稳定car ID与解编入口，提交前单向拒绝及两侧独立方向选择，完整成功后通知。方向设值冲突或提交前监听器异常拒绝提交；成功通知异常不回滚。相关API/事件标记unstable。必要回归与七模块门禁通过，证据见下方。
3. [ ] 四端部联结事务及碰撞结果消费：验证停车、真实连接路径与车钩接触，无位移准备与提交，处理extension、controller、ownership和完整frame；联结成功事件晚于提交，碰撞不再忽略异步结果。运行入口切换必须连同依赖完成，具体失败策略在实现前说明。

批次1、2已完成，下一批为批次3。用户已确认方向冲突拒绝、提交前监听器抛错不提交、成功通知抛错不回滚，并要求联结/解编API明确标记为unstable；owner合同见[train physics](../../spec/javarailways/core/train-physics.md)。批次2只接入解编事件，联结事件随批次3完整事务接入，不先交付无生产入口的声明。旧记录中“尚未批准开始”的表述仅代表当时边界，不再作为阻止已确认范围实现的依据。整体Core审查与Stage 2状态不因本批完成自动关闭。

### 批次3调整：每车JS与碰撞触发联结（2026-10-11）

- 用户已批准本批同时实施Bukkit每车JS改造与必要Core支持，并批准碰撞联结被拒绝/准备失败回退默认脱轨；主编组平局已于2026-10-12确认按稳定train ID字典序选择，具体规则见Core owner spec。
- [x] Core归属前置：新增`Car.train`只读身份查询；唯一原子owner替代单独stream UUID存储，渲染proof派生，构造成功/结构转移绑定，exact-instance CAS退休。复用6文件既有用例补断言，不增加测试数量，不涉及存档、JS或旧link算法。独立源码审查通过；定向测试32秒；Core/Basic/BukkitPlugin/TunnelBridgeBuilder完整回归及Core Dokka强制重跑通过，1126项（349/385/361/31），零失败/错误/跳过，2分21秒，41任务全部执行。cache使用`/workspace/.gradle`，offline/no-build-cache/no-parallel/rerun-tasks，禁用Kotlin incremental并使用in-process编译；既有构建警告仍存在。未部署，不能代替游戏验证。
- [x] 记录用户决定及[目标合同/伪代码](../../spec/bukkit-plugin/backend/car-behavior.md)：每节车厢独立JS，复用CarExtension和现有运行时代码，结构变更保留双方状态及已接受动作。此次仅文档，不宣称实现完成。
- [ ] Bukkit完整纵向迁移：JS归属、车厢action/sound、固定目标及物理门侧、逐车调度汇总、延迟效果完成与卸载销毁共同切换；不得提交“每车JS但延迟动作仍作用于整个列车”的半套运行时。复用现有Graal、CarExtension和解析，不另建引擎/loader。具体复用边界、调用端与验证见[实现研究](./research/car-behavior-implementation-plan.md)。
- 当前数据门禁：混合编组需要保留每车来源包的脚本快照引用，现有车厢模型快照不含behavior/actions。2026-10-12已按用户授权只读下载真实副本，99个文件复制前后及本地hash一致，12组current/.bak revision配对成立；6个world均无列车，本样本没有既存每车快照转换记录。来源版本、旧曲线异常及未完成的current codec/业务迁移验证见[存档研究](./research/dev-server-save-audit.md)。不要求远端临时造车，不用主编组脚本回退或FOLLOW_PACKAGE-only临时实现替代；非空车快照仍须固定fixture覆盖。
- [ ] 第三笔实现：collision专用四端联结事务及结果消费；捕获冻结前静止事实用于默认主编组，不增加主动Train.linkWith入口。主编组规则已冻结，失败回退默认脱轨已批准；此前“controller优先”和“调用列车优先”建议撤回。
- 验证要求：每笔独立审查，车辆行为批运行BukkitPlugin及其适配consumer回归；Core入口切换运行Core/Basic及七模块门禁。无需为本次纯文档重跑Gradle，不复用11a73930的1973项结果冒充本批测试。CR-030及整体审查保持开放。
- 本次文档独立审查通过，按发现明确每车须汇总所有已接受效果，不能在第一条延迟效果后提前完成；当前完成时点仍按既有最晚效果启动边界，不冒称完整动画结束。限定路径空白检查、local link target检查及20+20条上下文校验通过；无产品代码、存档或版本改动。

### 批次3源包解析前置与真实样本（2026-10-12）

- [x] 只读取得开发服真实存档及模型包；原压缩包及99项manifest保留于独立临时目录，未停服/触发保存/部署/远端写入。当前和备份均无列车；12组revision/epoch核对通过，不等于跨文件原子快照或current业务加载通过。详见[存档研究](./research/dev-server-save-audit.md)。旧outer/Basic归属转换及6条非法曲线记录独立于每车新增快照字段，继续作为旧服迁移缺口，不在runtime放宽校验。
- [x] 源包隔离代码：只改`ModelManager`三处非null包名查询，缺失不再跨包借用动作/JS；null全局查询不变，已pin快照路径不变。复用`ModelManagerReloadTest`的包fixture，新增两项有效回归；不改存档/版本、loader、其他resolver或JS生命周期。这是正式解析修复，不是临时测试版，也不宣称每车迁移完成。
- [x] 验证：独立trellis-check只读复核代码、consumer、测试、真实样本与文档通过；定向`ModelManagerReloadTest/PackageLoaderBehaviorTest/TrainPackageSnapshotTest`共5项通过（2分9秒），完整BukkitPlugin共363项通过（1分51秒），均零失败/错误/跳过、26任务全部执行。两次都使用`GRADLE_USER_HOME=/workspace/.gradle`、offline/no-build-cache/no-parallel/rerun-tasks，禁用Kotlin incremental并采用in-process编译；既有编译/Gradle弃用/依赖audit警告仍存在。限定路径diff空白检查、22+22条上下文校验通过；未做游戏验证或部署。
- 后续决定已于2026-10-12确认，见文首；此前未答复记录只描述当时状态。每车完整纵向迁移、CR-030与整体审查保持开放，不归档任务。

### 批次3前置：结构提交期间禁止新物理任务（2026-10-11）

- [x] 修复已确认的准入缺口：捕获/等待ticket并不能阻止timer随后开启新epoch。新增coordinator内部词法作用域，先关新epoch准入并捕获ticket，锁外等待和执行结构操作，finally释放临时准入；不复用或恢复永久shutdown状态。本批仅接入现有解编，旧联结入口不切换。
- 此前置不决定新的联结业务策略，不改存档、controller算法或所有维护入口。[当前联结研究](./research/core-link-current-contract.md)按`bd29118a`重新核实四端映射、extension union及Bukkit退休runtime/迟到动作处理；旧研究的已撤回建议不再用作实现依据。
- 历史待决清单已由上方批次3调整取代：当前已确认静止一方优先及每车JS保留，不再询问是否销毁/重建列车JS。主编组平局已于2026-10-12确认；collision联结失败回退默认脱轨、四端、停车接触、无吸附和事件两阶段已批准。
- 独立trellis-check复核通过；按发现修正并发测试失败清理顺序，以及文档对等待交错证明力度的表述。新增7项执行：作用域内timer/main wake阻断及恢复、action异常、shutdown/stall/close不复活、真实split成功/veto（含决策/extension准备/成功通知中的timer触发）。等待位于锁外由源码审查确认，不宣称测试确定性覆盖每次await阻塞交错；未新增压力/游戏验证。
- 验证：定向Coordinator/Split测试通过（45秒）；修正测试清理后强制重跑七模块，1973项（Core349、Basic385、SimpleATO352、SimpleATOBukkit8、SimpleSignals487、BukkitPlugin361、TunnelBridgeBuilder31），零失败/错误/跳过，3分48秒，54 tasks全执行。使用`GRADLE_USER_HOME=/workspace/.gradle`、offline/no-build-cache/no-parallel/rerun-tasks，禁用Kotlin incremental并采用in-process编译。既有依赖/弃用警告仍存在；本批无公开API或存档变化、未部署。上下文19+19条校验及限定路径`git diff --check`通过；CR-030及整个审查任务仍开放。

### 不稳定 API 标记复用（批次2后续，2026-10-11）

- 用户批准复用`org.jetbrains.annotations.ApiStatus.Experimental`，删除自定义`TrainStructureApi`与相应测试opt-in；仅标识接口可能变化，不新增Kotlin opt-in协议。Core显式`compileOnly`声明与根项目相同的JetBrains annotations版本，KDoc和owner spec同步；联结/解编行为、存档及版本不变。
- 下方批次2中关于自定义注解和opt-in warning的内容是`e459ae8e`的历史记录，当前规则由本节及[API边界](../../spec/javarailways/core/api-boundaries.md)取代。CR-030的联结事务缺口不因此关闭。
- 独立trellis-check只读复核通过：8处标记覆盖完整，仅删除对应3处测试opt-in，其余安全/线程opt-in保留；缓存注解的CLASS retention不引入运行时执行。强制执行`:Core:test :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test :Core:dokkaGeneratePublicationHtml`，1119项（342/385/361/31）零失败/错误/跳过，2分30秒/41 tasks全部执行，缓存及强制参数同批次2。Dokka产物显示新注解，Core JAR与Dokka产物不含旧注解；JetBrains annotations未打包进Core JAR。已有Dokka/编译告警仍存在。本批未新增测试，不改变原测试断言或使用上一批结果冒充重跑；未部署。上下文校验和限定本批路径的`git diff --check`通过。

### 批次2验证记录（历史：e459ae8e）

- 新增`TrainSplitEvent`、`TrainSplitSuccessEvent`及WARNING级`TrainStructureApi`。`Car.id`公开既有ID，`Train.splitBefore`无需实现类转换；旧联结、extension结构钩子和collision联结请求也标记unstable，不改变它们当前能力或存档版本。
- 解编等待既有physics ticket，经同步事件决策后预计算两侧几何/方向/frame，再prepare extension。完整提交后通知；总线仅新增内部汇总handler失败结果，普通publish语义不变，无每tick新扫描。
- 道岔回归实际检出旧端点代码向后跨轨时依赖反向选路，现沿原tail到head路径定位bogie与车端offset。保留模型/车厢几何不动；驻车frame比较使用数值相等，避免把正负零当作不同物理状态，未放宽几何容差。原非法编组回归仍验证拒绝及原状态不变，异常类型随更早的占用路径校验改为`CannotCreateTrainException`。
- 首次定向编译暴露公开`Car.id`缺失；随后测试发现上述道岔问题和signed-zero断言问题，均修复后重跑。最终定向50项零失败/错误/跳过，27秒/9 tasks。新增/扩展覆盖方向组合、同值重复、冲突/拒绝顺序、pre/post异常、extension失败、移动速度清零、混装保存恢复与非对称道岔。
- 冻结源码后七模块强制全量与Core Dokka通过，1966项（342/385/352/8/487/361/31），零失败/错误/跳过，2分56秒/55 tasks全部执行。命令/缓存参数同批次1；Core较此前增加18项展开用例。构建仍有Dokka/既有编译/依赖及Metaspace告警，新unstable接口的未opt-in调用也产生预期warning，不以构建成功宣称warning全清。
- task上下文校验与本批`git diff --check`通过。前期独立静态复核不能代替编译；末轮端点修复、数值断言和标记补齐由主agent复核及强制门禁验证。不宣称所有方向选择后的下一physics epoch、混装曲线/坡道、平台实体或游戏现场已验证。
- CR-030保持开放：四端联结事务、停车接触准入、联结事件和collision异步结果消费仍待批次3；未部署、未访问开发服、未做真实存档迁移。

### 批次1验证记录

- 生产改动仅`CoreCar.kt`、`CoreTrain.kt`、`TrainPersistence.kt`，新增`CoreTrainMixedInstallationTest.kt`的5项展开用例；无公开API、版本常量、controller算法或几何求解算法变更。安装朝向只增加持久字段，不增加运行时重复状态。
- 定向门禁：`:Core:test --tests '*CoreTrainMixedInstallationTest' --tests '*CoreTrainReversedPersistenceTest' --tests '*CoreTrainSplitTest'`，19项零失败/错误/跳过，29秒/9 tasks。
- 强制全量：`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :SimpleSignals:test :BukkitPlugin:test :TunnelBridgeBuilder:test :Core:dokkaGeneratePublicationHtml`；共1948项（324/385/352/8/487/361/31），零失败/错误/跳过，2分52秒/55 tasks全部执行。两轮均使用`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`。
- 独立trellis-check两次只读复核生产/测试和文档，未发现本批阻塞问题；`git diff --check`和task上下文校验通过。新测试源SHA256为`ae2c5f318197e6d16901a696d46231799160b751e7aca4939e52ef320f3893cd`。
- 本轮构建报告Dokka缺文档/链接、编译弃用等warning、WebUI依赖audit的5项告警及Kotlin将Graal POM当ZIP读取的warning，最终构建成功；未顺带修改依赖或构建设置。Gradle还提示当前daemon将在构建后因Metaspace压力退出，不将告警消失当作本批验收。
- 限制：新混装用例仅直线，未现场部署/游戏验证；四端联结、结构事件与未来SimpleATO业务仍未实现。Core与全项目审查任务保持in_progress。

此前完成批次（2026-10-11历史记录）：`TrainNameTest`、`CoreCarPositioningRegressionTest`的world清理已完成，原4项业务动作/断言保留，独立静态检查与Core/Basic全量门禁通过，详见文末。当时未批准的联结/结构事件范围已由上方当前批次中的后续确认取代；Seat、旧tick事件接线和真实存档迁移仍单独处理。

## 当前修复优先级（2026-10-11用户要求重新排序）

暂停逐个推进非阻塞测试维护，不再将全部维护项清零作为Core审查结束条件。按失败影响及实际生产可达性排序，设计未批准不等于缺陷已关闭：

1. 正确性/数据边界：CR-030联结方向、失败原子性与结果消费，以及CR-029正式codec的extension反射加载。四种稳定端部组合已获确认（包括A-A/B-B），已确认停车接触准入及取消吸附；先提出混合安装朝向的贯通实现方案，再完整切换事务与collision结果消费；坐标任务已闭合CR-023依赖，不再重复实施旧研究中的几何批次。registry替换须先明确映射与离线迁移验证，不新增猜测兼容或擅自操作开发服。
2. 未接线公开能力：CR-031 Core Seat递归缺陷（Core/Bukkit归属待确认）；旧事件保留并接入属于另行冻结阶段/线程合同的能力改进，不冒充已修复或无caller即可删除。
3. 验证与维护：对以上修复必要的回归随修复提交；其余fixture、接缝测试改写及文档/机械清理集中处理，不能长期挤占功能缺陷主线。Basic整模块审查仍未完成，独立跟踪。

此次优先级、四端部范围及停车接触准入确认不视为联结具体接口/字段、Seat、事件或迁移方案获批；当前最高优先级下一步是混合安装朝向及联结事务的贯通实现方案。运行时代码尚未修改。

2026-10-11后续补充：用户要求主编组/联结方向选择、两个解编结果分别选择方向、缺省自动选择及允许插件指定编组信息的联结/解编事件；随后明确调用方就是插件，联结决策事件允许兼容性拒绝，未来SimpleATO监听选择主编组并保存双方运营元数据，撤回调用方参数优先的提案。该类结构事件已进入目标范围，不与尚待设计的PreTick/PostTick/Renderable事件接线混淆。下一份实现方案须包含这些要求，默认选择及多监听器/异常处理尚待审查，详见[train physics](../../spec/javarailways/core/train-physics.md)。本轮仅文档变更。

后续确认：联结/解编均为提交前决策事件及提交后成功事件，拒绝不可撤销；解编也允许插件按固定单元等限制拒绝。未来SimpleATO的正式元数据更新放在成功事件，不以监听器优先级保证准入与运营变更顺序。尚待确定的是具体签名、缺省方向/主编组规则、多插件同时指定信息的冲突与监听异常处理；两阶段及拒绝规则本身不再列为待确认。运行时代码未改，本轮文档检查不替代实现回归。

## A. 完成审查

- [x] 记录当前 HEAD、根仓与 docs-site 脏状态；加载第一批涉及的 Basic/BukkitPlugin 规范。
- [x] 复核首批问题和文档/边界线索，建立带状态及证据的问题台账；具体修复前仍逐项复核。
- [ ] 检查废弃代码、模块归属与公共 API、重复模型、规范一致性、复杂度和安全兜底。
- [ ] 盘点四类文档与父任务清单，区分已实现、未实现和仅文档过期。
- [x] 输出已知问题、待核实/可延期项和八步计划，用户于 2026-10-09 批准；不把维护性线索直接定为游戏阻塞。

## B. 经批准后分批修复

### 全项目审查扩展（2026-10-09）

用户要求所有模块纳入；以下是后续审查顺序，不是批量修复授权。既有八步修复历史保留；第 7 步尚未完成，不能仅以 SimpleSignals 局部检查结案。

1. [x] 建立覆盖清单：按当前 settings.gradle 模块及仓库横向范围列出子系统；每批记录具体生产入口、关键函数/状态所有者、测试证据、未覆盖部分和检查深度。当前记录见 `research/coverage.md`，不按文件数或测试数计算完成率。
2. [x] Core 首轮完整审查：tick/物理状态发布及线程生命周期、曲线与弧长定位、轨道连接/道岔、列车物理解算、持久化与公共 API；核对不变量、重复状态、算法复杂度、四类文档和测试有效性。审查完成不等于缺陷已修复或模块通过验收。
   - [x] C1 runtime/epoch/publication/lifecycle及Basic observation消费边界静态审查；CR-013至CR-020已记录，未修代码。Core212项（6跳过）、Basic382项强制重跑零失败；新失败路径尚未定向复现，Core/Basic均未完成整模块审查。
   - [x] C2（2026-10-10）闭合完整 Core：136 个生产文件、49 个测试源文件和5个fixture，另查build、Dokka输入、14份Core规范及相关网页/导航。全部文件清单见`research/core-review-inventory.md`；七维报告见`research/core-module-review.md`。6条定向诊断已复现；Core/Basic强制重跑和Core Dokka成功，文档warning仍存在。当时CR-021至CR-034待修、运行时代码未改；后续CR-022处置另记于下。
3. [ ] Basic：从物理观察投影到拓扑/通用寻路，再检查基础设施资源、占用、编辑许可、world 生命周期和持久化；追踪 Core → Basic → consumer，确认权威归属及同 epoch/拓扑的一致性。
4. [ ] SimpleATO / SimpleATOBukkit：ATO 感知/制动与控制、运营方向/停车点、路线与时刻表、资源申请释放、控制权切换，以及平台适配；Dispatcher 大拆仍延期，不妨碍审查其正确性。
5. [ ] SimpleSignals：继续启动/世界加载卸载/停用、异步工作退出、拓扑失效与后台恢复、catalog/model 安装和编辑交错；此前事务与 Wand 检查仅是部分覆盖，CR-010/011/012待修复。
6. [ ] BukkitPlugin / BukkitPluginDev / TunnelBridgeBuilder：宿主服务与生命周期、平台线程/实体模型、命令/工具及扩展 consumer；RailWand 大拆延期，不免除其行为审查。
7. [ ] ConfigLib / Annotation / WebUI / 构建及工具：独立检查各自实现与测试，包含 WebUI 前后端、恢复数据、服务接口和打包依赖，不再仅检查 Stage 2 触及契约。
8. [ ] 横向收口：逐模块检查废弃代码、空源目录、风格/命名/i18n、过度兜底、测试质量及持久化版本；结合源码整理 Trellis/Dokka/docs-site/.codeabs 的职责与准确性。保留 docs-site 既有脏状态，不混入修改。
9. [ ] 汇总覆盖缺口、问题优先级和处置/延期；分别给出全项目审查状态与 Stage 2 游戏测试阻塞结论，不让非阻塞维护项自动阻止游戏测试。

下一完整审查模块仍为Basic；用户于2026-10-10要求下一步先改进Core，整模块审查暂在此处衔接，不以Core修复或跨层抽查宣称Basic完成。恢复审查时覆盖全部生产/测试/fixture/模块文档，重点核对Core→Basic→consumer同一权威来源。

### Core 用户确认与定向评估（2026-10-10）

- [x] 联结/解编确认保留为正式能力；CR-021/030不再作为删除候选，当前缺陷尚未修复。
- [x] 确认目前无仓库外插件；旧事件和座位按潜在扩展价值与状态所有权评估，而非直接按无caller删除。
- [x] 完成[旧事件](./research/core-event-api-assessment.md)与[Core/Bukkit座位](./research/core-seat-api-assessment.md)定向评估；事件初稿删除建议已按后续讨论撤回，不把评估完成记为缺陷修复。
- [x] 用户确认三个事件保留并考虑接入；已同步任务与Core规范，该决定记录批次未改公开接口或运行时代码。
- [ ] 用户检查Core Seat API收口建议，再按决定单独实施；本次未批准删除座位原型。
- [x] 记录开发服`192.168.1.230`的手动存档迁移要求；暂不在JAR打包自动迁移、不连接/修改远端，不据此升级schema。
- [ ] 涉及格式变更前取得真实一致存档副本，以离线dry-run/独立输出及current校验验证迁移；实际远端apply另行授权。

### 下一步：Core 改进批次

以下是建议执行顺序，非一批提交；每批开始前说明目的、方法和不涉及的内容，验证并独立提交，按下方最新授权统一交用户检查。

1. [x] 首批修CR-022：列车构造失败不遗留ACTIVE车端，前置身份校验并成对清理副作用；用真实失败路径断言world/rail索引不变，不改持久化格式。实现及验证见下，提交后等用户检查。
2. [ ] 修加载/恢复一致性（CR-023/027/028）及Core相关生命周期问题；涉及宿主或格式边界的修复单独划批，并先核对真实存档迁移要求。
3. [ ] 分批修LINE投影、碰撞容差与运动速度事实（CR-025/024/026），同步有价值但断言无效的测试。
4. [ ] 联结/解编先给出端部、方向和失败事务方案，再修CR-021/030及关联渲染换属主问题，不改变默认碰撞策略。CR-021解编已完成；CR-030的[联结方案](./research/core-link-transaction-plan.md)已提出，四端部范围及停车接触/无吸附规则已确认，混合安装朝向及事务细节待实现方案，运行时代码尚未修复。
5. [ ] 三种事件按保留目标设计接入：先给出阶段、payload、线程、生命周期及验证方案，再实施；复用现有事件/观察机制，不复制物理计算或状态。
6. [ ] 随修复同步受影响契约/KDoc，另批处理确认废路径和低质量测试；未确认的Core Seat处置不混入。

### 后续授权与当前批次（2026-10-10）

用户在CR-022提交后批准修复所有无争议问题，并明确允许连续小提交后统一检查；仍按具体证据修复，不把整个问题台账当作已冻结的技术设计。无争议的加载/几何/碰撞缺陷、生命周期遗漏和测试断言缺口可分批推进；公开接口、Seat去留、事件阶段及联结/解编事务方案先明确再实施。

- [ ] 当前批次CR-023（部分修复）：加载成功发布前验证同一tail→head路径上的rail身份、facing、bogie顺序/弧长间距及车端offset；保留精确合法摆位，不重新寻路/摆车。当前保存的bogie不能唯一恢复车体中心的求解锚点，合法曲线还原已有约10 mm中心差与约6.15 mm车钩残差，因此精确相邻车钩校验继续单列，不加宽5 mm容差掩盖问题。补固定损坏输入及有效reverse/curve/turnout回归；仅改Core内部加载校验/测试，不改JSON形状/版本，不接触远端。
  - [x] 同一路径、bogie及车端部分校验和14项正式回归。
  - [ ] 统一车体中心恢复规则后，补精确相邻车钩几何校验；不以本批路径校验结案CR-023。
- [x] 独立批次CR-024：collision查询复用既有容差扩展桶范围，保留exact overlap/candidate cap，补跨桶5mm内外的真实查询回归。
- [x] 独立批次CR-016：关闭终态和最终publication在既有coordinator状态边界复核，补in-flight worker及忽略中断的processor回归；不混入CR-014 quiesce接口。
- [x] 独立批次CR-025：最近点扫描真实piecewise-linear线段做投影，reverse复用canonical结果，复用既有frame插值；保留CurveException message，不加密stream、不新增索引或改raw-input合同。
- [x] 独立批次CR-027：load和prepared snapshot共用单端点严格解析，A/B均解析后才写；拒绝坏引用/远离端点/重复或超容量/非法active，保留合法null与顺序。运行时remove/structural-undo的合法fallback不变，不声称全world恢复事务已解决。
- [x] 独立批次CR-028：构造/decode共享一次raw-point输入校验，Vector3d缺字段拒绝；同步修Core LINE stream重复末点与Basic ConstraintSolver重复接头，不在decode去重兜底、不加热路径扫描。raw frame要求finite/可归一化/非平行，不额外禁止合法非正交输入；位置相同不等于弧长重复。
- [x] 独立批次CR-018：car render revision按opaque runtime train stream隔离；Core以原子owner token覆盖构造、link和remove，Bukkit region task不读取live train map/cars，换属主后接受new-low并拒绝delayed-old。direct commit不污染published gate，无持久化/版本或link policy变化。

### CR-018 修复与验证（2026-10-10）

- 实现：`CoreTrain`每实例生成process-local UUID；`CoreCar`原子保存当前owner。构造成功后绑定，link在几何更新成功后转移，`CoreWorld.removeTrain`使用exact instance删除并按exact token退休；重复移除旧实例不会删除同ID replacement或其mailbox frame。Bukkit只把UUID带到region task，先验证current published frame对象，再以owner token + per-stream revision gate拒绝迟到帧；direct commit保持独立同步路径。
- 回归：`CoreTrainLinkTest`覆盖构造/link/remove及同ID replacement exactness；`CarFrameRevisionGateTest`按真实old-high → link → new-low → delayed-old顺序证明旧stream不能切回。删除了只用注释声称direct commit行为的低质量测试。
- 最终稳定源码验证：`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :BukkitPlugin:test --offline --no-build-cache --no-parallel --rerun-tasks -Dkotlin.compiler.execution.strategy=in-process`成功，Core 296 tests（6 skipped）、BukkitPlugin 360 tests，零failure/error；随后单独`:BukkitPlugin:shadowJar`成功，`git diff --check`成功。一次在源码仍被审查修改时启动的组合test+shadowJar产生test-fixture `NoClassDefFoundError`，不计正式结果；稳定源码串行重跑已消除。
- 边界：没有Folia实体/客户端现场验证；不修link/split事务本身、不改变默认collision policy、不增加持久化字段/版本或公开add-on API。
- [x] 独立批次CR-032机械项：仅清空文件、无consumer helper/pool、未读临时list及废注释；逐项核实引用与持久化影响，事件/Seat/linksplit和需设计的旧factory/editor另列，不结案整个CR-032。
- [x] 独立批次CR-020局部：将匀速/脱轨/快照测试改为真实epoch及live mutation后的值断言，补对应world资源finally；不增加测试数量，不把6项disabled环线算绿色。其余reader时序、Koin fixture及Basic observation缺口另列，不因局部改写结案。
- [x] 独立批次CR-020 reader时序：两个拓扑事务reader回归捕获具体reader线程，并等待该线程确实进入`railTopologyLock`等待队列后才断言阻塞；删除固定sleep，finally释放writer及关闭future/executor/world。为避免反射，仅增加Core内部锁队列诊断，不成为公开API。
- [x] 独立批次CR-014：既有coordinator状态锁内永久停止新wake并捕获exact ticket，正常完成在途epoch之后才允许最终capture；Core边界连同宿主唯一卸载调用接入，补有界真实worker测试。不是close/abort，不新增超时、通用生命周期框架或正式Core API；7模块强制门禁通过，方法和限制见下。
- 后续单列CR-023剩余恢复一致性、CR-026速度语义、CR-015/017生命周期及CR-020/032局部测试/残留清理；CR-013失败清理见下。若核实需要新的产品选择或迁移要求，标记并跳过，不用猜测兜底替代。

### CR-024 修复与组合门禁（2026-10-10）

- collision查询与区间查询共享容差扩桶helper；索引写入footprint不变，exact过滤、排序与candidate cap不变，机制见[rail occupancy](../../spec/javarailways/core/rail-topology-and-occupancy.md#occupancyrelease与结果矩阵)。3项新增回归在旧实现全部失败；修复后该类11项全部通过。
- CR-024/016/023互不重叠源码冻结后串行验证：定向3类共49项全通过；随后强制重跑Core/Basic，15个Gradle任务执行，`BUILD SUCCESSFUL`（1m19s）。Core48 suites/242项（236执行、6跳过），Basic59 suites/382项全执行，零失败/错误。命令沿用下方非增量/in-process/offline门禁；本结果对应三批组合worktree，不宣称只含CR-024的孤立提交已单独重跑。
- 独立`trellis-check`复核实现/测试及契约；task manifest 11+11项、scoped whitespace检查通过。既有编译warning/daemon metaspace提示保留；无公开API、格式/版本、部署或游戏验证。CR-016/023分别提交并记录，不因组合门禁把CR-023精确车钩问题结案。

### CR-016 修复与验证（2026-10-10）

- 最终publication、metrics、latest-completed及ticket完成在原stateLock内检查RUNNING+exact ticket；外层只加CoreWorld内部read-lock scope，保持既有topology→coordinator顺序，不增加第二锁或公开API。失败/overload迟到返回不能覆盖CLOSED，具体合同见[runtime publication](../../spec/javarailways/core/runtime-publication.md)。
- 新增真实worker及消化中断的processor正常/overload共3项有界回归，旧实现全失败、修复后全通过（PhysicsCoordinatorTest共24项）；独立静态检查与上方CR-024/016/023冻结源码组合门禁通过。此提交只含CR-016代码、测试和受影响文档。
- close不是quiesce，也不回滚早先已发布的train frame或processor外部副作用；CR-014及宿主卸载/构造清理未在此修复。无格式/版本、事件接入、部署或游戏验证。

### CR-023 部分修复与剩余缺口（2026-10-10）

- 复用既有tail→head placementSegments，通过单调cursor验证exact rail/facing与bogie顺序，再以同一路径弧长校验车内间距及两端offset；替换错误的1 m欧氏弦长容差，校验后才恢复derived state。算法及未保证部分由[train physics](../../spec/javarailways/core/train-physics.md#存档摆位恢复与当前缺口)拥有，不重新摆车、不改Car/Train持久化字段/版本。
- 新增14项正式测试：1个固定损坏JSON、8种损坏DTO、直线/曲线AB/BA共4个合法控制及1个非对称道岔。坏输入断言具体异常原因和train ID，以及原world/rail/frame/mailbox保留；合法输入断言精确位置及方向。旧代码红灯阶段只运行其中最初11项，6种坏输入全部被接受；另有1个临时coupler诊断，后已移除。最后补的spacing及两端offset共3项没有单独旧代码红灯，不虚报。
- 定向14项与上方Core/Basic冻结源码组合门禁全通过，独立检查确认临时diagnostic/println已移除。仅此加载校验子项完成，CR-023继续部分修复/P1剩余项，不把通过测试误写为完整编组校验。
- 剩余依据：R=30 m、bogie弧长6 m、合法3车编组在restore前后centre偏移约10 mm，第一车钩残差约6.15 mm，已经超过5 mm；内部车两bogie共同前移1 m仍可能保持顺序和车端。当前SavedCar不包含求解锚点，而运动/换向与restore的centre恢复不同，先解决该一致性，不猜新增格式/宽容差方案。无远端、部署、游戏验证。

### CR-025 修复与下游组合门禁（2026-10-10）

- 直接扫描存储线段并投影，选定arc后仅调用一次既有frame插值；reverse委托canonical nearest，异常message正常传入Exception。算法/复杂度见[curve geometry](../../spec/javarailways/core/curve-geometry.md#33-几何最近点)，未改签名、格式、索引或raw-input校验。
- 6项新增回归涵盖稀疏LINE/三维DISCRETE/端点/零几何长度段/容差/诊断，旧实现5失败；既有reverse测试初次改25.4仍命中dense样本，红灯未失败，随后修正为两点LINE并验证位置/frame/view身份。最终两类13项全通过，不虚报稀疏reverse已经单独红灯。
- CR-025/027冻结源码组合门禁：`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :Core:dokkaGeneratePublicationHtml`，`GRADLE_USER_HOME=/workspace/.gradle`。36个任务全部执行，BUILD SUCCESSFUL（3m13s）；Core50 suites/271项（265执行、6跳过）、Basic59/382、SimpleATO46/351、Bukkit84/352，零失败/错误。结果对应两批组合源码，不是孤立提交测试。直接消费nearest的运营迁移/Wand/undo既有回归随之执行。
- 独立检查通过，manifest/whitespace通过。Dokka仍有既有缺KDoc/declare链接warning，构建链WebUI报告npm依赖漏洞与大bundle提示，另属后续全模块审查，未执行audit fix/升级依赖或声称全仓合规。无部署/游戏验证；CR-027单独提交。

### CR-027 修复与验证（2026-10-10）

- 复用prepared原有规则抽出一个内部endpoint decoder；CoreRail的A/B全部decode并resolve之后才写live连接，prepared仍保留全图对称检查，load仍通过TopologyStateBuilder。具体输入/错误规则由[Core persistence](../../spec/javarailways/core/persistence.md)拥有，未引入通用恢复框架、格式/版本或runtime compatibility。
- 新增23个参数化执行：8种坏输入分别经过load/prepared、4种端点方向、2种顺序/null/第二支选择、1种B端坏输入时双端保持；既有缺失active-target的损坏fixture改为拒绝并补资源关闭，不削弱live remove/undo有效fallback回归。旧生产代码11项失败（8种bad load、null恢复、双端保持、既有orphan），prepared严格控制全通过。绿灯各项全通过。
- 独立检查与上方CR-025/027冻结源码四模块/Dokka组合门禁通过。无全world原子load或DI资源清理保证，无开发服数据迁移/部署/游戏验证；坏旧开发数据如实际存在，应离线审计后手动修正，不恢复静默丢引用。

### CR-028 修复与组合验证（2026-10-10）

- 生产校验改动前执行新Core input类，实际15项中13失败、2项有效控制通过；该命令未加`--continue`，Core失败后Basic test未执行，不能把Basic compile误记成test通过。随后单独执行Basic ConstraintSolver整类，12项中新增junction回归失败、11项既有测试通过。
- 实现只改Core构造/decoder/LINE stream及Basic共享接头生成；测试保留红灯输入。曲线复制后一次验证≥2、精确零起点、finite严格递增arc、finite xyz及可归一化非平行frame；不要求原始frame已unit/正交，不以位置去重，不加查询热路径检查，不改格式/版本。机制由[Core geometry](../../spec/javarailways/core/curve-geometry.md)与[Basic authoring](../../spec/javarailways/basic/curve-authoring.md)拥有。
- CR-028/032机械项/020局部测试源码冻结后强制重跑：`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process :Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :SimpleSignals:test :TunnelBridgeBuilder:test`，cache为`/workspace/.gradle`。首次停在测试编译，快照测试错误使用接口不存在的`forward`；改用既有Core端部location成员后重新冻结并完整重跑，BUILD SUCCESSFUL（3m10s），54任务全部执行。
- XML：Core51 suites/286项（280执行、6跳过）、Basic59/383、SimpleATO46/351、SimpleATOBukkit5/8、Bukkit84/352、SimpleSignals47/485、Tunnel8/31，零失败/错误。共1896项、1890执行；CR-028新增16项全部通过。结果属于三批组合源码，不冒充孤立commit gate；后续CR-014不在此结果中。
- 独立检查确认规则/输入复制/共享decoder、真实junction与机械删除范围，manifest 13+13及whitespace通过。既有deprecated/unchecked、Gradle metaspace、Graal POM被当zip及WebUI npm漏洞/bundle提示未在此修复；未执行WebUI测试、Dokka或游戏验证。严格loader可能拒绝旧重复arc数据，真实开发服样本与离线迁移仍未验证，不添加runtime兼容或声称可以直接部署。

### CR-032 机械清理与验证（2026-10-10）

- 删除5个确认无作用文件：3个空文件/空类的rail/persistence空壳、未使用BinarySearch、空DummyAsset；另4文件只移除Quaternion pool、forwardPositive未读list、CoreWorld未用signals constructor字段及CoreCar废SavedCar注释。向量池、CoreRail实际Signal、current SavedCarV1/CarConfigV1不变；不清理用户生成物或空目录占位。
- 全仓生产/测试/资源配置引用核对及独立检查通过；5项删除均可从Git恢复。CoreWorld implementation constructor参数去掉unused值，仓库调用使用保留的secondary constructor，正式World API、持久化形状及版本不变。
- 绿色证据复用上方CR-028/032/020冻结源码7模块强制组合门禁（54任务、1896项/6跳过、零失败/错误），未为死代码删除新增无行为测试。该门禁不包括随后CR-014改动；没有部署/游戏验证。
- 旧RailTopologyEditor/factory构建链及其专用测试、其余可维护性候选仍待分批处理；Core事件保留、Seat处置与联结/解编设计不在删除范围。导航.codeabs漂移仍属CR-002/033，未改docs-site原有脏状态。

### CR-032 废RailTopologyEditor/TrainFactory路径清理（2026-10-10）

- 全仓核对production、test、resource、反射、序列化与文档引用后，删除旧
  `RailTopologyEditor.splitWithPreparedRails/mergeWithPreparedRail`及它们的两项专用测试。保留RailWand真实消费的
  `SplitResult/mergeEligibilityError`；后者的branch拒绝回归改为直接构造线路拓扑，不再借助已删的split执行方法。
  正式split/merge仍由RailWand在单一outer Core transaction内安装prepared rails、应用exact connection plan并同步metadata/undo。
- 删除零production/serialization consumer的`TrainFactory`、`TrainBuildDependency/CarBuildDependency`、
  `TrainConfigV1/BogieConfigV1`、它们的`TrainConfig/CarConfig/BogieConfig`alias及反射填prototype map的
  `TrainConfigurationLoadTest`。保留current `SavedTrainV1/SavedCarV1/SavedBogieV1/CarConfigV1`全部字段、
  extension payload、serializer、`SavedCarV1.load -> CarConfigV1.buildCar`恢复链和既有current fixtures。这是删除未接线并行实现，
  不是根据测试数量删测试。
- 同步Core persistence/topology与Bukkit interaction权威契约；历史审查记录保留原问题并增加完成状态。
  无持久化shape/schema/version/runtime compatibility改动，不触及link/splitBefore、Seat、事件或其他CR。
- 现有回归分别覆盖prepared explicit-connection plan成功/stale、Core transaction rollback与独立merge eligibility；尚无
  RailWand完整split/merge操作跨Core topology、Basic metadata、render sidecar、玩家状态及undo的成功/拒绝/rollback集成测试。
  被删除的旧editor测试只验证废执行器，不能作为现行管线覆盖；该缺口继续留在CR-020/033，不以本次死代码清理结案。
- 冻结源码使用`GRADLE_USER_HOME=/workspace/.gradle`与`--offline --no-build-cache --no-parallel --rerun-tasks
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test :BukkitPlugin:compileKotlin
  :Core:dokkaGeneratePublicationHtml :Core:jar`，21个task全部执行并`BUILD SUCCESSFUL`（1m39s）。Core 308项（302执行、6个既有disabled）、
  Basic 384项全执行，零failure/error；BukkitPlugin及其SimpleATO/SimpleATOBukkit生产依赖链编译成功，Core Dokka成功。
  生产JAR直接检查确认废factory/dependency/config/editor helper类消失，上述current persistence类及
  `RailTopologyEditor$SplitResult`仍存在。保留既有Dokka未文档化/link、Kotlin deprecated/unchecked和Gradle metaspace警告；未部署或游戏验证。

### CR-020 局部测试改写与验证（2026-10-10）

- 匀速原用例改为100个有界等待的真实50ms simulation epoch，每轮独立计算expected位移，并检查head/tail/每车公开位置及immutable frame、controller/train/frame速度与epoch；不再以两个local累加值自证。
- 脱轨用例沿100m尽头线路实际执行2000m/s×50ms，经过BogieException→CoreTrain→coordinator发布/排队；检查未drain时0、真实main drain后1、重复drain/后续idle epoch仍1，并保留reason/epoch/controller移除及原几何。listener只记录，assertion在外部，避免event bus吞测试错误。
- 快照用例实际改变live位置/lifecycle及真实30m跨段摆位，检查旧value隔离；publication用例本批仅补finally关闭，fixture KDoc改为真实NoOp。五文件共18项（12执行、6个原disabled环线），数量未增加；没有新增runtime接口/测试替代实现。
- 独立检查与上方CR-028/032/020组合门禁通过；首次测试编译错误已用现有`train.head.location.forward`修正后完整重跑，不新增extension/cast。CR-014随后改动不在本门禁内。
- CR-020仍部分完成：本批结束时reader sleep/未证明进入读取路径、coordinator无界barrier、Koin fixture、Basic observation collection/bodyTraversal及publication reverse/derail immutable-frame断言继续跟踪；随后CR-014处理了coordinator的2处无界barrier，其余缺口不因finally结案。无部署/游戏验证。

### CR-020 拓扑reader确定性时序（2026-10-10）

- 两个原回归不再用`Thread.sleep(50)`猜reader是否已被调度。测试使用独立双线程executor，writer在持有拓扑写锁且完成结构修改后发信号；reader先发布自身具体`Thread`，随后调用真实`publishWorldFrame`或`allRails`读取。主测试通过Core内部`ReentrantReadWriteLock.hasQueuedThread(readerThread)`诊断等待该线程确实进入同一锁的等待队列，再断言future未完成并释放writer。
- `CoreWorld.isQueuedForRailTopologyAccess`保持`internal`及`@JvmSynthetic`，只暴露锁队列事实，不提供锁控制、runtime fallback或公共addon合同；选择该最小seam是为了避免反射和任意延时。writer release、future有界等待、executor关闭及world资源关闭都在`finally`兜底。
- 定向强制重跑`CoreWorldRailTopologyTransactionTest`共22项、零跳过/失败/错误；独立检查又补强提交后的revision/具体rail ID inventory断言，并让writer、reader、executor及每个world的coordinator/tick manager清理失败可见且不覆盖主断言失败。冻结源码完整`:Core:test`执行53个suite/308项（302执行、6个既有disabled环线），零失败/错误，9个Gradle task全部执行并`BUILD SUCCESSFUL`。task manifest 15+15及scoped whitespace检查通过；既有4处`RailLocationPersistTest`冗余cast和Gradle deprecated warning保留。CR-020其余Koin/Basic collection/bodyTraversal及publication reverse/derail immutable-frame缺口仍开放。

### CR-020 道岔与多车测试有效性（2026-10-10）

- 非对称道岔三项运动回归不再在manual move后读取未提交的旧`isDerailed`。fixture使用关闭automatic fallback的真实`PhysicsCoordinator`；单步及80步场景均有界等待exact ticket，随后检查同一frame的epoch、车头/车尾rail/facing/AB弧长、固定实际位移、旧反向active path缺失时仍使用列车自身tail path、已发布车尾重锚及最终未脱轨。原拓扑/存档用例也补paired world cleanup，fixture构造失败会关闭已创建owner；测试体已有失败时，清理错误只作为suppressed附着。
- 多车诊断删除对`CoreCar._position`/`CommitBuffer`的反射。2 m×2明确fixture先断言`setLocation`在publication前不可见，再以maintenance commit后的`head=80.0`、`tail=75.4`、car center `79.0/76.4`等独立固定值检查实际摆位；随后真实10 m/s×50 ms epoch检查全部公开状态前移0.5 m且2.6 m车心距/4.6 m编组端距保持。默认bogie offset和端部offset直接检查`±0.6/0.4 m`，不再从同一生产公式生成expected。
- 两文件仍为原11项测试，没有加数量填充；focused非增量强制执行11项全部通过。冻结源码再以`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`完整执行`:Core:test :Basic:test`，15个task全部执行并`BUILD SUCCESSFUL`（46s）：Core 308项（302执行、6个既有disabled环线）、Basic 384项全执行，零失败/错误。task manifest 15+15和scoped whitespace检查通过；保留既有Core冗余cast、Basic unchecked/safe-call/non-null assertion、Gradle deprecated及daemon metaspace warning。CR-020继续保持partial，未覆盖Koin/Basic collection/bodyTraversal及publication reverse/derail immutable-frame缺口；未部署或游戏验证。

### CR-020 换向脱轨不可变帧发布边界（2026-10-10）

- 在`CoreTrainPublicationBoundaryTest`原reverse/derail两项中补齐三个独立阶段：physics mutation未提交、`publishPhysicsState`只翻转live buffer、`publishFrame`替换atomic immutable frame。前两步保持旧frame identity；第三步验证revision精确加一、传入epoch、rail/facing/固定20 m与10 m端部位置、反向body traversal或脱轨标记。后续`commit`同时推进state/frame且保留最近epoch，旧帧继续保留原值。
- 换向保留车厢稳定物理方向和car/bogie位置；reason只属于live train而未在当前frame建字段，测试不虚构frame reason。直接调用既有内部发布原语，不伪造coordinator ticket；真实coordinator调用、事件一次性排队与速度清理由已有集成回归覆盖。原6项测试数量不变，无production/API/schema改动；两项paired cleanup不覆盖主失败。
- 首轮focused 6项有1失败：新断言对整car frame逐字段比较，把fixture原尾bogie的`-0.0`与零速清理后的`+0.0`认作差异。改成独立固定位置、物理方向及带容差零速度断言，保留旧frame和边界断言，不改production。
- 修正后focused 6项全执行通过（29 s，9个task全部执行）；冻结源码用`GRADLE_USER_HOME=/workspace/.gradle`与`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`完整执行`:Core:test :Basic:test`，`BUILD SUCCESSFUL`（53 s，15个task全部执行）。Core 53 suites/308项（302执行、6个既有disabled环线）、Basic 59 suites/384项全执行，零失败/错误。task manifest 15+15及scoped diff check通过；保留既有Core冗余cast、Basic unchecked/safe-call/non-null assertion与Gradle deprecated warning。CR-020保持partial，Koin fixture、Basic observation collection/bodyTraversal及RailWand完整split/merge事务缺口继续开放，无部署/游戏验证。

### CR-020 Basic observation集合与车身路径（2026-10-10）

- 原BA投影验证独立固定40/50 m canonical AB弧长及BA tail→head非空body；原跨轨用例验证head、tail、body各自的有序rail身份/facing/独立几何弧长，并保留与Core原列表完全相等的投影断言。首轮focused 7项有1失败：保守车尾为39.996430664 m，与理想40 m差3.57 mm，属于既有5 mm placement tolerance；几何预期使用该现有容差，投影相等仍精确，不改production。
- 原train排序/复制用例改为直接调用Basic `RailwayObservation.create`后修改caller train集合，避免只验证Core已做的复制。新增一项合并回归直接向Basic `RailwayTrainObservation.create`传入五项非空caller-owned mutable集合，清空后检查occupiedIntervals/bodyTraversal/sweptSpans/headTraversal/tailTraversal的独立完整预期与不可修改输出；没有按字段复制五项测试。类内world均经本地Unit fixture配对关闭physics/tick，主错误及cleanup suppressed保留。
- 修正后focused 7项全执行通过（43 s，12个task全部执行）；冻结源码使用`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`完整运行`:Core:test :Basic:test`，`BUILD SUCCESSFUL`（51 s，15个task全部执行）。Core 53 suites/308项（302执行、6项既有disabled）、Basic 59 suites/385项全执行，零失败/错误；test source SHA-256为`dc6c5273f608a39e95c2e0a3f0dc7a13ec6e576c1c19971732ade23a9d02849f`且前后相同。任务manifest 15+15和scoped diff check通过；既有冗余cast/unchecked/safe-call/non-null assertion及Gradle deprecated warning保留。
- 独立检查确认五项非空mutable集合直接进入Basic构造边界、清空caller集合后的完整值及只读断言有效；BA body与多轨路径有独立几何预期，Core投影仍精确相等。检查修正Core testing规范中过时的Basic缺口描述，并再次强制执行focused 7项（43 s，12个task全部执行），零失败/跳过；test source SHA-256保持不变，task manifest 15+15及scoped diff check通过。没有代码修正，不重复已通过的冻结全量gate。
- 只关闭CR-020的Basic observation collection/bodyTraversal子项；Koin fixture及RailWand完整split/merge事务集成缺口继续开放，Basic整模块审查未完成。无production/API/schema改动、部署或游戏验证。

### CR-014 停止新物理工作后保存与验证（2026-10-10）

- `beginShutdown()`在既有stateLock内永久关闭wake入口并返回exact in-flight/latest-completed ticket；不等待、不取消、不改变RUNNING，使已开始epoch可以正常完成。MAIN/SIMULATION入口拒绝新请求，timer在同一锁内检查资格并分配ticket，关闭入口后静默返回；不能通过start重新开放。Bukkit唯一正常卸载入口改为成功await该ticket后capture/save，最后仍close；不引入第二协调器、超时或公开World接口。
- 补3个逻辑测试/4次执行：真实阻塞worker返回同一ticket并正常完成0.5 m运动，world/train/occupancy同epoch；已启动timer正控制与未启动bootstrap均永久停wake；在途失败保持exceptional/STALLED及前次frame/metrics，不用旧成功ticket伪装安全保存。既有2处无界barrier同步改为5 s有界断言，不为超时单独堆测试。没有单独旧代码红灯执行，不虚报。
- 源码冻结后强制运行上方相同7模块命令，`GRADLE_USER_HOME=/workspace/.gradle`，`--offline --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`；BUILD SUCCESSFUL（3m19s），54任务全部执行。XML为Core51 suites/290项（284执行、6跳过）、Basic59/383、SimpleATO46/351、SimpleATOBukkit5/8、Bukkit84/352、SimpleSignals47/485、Tunnel8/31；共300 suites/1900项、1894执行，零失败/错误，coordinator类28项全通过。
- 契约同步Core publication/DI生命周期及Bukkit生命周期；停止的仅是物理wake，不是所有编辑/通用tick/region task。Core真实并发边界已测，宿主接入经过源码追踪及既有消费测试，但未直接执行真实多owner final-save I/O或Folia场景；异常ticket也不证明所有worker已退出。CR-013异常清理、CR-015部分构造、CR-017 DI释放仍独立待处理，close仍是abort而非quiesce。
- 独立检查无本批阻塞项：3条源码hash与冻结值相同，复核7模块XML/28项coordinator回归及10条scoped diff的契约和状态一致性；manifest 15+15和whitespace检查通过。未为复核启动额外构建或修改源码。
- 未修改schema/version、事件/Seat/联结接口或构建配置，未运行Dokka/WebUI测试、部署、开发服迁移或游戏验证；既有编译/依赖warning保留。前轮成功Gradle daemon生成的667 MiB heap dump已非覆盖式移至ignored `.local/gradle-diagnostics/java_pid576750.hprof`保留，未读内容、未提交、未删除。

### CR-013 卸载失败清理（2026-10-10）

- 正常入口仍先标记unloading并撤UUID lookup；卸载开始时捕获exact owner world，保存及每项teardown通过同一局部顺序执行器分别尝试。首个异常保持主异常，后续异常按发生顺序加入suppressed；无论capture/write、render/tick或marker失败，最后都按exact identity移除旧owner map，避免旧world继续进入主tick或挡住同UUID重载。
- 生产保存仍是`beginShutdown`→成功await exact ticket→capture→等待旧periodic write→写最终split snapshot；没有把失败改为成功、自动重试或空存档。topology订阅、speed processor、三个宿主service、physics、render、tick及异常标记均保持原owner，只将原本嵌套finally改为逐项失败隔离。内部重载仅是测试seam，不新增正式API或通用lifecycle框架。
- 新增2项正式回归：持久化步骤失败作为主异常，同时让render/tick/marker失败并核对suppressed顺序；teardown自身首先失败时，后续tick/marker仍执行，并在final map remove前重绑相同Bukkit key以验证exact identity不会删除replacement。capture与write在卸载协调器中进入同一个持久化回调，失败后的控制流完全相同，因此不复制两项只改异常文本的低质量测试；两种生产异常均由这一边界覆盖。两项都断言physics关闭及对应map/lookup结果。
- 最终源码上完整运行`:BukkitPlugin:test`，BUILD SUCCESSFUL（1m40s），26个task中10个执行；XML为85 suites/354项全部执行，零跳过/失败/错误。最终生产源码同时通过`:BukkitPlugin:shadowJar`，无测试替代运行时代码。scoped whitespace、任务manifest 15+15及Trellis契约复核通过；静态复核合并了上面无独立覆盖的重复参数执行，未发现本批阻塞项。
- 既有Basic unchecked/safe-call、Bukkit deprecated/多余断言、JDK native/Unsafe、Gradle deprecated及WebUI 5项依赖漏洞/大bundle warning未在本批处理；没有Dokka、Folia/客户端、真实磁盘失败或游戏验证，不把离线门禁写成现场生命周期验证。
- 不处理CR-015 factory尚未返回world时的partial-init资源、CR-017 Koin child scope、Folia region task完成或远端数据；无持久化shape/version、事件、Seat、联结或构建配置变化。

### CR-015 构造失败资源归还（2026-10-10）

- `RailWorldFactory`新增窄范围LIFO构造owner：每成功取得tick、physics、render就登记其close；全部restore/materialize/render metadata完成才release并返回。失败保持原异常，按render→physics→tick继续清理，清理错误进入suppressed；调用方无需也不能清理未返回的world。
- `RenderChunkManager`补齐对象尚未返回时的责任：listener注册或initial-player refresh失败会立即close。close逐player隔离清理错误并保证最终尝试listener注销，首错保持主异常；注销函数仅作为internal构造注入点供正式回归观察，不形成add-on API。
- 新增`WorldConstructionResourcesTest`三项与`RenderChunkManagerConstructionTest`两项，验证逆序、异常身份/suppressed、成功移交不误关，以及listener已经注册后的refresh失败撤销。无持久化/version、运行期兼容、自动修复、重试或Koin scope变更；CR-017仍开放。
- 强制关闭增量编译后，5项定向回归全部通过；最终同一源码执行`:BukkitPlugin:test :BukkitPlugin:shadowJar`，27个task全部执行并`BUILD SUCCESSFUL`（2m08s）。XML为87 suites/359项全部执行、零跳过/失败/错误。既有Bukkit deprecated/unchecked、JDK native/Unsafe、Gradle deprecated及WebUI 5项依赖漏洞/大bundle warning保留；未部署、连接开发服或做Folia/客户端验证。
- inline `trellis-check`复核production owner链、构造器不可见窗口、异常优先级、正式测试断言和两份lifecycle契约；task manifest仍为15+15项，scoped whitespace通过。本批没有扩大到CR-017或全仓文档整理。

### CR-017 World context registration释放（2026-10-10）

- `WorldParentContext.attachWorld`改为返回窄范围幂等`WorldContextRegistration`。Koin child以world runtime ID创建、link parent并declare world；attach内部失败自行关闭partial scope。`CoreWorld`只持有/释放该binding，不实现通用`AutoCloseable`，也不越权关闭caller-owned physics/tick。
- Core constructor在coordinator attach失败时回滚binding；`CoreWorld.load`在rail/train恢复失败、world尚未返回时同样回滚并保留主异常。Bukkit factory取得world后立即把binding加入LIFO，失败顺序为render→binding→physics→tick；manager发布失败和正常unload均在service/physics/render停止使用后释放binding，再关闭tick。
- 独立检查补出并修复既有owner索引漏洞：manager在`loadedWorlds.publish`后失败时，旧代码只撤UUID lookup、不撤owner map。现在按exact world identity同时删除两者，迟到清理不会删除同UUID replacement；相关registry测试改为直接验证两个索引和幂等/replacement行为。
- 新增`CoreWorldRuntimeBindingTest`与`KoinWorldParentContextTest`，扩充factory/unload和真实Koin persistence清理测试。无持久化shape/version、兼容路径、部署或远端操作。
- 实现代理的5类定向回归及检查代理的非增量复跑均通过。最终源码强制执行`:Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :BukkitPlugin:shadowJar`，36个task全部执行并`BUILD SUCCESSFUL`（2m41s）；XML合计245 suites/1387项，其中1381执行、6项既有Core跳过，零失败/错误。task manifest 15+15、scoped whitespace通过。
- 独立检查唯一新增发现是上面的owner map撤销缺口，修复后由最终全量门禁覆盖；其余API最小性、Koin create/link/declare/close、constructor/load回滚、factory/register/unload顺序、主异常/suppressed及测试质量均无阻塞项。既有编译deprecated/unchecked、Gradle heap/metaspace、JDK native/Unsafe、WebUI 5项依赖漏洞与大bundle warning保留；未运行游戏/Folia/客户端验证。

### CR-026 Core同epoch速度传播与验证（2026-10-10）

- 保留公开`Train.moveTrainForward(deltaS)`签名；`TrainController`仅对CoreTrain使用内部distance+post-command speed seam。head bogie在移动后按当前train-local rail tangent注入m/s速度，随后沿car刚体和整列连接点传播；公开/manual distance-only路径不从距离猜速度。stationary、collision freeze、derail/运动失败和只换向epoch都清零整列active motion，再由既有epoch barrier连同geometry发布。
- 修复传播时暴露的两个原逻辑错误：Kotlin infix `cross`原表达式实际按错误优先级结合，car中心速度在直线退化为0；角速度原用单位方向丢失bogie杆长。现在显式括号并用实际head→tail bogie chord计算`r × deltaV / |r|²`，保持rad/s单位。机制/限制同步到[train physics](../../spec/javarailways/core/train-physics.md)和[controller](../../spec/javarailways/core/train-controller.md)；无公开API、Bukkit补算、持久化或版本变化。
- `TrainUniformMotionTest`在既有真实100×50 ms用例中逐车/逐bogie检查immutable frame速度（20 m/s对应每epoch 1 m），另用真实coordinator依次验证10 m/s×50 ms=0.5 m、停车清除旧速度、只换向epoch空traversal/零速度及下一epoch沿反向tangent发布-10 m/s。不是live field或local accumulator替代。
- 第一次完整Core运行暴露zero-speed清理仍经过重合bogie角速度公式而生成NaN；改为零速直接清理全部car/bogie motion后重跑。最终`:Core:test` BUILD SUCCESSFUL：53 suites/297项，291执行、6个既有disabled环线，零失败/错误。focused motion/publication类14项（8执行、6跳过）亦通过；task manifest与`git diff --check`通过。尚未运行下游/Bukkit、Dokka、Folia/客户端或游戏验证，等待独立检查决定最终组合门禁。

独立检查补充：原实现只覆盖`TrainController`基类tick；审查确认Basic `RealisticManualController`与SimpleATO `AbstractATO`覆盖tick并继续调用公开distance-only入口，因此仍会按合同发布0，ATO freeze还需显式清除旧motion。该跨模块/受保护API改动不在本次Core检查代理授权内，CR-026在补齐这些production caller前不能标记全量完成。Core范围内已修正：显式placement/maintenance/restore、主线程reverse/derail及无controller epoch清除旧速度；曲线刚体约束统一使用实际bogie chord；为公开frame velocity补充m/s单位KDoc。新增真实coordinator回归覆盖`deltaS=0 + 非零末速度`、controller解除后的清零、曲线切线/角速度/刚体关系、distance-only placement及collision suppression。最终完整门禁记录由主代理在跨模块补齐后更新。

跨模块补齐：`moveControlledTrain`提升为有KDoc的protected subclass seam；`RealisticManualController`提交同一个dynamics result的distance/speed，`AbstractATO`提交同一个actuator step并在预先Stall或异常时显式提交0/0 freeze。`WorldManager`不再携带冗余train speed，`CarExtension`把car/bogie各自frame的world-space m/s统一除20后传给Bukkit模型；车体不再由train speed×模型direction重建另一份速度事实。新增Basic真实manual epoch、SimpleATO真实actuate→exception freeze epoch和Bukkit完整car frame conversion回归。四模块focused命令BUILD SUCCESSFUL：Core 2类38项（32执行、6个既有disabled）、Basic 11项、SimpleATO 1项、Bukkit 2项，共52项、46执行、零失败/错误。完整模块门禁、Dokka及独立检查仍由主代理决定；无游戏/Folia/客户端验证。

最终复核先发现SimpleATO完整套件47项失败：ATO demand和actuator step正确，但motion-only publication用CoreCar/CoreBogie原先共享的dirty标记同时翻转未写入的geometry CommitBuffer槽，bogie位置回退，下一次几何搜索以`BogieException`脱轨。修复为car geometry/motion及bogie location/velocity分别维护dirty状态；几何先完整求解，再由求解后轨道切线生成整列速度，两类状态仍在同一epoch barrier发布。Core回归同时断言零位移非零末速度epoch不改变任何car/bogie frame位置；此前47项SimpleATO失败在修复后消失。

冻结源码最终命令使用`GRADLE_USER_HOME=/workspace/.gradle`和`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，执行`:Core:test :Basic:test :SimpleATO:test :SimpleATOBukkit:test :BukkitPlugin:test :Core:dokkaGeneratePublicationHtml :BukkitPlugin:shadowJar`，39个task全部执行并`BUILD SUCCESSFUL`（3m39s）。XML合计Core 53 suites/299项（293执行、6个既有disabled）、Basic 59/384、SimpleATO 47/352、SimpleATOBukkit 5/8、BukkitPlugin 89/361，共253 suites/1404项、1398执行，零失败/错误；Dokka与shadow JAR成功。保留既有Dokka未文档化/链接、编译deprecated/unchecked、Gradle metaspace/JDK探测、WebUI 5项依赖漏洞和大bundle warning；未运行Folia、客户端实体运动或游戏现场验证。

### CR-019 无行为拓扑字段清理（2026-10-10）

- `RouteGrantTailRelease`删除未读取的`topologyRevision`、只用于该字段的非负校验和从未产生的`STALE_TOPOLOGY`失败种类；两个生产构造点与测试fixture不再传入该值。KDoc只声明真实执行的world、单调physics epoch、train identity、冻结路线游标和claim release职责。
- 不修改`RailwayStateStamp`、`RouteSnapshot`、准入验证、物理编译、route-bound catalog、registry或受影响路线失效路径。tail release继续消费任意更新topology revision下的同world后续epoch；相关拓扑事务若实际影响路线，仍由准入engine的依赖失效机制处理。
- 既有集成回归`unrelated transaction preserves pending grant tail release and extension of older frozen route`继续覆盖旧冻结路线在无关topology revision提升后完成READY、扩展并释放资源。本批无公开API、持久化、版本、抽象或产品行为变化。
- 实现阶段非增量定向执行`RouteGrantTailReleaseTest` 5项和`RouteResourceAdmissionEngineTest` 43项，48项全部通过。独立检查随后在最终源码上用`--rerun-tasks`完整执行`:Basic:test`，59个suite/383项全部执行，零跳过/失败/错误，12个Gradle task全部执行并`BUILD SUCCESSFUL`（22s）；`compileKotlin`/`compileTestKotlin`、Trellis task manifest与scoped whitespace检查通过。既有Basic unchecked/冗余safe-call、测试冗余non-null assertion、JDK toolchain探测和Gradle deprecated warning未在本批处理；未运行Core测试、下游模块、部署或游戏验证。

### CR-022 修复与验证（2026-10-10）

- 根因：`TrainEnd`构造即注册rail，world身份检查与extension初始化却可能随后失败；调用方只有构造返回后才进入failed-load清理，因此无法取得失败对象撤销注册。旧测试缺少构造期失败后的索引/资源断言。
- 实现：`CoreWorld`共享内部身份校验；`CoreTrain`先验证身份并解析两个端部位置，再注册端部/world并初始化。失败逐项清理本次controller和车端，world仅在成功接纳后撤销；调用方cars不销毁，不复用完整destroy/failed-load清理。合同同步到[train physics](../../spec/javarailways/core/train-physics.md#构造与失败清理)，不新增通用事务或extension回滚协议。
- 定向红→绿：新增正式`CoreTrainConstructionTest`，旧运行时代码10/10失败，修复后10/10通过。四种身份冲突分别检查直接构造/存档加载，另测尾端位置拒绝及extension初始化失败/同身份成功重试；断言精确车端身份、原列车frame/controller及caller-owned cars，不只断言抛异常。
- 完整门禁：以下命令`BUILD SUCCESSFUL`，14个任务全部执行；Core47 suites/222项（216执行、6跳过），Basic59 suites/382项全部执行，零失败/错误。首次定向绿灯遇生成的Kotlin增量缓存损坏，改用非增量/in-process重跑，未删除缓存或修改构建配置。既有编译warning和daemon metaspace提示仍存在，不在本批顺带治理。

```bash
env GRADLE_USER_HOME=/workspace/.gradle ./gradlew --offline --rerun-tasks \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :Core:test :Basic:test
```

- 独立检查：`trellis-check`复核构造注册/清理、相同实例二次add及真实回归断言，未发现代码修改项；manifest的一处extension回滚表述已改为constructor-owned registrations。任务manifest（8+8项）、文档链接及变更空白检查均通过。
- 边界：无公开API或持久化格式/版本变化，无事件接入、Seat删除、编组几何或宿主factory修复；未运行Dokka、opt-in stress、平台测试或游戏验证，未连接开发服。仅CR-022记为修复，不把本批绿色回归记为Core通过或全项目审查完成。

### CR-010/011/012 SimpleSignals准备失败、取消分类与Wand stale回归（2026-10-10）

- CR-010把`Files.createDirectories(worldsDirectory)`移入prepared-world reservation的同一失败释放边界；目录创建、staging删除／写入／复制任一失败都会移除本world reservation，不增加重试状态机。真实临时文件系统回归用普通文件占用`worlds`路径，确认失败后修复路径，并由同一store/world重新prepare、close、save和load成功。
- CR-011保留host atomic source callback内、primary rename前的最后authoring guard，以独立取消标记把该失效分类为`WORLD_UNAVAILABLE`；未设置该标记的真实commit I/O异常仍为`PERSISTENCE_FAILED`。manager回归让第三次guard调用在callback已进入后失败，断言primary文本、runtime publication identity、host source publication identity和atomic commit计数均不变，permit正常关闭。
- CR-012不改已有正确生产清理。原stale-selection测试先让backend真实返回第一点及capture，再令publication失效；安装新的capture后继续点击，通过记录backend每次输入直接断言恢复调用的`firstPoint == null`，不再只以消息和零调用间接声称已清理。
- 完整非增量`:SimpleSignals:test`执行33个Gradle task并`BUILD SUCCESSFUL`（3m13s）；XML合计487项，零跳过／失败／错误，production与test Kotlin/Java compile均执行。首次3类定向运行134项时，新CR-011测试错误地对合法空source publication调用`single()`而失败；改为比较完整source list identity后，单项及完整模块门禁通过。
- 独立检查补强成功prepare后的reservation生命周期断言；冻结源码完整门禁仍为487项零失败／错误／跳过，task validate与变更空白检查通过。无持久化shape、schema version、runtime compatibility、公共API或协调器变化；未部署、连接开发服或运行Folia/客户端测试。既有Java toolchain探测、Kotlin读取Graal POM、deprecated/unchecked、WebUI 5项依赖漏洞／大bundle和一次测试期physics overload日志保留。

### CR-021 解编事务修复与验证（2026-10-10）

- 语义：用户确认收口为`splitBefore(carId,newId)`。`Train.cars`是不随reverse重排的稳定编组/持久化顺序；目标car之前的稳定前缀留在原车，目标及后缀进入新车。未知/首车拒绝，末节前合法。公开`isReversed`与只读派生`operatingCars`表达当前运营顺序；两列继承原方向，因此同一car ID在正反向分割后仍得到同一稳定两半。
- 机制：after-commit任务先等待exact physics ticket，再验证边界和新ID/name，严格解决两半端点/路径并预生成原车body traversal、occupancy与frame，然后按slot prepare extension并构造新车。新车构造失败复用已验证的constructor cleanup；成功后到原车发布之间是commit-only区间，只应用预计算字段、清零无controller的新车car/bogie运动、翻转CommitBuffer并更新端点队列/原子frame/mailbox，不再求解几何、调用extension或检查身份。两列结构frame沿用切分前同一已完成physics epoch；转移cars由新train stream取得，未销毁car，原controller保留。
- extension边界：`splitBefore`必须返回独立实例且不修改原extension/外部世界；返回实例的首次initialize仍属prepare，不得修改共享cars或发布平台效果。Core没有伪造通用plugin rollback，该合同写入`TrainExtension` KDoc和[train physics](../../spec/javarailways/core/train-physics.md#解编事务)。
- 回归：`CoreTrainSplitTest`的12项覆盖正/反向对同一car ID的稳定两半及各自operating view、末节单车、未知/首车、重复car ID、重复train身份、两半各自的端点/路径不可解、extension prepare/initialize失败，以及移动中切分的同epoch/新车零运动帧；断言对象身份、方向、controller、extension slot、frame、world/rail索引、mailbox及publication owner，12项全部通过。
- 公共API及完整门禁：冻结源码非增量强制重跑`:Core:test :Basic:test :BukkitPlugin:test`，并编译`SimpleATO`、`SimpleATOBukkit`、Bukkit生产源码和内置Java signal API consumer，生成Core Dokka；35个Gradle task全部执行并`BUILD SUCCESSFUL`（2m23s）。Core 54 suites/311项（305执行、6个既有disabled）、Basic 59/384及BukkitPlugin 89/361全部执行，合计1056项、零失败/错误。另用临时Java consumer实际调用`getCars()`、`isReversed()`和默认`getOperatingCars()`通过`javac`/`javap`。Dokka、Kotlin与WebUI仅出现既有warning；未将warning、WebUI依赖报告或bundle尺寸顺带纳入CR-021。task validate与`git diff --check`通过。
- 边界：删除未发布无caller的整数接口，不保留compatibility alias、不升持久化版本；不改碰撞/link策略、持久化shape或runtime compatibility，不实现SimpleATO可分割面元数据/规则。没有部署、Folia/客户端或开发服验证。CR-030 link事务仍开放；CR-033不再跟踪cars顺序语义。

以下八步于 2026-10-09 获用户批准；第 1、3、4、5、6 步及第 2 步根仓入口文档批次已完成。剩余文档问题留到全仓代码审查，第 6 步提交后等待用户检查，再推进第 7 步。步骤不是提交数量，每步可拆成独立可验证的提交。

1. [x] **无调用原型清理（CR-001）**：`6cf5805d` 已获用户认可，删除三个 Java 原型及 Lombok 残留。
2. [ ] **文档与阶段清单对齐（CR-002）**：先说明 Trellis/Dokka/docs-site/.codeabs 分工，修正过时 owner、JS 规则和完成状态。根仓与 docs-site 分批提交；导航在后续源码变更时同步更新。
   - [x] 根仓入口：文档职责规范与索引、requirement方向归属、模块表、SimpleSignals README和父任务当前状态汇总；纯文档校验和独立复核，未重跑JVM测试。
   - [ ] 延后至全仓代码审查：剩余owner spec、docs-site和代码地图整理。后续代码变更仍同步直接受影响的契约，不进行独立文档整顿。
3. [x] **旧寻路/观察路径清理（CR-003）**：`9ab81d46`和纯空行修正`9f6aac89`已获用户继续确认；World getter与fixture迁出pathFinding，保留有效身份冲突回归并新增3项访问器测试，受影响模块1601项测试零失败。
4. [x] **metadata 模型收口（CR-004）**：业务及测试统一使用四个分组，删除平铺 constructor/getter/custom copy 和 RailMetadata alias；迁移映射保留在一次性迁移边界。轨道重建共用局部函数，仅替换 CURVE 与 curveSafety，保留其他分类及未修改组；不改格式/版本。
5. [x] **公开 API 收口（CR-005）**：四个现有路线值迁入 basic.api.route，迁移公开签名与 consumer imports；算法、内部结果与快照转换仍隐藏。不复制模型、不改变行为/持久化，复用既有集合隔离测试与 Java consumer；下游回归、Dokka 页面及模块打包门禁通过。
6. [x] **运营编辑适配器迁移（CR-006）**：Destination 角色字段/动态表单/会话初始化与解析/保存删除及4项既有测试迁入 SimpleATOBukkit；宿主保留选点、PDC、编辑许可、调度、消息与标记。新增2项真实 service 回归并改用生产UI注册与独立预期字段；必要 Basic 编译依赖与单向门禁同步，测试/打包/边界门禁通过，不改存档或版本。
7. [ ] **局部一致性与复杂度治理（CR-007/008/009）**：基本代码约定、玩家文案先行；重点审查 SimpleSignals 事务/编辑职责，确认后小批拆分。RailWand/Dispatcher 非阻塞大重构允许延期，但须记录归属和理由。空源目录清理单独核对，不碰用户生成物；兜底只按证据调整。
   - [x] SimpleSignals 定义事务与 Wand 编辑专项只读审查：记录 CR-010 保存准备失败释放、CR-011 取消状态分类、CR-012 stale-selection 测试断言缺口；未修改运行时代码或运行测试。RailWand/Dispatcher 拆分建议已记入根 TODO，非游戏测试前置。
   - [ ] **低质量测试清理（用户追加）**：按 PRD 第 8 项审查，删除无有效覆盖的测试、合并纯重复项，修正有价值但断言无效的测试；逐项说明理由与保留覆盖，保护安全不变量和历史 bug 回归，小批提交并验证受影响模块。
8. [ ] **整体复核与正式游戏测试交接**：按台账核对处置/延期，运行受影响模块与边界门禁，修正父任务剩余项，输出真正阻塞正式游戏测试的问题和现场验证清单。不在审查任务内自动部署。

每批开始前说明目的/方法，完成后单独验证、提交并打开预览；当前按用户于2026-10-10确认的节奏连续小提交，最后统一检查。若后续复核发现需要改变产品行为或扩大重构范围，先修改计划并交用户确认；不因总体计划获批就自行扩张。

### CR-020 持久化fixture与零距离真实epoch（2026-10-10）

- 本批限定`WorldPersistenceRoundTripTest`、`SplitWorldSnapshotTest`及`CoreTrainPersistenceStateTest`三文件，原7项测试保留。复核确认此前zero-distance仍驱动generic TickManager，实际controller注册于PhysicsCoordinator；过去已完成的匀速改写不能覆盖这项空跑，台账补充更正而不改写旧验证历史。
- zero-distance使用显式`PhysicsCoordinator(1, automaticFallback=false)`，计数controller实际tick，6次50 ms exact ticket各以5 s上限等待；逐epoch检查ticket/metrics/frame匹配、revision推进、head/tail/car固定80/70/75 m和car/bogie零速度。存档恢复保留source实际比较，再检查固定位置；真实Koin JSON roundtrip增加独立18/10/14 m及AB方向预期。
- 各fixture在CoreWorld create/load以前就拥有显式tick/physics；private local helper只组合已有`use`，保留主异常与suppressed，physics先停，再释放该world runtime binding，最后关闭tick及其scheduler。`CoreWorld.load`失败已自行回滚binding但不会关闭caller dependency，因此坏train restore测试也显式关闭传入physics/tick。共享Koin parent保持原owner，测试不关闭它。
- 三类定向执行7项成功，9个task全部执行（10 s）；最终源码冻结后以`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false`强制运行`:Core:test :Basic:test`，15个task全部执行，`BUILD SUCCESSFUL`（20 s）。XML：Core 53 suites/308项（302执行、6个既有disabled），Basic 59 suites/385项全执行，零失败/错误。既有Core cast、Basic unchecked/多余safe-call与Gradle deprecated warning保留。
- 未改production/API、持久化shape/version，无部署/游戏验证。其余`CoreTrainReversedPersistenceTest`、`SavedRailConnectionsTest`等fixture资源缺口及RailWand完整split/merge集成仍单独跟踪，不以本批结案CR-020或整模块审查。

### CR-020 换向恢复与endpoint fixture清理（2026-10-10）

- 只改`CoreTrainReversedPersistenceTest`与`SavedRailConnectionsTest`；沿用既有局部`use`组合，使tick/coordinator在world create/load以前就有owner，成功world按physics→binding→tick释放，主异常保留、清理错误suppressed。失败load已有production binding回滚，不新增runtime hook、生产修改或格式/版本变化。
- 换向恢复保留逐bogie的source/restored精确比较，再检查独立给定的head=10 m、tail=64 m、稳定存储顺序car中心55/37/19 m及BA facing。固定值按既有`Curve.TOLERANCE`几何合同比较；首轮新增1e-9断言以10.0009765625 m失败，确认是既有二分摆位容差，未更改精确bogie比较或生产算法。
- 原“无部分注册”用例不再只读异常字符串：通过`WorldParentContext.attachWorld`捕获真实失败load目标，检查binding已释放、列车ID和不同name均不存在、rail车端/mailbox为空；下一真实exact ticket完成后检查已发布world frame epoch及空train/occupancy，未用反射或复制生产清理。单列车失败不能代表多列车world load整体回滚。
- 2项列车测试及23项endpoint参数/普通用例保留；定向25项全通过（9个task执行，36 s）。完整Core+Basic门禁结果由下方补充。窄查确认`RailConnectTest`两处及`CoreTrainTailBogieTest`五处world创建仍缺配对关闭；RailWand完整split/merge事务集成仍待补，CR-020保持partial。未部署、连接开发服或游戏验证。
- 最终冻结源码使用`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test`，15个task全部执行，`BUILD SUCCESSFUL`（1m10s）；Core 53 suites/308项（302执行、6项既有disabled），Basic 59/385全执行，零失败/错误。task manifest 15+15及scoped `git diff --check`通过。保留既有cast、unchecked/safe-call、Gradle deprecated等warning；无下游API变化，不运行Dokka/平台部署。

### CR-020 连接与尾架fixture清理（2026-10-10）

- 只改`RailConnectTest`两项及`CoreTrainTailBogieTest`五项的fixture生命周期，保留原连接、尾架、支路、换向间距/压缩修正断言与7项数量，不扩展physics epoch或geometry覆盖。
- 两类局部`withWorld`沿用既有显式dependency及嵌套`use`模式；tick/physics在`CoreWorld.createEmpty`前已有owner，成功world登记binding，按physics→binding→tick释放。setup/body失败仍继续清理，保留主异常及suppressed；helper显式`Unit`返回，防止表达式体返回断言值而被JUnit漏发现。移除未使用的CoreRail/ThreadGuard imports。
- 窄查确认`TrainSlopeJointMotionTest`两项仍创建world而无配对关闭；共享`createTestWorld`仍隐式创建dependency，调用方无法保护其world构造前的失败清理，另批评估，不顺带改共享fixture。RailWand完整split/merge事务集成与既有disabled环线仍待处理，CR-020保持partial。
- focused两类共7项全部执行、零失败（36 s，9个task全部执行）。冻结源码以`GRADLE_USER_HOME=/workspace/.gradle`及`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test`，`BUILD SUCCESSFUL`（1m4s，15个task全部执行）。Core 53 suites/308项（302执行、6项既有disabled）、Basic 59 suites/385项全执行，零失败/错误。task manifest 15+15及scoped whitespace检查通过，保留既有Core cast、Basic unchecked/safe-call/non-null assertion和Gradle deprecated warning。无production/API/schema改动、部署或游戏验证。

### CR-020 共享world fixture setup与坡道清理（2026-10-10）

- 限定共享`TrainTestUtils.createTestWorld`的setup与`TrainSlopeJointMotionTest`两项清理；原运动循环和断言保留，未改production/runtime/schema。Koin初始化保留并放在分配前；默认coordinator由nullable默认参数改为函数try内部创建，避免进入函数以前的分配不受helper保护。失败时只关闭helper-owned physics和tick/scheduler，显式传入的physics仍由caller拥有；保留原异常并附suppressed。成功返回后资源转交caller。
- nullable参数保持JVM一参描述符、Kotlin无参及具名调用；共享fixture消费者重新编译验证。坡道局部`withWorld`在helper成功后按physics→runtime binding→tick关闭，返回Unit，原2项测试数量不变。`CoreWorld`构造失败已负责parent registration回滚；helper补dependency所有权，不复制该回滚逻辑。
- focused坡道两项通过，9个task全部执行（39 s）。创建失败/清理异常的分支以控制流静态核查：Koin失败在分配前，scheduler失败没有后续资源，tick/默认physics/world失败进入owned-resource清理。未新增构造注入hook或模拟不可正常触发的分配故障；这些异常分支不能记为动态测试覆盖。完整消费者门禁结果待下方补充。
- 冻结最终源码以`GRADLE_USER_HOME=/workspace/.gradle`、`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test :BukkitPlugin:test :SimpleATO:compileTestKotlin :SimpleATOBukkit:compileTestKotlin`；35个task全部执行，`BUILD SUCCESSFUL`（2m43s）。Core 53 suites/308项（302执行、6项既有disabled）、Basic 59/385、BukkitPlugin 89/361，均零失败/错误。`javap`确认共享helper的一参及default bridge描述符；task manifest 15+15和scoped diff-check通过。保留既有Kotlin/Gradle/平台API warning；Gradle提示512 MiB heap/384 MiB metaspace的daemon将在build完成后退出，未修改构建配置。
- CR-020仍partial；其余fixture未宣称全审，RailWand完整split/merge事务集成、disabled环线及既有未闭合项继续跟踪。无部署或游戏验证。

### CR-034 事件注册与空桶清理同步（2026-10-10）

- 静态根因是bucket lookup与handler add之间没有注册变更锁，close/unregister可移除空bucket。`TypedRailEventBus`在锁外准备反射handler，随后同一monitor保护register、token close、unregister及空桶清理；publish保留ConcurrentHashMap和CopyOnWriteArrayList读取，callback在该锁外运行。token关闭按精确handler身份，幂等；`isClosed`只表示关闭标记，不承诺callback完成。
- 三项局部bus回归：100轮同event type并发注册/关闭/注销后验证live token仍可派发且关闭后不再派发；同listener旧token不能删除后来注册；callback自关并有界等待另线程注册后，下一次事件只交给replacement。没有sleep、反射私有字段或生产测试hook，executor有界关闭。barrier不强制旧实现的临界交错，未宣称旧版本必红或宿主生产故障已复现。
- 首次focused编译发现JDK17的ExecutorService不实现AutoCloseable，改为显式AutoCloseable资源包装并验证shutdownNow后的有界终止；无构建配置改动。最终focused 7项全通过，测试执行0.688 s、重编译总47 s。冻结源码以`GRADLE_USER_HOME=/workspace/.gradle`和`--offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行`:Core:test :Basic:test`，15个task全部执行，`BUILD SUCCESSFUL`（1m21s）；Core 53 suites/311项（305执行、6项既有disabled），Basic 59 suites/385项全执行，零失败/错误。task manifest 15+15与scoped whitespace检查通过；既有Core cast、Basic unchecked/safe-call/non-null assertion与Gradle deprecated warning保留。公开签名、持久化shape/version、事件接入均不变，未部署或游戏验证。

## 验证与关闭

- 任务文档：`python3 .trellis/scripts/task.py validate .trellis/tasks/10-09-stage2-code-review`、`git diff --check`。
- 代码改动按 `.trellis/spec/javarailways/shared/quality.md` 选择 touched modules 及直接 consumers，Gradle 使用 `/workspace/.gradle`。
- 公共 API/模块边界改动检查下游编译、Dokka、Java consumer 和现有模块打包门禁；平台改动补充调度/线程归属审查。
- docs-site 改动在其独立工作树验证；不把既有修改混入提交。
- [ ] 汇总问题处置与验证结果，核对父任务未完成项，列出正式游戏测试前剩余阻塞和现场测试清单。

### CR-023 剩余几何缺口收口（2026-10-11，独立任务）

本项后续由[坐标约束与性能任务](../archive/2026-10/10-11-train-coordinate-performance/prd.md)实现：真实三维轴距与统一中心恢复，加载检查相邻车钩，固定损坏内部车厢平移输入验证拒绝和清理。运行几何及跨模块全量门禁已通过，详细原始证据和性能结果仅在该任务维护。此前本文件的“剩余车钩缺口”记录为历史状态；联结事务CR-030、全world加载回滚和其他未解决项保持原范围。

### CR-033 结构准备重复扫描（2026-10-11）

- `TopologyStateBuilder.addLoopAnchors`将每个连通分量的remaining最小值扫描改为一次按同一group key排序并跳过已访问组；分量遍历和最小anchor选择保持原样。`PreparedRailConnectionPlan.captureLocked`将逐端点的creations扫描改为本次capture局部rail ID map，复用其keys判断批内身份。batch构造器已有ID唯一约束；重复endpoint校验仍在建索引之前，其余错误优先级不变。
- 只改两份production文件及对应测试；新增三项行为回归：独立环/孤立轨道的固定anchor和有序node/edge/projection、乱序三轨batch按身份连接并保留支路顺序及非首active选择、duplicate→symmetry→colocation错误顺序且不产生mutation。新增fixture按physics→binding→tick关闭；首次编译因TickManager静态类型非AutoCloseable失败，按既有fixture惯例显式转换后通过。
- 定向两类17项全部通过（51 s，9 tasks中5执行、4up-to-date）。冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`执行全量，`BUILD SUCCESSFUL`（1m11s，15 tasks全部执行）；XML Core 54 suites/319项（313执行、6个既有disabled），Basic 59 suites/385项全执行，零失败/错误。既有warning保留。
- owner spec补充一次排序/局部索引原理及复杂度中的prepared rail数量`N`；独立静态检查指出apply亦需遍历批内轨道，已修正文档并明确另计occupancy查询成本。没有公共API、持久化shape/version或长期catalog变化；没有运行耗时benchmark，不宣称毫秒或每tick收益。CR-033其他文档/诊断/重复校验项目仍开放，无部署或游戏验证。
- 独立检查强制重编译并复跑两类17项，全部执行、零失败/错误/跳过（42 s，9 tasks全部执行），无剩余阻塞项；scoped whitespace与task manifest 15+15通过，模块没有独立lint配置。提交前发现共享工作区位于`codex/train-coordinate-performance`；经用户确认，先将其几何修复及任务记录快进合入`dev/signal-system`（`00a44abd→84f35468`），再切回该分支提交本批。快进前后源码相同，未提交的其他任务内容保持原样。

### CR-033 事务共同校验去重（2026-10-11）

- 普通`CoreWorld.railTopologyTransaction`复用prepared路径已有的`validateReplacementRequirements`与`faultTopologyAfterFailedMutation`，删除两段重复实现；保留nested failure→replacement校验→结构检查顺序、原异常类型/文本和fault字段/stall顺序。普通事务仍用完整fingerprint判断恢复，prepared事务仍用编辑范围的base复核，不合并不同阶段或扩大扫描范围。不新增helper、manager、公共API或持久化变化。
- 加强`PreparedRailCreationBatchTest`原partial replacement rollback用例，检查准确错误文本、exact旧root/revision、实际剩余轨道、fault原因/版本/类型/消息及后续事务拒绝；测试数量不变，不宣称旧实现必红。触及用例按physics→binding→tick配对释放并保留主异常；同类其余6项旧fixture仍缺配对关闭，继续归CR-020，不扩大本批。
- 定向`CoreWorldRailTopologyTransactionTest`与`PreparedRailCreationBatchTest`共29项全部执行、零失败/错误/跳过（42 s，9 tasks全部执行）。冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`运行全量，`BUILD SUCCESSFUL`（1m11s，15 tasks全部执行）；Core 54 suites/319项（313执行、6既有disabled），Basic 59 suites/385项全执行，零失败/错误。既有Kotlin警告及Gradle daemon metaspace退出提示保留。
- owner spec复核：现有[rail topology契约](../../spec/javarailways/core/rail-topology-and-occupancy.md)已准确区分两种恢复检查和共同失败语义，本次纯重用不新增契约条款。独立静态检查无剩余问题；强制重编译并复跑两类29项全部通过、零跳过/失败/错误（49 s，9 tasks全部执行），两份源码测试前后SHA-256一致。scoped whitespace和task manifest 15+15通过，无独立lint配置。未部署或游戏验证，CR-033与整项目审查保持开放。

### CR-020 prepared creation fixture清理（2026-10-11）

- 限定`PreparedRailCreationBatchTest`一文件，为剩余6项测试补配对资源关闭，并将已有partial rollback用例收敛到同一private `withWorld(block: (CoreWorld) -> Unit): Unit`。原7项动作/断言均保留；显式Unit确保JUnit仍发现表达式体测试。helper复用`createTestWorld`，成功返回后的三层`use`按physics→binding→tick释放，原异常和suppressed保留；setup失败由共享helper负责。双world用例嵌套source/target helper，target构造失败也会清理已成功创建的source。
- 没有修改production/共享fixture、增加测试或新增故障注入hook；现有[Core testing契约](../../spec/javarailways/core/testing.md)已覆盖该清理规则，不新增重复规范。独立静态检查确认原测试行为及清理边界不变，无需自修。
- 定向强制执行该类7项全部通过，零跳过/失败/错误（49 s，9 tasks全部执行，测试体0.168 s）。冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`执行全量，`BUILD SUCCESSFUL`（1m8s，15 tasks全部执行）；Core 54 suites/319项（313执行、6既有disabled），Basic 59 suites/385项全部执行，零失败/错误。源码测试前后SHA-256一致：`9d7a5100ec620e8c597b4365ce9b56b8f67c01da12cd8a37a1fab1296a058ce0`；既有Kotlin/Gradle警告保留。该类缺口闭合不代表全仓fixture审计完成，RailWand完整事务集成等CR-020余项继续跟踪；异常清理按控制流静态核对，未新增动态故障注入。
- 独立检查无阻塞项；强制重编译并复跑该类7项全部执行通过，零失败/错误/跳过（49 s，9 tasks全部执行），测试前后源码hash一致。scoped whitespace及task manifest 15+15通过，无独立lint配置；未部署或游戏验证。

### CR-033 运动与选支路错误诊断（2026-10-11）

- 限定`BogieException`、`InvalidActiveRailException`及`CoreTrain`运动catch三处production。两异常保留原构造签名，在构造时将必要对象值复制成message字符串，不持有live bogie/view；转向架诊断明确位置来自published值，不声称实际failed active坐标。CoreTrain仍先清motion再标记脱轨，只给原异常类型/消息附列车ID及本次请求距离；不增加成功路径扫描/日志、重试、错误类别、公共构造器或其他异常改造。
- 加强两个现有测试类，数量仍4项：真实越过线路尽头后的脱轨frame和单次Main事件携带同一详细原因；真实非法道岔选择保留exact active支路、immutable continuation对象与revision，后续合法选择不改先前异常文本。未新增纯constructor测试。`javap -private -s`检查两异常只有新增String字段且原Java构造descriptor不变；consumer搜索未发现解析该诊断文本的协议，owner spec明确文本仅用于人工诊断。
- 定向4项全部执行通过（43 s，9 tasks全部执行）；冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test :BukkitPlugin:compileKotlin :SimpleATO:compileKotlin :SimpleATOBukkit:compileKotlin :SimpleSignals:compileKotlin :TunnelBridgeBuilder:compileKotlin :Core:dokkaGeneratePublicationHtml --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`执行组合门禁，`BUILD SUCCESSFUL`（2m42s，32 tasks全部执行）。Core 54 suites/319项（6既有跳过），Basic 59 suites/385项全执行，零失败/错误；五份代码测试前后SHA-256一致。Dokka既有Undocumented/declare链接warning保留；依赖链执行WebUI frontend构建时报告npm安全和大bundle警告，未在本批改动前端/依赖。没有部署或游戏验证；其他异常/cause诊断与CR-033剩余项不因此结案。
- 独立检查无阻塞项，强制重编译并完整复跑两类4项全部通过、零失败/错误/跳过（44 s，9 tasks全部执行），五份源码测试前后hash一致；scoped whitespace与task manifest 15+15通过，模块无独立lint配置。

### CR-032 测试曲线生成器归属（2026-10-11）

- 全仓含hidden源码、资源、构建/注册入口核查：`PrimeCurveGenerator`只有Core及五个下游模块的测试consumer；五模块已有`testFixtures(project(":Core"))`依赖，Core启用`java-test-fixtures`。将文件由main迁到testFixtures并保留原包名，方法/默认值/import/生成算法不变，仅KDoc与末尾换行机械变化。不复制生产兼容实现、不批量修改测试导入、不新增测试。
- 首次门禁在`compileTestFixturesKotlin`发现JOML缺失：生产`compileOnly`及普通`testImplementation`不能提供fixture编译classpath。报告后经主会话确认，将边界扩为`Core/build.gradle.kts`一行同版本`testFixturesImplementation("org.joml:joml:1.10.8")`；公开生成器方法不直接暴露JOML类型，故不用`testFixturesApi`，生产依赖/版本保持。owner testing规范同步源码位置及依赖边界；ignored `.codeabs`仅修正直接相关导航，不纳入提交。
- 六模块强制门禁运行2m22s、37 tasks全部执行后在SimpleATO失败：Core 319项（6既有跳过）、Basic 385项、SimpleATOBukkit 8项零失败；SimpleATO 352项中3项失败，均在`TimetableDispatcherTest`，分别为入库可用性交接、无兼容空停车点和周期边界。诊断涉及车库接缝附近的`CannotMoveForwardException: tail bogie is not reachable on the train-local forward path`。SimpleSignals/BukkitPlugin测试未执行，不引用旧XML。production JAR不含生成器、fixtures JAR包含生成器且原public方法保留；这不能替代全量门禁通过。暂停提交，在detached HEAD `4b906f85`的临时worktree对照原归属，尚未认定失败来源。
- 隔离基线对照完成：detached `4b906f85`保留旧main归属及原build，强制执行`TimetableDispatcherTest`全类（1m16s、14 tasks全部执行），37项中相同3项失败，零跳过/error。失败名称和断言消息与本批逐项相同，基线亦报告约130m接缝的尾架不可达；证实不是此次fixture迁移引入，但没有确定几何/测试fixture/运营逻辑中的根因，不追溯归罪具体历史提交。基线XML暂存`/tmp/javarailways-cr032-baseline-results.xml`，SHA-256 `59325b62f556b56cfa5ee44be04643957ee86c83d85d76c35b4b4c9b4370ed6f`；临时worktree已安全清理，主源码hash不变。门禁仍为失败，本批保持未提交。
- 独立check补跑`:BukkitPlugin:test :SimpleSignals:test :Core:jar :Core:testFixturesJar`（相同强制flags/cache）：`BUILD SUCCESSFUL`，2m49s、36 tasks全部执行；新鲜XML BukkitPlugin 89 suites/361项、SimpleSignals 47 suites/487项，零跳过/失败/error。重建runtime JAR不含生成器、fixtures JAR含原package class；build和移动源码前后hash不变，静态及scoped diff-check通过，task manifest 15+15有效。六模块现已执行测试，但SimpleATO的3项既有失败仍阻止整体门禁全绿；以CR-035单独跟踪，本批未提交，未部署或游戏验证。

### CR-035 车库运营测试轨道几何修复（2026-10-11）

- 隔离基线复现后检查三项fixture：两条重合、方向相反的140m直线首尾连接，接缝切线点积为-1。10m轴距列车head越过接缝0.5m后，尾架需退回原段129.5m，而旧尾端已在129.85125m；尾架不在前向路径不是搜索margin不足。保留Core刚体轴距和前向定位规则，仅改`TimetableDispatcherTest`三处构造，复用局部`depotLoop`及已有生成器的两个半圆，半径140/π、每段弧长140m、采样步长0.1m。原业务动作、目标弧长、断言和37项数量不变。
- 首轮半圆fixture仍使用最近端点推断，两段共享两头且闭合点有浮点误差，导致两次连接同一接头，完整类37项仍3失败（58s、14 tasks），错误变为不可达。最终改用既有`RailView.connect(outbound.viewAB, returning.viewBA)`及`RailView.connect(returning.viewAB, outbound.viewBA)`明确两处接头；不修改生产连接API或新增兜底。owner testing规范补充正常运营fixture几何可行性和显式端点要求。
- 冻结最终源码强制执行完整`TimetableDispatcherTest`：37项全执行、零跳过/失败/error，58s、14 tasks全部执行。随后完整`:SimpleATO:test`：47 suites/352项全执行、零跳过/失败/error，1m4s、14 tasks全部执行；沿用`/workspace/.gradle`和offline/no-build-cache/no-parallel/rerun/nonincremental/in-process flags。源码SHA-256 `a7272979dd131cff61bb76216ef1024dd7dc31d294fbeb1124cbb241f9a476c6`前后一致。Core/Basic及其余四模块证据沿用上方同一冻结迁移源码的回归；此次仅改SimpleATO测试，不将旧失败算作通过。静态独立检查确认两处接头的RailView方向正确，没有放宽或移除业务断言。独立动态检查待补；未部署或游戏验证。
- 独立check最终强制复跑完整类37项，全部执行通过、零跳过/失败/error（1m4s、14 tasks全部执行），源码hash不变，无需自修。task manifest 15+15及scoped diff-check通过，无独立lint配置。CR-035闭合，CR-032测试工具迁移可提交；整个Core/全仓审查仍有其他开放项，不归档总任务。

### CR-020 既有六项环线回归恢复（2026-10-11）

- 只读检查确认旧六项未驱动真实coordinator/publication，速度首轮读初始0，距离expected/actual是同一本地累加公式；world缺配对关闭。默认1mm圆采样使R500单case约314万点，不能仅删除Disabled启用。
- 本批仅改`TrainUniformMotionTest`，保留六组半径/编组及两圈加50 ticks，将六方法合为六组参数化执行，显式0.1m采样及四段圆端点连接；使用关闭fallback的真实50ms epoch，检查独立解析圆周位置、累计跨段/绕圈、速度、刚体约束和未脱轨。补setup失败及physics→binding→tick清理，其他四项enabled不改。production/共享fixture/默认采样精度不变。先focused确认正确性和实际耗时，若发现生产问题另行定位，不为恢复测试放宽规则。
- 单文件改写69增/69删，原六组参数及两圈加50 ticks保留，其他四项enabled原样。首次强制focused完整类直接通过：10项全部执行，零跳过/失败/error，31s、9 tasks全部执行；测试体2.045s，六环线合计1.571s，R500为0.921s，适合普通回归而非压力测试。源码SHA-256 `5da22d89fdc801a90456c59ae8965bdef3fefeff6ecbbea7386ad6eddf297ca0`前后一致。独立静态检查确认解析expected不调用production forward/search，实际绕圈里程来自published traversal，5mm容差未扩；fixture的floorHeight=0、offset=±车长/2、connector=0使相应几何断言成立。完整Core/Basic与独立动态检查待补。
- 冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`执行完整回归，`BUILD SUCCESSFUL`（50s、15 tasks全部执行）；Core 54 suites/319项与Basic 59 suites/385项均全部执行、零跳过/失败/error。本类10项测试体1.779s；各suite时间累计Core6.124s/Basic1.116s，不把累计suite时间当构建耗时或性能benchmark。源码hash不变，既有Kotlin/Gradle警告保留；没有生产/API/schema或游戏验证变化。
- 独立check强制复跑完整类10项全部通过、零跳过/失败/error（31s、9 tasks全部执行；测试体2.291s、六环线1.710s），源码hash不变，无需自修。owner testing规范移除当前disabled说明并记录有效断言及采样边界；scoped diff-check、task manifest 15+15通过，无独立lint配置。环线子项闭合，CR-020其余fixture及RailWand完整事务集成仍开放，不归档总任务。

### CR-033 world/car局部可读性（2026-10-11）

- 当前源码复核后仅整理`CoreWorld`重复全限定类型/函数名为现有或新增imports，保留嵌套类型归属及事务顺序；`CoreCar`私有`activePositon`改为`activePosition`，修正稳定车体有符号偏移和运营头/尾转向架注释，保留中间转向架未实现说明。不改表达式、分支、公开签名、Seat、几何算法、序列化或测试数量。
- 异常另批评估：CurveException已保留message，restore包装已保留cause，不重复修复；CannotCreateTrainException各抛出点的上下文和CannotConnectRailException诊断不混入纯机械批次。独立静态检查与完整Core/Basic回归待补。
- 两文件最终diff：CoreWorld 32增/40删，CoreCar 7增/9删。前者使用原类型的imports/短名，保留嵌套类型归属，不动Saved类型表达式；后者只改private名称及四组说明，保留中间转向架未实现TODO。现有train-physics规范已准确区分稳定车体偏移、运营头/尾与刚体求解；本批让源码注释与现有规范一致，没有新契约或需要重复写入spec的算法。
- 冻结源码以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`强制运行完整回归，`BUILD SUCCESSFUL`（51s、15 tasks全部执行）。新鲜XML Core 54 suites/319项、Basic 59 suites/385项均全部执行、零跳过/失败/error。前后SHA-256一致：CoreWorld `813f83c988839e2eb014fae2758df454b86f4911d0826b8965a3a93d982747dd`，CoreCar `0e6de427876ac7ac9664599e2287c71281205b78e94e791feb2bd2c834da5873`。既有warning保留，未部署或游戏验证。
- 独立check归一化比较：CoreWorld去imports并统一全限定名/空白后与HEAD一致，CoreCar去块注释并统一private拼写后与HEAD一致；private属性只在本类引用，新增注释与默认偏移和稳定direction逻辑相符。独立强制定向`CoreWorldRailTopologyTransactionTest`22项及`CoreTrainRigidGeometryTest`4项，共26项全执行通过、零跳过/失败/error（30s、9 tasks全部执行），两文件hash不变、无需自修。scoped diff-check和task manifest 15+15通过，无独立lint配置。其他异常诊断/文档及CR-033余项继续开放。

### CR-033 列车构造端点失败诊断（2026-10-11）

- `/jr train create`真实consumer会显示异常message；正常构造头/尾定位失败目前均为无message的`CannotCreateTrainException`。本批仅该异常、CoreTrain init两个抛点与既有ConstructionTest失败用例，保留无参JVM构造和默认null，增加可选不可变message；只在失败时记录失败端、列车和输入anchor位置/请求偏移，不持有live对象，不假造未生成端点的位置或cause。
- 将原tail越界真实用例参数化为head/tail两组，保留原world/rail索引、mailbox、cars/bogies不变及可重试断言；不新增纯异常构造器测试。split/link五个抛点、异常类型、失败顺序和清理保持，其他异常/事务不扩张。先focused，再Core/Basic完整回归与Bukkit消费方编译/Dokka/JVM签名检查。
- 三文件实现后首次强制focused完整`CoreTrainConstructionTest`11项全部执行通过、零跳过/失败/error（29s、9 tasks全部执行，测试体0.494s）。原tail失败参数新增head失败场景，使总执行项增加1，不是纯异常构造测试；没有改其他失败/重试断言。`javap -s`确认`()V`保留，新增`(String)V`，继承PhysicsException不变。静态独立检查无问题，两条失败分支仍在任何车端/world注册之前，文本只捕获输入anchor值，没有新增cause或live对象字段。
- 组合门禁`:Core:test :Basic:test :BukkitPlugin:compileKotlin :Core:dokkaGeneratePublicationHtml`在49s后失败（15 tasks执行）：新鲜Core 54 suites/320项，零跳过、1失败，`PhysicsCoordinatorTest`的close/in-flight用例line384期望旧train frame revision2/epoch1，实际revision3/epoch2；旧world frame身份及CLOSED/线程退出断言通过。Dokka已执行成功，Basic测试/Bukkit编译未执行，不引用旧XML。三文件hash冻结不变；当前不能判定失败来源，暂停提交/重试，单独只读检查关闭与train frame发布时序。

### CR-036 关闭与Core发布边界同步（2026-10-11）

- 独立只读检查确认runEpoch workers完成后逐列车buffer/frame/mailbox发布未与close同步，最终world publication才核exact ticket；已完成Future.get可在close中断后正常返回，从而形成观测到的列车epoch2/worldepoch1。本批单独修PhysicsCoordinator及原关闭回归，不混入构造异常文本逻辑；不靠锁外if消除TOCTOU，不把第三方processor放进stateLock，不宣称close回滚此前已发布结果或撤销外部效果。
- Core自有发布区复用stateLock并校验RUNNING及inFlight exact ticket，沿topology→stateLock顺序；补强既有close回归对真实train/world/mailbox边界的检查。若复现需引入生产hook或扩大锁内可扩展回调范围，先停止评估而非自行扩张。focused及完整组合门禁待补。
- 实施前发现controller的internal发布方法末尾调用protected可重写hook，不能整体加锁。报告后批准增加`TrainController.kt`：拆纯Core buffers与锁外hook的internal原语，原入口仍先buffers后hook；coordinator逐controller短锁门禁翻转后锁外通知，保持既有逐controller可见顺序，再对其余Core train/frame/mailbox与processor后的occupancy/capture分别门禁。第三方processor不进stateLock，close仍非quiesce，不撤销此前Core flip或hook外部效果。
- 批准保留原worker-close用例并新增一个具体发布竞态回归：用现有nanoTime注入按真实coordinator线程和一次arm barrier阻塞workers完成后的发布前位置，关闭后消化中断/释放，检查真实moving train的live位置、旧train/world frame、mailbox和metrics。先旧production单次证红再修复证绿，无生产hook、sleep或刷重试；不按调用次数猜位置。
- 新测试首次编译因接口headLocation没有arcLength属性失败，未执行；只改测试为直线真实position.x后，在未修发布实现上实际执行一次即红（1项/1失败，30s、9 tasks执行）：旧world frame断言通过、旧train frame身份断言失败（line455），证实关闭后仍发布下一epoch列车帧。构造诊断三文件仍在工作区，但PhysicsCoordinator/TrainController尚未修改；不将其描述成完全隔离的旧提交执行。
- 未修实现的红XML保留`/tmp/javarailways-cr036-red.xml`，SHA-256 `218c7a596ad5ea62ae9d03edc415c961def6cb2adb41fb67d21066562be755ae`。三文件修复后完整`PhysicsCoordinatorTest`29项全部执行通过、零跳过/失败/error（31s、9 tasks全部执行，测试体1.519s），保留原worker-close及两组processor-close覆盖；不是反复重跑原偶发失败刷绿。独立静态检查确认三个早退确实退出runEpoch、锁序一致、Core发布调用没有隐藏扩展回调，原controller逐个flip→hook顺序及commit包装保留。owner runtime-publication/train-controller规范同步门禁、复杂度及不quiesce限制；完整组合门禁及独立动态检查待补。
- 冻结六份源码运行组合门禁`:Core:test :Basic:test :BukkitPlugin:compileKotlin :Core:dokkaGeneratePublicationHtml`（沿用约定offline/no-cache/no-parallel/rerun/nonincremental/in-process flags及`/workspace/.gradle`），`BUILD SUCCESSFUL`（1m18s、21 tasks全部执行）：Core 54 suites/321项、Basic 59 suites/385项均全执行、零跳过/失败/error；Bukkit与其SimpleATO/SimpleATOBukkit依赖重新编译、Dokka成功，既有warning保留。六份源码前后hash一致，exception无参`()V`及String构造均保留。此前构造诊断批次被中止的验证也在同一冻结源码上补齐；没有部署或游戏验证。新增race对真实位置/frame/mailbox/metrics有动态证据，speed始终10m/s的断言不当作独立dirty-speed故障注入，buffer保护同时依赖静态同区校验。

#### CR-036 复盘：关闭保护不能只检查最终世界帧

1. 根因类别：C（修改传播不完整）、D（可控竞态覆盖缺口）、E（错误依赖Future.get必响应中断）。此前关闭保护落在world publication，Core buffer/train frame/mailbox等更早的发布入口仍在门禁外。
2. 既有修复的边界：终态没有被覆盖，最终world frame也确实未发布；不足的是没保护逐列车事实。直接整体加锁的草案在实施前因protected hook可阻塞而被否决，未引入运行时死锁后再补救。
3. 预防机制：同一锁内验证exact running ticket和Core写入，扩展callback留锁外；使用现有时钟注入确定性barrier，单次未修实现红→修复绿，不用随机重跑。无需新增运行时hook、retry或另一套publication owner。
4. 系统检查：controller buffers、无controller列车、frame/mailbox、processor后的occupancy/candidate及最终world/metrics均按同锁序检查，保持hook原顺序。close仍非quiesce，不把保护扩张为外部副作用回滚或全局原子epoch承诺。
5. 知识落地：runtime-publication与train-controller owner spec已同步，任务台账保留失败与红→绿证据；构造诊断属于另一笔提交。检索未发现`src/templates/markdown/spec/`镜像，不为本次debug创建另一套规范副本。

- 最终独立check强制复跑`PhysicsCoordinatorTest`29项及`CoreTrainConstructionTest`11项，共40项全执行通过、零跳过/失败/error（31s、9 tasks全部执行）；六文件hash不变，无需自修。`javap`确认旧`()V`及新增`(Ljava/lang/String;)V`，无参路径仍为null消息，仅String字段无live对象。scoped diff-check、task manifest 15+15通过，无独立lint配置；既有metaspace退出提示不影响本次成功结果，不顺带调整构建参数。CR-036与构造诊断子项闭合，不代表Core/全仓审查完成。

### CR-020 曲线弱测试改写（2026-10-11）

- 仅改`PrimeCurveTest.kt`：保留两点LINE后半段和反向最近点两项强回归，将五项主要验证弧长回显的测试合并为三项实际位置/frame检查，7项降为5项，66增/111删。移除错误的“最近样本”说明，不增加helper或参数化框架，不改生产代码。
- 手写10m斜直线位移(6,8,0)，独立给定端点、近端点和内部位置；DISCRETE位置/弧长0/5/10m，验证非单位切线归一化、非正交法线投影及45度bank插值；另用正交raw切线的中点45度验证方向插值。样本位置/弧长一致，expected不调用生产插值或生成器。终点保留raw frame的既有行为明确保留，不擅自统一归一化。既有curve-geometry规范已定义这些规则，本批没有新合同，不复制到另一份spec。
- 独立只读check确认手写数值、合法输入、左右区间/内部节点/边界覆盖及原强用例保留，无需补修；未重复运行Gradle。主会话冻结源码运行`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，`BUILD SUCCESSFUL`（54s、15 tasks全部执行）。新鲜XML Core 54 suites/319项、Basic 59 suites/385项均全执行，零跳过/失败/error；源码前后SHA-256均为`c20d401f31a6de1c4cebc82884c04b942d734c5ea702745d07da9253cc3874e7`。scoped diff-check和task manifest 15+15通过，无独立lint配置，既有warning保留。没有部署或游戏验证，opt-in stress不在普通test门禁中。
- 本子项闭合，CR-020仍partial：坡道运动用例及其余未配对关闭fixture、RailWand完整split/merge跨层事务集成继续跟踪；Basic整模块审查仍未完成。

### CR-020 直坡运动真实epoch与fixture清理（2026-10-11）

- 仅改`TrainSlopeMotionTest.kt`（54增/18删），仍为原1项四节10m车、水平200m/爬升20m轨道、10m/s、100个50ms步进；删除未使用import，不改生产算法或共享fixture。
- 原手动controller.tick+train.commit及位移/爬升下界断言改为关闭fallback的真实coordinator，每步有界等待exact ticket，再检查同epoch frame的固定车头弧长、四车及八转向架解析位移和速度、未脱轨。位置增量相对初始immutable frame，独立固定head初始弧长50m；不宣称额外验证了绝对初始车厢/转向架摆位或连接坡道接缝。具体断言方法见[Core测试契约](../../spec/javarailways/core/testing.md)。setup失败关闭caller-owned coordinator，成功后按physics→binding→tick通过use释放，保留主异常/suppressed；失败清理按控制流静态检查，未增加注入hook测试。
- 独立只读check确认解析期望、exact ticket/发布观察边界及资源owner正确，无需补修，不重复运行Gradle。主会话冻结源码运行`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，`BUILD SUCCESSFUL`（49s、15 tasks全部执行）。新鲜XML Core 54 suites/319项、Basic 59 suites/385项全部执行，零跳过/失败/error；本类1项实际执行通过。源码前后SHA-256均为`9e8194daa6fa9df9a5d62ca968c5d8b63a519879bb2f0a658f9fbb557bf995c3`。scoped diff-check及task manifest 15+15通过，既有warning保留，无独立lint配置；没有部署、游戏或opt-in stress验证。
- 本子项闭合，CR-020其余world fixture、接缝运动测试的发布断言及RailWand完整事务集成仍未全部完成；Basic整模块审查仍待推进，不归档全项目审查任务。

### CR-020 五类轨道测试fixture清理（2026-10-11）

- `RailTopologyEditorTest`、`PhysOvalConnectionTest`、`RailLocationPersistTest`、`RailLocationPeekCurveTest`、`RailDirectionRestrictionTest`共五文件，67增/24删；原1/2/5/2/1共11项测试的业务动作、断言和几何保持不变。
- 每类使用返回Unit的局部`withWorld`，沿用[Core测试契约](../../spec/javarailways/core/testing.md)既有默认world setup失败所有权；成功后的fixture构造和断言均在三层use内，按physics→binding→tick释放并保留主异常/suppressed。Persist私有`createStraightRail(world)`接收受管world，不再内部创建后只返回rail。没有生产代码、共享fixture、模型、schema版本或新规则变更，既有规范已覆盖本批，不重复新增条款。
- 独立只读check确认业务体与HEAD不变、JUnit表达式体仍返回Unit、资源归属正确，无需补修；未重复运行Gradle。主会话冻结五文件，使用`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`，`BUILD SUCCESSFUL`（54s、15 tasks全部执行）。新鲜XML Core 54 suites/319项、Basic 59 suites/385项均全执行，零跳过/失败/error；五类11项全部实际执行通过。五文件SHA-256与实施交付值一致，scoped diff-check和task manifest 15+15通过；既有warning保留，无独立lint配置，未部署或进行游戏/opt-in stress验证。
- 本批资源子项闭合，`TrainNameTest`和`CoreCarPositioningRegressionTest`等其余fixture仍待清理，接缝测试发布断言、RailWand完整事务集成及Basic整模块审查未完成，不归档总任务。

### CR-020 名称与车体摆位fixture清理（2026-10-11）

- `TrainNameTest`、`CoreCarPositioningRegressionTest`两文件仅增加局部Unit `withWorld`和physics→binding→tick的use释放（23增/8删）；原1+3共4项业务动作、断言、几何和参数不变。setup失败沿共享fixture既有owner合同，成功后setup及assert均在释放作用域内；主异常和suppressed由use保留。
- 独立只读check确认上述边界、JUnit返回类型和冻结hash，无需补修；不改生产代码/共享fixture或持久化。既有Core testing规范已经定义本次规则，不重复增补。主会话以`GRADLE_USER_HOME=/workspace/.gradle ./gradlew :Core:test :Basic:test --offline --no-build-cache --no-parallel --rerun-tasks -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`完成强制门禁，`BUILD SUCCESSFUL`（54s、15 tasks全部执行）；新鲜XML Core 54 suites/319项、Basic 59 suites/385项全执行，零跳过/失败/error，两类4项全部实际执行通过。源码前后SHA-256一致：Name `fe43616e7ffcb5d0d3f52c38ef4f03ccfbb8b08370db2da1aa3a1d819c8cddb6`，Positioning `18000b7d516646cbc56442bd1ccc9cc96c9af6404e980e9b7cb54b946454261f`。scoped diff-check和task manifest 15+15通过，无独立lint配置，既有warning保留；未部署或进行游戏/opt-in stress验证。
- 仅关闭两类资源子项；接缝运动真实epoch/发布断言、其余fixture所有权复核、RailWand完整事务集成和Basic整模块审查继续跟踪，CR-020保持partial，不归档总任务。
