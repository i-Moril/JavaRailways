# TunnelBridgeBuilder Backend 规范

## 适用范围

适用于 `TunnelBridgeBuilder/`：独立 Paper/Folia add-on、纯横断面扫掠算法、JavaRailways rail sampling/service、Block Wand/menu/state 和 undo。主插件 rail/operation service 的实现不属于本模块。

## 开发前检查

- [ ] 确认依赖能力来自 `bukkit.api.rail.RailTargetService`、`bukkit.api.operation.PlayerOperationService` 或 Core/Basic API，而不是主插件 internal。
- [ ] 将改动分为 pure `algorithm`、rail/Bukkit adapter 或 `wand` state/UI，并在最小层测试。
- [ ] 对 block/player 操作标出 async plan、region apply、entity message 的 Folia owner。
- [ ] 修改 Wand JSON token/shape 前阅读共享[持久化政策](../../javarailways/shared/persistence.md)。

## 子文档索引

模块规模适中但 boundary 清晰，本页集中记录规则；当前不拆空模板。跨模块 API 见 [BukkitPlugin add-on 规则](../../bukkit-plugin/backend/interaction-render-persistence.md)。

## 模块与算法

- build 对 JavaRailways modules 使用 `compileOnly`，`TunnelBridgeBuilder/build.gradle` 的 `bukkitPluginYaml` 通过 `depend.add('JavaRailways')` 生成 runtime dependency metadata；deployable JAR 不嵌入主插件或语言 runtime。
- `TunnelBridgeBuilderPlugin.onEnable` 从 Bukkit services 加载 rail targeting 与 player operation；缺失 service 立即失败，不反射寻找 implementation。
- `algorithm` 使用 `Vec3`、`CrossSection`、`SampledCenterLine`、`SweepFrame` 和 `BlockPositionChange` 等纯值类型，不引用 Bukkit。保持 rectangular section、anchor bounds、orthonormal frame 和 path direction validation。
- rail adapter 以稳定采样间距覆盖首尾 arc length；结构保持 upright、跟随 gradient 但移除 rail cant roll。material/Slab 规则在 apply 前完整解析。

## Folia 与 undo

`RailSweepService` 的顺序是：target service → async plan → player owner revalidation → 按 chunk/region 串行 apply → 记录一个 `UndoableOperation`。规则：

- per-player `operationsInProgress` 防止重入，所有 success/failure/retired 路径都必须释放；
- async 完成后重查 player world；block mutation 只在对应 region scheduler；player action bar 只在 player scheduler；
- apply 前 capture `FullBlockSnapshot`，undo 验证 world identity 后按 region restore；记录 undo 失败要明确告知“已修改但未记录”，不能假装回滚；
- 不从 async worker 直接读取/修改 Bukkit block/entity。

## Wand 状态

`BlockWandStateCodec` 使用 `classDiscriminator = "type"`、`encodeDefaults = true`、`ignoreUnknownKeys = false`。固定 token `regular`、`match_half`、`match_half_fill_down`、`match_half_fill_up`、`ignore`、`air`、`material` 是持久化 contract。`cells` 必须为 8×5，primary/secondary rail cell 不重叠且在 editor 内。能力变体继续用稳定 `type`/`PlacementRule`，不命名为 V2。

## 质量检查

- [ ] 运行 `./gradlew :TunnelBridgeBuilder:test :TunnelBridgeBuilder:shadowJar`。
- [ ] algorithm 改动覆盖 straight/diagonal/curve、flat ends、frame orthogonality、invalid section/path 和重复 block merge。
- [ ] rail/service 改动覆盖 logical segment resolution、sampling endpoints、half-block placement、failure cleanup 与 undo snapshot。
- [ ] Wand 改动覆盖 strict codec、固定 token、grid mapping、primary promotion 与 corrupt input。
- [ ] API 依赖改动同时运行 `./gradlew :BukkitPlugin:test`。
- [ ] 需要时与 `:BukkitPlugin:runFolia` 联合做跨 region smoke。
- [ ] 运行 `git diff --check`。

证据：`TunnelBridgeBuilder/build.gradle`、`TunnelBridgeBuilderPlugin.kt`、`RailSweepService.kt`、`BlockWandState.kt`、`SweepPlannerTest.kt`。


## 主插件服务生命周期

`JavaRailwaysServices.acquire()` 每次操作取得当前配对服务的 lease，服务 unregister、register 或 owner disable 都使旧 lease 失效。`ServiceGeneration.Lease.use` 在每个 region mutation group 与 undo record 边界验证；异步 targeting/planning 回调也验证 lease，不能用上一代结果清除下一代的 per-player operation slot。

同实例主插件恢复后重新查 registry；跨类加载器 API 替换必须共同重载 add-on。关闭可能在多个 region group 之间发生，此时明确报告部分修改，不宣称已自动回滚。测试 `ServiceGenerationTest` 断言 owner 禁用、替换 fencing 和失效等待当前 mutation；实际服务事件顺序仍需实服验收。
