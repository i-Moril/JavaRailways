# 父任务最终验收覆盖复核（2026-10-20）

## 最终结论：PASS

父任务 R1–R6 / AC1–AC7 及最终集成清单的源码与本地自动验收覆盖通过。初核发现的两项确定证据缺口，已由最小测试补齐并实际执行通过：真实 observation 构造的 LIVE–LIVE / LIVE–SIM / SIM–SIM 三组，以及模拟 capture/create/run/close 对非空 LIVE frame/列车/claim/Dispatcher/正式信号状态的逐阶段不变对照。当前没有剩余确定产品问题或功能验收缺口。

最终53路径候选 `c1d805b99ba0bff1ed7cd2c18b49b6bf6152b6785a31431c93ac26a959fdabda`，独立逐文件核对无漂移、重算canonical fingerprint一致。直接解析完整364份最终XML，2507个实际testcase、0失败/错误/跳过；新三组observation和LIVE单例都实际发现且各执行一次。API消费、模块边界、打包、Dokka与108项前端/生产构建通过。完整gate复用了本会话已通过且未变的任务输出（65任务中6执行、59 UP-TO-DATE），没有宣称八模块全部强制重跑。文档源码与前次已通过docs-site check逐字相同，本次未再跑该检查。

结论限于本地源码和自动测试；未执行Paper/Folia/客户端/浏览器现场验收。保留上游未知host故障全进程fatal halt政策，普通guest/business故障按原目标规则处理；不承诺同JVM CPU/GC/进程故障隔离。用户暂不合并要求继续保持，未提交、合并、推送、部署或归档。root负责最终任务框回填、manifest/whitespace与恢复patch收尾；下方“待补/执行中”均为保留的初核过程记录，以本节及末尾最终证据为准。

## 当前结论与确定证据缺口

这是对父任务 R1–R6、AC1–AC7 和 implement 最终清单的独立覆盖核对，不重复三个 P2 的产品审查。不把未勾选任务框当成实现缺失，也不把旧基线门禁当成当前执行证据。

本次初核基线为 `dev/signal-system@87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7`，候选为 `codex/simulation-world-alignment` 的 52 路径 `c781ed4f569c5bc2810d4e20faa465e32d1c8bb96f29adf330f741dc4807452f`（包含两项明确删除）。逐文件对照 review-candidate.json，现存文件哈希一致、删除项不存在。根会话正在执行父完整门禁；本代理未启动构建。

截至初核，没有发现必须改产品才能完成父目标的确定缺陷。但是两个父验收条目的自动证据不完整，不能直接关闭父 AC4/AC7：

1. **真实 observation 构造 barrier 的模式矩阵尚不全。** `BukkitPlugin/.../api/internal/DefaultRailwayObservationServiceTest.kt:198` 的真实 `RailwayObservation.fromCore` 暂停确实让 B 完成物理位移、current/publish 和共同 afterPublication；然而 `ObservationWorld:401` 的 createTestWorld 不传 mode，两边都是默认 LIVE。父 implement 明确要求 observation 与 JS 内部 barrier 的 LIVE–LIVE / LIVE–SIM / SIM–SIM 组合。JS 的三组已有，不能替 observation 作证明。最小补齐是参数化原用例的两个 mode，并传到原 ObservationWorld；保留真实 fromCore 列表读取 barrier 和物理/域断言，不加产品 hook。
2. **模拟前后 LIVE 的非空运行状态尚无完整对照。** `FullFeatureSimulationIsolationTest.kt:70` 真正经过 HTTP capture/create、正式四灯、s100 与 Dispatcher，并在结束时断言 source liveTrain 的 publishedFrame、controller 和空 Dispatcher（:157–159）。但 source 本身未安装信号；fixture 没有既存 LIVE route claim/非空 Dispatcher；也没有 source World frame 与信号状态前后断言。Signals manager 的正式链、方向不变与 survivor 继续推进测试不等于这组前后不变断言。最小补齐是用现有真实 LIVE/PreparedSimulationFeature 测试设施，在源具有非空 claim、Dispatcher 状态及已发布正式信号后记录 source World frame、列车对象/帧、claim snapshots、Dispatcher statuses、signal snapshot；从捕获创建/运行/关闭 SIM 后对照。业务运行来自原真实安装路径，不用手写假 claim 或空 signal source。

根会话已接收两项缺口，负责最小测试补齐；本文件将在补齐及新门禁到达后追加复核结果。没有建议增加 Bukkit 平台模拟、进程资源隔离或任意第三方自动克隆。

## R1–R6 对照

- **R1：实现覆盖，最终执行证据待刷新。** LIVE `RailWorldFactory` 与 SIM `IsolatedTimetableSimulation` 经共同 `OperationalWorldRuntime`、Core physics、Basic admission/resource arbiter、SimpleATO safety/Dispatcher。`SimpleSignalsSimulationFeature.install` 复用定义编译、occupancy/selection、SignalPackEpochRuntime、FixedBlockDomainResourcePolicy，注册 epoch、planning、两类 inspection、provider/contributor 及 admission update。`SimpleSignalsWorldRuntimeManagerTest:6324/6387/6480/6700` 分别真实覆盖占用释放、claim/冲突/尾释放、保护进路/inspection/ATO 与折返；不是空 source。`FullFeatureSimulationIsolationTest:70/165` 联合真实四灯+s100+时刻表及碰撞 owner transaction/编组。闭塞停车/保护/折返和碰撞检测分别由实际专题 fixture 覆盖，不能说单个联合用例包办所有功能。
- **R2：实现覆盖，保留上述两项验收证据缺口。** `CoreWorld.executionOwner`、每 World coordinator/tickManager/module stores 与每安装 SignalLogicRuntime/Engine 独立；静态 capture 不携带源 provider、Context、预约或活动列车。WorldTickRunner 的真实 owner barrier、observation 构造、Signals 实际逻辑 monitor、SIM 同源双 target 共同证明各阶段；需补 observation 模式矩阵和非空 LIVE 状态对照。相同 train ID 在 WorldTickRunner、WorldEvent、SIM 行为/安全用例里覆盖，非全局 trainId map。管理事务仍协同，见下文。
- **R3：通过源码与正式单元覆盖。** `WorldEvent` 携 exact World，TrainEvent 从 train.world 派生，CollisionEvent 接 World。`Core/.../api/event/WorldEventTest:33` 覆盖八事件、同名车、两模式、两总线；CoreTrain split/link/derail 与碰撞 fixture 验证真实决策/成功路径。平台 reverse/debug consumer 按 mode 分流；SIM 目标 listener 自己处理 onReverse，不跳过领域语义。未引入逐次事件发布归属校验或 Environment。
- **R4：通过源码与真实协调器回归覆盖。** `PhysicsCoordinator:618/788` 在真正 epoch 计算前发 Pre、完整 frame 发布后发 Post，通知不放在 coordinator 提交锁里；`PhysicsEpochEventsTest:50/104/135/171/196/236/278` 覆盖 normal/fallback/simulation、空 World、coalescing、delta、不可变 frame、关闭/失败、listener 异常和锁外回调。ticket 成功不被解读为 Bukkit 效果完成。
- **R5：通过实现与分层 fixture 覆盖。** 没有 publishCollisionEvents 模拟开关；`PhysicsEpochEventsTest:306` 和 `CoreTrainCollisionCouplingTest:249` 在两模式使用相同碰撞结果路径。逐车 SIM extension 直接使用 LIVE 相同 `CarBehavior`，共用 `TrainBehaviorRuntime`、`CarOperationBatch`、`ModelOperationTicket`；不再按 modelActionDurationTicks 估算成功。目标扩展由 preparation.create(target World) 新建，缺 opt-in 不能静默过滤。
- **R6：实现符合，最终 gate 与父记录待汇总。** reuse 原领域安装点、现有 EventBus、owner、arbiter 与正式编译器。Bukkit factory capture 检查 required owner，`TimetableExtensionRegistry.prepareSimulation` 对未支持 provider 明确失败。无持久化 schema 变更/旧数据兼容/新沙箱/重复算法。全 Feature 结论限于仓库当前插件领域模块，不含未提供的服务器第三方插件或 Bukkit 效果。

## AC1–AC7 对照

- **AC1：覆盖。** `WorldEventTest` 两模式八事件 exact World 与同名 train；真实联结/解编、脱轨、过信号 fixture 仍执行原事件。SIM 行为 listener 用 `event.world === world`；两同源 target 的同 ID runtime 不串用。
- **AC2：覆盖。** PhysicsEpochEventsTest 的参数化真实 wake path、coalescing、queued close、compute/processor/closed failure、ordinary listener failure。Post 带本次不可变 frame，完整发布前不产生成功通知。
- **AC3：覆盖。** 相同碰撞 fixture 两模式触发事件及 derail，collision coupling 双模式实际事务；联合 fixture 确认吸收无 controller 车后 ATO 解析其真实 profile、pending 逐车 JS 不重建。联合用例主动调用碰撞 owner 事务，不宣称它替代 Core 接触检测测试。
- **AC4：待补 observation 模式矩阵后可最终核。** owner/scheduler 和 same-World 串行已由 WorldExecutionOwnerTest、WorldTickRunnerTest、WorldHttpRequestIsolationTest 证明；真 fromCore 构造与 entry 失效/迟到转换已有，模式矩阵差一层。跨 World provider 检查/提交、unload/disable、manager 部分安装与同 UUID clear/install race 有实际 barrier 回归。资源重载和 handoff 的源码/自动组件证据见下文，不声称实际 Folia 操作已验证。
- **AC5：源码与正式自动 fixture 已覆盖，待当前父 gate 汇总。** Signals manager `formal rule execution is independent across real live and simulation worlds`(:6251) 的 CSV false/false、false/true、true/true 分别为 LIVE–LIVE / LIVE–SIM / SIM–SIM。ThreadMXBean 确认 A 真阻在实际 SignalLogicRuntime monitor；B 实际位移、正确四档/速度/epoch；A 同 World 第二 step 等待；关闭 A 后 B 再 step。正式 occupancy/route/inspection/claim/tail/reverse 与不可用规则有独立实际链测试。普通 guest/business 失败局部，fatal host failure 见下文限制。
- **AC6：共同逻辑与两出口接线覆盖。** LIVE CarExtension 与 SIM extension 使用同 CarBehavior；相同方向 helper，SIM 无驾驶 session 按真实无 session 公式。CarBehaviorTest 覆盖持久 JS、门侧冻结、多个延迟全部开始后完成、缺效果/失败、销毁/metadata 副作用。SIM gateway14用例覆盖多车/同源 targets、拒绝、延迟 owner commit、取消终态、真实 split/reverse/link/destroy、guest 错误只处理 exact 当前 consist；LIVE gateway3用例验证实际 future 完成/取消/失败终态。正式 s100 两车混装方向和真实 Dispatcher DOORS_CLOSING gate 在联合用例覆盖。LIVE 无 renderer 的 held scene 与 SIM 静态能力验证有明确平台状态差异；不在排除的可见性/音响播放上声称等价。目标日志格式可由源码直接核对，下面单列证据强度。
- **AC7：尚待上述两项证据补齐和当前完整父 gate。** 之前 c781ed4f 的1189只覆盖两个变化模块；ae862 的2491/前端108属于前一候选。不能相加或称当前所有模块已再次测过。当前活动 root gate 完成后须比较最终全部源与XML，再决定可关闭。现场游戏/浏览器验收继续如实记录未执行。

## implement 最终集成清单逐项

1. **子任务1–4自身 AC**：1/2已整合基线；3信号与4逐车实现已进入候选，功能证据如上。旧 PRD/implement 勾选状态落后于当前实现，父汇总需要根据本次 gate 更新，而非重新实现已接线部件。
2. **真实 Core/Basic/SimpleATO/SimpleSignals 与正式四灯联合**：FullFeatureSimulationIsolationTest 的两个正式用例，加 Signals manager 的 protection/reversal 与 Core 两模式接触检测，覆盖要求；每个断言的层级分别记录，不将 test-only Java bridge 当公共生产 SPI。
3. **LIVE/SIM、同源 SIM/SIM 真并发与同 ID**：WorldTickRunnerTest:25 同 ID LIVE A暂停、另一 LIVE 与SIM实际各移动0.25m；Signals三模式矩阵真物理阶段并发；FullFeature:70 同capture同 scheduled-train ID，A owner暂停B完整完成时刻表，A释放后逐阶段轨迹相同；关闭 A 后 B 能再提交一个 signal epoch。不同层互补，无须为了“一个大用例”复制产品算法。
4. **observation/JS内部 barrier**：JS三组满足；observation仅LIVE–LIVE，需上述最小参数化。
5. **LIVE全状态不变、平台效果/诊断/资源关闭**：源 train 帧/controller/空Dispatcher已有，但非空 claim/Dispatcher/信号完整对照待补。SIM production outlet没有 Bukkit scheduler/model/entity/audio调用；SimulationEventBoundaryTest 在无renderer/插件实例且CSV开启时真实发布 reverse/derail consumer，证明不碰平台。SimulationCarBehaviorExtension:65/96–97 的正常/错误日志包含 simulation、target runtimeId、car/current train；SimpleSignalsSimulationFeature:49包含 target UUID/原Throwable。guest-failure测试证实目标exact当前车和目标列车，未捕获全JVM日志内容作字符串assert，不能称日志sink集成测试。真实close先等worker终止，再退目标资源；destroy/取消/失败/清理失败均有fixture。
6. **epoch通知/失败合并/关闭取消**：PhysicsEpochEventsTest、IsolatedTimetableSimulationTest:472/488 和 gateway lifecycle covered。取消等待物理/generic callbacks 真退出后才关 Feature，不以 ticket.cancel 代替退出。
7. **重载/卸载/handoff与提交锁**：TrainResourceReloader:50–57 保留 LiveWorldManagement + stable UUID顺序 runWithWorlds +各World processBetweenFrames，整个一致检查与发布在联合准入内；ModelManagerReloadTest/TrainResourceConfigurationTest 覆盖当前type拒绝/旧版本保留/安装配置语义。DefaultInfrastructureResourceServiceTest:98/170 真实 barrier验证 planned removal 等 exact epoch、check/commit期间另一World新admission不得穿过；WorldManagerUnloadTest等真worker退出及sameUUID replacement。TrainControlManager:111–133跨World先完成旧owner release，再向新World submit，per-player identity检查仍在；TrainControlWorldIsolationTest:19证明并发只能装一个session及exact-world retire。此项是源码合同+真实组件自动测试证据，没有直接跑玩家在Paper/Folia座椅/region跨World handoff；这属于已明确的现场边界，不据此新增平台模拟器。epoch callbacks锁外有真实线程测试。
8. **必需 Feature 不可静默遗漏**：factory:123–129 的 required owner差集、DefaultSimulationFeatureServiceTest身份/disabled/失败/锁外prepare、factory:240拒缺simplesignals、TimetableExtensionRegistryTest:98拒未支持provider并每target新建；相同业务逻辑替代估时gateway已删。
9. **UI/坐标/旧事件/拆分规范**：当前基线已含既有UI/坐标；候选无前端与持久化模型变更。父最终前端/构建与规范回填由root gate/收尾核，不能用静态零diff宣称运行浏览器通过。SimpleSignals平台拆分仍明确非全完成，本次只抽出真实运行所需中立rules/domain policy。
10. **diff/task归属/保留WIP、无部署**：本代理只新增此报告，不改产品/测试/其他文档，不构建、提交、合并、推送或部署。root负责最终完整source manifest、temporary-index whitespace、task validate与恢复patch，避免遗漏untracked。

## 已确认失败边界

`dev@87fe3e45` 的 RailwaySimulationHalt 是既有未知宿主故障全进程停止政策，终止同进程 LIVE/SIM。保留此政策不是新增World隔离缺陷，也不能宣称任意fatal故障按World隔离。普通script guest错误、正常拒绝、不ready/Unavailable及本地操作失败仍走原目标规则；Core RailwaySimulationHaltTest和SIM guest用例分别验证，不混为一类。

同JVM共享 CPU/GC/进程崩溃及第三方脚本永久不退出不属于本任务承诺。没有Paper/Folia服务端部署、客户端效果或浏览器现场验收；自动fixture和源合同不能冒充这些结果。

## Verification（初核）

- 静态：父 R1–R6 / AC1–AC7 / 十项最终清单核对完成；两项确证测试证据缺口已交root。
- 已有证据：P2 affected1189/13new、历史alignment2491及对应reports已对照其候选范围，不扩大。
- 当前完整 lint/type/build/test：root执行中，本代理未启动或抢占Gradle令牌；结果及最终哈希待追加。
- 产品修复：本代理无修改；不重复报告已修复P2。

## 补测静态复核与前置完整门禁

根会话在补测前已完成 c781ed4f 的当前完整 gate。检查者直接解析 `../simulation-parent-acceptance-baseline-20261020/evidence/before-additions/*/TEST-*.xml`：364 suites、2504个实际testcase，与suite tests数逐模块一致；Core505、Basic390、SimpleATO375、SimpleATOBukkit2、Bukkit542、SimpleSignals647、TunnelBridgeBuilder31、WebUI12；failure/error/skip均0。因此此前“当前完整门禁执行中”已经有这份明确前置结果；它不包括随后新增的验收用例。

observation 补测已完成静态复核：独立核源码 SHA-256 `41541f78d934b8d46fc533d3d19eceb0c428e3c35a8efbcf9971ab586416e0f1` 与实现者记录一致。原真实fromCore测试改为三组mode参数，每组mode实际进入createTestWorld，并断言实际executionMode；真实列表读取barrier、B位移/完整epoch/afterPublication/current+publish、A仍暂停与cache identity、finally清理均保留。未增加生产测试缝隙或放宽断言，原同World串行及迟到转换fixture保留。第1项证据缺口的测试实现已补齐，实际三组发现执行仍待新gate XML。

两个owner spec已写入三组真实observation及非空LIVE状态对照要求。复核发现SimpleATO simulation文档失败表仍写“只记录平台效果”，与当前默认无效果历史不符；已告知root最小修正为无显示出口执行启动语义、观察器按需收集。本代理未越权编辑其他文档。

LIVE状态保持用例仍由对应实现者编写，本代理等完成后再核该测试，不提前验收。

## LIVE状态保持补测静态复核

新增 `SimpleSignalsWorldRuntimeManagerTest.capture create run and close preserve nonempty live claims dispatcher frames and formal signals` 已完成独立逐路径复核。它通过正式manager/catalog和共用OperationalWorldRuntime，在LIVE发布三个真实epoch，启动并取得实际Dispatcher的WAITING_TIMETABLE_TIME run；通过Basic实际admission取得含RailIntervalResource和`test:actual-block-3`的真实claim，并核对正式四灯四档。初始状态不是空预约/空Dispatcher/未安装信号。

记录源World frame、每列车publishedFrame、registry.claimSnapshots、Dispatcher statuses、正式signal snapshot、限制集合/completed stamp及方向；随后在capture、各target create、两个SIM各自step并申请同block、A改方向、A关闭、B再step、B关闭之后逐阶段核对这些源值不变。两个SIM都使用文本train ID `actual`、同一资源token，但exact train/World runtimeId不同；两边实际申请成功，A关闭只清A claim，B继续提交新epoch且claim/信号仍有效。此用例不冒充HTTPfactory/逐车JS联合场景，后者仍由已有FullFeatureSimulationIsolationTest两个真实用例覆盖。

`ActualSignalRun`/`actualLive`测试helper仅增加可选domain、observation回调和seed，以使本用例复用真实LIVE装配。既有调用的默认seed、独立signal processor和close路径保留；domain存在时使用它已登记的signal processor，不重复安装。finally先停止LIVE physics/generic实际worker，再清manager、admission/claim、validator和domain；两SIM的use按原路径真正停止并退注册，A显式close幂等。没有生产代码、依赖、公共API或持久化变化。

第2项初核证据缺口的测试实现已补齐；第1/2项均待新增用例实际gate后关闭。静态复核未发现新确定产品或测试语义问题；不把测试尚未执行视为通过。

首轮focused发现LIVE新增用例一处错误前提：第三物理frame被断言必须与最近signal snapshot同epoch，但该fixture输入未变，真实响应式handler保持IDLE，信号仍是第一个有效epoch的快照。已独立追到 `SignalEpochRuntime:181–191` 无变化跳过source handler、`:211` 仍completeEpoch；`SectionOccupancyRuntime:425` 仅update时换latest，signalSnapshot读取该published值；既有 `SignalEpochRuntimeTest:364` 专门验证baseline之后无变化保持IDLE。因此应修测试epoch前提，不改变产品为逐epoch重算。保留正式aspects及全阶段exact snapshot不变断言；有效signal目标身份、stamp不晚于frame和domain completed stamp当前性可分别校验。修后仍须实际运行，首轮不能记作LIVE补测通过。

后续fixture安装暴露其替换同World BasicData时遗漏railMetadata：LIVE基础设施service fixture能读原静态限速，SIM正式BasicInfrastructureSnapshot因此缺少必需finite SUBGRADE速度，目标真实规则编译正确拒绝。最终修正是让新LIVE BasicData沿用同一个源World原railMetadata store；目标仍由原snapshot capture/restore取得独立metadata，没有为SIM伪造静态限速端口或放宽正式prepare约束。epoch断言也已按原IDLE合同改为第一baseline stamp匹配、后续same snapshot而completed boundary继续推进。

最终新增用例候选为 `parent-candidate.json` 的53路径 `c1d805b99ba0bff1ed7cd2c18b49b6bf6152b6785a31431c93ac26a959fdabda`。本代理逐文件哈希独立核对无drift（删除标记也一致），与c781ed4f相比仅observation与Signals manager两个测试源改变，产品源码字节一致。默认无效果历史的SimpleATO表格文案亦由root修正，与两owner必需断言一致。最终静态复核通过，运行结果仍待完整gate。

## 最终 Verification（独立核对）

- **源码及基线**：parent-candidate.json全部53路径SHA与当前文件/两删除项一致，SHA-256(files JSON sort_keys/separators)重算为c1d805b9完整值。dev及候选HEAD均仍87fe3e45。与c781旧产品逐字相同，仅两个测试补验收；本代理不写产品。
- **完整XML**：直接解析 `../simulation-parent-acceptance-baseline-20261020/evidence/final/*/TEST-*.xml` 的364份原始XML，每suite声明tests与实际testcase数一致：Core505、Basic390、SimpleATO375、SimpleATOBukkit2、Bukkit544、Signals648、Tunnel31、WebUI12，总2507；failures/errors/skipped全0。不是把前置2504和最终结果相加。
- **确切补项发现执行**：DefaultRailwayObservationServiceTest的 `LIVE paused conversion leaves LIVE progressing`、`LIVE paused conversion leaves SIMULATION progressing`、`SIMULATION paused conversion leaves SIMULATION progressing` 各出现一次且无failure/error/skipped节点；Signals manager的 `capture create run and close preserve nonempty live claims dispatcher frames and formal signals()` 亦出现一次并通过。三参数替换原单例净增2，LIVE新增1，合计2504→2507，两个证据缺口实际关闭。
- **证据完整性**：逐份核对parent-final-evidence.json全部364 XML SHA及6份日志SHA，零不符。直接读完整gate log末尾 `BUILD SUCCESSFUL in 1m 53s`、65 actionable tasks/6 executed/59 up-to-date；本会话前置完整2504及本次修改模块新XML共同支持组合候选，不宣称全部测试本轮强制重执行。
- **类型/模块/打包/API**：最终log的Java API consumer、Bukkit shadowJar、SimpleSignals check、verifySignalModuleBoundaries与Core/Basic/SimpleATO Dokka均成功/有效UP-TO-DATE；无新增未修编译/type问题。没有另外宣称独立lint工具执行；完整whitespace/manifest由root最后收尾。
- **前端/文档**：直接读本轮前端日志，21文件108测试通过、生产构建成功。docs-site架构threading-model.md SHA为68eeeb49a93a61706f9ca5c190091545dbee1f0f7b196069a12f0c4af512dea4，与P2已通过npm run check源码相同；本次测试补项不改文档源，没有伪称重跑docs-site检查。
- **范围限制**：现场平台验证未执行；fatal host政策未改；无commit/merge/push/deploy/archive。父任务功能/自动验收现可关闭，提交与合并仍按用户后续指示。

## Findings（最终）

- Fixed：observation三模式与非空LIVE状态保持两项测试证据缺口已补齐并实际通过；fixture的IDLE stamp和源metadata前提已按真实业务合同修正，无产品规则放宽。
- Not fixed：无剩余确定产品问题。Paper/Folia/客户端/浏览器与进程资源隔离属于明确未执行/非目标边界，不作为暗示通过的功能项。
