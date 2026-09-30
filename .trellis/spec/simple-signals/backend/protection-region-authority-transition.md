# 保护区域到固定闭塞的授权衔接合同

## 1. 状态、范围、触发条件与 owner

- **状态**：`CURRENT/FOUNDATION`，对应 `SimpleSignals` 提交 `ad62ee04` 之后的行为。
- **触发条件**：SimpleATO 为一列车建立或替换 exact frozen-route inspection binding。
- **Owner**：保护区域、固定闭塞及二者的 route-local 衔接由 `SimpleSignals` 拥有；授权请求下限由
  `SimpleATO` 应用；最终物理资源验证、原子 claim 和 committed authority 由 `Basic` 拥有。
- **范围**：只规定保护区域出口与当前冻结路线中唯一直接相邻 fixed block 的衔接。
- **非目标**：不规定 moving-block 出口边界、通用列车尾净空、侧防/接近锁闭、灯色、模型或速度规则。

面向使用者的说明见
[保护区域与固定闭塞的授权衔接](../../../../docs-site/docs/architecture/protection-region-authority-transition.md)。

## 2. 模块工作方式

正常流程如下：

```text
SimpleATO 已选择并冻结 RouteSnapshot
  -> ProtectionRegionInspectionSource 捕获 region + fixed-block immutable snapshots
  -> SimpleSignals 分别匹配完整 region windows 与 fixed-block 路线交集 windows
  -> 对每个 region exit 查找同 route-metre 的 fixed-block entry
       0 个：minimum authority end = region exit
       1 个：minimum authority end = fixed-block 与运营路线交集的末端
       多个：定义冲突，binding fail closed
  -> SimpleATO 在 nominal request 严格越过 region entry 后应用 minimum end
  -> Basic 以扩大后的同一 frozen-route prefix 编译物理资源底线
  -> SimpleSignals 既有 contributor 为完整 fixed block 加入 block token
  -> Basic 原子 claim 完整 union；成功才发布更远 committed authority
```

保护区域不保存 successor ID，也不提交默认独占的 region token。出口处分叉已经由 exact frozen route
消解：不在所选支路上的 block 不会形成 route window，因此不是后继候选。

## 3. 内部机制与关键算法

### 3.1 输入表示

- `ProtectionRegionRouteWindow`保存当前路线 occurrence 上的entry/exit绝对route metres。
- `FixedBlockRouteWindow`保存有向block与当前路线交集的entry/exit绝对route metres；路线端点允许位于block内部。
  `enteredAtBoundary`区分真正经过入口与从block内部起步；只有前者生成fixed-block入口检查声明，后者仍参与资源匹配而不伪造入口。
- 两者都是针对同一个`RouteSnapshot`的派生结果，不写入sidecar。
- fixed-block matcher输出按`entryRouteDistanceMeters`、再按`blockId`排序。

### 3.2 快照一致性

binding provider各捕获一次`ProtectionRegionCompilationSnapshot`和`FixedBlockReadSnapshot`。二者必须：

1. 引用同一个exact immutable `TopologyState`对象；仅revision数值相同不够；
2. 使用相同的world definition revision。

任一条件不成立即拒绝binding，不能把两次publication拼进一个列车合同。

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
当前fixed-block catalog尚未统一禁止所有block-to-block重叠，因为反向定义共享物理范围的语义仍未冻结；
binding保留最后一道防御性fail-close检查。

### 3.4 授权与资源连接

每个region window只生成一个`InspectionDeclaration`：

```text
declarationId = regionId
trigger = region entry
inspectionLeadTimeSeconds = 0s
minimumRequestedAuthorityEnd = successor?.exit ?: region exit
```

这不是直接grant。SimpleATO只在nominal request严格越过trigger后抬高requested boundary；Basic仍可拒绝。
扩大后的prefix随后进入既有`FixedBlockRouteResourcePolicy`，由它独立匹配block交集并加入
`resourceType=simplesignals:fixed-block, resourceId=<blockId>`资源。Basic再加入不可删除的rail、turnout和
diamond资源并原子处理union。

后继block可以包含运营停车点。若冻结路线在block内部结束，successor window的exit是该路线终点，
floor不能超过它；列车仍申请整个block token，并在停车期间保留，后续申请通过既有队列延长释放位置。
“申请整个闭塞资源”不等于“必须取得到闭塞物理出口的行车授权”。保护区域本身仍要求完整入口到出口，
本规则不允许在咽喉保护区域内部停车。

衔接只处理一个直接后继，不递归连接第二个fixed block。后续普通fixed-block declarations可按自己的入口
继续抬高未来请求；region source不得自己遍历一条block链。

## 4. 接口、数据结构与单位

当前内部入口：

```kotlin
ProtectionRegionInspectionSource(
    regionSnapshot: () -> ProtectionRegionCompilationSnapshot,
    fixedBlockSnapshot: () -> FixedBlockReadSnapshot
)
```

跨模块输出复用SimpleATO现有公开值，不新增region专用API：

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
相等判断统一使用`Curve.TOLERANCE`。source ID固定为`simplesignals:protection-regions`，block resource type
仍为`simplesignals:fixed-block`。本合同没有新增持久化字段或schema version。

## 5. 不变量、生命周期、线程与版本

- 匹配只发生在route binding创建或替换时，不进入physics tick、signal wave或Bukkit回调。
- `RouteSnapshot`、region snapshot和block snapshot在一次binding编译中均视为immutable。
- binding保存完整replacement declarations，不在运行中增量修改某一个region。
- active frozen-route dependency与edit permit阻止相关定义在binding存活期间被无保护地编辑。
- region definition不保存successor；topology或definition变化后重新绑定并重新派生。
- region ID默认不成为独占资源；冲突由实际物理资源和明确的插件附加资源表达。
- update handler当前不产生速度restriction；本source只提供route-local authority floor。
- 任一异常都在Basic registry mutation前终止binding，不能留下部分claim。

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

## 7. 性能、复杂度与退化行为

设：

- `R`为冻结路线occurrence数；
- `K`为路线reverse index命中的唯一fixed block数；
- `I`为候选block traversal与同rail route occurrence的实际比较数；
- `W`为命中的region window数；
- `M`为所有exit容差窗口中的实际同点候选数。

fixed-block matcher成本为`O(R + K log K + I log I)`；每个region对排序结果做一次lower-bound，衔接成本为
`O(W log K + M)`。region matcher保留自己的route projection与reverse-index成本。当前允许region source和
fixed-block source在低频binding阶段分别执行一次fixed-block匹配；不为消除这次重复引入跨source cache、共享
publication token或失效协议。

定义规模增大只增加路线绑定耗时，不增加每tick成本。异常规模或冲突定义的退化行为是拒绝该binding，而不是
跳过信号策略、任选后继或回退为无限authority。

## 8. Good / Base / Bad cases

### Good：唯一直接固定闭塞

region为`[5m, 25m]`，当前冻结路线上的block为`[25m, 30m]`。trigger为`5m`，minimum end为
`30m`；Basic在同一次请求中取得region路径物理资源和block token。

### Base：没有直接后继

没有fixed block，或block从`26m`才开始。minimum end保持`25m`；不得跨越间隙猜测moving-block策略。

### Good：后继闭塞内部的站台

region为`[5m,25m]`，block从`25m`延伸到路线终点以外，站台运营目标为`30m`。minimum end为`30m`，
同一次申请包含区域物理资源与整个block token；停车不释放仍被列车占用的token，也不扩大授权到站台以外。

### Bad：冲突定义

两个同方向block都从`25m`开始并沿当前冻结路线延伸。它们不是道岔两条可选支路，而是重叠定义；binding拒绝，
不能按ID顺序任选一个。

## 9. 必需测试与断言点

当前回归必须保持：

- 完整region crossing在无block时产生entry-to-exit floor；
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

继续扩展本合同前应补充：

- 集成测试证明扩大后的同一次Basic admission同时包含物理footprint与block token，任一冲突不发布更远grant。

## 10. 迁移、未决事项与 Wrong / Correct

本规则不改变sidecar格式，不需要runtime compatibility或世界迁移。仍未决定：

- moving-block territory中的精确post-exit boundary；
- 不同列车长度和净空余量下的tail-clearance合同；
- 是否以及如何在authoring时禁止同方向fixed-block重叠，同时允许未来明确规定的反向共享定义。

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
