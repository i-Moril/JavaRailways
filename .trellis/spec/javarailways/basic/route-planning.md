# Basic route planning 契约

## 适用场景与用途

触发：修改WayPoint route search、direction restriction、expanded/planned route、loop progress或route compilation。

用途：记录当前Mission/Station/Line/Timetable共用的按实际弧长加权planner，并约束它向SimpleATO运营寻路迁移。长期authoritative输入是Core发布的版本化`TopologyState`；SimpleATO拥有方向、停站、线路和时刻表等路径选择策略，Basic只保留可复用的纯图算法与冻结路线校验/资源投影，不拥有另一张图。

当前生产调用的`RoutePlanner`仍直接遍历`RailView.nextRails`，属于待迁移实现，不能被新API固化；它只接受调用方提供的`(RailView) -> Boolean`遍历谓词，不读取任何运营metadata。SimpleATO把自己的运行方向store作为谓词传入。Basic现已拥有基于不可变`TopologyState`的`TopologyRoutePlanner`和中立限制值；`FrozenRouteSelector`已消费它，但Mission/Station/Line/Timetable尚未全部迁移，不能把旧planner删除或声称生产统一已经完成。

## Owner、输入输出与坐标

`RoutePlanner(world,traversalAllowed)`输入`WayPoint(id,displayName,railId,arcLength)`，arc是canonical AB米；optional required start/goal `ViewDirection`必须由运营调用方显式解析并传入。通用planner不查询Destination/Station/OperatingDirection store，也不推断运营方向；未传入端点方向表示该端点没有额外要求，默认谓词允许所有物理view。

```kotlin
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/route/RoutePlanner.kt`。segment arcs是该view-local meters，finite且`0<=start<=end`；`ExpandedRoute`segments defensive copy、nonempty、total length positive。AB→BA转换只在boundary：`viewArc=railLength-abArc`。

`buildRoute(...): RouteBuildResult.Success(route)|Failure(reason)`使用Dijkstra actual remaining/full arc length。same view goal在start后direct；在start前可绕loop重新进入。

`PlannedRouteCompiler.compile(routeId,revision,route): Success|Failure`重新resolve stable views，检查segment range、connected endpoint和调用方遍历谓词。`PlannedRoute`只保存route identity、revision和已通过验证且自身defensive copy的`ExpandedRoute`；它不保存信号边界、保护点、资源claim或道岔准备参数。

目标流程为：SimpleATO在一个`TopologyState(revision)`上生成一份绑定该revision的冻结traversal；Basic验证该traversal连续且每个continuation合法，并在同一次pass中把沿途rail intervals、受控transition与route-bound shared resources加入唯一claim集合。物理占用范围只是资源binding的验证数据，不是第二份footprint。Basic不得要求调用方另报资源清单，也不得在验证时另行寻路或用保护区域替调用方选择路径。

## 不可变拓扑寻路与限制

`basic.api.route.planning`只暴露物理中立值：`DirectedRailSpan`、`PhysicalTraversalPath`、
`PathReplacement`、`RoutePlanningRestrictions`和低频编译用的
`RoutePlanningRestrictionSource`。其中span使用canonical AB米范围加行驶方向；restriction绑定一个
topology revision并防御性复制集合。接口不知道保护区域、闭塞、信号、联锁或资源claim。

正常流程是：SimpleATO在搜索前调用各owner source，把运行方向和插件私有策略编译成不可变
restrictions；Basic一次性校验并建立query-local图；Dijkstra只枚举已经准备好的图边，不在扩展循环里
回调插件。`forbiddenTraversals`删除指定方向上的物理区间；`PathReplacement`删除
`replacedTraversals`的自由通行，并把每个`permittedPath`加入为一条不可拆分的有向macro。搜索选中
macro后才展开成普通`RouteSegmentSnapshot`，后续仍由Basic route projector独立验证。

编译器在对应位置临时切分normalized edge，不修改`TopologyState`。它拒绝缺失/越界rail、跨越
replacement覆盖范围的macro、不连续或不合法道岔路径，以及两个replacement在相同有向正长度
区间上的重叠。绝对禁止与macro相交时，该macro不加入搜索。起点或终点位于replacement内部时，
只有插件显式提供以该点为端点的完整path才可能到达；搜索不会自动截断macro。

设normalized topology为`V/E`，限制span总数为`S`，macro展开后的物理span总数为`P`，临时切点为
`C`。编译和物理连续性验证为`O(V+E+S log S+P)`量级，query-local图规模为`O(V+C+P)`，
Dijkstra为`O((V+C+A) log(V+C))`，其中`A`为普通、结构连接及macro边总数。source调用、编译和
图构造每次规划至多一次；tick、资源等待和Dijkstra扩展内部均不执行插件代码。

## Route/compile矩阵

| 条件 | result |
| --- | --- |
| missing rail/required direction无候选 | `Failure`含waypoint/方向 |
| disconnected/unreachable | `Failure`，无partial segments |
| 多条route | actual meter total最短；不按hop数 |
| same rail goal behind | direct非法；若loop存在可成功重复rail |
| stale/missing compiled view | compilation `Failure` |
| transition非endpoint/不connected | compilation `Failure` |
| caller predicate rejects selected branch | compilation `Failure` |
| facing/trailing turnout | compiler只验证选定连接；后续物理资源编译从冻结拓扑投影canonical turnout identity |
| straight/turnout route | 都不生成signal section或synthetic protection point |

`MissionRouteLayout.project(position,previousProgress)`按facing转换AB→view arc，并用previous progress从repeated rail candidates选monotonic值。

## 项目内正确示例

Planner把direction conversion集中在一个函数：

```kotlin
return when (direction) {
    ViewDirection.AB -> wayPoint.arcLength
    ViewDirection.BA -> totalLength - wayPoint.arcLength
}
```

来源：`RoutePlanner.kt`。这避免caller多次翻转或把BA view arc写回persisted WayPoint。

## 禁止实现

- 新mission/timetable调用legacy label BFS `PathFinder`；
- 以rail hop count作为最短；
- 返回partial unreachable route；
- 任意并发读写`RailMap`；
- compile后跳过stable topology revalidation；
- 新功能继续把`RailView.nextRails`或Basic复制的adjacency当成第二个authoritative graph；
- 在Dijkstra扩展期间调用插件callback，或让插件直接修改Core `TopologyState`；
- 把保护区域/进路表类型加入Basic；插件只能提交中立有向区间和完整物理path；
- 把一条permitted path拆成若干可自由拼接的边，从而产生进路表未列出的混合路径；
- Basic收到非法或不连续route后自行搜索并补齐一条运营路径；
- RoutePlanner隐式查询Basic/SimpleATO metadata；方向谓词只能由上层调用方显式注入；
- repeated rail progress取第一个candidate导致倒退。

## 必需测试与验证

- `RoutePlannerWeightedTest.kt`：more hops但30 m更短、disconnected failure、same-view loop behind。
- `DirectionRestrictionRouteTest.kt`：start/goal/branch direction。
- `PlannedRouteCompilerTest.kt`：range/connection failure、direction restriction，以及道岔路线不会生成signal section。
- `TopologyRouteRestrictionTest.kt`：完整path替换自由分支、禁止macro、内部目标不可中途退出及非法物理path拒绝。
- SimpleATO `MissionRouteProgressTest.kt`：AB/BA和loop monotonic projection。

```bash
./gradlew :Basic:test --tests '*RoutePlannerWeightedTest' --tests '*DirectionRestrictionRouteTest' \
  --tests '*PlannedRouteCompilerTest'
./gradlew :SimpleATO:test --tests '*MissionRouteProgressTest'
```
