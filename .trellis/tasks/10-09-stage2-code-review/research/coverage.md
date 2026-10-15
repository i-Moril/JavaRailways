# 全项目审查覆盖记录

2026-10-15补充：用户单独批准的Basic CR-040已修复，含旧码红灯、独立复核及五模块2153项回归；具体证据见[执行记录](../implement.md#cr-040恢复执行2026-10-15用户单独批准)。Basic剩余CR-041及维护/现场边界保留，不由本批推导整模块审查通过；下方早期顺序中的CR-040不再是未修项。

本清单描述实际覆盖，不按文件数/测试数计算审查百分比。状态分为未开始、部分覆盖、模块首轮完整审查完成（不等于模块通过）。源码静态检查、定向复现、既有测试执行和游戏现场验证分别记录。

## 当前模块交付（2026-10-10）

最新状态（2026-10-14）：**本轮Core源码审查及已确认缺陷修复已收尾**。下方保留首轮覆盖和基线数字；完整文件清单不按新测试数重算。后续代码变更逐批独立复核，最后收口Seat、接缝发布测试和CR-029，独立完整候选Core409/跨七个有测试模块2069项全执行且零失败/error/skip。Core报告顶部统一给出当前结论、接受限制、独立新能力与未完成现场/完整迁移边界，原始证据只在implement.md维护。全项目任务仍in_progress；Basic首轮已审、剩余修复和其他模块按各自范围推进，不称全仓通过。

- **Core 首轮完整审查完成，后续修复持续进行。** 合并C1与C2，按审查基线源码清单闭合全部136个生产文件、49个测试源文件（包括2个helper和1份opt-in stress）、5个fixture；另查build、Dokka输入、全部14份Core规范及对应网页/代码地图。数量只用于发现漏审文件，不是质量分数。后续新增回归与修复状态以[问题台账](./issues.md)及[验证记录](../implement.md)为准，不重复维护第二份实时清单。
- [整模块报告](./core-module-review.md)：逻辑、可维护性/可读性、文档、错误处理/过度兜底、残留代码、规范一致性、测试七维结论；含优先级和建议。
- [逐文件清单](./core-review-inventory.md)：明确分区、文件和文档范围；不是只列已发现问题的文件。
- 本轮真实复现：split失败破坏原编组、跨bucket容差漏碰撞、LINE最近点偏移、移动时car/bogie速度仍为0、损坏车端存档发布错误占用、重复列车构造遗留ACTIVE端点。临时诊断只在`/tmp`，不进入正式实现/测试套件。
- 本轮强制重跑`:Core:test :Basic:test`及Core Dokka：BUILD SUCCESSFUL，18个Gradle任务执行；Core46 suites/212项（206执行、6跳过），Basic59 suites/382项全执行，零失败/错误。Dokka仍有缺KDoc/无法解析链接warning，不能认定文档合规。未执行opt-in stress matrix，也未部署或做游戏/Folia验证。
- **Basic 在上述 2026-10-10 批次仅部分覆盖；2026-10-12 首轮整模块审查已完成**，见下方最新记录。两次覆盖和验证基线不混用。

## Basic 整模块交付（2026-10-12）

- 基线 `d255175f`；96 个生产 Kotlin 文件、53 个测试源文件、2 个 fixture、build 与两份 Dokka 输入完成静态审查，三个分区逐文件清单及七维结论见 [Basic 整模块报告](./basic-module-review.md)。窄范围消费者检查不代表下游模块已审完。
- 修复前全量 Basic 385 项全执行通过，Dokka 成功但仍有 warning，耗时 1 分 2 秒。源码证据确认资源释放、恢复、曲线参数及嵌套 UI 缺陷，不据绿色测试判定模块通过。
- 本批优先修资源安全及恢复边界；具体红→绿和完整门禁见 [执行记录](../implement.md)。几何、配置 UI 与维护项按优先级集中处理，不以全部 P3 清零阻塞正式游戏测试。
- 未做游戏、Folia、性能 profiling 或完整平台事务验证；全项目审查任务不结案。

## 批次 C1：Core runtime 与 Basic observation 边界

- 基线：`56025ea5`，2026-10-09；运行时代码未修改。
- 方法：三条只读审查线分别追踪 tick/lifecycle、frame publication/render 交接、Basic observation/资源释放消费；主会话复核具体源码与测试断言。
- 深度：下列实现及调用链静态检查完成；发现记录在 issues.md。未执行新问题的定向复现、未运行 Bukkit/Folia；不能以既有测试绿色推断未覆盖错误路径正确。
- 实际验证：重新执行 `:Core:test :Basic:test --rerun-tasks`，14 个 Gradle task 全部执行，BUILD SUCCESSFUL。Core XML 共212项（206执行、6跳过），Basic 382项全部执行；零失败/错误。未执行 BukkitPlugin 测试、Dokka 或游戏现场检查。

### 已检查的实现与入口

- Core `PhysicsCoordinator` 全文件：start/requestStep/main wake/timer fallback、exact ticket/coalescing、worker barrier、collision decision调度、train/world frame顺序、processor、overload/failure、close。
- Core `TickManagerImpl` 全文件：deferred register/unregister、pre/postTick、任务队列、commit mailbox交接及close；`AsyncTickThreadMarker`、tick/processor接口。
- Core `LatestTrainFrameMailbox`、`LatestTickCommitMailbox` 全文件：合并、容量和清理；未为并发clear写定向复现，不能把API层可构造竞态直接认定为正常生产路径已触发。
- Core `WorldParentContext`、`JavaRailwaysKoin`、`NoOpWorldParentContext`：attach、全局scope引用与teardown搜索。
- Core `CoreWorld` 定向路径：构造/load、train注册删除、共享occupancy、capture/publishWorldFrame、active continuation捕获/复核、saveInfrastructure/saveRuntime。
- Core `CoreTrain` 定向路径：controller注册/替换、pending state flip/frame revision、stationary/moving/derail publication、link迁移car、save；不代表完整列车物理/联结算法审查。
- Basic `RailwayObservation`、`RailwayTrainObservation`、`RailwayStateStamp`：同frame投影、stable identity、集合隔离与epoch值；`BasicWorldData`/accessors、`RailObjectStore`的生命周期/身份索引。
- Basic `RouteResourceAdmissionRuntime`、`RouteGrantTailRelease`：observation消费、close、wrong-world/epoch/train/cursor失败保持claim；`RouteResourceAdmissionEngine`只查请求stamp、按rail失效与consumeObservation入口，不代表完整仲裁正确性。
- Basic `RailwayAreaOccupancyTracker`：串行消费、成员与穿越事件；未复演并发替换。
- 跨层 `WorldManager` register/unregister/tickStart/tickEnd、snapshot capture/write、LoadedRailWorldRegistry；`RailWorldFactory` composition/restore顺序；`RenderChunkManager`构造/close。
- 跨层 `DefaultRailwayObservationService` publish/listener/unload；infrastructure service attach/consume/remove；SimpleATO `SignalEpochRuntime.process`及source replacement仅用于确认消费边界。
- 跨层帧交接：WorldManager dispatch/queue、CarExtension revision门禁、TrainExtension link接口，仅查Core train ownership变化到car rendering的revision身份，不代表完整模型呈现审查。

### 已核查的测试

- `PhysicsCoordinatorTest`、`TickManagerImplTest`、`CoreTrainPublicationBoundaryTest`完整断言；mailbox tests、`ImmutableSnapshotTest`、`CoreTrainDerailEventTest`对应路径。
- `RailwayObservationTest`、`RailwayStateStampTest`、Basic accessors、tail release、按rail局部失效与occupancy tests；Bukkit observation service tests。
- `CoreWorldRailTopologyTransactionTest`中两条reader阻塞测试、`TrainUniformMotionTest`、`WorldPersistenceRoundTripTest`及共享world fixture的相关断言/资源释放。
- 不把测试源静态阅读当作对应模块测试已执行；本批执行范围只含Core/Basic。新增缺陷回归尚待实现。

## 各模块状态与后续范围

- **Core：本轮源码审查与已确认缺陷处置收尾完成（2026-10-14）。** C1+C2覆盖初审基线的全部生产/测试/fixture及对应文档，后续修复独立复核并记录；不把局部新测试说成重新整仓逐行审查。接受的限制、明确保留的未接线能力及全仓维护债见Core报告顶部。平台现场、完整旧服迁移和性能行为仍未因离线通过而完成。
- **Basic：首轮完整静态审查完成，修复未完成。** 2026-10-12补齐原缺失子系统，逐文件清单/七维结论及未完成验证见上述整模块报告，不能写成模块通过。
- **SimpleATO：部分历史路径覆盖。** 本批只追消费边界；先前路线API/运营编辑迁移不是完整审查。待按ATO控制、感知/制动、运营数据/路线、时刻表/车站/车辆段/控制权及simulation/persistence逐项审查。
- **SimpleATOBukkit：部分历史路径覆盖。** 编辑迁移已有回归；待查全部接线、诊断、适配线程/错误/lifecycle及测试。
- **SimpleSignals：部分覆盖。** 上批定义事务/Wand检查及CR-010/011/012仍待修复；启动卸载、异步退出、拓扑重验、catalog/model交错、领域算法/JS执行及其余测试未全面审查。
- **BukkitPlugin：部分跨层覆盖。** C1查上述宿主边界；待完整审查平台调度/ownership、entity/model/audience、services、命令/Wand、配置及持久化。RailWand大拆已延期，但行为审查不免除。
- **BukkitPluginDev：未开始模块审查。** 待查开发命令、测试/调试工具、依赖和生命周期。
- **TunnelBridgeBuilder：未开始模块审查。** 待查建造流程、任务/撤销、平台副作用与consumer契约。
- **ConfigLib：未开始模块审查。** 待查配置模型、编解码/验证、调用边界与测试。
- **Annotation：未开始模块审查。** 待查注解/生成处理、consumer及构建输出。
- **WebUI：未开始本次模块审查。** 待查后端服务/鉴权/生命周期、前端状态/编辑/恢复、API及两端测试；不以历史外部审计代替本任务记录。
- **构建/打包、仓库脚本与文档：部分历史规则覆盖，全面审查未开始。** 已有模块门禁和文档职责规则不是全部脚本/依赖/文档准确性检查；docs-site既有脏状态保留。用户模型/生成物不作为任意清理目标。

## 下一批边界

2026-10-14：CR-030、统一帧间六批及最后Core尾项已完成，不再把下面2026-10-12恢复主线的记录当作当前未完成事项。下一模块仍按用户原顺序回到已记录的Basic剩余修复（CR-040/041等）；本次只完成Core收尾，不擅自开始其新代码改动。Stage2游戏测试就绪还须按父任务核对其他模块/完整开发服迁移及现场事项。

2026-10-12用户纠正擅自切换模块的执行顺序：Basic首轮完整审查及CR-037/038/039修复保留记录，剩余几何、配置UI和维护项暂存。当前返回原CR-030每车JS完整迁移及collision四端联结事务，不能把Basic剩余修复插入该主线。用户允许连续小提交后统一检查，不等于允许自行改变任务顺序；整体审查与正式游戏验收仍未完成。
