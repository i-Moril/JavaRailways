# Basic 列车纵向动力学契约

## 1. 状态、范围、触发条件与 Owner

- 状态：`CURRENT`。
- 触发：修改牵引/制动/阻力/坡度公式、手柄执行、人工驾驶、ATO actuator、重力注入或制动包络。
- Owner：`Basic`拥有平台无关的纵向动力学和手柄执行；`Core`只发布列车几何与物理运行方向；`SimpleATO`拥有目标速度规划；`BukkitPlugin`只解析配置和装配profile。
- 非目标：负速度倒车、轮滑、天气黏着、再生能量核算、连续轨迹优化器。

## 2. 模块工作方式

正常实时流程是：Bukkit从当前有效模型解析`TrainProfile`和逐车`CarProfile`，注入
`physics.yml`的`track.gravity`，然后构造Basic controller/actuator。人工控制把玩家选择的
P/N/B手柄交给`TrainDynamicsExecutor`；SimpleATO先归约许可速度，再由`HandleTrainActuator`选择一个配置中
存在的手柄。两者最终都只调用`TrainDynamics.step`，再把同一个`StepResult`的距离和post-step速度通过
`TrainController.moveControlledTrain`交给Core；不得绕回公开的distance-only `Train.moveTrainForward`而丢失本epoch速度。

隔离时刻表模拟在capture时冻结同一profile和重力，克隆世界中不得回查Bukkit renderer或live service。
详细行程预览也调用同一纯函数，但其“最大牵引/最大常用制动”驾驶策略仍是独立的粗略策略。

## 3. 内部机制与关键算法

`TrainDynamics.step`使用逐车设备状态：指令延迟 → 电制动力建立/退出及低速衰减 → 空气压力补足制动需求 →
牵引缓解联锁及牵引力建立/退出 → 黏着限制 → Davis阻力 → 逐车有符号坡度力 → jerk限制 → 梯形距离积分。
空气压力的建立/缓解沿用列车级`brakeApplyTime`/`brakeReleaseTime`，其他设备参数由逐车`Equipment`提供。
电制动受最大力、功率和低速衰减约束；空气制动承担目标制动力与当前电制动力的差额。压力或残余电制动力
未低于联锁阈值时不得建立牵引力。停止初始化持有空气制动，起步必须经历真实缓解过程。

公开`State.devices`是独立拥有存储的稳定快照，不能引用内部预演工作区。executor为每个当前编组绑定一个
逐车数组工作区；同一预演会话冻结profile、坡度与初始状态，各候选从同一初始状态试算并复用该工作区。借用的内部结果不得逃逸为公开`State`或`StepResult`，实际提交仍创建独立快照。

坡度输入是`sin(theta)`：正值表示当前物理运行方向上坡。实时输入必须读取
`Car.operatingDirection.y()`，禁止读取模型朝向`Car.direction.y()`。

executor构造时把当前每个实际`Car`实例与输入的`CarProfile`配对，保存为身份哈希表。
每次计算遍历当前`train.cars`，按实例取固有参数，再读取该车此刻的坡度；不保存“第几节车”的参数索引或坡度缓存。
例如20吨车和10吨车交换位置后，各自质量仍跟随原实例，坡度则来自各自当前线路位置。
公开`carProfiles`也按当前车厢顺序投影，供制动包络使用，已移出当前编组的车不再参与该列表。

结构准备为新车厢列表生成一张新身份表：优先复用本执行器绑定，再查询对方的`DynamicsProfileSource`，
最后仅为缺项调用composition注入的只读`profileResolver`。任何缺项都明确失败，不借同ID或默认参数。
表通过Core的prepared state update随结构安装；执行器身份、已选手柄及历史`lastResult`保留，
运动状态沿用，设备快照按新车厢实例顺序投影，不要求结构变化后的`State`对象身份相同。
资源重载可调用`RealisticManualController.prepareConfigurationChange`和`HandleTrainActuator.prepareConfigurationChange`，
返回Core拥有的prepared assignment tokens。准备替换immutable train profile、逐实例car profile与ATO手柄表，
不修改executor `State`/`lastResult`、已选手柄或controller身份；当前手柄不被新配置支持时拒绝准备。
安装由Core在排除worker后执行，不能通过重建controller丢失驾驶或ATO状态。

这些Basic capability只供第一方controller/actuator协作，不是另一个公开插件注册接口。

`HandleTrainActuator`只考虑配置中的正作用P档、N和非EB制动档，并每Tick向目标档移动一级。正常停车上限是
最强非EB制动；只有目标为零且最强常用制动预演不能减速时才允许EB。每个候选只预演一次当前Tick，预演不得
修改executor state。

坡度制动包络把已扫描路线切成有限`BrakingGradeSegment`，用可保证的常用空气制动力下界和
`a=(F_brake + m*g*grade)/m_effective`计算每段保证减速度；有利的Davis阻力不计入保证值。包络预先线性累计
`2*a*length`，距离查询二分定位后计算`v_in²=v_target²+work`。进入无法保证正减速度的分段前返回0。
电空补偿可能滞后，保证值不计电制动，也不能直接使用额定空气制动力；逐车下界为
`max(0, brakeMaxForce * serviceScale - electricBrakeMaxForce)`，再施加制动黏着上限。
该下界不代表实际制动力，实际执行始终使用完整逐车设备状态。

### 实际候选与可变物理步长

规划包络从当前真实设备状态预测立即进入最强常用制动的备份。执行前再对实际选择的下一档验收：
同一个`ControlDemand`携带SimpleATO现有空间约束的不可变目标（目标距离、目标速度及显式反应时间），
不复制route/resource策略，也不把未选中的更强P档当作当前必然动作。

`ServiceBrakingResponse`先用实际本次delta试算一个候选，再解析上界未来逐级常用制动过程：逐车残余牵引
上界、空气制动力下界、最坏坡度、指令与档位行程延迟和jerk消退。加速度峰值同时受真实牵引jerk上升斜率与单调力上界限制，不能把残余牵引瞬间换算成可达加速度；
再按制动jerk求消退上界。数值积分使用单调上界的左端矩形，
不是假定未来固定50ms或200ms的动力学滚动；最终速度截断的离散距离另作保守预留。
未来步长上限来自`World.maximumPhysicsDeltaSeconds`，保留Core原wall-clock/clamp/drop语义。
当前静止且设备无法产生位移时，不凭空制造牵引力或固定停车死区。

响应覆盖的空间从路线制动work起点扣除，不能把已经经过的有利上坡work移到之后使用；显式反应时间只补足
响应未覆盖的部分。实际Handle对整列车使用当前逐车与已观察前方坡度中的保守下界，避免车头已上坡而车尾
仍在下坡时乐观估计。无正保证减速度或有界证明预算内不能确认安全时，不继续加牵引，进入真实逐级制动。

零速度目标要求能在该目标前制停；非零限速目标允许以不超过目标速度通过。重联使用已验证接触准入的速度
目标和Core接触距离clamp，不能把普通车站的零速终点语义直接套到接触点，独立红灯/授权约束仍生效。
不改写动力学输出速度或添加固定提前停车距离来掩盖无法兑现的目标。

## 4. 接口、数据结构与单位

```kotlin
fun TrainDynamics.step(
    state: TrainDynamics.State,
    config: TrainDynamics.TrainProfile,
    cars: List<TrainDynamics.CarInput>,
    handle: String,
    gravityMetersPerSecondSquared: Double,
    deltaSeconds: Double
): TrainDynamics.StepResult

class TrainDynamicsExecutor(
    train: Train,
    trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> TrainDynamics.CarProfile?)? = null
)

data class BrakingGradeSegment(val lengthMeters: Double, val grade: Double)

// GradeAwareBrakingEnvelope的companion object入口：
fun fromDynamics(trainProfile: TrainDynamics.TrainProfile,
    carProfiles: List<TrainDynamics.CarProfile>, gravityMetersPerSecondSquared: Double,
    segments: List<BrakingGradeSegment>,
    currentAccelerationMetersPerSecondSquared: Double = 0.0): GradeAwareBrakingEnvelope
// 包络查询，距离m、速度m/s、时间s：
fun permittedSpeedMetersPerSecond(targetSpeedMetersPerSecond: Double, distanceMeters: Double,
    currentSpeedMetersPerSecond: Double = 0.0, reactionTimeSeconds: Double = 0.0): Double
```

速度为m/s、加速度与重力为m/s²、距离为m、质量为kg、力为N、时间为s。`grade`是无量纲正弦值。
`TrainDynamicsExecutor.preview`不提交状态，`step`提交`State`和`lastResult`。

`TrainActuator.brakingEnvelope(segments, deltaTimeSeconds, maximumControlDeltaSeconds)`显式接收真实步长与
上限。`BrakingConstraints`与`BrakingLimit`只携带运行时不可变值；SimpleATO规划和候选验收共用扣除原显式
空间余量后的目标。`serviceBrakeReactionTimeSeconds`仍提供工厂和粗略预览的保守反应参数；真实Handle的
设备响应证明独立使用所属世界的真实最大步长，不依赖该函数默认的名义周期。

## 5. 不变量、生命周期、线程与版本

- 构造时profile数量与当前`train.cars`数量完全一致，并按当时顺序建立实例绑定；后续计算不依赖该旧顺序。
- 绑定按对象身份，不按`Car.id`或`equals`；另一个同ID实例不能继承旧车参数。当前车厢缺少绑定时明确失败，不猜测默认参数。
- executor/controller/actuator是单列车、单physics worker拥有的有状态对象；固有profile单项和重力不可变，身份表只在排除worker的结构边界替换，动态坡度每次读取。
- `RealisticManualController`提交的distance与speed必须来自同一个dynamics step；Core在同一epoch据此发布car/bogie速度。
- 换向只改变`operatingDirection`和列车head/tail语义，不改变`Car.direction`模型朝向。
- 人工`setDirection(direction): CompletableFuture<Unit>`只提交帧间请求，执行时验证当前train/controller确切身份；
  同步`applyDirection(direction)`仅供已进入帧间阶段的整项接管调用。需要反向时先完成Core换向，再更新有效方向及显示符号；
  拒绝、旧controller或shutdown失败不提前改变方向字段。连续R/N/F按FIFO处理，N不改变上次有效运行方向。
  请求Future完成表示该项操作执行结束，不允许队列owner等待自身待执行请求；物理tick不新增换向分支。
- 重力只来自composition注入；Basic不得读取YAML或Bukkit singleton。
- `track.gravity`是schema 2的必填正数字段；它由旧`track.gravity-mps2`破坏性重命名而来，不保留runtime兼容读取。
- SimpleATO test source中的`KinematicTrainActuator`只可作为无真实车辆profile的纯规划/约束测试替身，production和
  isolated composition不得构造它；Basic main source不拥有该ATO测试策略。

配置 owner 必须在解析/反序列化边界拒绝非有限与越界固有参数：质量、转动质量系数和有效质量为正，
最大速度、力/功率、档位比例、制动建立/释放时间、jerk、黏着比例/系数和Davis系数非负。
零功率保持恒力模式，零jerk表示不限制jerk，零建立/释放时间表示立即响应。
动力学与制动包络使用真实有效质量，不施加1kg下限；正数小质量仍按其原值计算，非法质量明确失败。
牵引/制动黏着饱和、速度物理范围和坡度数值容差仍保留。

## 6. 校验与错误矩阵

| 条件 | 结果 |
| --- | --- |
| 重力非有限或`<=0` | 构造/step立即`require`失败，不移动 |
| 车辆profile数量不匹配 | executor构造失败 |
| 当前车厢实例无profile绑定 | 查询/计算抛`IllegalStateException`；失败的preview/step不提交state或lastResult |
| 速度或Tick时间非法 | actuator拒绝，不提交state |
| 没有非EB常用制动档 | 制动能力派生失败，controller不安装 |
| 包络某段保证减速度`<=0` | 对进入该段的目标fail-closed返回0许可速度 |
| ATO运行时动力学异常 | 走既有world Stall并freeze |
| no mission | SimpleATO仍输出零需求，速度上限不构成行车授权 |

## 7. 性能、复杂度与退化行为

单次动力学step为`O(cars)`，配置手柄候选是小型有界集合。内部候选逐车数组复用，公开已提交快照独立拥有存储。
路线坡度切片最多256段；累计制动work构造为`O(route samples)`，每个空间目标查询为`O(log segments)`。
逐车响应上界积分有明确时间预算，超预算返回未证明，不放宽限制；该成本不同于旧的单步标量模型，必须用
实际生产执行器单独测量，不能借用独立原型的数组优化百分比。不得对每个候选重新扫描物理路线。

实例绑定构造与存储为`O(initial cars)`，单次查表平均`O(1)`；当前参数列表投影和一次动力学输入构造均为`O(current cars)`。
结构准备为`O(result cars)`加缺项来源解析成本，安装为引用替换；不在每tick读取模型包或文件。
这是参数身份关联，不是对动态线路条件做缓存；未增加曲线阻力模型。
实际生产执行器的独立测量入口为`ai_tasks/experiments/production-emu-performance/`，排除世界调度、几何移动、
寻路扫描、显示和网络；不能直接据此推算服务器容量。

## 8. Good / Base / Bad Cases

- Good：换向后模型外观不翻转，但同一坡道的`gradeForce`符号翻转。
- Base：平坡相同profile、手柄、重力下，人工和ATO executor得到完全相同的下一状态、距离和力诊断。
- Bad：SimpleATO直接按固定加速度改速度，或Bukkit controller自行复制一套`TrainDynamics`公式。
- Bad：用`Car.direction`计算坡度，导致反向运行仍按旧方向受重力。
- Bad：只用稳定最大制动距离，却遗漏手柄换档、制动建立和jerk反应时间。

## 9. 必需测试与断言点

- 重力变化：坡度力和黏着上限随重力变化；无黏着截断的平坡牵引与Davis阻力不变。
- 上/平/下坡：惰行加速度和制动允许速度顺序正确。
- 换向：`Car.direction`不变、`operatingDirection`反向，executor的坡度力符号翻转。
- executor一致性：相同step前状态下，人工/ATO包装结果等于直接`TrainDynamics.step`。
- 实例绑定：不同质量/制动力的车辆换序后参数不串车；只保留一车时参数投影和力计算排除移出车，改变该车坡度后重新读取。
- 未知实例：相同car ID的新对象查询/preview/step失败，原state和lastResult身份保持；此单元边界不替代真实联结/解编测试。
- manual publication：真实50 ms physics epoch的head位移、train frame speed及全部car/bogie frame velocity与同一次dynamics step一致。
- actuator：逐级P/N/B、最弱安全档、EB边界、preview不提交状态、反应时间覆盖换档/apply/jerk。
- 包络：分段边界、扫描截断、无正减速度fail-closed和256段上限。
- 正加速度消退：非零坡度下的解析位移/速度增量、非正加速度/禁用jerk的原行为、距离耗尽返回0，以及额外扣除不能绕过原不可制停坡段门控。
- composition：生产和隔离均无`KinematicTrainActuator`，捕获后不访问live renderer/Bukkit。

## 10. 迁移、未决事项与 Wrong / Correct

迁移已删除Bukkit中的`RealisticManualController`实现，不保留runtime兼容alias。`physics.yml`从schema 1升级到2时，
使用`scripts/migrate_physics_config.py`先dry-run审查，再用`--apply`在保留`.bak`恢复点后把旧重力键单向改名。
本批接入解编保留侧的参数准备/安装、逐车来源解析及ATO安全参数重算；分离侧沿用新train不自动继承controller且速度归零的合同，
以后安装新controller时由该侧车厢来源装配并初始化静止持有空气制动，不跨controllerless间隔保存完整设备历史。联结参数准备调用点已写入CR-030草稿，
但碰撞入口和接触位置处理仍未完成，不能宣称完整联结可用。
不得用成功事件后的临时补齐或同ID回退冒充结构参数事务，也不能仅凭列表投影正确就宣称ATO安全参数同步。
未来是否把共享ATO primitive从Basic进一步拆分仍独立待定，不为此预先增加抽象层。

### Wrong

```kotlin
val grade = car.direction.y()
val gravity = 9.80665
```

### Correct

```kotlin
val grade = car.operatingDirection.y()
val result = TrainDynamics.step(
    state, profile, cars, handle, gravityMetersPerSecondSquared, deltaSeconds
)
```
