# SimpleSignals Backend 规范

## 状态与范围

`SimpleSignals` 是 `CURRENT/FOUNDATION` 的独立 Bukkit/Paper 插件模块。它单独生成可安装 JAR，
声明 `JavaRailways` 为必需服务器插件，并通过 Bukkit `ServicesManager` 获取宿主公开能力。它不是
JavaRailways 主 JAR 的组成部分，也不能复制、shadow 或直接导入宿主实现包。

当前已建立部署边界、保护区域领域核心、固定保护进路表的低频编译器，以及把该编译结果注册到实际world的
planning-only runtime。尚未建立持久化文件、固定闭塞、资源申请、inspection、信号规则、渲染、命令或 JS/YAML
接口，不得把 foundation 描述为可用信号系统。

## 工作方式

正常启动流程是：

```text
Paper 先启用 JavaRailways
  -> JavaRailways 向 ServicesManager 注册公开 host services
  -> Paper 构造并启用 SimpleSignalsPlugin
  -> SimpleSignals 同步取得所需 service capabilities
  -> 缺少任一能力则 onEnable 失败，不安装部分 world state
  -> 对已经加载的world读取host的exact immutable topology root
  -> 用空definition发布region/table candidate并注册一个route-planning source
  -> 后续WorldLoad在host完成注册后重复上述流程；WorldUnload丢弃local runtime
```

当前要求的能力是 coherent observation、通用 infrastructure/resource、signal epoch source、
inspection source和route-planning restriction source五个 service。插件 descriptor 的 `depend: [JavaRailways]` 确保加载顺序；
`api-version` 声明 Bukkit API 目标。宿主目前没有独立的数值化 signal API version，因此不能伪造
字符串版本比较；实际 capability gate 是所需 service 类型能否全部发现。后续若宿主引入明确的
capability descriptor，再在同一公开边界检查。

每次world load拥有一个新的`SimpleSignalsWorldRuntime`。`InfrastructureResourceService.currentTopology`返回
host当前发布的exact immutable root；runtime不取得mutable Core world。启动阶段尚无持久化，因此以definition revision 0
原子发布空region/table集合，再通过`RoutePlanningRestrictionSourceService`为该world注册且只注册
`simplesignals:fixed-protection-routes`一个binding。损坏world未被host注册时，查询root失败；插件只记录明确诊断并保持该world
无runtime/无source，不安装半套状态。空region publication只保留root并使用空index，不扫描整张拓扑；相同UUID
unload/reload先删除local runtime，再创建新runtime与binding，不能复活旧缓存。

## 保护区域领域

`region` package 只保存 SimpleSignals 自己的信号策略，不进入 Basic 公共 API：

- authored definition 只保存 namespaced region/boundary ID、region revision 和有向 rail cut；不保存第二份权威子图；
- `ProtectionRegionCatalog.publish` 捕获一个不可变 `TopologyState` root 和 plugin definition revision，先完整派生候选 snapshot，全部通过后才原子替换；失败保留旧 snapshot；
- graph resolver 在边界处分割临时 rail segments，从第一个 inward half-edge 做 BFS，验证所有 inward half-edge 同属一个连通内部、outside 不会绕路重连，并拒绝正长度区域重叠；
- published snapshot 保存 disposable interior slices、region lookup 和 rail-to-region reverse index。create/update/load publication 会先以 `O(V+E)` 派生拓扑，再为每个定义解析连通内部并检查区域两两重叠；这些工作不进入 Tick；
- route matcher 只投影冻结路线一次，再通过 rail reverse index 把 occurrence 分发给实际候选 region；它把 facing-local segment 坐标转换为 canonical AB，要求每个 region 只有一个连续 entry-to-exit interval，并派生 route-metre window。成本包含路线 occurrence、索引命中、候选区域的 interior slices 以及交集排序，而不是 `regions × full route`；
- matcher 不搜索路径，也不复制 Basic 的结构连续性/turnout legality 校验。Basic 在资源申请时仍独立验证同一冻结路线并编译不可删除的物理资源底线。

route-window 只描述 region entry/exit。列车需要在出口后继续申请固定闭塞或移动闭塞距离属于后续 plugin route-decision 层，不放回 region model，也不复制旧 Basic `exitClearance` 单窗口合同。

## 固定保护进路表

`ProtectionRouteTableDefinition`是SimpleSignals私有的联锁选路模型。每个table以稳定namespaced ID和
revision绑定一个region ID及其revision；每条带稳定ID的route声明entry boundary、exit boundary和从入口
到出口的完整有向物理traversal序列。route不是Basic graph edge，也不允许调用方只提交若干可自由拼接的支路。

`ProtectionRouteTableCatalog.publish`捕获`ProtectionRegionCatalog`的一个不可变publication，在authoring/load
路径完成以下工作：

1. 验证table/route ID唯一、table revision单调且region引用/revision仍匹配；
2. 验证route精确对齐入口/出口cut，从入口的inside facing开始，从出口的outside facing结束；
3. 验证每段位于派生region interior内，相邻段在同一rail上连续或通过Core topology允许的有向continuation相连；
4. 把该region的全部interior slice按AB和BA两个方向输出为一个`PathReplacement.replacedTraversals`，再把
   每条完整route输出为一个`PhysicalTraversalPath`。

因此Basic会删除区域内自由通行的query edge，只把每条完整route作为不可拆分macro加入查询。同一对gate可以有
多条route，但搜索不能把route A的前半段与route B的后半段拼成表外进路。未定义table的region不产生replacement，
本批次不会把“存在保护区域”隐式解释成固定进路限制。

所有table先全部验证和编译，成功后才原子替换cached restriction snapshot；失败保留旧snapshot。source callback
只做`O(1)`的region publication与topology root/revision一致性检查，然后返回已缓存的Basic中立
`RoutePlanningRestrictions`；不在Dijkstra或tick内解析region/table。设table route的总traversal数为`P`，
region interior slice总数为`S`，同一rail最多有`Q`个slice，publication编译为`O(S+P log Q)`（另加ID检查和
topology continuation的局部邻接检查），route query仍只有Basic既有的query-graph编译和Dijkstra成本。
region或topology publication变化而table尚未重编译时source抛出失败，不能返回旧的宽松限制。

world runtime的definition入口为一次更新建立全新的candidate region catalog和candidate table catalog；两者都成功后才以
一次volatile publication替换live pair。注册到host的binding保持稳定，只读取该publication，因此已捕获的source会立即看到
完整新pair或完整旧pair，不会看到“新region + 旧table”。拓扑变更不会触发callback内重建；在authoring/persistence层显式
republish之前，旧source按root identity检查fail-close。当前production启动只发布空集合，非空publication由内部入口和测试
证明；持久化与authoring仍未实现。

## 依赖与打包不变量

- 对 `Core`、`Basic`、`SimpleATO`、`BukkitPlugin` 和 Paper 使用 `compileOnly`；运行时由
  `JavaRailways` 与服务器提供。
- 只允许导入 `core.api.*`、`basic.api.*`、`simpleato.api.*` 与 `bukkit.api.*`；不得导入
  `*.internal` 或平台无关模块的实现包。
- Kotlin stdlib 由 Paper descriptor 的 library declaration 提供；仍不得打入插件 JAR。
- 插件只有 `SimpleSignalsPlugin` 一个 `JavaPlugin` lifecycle owner。
- 普通 `jar` 就是部署 artifact；不使用 shadowJar，也不把宿主 classes 打入该 JAR。
- 当前 `onDisable` 只丢弃plugin-local runtime map；Bukkit已经强制禁用时由host现有`PluginDisableEvent`路径删除失效
  planning callback。尚未实现planned-disable UI/state machine；未来正常停用仍必须先移除applied-plan dependency并调用
  宿主唯一owner-unregister gate，不能新增第二套卸载协议。

## 开发前检查

- [ ] 新类型是否确实属于 SimpleSignals 的具体信号策略，而不是 Basic 通用资源或 SimpleATO 运营逻辑？
- [ ] 是否只通过已记录的 host service 和 API value 通信？
- [ ] 是否保持独立 JAR、独立生命周期且不 shadow host class？
- [ ] world-local binding 是否在 unload/reload 后重新获取，而不是按 UUID 复活旧 binding？
- [ ] safety callback 是否同步、有界、不调用 Bukkit 或阻塞 I/O？

## 质量检查

```bash
./gradlew :SimpleSignals:check
./gradlew :BukkitPlugin:compileSignalApiConsumerJava
git diff --check
```

`verifyPluginJar` 必须证明 artifact 含 `plugin.yml`，且不含 Core、Basic、SimpleATO 或 BukkitPlugin
宿主 package。服务发现测试必须覆盖完整 capability 集合和缺少必需 capability 时的 fail-fast。
保护区域测试必须覆盖简单/多道岔连通内部、outside reconnect、重叠、AB/BA路线窗口、缺少entry/exit和catalog失败不替换旧snapshot。
world runtime测试还必须覆盖空启动、非空table改变Basic实际选路、candidate失败保留旧pair、topology变化到显式republish之间
fail-close，以及同UUID reload得到新binding。
