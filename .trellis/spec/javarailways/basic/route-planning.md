# Basic route planning 契约

## 适用场景与用途

触发：修改WayPoint route search、direction restriction、expanded/planned route、loop progress或route compilation。

用途：Mission/Station/Line/Timetable共用一个按实际弧长加权的authoritative planner；stable refs在进入ATO前重新解析并验证topology，失败不返回partial route。

## Owner、输入输出与坐标

`RoutePlanner(world)`输入`WayPoint(id,displayName,railId,arcLength)`，arc是canonical AB米；optional required start/goal `ViewDirection`，未显式提供时使用OperationalDestination approach。只遍历Basic `allowedDirection`许可的view。

```kotlin
data class RouteSegment(
    val railViewRef: RailViewRef,
    val startArcLength: Double,
    val endArcLength: Double
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/route/RoutePlanner.kt`。segment arcs是该view-local meters，finite且`0<=start<=end`；`ExpandedRoute`segments defensive copy、nonempty、total length positive。AB→BA转换只在boundary：`viewArc=railLength-abArc`。

`buildRoute(...): RouteBuildResult.Success(route)|Failure(reason)`使用Dijkstra actual remaining/full arc length。same view goal在start后direct；在start前可绕loop重新进入。

`PlannedRouteCompiler.compile(routeId,revision,route,protectionPoints): Success|Failure`重新resolve stable views，检查segment range、connected endpoint、direction restriction并canonicalize turnout。`PlannedRoute`sections index从0连续，覆盖完整route且defensive copy。

## Route/compile矩阵

| 条件 | result |
| --- | --- |
| missing rail/required direction无候选 | `Failure`含waypoint/方向 |
| disconnected/unreachable | `Failure`，无partial segments |
| 多条route | actual meter total最短；不按hop数 |
| same rail goal behind | direct非法；若loop存在可成功重复rail |
| stale/missing compiled view | compilation `Failure` |
| transition非endpoint/不connected | compilation `Failure` |
| direction-restricted selected branch | compilation `Failure` |
| facing/trailing turnout | canonical同一`TurnoutRequirement(from,selectedNext)` |
| straight route | section覆盖route但zero synthetic turnout claims |

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
- repeated rail progress取第一个candidate导致倒退。

## 必需测试与验证

- `RoutePlannerWeightedTest.kt`：more hops但30 m更短、disconnected failure、same-view loop behind。
- `DirectionRestrictionRouteTest.kt`：start/goal/branch direction。
- `PlannedRouteCompilerTest.kt`：range/connection failure、facing/trailing canonical identity、contiguous sections、straight zero claims/default distances。
- `MissionRouteProgressTest.kt`：AB/BA和loop monotonic projection。

```bash
./gradlew :Basic:test --tests '*RoutePlannerWeightedTest' --tests '*DirectionRestrictionRouteTest' \
  --tests '*PlannedRouteCompilerTest' --tests '*MissionRouteProgressTest'
```
