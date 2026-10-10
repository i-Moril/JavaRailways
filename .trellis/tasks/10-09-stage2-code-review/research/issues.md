# 问题台账

## CR-001：无调用 Java 原型和 Lombok 构建残留

- 优先级：P2（维护清理，非已证实的游戏测试阻塞）。
- 状态：已修复，用户已检查并同意。
- 证据：`Basic/src/main/java/work/art1st/javarailways/basic/controller/command/AccelerateCommand.java:3`、`BasicControlCommand.java:6`，`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/rail/CreateRail.java:6`；`Basic/build.gradle.kts:23-26` 四项 Lombok 配置。
- 复核：全仓生产、测试和资源文本中只有自身声明/继承及 Lombok 配置引用；没有注册或加载入口。CreateRail 仅有演示 main。生产纵向执行使用 `Basic/.../control/HandleTrainActuator.kt`。
- 处置：删除三个原型与四项依赖，不改 Core ControlCommand、当前 actuator、寻路、持久化或有意提供的 Java API。
- 验证：独立检查确认无生产、测试、资源或反射注册依赖；Core ControlCommand 与 RailWorldFactory 的 HandleTrainActuator 接线保留。Basic、SimpleATO、SimpleATOBukkit、BukkitPlugin、SimpleSignals、TunnelBridgeBuilder 共 1611 个测试用例，1 个跳过，0 失败/错误；Core:test 为 UP-TO-DATE（既有结果 212 个，6 个跳过），BukkitPluginDev:test 为 NO-SOURCE，其生产编译成功。编译输出已无三个被删类及 CreateRail 的嵌套类。任务上下文校验及 diff whitespace 检查通过。
- 环境说明：首轮受 JDK25 缺少 libz 和 Kotlin 编译内存不足影响；重试仅给进程设置现有 zlib/gcc 库路径、max-workers=2、Kotlin daemon 3g/Gradle 2g，未修改项目配置，Gradle 缓存仍为 `/workspace/.gradle`。
- 产物：`:BukkitPlugin:shadowJar` 成功；本次 `JavaRailways-1.0-SNAPSHOT-all.jar`、Basic JAR 和编译输出均无已删类，宿主 JAR 无 `lombok/`。未更新旧 reobf JAR，不把它当作本次产物；无部署或游戏内验证。
- 提交：`6cf5805d`；尚未执行游戏内验证。

## CR-002：文档职责及当前状态不一致

- 状态：已确认；P2，影响维护和阶段判断，不是运行时故障证据。
- 证据：`SimpleSignals/README.md:26-28` 仍描述旧规则；docs-site `docs/architecture/modules.md` 的模块归属过时；`requirement.md` 方向归属与当前 Basic owner 冲突；`.codeabs` 缺模块且含旧路径。初审细节见 `initial-findings.md:10`。
- 处置：确定四类文档分工，修正当前描述和父任务清单；历史记录不当作当前未完成事项。根仓与 docs-site 分开提交，保留 docs-site 已有六份修改。
- 验证：交叉核对 source/spec/API，检查链接、Dokka 范围及修改所属工作树。
- 进度：根仓新增文档职责规范并接入shared索引/requirement/编写指南；修正模块表、README的JS/tree/cycle、类型/模型字段与进路确认语义；父任务顶部给出当前状态并将离线模型显示标为完成，现场验收保持未完成。纯文档修改不重跑JVM或Dokka生成。
- 本批验证：独立审查核对FixedBlockSignalDefinition、SignalPackEpochRuntime与拓扑重验生产接线；改动文档的本地链接目标存在，任务context校验和`git diff --check`通过。docs-site的原有六份修改保持不动。
- 未完成：docs-site与.codeabs未改；owner spec还发现`model-packages.md:20,40`、`js-signal-rules.md:12,28,244`的Wand状态文字落后，以及`basic/operating-directions.md:14,52,69`的旧低速降级描述。另批依据生产逻辑收口，不在本次入口修正中宣称已解决。
- 用户决定：剩余文档问题延期到全仓代码审查；不阻塞已知代码问题修复。入口修正提交为`6d8c37d1`。

## CR-003：旧寻路与目的地索引残留

- 状态：已修复，用户已同意继续；提交`9ab81d46`及空行修正`9f6aac89`。P2，不宣称旧残留已造成运行错误。
- 证据：`Basic/.../pathFinding/PathFinder.kt:13`、`BasicWorldData.kt:20` 和 `SimpleATO/.../controller/SimpleATO.kt:1105`；RailMap 仍被创建和读取，初审未发现生产写入。
- 处置：统一使用当前拓扑寻路与 RailObjectStore；迁移 RailMap 文件中的有效 World 访问扩展，保留有价值的回归断言后移除旧路径。
- 验证：全仓 producer/consumer 搜索、Basic/SimpleATO 及下游回归；不得直接删整文件和全部旧测试。
- 实施：删除PathFinder/RailMap/RailDestination、BasicWorldData.railMap和ATO旧索引读取；保留Core静态信号/动态车端与RailObjectStore观察。World访问器移到Basic根包，fixture移到basic.testing，删除TestRailMapRegistry；所有调用只机械改import，无兼容alias或第二个实现。
- 测试保留：旧索引专用测试删除；显式注册测试迁移并扩展为3项（缺失不自动创建、精确对象身份、world隔离）；ATO同epoch与跨epoch重复ref的停车断言仍在，改用真实静态信号加当前RailObjectStore构造冲突。
- 本轮验证：Basic 379、SimpleATO 351、SimpleATOBukkit 2、BukkitPlugin 353、SimpleSignals 485、TunnelBridgeBuilder 31，共1601项实际执行，零失败/错误/跳过；Core:test为UP-TO-DATE（212项，6跳过），BukkitPluginDev:test为NO-SOURCE，编译成功。Java consumer编译、Basic Dokka和主插件shadowJar成功；Dokka仍有既有缺文档/链接warning，不代表文档全面合规。
- 产物检查：Basic编译输出及Basic/宿主JAR没有旧pathFinding类；宿主JAR仍可能保留空目录项，不是旧代码。未更新或使用旧reobf产物，没有部署/游戏内验证。
- 最终复核：全仓生产/测试源码无旧包、三类及TestRailMapRegistry引用；`verifySignalModuleBoundaries`和`SimpleSignals:verifyPluginJar`实际执行通过，任务context校验及`git diff --check`通过。

## CR-004：metadata 的平铺/分组双重调用模型

- 状态：已修复，待用户检查；P2，可维护性及字段保留问题。
- 证据：`Basic/.../rail/RailInfrastructureMetadataStore.kt:17-80` 旧 constructor/getter/copy 与 data class current shape 并存，RailWand 生产调用混用。
- 处置：业务调用统一为分组 current model；单独保留确有用途的一次性迁移 decode 边界，不新增 runtime compatibility 或 schema 升版。
- 验证：metadata 更新/复制、轨道编辑和持久化 fixture；先阅读版本基线规范。
- 实施：删除平铺 constructor/getter/custom copy 和 RailMetadata alias，所有业务与测试改用分组字段；旧速度映射只在 LegacyRailMetadataV1.toCurrent 内展开。三个轨道重建入口共用 withRebuiltCurveSpeedLimits，只更新 CURVE/curveSafety，不再由 authoring design 和 effective minimum 重建全部限速。
- 回归：新增6项测试，覆盖分组编辑保留、真实 split/merge 保留较高 CURVE、authoring 缺省、固定 grouped JSON、六种迁移速度组合、共用重建函数保留非 CURVE 分类与空 authoring；同时加强撤销断言。首轮编译发现遗漏测试/重建调用，补齐后重新验证。
- 验证结果：本轮合计实际执行 Basic 382、SimpleATO 351、SimpleATOBukkit 2、BukkitPlugin 356、SimpleSignals 485、TunnelBridgeBuilder 31，共1607项，零失败/错误/跳过；Core:test复用既有结果（212项、6跳过），BukkitPluginDev编译通过、test为NO-SOURCE。Java consumer、宿主shadowJar、SimpleSignals插件JAR检查、模块边界门禁及Basic Dokka通过；Dokka既有缺文档/链接warning仍保留。未部署、未做游戏内验证。

## CR-005：公共 API 的实现类型泄漏

- 状态：已修复，待用户检查；P2，正式边界与可维护性问题，不能只凭包名决定搬迁。
- 证据：`SimpleATO/.../api/line/LinePlanner.kt:25` 的 ExpandedRoute 和 `api/mission/AtoMission.kt:82` 的 PlannedRoute 位于 Basic 非 api 包，Dokka 隐藏这些实现包。
- 处置：审核 route 值的正式归属；将必要通用值纳入最小公开契约并迁移 consumer，不新增一套重复 route/snapshot。
- 验证：公开签名搜索、直接 consumer 编译、Java consumer、Dokka 和现有模块边界门禁。
- 实施：ExpandedRoute、RouteSegment、RailViewRef、PlannedRoute 直接迁入 basic.api.route；RouteBuildResult、准备时间/道岔默认参数、寻路与 toSnapshot 转换留在隐藏实现包。同步 Line/mission/station 等签名、consumer imports 和 API KDoc；明确候选路线不代表授权或物理验证证明。未增加 alias、重复模型、存档或版本变化。
- 复核：独立 trellis-check 确认四个值的校验、防御复制和 private copy 可见性未变，并补齐字段 KDoc。既有 ExpandedRouteTest 只移动包，Java consumer 新增公开路线构造及 Line/mission 签名编译片段；未增加测试数量。
- 验证结果：Basic 382、SimpleATO 351、SimpleATOBukkit 2、BukkitPlugin 356、SimpleSignals 485、TunnelBridgeBuilder 31，共1607项实际执行，零失败/错误/跳过。Core:test为UP-TO-DATE（212项、6跳过），BukkitPluginDev编译通过/test为NO-SOURCE；Java consumer、Basic/SimpleATO Dokka、宿主shadowJar、插件JAR和模块边界门禁通过。确认Dokka生成四个新公开类型页面；其他既有缺文档/链接warning未在本批整顿。未部署或做游戏内验证。
- 验证环境观察：首轮 SimpleSignals 108项类加载失败，缺失类在当前Basic JAR中存在；测试从14:41:28Z开始，JAR于14:41:49Z被改写。repo-local `.gradle-user-home/daemon/9.0.0/daemon-24739.out.log:13724` 显示同一checkout另一个VS Code Java环境Gradle build于14:41:48–49Z运行，支持外部构建改写运行中产物的解释，具体写入任务尚未确认。源码稳定后完整重跑通过；主门禁的`--no-parallel`不能约束另一daemon。未停止IDE或改其配置，后续验证应避免同checkout并发构建。

## CR-006：运营编辑适配器归属未收口

- 状态：已修复，待用户检查；P2，尚未证实影响游戏运行。
- 证据：`BukkitPlugin/.../tool/RailObjectWand.kt:832,870` 处理 Destination/Station/Depot 编辑，而 SimpleATOBukkit 目前以接线/诊断为主。
- 处置：将运营编辑及对应状态/测试放入 SimpleATOBukkit；宿主保留通用工具入口、公共方向编辑和平台基础能力，不制造双 owner 或反向模块依赖。
- 实施：SimpleATOBukkit/tool 接管 Destination 字段映射、配置组/动态可见性、会话初始化与解析、保存/删除及 standalone 转换回滚。RailObjectWand 直接组合 editor 和现有 DialogRenderer，保留玩家待编辑/待创建身份、PDC、选点、原 rail-scoped permit、消息、标记和 quit cleanup；无新服务发现/异步任务/插件生命周期。角色隐藏字段映射除包名外保持原实现。
- 依赖：adapter 新增实际需要的 Basic compileOnly；根门禁只允许 adapter→Basic，继续禁止 adapter→BukkitPlugin/SimpleSignals。Core fixtures、ConfigLib 和 JOML 只补到测试 classpath；不新增 Paper dependency、格式/版本或兼容路径。
- 回归与复核：既有4项测试迁移，动态 UI 使用生产注册/seed 而非复制 ConfigGroup；新增2项真实 service 用例验证完整表单→canonical/WayPoint/Station/RailObject投影→删除，以及失败恢复原 standalone anchor。独立 trellis-check 将成功用例改为实际 seed/read，并使用独立预期字段，避免与生产共用转换函数。fixture 使用真实运行对象，未使用的路线/时刻表入口若被调用则失败，不声称覆盖进路执行。
- 验证：初次编译/执行暴露测试所需 ConfigLib/JOML 缺失，补齐后完整门禁成功。实际执行 SimpleATOBukkit 8、BukkitPlugin 352、SimpleSignals 485，共845项零失败/错误/跳过；Core 212（6跳过）、Basic 382、SimpleATO 351、TunnelBridgeBuilder 31为UP-TO-DATE。BukkitPluginDev编译通过/test为NO-SOURCE；Java consumer、宿主shadowJar、插件JAR与模块边界门禁通过。任务context与whitespace检查通过；未部署或进行游戏内验证。
- 验证：依赖图、Wand 路由/状态、模块测试和打包门禁；现场交互留到正式游戏测试。

## CR-007：风格、玩家文案与复杂职责热点

- 状态：具体表现已确认，重构必要性及范围待继续审查；P2/P3，不等同于已确认功能 bug。
- 证据：初审 `initial-findings.md:9,12`；RailWand、TimetableDispatcher、SimpleSignalsWorldRuntime、SignalWandHandler 集中多类职责；部分处理压缩为单行；SimpleSignals 文案与宿主 i18n 不一致。
- 处置：先统一触及代码的基本约定和玩家文案；优先审查 SimpleSignals 发布事务/编辑流程，再按明确职责拆分。仅因文件长而拆分、全仓格式化、无证据重写 Dispatcher 均不在计划内。
- 验证：行为保持的现有回归，尤其 stale edit、保存失败、卸载和授权收紧；新增测试只针对确认缺口。

## CR-008：空目录与生成物边界

- 状态：空目录已确认；P3，非游戏测试阻塞。
- 处置：仅清理确认空的源目录，Git 无法记录这类删除；用户未跟踪 bin/logs/models 不是可直接删除目标，不加占位文件制造 diff。
- 验证：目录清单和 Git 状态，不删除生成物或用户数据。

## CR-009：过度兜底评估

- 状态：待审查，目前没有已确认应删除的具体安全校验。
- 处置：逐条写明触发条件、保护的不变量及替代机制；保留 epoch、资源授权、原子发布、卸载和按分支拒环。物理扫描环与输出依赖环不能混为一谈。
- 验证：真实可达调用链及对应测试，不能以“几乎不会发生”代替证据。

## 2026-10-09：SimpleSignals 事务与编辑流程专项审查

- 范围：定义候选准备、edit permit、持久化 staging、host source replacement、runtime publication，以及 Signal/Section Wand 会话与既有回归；只读检查，未运行测试或修改运行时代码。
- 职责结论：manager 仍是唯一正式定义提交入口；runtime 用单个 RuntimeState 同时替换 publication、occupancy 和 model binding，Wand/backend 不另存正式定义。不因文件长度拆分事务 owner。
- CR-007：RailWand 显示/预览优先拆分、撤销恢复另批评估；Dispatcher 只优先评估 ticket 公共机制及 checkpoint 映射，保留唯一状态机。两项已追加到根 TODO，可延期，不作为 Stage 2 游戏测试前置。
- CR-009：base publication、exact topology、catalog identity、session guard 和 unload 检查分别保护不同竞态；本次未确认可直接删除的安全兜底。InventoryDrag 监听差异不能证明会改变手持 SectionWandTool，不新增无证据限制。

### CR-010：保存准备的目录创建失败会泄漏 world reservation

- 状态：已修复并独立复核；P2，编辑保存恢复问题，不是行车授权错误。
- 证据：`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitionStore.kt:86-95` 先加入 preparedWorlds，随后在 try 外调用 Files.createDirectories；失败绕过 catch 内的 remove。
- 影响：例如 worlds 路径被普通文件占用，第一次保存失败后，即使外部修复路径，同一 store/world 的后续保存仍被 already prepared 拒绝，直到重新创建 store。
- 最小建议：将目录创建纳入同一个准备失败释放边界；不新增重试、兼容模型或版本号。
- 验证缺口：补真实文件系统回归，先制造目录路径冲突，再修复路径，使用同一 store/world 保存并加载成功；保留既有 primary replace 失败/旧 disk-live 配对测试。
- 处置：目录创建已纳入prepared reservation的同一try/catch释放边界；真实临时文件系统测试以普通文件占用`worlds`，失败后删除冲突，并用同一store/world重新prepare、close、save和load。

### CR-011：最后一次编辑取消被误分类为保存失败

- 状态：已修复并独立复核；P3，typed status/诊断问题，不会写盘或放行。
- 证据：`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt:1650,1673,1699-1700` callback 入口 guard 失效返回 WORLD_UNAVAILABLE，beforeCommit 内最后 guard 失效只抛普通异常，callbackStarted 将其归为 PERSISTENCE_FAILED。
- 最小建议：显式区分取消/失效与真实持久化异常，保留最后一次 guard；不拆出第二事务协调器。
- 验证缺口：既有 manager test 的 recording commit cancellation 在 callback 入口前失效；需覆盖进入 callback 后、primary rename 前失效，断言取消状态及 disk/source/publication 均不变。
- 处置：最后一道authoring guard用独立取消标记分类为`WORLD_UNAVAILABLE`；真实I/O异常仍走`PERSISTENCE_FAILED`。manager回归控制第三次guard在atomic callback内失效，并检查primary、source identity、publication identity及commit计数不变。

### CR-012：过期选择测试没有验证清除已选第一点

- 状态：已改写并独立复核；P3，低质量测试断言缺口。
- 证据：`SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandlerTest.kt:72-82` 名称宣称 clears old selection，但没有先建立 firstPoint，也未检查清除后的下一次 backend 输入；生产 handler 第 132 行的清除缺失仍不能由这些断言检出。
- 最小建议：改写原测试，先成功选第一点，再触发 stale，恢复新 capture 后继续点击，断言 backend 收到 firstPoint=null；不删除有价值的 stale 拒绝覆盖，不按数量减测试。
- 编辑器结论：本次未确认 SignalWandHandler/SectionWandHandler 的生产行为缺陷；已有 backend/actions 与事件协调分层，不进行无证据大拆分。
- 处置：原测试现先真实保存第一点，再令publication过期，随后安装新capture继续点击；直接记录每次backend输入并断言恢复调用的`firstPoint`为null。生产清理逻辑无需修改。

## 批次 C1：Core runtime / Basic observation（2026-10-09）

基线 `56025ea5`。以下为源码可达性及测试断言检查，不是现场或新定向回归复现；具体已查/未查范围见 [coverage.md](coverage.md)。既有 Core/Basic 测试已强制重跑通过，但没有覆盖以下新发现的失败序列。运行时代码未修改。

### CR-013：卸载异常后已关闭世界仍留在主 tick 集合

- 状态：已修复（2026-10-10）；P1宿主生命周期问题。CR-015 factory partial-init和CR-017 Koin scope仍独立开放。
- 证据：`BukkitPlugin/.../logic/WorldManager.kt:339-366` capture/await/save 失败会执行资源关闭的 finally，但跳过 finally 外的 marker 清理和 railWorlds.remove；`:715-716` withdraw 只撤 UUID lookup。
- 触发/影响：物理已 STALLED 后卸载，capture 直接抛；或 final save 失败。旧世界留在 owner map，后续 `tickStart:388-390` wake 关闭的 coordinator 再次抛，可能打断其他世界的遍历；同 BukkitWorld 的 register 又被 `:144` containsKey 挡住。
- 最小建议：owner map/marker 撤出覆盖保存及清理异常的外层 finally，各资源关闭不得因前一个失败而跳过；保留保存失败诊断，不吞异常伪造成功。
- 回归缺口：capture failure、write failure、teardown failure 下 map/lookup/资源都完成清理，同世界可重载，其他世界可继续 tick；已有 lookup 单测不覆盖真实 unload。
- 处置：卸载捕获exact owner world，保存和每项资源关闭分别尝试；首个异常原样抛出，后续异常进入suppressed，最终按exact identity撤owner map。新增持久化失败与teardown失败2项回归，断言后续cleanup、physics关闭、map/lookup清除及同UUID重新绑定；capture/write失败共享同一卸载后继控制流，不复制只改异常文本的测试。定向测试通过，最终模块门禁见[执行记录](../implement.md#cr-013-卸载失败清理2026-10-10)。不把该修复扩写为factory/Koin/Folia全部生命周期完成。

### CR-014：final save 未停止 timer fallback，等待一次 ticket 不等于冻结世界

- 状态：正常卸载物理边界已修复（2026-10-10）；P1存档一致性风险。下列保留原审查基线，异常清理与其他owner生命周期仍由CR-013/015/017跟踪。
- 证据：`WorldManager.kt:333-347` 标记 unloading 并等待一次 ticket，直到 `:357` 才 close；`Core/.../physics/tick/PhysicsCoordinator.kt:338-348` timer 只检查 started/status/heartbeat，不知 unloading；默认 heartbeat 75ms、timer 周期50ms。
- 触发/影响：epoch E 完成后，较慢的逐 owner capture/磁盘等待允许 fallback 启动 E+1。`WorldManager.kt:608-626` 顺序抓取多份快照，`CoreWorld.kt:1437-1444` 用调用方旧 epoch 标记 live trains，`CoreTrain.kt:823-835` 逐项保存。物理更新可能与 capture 交错，无法保证 final snapshot 同一 epoch；即使 capture 已结束，写盘等待期间仍可启动新物理工作。
- 最小建议：coordinator 拥有 stop-wake/quiesce 边界，在同一状态锁禁止新主线程/timer epoch 并返回当前 exact ticket，await 后再 capture/save，最后 close。重复 capture/await、只停平台 tick 或随意加 timeout 均不能替代该边界。
- 回归缺口：注入 clock/latch，在 stop-wake 后推进 heartbeat，确认没有新 epoch且各 snapshot owner读到相同稳定状态。
- 处置：`beginShutdown()`在既有状态锁内永久禁止新wake并返回exact ticket，宿主成功await后才capture/save；timer资格检查及分配同锁，保留在途epoch正常完成。新增4次执行验证真实worker、已启动timer/未启动bootstrap、在途失败不回退；7模块强制重跑1900项（1894执行、6跳过）零失败/错误，见[验证记录](../implement.md#cr-014-停止新物理工作后保存与验证2026-10-10)。未直接执行宿主多owner final-save I/O/Folia，不将物理停wake说成全世界所有任务已静止。

### CR-015：世界部分初始化失败遗留已注册渲染监听器

- 状态：已修复（2026-10-10）；P2，加载失败恢复/资源归属问题。Koin child scope仍由CR-017独立处理。
- 证据：`WorldManager.kt:161-169` factory 调用在保护 try 外；`RailWorldFactory.kt:63-77` 创建 tick/coordinator/world/render manager，`:208-210` 才 restore；factory 没有失败清理。`render/chunk/RenderChunkManager.kt:57-75` 构造注册 listener/安排刷新，close 才注销。
- 触发/影响：语法、版本和 infrastructure revision 都合法的 split 文件，Basic WayPoint 引用 Core 不存在的 rail UUID。decode/pairing 不交叉验证该引用，直到 `BasicInfrastructureSnapshotV2.restore:93-99` 才抛；已注册 listener 无人关闭，重复加载失败会重复注册。线程池是否启动依失败位置，不把它夸大为每次必然泄漏活线程。
- 最小建议：factory 自持 partial-init resources并逆序清理，保持原异常；单纯把外层 try 上移不够，失败时调用方尚未拿到 world。
- 原回归缺口：Basic restore单测不覆盖宿主清理。现已分别用production factory实际调用的LIFO owner验证资源关闭/异常规则，并用真实`RenderChunkManager`构造验证listener撤销；factory只有成功返回后才可能被调用方发布。相同world修复输入后的完整重载仍受CR-017遗留Koin scope影响，不能在本项伪称已验证。
- 处置：factory从tick创建开始以固定LIFO栈持有tick/physics/render，完整成功后才移交；任一后续restore/materialize/metadata失败保留主异常并按render→physics→tick清理。render构造器在listener注册后的initial refresh失败时自行close，close保证最终注销且将后续失败suppressed。新增5项回归验证逆序、异常身份/suppressed、成功移交及构造期listener撤销；不将其描述为Koin scope修复。

### CR-016：in-flight close 后 CLOSED 终态可被 STALLED 覆盖

- 状态：已修复（2026-10-10）；P2，终态/发布撤销契约问题。CR-014 final-save物理停wake已在后续独立批次修复，不改变close的abort语义。
- 证据：`PhysicsCoordinator.kt:589-600` 设置 CLOSED并shutdownNow，正在等待worker的 coordinator 被中断后，`:514-516` catch无条件写STALLED；overload分支`:492-494`同样不保留CLOSED。正常尾部`:502-509`也未与close/stall边界复查ticket发布资格。
- 影响：close后的最终status可能变为STALLED，而不是CLOSED；不代表重新运行，STALLED仍拒绝wake且executors已shutdown。若processor消化中断继续返回，关闭后发布资格也需回归验证，不能仅靠interrupt撤销。
- 最小建议：终态转换保留CLOSED，最终publication在与close/stall一致的边界验证ticket仍有效，不用额外无依据重试/预算。
- 回归缺口：真实worker进入latch后close，ticket exceptional且status始终CLOSED；另测忽略中断的processor返回后不得推进latest frame。
- 处置：成功发布与close在同一状态边界验证RUNNING及exact ticket；失败/overload同样保留已有终态，持锁顺序为topology→coordinator。新增3项回归在旧实现全失败、修复后全通过（该类24项）；独立检查及Core/Basic组合门禁通过，详见[验证记录](../implement.md#cr-016-修复与验证2026-10-10)。不承诺close撤销已完成的train commit或processor副作用。

### CR-017：Koin world scope 没有成对释放

- 状态：已修复（2026-10-10）；P2，world卸载/反复加载的引用保留。
- 证据：`Core/.../di/WorldParentContext.kt:18-19` 创建registry scope并declare(world)，CoreWorld构造时attach；`JavaRailwaysKoin.kt:21,46-53`缓存进程级parent。生产源码没有对应child scope close/delete；WorldManager teardown不释放scope。
- 影响：卸载后全局DI registry仍持有旧CoreWorld及其extensions，新实例使用新scope ID继续累积。这里存在registry外部引用根，不是把普通循环引用误判为泄漏。
- 最小建议：先核实world scope是否仍有生产读取者；无consumer则评估删除失效绑定，有consumer则由composition持有可关闭attach token，在失败启动和正常卸载关闭。不要让CoreWorld越权关闭所有平台资源。
- 回归缺口：反复attach/unload及失败启动后不存在旧scope/world声明；现有真实Koin persistence tests也未清理scope。
- 处置：`attachWorld`返回显式幂等registration；Koin child使用runtime ID、link parent并由registration close/delete，
  NoOp返回共享空registration。CoreWorld持有registration并在coordinator attach或snapshot恢复失败时只回滚这一资源；
  Bukkit factory、发布失败和正常卸载分别按实际取得顺序接入，DI均在render之后、tick之前释放。所有失败保持原异常，
  后续清理错误进入suppressed；不把CoreWorld改为通用AutoCloseable，也不关闭caller-owned snapshot dependency。独立检查另修正了manager已发布后
  启动失败只撤UUID lookup、不撤owner map的遗留；现在按exact world identity同时删除两个索引，不会删除同UUID replacement。

### CR-018：联结后 car renderer 比较了不同 train 的 revision

- 状态：已修复（2026-10-10）；P2。自动测试覆盖换属主时序，Folia实体/客户端呈现仍待游戏内验证。
- 证据：`CoreTrain.kt:574` revision按train递增；`:528` linkWith保留并移动原car对象；`WorldManager.kt:549`只给car传裸train revision；`extension/train/CarExtension.kt:50,113,117`按car保存一个latestFrameRevision且无换属主入口。Bukkit TrainExtension.linkWith为Unit。
- 触发/影响：旧列车车厢已应用高revision，新低revision列车作为survivor接入它，之后新属主frame全部被误判stale直到计数追平；旧属主排队的region frame也缺属主校验。默认collision是DERAIL，仓库没有生产listener请求LINK，不能描述为默认碰撞必现；公开CoreTrain.linkWith或CollisionEvent.requestLink可触发此能力。
- 最小建议：单独设计可区分publication stream身份/换属主代际的frame门禁，或car-owned revision；只清零计数会接受旧pending frame，不足以修复。
- 回归缺口：低revision survivor接入高revision车厢后立即更新；延迟旧owner task不能覆盖新owner。既有CoreTrainLinkTest只检查端点清理。
- 处置：每个`CoreTrain`生成不持久化的opaque runtime stream UUID，`CoreCar`用原子引用保存当前owner token；构造成功绑定、link转移、remove按exact token退休。Bukkit pending frame只携带UUID，不再让region task遍历live world/train集合；owner token匹配后才更新per-car stream/revision gate，同ID runtime重建另用新UUID，mailbox frame还须与当前train的published frame对象一致。正式回归执行old-high → link → new-low → delayed-old并验证旧stream不能切回；direct commit继续绕开published gate。无公开`core.api`、持久化、版本或link policy变化。

### CR-019：tail-release遗留没有行为的拓扑字段与错误分支

- 状态：已修复（2026-10-10）；P3，维护性而非授权漏洞。
- 证据：`Basic/.../infrastructure/RouteGrantTailRelease.kt:9,59,127` 的STALE_TOPOLOGY未产生，topologyRevision除非负校验外无读取，KDoc却声称该controller验证topology。
- 最小建议：清理误导的内部字段/分支与注释；真正的局部失效归admission engine按rail依赖处理，不能为“使用字段”恢复全局revision相等检查。
- 保留覆盖：无关拓扑编辑后旧frozen route仍可grant/extend/tail-release的现行回归。
- 处置：删除内部tail-release controller未读取的`topologyRevision`、不可产生的`STALE_TOPOLOGY`失败种类及相关调用参数；KDoc改为只声明它真实执行的world/epoch/train/冻结游标校验。准入、物理编译、catalog、registry和route invalidation中的真实拓扑校验保持不变；既有集成回归继续证明无关拓扑事务后旧冻结路线可以完成READY、扩展并按车尾释放。

### CR-020：Core/Basic测试有效性及资源释放缺口

- 本批补齐`CoreTrainReversedPersistenceTest`与`SavedRailConnectionsTest`的setup/load/断言失败清理，按physics→binding→tick释放并保留主异常。换向恢复保留source/restored bogie精确比较，另检查固定head/tail/car位置及BA方向；失败恢复由现有parent binding seam观察实际目标world，验证ID/name、rail ends、mailbox及下一真实epoch的world frame/occupancy不保留失败列车，binding已释放。23项endpoint参数覆盖及2项列车恢复测试保留，未改production/schema。此处只覆盖单列车失败，不证明整个多列车world加载事务原子性。CR-020仍partial；窄查确认`RailConnectTest`、`CoreTrainTailBogieTest`仍创建world却未关闭，RailWand完整split/merge事务集成继续开放。

- 状态：部分修复（2026-10-10）；P3，不批删测试，不把disabled计为绿色覆盖。下列保留原审查基线证据，当前处置见本节末。
- 本批更正进度：`CoreTrainPersistenceStateTest`的zero-distance在此前仍只驱动generic TickManager，尚未执行physics controller；不能由“匀速测试已改真实epoch”推断它也完成。本批才改为关闭fallback的真实coordinator，6个有界exact ticket、controller调用计数、frame epoch/revision、固定80/70/75 m摆位及car/bogie零速度断言。source/restored位置比较保留并增加独立位置预期。
- 同批仅修`WorldPersistenceRoundTripTest`、`SplitWorldSnapshotTest`和上述persistence state三文件的ownership：显式physics/tick在source/load setup前纳入use；world返回后保留binding，以physics→binding→tick顺序清理，失败保留主异常并附suppressed。真实Koin roundtrip保留，共享parent不关闭；无production或schema/version变化，7项测试数量不变。`CoreTrainReversedPersistenceTest`、`SavedRailConnectionsTest`等其余fixture及RailWand完整事务集成仍未闭合，CR-020保持partial。验证见[执行记录](../implement.md#cr-020-持久化fixture与零距离真实epoch2026-10-10)。
- `Core/.../train/TrainUniformMotionTest.kt:30-50`：expected与actual都来自本地constantSpeed累加，未断言train实际位置，speed断言被注释；仅剩未脱轨断言仍有有限价值。应改为真实epoch后published位置/速度断言；同文件6项环线测试disabled，作为待恢复覆盖，不直接删除。
- `CoreWorldRailTopologyTransactionTest.kt:439-488`：两项reader阻塞测试原先在sleep(50)前没有确认reader已进入读取路径；reader未调度也会通过isDone断言。现已捕获具体reader线程，并等待`railTopologyLock.hasQueuedThread(readerThread)`证明其实际阻塞于事务写锁，再检查未完成；finally释放writer并有界清理future/executor/world。最小内部诊断不进入公开API。
- `CoreTrainDerailEventTest.kt:36-40`：测试复制coordinator条件并自行queue通知，删掉生产`PhysicsCoordinator.kt:439-440`调用仍能绿。应由真实stepSimulation触发，再断言派发前0、后1、重复仍1；保留reason/epoch/token cleanup。
- `PhysicsCoordinatorTest.kt:474`：entered.await无期限，回归时可挂住整套测试；使用有界barrier断言，不删除exact ticket用例。
- `CoreTrainPublicationBoundaryTest`、`ImmutableSnapshotTest`、`CoreTrainDerailEventTest`及部分Koin persistence测试未成对关闭world resources；多数fixture未start，不能断言已产生大量活线程。补finally ownership，修正TrainTestUtils KDoc声称Koin parent但实际NoOp的注释。
- `RailwayObservationTest`未直接断言bodyTraversal投影和caller-owned各集合防御复制；现有排序/immutable断言有效，不能整项删除。stamp简单字段测试只作为合并候选，暂不认定无价值。
- 已完成局部：真实匀速epoch/published几何与速度、真实尽头脱轨→coordinator事件排队、live mutation快照隔离，以及这四类world fixture的paired cleanup；共18项保持不变（12执行/6disabled），独立检查和7模块强制组合回归成功，见[记录](../implement.md#cr-020-局部测试改写与验证2026-10-10)。后续CR-014将coordinator既有2处无界barrier改为5 s有界断言；本轮又完成上述reader确定时序。Koin/Basic测试仍待修，publication reverse/derail还需直接验证immutable frame在正确边界变化，不以这些局部修复结案。
- 后续局部改写：`CoreTrainAsymmetricTurnoutTest`的三项运动用例改为真实coordinator ticket完成后检查同epoch frame、固定实际位移、车尾路径/重锚和未脱轨，80次回归具有最终累计发布断言；`CoreTrainMultiCarDiagnosticTest`删除private `CommitBuffer`反射，用公开commit边界、真实50 ms epoch和固定2 m车fixture数值验证两车摆位、移动、间距及默认offset。两文件所有world/coordinator/tick owner均成对关闭，清理错误不覆盖已有测试失败；原11项测试数量不变。CR-020仍部分完成，Koin/Basic collection/bodyTraversal及publication reverse/derail immutable-frame缺口继续开放。
- 当前补齐`CoreTrainPublicationBoundaryTest`原reverse/derail两项的immutable-frame边界：pending mutation和buffer publication都保留旧frame identity，显式frame publication推进revision/epoch并发布正确方向或脱轨状态；后续`commit`证明同时发布且保持epoch，旧帧仍保留原值。车厢物理方向与运营方向区分，reason继续检查live API。未改生产代码/增加用例，CR-020仍partial；Koin fixture、Basic collection/bodyTraversal及RailWand完整事务集成缺口保留。验证见[执行记录](../implement.md#cr-020-换向脱轨不可变帧发布边界2026-10-10)。
- Basic observation局部补齐：原BA投影断言固定canonical head/tail=40/50 m及非空BA body；跨轨用例独立检查head/tail/body的rail顺序、facing及弧长，并精确保留Core列表。外层排序/复制改为直接测试Basic `RailwayObservation.create`；新增一个合并用例直接向`RailwayTrainObservation.create`传入五项非空mutable集合，清空caller集合后检查完整值及只读输出。四处world均成对清理且保留主失败。该Basic collection/bodyTraversal子项闭合，CR-020仍partial，Koin fixture及RailWand完整事务集成缺口继续开放；不代表Basic整模块审查完成。验证见[执行记录](../implement.md#cr-020-basic-observation集合与车身路径2026-10-10)。

### C1 验证与限制

- 主会话实际运行 `:Core:test :Basic:test --rerun-tasks`，14个task全部执行，BUILD SUCCESSFUL（35s）；Core212项含6跳过，Basic382项全部执行，零失败/错误。已有Kotlin unchecked cast/no cast needed等warning未在本批修改。
- 本批没有新问题的定向可执行复现，没有跑BukkitPlugin测试、部署或游戏验证；运行时代码未修改。多数已读publication/ticket/epoch/集合与局部失效校验保护实际不变量，没有确认应删除的过度兜底。
- Core/Basic均仍是部分覆盖；下批继续Core算法和拓扑，完整覆盖清单而非测试数量决定审查是否完成。

## C2：Core 整模块审查（2026-10-10）

审查基线`8f1d845d`，当时以下问题均未修复；后续处置见各项状态。完整解释、测试处置、已核查的不变量与复现输出见[Core报告](./core-module-review.md)，覆盖见[文件清单](./core-review-inventory.md)。本轮闭合Core，不代表Basic或全项目完成。

### CR-021：splitFrom失败前破坏原编组

- P2；公开能力可达，但仓库无正式调用。用户于2026-10-10确认解编保留为正式能力，本项是该能力验收阻塞，不再待确认是否删除。
- `Core/.../physics/train/CoreTrain.kt:498-514`先截断cars，再用已改变的length计算分离部分；extension选择/初始化也未形成事务。
- **已复现**：4车`splitFrom(3)`抛ClassCastException，原车变2车，世界仍只有原列车。
- 建议单独修编组事务和失败保持；不能只改takeLast。没有现有split回归。
- **已修复（2026-10-10）**：未发布且无caller的整数接口已删除，收口为`splitBefore(carId,newId)`：在稳定编组/持久化顺序的唯一目标car前分割，正反向对同一car ID产生同一连接面，两列继承原`isReversed`。Core在变更原编组前预校验car/train身份、两半端点/占用/路径和原车frame并prepare同slot extension，新列车完整构造成功后才发布原列车新结构。未知/第一/末节、重复car ID及train身份、两半几何、extension prepare/initialize失败的真实回归覆盖cars身份、controller、frame、world/rail索引、mailbox和publication owner。extension外部副作用因无通用rollback协议，明确为prepare-like实现者合同；见[train physics](../../../spec/javarailways/core/train-physics.md#解编事务)和[CR-021验证](../implement.md#cr-021-解编事务修复与验证2026-10-10)。`linkWith`的CR-030仍开放。

### CR-022：列车构造失败遗留ACTIVE车端

- P1；正常错误输入/重复名称可触发，不依赖随机UUID碰撞。
- `CoreTrain.kt:142-166`先注册两个TrainEnd再addTrain；`TrainPersistence.kt:40-58`的cleanup不覆盖constructor抛错。
- **已复现**：重复SavedTrain load被身份检查拒绝，rail车端数却从4变6，world未接纳新列车。
- **已修复（2026-10-10，首批Core代码改进）**：身份检查及两个端部位置计算前置，初始化失败仅撤销本构造已注册的controller、车端及world索引，不销毁调用方cars。实际机制见[train physics契约](../../../spec/javarailways/core/train-physics.md#构造与失败清理)，验证见[本批记录](../implement.md#cr-022-修复与验证2026-10-10)。原诊断证据保留；CR-015宿主factory部分初始化清理另批处理。

### CR-023：current存档车端与车厢脱节仍被接受

- P1；占用事实错误，不只是渲染问题。
- `CoreTrain.kt:345-361,686-691`只校验车内bogie欧氏间距，无完整车端/相邻车连续性校验。
- **已复现**：保留实际bogie弧长50.5m，却将saved head/tail设为180/140m；load成功并发布bodyTraversal/occupiedIntervals=[140,180]。
- 建议在load发布前校验完整编组；补固定损坏current fixture，不添加猜测兼容/版本升级。
- **部分修复（2026-10-10，P1剩余项保留）**：同一tail→head路径的exact rail/facing、bogie顺序/弧长间距与两端offset现在在成功恢复发布前验证；固定损坏输入与合法straight/curve/reverse/asymmetric-turnout共14项正式回归通过。6种坏输入已在旧实现确认全部被接受，另3项精确spacing/end-offset回归随实现补齐；不把后3项描述成已跑红灯。独立检查及Core/Basic组合门禁通过，详见[记录](../implement.md#cr-023-部分修复与剩余缺口2026-10-10)。
- 剩余：精确相邻车钩间距仍无法由当前restore可靠校验。R=30 m、bogie弧长6 m的合法3车编组，还原centre偏移约10 mm、首车钩残差约6.15 mm；临时诊断同时确认内部车bogies共同平移1 m、仍保持路径/顺序及两端位置时会被旧load接受。根因是保存位置缺少求解锚点、当前运动/换向与restore的centre恢复不一致，不能放宽5 mm车钩容差来假装解决。未新增持久化字段/兼容/版本，后续先统一几何还原规则。

### CR-024：碰撞broad phase漏掉跨bucket的容差内接触

- P2；生产coordinator碰撞路径可达。仅证明容差内近接触漏检，不宣称正长度相交也会漏检。
- `Core/.../rail/SweptOccupancyIndex.kt:83`未扩展ownSpan查询桶，exact阶段却使用tolerance。
- **已复现**：A=[31,31.996]、B=[32,33]、bucket=32、tolerance=.005；overlap=true，碰撞query返回null，blockingTrainIds返回B。
- 建议复用容差扩展的桶范围；不新增index或改变candidate cap。
- **已修复（2026-10-10）**：查询复用共享的容差扩桶helper，建索引仍使用零扩展；新增3项真实查询回归在旧实现全失败、修复后全通过（该类11项）。独立检查及Core/Basic组合门禁通过，详见[验证记录](../implement.md#cr-024-修复与组合门禁2026-10-10)；不改变candidate cap。

### CR-025：最近采样点冒充几何最近点

- P2；`Curve.kt:56-57`、`PrimeCurveImpl.kt:271-292`；SimpleATO OperationalDestination替换迁移及RailWand正式消费。
- **已复现**：两点LINE上4.4m得到4.0m；getPointAt(position)拒绝真实线上点，CurveException.message还是null。
- 建议按真实piecewise-linear segment投影求最近点；补稀疏LINE非整米和运营迁移断言，不把stream加密成1mm。
- **已修复（2026-10-10）**：直接投影真实相邻线段，复用getPointAt返回frame；reverse委托canonical结果，CurveException保留message。新增6项几何回归中5项在旧代码失败、修复后通过；既有reverse用例改为真正稀疏LINE，改fixture后没有再次跑旧代码红灯。Core/Basic/SimpleATO/Bukkit强制回归与Core Dokka通过，详见[验证记录](../implement.md#cr-025-修复与下游组合门禁2026-10-10)。raw-input校验CR-028单列。

### CR-026：位置前进但car/bogie速度始终从零推导

- P2；`CoreBogie.kt:58-77`与`CoreCar.kt:399-443`没有向首个head bogie注入速度。
- **已复现**：真实50ms simulation、train标量速度10m/s，car/bogie velocity仍为零。Bukkit对body另补算部分掩盖问题，bogie仍使用frame velocity。
- 建议统一Core epoch运动学输入/发布；不能让各consumer各自再补一份事实。没有证据说明ATO标量速度因此错误。
- **已修复（2026-10-10）**：保留公开distance-only API并将Core同epoch distance/speed入口定义为protected controller子类合同；基类、Basic `RealisticManualController`和SimpleATO `AbstractATO`均使用它，ATO Stall/异常freeze显式提交0 m/0 m/s。从移动后head rail tangent注入速度，沿刚体编组传播，collision/derail/停车/换向/解除controller及显式placement清除旧速度；同步修正既有infix `cross`优先级及实际bogie chord的rad/s角速度。Bukkit body/bogie现在都从各自frame world-space m/s统一`/20`为blocks/tick，不再用train speed×direction补算或把bogie m/s原样传入。真实coordinator及frame转换回归覆盖上述入口；四模块focused 52项中46执行、6个既有disabled，零失败/错误。无持久化或版本号变化；Folia/客户端现场呈现仍未验证。

### CR-027：正式load静默丢连接，且不验证接头共点

- 初审：P2；静态确认，当时未定向执行；后续修复与验证见下。
- `CoreRail.kt:136-168`对malformed/missing引用mapNotNull，并只选最近端点；相互引用但远离的rail仍可能形成拓扑edge。
- `PreparedRailWorldSnapshotPlan.kt:265-297`却严格验证引用/距离/对称，两个current入口不一致。
- 建议load边界复用严格物理校验，不保留默认旧开发数据容错；现有spec承认遗留容错，不把目标误写成已实现。
- **已修复（2026-10-10）**：load与prepared共用单端点严格decoder，单rail两端均完成解析/resolve后再写；保留合法null、顺序和第二支active，拒绝坏引用/重复/超容量/远离端点。旧resolver及仅供它使用的getDirection移除；live remove/structural-undo fallback及全图reciprocity校验保留。新增23项与修正的损坏load用例通过，其中11项在旧load已确认失败；独立检查和Core/Basic/直接consumer组合门禁通过，见[记录](../implement.md#cr-027-修复与验证2026-10-10)。不宣称全world失败无资源副作用，CR-015/017仍单列。

### CR-028：曲线raw/decode输入验证缺口

- P2；已修复（2026-10-10）。原审查时为静态确认，构造缺口已被curve spec标为TRANSITIONAL。
- `PrimeCurveImpl.kt:24-33`缺少样本数、零起点、严格递增、finite/frame校验；`:336-351`手写Vector3dSerializer缺失x/y/z直接补0。
- 建议共享构造/decode边界一次验证；不在getPointAt热路径重复兜底，不机械升版。补固定损坏输入测试。
- 处置：Core复制后统一验证≥2、精确零起点、finite严格递增arc、finite xyz及可归一化非平行frame；load共用构造器，缺xyz明确拒绝，合法非unit/非正交frame及相同位置不同arc保留。先修Core LINE stream重复末点和Basic ConstraintSolver共享接头重复，不在decoder去重。新增16项中14在旧代码失败，2有效控制通过；修复后全部通过，独立检查及7模块强制组合门禁成功，详见[记录](../implement.md#cr-028-修复与组合验证2026-10-10)。无格式/版本或runtime兼容改动；真实旧开发服数据与离线迁移尚未验证，不能据此直接部署。

### CR-029：extension仍按存档FQCN反射加载

- P2；正式codec路径确认，未做恶意输入运行诊断；不宣称远程代码执行漏洞。
- `WorldSnapshotCodec.kt:19-25`使用Class.forName，完整decode后才检查formatVersion；正式Bukkit saved render payload没有稳定type registry。
- Core persistence/extension-data规范却写stable registry已存在，shared规范/网页才正确承认遗留反射。
- 建议namespaced type显式注册，未知type失败；用户于2026-10-10确认保留`192.168.1.230`开发服数据，提供可审计/dry-run/可恢复的手动迁移，暂不要求JAR内自动迁移。真实样本尚未获取/验证，不默认加legacy reader或升版。

### CR-030：link缺方向规范化、事务结果和失败回滚

- P2；静态确认。默认碰撞DERAIL且无生产listener请求LINK，不描述为默认碰撞必现。
- `CoreTrain.kt:521-540`直接拼cars/按first解算，与reversed survivor operating head不一致；丢弃Future，失败部分mutation不能由调用方判断。
- 建议单独明确支持的端部/方向组合，预校验并可观察成功/失败；与CR-018换属主render stream身份一起验证，但不是同一个问题。
- 用户于2026-10-10确认联结保留为正式能力；该确认不改变默认碰撞DERAIL或授权自动联结，当前缺陷未修。

### CR-031：Core Seat.position自递归

- P2/P3；静态确定，未执行；无当前生产Core Seat consumer，Bukkit座位是另一条路径。
- `CoreCar.kt:61-70`匿名Seat getter中Vector3d(position)读取自身，访问会递归至栈溢出。
- 用户要求先评估Core Seat与Bukkit座位的合理分层，再决定未接线API去留；不据此宣称当前游戏座位坏了。
- [专项评估](./core-seat-api-assessment.md)建议以Bukkit模型SEAT为唯一定义/乘坐来源、删除Core Seat原型，不新增无具体consumer的共享目录。建议待用户检查，尚未实施。

### CR-032：Core旧实现/无用内部代码残留

- P3；全仓consumer/注册/测试/持久化搜索确认候选，详见Core报告清理分组。
- 明确机械候选：3个空rail/persistence文件、BinarySearch、Quaternion池、forwardPositive未读list、DummyAsset、CoreWorld未用signals字段、废注释。
- **部分完成（2026-10-10，本批废路径已清理）**：机械项见[首批记录](../implement.md#cr-032-机械清理与验证2026-10-10)；随后经二次全仓consumer/序列化核对，已删除旧
  `RailTopologyEditor` split/merge mutation、prototype `TrainFactory`构建链及只验证这两条废路径的3项测试，见[第二批记录](../implement.md#cr-032-废railtopologyeditortrainfactory路径清理2026-10-10)。
  保留现行prepared pipeline、`mergeEligibilityError/SplitResult`真实RailWand consumer与独立eligibility测试，以及
  current `SavedTrainV1/SavedCarV1/SavedBogieV1/CarConfigV1`恢复链。冻结回归Core 308（6 skipped）+ Basic 384零失败，Bukkit直接consumer编译、Core Dokka/JAR检查通过；无格式/版本改动。
- 现有测试只分别证明prepared plan、stale、Core transaction rollback与eligibility；尚无完整RailWand split/merge跨层事务测试，且其余公开面/维护候选仍单独评估，因此不将CR-032或Core审查整体结案。
- 用户确认目前无仓库外插件；未用异常/seat等公开面仍按扩展价值评估，不直接按无caller删除。联结/解编已确认保留。Signal和DynamicRenderable.lastState有真实consumer，不删。
- [旧事件专项评估](./core-event-api-assessment.md)初稿删除建议已撤回：用户要求保留PreTickEvent/PostTickEvent/RenderableEvent并考虑接入，从废代码删除候选中排除。暂无publisher只说明未接线；分组接口不必独立发布。接入阶段/线程契约尚未冻结，现有observation不作为所有事件用途的等价替代。

### CR-033：文档、诊断与可维护性不一致

- P2/P3；扩展CR-002/007/020，不重复宣称已修。
- 错误诊断丢message/cause/context；可用frame/占用语义KDoc不符；train-physics遗漏实际bogie/coupler二分原理；持久化把目标预校验写成现状。`Train.cars`已在CR-021明确为稳定编组/持久化顺序，并用公开`isReversed`/`operatingCars`表达运营顺序，不再是本项开放问题。
- 两处结构准备实际O(N²)，高于spec承诺；CoreWorld重复prepared校验适合局部抽取，不按行数大拆manager。
- 网页模块归属/API范围和性能结论过期；.codeabs含已删路径/TODO。Dokka本轮成功但缺文档和declare链接warning仍在。
- 测试处置扩展见Core报告：保留真实不变量；改写空跑、回显、复制生产分支和反射锁private实现的测试；废路径测试随实现删除。没有按数量批删依据。

### CR-034：EventBus并发注册/空桶清理可能丢listener

- P2候选；公开API静态可达交错，未定向复现；当前宿主启动/关闭串行，没有正常生产并发触发证据。
- `TypedRailEventBus.kt:48,95-96`取HandlerList与add不原子，cleanup可能移除刚取到但尚未写入的bucket。
- 建议只在低频register/close/unregister串行化，或明确并强制owner；不在publish热路径增加大锁。
