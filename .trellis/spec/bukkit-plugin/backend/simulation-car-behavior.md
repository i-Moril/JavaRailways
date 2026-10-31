# 隔离模拟中的真实逐车行为

## 状态、触发与owner

状态：`CURRENT`实现接线；完整最终候选/自动门禁见`10-18-simulation-behavior-features/research/validation.md`，本页不替代执行证据或Paper/Folia现场验收。修改模拟profile、car behavior、操作gateway、编组结构后配置或模拟close时加载。

BukkitPlugin既有`CarBehavior`、`CarOperationBatch`和`TrainBehaviorRuntime`仍为唯一行为实现；SIM factory也在同一模块，直接使用同一逻辑并经Basic `TrainOperationGateway`注入SimpleATO Dispatcher。不把这些类型或Graal搬到Basic/SimpleATO，不新增Environment、通用JS/clone/DI框架或第二事件总线。平台实体、可视距、玩家控制会话、实际动画/声音播放排除。

## 共同运行模型与最小差距

旧SIM仅按`modelActionDurationTicks`最大值倒计时并成功，不执行JS、门侧、拒绝或全部effects。该执行路径及只有此用途的duration resolver/gateway/字段已删除。`TrainExtension.resolveActions`还有命令能力推断consumer，不能误删。

每exact car持一个惰性persistent Context，独立globals与声明；同car入口/延迟效果/close保持monitor串行。LIVE继续CarExtension+region效果，SIM是无显示`SimulationCarBehaviorExtension`：复用原CarBehavior，动画/声音只执行已接受效果的启动语义，默认不记录历史，generic tick仅累计毫秒换算秒，既有owner commit执行闭包。不开每车JS tick，不调用Bukkit scheduler/model/entity，也不把每次调用改为fresh Context。

## 捕获值与producer→consumer

`SimulationControlProfileCatalog`在源稳定owner边界捕获物理蓝图及每车`CapturedCarBehavior(script,actions,animations)`；真正create/step不再读LIVE renderer或ModelManager。WORLD_TRAIN每车通过CarExtension的既有operationalSnapshot选择SNAPSHOT固定包/FOLLOW_PACKAGE当前成功发布包；已引用包/模型缺失明确失败。TRAIN_TEMPLATE使用当前resourceVersion的models与carPackages逐车源，不能用主包脚本替代跨包车厢来源。

动画能力是实际SavedCarModelTree的body/bogie/wheel部分递归索引；每节点调用现`toRuntimeAnimationMap`，保留空动画省略与同节点同名last-entry语义，读取现Animation的normalized scene索引，跨节点合并能力。共用 `supportsAnimation(name, scene?)` 为 JS 只读 `hasAnimation` 出口与真实效果验证提供同一名称/场景判断；查询不启动效果，可在初始化与声明读取时调用。s100按实际车厢端灯能力选择独立前/后灯动作，缺失动画的直接动作仍失败。不重复解析timeline，不携带模型实例、Compiled display、Context、延迟supplier或source World/服务。配置值及脚本文本为detached只读输入，同一capture每target新建状态。

安装方向保存`car.isReversed xor train.isReversed`，seed用现CoreCar.reverse还原。模板没有安装反向字段时用其当前真实默认false；不是旧数据兼容路径。每次接受操作才按共享`behaviorRelativeTail`计算`controlTail xor (car.isReversed xor train.isReversed)`；SIM没有手动session，使用LIVE无session的`controlTail=train.isReversed`。不复制在线列车/预约/控制器或JS globals。

## 精确操作与完成合同

`SimulationTrainOperationGateway(World)`与LIVE共用ModelOperationTicket的PENDING单次终态转换，支持OpenDoors/CloseDoors/ExecuteModelAction；SetCabLights/Announce与实际Bukkit adapter同样Rejected，不能零Tick冒充成功。

接受时固定现cars、每车方向与CarOperationBatch目标：普通门操作包括全车；custom action先查询全部声明/权限再选支持者，JS同名优先、权限与支持者合同沿原实现。SIM不复制简化动作选择或脚本语义。所有已接受动画/声音启动且集合封闭后才成功；同步effects callback不能掩盖随后拒绝，完成不代表动画播放结束。

取消前尚未派发的目标不执行；已接受effects可继续但CANCELLED不变SUCCEEDED。脚本false/抛错/未知动作或无有效animation/scene转为真实失败，Dispatcher继续原hold/stall门，不提前发车。初始化/声明扫描不得产生effects。错误保留实际源位置，行为和换向日志包含simulation、目标runtimeId及car/train上下文。

LIVE不可见renderer有既有held-scene缓存：有scene且模型未生成时可先接受，无scene且模型缺失可失败。SIM按捕获的静态真实能力验证effects，不模拟视距或实体可用性；不得据合法定义下的领域对照宣称所有平台可见性状态等价，也不得为了对齐改LIVE该分支。

## 结构、生命周期与事件

seed在owner帧间通过`CoreTrain.createOwned`创建bogie/car/train，逐车安装方向、behavior与train profile在`publish()`前完成；新behavior立即登记scope rollback，部分构造失败不会留下Context或delay。factory保留原设施active selection直接快照，并捕获Dispatcher的`atoAdmissionRetryTicks`，不恢复旧排序覆盖选择或移除的turnout defaults。

seed在现Bukkit Core plugin slot挂无显示car extension与`SimulationTrainExtension`，不分配新plugin身份。car扩展保留CarProfile，train扩展保留TrainProfile并按既有split/link hooks继承。operation/ATO从target本地extension容器读取实际类型，不持LIVE Plugin/factory/catalog。nullable平台renderer getter对这一实际另一实现返回null，不创建fallback renderer。

联结/解编移动原CoreCar而不关闭/重建其JS与pending效果，原操作目标和物理侧不重解释；新加入车不进入旧操作。train split的新对象继承配置；factory从当前cars解析CarProfiles并使用现HandleTrainActuator.profileResolver，使被吸收而无controller的car同样取得自己的已捕获配置。不再靠seed-only trainId map重建ATO，不建立新配置/重载系统。

真实destroy经现RenderableExtension.destroy关闭CarBehavior、结束pending并撤delay；car与delay均返回由`Destruction`保留的同一`CompletionStage<Void>`，清理异常仍是stage失败，close同步观察该结果。同ID新对象不继承旧上下文。World gateway注册现MainRailEventBus，按`event.world === target`只处理本World的onReverse并在close退订；LIVE平台listener继续不取SIM renderer，但领域onReverse已由target实现覆盖。

`SimulationCarBehaviorExtension(definition, carProfile, effectObserver = null)`与internal模拟factory接受可选`((Car, SimulationBehaviorEffect) -> Unit)?`观察器。正常模拟保持null；只有测试显式传入collector，才在真实动画验证/声音启动时构造通知值。通知发生在原owner/车厢monitor中，不新增调度或生产历史缓存；未知animation/scene仍失败，缺观察器不改变动作计数、延迟及ticket完成。观察器有界同步运行，异常沿现有host失败规则处理，不吞掉失败。测试collector自己持有记录、按exact Car区分同ID实例并负责释放；产品不提供effectSnapshot。

Core真实车厢销毁关闭其扩展；联结/解编保留转移车厢。网关不维护历史`ownedCars`集合，World关闭在真worker退出后仅快照当前train/cars并逐个close，单车失败不跳过后续车厢。已经退休的车厢即使销毁stage失败，也不被网关重新持有或二次观察；原销毁调用者仍收到真实失败。不能用弱引用、GC时序断言或另一个只增集合替代生命周期。

目标创建的operation owner必须纳入IsolatedTimetableSimulation既有资源栈。正常/取消/失败均先确认physics和generic worker真退出，再逆序关operation owner、事件、ticket和Contexts，create/seed/Feature install失败也清理。取消ticket不是worker完成证明，不加watchdog或强制关闭仍运行的Context。关闭只作用于目标实例。

## 继承上游失败边界

`TrainBehaviorScriptException`沿共同CarBehavior的`scriptFailed`出口记录simulation runtime/car/train，在故障发生时读取该car当前exact所属train，并向它提交帧间derail；执行时仍核对exact owner，联结/解编不能使旧train替新train承担故障。脚本加载、声明和调用的guest错误使操作FAILED、目标列车脱轨保留占用，其他World正常推进；普通拒绝/缺动作/scene缺失沿原结果失败。

未知host异常继续采用上游Core `RailwaySimulationHalt`进程级停止政策；本任务不改变它，不能声称故障影响也完全按World隔离。closed实例的metadata在进入共同JS前明确拒绝，不能把正常销毁后调用误当未知host故障。

## 成本与边界

广播/捕获按实际目标车及其模型树线性处理；JS只在调用、声明查询和效果commit执行。Context成本随当前车厢数，默认效果出口不分配或积累观察事件，动作延迟沿现generic tick注册/退休；没有逐tick全World扫描、第二调度循环或新的共享全局锁。第三方脚本永久不退出、同JVM CPU/GC不属于进程级隔离保证，不把正确性测试称性能基准。

## 必需验证

- 同真实脚本多车/两个同源target：persistent globals、声明、ticket、delay和close独立；官方s100门/换向脚本对照，混装方向及已接受效果不随反转重解。
- 默认无观察器仍执行真实成功/拒绝/延迟语义；显式collector只收实际启动。真实destroy后网关仅关闭当前车，真实失败销毁以既有IsolateGlobalHalt隔离用例证明不重读退休stage；一辆活车close失败仍关闭后续车。
- 多个delay动画+声音全部启动才成功；真实拒绝/坏源/缺动作/场景、初始化副作用、取消与迟到完成、car destroy/同ID重建和targetclose。
- 真Core split/link保留exact car状态/原目标，拆出的新train与吸收无controller car可用当前profiles重建/更新真实ATO，非helper伪结构变更。
- HTTP capture→create→Dispatcher门/动作完成gate，与正式SimpleSignals loader/JS/资源/inspection、Core碰撞/事件和两target并发联合；不存在空source或估时替身。
- Basic/Core/SimpleATO/Bukkit/SimpleSignals及受影响模块全测、Java API/JAR边界、Dokka、WebUI bridge前端与docs完整门禁；逐新增@Test核XML而非只看BUILD SUCCESSFUL。

## 持久化与回滚

仅运行态detached输入和目标资源接线；原包JS、actions、SavedCarModelTree、world/extension payload形状和版本不变。无旧开发reader/alias或无依据升版。工作区继承未提交信号候选，最终验证要重跑当前联合源并区分历史指纹，不回退前批/UI/坐标改动。
