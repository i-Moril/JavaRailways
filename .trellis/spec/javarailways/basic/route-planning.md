# Basic route planning 契约

## 适用场景与用途

触发：修改WayPoint route search、direction restriction、expanded/planned route、loop progress或route compilation。

用途：记录当前Mission/Station/Line/Timetable共用的按实际弧长加权planner，并约束它向SimpleATO运营寻路迁移。长期authoritative输入是Core发布的版本化`TopologyState`；SimpleATO拥有方向、停站、线路和时刻表等路径选择策略，Basic只保留可复用的纯图算法与冻结路线校验/资源投影，不拥有另一张图。

当前通用`RoutePlanner`仍直接遍历`RailView.nextRails`，属于待迁移实现，不能被新API固化；它只接受调用方提供的`(RailView) -> Boolean`遍历谓词，不读取任何运营metadata。SimpleATO把自己的运行方向store作为谓词传入。新增跨模块路径合同必须遵守Core[版本化铁路拓扑](../core/versioned-operational-topology.md)。

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
- Basic收到非法或不连续route后自行搜索并补齐一条运营路径；
- RoutePlanner隐式查询Basic/SimpleATO metadata；方向谓词只能由上层调用方显式注入；
- repeated rail progress取第一个candidate导致倒退。

## 必需测试与验证

- `RoutePlannerWeightedTest.kt`：more hops但30 m更短、disconnected failure、same-view loop behind。
- `DirectionRestrictionRouteTest.kt`：start/goal/branch direction。
- `PlannedRouteCompilerTest.kt`：range/connection failure、direction restriction，以及道岔路线不会生成signal section。
- SimpleATO `MissionRouteProgressTest.kt`：AB/BA和loop monotonic projection。

```bash
./gradlew :Basic:test --tests '*RoutePlannerWeightedTest' --tests '*DirectionRestrictionRouteTest' \
  --tests '*PlannedRouteCompilerTest'
./gradlew :SimpleATO:test --tests '*MissionRouteProgressTest'
```
