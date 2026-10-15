# 保护区域信号与进路显示合同

## 1. 状态、范围与 owner

- 状态：`CURRENT/PARTIAL`。共享入口选择、三种设备的后端配置/状态计算、JS signal type 与模型呈现已实现；主信号Wand、进路删除/保护主信号重绑及表示器新建/映射/跨入口编辑均已提供，真实服务器/客户端验收仍待完成。
  默认进路已取消；保护信号要求真实列车请求及匹配经过的净空证明，离线验证记录见任务 `implement.md`。
- SimpleSignals 拥有保护区域、固定进路、信号规则和显示设备；Basic 拥有物理资源准入、设备准备、claim 和 committed grant；SimpleATO 合并限制并执行中立入口净空检查，不识别具体信号或进路类型。
- 非目标：不把保护区域类型加入 Basic/SimpleATO，不创建整区独占 token，不规定国家制式灯色，不让显示设备产生行车授权，也不根据道岔当前状态猜测或自动建立进路。

## 2. 用户可配置的三种设备

保护区域提供三种用户可见设备，但它们不各自实现一套进路计算：

1. **固定进路信号**绑定一条作者指定的固定进路，始终按该进路的状态运行规则。
2. **当前信号**绑定一个有向入口，只选择有效列车请求中的进路；没有请求时停车，不从道岔状态挑选进路。
3. **进路显示器**只显示共享选择结果中的进路身份，不产生限速或授权。日本式进路表示机和进路预告机使用同一抽象；玩家把设备放在所需位置并选择不同模型，不在后端增加“预告”类型。

固定进路信号和当前信号产生受验证的有限非负速度限制及不透明 model variant。进路显示器只产生显示 variant；不得用无限速度或占位速度伪装成信号限制。

`ProtectionSignalDefinition.kind`为稳定的`FIXED_ROUTE`或`CURRENT_ENTRY`，可空`bindingId`分别指向固定进路或有向入口；null明确表示未绑定，不选择其他进路，运行输出为Unavailable/0。非空引用仍严格校验。删除进路时的设备保留、入口关系与空表禁行归[工具合同](section-authoring-wands.md#删除进路保留设备与显式重绑)。
定义还保存 `inspectionLeadTimeSeconds`、必填 `signalType` 和可选实例 placement。`signalType` 从 SimpleSignals catalog
解析 modelId 与 logicId；占用、选路、route-ready 和树形下游事实交给 JS，Kotlin 只投影／校验输入输出，不保留速度档位表。
`RouteIndicatorDefinition`保存入口ID、无进路variant及route-to-variant映射，不包含任何速度字段。
映射允许只配置本入口进路的子集，未知或其他入口的进路仍拒绝；选中缺项进路时使用明确配置的无进路外观，但保留真实selection身份。机制及界面状态归[表示器作者合同](section-authoring-wands.md#表示器映射作者与新建)。
三类设备共用可选模型/摆放配置，轨道或地面位置不改变逻辑入口；具体存档与校验归
[模型显示合同](model-presentation.md)，不保留显示器原有的第二份轨道坐标。

固定进路信号不跟随当前入口的选路：即使当前入口选择B，它仍检查自己绑定的A的占用、实际道岔方向和出口下游。
A必须有匹配的当前准入请求、覆盖本区域要求范围及车尾净空目标的完整授权、正确实际道岔状态和当前净空证明，才算准备完成。
A没有请求或当前请求属于其他进路时保持停车。显示允许信号也不替代Basic资源授权。
当前信号则读取共享选择，仅使用被选进路的占用和下游依赖。未选支路的下游不可用不应把当前信号拉成0。

## 3. 没有请求时的状态

默认进路概念已取消，进路表不再保存 `defaultRouteIds`，选择器没有 `DEFAULT` 分支。
即使道岔恰好全部朝向一条合法进路，也不能据此让保护信号开放。
没有请求时，当前信号及固定进路信号输出配置的零速度状态；显示器使用无进路外观。
这里不自动扳动或复位道岔；设备自身的默认状态与信号进路选择是不同概念。

## 4. 当前信号如何选择进路

对一个有向入口，一次不可变 signal update 严格按以下顺序选择：

1. **有列车请求。** 若该入口存在当前生效的准入请求，选择请求指定的冻结进路。设备仍在准备或 grant 尚未覆盖完整保护区域 floor 时，保留该 route identity 供显示，但当前信号必须保持限制状态。
2. **没有进路。** 没有列车请求时，当前信号输出配置的停车外观和0限速；进路显示器输出无进路/熄灭状态。

列车请求只覆盖 Basic 当前正在准备或已经授权、且与该有向入口相关的请求。失败、结束或尚未开始处理的排队请求不保存为信号状态。若 Basic 无法给出唯一当前请求，状态为不可用并保持限制，不能按 train ID、集合顺序或时间戳自行挑选。

## 5. 请求进路与安全条件

请求进路的 route identity 一旦被选中，信号不得在准备期间改为观察其他进路：

- 当前区域未完整授权时：保留请求进路身份，`routeReady=false`，设备输出配置的0速度限制；
- Basic全程请求状态为`PREPARING`不等于每个历史区域都未准备。申请更远后缀时，旧committed authority仍可覆盖当前区域floor，沿途道岔正确则该入口仍ready；
- `GRANTED`：只有 committed authority 覆盖完整 floor 和当前净空所需授权，实际 active continuations 已与请求进路一致，且匹配经过的移动侧净空证明成立，才能运行正常进路规则；
- 请求失败/结束或全部资源释放：Basic 不再发布该请求；某区域的出口已经由成功释放事件证明车尾通过时，选择器先移除该入口关联，不必等待整条冻结路线结束。跨越更长范围的资源仍由Basic保留，不会因选择变更被释放；
- grant 与实际道岔方向不一致、topology stale 或观测缺失：fail-close，速度为0。

当前信号和固定进路信号都必须等待匹配请求完成 Basic 原子授权及设备准备，随后才把进路占用及出口下游状态交给规则。
还必须消费SimpleATO中立净空结果：已授权但出口后红灯、运营停车点或前车使空间不足时，入口保持停车。
这是进入前的当前条件证明，不保证后续故障/紧急停车永不使列车停在区域内；真实资源继续按车尾释放。

## 6. 共享状态与输出边界

`ProtectionRouteSelectionRuntime`在相关变化发生的signal epoch，为每个dirty入口产生一个不可变选择结果，供未来当前信号及所有绑定该入口的进路显示器共同读取：

```text
SelectedProtectionRoute
  entryBoundaryId
  source: REQUEST | NONE
  routeId?
  requestState?
  routeReady
  available
```

这是已实现的plugin-internal值，不是宿主公开API。`available=false`表示不能证明唯一、当前的选择，不能作为允许状态。
`routeReady` 表示匹配请求已取得足够授权、实际道岔一致且当前经过的整列车净空已证明；没有请求时为 false。
`requestState`按本区域floor是否已被committed authority覆盖计算，不照抄正在准备更远资源的全程state。
信号规则另外读取本进路占用和下游输出；显示器只读取route identity/source并映射为模型。

同一份选择结果保证主信号与进路显示器不会在一个 epoch 中显示不同进路。设备放置距离不改变其依赖：远处的进路预告机仍显式绑定同一个入口选择源，不要求沿轨道重新查找目标信号。
模型绑定已消费该状态并异步提交显示；后端快照更新不等于真实 Folia 客户端画面已经完成验证。

## 7. Basic 准入观测边界

现有 `InfrastructureResourceService.liveClaims()` 只是 owner/footprint 诊断，缺少 train、冻结 route、请求阶段及 desired/committed authority，不能解析 owner 字符串反推进路。

Basic提供value-only的`ActiveRouteAdmissionSnapshot`及`ActiveRouteAdmissionUpdate`，经宿主signal epoch输入到达SimpleSignals。
共享选择器不在physics线程调用Bukkit service，也不从claim owner字符串反推进路。信息包括：

```text
ActiveRouteAdmission
  frozen route identity/revision
  state: PREPARING | GRANTED
  requested authority end
  committed authority end?
  releasedThroughRouteMeters
```

正式接口不暴露 mutable registry、live train/world 对象，也不要求信号插件重新寻路。只有存在当前请求时才使用准入信息。
`requested authority end`限定本次申请实际覆盖的冻结路线前缀；不能因为完整route plan还包含后续保护区域，就把尚未申请的后续入口当成当前请求。

第一次冻结路线到达时，选择器复用区域/固定闭塞matcher和有向route projector，把区域窗口与同入口、出口、完整有向path的
已配置固定进路匹配，并缓存该窗口的完整authority floor。floor复用inspection的计算：区域出口，或同点唯一直接固定闭塞后继的出口。
这不是重新寻路；匹配失败或多解标记入口不可用，不任意选择进路。相同route对象的后续授权/释放变化复用缓存。

上述步骤由publication-owned `ProtectionRouteBindingCompiler`统一实现，inspection binding及时刻表静态检查也调用同一实现。
compiler只返回不可变窗口、floor、匹配的canonical compiled route及诊断，不保存request/clearance。selector仍只把
自己已编译索引中相关的entry加入`invalidEntries`，避免把其他region或未知entry带入运行期owner map；既有binding缓存、
prefix append与清除事实交接不变。没有route table的region可用于授权floor，但不属于当前信号的可选固定进路。

selector按train缓存`simplesignals:protection-regions`的最新完整`InspectionClearanceSnapshot`，
通过冻结路线身份、region声明ID、本次entry/exit坐标及非未来observation stamp匹配当前window。
它还检查`movementReady`和committed authority至少覆盖结果里的`requiredAuthorityEndRouteDistanceMeters`。
缺结果、空撤销或错误经过不能借用其他列车/前一圈的ready。新baseline从host的当前完整值恢复，
不在编辑commit callback中读取并发worker的可变结果。中立值与控制算法归
[SimpleATO净空合同](../../javarailways/simpleato/entry-clearance.md)。

入口关联只对requested authority已越过入口、且释放事件证明的车尾下界尚未通过出口的窗口成立。
`releasedThroughRouteMeters`不是“之前的所有资源都已释放”；它只用于撤销已经被物理车尾通过的入口请求关联。
Basic对剩余资源的排他校验独立继续生效；没有请求不能代替列车grant。

同一路线允许多次经过同一region/入口。matcher输出每次完整有向穿越；selector对一个入口选择**最早尚未清除且已经请求**的window，
先选window再判断固定route ID，不能先按固定route ID过滤而误用后面的授权状态。每次经过仍有自己的entry/exit/floor。

仅用`releasedThroughRouteMeters`会在长资源保留时滞后。因此selector还消费统一epoch source已有的固定进路监测区更新：
只有本列车真实的`LEFT`且boundary等于该进路出口，才能证明其当前经过已经清除。以该window的exit route metre推进请求内
一个单调清除位置，再更新相关入口关联。空occupancy baseline、车头越过出口或另一列车LEFT都不能产生这种证明。
配对LEFT时只按这个清除位置寻找对应经过，不能先用本epoch已更新的资源释放位置过滤：释放通知与LEFT可能同tick到达，
否则会把第一次LEFT误配给第二次经过。冷source baseline若包含多次尚不能区分的经过，保持限制状态；物理坐标相同不能证明是第几圈。
在线定义替换不是冷启动，但原子安装回调不与physics串行，不能在其中复制请求清除记录。
每world只保留最后实际处理过signal callback的section runtime引用；候选首次callback在同一串行physics线程，先从该已完成runtime交接清除事实，再处理本帧准入/真实LEFT。因此旧epoch晚于编辑commit结束时，其最后更新也不会丢失。
candidate worker只比较不可变拓扑、区域定义和compiled route；未变route复用其canonical compiled reference，首次交接按引用验证匹配，不在physics路径递归比较完整几何。
连续未消费编辑只传递这份不可变route引用映射并共享一个last-processed引用，不串起候选runtime链。有实际定义变化的binding不能盲目继承，折返仍重置。
宿主新baseline还必须接续未变Basic监测区的占用记录并带上本帧真实crossings；只交接selector而丢掉首帧LEFT仍会错误保留第一次ready。
候选编译worker不读取可变清除状态；新baseline以前，已有inspection声明仍通过缺少新规则baseline而限制为0。无关定义编辑不能让正在行车的重复进路遗失历史。
这个位置不是资源授权或第二个释放游标，既不调用registry也不释放资源；它只决定信号参考哪次经过。
相同route对象的准入变化复用原缓存；严格prefix append保留已有清除事实，折返新坐标则重置。原路线已清除的window仍保留在
不可变binding中，但不会重新成为当前入口请求。没有历史LEFT证据时不能根据空占用猜测跳到下一次经过；只允许已证实的下界移除旧window。

## 8. 响应式更新与复杂度

删除默认进路后，不再派生默认组合的物理冲突集合或做默认路线两两比较。
低频编译仍保存每条实际进路的转辙要求及canonical引用，供局部更新与未变几何接续；
运行时只检查当前请求所匹配进路的要求，不用扫描全部合法进路寻找替代选择。

- 当前定义发布时编译entry-to-routes、rail-to-entry和turnout-entry-to-entry反向索引；不在tick内遍历保护区域图。
- 请求第一次换绑路线才调用共用compiler计算匹配窗口/floor；相同route对象复用结果。维护entry-to-request-owner集合；请求变化只更新旧/新关联入口，不遍历所有列车的请求。
- 请求状态变化只唤醒对应入口；进路所需 active continuation 变化只唤醒引用该道岔的入口；选择输出未变化时停止向当前信号和显示器传播。
- 净空变化通过现有train-to-request-owner和owner-to-entry关联，只唤醒涉及的入口；dirty入口仍作为固定信号图种子，
  不能因共享选择值未变而漏掉固定信号准备变化。与其他兴趣合并一次callback，未变净空不重复执行规则。
- 真实出口LEFT通过area-to-route和train-to-owner现有请求索引只更新相关请求；不为每tick发布车尾进度、不扫描所有列车或查询live claims。相同冻结route的更新不重新比较完整segments，只有真正append/换绑才检查前缀。
- 占用或下游输出变化只运行依赖对应已选进路的信号规则；进路显示器不因无关占用变化执行。
- 不允许 `all trains x all regions`、每 tick `liveClaims()` 轮询、重新寻路或根据显示器放置位置进行轨道扫描。
- admission provider 或观测异常时退化为限制状态；不得以道岔朝向推测请求状态。
- 同一个入口出现多个当前owner时`available=false`；topology/world不一致时同样不可用。物理拓扑变化的baseline必须检查所有入口，包括没有道岔的单轨进路。
- 列车从物理frame消失时，host按已有准入兴趣反查唤醒相关选择器；请求分支不可用，不等待Bukkit完成下一次车尾检查。
- 未变化的选择保留旧publication，不构造新的选择快照。当前实现复制入口map的成本是每次实际选择变化`O(E)`，不是每tick；新路线匹配是低频缓存建立成本，道岔检查按被选进路实际要求数计。
- 被信号或进路显示器引用的固定进路拥有`PROTECTION_ROUTE`监测范围，复用现有轨道区间几何编译和Basic占用观测；它不因此成为固定闭塞资源。
  候选进路允许共享前缀或重叠，几何不参与真实固定闭塞与保护区域的不重叠校验。占用某条独立支路不会给另一条不相交进路虚构占用。
- production由一个`SectionOccupancyRuntime`回调依次处理同一epoch的入口选择、区间占用及混合信号依赖图。
  固定闭塞和保护信号共用一张无环单向图，不靠两个callback的执行顺序交换结果，也不增加fixed-point求解器。
- 同一有向入口可绑定多台设备；入口/区间/道岔变化通过预编译反向索引找到消费者。当前信号的候选路径依赖在编译时确定，
  运行时只激活已选进路的依赖；输出相同则停止向依赖它的信号传播。
- **入口被影响不等于共享选择值改变。** 本次selector实际处理的dirty入口必须作为规则图种子；仅显示器使用selection值变化集。
  固定进路信号检查自己的请求及物理状态，可能在current selection对象完全不变时从ready变为unready。
  root cause是将共享选择的相等性误当作所有设备输入的相等性（隐式假设＋组合覆盖缺失），不是需要增加全局轮询。
  回归必须保持current selection对象不变，改变同入口请求owner集合，断言固定信号由允许转0、且无关信号和显示器不被重复更新。
- 两类限速设备共用`simplesignals:fixed-block-signals`epoch/inspection source身份。变化的保护信号失效自己的设备声明ID，
  既有ATO inspection流程把最新有效速度投影到入口路线坐标，不为显示器创建声明或限速。
  signal epoch只标记变化；controller在下一次物理控制计算中复用本次感知，更新自己的inspection激活状态、
  投影完整active声明集合并在planner前应用restriction，不等待Dispatcher。不同列车的投影callback可并发，
  只读已发布的信号快照；共享规则图仍在串行signal epoch执行。必须用停掉Dispatcher后的真实控制测试验证，不能用直接发布restriction的测试代替。
  首个baseline前及无法证明ready时为0，但inspection资源申请仍正常进行；不能形成“0速度所以永远不申请”的循环。
- 同一source内的声明ID按类别派生：固定闭塞使用`simplesignals:fixed-block/forward/<namespace>/<path>`或`simplesignals:fixed-block/reverse/<namespace>/<path>`，
  保护信号使用`simplesignals:protection-signal/<namespace>/<path>`（将作者ID的唯一冒号换为斜杠，完整保留namespace/path）。
  映射集中在`SignalInspectionIdentity.kt`。这只是inspection内部身份映射，不改变作者ID或资源ID。
  固定闭塞主信号、保护主信号、进路显示器和附属信号共用一个设备 ID namespace，作者 ID 不得跨类型重复；
  inspection 派生身份仍按设备类别映射。失效通知、声明绑定和状态读取必须使用同一映射，不能混用作者 ID 与派生 ID。
- 显示器结果与occupancy/signals放入同一个不可变publication，callback结束才一次替换。
  不得在规则执行前提前发布新显示器结果，也不增加另一套显示revision或rollback协议。
- 设本次传播到达`A`个图节点、`D`条依赖边，图执行沿用`O((A+D) log A)`。
  这不等于整个callback只需该成本：不可变section/signal快照仍需复制并排序全量map，成本约`O(N log N)`，其中`N`为监测区/信号数；
  显示器选择实际改变时复制显示器map，成本`O(I)`。这些是变化回调上的既有publication成本，不是每tick执行全部规则，当前未做大规模性能基准。

## 9. 校验和测试

正常案例：列车请求A，完整授权、道岔正确、入口净空就绪且A及下游空闲；当前信号按配置输出，显示器显示A。
切换案例：列车请求B，准备时当前信号0、显示器B；完整授权和实际道岔一致后才使用B规则；固定进路A设备始终检查A。
错误案例：混合依赖cycle、非法variant/速度在prepare拒绝，旧定义和运行时不变；暂时缺失绑定目标或下游的设备保留配置并发布Unavailable，
运行中观测失效同样保持0，不能丢掉依赖后恢复放行或复用旧允许结果。

- 无请求但道岔全部正确：固定进路信号和当前信号都停车，显示器无进路，不创建 claim/grant。
- 请求出现后显示其 route identity；准备中保持0；完整 grant 且道岔一致后才进入正常规则。
- 固定信号绑定A，而入口当前请求B：A保持停车，不因其path碰巧物理开通而放行。
- 请求结束后恢复无进路，不锁存请求模型或旧允许输出。
- 申请更远后缀期间，已完整授权且道岔正确的旧区域保持ready；车尾通过旧区域后恢复无进路但不释放仍持有的长资源。
- 冻结路线虽含未来区域，但requested authority尚未到达其入口时，该入口没有当前请求。
- 同一region/有向入口重复经过时，真实tail LEFT使第一次退出选择，第二次按自己的请求floor判断ready；固定进路设备不得因同ID多次匹配而跳过READY检查。长资源仍held、空baseline、其他列车LEFT、append及折返均有独立断言。
- 无关定义在线替换保留重复经过的清除记录；真正冷baseline仍不可按物理坐标猜测经过次数。资源释放与第一次LEFT同epoch时，第二次经过不能被误清除。
- 暂停编辑commit，让旧captured epoch处理第一次LEFT后再完成替换：新baseline必须选择第二次未完整授权的经过，不能回到第一次ready。
- 首个replacement frame自身跨越第一次出口：真实Basic tracker/host输入必须保留LEFT，selector在该帧移到第二次；新/变更几何不伪造LEFT。
- 多次无关编辑发生在下次callback前、交接callback失败、拓扑/区域/route真正改变及新baseline前inspection0限制有独立断言。
- 固定进路信号不受其他进路的选择变化影响，只在其固定进路状态变化时更新。
- 当前信号与任意距离放置的多个显示器读取同一 selection revision 并始终显示相同 route identity。
- stale topology、grant/道岔不一致、多个无法唯一裁决的当前请求和缺少 baseline 全部 fail-close。
- 混合固定闭塞/保护信号依赖的cycle和非法依赖路径在load/prepare拒绝；暂时缺失下游在低频编译时标成Unavailable，
  连同其上游保持0，不在tick里重试查找，也不能因此阻止已有合法区间删除。
- 删除 `defaultRouteIds` 和两端独立规则直接修正未发布sidecar，版本为1；误升的2/3已恢复，不改变现有设备字段和功能。
  current fixture保存/恢复、在线patch和source原子替换须覆盖三种设备；非1版本或旧字段拒绝，不添加runtime兼容层。

当前回归入口是`ProtectionSignalDevicesTest`、`SimpleSignalsWorldRuntimeManagerTest`及`ProtectionSignalControllerHandoffTest`。
最后一个组合真实Basic准入、SignalEpochRuntime、Dispatcher和SimpleATO控制器：Dispatcher建立路线/授权后停止推进，
验证信号从允许变0以及恢复时，下一次控制计算仍更新入口限速；另验证初始0仍可申请及和其他运营限速取最小值。

## 10. 存档变化与容易混淆的行为

道岔摆对只证明路径物理开通，不能代替列车请求和资源授权。没有匹配请求就停车；
请求取得足够授权且设备状态正确后，才运行正常进路规则。

进路预告机不应再沿轨道搜索目标信号。它与进路显示器都显式绑定同一入口选择源，
只负责显示；位置和模型由玩家配置。

删除旧开发配置字段直接修正未发布 schema 1，不提供运行时升级或别名。需要保留不符合当前形状的开发文件时，应先保留恢复点，再通过单独的一次性转换处理；
不能自动覆盖旧主文件。中立净空要求与结果不写存档，不单独升级schema；双向入口独立规则已实现，
详见[双向闭塞合同](bidirectional-fixed-block.md)；具体距离规则仍待单独评审，不因门控完成而宣称已实现。
