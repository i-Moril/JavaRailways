# Core rail topology 与 occupancy 契约

## 适用场景与用途

触发：修改 `Rail/RailView` connection、active branch、split/merge/remove、spatial query、`SweptOccupancyIndex` 或 `RailIntervalReleaseDetector`。

用途：structural mutation必须等待physics并以world write transaction发布；占用与release使用direction-independent AB区间，避免只看head、读到半成品topology或过早释放Basic资源。面向寻路与跨模块消费的唯一immutable图、结构节点归一化和overlay边界由[版本化铁路拓扑](./versioned-operational-topology.md)定义。

## Owner、类型与 transaction

- `CoreWorld`拥有rail map、`RailSpatialIndex`、增量`AtGradeRailCrossingIndex`与`ReentrantReadWriteLock`。两份索引共享curve真实piecewise-linear断点，不用`stream()`的1m直线虚拟点；spatial index沿相邻断点的线段填充cell，而不只记录断点所在cell。交叉检测从相同/相邻cell取候选后仍须做三维exact segment检查，并只在专用20 cm平交容差内发布近似交点；该常量不改变`Curve.TOLERANCE`或普通连接/occupancy容差。
- `CoreRail`拥有canonical curve与AB/BA views；迁移期间`RailView.nextRails`是Core内部physical connection编辑模型，发布给consumer的authoritative连通结构是同一transaction产生的`TopologyState`；`nextActiveRail`是runtime selected branch，不进入structural state，但会经同一world-frame的`ActiveRailContinuationsSnapshot`作为只读物理观测发布。
- Basic `allowedDirection`/route allocation不是Core physical topology。
- structural owner调用 `CoreWorld.railTopologyTransaction(action)`：先await current `PhysicsTicket`，再持write lock；expensive curve build在transaction外。
- live rail创建使用两阶段批量协议：平台worker调用`CoreWorld.prepareRailCreationBatch(curves, excludedIds)`，owner线程调用`installPreparedRails(batch)`；Core不持有executor或future。`CoreRail`构造本身不注册world。持久化restore在physics启动前只使用Core内部同步`createRailInTransaction`重建索引；同步测试建轨只存在于不打入runtime JAR的Core test fixture，runtime不暴露同步创建入口。
- RailWand online editor在worker从captured immutable `TopologyState`与明确final delta还原一次transient builder input，构造并验证
  完整normalized topology candidate；world commit只复核exact root、delta rail identities/count、changed endpoint与diamond result，应用delta并publish
  prepared root。它不执行完整live inventory fingerprint、`TopologyStateBuilder.build`或同步fallback。bootstrap/load以及Core内部低层同步
  transaction仍可从live rail inventory完整构建；这些入口不得重新用于玩家在线编辑。
- prepared update同时冻结该delta涉及的旧rail instance/absent identity、prepared新curve identity、两端structural continuation、raw crossing pair与全局rail count。
  同一次worker prepare还冻结exact base root对应的完整`SavedRailV1`列表，并由prepared target root、明确endpoint顺序、active branch与新curve
  生成完整target列表；RailWand用这两份值构造undo，不得在owner commit重新遍历live rail。该完整快照是undo输入，不参与commit的delta验证。
  `activeTrackAtA/B`是runtime选择而不是topology root内容；普通exact snapshot restore仍比较它，structural undo则必须在边界上明确归一化并使用
  下文的preserve-active apply语义，不能把topology root identity误称为完整saved-rail版本。
  action异常后只以`O(delta)`复核这些旧值：完全未改或完整恢复则保持world可用；任一结构结果未恢复才fault/stall。不得为了异常判断
  恢复`O(world)` fingerprint，也不得用“曾调用过mutation”代替最终结构检查。
- outer structural transaction在释放write lock前比较进入/退出时的rail identity与structural adjacency；只有成功且结构确实变化时才递增一次`topologyRevision`。nested transaction不重复递增，直接add/remove rail也进入同一边界；成功no-op与完整rollback不发布新revision。
- transaction exception不自动rollback。若异常前结构未变化，revision不变且world仍可用；若结构已经变化，revision仍保持旧值、world topology进入`RailTopologyHealth.Faulted`并Stall physics，禁止继续发布world frame或执行新结构事务，直到reload/recovery。fault record只保留稳定reason、最后成功revision、异常类型和消息，不长期持有原始`Throwable`；嵌套失败只存在于当前`RailTopologyTransactionContext`。不得把失败后的部分结构伪装成一个正常新revision。
- `removeRail`检查target及affected rails的full train placement envelope，修复links/spatial index后移除。
- `publishWorldFrame` 持 topology read lock 同时捕获 `topologyRevision` 与已发布列车 frames，因此不会观察到 structural transaction 的中间状态。

Canonical interval签名（`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailIntervalOccupancy.kt`）：

```kotlin
data class RailInterval(
    val railId: UUID,
    val minArcLengthInAbMeters: Double,
    val maxArcLengthInAbMeters: Double
)
```

字段finite且`min <= max`；overlap默认 tolerance `Curve.TOLERANCE`。

## Occupancy、release与结果矩阵

`SweptRailSpan(trainId,railId,minArcAB,maxArcAB)`在完整epoch后只形成一次immutable index；默认32 m bucket、collision query最多32 train candidates，exact overlap后返回`SweptOccupancyQueryResult(collidingTrainId,checkedCandidates,candidateLimitReached)`。同一index由`RailwayOccupancySnapshot`绑定到world frame的physics epoch并共享给Core collision与Basic admission/release consumer，后者通过`blockingTrainIds`查询任意canonical `RailInterval`且不设candidate cap。区间查询以`tolerance`扩展首尾bucket，再对去重candidate做exact overlap；返回不可变、按train ID排序的完整blocker集合。consumer不得从frame trains另建第二份world swept-occupancy index。

`TrainFrameSnapshot.occupiedIntervals`不是上述swept index的副本。它从epoch结束后的head、tail与bogie位置一次生成，保留既有相邻rail端点补全规则，供“当前仍在区间内吗”这类终态判断；`sweptSpans`继续用于碰撞与准入的整epoch保守查询。两者都按每根rail的canonical AB闭区间表达，跨轨道占用是多个interval，不存在跨整段路线的全局AB。

| 条件 | 结果 | mutation |
| --- | --- | --- |
| topology edit有in-flight physics | await exact ticket后进write transaction | await前不改rail |
| online edit的prepared topology已stale | commit前拒绝并提示重试 | rail/link/index/root均不变 |
| nested structural transaction | 由 outer transaction 统一发布 | topology revision 只递增一次 |
| affected rail有body occupancy | fail-fast | rail/link/index不变 |
| action失败且结构未变化/已完整rollback | revision不变，原异常传播 | world保持可用 |
| action失败且结构已经变化 | revision不变，topology fault + physics Stall | 禁止frame publication和新结构事务，等待reload/recovery |
| world frame 与 topology edit 并发 | reader 等待 write transaction 完成 | frame 携带完成后的 revision，不暴露中间图 |
| release detector未见过占用 | `BEFORE_OCCUPANCY` | 不release |
| overlap出现 | `OCCUPIED` | 保留claim |
| 曾占用后完全分离 | `RELEASED`（terminal） | owner可release |
| occupancy query达candidate cap | `candidateLimitReached=true`并记录metric/log | 不伪装完整无碰撞 |

## 项目内正确示例

`RailIntervalReleaseDetector.update` 只在“先占用、后分离”时释放：

```kotlin
state = when {
    overlaps -> IntervalOccupancyState.OCCUPIED
    state == IntervalOccupancyState.OCCUPIED -> IntervalOccupancyState.RELEASED
    else -> state
}
```

来源：`Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/RailIntervalOccupancy.kt`。它解决列车尚未进入保护区时被错误当作已清空的问题。

## 禁止实现

- consumer直接修改 `mutableNextRails/activeRail` 或无barrier调用`selectNextRail`；
- transaction内运行heavy curve solver；
- 失败后仅修Core map、不恢复Basic metadata/destination；
- occupancy只看head、direction或单个bogie；
- consumer从`RailwayWorldFrameSnapshot.trains`重复建立另一份全world occupancy index；
- release时强制把turnout切回旧branch；
- `getNearestRail`在main/per-Tick调用；coarse index候选仍须exact check。
- 把spatial cell共享直接当作diamond，或在route request/per-Tick重新扫描rail geometry；cell只做structural-edit broad phase。

## Live rail创建的异步准备与原子安装

`PreparedRailCreationBatch`是process-local immutable值：按调用方curve顺序保存已分配的stable UUID、curve、压缩后的piecewise-linear samples、spatial footprint、预计算crossing，以及捕获时的exact `CoreWorld`与published `TopologyState` root。它不是持久化边界，也不引入新的revision类型。

正常流程：

```text
Bukkit/Paper async worker
  -> 为整批curve一次分配rail ID并采样/压缩geometry
  -> Core在topology read lock内捕获published root及spatial候选geometry
  -> 释放read lock
  -> exact new-vs-existing + new-vs-new crossing detection
owner/world thread
  -> 验证world identity、同一TopologyState root、ID与replacement source
  -> 验证outer transaction尚未改变rail inventory
  -> 一次构造并注册全部CoreRail，安装预计算sample/crossing
  -> 完成connection/removal等编辑后只发布一次TopologyState
```

候选捕获只复制被new rail spatial cells及相邻cells命中的existing sampled geometry，不复制完整world graph。设新rail数为`N`、每条压缩段数为`S_i`、空间候选集合为`C_i`，准备阶段sampling为`O(sum S_i)`，new-vs-existing exact上界为`O(sum_i sum_{c in C_i}(S_i * S_c))`；new-vs-new只在spatial footprints相邻且AABB重叠后进入segment exact phase。安装前用transaction内的rail-inventory mutation标记做`O(1)` dirty检查；安装预计算结果为`O(N + K)`，`K`为该批预计算crossing数，不得重新运行exact detector。outer transaction最终仍按既有协议构建并发布完整`TopologyState`。

replacement/split/merge必须把同一事务将删除的rail ID作为`excludedIds`整体准备，不能逐rail分别prepare；否则新rail之间的crossing和批次顺序无法在一个root上证明。含excluded ID的batch只能安装在尚未改变rail inventory的outer transaction，且这些source rail必须在该transaction发布前全部移除。安装前world不一致、root/revision过期（包括source已变化/缺失）、outer transaction已改变rail inventory或batch身份冲突均抛`PreparedRailCreationRejectedException`及稳定reason，且batch本身产生零mutation；connection选择等不改变候选geometry的后续编辑可在安装后完成。Bukkit向玩家提示重试，不自动无限retry。安装后的后续编辑失败仍遵循现有transaction fault/rollback规则。

Rail创建和replacement的连接只能来自调用方已经明确的endpoint关系，不存在按距离自动选择相邻轨道的生产机制。
新建独立轨道默认不连接；锚点连接只连接玩家选中的endpoint与批内明确相邻的分段；replacement通过旧endpoint到新endpoint
的显式映射保留既有关系。`PreparedRailConnectionPlan`绑定同一个prepared batch、`TopologyState` root与所有相关既有rail
实例，只记录每个明确endpoint的最终`next/active`状态。capture只验证声明的对称关系、endpoint位置与容量，不扫描附近
endpoint，也不推断turnout stem/branch。apply在source rail仍存在时验证world/root、target inventory、占用及全部source
endpoint的connection顺序与active选择，然后按记录结果写入；任何变化都使整个plan失败，不会重新选择对象或partial apply。Basic只能把plan公布的准确existing endpoint与
prepared crossing identity转换为编辑影响，不能把spatial broad-phase candidate当作影响。
apply还要求prepared batch是当前outer transaction中唯一已经发生的rail-inventory mutation；调用方必须先安装该batch、
立即应用连接计划，再删除replacement source，不能在两者之间插入另一项rail增删。
一个显式变化会绑定其最终connection component中的全部既有endpoint；即使某条既有支路的本地next列表不变，它仍属于
正在重构的同一物理接头，必须参与占用检查与edit impact。

whole-world RailWand snapshot restore仍复用同一prepared geometry pipeline。普通创建入口先生成随机UUID；内部
stable-identity入口接收snapshot中的`(UUID, PrimeCurve)`，其余sampling、spatial candidate capture与exact crossing
detection完全相同。Bukkit只捕获current/target snapshot；Core以一次`O(N)` geometry/identity比较导出完整diff，并按target
geometry解码saved connection及surviving endpoint变化。Bukkit不重复比较，也不从只保存相邻rail UUID的格式猜target endpoint。
saved `nextRailAtA/B`与active selection仍是唯一连接来源，不搜索无声明的附近rail。same-UUID geometry replacement使用专用
plan：在outer transaction内完整验证root、rail instance、endpoint state和occupancy后，批量断开/移除旧实例，再以相同
UUID安装预采样实例，最后写入snapshot声明的endpoint state。普通`installPreparedRails`的duplicate-ID与
install-before-remove规则不放宽。旧raw crossing去除被替换rail后与prepared crossings合并，并通过target explicit
connections重新归一化。target topology和introduced crossings只在prepare内部用于导出removed old crossings及
prospective crossing的existing-rail参与者，不随plan长期保留；Basic permit只提交这些current-valid身份。
snapshot若只改变active branch selection，则它是runtime选择而非structural delta：apply更新active view，但复用exact同一个
`TopologyState` root且revision不变；rail identity/geometry或`nextRailAtA/B`变化才构造并发布revision+1 candidate。
async prepare只解析owner线程已捕获的current/target saved values和immutable curve geometry，不读取live mutable connection或
active branch；回到player owner后，plan以captured endpoint state逐项复核live rail，再允许permit/apply。
上述exact-active语义仍用于普通snapshot restore。RailWand structural undo使用单独的preserve-active入口：undo的`before`、
`expectedAfter`与相等性比较把`activeTrackAtA/B`归一化为null，connection列表及顺序保持不变；apply在持有topology transaction时
读取每个受影响endpoint的当前选择，目标结构仍包含该连接时保留，否则按target connection顺序选第一条，无连接则为null。
该模式的stale检查只忽略active变化，rail identity、geometry及ordered connection变化仍然fail-close；不改变persisted schema。
比较与target topology构建为`O(N + M + K)`，其中`M`为snapshot连接数、`K`为相关crossing数；exact geometry成本仍沿用
前述candidate-pruned bound。apply对surviving endpoints单次遍历并只写changed/created endpoints，不扫描图资源。

设声明中endpoint数为`E`、连接引用总数为`M`、prepared exact crossing数为`K`，capture为`O(E + M + K)`，其后验证与apply为`O(E + M)`，且不执行几何邻近搜索；
prepared crossing仍由前述creation batch检测。测试必须证明显式选择、附近未选rail排除、standalone保持断开、stale root拒绝、
deterministic apply与replacement准确重映射。

测试必须锁定：exact detector只在prepare运行；batch内new-vs-new crossing；input/output顺序；一次publication；world mismatch、stale root、dirty outer transaction与source缺失在batch mutation前拒绝；显式连接的exact selection、stale拒绝与deterministic apply。world bootstrap/load仍可在physics启动前同步完整重建。
snapshot restore还必须覆盖stable UUID、same-ID replacement、exact prospective crossing、unrelated rail exclusion、stale与occupied no-mutation。

## 必需测试与验证

- `CoreWorldRailTopologyTransactionTest.kt`：明确latch/barrier验证reader不可见中间态（不新增sleep猜测）。
- `CoreTrainAsymmetricTurnoutTest.kt`：body occupancy、active branch fallback。
- `SweptOccupancyIndexTest.kt`：bucket、tolerance、candidate cap、stable nearest ordering。
- `RailIntervalReleaseDetectorTest.kt`：`BEFORE→OCCUPIED→RELEASED`且方向无关。
- split/merge tests断言rollback后rail map、links、index和Basic projection一致。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.physics.rail.*' --tests '*CoreWorldRailTopologyTransactionTest'
./gradlew :Basic:test --tests 'work.art1st.javarailways.basic.rail.*'
```
