# Basic route planning 契约

## 适用场景与用途

触发：修改WayPoint route search、direction restriction、expanded/planned route、loop progress或route compilation。

用途：记录Mission、Station、Line、Timetable与诊断命令共用的按实际弧长加权planner。authoritative输入是Core发布的版本化`TopologyState`；Basic保存轨道公共方向并提供纯图算法与冻结路线校验/资源投影，SimpleATO拥有停站、线路和时刻表等路径选择策略。两者都不拥有另一张物理图。

运营路线的生产调用统一经过SimpleATO的`OperationalRoutePlanner`：每次查询捕获一个immutable topology root，在Dijkstra前组合运行方向与已注册plugin source，再调用Basic的`TopologyRoutePlanner`一次。下述纯物理测距不选择运营路线。旧的live-`RailView.nextRails` `RoutePlanner`和二次live `PlannedRouteCompiler`已经删除；Dispatcher、准入和时刻表应用不重新寻路。

## Owner、输入输出与坐标

`TopologyRoutePlanningInput`输入同一个`TopologyState`、编译后的neutral restrictions、canonical AB米表示的起终点、route identity/revision与optional start/end `ViewDirection`。通用planner不查询Destination、Station或OperatingDirection store，也不推断运营方向；未传入端点方向表示该端点没有额外要求。

```kotlin
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/route/ExpandedRoute.kt`。segment arcs是该view-local meters，finite且`0<=start<=end`；`ExpandedRoute`segments defensive copy、nonempty、total length positive。SimpleATO adapter移除Topology planner为端点方向保留的零长度marker，只把正长度段交给既有timetable/mission consumers。

`OperationalRoutePlanner.buildRoute(...): RouteBuildResult.Success(route)|Failure(reason)`使用Basic Dijkstra的actual metre cost。same rail goal在start后direct；在start前只可经合法loop重新进入。`PlannedRoute`只保存route identity、mission revision和已经由同一次immutable topology query选出的`ExpandedRoute`；它不保存信号边界、保护点、资源claim或道岔准备参数。

后续Basic准入仍独立验证冻结traversal连续且每个continuation合法，并把沿途rail intervals、受控transition与route-bound shared resources加入唯一claim集合。物理占用范围只是资源binding的验证数据，不是第二份footprint。Basic不得要求调用方另报资源清单，也不得在验证时另行寻路或用保护区域替调用方选择路径。

### 已知查询端点保真（CURRENT）

`TopologyRoutePlanner` 的查询输入是原始 canonical AB 米坐标。搜索将其换算为合并图边上的距离，
但普通图弧展开回轨道段时，若切点的轨道 ID 与图边距离精确匹配本次查询的起点或终点，直接沿用
该查询点的 canonical 坐标，再按段方向转换成 view-local 坐标。其余位置仍用既有轨道切片换算。
不能假定浮点的“加前缀再减前缀”可逆，也不能用近似判等加零长度 marker 代替正长度段端点保真：
SimpleATO 的适配器会过滤方向 marker。

例如查询终点 `109.1008889681433` 不得在普通段输出中因累计距离往返变成
`109.10088896814318`。BA 仍遵守一次 `railLength - canonicalAb` 的现有表示，不增加坐标字段。
只有两个查询局部锚点，每个切片检查成本为 O(1)，不增加搜索复杂度、持久化或可变共享状态。
缺失/非法输入和不可达仍走既有拒绝分支；宏路径仍完整使用原始 span，不能为端点“对齐”而截断宏。
本规则不放宽搜索位置判等，不将与宏端点近似相同的另一位置自动吸附到它。

回归由 `TopologyRoutePlannerTest` 和 `OperationalRoutePlannerTest` 覆盖：合并边内端点、AB/BA、
反向存储轨道、未指定起始方向、零长度方向 marker、完整宏前后普通段以及适配后的正长度段，
以不带 delta 的端点断言验证；既有宏内部不可退出和拓扑连续性测试继续通过。无需迁移。

### 公开路线值与内部实现的边界

当前 `basic.api.route` 直接拥有 `RailViewRef`、`RouteSegment`、`ExpandedRoute` 和 `PlannedRoute`，
SimpleATO 的 `CompiledLineLeg.route`、`AtoMissionLeg.plannedRoute` 和停站解析接口使用这些同一类型。
不再通过公开签名泄漏隐藏的 `basic.route` 类型，也不创建用于跨包转换的第二套路线。
`RouteBuildResult`与`RouteAllocationTiming`属于SimpleATO运营策略（`work.art1st.javarailways.simpleato.routing`）；Basic保留通用`TopologyRoutePlanner`、`TopologyRoutePlanningResult`、snapshot与距离/制动原语，路线申请 retry ticks 由 SimpleATO Dispatcher 持有，平台通过 `ato.admission-retry-ticks` 注入，Basic 不保存 ATO 重试策略。寻路实现、进度计算与`toSnapshot`转换仍属于各自内部实现包。

一次正常调用由 SimpleATO 选择路线，产出有序的 view-local 段，再包装为带 `routeId/revision` 的 `PlannedRoute`。
它们只校验数值和集合形状：段允许零长，路线必须非空且总长有限、严格为正；端点 ID 和 route ID 必须非空白，
revision 必须非负。构造失败抛 `IllegalArgumentException`，不改变世界或资源状态。
例如一个 BA 段的 5–10 米是沿 BA view 的弧长，不是 canonical AB 坐标；同一 rail 可在环线路线中重复出现。

这些值不保存 world、拓扑版本、live rail 或授权，`revision` 也不代表拓扑版本。
构造 `PlannedRoute` 不证明物理连续性、可达性或资源已获准；绑定拓扑版本的 `RouteSnapshot` 仍由后续准入独立校验。
除 `RailViewRef.from(view)` 读取调用方提供的 view 外，构造与读取都是无 I/O 的纯值操作，可跨线程共享；
调用 `from` 时须遵守该 view 所属世界的线程规则。对象没有注册/卸载生命周期，不新增持久化格式或版本。

`ExpandedRoute` 构造时以 `O(n)` 复制并校验段列表；输出列表不可修改，私有 data-class copy 不能绕过复制。
`totalLength` 每次读取为 `O(n)` 求和，本批不增加缓存、不改变算法。
现有 `ExpandedRouteTest` 保留集合隔离断言，独立 Java consumer 同时检查值的构造和 SimpleATO 公开返回类型；
Dokka 须生成四个值类型的页面，而不是放开整个实现包。非法值及实际连通性继续由既有路线/准入回归覆盖。

### 只读物理测距

`CURRENT`（2026-10-15）：公开`basic.api.route.planning.PhysicalRailDistance.between(topology, startRailId, startAbMeters, endRailId, endAbMeters): Double?`，位置以轨道UUID和canonical AB米表示。返回最短物理弧长（米），仅不可达返回null；缺失轨道、非有限/越界位置抛`IllegalArgumentException`。两端均合法且为同一位置时返回0。无端点朝向限制，不查询运营方向、信号、占用或当前道岔选择；合法continuation仍由Core拓扑决定。

该API是对现有内部`TopologyRoutePlanner.physicalDistance`的最小纯查询入口：构造空运营限制，复用现有查询图与Dijkstra，直接求所选图边的米制权重之和，不构造运营RouteSnapshot。这样合法公共端点可返回0，而运营`plan`原有零长拒绝保持不变；不复制算法，不公开内部planner/input/result类型，不申请资源或创建world注册。输入只读且可跨线程查询，结果只对传入拓扑成立；调用方负责检查world生命周期与拓扑是否仍有效。构图、搜索与空间成本沿用本页现有算法，低频按需执行，不增加缓存或搜索上限。

Basic测试需覆盖同点校验、非法输入、断开、跨轨道分支和环的米制最短结果；API签名/KDoc与Dokka公开范围同步。比如空间重合但无合法连接的交叉轨道不能返回0；不能用几何最近点或当前道岔选通替代物理最短路。它是几何工具能力而非SimpleATO运营选路seam，不改变既有运营调用链，无持久化格式或版本变化。
沿用搜索的`1e-9`米位置判等精度，处于该精度内的合法位置可能测为0；非法坐标仍须先拒绝，不能以“同点”跳过输入校验。

## 不可变拓扑寻路与限制

`basic.api.route.planning`只暴露物理中立值：`DirectedRailSpan`、`PhysicalTraversalPath`、
`PathReplacement`、`RoutePlanningRestrictions`和低频编译用的
`RoutePlanningRestrictionSource`。其中span使用canonical AB米范围加行驶方向；restriction绑定一个
topology revision并防御性复制集合。接口不知道保护区域、闭塞、信号、联锁或资源claim。

正常流程是：SimpleATO通过world-local source runtime捕获按namespaced source ID排序的不可变快照，在搜索前
依次调用各owner source一次，把运行方向和插件私有策略编译并合并成不可变restrictions；Basic一次性校验并建立
query-local图；Dijkstra只枚举已经准备好的图边，不在扩展循环里回调插件。`forbiddenTraversals`删除指定方向上的物理区间；`PathReplacement`删除
`replacedTraversals`的自由通行，并把每个`permittedPath`加入为一条不可拆分的有向macro。搜索选中
macro后才展开成普通`RouteSegmentSnapshot`，后续仍由Basic route projector独立验证。

编译器在对应位置临时切分normalized edge，不修改`TopologyState`。它拒绝缺失/越界rail、跨越
replacement覆盖范围的macro、不连续或不合法道岔路径，以及两个replacement在相同有向正长度
区间上的重叠。绝对禁止与macro相交时，该macro不加入搜索。起点或终点位于replacement内部时，
只有插件显式提供以该点为端点的完整path才可能到达；搜索不会自动截断macro。

设normalized topology为`V/E`，限制span总数为`S`，macro展开后的物理span总数为`P`，临时切点为
`C`。编译和物理连续性验证为`O(V+E+S log S+P)`量级，query-local图规模为`O(V+C+P)`，
Dijkstra为`O((V+C+A) log(V+C))`，其中`A`为普通、结构连接及macro边总数。若注册source数为`K`，
SimpleATO的source snapshot读取为`O(1)`，稳定顺序在低频replacement时以`O(K log K)`建立，一次查询调用与合并为
`O(K+S+P)`加各source自身编译成本；tick、资源等待和Dijkstra扩展内部均不执行插件代码。

## Route result矩阵

| 条件 | result |
| --- | --- |
| missing rail/required direction无候选 | `Failure`含waypoint/方向 |
| disconnected/unreachable | `Failure`，无partial segments |
| 多条route | actual meter total最短；不按hop数 |
| same rail goal behind | direct非法；若loop存在可成功重复rail |
| stale restrictions/topology mismatch | typed rejection，无partial route |
| transition非endpoint/不合法continuation | query graph不生成该arc |
| plugin禁止branch或完整path | 只在query-local graph收紧/替换 |
| facing/trailing turnout | planner只选择Core legal continuation；后续物理资源编译投影canonical turnout identity |
| straight/turnout route | 都不生成signal section或synthetic protection point |

`MissionRouteLayout.project(position,previousProgress)`按facing转换AB→view arc，并用previous progress从repeated rail candidates选monotonic值。

## 项目内正确示例

Planner把canonical rail facing到normalized edge direction的转换集中在一个函数：

```kotlin
val runsFirstToSecond = if (facing == ViewDirection.AB) railRunsFirstToSecond else !railRunsFirstToSecond
```

来源：`TopologyRoutePlanner.kt`。这避免caller多次翻转或把BA view arc写回persisted WayPoint。

## 禁止实现

- 重新引入legacy label BFS `PathFinder`作为mission/timetable生产寻路（旧实现及专用索引已删除）；
- 以rail hop count作为最短；
- 返回partial unreachable route；
- 在准入时跳过stable topology revalidation；
- 新功能继续把`RailView.nextRails`或Basic复制的adjacency当成第二个authoritative graph；
- 在Dijkstra扩展期间调用插件callback，或让插件直接修改Core `TopologyState`；
- 把保护区域/进路表类型加入Basic；插件只能提交中立有向区间和完整物理path；
- 把一条permitted path拆成若干可自由拼接的边，从而产生进路表未列出的混合路径；
- Basic收到非法或不连续route后自行搜索并补齐一条运营路径；
- `TopologyRoutePlanner`隐式查询Basic/SimpleATO metadata；运营限制必须由SimpleATO在查询前显式编译；
- repeated rail progress取第一个candidate导致倒退。

## 必需测试与验证

- `TopologyRoutePlannerTest.kt`：more hops但实际米数更短、disconnected、same-edge/loop与方向限制。
- `OperationalRoutePlannerTest.kt`：运行方向/source组合只在查询前执行、端点方向和零长度marker适配。
- `ExpandedRouteTest.kt`与`TopologyRouteProjectorTest.kt`：defensive copy及冻结路线range/connection验证。
- `TopologyRouteRestrictionTest.kt`：完整path替换自由分支、禁止macro、内部目标不可中途退出及非法物理path拒绝。
- SimpleATO `MissionRouteProgressTest.kt`：AB/BA和loop monotonic projection。

```bash
./gradlew :Basic:test --tests '*TopologyRouteRestrictionTest' --tests '*ExpandedRouteTest' \
  --tests '*TopologyRouteProjectorTest'
./gradlew :SimpleATO:test --tests '*TopologyRoutePlannerTest' \
  --tests '*OperationalRoutePlannerTest' --tests '*MissionRouteProgressTest'
```

## 拓扑来源与局部重新验证

来源topology revision用于标识快照，不是请求失效条件。路线规划把stable rail spans和permitted paths重新投影到当前图，验证区间及连接；方向overlay同样按实际rail重新解析。来自旧帧的列车位置在用于新拓扑时验证该列车的head/tail、body与occupied intervals，而非因世界远处编辑拒绝。单个已发布observation内部的epoch/topology配对仍必须一致。资源目录和应用时刻表使用实际依赖rail incarnation、连接及crossing验证，不用整图root身份代替有效性。

## 显式唯一路径证明与禁止经过的位置（CURRENT）

纯规划输入增加 requireUniquePath=false、blockedLocations=[]、uniqueSearchLimit=100000，非严格默认仍为原最短路。SimpleATO 提供运营 waypoint 策略，Basic 只消费 `OperationalRouteBlocker(location, facing?)` 的物理位置及可选本轨道行驶方向。剪枝位置解析到同一 topology 的切点；查询起终点同位 alias 被排除。未指定方向时阻断双向，指定方向时只阻断沿该 rail facing 经过的路径；须覆盖普通弧、结构端点和保护替换弧内部的真实 rail slices。不能把不同轨道的 AB/BA 当作全局上行/下行比较。端点落在原子保护路径内部不能任意切开该路径，保持不可达拒绝。查询失败不改变拓扑、资源或授权。

`requireUniquePath=true` 使用有工作预算的迭代深度搜索，检查全部合法物理简单路径，找到第二条不同路径即 AMBIGUOUS；两条路径长度不同也拒绝，不能只证明最短路径唯一。状态记录有方向切点、当前路径 rail intervals 和 node visits，回溯删除当前分支状态，拒绝重复占用物理区间及不同距离重复访问节点的绕圈捷径。每条保护区域替换弧展开真实 rail footprint 做同样检查，归一相邻同向片段后比较物理路径，重复的相同替换描述不伪装为不同路线。

默认 `uniqueSearchLimit=100000` 工作单位，包含展开、footprint 与冲突检查；预算耗尽返回 `SEARCH_LIMIT_EXCEEDED`，没有找到完整证明不能继续用第一条路。路径数可能指数增长，因此不声称严格模式保持 Dijkstra 的多项式成本；显式经停点缩小搜索段。候选邻接及 footprint 缓存只属于本次查询，不能跨 topology revision。严格模式不使用当前道岔位置、列车占用或灯色来消除配置歧义。


两条长度不同的路径也应 AMBIGUOUS；不能把“唯一最短路径”当成唯一物理路径。重复相同替换定义归一后只算一条；访问过的物理节点不同距离再次出现则是绕圈，不作为另一条简单路径。合法从同轨目标后方向绕环回到目标可保留，但不能重复经过源节点绕出又折回。无完整路径为 UNREACHABLE，发现第二条为 AMBIGUOUS，预算耗尽为 SEARCH_LIMIT_EXCEEDED；不能把后两者降为最短路成功。

TopologyRouteUniquenessTest 覆盖不同长度分支、保护内部、相同物理路径去重、物理节点重访、waypoint 中断、同位端点 alias 与预算上限。选项为查询局部值，不持久化，不新增 schema；复用 planner 的调用方须显式选择严格策略，不能改变所有公共测距/诊断查询默认行为。
