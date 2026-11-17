# Basic API 与 consumer 边界契约

## 适用场景与用途

触发：修改`basic.api.*`、Basic Dokka、Core dependency、Bukkit adapter或新增provider/service API。

用途：保持Basic standalone/platform-independent，阻止Core implementation和Bukkit types继续扩散到候选API；adapter只翻译side effect，不复制运营规则。

## Owner、build与类型边界

- `Basic/build.gradle.kts` Dokka只保留`work.art1st.javarailways.basic.api.*`；`Module.md`定义`1.0-SNAPSHOT`实验面，无binary compatibility promise。
- Basic以`compileOnly(project(":Core"))`、`compileOnly(ConfigLib/JOML)`编译；artifact不自动携带这些runtime。
- 新Basic API优先stable ID、immutable/value snapshot、sealed result与最小interface；collection defensive copy。
- `basic.api.route.WayPoint`是无方向canonical AB米制路径点值；`World.wayPoints`通过`WayPointAccess`直接访问唯一
  Basic存储。接口保留原get/all/add/rename/remove行为，不新建投影或事务owner；Destination事务继续由SimpleATO负责。
  单轨`operatingDirection`、指定轨道`operatingDirections`与规划`operatingDirectionOverlay`复用同一锁内查询，
  线程、稀疏复杂度、原子捕获和失败规则见[公共方向](./operating-directions.md)及[world runtime](./world-runtime.md)。
  `basic.api.rail.World.railObjects`同样直接返回已注册的唯一RailObjectStore，不注册或回退到第二份数据。
- `basic.api.ato`中的逐车设备状态、设备参数与纯设备计算是第一方控制模块契约，不暴露`TrainDynamics`内部类型，
  不承诺独立第三方插件的长期二进制兼容。`TrainDynamicsProfile`、`CarDynamicsProfile`、精确实例参数来源及
  可选编组/配置准备能力同属该第一方契约；准备结果只交付Core待安装更新和派生制动值，SimpleATO不识别具体执行器。
  设备状态所有权、采样对应、失败与安装顺序见[列车纵向动力学](./train-longitudinal-dynamics.md)。
- `basic.api.ato.nativeBodySpeedLimits`与`RailGradeBounds`是第一方物理查询和纯计算入口，不要求额外service或可替换interface。
  输入使用Core公开Train/frame/PrimeCurve；查询在调用方的物理worker内同步执行，不注册owner或授予资源。
  整车限速的覆盖与失败规则见[速度与脱轨安全](./speed-and-derail-safety.md)，全曲线坡度下界、缓存和采样规则见
  [列车纵向动力学](./train-longitudinal-dynamics.md)。SimpleATO仍负责控制目标和制动切片策略。
- 同包`nativeRailSpeedLimit`与`nativeHeadSpeedLimitContext`收口实时单轨限速和当前车头上下文；前者返回nullable m/s，
  后者只捕获Core API rail/facing且不读metadata。`basic.api.rail.OperationalSpeedLimitTransition`由原运行对象直接实现，
  保留其ref/location/lifecycle及live限速、方向能力。采样次序、release与失败归[速度安全](./speed-and-derail-safety.md)。
- `basic.api.ato`的选定物理前视查询为第一方worker提供live读取边界：`SelectedTrackAhead`保留有序物理片段、
  捕获的topology revision和覆盖距离；`PhysicalTrackSegment`仅公开Core API `RailView`及view-local米制区间，
  `PhysicalTrackObservation`保留原RailObject实例身份与本次捕获的位置/距离/相对速度。
  这些结果不是跨线程不可变世界快照；调用方仍在原时点检查live续接、signal与车端状态。
  两阶段读取、对象来源、重复身份及控制策略归属见[感知契约](./ato-missions-and-perception.md)。
- 同包`traceBoundedSelectedTrackAhead`复用原人工有界物理遍历，horizon和段数上限由SimpleATO指定；
  它与自动无界遍历保留各自原端点算法，不引入新的策略默认值。`staticTrackObjects`提供方向过滤后的只读列表，
  `trackTrainEnds`提供原动态车端集合的只读live视图；两者不提前执行调用方的生命周期/区间/自身过滤。
  这些入口仅公开Core API引用，语义、读取时机和复杂度见[感知契约](./ato-missions-and-perception.md)。
- `basic.api.route` 的 `RailViewRef`、`RouteSegment`、`ExpandedRoute`、`PlannedRoute` 是 SimpleATO 公开路线签名共用的值类型；它们不授予资源或证明拓扑合法性。坐标、不可变性和后续准入职责见[路线契约](./route-planning.md#公开路线值与内部实现的边界)。内部算法和快照转换不随值类型公开；规划查询结果使用下述独立契约。
- `RailwayObservation`、`RailwayTrainObservation`与`RailwayStateStamp`是公开value投影；它们保留epoch-end保守`occupiedIntervals`、有序tail-to-head `bodyTraversal`、整epoch`sweptSpans`、同epoch有序head/tail movement traversal及Core frame的immutable active-turnout continuation snapshot。这些事实不得互相替代，也不得包含render car或live Core对象。Basic只原样投影live continuation，不解释信号依赖或重建一份选择map。
- `RailwayOccupancyAreaDefinition`只描述插件拥有的监测区几何和有向边界；公开`RailwayAreaOccupancyEngine`由串行caller喂入连续observation并推导`ENTER`/`LEFT`，不把该area解释成固定闭塞、保护区域或授权资源。详见[监测区占用](./railway-area-occupancy.md)。
- `InfrastructureResource`只表示运行资源；`InfrastructureDependencies`和`TopologyEditImpact`分别以rail ID、stable
  topology-node key、runtime resource ID三类集合表达非独占编辑依赖与编辑影响。它们的typed result/read-only snapshot属于
  公开host contract；raw live-claim mutation只属于Basic路线准入实现，不进入add-on API。
- `basic.api.infrastructure.PhysicalRouteDependencyCompiler`及其结果是第一方冻结路线物理编辑依赖查询契约，
  只接收Core/Basic API值并返回非独占依赖声明，不授予资源或注册owner。结果、坐标与应用职责见[资源协调](./resource-allocation.md)。
- 目标`work.art1st.javarailways.basic.api.route.RouteResourceIntent`复用现有`RouteSnapshot`，并只携带nonblank stable
  train ID、同一观察stamp及从冻结路线起点起算的米制authority boundary。ATO request不得携带具体resource identity、
  preparation descriptor、topology revision副本、可信列车长度或替代Basic物理推导的raw footprint。Basic自行加入
  不可删除的rail/turnout/diamond资源，再与宿主从认证route contributor收集的合法资源合并为唯一claim。确切字段、
  单位、结果和extension关系由
  [路线资源准入](./route-resource-admission.md)冻结。
- Contributor结果只接受exact `StableInfrastructureResource`，每项显式声明车尾释放route-metre boundary；没有默认
  释放点。可选准备target仍可使用namespaced kind加immutable scalar arguments的runtime-only descriptor，但它只在
  contributor与resource owner之间传递，SimpleATO不构造或解释；不接受`Any`、mutable object、JVM class reflection
  或JSON persistence payload。
- 通用preparation ticket的state/snapshot属于platform-independent Basic API；Bukkit handler registration只绑定
  plugin/world lifecycle并返回该ticket。认证contributor每次route request最多调用一次并使用自己的索引产生需求；
  handler只接收attempt UUID、world UUID、exact stable resource和descriptor，不接收train/route/registry/live Core
  object。迁移直接删除caller-additions constructor和三参数provider registration，不保留兼容overload。
- Basic production不得import Bukkit/Paper/Minecraft；platform side effect只经`TrainOperationGateway`等semantic adapter。
- `basic.api.route.planning`的限制值只携带canonical AB米、有向rail UUID、topology revision和完整物理path。
  `RoutePlanningRestrictionSource`在搜索前低频编译一次immutable value；Dijkstra扩展期间不回调source。该API不得加入
  protection region、fixed block、signal aspect、resource claim或live world对象。完整编译/校验算法见
  [route planning](./route-planning.md)。
- 同包`TopologyRoutePlanner`及其请求/结果是第一方纯规划与校验契约，内部搜索由`basic.route.TopologyRoutePlannerEngine`唯一实现。坐标、输入稳定性、唯一性预算及拒绝语义见[路线契约](./route-planning.md)。
- 同包`PhysicalRailDistance.between`是只读物理测距入口，只接收不可变拓扑、rail UUID和canonical AB米，返回可空距离；内部测距实现与查询图不随之公开。无运营限制不等于无物理连接约束，且测距结果不授予运行资源。零长、错误与算法复用见[测距合同](./route-planning.md#只读物理测距)。
- 非Core模块优先`core.api.*`；能力不足先设计最小Core API/value DTO，不能以现存internal import为先例。
- BukkitPlugin是同仓库composition root，不是第三方add-on contract；它不能把Basic non-API object重新包装成稳定API。
- Bukkit `InfrastructureResourceService.routeAdmission(plugin, worldId)` 是公开准入service的认证入口：返回值绑定
  该plugin实例和确切world runtime，所有request/progress/endGrant沿用该身份。plugin disable、service close或
  world unload后返回`WORLD_UNAVAILABLE`/false；同UUID的新world不能复活旧binding，且失效不能删除host-owned live claim。
  计划禁用必须先成功调用owner unregister；pending reservation或非空claim会拒绝注销。已发生的强制禁用不可取消，
  此时Basic保留claim并经既有world Stall owner fail-close；无资源、无pending而只等待`endGrant`的空route state可安全删除。
- Bukkit `InfrastructureResourceService.selectTurnout(plugin, worldId, request)` 是受保护的手动道岔操作入口。
  request只携带当前topology revision和两个stable turnout port；调用在串行simulation context中阻塞至当前physics epoch
  完成，然后同时检查确切道岔资源是否存在运行claim冲突、结构道岔每个端口是否存在车体物理占用。成功时只改变
  live turnout selection，不产生route grant、movement authority或长期资源占用；返回值是detached typed result，不能
  暴露live `RailView`、registry handle或Core mutable object。

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
| 基础设施协调 | 运行claim使用immutable footprint；编辑依赖/编辑影响使用独立三类引用 + typed atomic result | 把rail/node包装成resource、让编辑依赖阻止列车claim，或向add-on暴露raw registry |
| 路线资源准入 | ATO只提交topology-bound frozen route + train/observation/boundary；宿主收集认证contribution，Basic推导不可删除的物理底线并原子claim union | ATO认识信号资源、add-on替换物理footprint/列车长度、Basic替其寻路或执行具体信号制式 |
| 自定义资源准备 | exact identity + provider-owned runtime descriptor；world owner thread返回typed ticket | handler读取mutable train/world、扫描路线、追加资源或把descriptor当存档schema |
| 手动道岔操作 | stable ports + topology revision；短期占用确切道岔资源，等待physics后检查结构端口物理占用，再通过统一live selector切换 | 绕过运行claim/物理占用、创建ATO授权，或向调用方返回live turnout object |

每个signature/KDoc必须包含world/host owner、stable identity、字段类型和单位、thread/blocking、live/value、failure/result、register/unregister、persistence含义。annotation不能替代文字。

## 项目内正确示例

`RailwayObservation.fromCore(worldId, frame)`是另一个正确边界：它保留同一Core world frame的physics epoch、topology revision、列车位置/占用与frame revision，但主动丢弃render-only car数据。Infrastructure API同样只暴露stable resource identity、owner revision和immutable diagnostics；Bukkit service负责plugin owner与lifecycle，不把`InfrastructureCoordinationRegistry`公开给add-on。

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
