# Core 版本化铁路拓扑契约

## 1. 状态、范围、触发条件与Owner

- **Status**：`TARGET`；当前只有`topologyRevision`和`RailView.nextRails`，尚无本页描述的统一`TopologyState`。
- **Owner**：Core/host拥有物理结构及其publication；SimpleATO和Basic只能消费指定revision。
- **Non-goals**：本页不定义运营寻路策略、信号显示规则、claim公平性或持久化schema。

触发：新增或修改铁路图、寻路输入、道岔/尽头身份、菱形交叉资源绑定、拓扑 revision、保护区域解析，或把 `RailView.nextRails` 传给 Core 以外的长期运行逻辑。

本页定义唯一 authoritative 的物理连通结构。Core/host 拥有并发布一个不可变、带 revision 的 `TopologyState`；SimpleATO 在该状态上选择运营路径并形成资源意图；Basic 只验证意图是否完整匹配该状态、推导物理锁闭 footprint，并原子执行。站台、闭塞区间、保护区域、信号边界、限速和运行方向是引用拓扑的运营 overlay，不是第二张连通图。

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

SimpleATO读取一个已发布版本及其运营overlay，选择冻结路径并生成typed resource intent；Basic针对同一版本验证intent、编译物理footprint并执行claim。验证过程中若当前revision变化，整个请求失败并由SimpleATO在新版本上重算；Basic不修改旧state，也不替SimpleATO换路。

构建失败时，candidate不得替换当前state。当前已发布state继续可读，但触发编辑的事务必须报告失败/回滚，不能让Core connection与published state永久分叉。

## 3. 内部机制与关键算法

### 3.1 图归一化

构建器先把rail endpoint connection表示成ports。以下位置成为结构节点：可选择continuation的turnout、单端terminus，以及无结构节点纯环所需的deterministic anchor；其余普通二度连接不是节点。菱形交叉不改变任一线路的continuation，因此不是结构节点，也不切分寻路edge。

从每个结构节点的尚未访问port开始，按唯一continuation连续行走，直到下一个结构节点；沿途按行进次序记录`RailSliceRef(railId,startAb,endAb)`并形成一个`TrackEdge`。每个物理rail slice必须被恰好一个edge覆盖；重复、遗漏、断裂或方向不相容使candidate无效。若一个连通分量全部是二度连接而没有结构节点，构建器选择一个可确定重建的anchor再执行同样遍历，避免产生无端点edge。

At-grade diamond在rail放置/更新时通过spatial candidate index加exact geometry与vertical-clearance校验建立。它保存一个稳定conflict identity及两条线路上的route-bound anchors/slices；反向索引把任一线路的traversal映射到同一个资源ID。空间相交从不产生跨线continuation，删除/移动rail必须在同一topology transaction中更新或拒绝该resource binding。

### 3.2 edge与rail位置映射

`TrackEdge`内部保存有序rail slices及累计edge距离。edge distance映射到物理位置时，在严格递增的slice累计终点中查找包含区间，再按slice方向转换为canonical AB弧长；rail AB位置通过`railProjection`定位其edge slice并执行反向换算。边界命中必须使用一致的半开/闭区间规则，避免同一位置同时属于两个slice；具体规则在API冻结前补齐。

路径起点、终点或停车点位于edge内部时，查询层创建仅属于该次计算的virtual node/edge slices，不修改或发布新的`TopologyState`。

### 3.3 intent完整性验证

Basic以线性zipper同时遍历冻结route、`DirectedTrackEdgeRef`和controlled transitions：每跨过一个结构节点就根据入/出port得到期望transition；普通连接期望无资源，turnout期望恰好一个同ID selection intent。与此同时按edge/rail interval反向索引收集diamond等route-bound共享资源。缺失、重复、额外或顺序不同立即拒绝。只有验证完成后才能从rail projection、resource binding和node conflict envelope编译claim、physical locking与release slices。

## 4. 概念数据模型与所有权

下列代码是尚待字段冻结的概念shape，不是已经存在的公开API；职责和数据方向已经冻结：

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

data class RouteResourceIntent(
    val topologyRevision: Long,
    val routeRevision: Long,
    val traversal: List<DirectedTrackEdgeRef>,
    val transitions: List<ControlledTransitionIntent>
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
- SimpleATO 拥有运营寻路、方向限制、停站/时刻表路线选择及 `RouteResourceIntent` 的生成。通用的纯图算法可以暂时作为共享实现复用，但不能拥有运营策略或 Basic 资源状态。
- Basic 拥有 intent validator、物理占用/冲突 footprint 编译、claim、道岔执行、回滚和释放；Basic 不替调用方选择或发现运营路径。

## 5. 不变量、生命周期、线程与版本

### 5.1 唯一版本与发布

每次 structural transaction 基于当前 state 构造候选版本；只有成功且结构确实变化时才原子发布`revision + 1`。成功no-op、完整rollback及失败prepare都保持root与revision不变。若旧的live-action seam在抛错前已经改变结构且Core不能证明回滚完整，world必须进入topology fault并Stall physics，禁止发布frame或继续编辑，不能通过增加revision把部分mutation包装成正常新状态。实现可以让未变化的 immutable maps、nodes 和 edges 结构共享（copy-on-write），不得为每次请求深拷贝整图。读者持有旧 state 时内容永不变化；需要当前性时显式比较 revision。

迁移期间 `RailView.nextRails` 可以作为 Core 内部编辑模型或构建新 state 的适配输入，但不得与 `TopologyState` 长期并列为两个可独立修改的 authoritative graph。完成迁移后，所有结构编辑必须在同一 transaction 中产生下一 state；Basic 的保护区域缓存只按 `(topologyRevision, regionRevision)` 派生并可丢弃重建。

`nextActiveRail`、列车占用、资源 claim、信号显示、方向限制和临时限速都不是 structural topology。它们携带对应 topology revision，以独立 runtime/overlay state 发布；不能通过修改 `TopologyState` 表达一次道岔选择或一个信号变色。

### 5.2 连通与 overlay

`TrackEdge`只表达两个结构节点之间不分岔的连续物理路径。Diamond不参与continuation；两条原有线路保持各自edge，两个route anchor共享同一个conflict identity。Turnout对正向/背向遍历使用同一稳定node ID，只是进入port和合法continuation不同。

运营 overlay 使用有向 edge interval、node/subgraph reference 或二者组合：

- 站台/停车泊位：位置与可容纳长度；
- 普通闭塞区间：区间边界与方向；
- 保护区域：玩家边界解析得到的连通子图及 revision；
- 信号、限速、运行方向和线路/时刻表引用。

不同 overlay 可以合法重叠，例如站台位于闭塞区间内；重叠是否冲突由各自 authoring contract 决定。普通闭塞区间与保护区域不得重叠，是 SimpleATO 配置校验规则，不是 Core 通过拆分结构节点强制的规则。

### 5.3 路径意图与完整性校验

SimpleATO 提交有序 traversal 和每个受控 transition 的 typed intent。Basic 使用同一个 `TopologyState(intent.topologyRevision)` 做确定性验证：

1. edge 连续、方向和起终点与冻结 route 一致；
2. 每个声明的turnout身份、port和continuation存在且合法；route-bound diamond身份与anchor来自host索引而非caller伪造；
3. route实际经过的每个受控节点和绑定资源恰好出现一次，不得缺失、重复或额外声明；
4. 保护区域 entry/exit 与 traversal 相交结果完整，出口净空可由 rail projection 推导；
5. 由验证后的 traversal 推导 rail intervals、共享 conflict nodes、physical locking intervals 和 release slices。

Basic 不能只逐项检查“调用方报的资源存在”，否则漏报一个未选支路的物理锁闭范围仍会被接受。Basic 也不能自行搜索另一条可行路径来修正 intent；不匹配必须 typed denial，由 SimpleATO 在新状态上重算。

## 6. 校验与错误矩阵

| 条件 | 结果 | mutation |
| --- | --- | --- |
| intent revision 不是当前已发布 revision | `STALE_TOPOLOGY` | 无 |
| edge 不连续、port/continuation 非法 | `INVALID_ROUTE` | 无 |
| controlled transition 缺失、重复或额外 | `INCOMPLETE_RESOURCE_INTENT` | 无 |
| route plan试图在diamond空间交点跨线 | 因图中不存在该continuation而返回`INVALID_ROUTE` | 无 |
| overlay 引用不存在的 edge/slice | 对应配置 `INVALID_REFERENCE` | 不应用配置 |
| validation 后、commit 前 revision 改变 | `STALE_TOPOLOGY` | 无 claim、无 selection |
| intent 完整但物理占用或 claim 冲突 | `PHYSICALLY_OCCUPIED` / `RESOURCE_CONFLICT` | 无 partial mutation |
| 纯环无法得到确定 anchor | topology build 失败 | 旧 state 保持 published |

校验/编译结果若缓存，cache identity必须覆盖所有会改变语义的topology、route、region revision及clearance输入；精确key shape属于实现。任一相关revision改变即失效，不能跨版本复用node/edge对象身份。

## 7. 性能、复杂度与退化行为

- graph normalization对输入rail slices与connections至少是`O(V + E)`；diamond几何检测先用spatial index缩小候选，再做exact geometry/vertical-clearance检查，禁止每tick全图两两相交扫描。
- 初版可以在structural edit时重建完整candidate state；不得在寻路、信号查询或每列车tick时重建。后续增量化只能改变构建成本，不能改变原子publication和完整覆盖校验。
- edge位置查询应利用累计距离索引而不是线性扫描长edge；目标签名冻结时必须明确边界规则和复杂度。
- intent完整性验证对route长度为线性复杂度；物理footprint编译复用同一次遍历，不得再做第二次全图寻路。
- 旧state可在读者持有期间继续存在；实现必须有有界保留策略或正常对象生命周期，不能为每个tick复制整图或永久保存所有revision。
- 任一索引缺失、coverage不完整或revision不一致均fail closed；不得退化为读取live mutable adjacency补齐。

## 8. Good / Base / Bad cases

- Good：两条 rail 形成普通二度连接；state 把它们投影进同一 edge，站台 overlay 引用 edge 中段，寻路不多处理一个无意义节点。
- Good：菱形交叉不切分原有edge；两条rail anchors通过反向索引命中同一个conflict ID，因此不能互相转线，但claim会在共享资源上冲突。
- Base：无节点纯环生成一个 deterministic `LoopAnchor`；查询起终点再用 virtual nodes 临时切分 edge。
- Bad：Core 更新 `nextRails`，Basic 继续使用自己早先复制的 adjacency 并称其为当前 snapshot；必须由同一次 topology publication 产生新 state 并让旧 revision 失效。
- Bad：ATO 只提交所选 turnout branch，Basic 因资源存在便批准，却没有核对 route 穿越的全部 controlled nodes 和物理锁闭 legs。

## 9. 必需测试与验证

- state publication：读者永远看到完整旧版或完整新版；同一 transaction 只发布一个 revision；旧 state 内容不变。
- transaction failure：prepare/no-op/完整rollback不增加revision；mutation后异常保持旧revision并fault/Stall，后续frame与edit均fail closed。
- graph normalization：二度链折叠、terminus、turnout、纯环anchor、diamond不切edge、rail slice/AB投影往返。
- edge lookup：累计距离边界、首尾slice、正反方向、二分命中和virtual split不修改published state。
- diamond：不新增continuation/结构节点、禁止跨线、双anchor共享conflict ID、放置/更新几何检测、resource反向索引与revision失效。
- overlay：edge 内 virtual node 查询不修改 state；站台与闭塞可重叠；保护区域与普通闭塞重叠被 SimpleATO 配置层拒绝。
- intent validator：零/一/多受控节点，正反方向获得同一资源身份，缺失/重复/额外 transition，stale-before 和 stale-before-commit。
- transaction：完整 intent 编译成 footprint 后原子 claim/apply；任何失败不留 claim、不改变 selection。

```bash
./gradlew :Core:test :Basic:test
git diff --check
```

## 10. 迁移、未决事项与错误/正确对照

迁移顺序：先让Core transaction同时生成唯一state，再让保护区域与route planner改读该state，随后删除Basic复制adjacency作为权威来源的可能性。迁移期adapter必须共享同一revision boundary，不能允许两边分别更新。

实现前仍需冻结：node/edge稳定ID在split/merge后的规则、edge slice边界归属、state获取/订阅API、旧revision保留策略，以及topology build失败时Core结构编辑的rollback接口。这些未决项不能由首个实现私自决定后反向固化成API。

```text
错误：Core live adjacency + Basic copied snapshot + SimpleATO private graph 三者分别更新
正确：Core 发布唯一 immutable TopologyState；其他模块只引用 revision 或构建可丢弃缓存

错误：把菱形交叉、站台、信号、闭塞边界全部做成永久结构节点
正确：结构图只保留改变连通选择的道岔、尽头和环锚点；菱形交叉是route-bound共享资源，运营对象以overlay引用edge interval

错误：Basic 根据保护区域再次寻路并替 ATO 选择资源
正确：SimpleATO 提交有序 typed intent；Basic 证明完整性、推导物理 footprint 并原子执行
```
