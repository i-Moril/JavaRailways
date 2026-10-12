# 交互、渲染与持久化

## Commands、Wand 与 Dialog

- Cloud `PaperCommandManager` 是 `/javarailways`/`/jr` 的注册入口；`CloudCommandRegistrar.ROUTES` 与 `JavaRailwaysCommand` delegate 必须同步。新增 nested route 同时测试 route set、trailing-space suggestion token 和 permission/console/player boundary。参考 `CloudCommandRegistrar.kt`、`CloudCommandRegistrarTest.kt`。
- `AbstractWand` 将每个 item instance UUID 与 state 写入 PDC。decode 损坏时保留原 encoded data、使用内存 default 并记录警告；禁止自动覆盖不可读状态。interaction 进入 simulation context，但 player/entity UI 仍要 owner-aware。
- Dialog 输入 key 只允许 Paper 支持字符，当前用 `.` → `_`；NUMBER_TEXT 先 parse 再检查 finite，trigger enum 变更 commit 后重建可见字段。新增 `ConfigValueType` 时同步 input、capture、validation 与 tests。
- 玩家文本使用 Adventure `Component` 和资源/locale owner；固定 enum/JSON token 不翻译。
- RailWand结构替换先从immutable `TopologyState`捕获exact source-rail replacement plan，再申请world-local edit permit。
  Plan绑定同一root及最终影响中的既有rail实例；permit必须跨Core transaction、Basic metadata、render sidecar persistence
  与undo record写入持有。Split/merge/delete各用一个外层Core transaction覆盖结构mutation、Basic metadata、render sidecar、
  玩家state persistence与undo写入；RailWand在该transaction内安装prepared rails并应用exact connection plan，不再调用并行的
  `RailTopologyEditor` split/merge mutation。结构变更后的任一异常仍由同一Core transaction触发fail-close。
  Split/merge/delete使用source replacement plan；turnout create、selected-anchor connect、endpoint/grade/geometry rebuild
  使用Core在安装前生成的prepared explicit-connection plan。新独立rail保持断开；selected-anchor只连接选中endpoint及明确的
  批内相邻段；rebuild用旧到新endpoint映射保留已有连接。任何路径都不得按距离搜索附近endpoint或推断turnout stem/branch。
  Basic仅转换计划公布的exact existing endpoint/crossing identity，禁止用附近rail候选或整图扫描扩大impact。whole-world
  snapshot undo由Bukkit在async worker从exact prepared base/target rail值、render sidecar cache及Basic/SimpleATO immutable store
  snapshot一次性构造完整`before`/`expectedAfter`和对应delta；回到player owner后必须验证world instance、exact topology root，以及
  ordered physical connections没有变化。`SavedRailV1.activeTrackAtA/B`是transient运行选择，不进入structural undo的记录或相等性；
  undo应用时保留target结构中仍有效的当前选择，否则退回target首条connection或null。
  不为写入undo record重新遍历rail、metadata、render或运营对象。主动undo仍在owner上比较完整current snapshot，随后才把Core restore plan
  交给worker准备，并在应用前再次进行完整一致性检查；在非拓扑store具备统一revision以前，不能把这项检查降级为只比较topology root。permit持有到
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
- 取得slot后重新读取current records，并与undo捕获的完整旧集合比较；不相等即stale，防止后到的整文件目标覆盖先到编辑。
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

- Core 发布 immutable `TrainFrameSnapshot`；`WorldManager` 只 drain latest frame，并按目标 location 调度 `CarExtension.applyPublishedFrame`。render code 不反向读取正在变化的 Core physics state。
- `RenderableFrameSnapshot.velocity`是world-space m/s；`CarExtension`只做`/20`转换为Bukkit blocks/tick。body和bogie都从各自同一frame velocity转换，禁止用train标量speed×模型direction重建车体速度，也禁止把bogie的m/s原样传给entity。
- `DisplayRenderBackendFactory` 根据 config 选择 `NATIVE`/`ENTITYLIB`，backend-specific entity/packet 细节隐藏在 `render.backend`。
- composite visual 显示顺序是 children before root passenger packet；隐藏顺序是 root before children。`CompositeModelVisibilityTest` 固定该 contract。
- model/audience/revision pipeline 保持 latest-wins、有界队列和 stale revision rejection；不要引入每车遍历全服玩家或每 tick 无界 scheduler submission。
- destroy/unload 必须幂等并清理 child/root/backend resources。

### Car frame runtime stream 与换属主（CURRENT）

1. **范围与 owner**：Core拥有每个`CoreTrain`实例的process-local publication stream UUID及每节`CoreCar`的当前owner token；Bukkit只消费token并拥有per-car revision gate。该UUID不是train ID、physics epoch、schema version或持久化字段。
2. **工作模型**：simulation owner从mailbox取得frame后，先要求当前train的`publishedFrame`仍是同一对象，再把train的UUID和car frame排入目标region。region执行时只做car的原子owner-token比较，不读取live world train map或mutable cars list。
3. **内部机制**：car唯一owner为`AtomicReference<CoreTrain?>`，stream proof从owner的UUID派生，公开`Car.train`读取同一归属；构造成功绑定，结构转移更新引用，`CoreWorld.removeTrain`按exact旧train实例CAS退休，不清除已转移cars。per-car gate只在owner匹配后才切换stream和记录revision，因此旧task不能在新stream已接受后把gate切回。返回train身份不授权region读取其mutable集合。
4. **接口与数据**：`CoreTrain.publicationStreamId`和`CoreCar.isOwnedByPublicationStream`是Core implementation seam，不进入`core.api`或Bukkit add-on API。`PendingCarFrame`只持immutable UUID、frame value、revision和已有car render target，不持有旧`CoreTrain`或冗余train speed；平台速度由car frame本身提供。
5. **不变量与线程**：同一runtime train的UUID稳定且不同实例（含同ID重建/world reload）使用新UUID；同stream拒绝`revision <= latest`，换到当前owner的新stream可立即接受低revision。Core结构owner写token，任意region只原子读取；不得跨region遍历`CoreWorld.getTrain`或`CoreTrain.cars`证明ownership。
6. **错误矩阵**：负revision在stale判定前拒绝；frame构造已经拒绝non-finite vector，错误location owner仍由`BukkitThreadOwnership`失败；owner token不匹配和同stream stale/equal只丢帧且不改变gate。当前frame对象不匹配时simulation owner丢弃，防止同train ID重建把旧mailbox frame绑定到新stream。
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

`ModelPresentationService` 是第八个公开服务；定义、状态合并、附近玩家显示与生命周期见
[通用模型 API 契约](model-presentation-api.md)。
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

## Persistence 与配置

- world 保存分为 infrastructure/runtime 两文件，必须选择相同 `infrastructureRevision` 的最新 primary/backup pair。outer `InfrastructureFileV2` 包含 Core、Basic、SimpleATO 三个严格 current document，恢复顺序为 Core → Basic → SimpleATO；旧 outer format 不由 runtime reader 接受。Stage 2 ownership extraction 的真实旧源只有 outer V1 + Basic V1，纯迁移一步输出 outer V2 + Basic V2 + SimpleATO V1，不接受分批重构产生的中间版本。文件发现、dry-run、恢复点和 apply 仍由独立 09-24 迁移任务实现。写入使用 temp、backup、atomic replace fallback；transport 策略不等于 schema version。
- runtime snapshot capture 对齐 Core/Basic/SimpleATO 的 infrastructure revision 与 physics epoch；外层 `RuntimeFileV2` 同时封装三个 current document，恢复顺序固定为 Core → Basic → SimpleATO。正常 loader 不读取旧 runtime V1，也不保留双写/nullable fallback；旧开发存档只通过单独、可审计的一次性迁移工具升级。参考 `WorldSnapshotExtensionDataTest.kt`。
- sidecar（例如 `RailRenderMetaStore`、model snapshot、Wand PDC）各自是独立 boundary，只在自身 breaking change 时升级。
- 每车来源包通过`SavedCarRenderState.packageSnapshotHash`引用既有内容寻址包快照，模型树引用与行为包引用分开；默认null字段不升版，不能因联结选择主编组而替换其他车厢的脚本/actions。JS运行时状态不写入存档。具体加载、失败及生命周期见[每车JS行为](car-behavior.md)。
- 配置 loader 对 invalid bundled/user config fail-fast 并带文件上下文；例如 `loadRailModelConfigFromPlugin()` 不静默 fallback。
- `physics.yml`当前独立schema为2，重力键是必填正数`track.gravity`。schema 1的`track.gravity-mps2`只由
  `scripts/migrate_physics_config.py`在runtime之外单向升级；脚本默认dry-run，`--apply`写入前保留带时间戳的`.bak`恢复点。
  runtime loader不接受旧version或旧键。
- extension payload 新设计使用 stable `typeId`，不要复制当前 Core class-name reflection legacy。

验证 persistence 时覆盖 missing、corrupt、primary/backup 组合、revision mismatch、atomic recovery、unknown field/version 与不可覆盖恢复点。权威路径：`persistence/WorldPersistence.kt`、`persistence/SplitWorldSnapshot.kt`、`ai_tasks/persistence_schema_evolution_rules.md`。
