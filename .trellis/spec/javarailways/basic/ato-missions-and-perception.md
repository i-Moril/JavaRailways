# Basic route/perception snapshot 契约

## 状态与 owner

Basic拥有自动行车和资源校验共用的不可变`RouteSnapshot`、`PerceptionSnapshot`、
`RevisionedCommandQueue`及下述第一方物理前视查询。`AtoMissionRequest`、mission resolver、mission route progress以及mission
submission属于SimpleATO，见[SimpleATO ATO policy runtime](../simpleato/ato-policy-runtime.md)。Basic不得重新建立
mission mirror、resolver facade或SimpleATO forwarding accessor。

## Snapshot机制

`RouteSnapshot(routeId,revision,topologyRevision,segments)`把一次已选路线绑定到独立的route revision和Core topology
revision；两者都是非负runtime计数，不能互相推导。segment使用rail UUID、`ViewDirection`和view-local SI弧长，满足
finite且`0 <= start <= end`。

`PerceptionSnapshot(epoch,train,route,observations)`防御性复制输入；observations按`routeDistanceMeters`非降序，
`relativeSpeedMetersPerSecond`为nullable finite值。transition固定为`ENTER/UPDATE/EXIT/PASS`。SimpleATO负责从同一
topology root与physics publication构造这些值，Basic资源校验只消费，不选择运营路线。

其中`train: TrainFrameSnapshot`直接引用Core已完整发布的物理帧，不使用`TrainSnapshot`摘取副本。
一次扫描捕获一帧并由head/speed/body共同消费；worker读取上一已提交帧是本轮控制输入，不是发布错误。
`epoch`是感知自身计数，不冒充`train.physicsEpoch`或`train.revision`；诊断分别从所属上下文取值。
旧感知引用的帧不会随列车运动变化，读取历史感知时不能重新查询最新train来补字段。
此迁移不改变route、observation单位、排序和防御性复制规则，不新增物理缓存或schema版本。

`RevisionedCommandQueue`只提供严格递增revision、原子enqueue和physics boundary drain；payload不可变性由owner保证。
它不解释mission、provider或constraint。

## 选定物理线路的两阶段前视查询

```kotlin
fun traceSelectedTrackAhead(world: World, head: RailPositionSnapshot, horizonMeters: Double): SelectedTrackAhead
fun observeTrackObjects(train: Train, path: SelectedTrackAhead, railObjects: RailObjectStore,
    lowerToleranceMeters: Double, observerSpeedMetersPerSecond: Double): Map<RailObjectRef, PhysicalTrackObservation>
```

`basic.api.ato.SelectedTrackAhead`表示从调用方已捕获Core frame的head开始，沿当前选中live continuation
追踪得到的有序`PhysicalTrackSegment`，附带扫描开始时捕获的topology revision及覆盖距离。
区间弧长使用各view方向米；BA车头从canonical AB米转换后仍复用Core原`peekCurve`算法。
它遵守当前道岔选择，不运行最短路规划；不添加256段上限，不复制Core的循环/零长/精确端点处理。
物理线路提前终止时覆盖可小于请求horizon，终点零长度片段保留原语义。
距离原样传给Core：零值仍产生当前位置片段，负值仍按Core原反向追踪，不增加输入限制。

调用方先取得路径，在原时点构造自己的route revision与`RouteSnapshot`，再收集该路径对象。
调用方传入本轮稳定的stop tolerance与activeSpeed，配置替换/速度积分仍在排除该读取的原owner边界；
收集按片段顺序读取Core static objects、dynamic train ends、传入的既有Basic `RailObjectStore`，
仅保留ACTIVE；同ID自身列车端排除。下边界按调用方stop tolerance纳入，上边界仍严格不超出end；
纳入的后方容差对象距离夹为片段起点。位置快照按当前view捕获；前车同向速度取原live train.speed，
异向取0，再减调用方本次activeSpeed；普通对象相对速度为null。
同ref不同live对象按原消息抛错；同实例在重复经过的路径上只保留最近一次，距离相等保持首次。
返回map保持首次ref插入顺序，后续事件稳定排序和跨epoch身份检查属于SimpleATO。

片段公开Core API `RailView`引用、固定起止弧长和长度；对象观察公开原`RailObject`实例、
`RailObjectSnapshot`、距离米及可空相对速度m/s。集合结构只读，但其中live引用不冻结世界：
不能将这些结果跨线程发布为观测快照，也不能提前读取所有signal/lifecycle/车端owner来替代原后续检查。
`TrainEndRailObject`的owner和HEAD/TAIL能力归[Core API边界](../core/api-boundaries.md)。
SimpleATO仍拥有路线revision、ENTER/UPDATE/PASS/EXIT、PASS距离阈值、感知epoch及最终不可变publication；
跟车、精确联结目标排除、停车与制动切片不进入Basic查询。

查询由调用方在原simulation/physics上下文同步执行，不调度、不注册owner、不写存档或产生授权。
时间与追踪片段及所枚举对象数量成正比（重复片段会重复访问其对象），临时空间由路径片段、唯一可见ref及单段枚举对象数量决定；
不新增全世界扫描、身份registry或跨tick缓存。缺车头轨道、空路径、重复live ref及底层getter失败沿原异常传播。
调用方在对象回调后检查物理终点，并在移动前按原对象身份检查每对live续接，不能把扫描完成视为后续有效性证明。

回归应覆盖AB/BA坐标、精确horizon/物理端点、沿选定分支和循环重复经过、源合并顺序、ACTIVE过滤、
同实例最近去重与重复ref失败、同向/反向相对速度，以及晚期道岔变化拒绝。
SimpleATO原回归继续证明事件历史、目标消失、跨epoch重复ref、跟车/联结及入口净空只扫描一次。

## 失败与测试

- topology/route revision混用、未排序observation、非法弧长在构造边界拒绝；
- snapshot collection不可被调用方后续修改；
- perception持有原Core frame引用，后续移动/新帧不改变既有感知的物理输入；
- Basic不得在snapshot失败时回退读取live world并继续授权。

```bash
./gradlew :Basic:test --tests '*PhaseAFoundationTest'
./gradlew :SimpleATO:test --tests '*AtoMissionTest' --tests '*SimpleATOTest'
```
