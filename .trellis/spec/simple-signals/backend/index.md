# SimpleSignals Backend 规范

## 状态与范围

`SimpleSignals` 是 `CURRENT/FOUNDATION` 的独立 Bukkit/Paper 插件模块。它单独生成可安装 JAR，
声明 `JavaRailways` 为必需服务器插件，并通过 Bukkit `ServicesManager` 获取宿主公开能力。它不是
JavaRailways 主 JAR 的组成部分，也不能复制、shadow 或直接导入宿主实现包。

当前已建立部署边界、严格current-model世界定义sidecar、保护区域领域核心、固定保护进路表和固定闭塞定义的低频编译器，
以及把存档编译结果原子发布到实际world runtime。固定闭塞还把已编译Basic监测区接入host physics epoch，发布
plugin-local immutable section occupancy snapshot，并以同一exact frozen-route matcher建立资源贡献与inspection authority floor；
已建立plugin-local信号规则执行边界、灯色/限速输出快照及其列车限制投影，但尚未提供具体规则、渲染、命令或JS/YAML接口，
不得把 foundation 描述为可用信号系统。

## 工作方式

正常启动流程是：

```text
Paper 先启用 JavaRailways
  -> JavaRailways 向 ServicesManager 注册公开 host services
  -> Paper 构造并启用 SimpleSignalsPlugin
  -> SimpleSignals 同步取得所需 service capabilities
  -> 缺少任一能力则 onEnable 失败，不安装部分 world state
  -> 对已经加载的world读取自己的definition primary/backup sidecar
  -> missing使用内存中的revision 0空定义，损坏则安装拒绝新寻路的fail-closed source
  -> 取得host的exact immutable topology root并完整编译region/table/fixed-block candidate
  -> 编译成功后才注册一个route-planning source并发布local runtime
  -> 后续WorldLoad在host完成注册后重复上述流程；WorldUnload丢弃local runtime
```

当前要求的能力是 coherent observation、通用 infrastructure/resource、signal epoch source、
inspection source和route-planning restriction source五个 service。插件 descriptor 的 `depend: [JavaRailways]` 确保加载顺序；
`api-version` 声明 Bukkit API 目标。宿主目前没有独立的数值化 signal API version，因此不能伪造
字符串版本比较；实际 capability gate 是所需 service 类型能否全部发现。后续若宿主引入明确的
capability descriptor，再在同一公开边界检查。

每次world load拥有一个新的`SimpleSignalsWorldRuntime`。`InfrastructureResourceService.currentTopology`返回
host当前发布的exact immutable root；runtime不取得mutable Core world。安装先从插件data folder加载当前定义；只有
missing才以definition revision 0原子发布空region/table/fixed-block集合，且不会为此写出空文件。随后通过
`RoutePlanningRestrictionSourceService`为该world注册且只注册
`simplesignals:fixed-protection-routes`一个binding。损坏world未被host注册时，查询root失败；插件只记录明确诊断并保持该world
无runtime/无source，不安装半套状态。definition损坏、world ID不匹配、orphan rail引用或任一catalog编译失败时不发布可用runtime，
但为已注册的host world安装同一ID的fail-closed source；其callback明确失败，使SimpleATO拒绝新的运营寻路，而不是把损坏定义解释为
“没有限制”。文件修复后的重新安装以正常编译source整体替换该binding。空region publication只保留root并使用空index，不扫描整张拓扑；相同UUID
unload/reload先删除local runtime，再创建新runtime与binding，不能复活旧缓存。

## 世界定义持久化

每个world使用带`schemaVersion = 1`的独立文件：
`<plugin dataFolder>/worlds/<world UUID>-definitions.json`。current document只保存`worldId`、
`definitionRevision`、保护区域及其有向边界、固定进路表、每条进路完整的有向traversal，以及固定闭塞的完整有向traversal。
`fixedBlocks`是schema 1的additive字段并有明确empty default，因此不升级独立sidecar版本。identity使用稳定namespaced ID与
canonical UUID字符串；位置统一保存canonical AB metres，方向使用`ViewDirection`。派生interior、rail reverse index、occupancy
geometry、topology revision和编译后的Basic restriction都是可丢弃缓存，禁止写入sidecar。

codec固定`encodeDefaults = true`和`ignoreUnknownKeys = false`。未知字段、非version 1、非canonical UUID、文件world ID与
加载world不符或domain invariant失败均拒绝；runtime不包含旧格式reader、upcaster或兼容分支。decode边界把DTO一次转换为
当前`ProtectionRegionDefinition`/`ProtectionRouteTableDefinition`/`FixedBlockDefinition`，业务编译器不处理磁盘版本。

读取顺序是primary再到同目录`.bak`：两者都不存在返回`Missing`；primary无效而backup有效时保留损坏primary、使用backup并
返回可记录的candidate诊断；存在的candidate全部无效返回`Corrupted`，不能回退为空定义或覆盖原文件。写入在同目录
`.tmp`先完整编码，并把有效旧primary复制到独立`.bak.tmp` staging；以atomic move替换primary作为唯一commit point，随后才
best-effort把staged旧primary轮转为`.bak`。commit point之后的backup/cleanup失败不把已提交primary误报成失败；commit point
之前的异常不修改primary/backup。不支持atomic move的文件系统只对对应replace使用普通move fallback。启动读取/编译是world load
低频路径且当前同步执行；route callback只读取已编译publication，不做JSON I/O。

## 保护区域领域

`region` package 只保存 SimpleSignals 自己的信号策略，不进入 Basic 公共 API：

- authored definition 只保存 namespaced region/boundary ID、region revision 和有向 rail cut；不保存第二份权威子图；
- `ProtectionRegionCatalog.publish` 捕获一个不可变 `TopologyState` root 和 plugin definition revision，先完整派生候选 snapshot，全部通过后才原子替换；失败保留旧 snapshot；
- graph resolver 在边界处分割临时 rail segments，从第一个 inward half-edge 做 BFS，验证所有 inward half-edge 同属一个连通内部、outside 不会绕路重连，并拒绝正长度区域重叠；
- published snapshot 保存 disposable interior slices、region lookup 和 rail-to-region reverse index。create/update/load publication 会先以 `O(V+E)` 派生拓扑，再为每个定义解析连通内部并检查区域两两重叠；这些工作不进入 Tick；
- route matcher 只投影冻结路线一次，再通过 rail reverse index 把 occurrence 分发给实际候选 region；它把 facing-local segment 坐标转换为 canonical AB，要求每个 region 只有一个连续 entry-to-exit interval，并派生 route-metre window。成本包含路线 occurrence、索引命中、候选区域的 interior slices 以及交集排序，而不是 `regions × full route`；
- matcher 不搜索路径，也不复制 Basic 的结构连续性/turnout legality 校验。Basic 在资源申请时仍独立验证同一冻结路线并编译不可删除的物理资源底线。

route-window 只描述 region entry/exit。列车需要在出口后继续申请固定闭塞或移动闭塞距离属于后续 plugin route-decision 层，不放回 region model，也不复制旧 Basic `exitClearance` 单窗口合同。

world runtime稳定注册`simplesignals:protection-regions` inspection source。binding provider捕获一次上述immutable compilation snapshot，
只投影host已经选择的exact frozen route；每个完整window形成`trigger=entry`、`minimumRequestedAuthorityEnd=exit`的声明。nominal authority
未越过entry时不申请区域，严格越过后至少把同一次Basic请求抬到exit；Basic随后从扩大后的同一冻结路线前缀独立推导rail、turnout和
diamond资源。插件不提交region ID整区锁，因此本步骤不会把一整个咽喉强制互斥。部分进入、部分离开、同一区域重复经过或过期topology
在binding编译时拒绝，不能形成部分授权。当前floor只防止授权终点落在区域内部；列车尾完全越过exit及后续固定/移动闭塞距离仍由下一批
route-decision策略决定，不能把`exit`误称为完整净空终点。

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

world runtime的definition入口为一次更新建立全新的candidate region、route-table和fixed-block catalog；三者都成功后才以
一次volatile publication替换live triple。注册到host的binding保持稳定，只读取该publication，因此已捕获的source和其他读取者
会看到完整新组或完整旧组，不会看到跨revision混合状态。拓扑变更不会触发callback内重建；在authoring/persistence层显式
republish之前，旧source/snapshot按root identity检查fail-close。production启动会把已恢复的非空定义完整编译后发布。

## 固定闭塞定义与索引

`FixedBlockDefinition`由稳定namespaced block ID、单调revision和一条从入口到出口的完整有序有向traversal组成；每段使用
rail UUID、`ViewDirection`及canonical AB闭区间。定义不保存长度、occupancy或route occurrence，这些都是exact topology root下的
派生值。

`FixedBlockCatalog.publish`只在load/authoring低频路径执行：它防御性复制并排序完整定义集，验证block ID唯一和保留定义revision
单调，随后针对一个exact `TopologyState` root验证每段rail/范围及相邻段的物理continuation。跨rail连接必须是Core topology声明的
有向continuation；仅共享一个道岔node但该entry没有到目标port的continuation仍拒绝。所有block编译成功后才一次替换publication，
任一失败保留旧snapshot。读取者必须以同一个topology对象取得snapshot，仅revision数值相同的另一个root也视为stale。
authoring坐标最多允许超出rail末端`Curve.TOLERANCE`，编译几何会夹回真实rail长度；更大越界或夹回后不再具有正长度的traversal拒绝。

每个编译结果保存作者定义、端点容差归一后的有向traversal、总长度，以及覆盖整段block的`RailwayOccupancyAreaDefinition`。occupancy geometry按rail合并相接或重叠的
canonical interval；entry boundary位于首段travel start且facing指向block内，exit boundary位于末段travel end且inward facing为
末段行驶方向的反向。因此AB与BA block均可直接交给Basic同一通用occupancy engine，Basic仍不知道它是固定闭塞。

publication同时建立`rail UUID -> block IDs`反向索引。给定冻结路线时只遍历route segment rail ID并去重命中的block，再读取候选，
不执行`all blocks x route`扫描。设拓扑规模为`V+E`、定义总traversal数为`T`，publication为`O(V+E+T log T)`（排序和rail-local
interval合并），route-local候选发现为`O(R+K log K)`，其中`R`是route segment数、`K`是唯一候选数。这些工作均不进入physics tick；
后续common section observation只消费已编译length/geometry/snapshot，不重新解析定义。

world install会把全部固定闭塞的已编译occupancy geometry组成一个
`simplesignals:fixed-block-signals` source；同一个source ID也用于对应的inspection binding，使规则输出可直接失效当前列车binding中的
block声明，而不经过Bukkit回调或插件自建列车索引。没有固定闭塞时以owner-scoped empty replacement明确清空该类source，不创建占位监测区。
Basic在同一physics epoch完成occupant和ENTER/LEFT推导，SimpleSignals callback只把changed area ID映射回fixed-block section并原子发布
不可变occupancy与规则结果snapshot。规则输出本身不在epoch callback里按列车生成restriction；它只返回发生变化的block ID，SimpleATO在
下一次受影响列车的既有controller update中，把完整active block集合投影为入口处限速restriction。首次callback必须为全部block建立
baseline；此前snapshot为unknown而不是空占用。没有配置规则的block不产生额外限速，仍由既有资源授权约束进入。
后续occupant/crossing不变时host不会调用callback，snapshot对象保持不变。因此没有新tick loop，也没有
`all trains x all blocks`扫描。保护区域当前只接入冻结路线inspection/authority floor，尚未加入这个common occupancy read surface；
等具体联锁规则需要时复用适合分支区域的观测，不提前虚构全局长度语义。

同一world candidate完整编译后、outer publication替换前，runtime还会用已编译interval检查fixed block与所有保护区域的正长度interior
重叠；大于`Curve.TOLERANCE`的交集拒绝整批candidate，只有共用边界点允许。失败不取得edit permit、不写盘且保留旧publication。
这个检查只发生在load/authoring路径，不进入tick。当前不规定两个fixed block之间能否重叠；双向block是否共享物理范围属于后续信号规则，
不能在基础catalog中提前禁止。

## 固定闭塞进路资源

SimpleSignals在plugin enable时向宿主注册一个`simplesignals:fixed-block`稳定资源类型和一个route-resource contributor。
资源`resourceId`直接使用作者定义的block ID；它没有设备准备参数。provider只接受同一SimpleSignals plugin instance提交、
当前world publication中确实存在且不带preparation的资源。world没有可用publication时contributor抛出失败，不能返回empty把损坏状态
解释成无限制。

contributor只在Basic的新授权或同路线suffix extension准入中执行，不是physics tick或全世界signal loop。它先把冻结route的每个
segment从facing-local metres转换为canonical AB起终点，并保留单调route metres；这份plugin-local projection同时供region matcher
复用。随后用publication的`rail UUID -> block IDs`反向索引取得候选，而不是扫描全部block。对每个候选，把相同rail/facing的route
occurrence与authoritative block traversal求正长度交集，并同时记录block-local与route-local区间；两个坐标都连续的piece才会合并。

只检查本次`desiredAuthority`前缀：authority恰好停在entry不申请block；一旦与有向block产生正长度交集，合并结果必须完整覆盖
block-local `[0, length]`，否则整个contribution失败。完整window产生
`ContributedRouteResource(StableInfrastructureResource("simplesignals:fixed-block", blockId), exitRouteBoundary)`。
同一block在一个authority prefix中出现多个完整window时视为路线有歧义并拒绝；当前不为理论上的多圈环线引入跨圈资源生命周期。
不同block独立加入同一个union；Basic仍自行编译不可删除的rail/turnout/diamond物理底线并原子claim。

匹配失败、stale topology、缺world runtime或provider validation失败均发生在registry mutation前。在线definition patch继续持有旧、新
受影响rail的host edit permit：若route已经claim这些物理rail，edit被拒绝；若edit先取得permit，route claim被拒绝。因此无需把
definition revision编码进resource ID，也不新增第二个revision token。publication通过volatile runtime引用读取；manager另发布只读
world→runtime map，使准入热路径不等待authoring持有的manager lock或文件I/O。

设冻结route occurrence数为`R`、命中的唯一block数为`K`、候选block traversal与同rail route occurrence形成的实际比较数为`I`。
projection与rail分组为`O(R)`，候选去重/稳定排序为`O(R + K log K)`，交集和window合并为`O(I log I)`；没有block时为`O(1)`返回empty。
这些成本只在授权申请/延长发生，不进入每tick occupancy source。world runtime另注册一个稳定fixed-block inspection source：
binding provider复用同一publication reverse index和exact frozen-route matcher，把每个完整block window编译成
`trigger=entry, minimumRequestedAuthorityEnd=exit`。SimpleATO只看到中立route-local floor；nominal请求未越过entry或已经
到达exit时不变，进入block后才向前抬到exit，重叠window按有序单向扫描连锁。随后上述contributor收到完整authority prefix并
继续做独立完整性验证，Basic仍可拒绝且只有Basic grant能扩大实际authority。定义patch的旧、新affected-rail permit会被active
frozen-route dependency阻止，因此retained inspection binding不会在运行中失效；不相交、非运营轨道仍可在线编辑。

## 定义编辑事务

插件内部唯一的definition patch入口在一个事务中接受region/table/fixed-block的upsert与remove，并要求调用方提交
`expectedDefinitionRevision`。空patch、整体无变化、重复操作、同ID同时upsert/remove、删除不存在对象以及revision冲突均在
取得host permit前拒绝；成功revision严格增加1。保留的region、table和block各自也必须保持revision单调，同revision改变内容或revision
倒退都属于validation failure。

runtime先用fresh catalog完整编译candidate而不修改live publication。prepared value绑定base publication对象身份、exact
`TopologyState` root、完整候选定义及解析后的affected rail IDs；后者包含region boundary、derived interior、route和block traversal。
manager以旧、新affected rail ID并集申请既有`InfrastructureResourceService.acquireEditPermit`，不创建第二套锁，也不把permit扩大到
不相关轨道。permit owner为`simplesignals:definitions/<world UUID>`，owner revision等于新的definition revision。

permit取得后再次要求`currentTopology(worldId)`与prepared root是同一对象。store在进入publication callback前完成JSON encode、
旧primary有效性检查、temporary write与backup staging。commit在runtime lock内先复核base publication未变化，再只以同目录
atomic rename提交primary；rename成功后执行一次volatile swap。callback返回后才轮换backup并清理staging。permit覆盖prepare与swap并在
所有返回路径关闭。
revision/patch/validation、host conflict/stale、persistence和world unavailable使用不同typed result；host拒绝时保留原始
`TopologyEditDecision`及blocker diagnostics。任一步失败都保留旧publication；save在替换primary前只写staging文件，替换primary
是唯一commit point，之后backup rotation只作best-effort recovery维护，不能把已提交primary误报为失败。

fixed-block增删/改形或exact topology root变化时，runtime先完整编译candidate definition与新的
`SectionOccupancyRuntime`，再以旧/新affected rails并集取得既有edit permit。permit拒绝发生在写盘和source callback前。
permit成功后通过host `replaceSourcesAtomically`提交：host先验证/编译所有owner的candidate source全集并检查retained restriction，
但不取得跨world simulation lock。已开始的epoch保留其捕获的旧immutable source，下一epoch才读取replacement并建立新baseline。
callback在runtime lock内复核base publication，以staged primary rename作为pre-commit，并重读exact topology root；root已变化则在
rename前返回typed stale。然后用一个预构造的
`RuntimeState`引用同时替换definition publication和section occupancy runtime；callback成功后host只安装已经
编译好的area source state。任何host prevalidation失败都不调用save，save或base commit失败则host保留旧source；没有rollback token、
占位area或可被physics观察到的错配窗口。删除最后一个block使用empty epoch-source replacement，只清除此类别而保留同owner的
route-planning source。region/table-only且exact root未变化时不重建相同fixed-block geometry，继续使用既有save+publication路径。

authoring是低频路径：patch map操作为`O(D)`。设fixed-block traversal总数为`T`、合并后interval数为`F`，跨section检查实际比较
`H`个rail-local候选，则完整candidate编译为`O(V+E+S+P log Q+T log T+S log S+F log S+H)`；affected-rail并集与写盘为定义规模线性。
这些规模相关的编译、encode、validation和ordinary write必须在async authoring worker；最终commit只允许exact root/base检查、
already-staged atomic rename与immutable reference swap。route callback仍只读取一次volatile publication和cached restrictions。当前只建立plugin-local事务 seam，尚无RailObjectWand、命令、
inspect/dry-run UI或拓扑迁移工具。`applyDefinitionPatch`以`ThreadGuard.ensureOffMainThread`拒绝错误的owner-thread调用；未来Wand/command必须先
dispatch worker，不能依赖调用约定而省略这一断言。

## 已评审的响应式方向与已完成前置观测

响应式signal更新必须是有向无环的provider-to-consumer失效链：占用变化、同一physics epoch的道岔active continuation变化和下游
section输出变化只能通知预注册的上游consumer；consumer不得反向enqueue自己的provider。一个update wave内每个node最多执行一次，
输出不变即停止向上游传播，不引入fixed-point求解器或通用双向graph runtime。

Core是live directed active continuation的物理权威。`RailwayWorldFrameSnapshot.activeContinuations`已在同一physics epoch公开
`entry RailEndpointRef -> selected exit RailEndpointRef?`；Basic通过`RailwayObservation`原样投影，不解释信号含义，SimpleSignals、
SimpleATO、WebUI、渲染或诊断等consumer均可读取。Core仅在topology或selection revision变化时重建turnout-port map，普通tick复用
同一immutable snapshot；processor期间发生选择变化会使候选frame拒绝发布。后续依赖链必须使用该值，不能临时读取mutable `RailView`。

插件内部的`OneWayInvalidationGraph`只保存预编译的provider-to-consumer邻接表和topological index。定义安装低频路径对完整
node/edge集合做一次Kahn拓扑排序；未知端点、重复node/edge、自环或任意cycle拒绝整份candidate，因此运行时没有fixed-point、
迭代上限或双向通知。一个update wave以已经发生状态变化的provider作为seed，只把其consumer加入按topological index排序的队列；
每个node最多入队和执行一次，recompute返回output unchanged时该分支立即停止，返回changed时才继续加入其consumer。多个变化provider
汇入同一consumer时，topological order保证所有本wave内可能变化的前驱先执行，而consumer仍只执行一次。设本wave实际到达`A`个
node、`D`条edge，成本为`O((A + D) log A)`、临时空间为`O(A)`，与world中未受影响的信号数量无关。callback失败由既有同步signal
epoch向外传播并fail-close，不为plugin-local output增加rollback协议。该机制本身不定义具体aspect/限速策略。

`SectionSignalRuleRuntime`在这张图上提供plugin-local规则执行边界。一个预编译binding只关联稳定signal ID、自己控制的section、按距离
排序的下游signal引用和一个注入rule；Java/Kotlin runtime不包含`R/Y/YG/G`词汇、速度表或model到速度的反向映射。rule输入只包含同一
`RailwayStateStamp`的本section占用/长度及已发布下游输出；rule必须返回有限非负`speedLimitMetersPerSecond`和非空但不透明的
`modelVariant`。这些值在进入snapshot前统一验证，未来Java、JS或YAML provider都构造相同binding/output。

section callback先建立完整occupancy candidate，再在临时signal-state map上执行受影响wave；全部到达的rule处理完后，以一个volatile
state同时发布occupancy和signal snapshot。相同输出不继续传播。rule抛错或返回非法output时，对应signal变成`Unavailable(reason)`，
其宿主安全限速为0且没有伪造model variant；任一下游unavailable会跳过consumer rule并继续传播unavailable，因此旧permissive输出
不会在失效后留存。首次baseline必须为全部binding建立Available或Unavailable状态；未收到首个physical baseline以前仍没有signal
snapshot。规则输出到每列车SimpleATO restriction的投影已经接通；“只有active turnout发生变化而area不变”的source唤醒仍须通过
一次单独评审的signal-epoch触发扩展完成，不能由本runtime自行轮询。

固定闭塞的area source与inspection source使用同一个`simplesignals:fixed-block-signals`身份。规则wave把实际变化signal映射为其
controlled block声明ID并随`SignalEpochResult`返回；SimpleATO只在当前路线binding中标记存在的声明，下一次controller计算把当前
完整active block声明集合交回`FixedBlockInspectionSource`。该source从同一原子section/signal snapshot读取每个block入口输出，生成
入口route metre上的`RouteSpeedRestriction`；`Available`使用规则给出的任意有限非负速度，`Unavailable`使用0，`modelVariant`不进入
ATO。列车通过入口导致active集合移除该声明时，source发布新的完整快照并清除已越过的限制。没有配置rule binding的block仍保留
整段authority floor与资源申请，但不会凭空产生速度规则。生产安装目前仍传入空rule binding集合，因此本接线尚无具体制式策略。

## 依赖与打包不变量

- 对 `Core`、`Basic`、`SimpleATO`、`BukkitPlugin` 和 Paper 使用 `compileOnly`；运行时由
  `JavaRailways` 与服务器提供。kotlinx serialization同样不打入JAR，由plugin descriptor声明Paper library。
- 只允许导入 `core.api.*`、`basic.api.*`、`simpleato.api.*` 与 `bukkit.api.*`；不得导入
  `*.internal` 或平台无关模块的实现包。
- Kotlin stdlib 由 Paper descriptor 的 library declaration 提供；仍不得打入插件 JAR。
- 插件只有 `SimpleSignalsPlugin` 一个 `JavaPlugin` lifecycle owner。
- 普通 `jar` 就是部署 artifact；不使用 shadowJar，也不把宿主 classes 打入该 JAR。
- 当前 `onDisable` 只丢弃plugin-local runtime map；Bukkit已经强制禁用时由host现有`PluginDisableEvent`路径删除失效
  planning callback。尚未实现planned-disable UI/state machine；未来正常停用仍必须先移除infrastructure dependency并调用
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
固定闭塞测试必须覆盖多rail AB/BA、连续性与非法道岔transition、重复ID、revision/root stale、失败publication原子性、与保护区域
正长度interior重叠拒绝但边界相接允许、reverse index、
occupancy boundary方向/几何、schema-1 empty default/round-trip/严格非法输入，以及runtime patch/persistence/affected-rail整合。
world runtime测试还必须覆盖空启动、非空table改变Basic实际选路、candidate失败保留旧pair、topology变化到显式republish之间
fail-close，以及同UUID reload得到新binding。持久化测试必须覆盖固定current fixture round-trip、严格未知字段/version/world ID、
primary损坏时backup恢复、恢复后写入不以坏primary覆盖最后有效backup、双损坏保留原文件、写入backup轮转、loaded definition在
注册前改变Basic实际选路，以及orphan/corrupt输入只安装拒绝新寻路的fail-closed source而不发布可用runtime。authoring测试还须覆盖
组合patch、revision/patch/validation拒绝、三类host conflict透传、permit后root变化、save失败保持disk/live旧值、成功disk/source同步
和permit关闭。
