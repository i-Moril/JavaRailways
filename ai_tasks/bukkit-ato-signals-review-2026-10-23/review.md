# BukkitPlugin / SimpleATO / SimpleSignals 只读审查

> 第三批 BUK-04/06/20/21/22 及 ATO-06 契约处理见 [third-batch-fixes.md](third-batch-fixes.md)。ATO-04 已按用户要求从问题清单删除；已修复项的真实性复核见 [fixed-findings-reaudit.md](fixed-findings-reaudit.md)。以下保留其余原始只读审查证据。用户后续授权的第一批 9 项修复及测试状态见 [first-batch-fixes.md](first-batch-fixes.md)；第二批 12 组修复见 [second-batch-fixes.md](second-batch-fixes.md)。原文“未修改、未测试”仅描述审查阶段。

使用本仓库 `javarailways-code-review` skill，按生产调用链、失败后的状态、资源所有权和测试有效性审查。基线：`dev/signal-system`，HEAD `a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2`，包含已有未提交修改。本次只新增审查文档；未修改生产/测试代码、未构建、未运行测试或服务器。开始与收尾的 746 个目标文件哈希一致。

以下为历史静态条目；本轮真实性复核已收窄部分影响描述，并将 BUK-15 降为待客户端验证的回调边界风险。各项未因此获得动态复现证据。P1/P2 保留原优先级供追溯，当前是否已修复及证据边界以修复报告和复核记录为准。跨模块卸载问题合并计数；续审撤回 ATO-02，新增 BUK-10 至 BUK-19，历史清单在专项新增5项后曾计29项；现撤下 ATO-04，保留28组记录（含已修复项及 BUK-17 用户设计决策），不代表28项待修复问题。工程与测试风险另列。

## 第三批完成状态（2026-10-23）

BUK-04/06/20/21/22已实施，ATO-06按用户决定补不可变所有权契约而不增加全面复制。最终相关模块检查、API消费者编译、开发模块编译、shadowJar及文档站构建通过。测试实际执行与up-to-date边界、首轮被终止后的完整收尾和Paper/Folia未验证项见 [third-batch-fixes.md](third-batch-fixes.md)。以下条目保留原发现及历史编号，不再代表待修复清单；ATO-04仍维持撤下。

## 撤下 ATO-04（2026-10-23）

当前 WebUI 的 Reset／重算时刻始终传入每站 destinationIds，使用 compileResetCandidates，未调用原报告讨论的缺省泊位分支。原报告把该分支描述成实际界面预览故障，证据不足。按用户要求删除该问题，不安排修复；保留本说明用于解释编号缺口，不据此宣称该分支已通过连续性验证。

## 后续决策：BUK-17 按玩家位置放车

用户明确取消自动挪车找位置。实现已删除指数探测和二分平移，固定玩家位置的最近轨道投影点为车头，仅按原有 AB/BA 顺序选择可容纳完整列车的朝向；两向均不足直接拒绝。原车长加连接器间距计算、占用检查和 Core 摆位校验保留。`TrainPlacementSelectionTest` 覆盖实际车头/车尾位置、反向不平移、别处能放但原点不足仍拒绝，以及长度边界。BukkitPlugin 全量 571 项测试通过（含新增 4 项），`git diff --check` 通过；日志 `/tmp/jr-exact-placement-tests.log`。未做游戏内验收、未部署。BUK-17 按此用户决策结案，不再改进旧自动搜索算法。

## 优先问题

1. **P1 / BUK-01：重启后的第一次保存可能被旧备份覆盖。** 保存 revision 使用重启归零的 tick，读取却选择 revision 最大的配对文件。旧会话保存 10000，新会话保存 100，下一次加载会选择旧备份，丢掉新修改。位置：`WorldManager.kt:720`、`WorldPersistence.kt:58-71`。需要独立且跨重启单调的保存序号。
2. **P1 / SS-1：固定进路信号遗漏自身入口的状态依赖。** 仅授权、入口净空或自身岔路发生变化时可能跳过重新求值，出现该开不开放或撤销后仍保留绿灯。Basic 行车授权仍独立约束列车，不能据此推断碰撞。位置：`SignalPackEpochRuntime.kt:264-266`。应把根进路依赖纳入脏检查，并用真实 JS catalog 测试开放和撤销。
3. **P1 / SS-2：无关区域的拓扑编辑可导致后续信号 epoch 失败。** 局部依赖未变所以不重编译，但执行时要求全局 topology revision 完全相等；下一次相关事件进入旧发布即抛错，传播到 `RailwaySimulationHalt.halt`。并非编辑后必然立即触发。位置：`SignalPackEpochRuntime.kt:92-95`。需统一局部有效性与执行检查合同。
4. **P1 / BUK-02 + SS-3：被取消的世界卸载仍销毁运行时。** BukkitPlugin 在 NORMAL 阶段直接注销，SimpleSignals 的 MONITOR 监听器也未忽略取消事件。世界留下，铁路与信号运行时被关闭，且没有新加载事件恢复。位置：`WorldManager.kt:468-470`、`SimpleSignalsWorldLifecycleListener.kt:27-29`。应在确认未取消的卸载阶段清理。
5. **P1 / BUK-03：强制重置被拒绝前已移除列车控制器。** 命令先释放手动控制、设置 null controller，再由 Dispatcher 拒绝仍持有授权资源的 run；拒绝后保留 run/claim 却失去控制器。位置：`JavaRailwaysCommand.kt:967-973`、`TimetableDispatcher.kt:1780-1783`。需先完整校验再改变状态。
6. **P1 / BUK-06：时刻表 JS 编译缺少运行预算。** 两次不终止的脚本即可永久占用两个编译许可；输出缓冲也无界。HTTP 超时不覆盖该同步执行。文档仍承诺独立 JVM 和 3 秒终止，与现实现不符。位置：`TimetableScriptSandbox.kt:101-114`、`scriptworker/Main.kt:14-33`。需要可取消执行与有界输出；未执行死循环探针。

## 其他确认问题

7. **P2 / ATO-01：已正常撤销的空限制流会在下一条路线重新变成停车约束。** store 保留旧 route 的空快照，adapter 先判断 route 不同，再处理空限制；已移除的源无法更新，下一趟持续零速。位置：`TrainRestrictionConstraintAdapter.kt:128-140`。
8. **P2 / ATO-03：Depot 拒绝取消时已删除账本。** `assignments.remove` 在 token/state 校验前执行，返回 false 仍破坏有效分配，后续正常释放失败。位置：`Depot.kt:607-610`，storage 同类问题 `:555-557`。这是公共 API 边界缺陷；正常 Dispatcher 有额外 guard，未证明常规调度会触发。
10. **P2 / BUK-04：轨道显示全局索引保留卸载世界。** `railVisuals` 持有 lifetime→rail→world，卸载未退休这些条目。索引仅以 rail UUID 为键，复制世界还可能相互覆盖。位置：`RailVisualBootstrap.kt:31-36`、`WorldManager.kt:438-447`。强引用链已确认，未测量堆增长。
11. **P2 / BUK-05：装饰元数据写入被拒绝仍报告成功。** sidecar 损坏时 writer 正常返回，调用方已经修改显示并记录 undo，用户看到成功但重启后消失。位置：`RailRenderMetaStore.kt:163-168`、`RailWandDecorateMode.kt:49-58,81-96`。
12. **P2 / BUK-07：地图缓存遗漏 marker 所在 rail。** 标记删除后在另一已有轨道同名重建，若 id/名称/弧长等不变且两次地图请求未观察到中间删除，签名不变而返回旧位置。未找到单步迁移界面。位置：`RailMapPreviewService.kt:55`。
13. **P2 / BUK-08：内联模型变换计算两次。** YAML 同一 offset/rotation 写入 definition 与 entry，builder 又相加。位置：`PartRegistry.kt:260-261`、`ModelTreeBuilder.kt:210-234`。现有测试手工构造对象绕过了解析入口。
14. **P2 / BUK-09：车体/转向架构造失败不回收已创建显示资源。** 后续部件或根显示创建失败时，早先创建的对象没有回滚 owner；车体先赋值，后续 bogie 失败又可在重试时覆盖它。位置：`ModelTreeBuilder.kt:124-148,187-194`、`CarExtension.kt:389-392`。依赖后端创建失败这一触发条件，未做实体泄漏动态测量。

## 工程与测试有效性

- Depot 公共对象和持久化捕获保留调用方可变 Set，构造后修改可以绕过校验并改变旧 snapshot；尚未找到平台实际修改这些 Set 的路径，单列为 API 所有权风险。
- 部分 fixed-route readiness/recovery 测试使用 unavailable fallback、只断言零值，不能证明真实 JS 信号开放与撤销；模块也包含真实 Graal/live/simulation/current-entry handoff 测试，不能泛称全部测试无效。
- 时刻表脚本文档的隔离/资源预算声明过时；信号模块另有明确允许作者承担无界 JS 的合同，不把两者混作一个问题。
- 信号编译器 unused 参数、没有 producer 的错误枚举等是遗留合同清理项，不能和行为缺陷等同。

## 续审新增问题

- **P1 / BUK-11：显式列车 ID 不存在时删除另一列车。** `/jr train delete typo` 的查询失败会回退最近列车，随后直接销毁。省略 ID 与错误 ID 必须分支处理。位置：`command/TrainCommand.kt:667-680`。
- **P2 / BUK-10：HTTP 路径重复解码，改变对象身份。** `requestURI.path` 已解码，再用 URLDecoder 把 `A+B` 变成 `A B`；编码斜杠也在分段前被展开。可导致请求失败或操作另一个合法 ID。用 rawPath 分段、仅解码一次。位置：`http/TimetableHttpBridge.kt:129,143,191-202,857`。
- **P2 / BUK-12：issues 命令未注册。** help、重载失败消息都要求运行 `/jr issues`，delegate 存在，但 Cloud 路由缺失且无兜底。位置：`command/CloudCommandRegistrar.kt:61-91`。
- **P2 / BUK-13：未装饰的实体轨道端点无法重新选取。** 端点枚举依赖渲染 sidecar；关闭 autoDecorate 或清除装饰后，物理轨道仍在，但工具丢失端点发现能力。应从实体轨道枚举。位置：`tool/RailWand.kt:5198-5201`。
- **P2 / BUK-14：损坏 Wand 数据仍会被默认值覆盖。** 解码失败分支声称保留原 PDC，却只跳过首次写入；交互结束、切换或退出仍无条件写默认状态。须明确保持不可写状态直至用户替换。位置：`tool/AbstractWand.kt:127-129,200,263-271`。
- **BUK-15 / 回调边界风险（确定性降级）：编辑回调未绑定原世界及目标实例。** 静态可构造的旧 destination 对话框回调会按玩家当前世界解析同名对象。身份约束已补齐；尚未证明原生客户端跨世界后能送达旧回复，不能列作已经证实的正常界面误写。位置：`tool/RailObjectWand.kt:625-659,825-828`。
- **P2 / BUK-16：重命名一个世界的 destination 会修改其他世界同名 marker。** renameMarker 遍历全部玩家映射，只按字符串 ID 匹配；另一世界显示被改名且删除旧 ID 无法清理。位置：`tool/RailObjectWand.kt:770-782`。
- **BUK-17：按用户决策取消自动挪车（已实施）。** 原先以15.5m孤立轨道、起点0说明整条创建失败不成立：旧入口还尝试反向，反向在原点即可容纳列车。两向搜索均失败的示例应为中点7.75m、整列长15m，所需平移7.25m，试4m不足、试8m越界。最终合同由用户明确选择为固定玩家投影位置，仅选择朝向，已删除旧搜索；不再将起点0示例作为修复证据。
- **P2 / BUK-18：取消下车仍释放驾驶会话。** 默认阶段监听未处理取消结果，立即通知离座并排队 teleport；玩家仍在座位但控制已被释放。需在最终确认下车后执行副作用。位置：`render/model/SeatModel.kt:384-396`，消费者 `control/TrainControlManager.kt:69-72,271-304`。
- **P2 / BUK-19：动画提前应用下一关键帧外观。** 0秒 stone、1秒 gold，在50ms就选择未来帧的gold，变换却只插值5%；brightness 同样受影响。离散属性应只选已到达的关键帧。位置：`render/Animation.kt:265`。

## 纠错与新增工程观察

**ATO-02 撤回。** 原 Check→Extension→GoTo 触发方式会被 Timetable 校验器拒绝；普通已移动 run 又受 retained admission guard 保护。虽存在取消遗漏代码，但没有证明合法可达路径，不再作为确认问题。

- P2 工程：六个 Bukkit 测试文件未关闭自行创建的 world，PhysicsCoordinator 始终留在 RailwaySimulationHalt 的全局强引用集合中。属于测试隔离/资源所有权问题，未声称每个 fixture 都已启动线程。详见测试续审报告。
- P3：SimpleATOTest:292-302 限速测试没有授权 Mission，只断言零速；即使删除基础设施限速逻辑也可能通过。
- P3：SignalEpochRuntimeTest:285-309 并发测试有无界 await，缺少 finally 释放/线程回收；被测逻辑回归可能挂住测试。不是生产死锁结论。
- TimetableDispatcherTest:1434-1441 在未到站时直接移动到终点，后续 COMPLETED 断言不能证明实际完成行驶；作为测试范围限制。
- P3：SimpleSignals/README.md:165-180 仍描述已移除的 wand block/region 命令与旧交互，实际为 wand section。
- Bukkit 保留不可达旧手动驾驶方法与过时 start/stop 帮助；stand 报成功但未实际离座，sit 为未实现占位。建议按当前控制合同清理，而非恢复旧路径。

## 专项复审新增（本轮）

25. **P2 / BUK-20：旧 provider 句柄可移除新注册。** 注册A→正常unregisterOwner→同owner/type复用同provider对象注册B→迟到A.close，会按owner/provider删除B。缺少准确注册实例身份校验。公开API可达，未证明内置插件目前触发。位置：`DefaultInfrastructureResourceService.kt:294-300,366-400`。详细证据见 [deep-bukkit.md](deep-bukkit.md)。
26. **P2 / BUK-21：道岔API暴露内部队列Future完成权。** selectTurnout直接返回submitBetweenFrames内部Future；消费者complete/completeOnTimeout/orTimeout提前完成它，内部execute因isDone而跳过实际操作，或在实际发布前已对外完成。当前Wand只监听结果，问题范围为外部API消费者。位置：`DefaultInfrastructureResourceService.kt:436-442`及`PhysicsCoordinator.kt:66-75,300-315`。详细证据见 [deep-bukkit.md](deep-bukkit.md)。
27. **P2 / BUK-22：车体模型引用环加载成功，显示时无限递归。** 快照收集把重复引用当作已完成节点跳过，没有拒绝A→A或A→B→A；实际buildPart却无限递归直到StackOverflowError。普通模型compiler的环校验不在这条生产路径上。位置：`SavedCarModelTree.kt:53-56`、`ModelTreeBuilder.kt:251`。详细证据见 [deep-model-lifecycle.md](deep-model-lifecycle.md)。
28. **P2 / ATO-05（DEEP-ATO-01，扩展 API 合同）：停止已完成但尚未轮询的扩展动作，会错误触发世界ATO停滞。** 同步返回SUCCEEDED的合法ticket仍被保留至下一tick；此间stop无条件要求cancel后变为CANCELLED，将已经成功的终态当作取消失败。普通operation分支正确接受终态，extension分支遗漏。位置：`TimetableDispatcher.kt:4097-4114`；现有测试2172-2176、2207-2213证明该等待窗口。与撤回的ATO-02不同。复核未找到随仓库交付的具体 action provider；触发依赖外部注册的扩展提供者，测试提供者证明接口状态可达，不能当作内置功能已发生故障。详细证据见 [deep-ato.md](deep-ato.md)。
29. **P2 / ATO-06（DEEP-ATO-02）：时刻表原始集合可改变已验证运行状态及已捕获存档。** GoVia列表、service.steps等未隔离；SavedTimetable capture直接保留wayPointIds，修改原可变列表可改变旧capture，Bukkit异步writer稍后编码。是公开构造/捕获边界缺陷，未声称内置HTTP编辑会原地修改。位置：`Timetable.kt:30-82`、`TimetableSnapshot.kt:207-223`。详细证据见 [deep-ato.md](deep-ato.md)。

本轮另列工程/值风险：

- **E-SNAPSHOT / P2 API值风险**：SavedCarModelTree及SavedTrainPackageSnapshot只隔离部分外层容器，仍借用嵌套列表；原输入改变后内容与既存hash不符，save写出后load会拒绝。未找到内置捕获后修改caller，故不混作正常路径必现问题。
- **DEEP-ATO-03 / P3**：AtoWorldCoordinator.initialTrains随历史train ID增长，没有退休入口；保留的是诊断快照值，不是活的列车对象。未测量内存增长。
- **低优先工程项**：CarLoader.partRegistry字段无读取，不能据此认为引用已验证；SignalModelBinding.topologyRevision参数无读取，实际门禁使用发布/绑定身份。

## 本轮检查记录与剩余边界

[专项覆盖记录](aspect-coverage.tsv)记录每项检查的方面、文件/符号、阅读范围或调用链、结果和验证缺口，共67行；其中包含规范、测试和跨模块核对记录，**不代表67个独立方面或文件，也不代表全部文件已逐方面复核**。原逐文件全文阅读覆盖仍见file-coverage.tsv。

已覆盖：公开API完成权与准确注册身份；model/editor清理结果与owner边界；安装/替换/回滚/退出对称路径；模型引用图及快照哈希；时刻表终态取消、运行列表与保存捕获；信号发布门禁、旧实例退休、规则/模型资源所有权；选定生产者消费者与重复算法。

排除理由同样入账：基础设施close顺序虽看似可短路，实际关闭对象没有找到可达抛错触发；model/editor返回minimal stage已隔离内部完成权；Signals未成功解绑的旧engine会保留而非提前销毁；世界metrics的phase有世界名前缀；懒JS上下文未证明在失败加载时已经创建。

尚未验证：实际并发交错、Paper/Folia线程和事件时序、实体/网络删除失败、故障注入、旧实现失败回归、客户端视觉。仍未逐一证明所有公开构造器/copy/serializer及每个caller的全生命周期，也未完成全仓符号可达性证明。记录用于继续复审，不是“已无遗漏”的证明。

## 覆盖与详细证据

本轮累计全文静态阅读 726/730 个清单文件，0 部分阅读；剩余4张PNG未做视觉验收。BukkitPlugin 为390/391（生产源码246、测试源码127均全文）；SimpleATO 为121/121（生产66、测试52）；SimpleSignals 为215/218（生产88、测试63）。共400个生产源码、242个测试源码全文读完。资源、fixture和文档计入总文件数，不代表运行覆盖率。

覆盖统计以 [/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/file-coverage.tsv) 最终合并清单为准；旧代理报告中首轮/第二轮范围段落只记录历史进度。

- [Bukkit 详细调用链](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/bukkit-evidence.md)
- [HTTP 与 API 续审](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/http-api-evidence.md)
- [SimpleATO 详细证据与撤回说明](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/ato-evidence.md)
- [SimpleSignals 详细证据](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/signals-evidence.md)
- [Bukkit 测试续审](/workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/bukkit-test-evidence.md)

全部结论均为静态审查。未运行构建、测试、JavaScript、Paper/Folia、游戏客户端或故障注入；源码阅读完成不等于运行验收，也不保证不存在其他缺陷。

## Wand 与源码目录工程补查

见 [wand-structure.md](wand-structure.md)。已区分源码布局、已有有效抽象与仍集中在RailWand的职责；属于工程维护观察，不增加行为缺陷数量。
