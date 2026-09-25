# 交互、渲染与持久化

## Commands、Wand 与 Dialog

- Cloud `PaperCommandManager` 是 `/javarailways`/`/jr` 的注册入口；`CloudCommandRegistrar.ROUTES` 与 `JavaRailwaysCommand` delegate 必须同步。新增 nested route 同时测试 route set、trailing-space suggestion token 和 permission/console/player boundary。参考 `CloudCommandRegistrar.kt`、`CloudCommandRegistrarTest.kt`。
- `AbstractWand` 将每个 item instance UUID 与 state 写入 PDC。decode 损坏时保留原 encoded data、使用内存 default 并记录警告；禁止自动覆盖不可读状态。interaction 进入 simulation context，但 player/entity UI 仍要 owner-aware。
- Dialog 输入 key 只允许 Paper 支持字符，当前用 `.` → `_`；NUMBER_TEXT 先 parse 再检查 finite，trigger enum 变更 commit 后重建可见字段。新增 `ConfigValueType` 时同步 input、capture、validation 与 tests。
- 玩家文本使用 Adventure `Component` 和资源/locale owner；固定 enum/JSON token 不翻译。
- RailWand结构替换先从immutable `TopologyState`捕获exact source-rail replacement plan，再申请world-local edit permit。
  Plan绑定同一root及最终影响中的既有rail实例；permit必须跨Core transaction、Basic metadata、render sidecar persistence
  与undo record写入持有。Split/merge/delete各用一个外层Core transaction覆盖结构mutation、Basic metadata、render sidecar、
  玩家state persistence与undo写入；嵌套RailTopologyEditor加入该transaction，确保结构变更后的任一异常触发Core fail-close。
  Split/merge/delete使用source replacement plan；turnout create、selected-anchor connect、endpoint/grade/geometry rebuild
  使用Core在安装前生成的prepared explicit-connection plan。新独立rail保持断开；selected-anchor只连接选中endpoint及明确的
  批内相邻段；rebuild用旧到新endpoint映射保留已有连接。任何路径都不得按距离搜索附近endpoint或推断turnout stem/branch。
  Basic仅转换计划公布的exact existing endpoint/crossing identity，禁止用附近rail候选或整图扫描扩大impact。whole-world
  snapshot undo由Bukkit捕获current/target rail snapshot，在async worker交给Core完成唯一一次diff及stable-identity
  prospective plan；回到player owner后重新验证world instance、完整current snapshot与topology root。旧Dispatcher
  route-allocation guard只在这些验证之后、取得共同permit之前执行一次，避免异步准备窗口中的过期结论；permit持有到
  outer Core transaction、metadata/render、player state persistence与显示刷新全部完成。MultiAction以CompletionStage逆序串行，
  不在Bukkit owner线程阻塞等待。snapshot plan统一执行准确occupancy验证；在production Dispatcher尚未迁入common registry期间，
  undo仍保留其route-allocation guard，迁移完成后随旧allocator一起删除。

证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/AbstractWand.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/ui/dialog/DialogRenderer.kt`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/tool/`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/ui/dialog/`。

## 渲染 pipeline

- Core 发布 immutable `TrainFrameSnapshot`；`WorldManager` 只 drain latest frame，并按目标 location 调度 `CarExtension.applyFrame`。render code 不反向读取正在变化的 Core physics state。
- `DisplayRenderBackendFactory` 根据 config 选择 `NATIVE`/`ENTITYLIB`，backend-specific entity/packet 细节隐藏在 `render.backend`。
- composite visual 显示顺序是 children before root passenger packet；隐藏顺序是 root before children。`CompositeModelVisibilityTest` 固定该 contract。
- model/audience/revision pipeline 保持 latest-wins、有界队列和 stale revision rejection；不要引入每车遍历全服玩家或每 tick 无界 scheduler submission。
- destroy/unload 必须幂等并清理 child/root/backend resources。

## Add-on API 边界

独立插件通过 `bukkit.api` service contract 获取能力。当前 `TunnelBridgeBuilderPlugin` 从 Bukkit `ServicesManager` 加载 `RailTargetService` 与 `PlayerOperationService`，缺失时 fail-fast。新增 service：

1. interface 与 value/result type 放在 `bukkit.api.*`；
2. implementation 可位于 `api.internal`；
3. `onEnable` 注册，`onDisable` `unregisterAll(this)`；
4. 文档线程、ownership、failure 与 lifecycle；
5. consumer build 使用 `compileOnly(project(":BukkitPlugin"))`，不 shadow 主插件。

## Persistence 与配置

- world 保存分为 infrastructure/runtime 两文件，必须选择相同 `infrastructureRevision` 的最新 primary/backup pair。写入使用 temp、backup、atomic replace fallback；transport 策略不等于 schema version。
- runtime snapshot capture 对齐 Core/Basic/SimpleATO 的 infrastructure revision 与 physics epoch；外层 `RuntimeFileV2` 同时封装三个 current document，恢复顺序固定为 Core → Basic → SimpleATO。正常 loader 不读取旧 runtime V1，也不保留双写/nullable fallback；旧开发存档只通过单独、可审计的一次性迁移工具升级。参考 `WorldSnapshotExtensionDataTest.kt`。
- sidecar（例如 `RailRenderMetaStore`、model snapshot、Wand PDC）各自是独立 boundary，只在自身 breaking change 时升级。
- 配置 loader 对 invalid bundled/user config fail-fast 并带文件上下文；例如 `loadRailModelConfigFromPlugin()` 不静默 fallback。
- extension payload 新设计使用 stable `typeId`，不要复制当前 Core class-name reflection legacy。

验证 persistence 时覆盖 missing、corrupt、primary/backup 组合、revision mismatch、atomic recovery、unknown field/version 与不可覆盖恢复点。权威路径：`persistence/WorldPersistence.kt`、`persistence/SplitWorldSnapshot.kt`、`ai_tasks/persistence_schema_evolution_rules.md`。
