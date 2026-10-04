# SimpleATO controller runtime 契约

## 状态与范围

`work.art1st.javarailways.simpleato.controller.SimpleATO`是当前唯一具体自动驾驶controller owner。
它连同`SimpleAtoPublishedState`、mission queue、station-call状态、
movement authority和owner-scoped constraint manager位于SimpleATO模块。Basic不保留同名controller、typealias、
factory facade或转发访问器。

SimpleATO同时拥有`AbstractATO`、mission、constraint与world Stall coordinator；详细机制见
[ATO policy runtime](./ato-policy-runtime.md)。SimpleATO拥有`AtoSafetyProfile`；Basic只提供actuator、坡度制动包络、
不可变拓扑、route/perception值对象与world safety interlock，不拥有或转发ATO可写状态。

## 正常流程与内部机制

一次控制周期按以下顺序运行：

1. Bukkit或隔离仿真的`TimetableAtoFactory`解析/捕获同一列车profile、逐车profile和重力，创建Basic
   `HandleTrainActuator`后再把controller安装到目标Core train；生产与隔离composition不得使用运动学actuator；
2. Dispatcher在serialized owner上提交带revision的mission、station call、reverse或authority命令；
3. physics tick开始时controller只在边界drain已接受命令，形成一份active mission/route状态；
4. controller构造一次perception，用当前Mission投影更新该列车inspection激活状态并消费上一成功signal epoch的失效标记，
   然后读取最新immutable restriction snapshots，按owner原子替换constraints并取所有候选中最严格速度；
5. SimpleATO `AbstractATO`先执行invariant validation，再调用Basic真实手柄actuator；它逐级选择配置中的P/N/B档并通过共享
   `TrainDynamicsExecutor`移动Core train；
6. 成功epoch结束时`AbstractATO`原子发布train snapshot、累计距离和controller value state，Dispatcher只读取这份publication。

Mission、constraint和station-call的mutable集合都只存在于该controller实例，不在
`SimpleATOWorldData`或Basic创建第二份镜像。每列车每tick的route/perception扫描仍为线性；controller只从world-local
inspection runtime读取编译好的声明及最大提前时间；controller按最高速度转换为保守距离并并入原有horizon。
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

controller同时拥有该列车唯一的`TrainRestrictionConstraintAdapter`。admission client直接向adapter发布immutable
restriction snapshot；Dispatcher仅在route建立/延长时绑定冻结route、Mission revision及二者的坐标映射。每个physics tick
由controller用当前Mission投影和上一成功signal observation stamp调用apply，adapter把结果原子替换进controller原有
`ConstraintManager`。这不是第二份约束存储或第二个制动器：最终仍由同一个
`MinimumConstraintPlanner`与车辆、停车、跟车等constraint一起取最严格值。部分Basic授权保留外部MA；当grant覆盖完整
frozen mission route时，该来源发布更高revision空restriction，终点仍由mission Stop constraint精确停车。

signal epoch不直接持有controller。它先写world-local immutable snapshot store，并只在全部publication成功后推进completed
observation stamp。下一次physics tick中controller从route绑定携带的world-local snapshot source读取最新限制；同revision
重复读取是幂等的，wrong train、source unavailable或store/controller revision矛盾会在controller tick内使world fail closed。

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
来源仍负责把未知状态投影为 0，SimpleATO 不解释闭塞、方向失配或信号灯色。

例如范围为 `[40m, 100m]`：车头在 20m 时按既有曲线为入口减速，在 60m 时保持上限，
越过 100m 后这项限制结束。资源是否仍被车身占用由 Basic 的车尾释放流程决定，不由速度范围决定。
范围随现有路线绑定替换、折返或移除，不创建另一套列车生命周期。稳定范围声明 ID 的查询集合在来源绑定时缓存，
失效交接只查询实际收到的 ID；没有失效 ID 时不额外扫描范围。每 tick 仅在原有限制适配中检查缓存限制的终点，
增加的工作为 O(C)，C 为该列车缓存的限制数；不重新扫描路线或调用信号规则。手动控制器不消费这一接口。

## Publication边界

`AbstractATO`把一次publication表示为protected immutable `PublishedAtoState`，并通过一次volatile reference
读取返回完整快照。SimpleATO必须只调用一次reader，再从该对象读取train snapshot、累计距离和controller state；
不得把字段拆成多次volatile读取，否则并发publication可能产生跨epoch混合。Dispatcher不能读取worker正在修改的
active字段，也不能改写controller内部publication reference。

`SimpleAtoPublishedState`把这三个值和immutable controller state组合成Dispatcher可读取的快照。tick完成但
Core尚未调用commit/publication时，旧快照必须保持不变；publication成功后所有字段一起前进。
成功publication还记录本次已完成inspection的冻结route。新binding的Basic申请/推进/扩权必须等controller已检查该route，
且完整publication包含所需horizon；不得用旧路线结果或上一个较短perception放宽准入。

## Failure与禁止实现

- mission resolve失败或stale revision不改变active mission，也不误触发world Stall；
- physics invariant或controller异常由同一SimpleATO world coordinator fail-close并freeze，同时锁住Basic物理interlock；
- 不得在Basic恢复具体SimpleATO subclass、兼容alias或callback bridge；
- 不得让Bukkit直接更新active mission/constraint集合；
- 不得为本次inspection线程ownership迁移增加第二套冻结路线生命周期、资源registry或控制器。

## 必需测试

- `SimpleATOTest`覆盖mission boundary、constraint归约、publication原子性、Stall与freeze；
- `InspectionRuntimeTest`覆盖两列车并发投影/独立dirty消费、旧route view完成后新route baseline、折返/移除/reset；
- 区间限制覆盖只有范围而没有入口声明的首次投影、稳定声明失效、范围越界/重复限制 ID 拒绝、
  入口前制动目标、入口后持续上限、车头在终点及越过终点；折返使用新绑定范围，不提前释放已有资源；
- `ProtectionSignalControllerHandoffTest`在停止Dispatcher后，验证允许→0→允许均到达下一次控制计算，
  未变化epoch不产生新投影revision，越过入口撤销对应限制，0限速不阻止首次资源申请；
- `StationCallRuntimeTest`覆盖停站状态与controller publication；
- Dispatcher、manual navigation和隔离仿真测试证明所有composition caller使用新owner；
- Basic测试独立编译，证明Basic main/test source均不依赖SimpleATO。

```bash
./gradlew :Basic:test :SimpleATO:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
git diff --check
```
