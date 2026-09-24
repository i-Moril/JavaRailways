# Core 版本化铁路拓扑契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`PARTIAL`；已有revision-scoped public read-only `TopologyState`、纯归一化构建器、平交观测及`CoreWorld`原子publication；通用edge-distance查询层与全部结构编辑入口封装尚未完成。
- **Owner**：Core/host拥有物理结构、归一化索引及其publication；外部模块只能消费指定revision。
- **Non-goals**：本页不定义运营overlay、选路策略、资源身份/claim、信号规则或持久化schema。

触发：新增或修改铁路图、结构节点、拓扑 revision、rail projection，或把 `RailView.nextRails` 传给 Core 以外的长期运行逻辑。

本页只定义唯一 authoritative 的物理连通结构。Core/host 拥有并发布一个不可变、带 revision 的 `TopologyState`。外部运营对象或资源可以引用它的 rail slices/edges，但这些引用不扩充结构图，也不能成为第二张 authoritative connectivity graph。

## 2. 模块工作方式

一次正常结构更新按以下流程工作：

```text
rail structural transaction
  -> capture current physical connections and crossing candidates
  -> build a candidate normalized node/edge graph
  -> validate connectivity, identities and rail-slice coverage
  -> atomically publish TopologyState(revision + 1)
  -> invalidate revision-keyed derived caches and notify consumers
```

构建失败时，candidate不得替换当前state。当前已发布state继续可读，但触发编辑的事务必须报告失败/回滚，不能让Core connection与published state永久分叉。

## 3. 内部机制与关键算法

### 3.1 图归一化

构建器先把rail endpoint connection表示成ports。以下位置成为结构节点：可选择continuation的turnout、单端terminus，以及无结构节点纯环所需的deterministic anchor；其余普通二度连接不是节点。菱形交叉不改变任一线路的continuation，因此不是结构节点，也不切分寻路edge。

从每个结构节点的尚未访问port开始，按唯一continuation连续行走，直到下一个结构节点；沿途按行进次序记录`RailSliceRef(railId,startAb,endAb)`并形成一个`TrackEdge`。每个物理rail slice必须被恰好一个edge覆盖；重复、遗漏、断裂或方向不相容使candidate无效。若一个连通分量全部是二度连接而没有结构节点，构建器选择一个可确定重建的anchor再执行同样遍历，避免产生无端点edge。

At-grade diamond不改变物理continuation：空间相交不产生跨线连接，不成为结构节点，也不切分edge。Core在rail加入/替换时先从`RailSpatialIndex`相同或相邻cell取得候选rail，再把两条curve的有序采样点视为其实际分段线性几何，对候选segment pair执行clamped closest-points计算。索引遍历所有通过AABB筛选的segment pair，包括平行pair，并为每一rail pair只保留三维距离最小的P/Q；距离相同时保留AB遍历顺序中最早的结果。最终获胜的segment pair方向不平行且全局最小距离不超过专用的`AT_GRADE_CROSSING_TOLERANCE_METERS=0.2 m`才形成一个`AtGradeRailCrossing`；因此连接处的平行/重合切线仍可成为全局P/Q并抑制伪交叉，但平行邻线本身不会形成diamond。超过20 cm高度差的桥梁和更远候选同样不会命中。同一rail pair只表达一个交叉；暂不支持同一对复杂曲线多次相交。

检测索引为每条rail缓存一次真实piecewise-linear断点，并保留按rail UUID pair组织的唯一交叉观测；它不调用会把直线扩成1m虚拟点的`Curve.stream()`。连续共线断点会被精确压成一个segment；同一份断点同时供spatial cell填充与交叉exact phase使用。rail AABB与segment AABB先排除不可能相交的组合，再运行closest-points。新增或替换一条rail只删除并重算包含该rail的pair；删除rail同步删除cache与这些pair。纯连接更新无需重做几何：若全局最近的P/Q分别位于两rail相连端点的20 cm弧长范围内，且这两个端点属于同一physical connection group，builder就排除该观测。该规则同时覆盖普通接头和道岔直股/侧股，不读取或特判`NodeKind.TURNOUT`；几何接近但没有连接关系的端点仍保留。暂不支持同一对已连接rail在远处再次形成独立平交；这种线路需要先切分rail。最终`AtGradeRailCrossing`只保存两个persistent rail UUID与canonical AB弧长，是同一`TopologyState`root内的immutable几何事实；它没有资源身份。Basic针对该root绑定共享资源，rail删除或移动后旧binding随旧root失效。

### 3.2 edge与rail位置映射

`TrackEdge`内部保存有序rail slices及累计edge距离。edge distance映射到物理位置时，在严格递增的slice累计终点中查找包含区间，再按slice方向转换为canonical AB弧长；rail AB位置通过`railProjection`定位其edge slice并执行反向换算。边界命中必须使用一致的半开/闭区间规则，避免同一位置同时属于两个slice；具体规则在API冻结前补齐。

路径起点、终点或停车点位于edge内部时，查询层创建仅属于该次计算的virtual node/edge slices，不修改或发布新的`TopologyState`。

## 4. 概念数据模型与所有权

下列拓扑读取模型已经位于`core.api.physics.rail.topology`；构建input/builder仍是Core internal：

```kotlin
data class TopologyState(
    val revision: Long,
    val nodes: Map<TopologyNodeId, TopologyNode>,
    val edges: Map<TrackEdgeId, TrackEdge>,
    val railProjection: Map<UUID, List<TrackEdgeSlice>>,
    val atGradeCrossings: List<AtGradeRailCrossing>
)

data class AtGradeRailCrossing(
    val first: RailCrossingPoint, // lower rail UUID, canonical AB metres
    val second: RailCrossingPoint
)

sealed interface TopologyNode {
    val id: TopologyNodeId
    data class Turnout(/* ports and legal selectable continuations */) : TopologyNode
    data class Terminus(/* one incident port */) : TopologyNode
    data class LoopAnchor(/* deterministic anchor for a node-free cycle */) : TopologyNode
}

data class TrackEdge(
    val id: TrackEdgeId,
    val first: TopologyPortRef,
    val second: TopologyPortRef,
    val lengthMeters: Double,
    val railSlices: List<RailSliceRef>
)

```

- Core/host 是 `TopologyState` 的唯一 structural owner。`TopologySnapshot` 若保留该名称，只能是对某个已发布 `TopologyState(revision)` 的只读引用/包装，不能重新遍历并复制出另一份独立事实来源。
- 普通二度连接折叠进 `TrackEdge`；edge 仍保存对每段 Core rail 及 canonical AB 范围的投影，保证占用、编辑和持久化诊断可回到物理 rail。
- `Turnout`和`Terminus`是结构节点。`AtGradeRailCrossing`只是可供外部owner绑定到edge/rail slices的几何观测，共享冲突资源身份不在Core，也不是节点。纯环没有结构节点时插入一个可确定重建的`LoopAnchor`，避免把整环表示成无端点edge。
- 路径起点、终点或停车点落在 edge 内部时，寻路查询创建临时 virtual node；不得为每个站台或信号永久切分 authoritative graph。
- 外部模块只能把node/edge对象当作revision-local read model；运营或资源 ownership 由各自模块规范定义，不在Core页重复。

## 5. 不变量、生命周期、线程与版本

### 5.1 唯一版本与发布

每次 structural transaction 基于当前 state 构造候选版本；只有成功且结构确实变化时才原子发布`revision + 1`。成功no-op、完整rollback及失败prepare都保持root与revision不变。若旧的live-action seam在抛错前已经改变结构且Core不能证明回滚完整，world必须进入topology fault并Stall physics，禁止发布frame或继续编辑，不能通过增加revision把部分mutation包装成正常新状态。实现可以让未变化的 immutable maps、nodes 和 edges 结构共享（copy-on-write），不得为每次请求深拷贝整图。读者持有旧 state 时内容永不变化；需要当前性时显式比较 revision。

当前迁移实现用一个volatile `TopologyState`作为revision与图内容的共同publication root；`topologyRevision`从该root派生，不另维护可漂移的counter。事务持write lock捕获live rails，经adapter映射为`TopologyInput`并构建candidate，构建成功后只替换一次root。adapter将source view终点（AB→B、BA→A）连接到target view起点（AB→A、BA→B），但不补写缺失的反向关系；非对称连接是无效物理拓扑，必须拒绝publication，不能只在派生state中自动修复。引用当前world之外rail同样使构建失败。`activeRail`不进入input或fingerprint。

迁移期间 `RailView.nextRails` 可以作为 Core 内部编辑模型或构建新 state 的适配输入，但不得与 `TopologyState` 长期并列为两个可独立修改的 authoritative graph。`RailView.connect`、单rail restore和world bulk load已经进入同一transaction/publication边界；`mutableNextRails`仍有直接写入caller，这是明确的后续封装缺口，不得把它解释成受支持的旁路authority。完成迁移后，所有结构编辑必须在同一 transaction 中产生下一 state；外部派生缓存必须按topology revision失效并可丢弃重建。

`nextActiveRail`、列车占用、资源 claim、信号显示、方向限制和临时限速都不是 structural topology。它们携带对应 topology revision，以独立 runtime/overlay state 发布；不能通过修改 `TopologyState` 表达一次道岔选择或一个信号变色。

`TopologyState`是从current rail infrastructure派生的runtime索引，不是独立持久化边界。world load先恢复持久化rail UUID、geometry和双向physical connections，再在同一个structural transaction中重建并校验state；runtime `topologyRevision`随本次world实例重新开始。长期overlay不得持久化revision-local node/edge ID，应保存stable rail UUID加canonical AB interval或独立stable infrastructure ID，并在load/apply时投影到current state。若未来需要跨重启stable node/edge identity，必须先单独冻结身份与migration合同，不能直接把当前派生state写入存档。

### 5.2 连通与外部引用

`TrackEdge`只表达两个结构节点之间不分岔的连续物理路径。Diamond不参与continuation，两条原有线路保持各自edge；其冲突身份与资源绑定由外部资源owner定义。Turnout对正向/背向遍历使用同一稳定node ID，只是进入port和合法continuation不同。

外部overlay、资源或冻结路线可以使用有向 edge interval、node/subgraph reference 或stable rail UUID加canonical AB interval。它们必须记录解析所依据的topology revision；revision变化后重新解析或拒绝，不能把旧node/edge对象与新root组合。它们之间是否允许重叠、如何选路或如何claim由对应owner spec决定，Core不通过增加结构节点表达这些运营规则。

## 6. 校验与错误矩阵

| 条件 | 结果 | mutation |
| --- | --- | --- |
| physical connection不对称、引用外部world rail或coverage不完整 | topology build失败 | 旧state保持published |
| consumer使用旧revision的node/edge对象解析新state | stale reference；由consumer拒绝 | Core state无变化 |
| consumer试图在diamond空间交点跨线 | 图中不存在该continuation | Core state无变化 |
| overlay 引用不存在的 edge/slice | 对应配置 `INVALID_REFERENCE` | 不应用配置 |
| 纯环无法得到确定 anchor | topology build 失败 | 旧 state 保持 published |

外部解析结果若缓存，cache identity必须至少包含topology root/revision；任一相关revision改变即失效，不能跨版本复用node/edge对象身份。

## 7. 性能、复杂度与退化行为

- graph normalization对输入rail slices与connections做一次`O(V + E)`遍历；为生成不依赖输入集合顺序的revision-local ID，还需`O(V log V)`确定性排序。该工作只发生在structural edit，不进入每tick路径。
- graph candidate仍可在structural edit时完整重建；平交检测按新增/替换rail增量更新。设目标rail压缩后采样段数为`S`、空间候选集合为`C`，采样只对新增rail执行一次，当前精确检测最坏成本为`O(sum(S * segments(candidate)))`，但rail/segment AABB先剪枝；长直轨压缩后只有一个segment。该工作只发生在结构编辑，不进入寻路、资源申请、信号查询或每列车tick。cell broad phase可以产生多余候选，但exact phase必须拒绝；不得用cell命中直接认定交叉。
- edge位置查询应利用累计距离索引而不是线性扫描长edge；目标签名冻结时必须明确边界规则和复杂度。
- 旧state可在读者持有期间继续存在；实现必须有有界保留策略或正常对象生命周期，不能为每个tick复制整图或永久保存所有revision。
- 任一结构coverage不完整或revision不一致均fail closed；不得退化为读取live mutable adjacency补齐。

## 8. Good / Base / Bad cases

- Good：两条 rail 形成普通二度连接；state 把它们投影进同一 edge，站台 overlay 引用 edge 中段，寻路不多处理一个无意义节点。
- Good：两条rail在空间相交但没有physical connection；各自edge连续通过，图中不存在跨线continuation。
- Base：无节点纯环生成一个 deterministic `LoopAnchor`；查询起终点再用 virtual nodes 临时切分 edge。
- Bad：Core 更新 `nextRails`，consumer继续使用自己早先复制的 adjacency 并称其为当前 snapshot；必须由同一次 topology publication 产生新 state并让旧revision失效。

## 9. 必需测试与验证

- state publication：读者永远看到完整旧版或完整新版；同一 transaction 只发布一个 revision；旧 state 内容不变。
- transaction failure：prepare/no-op/完整rollback不增加revision；mutation后异常保持旧revision并fault/Stall，后续frame与edit均fail closed。
- graph normalization：二度链折叠、terminus、turnout、纯环anchor、diamond不切edge、rail slice/AB投影往返。
- edge lookup：累计距离边界、首尾slice、正反方向、二分命中和virtual split不修改published state。
- diamond topology：空间相交不新增continuation/结构节点、不切edge并禁止跨线。
- external reference：edge内virtual split不修改state；旧revision引用不能与新root混用。

```bash
./gradlew :Core:test
git diff --check
```

## 10. 迁移、未决事项与错误/正确对照

迁移顺序：先让Core transaction生成唯一state，再让所有外部consumer改读该state，随后删除复制adjacency作为权威来源的可能性。迁移期adapter必须共享同一revision boundary，不能允许两边分别更新。

实现前仍需冻结：edge slice边界归属、订阅API、旧revision保留策略，以及topology build失败时Core结构编辑的rollback接口。当前node/edge ID明确为revision-local runtime identity，不承诺split/merge或restart后稳定。这些未决项不能由首个实现私自决定后反向固化成API。

```text
错误：Core live adjacency + consumer copied snapshot + private graph 三者分别更新
正确：Core 发布唯一 immutable TopologyState；其他模块只引用 revision 或构建可丢弃缓存

错误：把菱形交叉、站台、信号、闭塞边界全部做成永久结构节点
正确：结构图只保留改变连通选择的道岔、尽头和环锚点；菱形交叉及运营对象由外部owner引用rail/edge interval
```
