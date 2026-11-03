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
`WorldSafetyInterlock`；后续失败不覆盖first-failure报告。reset同时清除报告、恢复coordinator并释放interlock。
因此manual controller仍fail closed，但Basic无需知道ATO报告或constraint类型。
`SimpleATOWorldData`在组合边界要求coordinator引用的必须是同一Basic world的exact interlock实例；
不允许ATO与手动控制各自锁定一份彼此隔离的latch。

## Mission与constraint机制

- mission request与legs防御性复制；resolver逐leg执行WayPoint resolve和统一的`OperationalRoutePlanner`，任一失败不产生partial mission；
- 每次业务寻路只合成一次限制并执行一次拓扑搜索；搜索结果直接转换为冻结路线，不再针对live `RailView`做第二遍route compile；
- `MissionRouteLayout`一次编译累计距离；重复rail/环线用先前progress选择非倒退occurrence；
- constraint manager按owner原子replace，拒绝同owner重复key，并线性取全部当前candidate的最小许可速度；
- signal inspection按已选冻结路线声明需要检查的来源，restriction adapter把插件的namespaced输出作为只会收紧的constraint并入同一个manager；
- controller没有可切换provider状态，也不通过RailObject边界替换整套信号逻辑。

## 失败、复杂度与测试

mission resolve和stale revision是typed rejection，不Stall；controller/invariant异常Stall并freeze。mission route
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
