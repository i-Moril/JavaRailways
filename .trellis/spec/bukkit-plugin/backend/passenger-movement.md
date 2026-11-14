# 列车 platform 乘客移动

## 状态与范围

CURRENT（源码机制，实机待复验）：玩家 entity owner 计算相对移动，行驶时随正常车厢帧呈现，停车输入仅更新变动乘客。模块负责脚底接触后挂载、固定步速局部行走、同列车连续交接及越界释放。已删除原会话持锁和乘客专用 audience 延迟；不新增列车帧缓存、整列停车任务或组合全部发包批次的等待。第一人称平滑度、真实下车和性能仍需客户端复验。

## 配置开关与接触判定边界

主 `config.yml` 的 `passenger-movement-enabled` 为 boolean，缺省 `true`；设为 `false` 并重启插件后，composition root 不创建/启动 `PassengerMovementManager`。已有可空接入因此不登记平台、不创建玩家跟车 watch/会话或 carrier。原有平台碰撞与固定座椅继续运行。新增有默认值的配置字段，不升级 schema，不增加旧形状解析或迁移。

当前上车判定只检查玩家脚底是否落入平台矩形，以及平台局部高度差是否不超过 `0.15` 米；不检查脚下方块碰撞支撑，不区分车内与站台。预激活的额外水平 `3` 米只提供候选，不直接授权挂载。若站台玩家的脚底已落入实际平台矩形且满足高度条件，即使脚下有站台实体方块，仍会被自动挂载而随车移动。这是源码可确认的误带路径；某次实机反馈是否由平台模型伸入站台、转弯扫过站台等触发，须核对该场景模型和坐标，不能凭源码断定。此开关不修改接触算法。

## 一次正常移动

平台预激活仅提供候选来源。玩家已有实体调度逐 tick 判断脚底接触实际平台，挂载个人隐藏 carrier。受控期间按视角读取 WASD，以每 tick 0.215 米累计局部位置，一次发布所属平台、局部坐标、朝向和退出状态。行驶时，WorldManager 在正常列车候选分区域前采样一次乘客清单，车厢接受对应姿态后，在同一 bundle 输出乘客位置。停车时（已接受场景的 speedMetersPerSecond == 0.0），玩家 tick 推进步行状态后直接调用 renderStationary，仅输出本人 carrier 的变化。两条路径共用 render 的坐标变换、packet 呈现和原生 owner 同步。

## 表示与算法

- 跨线程只传不可变值和受控身份引用，不传 live model/JOML 状态。所属平台与坐标必须在同一快照中原子发布，不能分别读写。
- 局部位置是累计结果，不是本次输入增量；渲染跳过中间状态不会丢已执行的步行距离。玩家 owner 是局部位置、交接和越界意图的唯一写入者，渲染不修改它们。
- `PassengerWalking.viewDirection` 根据水平 yaw 与 WASD 组合并归一化；`SPEED_METERS_PER_SECOND=4.3`，每个实际执行的玩家 tick 前进 `DISTANCE_PER_TICK=0.215` 米，疾跑不改变步长，跳跃不参与计算。
- `advance` 逐段走完步长，先到当前平台边界，再在同列车平台中寻找边界点共面的连续入口并消费剩余步长。保持同一 carrier；有真实空隙则产生越界结果。几何容差 `1e-7`，不设门、墙、跳台或桥接。
- 输入几何来自只读列车场景，同一次几何计算使用一份完整 TrainFrameSnapshot；首次接触以被接触车厢实际接受的显示场景为依据。呈现位置使用这次车厢实际接受的姿态，不从更快的物理 publishedFrame 独立跟车。

## 内部接口与单位

`WalkingState(registration, surface, local, yaw, exit)` 是不可变累计状态，`Session.state` 以 volatile 引用一次发布；`Presentation(session, state)` 固定本次采用的完整状态。内部入口：

```kotlin
fun capture(world: org.bukkit.World): Map<UUID, List<Presentation>>
fun present(registration: PassengerCarPlatforms, frame: TrainFrameSnapshot)
fun render(presentation: Presentation, frame: TrainFrameSnapshot, flushed: CompletionStage<Void>)
```

`capture` 每次只读一次每个会话的 state，再按准确 publication stream 分组。`present` 只登记车厢已接受的场景；`render` 以同一 frame 的平台姿态将 local/exit 换成世界坐标。`CarExtension.applyPublishedFrame(...): TrainFrameSnapshot?` 在成功接受和等于当前 revision 时返回实际接受帧；旧 revision 或旧 stream 返回 null，既有模型 gate 不因乘客输入而重新应用相同帧。

`PassengerCarrierAccess` 分开提供 render、syncAuthority、canDismount、dismountAt、release。行驶 render 加入当前车厢 bundle，外层 bundle 提交后完成本批 flushed；停车只发本人 carrier，本次 enqueue 返回后完成 flushed。完成指提交发送队列，不等待客户端。之后在 carrier owner 上同步真实位置或执行越界释放。公共 API、模型/配置和持久化格式不变。

`PassengerPoint` 为米制不可变坐标。carrier 的世界 Y 为脚底加 `0.6`。现有 Chicken/Shulker 碰撞面高度为世界竖直 `0.7 + 1.0 - 0.6875 = 1.0125`，并含按 yaw 朝后 `0.1` 的偏移；不能把碰撞高度改成倾斜后的局部竖直偏移。实际接触要求局部水平范围及高度差 `<=0.15`；平台额外预激活范围不作为上车范围。

## 线程、生命周期与发布

- `runForEntityAtFixedRate(player, 1, 1)` 是既有时钟，不新建乘客线程。spawn、挂载、真实移动、解除和删除保持 Folia ownership。
- 正常列车分发在拆分区域回调之前为每位乘客采样一次。A→B 交接不能由 A、B 两个回调分别读取最新归属，导致重复呈现或遗漏。保留已有逐列串行分发和区域分块，不要求同列车所有车厢位于同一 Folia region。
- 停车走动使用只处理乘客的简化路径，复用平台当前已接受的姿态，不提交整列渲染、不新增 retained 列车帧缓存、不修改通用 latest-only dispatcher。
- 停车乘客更新不重跑模型树；行驶时由车厢呈现更新乘客，停车时只处理玩家移动。两条路径共用既有 carrier 后端与生命周期。玩家 owner 始终唯一推进局部坐标，呈现方法不修改步行进度。
- 正常移动不持会话锁跨线程等待发包。既有短生命周期事务与后端同步有独立职责，不能宣称全插件无锁。
- 结束先使新/迟到位置失效，沿用已记录的最后一次呈现批次完成标记，然后移除乘坐关系、释放 packet carrier 并完成真实实体删除；本轮不扩展为汇总所有批次的等待。完成结果不能早于实际操作；世界/插件停止后无需后续 world tick 才能清理。分配途中关闭也必须回收已生成的 carrier。

## 下车与失败处理

普通 Shift 只在活动受控会话中取消主动下车；死亡、退出、已接受传送、换世界、模型/列车/世界销毁及插件停止仍清理。不得抢占已有固定座位或其他 vehicle。

越界保留步长走出的外侧脚底位置。`SeatAuthorityMover.hasPendingTeleport` 只在玩家 owner 读取当前 NMS `awaitingPositionFromClient`；真实 boarding 确认未清时保留越界状态，确认仍由原版处理，不伪造 ACK。可释放时先把 rider/carrier 移到外侧，再 `ServerPlayer.removeVehicle()`，尊重 dismount event veto，并 `connection.resetPosition()`；避免 `LivingEntity.stopRiding()` 额外 DISMOUNT teleport 造成确认等待停顿。veto 后保留乘坐关系，并让玩家 owner 处理其后状态。

注册已失效、surface 已退休或玩家离开本会话 vehicle 时结束。调度退休/拒绝及真实清理失败必须完成或失败对应 future 并记录原因；不吞错后报告完成，不增加超时、兼容字段名、传送协议替代或重新上车冷却。

## 成本与退化

每位受控玩家每 tick 做一次输入和几何推进。平台登记保存现有每车已接受场景，capture 是取引用；车厢 present 投影涉及同列车车厢/平台并保留现有 registry 同步。设候选平台数 P，advance 每次交接搜索候选，visited 防止重复访问，最坏 O(P²)，不把它描述为全程 O(1)。

WorldManager 正常帧准备的乘客 capture 扫描全部活动会话后按 world/stream 过滤分组，成本 O(S)，没有扫描全服玩家。每车呈现筛选本列乘客，成本随车厢数及乘客数增长。既有逐列分发仍维持一个执行中任务和一个最新待处理任务；停车路径不进入该队列，不重新应用模型。原生更新沿既有 owner dispatcher，没有新 executor。玩家可累计相对坐标，显示可跳过中间位置。未做 tick 性能基准，不承诺零开销。

## 正常与边界情形

停车：玩家局部位置从 1.0 变为 1.2，只更新该乘客 carrier；整列模型不重新渲染。行驶且区域显示暂未运行时，玩家局部位置仍可累计到 1.4，之后随车厢呈现最新坐标。列车移动：局部位置不变，乘客和车厢用同一次显示姿态。跨车厢：在 A 的边界连续进入 B，只改归属和局部坐标，carrier/乘坐关系不重建。越界：走到外侧后恢复普通移动，存在真实 teleport pending 时按原版确认结果释放。退出或关闭：旧快照不能重新生成或移动已结束的 carrier。

## 必需测试与验证边界

生产调度链覆盖：行驶中多次玩家 tick 累计且不独立发位置包、匹配车厢帧的 bundle、静止持续行走且不重算未变模型、停车输入不增加车厢渲染次数/不提交整列任务、一次采样后的跨车厢交接、旧实体目标失效、pending/veto、外部事件、分配/发包中 close、卸载/停止后真实完成。

保留纯几何的方向、斜向速度、碰撞高度、连续平台与真实空隙测试。检查 `:BukkitPlugin:test`、`:BukkitPlugin:shadowJar` 及 diff-check；既有 S100 ZIP/source 基线失败单独记录。fixture 不执行真实 Craft/NMS 私有字段、Folia 迁移与客户端插值。实机仍需停车/启动/制动/转弯/坡道、多玩家、跨区域、Shift/跳跃与边界下车复验。

## 迁移与避免的做法

本轮只替换 Bukkit 乘客调度和必要接入，不改 Core 物理、普通座位职责或存档 schema。历史部署不代表本轮代码已部署。

避免玩家 tick 与车厢回调都发位置、都改 local，再用长范围锁串联。采用玩家唯一推进 local，行驶跟随车厢呈现、停车仅呈现玩家。避免为停车走动重提整列任务，再增加整列缓存和增量合并；直接复用现有平台姿态与 carrier 输出。源码机制已同步到本规范；聚焦测试通过，完整测试与构建证据归 task/research/validation.md，客户端验收独立保留待完成。
