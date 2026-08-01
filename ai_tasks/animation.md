# 列车部件动画支持实施计划

## 目标与范围

本计划用于在 JavaRailways 中分步支持列车部件动画。参考来源：

- `/workspace/train/TrainCartResources/src/yml/s100/door.lib.yml`
- `/workspace/train/TrainCartResources/src/yml/s100/train.yml`

第一轮实现 **部件 Transform 动画 + scene 语义**：位置、旋转、缩放关键帧插值，按 scene 播放动画片段，以及列车/车厢触发入口。

暂不在第一轮实现：

- item model 切换
- brightness 切换
- 门灯闪烁
- 自动到站开关门
- TrainCarts 字符串动画语法完全兼容；但会实现等价的结构化 scene/segment 语义

这些放到后续阶段。

## 当前状态摘要

截至 2026-07-06，列车部件动画主线已经完成并收尾：

- [x] 结构化 part animation YAML 解析。
- [x] scene-aware transform 动画运行时。
- [x] ModelTreeBuilder 接入部件动画。
- [x] 安全递归触发链路。
- [x] `/jr train anim` 调试命令。
- [x] 已验证 S100 模型包，保存于 `models/s100/`。
- [x] YAML Actions：`actions.yml`、`/jr train action <name>`、延迟动画/声音。
- [x] Display state 动画：`item-type`、`item-model`、`custom-model-data`、`brightness`。
- [x] GraalJS Behavior：`behavior.js`、`action()`、`sound()`、`log()`、`/jr train behavior <method>`。
- [x] 渲染扩展持久化：保存/恢复 `carModelName`。
- [x] held scene 持久化：保存/恢复如 `doors_l -> open` 的语义状态。
- [x] `INTERACT` / `SEAT` / `PLATFORM` 分离。
- [x] `PLATFORM` 使用 chicken-mounted invisible shulker grid 做地板碰撞。
- [x] `train-model-update-policy`：`follow-package` 与全局 hash pool `snapshot`。
- [x] 教程文档：`docs/train_model_packages.md`。

剩余事项主要是实机验收和后续集成：

- [ ] 保存/重启后模型显示、held scene、动画命令、删除命令的完整实机回归。
- [ ] Actions 的声音、灯光、门动作联动实机回归。
- [ ] 将真实到站/时刻表/站台事件自动接入 `behavior.js` 的 `onStation(...)`。
- [ ] platform 碰撞在平直、曲线、连接段、坡道上的调试与微调。
- [ ] snapshot pool 后续清理策略与更完整的缺失快照降级/告警。

## 分步实施

### Phase 0：确认配置格式、scene 语义与兼容策略

目标：先定下 JavaRailways 自己的动画 YAML 格式和 scene 播放语义，避免后面反复改解析器。

建议格式：

```yml
config-version: 1
name: door_a
type: item_display
item:
  type: IRON_NUGGET
  item-model: "art1st/tc/s100/door_a"
animations:
  - name: doors_l
    keep-final-state: true
    keyframes:
      - at: 0.0
        scene: closed
        offset: { z: 0.0 }
      - at: 0.3
        offset: { z: 0.1 }
      - at: 1.3
        scene: open
        offset: { z: 0.625 }
```

也允许直接定义 segment 形式，便于简单动画：

```yml
animations:
  - name: door_left_open
    keep-final-state: true
    commands:
      - duration: 0.3
        offset: { z: 0.1 }
      - duration: 1.0
        offset: { z: 0.625 }
```

字段约定：

- `animations` 放在 part 顶层，适用于 `dummy`、`item_display`、`seat` 等所有 part。
- 兼容旧字段：如果 `item.animations` 存在，也解析并与顶层动画合并。
- `keyframes` 使用绝对时间 `at`，单位秒；适合带 scene 的完整时间轴。
- `commands` 使用相对时间 `duration`，单位秒；适合简单顺序动画。
- `scene` 是关键帧标签，例如 `closed` / `open`，也可支持短别名 `c` / `o`。
- `offset` 是相对部件原始 local position 的偏移。
- `rotation` 是相对部件原始 local rotation 的欧拉角，单位度。
- `scale` 是动画叠加缩放，默认 `{x: 1, y: 1, z: 1}`。
- `keep-final-state` 默认 `true`。

scene 播放语义：

- 一个动画可以是一条完整时间轴，例如 `doors_l` 同时包含 `closed` 与 `open` scene。
- 触发 `doors_l:open` 时，从当前保持姿态或上一个 scene 对应姿态播放到 `open` scene。
- 触发 `doors_l:closed` 时播放到 `closed` scene。
- 如果当前没有已知 scene，则可从时间轴第一个关键帧或当前 held transform 开始。
- 第一轮只需要支持“播放到目标 scene”；不要求完整兼容 TrainCarts 的 `doors_l[o]@1.0 1.3` 字符串语法。

完成标准：

- 配置格式写入本计划并在实现中保持一致。

---

### Phase 1：配置模型与解析器

目标：让 YAML 能被解析成结构化动画配置，包括 keyframe scene 标签，但暂不要求播放。

涉及文件：

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartAnimationConfig.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartDefinition.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartItemConfig.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartRegistry.kt`

任务：

- [x] 新增/改造 `PartAnimationConfig`：
  - `name: String`
  - `keepFinalState: Boolean = true`
  - `commands: List<PartAnimationCommandConfig>`，用于相对 duration 写法
  - `keyframes: List<PartAnimationKeyframeConfig>`，用于绝对 at + scene 写法
- [x] 新增 `PartAnimationCommandConfig`：
  - `duration: Double`
  - `offset: Vec3Config = Vec3Config()`
  - `rotation: Vec3Config = Vec3Config()`
  - `scale: PartScaleConfig? = null`
  - `scene: String? = null`，可选，兼容简单写法
- [x] 新增 `PartAnimationKeyframeConfig`：
  - `at: Double`
  - `scene: String? = null`
  - `offset: Vec3Config = Vec3Config()`
  - `rotation: Vec3Config = Vec3Config()`
  - `scale: PartScaleConfig? = null`
- [x] `PartDefinition` 增加 `animations: List<PartAnimationConfig>`。
- [x] `PartRegistry.parsePartDefinition()` 解析顶层 `animations`。
- [x] `PartRegistry.parseItemConfig()` 暂时继续兼容 `item.animations`。
- [x] `PartRegistry.parseAnimations()` 改为解析结构化 map，不再依赖 `List<String>`。
- [x] 校验同一个 animation 内 scene 名不要重复；允许别名映射 `o -> open`、`c -> closed`。
- [x] 校验 `keyframes` 按 `at` 升序，或解析后排序。

验证：

- [x] 新增 `PartRegistryAnimationTest` 覆盖顶层 keyframes、item-level commands、重复 scene 校验。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 插入一份包含 `animations` 的 part YAML 后，插件加载不报错。
- [ ] 不含 `animations` 的旧 part YAML 仍可加载。

---

### Phase 2：运行时 Animation 修正与 scene 播放

目标：让 `Animation` 能可靠播放 transform keyframe，并支持播放到目标 scene。

涉及文件：

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/Animation.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/CompactTransform.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/ModelState.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/AbstractModel.kt`

任务：

- [x] 统一时间单位：配置为秒；`TickManager.tick(deltaT)` 输入是毫秒，需要转换。
- [x] 支持 `duration == 0` 的瞬时关键帧，避免除零。
- [x] 修正跨多个 command 的 tick 消费逻辑。
- [x] `CompactTransform.lerp()` 支持 scale 插值。
- [x] 动画结束时，如果 `keepFinalState = true`，保存最终 transform。
- [x] 动画结束时记录当前 scene，例如 `open` / `closed`。
- [x] `ModelState.applyRotation()` 支持使用 active transform 或 held final transform。
- [x] 触发新动画时，正确 unregister 旧 active animation，避免 tick object 泄漏。
- [x] 支持 `activateToScene(sceneName)` 或等价 API。

建议语义：

- `activeAnimationTransform`：正在播放时每 tick 更新。
- `heldAnimationTransform`：动画结束后的保持姿态。
- `heldScene`：当前保持姿态对应的 scene，可能为空。
- 新动画开始时可覆盖旧 active animation。
- 播放到目标 scene 时：
  - 如果 `heldScene` 在同一条时间轴中存在，则从该 scene 对应关键帧时间播放到目标 scene 时间。
  - 如果 `heldScene` 不存在，则从当前 held transform 或第一个关键帧插值到目标 scene。
  - 第一轮可先要求 scene 播放路径在时间轴上前后都能工作；即 `closed -> open` 和 `open -> closed` 都可播放。

验证：

- [x] 新增 `AnimationTest` 覆盖毫秒 tick、scale 插值、scene 正/反向播放、零时长动画。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 单个 DisplayModel 播放动画后能停在最终位置。
- [ ] `doors_l:open` 播放到 open scene 并记录 heldScene。
- [ ] `doors_l:closed` 能从 open scene 播放回 closed scene。
- [ ] 重复触发动画不会产生多个仍在 tick 的旧动画。

---

### Phase 3：ModelTreeBuilder 接入动画配置

目标：模型树构建时把配置动画编译成 `Map<String, Animation>` 并传入模型。

涉及文件：

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/ModelTreeBuilder.kt`

任务：

- [x] 添加 `PartAnimationConfig.toAnimation()` 或等价 helper，保留 scene 索引。
- [x] 在 `buildPart()` 中合并动画来源：
  - `def.animations`
  - `def.item?.animations`，作为兼容旧字段
- [x] `DisplayModel` 构造时传入实际 animation map。
- [x] `DummyModel` 构造时也传入实际 animation map。
- [x] `PlatformModel` 构造时也传入实际 animation map。
- [x] `buildBogieGroup()` 后续如需支持 bogie group 动画，预留入口；第一轮可保持 empty。

验证：

- [x] 新增 `PartAnimationRuntimeTest` 覆盖 keyframes/commands 到运行时 Animation 的编译。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 配置在 part 顶层的动画能被对应模型发现。
- [ ] 配置了 scene 的动画能通过动画名和目标 scene 找到。
- [ ] 无动画配置的模型保持现状。

---

### Phase 4：触发链路与安全递归

目标：从列车/车厢层触发动画时，只让有对应动画的部件响应，没动画的部件静默跳过。

涉及文件：

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/AbstractModel.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/CompositeModel.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainComponentExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/CarExtension.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainExtension.kt`

任务：

- [x] `AbstractModel.applyAnimation(name)` 改为返回 `Boolean`。
- [x] 当前模型无该动画时不抛异常，继续递归 children。
- [x] 当前模型有该动画时启动播放，并返回 true。
- [x] `CompositeModel.applyAnimation(name)` 返回是否至少一个模型响应。
- [x] `TrainComponentExtension.applyAnimation(name)` 改为 open，并返回 `Boolean`。
- [x] `CarExtension.applyAnimation(name)` 分发到：
  - `bodyModel`
  - `bogieModels`
- [x] `TrainExtension.applyAnimation(name)` 聚合所有车厢返回值；可返回响应数量或 Boolean。

验证：

- [x] 新增 `AbstractModelAnimationTest` 覆盖缺失动画/scene 跳过、递归触发、结束姿态保持。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [x] 调用不存在的动画名不会抛异常。
- [x] 调用存在的动画名，只有配置了该动画的部件播放。

---

### Phase 5：调试命令

目标：提供最小手动验证入口。

涉及文件：

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/TrainCommand.kt`
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/JavaRailwaysCommand.kt`，如需 help/tab 补充

新增命令：

```text
/jr train anim <animationName>
/jr train anim <animationName> <sceneName>
```

也可支持简写：

```text
/jr train anim <animationName>:<sceneName>
```

行为：

- [x] 优先使用当前玩家控制器连接的列车。
- [x] 如果没有控制器连接，则找玩家附近最近列车。
- [x] 无 scene 参数时调用 `train.renderer.applyAnimation(animationName)`。
- [x] 有 scene 参数时调用 `train.renderer.applyAnimation(animationName, sceneName)` 或等价接口。
- [x] 向玩家反馈是否有部件响应。

验证：

- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] `/jr train anim missing_name` 不报错，并提示没有部件响应。
- [ ] `/jr train anim doors_l open` 或 `/jr train anim doors_l:open` 能触发配置了该 scene 的部件。
- [ ] `/jr train anim doors_l closed` 能播放回关闭状态。

---

### Phase 6：S100 门动画配置样例

目标：给现有 S100 资源补最小可测动画。

涉及目录：

- `BukkitPlugin/run/plugins/JavaRailways/trains/s100/parts/s100/`

候选文件：

- `door_a.yml`
- `door_b.yml`
- 如需按左右侧拆分，也可新增更细粒度 part。

参考 TrainCartResources：

- 左门组：`doors_l`
- 右门组：`doors_r`
- 门页打开位移约 `0.625`
- 中间过渡位移约 `0.1`

任务：

- [x] 给门部件添加带 scene 的动画：
  - `doors_l`，包含 `closed` 与 `open` scene。
  - `doors_r`，包含 `closed` 与 `open` scene。
- [ ] 如需兼容无 scene 命令，也可额外提供：
  - `door_left_open`
  - `door_left_close`
  - `door_right_open`
  - `door_right_close`
- [x] 确认左右门位移方向正确。
- [x] 确认车体翻转或 `rotation: {y: 180}` 时局部坐标方向符合预期。
- [x] 将已验证的 S100 模型包保存到 `models/s100/`。

验证：

- [x] 创建 S100。
- [x] 执行 `/jr train anim doors_l open`。
- [x] 左侧门打开并保持，held scene 为 `open`。
- [x] 执行 `/jr train anim doors_l closed`。
- [x] 左侧门关闭，held scene 为 `closed`。
- [x] 右侧门同理。

---

## 后续阶段：Actions、Behavior、灯光与持久化

### Phase 7：YAML Actions

目标：用声明式 YAML 定义列车业务动作，不直接暴露底层部件动画名。

`actions.yml` 负责描述“一个动作会触发哪些资源效果”，包括 transform 动画、声音，后续也可包含灯光和 item/brightness 切换。

建议新增 `actions.yml`：

```yml
actions:
  door-left-open:
    animations:
      - name: doors_l
        scene: open
    sounds:
      - key: minecraft:block.iron_door.open
        volume: 0.8
        pitch: 1.0

  door-left-close:
    animations:
      - name: doors_l
        scene: closed
    sounds:
      - key: minecraft:block.iron_door.close
        volume: 0.8
        pitch: 1.0

  door-right-open:
    animations:
      - name: doors_r
        scene: open
    sounds:
      - key: minecraft:block.iron_door.open
        volume: 0.8
        pitch: 1.0

  door-right-close:
    animations:
      - name: doors_r
        scene: closed
    sounds:
      - key: minecraft:block.iron_door.close
        volume: 0.8
        pitch: 1.0
```

任务：

- [x] 定义 `TrainActionConfig`、`TrainActionAnimationConfig`、`TrainActionSoundConfig`。
- [x] 在 package loader 中加载 `actions.yml`。
- [x] 将 actions 绑定到 `LoadedTrainPackage` / `TrainDefinition` 可查询结构。
- [x] 添加 `train.renderer.applyAction("door-left-open")` 或等价接口。
- [x] action 执行时支持调用已有 `applyAnimation(name, scene)`。
- [x] action 执行时支持在列车/车厢位置播放 sound。
- [x] action 动画/声音条目支持 `at` 延迟播放。
- [x] 命令支持 `/jr train action <name>`。
- [x] 命令反馈 action 是否存在、哪些子效果被触发。

验证：

- [x] 新增 `TrainActionConfigTest` 覆盖 actions.yml 解析。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] `/jr train action door-left-open` 等价触发 `doors_l open`。
- [ ] `/jr train action door-left-close` 等价触发 `doors_l closed`。
- [ ] 执行动作时能听到配置的声音。
- [ ] 不存在的 action 有清晰提示，不抛异常。

### Phase 8：Item / Brightness 动画

目标：支持门灯警示闪烁，以及 action 中更丰富的显示状态切换。

需要扩展动画/action command：

```yml
commands:
  - duration: 0.6
    item-model: "art1st/tc/s100/left_door_light_warning"
    brightness: { block: 5, sky: 0 }
  - duration: 0.4
    item-model: "art1st/tc/s100/left_door_light"
    brightness: { block: 0, sky: 0 }
```

涉及：

- [x] `DisplayModel` 支持更新 item stack。
- [x] `DisplayModel` 支持更新 brightness。
- [x] 动画 keyframe 未声明 `brightness` 时不会覆盖 Display brightness。
- [x] `Animation` 支持非 transform payload。
- [x] `actions.yml` 支持引用灯光/显示类动画。
- [x] S100 door actions 引用门灯闪烁动画。

验证：

- [x] 新增/扩展测试覆盖 display state 解析与运行时编译。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 门开关 action 能同时触发门移动和门灯闪烁。
- [ ] item model 切换后能恢复正常状态。
- [ ] brightness 切换不会影响其他 display 部件。

### Phase 9：JS Behavior

目标：用 JS 描述“什么时候做什么”，让每种列车可实现自己的行为逻辑，并向其他插件暴露可调用方法。

建议模型包结构：

```text
models/s100/
  manifest.yml
  actions.yml
  behavior.js
  cars/
  parts/
```

`behavior.js` 示例：

```js
function onStation(time, door_side) {
  if (time === 0) action(`door-${door_side}-open`)
  if (time >= 20) action(`door-${door_side}-close`)
}

function horn() {
  sound("minecraft:entity.minecart.riding", 1.0, 1.0)
}
```

职责划分：

- YAML Actions 描述“有什么动作/资源效果”。
- JS Behavior 描述“什么时候调用哪些动作”。

任务：

- [x] 在 train package 中加载可选 `behavior.js`。
- [x] 定义列车 behavior runtime，与具体 train instance 绑定。
- [x] 向 JS 暴露安全 API：`action(name)`、`sound(key, volume, pitch)`、`log(message)`。
- [x] 支持标准方法：`onStation(time, door_side)`。
- [x] 支持自定义方法，供命令或其他插件调用。
- [x] 命令支持 `/jr train behavior <method> [args...]` 用于调试。
- [ ] 后续把自动门控逻辑接入 `onStation(time, door_side)`。

验证：

- [x] S100 可通过 `behavior.js` 实现到站开/关门流程。
- [x] `onStation(0, "left")` 会调用 `door-left-open`。
- [x] `onStation(20, "left")` 会调用 `door-left-close`。
- [x] 其他插件可通过 `train.renderer.callBehavior(...)` 调用列车 behavior 方法。
- [x] 新增 `TrainBehaviorRuntimeTest` 覆盖 action/sound/log、自定义方法和缺失方法。
- [x] 新增 `PackageLoaderBehaviorTest` 覆盖 package 内 `behavior.js` 加载。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。

### Phase 10：列车渲染扩展持久化

目标：重启后恢复列车模型，而不是只恢复 Core 物理列车。

任务：

- [x] 定义 `SavedCarRenderState : SerializableExtensionData`。
- [x] `CarExtension.save()` 保存 `carModelName`。
- [x] `CarExtension.initialize()` 从 saved extension data 恢复 `carModelName`。
- [x] 定义/完善 `SavedTrainRenderState : SerializableExtensionData`，如需保存列车级渲染状态。
- [x] `TrainExtension.save()` 保存列车级渲染状态。
- [x] 世界加载后，为已恢复的 train/car 重新注册 Bukkit 的 `TrainExtension` / `CarExtension`。
- [ ] 确认恢复后的列车可见、可删除、可执行 `/jr train anim`。

验证：

- [x] 新增测试覆盖 render extension data 的 world snapshot 编解码。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 创建列车并保存/重启。
- [ ] 重启后模型自动显示。
- [ ] `/jr train delete` 能删除恢复后的列车模型。
- [ ] `/jr train anim doors_l open` 能作用于恢复后的列车。

### Phase 11：Animation scene 状态持久化

目标：持久化有状态动画的语义状态，例如门当前是 open 还是 closed。

建议只保存稳定语义状态，不保存运行时对象：

```kotlin
@Serializable
data class SavedCarRenderState(
    val carModelName: String,
    val heldScenes: Map<String, String> = emptyMap()
) : SerializableExtensionData()
```

任务：

- [x] 在模型/扩展层暴露 held animation scene 快照，如 `doors_l -> open`。
- [x] `CarExtension.save()` 将每节车厢的 held scene 写入 `SavedCarRenderState`。
- [x] `CarExtension.initialize()` 暂存恢复的 held scene。
- [x] `buildModel()` 完成后，把 held scene 重新应用到模型树。
- [x] 第一版恢复到目标 scene 即可；不保存 active animation 的 elapsed/progress。
- [ ] 后续如需精确恢复，再额外保存 `activeAnimationName`、`targetScene`、`elapsedSeconds`。

验证：

- [x] 新增测试覆盖 held scene 快照与即时恢复。
- [x] `bash ./gradlew :BukkitPlugin:test` 通过。
- [ ] 打开左门后保存/重启。
- [ ] 重启后左门仍保持 open scene。
- [ ] 执行 `/jr train anim doors_l closed` 能从 open scene 关闭。

---

## 第一轮最终验收标准

完成 Phase 1-6 后应满足：

- [x] 插件能加载带 `animations`、`keyframes`、`scene` 的 part YAML。
- [x] `/jr train anim doors_l open` 能让已渲染列车的左门部件移动到 open scene。
- [x] 动画结束后门保持打开状态，并记录 held scene。
- [x] `/jr train anim doors_l closed` 能从 open scene 播放回 closed scene。
- [x] 右门 `doors_r open/closed` 同理。
- [x] 没有对应动画或 scene 的部件不会抛异常。
- [x] 重复触发动画不会导致 tick object 泄漏。
- [x] 列车移动时，正在播放动画的部件仍跟随车体姿态正确更新。

## 建议提交顺序

1. 配置数据类与解析器。
2. Animation 运行时修正。
3. ModelTreeBuilder 接入动画 map。
4. 递归触发链路改造。
5. `/jr train anim` 调试命令。
6. S100 门动画样例配置。
