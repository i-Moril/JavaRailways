# Basic API 与 consumer 边界契约

## 适用场景与用途

触发：修改`basic.api.*`、Basic Dokka、Core dependency、Bukkit adapter或新增provider/service API。

用途：保持Basic standalone/platform-independent，阻止Core implementation和Bukkit types继续扩散到候选API；adapter只翻译side effect，不复制运营规则。

## Owner、build与类型边界

- `Basic/build.gradle.kts` Dokka只保留`work.art1st.javarailways.basic.api.*`；`Module.md`定义`1.0-SNAPSHOT`实验面，无binary compatibility promise。
- Basic以`compileOnly(project(":Core"))`、`compileOnly(ConfigLib/JOML)`编译；artifact不自动携带这些runtime。
- 新Basic API优先stable ID、immutable/value snapshot、sealed result与最小interface；collection defensive copy。
- `RailwayObservation`、`RailwayTrainObservation`与`RailwayStateStamp`是公开value投影；不得包含render car或live Core对象。
- `InfrastructureResource`、lease/edit request、typed result与read-only snapshot属于公开host contract；raw live-claim mutation只属于Basic路线准入实现，不进入add-on API。
- 目标`RouteResourceIntent`公开一个自身携带topology revision的冻结route plan、train ID、同一观察stamp、期望authority boundary，以及可选的**附加**注册资源。route是调用方提交的唯一路径及拓扑绑定事实；request不得复制第二个topology revision、可信列车长度或替代Basic物理推导的raw footprint。Basic从路线自行加入不可删除的rail/turnout/diamond物理资源，再与通过验证的附加资源合并为唯一claim集合。
- Basic production不得import Bukkit/Paper/Minecraft；platform side effect只经`TrainOperationGateway`等semantic adapter。
- 非Core模块优先`core.api.*`；能力不足先设计最小Core API/value DTO，不能以现存internal import为先例。
- BukkitPlugin是同仓库composition root，不是第三方add-on contract；它不能把Basic non-API object重新包装成稳定API。

代表性platform-independent签名（`Basic/src/main/java/work/art1st/javarailways/basic/api/operation/TrainOperation.kt`）：

```kotlin
fun interface TrainOperationGateway {
    @MainThread
    fun execute(request: TrainOperationRequest): TrainOperationExecutionResult
}
```

Basic只知道semantic request/result；region scheduling与model action完成由adapter拥有。

## Boundary矩阵

| 场景 | 必须结果 | 禁止结果 |
| --- | --- | --- |
| 新API返回route/location | stable ref/value DTO，注明AB/方向/单位 | `CoreRail/CoreTrain/RailLocationWithFacing/basic.route.*` |
| API collection | immutable/defensive copy或明确live restriction | 暴露mutable map/list |
| 平台effect | semantic request→gateway | Basic调用Bukkit scheduler/entity |
| API缺Core能力 | 最小Core interface/service + lifecycle | cast internal type |
| API变更 | Dokka+Core/Basic/Bukkit downstream review | 仅compile success |
| extension identity | namespaced stable ID/version | JVM FQCN reflection |
| 世界观察 | 同一`RailwayStateStamp`下的immutable train values | 分别读取live train与latest topology再拼装 |
| 基础设施仲裁 | immutable footprint + typed atomic result | 向add-on暴露raw claim mutation或registry implementation |
| 路线资源准入 | topology-bound frozen route + train/observation/boundary + optional additions；Basic推导不可删除的物理底线并原子claim union | add-on替换物理footprint/列车长度、Basic替其寻路或执行具体信号制式 |

每个signature/KDoc必须包含world/host owner、stable identity、字段类型和单位、thread/blocking、live/value、failure/result、register/unregister、persistence含义。annotation不能替代文字。

## 项目内正确示例

`TimetableExtensionCall`把provider identity做成稳定数据：

```kotlin
data class TimetableExtensionCall(
    val typeId: String,
    val version: Int = 1,
    val parameters: JsonObject = JsonObject(emptyMap())
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/timetable/TimetableExtension.kt`。`typeId`必须匹配`namespace:name`且`version>0`，不会随Kotlin class rename改变。

`RailwayObservation.fromCore(worldId, frame)`是另一个正确边界：它保留同一Core world frame的physics epoch、topology revision、列车位置/占用与frame revision，但主动丢弃render-only car数据。Infrastructure API同样只暴露stable resource identity、owner revision和immutable diagnostics；Bukkit service负责plugin owner与lifecycle，不把`InfrastructureLeaseRegistry`公开给add-on。

目标route validator是host-owned物理证明边界：它不选择路径或解释保护区域，而是校验调用方traversal的edge连续性与合法continuation，并在同一次线性遍历中从`TopologyState`及route-bound resource index加入不可删除的rail、turnout与diamond资源。调用方可以附加已注册资源，但不能删改该底线。Basic从同一观察读取列车占用、验证最终union并原子提交；`INVALID_ROUTE`、stale、occupancy、无效addition或任一资源冲突都不得留下partial claim。完整算法见[路线资源准入](./route-resource-admission.md)。

## 禁止实现

```text
错误：Basic API返回CoreTrain；Bukkit command复制route/berth验证
正确：Basic API返回stable value/result；Bukkit adapter调用Basic owner
```

不得因为Dokka task exit 0就声称文档完整；`failOnWarning=false`，review必须检查warning和package includes。

## 必需测试与验证

- API value tests断言identity、units、defensive copy、invalid input和sealed failure。
- observation tests断言epoch/topology不混用、投影不保留render/live对象、collection不可变。
- infrastructure tests断言typed denial无partial mutation，外部consumer只能使用公开request/result/snapshot。
- route-admission tests断言单次route validation、不可删除物理底线、合法addition进入唯一claim、双重topology检查和所有拒绝无partial mutation。
- import scan确认Basic production无platform types，新consumer优先`core.api.*`。
- Dokka人工检查`api.line`、`api.timetable`及所有新增package。

```bash
./gradlew :Core:test :Basic:test
./gradlew :Basic:dokkaGeneratePublicationHtml
./gradlew :BukkitPlugin:test
```
