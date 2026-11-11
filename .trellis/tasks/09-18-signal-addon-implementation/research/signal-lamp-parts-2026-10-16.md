# Research: 四灯信号灯面拆分与独立亮度

- Query: 用户建议“信号机灯泡单独做一个模型，以便设置其亮度”；核对正式四灯包、公开模型 API、亮度语义和最小落地范围。
- Scope: internal；只读生产代码、配置、资源与测试，只写本研究；不实现、不提交、不部署、不远程、不运行大套测试。
- Date: 2026-10-16

## Findings

### 结论与三个不同边界

当前宿主已经支持该需求的核心能力。若需求是“外壳保持环境光，点亮的灯面单独设固定显示亮度”，最小实施可限定在正式四灯包 YAML、客户端模型资源、相关样例说明和聚焦回归，不需要新增宿主 API、不需要增加 JS 或逻辑 signal、不需要世界 schema 变化。

必须分清：

1. **独立客户端模型资源**：`assets/jr/models/block/*.json` 网格及 `assets/jr/items/*.json` 物品模型键。单纯把 JSON 拆文件后再合成为同一 ItemDisplay，仍只有一份实体亮度；不能靠 JSON element 的名称得到独立 brightness。
2. **同一设备的多个 render part**：一个 `jr_four_light:head` 的 `ModelDefinition` 内有外壳和灯面等多个 `ModelPart.Item`，由一个 handle、摆放、variant 完整状态管理；各 ItemDisplay 有自己的亮度。这是当前能力最直接的复用方式。
3. **独立逻辑 signal**：有自己的身份、区间/保护入口绑定和规则求值。灯面拆分不需要这种身份，也不需要新增 attached signal（那是另一台可独立摆放的附属设备）。

依据：公开 `ModelPart.java:8-35` 定义 Group/Item，`ModelTreeBuilder.kt:89-111` 为每个 Item 创建 visual、Group 仅 DummyModel，`SignalModelBinding.kt:205-211` 每台设备创建一个模型 handle、`:265-280` 把同一 signal 输出的 variant 转成该设备模型的完整 scenes。

### 正式四灯包当前未拆灯面

- `SimpleSignals/examples/jr-four-light/signal-packs/jr_four_light/model/head.yml:1-20`：`parts` 只有一个 `item_display`，包含灯头整体；动画 `lamp` 按 stop/caution/slow/proceed 切换四个整件 item-model。整个文件没有 brightness。
- 同文件 `:21-26`：完整 default/variant 均只有 `lamp` 一项；`:3-6` 的 offset.y=1.5 与 rotation.y=90 是现有灯头几何校正，拆分后需保留在共同父节点或对各 leaf 等价应用，不能无意移动/旋转灯面。
- `resourcepack/assets/jr/models/block/four_light_signal_stop.json:5-17` 是外壳，`:18,22,26,30` 是四个 pixel lens，`:19-21,23-25,27-29,31-33` 为遮檐；都在一个 elements 数组内。每个颜色版本均包含整套几何，独立 element 名字只是资源中的命名，不是宿主 render part。
- `SimpleSignals/examples/jr-four-light/README.md:10-18`：现有四状态红、黄、黄绿、绿；默认 stop 是包作者选择，不是宿主隐式信号语义。
- `.../resourcepack/assets/jr/models/block/four_light_signal_slow.json:18,30`：YG 使用第1灯面和第4灯面；stop `:22` 是第2灯面；caution 文件 `:26` 是第3灯面；proceed 文件 `:30` 是第4灯面。四个高度位置并不等价于四种独立逻辑信号。
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/presentation/JrFourLightDemoTest.kt:38-56` 明确断言一个顶层 ModelPart.Item 和四个旧整件资源键；`:89-99` 明确每个资源29元素、四灯面。因此改资源必须同步修改这些实际资产测试，不能留下 `.single() as ModelPart.Item` 的旧假设。
- `models/jr-four-light-26.1.2.zip` 的只读目录列表也仅有四个整件 item wrapper、四个整件 block JSON 和一张纹理；未修改、未解包写入用户 models 工作区。本研究的权威实现来源仍是 examples 分发目录，未比较 ZIP 内容同一性。

### 已有每部件亮度和完整场景复用

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/model/ItemAppearance.java:12,29-33`：每个 appearance 自带可空 Brightness(block,sky)，各分量0..15。
- `.../api/model/asset/ModelAssetYamlCompiler.kt:278-299`：现有公共 compiler 已接受基础 item 与 scene item 的 brightness，提供时 block 和 sky 都必填。
- 同 compiler `:101-119` 在准备时解析 parts/default/variants，并验证每个完整选择；`:150-180` 建立动画命中和冲突检查。无需信号插件解析新的亮度字段或第二套渲染索引。
- `.../api/model/ModelDefinition.java:13-28`：不同部件可同名动画广播，但同名动画的 scene 名集合必须完全一致。因此可以四个 lamp leaf 都声明 `lamp` 和同样四个 scenes，每个 scene 各自决定该 leaf 的 item 与亮度；现有 `{lamp: stop}` 等映射原样控制所有灯。
- 也可以按 `lamp1/lamp2/lamp3/lamp4` 设四个动画，由 YAML variant 映射成多个 on/off scene。JS 仍只输出一个 variant，拆分细节留在包里。两种方式择一即可，不要并行维护两套 JS。
- `.../render/model/DisplayModel.kt:66-84`：帧 appearance 缺失会回到基础外观；完整 item 外观变化会替换物品，并设置或清除 brightness。场景 item 不是增量补丁，若显式提供 item 必须重述 type/model/brightness 等完整所需字段，省略 brightness 会清除 override，不继承基础或上一个场景。
- `.../render/model/OrdinaryModel.kt:39-58`：相同 scenes 和 pose 直接返回；只替换发生变化的命中帧，层级更新仍遍历 parts。

### 亮度仅为显示光照 override，不是世界光源

- `.../render/backend/NativeDisplayRenderBackend.kt:80-86` 只给 Display 实体设置 `Display.Brightness(blockLight,skyLight)`，清除时设 null。
- `.../render/backend/EntityLibDisplayRenderBackend.kt:138-152` 只写 `AbstractDisplayMeta.setBrightnessOverride` 并排队 metadata；自然光用协议值 -1。
- 这条实现路径没有设置世界方块、光照引擎或 LIGHT 方块。它改变该显示部件如何被着色，不会照亮旁边轨道/地面。`{block:15,sky:0}` 或 `{block:15,sky:15}` 是包作者的显示光照选择，不是光源强度或颜色、光束或 bloom。若以后要求照亮环境，那是当前 API 之外的新需求。
- 熄灯建议保留暗色灯面并恢复自然光（scene item 省略 brightness），或作者明确选择固定 `(0,0)`；二者语义不同，不能用 `(0,0)` 代替“移除覆盖”。外壳与遮檐保持自然光即可。

### 两种最小装配方案与成本

**最少显示部件方案：静态外壳 + 一个活动灯面层。** 外壳资源保留外壳/遮檐及需要的暗灯面；活动层四个资源分别只含应点亮的灯面，YG资源包含两个点亮灯面。活动层每场景固定亮度；外壳无 override。这样外壳和灯面可独立明暗，保持现有 JS/variant 与一台逻辑 signal。需要避免暗灯面和亮层共面导致 Z-fighting；也可让活动层完整包含四个灯面，暗色镜片明确选用暗纹理，但这会使暗灯面也受到该层的统一光照。若严格要求暗灯面保持环境光，应优先静态暗面 + 明确深度分离的亮面覆盖，或采用下一方案。

**严格逐灯亮度方案：静态外壳 + 四个灯面 leaf。** 外壳不包含重复灯面，四个灯面各自设置亮/暗 appearance 与 brightness；每灯可在不同场景使用不同亮度。通用资源若可复用，可用同一个局部灯片网格经 leaf offset 放到四个位置，颜色仍由 scene item-model 区分；复用网格不减少显示 leaf 数量。无需把四个 leaf 暴露成四台可选信号设备。

目前 `ModelTreeBuilder.kt:95-111` 每个 Item leaf 生成一个 visual，还固定生成一个 AIR 根 visual。因此按当前构造计数：

- 当前整件灯头：1 Item leaf + 1 AIR 根 = 2 visuals。
- 外壳 + 活动层：2 Item leaves + 1 AIR 根 = 3 visuals，较现状每设备+1。
- 外壳 + 四灯：5 Item leaves + 1 AIR 根 = 6 visuals，较现状每设备+4。
- Dummy/Group 本身不增加 visual。Native 后端的 visual 是实际 ItemDisplay（`NativeDisplayRenderBackend.kt:17-25`）；EntityLib 走自己的 display 实体/metadata 路径，不能把上面的数都称为服务端真实 Bukkit 实体数。

额外成本主要是显示实体/packet object、玩家可见性跟踪、metadata、客户端绘制和状态切换时的 parts 遍历。没有额外 JS 求值或区间扫描：`SignalModelBinding.kt:269-271` 在同 scenes/pose 下不提交，`OrdinaryModel.kt:19-20,39-58` 避免不变状态操作、已有树更新是 O(parts)。不能据静态代码给出 TPS/FPS 容量保证，应在目标规模客户端验收中测量。若只要求灯面与外壳不同亮度，2-leaf方案更省显示数量；若明确每灯独立调亮度，5-leaf方案才是准确表达。

### JS、设备身份和宿主服务均可保持现有边界

- `SimpleSignals/examples/jr-four-light/signal-packs/jr_four_light/signal/block.yml:1-3` 继续指向同一 `jr_four_light:head` 和 `jr_four_light:block`。
- `.../logic/block.js:5-9,73-86` 只输出速度操作和 `stop/caution/slow/proceed`；不感知部件数量和 brightness。因此此次灯片拆分不需要改规则，更不应逐灯复制规则。
- `SignalModelBinding.kt:269-280` 将 variant 查表成 scenes 后一次提交整个设备模型；模型定义内部可以任意增加上述合法叶子。保持 one signal → one device model handle → many render parts。
- attached signal 是另一台摆放设备读取主 signal 输出（`.trellis/spec/simple-signals/backend/model-presentation.md:157-163`）；用它表示同一灯头里的灯泡会引入无必要的身份、持久化和生命周期，不是本需求的最小路径。

### 最小可审查实施与验证范围（建议，未实施）

1. 明确是“所有点亮灯面同一固定亮度”还是“逐灯独立亮度/后续调整”，据此选2或5 Item leaves；固定亮度值写进包，无需先设计实例样式 API。
2. 仅修改 `SimpleSignals/examples/jr-four-light/{signal-packs/jr_four_light/model/head.yml,resourcepack/assets/jr/{items,models/block}/*}`，必要时更新纹理；保持尺寸、Y rebasing、朝向、安装基底、左右预设和 default stop。共用 dummy 父节点保留现有 offset/rotation，子件避免重复校正。
3. JS/logic/signal type不变。更新 README 和针对真实分发资产的 `JrFourLightDemoTest`，验证完整亮灭组合、未点亮灯面/外壳的自然光、scene清除override、引用链、无重复几何/共面闪烁，以及当前AB/BA摆放几何。
4. 根据是否触及宿主代码选择聚焦模型编译/场景测试；只改包时不需要扩大成宿主重构。后续真实客户端验收昼/夜、远近、YG双灯、暗灯和遮檐、资源重载及规模成本；离线测试不替代画面验收。
5. 保持当前包 config-version 和世界 schema；模型内容变化不是新的独立持久化边界。不新增旧格式兼容路径、不改用户原始/untracked models文件。资源包 ZIP 是否作为最终交付物重建，需由主任务指定正式来源和目标，本研究未写任何 ZIP。

## Files Found

- `SimpleSignals/examples/jr-four-light/signal-packs/jr_four_light/model/head.yml`：正式单Item灯头与四variant。
- `SimpleSignals/examples/jr-four-light/resourcepack/assets/jr/{items,models/block}/four_light_signal_*.json`：四个整件资源及物品wrapper。
- `SimpleSignals/examples/jr-four-light/signal-packs/jr_four_light/logic/block.js`：唯一四灯规则；无需复制。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/presentation/SignalModelBinding.kt`：每设备handle、variant映射、变化提交。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/model/{ModelPart,ModelDefinition,ItemAppearance}.java`：不可变多部件/亮度公开值。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/model/asset/ModelAssetYamlCompiler.kt`：YAML与scene亮度解析、完整映射验证。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/ModelTreeBuilder.kt`：leaf/root visual计数来源。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/{OrdinaryModel,DisplayModel}.kt`：场景保持、去重、亮度复位。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/{NativeDisplayRenderBackend,EntityLibDisplayRenderBackend}.kt`：亮度只是显示metadata。
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/presentation/JrFourLightDemoTest.kt`：现有单leaf/整件资源回归需随装配更新。

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md`：主插件公共服务、插件边界、离线与现场验收区别。
- `.trellis/spec/simple-signals/backend/formal-four-light.md`：正式包规则、固定variant、保持config-version1。
- `.trellis/spec/simple-signals/backend/model-presentation.md`：一台设备的model/placement、附属设备不是部件。
- `.trellis/spec/simple-signals/backend/model-packages.md:299-352`：完整variants、dummy/item树、每item亮度、完整scene appearance。
- `.trellis/spec/bukkit-plugin/backend/model-presentation-api.md:148-239`：公开树、0..15/null亮度、广播、完整复位。
- `.trellis/spec/javarailways/shared/versioning.md` 与 `ai_tasks/persistence_schema_evolution_rules.md`：不把开发改动当升版依据。

## External References

未使用外部网页；结论来自当前本地源码和正式分发目录。当前分发 README `:51-52` 与 `JrFourLightDemoTest.kt:71-75` 声明/校验Minecraft1.21.11、资源格式75.0；本研究未运行该测试，未据此宣称当前ZIP或客户端已兼容。没有新增外部库或版本要求。

## Caveats / Not Found

- 本研究没有实现或运行测试，没有真实Paper/Folia、客户端画面或性能数据。
- 当前没有逐设备任意样式/亮度编辑UI合同（model-presentation.md:10-11）；若用户要求游戏内按实例调亮度，包内固定值不是完整交付，需要另行确认实例参数与编辑入口范围，但宿主已有appearance亮度原语。
- 未核实目标客户端shader、资源包发光贴图/bloom支持；亮度override本身不承诺这些效果。
- 未检查或修改用户原始模型工作区；ZIP只查看目录，未假定其与examples逐字节一致。
- 受research角色隔离约束，没有读取implement.jsonl/check.jsonl；相关spec按注入目录及目标代码直接发现。
