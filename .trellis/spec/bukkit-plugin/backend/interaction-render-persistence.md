# 交互、渲染与持久化

## Commands、Wand 与 Dialog

- Cloud `PaperCommandManager` 是 `/javarailways`/`/jr` 的注册入口；`CloudCommandRegistrar.ROUTES` 与 `JavaRailwaysCommand` delegate 必须同步。新增 nested route 同时测试 route set、trailing-space suggestion token 和 permission/console/player boundary。参考 `CloudCommandRegistrar.kt`、`CloudCommandRegistrarTest.kt`。
- `AbstractWand` 将每个 item instance UUID 与 state 写入 PDC。decode 损坏时保留原 encoded data、使用内存 default 并记录警告；禁止自动覆盖不可读状态。interaction 进入 simulation context，但 player/entity UI 仍要 owner-aware。
- Dialog 输入 key 只允许 Paper 支持字符，当前用 `.` → `_`；NUMBER_TEXT 先 parse 再检查 finite，trigger enum 变更 commit 后重建可见字段。新增 `ConfigValueType` 时同步 input、capture、validation 与 tests。
- 嵌套配置的唯一有效条目与值快照归[Wand配置UI合同](./wand-config.md)。Dialog显示、触发按钮与回复捕获只消费`BuiltGroup.effectiveItems`，不能拼回原始子组items；初始值来自该构建的snapshot，不临时重读session。CR-041实现状态以该合同及审查记录为准。
- 玩家文本使用 Adventure `Component` 和资源/locale owner；固定 enum/JSON token 不翻译。

### 共用Wand按F切换模式（CURRENT）

BukkitPlugin公开`bukkit.api.wand.WandModeSwitch.handle(PlayerSwapHandItemsEvent event, Predicate<ItemStack> matches, Consumer<Player> switchMode): boolean`。它只提取既有AbstractWand的输入路由，不拥有模式、物品状态或铁路业务，也不注册服务或监听器。宿主AbstractWand及附属工具在各自的事件监听器中调用。

一次按F的流程是：已取消事件直接返回false；读取玩家当前主手物品，谓词不匹配返回false；匹配则先取消真正换手，再同步调用切模式回调，返回true。不要求按住Shift，只有副手持Wand时不接管F，避免副手工具抢占主手工具的快捷键。重复监听到已经取消的同一事件不会再切一次；F指默认换手动作，客户端可以重绑按键。

调用位于玩家所属线程/region，谓词与回调同步执行，不调度、不保存Player或ItemStack、无额外生命周期注册。回调负责权限、允许的模式、写回PDC、撤销旧选点/对话/异步接续并反馈当前模式；helper不替调用方判断编辑权限。回调拒绝切换或抛异常时仍保持换手取消，异常交原监听器处理，不暗中吞掉。非匹配物品保留原换手行为。

该处理为常数次调用，无轮询、全服扫描或额外任务。使用者必须复用原设置页的状态更新入口，而非只改临时显示。只读主手身份后直接执行回调，不使用事件中已交换语义的mainHandItem字段反推当前工具。无持久化字段/格式或版本变化；验证仅需真实swap事件的单次取消/路由回归及消费者模式和旧接续失效检查，不因这个小改动重复跑整模块套件。实际客户端按键效果另验。
- RailWand结构替换先从immutable `TopologyState`捕获exact source-rail replacement plan，再申请world-local edit permit。
  Plan绑定实际依赖的rail实例及连接；无关root更新不使其失效；permit必须跨Core transaction、Basic metadata、render sidecar persistence
  与undo record写入持有。Split/merge/delete各用一个外层Core transaction覆盖结构mutation、Basic metadata、render sidecar、
  玩家state persistence与undo写入；RailWand在该transaction内安装prepared rails并应用exact connection plan，不再调用并行的
  `RailTopologyEditor` split/merge mutation。结构变更后的任一异常仍由同一Core transaction触发fail-close。
  Split/merge/delete使用source replacement plan；turnout create、selected-anchor connect、endpoint/grade/geometry rebuild
  使用Core在安装前生成的prepared explicit-connection plan。新独立rail保持断开；selected-anchor只连接选中endpoint及明确的
  批内相邻段；rebuild用旧到新endpoint映射保留已有连接。任何路径都不得按距离搜索附近endpoint或推断turnout stem/branch。
  Basic仅转换计划公布的exact existing endpoint/crossing identity，禁止用附近rail候选或整图扫描扩大impact。whole-world
  snapshot undo由Bukkit在async worker从exact prepared base/target rail值、render sidecar cache及Basic/SimpleATO immutable store
  snapshot一次性构造完整`before`/`expectedAfter`和对应delta；回到player owner后必须验证world instance、实际依赖的rail生命周期身份及
  ordered physical connections没有变化。`SavedRailV1.activeTrackAtA/B`是transient运行选择，不进入structural undo的记录或相等性；
  undo应用时保留target结构中仍有效的当前选择，否则退回target首条connection或null。
  不为写入undo record重新遍历rail、metadata、render或运营对象。主动undo仅比较该action的before/expectedAfter差异条目，将逆向delta合并到当前snapshot，再准备Core restore plan；应用前重验相同差异，保留无关rail、metadata、render及运营对象变化。permit持有到
  outer Core transaction、metadata/render、player state persistence与显示刷新全部完成。MultiAction以CompletionStage逆序串行，
  不在Bukkit owner线程阻塞等待。snapshot plan统一执行准确occupancy验证；Dispatcher已经只使用common registry中的
  route claim，因此编辑器不再读取一份私有route-allocation状态。

### Scenario: RailRenderMeta structural staging

#### 1. Status / Scope / Trigger / Owner

- Status: CURRENT；适用于RailWand create/replace/rebuild/smooth/split/merge/delete的结构编辑，不改变主动undo应用流程。
- Trigger: `RailRenderMetaStore`是每world一个整文件sidecar，不能在owner transaction编码/写盘，也不能让不相交编辑互相覆盖。
- Owner: BukkitPlugin的`RailRenderMetaStore`拥有transport/cache；`PreparedRailWorldUndo`只管理本次准备值的生命周期。

#### 2. Module Working Model

worker在exact edit permit内捕获旧render records并构造完整目标records，调用`prepareSave`写staging及旧primary恢复副本；player owner复核world/topology后执行结构transaction并调用`publishRenderSidecar`原子替换primary。若随后任一步失败，coordinator在释放permit前调用`rollbackRenderSidecar`，最后把临时文件清理交回async worker。

#### 3. Internal Mechanisms & Key Algorithms

- 每个world UUID对应一个writer slot；只有worker可等待该slot，physics/player owner不等待。
- 取得slot后重新读取current records，仅校验本次before/after差异涉及的rail；将delta合并到current后编码，保留先提交的无关编辑。相关rail记录变化才拒绝。
- staging和rollback与primary位于同目录；publish/rollback使用atomic move，文件系统不支持时只对move使用replace fallback。
- primary身份是`exists + lastModified + length`；它负责检测插件外或旧同步writer在prepare与publish之间的普通变化。插件内部并发还由writer slot和record equality保护。

#### 4. Signatures And Contracts

- `prepareSave(worldId, worldName, expectedRecords, records): PreparedSave`只能在async worker调用。
- `PreparedSave.commitPrimary()`只做身份检查、rename和cache替换。
- `PreparedSave.rollbackPrimary()`只在已publish时恢复旧primary；旧文件原本不存在则删除新primary。
- `PreparedSave.close()`幂等清理临时文件并释放writer slot；`PreparedRailWorldUndo.close()`转交该生命周期。

#### 5. Invariants, Lifecycle, Threading, And Versioning

- edit permit从side-state捕获前一直持有到publish成功，或rollback完成；不能在rollback前释放。
- 成功、stale、worker failure、player retired和owner exception均必须最终close。
- publication后只有完整旧文件或完整新文件可见；cache与primary在同一store临界区切换/恢复。
- 这是transport变化，JSON仍为RailRenderMeta schema 2，不增加reader或兼容路径。

#### 6. Validation & Error Matrix

- current records不等于`expectedRecords`：准备失败为stale，不写primary。
- primary身份在prepare后变化：publication失败，不替换外部变化。
- current primary损坏：拒绝覆盖并保留原文件。
- owner transaction在publication后失败：先恢复旧primary，再报告原错误；rollback错误作为suppressed failure保留。

#### 7. Performance, Complexity, And Degradation

worker的record比较、JSON编码、旧文件复制均为`O(render record count + file bytes)`；owner publish/rollback为常数次metadata检查、rename和cache引用替换。一个world的整文件writer必须串行，拥塞时worker排队；其他world、physics和player owner不共享该slot。遗留非结构`saveAll`若发现prepared writer会立即失败，不在owner等待。

#### 8. Good / Base / Bad Cases

- Good: 两个world同时准备，各自独立完成。
- Base: 同world第二个编辑等待；第一个只close未publish时，第二个继续。
- Bad: 同world第二个编辑仍携带第一个publish前的records；必须stale失败，禁止“最后写入者覆盖全部”。

#### 9. Tests Required

- prepare前后断言primary直到commit都不变；commit后是current schema。
- primary身份变化时commit拒绝且保留变化。
- publish后rollback逐字节恢复旧primary。
- close删除`.staged`/`.rollback`并让等待writer继续。
- 排队writer基于旧records时失败，并确认失败路径释放slot。

#### 10. Migration, Open Decisions, And Wrong vs Correct

无schema migration。主动undo的完整owner-thread current-state检查仍按既有决定保留，后续只有在非拓扑store获得统一revision边界后另行评审。

- Wrong: 两个编辑各自读取整文件、修改自己的rail、随后无条件写完整列表。
- Correct: per-world串行准备，并在真正编码前验证调用方的完整base records仍是current；不current就重试整个编辑。
- 只修改既有rail上的运营属性或对象时，使用同一个permit服务但impact只包含确切rail ID，不扩张到连接邻居、node、
  turnout或diamond。RailWand限速metadata及其undo、RailObjectWand运行方向与destination、`/jr waypoint remove`都在
  mutation前重新绑定当前topology root和rail实例，并把permit保持到Basic metadata/运营对象与相关玩家side effect完成。
  `BukkitPluginDev /phys`创建轨道时从Core prepared connection plan取得确切既有交叉参与rail，删除时使用现有replacement/
  removal resolver；纯新增且不接触既有identity的batch无需制造空permit。离线文件迁移不修改已加载world，不经过runtime permit。

证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/AbstractWand.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/ui/dialog/DialogRenderer.kt`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/tool/`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/ui/dialog/`。

## 渲染 pipeline

- Core 发布 immutable `TrainFrameSnapshot`；`WorldManager` 在成功帧边界drain待刷新通知，按train ID解析当前实例并一次读取最新完整`publishedFrame`，再按目标 location 调度 `CarExtension.applyPublishedFrame`。region render code 不反向读取正在变化的 Core physics state。
- `RenderableFrameSnapshot.velocity`是world-space m/s；`CarExtension`只做`/20`转换为Bukkit blocks/tick。body和bogie都从各自同一frame velocity转换，禁止用train标量speed×模型direction重建车体速度，也禁止把bogie的m/s原样传给entity。
- `DisplayRenderBackendFactory` 根据 config 选择 `NATIVE`/`ENTITYLIB`，backend-specific entity/packet 细节隐藏在 `render.backend`。
- composite visual 显示顺序是 children before root passenger packet；隐藏顺序是 root before children。`CompositeModelVisibilityTest` 固定该 contract。
- model/audience/revision pipeline 保持 latest-wins、有界队列和 stale revision rejection；不要引入每车遍历全服玩家或每 tick 无界 scheduler submission。
- destroy/unload 必须幂等并清理 child/root/backend resources。
- 列车模型部件的 `item.type` 与动画 command/keyframe 的 `item-type` 在包解析阶段校验 Bukkit 材质名；构建和播放不以石头或原材质掩盖非法输入。解析错误沿现有文件上下文上报，不新增格式版本。
- 列车、轨道包及信号呈现统一使用局部 X=上方向×前方向、Y=上方向、Z=前方向；轨道 `offset` 和 `light_probe_offset` 均沿该基底，模型自身 XYZ 旋转不改变偏移轴。元素 scale 保留模型自身轴，不随轨道基底换轴交换。Core 曲线内部积分坐标不属于模型包合同。
- 轨道模型的 `repeat_length > 0` 由配置边界约束；metadata 准备和实体显示直接使用其值，包括小于 0.1 米的合法间距，不在消费端再次夹到 0.1 米。

### Car frame runtime stream 与换属主（CURRENT）

1. **范围与 owner**：Core拥有每个`CoreTrain`实例的process-local publication stream UUID及每节`CoreCar`的当前owner token；Bukkit只消费token并拥有per-car revision gate。该UUID不是train ID、physics epoch、schema version或持久化字段。
2. **工作模型**：simulation owner原子drain本批mailbox通知，按通知的train ID解析当前实例，一次捕获其最新完整`publishedFrame`，把该实例UUID和同一帧的car值排入目标region。新offer留给下一批，不在处理后清空队列。region执行时只做car的原子owner-token比较，不读取live world train map或mutable cars list。
3. **内部机制**：car唯一owner为`AtomicReference<CoreTrain?>`，stream proof从owner的UUID派生，公开`Car.train`读取同一归属；构造成功绑定，结构转移更新引用，`CoreWorld.removeTrain`按exact旧train实例CAS退休，不清除已转移cars。per-car gate只在owner匹配后才切换stream和记录revision，因此旧task不能在新stream已接受后把gate切回。返回train身份不授权region读取其mutable集合。
4. **接口与数据**：`CoreTrain.publicationStreamId`和`CoreCar.isOwnedByPublicationStream`是Core implementation seam，不进入`core.api`或Bukkit add-on API。`PendingCarFrame`只持immutable UUID、frame value、revision和已有car render target，不持有旧`CoreTrain`或冗余train speed；平台速度由car frame本身提供。
5. **不变量与线程**：同一runtime train的UUID稳定且不同实例（含同ID重建/world reload）使用新UUID；同stream拒绝`revision <= latest`，换到当前owner的新stream可立即接受低revision。Core结构owner写token，任意region只原子读取；不得跨region遍历`CoreWorld.getTrain`或`CoreTrain.cars`证明ownership。
6. **错误矩阵**：负revision在stale判定前拒绝；frame构造已经拒绝non-finite vector，错误location owner仍由`BukkitThreadOwnership`失败；owner token不匹配和同stream stale/equal只丢帧且不改变gate。simulation owner跳过已不存在train的通知；同ID重建时读取新实例自身快照，不把旧mailbox frame绑定到新stream。静止初始帧或末次停车通知即使跨过后续epoch也必须刷新，不得以快照对象不相同为由丢弃。
7. **性能与退化**：每个region task做常数次原子/UUID比较和per-car revision比较，无world/train扫描、锁或新scheduler task；gate最多保留一个UUID和revision，renderer销毁时随extension释放。
8. **情形**：正常连续帧按revision推进；旧owner高revision后link到低revision survivor立即接受新帧；新帧后迟到的旧owner帧因token不匹配拒绝；显式`CarExtension.commit()`应用live frame但不读写published-stream gate。
9. **必需测试**：真实Core train/car执行old-high → link → new-low → delayed-old顺序，并断言same-stream equal/stale拒绝、token在构造/link/remove转移；Bukkit focused test不能只分别测试两个无关联helper。Folia实体/客户端呈现仍需游戏内验证。
10. **迁移与禁例**：无持久化、版本或旧数据迁移。错误做法是把裸per-train revision当per-car全局revision，或region task读取live train集合；正确做法是用opaque runtime UUID先证明当前ownership，再比较该stream内revision。不能靠清零revision解决迟到旧task。

## Add-on API 边界

独立插件通过 `bukkit.api` service contract 获取能力。当前 `TunnelBridgeBuilderPlugin` 从 Bukkit `ServicesManager` 加载 `RailTargetService` 与 `PlayerOperationService`，缺失时 fail-fast。新增 service：

1. interface 与 value/result type 放在 `bukkit.api.*`；
2. implementation 可位于 `api.internal`；
3. `onEnable` 注册，`onDisable` `unregisterAll(this)`；
4. 文档线程、ownership、failure 与 lifecycle；
5. consumer build 使用 `compileOnly(project(":BukkitPlugin"))`，不 shadow 主插件。

信号附属的当前完整消费流程记录在
`BukkitPlugin/src/signalApiConsumer/README.md`，并由同目录Java fixture通过
`:BukkitPlugin:compileSignalApiConsumerJava`持续验证。正常停用只调用
`InfrastructureResourceService.unregisterOwner`这一条统一owner gate；不要先分别删除provider、contributor或signal source
来模拟插件卸载。信号含义、外观映射和模型包由具体信号插件负责；主插件只提供与铁路规则无关的普通模型显示服务。
显示清理不释放列车资源，也不绕过这条 owner gate。内部调度工具仍不是附属插件可以直接调用的 API。

`ModelPresentationService`的定义、状态合并、附近玩家显示与生命周期见
[通用模型 API 契约](model-presentation-api.md)。新增的`EditorTextService`提供玩家专用临时文字，契约见[编辑标记](editor-markers.md#首批临时文字-api)；两项均由宿主调度并提供owner清理完成通知，不接管铁路业务规则。
信号插件仍负责模型包、外观映射和摆放配置，不直接调用主插件的内部工具类。

`RailTargetService.findCurve(worldId, railId)` 复用既有选轨服务。
它通过加载时维护的世界 UUID 索引和 Core 的线程安全轨道查询，返回 canonical AB 不可变曲线；
可以在工作线程调用，缺轨道返回空、世界未加载或已开始卸载时明确失败。取得的旧曲线仍可读取，
但不是当前线路存在性或授权的证明。`api.model` 已公开不可变的普通模型树、姿态、缩放、完整物品外观和动画帧；
定义先校验，列表复制为只读集合，不包含列车、可变 ItemStack 或模型包读取回调。
内部 `ModelTreeBuilder.buildModel(ModelDefinition, Location)` 复用部件树和渲染后端，
`OrdinaryModel` 完整选择场景、恢复基础外观，且不启动列车动画 tick。它们仍不是附属插件可直接调用的显示 API；
附属插件使用 `ModelHandle` 提交状态和关闭模型，不直接取得 `CompositeModel` 或模型管理器。
卸载主插件前，先卸载附属插件并确认 `closeOwner` 返回的模型清理结果成功；不能把排入区域线程队列当成删除完成。
普通模型使用零插值，列车保持三 tick；
旧部件引用/命名空间普通入口已删除，不增加草稿兼容路径。

`InfrastructureResourceService.currentTopology(worldId)`只暴露该world runtime当前发布的exact immutable
`TopologyState` root，不暴露mutable `World`。插件必须把派生index与该root对象身份绑定；结构编辑发布新root后由插件在
低频publication入口显式重编译，不能在route callback里临时重建。未注册、损坏而未加载或已经unload的railway world直接
抛出`IllegalStateException`。同UUID重新加载是新的root/binding，旧root可继续作为immutable value读取，但不能冒充新world。

`InfrastructureResourceService.operatingDirections(worldId, topology, railIds): RailOperatingDirections`
只读 Basic 持有的公共运营方向，返回 Basic API 值，不取得资源或编辑许可。调用方传入该世界 exact current topology root 和所需 rail UUID 集合；
返回值保留这个 root，并一次捕获对应的不可变 `directions: Map<UUID, ViewDirection?>`。
每个请求轨道都有条目，值为 null 表示允许两向，非 null 表示仅允许该方向；缺轨道与世界不可用不能解释为 null。
过期或不属于该世界的 root 拒绝；读取前后仍须是同一个当前 root。方向子集在同一个存储锁内读取，
不会复制其他轨道的政策；时间和空间都是 O(U)，U 为本次请求的不同轨道数。
worker 和信号回调都可读取这份值，不能从公开接口取得可写 store、live World 或 SimpleATO 私有状态。

### 静态轨道限速子集读取

`InfrastructureResourceService.railSpeedLimits(UUID worldId, TopologyState topology, Collection<UUID> railIds): Map<UUID, Double>`向附属插件暴露Basic已有元数据的有效限速，单位m/s。Java map的值允许null：每个请求rail都有key，null表示它没有任何有限CURVE/SUBGRADE限制，不代表轨道或世界缺失。非null值有限正数；effective已取CURVE与SUBGRADE最小，不额外建立线路/时刻表速度存储。

读取流程复用operatingDirections的world注册provider：先验证service/world有效及exact current root，调用Basic store在同一个transactionLock下捕获所需不同rail的值，再校验返回key集合/值并复制为不可修改map，返回前复核同一world实例和root。Basic只读查询按所给topology校验rail存在，不反向导入Bukkit。null不能用`Map.copyOf`丢弃/拒绝，也不能以省略key冒充缺速。既有值复制后不受store后续变更影响，map不暴露live store或World。

这是低频准备API，worker可读；成本O(U)时间/空间，U为请求的不同rail数，不复制全world。service关闭、world卸载/同UUID重载、过期/外来root、缺rail、provider返回缺/多key或非法数值均明确失败；旧map仍是可读值，不代表当前world或授权。没有速度变更事件、订阅或tick轮询，静态速度只在已有准备/编辑流程重新捕获，不更改schema或版本。

必须验证真实store的CURVE/SUBGRADE最小、空limits显式null、subset/duplicate请求、复制不可变；service的过期root/world、provider前后world替换和返回值完整性；Java consumer编译。SimpleSignals如何计算距离与缓存由[JS观察计划](../../simple-signals/backend/js-signal-rules.md#准备时按静态轨道速度计算范围)拥有，不在host实现制动或信号规则。

## Persistence 与配置

- 全局`config.yml`的`ato.safety-distance-meters`默认5.0米，有限且非负，允许0；缺失取明确默认值，存在但类型/数值错误时拒绝加载。值加载为immutable配置，同步传给所有真实world和隔离仿真的Dispatcher与`AtoSafetyProfile`，不随train profile改变；制动能力仍按各车计算。用于静态保护及真实授权的机制归[SimpleATO停车目标与授权终点](../../javarailways/simpleato/terminal-route-completion.md)。这是带默认值的新增字段，不升级schema、不新增迁移/兼容reader，也不增加在线热切换。

- world 保存分为 infrastructure/runtime 两文件，必须选择相同 `infrastructureRevision` 的最新 primary/backup pair。outer `InfrastructureFileV2` 包含 Core、Basic、SimpleATO 三个严格 current document，恢复顺序为 Core → Basic → SimpleATO；旧 outer format 不由 runtime reader 接受。Stage 2 ownership extraction 的真实旧源只有 outer V1 + Basic V1，纯迁移一步输出 outer V2 + Basic V2 + SimpleATO V1，不接受分批重构产生的中间版本。已审计空运行数据的开发服现提供[独立离线迁移](offline-development-migration.md)，不打入JAR；09-24的未来端点格式/自动迁移仍未实现。写入使用 temp、backup、atomic replace fallback；transport 策略不等于 schema version。
- runtime snapshot capture 对齐 Core/Basic/SimpleATO 的 infrastructure revision 与 physics epoch；外层 `RuntimeFileV2` 同时封装三个 current document，恢复顺序固定为 Core → Basic → SimpleATO。正常 loader 不读取旧 runtime V1，也不保留双写/nullable fallback；旧开发存档只通过单独、可审计的一次性迁移工具升级。参考 `WorldSnapshotExtensionDataTest.kt`。
- sidecar（例如 `RailRenderMetaStore`、model snapshot、Wand PDC）各自是独立 boundary，只在自身 breaking change 时升级。
- 每车来源包通过`SavedCarRenderState.packageSnapshotHash`引用既有内容寻址包快照，模型树引用与行为包引用分开；默认null字段不升版，不能因联结选择主编组而替换其他车厢的脚本/actions。JS运行时状态不写入存档。具体加载、失败及生命周期见[每车JS行为](car-behavior.md)。
- 配置 loader 对 invalid bundled/user config fail-fast 并带文件上下文；例如 `loadRailModelConfigFromPlugin()` 不静默 fallback。
- `physics.yml`当前独立schema为2，重力键是必填正数`track.gravity`。schema 1的`track.gravity-mps2`只由
  `scripts/migrate_physics_config.py`在runtime之外单向升级；脚本默认dry-run，`--apply`写入前保留带时间戳的`.bak`恢复点。
  runtime loader不接受旧version或旧键。
- extension payload 新设计使用 stable `typeId`，不要复制当前 Core class-name reflection legacy。

验证 persistence 时覆盖 missing、corrupt、primary/backup 组合、revision mismatch、atomic recovery、unknown field/version 与不可覆盖恢复点。权威路径：`persistence/WorldPersistence.kt`、`persistence/SplitWorldSnapshot.kt`、`ai_tasks/persistence_schema_evolution_rules.md`。

### 列车资源与运行配置版本

`train-model-update-policy`共用模型与运行配置策略。SNAPSHOT在创建前捕获完整模型树、列车控制和各车厢来源包操作资源，异步原子写入内容寻址快照后才能发布列车；重启恢复只读所引用的快照，缺失、损坏或内容hash不符必须明确失败，不借用当前包或默认控制。
`SavedTrainRenderState.packageName`与`SavedCarRenderState.resourceType`记录创建模板身份，改名不改变身份，解编继承，联结保留每车身份与来源包行为。车厢模型/控制使用完整模型名作key，不能按basename混合；新字段保持开发中的既有schema，不新增运行时旧数据兼容分支。

FOLLOW_PACKAGE只由资源reload触发按创建模板类型统一更新。异步准备模型、控制和快照，回到global simulation owner后同时排除全部已加载world的physics；预演所有实例的活动manual/ATO控制器、速度偏移和制动安全参数，再发布成功类型并安装准备好的不可抛错状态。保留控制器身份、当前档位和动力历史。任一实例验证失败，其类型全部保留原版并记录带原始异常的错误日志，其他类型继续；拒绝更新候选本身不产生世界问题，当前配置或模型确实不可用时由对应状态生产者报告Issue；混合编组的联合配置不能被控制器接受时，拒绝该编组涉及的候选类型并重新准备其余类型。车长、地板高度、连接器或转向架位置改变时拒绝已有实例热更新，要求重建。

SNAPSHOT reload只发布资源目录，不刷新正常实例。已加载但运行配置不可用的列车保留对象、位置与占用并暂停移动，逐列报Issue；手动驾驶、ATO接管、联结与解编不得绕过暂停。reload可重验暂停实例，但固定模式只重读它自己的引用，验证成功才解除；不能用新目录替换旧配置。存档保留损坏对象原有引用，不能因快照缺失让整世界保存失败。模型不可用单独报Issue，不降级为当前包。世界owner清除已删除/退休列车的当前Issue，卸载清空该世界。

快照writer原子替换完整文件，loader校验版本与内容hash；模型树和运行快照保持各自独立边界，不为本次开发语义修正递增版本。

### 当前世界问题

`WorldIssueService`是宿主公开的线程安全诊断汇总服务，按world UUID及source/kind/objectId/code去重。
`report`保留首次出现时间并更新当前原因及最近时间；`replace`原子替换单个source的全集，其他生产者不受影响。
`snapshot`返回不可修改副本，Issue包含WORLD、TRAIN、PROTECTION_REGION、BLOCK、SIGNAL对象类型；无法解码对象身份的世界安装错误使用WORLD。
该列表不持久化、不保留历史，不改变铁路运行状态；生产者负责成功恢复/删除时解除，世界卸载清空世界，附属停用清空自己的source。
列表表示当前世界内容存在的问题，不是异常事件流。配置无效、轨道/区间定义失效、信号机规则或显示不可用、列车模型缺失属于内容状态；一次计算、命令、加载候选或更新尝试抛错本身不属于内容状态。原始Throwable、cause/suppressed和操作上下文写入[磁盘错误日志](error-logging.md)，不把异常栈作为世界问题的历史载荷。
规则异常导致信号机当前不可用时，可以同时记录异常日志与该信号机不可用状态；后续成功求值只解除状态，不删除磁盘记录。资源更新候选被拒绝而旧实例仍正常时，只记日志；不能将“保留旧版”误报为现有列车故障。恢复/删除/卸载清理必须保持source与对象身份隔离，不能为了清一次操作失败而清除另一生产者的真实缺陷。
必需回归：正常旧资源的失败更新不新增Issue；现有配置故障仍保持暂停和Issue，成功重验才清除；信号失败、重复失败、恢复、再次失败的状态与日志分别断言；firstSeen/lastSeen是当前问题时间，不是错误次数或历史记录。
游戏管理员通过`/jr issues [世界] [编号]`读取，玩家可点击列表打开原生详情并复制诊断，控制台必须指定世界。

CoreWorld拥有唯一infrastructureCoordination；宿主仅注册领域resource validator。InfrastructureEditPermit.execute在同步修改期间认证并固定许可，跨线程准备仍由原permit生命周期保护。卸载世界最后关闭Core registry，宿主service不独立创建或提前关闭第二个registry。

### 首批审查修复约束（2026-10-23）

- 删除列车时，显式 ID 不存在必须拒绝；仅省略 ID 才允许最近列车选择。
- HTTP 路由先按 rawPath 分段，再对每段百分号解码一次；路径中的加号保持原值，query 参数仍使用其原有 form 解码规则。
- 地图 marker 缓存签名包含 railId，只有换轨、其余属性不变时也必须重新生成。
- destination marker 句柄记录创建时的 world UUID，重命名只匹配同世界句柄，包括异步实体尚未生成的句柄。
- 内联模型变换由 definition 持有一次；import 的引用局部变换仍叠加。动画离散外观只取播放时间已到达的关键帧，不能随插值提前读取未来帧。

### 第二批审查：编辑和模型失败边界（CURRENT）

装饰命令先捕获无注册副作用的 PersistedRailRenderMeta，再执行 sidecar 写入；写入成功后才安装/删除显示并记录 undo。RailRenderMetaStore.updateAll 对损坏输入抛出拒绝错误，不可正常返回伪装保存成功；拒绝路径保留原文件、显示及撤销记录。端点发现从 CoreWorld 的物理轨道枚举，不依赖显示 sidecar；清除装饰不改变端点可编辑性。

AbstractWand 的 BoundState.writable 在 decode 失败时为 false。同一 item UUID 的所有持久化路径保留该标志，交互、模式切换和退出均不覆盖原损坏 PDC；缺少目标绑定时 persistState 也必须先 getState 解码以确定 writable，不能因退出或未先读取就默认允许写入；换成新物品才建立新绑定。DestinationEdit 保存原 Bukkit/Core world、编辑 token、configSession 和原 WayPoint/Destination 实例。DialogRenderer 在捕获输入、提交配置和切换 enum 页之前调用 isCurrent；Wand 在领域 mutation 内再次检查。世界切换、目标替换、新会话及卸载均使旧回复无效。检查为常数次身份查询；不能只在保存时检查，因为旧回复此前就可能污染共享 session。

ModelConstruction.build 在 ModelTreeBuilder 和 CarExtension 构造期间记录尚未转交的资源；成功挂入父 owner 后 transfer，失败按逆序销毁未转交资源并组合异步 cleanup。DisplayCleanupException 保留原构造错误为 cause，清理错误作为 suppressed，不吞掉原失败。车体、全部 bogie 和初始动画场景准备完成后才发布 CarExtension 字段；失败不留下可被重试覆盖的半成品。资源记录随本次构造结束释放，空间/清理成本 O(本次创建节点数)，不建立全局模型账本。ModelTreeBuilderTest 与 ModelConstructionTest 覆盖子树、兄弟、根创建/挂载和异步清理失败。无持久化格式变化或运行时旧数据兼容。

### 玩家指定放车位置（CURRENT，2026-10-23 用户决策）

`/jr train create` 仍把玩家位置投影到最近轨道，投影点固定作为车头位置。只在该点按 AB、BA 顺序检查后方能否容纳整列车（车体长度总和加车间连接器间距）；均不满足则拒绝，禁止指数探测、二分或自动前移寻找替代放置点。后续占用检查及 CoreTrain.setLocation 的完整摆位校验保留。方向选择不是位置调整；两向均可时保持 AB 优先。无需改动 Core 几何或持久化格式。


### 第三批：注册句柄、道岔结果与模型引用图（CURRENT）

InfrastructureResourceProviderRegistration持有本次准确ProviderRegistration实例，注销前后只按引用身份匹配。unregisterOwner后复用同provider对象重新登记，旧句柄close不删除新登记；不增加世代编号。现有活动资源/依赖拒绝门禁保留。

InfrastructureResourceService.selectTurnout保留CompletableFuture公开签名，返回内部帧间Future的独立dependent future。调用方complete/completeExceptionally/cancel/orTimeout只改变自己的等待结果，不更改内部队列是否执行、发布状态或完成时间；实际操作仍在既有帧间owner执行。不能让队列内部result.isDone被外部等待策略改变。回归检查外部完成前未切岔、帧间drain后实际切岔且世界continuation已发布。

SavedCarModelTree.build在加载/重载准备资源时检查当前活动解析路径，A→A或A→B→A拒绝并显示引用路径；已经完成的共享子零件或diamond复用允许。失败候选不发布新模型资源，旧可用registry/资源版本按原reload失败合同保留。未改变快照schema/hash或引入旧开发数据reader。真实YAML加载/重载及图构造回归分别验证拒绝环和允许复用。

## 逐车牵引／制动设备参数

`CarControlConfig.equipment`直接保存Basic的不可变`TrainDynamics.Equipment`，YAML入口为`control.equipment`。
字段使用kebab-case并在解析边界校验，缺项采用同一显式默认值，不增加模型版本或新旧模型选择分支。
`brake.regenerative-force`为电制动力上限；设备字段分别设置缸压、指令延迟、电制动功率/低速衰减、
电制动与牵引力建立/退出速率，以及牵引缓解联锁阈值。列车级`brake.apply-time/release-time`仍负责空气压力响应。
六种S100车厢的YAML和ZIP成员同步写出参数；这些是工程近似，不代表真实车型标定。

设备默认字段改变canonical train-package snapshot哈希；`CarDefinition.control`是transient，模型树快照哈希不因此改变。
离线`ai_tasks/migrations/migrate_equipment_snapshots.py`使用源/候选真实JAR codec重算包哈希并更新已知渲染引用，
覆盖current与`.bak`，先dry-run固定输入/JAR哈希，再停服apply并保存外部恢复点。不得放宽运行时hash验证、
改变默认字段编码或保留旧哈希读取分支。设备运行状态不因这次配置扩展自动成为持久化字段。
