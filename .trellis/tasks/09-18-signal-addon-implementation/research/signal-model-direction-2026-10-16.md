# Research: 正式四灯信号模型方向反馈

- Query: 游戏反馈“信号机的模型方向是反的”；区分正式模型适配、通用姿态计算、rail/ground 放置语义及现场资产差异。
- Scope: internal；仅静态读取正式包、计算/显示源码、测试、任务和规范；未修改产品、用户 models 工作区、服务器或客户端，未运行测试。
- Date: 2026-10-16

## Findings

### 可确认结论

已找到能解释反向的具体遗漏：本机现成 Minecraft **26.1.2** 客户端 JAR 中，`DisplayRenderer$ItemDisplayRenderer.submitInner` 在物品绘制前无条件执行 **Y +π**。正式四灯离线测试漏了它。实际复合应为 `q(root) × q(head) × Ry(π) × sourceNormal`；正式灯面 source -Z 会先变 +Z，再经 head Y+90° 变为 **模型 +X**，与预期面向来车的 -X 相反。左右预设只有侧移；没有发现通用轨道基础轴计算错误。最小候选 owner 是正式四灯部件适配（Y+90 改 Y-90），而不是全局 yaw+180。

特别需要排查“右键摆放时玩家从相反方向看轨道”：逻辑入口左键绑定和模型右键摆放使用各自的视线。右键得到的 placement facing 不自动采用先前绑定的逻辑入口方向。这能产生“逻辑方向正确，实体朝反向”的单设备结果，而且不代表 quaternion 算法错误。

这是已核对客户端字节码的方向推导，仍不是当前玩家实际版本/实装资源证明。README 声明资源目标 1.21.11，而此处现成可验证客户端为26.1.2；需确认玩家版本及服务端包是否与仓库一致。无需为了继续讨论而要求全量现场日志。

### 追加的客户端直接证据

- 现成只读文件 `/tmp/javarailways-mojang-client-26.1.2.jar`，内嵌 `version.json` 明确 id/name=26.1.2、protocol=775、resource_major=84；SHA256=`b1b3158572666445eff01e82fad8c7de2e4953db6d354f311730d77a8359d0b0`。本轮未下载/写入该文件。
- 命令 `javap -c -p -classpath /tmp/javarailways-mojang-client-26.1.2.jar 'net.minecraft.client.renderer.entity.DisplayRenderer$ItemDisplayRenderer'`：`submitInner` offsets 11/12 取 PoseStack、Axis.YP，15 装载 3.1415927f，17 调 Axis.rotation，22 调 PoseStack.mulPose，25-40 随后调用 ItemStackRenderState.submit。此前仅空物品返回，无 NONE/FIXED 条件分支。
- 外层 `DisplayRenderer.submit` offsets 38-53 先乘 entity orientation，73-76 乘 display Transformation，79-89 才 submitInner，因此上述 Yπ 在部件 display transformation **之后**，顺序不能猜成绕世界 Y。
- `ItemStackRenderState$LayerRenderState.applyTransform` offsets 0-15 调 itemTransform.apply，18-23 乘 localTransform；`ItemTransforms.getTransform` default offset123 返回 NO_TRANSFORM。其 synthetic switch 明列第三/第一人称、HEAD、GUI、GROUND、FIXED、ON_SHELF，**不列 NONE**，所以 NONE 不从 fixed 继承，也不应用 GUI 角度。
- 正式四个 `four_light_signal_*.json:35` 仅有 `display.gui.rotation=[20,145,0]`；没有 `parent`、`display.fixed` 或其他视角覆盖。现代 items JSON（如 `items/four_light_signal_stop.json:2-4`）只是 `minecraft:model` 直接引用 block model，没有额外 transform。
- `BukkitPlugin/build.gradle:47` 的 editor 资源目标也是26.1.2，但 `:95-96` 编译 API/dev bundle 仍1.21.11；不能将编译依赖版本当玩家客户端版本。

### 文件与模式证据

- `SimpleSignals/examples/jr-four-light/signal-packs/jr_four_light/model/head.yml:3-6`：部件 offset.y=1.5，注释明确 source lamp -Z，rotation.y=90 指向来车 -X。
- 同文件 `:27-33`：follow-rail-tilt=false；left z=-2、right z=+2；没有预设旋转。
- `SimpleSignals/examples/jr-four-light/resourcepack/assets/jr/models/block/four_light_signal_stop.json:18-21`：灯片 from=(4,14,7)、to=(12,22,8)，仅 north 面；遮檐延伸至 z=3。灯背主体 z=8..10（`:5`）。因此 source 的正面确实是 -Z，不是仅凭名字推断。
- `SimpleSignals/examples/jr-four-light/README.md:54-71`：正式适配说明、NONE 显示上下文、模型中心(8,8,8)、-Z→-X 和 AB/BA 语义，明确尚未客户端验收。`:75-88` 说明服务器 signal-packs 和客户端资源包是两份独立交付，当前没有包热重载。
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/presentation/ModelPlacementCalculator.kt:28-54`：按 canonical AB 弧长取一点；BA 反切线；前×上得到右；列矩阵为 forward/up/right；最终 q=basis×adjustment。`:44-48` 偏移先在轨道参照下求值，不受模型自身旋转影响。`:13` ground 原样返回绝对 pose。
- `.../presentation/ModelPlacement.kt:9-26`：预设仅一次复制为设备自身 adjustment/tilt；以后改包预设不会追溯影响已有设备。`:30-31` ground 不使用轨道偏移与倾斜。
- `.../authoring/SignalWandHandler.kt:489-493,590-612`：rail 的 dot=轨道切线·本次玩家视线，dot>=0 选 AB，否则 BA；abs(dot)<0.15 拒绝。BIND 与 PLACE 都用本次 point，PLACE 不取已绑定入口方向。
- 同文件 `:900-926`：ground 根在点击方块面中心，yaw 的 forward=(-sin(yaw),0,cos(yaw))；local +X 对齐玩家水平朝向，local +Z 为右；点击的 face 只决定位置，不决定朝向。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/model/asset/ModelAssetYamlCompiler.kt:317-324`：部件 rotation 为角度，JOML rotateXYZ；不是 Bukkit yaw。
- `.../render/model/OrdinaryModel.kt:39-54,77-79`：公共 pose 原值进入 CompactTransform；`CompositeModel.kt:45-60` 将根位置和旋转分开用于层级；`DisplayModel.kt:54-60` 将合成 rotation 写入 Transformation 的 rightRotation。此链路没有发现显式附加 Y 180°。
- `.../render/backend/NativeDisplayRenderBackend.kt:19-25` 与 `EntityLibDisplayRenderBackend.kt:30-33,65-69`：两 backend 均 NONE；把变换/四元数直接交给 display metadata。NONE 只选择无 item context transform，不取消客户端 ItemDisplayRenderer 固有 Yπ。
- `.../presentation/SignalModelBinding.kt:68-71,90-123`：rail/ground 共同进入同一 binding；主信号模型从 catalog 推导，附属和表示器使用显式 model。`:194,209` 模型对象更换会替换相应 handle。因此模型部件修正的影响不止新放设备。

### 具体可复现的坐标推导

以下对比现有离线预期和包含26.1.2客户端Yπ后的实际推导：

1. 平直轨道 A=(10,20,30)、B=(20,20,30)，AB 切线 +world X，弧长 5，identity adjustment：根在(15,20,30)，离线预期灯面 -world X；客户端 Yπ 后为 **+world X**。列车从 x<15 往 +X 行驶需要看到 -X 正面。right 预设根在(15,20,32)。
2. 相同弧长改 BA：根仍在 canonical 中点，forward=-world X，right=-world Z；离线预期正面 +world X，客户端推导 **-world X**；right 预设根在(15,20,28)。同时改变 facing 会换边，不能把它当作“原地翻面”修复。
3. 上述轨道逻辑入口 AB 已绑定，但右键时视线为 -world X：PLACE 计算 BA。假设先修正包适配，灯面将为 +world X，与 AB 来车需要的 -world X 相反；在当前未修正的26.1.2链路里，两个相反因素可能恰好抵消。这是不能仅凭单张最终画面推断 placement facing 的原因。
4. ground 点击方块(10,63,-4)的 EAST 面，玩家 yaw=0：根=(11,63.5,-3.5)，+X 对齐 world +Z；离线预期四灯正面 -world Z，客户端推导 **+world Z**。方块 EAST 面本身不会使灯面朝东。因此同一个包适配遗漏能同时解释 rail 与 ground 反向，无需两套补偿算法。

### 测试覆盖及空白

- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/presentation/JrFourLightDemoTest.kt:114-137`：直接加载正式 head.yml，以真实 PrimeCurve 检查左右预设、AB/BA，计算 q(root)×q(head)×(0,0,-1)；期望正面朝 -direction，检查基底高度和完整尺寸。它证明离线约定，没有启动 renderer/client。
- `.../ModelPlacementCalculatorTest.kt:25-37`：定向偏移和 AB/BA 基础轴；`:41-84` 任意朝向、坡度/超高和局部旋转；`:154-156` ground 原样返回。
- `.../authoring/SignalWandHandlerTest.kt:602-620`：ground face centre、yaw=0 的 quaternion 和不查轨道；`:623-638` 验证实际 block event 捕获 yaw。但不是正式灯头在 ground 的端到端画面测试。
- `.../signalpack/JrFourLightSignalPackTest.kt` 主要验证 catalog、JS 与灯色，不提供客户端方向证据。正式规范 `.trellis/spec/simple-signals/backend/formal-four-light.md:5,48-57` 同样区分离线与现场验收。

### 最小诊断输入

对一个反向设备取得：设备 ID/type/model，保存的 placement（kind、railId/arc/facing/adjustment 或 ground pose），该 rail 的 A/B 世界位置或该点切线，逻辑入口行车方向，以及截图中来车和灯面的可辨方向。再确认服务器实际安装 head.yml 是否仍为 rotation.y=90、客户端是否启用匹配 JR 包/是否被更高优先级包覆盖、插件与 Minecraft 版本/backend。可先只取一个设备，不扫描或修改全服。

现场最小复现：在同一平直轨道用当前正式包新建 AB/right 和 BA/right 各一台，记录放置视线；另以 yaw=0 ground 放一台。若仅个别旧设备反向，先比实例旋转/facing；若 rail 与 ground 的所有新设备都相反，再查实际 item 渲染与包适配。对比旧/新设备应读取值，不通过猜测重放覆盖原设备。

### 可选最小修复 owner 和影响

- 若只是一台实例方向/旋转错误：owner 为该设备 placement，经既有 Wand 数值编辑或精确 revision 的 manager patch 原地修旋转；不改 model、JS、逻辑绑定或通用计算。原地旋转不会移动 rail 偏移；ground 使用绝对世界旋转，不能把 rail 局部角直接原样套过去。数值编辑合同当前不允许直接改 facing。
- 基于当前客户端证据，首选最小候选为正式 `model/head.yml:6` 的 Y+90 改 **Y-90**（等价270）：`Ry(-90)×Ry(180)×(-Z)=-X`。这是四灯资产适配，不更改通用计算器或 NONE context。只改此模型适配可同时覆盖 rail、ground、主/附属/表示器中引用 `jr_four_light:head` 的设备，不影响其他模型；现有实例自身旋转仍参与叠加，已人工补偿的实例会再次被翻转。需同步部件注释、README 和正式方向测试，在测试中明确客户端 Yπ，而非继续只算 root×head。修改包后须重启/重新加载既有包加载生命周期，文件不会热生效。人工补偿数据如需保留则另做有恢复点的一次性修正，不新增 runtime compatibility path。
- 若只有 ground 的“应面向方块外侧”期待与现行为不符：owner 为 Wand ground authoring 语义/交互，先明确预期；目前合同是按玩家 yaw，而非 face normal。修改它影响所有模型的新 ground 放置，既有 ground pose 已固化不会自动改变。
- 若希望 rail 摆放强制沿逻辑入口：owner 为 Signal Wand 作者流程；先确认是否放弃当前“逻辑入口与显示附着位置/方向独立”的能力。不能在通用计算器暗中覆盖 facing，否则附属预告、独立表示器和其他模型一起受影响。
- 只有证实基础轴/四元数组装错误才改 `ModelPlacementCalculator`。该 owner 影响所有 rail 模型和已有 rail placement 的重新准备；若改 forward/right，左右偏移也可能倒换。全局 yaw+180 不具备当前证据支撑。

### 相关规范和任务约束

已读取 workflow、当前 PRD 的游戏建造/模型范围及 design:369-410。模型显示规范 `model-presentation.md` 的两种摆放/实例复制/已实现计算边界、`model-packages.md:322-330,354-360` 的轴与预设、`section-authoring-wands.md:76-78,144-156` 的 ground 和实例编辑是直接合同。研究 role 按隔离要求未读取 implement.jsonl/check.jsonl；直接读取相关 specs。

已读取 `javarailways/shared/versioning.md` 与 `ai_tasks/persistence_schema_evolution_rules.md`：本研究不提出 schema/PDC/config version 变更，不增加旧开发 reader/别名/运行时补偿。配置适配或已有设备修正与 schema 升版是独立问题。

## External References

未浏览或依赖新网络资料；客户端原始一手证据来自上述现成26.1.2 JAR 的 version.json 和 javap 字节码。README 引用的 Fabric 1.21.11 block-model 文档只用于既有 Y 范围说明，不作为客户端方向证明。未取得1.21.11客户端作同样复核。

## Caveats / Not Found

- 未获反向设备参数、截图、服务器实装 head.yml 或实际客户端包优先级；已定位明确的26.1.2渲染遗漏，但尚不能排除该现场设备另有实例方向/旧包差异。
- 没有读取或修改 untracked `models/` 用户原始工作区；正式交付包是本研究资产来源。
- 本轮只读源码与现有测试，没有运行测试、构建、客户端或远程操作；不报告测试通过或已修复。
- 先前记忆只帮助定位 authoring owner，以上行为全部复查当前源码；内存记录中的历史门禁不用于现场方向结论。
