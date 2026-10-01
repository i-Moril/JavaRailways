# Research: 普通模型的最小公开值契约

- Query: 哪些部件、变换、物品和动画值可复用现有模型实现，又不泄漏列车、ConfigLib 或资源注册器？
- Scope: mixed；以 BukkitPlugin 模型源码为主，仅核对 JOML/Paper 官方文档；本批只提供待审查建议。
- Date: 2026-10-01
- 路径约定：下文 `B/` = `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/`；`T/` = 同模块 `src/test/java/work/art1st/javarailways/bukkit/`。

## Findings

### 文件与相关规范

- `B/config/model/PartRefEntry.kt`、`PartDefinition.kt`：引用、内联定义、局部变换和子树；现有定义还携带驾驶座/碰撞配置。
- `B/config/model/PartItemConfig.kt`、`PartScaleConfig.kt`、`PartAnimationConfig.kt`：物品资源、缩放、动画帧的标量字段。
- `B/config/model/ModelTreeBuilder.kt`：普通/车体/转向架入口及实际部件构造；`PartRegistry.kt`、`PartResolver.kt`：现有名称解析。
- `B/config/model/PartAnimationRuntime.kt`、`B/render/Animation.kt`：帧转换、场景采样和播放；没有名为 `PartModel` 的类，部件行为由 `AbstractModel` 承担。
- `B/render/model/{AbstractModel,ModelState,CompactTransform,CompositeModel,DisplayModel}.kt`：变换合成、单动画槽、场景广播和外观应用。
- `B/render/backend/{DisplayVisual,NativeDisplayRenderBackend,EntityLibDisplayRenderBackend}.kt`、`B/render/model/DisplaySmoothing.kt`：后端值边界和当前插值。
- 相关规范：`.trellis/spec/bukkit-plugin/backend/{index,model-presentation-api,interaction-render-persistence,lifecycle-and-threading}.md`；`.trellis/spec/simple-signals/backend/model-presentation.md`。
- 这些规范及任务 `design.md:237-247` 均将完整公开显示服务标为 TARGET；现有 `buildModel` 只属于内部普通构造入口。

### 已观察：变换单位和合成顺序

- 定义与引用都有 offset/rotation；两者分别逐轴相加，随后只调用一次 `rotateXYZ`，不是两个旋转四元数相乘。rotation 输入是度，先转弧度。offset 沿用 Minecraft 坐标尺度（1 block = 1 m），缩放无量纲。依据：`B/config/model/ModelTreeBuilder.kt:176-199`、`PartDefinition.kt:22-24`。
- JOML `rotateXYZ(x,y,z)` 相当于 `rotateX(x).rotateY(y).rotateZ(z)`：`q = qx*qy*qz`，作用到列向量时先 Z、再 Y、再 X。不能把它解释成 Bukkit yaw/pitch/roll 字段；源码明确处理 x/y/z 轴。
- 动画 overlay 先加入部件局部状态：`p = pBase + pFrame`，`q = qBase*qFrame`，`s = sBase ⊙ sFrame`；动画位移不会先被该部件自身旋转。依据：`B/render/model/ModelState.kt:31-49`。
- 子变换合到父变换：`pOut = pParent + rotate(qParent,pLocal)`，`qOut = qParent*qLocal`，`sOut = sParent ⊙ sLocal`。父 scale **不乘子 offset**，这不是一般矩阵 TRS 层级。依据：`B/render/model/CompactTransform.kt:46-67`；其“局部旋转左乘世界旋转”注释与实际乘法相反，应以代码为准。
- `CompositeModel.updateState` 将根旋转放入父相对变换，将世界位置放入无旋转的 root，root scale 丢失。最终节点位置可写为 `pWorld = pRoot + rotate(qRoot,pTree)`。依据：`B/render/model/CompositeModel.kt:43-51`。
- Display 的实际四字段是 `Transformation(translation, leftRotation=identity, scale, rightRotation=q)`；mounted 部件用根相对位移，否则实体 teleport 到世界位置、translation=0。不要把现有非均匀缩放宣称为任意通用矩阵变换。依据：`B/render/model/DisplayModel.kt:42-56`。
- 普通输入没有整体 scale；初始节点 scale 来自 `def.item?.scale`，缺省 `(1,1,1)`；动画帧 scale 缺省也为 `(1,1,1)`。依据：`B/config/model/ModelTreeBuilder.kt:195-199`、`PartAnimationRuntime.kt:105-106`。

### 已观察：部件与物品资源

- 当前 builder 分支有 ITEM_DISPLAY、DUMMY、INTERACT、SEAT、PLATFORM；普通入口把 ownerCar 传 null，但没有限制后三者。INTERACT 会创建 hitbox；SEAT 带 driver/control-end；PLATFORM 有玩家碰撞/承载行为，不能仅因“不传 Car”便称为纯显示。依据：`B/config/model/ModelTreeBuilder.kt:64-69,208-243`、`PartType.kt:9-14`、`B/render/model/InteractionModel.kt:49-55`、`PlatformModel.kt:27-40,55-81`。
- 物品值是 `type:String`、`itemModel:String?`、`customModelData:Int?`、`scale: xyz Double?`。初始 item 没有 brightness 字段；动画帧可覆盖亮度。依据：`B/config/model/PartItemConfig.kt:13-17`、`PartAnimationConfig.kt:23-58`。
- YAML item loader 要求 itemModel/CMD 至少一项；直接构造 `PartItemConfig` 则没有该校验，builder 可渲染明确指定的普通 material。material 以大写 `Material.valueOf` 解析；初始失败静默变 STONE，动画失败沿用当前 material。依据：`B/config/model/PartRegistry.kt:159-178`、`ModelTreeBuilder.kt:247-267`、`B/render/model/DisplayModel.kt:86-101`。
- 初始和动画都硬拼 `minecraft:<itemModel>`，并非按部件 namespace 解析；给完整 `other:path` 会变成非法的 `minecraft:other:path`。实际组件指向资源包 `assets/<namespace>/items/<path>.json`，不是服务端文件查找。依据：`B/config/model/ModelTreeBuilder.kt:256-264`、`B/render/model/DisplayModel.kt:92-99`。
- itemModel 和 CMD 可以同时写入，源码没有“二选一 fallback”；CMD 当前以 `Int.toFloat()` 写入组件，较大整数可能失真。服务器只能校验本地值，不能确认客户端已安装对应资源包。
- 部件引用 namespace 是 slash 路径：裸名变 `<defaultNamespace>/<name>`；有 slash 先按完整本地键查找，找不到才把首段当 package 转向 fallback registry。递归子引用用被解析定义的 namespace；内联用自己的定义 namespace。依据：`B/config/model/PartRegistry.kt:77-102`、`PartDefinition.kt:59-64`、`ModelTreeBuilder.kt:161-173,202`。
- `DisplayVisualContent.Block` 两后端都支持，但当前 PartType/builder 没有 block display 部件；不能把后端能力当作现有普通部件支持。依据：`B/render/backend/DisplayVisual.kt:20-23`、`B/config/model/ModelTreeBuilder.kt:208-244`。

### 已观察：动画字段、顺序、播放和场景

- 动画字段：`name:String`、`keepFinalState:Boolean=true`、commands 或 keyframes。command `duration:Double` 是相对前帧的秒数；keyframe `at:Double` 是从时间线起点算的绝对秒数。依据：`B/config/model/PartAnimationConfig.kt:12-48`。
- 每帧属性：scene 可空 String；offset xyz Double，rotation xyz Double（度）；scale xyz Double 可空；itemType/itemModel 可空 String；CMD 可空 Int；brightness 可空、block/sky 各 Int（缺省0）。两后端最终要求亮度在 `0..15`。依据：同文件、`B/render/backend/DisplayVisual.kt:42-46`。
- keyframes 非空时优先于 commands；keyframes 按 at 升序，转成非负 delta；commands 保留输入顺序。runtime 在 t=0 前置 identity 帧，再累加 `max(0,deltaT)`；同一时刻采样取最后帧，重复 scene 的 runtime map 也取后者。依据：`B/config/model/PartAnimationRuntime.kt:15-48`、`B/render/Animation.kt:41-54,153-162`。
- 没有 easing 参数或类型，也没有 loop 参数或类型。位移/scale 固定线性 lerp，旋转固定 quaternion slerp；播放一次，到末帧后 commit/destroy。tick 输入毫秒，内部除1000成为秒。依据：`B/render/model/CompactTransform.kt:37-43`、`B/render/Animation.kt:207-228,233-265`。
- scene 只是命名时间点，**不是独立片段**。`setAnimationScene` 直接采样并持有，不注册动画 tick；`applyAnimation(name,scene)` 从已持有同名 scene 沿时间线前进/倒退，首次从0开始；仅有 heldTransform 时采用到目标的直线过渡，最短0.05秒。依据：`B/render/model/AbstractModel.kt:67-125`、`B/render/Animation.kt:75-115,120-145`。
- 动画 name 按原字符串精确查找。YAML scene trim/lowercase；runtime 查询再把 `o/c` 转为 `open/closed`，但 sceneTimes 建表没有这个转换。直接构造大小写 scene 或 YAML `scene:o` 会出现定义存在却查不到的情况。依据：`B/config/model/PartRegistry.kt:357-364`、`B/render/Animation.kt:53,73-77,286-292`。
- YAML 检查同一动画内重复 scene（commands+keyframes 一起检查），却未拒绝重复动画名；`toRuntimeAnimationMap().toMap()` 后者覆盖前者。直接标量对象也不保证 finite、非负时间或唯一名字。依据：`B/config/model/PartRegistry.kt:268-289`、`PartAnimationRuntime.kt:9-12`；YAML finite 检查见 `YamlLoading.kt:47-52`。
- 每部件只有一个 active/held 动画槽。同名动画向所有匹配部件递归广播；有名字但缺 scene 的部件静默跳过，返回 Boolean 只表示至少一项成功。不同部件可共同使用一个名字；同一部件选择多个不同名字则最后调用覆盖前者。依据：`B/render/model/AbstractModel.kt:19-23,48-117`、`CompositeModel.kt:61-82`。
- `collectHeldAnimationScenes` 把各部件压入同一 `name -> scene` map，同名不同场景后遍历者覆盖；这不是逐部件完整状态。依据：`B/render/model/AbstractModel.kt:93-100`。

### 已观察：复位缺口与插值

- `setAnimationScenes` 只遍历给出的条目；空 map 不清旧场景。`keepFinalState=false` 结束时清 held 值，但结束回调调用 updateState 时 active 仍存在，仍采样末帧，直到以后更新才可能恢复基础 transform。依据：`B/render/model/CompositeModel.kt:75-82`、`AbstractModel.kt:140-154`。
- `DisplayModel.applyDisplayState(null)` 直接返回；后续帧只改 brightness 不复原 item，只改 item 不复原 brightness；同 material clone 保留旧 ITEM_MODEL/CMD，空字段也不能移除旧组件。因此不能用“清 held overlay”声称已完整恢复外观。依据：`B/render/model/DisplayModel.kt:71-101`。
- 当前 `DisplayVisual` 只有设置 block/sky 的方法，缺少取消亮度 override 的入口；自然光与 `(0,0)` 固定亮度不同。依据：`B/render/backend/DisplayVisual.kt:25-33`；Paper setter 接受 nullable Brightness。
- descriptor 自身插值缺省0；普通 builder 明确复用 train 常量：root/item 的 transformation interpolation、teleport duration 都是3 ticks。两后端 delay=0，ENTITYLIB 每次 setTransformation 重发 delay=0。依据：`B/render/backend/DisplayVisual.kt:11-17`、`B/config/model/ModelTreeBuilder.kt:270-300`、`B/render/model/DisplaySmoothing.kt:3-7`、`NativeDisplayRenderBackend.kt:39-46`、`EntityLibDisplayRenderBackend.kt:49-54,107-117`。

### TARGET 建议：只暴露已展开的普通值树（待用户审查）

- `ModelDefinition(roots: immutable List<ModelPart>)`；`ModelGroup(localPose, children, animations)` 与 `ModelItem(localPose, item, itemScale, animations)` 即可。group 复用 DUMMY；item 为终端叶子，复用 ITEM_DISPLAY。不在本批公开 hitbox、seat、platform 或 block display。
- `Pose` 只含有限 Double 位移 x/y/z（米）和单位四元数 x/y/z/w；缺省零位移、identity。预展开阶段合并旧引用/定义的度数，再转四元数一次；公开入口不接 Euler、namespace、import、PartResolver 或 registry callback。
- `ItemValue` 含明确 material 名、可空完整 namespaced ITEM_MODEL key、可空 `Integer` CMD、可空 brightness `(int block,int sky)`；允许作者有意选择普通 material。非法 material/key 拒绝，不能自动生成 STONE。itemModel 与 CMD 可同时存在；建议 CMD 非负且能精确表示为组件 float，拒绝静默舍入。
- `ItemScale` 是有限 Double xyz，缺省 `(1,1,1)`；主会话收敛提案允许各轴为0以隐藏物品，负值拒绝。缩放只属于物品叶子，group/frame group scale 固定1；不承诺 root 整体缩放或父缩放改变子位移。保留现有后端四字段顺序，非均匀缩放的可见效果仍需实际资源包验证。
- `ModelAnimation(name, frames)`、`ModelFrame(atSeconds:double, scene:String?, offset xyz, unit quaternion xyzw, itemScale xyz, visual:ItemValue?)`：只用绝对时间，frames非空、时间finite、非负并严格递增；主会话收敛提案要求第一帧为0。每帧是完整 transform overlay，不是增量命令；group 禁止物品/亮度与非1 scale。
- 最小定义不增加 easing/loop 字段：固定 linear position/scale + quaternion slerp、无循环；若以后公开播放，可独立审查 `keepFinalState`/播放命令。当前场景 submit 建议只定位并持有选中帧，不自动播放路径或引入安全 tick callback。
- 动画名、场景名均为非空白 opaque 字符串，精确匹配，不加入 o/c 等列车别名。一个部件内动画名唯一、一个动画内 scene 唯一；同名跨部件允许广播，但所有同名定义需有相同 scene 集合，避免静默部分应用。时间/变换/外观可各自不同。
- 提交 map 表示完整选择：未知 name/scene 拒绝；每个部件至多命中一个被选动画，多项命中拒绝，结果不依赖 map 顺序。未命中的部件恢复基础 pose/scale/item/natural brightness；相同选择不重启动画。
- 静态帧的 `visual=null` 表示基础 ItemValue；非空是完整外观值，其 model/CMD/brightness 空值表示明确移除/自然光，不继承上一次实体外观。配置作者若需要属性继承，可在传入公开定义前自行展开完整帧。
- 值对象只含 primitive/String/immutable list/map，逐层 defensive copy；不暴露 JOML/Bukkit mutable refs、ItemStack、World/Car 或 ConfigLib `Loads`。现有 `B/render/config/AnimationConfig.kt:3-8` 依赖 ConfigLib/Renderable，不能成为公开定义。
- 参数建议：普通模型 transformation interpolation=0、teleport duration=0、delay=0，让静态场景立即切换；与现有 train=3 分开。先固定该默认值，不增加未有需求的公共插值配置字段。

### 实现前所需最小内部修正（本研究不实施）

- 在现有 builder 中增加直接消费已展开节点的内部路径，复用部件/visual 构造；不另造公共解析器或让 renderer 遍历资源引用图。展开与引用错误属于附属插件加载阶段；host 校验纯值树，不做注册器回调。
- 用完整选择求出每部件唯一目标，缺选时清 active/held 名称、场景和 overlay，再真实更新基础状态；解决结束时 active 仍覆盖 reset 的顺序问题。
- DisplayModel 保留基础物品与基础亮度，目标变化时由完整值重建 ItemStack 和取消/设置 brightness；两后端补足内部 clear-brightness 能力。不能只把 `lastDisplayState` 置 null。
- 初始/帧物品使用已验证完整 key；取消静默 material fallback，明确 CMD 的整数到float边界；将普通显示的插值参数传入现有 descriptor。
- 若以后复用 timed playback，修正 `Animation.kt:242-257` 提前读取下一帧 displayState 的行为：离散属性应在到达该帧时切换。当前静态 stateAtScene 不等于证明时间播放属性正确。

### 外部参考与验证证据

- 构建依赖：Paper `1.21.11-R0.1-SNAPSHOT`、JOML `1.10.8`、PacketEvents `2.12.1`、EntityLib `3.0.3-SNAPSHOT`（`BukkitPlugin/build.gradle:82-92`）。
- [JOML 官方 Quaterniond 文档](https://joml-ci.github.io/JOML/apidocs/org/joml/Quaterniond.html)：核对 rotateXYZ 与后乘语义；该在线页标为1.10.5，不是本项目1.10.8的完整版本验证。
- [Paper 1.21.11 Display 文档](https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Display.html)：teleport=0立即更新，范围0..59；brightness setter 可取消 override。ENTITYLIB 对应取消值/API 仍应在实施时核对。
- 现有证据：`T/config/model/ModelTreeBuilderTest.kt:22-63` 验证普通层级/位移/旋转/场景；`T/render/AnimationTest.kt:10-68` 验证秒/毫秒、倒放和零时长；`T/render/model/AbstractModelAnimationTest.kt:24-77` 验证递归命中和 held scene。这些测试没有覆盖完整外观复位、同部件多选冲突或实际两后端画面。

## Caveats / Not Found

- 本文件只记录当前源码和 TARGET 提议；未改运行时代码、规范、manifest，未运行 Gradle，也未验证 Folia 或客户端资源包效果。
- 当前内联 YAML 会把同一 offset/rotation 同时放进引用和定义，builder 再相加，可能翻倍（`PartRegistry.kt:246-259` + `ModelTreeBuilder.kt:176-186`）；旧 `PartDefinition.resolveTree` 不处理 inline，也没有循环检查（`PartDefinition.kt:30-55`）。公开已展开值树不应继承这两条路径。
- 扩展范围不包含脚本/schema/persistence/兼容方案或完整显示服务；全部候选字段和行为仍需用户审查，不能标为已冻结 CURRENT API。
