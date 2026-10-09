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

- 状态：已确认，待修复；P2，编辑保存恢复问题，不是行车授权错误。
- 证据：`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/persistence/SimpleSignalsWorldDefinitionStore.kt:86-95` 先加入 preparedWorlds，随后在 try 外调用 Files.createDirectories；失败绕过 catch 内的 remove。
- 影响：例如 worlds 路径被普通文件占用，第一次保存失败后，即使外部修复路径，同一 store/world 的后续保存仍被 already prepared 拒绝，直到重新创建 store。
- 最小建议：将目录创建纳入同一个准备失败释放边界；不新增重试、兼容模型或版本号。
- 验证缺口：补真实文件系统回归，先制造目录路径冲突，再修复路径，使用同一 store/world 保存并加载成功；保留既有 primary replace 失败/旧 disk-live 配对测试。

### CR-011：最后一次编辑取消被误分类为保存失败

- 状态：已确认，待修复；P3，typed status/诊断问题，不会写盘或放行。
- 证据：`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt:1650,1673,1699-1700` callback 入口 guard 失效返回 WORLD_UNAVAILABLE，beforeCommit 内最后 guard 失效只抛普通异常，callbackStarted 将其归为 PERSISTENCE_FAILED。
- 最小建议：显式区分取消/失效与真实持久化异常，保留最后一次 guard；不拆出第二事务协调器。
- 验证缺口：既有 manager test 的 recording commit cancellation 在 callback 入口前失效；需覆盖进入 callback 后、primary rename 前失效，断言取消状态及 disk/source/publication 均不变。

### CR-012：过期选择测试没有验证清除已选第一点

- 状态：已确认，待改写；P3，低质量测试断言缺口。
- 证据：`SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionWandHandlerTest.kt:72-82` 名称宣称 clears old selection，但没有先建立 firstPoint，也未检查清除后的下一次 backend 输入；生产 handler 第 132 行的清除缺失仍不能由这些断言检出。
- 最小建议：改写原测试，先成功选第一点，再触发 stale，恢复新 capture 后继续点击，断言 backend 收到 firstPoint=null；不删除有价值的 stale 拒绝覆盖，不按数量减测试。
- 编辑器结论：本次未确认 SignalWandHandler/SectionWandHandler 的生产行为缺陷；已有 backend/actions 与事件协调分层，不进行无证据大拆分。
