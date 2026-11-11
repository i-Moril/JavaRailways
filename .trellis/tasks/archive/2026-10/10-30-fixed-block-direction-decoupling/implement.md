# 实施与验收计划

配套文档：[需求与验收](prd.md)、[技术设计](design.md)、[源码证据](research/current-behavior.md)。用户在计划交付后明确要求“修改代码”，现已进入实施阶段；下列清单随验证更新。

## 0. 启动门禁

- [x] 用户授权创建计划；随后明确授权实施，当前任务为 `in_progress`。
- [x] 需求、设计、源码证据已形成，PRD 按最终结构收敛。
- [x] 用户在最终规划摘要后明确要求“修改代码”，授权实施，任务已启动。
- [x] 实施前重查分支、HEAD 与工作区。基线 `a59606ba6de0df9a134ff37fe9559bf64b6de680`；已有五项无关未跟踪文件不纳入本任务。
- [x] 已加载 Trellis Phase 1.4 / 2.1，按批准计划启动，使用 implement/check 子代理并由主会话协调复核。

这是共同验收的一组跨层修改。可以分批本地实现，但不能发布只删创建检查或一半消费者的中间版本。

## 1. ATO 方向计算与运行约束

对应 R2、R3、R6、R8；验收 AC03–AC06、AC09、AC12。

- [x] 在 SimpleATO routing 层增加／提取小型内部纯方向计算：精确 frozen route + 完整 Basic 方向读取 → 首个禁行边界及绑定结果。不新增方向副本。
- [x] 核对运营前缀、停车偏移、车身路径和全局停车余量；既有后缀复用也必须验证，不能只看新追加部分。
- [x] 内部结果完整传递到候选、静态时刻表、接续、形成输出、换向和失败路径，不跨 binding 复用。
- [x] 最终补全路径确定后，明确证明必需行驶前缀合法；保护后缀够长不能掩盖 margin failure，拓扑／replacement 校验继续成立。
- [x] 在现有 authority source 的同一 snapshot 加入独立方向 `MovementAuthorityRestriction`；真实 Basic grant 和原物理边界照实保留。
- [x] pending、拒绝、同向接续、后缀扩长、adoption、换向 suspend/rebase 及连挂接触路径不提前放宽、不丢约束。
- [x] 通过既有方向 epoch 和 rail 索引定向失效；无信号 source 快速返回也覆盖 ATO 自身限制；正常 tick 不增加世界扫描。

主要文件均位于 `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/`：

- `routing/FrozenRouteSelector.kt`、`FrozenMissionRouteAssembler.kt`、`TerminalRouteMargin.kt`、`RoutePlanningRestrictionRuntime.kt`；按需增加单职责内部纯计算文件。
- `inspection/InspectionRuntime.kt`、`timetable/TimetableDispatcher.kt`。
- `admission/RouteAuthorityAdmissionClient.kt`。
- `signal/SignalEpochRuntime.kt` 及现有 runtime 组合点，仅处理有界失效接线。

## 2. 删除闭塞单双向权限和旧策略

对应 R1、R4、R5；验收 AC01、AC02、AC07、AC08。

- [x] `FixedBlockDefinition` 删除 flag，`BlockPathBuilder.definition` 不依方向决定创建或倒转保存顺序。
- [x] `SectionAuthoringActions`、`SectionWandBackend` 及调用方删除只服务闭塞的方向读取，保留保护区域方向读取。
- [x] `CompiledFixedBlock.entryTraversals` 始终包含两端，覆盖编辑影响、边界删除与相邻区间匹配。
- [x] matcher 始终识别两方向，简化 `detectReverse`；保持内部起终点、重复 window、中途偏离和歧义规则。
- [x] 删除 `FixedBlockDirectionPolicy`；清理 contributor、inspection、plugin、world runtime、仿真 feature、Java consumer 与测试注入。
- [x] `SectionOccupancyRuntime` 删除由旧策略产生的 blockControls，保留拓扑不可用、等待基线和未知失败门禁。
- [x] 按同一个 block ID 贡献资源，以最后已申请出口释放；内部起步保留 authority floor，不创建车后入口声明。

主要生产目录为 `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/{block,authoring,runtime}`，另有 `SimpleSignalsPlugin.kt`。

## 3. 两端信号与扫描

对应 R4、R6；验收 AC02、AC07、AC09、AC12。

- [x] `FixedBlockSignalEntranceResolver`、`FixedBlockSignalCompiler` 删除 reverse 必须双向的条件；保留实际入口、朝内方向、0.75m 容差、唯一性和引用校验。
- [x] `SignalWandBackend` rebind 提供两端；不重写既有 signal ID、entryDirection 或模型 placement。
- [x] `SignalObservationGraphPublication` 始终构造两个方向，各端独立扫描下游并共享占用。
- [x] 无规则与已配置但缺状态仍区别处理；入口新增／解绑／删除、attached signal、source 替换、拓扑重验和隔离仿真保持原子发布。

## 4. 当前存档与提示

对应 R7；验收 AC10、AC11。

- [x] `SimpleSignalsWorldDefinitions.kt` 删除保存字段、转换参数和专用 serializer，current schema 保持 1，reader 继续严格拒绝未知字段。
- [x] 更新 current JSON fixture、测试和名称；保留两端入口、路径顺序、ID、revision 与 placement；不添加旧开发 reader 或 unknown-key 放行。
- [x] 更新中英文 wand 创建文案，移除闭塞单／双向分类和参数。
- [x] 后续授权部署时已执行：正常停服与完整恢复点、实际current codec、primary／backup dry-run及独立候选转换，安装只删25个旧字段；见 [results/deployment.md](results/deployment.md)。

## 5. 验收场景

- [x] 创建／匹配：混合方向、混合 canonical AB/BA、多轨道两端、内部端点、重复经过；原道岔、冲突、重叠、歧义拒绝仍成立。
- [x] 方向：双向部分内合法折返；实际逆行、换向后车身涉及禁止方向、缺完整快照均拒绝。
- [x] 余量：名义停点合法但安全余量非法；保护后缀够长仍拒绝；复用已有后缀也不能绕过。
- [x] 边界：Basic grant 覆盖更长保护路径，原边界如实保留；ATO 更短方向约束有效并使用现有统一安全距离。
- [x] 真实运行：保留原全双向返库回归，新增两条普通连接轨道的混合方向 50ms Dispatcher 场景，在双向部分停车、换向、返回、再次换向并合法出清。观察全过程 claim／grant 身份、相向冲突和真实车尾释放。
- [x] 生命周期：pending、拒绝、source 丢失、相关方向／拓扑变化、无插件和无关轨道编辑。
- [x] 信号：两端独立规则、唯一性、无信号资源准入、有信号未知状态零速、attached device 与模型摆放不串改。
- [x] 存档：固定 fixture、严格字段／版本、primary 损坏后的有效 backup 恢复、保存失败 disk/live 仍为旧值。

优先扩展的现有测试：

- SimpleSignals：`SectionTopologyTest`、`FixedBlockCatalogTest`、`FixedBlockRouteResourcePolicyTest`、`FixedBlockStopContinuationTest`、`FixedBlockInspectionSourceTest`、`StoppedReturnToDepotIntegrationTest`。
- SimpleSignals：`FixedBlockSignalEntranceResolverTest`、`FixedBlockSignalCompilerTest`、`SignalObservationGraphPublicationTest`、`SimpleSignalsWorldRuntimeManagerTest`、`SimpleSignalsWorldDefinitionStoreTest`、`DirectionalSignalPersistenceTest`。
- SimpleATO：`TerminalRouteMarginTest`、`FrozenMissionRouteAssemblerTest`、`InspectionRuntimeTest`、`RouteAuthorityAdmissionClientTest`、`SignalEpochRuntimeTest`、`TrainRestrictionConstraintAdapterTest`、`TimetableDispatcherSharedAdmissionTest`。

### 实际验证

聚焦测试、独立复核修正和受影响模块完整门禁的命令、进程配置与实际结果见 [results/verification.md](results/verification.md)，复核问题见 [results/review.md](results/review.md)。

`Basic:test` 验证原机制，不代表修改 Basic 生产代码。Java consumer 使用 `:BukkitPlugin:compileSignalApiConsumerJava`，JAR 检查使用 `:SimpleSignals:check` 和 `verifySignalModuleBoundaries`。真实游戏内视觉／操控验收是部署后的独立证据。

## 6. 规范同步与交付

- [x] 更新 SimpleSignals backend index、`bidirectional-fixed-block.md`（如改名同步全部引用）、section wand、定义编辑影响文档，删除旧 flag 和整块方向失配合同。
- [x] 更新 SimpleATO terminal completion、运营选路／authority 及必要约束消费文档，明确真实 grant 与额外方向边界。
- [x] 更新 Basic operating-directions 文档中 SimpleSignals 消费方式；方向数据所有权与公开 API 不变。
- [x] 最终审查 Basic/Core 生产目录无变动，无第二份方向 store、runtime compatibility 或换名保留的幽灵权限开关。
- [x] 记录 AC01–AC12 的实际证据、未验证项和风险，区分静态、单测、真实离线运行与游戏验收。
- [x] 交付可审阅改动；本任务未授权提交、推送、部署或执行数据转换。

## 回退与范围门禁

部署或转换前保留代码基线与数据恢复点；半成品不安装到服务器。运行失败保持原 grant／claim 与停止约束，不能清锁恢复。已转换数据使用原恢复点回退，不新增降级 writer。若需要 Basic/Core API、多闭塞协调或手动强制，先修订计划并评审，不能静默扩大本批。

## 首次代码交付状态（提交前）

代码实现、独立复核、规范同步与完整离线门禁完成，AC01–AC12 的证据见 [results/verification.md](results/verification.md)。改动未提交，任务保留 `in_progress` 供审阅；不执行带自动提交的归档或 journal 脚本。游戏内验收和条件性开发数据转换属于后续部署步骤。

## 后续提交与部署授权

用户在代码交付后明确要求“提交并部署”，已授权本次本地提交、测试服正常停启、恢复点及必要的一次性开发数据转换；不推送远端。原有五项无关文件保留。主代码提交188c24c6。只读检查发现测试服实际部署源为4c19f7f4，领先原任务基线；隔离工作树合入该已部署分支，保留既有程序编辑、路径点剪枝和仿真功能，再按真实集成版本重验。相对于已部署版本，Basic/Core无改动。部署及转换证据另见results下的deployment/migration记录。

## 最终交付状态

用户授权的提交与部署已完成，功能源188c24c6，保留已部署功能的集成源220e705e；主分支已快进，未推送。完整3001项JVM与323项前端测试通过。13项转换测试、11项本地安装脚本检查、停服恢复点和实际部署核验通过。详见 [results/deployment.md](results/deployment.md)。仅归档本任务并记录本轮journal；其他任务和五项无关文件保留。
