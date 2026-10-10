# Core 首轮完整审查

日期：2026-10-10。源码基线：`dev/signal-system` / `8f1d845d`。

结论：**Core整模块首轮静态审查完成，但不能判为通过，缺陷尚未修复。** 合并先前C1的runtime/publication检查与本轮全量源码、测试、契约检查；不是只抽查Stage 2 diff。Basic仍只有局部覆盖。

## 范围、方法与证据等级

- 完整阅读Core全部136个生产文件、49个测试源文件、5个fixture；文件集合闭合见[逐文件清单](./core-review-inventory.md)。另核查build.gradle.kts、两个Dokka输入、全部14份Core spec和相关网页/代码地图。
- 四个分区：曲线/轨道/拓扑；列车/控制/渲染值；事件/扩展/持久化/线程/DI；CoreWorld/runtime/build与整合。分区全部结束后才报告完整模块。
- 结合真实producer/consumer检查：Core→Basic occupancy；Core→SimpleATO运营点迁移；Core→Bukkit加载、卸载及render frame。跨层追踪不等于其他模块审查完成。
- 对删除候选检索全仓生产、测试、注册/资源、持久化及文档；公开声明无仓库caller不等于不存在外部使用者。
- 下文“已复现”使用实际Core类和真实simulation，只在`/tmp`放诊断；“静态确认”有明确源码路径但未执行对应失败序列；“候选/缺口”不冒充已触发故障。
- 未修改运行时代码、未删除测试、未改schema version、未增加兼容路径、未部署。docs-site与其他已有脏文件保持不动。

问题稳定编号见[台账C2](./issues.md#c2core-整模块审查2026-10-10)。P1是应优先处理的状态/生命周期错误，P2是具体正确性或维护风险，P3为可延期治理；不把每个P2自动定为全项目游戏测试阻塞。

## 用户确认（2026-10-10）

- 联结/解编保留为正式能力；CR-021/030是待修缺陷，不再待决定是否删除该能力。此确认不改变默认碰撞DERAIL，也不表示现有实现已验收。
- 目前没有仓库外插件；已补[旧事件扩展价值评估](./core-event-api-assessment.md)及[Core/Bukkit座位评估](./core-seat-api-assessment.md)。用户进一步确认三个事件保留并考虑接入，旧删除建议撤回；阶段/线程等具体契约待接入批次明确。Core Seat原型删除仍只是待用户检查的建议，本次未批准。
- 开发服`192.168.1.230`的存档必须能手动迁移；暂不要求JAR内自动迁移。迁移按真实一致样本、离线dry-run、保留原件/恢复点与迁移后校验处理；本轮未读取或验证服务器数据，也未获远端修改授权。

## 1. 逻辑正确性

### 优先处理：加载/失败后状态不可信

- **CR-022，P1，已复现：列车构造失败留下ACTIVE车端。** `CoreTrain.kt:142-166`先创建注册head/tail，再检查world身份；`SavedTrainV1.load`的try在构造之后。重复ID/name不是不可能错误：文件和命令可能重复。身份拒绝后rail中车端从4增至6，且孤儿车端ref与原列车重复。应前置身份校验并撤销constructor副作用，不只加强构造完成后的cleanup。
- **CR-023，P1，已复现：损坏current存档可发布脱节的占用。** `CoreTrain.kt:345-361`只检查每车bogie间距，直接接受存档head/tail。有效bogie仍在50.5m，改head/tail为180/140m后load成功，`bodyTraversal`与`occupiedIntervals`却是[140,180]。必须校验完整编组、相邻车/车端和方向；round-trip不能覆盖这种损坏输入。
- **CR-027，P2，静态确认：load容错与严格restore不一致。** `CoreRail.kt:136-168`忽略无效UUID/缺失rail，只按最近端点恢复，不验证共点；对称但远离的接头可形成看似合法edge。prepared snapshot restore已严格校验引用和距离，应在同一输入边界统一，而不是业务层多加兜底。
- **CR-028，P2，静态确认：raw曲线及向量decode未验证完整输入。** `PrimeCurveImpl.kt:24-33`不校验至少两点、零起点、严格递增弧长、finite/frame；手写Vector3dSerializer缺少分量会补0。前者已被curve spec标TRANSITIONAL，不伪装成新发现；后者与网页“missing必填立即失败”不符。集中在构造/decode验证，不放进每次求点热路径。

此前C1的**CR-013/014/015/016/017**仍未修：卸载失败保留已关闭world、最终保存缺少fallback静默屏障、factory部分初始化漏关资源、close状态可被在途失败覆盖、Koin world scope不释放。部分位于宿主，Core整模块交付仍保留跨层问题，不因模块边界忽略它们。

### 正常算法/事实输出

- **CR-024，P2，已复现：跨bucket容差内接触被碰撞query漏掉。** A=[31,31.996]、B=[32,33]，桶32m、容差5mm；exact overlap=true，collision query没有候选，blockingTrainIds却返回B。只证明容差内近接触漏检，不声称正长度相交也漏。复用按容差扩展的桶范围即可，不需要另一套index。
- **CR-025，P2，已复现：getNearestPoint是最近样本，不是几何投影。** 两点LINE的4.4m被算成4.0m；getPointAt(position)还拒绝这个真实线上点。SimpleATO `OperationalDestination.kt:279,298`的replacement migration及RailWand五个入口实际使用它，可能移动停车点。应按已有piecewise-linear segment投影求弧长，不能把stream加密成1mm掩盖问题。
- **CR-026，P2，已复现：列车标量速度10m/s，车厢/转向架velocity却为0。** 首个head bogie从未注入运动速度，后续仅从它推导。Bukkit另用trainSpeed补body部分，不修复Core事实，也不修复bogie frame。应统一epoch运动学输入；没有证据说明ATO标量速度因此错误。修复时还须核对angular velocity单位/杆长，不能只让零变成非零。

### 公开但未接入默认玩法的能力

- **CR-021，P2，已复现：splitFrom先改坏原编组再失败。** 四车splitFrom(3)抛ClassCastException而原车只剩两车；carNum=1也被接受后清空原车。extension枚举/初始化还存在后续问题。仓库无正式split调用；用户已确认保留此正式能力，需独立编组事务、预校验和失败保持，不是改一个takeLast就完成。
- **CR-030，P2，静态确认：link未规范化方向且丢弃失败结果。** `CoreTrain.kt:521-540`直接拼cars，reversed survivor的operating head与从first解算不一致；任务Future被丢弃，部分mutation后失败不可见。需明确端部/方向组合、结果和回滚。与C1 **CR-018** car renderer换属主后比较不同train revision的问题分别修。默认collision=DERAIL且无生产listener请求LINK，不描述为默认碰撞必现。
- **CR-031，P2/P3，静态确定：Core Seat.position自递归。** `CoreCar.kt:65`匿名Seat getter读取自身position。没有生产Core Seat consumer，Bukkit模型座位是另一条路径；按用户要求先评估座位分层再决定API去留，不能说游戏座位当前必坏。
- **CR-034，P2候选：EventBus register与空桶cleanup竞态。** 取得HandlerList后add前，该list可被close/unregister从map移除；新listener加入脱离map的list。没有定向复现，当前宿主注册/关闭串行，未找到正常生产并发触发。只需低频注册变更串行化或明确owner，不把锁加到publish热路径。

## 2. 可维护性与可读性

总体：CoreWorld/CoreTrain负责各自权威状态，publication和prepared edit已有明确边界；不能只按1478/841行判面条代码。真正应治理的是失败事务、废弃平行路径和重复验证。

- CoreWorld含多处重复prepared replacement校验、冗长fully-qualified类型和结构提交逻辑。适合抽局部共同校验/按职责整理函数，不适合新增几个共同修改同一状态的manager。
- split/link缺少同一结构事务；“先mutation、再排任务、异常藏在Future”比文件长度更值得修。
- 旧RailTopologyEditor split/merge还保留全world复制/rollback，但正式Wand已使用prepared方案。应先删除废路径，而非重构两套算法。
- `TopologyStateBuilder.kt:210-223`每个连通分量都扫描remaining取最小值，互不连接rail最坏O(N²)；`PreparedRailConnectionPlan.kt:301-302,381`逐端点扫描creations，同样O(N²)。这是结构编辑路径，未做性能profiling；排序种子/局部railId map足够，不新增长期catalog。
- `CoreCar`车内/车间求解已分函数并有公式注释，但offset正负注释与实际默认相反；activePositon拼写、残缺注释和废SavedCar代码应机械整理。
- `spansFromLocations`对bogie/端点有双重循环；render-publication复杂度应明确只描述新增frame/traversal阶段，不暗示整个move都是线性。
- 公开Bogie泄漏实现RailLocationWithFacing仍是API债，现有Core源码不包含Java实现；不能为了Kotlin风格删除有意保留的下游Java consumer。

以上归CR-007/032/033，不另开“大拆Core”实施范围。

## 3. 文档正确性与完整性

### Trellis：契约与现状混淆、关键内部原理不够

- persistence/extension-data写stable type registry、全面预校验/失败清理已存在；实际codec反射、编组恢复和constructor清理未满足。shared persistence却准确标遗留。应同时写当前差距与目标，不能改规范把旧实现漂白。
- train-physics说head segment复用于signal扫描，实际peekSignals又扫描一次；缺车内旧tail bracket、车间三维coupler error二分、压缩摆位向后修正、车端重定位等non-trivial原理。
- train-controller/testing列出的无command/finished once/invalid command、registration cleanup等要求未全部有测试；不要把“必需覆盖”写成“已有覆盖”。
- event-bus写NORMAL，enum实际MEDIUM；优先级只保证同声明事件type内顺序，跨supertype不能按全局priority理解。
- topology/prepared复杂度文字高于实现；curve spec准确标注raw输入校验是TRANSITIONAL，保留这一区分。

### Dokka / KDoc：能生成，但契约不完整且有错误

- Train.cars写operating head→tail顺序，实际reverse不重排存储cars；内部自行asReversed。
- occupiedIntervals应明确epoch-end placement，不是swept占用；Train.publishedFrame的null说明不符Core始终创建frame但可能bodyTraversal为空的行为。
- ControlCommand/TickingObject、Car/Bogie/TrainExtension缺单位、线程、失败与所有权说明；RenderableStateSnapshot缺live JOML/World不可跨线程语义。
- Curve/PrimeCurve/RailView/RailLocation缺坐标范围、live/value和失败约束；CorePackages没有curve/render包单独导览。
- TickManager deferred注册、afterCommit历史命名不等于平台commit完成应写在API上；TrainController.commit不是renderer调用。
- 本轮生成HTML与Javadoc成功，但大量Undocumented及`WorldParentContext [declare]`链接warning仍在；failOnWarning=false，成功不是文档合规证据。

### 网页与.codeabs：职责规则已有，内容仍漂移

- architecture/modules、overview仍把运营/时刻表归Basic，未完整列SimpleATO/SimpleATOBukkit/SimpleSignals等；api-status/reference只列Core/Basic并写bukkit.api尚未建立，与当前公开宿主服务/Dokka配置不符。
- rendering相关说明未清楚区分Core publication与Folia target owner操作；overview主线程概括过宽。
- performance_assessment仍称二分固定50次/未用tolerance，实际已按tolerance早退，应更新或明确历史。
- Core rail地图仍有已删RailMetadata，遗漏prepared/topology/crossing；train地图把prototype Factory当正式入口，多份API/test地图是TODO。
- docs-site已有脏修改，本次未覆盖/提交；地图只用于导航，不刷新成另一份行为契约。CR-002/033继续待修。

## 4. 错误处理与过度兜底

### 需要处理的真实错误

缺失rail/损坏current输入、重复身份、构造中途异常、文件保存失败、世界卸载和异步在途任务都可发生；当前主要缺口是**失败后仍有副作用、静默丢数据、诊断没上下文**，不是缺更多retry。

- CurveException接收message却调用Exception()；Bogie/InvalidActiveRail异常丢输入；CannotCreateTrainException/PhysicsException没有message/cause。move转derail时常只留下异常类名。
- 用值记录train/rail/arc/操作/cause即可，不长期持有live对象，不引入自动重试或更多fallback。
- TrainController.setCommand负值silent return/NaN不拒绝、non-Core reverse线程约束是已标技术债；当前生产控制器不调用setCommand，不能夸大成ATO每tick错误。
- WeakReferenceWrapper非nullable getter可能带出null，实际GC后访问序列未证实；仅记失败语义不足，不新增预测GC的兜底。

### 不应机械删除的保护

没有确认应批删epoch/root/occupancy重验、token精确身份、finite值、端部visited-loop、防御复制、frame revision等保护。它们分别保护真实并发/生命周期不变量。

车厢压缩摆位±1m向后修正有历史回归支持；构造初始未对齐frame空body有明确command-style用途。应说明何时不可准入，而不是以“罕见”删除。

`RailSpatialIndex`半cell采样可能漏短暂经过cell，直接小radius API存在限制；现有生产64m/至少一cell查询覆盖邻cell，未证明Wand或diamond路径因此漏轨。保留为查询合同/潜在调用限制，不包装成游戏前P1。

## 5. 残留代码与删除边界（CR-032）

明确内部机械清理候选：

- 三个rail/persistence空文件/空类；无调用BinarySearch；未用Quaternion池；forwardPositive只写不读list；CoreWorld.signals未用字段；DummyAsset空helper；废SavedCar注释。
- RailView.save/SavedRailViewV1没有caller/load；peekRailObjects无caller。保留有consumer的peekSignals/peekTrains。

废实现与测试成组清理：

- RailTopologyEditor旧splitWithPreparedRails/mergeWithPreparedRail及专用测试。保留mergeEligibilityError/SplitResult与正式prepared pipeline回归。
- TrainFactory、TrainBuildDependency/CarBuildDependency、TrainConfigV1/BogieConfigV1的prototype链及反射填map测试。**保留CarConfigV1**，它仍是SavedCarV1 current shape且buildCar用于真实恢复。
- PrimeCurveGenerator全是测试consumer，应迁testFixtures，不删下游物理回归。

PreTick/PostTick/RenderableEvent已确认保留并考虑接入，从删除候选中排除；暂无publisher不等于没有扩展价值，分组接口也不必独立发布。接入前明确真实阶段/线程，不承诺平台呈现已同步完成。其余公开面仍单独评估：无调用PersistenceException/LoadPersistentDataException、CannotSplitException/Collision、canLink/COLLISION_SPEED及未接线Seat。联结/解编已经确认保留，不连同未用helper删除能力。

明确不删：Core Signal由Basic限速和Core扫描使用，DynamicRenderable.lastState由Bukkit初始render读取，CorruptedDataException有真实reader使用。

## 6. 跨模块规范一致性

- 保持唯一TopologyState/epoch占用事实：没有发现正常候选最终发布跳过拓扑root、occupancy实例、全体frame、active continuation身份复核。epoch与schema version未混用，不建议另造snapshot/MA/index。
- ThreadGuardHost精确token、marker lexical finally恢复和plugin slot启动固定约束合理；ExtensionDataContainer attach与initialize是不同阶段，split不能把attach当初始化完成。
- load与prepared restore的错误规则不同、Core/Bukkit分别补算velocity、每train revision被当car全局revision、四类文档不同步，是具体需要统一的机制，不是只统一命名/缩进。
- 旧world scope无consumer却持续保留world，优先考虑删除无效绑定，不默认新建attach框架。
- 本轮没有格式/持久化修改，也没有根据开发提交升级版本；后续移除反射先确认真实发布/冻结基线与数据迁移需求。

## 7. 测试套件合理性

处置逐文件见清单；以下是需要优先治理的实际断言，而非按数量删测试。

### 应改写，不应删除

- TrainUniformMotionTest enabled项expected/actual都本地累加，未publish实际位置，isDerailed也可能是旧状态；改真实simulation ticket后的位移/速度。6项disabled环线不计有效覆盖，参数化恢复而非为了减数直接删。
- CoreTrainPersistenceStateTest“zero distance controller tick”驱动generic TickManager，控制器实际只注册PhysicsCoordinator，因此空跑；改真实physics epoch。
- CoreTrainDerailEventTest自己复制生产条件并queue通知，无法发现coordinator漏排；改真实stepSimulation和派发前后一次性断言。
- CoreTrainAsymmetricTurnoutTest多个move后在commit前检查published旧值，最后一次commit后无断言；改publish后实际推进/未脱轨。
- PrimeCurveTest五项主要检查arcLength回显，即使position错也能过；合并成少量独立position/tangent/normal断言，保留LINE/DISCRETE差异。
- CoreTrainMultiCarDiagnosticTest反射读取private buffer、offset expected复制公式；改真实发布前后和固定数值预期。
- CoreWorldRailTopologyTransactionTest两个reader用sleep(50)而无已进入读取证据；改确定性barrier/finally，保留原子性覆盖。PhysicsCoordinatorTest无期限await要加有界断言。
- CoreTrainReversedPersistenceTest“没有部分注册”只看异常文本；需检查实际world/rail索引。load round-trip补独立位置/方向/extension预期与finally清理；future/bad-current用固定fixture。
- ImmutableSnapshotTest capture后没有变更live train；需验证旧snapshot不变。单位验证不必创建world。

### 可合并/删除的明确候选

- RailIntervalReleaseDetectorTest所谓reverse只是相同区间更新两次；有效持续占用断言可并入生命周期，真正reverse在train integration验证。保留BEFORE→OCCUPIED→RELEASED安全语义。
- RailDirectionRestrictionTest重复一行active断言可删，不删整个测试。
- 旧RailTopologyEditor专用测试、TrainConfigurationLoadTest随废实现删除；DummyAsset直接删。不能删除这些测试却保留无验证的旧实现。

### 应保留/加强的覆盖

normalized topology/纯环/菱形交叉、prepared stale/occupancy/identity、active-only与structural undo、API constructor能力约束；方向无关/oval/坡道/短长编组物理回归；ThreadGuard旧token、事件默认DERAIL、required/nullable extension；mailbox coalescing/stale/capacity、Core render边界均有独立意义。

缺口：新增六条诊断对应回归、完整split/link事务、坏存档及constructor清理；base command finished/invalid/reverse/registration；LINE projection；rootsearch边界与多段首root；extension高slot扩容/host隔离；mailbox tail/swept/bogies隔离。优先增强已有用例，不为每个字段或pool操作再复制一组测试。

多处fixture没有finally关闭coordinator/TickManager，部分Koin scope未清理；多数fixture并未start，不直接断言已泄漏大量活线程。stress suite是有意义的opt-in性能工具，不把其一项测试当容量结论，本轮未运行完整matrix。

## 验证记录

实际执行：带`--rerun-tasks`运行`:Core:test :Basic:test :Core:dokkaGenerate`及临时`:Core:readOnlyAuditProbe`。同一最终成功调用18个task全部执行，43s；Core 46 suites/212 tests（206执行、6跳过），Basic 59 suites/382 tests（全执行），零失败/错误。HTML和Javadoc生成成功但warning仍在。之后追加load/motion诊断成功，2s，未改源码。

临时入口：`/tmp/javarailways-core-audit-QBIbYm/CoreAuditProbe.java`与`probe.init.gradle`。使用Core test runtime classpath、JDK17目标；不安装进runtime JAR或正式测试套件，也不是临时玩法实现。初次诊断遇JDK21/17 class版本不匹配、Java catch Kotlin未声明checked exception编译问题，修正的是`/tmp` harness，不是项目缺陷；最终成功结果如下。

```text
collision.exactOverlap=true
collision.broadPhase=(collidingTrainId=null, checkedCandidates=0)
collision.blockers=[B]
curve.nearestArc=4.0              # 查询4.4m
curve.failure=CurveException: null
motion.trainSpeed=10.0
motion.carVelocity=0; motion.bogieVelocity=0
split.beforeCars=4
split.failure=ClassCastException: EmptyList cannot be cast to MutableList
split.afterCars=2; split.worldTrains=1
load.bodyTraversal=[140.0,180.0]; load.occupiedIntervals=[140.0,180.0]
load.actualBogieArc=50.5
duplicate.failure=train name and technical ID namespaces must not overlap
duplicate.endpointsBefore=4; duplicate.endpointsAfter=6
```

复现构造：32m bucket的两span；0→10m两点LINE；500m直轨上4×10m真实CoreTrain，用10m/s controller执行50ms simulation；保存有效cars后做split；以相同cars、不同ID和head/tail=180/140恢复；再尝试重复身份恢复并读rail端点索引。所有coordinator/TickManager在finally关闭。已有测试全绿与这些错误可并存，不能以绿色测试否定诊断。

缓存使用`/workspace/.gradle`；没有服务启动、Folia实体/客户端或opt-in容量验证。源码没有变化，无需把编译成功包装为已修复。

## 下一步与待用户检查

用户已要求下一步先改进Core；本批仅记录，不实施运行时代码。按[执行计划](../implement.md#下一步core-改进批次)逐批说明、验证、提交并等用户检查，优先顺序为：

1. 首批CR-022列车构造失败清理，再处理加载/恢复/卸载一致性（CR-023/027/028及C1生命周期）。
2. 正式算法事实：LINE投影、碰撞容差、motion velocity。
3. split/link已确认保留，先明确端部/方向与事务验收方案，再修事务；三个事件保留，先明确接入契约再接线。Seat去留仍待确认；废路径/测试、文档和诊断另成小批。

Core改进后恢复整模块审查时，下一模块为Basic；只在其源码、测试、文档范围闭合后报告。全项目审查未结束，也尚不具备仅据Core报告宣布Stage 2正式游戏验收就绪的依据。
