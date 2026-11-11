# Research: Core Seat 与 Bukkit 座椅的产品边界评估

- Query: 当前座椅应使用 Core Seat 还是 Bukkit SeatModel？两者是否同一概念，平台无关座位定义是否存在真实消费者，保留 Core 时最小职责是什么？
- Scope: internal；定向静态审查 Core train/render/API、Bukkit 模型配置/乘坐/移动/离座/模型与世界生命周期、JS 和隔离模拟消费者。不负责 Core 事件契约或存档迁移。
- Date: 2026-10-10
- Baseline: `dev/signal-system` / `7ff3a8d3`；本轮源码未改动。
- Authority: 只评估与提出建议，未批准具体 Seat API 改动；仅写本研究文件，不改代码、规格或其他任务文件，不构建、不测试、不执行 Git 操作。
- Context: 按研究角色隔离规则未读取 implement.jsonl/check.jsonl；已读取任务 prd.md → design.md → implement.md，以及 .trellis/workflow.md 和下面列出的相关规范。用户已确认没有仓库外插件，此事实来自主会话转述，不由源码搜索证明。

## Findings

### 推荐与产品判断

现有游戏乘坐能力继续由 Bukkit SeatModel 负责最合理。Core 当前 Seat 只是未接入生产的车厢内几何位置接口，既不能替代 Bukkit 实体/passenger/交互，也没有被 Bukkit 当作座位定义来源。两者有“车内一个可坐位置”的概念交集，但当前不是同一个模型的两层实现。不能把整个 SeatModel 搬到 Core，也不应让 Core 和 Bukkit 分别维护座位占用状态。

共享的平台无关“座位目录/定义”在概念上成立：它可在没有玩家、没有模型实体时回答某车有哪些座位、局部位置和朝向。但本次没有找到模拟、JS、Basic、SimpleATO、其他仓库内插件对它的当前需求。结合用户已确认无仓库外插件，按现有产品范围推荐继续以 Bukkit 模型 SEAT 为唯一座位定义来源和运行实现，并在用户检查认可后单独删除未接入的 Core Seat 原型；本次不执行删除，也不新增 seat catalog/service。该推荐依据职责与实际布局能力，不只依据无调用。若用户另行确认平台无关座位需求，再采用下述最小抽象方向。

如果决定保留，建议最小职责仅为不可变座位身份与相对几何定义，必要时提供基于一次捕获的车厢帧进行纯计算的姿态投影。Bukkit 仍唯一拥有实体、passenger、乘坐结果、驾驶授权、近距离激活和清理。下述具体字段只是评估方向，不是已批准签名。

### 文件定位

- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/Seat.kt`：候选 Core API，仅有 car、seatIndex、世界 position/direction。
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/Car.kt`：公开 seats 列表，当前没有定义写入 API。
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt`：唯一 Core Seat 实现及 addSeat/removeLastSeat，未找到调用。
- `Core/src/main/java/work/art1st/javarailways/core/api/physics/train/TrainFrameSnapshot.kt`：CarFrameSnapshot 的身份/车体与转向架帧，当前没有座位列表。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartRegistry.kt`：SEAT 类型、局部位移/旋转和驾驶配置的解析。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/ModelTreeBuilder.kt`：递归解析模型引用、组合位移/旋转，直接构造 SeatModel。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/CarDefinition.kt`：车体部件和 bogie 定义；没有 SeatConfig 字段。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/SeatConfig.kt`：独立但未找到调用的另一份座位配置草稿；不是现有 SEAT 部件配置入口。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/SeatModel.kt`：点击命中箱、carrier、passenger、驾驶座关联及释放的实际生产所有者。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/ModelState.kt`、`CompactTransform.kt`：父子和动画姿态组合。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/CarExtension.kt`、`TrainComponentExtension.kt`：车体帧消费、模型构建、audience 激活、重建/销毁。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/PacketEntityMotion.kt`、`SeatAuthorityMover.kt`、`PacketVirtualSeat.kt`：真实载体移动、客户端虚拟 mount 与 packet 生命周期。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlManager.kt`：从真实驾驶座和 passenger 关联获取控制权。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/WorldManager.kt`、`render/chunk/RenderChunkManager.kt`、`BukkitPlugin.kt`：帧调度、世界卸载及停用链。
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainBehaviorRuntime.kt`：受限 JS 导出面；不暴露 live Train/Car/Seat。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`、`DetailedTrainTravelSimulator.kt`：现有非 Bukkit 模拟及动力学输入；未消费 Seat。
- `models/s100/parts/s100/{seat,driver_seat_head,driver_seat_tail,body_seated}.yml`、`models/s100/cars/s100/head.yml`：当前实际座位、驾驶控制端、嵌套布局及车体段旋转的模型样例。

### 当前 Core Seat 的事实

1. `Seat.kt:9-16` 是 live 几何接口，不是当前平台无关座位定义 DTO：持有 live Car，位置是世界坐标，没有局部 offset、局部 rotation、稳定 seat ID、占用、容量或乘客类型。`Car.kt:24` 只声明 `List<Seat>`。
2. `CoreCar.kt:49-51` 初始列表为空；`57-79` 是全仓唯一 addSeat/removeLastSeat 定义。对含隐藏目录的仓库源码、脚本、测试与文档搜索 `addSeat/removeLastSeat/seatIndex/.seats/getSeats/core.api.physics.train.Seat`，未发现定义/import 以外消费者。Core 生产 factory、train/frame 和 render API 中也未发现创建/发布该列表的入口。此结论限定当前工作区源码，不把搜索无结果当作未来产品决定。
3. `CoreCar.kt:64-69` 匿名对象的 position getter 中 `Vector3d(position)` 指向其自身 position，静态可判定递归；本次没有运行复现。外层 position 应显式限定才能避免该问题，但修复 getter 本身不会接通生产座椅。
4. `CoreCar.kt:57-70` 无位置参数，所有新座位采用相同 `-length * 0.25` 偏移和车体 direction；只增加 index 不会产生不同座位位置。当前模型包实际有多排横向/纵向座位和旋转，无法用这段固定公式表达。
5. `CoreCar.kt:49` 返回 backing mutable list 的只读类型视图，接口没有写明线程或一致帧语义。`TrainFrameSnapshot.kt:11-28` 只发布 carId、length、车体与 bogie 帧，没有 Seat。把 live Seat.position 放进 Bukkit 异步渲染会重新引入逐字段读 live 状态，违背 Core render publication 合同。

### Bukkit 实际生产调用与状态所有权

配置与构造：

- `PartRegistry.kt:121-155` 解析 `type`、offset、rotation、子部件和动画；`181-199` 解析 `driver/control-end`。真正入口是 `PartType.SEAT`，不是 Core addSeat 或 SeatConfig。
- `ModelTreeBuilder.kt:201-254` 解析 inline/import 并递归构造子部件；`277-287` 直接将 ModelState、ownerCar、driverSeat、controlEnd 传入 SeatModel，没有创建 Core Seat 或记录其 index。
- `CarExtension.kt:135-169` 从当前或 pinned car definition 构建模型，再递归收集 SeatModel。`SeatConfig.kt:6-15` 全仓仅见声明，`CarDefinition.kt:13-25` 无对应字段；不要把这份未接入草稿当作第二个正在使用的配置来源。
- S100 的 `body_seated.yml:8-25` 每个部件包含 9 个 seat import，左右位置不同；`cars/s100/head.yml:30-45` 对其中车体段施加 180° 旋转；`driver_seat_head.yml:3-6` 和 `driver_seat_tail.yml:3-6` 分别指定控制端。seat 的实际姿态来自模型层次，不来自车长四分之一公式。

生成、乘坐和驾驶身份：

- `SeatModel.kt:55-91,94-115,295-298` 根据近距离玩家激活/停用，6m 创建、8m 退出激活，原生 Interaction 负责点击命中箱。
- `SeatModel.kt:156-174` 在乘坐需要时创建真实 native ArmorStand，记录 SeatBinding 并注册 packet mount；`301-315` 的映射身份是 hitbox/carrier UUID，不是 Core seatIndex。
- `SeatModel.kt:396-420` 点击后检查 carrier passenger 并在 entity owner 调用 `addPassenger`；载体真实 passenger 集合是占用事实。命中箱 responsive/尺寸是相应的交互投影，不需要 Core 另加 occupied 字段。
- `SeatModel.kt:350-358` 通过 player.vehicle、carrier 映射和真实 passenger 验证驾驶身份，再从 ownerCar 找到当前列车。`TrainControlManager.kt:60-63,84-95,126` 直接消费此结果，离座释放控制，control-end 决定驾驶端。

车辆移动与显示：

- `WorldManager.kt:537-577` 消费 immutable TrainFrameSnapshot，以 train/car ID 对齐，目标 location owner 上应用，卸载/停用时拒绝排队帧。`CarExtension.kt:99-120` 拒绝旧 revision；`185-240` 以同一 CarFrameSnapshot 的 position/direction/normal 构建车体及 bogie transform。
- `AbstractModel.kt:40-46`、`ModelState.kt:31-49`、`CompactTransform.kt:46-66` 递归组合父子局部位移/旋转与动画，再跟随车体世界姿态。这些是真实座位的几何来源。
- `SeatModel.kt:120-145,259-290` 由该模型姿态移动 hitbox 与 carrier，entity task 用 movementRevision 拒绝旧任务；客户端 mount 先进入当前列车 packet bundle，真实 carrier 在 entity owner 更新。
- `PacketEntityMotion.kt:113-160` 将 native server authority 与虚拟 mount 关联；`SeatAuthorityMover.kt:13-39` 用 native `move` 更新 carrier，并同步 positionRider；`PacketVirtualSeat.kt:19-30,70-81` 只镜像 passenger 到客户端 mount。虚拟 mount 是同一乘坐事实的显示投影，不是另一套乘客权威。
- `src/main/resources/config.yml:9-12` 明确两种展示后端都使用 packet 虚拟 ArmorStand 与隐藏 native ArmorStand。切换 native/entitylib Display 后端不等于新增一种 Core seat consumer。

离座、模型退出、世界卸载：

- `SeatModel.kt:371-394,423-452` 处理离座、退出、死亡，恢复点击状态并销毁空 carrier；`210-218` 另调度离座位置对齐。
- `SeatModel.kt:148-207` 销毁时先 retired、失效旧移动 revision、清 activePlayers、解绑 registry、释放 packet 资源，再按 entity owner eject/remove。`destroyRuntimeEntities(false)` 在 carrier 仍存在时保留占用载体；强制模型销毁则清理全部。
- `TrainComponentExtension.kt:85-105,108-135` 首个 audience 构建，最后 audience 隐藏时销毁，显式 destroy 幂等 retired，包更新重建也销毁旧树。`CarExtension.kt:270-283` 销毁 body/bogies 并清空收集列表，递归到 SeatModel。
- 列车显式销毁经 `CoreCar.kt:474-476` → `ExtendableRenderableTrait.kt:13-16` → CarExtension.destroy；Core 只是触发 extension 生命周期，未接管 passenger。
- 世界卸载经 `WorldManager.kt:331-366` 撤回 world、等物理 ticket、关闭 render manager；`RenderChunkManager.kt:63-75,225-248` 在玩家 owner 清 audience 并调用 renderer.hideFrom，最后 audience 路径销毁模型。插件停用经 `BukkitPlugin.kt:308-318,340-342` 停 world/unregister world 后关闭 packet runtime。这里只确认源码调度链；没有验证跨区域删除完成、离线玩家或服务器停用边界，不声称所有 teardown 场景已无泄漏。

### 是否已有平台无关消费者

- 非 Bukkit 模拟确实存在：`IsolatedTimetableSimulation.kt:93-104,253-278` 使用独立 CoreWorld 和 Basic/ATO 数据执行时刻表；但本次搜索整个 SimpleATO 生产目录没有 Seat/addSeat/seats 消费。`DetailedTrainTravelSimulator.kt:27-31,133-143` 的物理输入是动力学 profile、车厢 mass/force/grade 和 gravity，没有乘客/座位输入。“已有 headless”不能推导“headless 当前需要 seat”。
- 车辆 JS：`TrainBehaviorRuntime.kt:34-44` 仅允许 explicit host exports 且禁止任意 host class lookup；`93-108` 的导出只有 action、sound、log、operatingEnd。JS 未拿到 live Train/Car，没有反射绕过目录暴露 Core Seat 的入口。时刻表 worker 的 `scriptworker/Model.kt:6-45` 同样是时刻表值模型，没有座位接口。
- 仓库内其他插件/API：全仓定向符号搜索没有 Core Seat consumer；Bukkit 公共模型 API 只承诺普通 Group/Item 定义，并明确排除驾驶座和列车控制。没有通过普通模型服务把 Seat 变成平台无关能力。
- 用户明确确认没有仓库外插件后，不能再以未知第三方消费者作为当前保留理由。但仍需尊重用户对产品抽象取舍的决定，不能因此自动删公开类型。

### 若保留 Core 抽象：最小职责与单一来源

1. 语义限定为“车厢的固定座位定义/目录”，例如 car 内稳定 seatId 和不可变局部位置、朝向。seatIndex 最多是展示顺序，不能宣称它在插入、模型包更新或 reload 后仍是稳定身份。相对坐标约定应明确 model +X 横向、+Y 向上、+Z 纵向，并注明米制和姿态所属车体；不复用当前含糊的固定尾部公式。
2. 若消费者需要世界姿态，使用一次捕获的 CarFrameSnapshot 和定义做纯计算，返回不可变 value，或仅由 Bukkit 从已经计算的模型姿态投影。不要从多个 live Car getter 拼 pose，不因共享定义就新增每 seat physics tick 或每帧重复列表。
3. 模型包继续作为现有布局唯一 authoring source；加载/编译时生成共享定义，然后 Bukkit 的 SEAT 节点引用同一 seat 身份/定义。不要同时保留“模型位置”和“CoreCar.addSeat 位置”并靠周期同步维持一致。若 Core 独立拥有定义，则 Bukkit 需要从该定义构建实际座位，必须明确改掉现有第二来源，不能仅加一条同步链。
4. Core 不含 Bukkit World/Entity/Player、scheduler、packet、点击范围、mount/unmount、driver authorization、passenger map 或 occupied flags。平台真实 passenger 仍是唯一乘坐事实；跨平台业务如需统计，只发布不可变观察值，并先定义 owner/时点，而非建立第二套写状态。
5. 现有 SEAT 可嵌在模型/动画树，builder 的 bogie 路径也会构造 SEAT，但 `buildBogieGroup:176` 传入 ownerCar=null。未来目录若覆盖这些情况，必须先决定支持范围：固定车体座位可编译静态局部姿态；随动画或 bogie 移动的座位不能用一次静态 car offset 假装精确。应明确父绑定/动态姿态权威或明确限制，不能为未确认需求先引入通用层次变换框架。
6. 目录生命周期应与车辆/模型定义绑定，独立于 near audience 与实体生成。当前 SeatModel 会随 audience/包 generation 销毁重建，所以不能把 runtime entity UUID 或 SeatModel 实例直接当成长期平台无关 seat identity。

### 可选后续与仍需用户决定的内容

- 选项 A（按当前需求推荐）：产品只承诺 Minecraft 真实乘坐与驾驶。继续使用 Bukkit SEAT/SeatModel。建议在用户明确认可边界后，将 Core Seat/Car.seats/CoreCar 内未接入实现作为单独 API 收口批次删除；这是评估后建议，不是本次已经批准的操作。SeatConfig 另列未接入配置候选，不能顺手扩大删除范围。
- 选项 B：产品希望 Core 在无实体/headless 状态也能查询座位。保留并重新限定为上述不可变目录，先接通一个具体消费者，再谈需要哪些字段；Bukkit 仍实施乘坐，数据布局必须有单一来源。没有找到现有调用意味着这属于功能设计与接线，不是仅修递归 getter。
- 若本轮只修已确认缺陷，显式限定外层 position 可作为局部候选，但它只修错误访问，不证明 Core Seat 布局正确或生产可用；不能据此宣布座位系统完成。是否做此临时局部修复由主会话结合用户当前批次决定。
- 首先需要用户决定：是否把“无实体也可查询的座位目录/身份”列为 Core 产品能力？若要保留，还需要确认是否只支持固定车体座位，以及是否要求身份在模型包改版后保持。无需让用户决定 entity/scheduler/packet 属于哪层；现有依赖规则已明确归 Bukkit。

### 现有验证证据与合适的后续验证

- 本次只读源码和现有测试，不运行构建或测试。`PartRegistryDriverSeatTest.kt:9-21` 验证 driver/control-end 解析；`PartRegistryAnimationTest.kt:151` 验证 SEAT 类型；`SeatAuthorityMovePlanTest.kt:8-28` 仅验证移动差值/残差数学；`PacketVirtualSeatTest.kt:9-17` 仅验证 own-mount health 条件。它们不能证明点击、实际 passenger、Folia 跨区或卸载完成。
- 若仅修 Core getter，需要真实 CoreCar 加座并读取姿态的针对性回归，独立期望值，不能用同一 getter 计算 expected；同时保留“未接入生产”的说明。
- 若选择共享目录，需要真实模型包展开后的 seat 数量/局部姿态与 Bukkit 映射的一致性、稳定身份策略、无 audience 时目录仍存在、模型更新时单一替换边界，以及 world-frame 派生姿态的一致性验证；不是给两个可变列表写同步测试。
- 任何乘坐实现变更最终仍需 Paper/Folia 实机验证点击、占用竞争、驾驶授权、离座、移动跨区和 world/plugin unload；CPU 静态检查和 helper 测试不足以证明这些结果。

### Related Specs

- `.trellis/spec/javarailways/core/index.md`、`api-boundaries.md`：Core 不导入 Bukkit；API 缺能力时最小契约并注明 live/value、owner、线程与生命周期。
- `.trellis/spec/javarailways/core/train-physics.md`：Core 拥有车体物理与完整销毁；Car.direction 与 operatingDirection 分离，座椅视觉朝向不能随 operating reverse 随意翻转。
- `.trellis/spec/javarailways/core/render-publication.md`：跨线程只消费一致 value frame；平台拥有 entity/viewer/location side effect，不补读 live car。
- `.trellis/spec/bukkit-plugin/backend/index.md`、`lifecycle-and-threading.md`、`interaction-render-persistence.md`：composition、owner scheduler、latest revision 与成对释放。
- `.trellis/spec/bukkit-plugin/backend/model-presentation-api.md`：普通模型公共定义排除车体/驾驶座，不能以通用显示 API 冒充列车座位 API。
- `.trellis/spec/javarailways/shared/versioning.md`、`ai_tasks/persistence_schema_evolution_rules.md`：本次不改格式。若后续引入稳定 seat identity 或改变模型包配置，单独判断最小持久化边界及发布基线，不因 API 重构自行升版或加旧开发格式 reader。

### External References

无外部资料依赖。本结论来自当前工作区源码、模型样例、任务合同和维护规范，不依据旧历史笔记或外部 Paper API 行为推断。未核实服务器版本或现场运行状态。

## Caveats / Not Found

- 未找到 Core Seat 的当前生产/测试/脚本消费者；不是承诺不存在未来需求，也不构成删除授权。
- Core getter 递归是静态确认，本次没有运行新的定向复现。Core 列表初始为空且无调用，因此没有证据表明它正在导致游戏乘坐失败。
- 当前 Core Seat 不是“纯定义/相对位置/稳定身份”的成熟抽象；这是可选未来职责，不能描述成已存在的功能。
- `docs-site/docs/train_model_packages.md:367` 仍描述零尺寸 Interaction carrier；`PartDefinition.kt:10` 和 `ModelTreeBuilder.kt:58` 注释仍称 invisible minecart。当前生产源码使用 native ArmorStand + packet ArmorStand，应在未来相应批次同步文档。本次未修改 docs-site 或用户模型文件。
- 世界卸载追踪只确认代码路径和已安排操作，没有验证调度最终完成或泄漏问题；不以这份定向评估替代完整 Bukkit 生命周期审查。
- 没有实施任何 API 取舍、重构或清理。下一步产品选择仍由用户/主会话决定。
