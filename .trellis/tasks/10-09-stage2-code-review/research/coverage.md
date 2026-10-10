# 全项目审查覆盖记录

本清单描述实际覆盖，不按文件数/测试数计算审查百分比。状态分为未开始、部分覆盖、模块首轮完整审查完成（不等于模块通过）。源码静态检查、定向复现、既有测试执行和游戏现场验证分别记录。

## 当前模块交付（2026-10-10）

- **Core 首轮完整审查完成，后续仅CR-022已修。** 合并C1与C2，按审查基线源码清单闭合全部136个生产文件、49个测试源文件（包括2个helper和1份opt-in stress）、5个fixture；另查build、Dokka输入、全部14份Core规范及对应网页/代码地图。数量只用于发现漏审文件，不是质量分数。后续CR-022新增1个正式回归测试源，修复及验证另见[本批记录](../implement.md#cr-022-修复与验证2026-10-10)。
- [整模块报告](./core-module-review.md)：逻辑、可维护性/可读性、文档、错误处理/过度兜底、残留代码、规范一致性、测试七维结论；含优先级和建议。
- [逐文件清单](./core-review-inventory.md)：明确分区、文件和文档范围；不是只列已发现问题的文件。
- 本轮真实复现：split失败破坏原编组、跨bucket容差漏碰撞、LINE最近点偏移、移动时car/bogie速度仍为0、损坏车端存档发布错误占用、重复列车构造遗留ACTIVE端点。临时诊断只在`/tmp`，不进入正式实现/测试套件。
- 本轮强制重跑`:Core:test :Basic:test`及Core Dokka：BUILD SUCCESSFUL，18个Gradle任务执行；Core46 suites/212项（206执行、6跳过），Basic59 suites/382项全执行，零失败/错误。Dokka仍有缺KDoc/无法解析链接warning，不能认定文档合规。未执行opt-in stress matrix，也未部署或做游戏/Folia验证。
- **Basic仍仅部分覆盖。** 本轮重跑其测试是Core边界验证，不等于阅读完Basic；下一次以完整Basic为报告单位。

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

- **Core：首轮完整静态审查完成，CR-022已修，其余待处置。** C1+C2覆盖审查基线的全部生产/测试/fixture及对应文档；6条诊断已复现，其余结论明确标静态证据或待验证。CR-013至CR-018、CR-020的Core部分与CR-021至CR-034（除CR-022）仍待处置，不能写成Core通过。平台全生命周期及现场行为未因跨层追踪而完成。
- **Basic：部分覆盖。** C1只覆盖observation消费及部分生命周期/释放边界，之前metadata/API清理不算全模块通过。待查通用寻路与投影、resource registry/admission/edit permit完整并发链、actuator/动力学、安全限速/脱轨、curve authoring、WayPoint/方向/metadata、ConfigUI及持久化。
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

用户已选择先分批改进Core，首批CR-022完成后停下等检查；随后按已确认计划推进。恢复完整模块审查时下一模块仍为Basic，而非再提交一个局部调用链报告。全项目审查、全部问题修复和正式游戏验收仍未完成。
