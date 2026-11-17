# 普通列车联挂模式

## 已实现的范围与一次联挂

本契约为 `CURRENT`：`SimpleATO` 的 `CouplingModeService` 负责没有 Depot 任务归属的普通列车联挂。玩家先把接近车开到目标车运行尾端后方，两车均已停车，再调用 `World.couplingModes.enter(approachingTrainId, stationaryTrainId)`。系统不自动寻找另一列车，也不把接近车移动到入口位置。已有任务归属的列车继续由[Dispatcher](./dispatcher-runtime.md)执行其联挂动作，普通入口不会取消或抢占该归属。

Core 的 `inspectCouplingModeEntry` 根据两车运行方向、选中轨道和真实车钩几何判断入口是否成立。通过后，运行时暂时请求两车停车，分别为实际车体申请 Basic 路线资源；随后重新读取入口几何，调用 Basic 固定编组准备、封存和接近准入。该过程中不向 Depot 写入任务、结构日志或虚构的控制系统兼容关系。

接近资源准备完毕时，已有 `SimpleATO` controller 接收 `FormationContact` mission，并绑定该接近 grant 对应的路线、运行方向约束和行车授权；已有手动 controller 保留玩家的手柄输入。联挂 mission 的 revision 必须在同一 serialized owner 上读取 controller 实际命令流的最新已接受 revision 后立即递增并提交，不能根据 `currentMission` 的已发布 revision 分配：例如普通 mission 41 已接受但未经过首个 physics publication 时，联挂须提交 42，而不能误提交 1 导致 READY 后无法撤销。该内部读取不预占 revision，也不重试过期提交。两者使用同一个 Basic 签发的接触授权和控制管线速度上限。目标车继续保持停车。正间距时由真实物理运动到达接触点，Core 的真实联挂事务生成实际输出车体。入口已经精确接触时，只在两车仍停稳的 READY 时刻调用一次 `completeCouplingContact`，复用同一 Core 事务，不伪造运动或联挂事件。

Basic 观测到实际输出且保持物理 fence 后，运行时根据输出车体建立仅含车体的 grant，并在 fence 仍有效时通知平台释放两个原始列车对象的控制会话。然后删除已消费的 controller、接触授权和旧的 ATO 保护源，调用 `finishFormationForManual` 完成资源交还。输出没有后续普通 ATO mission，保持停止；玩家可以通过已有手动控制入口重新取得实际新编组。具体物理底线、接触授权和 handoff 原子性由[Basic 路线准入](../basic/route-resource-admission.md)与[Core 列车物理](../core/train-physics.md)定义。

## 公共接口、单位与所有权

接口位于 `simpleato.api.coupling`：

```kotlin
data class CouplingModeSettings(
    val maximumEntryGapMeters: Double = 5.0,
    val maximumApproachSpeedMetersPerSecond: Double = 0.5,
)

interface CouplingModeService {
    fun enter(approachingTrainId: String, stationaryTrainId: String): CouplingModeEntryResult
    fun status(trainId: String): CouplingModeSnapshot?
}
```

间距单位为米，含义是两车车钩之间的真实距离；接近速度单位为米每秒。间距必须有限且非负，速度必须有限且为正。它们是本世界组合时注入的参数，不是另一份列车几何或信号安全规则。

`enter` 返回 `Accepted(snapshot)` 或 `Rejected(detail)`。接受表示模式已经进入运行时管理，不能据此断言已联挂或一定能完成。快照包含事件 ID、两个入口列车 ID、阶段和可选详情；阶段为 `PREPARING`、`APPROACHING`、`COMPLETED`、`REJECTED` 或 `RECOVERY_REQUIRED`。`status` 优先返回使用该车的活动操作，随后返回以它为接近车的最近操作。完成的目标车可能已被 Core 从 world 移除，不能把其旧 ID 当成新物理列车。

调用与推进均在 world owner、完整 physics frame 之间执行。`OperationalWorldRuntime.afterPublication` 在观察与信号 epoch 完成后、Dispatcher 推进前调用普通联挂 tick。运行时复用组合层注入的 `TimetableRouteAdmissionContextProvider`，只接受本次操作最初捕获的同一 admission service。不存在第二份资源 registry、claim ledger、控制器工厂或物理执行器。

`OperationalWorldRuntime.create` 的组合参数为 `couplingModeSettings`、`couplingAdmissionContextProvider` 与 `couplingControlReleased: (Train) -> Unit`。最后一个回调在输出实际车体已经被 Basic 保持时执行，参数依次是两个原始 source 对象，包括已被移除的目标对象。平台在此释放由自己拥有的控制会话和命令索引；运行时只处理领域 controller 与保护绑定，避免旧平台会话继续指向已消费的物理对象。该回调不得把“移除 controller”误认为新输出已获行车授权。

## 几何、停车和状态推进

Source grant 由 published frame 的实际 `bodyTraversal` 派生。接近 grant 的 movement route 由接近车 `bodyTraversal + proof.headTraversal` 派生。Core traversal 使用 AB 坐标；转换成 `RouteSegmentSnapshot` 时，BA 方向必须用 rail 长度减去 AB 坐标，不能直接把 canonical 坐标当成有向路线坐标。接近 mission 的行驶距离只取 `headTraversal`，不能把保护目标车体的 corridor 当成可行驶距离。

准备阶段保留两个确切的 train 实例和原始 controller 身份，未完成的设备准备只推进既有 pending handle，不反复申请资源。准备完成后再次读取入口 proof。发布封存后，每次接近推进仍检查实际 train 实例及 `validatePublishedCouplingApproach`，在目标移动、换向或几何变化时先撤除旧接触授权并请求本地停车。进入真实输出阶段后，不再拿已被物理联挂消费的 source proof 校验新车体。

临时停车使用 `RailwayControlRuntime.acquireCouplingHold(train)` 的精确 token。移动时请求已有普通运营制动，停稳后保持零运动；不使用 ATP 硬零速边界冒充停车。Token 和接触授权按实际 Train 共享，因此同一车的受支持手动接管不会因 controller 实例变化失去保护。独立信号、运营方向和其他约束仍进入原来的严格限制归约；注册接触授权不允许 ATO 绕过绑定路线、mission revision 或行车授权终点。

例如，接近车停在目标后方 0.9 米时，目标 hold 保留，接近 hold 在 READY 后释放，ATO 或玩家低速前进直到 Core 接触。接近途中红灯给出当前零速限制时，ATO 仍停下；解除限制后才继续接近。非零车钩的两车入口已精确接触时，仍完成资源准备并走真实 Core 联挂，不新增“必须留出最小间距”的业务限制。

当前有一项具体资源准入边界：两端车钩长度均为零、两个独立车身已经精确贴靠，而且尚无可复用的 source grants 时，Core 几何入口可以接受，但 Basic 原有闭区间占用规则会在申请 source body grant 时返回 `PHYSICALLY_OCCUPIED`。普通运行时保留两列原车及 controller，清理本次资源和临时 hold 后返回拒绝；不会为此放宽全局端点占用规则、伪造已有 grant 或添加最小间距要求。该输入不属于当前已完成的零间距联挂成功覆盖。

## 拒绝与资源清理

入口几何不成立、没有 coherent observation、世界已经 Stall、参与车已有活动操作、已有 Depot 任务或结构归属、仍有 station call 时，入口返回明确拒绝。该入口只读取已有任务归属；未归属列车不因进入联挂模式获得 Depot assignment。

Source 或接近准入拒绝时，运行时撤除本次接触授权并保持或重新取得两车本地停车 token。只有两个原始物理实例仍存在且停稳，且 Basic 成功把结构操作撤销到 `ABORTED`，才能结束本次独有 owner 的 source route 并释放临时 hold。Basic 仍在准备、不能安全回滚或已经发布 READY 授权时，运行时保留资源与 hold 并报告 `RECOVERY_REQUIRED`；不能只改枚举就假装资源已回收。

普通入口和推进失败不会新建世界级 Stall。旧的 Core/Basic 不变量处理仍由其 owner 决定；本契约不覆盖或弱化它们。真实联挂之后的绑定或控制释放异常，也不能跳过实际输出保护提前放车。

## 成本、存续与验收

每个 tick 仅扫描本世界活动 session；车体/路线遍历和资源编译主要发生在阶段转换，成本与两车实际 traversal、car 数量及既有 Basic 准入成本相关。没有额外全图搜索、重新摆位、后台轮询或自建候选缓存。运行时保留每个仍存在接近车的最近结果以支持状态查询，已终止且接近车不在 world 时清除。世界关闭时不再推进操作，并由既有 control/runtime 生命周期撤销内存中的请求。

Session、pending handle、临时 token 和查询快照只存在于当前进程，不加入世界存档或 SimpleATO runtime snapshot；实际物理列车组成仍由 Core 既有持久化负责。不能在重启后根据旧事件 ID 恢复一份没有资源凭据的联挂模式，也不新增旧格式兼容路径。批量摆位不属于本接口。

`CouplingModeRuntimeTest` 必须使用真实 Basic 准入、Core 物理、`RealisticManualController` 和既有列车设备执行器覆盖手动/ATO、非零车钩的正间距/零间距。完成后断言实际单列车两车体、停稳、旧控制会话均在 fence 内释放、资源和 admission 无遗留、Depot 无新增归属，随后实际重新手动驾驶并观察新编组移动。独立零速限制必须能阻止 ATO 接近；source 和 contact 拒绝必须保持原 controller、清理本次资源、不 Stall 且允许重试。`CouplingApproachAdmissionTest` 另断言双零长度车钩精确贴靠且无已有 source grants 时的 `PHYSICALLY_OCCUPIED` 拒绝及无状态变更。这些是源代码与单元/集成夹具层面的验收，不能代替真实服务端玩家驾驶与客户端显示验收。
