# Core / Basic 工程审查与验收

> 2026-10-23新增[历史修复真实性与契约成本复核](review-fix-contract-audit-2026-10-23.md)：收窄部分触发范围，列出S01–S04简化候选。以下为历史发现/验收依据，不将API边界、工程建议或设计调整一概视为正常行车故障。

更新日期：2026-10-20。原始审查基线：`467bb698`；本次验收针对当前未提交工作区。

本文是当前问题清单与验收合同。下文每项的“当前实现”“位置”等描述保留**发现时的证据**，不代表缺陷仍存在；是否完成统一见本节。此前51项及BC-09/BC-10拆分背景见[上一轮审查](basic-core-engineering-review-2026-10-19.md)。未创建任务、未提交、未部署。

## 当前状态

- **实现及静态复核完成：** R01–R09、E01–E03、D01–D05、BC-23，以及上一轮约定的BC-09/BC-10职责拆分。
- **生命周期收尾完成：** BC-51补查Core、Basic、SimpleATO测试世界所有权；注销数据登记不等于关闭世界，复用`TestWorldFixture`关闭physics和tick，多个清理步骤尽力执行并保留失败。检查覆盖Core中44个直接构造world的测试文件及相关helper、Basic/SimpleATO中39个消费者；发现的确定遗漏已补齐。没有将registry注销当作资源释放。未穷尽其他插件资源及所有close自身失败路径，未做长期堆/线程泄漏测量。
- **故障门禁收尾完成：** F01补齐全局halt后的资源准入、旧许可进入及信号定义提交检查；允许诊断、释放、回滚和卸载清理。使用独立进程验证全局halt，避免污染后续测试。
- **待验证风险，未升级为确定缺陷：** C02 mailbox清理并发、C03曲线热路径输出别名、C05第三方扩展/平台异常安全。保留后文限定，不为假设场景添加兜底。
- **本轮约定范围的审查与本地验收已完成，没有新增产品决策点或已确认的未修复阻塞项。** 这不是整个项目无缺陷的保证。

## 本轮补充合同

1. Core世界持有唯一资源保护注册表，Basic、ATO和宿主共享；结构修改及选岔不能绕过保护。公开许可通过同步执行作用域认证，作用域退出前不能提前释放保护。
2. 编辑与撤销应用局部差异到当前状态，不用旧快照整图替换。无关修改保留，实际相关轨道或连接变化才使操作失效。
3. 拓扑revision保留快照和局部ID来源含义，不以数值不一致直接拒绝。轨道实例身份可识别同UUID替换；观察、路径和选区验证实际依赖。
4. 本帧碰撞冻结后在紧接的帧间owner阶段处理，不额外等待下一物理帧。
5. Basic仍保留Core公开ABI所需的ConfigLib编译依赖；删除的是无用依赖及过期Dokka职责，不能将E02描述为“全部移除ConfigLib”。
6. 本次不修改持久化schema版本，不新增旧开发数据的运行时兼容路径。

## 验证结果

2026-10-20最终同一批源码使用`--rerun-tasks --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx3g`强制执行，56个构建任务全部执行，整体成功；以下不是拼接不同版本的测试结果。

- Core：505项通过。
- Basic：390项通过。
- SimpleATO：375项通过。
- BukkitPlugin：510项通过。
- SimpleSignals：631项通过。
- TunnelBridgeBuilder：31项通过。

合计2442项，0失败、0错误、0跳过（Core常规任务按既有设置排除`physics-stress`）。Java信号API消费者与BukkitPluginDev编译通过，`git diff --check`通过。构建仍有弃用、无用断言/转换等warning，不将其表述为零警告。

执行任务：` :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :SimpleSignals:test :TunnelBridgeBuilder:test :BukkitPlugin:compileSignalApiConsumerJava :BukkitPluginDev:compileKotlin`。本地完整日志：`/tmp/jr-closeout-final-validation.log`；各模块JUnit XML位于`build/test-results/test/`。

独立内存回归：`TimetableDispatcherSharedAdmissionTest`在256 MB堆下24项通过，未降低采样精度。未执行压力测试、开发服部署、Minecraft/Folia客户端操作验收或长期泄漏测量。

## 确认的行为与接口缺陷

### R01 — 销毁接口泄漏内部完成权

- 位置：`Core/.../api/util/Destruction.kt:8–22`；`Destroyable.kt`；`CoreTrain.destroy`。
- 当前实现缓存并返回同一个可写 `CompletableFuture`。调用者可通过 `toCompletableFuture().complete/cancel/orTimeout` 提前改变所有后续观察者看到的共同终态，而真实资源清理可能未结束。
- 这是公开API所有权漏洞；尚未发现生产调用实际进行这些操作。对照 `TrainController.reverse` 已使用独立副本隔离取消。
- 修复要求：内部独占完成源，对外提供不能改写内部结果的观察视图，保留重复销毁观察同一真实结果的合同。
- 边界：允许取消排队动作的Future具有不同合同，不应机械套用。

### R02 — removeRail清理参数实例，却按UUID删除当前实例

- 位置：`Core/.../physics/CoreWorld.kt:1076–1115`。
- 删除前未核对 `rail.world` 和 `rails[id] === rail`。旧对象被同UUID的新对象替换后，传旧引用可能清理旧连接、删除新对象；非Core实现还会跳过连接清理直接删除索引。
- 修复要求：任何修改前校验世界、支持类型、准确注册实例身份，之后统一操作确认过的对象。
- 实施时明确重复删除的幂等行为；已经不存在与同UUID但实例错误必须区分。

### R03 — ManualController构造迁移遗漏开发插件

- 位置：`Basic/.../controller/ManualController.kt:16–20`；`BukkitPluginDev/src/main/kotlin/work/art1st/javarailways/bukkit/tests/PhysTestCommand.kt:231`。
- 当前第二参数为 `Double acceleration`，开发插件仍调用 `ManualController(t, world)`。
- 静态可确定签名不匹配；未执行编译。
- 修复要求：移除失效world实参，检查相关构造消费者。

### R04 — 非法roll字段被静默当成零

- 位置：`Basic/.../curve/AnchorGeometry.kt:16–20`。
- `toDoubleOrNull() ?: 0.0` 合并了“未提供字段”和“提供了abc等非法字段”；NaN又走另一条下游失败路径。
- 修复要求：缺省允许零，存在的字段必须可解析且有限；写入入口同样拒绝非有限值。

### R05 — 非有限移动距离可以进入不终止的闭环遍历

- 位置：`Core/.../physics/train/CoreTrain.kt:272–284`；`physics/rail/RailLocationWithFacing.kt:59–114`。
- `deltaS >= 0`允许正无穷，随后先进入peekSignals/peekCurve。闭环中每步扣除有限距离，正无穷无法耗尽，可能持续循环和分配。location公开遍历入口也缺有限值检查。
- 修复要求：移动入口要求有限且非负；允许正负位移的location入口要求有限。
- 范围：未证明正常controller已经产生此输入，属低成本即可阻止严重后果的API输入缺陷，不穷举极大数。

### R06 — 同步事件总线注册时未拒绝suspend handler

- 位置：`Core/.../api/event/processor/TypedRailEventBus.kt:39–63,112`。
- `@HandlesEvent suspend fun onEvent(event: ...)`可通过Kotlin参数检查，但派发使用普通 `call`，不具备挂起函数调用协议。错误延迟到派发时被记录，注册却成功。
- 规范要求同步dispatcher及不支持签名注册前失败；当前生产监听器未发现这种签名，未运行复现。
- 修复要求：注册准备阶段明确拒绝 `isSuspend`，带类名和方法名。无新产品决策，不引入协程调度器。

### R07 — BA替代路径的边界容差导致异常逃出规划接口

- 位置：`Basic/.../route/TopologyRoutePlanner.kt:326–331,424–435`；`api/ato/snapshot/RouteSnapshot.kt:15`。
- 具体输入：长度100m，BA span的lower=90、upper=100+5e-10；path replacement的替换范围和permittedPath包含同一span，所以覆盖检查通过。
- 上界先通过1e-9容差检查，BA转换生成负起点，再被RouteSegmentSnapshot直接抛异常拒绝，绕过 `INVALID_RESTRICTIONS` 结果。相邻project路径却会规范化坐标。
- SimpleATO只捕获restriction source编译阶段异常，Basic规划调用位于该catch外；未证明当前内置source已生成此输入。
- 修复要求：统一容差内规范化或拒绝的策略。沿用现有project的规范化意图即可，并处理规范化后零长度区间；不要笼统catch所有内部错误。

### R08 — 自动超高在有效速度为零时回退最大超高

- 位置：`Basic/.../curve/StandardRailCurve.kt:49–58`；Wand超高配置转换。
- 正常可达场景：开启超高，设计速度5–10km/h，默认平衡速度偏移10；Wand减偏移并截到零，传入自动计算配置。实现跳过正速度分支，返回最大超高，违背零速度平方公式和文档。
- 普通含缓和段曲线会得到正超高，速度刚超过偏移时又接近零；关闭超高及手动固定超高不属于此缺陷。
- 修复要求：自动与手动分支明确分开；自动非正有效速度返回零。配置类与Builder重复公式已发生语义漂移，应收敛到一处纯计算，不增加策略接口。

### R09 — 纵断面重建丢失原水平距离定义

- 位置：`Basic/.../curve/VerticalCurveRebuilder.kt:54–58,84–104`。
- horizontalLength、水平station及最终arc均重新累计采样点弦长。原圆弧/缓和曲线保存的解析水平行程因此缩短，即使其中一段仍保持水平。
- 实际调用：Wand接缝坡度不连续时smooth整链，原本水平的邻接曲线也重建；纯平面且无需平滑的整链会跳过，不能说所有建轨都会触发。
- `.trellis/spec/javarailways/basic/curve-authoring.md:60–82`明确要求保留水平解析长度，并已有 `RailCurvePartitioner.horizontalStations/withElevationArc`。
- 修复要求：复用既有station/高差转换，同时统一profile长度与重建长度。
- 边界：分段线性插值的精度约定不允许两个阶段改变距离定义；误差受采样密度限制，不夸大为严重变形或已发生事故。

## 工程实现修复

### E01 — RailObjectStore注销对内部不变量缺失静默跳过

- 位置：`Basic/.../api/rail/RailObjectStore.kt:43–50`。
- 主索引已确认对象存在后，登记rail和辅助列表应在同一mutationLock下必有，却仍可空跳过并正常返回。
- 修复要求：修改前核对完整登记关系，内部损坏明确报错；正常“对象不存在”仍保留原合同。无需引入通用索引管理器。
- 未证明正常注册路径已产生缺失，这是错误处理合同问题，不是已发生的索引损坏。

### E02 — 模块迁移后的依赖和Dokka未收尾

- 位置：`Basic/build.gradle.kts`；`Basic/src/main/dokka/BasicPackages.md`、`Module.md`。
- Basic源码已无ConfigLib及jcip注解使用，依赖声明仍在；Dokka仍写旧api.ato.provider包及已迁出的配置UI职责。
- 修复要求：清理声明与文档；删除依赖后仍需构建验证隐式依赖，本轮未解析或构建。

### E03 — Anchor及几何参数借用可变输入

- 位置：`Basic/.../curve/Anchor.kt:9–21`；`RailGeometryParameters.kt:23,39–41`。
- Vector3dc只是只读接口，原对象仍可变；Map/List也直接借用。调用方可以在校验后修改同一对象，破坏几何输入快照语义。
- 当前Wand主要采用copy替换，未证明生产已经发生并发改写。
- 修复要求：在不影响性能的前提下采用独立值，在构造边界统一复制向量和集合，覆盖copy等入口；不得在每个worker边界重复复制。不得将这项要求扩展成每帧曲线查询的无差别拷贝。

## 接口与领域行为调整

### D01 — 曲线创建只接受当前支持的可保存实现

位置：`Core/.../api/physics/curve/PrimeCurve.kt`、`CoreWorld`轨道创建入口、`CoreRail.kt:103`。

创建接口接受开放PrimeCurve，保存却强转内建SavedPrimeCurveV1。外部实现可能安装后才在保存时失败；尚未发现当前生产使用外部实现。

**修复要求：**在创建或准备入口、任何世界修改之前拒绝不支持的实现。保留已有受支持反向曲线的规范化能力。暂不引入任意曲线codec或通用采样转换；未来出现自定义曲线需求时，再设计持久化支持。

### D02 — 删除遗留距离配置，以资源冲突和实际占用控制手动扳道

位置：`Basic/.../route/TurnoutSafetyDefaults.kt`、`infrastructure/ManualTurnoutSelectionRuntime.kt`、`rail/TurnoutTopology.kt`、`RailOperatingMetadata.kt`；Bukkit `PhysicsConfig`、`RailWorldFactory`、`RailWand`；SimpleATO调度器。

当前机制：

- SimpleATO按制动距离、设备准备时间、反应时间和安全距离提前申请；不读取decisionLeadTicks。
- SimpleSignals按保护区域入口、出口及后继闭塞边界确定授权要求；不读取统一protectionDistanceMeters。
- Basic负责路线物理资源申请、设备READY、授权与车尾释放。保护区域不自动等于整区互斥锁。
- 手动扳道已经申请临时turnout资源，随后按lockingDistance检查物理占用；该距离还限制Wand在道岔附近分割短轨。
- 自动进路的额外道岔占用检查使用端口位置及请求路线区间，不消费此locking距离。

旧距离消费者随 `07c5074f` 和 `9bae71c5` 删除，但配置遗留。每端TurnoutEndpointMetadata中的两项距离也只有持久化与编辑搬移消费者，没有安全运行消费者。

**修复要求：**

1. 删除protectionDistanceMeters、decisionLeadTicks及附近lockingDistanceMeters设定，移除对应遗留校验与基于该距离的分割限制；保留独立的几何合法性约束。
2. 手动扳道必须在统一准入中同时检查资源冲突和道岔实际占用，失败则禁止扳动。不能仅删除lockingInterval检查：没有进路claim的人工驾驶列车仍须受保护。
3. 实际占用按道岔结构判定，不替换成另一个任意附近距离参数。实施前证明使用的占用范围覆盖相关车体/端口场景。
4. retryTicks归相应ATO策略，不保留在Basic通用默认值中。
5. 清理配置、调用方、说明及无消费者的端点距离metadata；持久化部分先核对基线，必要时提供一次性迁移。

### D03 — WayPoint在恢复前验证完整位置

位置：`BasicWorldData.kt:29–42`、`persistence/BasicInfrastructureSnapshot.kt:93–103`。

当前只检查id及rail存在，有限负数或超长arc可载入后才被规划器拒绝。

**修复要求：**值构造检查有限且非负；恢复时在任何写入前检查不超过真实轨道长度，错误指出对象、轨道和位置。不自动截到端点，不保留超长值作特殊标记。默认JSON已拒绝非有限数，不能将程序构造NaN与正常JSON输入混为一谈。

### D04 — 完整生命周期入口与真实完成结果

位置：Core的Car/Train/Rail公开接口及实现；Bukkit列车创建、轨道编辑和工具完成回调。

当前Car公开资源清理能力，Rail资源清理与拓扑删除由外部组合；列车创建失败的车厢清理也由平台调用方承担。

**修复要求：**

- Car不向外暴露Destroyable或等价的任意车厢销毁入口，防止列车进入半清理状态。
- 列车创建流程内部清理自己新建的车厢，外部无需接管失败回滚。联结/解编借用或移交的已有车厢保持原所有权，不能无条件销毁。
- 轨道公开删除完成拓扑退出及资源清理，返回结果代表两者全部完成。内部可保留事务准备、提交、清理等阶段，以支持批量编辑和回滚。
- 工具仅在全部清理成功后发送成功消息；失败明确报告，不能提前发成功。
- 不持世界/拓扑事务锁等待平台清理；结构提交后异步汇总完成结果，再回到适当平台执行边界通知。
- R01完成权隔离和R02实例身份校验同时适用于这些入口。

### D05 — 封装连接写入，默认选通最接近直线的分支

位置：`Core/.../physics/rail/RailView.kt:20,30`；Bukkit `RailWand.restoreViewConnections`、`RailWandTurnoutGeometryPolicy`。

裸MutableList写权限跨模块。实际用途包含事务失败后精确恢复连接顺序与active选择；尚未证明这些调用发生无锁写入。

**修复要求：**

- 由Core提供受所属世界执行边界约束的精确恢复/重排操作，再收回裸列表写权限；同步迁移Bukkit回滚调用。
- 沿用现有连接/显式准备顺序，不引入新的有符号角全列表排序。
- 需要默认选通时优先最接近直线的分支，不能以列表首项代替；已有明确active选择和回滚保存的选择不得被重排覆盖。
- 遵守一个道岔两个分叉的领域约束。实际直行角度定义及同角度时的确定性选择在实施前核对现有几何合同，不能声称当前已经存在几何排序。

## F01 — 列车局部故障隔离与核心故障全局halt

位置：Core物理协调器及通用TickManager，Bukkit world runner/观察发布/列车行为，相关日志与平台任务生命周期。

**目标合同：**

- 可安全隔离的列车包JS错误、列车运动失败及脱轨，只停止受影响列车并记录错误，保留真实占用及必要资源，其他独立线路继续。
- 不应发生的核心不变量破坏，例如frame epoch与完成ticket不一致、存续世界消费已关闭服务，触发所有世界铁路模拟的全局halt并记录。
- 不按异常类型或线程位置直接决定影响范围。未知异常、共享状态已部分修改或无法证明可隔离的故障，不能伪装成普通脱轨后继续。
- 全局halt停止新的模拟推进与操作准入，保留诊断状态并安全收尾在途任务；不销毁列车、不释放实际占用、不关闭Minecraft服务器。

**通用tick的实际边界：**

生产使用单线程执行器，主要运行模型动画计算、车厢延迟效果计时和隔离模拟操作计时。obj.tick异常被捕获，finally释放等待计数，后续统一记录；后台对象异常本身不会跳过barrier。

宿主在preTick之后、postTick之前失败时，可能跳过收尾。若physics仍RUNNING，下一批可能覆盖旧等待和错误统计；尚未证明正常输入触发。单线程下不能据此声称同一对象并行tick。physics已STALLED时下一wake在preTick之前拒绝，也不会反复追加通用批次。

**修复要求：**将宿主异常纳入上述局部/核心分类。核心错误halt后完成必要的后台任务等待或终止，不能继续下一批覆盖旧状态；不能无条件finally执行完整postTick，因为其中还包含排队业务动作。具体等待、取消与安全收尾边界需随实现明确，不建立任意异常自动恢复机制。

## 既有未完项

### BC-23 — 曲线结果nullable访问面

成功/失败模型已经sealed；基类仍有nullable curve/error、isSuccess和失败时空列表便利面，Wand部分成功helper仍可空。统一成功/失败消费合同，移除无效分支，不否定已经完成的模型修复。

### BC-51 — 测试fixture资源归属

已有作用域fixture，但仍有明确遗漏：

- `Basic/.../BasicWorldDataAccessorsTest.kt`三个测试创建四个world，无tick/coordinator关闭；registry.unregister不释放world资源。
- `Basic/.../rail/OperatingDirectionStoreTest.kt`三个测试创建world，独立executor关闭不等于关闭world。

迁移到已有fixture或等价完整清理，不另建测试框架。未测量线程泄漏，创建executor也不表示线程已经启动。43个createTestWorld消费者只完成定位扫描，嵌套use等正确清理不能误报。

## 待验证风险与低优先级边界

这些条目不计为确定生产故障，也不应为假设场景引入复杂机制。

- **C02：generic mailbox清理计数。** map与size独立维护，clear与offer可交错。正常卸载已有world串行化和worker退出后的最终clear，未证明同时发生正常drain。若调整，保证清理发生在producer退出后或受统一准入控制。
- **C03：Core曲线输出别名。** 部分Point/stream借用内部向量，强转可修改内部数据；未发现生产写入。与E03不同，这属于热路径输出，需评估不可变表示或复制成本。
- **C04：有限值输入检查。** 最近点NaN查询会返回首点，宜低成本拒绝；部分底层配置仍需核对finite。正常Wand输入已有约束，不穷举没有实际来源的超大坐标和采样数量。
- **C05：平台及外部扩展异常安全。** 轨道展示提交直接抛错可能需要索引回滚，但玩家缺失、退休、调度返回null已有处理，未找到正常输入触发。预注入第三方扩展的构造异常安全也未建立生产触发链。

## 验收关注点

以下合同已纳入源码复核与对应回归；它们不是待用户重新决策的方案：

1. 不使用附近距离后，道岔实际占用准入的精确范围及人工驾驶覆盖。
2. 删除端点距离metadata的持久化基线、实际数据与迁移需求。
3. 默认直行选择的角度定义、同角度处理，以及完整删除的重复调用语义。
4. 全局halt禁止新的物理推进、资源准入及信号定义提交；诊断、释放、回滚与卸载清理仍可执行。
5. 独立值实现的分配成本，不将输入复制扩散到每帧热路径。

## 不纳入修复范围的设计

- Core Seat API及固定偏移是明确保留的能力，缺少默认玩法消费者不足以证明缺陷。
- BasicRuntimeSnapshot的metadata承担跨文件revision/epoch配对；本次不删除这个持久化边界。
- 内部多阶段准备、提交、清理以及调用方拥有借用资源可以合理存在；D04调整的是公开领域语义与创建流程责任。
- 设备动作不确定时保留claim、延长失败只撤新增delta、同epoch去重、两层tail release、事件关闭不等待已选回调等已有明确合同，不按表面相似性重构。

## 审查与验收边界

已覆盖主要API和消费者、准入/资源registry/尾释放、路径compiler/projector/catalog/planner、联结解编失败链、平台扩展初始化及展示注册、world锁序与关闭、Core刚体与最近点、Basic底层几何和辅助度量，以及Core/Basic文档。

未逐个深查全部测试断言与清理；Hermite/Parallel全部拟合数学路径、所有平台effect内部锁和region退出组合、第三方直接调用与极端姿态未穷尽。现有报告不是形式证明，也不代表不存在其他问题。

实施后的验证应覆盖各项描述的触发场景、失败前不修改状态、失败后资源归属及跨模块调用一致性。当前运行验证及剩余边界以本文开头的验收状态为准。
