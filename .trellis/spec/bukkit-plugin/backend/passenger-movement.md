# 列车 platform 乘客移动

## 状态、范围与归属

CURRENT：乘客会话、平台交接与生命周期清理已实现。用户实机报告了不按键随车 jerk、静止时边界短暂停顿；对应修复已完成自动化回归，真实客户端复验仍待完成。BukkitPlugin 负责玩家输入、实体乘坐、平台部件姿态与清理；Core 继续负责列车物理和完整不可变帧。

玩家进入列车实际 platform 支撑面后，自动挂载到个人隐藏承载实体。WASD 按玩家水平视角以固定速度改变车内位置；相邻相接或重叠平台之间保持同一乘坐关系；走出连续区域才恢复普通运动。本轮不处理车门、车墙/座椅碰撞、防坠落、跨空隙连接面或不同列车之间的交接。

## 一次正常移动

已有车厢观众/互动激活路径提供附近玩家候选。平台登记其车厢归属、模型生命周期和复制后的相对根部件变换。玩家脚底接触真实支撑面才开始会话，已经在固定座位或其他坐骑上的玩家不会被抢占。

每个受控玩家使用一个独立承载实体。`WorldManager.PendingTrainFrame` 保留一次选定的完整列车帧，`CarExtension.applyPublishedFrame` 接受该帧后，在更新模型的同一 packet bundle 内提交乘客姿态。平台注册表按车厢保存已接受的完整场景，不在玩家 tick 读取更新更快的物理帧。

实体 owner 每 tick 读取输入和水平视角，在该会话最近已接受的场景中积分局部行走。无输入时不另发随车位置；随车位移由本节车厢的显示提交驱动。静止列车即使没有新 Core 帧，仍能按最后场景行走。初次接管只检查该候选车厢自己的平台，避免借 A 的旧整列场景接管已经显示到新帧的 B；已挂载后的跨平台几何仍取一次完整场景。

## 几何与交接机制

平台边界沿用当前 rows、columns、spacing 与单个碰撞单元半宽 0.5 米的定义。现有 `PlatformModel` 的 3/5 米横向预激活余量只用于碰撞实体显示，不属于强制上车区域。必须使用脚底接触高度，不能把宽纵向预激活范围当成地板。

1. WASD 组合后归一化，斜向与直向速度相同；固定步速为 `4.3 m/s`，20 tick/s 时每 tick `0.215 m`。疾跑、潜行和跳跃不会增加或改变受控行走速度。
2. 使用当前 yaw 形成世界水平方向，投影到平台支撑面并归一化；pitch 不产生飞行分量。变更视角后下一次 tick 即使用新方向，不等待另一条按键事件。
3. 优先保留当前平台，即使当前点落在多个平台的重叠区。到达离开边界时寻找同列车连续可达的平台；将世界交接点换算到目标平台局部坐标，继续消费剩余步长。
4. 沿运动路径判断连续性，不能仅因最终位置位于 B 就越过中间真实空隙。浮点误差容差用于边界比较，不能变成吸附或跨缝距离。
5. 交接不更换真实/虚拟承载实体，不反复发送下车/上车，不移动到目标平台中心，不清空仍按住的输入。
6. 不存在目标平台时结束会话并释放至区域外位置；不夹回边缘，不增加全局重上车冷却。

## 实体高度与后端合同

复用 `PacketEntityMotion` 的 carrier 注册、客户端虚拟挂载和服务端 authority 移动；不另写第二套座位数据包协议。固定座位与可行走乘客的会话/输入归属分别管理，仅共享真实相同的实体配置与移动机制。

当前 1.21.11 cached NMS 定义：Chicken passenger attachment 为 `(0, 0.7, -0.1)`；闭合 Shulker 的碰撞高度为 1 米；现有平台 Chicken 原点为部件世界 Y 减 `0.6875`。因此支撑面相对于部件原点有约 `+1.0125` 的世界 Y 偏移和 yaw-forward `-0.1` 的水平偏移，不能简单取 local Y=0。

Marker ArmorStand 高度为 0；玩家 vehicle attachment 为 `(0, 0.6, 0)`。以脚底为目标时需要把承载实体提高 0.6 米。受控越界时沿用 `SeatAuthorityMover.move`，先把 carrier 与 rider 对齐到目标脚底，再调用原生玩家 `removeVehicle()`；确认实际已解除后调用连接 `resetPosition()` 更新普通移动基线。不能调用会追加 DISMOUNT teleport 的 `stopRiding`，也不能复用固定座位下一 tick 回位。原生取消事件若保留原载具，仍保留本会话和 carrier，并恢复该会话既有局部位置。普通固定座位和生命周期 eject 继续按其原有路径处理。

真实 Folia 26.1.2 字节码确认 DISMOUNT teleport 会设置 `awaitingPositionFromClient`，原生在确认前跳过普通行走 XYZ，构成静止时也会发生的交接停顿。刚上车时也可能尚有原生 boarding teleport：此时立即无传送下车会被迟到确认覆盖。`SeatAuthorityMover.hasPendingTeleport(Player)` 在玩家 owner 上，以缓存的严格 `VarHandle` 只读连接字段；当前编译 1.21.11 与实服 26.1.2 的字段名、类型已核对。未清空时仅保存越界局部位置并随车，后续 entity tick 在原生实际清空后释放。全部确认包沿原生路径处理，不写私有状态，不伪造确认，不用超时或近距离猜测。该绑定不包含旧字段名探测或兼容分支。客户端实际眼高、连续性和平滑度仍需游戏内确认。

没有新增公开 API、命令、配置或持久化字段；输入方向无量纲，局部/世界位置以米计，固定速度以米每秒计，跨 owner 姿态必须是值快照。

内部接入由 `PassengerMovementManager` 提供：`start()` 注册事件，`register(PassengerCarPlatforms)` 登记一节车厢的部件，`watch(Player, PassengerSurfaceSource)` / `unwatch(...)` 跟随现有附近观众，`retire(PassengerCarPlatforms): CompletionStage<Void>` 和 `close(): CompletionStage<Void>` 返回真实会话清理结果。每位附近玩家只持有一个 player entity-owner 时钟；挂载后玩家与 carrier 共享 owner。每 tick 直接读取 `Player.currentInput`，不再维护第二份按键事件缓存。

`PassengerPlatformRegistry.present(registration, TrainFrameSnapshot)` 在车厢模型应用后，以调用者给定的完整帧投影平台并保留该车厢场景；`capture(registration)` 只读取该场景。`PassengerScene` 包含完整帧和 `PassengerSurface` 列表。`PassengerSurfaceSource.publish/current/retire` 只发布复制后的局部姿态。`PassengerWalking.advance(...)` 返回 `PassengerWalk`，其中 `surface == null` 表示此次位移已离开连续区域。共用 `RenderableFrameSnapshot.modelTransform(floorHeight)` / `modelRotation` 保持模型显示与乘客几何使用同一姿态公式。

## 生命周期、线程与帧来源

- 每个玩家最多一个乘客会话；会话启动、运动和退出有明确 owner。Bukkit entity/location 操作经 `PlatformDispatcher` 的目标 owner 执行，不能在 physics/global worker 上直接操作玩家。
- 一个积分步骤只使用一份已接受的完整场景。Core 的 `Car.train` 原子归属与 runtime 来源用于识别换编组/删除后的旧登记；不得把多个车厢各自的最新可变姿态拼成场景，或从后续物理帧抢先发送乘客位置。
- `PassengerMovementManager.present` 在车厢原有 bundle 内输出虚拟 carrier 位置。`ViewerPacketFrame.holdUntilFrameSent(ReentrantLock)` 将该会话锁保留至最外层帧向各 viewer 的 Netty 队列入队完成；嵌套帧复用外层，发送异常也在 finally 反序释放全部锁。后续 owner 行走不会越过尚在缓冲的旧位置。临界区覆盖当前 chunk 的同步构建与发送入队，不等待尚未运行的 owner future。
- native authority 通过 carrier owner 更新，并用每会话 movement revision 拒绝旧目标；结束会话先 fence，排队目标不能在释放后执行。`PacketEntityMotion.syncPassengerAuthority` 沿用既有 mover/metrics，仅将可能重新进入 `CarExtension` 的观众刷新合并并延后一 tick，避免 session → audience → car 与 car → session 的实际锁环。每个 managed carrier 最多一个待刷新任务；回调复核 active、身份和当前乘坐关系，retire 后不重建旧观众。位置、输入和 packet 不因这次观众刷新而延迟，固定 Seat 保持原刷新路径。
- 分 chunk 派发不等于跨 Folia region 或并行线程。优先直接消费已有完整帧，不因为分 chunk 就新增 `WorldManager` 整列汇合机制。
- Shift 拦截只针对当前活动乘客会话的主动 dismount。进入退出状态后，越界、死亡、退出、有效外部传送、换世界与销毁均能释放。尊重已取消的世界卸载/传送事件。
- 平台/模型重建、列车 runtime 来源变化、世界卸载和插件停止必须失效旧登记和会话；排队回调不能把玩家拉回、重新注册 carrier 或恢复旧输入。
- 清理沿用 `DestructionSteps` / `PlatformCleanup`，由真实 owner 动作完成通知证明删除完成；GentleUnload 在 packet runtime 关闭前结束会话。整服 Folia 停止遵循既有 skip-owner 合同。
- manager 使用已有 `presentationOwner` 调度器。正常业务 dispatcher 先停止，manager.close 在 owner cleanup 批次中等待，包括此前已排队的接受下车清理；最后才 drain presentation dispatcher。不能把该延迟清理留在已停止的业务 dispatcher 上。
- 所有状态仅存在于运行期，无格式升级、migration 或旧格式兼容行为。

## 条件与结果

- 附近但未接触支撑面：不接管，现有显示预激活照常。
- 已乘坐固定座位/驾驶席/其他坐骑：不抢占乘坐关系，不获得驾驶输入控制权。
- 仍在当前平台或重叠区：保持当前归属。
- 进入相接目标平台：同 carrier 转换局部坐标并消耗剩余位移。
- 侧边/端部或真实空隙：释放为普通运动，不补连接面。
- 已取消的传送/世界卸载：保留仍有效的会话；被接受的动作进入终止流程。
- 初次挂载失败：仅释放本次已创建的实体/任务，不留下半注册会话。spawn 前登记到现有 removals 的真实分配 completion，发布 session 前在 lifecycle 短锁内重新检查 closed/watch/registration；close 不漏掉 spawn 中的资源，不在 lifecycle 锁内等待 session。
- 运动或清理失败：沿用现有后端失败报告；不能吞错伪装完成，也不新增另一套兜底传送协议。

## 成本与退化行为

平台登记跟随模型生命周期；不逐 tick 扫描世界所有玩家或所有列车。每个活动会话仅处理其所属列车已登记平台，普通无边界步长优先测试当前平台，边界交接才搜索候选。静止列车行走由实体 tick 驱动，不改变 Core stationary mailbox suppression。

每次车厢显示提交投影所属列车的 C 节车厢与 P 个平台，成本 O(C+P)；manager 另遍历 S 个活动会话并为 M 个匹配会话查找平台，成本 O(S+MP)。若 C 节车厢均提交，整列该轮投影最坏 O(C(C+P))，会话扫描/查找成本按各车厢累加。缓存每个登记车厢一份场景，空间 O(CP)，完整帧引用复用；不是按历史 revision 累积。玩家 tick 复用场景，无输入不再重复投影或发送位置。一次行走最坏逐次搜索相接平台，已访问集合保证每个平台至多交接一次，成本上界 O(P²)。附近未接管玩家只搜索其预激活候选关联的车厢平台。帧持锁和延后观众刷新只保留当前任务的有界状态，不添加常驻输入队列。

注册表只保存当前模型成员和会话；终止后释放引用。数值比较和交接迭代必须根据真实几何与剩余步长收敛，不能引入任意“最近平台”吸附或无界重试。

## 必须覆盖的场景

直线 A→B→A 应保持 carrier 身份、世界交接点和剩余步长；重叠区不反复切换。曲线和坡道按各自支撑面换算，不能拿 A 的局部坐标原样复制给 B。真实空隙释放，不因为步长末点在 B 而穿过空隙。

静止列车上的长按仍每 tick 移动；转头改变方向；两名玩家的输入和位置互不覆盖。正常座位和驾驶席继续执行原有输入和下车逻辑。

## 测试与验收边界

要求覆盖方向/斜向归一化、旋转/坡道变换、连续与重叠交接、空隙退出、剩余步长、静止持续输入、frame 来源、模型失效、取消和接受的结束事件、Shift/跳跃及清理后的迟到回调。涉及共享座位 helper 时保留现有 Seat 相关测试。

回归必须先发布更新的物理帧而推迟车厢显示，证明无输入乘客不会抢跑；实际接受帧即使早于最新物理帧，也与车厢进入同一 bundle。外层帧开放时另一个 owner 的行走不能先发包，发送异常须释放锁。还需覆盖首次接管不借其他车厢旧场景、静止持续行走、旧 authority 迟到、原生 pending 确认前后释放、下车 veto 保留同 carrier，以及 spawn 与 close 并发的真实分配 completion。

`PassengerGeometryTest` 的 8 项纯几何/值快照、真实 Core 列车场景及既有 Seat 测试继续保留。测试中的 entity/dispatcher doubles 只替代 Bukkit 执行出口，不证明 Craft/NMS 已在服务器执行；切换 pending 布尔的 fixture 也不等于测试了真实私有字段。精确 NMS 行为的静态依据与实机复验边界见本任务研究/验证记录。

运行受影响的 `:BukkitPlugin:test` 和 `:BukkitPlugin:shadowJar`，并检查 `git diff --check`。测试使用生产几何/会话逻辑；可控 scheduler/event fixture 不构成真实客户端验收。

Paper/Folia 游戏内另验收挂载高度、第一人称插值、直线/曲线/坡道跨车厢、多玩家和退出连续性。仅编译、单元测试或插件启动不能证明乘客体验通过。

## 维护与迁移

本轮没有持久化变更。新增跨空隙连接面、防坠落、车门/内部碰撞、跨列车交接或不同移动模式，均属于后续产品范围，不能当作当前交接失败的隐式修复。

应避免每个平台单独挂载同一玩家、每次交接先 eject 再 mount、用近距离显示范围吸入乘客、延迟传送玩家回旧位置。正确做法是单个乘客会话持有稳定 carrier，用一致帧换算局部坐标，并在退出状态中完成位置释放和资源删除。
