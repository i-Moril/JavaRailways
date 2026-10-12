# 每节车厢的 JS 行为与动作生命周期

## 状态、范围与归属

`CURRENT`：每车行为纵向迁移已接入生产并通过离线回归及独立检查。`CarExtension`通过内部`CarBehavior`拥有惰性`TrainBehaviorRuntime`及延迟效果，`TrainExtension`只捕获目标并汇总结果。Core的碰撞四端联结事务仍是后续批次；不能据此宣称CR-030或游戏验收完成。

BukkitPlugin拥有车辆模型行为；Core只拥有车厢、编组结构及物理事实；SimpleATO拥有时刻表、运营方向和运用元数据。复用现有`CarExtension`及Graal运行时代码，不新增全局车辆行为注册系统，不把运营业务搬入模型JS。本批不新增信号JS接口。

## 当前前置能力：按指定源包解析

`CURRENT`：`ModelManager.getTrainAction(packageName, name)`、`getTrainActions(packageName)`和`getTrainBehavior(packageName)`在`packageName != null`时只查询该包。包不存在时，三个查询分别返回`null`、空map、`null`；包存在但缺少动作名或脚本时，对应单项查询返回`null`，动作列表只包含该包的声明。不能继续搜索其他包。例如A包没有开门动作而B包有，指定A不会借用B的开门逻辑。

内部直接读取已发布的包索引和动作map，指定包查询平均O(1)，不加载脚本或创建Context，也不引入新缓存/调度。包重载仍沿用现有registry发布边界。本前置不修改存档、版本或JS归属。`packageName == null`仍保留旧全局查询：单动作/脚本搜索各包，动作列表汇总各包；它不是已知源包缺失时的兜底，不能把指定包改为null绕过隔离。

回归需使用两个不同包，断言同名动作命中指定包、仅另一包有的动作/脚本不泄漏、未知包返回不存在，以及null查询行为不变。这项查询隔离与每车运行时迁移是两个独立边界。

## 一次开门经过哪些步骤

列车操作适配器在simulation owner捕获本次操作的确切车厢对象集合及换算门侧所需的每车方向，然后分别交给车厢行为。每节车厢使用自己的JS状态，将语义门侧转换成物理侧并调用模型action；动画、声音和延迟效果只针对这节车厢。列车层汇总这次操作的每车结果，不再拥有共享JS全局变量。

动作进行中发生联结/解编，只改变车厢所属编组，保留`CarExtension`、JS执行环境及待执行效果。已接受的开门继续操作原车厢，已打开的门不自动关闭；新加入车厢不加入此前操作。下一条新命令才使用新编组集合与新运营方向。

## 内部表示与执行原则

- 运行时归属确切的车厢实例，不单独按`Car.id`做全世界查找：公开ID目前不保证world-wide唯一。同名重建车厢不得继承旧实例的迟到动作。
- 每车维护独立脚本全局状态，按需初始化。共享不可变脚本源/编译设施不等于共享JS全局对象；不能在每次调用时重建Context，抹掉车厢状态。复用已有runtime；共享Engine、Context隔离形式及成本在实施时验证，不先宣称O(1)或固定内存成本。
- 脚本的模型action和sound绑定当前车厢，不在执行延迟闭包时重新遍历某列`train.cars`。声音也使用车厢位置，不绑定旧列车车头。
- 需要查询所属编组的操作在调用时取得当前关联；Core已提供`Car.train: Train?`（CURRENT），读取唯一原子owner，构造成功绑定前/退休后为null，结构转移后返回新train。它只允许跨线程身份查询，不授权region遍历live train状态；JS调用所需cars/方向仍由simulation捕获。不能在CarExtension.initialize缓存train，也不能把split准备host当已转移owner。
- 接受命令时把运营左右换算为每车固定物理左右，包含混合安装方向；延迟执行不得因途中换向/反向接入重新解释门侧。物理侧不由简单的train head/tail布尔值代替。
- 一次列车操作的完成条件固定为本次捕获的每车结果集合；每车结果覆盖该车本次全部已接受效果，不能在首个延迟效果执行后就报告整车完成。完成时点沿用既有动作合同（当前追踪到最晚声明效果的启动时点，不等同于动画播放结束），失败也必须有终态。联结/解编不能扩大集合、丢失完成通知，或把旧完成通知解释为新编组的全部车门已完成。消费者只能据该operation结果更新对应目标，不能重新查当前cars冒充原目标。

## 接口与伪代码

列车层的`executeDoorOperation(side, opening)`、`executeModelAction(name)`、`callBehavior(method, args)`及`applyAnimation(name, scene)`返回`CompletableFuture<Unit>`。调用入口要求simulation owner；future成功表示本次所有目标的已接受效果已到达执行启动点，不表示动画播放结束。控制面板和命令必须等待实际结果，再调度到玩家owner反馈，不能把排队成功作为动作完成。

`loadBehaviorActions()`逐车查询并返回声明并集的future；JS与YAML同名时使用该车JS声明。同名声明的`requiresControl`取OR，`executable`取AND。自定义动作在任何执行副作用前完成全部目标的声明查询及权限检查，再固定支持者集合；菜单本身不作为执行授权。

以下是流程伪代码；现有JS的`door`、`defineAction`、action/sound名称不变。

```text
onTrainDoorCommand(train, requestedSide, opening):    // simulation owner
    targets = captureExactCarsAndRelativeControlEnds(train)
    operation = aggregateResultsFor(targets)          // 仅这批目标
    for target in targets:
        dispatchToCarOwner(target.car):
            behavior = target.car.extension.ensureBehavior()
            result = behavior.door(requestedSide, opening, target.relativeTail)
            attachResult(operation, target, result)

onConsistStructureCommitted(changedCars):
    updateCurrentConsistAssociation(changedCars)
    // 不关闭JS、不重建模型、不取消已接受动作、不重算其门侧

onDelayedEffect(carInstance, capturedPhysicalEffect):
    dispatchToCurrentCarOwner(carInstance):
        verifySameLiveCarAndBehavior()
        applyEffectOnlyToThisCar(capturedPhysicalEffect)
        recordEffectResult()
        completeOriginalTargetOnceWhenAllAcceptedEffectsAreTerminal()
```

## 生命周期与线程

每车Context同一时间只允许一个owner执行；列车广播跨Folia区域时逐车调度，不能以车头region拥有全部车厢。generic tick只推进计时，模型/声音效果仍由目标location/entity owner执行。所属编组读取在simulation边界捕获，不从region任意遍历live Core列车集合。复用已有dispatcher和线程guard，不引入第二套调度器。

同一`CarExtension`的monitor串行化Context初始化、声明查询、调用、延迟效果和关闭。每次调用捕获的相对方向为`(controlEnd == TAIL) xor (car.isReversed xor train.isReversed)`；先绑定该方向再执行脚本，包括首次顶层初始化，调用退出时恢复。JS接收语义门侧，由现有脚本按`operatingEnd()`转换一次；无脚本的YAML门动作才由宿主转换。不能两侧都翻转。

用户于2026-10-12确认初始化只声明状态和函数，不得触发模型效果。`CarBehavior`在Context构造、首次声明扫描以及后续`listActions()`刷新期间设置只读效果标记；这期间调用`action()`或`sound()`明确抛错，不排动画、声音或延迟任务。实际`door`、已声明动作及普通行为函数调用才收集效果。声明查询每次刷新，保留脚本运行中动态`defineAction`的可见性，不重建Context或永久缓存旧声明。

每次调用先建立效果计数，再执行脚本；每项动画/声音在接受时增加待完成数，调用结束后封闭集合。只有集合已封闭且待完成数为零才成功；失败、取消和卸载不能留下永远等待的ticket。延迟项复用generic tick计时与location affinity提交，在执行后注销，不新增每车JS tick。world同时追踪尚未完成的列车future，卸载时可直接使其失败，不依赖已经不会执行的region任务回调。

列车汇总还要同时等待车厢入口的接受结果和效果完成回调：同步效果可能先通知完成，随后入口才返回拒绝，不能只凭先到的回调报告成功。原始动画命令也通过`CarExtension.executeAnimation`取得同一monitor并验证关闭状态，不能绕过行为入口修改已销毁实例。

联结/解编不关闭车厢runtime。车厢真正销毁、world卸载和插件停用才负责结束待执行动作并释放runtime；关闭幂等，迟到任务不能复活实例。模型重建/脚本替换不是联结，单独遵循各自资源生命周期，不能把二者混为同一reset。

## 失败和可见结果

用户于2026-10-12确认自定义动作菜单采用各车声明的并集。同名动作分别使用支持该动作的车厢自己的实现，未声明该动作的车厢不属于此次自定义动作目标；没有任何支持者时明确失败。已选支持者执行失败仍使操作失败，不能冒充全成功。开关门等整列语义操作仍捕获全部目标，不能借并集规则静默略过缺失或失败车厢。菜单查询与执行捕获分开：最终目标以接受命令时的确切车厢与声明为准，不复用旧编组菜单作为授权。

脚本加载/调用失败、声明动作缺失或目标已销毁必须给出明确的目标失败；列车future异常完成，gateway将pending ticket转为FAILED。初始化失败关闭新建Context，声明刷新失败向查询方报告，不冒充正常空菜单。已在其他车厢发生的效果不回滚，不承诺跨车厢动作原子性。取消ticket只允许PENDING转为CANCELLED；未派发目标再次校验pending，已接受效果可继续，迟到完成不得将取消改成成功。卸载直接使world追踪的future失败，不能仅删除完成回调。

联结准备失败不得关闭原车厢runtime、清除全局变量或修改待执行动作。联结成功通知异常仍不回滚Core结构；车辆状态延续不能依赖某个可抛错成功listener事后重新创建JS来保证。

## 性能与资源

只在实际行为调用或动作声明查询时执行JS，不新增每tick全车脚本轮询。向N节车厢广播为O(N)加各脚本自身成本；固定目标集合和完成计数为O(N)。活跃runtime内存随已初始化车厢数增长；模型包/script source可共享，JS状态不可混用。实现验证要记录初始化次数及联结/解编前后是否重复初始化，不把正确性测试当成内存或耗时基准。

## 具体情形与禁例

两节车正在开门时加入另一节车：原两节继续并完成，第三节不受旧命令影响。开门途中解编且反转一侧运营方向：原物理侧不变，两侧JS变量各自保留，后续新命令才按各自方向计算。已开门车辆联结：模型场景原样保留，不自动重置。

错误做法是销毁列车级JS后重建并取消所有延迟动作，或让延迟闭包读取合并后的`train.cars`；正确做法是车厢拥有runtime，命令固定原目标和物理侧，编组层只汇总。

## 必需回归与验收

- 同一脚本作用于两节车：变量互不影响；连续调用保留各自状态。
- 顶层和声明扫描触发action/sound必须失败且无效果；运行中动态注册的新动作后续可见、可执行；源脚本替换重置Context，但已接受的旧延迟效果及原结果不变。
- 真实联结/解编前后runtime身份及初始化次数不变，双方车厢状态均保留，不只保留主侧。
- 开门中联结、解编和运营换向：原目标、物理侧、延迟动画/声音及完成结果不变，新加入车厢不被旧命令操作。
- 汇总按原集合恰好结束一次；部分目标失败/销毁可观测，不永远等待，不给新编组作错误的完成确认。
- 销毁、world卸载、迟到region任务和同ID重建不能访问已释放runtime或影响新车；同Context不得并发执行。
- 混合安装方向、跨region逐车效果和不同源模型包均纳入验收。离线测试不能替代Folia/客户端现场验证。

## 迁移与待细化事项

包布局与JS声明不变。`SavedCarRenderState.packageSnapshotHash: String? = null`引用既有内容寻址的`SavedTrainPackageSnapshot`，复用`TrainPackageSnapshotStore`保存该车来源包的actions/behavior；原`modelSnapshotHash`只负责模型树，不能代替行为快照。SNAPSHOT模式逐车固定来源，缺失已引用快照明确失败；FOLLOW_PACKAGE模式沿用资源重载，源脚本改变时才替换Context。没有主编组包回退、新快照store或格式升版。列车级控制profile仍沿用原策略，不随本批改变。

真实开发服样本的六个world均没有列车，不存在需要转换的既存每车记录；这不代表旧outer/曲线数据已完成迁移。非空车辆快照由固定fixture验证。任意JS Context/闭包不作为存档数据；显式可保存脚本状态的协议另行设计，本批不顺带添加。

每车调用、动作执行和完成协议已完成本批离线验证；下一批接入完整四端联结事务，补联结/解编前后双方状态持续性的集成验证。不能把旧link调用成功或独立helper测试当成完整联结正确性；真实CarExtension的SNAPSHOT/FOLLOW_PACKAGE解析链、完整方向捕获与Folia跨region仍须集成/游戏验证。Core的collision入口及主编组政策见[train physics](../../javarailways/core/train-physics.md)，不在本页重复定义。
