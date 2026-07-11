下面给你一份**按优先级排序**的清理清单，**只列建议，不改代码**。

---

# P0：建议优先处理的“兜底过度”问题

> 更新说明：
> - 其中 **1 / 2 / 3** 对应的问题已经处理过，这里保留作为历史记录。
> - 当前真正还剩的重点主要是 **4 / 5**。
> - 按当前约定，`offset.x/y/z`、`rotation.x/y/z`、`scale.x/y/z` 的默认值 **保留**，不计入剩余问题。

## 1) 运行时路径回退到了测试注册表
**代码位置**
- `Basic/src/main/java/work/art1st/mineRails/basic/pathFinding/RailMap.kt:71-75`
- `Basic/src/main/java/work/art1st/mineRails/basic/pathFinding/RailMap.kt:83-100`
- 对应正常注册路径：`BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/logic/RailWorldFactory.kt:36-42`

**问题描述**
- `World.basicWorldData` 先 `runCatching { getExtensionData(...) }`，失败后回退到 `TestBasicWorldDataRegistry`。
- 但运行时世界创建时，本来就会注册 `BasicWorldData`。
- 这会把“扩展没挂上 / 初始化顺序错了”这种真实 bug，伪装成“还能继续跑”。

**解决方案**
- 把测试回退从主运行路径移走。
- 生产代码改成：
  - 要么 `requireExtensionData(...)`，缺失就直接报错；
  - 要么提供 `basicWorldDataOrNull()`，由调用方显式处理。
- `TestBasicWorldDataRegistry` / `TestRailMapRegistry` 放到 test source set 或测试 helper 中，避免主代码偷偷兜底。

---

## 2) 世界快照损坏会被当成“没有存档”
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/persistence/WorldPersistence.kt:17-22`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/logic/WorldManager.kt:27-30`

**问题描述**
- `tryLoadSnapshot()` 把以下几种情况都折叠成 `null`：
  - 文件不存在
  - 文件不可读
  - 文件格式损坏 / JSON 解码失败
- `WorldManager` 拿到 `null` 后直接创建空世界。
- 结果：**坏存档 ≈ 没存档**，问题被掩盖。

**解决方案**
- 返回更明确的结果类型，例如：
  - `Missing`
  - `Loaded(snapshot)`
  - `Corrupted(error)`
- 对 `Corrupted`：
  - 至少打印明确日志；
  - 更稳妥的是拒绝加载该世界，或把坏文件重命名到 `*.broken` 后再处理。

---

## 3) 轨道渲染元数据加载过于宽松，存在“读坏后再写坏”的风险
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/persistence/RailRenderMetaStore.kt:93`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/persistence/RailRenderMetaStore.kt:109-124`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/persistence/RailRenderMetaStore.kt:190-199`
- 读取后立即参与恢复：`BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/logic/RailWorldFactory.kt:44-47`

**问题描述**
- 未知 `RailDisplayType` 被强行兜底成 `ITEM`
- 单条记录解析失败会被 `mapNotNull` 静默丢弃
- 整个文件解析失败直接 `emptyList()`
- 后续 `upsertRails/removeRail/saveAll` 又基于这个结果继续写回

这意味着：
- 一次读取异常，后续写入可能把原始数据进一步覆盖掉。

**解决方案**
- 给 `load()` 增加“是否有错误”的状态，例如 `LoadResult(records, hadErrors)`。
- 只要 `hadErrors == true`，就**不要自动写回覆盖**。
- 未知枚举值不要默默变 `ITEM`，可改为：
  - 记录详细错误并跳过；
  - 或保留原始字符串，延后处理。
- 对被跳过的记录打印具体 railId / 字段名。

---

## 4) 轨道 / 模型配置解析仍残留少量**非向量字段**默认值
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/RailModelConfig.kt:100-106,129-140`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/PartRegistry.kt:159-180,247-299`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/CarLoader.kt:87-97`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/PartInteractionConfig.kt:18-26`

**问题描述**
- 当前已明确：`offset/rotation/scale` 的 `x/y/z` 分量默认值需要保留；这部分不算问题。
- 但除此之外，仍有一些字段在静默补默认：
  - `rail_model.yml` 中 `rail element.item.material` 缺失时，会回退到外层 `material`
  - `interaction.width / height / driver / control-end`
  - `platform.rows / columns / spacingX / spacingZ`
  - `animations[].keep-final-state`
  - `animation.commands[].duration`
  - `brightness.block / sky`
  - `bogies[].wheel-diameter`
- 另外 `DriverControlEnd.fromConfig(...)` 对未知字符串会直接返回 `null`，本质上也是一种宽松兜底。

**解决方案**
- 除 `offset/rotation/scale.x/y/z` 外，把其余字段重新划分为“必填”或“真正可选”。
- 建议优先收紧为：
  - `rail element.item.material` 不再回退外层字段
  - `INTERACT / SEAT` 所需的 `interaction` 字段显式校验
  - `PLATFORM` 的 `rows / columns / spacing` 显式校验
  - 动画中的 `keep-final-state / duration / brightness.*` 缺失即报错，或先明确声明为真正可选
  - `bogies[].wheel-diameter` 缺失即报错
  - `control-end` 遇到未知值直接报错，不再静默变 `null`

---

## 5) `actions.yml` 仍在静默跳过坏项，文件坏了也可能继续跑
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/TrainActionConfig.kt:17-61`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/PackageLoader.kt:104-106`

**问题描述**
- `actions.yml` 缺文件时返回空 action 集合作为“可选文件”，这一点可以接受。
- 但只要文件存在，内部解析仍然过宽：
  - root 不是 `actions: { ... }` 时直接 `emptyMap()`
  - action 项 key/value 类型不对时被 `mapNotNull` 静默跳过
  - animation / sound 子项损坏时也被 `mapNotNull` 静默跳过
  - `display-name / executable / requires-control / at / volume / pitch` 继续使用代码默认值
- 结果是：`actions.yml` 可能处于“部分失效但不报错”的状态，排查体验很差。

**解决方案**
- 保留“文件缺失 = 没有 actions”的语义；
- 但只要文件存在，就改成严格解析：
  - root 结构错误直接报错
  - 条目错误聚合后一次性报出
  - 去掉 `mapNotNull` 跳过坏项
  - 对仍想保留的可选字段单独列清楚；否则缺失就报错

---

# P1：明显的过度封装

## 6) `ConfigUI` 这套框架明显超前设计
**代码位置**
- 接口：`Basic/src/main/java/work/art1st/mineRails/basic/api/ui/config/ConfigUI.kt:16-159`
- 实现：`Basic/src/main/java/work/art1st/mineRails/basic/impl/config/ConfigUIRegistry.kt:21-43,244-256`
- 实际构造点只有：
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt:150`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/OperationWand.kt:58`

**问题描述**
- 设计了分组扩展、构建监听、pending merge、player session、value callback 等完整框架。
- 但当前实际只服务于两个 wand 的配置对话框。
- 以下 API 全库无外部调用：
  - `registerToGroup`
  - `addBuildListener`
  - `removeBuildListener`
  - `getEffectiveItems`
  - `removePlayerSession`
  - `setValueChangedCallback`

这是典型的“为未来预留了很多层，但当前没有真实需求支撑”。

**解决方案**
- 两种路线二选一：
  1. **收缩**：把 `ConfigUI` 缩成当前实际需要的最小接口；
  2. **坐实**：如果真要做平台化，就尽快让外部模块实际使用这些扩展点。
- 在没有真实调用前，建议优先走第 1 条。

---

## 7) `playerSessions` 有生命周期设计，但当前没有被真正管理
**代码位置**
- `Basic/src/main/java/work/art1st/mineRails/basic/impl/config/ConfigUIRegistry.kt:43,252-257`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/RailWand.kt:407-411`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/tool/OperationWand.kt:328-331`

**问题描述**
- `ConfigUIRegistry` 维护了 `playerSessions`
- 也提供了 `removePlayerSession`
- 但退出时两个 wand 都没调用它
- 于是“有清理 API，但系统不走清理路径”

这属于**抽象做了，但闭环没做完**。

**解决方案**
- 至少在 `onPlayerQuit` 中调用 `configUI.removePlayerSession(player.uniqueId)`。
- 如果会话本质只是短期对话框状态，也可以取消常驻 session map，改成对话框生命周期内临时对象。

---

## 8) `PackageLoader.load(): Any?` 是不必要的泛化
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/PackageLoader.kt:24`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/ModelManager.kt:52,69`
- 相关类型其实已经存在：`BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/config/model/LoadedPackage.kt`

**问题描述**
- `PackageLoader.load()` 返回 `Any?`
- `ModelManager` 再用 `is LoadedTrainPackage` / `is LoadedRailPackage` 区分
- 但项目里已经有明确的 `LoadedTrainPackage` / `LoadedRailPackage`

这是典型“抽象层比实际类型系统更弱”。

**解决方案**
- 改成：
  - `sealed interface LoadedPackage`
  - 或拆成 `loadTrainPackage()` / `loadRailPackage()`
- 让返回类型本身表达语义，而不是回到 `Any? + is`。

---

## 9) 扩展系统 API 过硬，逼出了异常流和占位实现
**代码位置**
- `Core/src/main/java/work/art1st/mineRails/core/api/util/plugin/ExtensionDataContainer.kt:12-16`
- `Core/src/main/java/work/art1st/mineRails/core/api/util/plugin/Extendable.kt:7-13`
- 运行时到处补异常兜底：
  - `Basic/src/main/java/work/art1st/mineRails/basic/pathFinding/RailMap.kt:71-75`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/debug/AbnormalTrainMarker.kt:141`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/control/TrainControlManager.kt:320`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/control/TrainControlDialog.kt:148`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/train/TrainExtension.kt:133`
  - `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/command/TrainCommand.kt:696`

**问题描述**
- `getExtensionData()` 本质是硬转型
- 缺扩展时不是显式语义，而是异常
- 于是业务层开始用 `runCatching` 把“正常分支”当异常分支处理

这说明 API 本身不够贴合业务。

**解决方案**
- 补两个明确接口：
  - `getExtensionDataOrNull(plugin)`
  - `requireExtensionData(plugin)`
- 业务代码按语义选择：
  - 必须有 → `require`
  - 可选有 → `orNull`
- 减少 `runCatching` 作为控制流。

---

## 10) `BogieExtension` 是明显的占位类，且和当前渲染路径不一致
**代码位置**
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/train/BogieExtension.kt:11-25`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/extension/train/TrainComponentExtension.kt:15-20`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/render/chunk/RenderChunkManager.kt:47-52`

**问题描述**
- 注释已经明确写了：`placeholder to satisfy the extension registration system`
- 但全库没有 `BogieExtension` 注册代码
- 渲染分发里又存在 `is Bogie -> renderable.renderer.sendTo(player)`

说明这里的抽象关系并不干净：
- 要么 bogie 本来就不该有独立 renderer
- 要么 renderer 是必须的，那就不该靠占位类撑着

**解决方案**
- 明确 bogie 的渲染归属：
  1. **如果由 `CarExtension` 全权负责**：那就不要让 `RenderChunkManager` 对 `Bogie` 走独立 renderer 分发；
  2. **如果 bogie 也能独立渲染**：那就真正注册并维护 `BogieExtension`，不要只留 placeholder。

---

# P2：低成本可清理的死代码 / 空抽象

## 11) 明确的死抽象：`Factory<T>`、`UuidGenerator`（已处理）
**代码位置**
- `Core/src/main/java/work/art1st/mineRails/core/util/Factory.kt`（已删除）
- `Core/src/main/java/work/art1st/mineRails/core/util/UuidGenerator.kt`（已删除）

**处理结果**
- 两者已从代码库移除。
- 若将来确实需要，再按真实调用场景重建，不保留空抽象。

---

## 12) `ThreadGuard` / Tick metrics（已开始落地，仍需继续）
**代码位置**
- `Core/src/main/java/work/art1st/mineRails/core/util/ThreadGuard.kt`
- `Core/src/main/java/work/art1st/mineRails/core/util/TickManagerImpl.kt`
- `BukkitPlugin/src/main/java/work/art1st/mineRails/bukkit/logic/WorldManager.kt`

**当前进展**
- `ThreadGuard` 已真正实现，不再是空壳。
- 已接入 `ThreadGuardPlatformBridge` / `AsyncTickThreadMarker`。
- `TickManagerImpl` 已能采集异步 tick 对象耗时与主线程 tick phase 耗时。
- Bukkit 关键边界已开始接入 `ensureMainThread(...)`，包括 world lifecycle、render chunk、rail/train render、seat tick、粒子/声音等入口。
- 已把一批明确的 heavy 根入口迁到异步：
  - `OperationWand` 创建路径点时的最近轨道查询
  - `TrainCommand` 的 `create` / `info` 最近轨道查询
  - `RailWand` 的轨道连接曲线生成、端点编辑重建、删除模式指向轨道选取
- 已给迁移完成的纯计算入口补 `ensureOffMainThread(...)`：
  - `CoreWorld.getNearestRail(...)`
  - `RailCurveFactory.build(...)`
  - `RailCurveFactory.buildParallel(...)`

**剩余工作**
- 继续排查并补齐剩余 Bukkit 边界上的主线程 guard。
- 继续盘点是否还有未迁移的 heavy 路径（尤其是其他编辑/查询工具链）。
- 视运行反馈决定是否扩大 `ensureOffMainThread(...)` 覆盖面。

---

## 13) `ConfigItemImpl.typedValue()` 未使用，`try/catch` 也多余（已处理）
**代码位置**
- `Basic/src/main/java/work/art1st/mineRails/basic/api/ui/config/ConfigItem.kt`

**处理结果**
- 未使用的 `typedValue()` 已删除。
- 该类当前只保留实际使用的 `setValue / commit / reset` 等核心行为。

---

# 建议的处理顺序

如果你要分批清理，我建议顺序是：

1. **先处理兜底**
   - `basicWorldData` 测试回退
   - `WorldPersistence` 损坏=缺失
   - `RailRenderMetaStore` 读坏再写坏
   - 配置/模型加载“静默默认”和“跳过坏项”

2. **再收缩抽象**
   - `ConfigUI` 缩口
   - `PackageLoader.load(): Any?`
   - `getExtensionData` API 语义化
   - `BogieExtension` / bogie 渲染归属

3. **最后删死代码**
   - `Factory`
   - `UuidGenerator`
   - `ensureMainThread`
   - `typedValue`
