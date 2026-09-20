# Basic ATO mission 与 perception 契约

## 适用场景与用途

触发：修改`AtoMissionRequest/AtoMissionResolver`、mission revision、route/perception snapshots、RailObject transition或mission submission。

用途：main先解析stable refs与完整route，再在physics Tick boundary原子激活；planner只消费同epoch immutable train/route/perception，避免partial route、stale revision和live object shadow。

## Owner、真实类型与单位

`AtoMissionRequest(id:String,revision:Long,origin:WayPointRef,points:List<AtoMissionPoint>,requiredStartDirection:ViewDirection?)`：id稳定trimmed；revision`>=0`；points非空且defensive copy。`Via`只通过；`Stop.offsetMetersBefore` finite且`>=0 m`。

`AtoMissionResolver.resolve(request): AtoMissionResolutionResult`在main执行：resolve every WayPoint → `RoutePlanner` → `PlannedRouteCompiler` → continuous legs。结果：

- `Success(mission)`；
- `TargetUnresolved(target)`；
- `RouteUnavailable(from,to,reason)`。

`SimpleATO.submitMission`再返回`Accepted`、`StaleRevision(submitted,latest)`或对应resolve failure；accepted只入revision queue，physics boundary才active。

Snapshot exact fields：

```kotlin
data class PerceptionSnapshot private constructor(
    val epoch: Long,
    val train: TrainSnapshot,
    val route: RouteSnapshot,
    val observations: List<RailObservationSnapshot>
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/snapshot/PerceptionSnapshot.kt`。observations按`routeDistanceMeters`非降序、defensive copy；`relativeSpeedMetersPerSecond` nullable finite。transition token固定`ENTER/UPDATE/EXIT/PASS`。

## 结果矩阵

| 条件 | submission/result | active/published state |
| --- | --- | --- |
| origin/target缺失 | `TargetUnresolved` | 不改queue/mission，不Stall |
| route不可达/stale topology | `RouteUnavailable` | 不安装partial legs |
| revision `<= latestAccepted` | `StaleRevision` | 不入queue |
| valid newer mission | `Accepted` | 下个physics boundary替换；publish前main仍读old |
| observations未按distance排序 | constructor `IllegalArgumentException` | snapshot不创建 |
| same `RailObjectRef`对应different live object | world Stall、freeze | 不shadow旧object |
| provider boundary direction不匹配 | 非PASS activation | provider不切换 |

`RouteSnapshot(routeId,revision,topologyRevision,segments)`中`revision`只标识所选路径版本，
`topologyRevision`只标识生成该冻结路径时读取的Core结构拓扑版本；二者是独立的非负runtime计数，
不得因数值相同而合并或互相推导。segment字段为rail UUID、`ViewDirection`、view-local
start/end arc meters，满足finite、`0<=start<=end`。SimpleATO在开始扫描route前捕获一次不可变
`world.topologyState`根，并把该根的revision写入snapshot；其他producer也必须显式传入，禁止helper
在路线生成后猜测“最新”拓扑。若扫描期间发布新根，旧绑定将在后续校验中作为stale fail closed。

## 项目内正确示例

Mission resolver遇到route failure直接返回typed result，不构造partial mission：

```kotlin
is RouteBuildResult.Failure ->
    return AtoMissionResolutionResult.RouteUnavailable(from, to, result.reason)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/mission/AtoMission.kt`。这让Dispatcher在任何physics mutation前获得完整可定位failure。

## 禁止实现

- 持久化/live传`RailLocation`或mutable`PlannedRoute`；
- main直接设`activeMission`；
- stale revision覆盖新mission；
- 只保存route revision，或由consumer在稍后读取latest topology并拼接；
- unresolved leg仍激活前半条route；
- observation未排序或collection可变；
- same ref不同object悄悄替换。

## 必需测试与验证

- `AtoMissionTest.kt`：continuous legs、unresolved/unreachable、defensive copy、revision。
- `PhaseAFoundationTest.kt`：route/perception copy、unsorted rejection。
- `SimpleATOTest.kt`：stale/atomic physics activation、publish boundary、duplicate/live ref Stall、transition顺序。
- assertion必须包含failure后current mission/queue不变且world未误Stall（普通resolve failure）。

```bash
./gradlew :Basic:test --tests '*AtoMissionTest' --tests '*PhaseAFoundationTest' --tests '*SimpleATOTest'
```
