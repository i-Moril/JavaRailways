# SimpleATO controller runtime 契约

## 状态与范围

`work.art1st.javarailways.simpleato.controller.SimpleATO`是当前唯一具体自动驾驶controller owner。
它连同`SimpleAtoPublishedState`、mission queue、station-call状态、
movement authority和owner-scoped constraint manager位于SimpleATO模块。Basic不保留同名controller、typealias、
factory facade或转发访问器。

SimpleATO同时拥有`AbstractATO`、mission、constraint与world Stall coordinator；详细机制见
[ATO policy runtime](./ato-policy-runtime.md)。SimpleATO拥有`AtoSafetyProfile`；Basic只提供actuator、坡度制动包络、
不可变拓扑、物理前视查询、route/perception值对象与world safety interlock，不拥有或转发ATO可写状态。

## 正常流程与内部机制

一次控制周期按以下顺序运行：

1. Bukkit或隔离仿真的`TimetableAtoFactory`解析/捕获同一列车profile、逐车profile和重力，创建Basic
   `HandleTrainActuator`后再把controller安装到目标Core train；生产与隔离composition不得使用运动学actuator；
2. Dispatcher在serialized owner上提交带revision的mission、station call、reverse或authority命令；
3. physics tick开始时controller只在边界drain已接受命令，形成一份active mission/route状态；
4. controller先从本次Core帧在已绑定冻结物理路线上定位车头，用同一进度计算前视入口净空、更新inspection并消费上一成功signal epoch的失效标记；前视净空目标并入horizon后只构造一次perception，
   然后读取最新immutable restriction snapshots，按owner原子替换constraints并取所有候选中最严格速度；
   入口净空门控在基础线路、mission、station及信号约束合成以后、最终planner以前检查同一份约束；
   未就绪时安装该入口停车约束，同时交接不可变结果，不阻止Dispatcher继续准备资源；
5. `AbstractATO`先验证不变量，将实际设备状态和 `TrainControlFrame` 交给 world-local `RailwayControlRuntime`。
   `VehicleControlExecutor`每物理tick检查ATP，按行车/停车参考策略选择数字牵引/制动指令；Basic actuator只执行该指令，
   由现有逐车 `TrainDynamicsExecutor`积分一次，再经protected seam移动Core train；
6. Core先发布本轮完整train frame，然后`AbstractATO`原子发布该frame引用、累计距离和controller value state。Dispatcher读取这份控制状态，并在owner阶段一次捕获当前Core帧用于物理判断，不从旧ATO副本补物理字段。

Mission、constraint和station-call的mutable集合都只存在于该controller实例，不在
`SimpleATOWorldData`或Basic创建第二份镜像。每列车每tick的route/perception扫描仍为线性；controller只从world-local
inspection runtime读取编译好的声明及最大提前时间；controller按最高速度转换为保守距离并并入原有horizon。

物理前视经Basic `SelectedTrackAhead`两阶段入口取得：先沿当前选中续接追踪一次路径，
SimpleATO再按有序rail ID/facing计算原route key并更新revision、构造`RouteSnapshot`，然后观察该路径上的对象。
路线端点弧长变化本身不改变route revision；对象getter和异常仍在路线revision/快照构造之后发生。
SimpleATO继续拥有previous/current observation身份比较、ENTER/UPDATE/PASS/EXIT差分、稳定距离排序、
独立perception epoch和最终`PerceptionSnapshot`，不把这些可写历史转交给Basic。

扫描返回固定区间及位置值，但保留Core API live `RailView`与原`RailObject`实例，
以便在原阶段读取signal限速、REMOVED状态和`TrainEndRailObject.trainOrNull/isHead`。
联结目标仍由SimpleATO按现有proof/reversed映射识别；反向前车的相对速度仍采用原零速假设。
对象回调/inspection之后，路线物理末端仍查询最后view的当前续接；移动前仍逐对检查
`current.railView.nextActiveRail === next.railView`，选择变化按原消息失败，不能用扫描时捕获的布尔值代替。

线路速度通过Basic公开`nativeRailSpeedLimit`和`OperationalSpeedLimitTransition`消费，不直接识别具体signal或metadata存储。
当前限速仍在scan及restriction更新后调用`nativeHeadSpeedLimitContext`读取一次live车头rail/facing，
只在首次或rail/facing变化时同步metadata；不改成先前的perception frame。PASS按既有稳定距离顺序读取原对象的
方向与nullable转换值，null解除当前转换上限；可见前方限制也按该观察位置的facing执行同一纯方向判断，
以覆盖generic RailObjectStore中的转换能力对象，之后才读取限速值并使用同一ref及既有约束key。不影响其感知事件。
人工保护每段继续先取metadata、再处理方向适用的ACTIVE静态转换、最后处理动态车端；第一段当前转换的
null release不得回退到更早限制。接口仅收口读取，不新增查询阶段或另建感知/控制状态。
Basic查询的源顺序、容差和重复实例规则归[物理前视契约](../basic/ato-missions-and-perception.md)。
人工未绑定路线通过Basic `traceBoundedSelectedTrackAhead`取得物理片段，调用方仍选择256段及原horizon，
保留原手动端点算术；逐段用`staticTrackObjects`读取静态信号并完成限速处理后，才迭代`trackTrainEnds`。
前者是方向过滤的只读列表，后者是原动态车端集合的只读live视图；不提前观察后续片段，不加入Basic registry对象。
自身端点按精确Train实例排除、区间双侧包含且无stop tolerance、其余端点按静止零速障碍处理；
目标key/顺序、当前signal转换、最末已观察零速边界与reference/ATP双目标仍由SimpleATO按原策略构造。
现有OperationalSpeedLimitSignal和限速metadata作为稳定领域类型继续复用，不为移除import增加包装接口。
自动与人工的对象来源、去重、自身判断和相对速度语义保持各自既有规则。

结构输出仍由formation hold停车时，Core只调用`AbstractATO.prepareWhileFormationHeld`，不调用正常tick。
该边界先清零controller speed和上一编组的lastTravelDistance，再消费命令并执行同一个planner/perception/inspection流程，
通过正常epoch publication发布mission就绪状态。允许读取制动包络等只读规划查询，但不调用actuator.step/freeze或移动seam，
不累计行驶距离。异常保留同一ATO stall诊断并保持停车；配置隔离、脱轨或待处理碰撞均不进入该准备流程。
`FormationHeldAtoPreparationTest`使用真实SimpleATO mission验证合法激活、过期物理origin拒绝及全程不改变actuator历史。
具有中立净空要求的列车才沿本次已捕获Core帧求和有序车身跨度；冻结授权路线定位由外部限制共用，不以是否存在净空要求决定。
没有要求的普通列车不承担净空求和工作。净空跨度进入下一个入口时按预编译有序入口继续扩长一次，
仍只执行原有的一次前方扫描，不能先扫描再补扫。具体算法、坐标和回归归[入口净空合同](entry-clearance.md)。
每列车的`InspectionRuntime.TrainInspection`独占自己的scheduler和baseline状态，低频lifecycle只发布编译结果，
不得从Dispatcher或signal epoch修改物理worker正在使用的scheduler。不同列车的`InspectionUpdateHandler`可以并发执行，
只投影已发布的不可变策略/信号状态；共享信号规则仍在串行signal epoch运行。
成功publication把本次`PerceptionSnapshot`和实际horizon与train/controller state一起原子发布。production Dispatcher
复用该perception规划滚动授权；inspection update在controller本次planner前直接复用同一perception，不增加第二次路线扫描。Dispatcher不维护第二套route allocator；生产环境与
隔离仿真都通过Basic的同一个route-admission runtime申请、准备、授权并按车尾释放资源。

每次既有route scan还构造最多256个保守坡度切片，并从actuator取得共享`GradeAwareBrakingEnvelope`。前方限速、
停车、跟车、移动授权和路线终点都查询这一包络；无真实包络的纯单元测试才回退常减速度。扫描不是物理终点时，
其远端在真实包络下作为可制停边界，避免下坡制动距离超过平坡感知horizon。算法owner与复杂度见
[Basic列车纵向动力学](../basic/train-longitudinal-dynamics.md)。

Stop、MA、跟车和前方限速约束把各自扣除显式空间余量后的目标距离、当前速度及反应时间一次交给同一包络；
反应距离由包络统一扣除，不能调用方先扣一次再让包络重复扣除。无包络的运动学单元测试仍使用原常减速度公式。
真实设备响应、正加速度消退与坡段门控归上述Basic契约，不在SimpleATO复制动力学。
既有标量planner与解析work envelope仍用于perception/入口净空及兼容现有诊断，不能把其动态permitted speed
当作新策略的恒定速度上限。`TrainControlFrame`直接从同一已捕获约束集合构造原始空间目标：运营停车mark与
物理MA分开，Stop使用显式`operatingTarget`语义而不是猜key namespace；MA传入ATP时保留未扣margin的真实
signed边界，越界不得clamp为0。普通策略使用扣除明确运营margin的目标。`StopConstraint`默认瞄准mark，不扣到站容差。
巡航策略默认0.2s重算，停车末段/目标变化/保持指令不安全时提前；ATP与实际积分每tick执行。不得在在线
ATO/ATP路径调用未来多tick的真实动力学预演。参考策略见[参考控制](reference-control.md)。

### 调度器运营停车保持

`SimpleATO.setOperatingHold(key: String): Long`用于到达任务停车点、目的地停站、站点程序、独立门操作、
停站等待和普通首次发车时间等待。调用沿既有revision命令队列提交，在physics边界生效；key要求非空白。
同一external key的运营保持与`setSpeedLimit`相互替换，后接受的命令生效；既有`removeSpeedLimit(key)`
清除该key的external约束和`stopAt`目标，也清除运营保持。移交、discard与下一mission沿既有key清理路径执行，
不能另存一份未随原key清理的保持状态。

运营保持复用`StopConstraint(remainingDistanceMeters=0.0, stoppingMarginMeters=0.0, operatingTarget=true)`。
它只进入普通控制参考目标；既有停车策略排除牵引，选择通过真实ATP候选检查的最强常用制动并维持压力。
保持指令不为ATP新增车头当前位置的物理停车边界。真实MA、线路/信号限速和信号限制失效后的保护继续参与ATP，
保留原始距离与符号；例如车站运营点之后0.3m的实际授权不因停车保持缩为0m。

主动停止服务、任意扩展动作、候选资源等待以及编组/联结等待仍使用原有限速保持路径。
`CurrentSpeedLimitConstraint(0.0)`和`DepartureHoldConstraint`仍是硬保护，不能按数值为零或owner类型统一排除。
本修复不修改ATP比较容差、坡度计算、停车曲线或到站/开门确认时序。

外部约束仍由controller唯一拥有；每轮合成成本为O(E)，E为当前external约束数，不增加路线扫描或未来物理预演。
保持状态沿现有runtime命令生命周期存在，不新增持久化字段或恢复兼容路径。
`SimpleATOTest`和`TimetableDispatcherTest`验证指令转换、同key替换/删除及停站到下一mission的清理。
`OperatingHoldProtectionTest`组合真实逐车动力学，验证零气压静止列车在近零负坡度上建立常用制动、
真实下坡已有制动的持续保持、解除后发车，以及运营保持期间真实授权越界和显式零速/失效保持仍触发ATP。

普通mission和车站终点仍是零速目标；只有通过当前接触证明、mission/MA revision及精确边界校验的重联终点，
才以准入grant携带的接近速度为目标。接近速度与grant上限必须一致；Core仍夹紧实际接触位移。
独立停车、红灯、跟车和授权约束不因存在接触动作而删除或放宽。

`RailwayControlRuntime`的精确Train实例entry可由运营owner登记Basic已准入的
`CouplingContactAuthority`；`registerCouplingAuthority`不创造资源或放行信号，登记前仍要求当前Core接触几何有效。
`couplingAuthority`只返回同一Train实例的capability，切换手动/ATO不复制一份联挂几何状态。
人工无绑定路线时复用原有逐段扫描，只豁免proof指定的目标train与车端，其余端点、原生车身/前方限速、信号转换
及观察末端仍原样进入保护。该接近速度以距离零的上限同时进入reference和ATP，routed manual与automatic同样叠加。
已有ATO restriction adapter的接触授权与runtime登记授权复用同一mission revision、MA终点、冻结路线contact boundary
以及Core published geometry校验；没有bound restriction route、边界不符或目标失效时停止，不能只凭低速上限驶向普通任务。
人工积分后的距离通过Basic supervisor movement seam调用同一Core raw-bogie contact clamp；失效或离路证明拒绝运动，
不因ATP已发出EB而省略物理夹紧。运营owner在联挂完成/终止时显式detach该authority；普通protection source的转交不代替此生命周期。
`CouplingControlRuntimeTest`覆盖精确peer端豁免、保留更严格native/signal及第三列车限制、手动/自动同一速度cap和失效证明；
`SimpleATOTest`用真实epoch验证匹配的contact mission可驱动，以及无route、错误boundary、已移动target均保持停车。

`RailwayControlRuntime.acquireCouplingHold(train)`为一次联挂操作返回独立exact token；close幂等且只清自己的请求。
当前Train身份由owner入口验证，退役Train不能重新取得hold，也不替换controller或覆盖其他owner的token。
有请求且列车移动时，只在reference加入运营停车目标，手动也复用同一ordinary stopping策略并真实积分制动；
原signal/obstacle/native hard targets仍参与ATP。完全停稳后，manual监督继续构建观察但不触发等待造成的ATP ack，
AbstractATO继续处理commands、plan、perception和inspection再复用freeze保持0；两者不做动力学积分或伪造设备历史。
联挂操作在READY时只关闭approaching token，stationary token由同一操作保持到实际结构交接。

`SimpleATO.attachTransferredProtection()`显式安装既有refreshTransferredProtection callback，
使尚未执行过第一次ATO motion的控制器也能作为MANUAL_ROUTED保护来源，不复制第二套扫描。
同exact stopped Train的serialized owner handoff可调用`transferOperationalProtectionTo(replacement)`：
先消费source待处理命令，再把外部限速、运营保持及stopAt的stable ref/offset转给尚未运行的新保护ATO。
不复制旧mission、route mapping、following、perception或累计里程；dispatcher须先在结构前使原physical Mission命令就绪，
不能用此方法绕过过期origin检查。`SimpleATOTest`验证已应用/待应用命令保留及后续按原key清除。

普通非车站mission Stop显式使用零几何停车margin，曲线瞄准原运营目标；停车容差用于接受到达，不把普通目标退到容差边缘。
车站Stop同样使用零几何停车margin，瞄准既有停车点（含明确设置的停车偏移），不添加固定提前量，也不根据到站容差平移目标。由`AtoMissionPoint.Stop.stationStop`或同revision实际terminal StationCall明确标识的终点，mission授权余量为0.3米；普通mission和独立红灯/资源约束默认保留全局安全距离。`MovementAuthorityConstraint.stoppingMarginMeters`为空时使用profile全局值，否则使用明确值；公开`terminalMovementAuthorityLimitMeters(routeEndDistanceMeters, stationTerminal)`只使用显式参数，不读取physics私有active状态；资源准入如何证明车站终点及保留原始grant见[终点授权合同](terminal-route-completion.md)。外部Stop/物理边界的原默认margin和连挂contact规则不受影响。

controller同时拥有该列车唯一的`TrainRestrictionConstraintAdapter`。admission client直接向adapter发布immutable
restriction snapshot；Dispatcher仅在route建立/延长时绑定冻结route、Mission revision及二者的坐标映射。每个physics tick
由controller用本次实际物理路线进度和上一成功signal observation stamp调用apply，adapter把结果原子替换进controller原有
`ConstraintManager`。这不是第二份约束存储或第二个制动器：最终仍由同一个
`MinimumConstraintPlanner`与车辆、停车、跟车等constraint一起取最严格值。部分和完整Basic授权都保留真实外部MA；
完整frozen route授权不是撤销硬边界的理由。mission Stop constraint仍按原运营目标停车，真实余量与完成检查归[终点分离契约](terminal-route-completion.md)。

运营停车目标不一定是物理路线末端。controller已有冻结授权路线时，先沿该路线定位本次真实车头；匹配的Mission进度复用这次结果换算，不能再投影一遍。新Mission已生效而旧授权映射仍有效时，旧授权继续使用真实物理位置，新Mission自身按其layout定位，不用旧路线终点冒充车头，也不跳过旧grant。Dispatcher的运营进度与实际推进检查仍遵循各自既有调用时机。接续、重复经过、折返与成本归[停车目标与授权终点分离](terminal-route-completion.md#坐标映射与下一任务)。

signal epoch不直接持有controller。它先写world-local immutable snapshot store，并只在全部publication成功后推进completed
observation stamp。下一次physics tick中controller从route绑定携带的world-local snapshot source读取最新限制；同revision
重复读取是幂等的，wrong train、source unavailable或store/controller revision矛盾会在controller tick内使world fail closed。

### 车身覆盖的原生轨道限速

自动和手动驾驶的当前原生限速由同一次捕获的 `TrainFrameSnapshot.bodyTraversal` 决定，
不能用车头所在轨道、转向架落点或旧 ATO 状态代替整车覆盖。SimpleATO 负责把限制接入普通参考控制和 ATP；
Basic 仍负责 `CURVE/SUBGRADE` 元数据与独立的脱轨监测，见[速度与脱轨安全](../basic/speed-and-derail-safety.md)。

`basic.api.ato.nativeBodySpeedLimits(train: Train, frame: TrainFrameSnapshot): Map<UUID, Double>` 是 Basic 拥有的公开查询函数，
返回轨道 ID 到 m/s 上限的本次计算结果，不保留状态。控制周期从已捕获帧枚举车身轨道并按 ID 去重，读取每轨已有
`speedLimits.effectiveLimitMetersPerSecond()`，将有限限制加入距离零的当前限速目标。
自动路径与手动路径共用这一小段读取逻辑，各自接入原有控制帧合成，普通参考与硬保护同时收到限制。
例如车头已进入无限速轨道、两转向架之间仍覆盖一米长的低速轨道，当前目标仍含该低速限制；
只有后续物理帧显示车身完全离开它，下一周期才能移除。反向行驶遵守相同规则。

前方原生入口信号仍提供有距离的提前制动目标，当前车身限速不替代前方扫描；
也不改变下文按车头通过终点释放的信号区间限速。无原生限制的轨道不增加目标；缺失物理帧、
空车体或已不存在的占用轨道是控制输入错误，沿既有异常路径停止，不能从 live bogie 拼回另一份位置。

查询返回本次新建的脱离元数据存储的 map；SimpleATO 不保留第二份查询实现或兼容转发。
该计算在原有 worker 决策内执行，时间 O(B)、临时空间 O(B+U)，B 为车身遍历片段数，U 为不同轨道数。
元数据仍是既有静态值；不新建缓存、信号来源、版本或生命周期，物理帧只读且不逐字段重采样。
无需持久化迁移。只读车头会过早解除限制，依赖独立脱轨监测事后处理也不能补足普通驾驶/ATP的输入。

回归应覆盖自动与手动的 reference/protection 双目标、无转向架落入的短轨、AB/BA、
车尾清除前后，以及前方入口提前响应；至少一组真实 Basic 设备行驶回归证明覆盖期间受限、清除后可加速，
不能仅靠静态目标列表或替代 actuator 宣称动态控制已验证。

### 缓存的区间速度限制

`RouteSpeedRestriction` 的 `routeDistanceMeters` 表示开始位置，新增可选
`endRouteDistanceMeters: Double? = null` 表示结束位置，单位都是冻结路线上的米。
终点为 null 时仍是原来的目标速度限制；有终点时，适配器在车头越过终点后才忽略这一项。
终点必须有限、不小于起点且不超过路线长度。入口前使用同一制动曲线，入口后起点已经经过，
现有映射把它转换为当前位置的速度上限，而不是立即截断车辆速度。

`InspectionDeclarationBinding.speedRanges` 保存不可变的 `InspectionSpeedRange(declarationId, restriction)` 列表，
并经 `InspectionUpdateInput.speedRanges` 交给来源的完整限制投影。缓存范围要求正的有限速度和明确终点；
`declarationId` 只用来接收既有失效通知，不要求入口仍在激活集合内。每次经过有独立的 restriction ID，
同一物理声明 ID 可以唤醒这些范围。没有入口声明的区间内部起步也能在首次检查时发布限制。
来源仍负责把未知信号状态投影为 0，SimpleATO 不解释闭塞内部规则或灯色；公共轨道方向由自身[方向限制](operating-direction-enforcement.md)校验，不再依赖整个闭塞方向失配。

例如范围为 `[40m, 100m]`：车头在 20m 时按既有曲线为入口减速，在 60m 时保持上限，
越过 100m 后这项限制结束。资源是否仍被车身占用由 Basic 的车尾释放流程决定，不由速度范围决定。
范围随现有路线绑定替换、折返或移除，不创建另一套列车生命周期。稳定范围声明 ID 的查询集合在来源绑定时缓存，
失效交接只查询实际收到的 ID；没有失效 ID 时不额外扫描范围。每 tick 仅在原有限制适配中检查缓存限制的终点，
增加的工作为 O(C)，C 为该列车缓存的限制数；不重新扫描路线或调用信号规则。手动控制器不消费这一接口。

## Publication边界

### 编组变化时的制动参数

`AbstractATO.prepareConsistChange`在结构修改前要求Basic actuator准备新实例参数表，并用同一结果
派生常用制动减速度和反应时间。通过`AtoSafetyProfile.copy`仅替换这两项，保留安全距离、停车容差和加速度设置；
新参数表与安全profile均通过Core prepared state update安装。派生失败不改变当前profile、actuator历史或手柄，
不在成功事件中事后补算。无真实动力学的测试actuator无需实现该capability。

对方参数来源能力由具体`SimpleATO`通过`basic.api.ato.DynamicsProfileSource`代理给actuator；
来源返回Basic API参数契约与设备值，公开签名不再依赖Basic内部参数类型。
解编保留侧保持原controller；分离侧不会自动复制mission或controller。联结安装仍须完整碰撞事务验收，
不以这里的参数准备测试代替接触位置及资源/运营元数据的验证。

`AbstractATO`把一次publication表示为protected immutable `PublishedAtoState`，并通过一次volatile reference
读取返回完整快照。SimpleATO必须只调用一次reader，再从该对象读取trainFrame、累计距离和controller state；
不得把字段拆成多次volatile读取，否则并发publication可能产生跨epoch混合。Dispatcher不能读取worker正在修改的
active字段，也不能改写controller内部publication reference。

`SimpleAtoPublishedState`把这三个值和immutable controller state组合成Dispatcher可读取的快照。tick完成但
Core尚未调用commit/publication时，旧快照必须保持不变；publication成功后所有字段一起前进。
`TrainSnapshot`摘取类型已删除；`trainFrame`引用Core原始不可变值，不覆盖其speed，也不再保存第二份head/tail。
感知使用本轮开始时捕获的已提交帧，发布状态使用本轮结束的新帧，二者采样阶段不同是正常情况；
不能拿感知/ATO旧车头和当前车头相等当作同一epoch的证明。人工接管后旧ATO不再发布，
Dispatcher的物理状态和状态展示必须直接读取当前Core帧，不依赖旧控制器仍在更新。
成功publication还记录本次已完成inspection的冻结route。新binding的Basic申请/推进/扩权必须等controller已检查该route，
且完整publication包含所需horizon；不得用旧路线结果或上一个较短perception放宽准入。

## Failure与禁止实现

- mission resolve失败或stale revision不改变active mission，也不误触发world Stall；
- physics invariant或controller异常由同一SimpleATO world coordinator fail-close并freeze，同时锁住Basic物理interlock；
- 已Stall、validation异常或actuation异常的freeze epoch必须调用同一controller seam发布0 m/s和0 m位移，清除上一成功epoch的car/bogie motion；只改train标量speed不算完成freeze；
- 不得在Basic恢复具体SimpleATO subclass、兼容alias或callback bridge；
- 不得让Bukkit直接更新active mission/constraint集合；
- 不得为本次inspection线程ownership迁移增加第二套冻结路线生命周期、资源registry或控制器。

## 必需测试

- `SimpleATOTest`覆盖mission boundary、constraint归约、publication原子性、Stall与freeze；
- `AbstractATOMotionPublicationTest`用真实PhysicsCoordinator证明actuator distance/speed进入同一frame，并证明下一异常freeze epoch把train/car/bogie frame速度全部清零；
- `InspectionRuntimeTest`覆盖两列车并发投影/独立dirty消费、旧route view完成后新route baseline、折返/移除/reset；
- 区间限制覆盖只有范围而没有入口声明的首次投影、稳定声明失效、范围越界/重复限制 ID 拒绝、
  入口前制动目标、入口后持续上限、车头在终点及越过终点；折返使用新绑定范围，不提前释放已有资源；
- `ProtectionSignalControllerHandoffTest`在停止Dispatcher后，验证允许→0→允许均到达下一次控制计算，
  未变化epoch不产生新投影revision，越过入口撤销对应限制，0限速不阻止首次资源申请；
- 入口净空回归必须组合真实控制器、Basic授权及串行信号交接：完整grant但出口后红灯/站台/前车不足仍等待，
  本入口红灯只在局部证明中精确排除；延长前视仍一次扫描，相邻入口不形成互相准备循环；
- `StationCallRuntimeTest`覆盖停站状态与controller publication；
- Dispatcher、manual navigation和隔离仿真测试证明所有composition caller使用新owner；
- Basic测试独立编译，证明Basic main/test source均不依赖SimpleATO。

```bash
./gradlew :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
git diff --check
```

## 配置重载的准备与安装

`AbstractATO.prepareConfigurationChange(trainProfile, carProfiles)`只依赖可选的
`basic.api.ato.ConfigurationDynamicsActuator`，当前真实实现为`HandleTrainActuator`；无此能力仍按原错误拒绝。
Basic先准备新profile、实例绑定和手柄表，再按新profile重算service deceleration与reaction time，
通过`PreparedConfigurationDynamicsChange`返回待安装更新与这两个值。结果不新增数值校验，
ATO仍在原`AtoSafetyProfile.copy`位置验证，仅替换两项并保留其他safety覆盖值。
编组路径的`PreparedDynamicsChange`保留原Basic结果校验，两者不为统一形状而改变失败位置。
全部成功才返回Core prepared tokens，依次为原执行器更新和ATO安全profile更新；
失败保持旧引用，不清mission、route、命令队列、距离历史、手柄或actuator积分状态。
平台按车型完成所有实例准备后，在排除worker的边界通过Core安装整批tokens。

Formation output installation retires the consumed source mission with `SimpleATO.retireMissionForFormation(): Long`, not reverse preparation. Enqueue requires the current controller under an actual Core structural hold, a stopped published frame and no active/queued StationCall. The serialized command removes only consumed mission/layout/progress/authority constraints, preserving controller identity and external restrictions. A later accepted mission revision survives retirement even when submitted before consumption. See the fixed-formation handoff contract in `../basic/route-resource-admission.md`.

When an active StationCall belongs to the active Mission revision, its position error must reuse that Mission's single signed projected progress for the current frame. It must not project the train head independently onto the StationCall route. In particular, a frozen physical authority can validly report a small negative Mission progress when a stopped train rebounds behind an observed origin; Mission and StationCall constraints must retain the same value. After departure grants allow the StationCall to outlive that Mission, its retained route projection continues to track departure independently.


## 新版逐车控制接线与异常

`OperationalWorldRuntime`安装Basic `VehicleCommandSupervisor`，使人工/自动共用同一物理Train的ATP latch。
替换controller不能清除人工触发的手动确认要求；新物理Train实例不能通过同字符串ID继承旧latch。
自动模式异常记录实际位置/速度/加速度/设备、路径目标、拓扑/mission版本及安全witness，并在停稳和新鲜安全判断后自动解除。
人工模式还需玩家 `/jr train atp release` 明确请求；请求在worker下一次新鲜检查消费。模拟模式在异常发生时中断，
不能继续产出成功运行时间。`IsolatedTimetableSimulationWorld`默认设置此模式，真实物理线预览也调用同一执行器。
名义mark的精确预测无解是 `OperatingPrecisionStatus`，不能仅凭这一条件制造ATP异常；所有执行候选仍须通过硬安全检查。

到站、起点匹配与插件解析结果的位置容差由Basic `AtoSpatialPolicy`唯一给出0.3m，不是参考曲线的提前停车量。
去除destination持久化的独立容差字段使用 `ai_tasks/migrations/remove_authored_stop_tolerance.py`，只做离线单向转换，
不增加旧格式runtime reader或schema版本。运行数据写入前必须dry-run、停止owner并保留独立恢复点。

待授权的 mission 使用显式速度保持（`holdForMovementAuthority`），不能把路线原点当作物理授权终点。真实 `setMovementAuthority(0)` 仍是物理边界，并保留已越过边界的有符号距离。实际 grant 同步至 AUTOMATIC 与 MANUAL_ROUTED 的同一 mission；FULL_MANUAL 已解除该路线保护源。
