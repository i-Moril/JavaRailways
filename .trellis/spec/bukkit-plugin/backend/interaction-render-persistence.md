# 交互、渲染与持久化

## Commands、Wand 与 Dialog

- Cloud `PaperCommandManager` 是 `/javarailways`/`/jr` 的注册入口；`CloudCommandRegistrar.ROUTES` 与 `JavaRailwaysCommand` delegate 必须同步。新增 nested route 同时测试 route set、trailing-space suggestion token 和 permission/console/player boundary。参考 `CloudCommandRegistrar.kt`、`CloudCommandRegistrarTest.kt`。
- `AbstractWand` 将每个 item instance UUID 与 state 写入 PDC。每个持久化状态变更需在玩家 owner 上立即写回；Folia 停服不依赖最后扫描背包。decode 损坏时保留原 encoded data、使用内存 default 并记录警告；禁止自动覆盖不可读状态。interaction 进入 simulation context，但 player/entity UI 仍要 owner-aware。
- Dialog 输入 key 只允许 Paper 支持字符，当前用 `.` → `_`；NUMBER_TEXT 先 parse 再检查 finite，trigger enum 变更 commit 后重建可见字段。新增 `ConfigValueType` 时同步 input、capture、validation 与 tests。
- 玩家文本使用 Adventure `Component` 和资源/locale owner；固定 enum/JSON token 不翻译。

证据：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/AbstractWand.kt`、`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/ui/dialog/DialogRenderer.kt`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/tool/`、`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/ui/dialog/`。

## 渲染 pipeline

- Core 发布 immutable `TrainFrameSnapshot`；`WorldManager` 只 drain latest frame，并按目标 location 调度 `CarExtension.applyFrame`。render code 不反向读取正在变化的 Core physics state。
- `DisplayRenderBackendFactory` 根据 config 选择 `NATIVE`/`ENTITYLIB`，backend-specific entity/packet 细节隐藏在 `render.backend`。
- composite visual 显示顺序是 children before root passenger packet；隐藏顺序是 root before children。`CompositeModelVisibilityTest` 固定该 contract。
- model/audience/revision pipeline 保持 latest-wins、有界队列和 stale revision rejection；不要引入每车遍历全服玩家或每 tick 无界 scheduler submission。
- destroy/unload 必须幂等并清理 child/root/backend resources。所有原生临时实体在 `World.spawn` consumer 内、加入世界前设置 `isPersistent = false`；不能等 spawn 返回后再设置。Folia 真正停服可以跳过 native owner 删除，但本地 teardown、JR 快照和已写入的玩家工具 PDC 仍保留，参见 lifecycle 停服契约。

## Add-on API 边界

独立插件通过 `bukkit.api` service contract 获取能力。当前 `TunnelBridgeBuilderPlugin` 从 Bukkit `ServicesManager` 加载 `RailTargetService` 与 `PlayerOperationService`，缺失时 fail-fast。新增 service：

1. interface 与 value/result type 放在 `bukkit.api.*`；
2. implementation 可位于 `api.internal`；
3. `onEnable` 注册，`onDisable` `unregisterAll(this)`；
4. 文档线程、ownership、failure 与 lifecycle；
5. consumer build 使用 `compileOnly(project(":BukkitPlugin"))`，不 shadow 主插件。

## Persistence 与配置

- world 保存分为 infrastructure/runtime 两文件，必须选择相同 `infrastructureRevision` 的最新 primary/backup pair。写入使用 temp、backup、atomic replace fallback；transport 策略不等于 schema version。
- snapshot capture 对齐 Core/Basic revision/physics epoch；参考 `WorldSnapshotExtensionDataTest.kt`。
- sidecar（例如 `RailRenderMetaStore`、model snapshot、Wand PDC）各自是独立 boundary，只在自身 breaking change 时升级。
- 配置 loader 对 invalid bundled/user config fail-fast 并带文件上下文；例如 `loadRailModelConfigFromPlugin()` 不静默 fallback。
- extension payload 新设计使用 stable `typeId`，不要复制当前 Core class-name reflection legacy。

验证 persistence 时覆盖 missing、corrupt、primary/backup 组合、revision mismatch、atomic recovery、unknown field/version 与不可覆盖恢复点。权威路径：`persistence/WorldPersistence.kt`、`persistence/SplitWorldSnapshot.kt`、`ai_tasks/persistence_schema_evolution_rules.md`。


## Dialog 回调的运行代次

### 1. 触发范围

Paper native Dialog 的 callback registry 不随 Bukkit HandlerList 注销。旧界面在主插件禁用或 PlugManX 创建新实例后仍可能被点击，不能直接调用旧 Wand/session/controller。

### 2. 签名

`DialogRenderer(configUI: ConfigUI, dispatcher: PlatformDispatcher)` 必须显式传入创建本轮 UI 的 dispatcher。所有 `DialogAction.customClick` 使用 `playerDialogCallback(dispatcher, capture, action)`；TrainControlDialog 构造时捕获 dispatcher，不在点击时读取 plugin.platformDispatcher。

### 3. 契约

capture 在 Paper callback 内只复制 response primitive，不修改 session、PDC、世界或发送消息；action 交给捕获的 dispatcher.runForEntity，复用已有代次 gate 和 in-flight 计数。配置响应复制 text/boolean/float，VECTOR3D 的 xyz key 沿用 toInputKey。菜单选择、确认、选项重建、控制动作都受相同 gate 保护。

### 4. 校验与错误

非 Player audience 不执行。关闭后的旧 dispatcher 不提交任务，关闭前已排队的 action 在真正执行时再次检查。新 enable 使用新 dispatcher；plugin.isEnabled=true 不能证明旧 callback 属于新运行期。capture 不得把 Paper DialogResponseView 留给异步任务读取。

### 5. 正常、基本和失败场景

正常：有效菜单/配置确认在玩家 owner 上执行。基本：新运行期开的新界面照常工作。失败：打开界面后重载，再点击旧按钮，不能写 PDC、提交旧 session、修改旧 controller 或重新打开界面。

### 6. 必需测试

PlayerDialogCallbackTest 使用生产 adapter 和真实 PaperPlatformDispatcher，覆盖 response 当场复制、正常执行、排队后禁用拒绝、旧 callback 面对新 dispatcher 仍拒绝、新 callback 可用。真实客户端点击与 PlugManX 重载另行验收。

### 7. 错误与正确做法

错误：customClick 直接调用 entry.onSelected，或点击时读取 plugin.platformDispatcher。正确：创建时捕获 dispatcher，复制响应后通过其生命周期 gate 执行动作。
