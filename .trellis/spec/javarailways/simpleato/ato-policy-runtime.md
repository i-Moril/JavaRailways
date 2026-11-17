# SimpleATO ATO policy runtime

## 状态、范围与正常流程

SimpleATO是ATO mission、constraint与world Stall诊断的唯一owner。它拥有`AtoSafetyProfile`、`AbstractATO`、`AtoPlanner`、
`ConstraintManager`及具体constraints、`AtoMission`/resolver、`MissionRouteLayout`和`AtoWorldCoordinator`。
旧`AtoProvider` factory/registry/boundary切换机制已经直接删除，不保留alias、facade、runtime fallback或替代抽象；
信号扩展只使用Stage 1 inspection与namespaced restriction合同。
恒加减速的`KinematicTrainActuator`只位于SimpleATO test source，不是Basic API或production fallback。

一次正常流程是：serialized owner解析WayPoint mission并生成完整legs → revision queue在physics boundary激活 → controller用一次
route/perception scan生成车辆、停车、跟车、授权与外部restriction constraints → `MinimumConstraintPlanner`取最严格速度 → Basic actuator
执行物理运动 → 成功epoch原子publication。resolver通过SimpleATO的`OperationalRoutePlanner`捕获一次不可变拓扑，读取 Basic 公共轨道方向并合成
已注册的寻路限制，再调用一次Basic的拓扑寻路算法；SimpleATO负责目标、车次行驶方向、mission连续性和选择结果，Basic算法不成为运营状态owner。

## World Stall与共享物理interlock

`SimpleATOWorldData`持有每world唯一`AtoWorldCoordinator`。controller、Dispatcher、Depot reset、
隔离仿真和Bukkit诊断全部解析这一份owner。coordinator首次Stall保存结构化报告并锁住Basic同world
`WorldSafetyInterlock`；后续失败不覆盖first-failure报告。Stall状态由同一个原子报告引用派生，
不能先发布STALLED再安装报告，否则并发controller无法绑定自己的本次故障。reset清除报告并释放interlock；
这是运行期状态，不增加持久化字段或schema版本。Basic无需知道ATO报告或constraint类型。

当前ATO Stall与Core物理Stall有不同职责：ATO Stall锁住普通运营控制，但物理epoch继续推进。
健康ATO在下一次观察到Stall时跳过命令消费、规划、inspection和普通ATP选挡，直接用Basic公开
`TrainActuator.step(currentSpeed, VehicleControlCommand.EMERGENCY_BRAKE, dtSeconds)`积分一次，
沿原接触夹紧和movement seam提交真实距离、速度及累计里程。手动controller通过同一interlock执行真实EB。
例如一列车故障时，另一列以2 m/s行驶的健康列车应先有制动位移，再随真实设备响应停稳，不能直接发布0速度。
Core物理Stall则排除worker推进；暂停期间不执行EB积分，恢复物理后才继续。

只有controller本身捕获的普通规划、校验或执行异常保留既有freeze。controller私有记录当前锁定的报告引用，
在同一故障期间持续冻结；不能按报告trainId判定故障车，因为Dispatcher也可能报告一列健康车。
reset后新报告不继承旧冻结标记，即使controller尚未经历正常tick。已停稳的结构/联挂保持继续保持零运动。
真实EB执行、接触边界或运动提交失败必须冒泡至Core失败ticket/物理halt，不能吞掉异常后伪造成功零速帧。
Stall检查本身为O(1)，制动沿用既有逐车积分与接触边界成本，不重建路线或运行候选选挡。

恢复规划时，用自上次规划以来的累计物理里程增量更新任务里程、StationCall末端进度和PASS/EXIT判断，
不只读取最后一个EB帧的距离。仍在原规划采样时机更新感知、任务诊断和站停通知；Stall期间不推进任务事件。
任务激活、退休或折返重置任务自身里程起点；扫描的累计位移基线仍只在规划时消费，
因此上一任务的制动距离不计入新任务，却仍可用于独立StationCall和既有对象的PASS判断。

重复轨道的圈次也必须使用自上次投影以来的累计位移，而不是只看最后一帧。
冻结物理路线游标保留已消费的controller lifetime基线，加入尚未消费的制动距离；
最新帧若来自额外显式移动，再加入其traversal一次，不能重复加入已包含在lifetime的末帧。
无冻结restriction的Mission和独立StationCall在原真实位置候选及非倒退筛选后，
选择最接近“旧进度+累计位移”的出现位置；这个期望值只选择圈次，不改变坐标、轨道成员范围或容差。
例如A→B→A上的列车从10m制动到225m，最终位于第二次A的25m处，恢复后必须得到225m，不能退回25m并放大剩余授权。
`AbstractATOStallBrakingTest`覆盖真实逐车设备EB、故障冻结、外部报告/直接interlock、规划中Stall、
reset后新故障、物理暂停和EB失败不发布伪帧；`SimpleATOTest`覆盖多帧制动后恢复记账、绑定/未绑定物理路线的跨圈定位及命令/任务队列保持。
不增加迁移路径或新的恢复策略；错误做法是将世界Stall统一转成freeze，正确做法是仅冻结自身故障并让健康车真实制动。
`SimpleATOWorldData`在组合边界要求coordinator引用的必须是同一Basic world的exact interlock实例；
不允许ATO与手动控制各自锁定一份彼此隔离的latch。

初始物理现场由当前`AbstractATO.initialTrainFrame`持有，含义是本控制器创建时捕获的Core帧；
reset不重新采样。world coordinator不再按所有历史train ID维护初态map。故障报告保存当时帧引用，
旧帧不随列车后续运动改变，也不能在JSON输出时重新读取最新帧替换现场。报告只显式写既有小字段，
carCount取frame.cars.size；旧诊断revision按ATO/感知上下文的计数写出，不偷换成Core frame.revision。
此变化不增加物理摘要模型、持久化字段或schema代际；frame所有权与发布顺序归Core。

## Mission与constraint机制

- mission request与legs防御性复制；resolver逐leg执行WayPoint resolve和统一的`OperationalRoutePlanner`，任一失败不产生partial mission；
- 每次业务寻路只合成一次限制并执行一次拓扑搜索；搜索结果直接转换为冻结路线，不再针对live `RailView`做第二遍route compile；
- `MissionRouteLayout`一次编译累计距离；重复rail/环线用先前progress选择非倒退occurrence；
- constraint manager按owner原子replace，拒绝同owner重复key，并线性取全部当前candidate的最小许可速度；
- signal inspection按已选冻结路线声明需要检查的来源，restriction adapter把插件的namespaced输出作为只会收紧的constraint并入同一个manager；
- controller没有可切换provider状态，也不通过RailObject边界替换整套信号逻辑。

### 真实位置到 Mission 累计距离

`MissionRouteLayout.project(position, previousProgressMeters): Double?`由controller和Dispatcher消费，
将同一有向轨道上的实际弧长转换为Mission累计米数。编译后的分段只读，不新增游标或第二套位置状态。
只接受rail ID、方向相同且实际弧长在分段闭区间内的候选；直接计算`累计起点 + (实际弧长 - 分段起点)`。
不得用0.3米站停验收容差扩张区间，不得把段外位置截回端点，也不默认添加浮点epsilon。
相邻同向分段在共享边界对应同一实际位置；越过边界后必须使用包含车头的下一段，而不是选择
更接近上一Tick进度的、由截断制造的旧段终点。

无先前进度时选择最早的合法候选；重复轨道或环线存在多个候选时，只保留不小于先前进度的候选，
再选择最近者。没有匹配或所有候选均小于先前进度时返回null，不伪造位移。
调用方仍按既有合同处理冻结物理路线后缀或失败，不在此函数中新增兜底。复杂度保持每次O(S)。
此行为不改变站停到位标准、真实占用、信号灯色或ATP边界，无持久化格式变化。

停车偏差不通过扩大这条通用投影的区间处理。Dispatcher 在已有停稳判定、同有向轨道和
`missionStopToleranceMeters` 内识别实际停车起点，沿用 `ObservedAtoMissionOrigin` 形成从真实车头
开始的首段，再走既有物理路线衔接；不改物理车头，不强迫列车精确停在作者标记上。
冻结路线及 inspection 的进度转换保留有符号位移，不将实际进度夹在路线首尾之间。
这不放宽 inspection API 的输入范围：实际转换结果越出冻结路线时，既有范围校验仍拒绝并保持安全停止，
不能将越界值伪装成合法端点。保留原始数值不等于允许任意负值或超长值通过下游。
限制目标的合法裁剪不是位置测量，不能把这条规则机械应用到所有 `coerceIn`。

冻结车身区间的越界不允许用停车容差修补。累计距离裁剪后与车身连接的数值核对沿用该组装器
已有 `1e-6m` 精度，而非 `0.3m` 停车容差；例如 `(590 + 10.7) - 590` 会产生
`10.700000000000045`，允许核对此类算术差异但不吸附或改写实际车身坐标。已知裁剪端点直接沿用
原值。`FrozenMissionRouteAssemblerTest` 覆盖有符号映射、累计加减、拒绝 `0.0001m` 几何缺口和
真实轨道范围外的车身；`TimetableDispatcherSharedAdmissionTest` 覆盖目标前后 `0.1m` 停稳起步。

例如信号处把一条轨道分为`[..., 582.738...]`和`[582.738..., ...]`，车头实际在`582.859...`时，
投影必须保留已通过的约0.122米；将它吸回共享端点会让本车占用产生的红灯继续作用于本车。
`MissionRouteProgressTest`须覆盖AB/BA、共享边界前后及恰好边界、极小但真实的越界、路线外返回null，
以及重复轨道/环线的非倒退选择；信号过点撤销和实际全局重放验证其下游行为。

## 失败、复杂度与测试

mission resolve和stale revision是typed rejection，不Stall；controller/invariant异常触发世界Stall，自身freeze、健康车真实EB。mission route
编译对segment数为`O(S)`，每次position投影当前实现扫描compiled segments为`O(S)`；constraint归约为`O(C)`。
不得逐tick重新寻路或累计旧constraint。

```bash
./gradlew :SimpleATO:test --tests '*AtoPolicyTest' --tests '*AtoMissionTest' \
  --tests '*MissionRouteProgressTest' --tests '*SimpleATOTest'
./gradlew :Basic:test
```

## 逐车 ATP 与执行器边界

`ConservativeAtpModel`读取真实、按编组顺序排列的设备快照；设备缺失、非有限输入、
无有效紧急气制动、超出能力速度域或无法保证下坡减速均返回typed `UNAVAILABLE`。
禁止用旧聚合制动比例或理想即时响应替代生产设备状态。

- ATP只返回判断与证据，不操作手柄。`emergency`检查当前立即发EB的可停性；
  `afterCandidate`用`VehicleDeviceDynamics.advanceDeviceState`计算唯一已知的当前interval设备更新，
  以忽略阻力、最不利坡度和原kernel jerk/限速夹紧求位移上界，然后检查EB或持续最大常用制动。
  当前interval使用实际dt，未来未知interval使用world声明的最大dt；不把二者混为指令延迟。
- 编组能力按configuration identity缓存；逐帧candidate的电机力系数来自本帧capabilities，
  不从缓存模型创建时的速度取值。真实动力积分仍仅由Basic执行一次。
- 查表内容是恒定保证制动下的坡度系数`1/(2b)`，不是完整设备状态表。
  速度连续代入`(v-vTarget)*(v+vTarget)`；grade选不大于输入的格点，系数和距离向保守方向舍入。
  准备阶段是有限的解析牵引释放、指令剩余延迟、电制动释放、气制动建立和jerk阶段；
  不调用未来真实动力学tick，不逐tick展开预测。
- EB恒定保证只信用气制动力并按逐车adhesion上限裁剪。普通制动使用Basic统一的
  `guaranteedSustainedServiceForceNewtons`，其电/气混合不变量成立后才信用恒定保证；
  初始设备状态和建立期限仍分别计算。不得把随速度fade的电制动当恒定EB保证。
- 向上jerk采用最大interval领先界覆盖离散设备采样；下降jerk即使经过force catch也不得跳过。
  kernel最终速度夹零后仍对整个interval使用梯形距离，连续模型须加入由最大dt及最大实际减速度
  推导的末步正部拐角积分误差界`bMax*dtMax²/8`，并可用`v*dtMax/2`进一步收紧。
  不修改停车空间容差来掩盖积分差异。
- `distance<=0`的正限速是持续当前限速；candidate跨越未来正限速点时也须检查已知interval峰值，
  不得把负距离误当零速停车点，也不得丢弃已越过的限速约束。

`VehicleControlExecutor`每tick检查当前EB和继续当前candidate的硬证书；普通巡航策略最多每0.2s
重新选挡，目标/模式变化、硬证书不通过、名义停车可达性将丢失时提前重选。停车模式每实际interval
更新。纯名义停车误差或名义预测不可达只产生`OperatingPrecisionStatus`，不能转换为ATP事件；
仅硬安全candidate集合为空或输入/模型不可用时触发异常。

当前EB判断为SAFE且全部硬目标都超出一个保守保护视界时，执行器允许普通策略直接使用完整档位
catalogue，跳过逐候选ATP恢复计算。视界由当前EB响应距离、下一已知区间最大牵引增量和决策缓存
覆盖；它只用于放宽候选筛选，选中命令仍必须通过同一`candidateJudgement`硬检查。检查失败时
执行器立即回退到严格逐候选筛选，再按既有planning-reserve/no-ordinary-recovery语义提交。
因此该快速路径不能免除任何实际执行命令的硬证书，也不能把ATP异常转换为运营精度状态。

同一`choose`内的candidate judgement按命令和reserve标志复用，map在每次调用开始时清空；不分配
`Pair`键。`afterCandidate`已对源状态做一次validation，派生的prefix状态来自共享设备代数，
不再重复validation；候选目标通过距离偏移在原地检查，不构造shifted target列表。若继续命令与
已缓存的reserve-safe judgement一致，最终无reserve硬判断复用该judgement（reserve-safe蕴含
无reserve-safe）；命令改变时仍重新检查。目标形状用无分配64位签名比较。
普通巡航在离最近零速目标很远时跳过每tick的名义停车模型；该预检查只决定是否提前重选，
0.2s的正常决策周期和逐tick硬证书保持不变。

AUTO在停稳且新鲜安全输入允许候选时自动解除；MANUAL触发的人工确认要求跨controller/mode保留，
ack仅排队，下一次新鲜安全检查成功才解除。SIMULATION记录不可变事件后中断，已有latch也必须中断。
事件记录原状态、候选、判断、能力identity、坡度、所有硬目标和调用方immutable world/head/epoch上下文。

额外`planningReserve`与上述硬证书分开：它为一次service→EB设备转换保留真实指令重置、
电制动释放和补气期限，并随实际保留气制动力建立而消退，完整气制动时为零。
执行器优先使用拥有该额外余量的候选；余量集合为空时仅在通过当前EB、candidate后EB及
raw held-service硬检查的普通制动/惰行中选择，禁止继续牵引，也禁止盲选未通过检查的新最大制动。
此时`planningReserveAvailable=false`独立记录，不能将其当作ATP异常或混入停车精度状态。
