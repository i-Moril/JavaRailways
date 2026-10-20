# 独立全范围检查

2026-10-19，`trellis-check`；工作区 `.worktree/simulation-signal-features`，基线 `9ed27818`。本报告覆盖本批全部27个改动/新增Java、Kotlin产品及测试文件、18项check上下文及相关原文、真实生产caller和更新规范。不是仅对最后一行修复的增量检查，也不是全仓库审计。检查者未改产品、未启动竞争Gradle、未提交或部署，仅写本报告。

## Findings (fixed)

以下均已由对应实现者修复，检查者重新沿实际路径确认；没有保留未修产品缺陷。

1. `DefaultSimulationDomainTarget`：inspection退休曾调用会拒绝active binding卸载的planned replace；现按所属source调用既有removeSource，其他Feature保留。新增`feature retirement removes active inspection bindings without unloading another feature`真实执行。
2. `SimpleSignalsWorldRuntimeManager.prepareSimulation`：源owner内capture不能反取管理锁。现只读不可变publication、rules、readView和topologyGuard；生产HTTP的withWorld→owner→structural boundary实际调用同一prepare路径。
3. `attachModels`：目录锁内等待runtime publication锁可能间接串行其他World。现等待在所属runtime.managementLock内、目录外；真实prepareDefinitions/attach阻塞回归证明B安装继续。
4. `retire/remove/clearLocal/failed-install`：subscription.close异常曾跳过engine清理或残留reservation。现finally撤identity、逐项独立关闭model/logic，并保留原异常和suppressed；真实engine关闭及另一SIM存活回归通过。
5. 同UUID重装：旧remove/迟到install不得清除新实例诊断。现按实际runtime identity，在UUID可复用前清旧诊断；旧remove延迟完成后的新rule诊断仍保留。
6. `DefaultSimulationFeatureServiceTest`：表达式测试曾推导非void，未被JUnit发现。显式withServer<Unit>后，最终XML中`registration close disable and service close preserve exact owner identity()`恰好执行一次。
7. closed runtime的既有卸载回归：candidateModels之后复核实际runtime identity，保留原WORLD_UNAVAILABLE断言，没有改旧测试来适配VALIDATION_REJECTED。
8. clearLocal诊断清空：使用既有issueLock和在途回调协调；Dir→issue只包含身份/内存诊断，issue内不反取Dir或管理锁、不等待JS。
9. 本次正式检查发现的拓扑重验遗漏：成功分支在publishIssues之后又按UUID无条件清revalidation诊断。已删除该冗余语句，统一使用publishIssues已有issueLock、实际runtime identity及pending校验；避免旧worker清新实例诊断。没有新增test hook；删除后manager154项及最终全门禁通过，既有迟到拓扑/重装回归继续执行。
10. 规范同步：SimpleSignals index的必需服务列表已由九项更新为含simulation preparation的十项。

## Findings (not fixed)

无剩余已确认的本批产品缺陷。以下是证据边界，不是本次发现后搁置的实现问题：

- 新集成覆盖500/300/150/500m及100km/h静态元数据；全10m极短链、多档静态速度/缺值与低参考速度边界由既有正式测试覆盖，没有把它们写成新增SIM全参数矩阵。
- 缺规则目录、未知type、坏JS/非法输出分别有共用编译器、LIVE manager或沙箱测试；没有对每种失败重新做一遍完整SIM交叉矩阵。SIM的错误日志target UUID/原Throwable及无Bukkit回调由实际安装源码复核，没有本轮服务端日志采集证据。
- LIVE模式CoreWorld的确定性物理推进、真实Graal及领域运行链属于本地集成；服务测试使用本地平台fixture，不证明Paper/Folia调度、library resolution、独立classloader或玩家客户端验收。没有无Bukkit classpath产物验收，也没有完成SimpleSignalsBukkit模块拆分。
- 车厢脚本仍归子任务4；本报告不关闭父任务全部Feature及最终联合验收。第三方guest永久不返回的成本仍按已批准合同保留，不添加超时/强制卸载。

## 七项PRD验收的实际证明

下列方法默认属于`SimpleSignalsWorldRuntimeManagerTest`，另注明的除外；方法源码及真实消费者均已阅读，不用总数代替业务证明。

1. **正式四灯JS及真实编译安装**：新增`formal rule execution is independent across real live and simulation worlds`的三组参数，调用分发包的真实loader、per-target定义/观察图/规则编译及安装；LIVE/SIM均运行相同正式JS。`detached simulation installs formal rules preserves captured values and updates real occupancy on release`在LIVE manager关闭后仍安装并运行capture，证明不是保留live runtime。
2. **占用、释放、保护、方向、折返、静态速度**：上述matrix的fixture为500/300/150/500m，真实静态限速100km/h、实际列车运动，断言proceed/slow/caution/stop和null/65/45/0；matrix中LIVE与SIM目标按相同预期核对。detached用例销毁占用列车后发布释放结果，末段仍因轨道尽头限65km/h，旧snapshot不变。新`real simulation admission claims fixed blocks releases by tail and observes direction updates`验证SIM方向变化收紧且LIVE方向未变；新`real simulation reversal retains the same bidirectional block claim and releases after the reverse tail`执行suspend→Core.reverse→同grant rebase/extend→实际反向运动超过60m→尾释放。新`installed formal protection signal receives real admission and inspection feedback without dispatcher ticks`验证红灯、真实selection/routeReady、ATO许可及占用/释放后的inspection反馈。既有`distributed four light package propagates actual same epoch occupancy through mixed and short blocks`额外覆盖全10m链，但其输入为合成不可变frame；`scan plan captures static speed only in calculated mode and rebuilds only on topology preparation`、`calculated scan captures whole block maximum in either direction and rejects any missing rail speed`和`JrFourLightSignalPackTest.quantization respects thresholds and low reference speeds without inventing a green cap`覆盖多值/缺值/cache/阈值，属于共用规则测试，不冒称新SIM多参数测试。
3. **inspection、claim/尾释放及admission进入同目标**：新保护用例通过真实Dispatcher/ATO与inspection读取结果；新资源和折返用例真实调用本地Basic admission，检验相同区间token冲突、sameGrant保留和实际尾释放。既有已适配公开API的`IsolatedTimetableSimulationTest.target feature receives real admission updates only from its local runtime`验证admission update只进入自己的signal runtime；新增active inspection退休用例验证另一Feature未被清除。
4. **S1执行实例归属**：源码中manager不再拥有共享logic；runtime按World管理构造/复用/关闭，SIM每次install创建独立runtime和Engine。catalog.rules只保留源文本/元数据/variant字符串，不带CompiledModelAsset或Material。三组matrix真实阻塞所属logic monitor；`SignalLogicRuntimeTest.does not leak closure or global state across evaluations`、`runtime is serially reusable and rejects calls after close`继续验证fresh realm和同实例同步。
5. **S2并发、同World有序及生命周期**：matrix参数false/false、false/true、true/true分别为LIVE-LIVE、LIVE-SIM、SIM-SIM。在ThreadMXBean确认A已阻在真实evaluate monitor后，B完成真实位移和正确epoch输出；A第二次step仍等待，释放后A完成两epoch。A关闭后B继续；新增`subscription retirement failure still closes the owned engine and preserves another simulation`真实验证remove/clear失败仍关闭所属engine并保留SIM，`a failed simulation installation cleans its own rules and leaves another real installation usable`验证部分安装失败与另一目标/再次安装隔离。
6. **S3共享目录与归属边界**：新增`a world source commit wait does not hold manager directory or block another install or detached capture`暂停A真实管理流程的source提交seam，B安装与A detached capture均完成；`real definition preparation and waiting model attachment do not capture the manager directory`暂停真正prepareDefinitions并确认attach等待其publication monitor，此时B仍安装。`late old removal cannot clear replacement world rule diagnostics`覆盖同UUID旧remove迟到。源码审查确认Dir只承担reservation/身份/readView；单World编译、JS初始化、host owner等待和close均在外，物理callback不取management/host owner。clearLocal逐World并行独立cleanup，异常不会跳过其他实例。
7. **无Bukkit效果、模拟诊断与不可用规则**：公开target不暴露CoreWorld/OperationalWorldRuntime；SIM Feature只持detached定义、neutral rules和标量设置，target UUID及exact root重新编译，真实source/provider/inspection均连接本地domain。新`DefaultSimulationFeatureServiceTest`三项验证身份、disabled/错ID/异常和回调在共享目录锁外；已扩展`IsolatedTimetableSimulationFactoryTest.one captured snapshot creates new extension and domain state for every target`实际经过service→capture→两个目标source并独立关闭。既有`capture rejects an installed domain feature without detached preparation`继续拒绝缺准备器；`persisted package signal stays restrictive until startup catalog atomically replaces its epoch source`、`failed package rebind preserves disk publication sources and existing model handle`、`package model validates the JS variant selected from the same catalog signal`、`rule failures retain original throwable and report only changed failure states rather than normal red`及SignalLogicRuntimeTest的`rejects syntax errors and missing evaluate during preparation`、`rejects missing duplicate and invalid setters`、`rejects thrown exceptions and Promise results`覆盖共用失败语义。SIM源码使用同一个SignalPackEpochRuntime.compile/evaluate，无平行算法或放宽Unavailable/0；SIM JVM日志显式带simulation、target UUID与原Throwable。

## Verification

- 最终27源canonical SHA-256：`a50950903a2a5a1273e2d006e546249b8a3dd08116324ac92e74c4e7f7377820`。检查者独立计算每文件SHA与path+NUL+bytes+NUL哈希；git diff基线与全部untracked Java/Kotlin集合恰好等于27份清单，无遗漏。初始5888246a候选仅作历史；最终manager删冗余clear后重新验证，未沿用旧指纹。
- 主会话执行[完整命令](full-gate.sh)，检查者未重复构建；独立读取最终全部357份XML原字节、逐份SHA、每个testcase、模块计数和原始log/script SHA。合计2411项，failures/errors/skipped全0。Core447、Basic413、SimpleATO386、SimpleATOBukkit8、BukkitPlugin473、SimpleSignals641、TunnelBridgeBuilder31、WebUI12。
- 全部16个新增case在最终XML恰好出现一次，其中Signals12（包括三参数matrix），service3、inspection退休1；特别确认此前未发现的Unit生命周期用例实际执行。本轮八个test任务均--rerun实际执行；API consumer编译任务允许已有有效UP-TO-DATE输出，未称它被强制重编。
- 类型/编译及模块门禁通过：SimpleSignals check/verifyPluginJar、Java API consumer、verifySignalModuleBoundaries、Bukkit shadowJar、Core/Basic/SimpleATO Dokka。现有deprecated/Undocumented/native-access警告仍在，不当成验收或屏蔽它们。
- 完整JVM日志`/tmp/simulation-signal-full-gate.log` SHA-256为`e9655ac5e435820310664a7b23f69cf0d17d15cfc530974ebb08424e2e49747d`；精确命令脚本SHA为`2bb1b542c9d176807da14723e6588f7f7041e9c74a9e88ad8004e1254eb69d9d`。已独立比较持久[final-candidate.json](final-candidate.json)与原始`/tmp/simulation-signal-full-evidence.json`的27源/357XML/16case/计数/脚本日志字段，一致。
- docs npm run check退出0：TypeScript、58模型YAML、schema fixtures、reference copy、Docusaurus、Pagefind27页；单源threading-model与源码语义一致。独立核文档源SHA `e8fc0e6e87964236347ce58ca0cae9e4a6b1883fa95359eee52cb980bc1af2b0`、文档日志SHA `262581c6a1a31bd0b946e4fe4804633e143fdf0469ef23bcca7532a8f93bbd1e`与持久docsGate，一致。
- 规范/父任务更新保留CURRENT与本地/现场证据的区分，明确平台拆分未完成、车厢后续和父联合验收开放；已复核相关变更。产品及独立docs仓diff --check通过；本项目以这些门禁为准，没有另外宣称未运行的独立lint任务通过。

本轮另已按Bukkit index的HTTP bridge要求执行frontend `npm test`：21个文件、108项全部通过；检查者独立核新日志SHA `9180dc762150722802ad033b551526258f3786f9ffc458256ae06fb3dbb0e6e2`、68份tracked前端文件canonical SHA `896a465c252d43ffbfb3bdb1cdcffc7ca11dbdeb3295751867443be7291fe859`及相对HEAD零前端源码改动，与final-candidate.frontendGate一致。不是沿用上批108项。两处task创建的node_modules symlink已移除。

最终结论：本批静态、类型/构建、测试、文档及规定前端门禁均通过，无剩余具体产品缺陷；已列集成参数和现场证据边界保持。未执行现场验证。
