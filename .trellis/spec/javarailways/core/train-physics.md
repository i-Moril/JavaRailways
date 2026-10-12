# Core train physics 契约

## 适用场景与用途

触发：修改 consist geometry、bogie/head/tail movement、collision、derail、reverse、link/split/destroy。

用途：保证rigid consist沿同一active path解算，worker mutation经epoch发布；碰撞、方向与结构变更不把partial geometry暴露给consumer。

联结与解编是保留的正式能力（用户于2026-10-10确认），不能仅因暂无生产caller而作为原型删除。`splitBefore`的失败原子性与稳定car ID语义已收口；`linkWith`的事务与方向问题仍须单独修复。此决定不改变默认碰撞DERAIL行为，也不授权自动联结。

联结目标范围（`TARGET`，2026-10-11用户确认，尚未实现）：须支持稳定物理端A-A、A-B、B-A、B-B全部组合，不能通过拒绝同名端回避模型缺口。稳定端不随运营换向更名。例如A-A接触，一侧反序/反向安装加入，但车厢ID、物理端与模型朝向保持；不能重命名或翻转模型伪装。混合安装模型已实现，见下文CURRENT；四端联结事务仍待实现，验收须贯通运动、换向、split、frame与保存恢复，不能只证明列表拼接。

联结准入（`TARGET`，2026-10-11用户确认，尚未实现）：两列均已停车且指定车钩已接触才允许联结；取消远距离吸附，联结不得移动已有车体或转向架来制造接触。接触须依据同一实际可连接轨道路径上的相向车钩及三维车体端点/两端connector length判定，不要求不含车钩伸出长度的两个`TrainEnd`重合，也不能把菱形交叉处的空间近邻当作接触。停车与接触校验须基于结构事务取得的同一已完成physics epoch；具体停车判据及接触数值容差在实现方案中明确，不自行引入可吸附距离。移动中、未接触或路径不连通时拒绝，保持两列原有状态。验收覆盖四种端部组合下的合法接触、不接触/移动中拒绝与失败原子性，并断言成功时原有车体和转向架没有位移；默认碰撞DERAIL行为不因此改变。

### 联结/解编的方向选择与插件事件（联结TARGET，解编CURRENT）

联结由插件共同决定兼容性、主编组及结果方向，不是调用方先决定、监听器只能补空值。未来SimpleATO按时刻表选择主编组，并保留双方元数据供解编使用；Core不解释运营信息。A-A/B-B允许另一侧反向安装接入。当前解编已允许插件分别指定两个结果方向，默认各自继承原方向，见下文；联结事务和SimpleATO业务仍未接入。

方向必须分为三个概念：车厢固定物理A/B及模型朝向、车厢相对于编组稳定顺序的安装朝向、结果编组的运营方向。反向接入只改变第二项，插件通过决策事件选择第三项；不能移动模型或重命名物理端。例如两列A-A接触时，以主编组为基准反向排列另一侧，并记录其安装朝向；解编后两侧可以独立选择运营方向，不改变切分的稳定car ID连接面。

#### 两阶段规则（解编已接入，联结待实现）

联结与解编均采用“提交前决策事件 → Core执行结构事务 → 成功事件”。联结前插件检查车钩兼容性等条件；解编前插件可以拒绝拆开固定单元、禁止的连接面或不允许的运营状态。即使操作由另一个插件发起，也不能绕过这些检查。Core只检查物理与结构合法性，不解释车型和运营规则。

提交前事件允许插件拒绝及指定结果编组信息；拒绝是本次操作内的单向状态，一旦任一插件拒绝，后续监听器不能清除。Core必须等决策派发完成再决定是否提交。主编组和结果方向影响物理结构，仍在此阶段确定；监听器只能修改决策/结果草案，不能提前修改原编组及正式运营记录。

联结成功事件、解编成功事件仅在对应结构、索引及完整物理frame提交成功后触发，每次成功操作各一次；被拒绝或准备失败不触发。成功事件是通知而非第二次决策，不允许取消或改写已完成的结构。未来SimpleATO在成功事件中才正式保存/更新双方运营元数据及编组关联，提交前只能准备所需信息。这样无需依赖SimpleATO与兼容性插件的监听优先级来避免失败操作留下运营变更。

多个插件指定互相冲突的方向时拒绝，不采用最后写入覆盖。提交前监听器异常时记录错误并继续派发，但不得提交；成功通知异常时继续通知，不回滚或将成功报告为失败。当前解编入口/事件及既有联结与extension结构钩子已标记unstable，后续可能修改签名和语义；这不表示仅用于测试，也不改变存档版本。

正常流程为：取得结构变更准入及已完成epoch → 基础物理校验 → 决策事件 → 汇总拒绝及编组/方向选择 → 准备extension/frame → 一次提交 → 成功事件。插件不能绕过物理校验；准备失败保留原结构、方向、extension、索引及frame。可变草案仅在结构owner线程使用，现有总线不会自动取消，结构owner须显式检查派发失败。

联结事件仍待实现：仅由碰撞产生尝试，插件决定是否联结、主编组及方向，不新增主动`Train.linkWith`入口。“调用方”指插件，不指参与碰撞的列车。未指定主编组时优先选碰撞前唯一静止的一方；必须捕获冻结前事实，不能等两侧均被停住再判断。双方同为静止/运动且插件未指定时，按稳定train ID字典序选择较小者（2026-10-12用户确认），不依赖碰撞候选遍历顺序；这只决定主编组，不替代实际接触端与安装方向决定的车厢排列。用户已批准联结被拒绝或准备失败时回退默认脱轨。缺省方向保持主编组实际运营朝向；插件冲突拒绝。车厢JS与动作随车厢保留，见[目标合同](../../bukkit-plugin/backend/car-behavior.md)；不因合并而重建JS或清空开门状态。

未来SimpleATO只在准备阶段捕获双方运营元数据与稳定车厢/连接面关联，成功后才正式更新记录；准备失败不得先删除另一列数据。元数据内容、保存/恢复协议及运用分配由SimpleATO另行设计，Core不新增时刻表字段，也不假定解编必然恢复旧运用。

方向映射预计为O(N)，N为车厢数，不增加每tick事件或全世界扫描。联结后续验收须覆盖四种端部组合、指定/缺省方向、选择主编组后被拒绝及反向监听顺序、准备失败原状态保持、成功一次且完整frame可见；解编验收见下节。未来SimpleATO接入另验双方元数据保留与解编映射，不以Core结构测试替代业务验证。

## Owner、签名、单位与 lifecycle

`CoreTrain`拥有有序cars、head/tail、epoch-end placement spans、published swept spans和controller registration。`moveTrainForward(deltaS: Double)` 仅physics worker调用，`deltaS`单位米且`>=0`：

```kotlin
override fun moveTrainForward(deltaS: Double) {
    if (activeDerailState.derailed) return
    require(deltaS >= 0.0) { "deltaS must >= 0" }
    // signal scan and consist solve follow
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`。

公开的`moveTrainForward(deltaS)`只有距离、没有时间基准，因此不得从`deltaS`猜测速度；该路径移动几何时把本次
car/bogie速度按0发布。`TrainController.moveControlledTrain`对`CoreTrain`改用implementation-only
`moveTrainForwardFromController(deltaS, speedMetersPerSecond)`，其中speed是command完成本次`tick(deltaT)`后的
当前m/s值。内部CoreTrain方法不进入`core.api`；protected controller seam是子类合同，非Core `Train`才回退公开distance调用。
基类、Basic realistic manual与SimpleATO ATO均使用该入口，覆盖`tick`的新controller不得绕开。

controller移动head bogie后，Core用其**新位置**的train-local facing tangent乘当前speed，得到首个head bogie速度；
随后沿有序编组传递tail/head端速度。car刚体角速度使用实际head→tail bogie chord `r`计算
`omega = r × (vTail - vHead) / |r|²`，单位为rad/s；car中心和下一车连接点速度再用
`v(point) = vHead + omega × offset`求得。括号是合同的一部分：Kotlin infix `cross`的优先级不能让表达式退化成
`(vHead + omega) × offset`。bogie/car velocity与geometry各自维护dirty状态和CommitBuffer槽位，避免
motion-only epoch翻转未写入的geometry槽；两类状态仍在同一个epoch barrier发布。consumer不得按位移或train标量速度重算。

零速controller epoch、collision/derail拒绝移动、运动异常、无controller的下一physics epoch及只发布换向的epoch均把
整列active motion清零，防止上一epoch非零速度残留。`deltaS == 0`但controller仍报告非零当前speed时保留**epoch结束瞬时速度**
语义：这可表达本tick末才建立的速度，不表示本tick发生了位移；collision/reverse等明确冻结分支仍强制为0。无时间基准的
公开/manual移动、`setLocation`/maintenance placement、存档placement恢复与主线程reverse/derail始终发布0。换向后的下一次
移动从反向后的active rail facing tangent重新注入速度。

运动顺序：coordinator先检查published swept collision并标记参与双方，再启动worker；未冻结的controller扫active route signal并同步发event → 解head bogie/cars/tail → 写active buffers/swept spans及两端有序traversal。`PhysicsException`转physics derail。tail从train-local forward path重算，不依赖reverse branch当前selection。

### 碰撞查询时点（CURRENT）

正常epoch先应用此前碰撞决定、捕获controller列表，然后在启动任何worker前，以AsyncTick线程上下文按该列表查询上一完整epoch的共享碰撞索引。参与双方先加入既有pending集合，后续worker跳过其controller tick并冻结物理运动。原`moveTrainForwardFromController`不重复查询；这不是预测本tick位移，也不新增移动意图、任务或等待屏障。每个controller至多一次既有局部索引查询，不扫描全世界列车。查询从worker移到coordinator串行执行，自定义不调用move或本tick只换向的controller也先检测既有碰撞；不承诺查询总数或墙钟成本完全不变，实际开销尚未profiling。

检测先检查发起查询列车的内部active脱轨状态。此前决定可能已在本epoch标记脱轨，但published frame仍是旧值；只读published状态会在这段窗口重复派发碰撞。该检查迁移原移动入口最前面的脱轨拒绝语义，内部查询不成为公开API。不能因为查询命中的另一列车已经脱轨就忽略它：它仍是物理障碍，正常列车撞上它仍须冻结/处理；在首个候选返回后直接忽略它还会遮蔽后续候选。事件仍在physics上下文同步派发；主线程结构处理和下一epoch决定语义不变。冻结不执行controller tick，因此不提前消耗其命令或新增该tick的ATO里程，但不会撤销上一碰撞步进已发布的经过事件和里程。

预扫耗时计入总`wallNanos`，但不在现有`coordinatorNanos`的worker等待后计时窗口内；不能用该局部指标声称覆盖新增串行阶段。`PhysicsCoordinatorTest`以两列重叠、均有可移动controller的真实epoch断言两个tick调用均为0、双方head/tail不变；既有下一epoch脱轨及每pair一次事件仍须通过。并行worker测试的列车必须分开放置，否则被正确冻结不能作为并行性能fixture。此调整只闭合观察帧与worker启动之间的竞态，不代表车钩检测或联结重算已经完成。

运动失败的脱轨原因保留异常类型与消息，并附列车ID和本次请求距离（米）；它沿原有frame→Main事件链发布，不因诊断改动而重试或改变失败分类。
`BogieException(Bogie)`在构造时复制转向架已发布位置的rail ID、facing与弧长为字符串，不持有live bogie；该位置不是失败时的active位置，不能用来声称精确故障坐标。比如请求100 m越过线路尽头时，诊断同时说明请求距离和此前已发布位置，而不是只留下异常类名。正常移动不构造这些诊断。
原因文本供人阅读，不是可解析的稳定错误协议；既有脱轨事件回归须验证上下文可见、frame先发布、事件只发一次，不能仅检查抛异常。

每次正距离移动已经取得head的`peekCurve(deltaS)`有序segments；同一列表同时供signal扫描、swept
occupancy补全和head traversal publication使用。tail endpoint仍通过既有train-local forward search求解，
并保留该search的`RailSegments`作为tail traversal；不得从反向`RailView`或移动后的active branch重建。
publication把每个segment转换为canonical AB弧长但保留local facing与列表顺序。零距离移动显式发布
空的head/tail movement traversal，下一epoch无移动也清空前一epoch证据；这两项不是持续占用状态。

每次发布还从同一已提交train-local path生成`bodyTraversal`：它是epoch末从保守车尾到车头的有序
持续位置，不是一次移动事件，也不从无序`occupiedIntervals`反推。Dispatcher只把它接到已经选定的
head-forward mission route前面，以满足Basic“冻结路线从物理车尾开始”的合同；Dispatcher不得为此再寻路。

公开`tail`、`bodyTraversal`与`tailTraversal`的长期安全语义是“保守安全车尾”，而不是强制精确几何车尾。当前实现发布精确train-end；未来低精度模式可让安全车尾落后于真实车尾并延迟释放，但禁止让它沿行驶方向领先真实车尾。与此一致，`bodyTraversal`和`occupiedIntervals`必须覆盖epoch末真实车体；允许多占，不允许漏占。

完整删除由`CoreTrain.destroy()`拥有：deferred structural task → remove controller → destroy head/tail/cars/extensions → `world.removeTrain(this)`。`World.removeTrain`只删index并退休process-local render ownership proof，不能独立代表destroy。

每个runtime `CoreTrain`持有不持久化的publication stream UUID；`CoreCar`只用一个`AtomicReference<CoreTrain?>`保存当前owner，公开`Car.train`读取同一引用，render proof从该owner派生UUID，不维护两份归属或全世界索引。构造完整成功后才绑定cars；prepare失败保留旧owner；link转移后移除旧train，split新构造取得其cars。`CoreWorld.removeTrain`按exact旧train实例CAS退休，仅清除仍属自己的car；已转移或同ID替换不受影响。查询/转移均O(1)，整列绑定沿用原cars遍历。原用例覆盖构造/解编失败及成功、旧owner退休和同ID重建；不改变旧link事务、epoch或存档语义。

## 构造与失败清理

`CoreTrain`构造在main thread完成。先通过`CoreWorld.validateNewTrainIdentity(id, name)`检查ID/name唯一性及交叉命名空间冲突，再计算head/tail两个位置；任一位置无法放置时抛`CannotCreateTrainException`，此时尚未注册任何`TrainEnd`。`addTrain`复用同一身份校验，仍允许同一实例的重复添加（world加载链会调用）。校验查world索引，不额外遍历列车。

`CannotCreateTrainException(message: String? = null)`保留JVM无参构造和默认null消息，另可接收不可变诊断文本；不持有live列车或位置对象。正常构造的head/tail定位返回null时才生成消息，记录失败端、列车ID、输入转向架anchor的rail/facing/弧长以及有符号端点位移（米）。anchor是本次定位的起点，不是尚未生成的失败端点坐标；例如120m线路上从119m请求+2.5m定位HEAD，应说明起点119m和请求+2.5m，而非声称已得到121.5m的合法轨道位置。这里没有被捕获的原异常，不人为增加cause。消息供玩家/维护者排错，不是稳定可解析协议；split/link的其他拒绝点不因此改变。此改动不增加成功路径扫描或改变失败注册/清理边界。

随后依次创建注册head/tail、添加world索引、初始化train extensions、生成初始published frame。该阶段失败时，构造器仅撤销自己已完成的注册：移除controller，分别destroy已创建的车端，只在本次`addTrain`成功后移除world索引。清理逐项尝试，失败附加到原异常的`suppressed`后继续，最终重抛原异常。不能在身份拒绝后无条件`removeTrain`，否则可能删除同ID的原列车及其frame。

构造未返回时，传入cars仍由调用方拥有；此清理不调用完整`destroy()`或`discardFailedLoad()`，不销毁cars/bogies。已返回列车的加载恢复失败仍由[持久化加载边界](./persistence.md)处理。`TrainExtension`没有通用destroy/rollback协议，本边界不承诺撤销extension任意外部副作用；正常的列车销毁及结构变更也不能用此局部清理替代。

## 解编事务

### 解编事件与方向提交（CURRENT）

公开入口为`@ApiStatus.Experimental fun Train.splitBefore(carId: String, newId: String): Future<Train>`，按稳定车厢ID切分，返回的future表示结构操作结果，不表示平台实体显示完成。复用JetBrains annotations的`ApiStatus.Experimental`标识联结/解编接口和事件不保证后续源码或二进制兼容，不引入自定义opt-in协议；Java/Kotlin调用方均须遵守KDoc约定。该标记不扩大尚未修复的旧`CoreTrain.linkWith`的能力范围。

入口非阻塞地排入原有main-thread结构队列，在coordinator的内部`withStructuralMutation`作用域中捕获并等待physics ticket，同时禁止新epoch进入，直到成功通知完成或异常退出才释放；仅await不能替代该[临时准入边界](./runtime-publication.md#结构变更的临时物理准入current)。检查切分边界和新身份后才派发`TrainSplitEvent(train,carId,newTrainId)`。事件内`originalCarIds`为不可变副本，`originalReversed`保存原方向；live train只能在owner线程读取。插件通过`reject(reason)`保留第一次拒绝原因，或分别调用`requestRetainedDirection(reversed)`、`requestSeparatedDirection(reversed)`。未指定时继承原方向；同值重复有效，异值冲突拒绝。派发后冻结，mutator同时检查main context，不能延后修改。总线继续隔离handler异常，结构owner显式检查派发结果；失败或拒绝使future以`IllegalStateException`异常完成且不进入extension准备。调用方不得在负责drain队列的线程等待future。

只有决定通过后，才准备两侧几何、extension和最终frame。先按原运营方向取得tail到head的占用路径，再在该有序路径上按bogie位置与车端offset定位切分端点，不能调用反向选路来向后推算车端；不在原占用路径内的bogie或端点拒绝解编。方向改变交换并反向表示head/tail，逆序原有body traversal并交换每段起止位置/方向，预计算bogie反向位置；不能从反向道岔选择重新寻路或调用摆车算法。所有可失败计算都在原状态变更前完成。成功构造分离列车后，提交区间只应用准备好的方向、位置表示和frame，不再执行扩展回调或几何求解。

未改变保留侧方向时保留既有运动状态；改变时按现有主线程换向语义，保留controller及其标量speed，但本次car/bogie速度清零，下一physics epoch由controller沿新方向生成运动。分离侧始终无controller且速度清零。换向不改变稳定car顺序、物理端身份、安装朝向或模型位置/朝向。最终两侧frame、车端索引与publication ownership提交后，才发布一次`TrainSplitSuccessEvent`；通知失败不撤销结构，不将future改为失败。事件携带切分前的稳定车厢身份和原方向，运营层可据此映射自己的元数据，Core不解释其内容。

事件只在低频结构操作执行。方向/frame准备为O(N+P)，N为相关车厢及bogie数、P为两半车身路径段数，另计既有路径展开与端点定位成本；不增加每tick扫描。`CoreTrainSplitTest`检查方向组合、同值重复、冲突顺序、单向拒绝、两阶段异常和初始化失败；`CoreTrainAsymmetricTurnoutTest`检查反向选择未设置时仍保留原路径。离线测试记录见任务，不表示平台实体或游戏验证已完成，更不表示联结事务完成。

### 混合安装朝向的表示（CURRENT）

Core复用每车`CoreCar.isReversed`表示物理B端是否为当前运营前端，整列`CoreTrain.isReversed`只决定稳定cars列表的运营遍历顺序。车厢相对于稳定编组的安装朝向为`car.isReversed xor train.isReversed`，不另存一张运行时朝向表。整列换向同时翻转整列与每车的运营方向，因此安装朝向不变；模型direction及bogieA/bogieB身份仍保持。例如安装朝向为`[true,false,true]`的编组，整列未反向时每车运营反向状态相同，整列换向后变为`[false,true,false]`。

车头/尾先按整列方向选首/末车，再使用所选车自身的head/tail bogie、offset与connector；恢复校验和解编端点也按每车选择。移动、速度与车钩求解继续复用既有逐车active方向算法，不增加每tick索引或几何扫描。单项方向查询为O(1)，保存/恢复的朝向转换随原有cars遍历为O(N)。错误做法是所有车统一使用train的布尔值选bogieB；正确做法是先选运营首车，再由该车自己的方向选择物理端。

`SavedCarV1.installedReversed: Boolean = false`保存相对所属列车稳定顺序的安装朝向；`CoreTrain.save()`逐车传入上述异或值，独立`CoreCar.save()`没有编组上下文，明确写false。`SavedTrainV1.load()`先在新创建的cars上只恢复`train.reversed xor installedReversed`端部选择，不反转已保存的bogie facing，也不在校验前计算几何；随后按已保存整列方向构造，沿既有路径/三维间距校验后重建车身并提交。非法位置仍拒绝并沿现有failed-load清理，不增加静默摆车或兼容reader。字段有明确默认值，不改变runtime格式版本；缺字段表示同向安装，不根据位置猜测朝向。

`CoreTrainMixedInstallationTest`使用非对称offset `+3/-2m`、connector `.25/.75m`及首尾均反装的三车编组，覆盖两种初始方向的真实epoch运动、换向、稳定切分并独立反转分离侧、完整codec精确恢复及手写缺字段输入。混装定向用例只覆盖直线；混装曲线/坡道与四端联结仍须后续验收，不以现有同向曲线回归替代。

`Train.cars`是稳定编组顺序，reverse不重排；`operatingCars`只是`cars + isReversed`的只读派生。`splitBefore`始终在稳定目标car之前切分：原列车保留前缀，新列车取得目标及后缀。目标未知、不唯一或为首车时抛`CannotCreateTrainException`；末车之前可分出单车。新ID/name冲突由world身份校验拒绝。换向前后同一car ID对应同一连接面，不按运营head偷换保留方。

决策通过后，准备两侧几何及完整frame，按原slot顺序取得独立`TrainExtension.splitBefore`结果并构造新列车。准备或构造失败保留原cars/方向/controller/extensions/frame/owner/world与rail索引，新注册由构造器清理。构造期间新host仍使用原方向及几何，所选方向和最终frame仅在initialize成功后安装，extension不得提前将prepare host当作最终结果。

Core没有能撤销任意插件外部副作用的通用协议，因此`TrainExtension.splitBefore`是prepare-like边界：不得修改原extension或发布外部效果，必须返回尚未attach的独立实例。新实例保留同一slot identity，只在新列车构造期间initialize；该次initialize仍属于prepare，只能写返回extension自身，不得修改两列共享的cars或发布平台效果。这是extension实现者的合同，不通过猜测原extension内部状态实现伪回滚。成功后新`CoreTrain`取得转移cars的publication stream ownership，原列车以预计算结果更新两端、占用和frame，不销毁任何car。新列车没有controller，因此分离cars/bogies的运动状态在提交时清零；两列结构frame沿用切分前已完成的同一physics epoch，不能发布“新车speed为0但车体仍携带旧非零速度”的混合帧，也不能把新车留在epoch 0。新列车构造成功后的commit-only区间不再调用可扩展代码或几何求解：只应用预计算字段、清零分离侧运动、翻转`CommitBuffer`，更新rail端队列、原子publication引用和latest-wins mailbox。

车型或运营插件通过决策事件拒绝禁止的连接面，即使另一个插件发起也能检查；成功后才更新正式元数据。Core不实现车型、权限或SimpleATO业务。

## 三维刚体约束与局部距离求解

CoreCar 拥有车内和车间几何求解。正常运行时 controller 先移动首节车的前转向架，CoreCar 固定这个新位置，求后转向架，再按运营顺序逐车满足车钩约束；后车纠偏不能反推或重建领车目标。显式摆位与恢复采用同一车体中心规则。

车内约束使用连接点 `location.position + location.normal * floorHeight` 的三维直线距离，等于 `bogieAOffset - bogieBOffset`；不能用轨道弧长代替。车体中心统一从运营前转向架连接点，沿后→前的连接点弦方向减去相应前端配置偏移。正反向仅选择不同端点和偏移，不改变配置距离的意义。车间约束为两个车体端点的三维距离等于两端 connector length 之和，纵向投影仅用于辨认正确侧。

### 本次解算的路径与候选

每次解算捕获一个有序 RailSegments，并以累计弧长查询其中候选位置；内层车内求解复用外层同一条前向路径，不重新读取反向道岔选择。运行时先按上一位置加 deltaS 预测；后续车的内层轴距预测复用上一 tick 的轨道间距。若必须向后纠正，范围严格限制在上一转向架所在 RailView 的当前轨道内，以直接弧长定位，不能跨 reverse turnout。

私有 `solveDistance(maximumDistance, prediction, tolerance, sample, condition)` 先验证预测候选的实际残差；不满足时从附近扩张区间，初始半径为 max(0.01m, |残差|)，每次加倍至捕获路径边界。找到异号区间后最多迭代60次：割线落在区间中间80%内时采用割线，否则取中点，保持区间收缩。候选连同已计算的几何和残差保存，命中时复用结果。只有有限残差满足精度才能成功，步长很小或耗尽次数不能视为收敛。该局部根搜索依赖目标附近连续、可包围的距离条件，不是任意自交线路上的全局最近根证明。用户明确将轴距接近曲线直径的极紧曲线排除本次支持范围；不为其非单调窄根区间增加特殊扫描，找不到可验证的根则明确失败。

内层轴距容差为1e-6m，外层车钩为1e-5m，均小于最终公开几何容差 Curve.TOLERANCE=5mm；严格内层精度减少锚点换向后的中心误差。在有限线路末端，先用实际连接点距离求出最后可行尾转向架候选，不能只按名义轴距弧长裁剪，否则外层试探可能越过可解区间。

候选几何计算不修改 CommitBuffer 或 dirty 标志；成功应用后才设置 geometryPhysicsDirty，运动状态保持原有独立 dirty/publication 合同。无包围区间、不满足残差或不能到达目标时抛既有 BogieException，运行入口沿 PhysicsException→derail 边界处理，不将求解失败标为成功；这不新增整个编组运动的事务回滚保证。

### 计算成本、边界与验证

每次曲线坐标查询仍为 O(log P)，候选路径定位为 O(K)，P为轨道采样点数、K为本次跨越段数。轴距搜索与车钩搜索仍可能嵌套；预测命中可减少次数，最坏成本包含扩张和60次区间修正，不能宣称单车严格 O(1)。所有缓存只属于本次求解，不跨 tick 或拓扑更新保留。

直线匀速时预测通常直接满足残差；R30/6m轴距反例必须求真实弦长；S弯、坡道、normal/floorHeight、非对称偏移、零车钩、换向及有限轨道末端均须验证。新增 `CoreTrainRigidGeometryTest` 用真实 epoch 检查实际连接点间距、两端配置偏移、车钩长度/侧、精确领车目标及加载中心一致性，不能通过扩大5mm容差掩盖误差。

## 存档摆位恢复

`restorePersistedPlacement`先验证保存的位置，再恢复derived car state和车端；不重新摆车来覆盖损坏输入。它复用body publication的`placementSegments(savedTail, savedHead)`：沿tail→head的active path一次展开，不能从head反向沿当前道岔选择重算，因为两个方向的选择可以不对称。

单调cursor按operating顺序从车尾向车头扫描各车tail/head bogie，要求exact RailView身份（包含facing）、位置落在该segment且距离不倒退。累计弧长只证明路径顺序及首尾bogie到saved endpoint的沿轨offset；车内间距按上述三维连接点距离校验。随后按相同前连接点锚定规则计算各车中心，校验相邻车体端点距离与车钩前后侧；各项允许误差不超过 Curve.TOLERANCE（5mm）。内部某车整体平移而保持轴距/顺序的损坏输入必须因车钩不符而拒绝。

除既有路径展开外，只顺序访问一次segments/bogies/cars；这些校验在load执行，不增加正常tick扫描。失败沿既有failed-load清理，合法保存的bogie/车端精确位置保留；这不代表整个多列车world materialization失败可原子回滚。

摆位恢复保留既有位置字段及格式版本；混合朝向另增加上述默认false的安装字段，不保留旧弧长近似的运行时兼容分支。如需保留不符合当前刚体约束的旧开发数据，应另行提供可审计、dry-run且保留恢复点的离线迁移。错误做法是用按弧长放置的旧数据证明新刚体合法，或先移动保存位置再静默接受；正确做法是在当前模型边界校验实际几何，并明确拒绝不一致输入。

## Collision、derail、reverse矩阵

| 触发 | 当前epoch结果 | 后续结果/side effect |
| --- | --- | --- |
| swept overlap首次claim | pair冻结，发布一次`CollisionEvent` | 下一epoch应用`DERAIL/LINK` |
| listener `requestLink()` | pending resolution=`LINK` | 下一epoch owner执行link transaction |
| worker physics failure | active derail pending | epoch frame flip后queue main derail reason |
| main `derail(reason)` | reason非blank，await physics后publish | frame后立即Main event，仅一次 |
| controller reverse奇数request | 本epoch只改direction/geometry | frame先发布，下一epochmovement |
| even reverse requests | 抵消 | 无direction mutation |
| `destroy()` | deferred完整cleanup | world index最后移除 |

Reverse改变operating head/tail，但保持`Car.direction`的模型/渲染朝向不变。`Car.operatingDirection`是只读派生值，
表示正的`moveTrainForward`距离对应的当前物理前进方向；换向后它必须翻转，供Basic坡度计算使用。不得把这两个方向
重新合并成一个字段。exact persisted bogie/head/tail placement restore后重建derived car state。

## 项目内正确示例

完整destroy按owner顺序清资源：

```kotlin
override fun destroy() {
    world.tickManager.scheduleMainThreadAfterCommitTask {
        removeController()
        head.destroy(); tail.destroy()
        cars.forEach { it.destroy() }
        world.removeTrain(this)
    }
}
```

来源：`CoreTrain.kt`。它避免只删world map后遗留controller、TrainEnd或render extension。

## 禁止实现

- worker直接修改published getter或发Main event；
- collision同epoch检测并立即link/derail；
- tail沿独立reverse active branch求位置；
- 从合并swept span或epoch终点反推head/tail经过顺序；
- 从`deltaS`推测速度，或由Bukkit/其他consumer补算Core car/bogie速度；
- 用单位车身方向代替实际bogie chord计算角速度（会丢失杆长并破坏rad/s单位）；
- 用整epoch `sweptSpans`判断epoch末列车是否仍占用一个监测区；
- structure mutation直接改cars/map而无serialized task、prevalidation与rollback；
- 用`world.removeTrain`代替destroy；
- 将构造失败清理放在调用方取得列车之后，或让失败构造销毁调用方传入的cars；
- 让split在身份、端点、extension或新列车构造可失败阶段前修改原编组，或将extension副作用伪装成Core可回滚的事务。

## 必需测试与验证

- `CoreTrainTailBogieTest.kt`、`CoreTrainAsymmetricTurnoutTest.kt`：turnout/reverse/tail spacing。
- `CoreTrainSplitTest.kt`：正/反方向与末节解编，非法边界、身份、端点、extension prepare/initialize失败时的原状态保持，slot和publication ownership。
- `TrainSlopeMotionTest.kt`、`TrainSlopeJointMotionTest.kt`、`CoreCarPositioningRegressionTest.kt`：3D geometry。
- `TrainUniformMotionTest.kt`：真实50 ms coordinator epoch同时断言0.5 m位移、car/bogie frame的m/s速度、停车清零及换向后tangent符号。
- `PhysicsCoordinatorTest.kt`：stationary collision、pair once、next-epoch decision。
- `CoreTrainDerailEventTest.kt`、`CoreTrainPublicationBoundaryTest.kt`：publication before event/getter。
- `CoreTrainConstructionTest.kt`：直接构造/存档加载的ID、name及交叉命名空间拒绝不污染world/rail索引；head或tail无法放置时诊断含对应端点及输入anchor/请求位移，不注册车端、不接管cars；extension初始化失败清理车端和controller、保留原列车及调用方cars，并允许同身份成功重试。
- `CoreTrainPersistedPlacementTest.kt`：固定损坏车端JSON；脱节/重排/错误rail或facing、bogie连接点距离及两端offset拒绝并保持原world/rail/frame/mailbox；合法直线/曲线AB与BA、非对称道岔精确位置保留。内部车厢整体平移而保持顺序/轴距时，因相邻车钩不符而拒绝。
- 新link/split/destroy变更必须断言cars、ends、controller、world indexes和extensions全量cleanup。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.train.*' --tests '*PhysicsCoordinatorTest'
```
