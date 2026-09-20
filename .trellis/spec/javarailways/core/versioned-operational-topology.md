# Core 版本化铁路拓扑契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`PARTIAL`；已有revision-scoped public read-only `TopologyState`、纯归一化构建器及`CoreWorld`原子publication；尚未迁移Basic/SimpleATO，也尚未实现edge-distance查询层。
- **Owner**：Core/host拥有物理结构及其publication；SimpleATO和Basic只能消费指定revision。
- **Non-goals**：本页不定义运营寻路策略、信号显示规则、claim公平性或持久化schema。

触发：新增或修改铁路图、寻路输入、道岔/尽头身份、菱形交叉资源绑定、拓扑 revision、保护区域解析，或把 `RailView.nextRails` 传给 Core 以外的长期运行逻辑。

本页定义唯一 authoritative 的物理连通结构。Core/host 拥有并发布一个不可变、带 revision 的 `TopologyState`；SimpleATO 在该状态上选择并冻结运营路径；Basic 只验证该路径、从同一状态派生全部route-bound资源与物理锁闭footprint，并原子执行。站台、闭塞区间、保护区域、信号边界、限速和运行方向是引用拓扑的运营 overlay，不是第二张连通图。

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

SimpleATO读取一个已发布版本及其运营overlay，选择冻结路径并将该路径绑定到topology revision；Basic针对同一版本验证路径，在一次线性扫描中投影资源、编译物理footprint并执行claim。验证过程中若当前revision变化，整个请求失败并由SimpleATO在新版本上重算；Basic不修改旧state，也不替SimpleATO换路。

构建失败时，candidate不得替换当前state。当前已发布state继续可读，但触发编辑的事务必须报告失败/回滚，不能让Core connection与published state永久分叉。

## 3. 内部机制与关键算法

### 3.1 图归一化

构建器先把rail endpoint connection表示成ports。以下位置成为结构节点：可选择continuation的turnout、单端terminus，以及无结构节点纯环所需的deterministic anchor；其余普通二度连接不是节点。菱形交叉不改变任一线路的continuation，因此不是结构节点，也不切分寻路edge。

从每个结构节点的尚未访问port开始，按唯一continuation连续行走，直到下一个结构节点；沿途按行进次序记录`RailSliceRef(railId,startAb,endAb)`并形成一个`TrackEdge`。每个物理rail slice必须被恰好一个edge覆盖；重复、遗漏、断裂或方向不相容使candidate无效。若一个连通分量全部是二度连接而没有结构节点，构建器选择一个可确定重建的anchor再执行同样遍历，避免产生无端点edge。

At-grade diamond在rail放置/更新时通过spatial candidate index加exact geometry与vertical-clearance校验建立。它保存一个稳定conflict identity及两条线路上的route-bound anchors/slices；反向索引把任一线路的traversal映射到同一个资源ID。空间相交从不产生跨线continuation，删除/移动rail必须在同一topology transaction中更新或拒绝该resource binding。

### 3.2 edge与rail位置映射

`TrackEdge`内部保存有序rail slices及累计edge距离。edge distance映射到物理位置时，在严格递增的slice累计终点中查找包含区间，再按slice方向转换为canonical AB弧长；rail AB位置通过`railProjection`定位其edge slice并执行反向换算。边界命中必须使用一致的半开/闭区间规则，避免同一位置同时属于两个slice；具体规则在API冻结前补齐。

路径起点、终点或停车点位于edge内部时，查询层创建仅属于该次计算的virtual node/edge slices，不修改或发布新的`TopologyState`。

### 3.3 冻结路线验证与资源投影

Basic以一个单调游标线性遍历冻结route。每个edge occurrence必须存在于指定`TopologyState`、方向合法，并与前后occurrence共享预期port；每跨过一个结构节点，Basic根据入/出port验证continuation，并把turnout选择派生成内部resource projection。与此同时按edge/rail interval反向索引收集diamond等route-bound共享资源，再从rail projection、resource binding和node conflict envelope累积claim、physical locking与release slices。调用方不提交第二份controlled-transition/resource列表，因此不存在“route正确但资源漏报”的可接受状态；任何断裂、非法continuation、无法解析的资源binding或revision变化均在mutation前拒绝。

验证和投影必须复用同一次route扫描；资源投影是不可变的内部编译结果，不是另一份authoritative route DTO。保护区域、固定闭塞和移动闭塞等contributor只消费已验证occurrence并扩展closure，不得重新寻路或重新解释connectivity。

## 4. 概念数据模型与所有权

下列拓扑读取模型已经位于`core.api.physics.rail.topology`；构建input/builder仍是Core internal。后续冻结route引用仍是概念shape，不能由本页示例推断为已经开放：

```kotlin
data class TopologyState(
    val revision: Long,
    val nodes: Map<TopologyNodeId, TopologyNode>,
    val edges: Map<TrackEdgeId, TrackEdge>,
    val railProjection: Map<UUID, List<TrackEdgeSlice>>
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

data class TopologyBoundRoutePlan(
    val topologyRevision: Long,
    val routeRevision: Long,
    val traversal: List<DirectedTrackEdgeRef>
)

data class RouteBoundInfrastructureResource(
    val resource: StableInfrastructureResource,
    val anchors: List<RailSliceRef>
)
```

- Core/host 是 `TopologyState` 的唯一 structural owner。`TopologySnapshot` 若保留该名称，只能是对某个已发布 `TopologyState(revision)` 的只读引用/包装，不能重新遍历并复制出另一份独立事实来源。
- 普通二度连接折叠进 `TrackEdge`；edge 仍保存对每段 Core rail 及 canonical AB 范围的投影，保证占用、编辑和持久化诊断可回到物理 rail。
- `Turnout`和`Terminus`是结构节点。菱形交叉是绑定到edge/rail slices的共享冲突资源，不是节点。纯环没有结构节点时插入一个可确定重建的`LoopAnchor`，避免把整环表示成无端点edge。
- 路径起点、终点或停车点落在 edge 内部时，寻路查询创建临时 virtual node；不得为每个站台或信号永久切分 authoritative graph。
- SimpleATO 拥有运营寻路、方向限制、停站/时刻表路线选择及 topology-bound frozen route 的生成。通用的纯图算法可以暂时作为共享实现复用，但不能拥有运营策略或 Basic 资源状态。
- Basic 拥有 route validator、route-bound resource projection、物理占用/冲突 footprint 编译、claim、道岔执行、回滚和释放；Basic 不替调用方选择或发现运营路径。

## 5. 不变量、生命周期、线程与版本

### 5.1 唯一版本与发布

每次 structural transaction 基于当前 state 构造候选版本；只有成功且结构确实变化时才原子发布`revision + 1`。成功no-op、完整rollback及失败prepare都保持root与revision不变。若旧的live-action seam在抛错前已经改变结构且Core不能证明回滚完整，world必须进入topology fault并Stall physics，禁止发布frame或继续编辑，不能通过增加revision把部分mutation包装成正常新状态。实现可以让未变化的 immutable maps、nodes 和 edges 结构共享（copy-on-write），不得为每次请求深拷贝整图。读者持有旧 state 时内容永不变化；需要当前性时显式比较 revision。

当前迁移实现用一个volatile `TopologyState`作为revision与图内容的共同publication root；`topologyRevision`从该root派生，不另维护可漂移的counter。事务持write lock捕获live rails，经adapter映射为`TopologyInput`并构建candidate，构建成功后只替换一次root。adapter将source view终点（AB→B、BA→A）连接到target view起点（AB→A、BA→B），但不补写缺失的反向关系；非对称连接是无效物理拓扑，必须拒绝publication，不能只在派生state中自动修复。引用当前world之外rail同样使构建失败。`activeRail`不进入input或fingerprint。

迁移期间 `RailView.nextRails` 可以作为 Core 内部编辑模型或构建新 state 的适配输入，但不得与 `TopologyState` 长期并列为两个可独立修改的 authoritative graph。`RailView.connect`、单rail restore和world bulk load已经进入同一transaction/publication边界；`mutableNextRails`仍有直接写入caller，这是明确的后续封装缺口，不得把它解释成受支持的旁路authority。完成迁移后，所有结构编辑必须在同一 transaction 中产生下一 state；Basic 的保护区域缓存只按 `(topologyRevision, regionRevision)` 派生并可丢弃重建。

`nextActiveRail`、列车占用、资源 claim、信号显示、方向限制和临时限速都不是 structural topology。它们携带对应 topology revision，以独立 runtime/overlay state 发布；不能通过修改 `TopologyState` 表达一次道岔选择或一个信号变色。

`TopologyState`是从current rail infrastructure派生的runtime索引，不是独立持久化边界。world load先恢复持久化rail UUID、geometry和双向physical connections，再在同一个structural transaction中重建并校验state；runtime `topologyRevision`随本次world实例重新开始。长期overlay不得持久化revision-local node/edge ID，应保存stable rail UUID加canonical AB interval或独立stable infrastructure ID，并在load/apply时投影到current state。若未来需要跨重启stable node/edge identity，必须先单独冻结身份与migration合同，不能直接把当前派生state写入存档。

### 5.2 连通与 overlay

`TrackEdge`只表达两个结构节点之间不分岔的连续物理路径。Diamond不参与continuation；两条原有线路保持各自edge，两个route anchor共享同一个conflict identity。Turnout对正向/背向遍历使用同一稳定node ID，只是进入port和合法continuation不同。

运营 overlay 使用有向 edge interval、node/subgraph reference 或二者组合：

- 站台/停车泊位：位置与可容纳长度；
- 普通闭塞区间：区间边界与方向；
- 保护区域：玩家边界解析得到的连通子图及 revision；
- 信号、限速、运行方向和线路/时刻表引用。

不同 overlay 可以合法重叠，例如站台位于闭塞区间内；重叠是否冲突由各自 authoring contract 决定。普通闭塞区间与保护区域不得重叠，是 SimpleATO 配置校验规则，不是 Core 通过拆分结构节点强制的规则。

### 5.3 冻结路径与资源投影

SimpleATO提交一份有序traversal，并以`topologyRevision`绑定它读取的`TopologyState`。Basic使用同一个状态做确定性验证和资源投影：

1. edge存在、方向合法，起终点及相邻edge port连续；
2. traversal跨越turnout时，入/出port对应一个合法continuation，Basic由此派生同一稳定turnout身份和目标selection；
3. traversal经过diamond或其他route-bound共享资源anchor时，Basic从host-owned反向索引派生稳定资源身份；caller不能声明、遗漏或伪造该身份；
4. 保护区域entry/exit与traversal相交结果完整，出口净空可由rail projection推导；
5. 同一次扫描累积rail intervals、共享conflict resources、physical locking intervals和release slices，生成只属于本次编译的内部projection。

Basic不能要求SimpleATO同时提交route与资源列表：两者重复表达同一事实，可能产生漏报和冲突。Basic也不能自行搜索另一条可行路径来修正非法traversal；不匹配必须typed denial，由SimpleATO在新状态上重算。

## 6. 校验与错误矩阵

| 条件 | 结果 | mutation |
| --- | --- | --- |
| route绑定的topology revision不是当前已发布revision | `STALE_TOPOLOGY` | 无 |
| edge 不连续、port/continuation 非法 | `INVALID_ROUTE` | 无 |
| route-bound resource index缺失或与topology coverage不一致 | topology/configuration fault，准入fail closed | 无 |
| route plan试图在diamond空间交点跨线 | 因图中不存在该continuation而返回`INVALID_ROUTE` | 无 |
| overlay 引用不存在的 edge/slice | 对应配置 `INVALID_REFERENCE` | 不应用配置 |
| validation 后、commit 前 revision 改变 | `STALE_TOPOLOGY` | 无 claim、无 selection |
| route有效但物理占用或claim冲突 | `PHYSICALLY_OCCUPIED` / `RESOURCE_CONFLICT` | 无partial mutation |
| 纯环无法得到确定 anchor | topology build 失败 | 旧 state 保持 published |

校验/编译结果若缓存，cache identity必须覆盖所有会改变语义的topology、route、region revision及clearance输入；精确key shape属于实现。任一相关revision改变即失效，不能跨版本复用node/edge对象身份。

## 7. 性能、复杂度与退化行为

- graph normalization对输入rail slices与connections做一次`O(V + E)`遍历；为生成不依赖输入集合顺序的revision-local ID，还需`O(V log V)`确定性排序。该工作只发生在structural edit，不进入每tick路径。diamond几何检测先用spatial index缩小候选，再做exact geometry/vertical-clearance检查，禁止每tick全图两两相交扫描。
- 初版可以在structural edit时重建完整candidate state；不得在寻路、信号查询或每列车tick时重建。后续增量化只能改变构建成本，不能改变原子publication和完整覆盖校验。
- edge位置查询应利用累计距离索引而不是线性扫描长edge；目标签名冻结时必须明确边界规则和复杂度。
- route验证与资源投影对扫描到的route长度为线性复杂度；物理footprint编译复用同一次遍历，不得再做第二次全图寻路或要求caller预扫描生成资源清单。
- 旧state可在读者持有期间继续存在；实现必须有有界保留策略或正常对象生命周期，不能为每个tick复制整图或永久保存所有revision。
- 任一索引缺失、coverage不完整或revision不一致均fail closed；不得退化为读取live mutable adjacency补齐。

## 8. Good / Base / Bad cases

- Good：两条 rail 形成普通二度连接；state 把它们投影进同一 edge，站台 overlay 引用 edge 中段，寻路不多处理一个无意义节点。
- Good：菱形交叉不切分原有edge；两条rail anchors通过反向索引命中同一个conflict ID，因此不能互相转线，但claim会在共享资源上冲突。
- Base：无节点纯环生成一个 deterministic `LoopAnchor`；查询起终点再用 virtual nodes 临时切分 edge。
- Bad：Core 更新 `nextRails`，Basic 继续使用自己早先复制的 adjacency 并称其为当前 snapshot；必须由同一次 topology publication 产生新 state 并让旧 revision 失效。
- Bad：ATO同时提交route与自己枚举的turnout/diamond资源，Basic只检查这些资源存在便批准；应由Basic从route和host索引派生完整资源及物理锁闭legs。

## 9. 必需测试与验证

- state publication：读者永远看到完整旧版或完整新版；同一 transaction 只发布一个 revision；旧 state 内容不变。
- transaction failure：prepare/no-op/完整rollback不增加revision；mutation后异常保持旧revision并fault/Stall，后续frame与edit均fail closed。
- graph normalization：二度链折叠、terminus、turnout、纯环anchor、diamond不切edge、rail slice/AB投影往返。
- edge lookup：累计距离边界、首尾slice、正反方向、二分命中和virtual split不修改published state。
- diamond：不新增continuation/结构节点、禁止跨线、双anchor共享conflict ID、放置/更新几何检测、resource反向索引与revision失效。
- overlay：edge 内 virtual node 查询不修改 state；站台与闭塞可重叠；保护区域与普通闭塞重叠被 SimpleATO 配置层拒绝。
- route validator/projector：零/一/多受控节点，正反方向获得同一资源身份，非法continuation、断裂route、route-bound resource index缺失、stale-before和stale-before-commit。
- transaction：验证后的route projection编译成footprint后原子claim/apply；任何失败不留claim、不改变selection。

```bash
./gradlew :Core:test :Basic:test
git diff --check
```

## 10. 迁移、未决事项与错误/正确对照

迁移顺序：先让Core transaction同时生成唯一state，再让保护区域与route planner改读该state，随后删除Basic复制adjacency作为权威来源的可能性。迁移期adapter必须共享同一revision boundary，不能允许两边分别更新。

实现前仍需冻结：edge slice边界归属、订阅API、旧revision保留策略，以及topology build失败时Core结构编辑的rollback接口。当前node/edge ID明确为revision-local runtime identity，不承诺split/merge或restart后稳定。这些未决项不能由首个实现私自决定后反向固化成API。

```text
错误：Core live adjacency + Basic copied snapshot + SimpleATO private graph 三者分别更新
正确：Core 发布唯一 immutable TopologyState；其他模块只引用 revision 或构建可丢弃缓存

错误：把菱形交叉、站台、信号、闭塞边界全部做成永久结构节点
正确：结构图只保留改变连通选择的道岔、尽头和环锚点；菱形交叉是route-bound共享资源，运营对象以overlay引用edge interval

错误：SimpleATO提交route和第二份resource intent，Basic尝试证明两份重复事实一致
正确：SimpleATO只提交topology-bound frozen route；Basic验证route并从同一TopologyState派生完整resource/physical footprint
```
