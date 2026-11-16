# Basic 列车纵向动力学契约

## 1. 状态、范围、触发条件与 Owner

- 状态：`CURRENT`。
- 触发：修改牵引/制动/阻力/坡度公式、手柄执行、人工驾驶、ATO actuator、重力注入或制动包络。
- Owner：`Basic`拥有平台无关的纵向动力学和手柄执行；`Core`只发布列车几何与物理运行方向；`SimpleATO`拥有目标速度规划；`BukkitPlugin`只解析配置和装配profile。
- 非目标：负速度倒车、轮滑、天气黏着、再生能量核算、连续轨迹优化器。

## 2. 模块工作方式

正常实时流程是：Bukkit从当前有效模型解析`TrainDynamicsProfile`和逐车`CarDynamicsProfile`，注入
`physics.yml`的`track.gravity`，然后构造Basic controller/actuator。人工控制把玩家选择的
P/N/B手柄映射为数字指令交给`TrainDynamicsExecutor`；SimpleATO策略和监督器决定最终数字指令，
`HandleTrainActuator`仅执行。两者最终都只调用`TrainDynamics.step`，再把同一个`StepResult`的距离和post-step速度通过
`TrainController.moveControlledTrain`交给Core；不得绕回公开的distance-only `Train.moveTrainForward`而丢失本epoch速度。

隔离时刻表模拟在capture时冻结同一profile和重力，克隆世界中不得回查Bukkit renderer或live service。
详细行程预览使用同一逐车物理函数；策略与ATP判断由SimpleATO负责，不能在Basic复制一套驾驶策略。

## 3. 内部机制与关键算法

`TrainDynamics.step`使用逐车设备状态：指令延迟 → 电制动力建立/退出及低速衰减 → 空气压力补足制动需求 →
牵引缓解联锁及牵引力建立/退出 → 黏着限制 → Davis阻力 → 逐车有符号坡度力 → jerk限制 → 梯形距离积分。
空气压力的建立/缓解沿用列车级`brakeApplyTime`/`brakeReleaseTime`，其他设备参数由逐车`Equipment`提供。
电制动受最大力、功率和低速衰减约束；空气制动承担目标制动力与当前电制动力的差额。压力或残余电制动力
未低于联锁阈值时不得建立牵引力。停止初始化持有空气制动，起步必须经历真实缓解过程。

公开`State.devices`是独立拥有存储的稳定快照，不暴露可变数组。
`basic.api.ato.VehicleDeviceState`包含压力、实际电制动力、实际牵引力、指令年龄及上次独立牵引/制动/EB身份，
只有有效数字指令改变才重置延迟，诊断handle变化不能重启设备延迟。
实际kernel与`VehicleDeviceDynamics.advanceDeviceState`共用同一个内联设备代数；后者只更新已知当前区间设备，
不积分运动、不循环未来tick。旧PreviewSession限离线或物理测试，不得进入生产ATO热路径；旧`ServiceBrakingResponse`只保留在test source作为离线物理对照。

`VehicleDeviceState`、`VehicleDeviceEquipment`与`VehicleDeviceDynamics`位于`basic.api.ato`，是第一方模块共用的设备契约，
不承诺独立第三方插件的长期二进制兼容。设备状态是无world引用的不可变值；`VehicleControlState.devices`与同次捕获的
`VehicleControlCapabilities.deviceCapabilities`按相同实际车厢顺序对应。状态字段、默认值和既有校验位置保持原语义，
设备历史仍由Basic执行器按精确Car身份维护，不因值类型公开而转移所有权。
纯设备计算保留原输入校验；有效零时长调用返回原状态实例，失败不修改输入或发布历史。
实际kernel调用的内联数组写入入口仍为Basic模块内部能力；SimpleATO只消费设备值与既有纯计算入口。

坡度输入是`sin(theta)`：正值表示当前物理运行方向上坡。实时输入必须读取
`Car.operatingDirection.y()`，禁止读取模型朝向`Car.direction.y()`。

`basic.api.ato.RailGradeBounds.minimumGrade(curve)`查询整条不可变有向`PrimeCurve`的最不利坡度，
不是调用方路线片段的局部下界。算法扫描原切线端点与相邻切线归一化插值的内部极值，
结果向下舍入并限制于[-1,1]；精确零保留0，避免落入下坡查表格。
同步`WeakHashMap<PrimeCurve, Double>`按原曲线key语义缓存，首次为O(curve samples)，命中为O(1)，
反向curve使用自身的有向切线；缺失/非法切线仍沿原校验失败，不假设平坡。

`RailGradeBounds.mostAdverseOccupiedGrade(train)`在调用时读取一次当前published frame，
按有序tail-to-head `bodyTraversal`的rail/facing查询整条curve。无frame、train ID不符、脱轨、空覆盖或缺几何
返回NaN，保持“安全证据不可用”的语义。该读取时机独立于上层先前捕获的感知帧，不提前合并采样。
查询不发布状态或创建world生命周期；调用方在既有physics worker内保证世界读取时序。
SimpleATO按现有片段顺序折叠这些下界与当前grade；10m/最多256段的局部制动切片及其采样策略仍由SimpleATO拥有，
不能用全曲线下界替换后宣称只是接口调整。

executor构造时把当前每个实际`Car`实例与输入的`CarDynamicsProfile`配对，保存为身份哈希表。
每次计算遍历当前`train.cars`，按实例取固有参数，再读取该车此刻的坡度；不保存“第几节车”的参数索引或坡度缓存。
例如20吨车和10吨车交换位置后，各自质量仍跟随原实例，坡度则来自各自当前线路位置。
公开`carProfiles`也按当前车厢顺序投影，供制动包络使用，已移出当前编组的车不再参与该列表。

结构准备为新车厢列表生成一张新身份表：优先复用本执行器绑定，再查询对方的`DynamicsProfileSource`，
最后仅为缺项调用composition注入的只读`profileResolver`。任何缺项都明确失败，不借同ID或默认参数。
表通过Core的prepared state update随结构安装；执行器身份、已选手柄及历史`lastResult`保留，
运动状态沿用，设备快照按新车厢实例顺序投影，不要求结构变化后的`State`对象身份相同。
资源重载可调用`RealisticManualController.prepareConfigurationChange`和`HandleTrainActuator.prepareConfigurationChange`，
人工控制器返回Core拥有的prepared assignment tokens；自动执行器通过下述配置准备结果返回同类tokens与制动值。
准备替换immutable train profile、逐实例car profile与ATO手柄表，
不修改executor `State`/`lastResult`、已选手柄或controller身份；当前手柄不被新配置支持时拒绝准备。
安装由Core在排除worker后执行，不能通过重建controller丢失驾驶或ATO状态。

`basic.api.ato`的`TrainDynamicsProfile`与`CarDynamicsProfile`定义第一方配置参数契约，保留原字段、
默认值与函数；它们是调用方提供的只读参数视图，接口不复制实现或其档位列表，调用方须在使用期间保持稳定。
`CarDynamicsProfile.equipment`只以已有`VehicleDeviceEquipment`表达；原`TrainDynamics.Equipment`配置模型、
serializer、字段和默认值保持原位，默认参数仍复用同一个共享设备实例，不改变配置或存档格式。

`DynamicsProfileSource`和可选的`ConsistDynamicsActuator`也归`basic.api.ato`。来源只按确切Car实例查询，
null表示该实例没有绑定；不按ID匹配、不转移状态所有权。Basic内部仍准备参数表并计算制动能力，
`PreparedDynamicsChange`向ATO交付Core拥有的待安装更新、常用制动减速度及反应时间，原正值/有限性校验保持不变。
无此能力的actuator维持无编组更新语义，不因为接口整理变成强制能力。

配置准备使用独立的可选`ConfigurationDynamicsActuator`能力及`PreparedConfigurationDynamicsChange`结果。
`HandleTrainActuator`先按原顺序准备实例绑定和手柄表，再以相同函数计算新配置的减速度与反应时间；
结果本身不添加数值校验，ATO仍在原来的`AtoSafetyProfile.copy`位置校验。两种准备结果均不安装任何状态，
也不将配置参数表交给SimpleATO重新派生。ATO只保留非制动安全覆盖值、追加自己的安全profile更新，
由Core在物理帧间完成安装；失败时整批候选不安装，更新token顺序保持原样。

这些Basic capability只供第一方controller/actuator协作，不承诺独立第三方插件的长期二进制兼容，
不是另一个公开插件注册接口。参数、来源、准备结果均不新增持久化格式或独立world生命周期；
准备工作为O(cars)加原有制动派生成本，安装保持Core既有引用替换，不进入每tick配置解析。

`HandleTrainActuator`执行`VehicleControlCommand`，不选择档位或进行候选试算。
`TrainDynamics.step`保留人工字符串映射便利入口，最终进入同一个数字执行路径。
比例可以超过1，但牵引不得超过配置最大正牵引比例；EB强制选择配置EB比例并请求切除牵引。
所有设备联锁和实际退出速率仍有效，不能把请求误当实际设备状态。

坡度制动包络把已扫描路线切成有限`BrakingGradeSegment`，用可保证的常用空气制动力下界和
`a=(F_brake + m*g*grade)/m_effective`计算每段保证减速度；有利的Davis阻力不计入保证值。包络预先线性累计
`2*a*length`，距离查询二分定位后计算`v_in²=v_target²+work`。进入无法保证正减速度的分段前返回0。
电空补偿可能滞后，保证值不计电制动，也不能直接使用额定空气制动力；逐车下界为
`max(0, brakeMaxForce * serviceScale - electricBrakeMaxForce)`，再施加制动黏着上限。
该下界不代表实际制动力，实际执行始终使用完整逐车设备状态。

### 持续混合制动保证与当前设备状态

旧规划work包络保持`max(B*r-Emax,0)`空气下界，不代表新ATP能力，也不参与数字指令选择。
`VehicleControlCapabilityModel`的持续常用制动能力使用唯一helper
`VehicleDeviceDynamics.guaranteedSustainedServiceForceNewtons`：令F=B*r、Ra=B/airApplyTime、
Re为电制动释放速率，k=min(1,Ra/Re)、L=F-(1-k)*min(Emax,F)，最终按逐车黏着截断。
它是恒定普通制动指令delay结束且建立不变量`A+k*min(E,F)>=L`之后的持续保证，**不是当前瞬时制动力**。
电制动释放每步至多Re*h，而空气可补足k*Re*h；空气释放时A_new>=F-E_new，同样保持不变量。
建立前、命令切换或重载后实际电制动力高于新配置Emax时，调用者须单独计算响应阶段，不能直接信用L。
EB等待残余牵引/电制动释放及空气建立，不能把全EB空气能力直接套到当前帧。

控制能力按immutable配置和精确编组准备/惰性构造，复用速度性能样本与设备系数。
全速度牵引上界优先使用`CarDynamicsProfile.maximumMotorForceUpperBound`的声明证明，再按最大牵引比例和黏着限制；
未知曲线使用黏着上界，不用速度采样推断全区间上界。样本和各档净加速度只供名义操作参考。

`BasicWorldData.trainDynamicsHistory`在成功step保存精确Train/Car的设备状态与最后指令，使用弱身份关联避免保活删除实体。
替换控制器和复用原ATO每次活动查询/执行都检查最新历史引用，自己发布的同一快照无需反复适配。
真实step还更新world-local弱精确Car设备索引，使controllerless split新Train无需旧controller即可继承压力/电力/指令年龄；
初始化优先显式source、再查单车历史，活动owner不重复采用单车历史覆盖状态。
构造和候选准备只读；设备按确切车厢重排，不能按ID或位置索引串车。公开强制静止只清除残余运动学状态，
保留真实压力/电制动力/牵引状态，不能凭空补满空气制动。

## 4. 接口、数据结构与单位

```kotlin
fun TrainDynamics.step(
    state: TrainDynamics.State,
    config: TrainDynamicsProfile,
    cars: List<TrainDynamics.CarInput>,
    command: VehicleControlCommand,
    gravityMetersPerSecondSquared: Double,
    deltaSeconds: Double
): TrainDynamics.StepResult

class TrainDynamicsExecutor(
    train: Train,
    trainProfile: TrainDynamicsProfile,
    carProfiles: List<CarDynamicsProfile>,
    gravityMetersPerSecondSquared: Double,
    initialSpeedMetersPerSecond: Double = train.speed,
    profileResolver: ((Car) -> CarDynamicsProfile?)? = null
)

data class BrakingGradeSegment(val lengthMeters: Double, val grade: Double)

// GradeAwareBrakingEnvelope的companion object入口：
fun fromDynamics(trainProfile: TrainDynamicsProfile,
    carProfiles: List<CarDynamicsProfile>, gravityMetersPerSecondSquared: Double,
    segments: List<BrakingGradeSegment>,
    currentAccelerationMetersPerSecondSquared: Double = 0.0): GradeAwareBrakingEnvelope
// 包络查询，距离m、速度m/s、时间s：
fun permittedSpeedMetersPerSecond(targetSpeedMetersPerSecond: Double, distanceMeters: Double,
    currentSpeedMetersPerSecond: Double = 0.0, reactionTimeSeconds: Double = 0.0): Double
```

速度为m/s、加速度与重力为m/s²、距离为m、质量为kg、力为N、时间为s。`grade`是无量纲正弦值。
`TrainDynamicsExecutor.preview`不提交状态，`step`提交`State`和`lastResult`。

`TrainActuator.brakingEnvelope(segments, deltaTimeSeconds, maximumControlDeltaSeconds)`保留现有解析work规划接口，
不调用未来物理试算。最终安全判断由SimpleATO ATP使用完整逐车能力/状态完成。
`ControlDemand`/`BrakingConstraints`暂留为上层值，Basic执行器不提供旧目标速度自动驾驶fallback。

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

单次动力学step为`O(cars)`，实际kernel内联写入独立拥有的数组，公开已提交快照独立拥有存储。
路线坡度切片最多256段；累计制动work构造为`O(route samples)`，每个空间目标查询为`O(log segments)`。
能力表按配置/编组身份构造一次，热路径不重新采样整条性能曲线、不扫描未来物理tick。

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
- actuator：只执行数字指令；手柄映射等价、真实设备延迟/联锁、EB请求、preview不提交状态。
- 设备helper逐区间对照kernel；持续混合制动不变量在变dt、低速淡出和强电制动下保持。
- 历史：延迟安装、A→B→原A复用、真实逐车换序、只读构造不覆盖历史。
- 包络：分段边界、扫描截断、无正减速度fail-closed和256段上限。
- 正加速度消退：非零坡度下的解析位移/速度增量、非正加速度/禁用jerk的原行为、距离耗尽返回0，以及额外扣除不能绕过原不可制停坡段门控。
- composition：生产和隔离均无`KinematicTrainActuator`，捕获后不访问live renderer/Bukkit。

## 10. 迁移、未决事项与 Wrong / Correct

迁移已删除Bukkit中的`RealisticManualController`实现，不保留runtime兼容alias。`physics.yml`从schema 1升级到2时，
使用`scripts/migrate_physics_config.py`先dry-run审查，再用`--apply`在保留`.bak`恢复点后把旧重力键单向改名。
编组结构变化通过准备/安装合同保存精确车厢设备；新controller优先采用世界历史及装配处显式传入`initialDeviceSource`的逐车设备来源，
无来源时才使用初始状态。不得把新controller构造误当全部设备瞬间复位。
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
