# 保护区域到固定闭塞的授权衔接合同

## 1. 状态、范围、触发条件与 owner

- **状态**：`CURRENT/FOUNDATION`，区域衔接与入口整列车净空门控已通过独立离线检查。
- **触发条件**：SimpleATO 为一列车建立或替换 exact frozen-route inspection binding。
- **Owner**：保护区域、固定闭塞及二者的 route-local 衔接由 `SimpleSignals` 拥有；授权请求下限由
  `SimpleATO` 应用；最终物理资源验证、原子 claim 和 committed authority 由 `Basic` 拥有。
- **范围**：保护区域的完整资源申请、直接相邻 fixed block 衔接，以及入场前整列车能够驶出出口的当前条件检查。
- **非目标**：不规定未来故障下的必然通行、侧防/接近锁闭、国家制式灯色或模型规则；不改变真实车尾释放。

面向使用者的说明见
[保护区域与固定闭塞的授权衔接](../../../../docs-site/docs/architecture/protection-region-authority-transition.md)。

## 2. 模块工作方式

正常流程如下：

```text
SimpleATO 已选择并冻结 RouteSnapshot
  -> ProtectionRegionInspectionSource 捕获一个 SimpleSignalsRuntimePublication
  -> publication-owned ProtectionRouteBindingCompiler 匹配完整 region windows、table route 与 fixed-block windows
  -> 对每个 region exit 查找同 route-metre 的 fixed-block entry
       0 个：minimum authority end = region exit
       1 个：minimum authority end = fixed-block 与运营路线交集的末端
       多个：定义冲突，binding fail closed
  -> 同一匹配声明中立 entry clearance：入口、车尾须通过的出口、精确入口自身信号身份
  -> SimpleATO 用同周期车身长度 L 和原有授权安全距离 m，取 max(上述静态下限, region exit + L + m)
  -> nominal request 严格越过 region entry 后应用该下限
  -> Basic 以扩大后的同一 frozen-route prefix 编译物理资源底线
  -> SimpleSignals 既有 contributor 为完整 fixed block 加入 block token
  -> Basic 原子 claim 完整 union；成功才发布更远 committed authority
  -> 控制器在原有单次前视内检查授权、停车点、下游红灯及前车，发布当前经过的净空结果
  -> 串行 signal epoch 合并净空、Basic 授权与实际设备状态；缺一项则入口仍停车
```

保护区域不保存 successor ID，也不提交默认独占的 region token。出口处分叉已经由 exact frozen route
消解：不在所选支路上的 block 不会形成 route window，因此不是后继候选。

## 3. 内部机制与关键算法

### 3.1 输入表示

- `ProtectionRegionRouteWindow`保存当前路线 occurrence 上的entry/exit绝对route metres。
- 同一保护区域可产生多个独立完整window；逐个验证有向入口、连续内部与出口，按entry route metre排序。重复window不创建新region或resource身份，内部停车仍拒绝。
- `FixedBlockRouteWindow`保存有向block与当前路线交集的entry/exit绝对route metres；路线端点允许位于block内部。
  `enteredAtBoundary`区分真正经过入口与从block内部起步；只有前者生成fixed-block入口检查声明，后者仍参与资源匹配而不伪造入口。
- 两者都是针对同一个`RouteSnapshot`的派生结果，不写入sidecar。
- fixed-block matcher输出按`entryRouteDistanceMeters`、再按`blockId`排序。
- publication包含固定进路表时，每个region window还必须唯一匹配同region、entry、exit及标准化有向path的
  `CompiledProtectionRoute`；没有表的region只验证完整穿越，不强制作者建立固定进路表。

### 3.1.1 冻结路线端点的精确复用

固定闭塞与保护区域匹配共用 `DirectedRouteOccurrence.routeDistanceAt(canonicalAbMeters: Double): Double`。
每次经过保存同一分段的 canonical AB 起止值和路线累计起止值；输入精确等于已保存的起点或终点时，直接返回
对应的 `routeStartMeters` 或 `routeEndMeters`，不重新加减。内部交点才先计算有符号的局部偏移，再加路线前缀。
反向 `canonicalAt(routeDistanceMeters)` 命中已保存的路线端点也直接返回原 canonical 值。

这不是近似吸附：不以 `Curve.TOLERANCE` 判定“命中了已有端点”，不把相邻的不同位置合为一个点。
例如前缀471.91541186264385、BA轨长162.55741170015818时，先加轨长再减同一个轨长会改变前缀；
已有端点无需这次往返。不同的重复经过分别使用各自 occurrence 的累计距离，不能按rail ID共用一个进度。

固定闭塞交集的起止位置各自转换，授权裁剪使用 `min(已转换的交集终点, 请求终点)`，不得再用
“重新计算的起点＋交集长度”重建路线末端。block-local 距离仍用于验证完整闭塞经过；部分授权裁剪时保留对应的
部分 block 长度，不把完整闭塞长度误当已授予距离。保护区域的两个交点也使用同一转换。
原有几何合法性、真实内部交点、完整进出及资源检查不变；本修复不新增容差、不扩大请求、不改变Basic grant。

转换本身 O(1)，不新增状态、索引或生命周期，仍在既有低频匹配中执行，总复杂度不变。无持久化或API迁移。
必须用逐值相等断言覆盖AB/BA的小数端点、内部切口、端点相邻的不同浮点值、重复经过，以及部分授权恰好裁在指定边界；
仅以误差窗口验证接近不能证明端点身份被保留。

### 3.2 快照一致性

`ProtectionRegionInspectionSource`每次binding或静态时刻表检查只捕获一次完整
`SimpleSignalsRuntimePublication`。publication-owned compiler随后取得其中的region、block及route-selection快照；它们必须：

1. 引用同一个exact immutable `TopologyState`对象；仅revision数值相同不够；
2. 使用相同的world definition revision。
3. 输入route的topology revision等于publication topology revision；即使region/block/table全为空也不能跳过。

任一条件不成立即拒绝，不能把两次publication拼进一个列车合同。

同一个compiler供运行期selector、inspection binding和时刻表静态检查使用。运行期selector把异常或表内进路不唯一
映射为已知相关入口的不可用binding，继续沿用现有fail-close选择；静态检查则直接失败，使时刻表在clock和初始定位
发布前拒绝。二者不重复寻路，也不读取占用、灯色、active turnout、列车或请求状态。

### 3.3 直接后继查找

对每个region exit `x`，在已排序block windows中对`x - Curve.TOLERANCE`做lower-bound二分查找：

```text
i = first index where block[i].entry >= x - tolerance
if i is absent or block[i].entry > x + tolerance:
    successor = none
else if block[i + 1] exists and block[i + 1].entry <= x + tolerance:
    reject conflicting definitions
else:
    successor = block[i]
```

不能把世界坐标距离、rail UUID是否相邻或拓扑node分叉数替代这个判断。这里比较的是已经完成有向路线
匹配后的route-local坐标；因此道岔另一支路不会被误认为第二个候选。

多个匹配不是正常选路歧义。它表示两个同方向fixed-block定义在当前冻结路线的同一入口开始并发生冲突。
当前fixed-block catalog尚未统一禁止不同block定义之间的重叠，binding仍拒绝同一入口上的冲突定义。
[单个双向闭塞](bidirectional-fixed-block.md)不依靠两个重叠定义：同一block在两种方向生成路线窗口，
此处照常选择当前冻结路线上的唯一后继，无须另一套区域衔接算法。

### 3.4 授权与资源连接

每个region window只生成一个`InspectionDeclaration`：

```text
declarationId = regionId
trigger = region entry
inspectionLeadTimeSeconds = 0s
minimumRequestedAuthorityEnd = successor?.exit ?: region exit
```

这是插件的静态 floor，不是完整入场条件。同一 window 还产生一个中立 `InspectionEntryClearance`，
`entryRouteDistanceMeters=entry`、`tailClearRouteDistanceMeters=exit`。
SimpleATO 用当前一致的保守车身跨度 L 和控制器安全距离 m 派生 `exit+L+m`，与静态 floor 取最大值。
车长来自 Core 已发布的完整车身轨迹，不是车厢数量或模型长度相加。

这不是直接grant。SimpleATO只在nominal request严格越过trigger后抬高requested boundary；Basic仍可拒绝。
扩大后的prefix随后进入既有`FixedBlockRouteResourcePolicy`，由它独立匹配block交集并加入
`resourceType=simplesignals:fixed-block, resourceId=<blockId>`资源。Basic再加入不可删除的rail、turnout和
diamond资源并原子处理union。

后继block可以包含运营停车点。若冻结路线在block内部结束，successor window的exit是该路线终点，
这个静态floor不能超过它；列车仍申请整个block token，并在停车期间保留，后续申请通过既有队列延长释放位置。
“申请整个闭塞资源”不等于“必须取得到闭塞物理出口的行车授权”。保护区域本身仍要求完整入口到出口，
本规则不允许在咽喉保护区域内部停车。

运营终点裁剪不裁剪净空证明：若 `exit+L+m` 超过路线终点，入口保持停车；不能以已经取得裁剪后的
block token 代替足够空间。授权虽足够、但下游红灯或实际停车余量使车头无法到达 `exit+L` 时同样不放行。

衔接只处理一个直接后继，不递归连接第二个fixed block。后续普通fixed-block declarations可按自己的入口
继续抬高未来请求；region source不得自己遍历一条block链。
控制器所需空间可以覆盖更多后续窗口，仍由宿主的一次前视和已有声明处理，不由区域 callback 递归申请。

## 4. 接口、数据结构与单位

当前内部入口：

```kotlin
ProtectionRegionInspectionSource(
    publication: () -> SimpleSignalsRuntimePublication
)

SimpleSignalsRuntimePublication.routeBindingCompiler
    .compile(route: RouteSnapshot): ProtectionRouteBindingMatch
```

跨模块输出复用SimpleATO公开值，不新增region专用API：

```kotlin
InspectionDeclaration(
    declarationId: String,
    railId: UUID,
    facing: ViewDirection,
    triggerRouteDistanceMeters: Double,
    inspectionLeadTimeSeconds: Double,
    minimumRequestedAuthorityEndRouteDistanceMeters: Double?
)
```

所有距离均为从当前冻结路线起点计算的metres；rail上的authoring坐标仍为canonical AB metres，不能混用。
几何匹配判断沿用`Curve.TOLERANCE`；已有端点身份的复用使用精确相等，不引入吸附窗口。source ID固定为`simplesignals:protection-regions`，block resource type
仍为`simplesignals:fixed-block`。本合同没有新增持久化字段或schema version。

`InspectionDeclarationBinding.entryClearances` 中的中立要求及 `InspectionClearanceSnapshot` 完整结果归
[SimpleATO 净空合同](../../javarailways/simpleato/entry-clearance.md)。入口自身红灯的排除身份仅来自本次匹配
进路绑定的保护信号设备；没有绑定设备时排除集合为空。排除还必须匹配本次入口坐标，不能按来源整批忽略。

## 5. 不变量、生命周期、线程与版本

- 匹配只发生在route binding创建或替换时，不进入physics tick、signal wave或Bukkit回调。
- `RouteSnapshot`、region snapshot和block snapshot在一次binding编译中均视为immutable。
- binding保存完整replacement declarations，不在运行中增量修改某一个region。
- active frozen-route dependency与edit permit阻止相关定义在binding存活期间被无保护地编辑。
- region definition不保存successor；topology或definition变化后重新绑定并重新派生。
- 灯泡线的每次经过分别产生原region ID与不同trigger的声明，复用已存在的`(ID, trigger)`唯一性。Basic的过长道岔申请提示必须经SimpleATO通用floor回退，不能把保护区域内部当作可停车的重试终点。
- region ID默认不成为独占资源；冲突由实际物理资源和明确的插件附加资源表达。
- update handler不产生速度restriction；本source提供静态floor和中立净空要求。控制器使用自己的约束 owner 安装入口等待，具体信号仍由既有信号来源投影。
- 任一异常都在Basic registry mutation前终止binding，不能留下部分claim。
- `routeInfrastructureDependencies`复用相同compiler做应用前检查并返回空resource集合；region没有默认exclusive token。
  它不建立inspection binding、不运行update handler，也不发布restriction或修改selector。

## 6. 校验与错误矩阵

- region/block snapshot不是同一topology root → binding失败，无声明、无资源mutation。
- region/block definition revision不同 → binding失败，防止混合publication。
- route不能完整进入并离开命中的region → region matcher拒绝binding。
- route起点/终点位于fixed block内部 → 合法，window裁剪到路线端点，floor不越过运营终点。
- route在非端点处偏离fixed block定义的有向路径 → fixed-block matcher拒绝binding。
- region exit没有同点block entry → 合法，floor保持region exit。
- region exit恰有一个同点block entry → 合法，floor使用block exit。
- region exit匹配多个block entries → fixed-block定义冲突，binding fail closed；这不是道岔分支选择。
- 计算出的trigger或minimum end超出frozen route → `InspectionDeclarationBinding`拒绝。
- Basic后续claim或设备准备失败 → 不发布更远authority，插件不得自行放行。
- 缺完整当前车身帧、净空目标/安全余量超过运营终点、扫描未覆盖目标 → 净空不可用，入口等待。
- 已授权但入口后的实际停车/跟车边界不足 → 净空未就绪；资源准备可以继续。
- 净空结果缺失、来自其他列车/路线/经过或未来physics epoch → selector不放行。

## 7. 性能、复杂度与退化行为

设：

- `R`为冻结路线occurrence数；
- `K`为路线reverse index命中的唯一fixed block数；
- `I`为候选block traversal与同rail route occurrence的实际比较数；
- `B`为输出的fixed-block windows数（同一block可有多次经过）；
- `W`为命中的region window数；
- `M`为所有exit容差窗口中的实际同点候选数。

fixed-block matcher成本为`O(R + K log K + I log I)`；每个region对排序结果做一次lower-bound，衔接成本为
`O(W log B + M)`。存在固定进路表时，共用compiler另投影一次route，并仅在对应entry索引中比较候选path；
设实际比较的候选traversal总数为`P`，增加`O(R + P)`。region matcher保留自己的route projection与reverse-index成本。当前允许region source和
fixed-block source在低频binding阶段分别执行一次fixed-block匹配；不为消除这次重复引入跨source cache、共享
publication token或失效协议。

定义规模增大只增加路线绑定耗时；新增热路径只检查本列车实际前视净空要求和既有约束，复杂度及交接归
[SimpleATO 净空合同](../../javarailways/simpleato/entry-clearance.md)。异常规模或冲突定义的退化行为是拒绝该binding，而不是
跳过信号策略、任选后继或回退为无限authority。

## 8. Good / Base / Bad cases

### Good：唯一直接固定闭塞

region为`[5m, 25m]`，当前冻结路线上的block为`[25m, 30m]`，车身10m、安全距离2m。
trigger为5m，静态floor为30m，实际请求下限至少37m。只取得30m授权不能放行；取得足够授权且前方无停车阻碍才可放行。

### Base：没有直接后继

没有fixed block，或block从26m才开始。静态floor保持25m；车身10m、安全距离2m时仍要求至少37m授权。
没有直接闭塞不取消车尾净空要求，也不猜测额外闭塞资源。

### Good：后继闭塞内部的站台

region为`[5m,25m]`，车身10m，站台运营目标为30m：即使整个block token可获得，仍不能入场。
目标在35m之后还必须满足站台实际停车余量与授权安全距离；例如目标38m、两项余量均2m时，36m停车边界覆盖35m车头净空目标，且37m所需授权未越过运营终点。
停车不释放仍被车身占用的token，不扩大授权到运营路线以外。

### Bad：冲突定义

两个同方向block都从`25m`开始并沿当前冻结路线延伸。它们不是道岔两条可选支路，而是重叠定义；binding拒绝，
不能按ID顺序任选一个。

## 9. 必需测试与断言点

当前回归必须保持：

- 完整region crossing在无block时产生entry-to-exit floor；
- 同一region的多次完整穿越分别产生window和floor；缺失任一次入口/出口仍拒绝，不用重复ID掩盖断裂。
- reverse crossing保留路线方向和反向gate；
- route在region内部结束时binding失败；
- 空region/block publication产生空binding；
- 唯一直接block把floor从region exit抬到block exit；
- 路线终点位于直接后继block内部时，floor只抬到运营终点，contributor仍产生block token；
- 同点冲突block definitions使binding失败；
- entry差值恰在`Curve.TOLERANCE`内时匹配，超过容差时保持region exit；
- reverse route上的直接fixed-block successor；
- topology root或definition revision不一致时无部分声明；
- plugin内整合测试证明inspection扩大的边界会让既有fixed-block contributor产出对应block token。
- 时刻表静态检查中，无table完整穿越成功且不产生resource；内部起止、同点后继冲突、stale空catalog失败。
- 有table时，唯一表内路径与重复完整经过成功；缺失或重复匹配失败，但缺少signal/occupancy baseline不影响结果。
- 真实Dispatcher组合证明首次/替换失败均发生在clock与初始定位之前，替换恢复旧完整依赖且不产生claim/binding/output。
- `EntryClearanceTest`覆盖授权安全距离、实际停车余量、前车、不足终点、精确自身入口排除、BA/重复轨道跨度和单调扩长；
- `EntryClearanceRuntimeTest`覆盖per-train floor-only刷新、变化合并/首次baseline/来源替换/退出/空source排空、
  不可变公开值与正负零去重，以及真实Core直线100步移动的长度上界、floor实例和共享callback稳定；
- `ProtectionSignalControllerHandoffTest`用真实Core周期组合Basic、Dispatcher、控制器和信号，
  覆盖远端红灯、同点外部红灯、前车、短运营终点、相邻区域先下游后上游恢复及停止Dispatcher仍重判。

继续扩展本合同前应补充：

- 集成测试证明扩大后的同一次Basic admission同时包含物理footprint与block token，任一冲突不发布更远grant。

## 10. 迁移、未决事项与 Wrong / Correct

### 入场净空与迁移边界

直接后继 floor、净空空间与实际车尾释放是不同条件，不能互相代替。

- 保护区域匹配提供 occurrence-specific 的入口与出口。对于出口 x 与权威列车长度 L，
  要求车头能到达 x+L，授权需再包含控制器原有安全余量。无后继闭塞、后继闭塞太短和运营终点太近都不能绕过这个要求。
- SimpleATO 在已有前视扫描前将这段必需范围并入 horizon；之后只检查已合成的当前约束，
  包括授权、运营停车点、下游零速目标和前车间距，不重走路线、不重跑信号规则。
- 排除对象必须是当前有向入口、当前经过对应的自身红灯/净空等待，不能按类别忽略所有信号、所有零速限制或所有授权。
  入口停车不阻止资源准备；净空变化必须到达下一次控制计算，不能等待 Dispatcher 活动。
- 共享信号准备状态只有在资源、设备和这次列车的净空证明均成立时才允许。
  净空结果缺失、路线/列车不匹配、来自未来周期或所需扫描不完整时保持停车。
- 保持真实车尾释放、资源身份与存档格式不变；不增加全世界轮询、第二套资源管理或任意旧数据兼容。
- 回归须覆盖短后继/无后继、路线终点不足、已授权但下游红灯、站台停车、前车、
  本入口红灯不自锁、Dispatcher 停止后的恢复、超出原前视的障碍、BA 与重复经过及严格路线替换。

公开中立值与控制算法归[SimpleATO 净空合同](../../javarailways/simpleato/entry-clearance.md)。

本规则不改变sidecar格式，不需要runtime compatibility或世界迁移。仍未决定：

- 是否以及如何在authoring时禁止不同fixed-block定义的重叠。单个双向闭塞已使用一份定义，不需要重叠定义表达。

### Wrong

- 把某个固定block ID写进`ProtectionRegionDefinition`作为永久后继；
- 在道岔出口扫描所有物理分支，再让region任选一个block；
- region callback递归调用successor并逐个claim资源；
- 为简化冲突把整个region ID默认做成独占锁；
- 每tick重新投影route或扫描所有blocks。

### Correct

- route planner先确定exact frozen route；
- region和block各自保持独立定义；
- binding阶段只连接所选路线上的route-local windows；
- 一个minimum end形成一个Basic admission，Basic原子验证完整资源union；
- 多个同点匹配按定义冲突fail closed，并在未来可移到更早的authoring校验。
