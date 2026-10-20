# Research: 当前全部模块的模拟领域覆盖边界

- Query: 盘点当前已有模块领域功能如何进入隔离模拟，查找事件消费者的模拟旁路、共享可变状态及缺准备器的失败边界，为父任务最终全 Feature 验收提供清单。
- Scope: internal；只读产品代码及相关 CURRENT specs，不构建、不提交。
- Date: 2026-10-19
- Workspace: `/workspace/minecraft/JavaRailways/.worktree/simulation-signal-features`。
- Baseline: 在本批开始时的信号 a5095090 候选基础上逐段阅读实际源文件；该指纹由主会话提供，本研究没有重算，也不借旧任务 research 或旧测试总数推断当前完成度。本文是行为批实施前的 inventory，后续源文件修改须由最终检查更新对应行号及状态。

## Findings

### 1. 完整模块清单与业务范围

`settings.gradle:15-25` 实际 include 11 个模块；以下无遗漏地覆盖该列表。规范目录中存在名称不等于当前已安装模块，不把目录名推断为 Feature。

1. **Core — 静态已接通，需联合实测。** CoreWorld、CoreTrain、轨道/拓扑、纵向推进、占用、碰撞检测及结果应用、联结/解编、物理 epoch/frame、事件来源为两模式共用产品代码。SIM 使用 `CoreWorld.load(infrastructure, runtime=null, SnapshotDependency(..., SIMULATION))`，新建物理池、独立 logical tick、独立 executionOwner；并不复制在线列车/预约。证据：`SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt:239-313`（下文缩写路径均对应本节 Files found 中完整路径），`CoreWorld.kt:72-109`。碰撞没有模式门：`PhysicsCoordinator.kt:449-484` 同步发布实际 World 的 CollisionEvent，`773-811` 下 epoch 执行真实 DERAIL 或排入真实 coupling owner 操作。
2. **Basic — 静态已接通，需联合实测。** 轨道公共方向、几何/元数据与静态限速、WayPoint、安全 interlock、速度安全、真实 HandleTrainActuator/TrainDynamics、物理资源推导、registry/claim/preparation/grant/tail release 都使用现有领域部件。`BasicWorldData.kt:18-25` 为目标创建独立 store；`BasicInfrastructureSnapshot.kt:63-113,119-150` 捕获/恢复 WayPoint、rail metadata、方向；目标再次 materialize 默认速度 transition（`IsolatedTimetableSimulation.kt:304-307`）。模拟本地 host 的 `RouteResourceAdmissionRuntime.create` 与 LIVE 同一类，admission update 传给本目标 SignalEpochRuntime（`338-431`）。人工驾驶输入/玩家钥匙属于平台适配，未复制人工操作状态符合已批准初始条件。
3. **SimpleATO — 领域主链静态已接通；逐车操作仍有确定缺口，归当前行为批。** OperationalWorldRuntime 对两模式创建相同限制 store、inspection、SignalEpochRuntime、Basic 数据、安全协调器及 Dispatcher（`OperationalWorldRuntime.kt:75-105`）；publish observation → derail request → Dispatcher → generic postTick 顺序共用 `afterPublication:42-56`。Destination/Station/Depot/Line 来自 SimpleATO 基础设施快照并重新建立目标 store；时刻表列车根据 profiles 合成，非 live checkpoint。真实 SimpleATO/actuator/safety 使用捕获的控制参数（`IsolatedTimetableSimulationFactory.kt:185-215`）。当前 factory 仍创建 duration resolver gateway（`158-160,361-386`），不能称完整逐车脚本；详细 runtime 与 capture 改法由本批另外两份研究负责。
4. **SimpleATOBukkit — 平台适配已明确排除；其领域归属已在 SimpleATO。** 实际 `SimpleATOBukkitModule.install:13-17` 只初始化并登记 SimpleATOPlugin extension namespace；其余 main 源是 destination/Wand 编辑与平台诊断文件，不存在第二份必须安装到 SIM 的运营引擎。SIM 可使用已由启动登记的 SimpleATO slot，但每 World ExtensionDataContainer/store 是新对象。
5. **SimpleSignals — 本批信号链静态完整接通；已有专项证据归主会话，仍需联合场景。** Plugin 通过 simulationFeatures 注册 manager.prepareSimulation（`SimpleSignalsPlugin.kt:82`）。准备器只捕获定义、catalog.rules 中立规则输入和保护距离（`SimpleSignalsWorldRuntime.kt:1337-1348`）；目标重绑 UUID、真实 compile、occupancy、fixed block domain policy、planning、两种 inspection、signal sources 全部安装（`SimpleSignalsSimulationFeature.kt:28-77`）。每目标逻辑/清理归 target，不保留 LIVE runtime/Plugin/model handle。信号错误日志包含 simulation 与 target UUID（`47-49`）。不能因这些静态接线或上一批总测试数声称本批车厢/信号/碰撞联合通过。
6. **BukkitPlugin — 混合模块。** Bukkit 实体、玩家/region 调度、模型显示、声音、CSV 平台出口排除；其中已有车厢脚本业务仍在任务范围，必须从 renderer/platform 出口分离而非整块排除。LIVE World factory 调用共同 OperationalWorldRuntime（`RailWorldFactory.kt:134-210`）；HTTP factory 捕获 source → isolated snapshot → create 真模拟（`IsolatedTimetableSimulationFactory.kt:120-149,153-220`）。宿主 Feature authentication/registry 只在准备边界使用；目标不调用 Bukkit 服务。
7. **TunnelBridgeBuilder — 平台功能排除。** 独立 Bukkit add-on 的入口只取得 RailTargetService/PlayerOperationService、注册 wand 与 RailSweepService（`TunnelBridgeBuilderPlugin.kt:20-35`），输出 Minecraft 方块/隧道/桥，未发现运营 epoch provider/contributor 或事件 consumer。纯几何算法虽可无 Bukkit 测试，模块实际用途不构成要在每次时刻表 SIM 中执行的运营算法；已建轨道的静态设施仍通过 Core snapshot 进入 SIM。
8. **WebUI — UI/HTTP 调用端，非模拟 World Feature。** standalone Javalin 服务（`WebUiApplication.java:30-40,85-115`）提供会话、代理和编辑界面；铁路执行归 Bukkit HTTP bridge/SimpleATO。SIM 不启动第二 WebUI 服务、不复制 draft/session；保留前端/桥接回归属于最终门禁，不等于把 UI 安装进模拟。
9. **BukkitPluginDev — 开发命令/测试插件，平台排除。** main 源为 `/jrdev` 等诊断/运动/配置/物理测试入口；不属于生产 Railway World 领域 runtime。以源码清单和其 paper-plugin.yml 核对，不因 module 被 include 就认为需要装配到 SIM。
10. **ConfigLib — 共享平台无关工具，已由真实模型链复用。** `Persistence.kt:10-39` 提供 PersistentBy/Loads/DelegateSerializer 等依赖注入式加载工具；不是需复制运行状态的铁路插件 Feature。Core 真实 load 使用自身 SnapshotDependency 创建目标；没有进程全局运营状态需额外安装。
11. **Annotation — 未启用实验构建工具，非已安装运营 Feature。** `Core/build.gradle.kts:34-35` 的 Annotation/KSP consumer 行仍注释；Annotation main 源为 KSP provider/processor。不能将它列为实际领域能力或本任务需要补齐的模拟缺口。

### 2. 事件 consumer 与进程全局状态

对上述模块全部 main Java/Kotlin 源搜索 `@HandlesEvent` 与主/物理 bus register，以及 `executionMode/SIMULATION/isSimulation/isLive`。当前仓库生产 handler 只有两个，均由 BukkitPlugin 启动登记：

- `TrainReverseBehaviorListener.kt:11-19` 对 SIM 直接 return，然后 LIVE 调 renderer.callBehavior(onReverse)。**这是仍存在的领域行为旁路**：onReverse 影响真实逐车脚本，属于当前行为批必须替换的桥，不可因文件在 Bukkit 模块就视作全部平台排除。应由本批目标运行时处理 target 车厢行为，同时保留平台出口分离；本研究不指定具体新 API。
- `TrainDebugEventListener.kt:11-34` 对 SIM return，剩余操作是 TrainDebugLog CSV 行、真实 Bukkit 世界/当前平台时间。这是平台诊断出口旁路；不会跳过 derail 的 Core 状态应用。模拟诊断应由目标 runtime 独立提供，不能声称 CSV 即运行安全链。

另外，Core 的换向、脱轨、过信号、碰撞、联结/解编决策和成功事件共 8 种拥有 WorldEvent 来源；加 Pre/Post 共 10 个具体运行事件。`TrainEvent.kt:7-18` 复用 train.world，`TrainLinkEvent.kt:42-43` 取 firstTrain.world，CollisionEvent 直接持 World（`26-32`）；`PhysicsCoordinator.kt:590,755` 每真实 epoch Pre/成功 Post，无 SIM 分支。

进程级 bus 仍然共享，这是已批准的同步 dispatcher：`TypedRailEventBus.kt:36-37` 共享 handler 目录和 registrationLock，仅 register/unregister 锁目录；`93-112` 不持该锁同步调用 consumer。现有两个 handler 没有按 trainId 保存可变领域 Map；CSV 全局日志在 SIM 被过滤。**新行为桥不能用仅 trainId 的进程全局状态或全局 JS Runtime 代替 target 的脚本/时间状态。** 各 World 发布可能并发调用同一个 listener，第三方 listener 必须依 event.world 隔离自己的状态；全局 bus 本身不自动隔离外部闭包。

可合理保留的共享设施与隔离边界：

- `PluginRegistry.kt:7-25` 共享启动期 plugin namespace/identifier，不持列车运行状态；BasicPlugin/SimpleATOPlugin INSTANCE 只用于同 slot 定位。`ExtensionDataContainer.kt:3-8,14-16,40-44` 的扩展列表属于各宿主对象，新 World 不能复用源 container。
- `WorldExecutionOwner.kt:8-14` 真锁是实例字段；companion 的 AtomicLong 只排序、ThreadLocal 只记录当前线程持有者。跨 World runTogether（35-42）仍限管理事务。SIM 配 NoOpWorldParentContext（`IsolatedTimetableSimulation.kt:284`）不连接 LIVE Koin scope。
- TickManagerImpl 的 scheduler、clock、tickingObjects、commit/postTick queues 均是实例字段（`21-34`）；目标逻辑 tick 从 0 按 50ms 推进，不能从通用线程 marker 推断实际 World 身份。
- SignalLogicRuntime 的 Engine、sources、closed 是实例字段；evaluate/close @Synchronized 是所属实例 monitor，允许同 World 串行，非跨 World 全局锁（`SignalLogicRuntime.kt:23-28,43,62`）。静态 HostAccess 与 discard stream 为配置/无状态出口，不是共享 JS Context。
- 仍有 process host ThreadGuardBridge 与日志/serializer 注册表；不作为目标脚本运行状态。此次不是对全部 static/object 做无语义清除；本研究也没有完成所有源码对象的穷举全局状态审计。

### 3. 缺少必需准备器是否明确失败

静态确认以下链条为明确失败，未发现本仓库已接入 owner 被静默漏掉：

1. `BukkitPlugin.kt:192-199` required 集合为 infrastructureResourceService 与 signalEpochSourceService 所发现的 owner 并集。
2. `DefaultInfrastructureResourceService.kt:822-826` 收集已登记 resource provider 与 route contributor 的小写插件 owner；它们是对所有绑定 World 可用的插件级注册，检查不是逐场景任意省略。
3. `DefaultSignalEpochSourceService.kt:35-39,128-219,345-346` 同一 OwnerSources 保存 epoch、inspection、planning；required 从这个集合取 owner，因此**只提供 inspection 或 planning 也必须有准备器**，并非只检查 epoch。
4. `DefaultSimulationFeatureService.kt:15-23,27-36` 拒绝 disabled/duplicate/closed、准备异常、返回 ID 不符；只在目录锁内复制列表，prepare callback 在锁外。`IsolatedTimetableSimulationFactory.kt:120-125` 再以 required - prepared 集合判定缺项并 require 失败，尚未创建 target/worker。
5. `TimetableExtensionRegistry.prepareSimulation:274-288` 对所有非原本 built-in condition、全部 action 要求相应 SimulationFactory；缺失立即 require 失败。`TimetableSimulationExtensions.create:216-219` 为每目标新 registry，并调用每份 preparation.create(targetWorld)。built-in conditions 在每个 registry constructor 重新创建（`BuiltinTimetableConditions.kt:9-59`），不复用 LIVE provider instance。
6. 本地 admission 收到没有对应 provider 的 contribution 时返回 INVALID_CONTRIBUTION（`IsolatedTimetableSimulation.kt:409-415`），不是当作无贡献放行。

约束的实际可证明范围：上述 guard 对显式登记的 domain owner 和 timetable provider 有效；**它不能从任意第三方全局 EventBus listener 或 arbitrary Core extensionData 反推插件是否有必需模拟能力**。当前 11 模块的真实 composition 没有发现此类额外运营插件。新插件若仅偷偷改变事件决策或对象闭包，不能以“插件启用但未登记 owner”自动视为已被准备。不要为此添加每次事件发布归属校验或通用 clone 框架；应在插件真实接入边界明确其必需 Feature 合同。

### 4. 确定缺口、静态接通与最终联合验证清单

**确定缺口，当前行为批负责：** duration-only operation resolver；SIM onReverse 直接跳过。真实每车脚本、门侧/换向、拒绝、多个延迟、失败、取消、全部效果完成、target 诊断和无平台效果由另外两份研究展开，本文不重复提出另一套行为运行时设计。

**静态已接通，但本研究未执行证明：** Core 物理/碰撞/事件与 Basic/ATO/正式信号完整领域安装链；独立 owner/physics/tick/JS/claim/source 生命周期；扩展 factory/required owner 缺失失败。上一批已有专项验证的精确 candidate、XML 与 source hash 由主会话维护，本文不从测试总数把细节自动提升为联合验收。

**建议父任务最终联合 fixture 必须观察的事实：**

- 同一设施/正式 JS、实际门脚本、timedata/ext providers，在 LIVE fixture 与 SIM 执行真实业务，比较操作选择/拒绝、门/换向状态、信号限制、到发及全完成顺序；initial conditions 保持已批准的静态设施+合成列车，不要求复制 LIVE checkpoint。
- A 在脚本真实执行/逻辑延迟或实际 source observation 构造中被 barrier 暂停，B 的 owner、物理 epoch、正式 signal 与 timetable 能继续；覆盖 LIVE-LIVE、LIVE-SIM、SIM-SIM 及同 A 顺序，不能只测执行器空 runnable。
- 联合场景产生真实 collision decision 与 link/derail 结果，并确认另一同名 train/同定义另一 World 未受影响；若发生拆车/联结，检查实际车厢行为绑定仍随同车归目标，且新列车的 control profile lookup 不仍依赖仅 seed 时登记的旧 trainId map。当前 factory `profilesByTrainId` 在 seed 登记（`157,235-240,274-278`），controller 创建时按 train.id require（`185-188`）；完整静态 trace 已在第 5 节确认具体失败条件；真实联合复现尚待实现/检查。
- 扩展 prepared definitions 复用到两个 SIM 时 create(targetWorld) 得到不同 provider/script/ticket/clock；关闭/取消 A 的真实 worker 退出后才撤 source/logic，不撤 B；失败及诊断包含实际 target identity。
- 缺 condition/action factory、缺 domain preparer、坏正式规则、目标 install 中途失败均明确失败且不留下可用假成功或影响别的实例。

**平台排除/不作结论：** Bukkit/Paper/Folia API、实体/音频/显示/玩家输入、Tunnel 方块铺设、WebUI 会话和可视编辑不是模拟 World Feature；本研究没有运行 Paper/Folia/client。也不声称独立进程资源隔离、CPU/GC 隔离或第三方无限循环脚本强制终止。

### 5. 结构变化后 profile 的具体追踪（按主会话补充查询）

以下是当前 source 的确定静态数据流，不是已运行复现；触发条件分别列明，避免把全部联结一概说成失败。

**Core 真实结构事务：**

- `CoreTrain.kt:765-798` split 保留/分离的是同一批 CoreCar 实例；为新 train 创建新的 ExtensionDataContainer，调用每个 TrainExtension.splitBefore，旧 train 的 controller 通过 prepareConsistChange 更新保留半列。新 CoreTrain 从这些专属 extension 构造，**不自动复制原 controller**。成功后才发 TrainSplitSuccessEvent（808-814）。
- `TrainCoupling.kt:84-107` 选择 master，准备 master controller 与 removed controller 的 consist changes，并为 union extension slot 调 prepareLink。commit 时安装 controller updates、移除 removed controller/train、保留相同 CoreCar 对象（115-126）；成功事件在提交之后（132-135）。Core 不知道 profile map，不会维护宿主按 trainId 的外部目录。

**LIVE 当前行为：**

- `Bukkit/.../extension/train/TrainExtension.kt:147-159` split 与 link 返回专属 TrainExtension，复制 immutable creation template 名/快照 hash/快照引用，initialize 绑定新 host（44-49）；无须 success-event listener 重建。
- `CarExtension.kt:119-143` 每原 Car 的 control 从它自己的 pinned/follow package 模型查出；CarExtension 随 CoreCar 身份迁移，没有按新 trainId 重新从旧列车车序猜 control。
- `RailWorldFactory.kt:175-203` 每次建立 SimpleATO 都遍历 **当前** train.cars 重新 resolveControl/resolveCarControl，按当前编组计算 safety，并为 HandleTrainActuator 提供 per-car profileResolver。
- 已存在 controller 也会由 Core 调 AbstractATO.prepareConsistChange（`AbstractATO.kt:40-47`）更新 dynamics 与 braking safety。`TrainDynamicsExecutor.kt:50-58` 对每实际 Car 依次查 own identity map、counterpart DynamicsProfileSource、profileResolver；它不使用车序索引复制另列控制。

**SIM 的确定缺口与非缺口：**

1. **新 split train 后续需要 ATO/操作时必然丢 lookup。** 当前 `profilesByTrainId` 只在 seed 注册，既无 Core TrainExtension hooks 也无结构 success consumer 写新 ID。若新 train 被后续 timetable/extension 选中，`IsolatedTimetableSimulationFactory.kt:185-188` 按新 train.id require 失败；Dispatcher 实际进入 factory 的路径在 `TimetableDispatcher.kt:680-689`，并返回明确 startup failure。旧操作 resolver 对新 ID 返回 null（factory:365），进而拒绝，不是自动继承原脚本。**当前内置 timetable 没有自动 split action**，因此不能声称每份现有时刻表都触发；但插件可通过真实 Core API 拆车，用户全 Feature/扩展联合合同不能当作不可发生。
2. **旧 train split 或 link 后重新建 ATO 时必然有错误 car count。** 其 seed ID 仍可 lookup，却传最初 profile.carControls 而非当前 cars。`TrainDynamicsExecutor.kt:39-44` 要求数目相同并按 index 建 identity map，车数变化后 require 失败。后续原 train 无 controller 的 service startup、或 Dispatcher.replan replacement（`1102-1109`）都是实际 factory 调用入口；无需假定另一个 profile loader存在。
3. **已有 ATO 持续运行并非普遍失真。** 截断旧半列或联结双方都有 DynamicsProfileSource 时，Core + AbstractATO + TrainDynamicsExecutor 的真实准备机制已按实际 Car 更新 actuator/safety，不能误报旧 actuator 仍永远用原车序/质量。
4. **有 controller 的 master 联结无 controller 的 incoming 存在 LIVE/SIM 不一致的确定分支。** SIM factory 当前不传 profileResolver；incoming car 既不在 own map 又没有 counterpart source，则 `TrainDynamicsExecutor.kt:53-54` 报 missing profile，阻止结构提交。LIVE 的 per-car CarExtension resolver 可以提供该原车控制。触发条件需要真实 moving/stationary collision/link 约束成立；本文未运行碰撞 fixture，不能声称已重现完整时序。

**最小接线建议（不实现）：** 利用本行为批本就需要的目标 TrainExtension/CarExtension 保存 captured immutable TrainControl/CarControl。train extension 现有 split/link hooks 创建专属新 wrapper、保留 master train-level 配置；每车 control 和脚本 state 跟实际 Car 对象，结构事务不复制成新的共享脚本。factory 每次从当前 train 与当前 cars 解析并重算 safety，HandleTrainActuator 传同一 per-car resolver；operation gateway 也从当前 train/车厢读取，消除执行依赖 seed-only trainId map。可保留 seed metadata用于初始建造，不新增配置系统、全局结构 listener 或发布归属验证。

### 6. 最小联合测试模块与依赖建议（按主会话补充查询）

- 当前 BukkitPlugin 与 SimpleSignals 的 plugins 列表均**没有 java-test-fixtures**。现成共享 fixtures 仅 Core、Basic；Bukkit HTTP factory 是 internal，Signals 现有 manager test helpers 又是 test private，跨模块无法直接 reuse 测试私有实现。
- 建议联合测试归 `BukkitPlugin/src/test/`，只加 `testImplementation(project(":SimpleSignals"))`。宿主本模块 Kotlin test 可调用真实 internal IsolatedTimetableSimulationFactory，不复制 factory、不公开生产 API。
- 静态 Gradle 图上没有 production cycle：SimpleSignals main compileOnly Bukkit main；Bukkit test → Signals main → Bukkit main，不回到 Bukkit test class 输出。`build.gradle:137-139` 模块边界只检查宿主 main api/implementation/compileOnly/compileOnlyApi/runtimeOnly；不禁测试边。shadowJar 的 runtime graph 不包含该 testImplementation，仍须用最终 boundary/JAR gate 验证这一事实。
- **注意已有测试配置桥：** `SimpleSignals/build.gradle:46` 使用 `testImplementation(files(BukkitPlugin.testRuntimeClasspath))` 复用配置依赖。新 test edge 会让 Signals 自己的 test classpath 也看到 Signals main JAR，静态未见 test-output cycle，但应首轮实际 Gradle resolve/build 验证；本研究不运行构建，不能把静态推断写成已通过。
- Signals 的 feature/definitions/runtime/occupancy 类型是 Kotlin internal，不要为了联合测试扩大生产 public SPI。用一个 Bukkit test-only Java helper 调现有 JVM public constructors 并返回既有 PreparedSimulationFeature，即可把真实 SimpleSignalsSimulationFeature 送 factory。已只读 `javap` 检查现有 a509 class 输出，确认 Feature 的 `(String, SimpleSignalsWorldDefinitions, SignalRuleCatalog, double)` 与 Definitions 的完整 collection 构造器为 JVM public；不需要反射，也不复制规则。Java helper 必须使用真实产品 compile/install，不能手写 empty sources 或模拟替代。
- 正式四灯例包位于 `SimpleSignals/examples/jr-four-light/signal-packs`，当前只由 Signals.processTestResources 复制进 Signals **test** resources；它不随 main JAR 导入宿主测试。宿主 test 可直接用 rootProject 对应路径，或在本 test resources 单点复用目录配置；不要复制一份 YAML/JS 或添加 production 打包内容。公开 SignalPackLoader.load 可读正式包，准备 feature helper 返回公开 PreparedSimulationFeature。
- 现有 Core real train/rail fixture 可直接复用（宿主已有 Core testFixtures 依赖），Bukkit 自身 test suite 的 server registry bootstrap也在本模块。涉及 ItemAppearance/Material 的 loader 环境仍需真实 cached Paper registry bootstrap；仅试图用规则字符串手抄正式 JS 不能证明完整 loader/安装链。

## Files found

主证据路径（每项一行说明；上文短文件名以此唯一定位）：

- `settings.gradle` — 当前 11 include 模块清单。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt` — LIVE World 的共同领域和平台 adapter composition。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt` — LIVE 正常发布调用共同 afterPublication，平台呈现处于 beforeDispatcher 出口。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/runtime/OperationalWorldRuntime.kt` — 两模式共同 per-World runtime、signal/inspection/safety/dispatcher 生命周期。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt` — SIM World 初始化、真实物理 stepping、本地 admission、终止/cleanup。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt` — source capture、必需 Feature 判定、真实 ATO/profile 注入及现有 duration-only gateway。
- `Basic/src/main/java/work/art1st/javarailways/basic/BasicWorldData.kt` — World 独立底层 store/safety/metadata。
- `Basic/src/main/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshot.kt` — 静态 WayPoint、限速元数据和方向复制。
- `Basic/src/main/java/work/art1st/javarailways/basic/rail/OperationalSpeedLimitSignalMaterializer.kt` — 每目标重建默认限速 transition。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/SimpleATOWorldData.kt` — 每 World Destination/Station/Depot/Line/timetable/extension store。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/SimpleATOInfrastructureSnapshot.kt` — SimpleATO 静态运营对象复制与重新 materialize。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/TimetableExtension.kt` — extension preparation/fresh-target provider/failure 合同。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/BuiltinTimetableConditions.kt` — 内置 condition 在新 registry 重建。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt` — 正式插件登记 simulation preparation。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt` — 当前只读信号 capture。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsSimulationFeature.kt` — 正式规则与全部 source/resource chain 的目标安装。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalLogicRuntime.kt` — per-instance Engine/source/monitor ownership。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt` — 当前 required owner/authenticated prepare/事件登记。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultSimulationFeatureService.kt` — 短目录和锁外 preparation、owner ID 与显式失败。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultInfrastructureResourceService.kt` — required provider/contributor owner 集合。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultSignalEpochSourceService.kt` — epoch/inspection/planning 统一 owner 发现。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainReverseBehaviorListener.kt` — 当前 SIM onReverse 领域旁路。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/debug/TrainDebugEventListener.kt` — SIM 平台 CSV 诊断过滤。
- `Core/src/main/java/work/art1st/javarailways/core/api/event/*.kt` — 8 个运营事件与 Pre/Post 的统一 World 来源。
- `Core/src/main/java/work/art1st/javarailways/core/api/event/processor/TypedRailEventBus.kt` — 全局同步 listener 目录，正常 dispatch 无 registrationLock。
- `Core/src/main/java/work/art1st/javarailways/core/api/util/plugin/PluginRegistry.kt` — 全局启动期 namespace 目录。
- `Core/src/main/java/work/art1st/javarailways/core/api/util/plugin/ExtensionDataContainer.kt` — 每宿主实际 extension slot 数据。
- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt` — per-World physics topology/trains/index/owner/runtime identity。
- `Core/src/main/java/work/art1st/javarailways/core/physics/WorldExecutionOwner.kt` — 本地锁与低频跨 World 管理归属。
- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt` — 无 SIM 旁路的碰撞与真实 Pre/Post 发布。
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt` — 本地时钟、执行器与 tick/commit queues。
- `Core/src/main/java/work/art1st/javarailways/core/api/threading/ThreadGuard.kt` — 共享平台分类入口；不是运行上下文身份。
- `Core/src/main/java/work/art1st/javarailways/core/di/NoOpWorldParentContext.kt` — SIM 不绑定 LIVE Koin child scope。
- `SimpleATOBukkit/src/main/kotlin/work/art1st/javarailways/simpleatobukkit/SimpleATOBukkitModule.kt` — 只装配领域 namespace 的嵌入平台入口。
- `TunnelBridgeBuilder/src/main/kotlin/work/art1st/javarailways/tunnelbridgebuilder/TunnelBridgeBuilderPlugin.kt` — 独立平台方块编辑 add-on。
- `WebUI/src/main/java/work/art1st/javarailways/webui/WebUiApplication.java` — 独立 UI/HTTP 服务，非 World runtime。
- `BukkitPluginDev/src/main/` — 开发平台命令/测试入口 inventory。
- `ConfigLib/src/main/kotlin/work/art1st/configLib/persistence/Persistence.kt` — 无 World 运行态的工具模型。
- `Annotation/src/main/kotlin/work/art1st/javarailways/annotation/processor/` 与 `Core/build.gradle.kts` — KSP 实验及未启用 consumer。

## Related specs

- `.trellis/workflow.md` — 研究落盘、阶段及 scope 合同。
- `.trellis/spec/javarailways/core/event-bus.md` — World 来源、物理 epoch、同步 bus 与平台 consumer 边界。
- `.trellis/spec/javarailways/core/di-and-runtime-lifecycle.md` — World/runtime 归属。
- `.trellis/spec/javarailways/basic/index.md` — Basic 与 SimpleATO/信号/platform 功能唯一归属。
- `.trellis/spec/javarailways/simpleato/simulation.md` — 静态设施、共同领域、prepared Feature、终止及开放行为缺口。
- `.trellis/spec/simple-signals/backend/simulation-runtime.md` — 正式规则、目标安装与 per-World 执行合同。
- `.trellis/spec/simpleato-bukkit/backend/index.md` — 平台适配范围。
- `.trellis/spec/tunnel-bridge-builder/backend/index.md` — 方块编辑/玩家工具范围。
- `.trellis/spec/annotation/backend/index.md` — 实验 KSP 未接生产。
- `.trellis/tasks/10-18-isolated-simulation-runtime/prd.md` — R1–R6 与 AC1–AC7 的父级完整目标、批准初始条件和平台非目标。

## External references

未用外部参考。全部为当前工作区实际源文件和 CURRENT 规范；未查询发布 API、版本或网络资料。

## Caveats / Not Found

- 本研究覆盖完整 Gradle 模块 inventory、两模式真实 composition、仓库全部生产事件 handler/模式分支及准备器缺失 guard；不是 11 个模块每个函数的全仓缺陷审计，也没有检查未提供的服务器插件安装目录。
- 逐车 runtime、行为 capture/gateway 的具体设计由并行专题研究拥有；本文只标边界和真实残留旁路，避免重复或另起框架。
- 新行为 implementation 将修改上文部分行号和缺口状态，最终 checker 必须以最终源指纹更新结论。
- 当前研究没有执行任何测试或运行服务器；源码接通、历史专项测试与最终联合行为验证保持三个证据层级。
- 不能由 owner registry 推导任意第三方 EventBus listener 的不可变性/World 绑定，亦不新增过度防御的事件发布验证来掩盖该合同。
