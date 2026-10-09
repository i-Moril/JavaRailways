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

- 状态：修复与回归完成，待提交后用户检查；P2，不宣称旧残留已造成运行错误。
- 证据：`Basic/.../pathFinding/PathFinder.kt:13`、`BasicWorldData.kt:20` 和 `SimpleATO/.../controller/SimpleATO.kt:1105`；RailMap 仍被创建和读取，初审未发现生产写入。
- 处置：统一使用当前拓扑寻路与 RailObjectStore；迁移 RailMap 文件中的有效 World 访问扩展，保留有价值的回归断言后移除旧路径。
- 验证：全仓 producer/consumer 搜索、Basic/SimpleATO 及下游回归；不得直接删整文件和全部旧测试。
- 实施：删除PathFinder/RailMap/RailDestination、BasicWorldData.railMap和ATO旧索引读取；保留Core静态信号/动态车端与RailObjectStore观察。World访问器移到Basic根包，fixture移到basic.testing，删除TestRailMapRegistry；所有调用只机械改import，无兼容alias或第二个实现。
- 测试保留：旧索引专用测试删除；显式注册测试迁移并扩展为3项（缺失不自动创建、精确对象身份、world隔离）；ATO同epoch与跨epoch重复ref的停车断言仍在，改用真实静态信号加当前RailObjectStore构造冲突。
- 本轮验证：Basic 379、SimpleATO 351、SimpleATOBukkit 2、BukkitPlugin 353、SimpleSignals 485、TunnelBridgeBuilder 31，共1601项实际执行，零失败/错误/跳过；Core:test为UP-TO-DATE（212项，6跳过），BukkitPluginDev:test为NO-SOURCE，编译成功。Java consumer编译、Basic Dokka和主插件shadowJar成功；Dokka仍有既有缺文档/链接warning，不代表文档全面合规。
- 产物检查：Basic编译输出及Basic/宿主JAR没有旧pathFinding类；宿主JAR仍可能保留空目录项，不是旧代码。未更新或使用旧reobf产物，没有部署/游戏内验证。
- 最终复核：全仓生产/测试源码无旧包、三类及TestRailMapRegistry引用；`verifySignalModuleBoundaries`和`SimpleSignals:verifyPluginJar`实际执行通过，任务context校验及`git diff --check`通过。

## CR-004：metadata 的平铺/分组双重调用模型

- 状态：已确认；P2，可维护性问题，尚未证实数据丢失 bug。
- 证据：`Basic/.../rail/RailInfrastructureMetadataStore.kt:17-80` 旧 constructor/getter/copy 与 data class current shape 并存，RailWand 生产调用混用。
- 处置：业务调用统一为分组 current model；单独保留确有用途的一次性迁移 decode 边界，不新增 runtime compatibility 或 schema 升版。
- 验证：metadata 更新/复制、轨道编辑和持久化 fixture；先阅读版本基线规范。

## CR-005：公共 API 的实现类型泄漏

- 状态：已确认；P2，正式边界与可维护性问题，不能只凭包名决定搬迁。
- 证据：`SimpleATO/.../api/line/LinePlanner.kt:25` 的 ExpandedRoute 和 `api/mission/AtoMission.kt:82` 的 PlannedRoute 位于 Basic 非 api 包，Dokka 隐藏这些实现包。
- 处置：审核 route 值的正式归属；将必要通用值纳入最小公开契约并迁移 consumer，不新增一套重复 route/snapshot。
- 验证：公开签名搜索、直接 consumer 编译、Java consumer、Dokka 和现有模块边界门禁。

## CR-006：运营编辑适配器归属未收口

- 状态：已确认；P2，尚未证实影响游戏运行。
- 证据：`BukkitPlugin/.../tool/RailObjectWand.kt:832,870` 处理 Destination/Station/Depot 编辑，而 SimpleATOBukkit 目前以接线/诊断为主。
- 处置：将运营编辑及对应状态/测试放入 SimpleATOBukkit；宿主保留通用工具入口、公共方向编辑和平台基础能力，不制造双 owner 或反向模块依赖。
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
