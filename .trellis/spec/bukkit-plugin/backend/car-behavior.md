# 每节车厢的 JS 行为与动作生命周期

## 状态、范围与归属

`TARGET`：用户于2026-10-11确认每节车厢维护独立JS状态；本文为实现合同，尚未接入生产。当前`TrainExtension`仍拥有`TrainBehaviorRuntime`、动作计时和延迟效果，`CarExtension`只拥有车辆模型/场景。不要将目标误报为已实现。

BukkitPlugin拥有车辆模型行为；Core只拥有车厢、编组结构及物理事实；SimpleATO拥有时刻表、运营方向和运用元数据。复用现有`CarExtension`及Graal运行时代码，不新增全局车辆行为注册系统，不把运营业务搬入模型JS。本批不新增信号JS接口。

## 一次开门经过哪些步骤

列车操作适配器在simulation owner捕获本次操作的确切车厢对象集合及每车物理门侧，然后分别交给车厢行为。每节车厢使用自己的JS状态调用模型action；动画、声音和延迟效果只针对这节车厢。列车层汇总这次操作的每车结果，不再拥有共享JS全局变量。

动作进行中发生联结/解编，只改变车厢所属编组，保留`CarExtension`、JS执行环境及待执行效果。已接受的开门继续操作原车厢，已打开的门不自动关闭；新加入车厢不加入此前操作。下一条新命令才使用新编组集合与新运营方向。

## 内部表示与执行原则

- 运行时归属确切的车厢实例，不单独按`Car.id`做全世界查找：公开ID目前不保证world-wide唯一。同名重建车厢不得继承旧实例的迟到动作。
- 每车维护独立脚本全局状态，按需初始化。共享不可变脚本源/编译设施不等于共享JS全局对象；不能在每次调用时重建Context，抹掉车厢状态。复用已有runtime；共享Engine、Context隔离形式及成本在实施时验证，不先宣称O(1)或固定内存成本。
- 脚本的模型action和sound绑定当前车厢，不在执行延迟闭包时重新遍历某列`train.cars`。声音也使用车厢位置，不绑定旧列车车头。
- 需要查询所属编组的操作在调用时取得当前关联；编组变化时更新关联，不通过每tick全世界扫描推断，也不能缓存永不过期的旧train。具体内部接口尚未冻结。
- 接受命令时把运营左右换算为每车固定物理左右，包含混合安装方向；延迟执行不得因途中换向/反向接入重新解释门侧。物理侧不由简单的train head/tail布尔值代替。
- 一次列车操作的完成条件固定为本次捕获的每车结果集合；每车结果覆盖该车本次全部已接受效果，不能在首个延迟效果执行后就报告整车完成。完成时点沿用既有动作合同（当前追踪到最晚声明效果的启动时点，不等同于动画播放结束），失败也必须有终态。联结/解编不能扩大集合、丢失完成通知，或把旧完成通知解释为新编组的全部车门已完成。消费者只能据该operation结果更新对应目标，不能重新查当前cars冒充原目标。

## 接口与伪代码

以下是流程伪代码，不是已承诺的Kotlin/JS签名。优先保留现有`door`、`defineAction`、action/sound等行为代码与解析设施；现有列车广播绑定须改为车厢作用域，包声明和调用约定在实现前核对全部consumer。

```text
onTrainDoorCommand(train, requestedSide, opening):    // simulation owner
    targets = captureExactCarsAndPhysicalSides(train, requestedSide)
    operation = aggregateResultsFor(targets)          // 仅这批目标
    for target in targets:
        dispatchToCarOwner(target.car):
            behavior = target.car.extension.ensureBehavior()
            result = behavior.door(target.physicalSide, opening)
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

联结/解编不关闭车厢runtime。车厢真正销毁、world卸载和插件停用才负责结束待执行动作并释放runtime；关闭幂等，迟到任务不能复活实例。模型重建/脚本替换不是联结，单独遵循各自资源生命周期，不能把二者混为同一reset。

## 失败和可见结果

脚本加载/调用失败、声明动作缺失或目标已销毁必须给出明确的目标失败；不能将操作标为成功，也不能让汇总永远等待。已在其他车厢发生的效果不回滚，不承诺跨车厢动作原子性。取消请求或卸载必须有明确终态；具体result形状在适配器实现批次冻结，不能仅删除完成回调。

联结准备失败不得关闭原车厢runtime、清除全局变量或修改待执行动作。联结成功通知异常仍不回滚Core结构；车辆状态延续不能依赖某个可抛错成功listener事后重新创建JS来保证。

## 性能与资源

只在实际行为调用时执行JS，不新增每tick全车脚本轮询。向N节车厢广播为O(N)加各脚本自身成本；固定目标集合和完成计数为O(N)。活跃runtime内存随已初始化车厢数增长；模型包/script source可共享，JS状态不可混用。实现验证要记录初始化次数及联结/解编前后是否重复初始化，不把正确性测试当成内存或耗时基准。

## 具体情形与禁例

两节车正在开门时加入另一节车：原两节继续并完成，第三节不受旧命令影响。开门途中解编且反转一侧运营方向：原物理侧不变，两侧JS变量各自保留，后续新命令才按各自方向计算。已开门车辆联结：模型场景原样保留，不自动重置。

错误做法是销毁列车级JS后重建并取消所有延迟动作，或让延迟闭包读取合并后的`train.cars`；正确做法是车厢拥有runtime，命令固定原目标和物理侧，编组层只汇总。

## 必需回归与验收

- 同一脚本作用于两节车：变量互不影响；连续调用保留各自状态。
- 真实联结/解编前后runtime身份及初始化次数不变，双方车厢状态均保留，不只保留主侧。
- 开门中联结、解编和运营换向：原目标、物理侧、延迟动画/声音及完成结果不变，新加入车厢不被旧命令操作。
- 汇总按原集合恰好结束一次；部分目标失败/销毁可观测，不永远等待，不给新编组作错误的完成确认。
- 销毁、world卸载、迟到region任务和同ID重建不能访问已释放runtime或影响新车；同Context不得并发执行。
- 混合安装方向、跨region逐车效果和不同源模型包均纳入验收。离线测试不能替代Folia/客户端现场验证。

## 迁移与待细化事项

本次只记录合同，不改变包布局、存档或版本。车厢动作应保留各自源包/快照，不能因主编组改变而统一替换成主包；具体脚本解析及保存引用路径须先核对当前模型快照边界。任意JS Context/闭包不作为存档数据；显式可保存脚本状态的协议另行设计，本批不顺带添加。

后续批次先冻结每车调用/完成协议，再迁移动作执行，最后接入完整联结事务；不能先切换旧link而仍销毁或错误绑定车厢状态。Core的collision入口及主编组政策见[train physics](../../javarailways/core/train-physics.md)，不在本页重复定义。
