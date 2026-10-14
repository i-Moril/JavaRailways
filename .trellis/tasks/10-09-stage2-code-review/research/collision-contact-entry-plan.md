# Research: collision 首次接触入口与最小 intent barrier

> 后续用户已确认采用另一方案：沿用上一tick碰撞检测，复用该tick已有frame/有序轨道片段，在联结事务中以静止方为锚点重算接近方位置。不新增intent barrier或路径历史。本文的入口问题分析仍可参考，但首次接触截断、禁止事后修正及新增控制阶段均为已撤回建议，不能作为实现要求。当前目标以Core train-physics的位置修正章节为准。

- Query: 怎样使非零车钩的碰撞联结可达，在首次接触前截短本 tick 的位移，而非事后回退/吸附？怎样处理真实 coordinator 并发？
- Scope: internal；只读源码研究，不实施、不构建、不部署。
- Date: 2026-10-12
- Baseline: 主会话提供的已提交 `9b833d99`，参数前置已提交；本文另外阅读工作区的四端联结未提交草稿。草稿不视为已交付实现。

## Findings

### 1. 当前真实链路与缺口

1. `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:448`：进入 epoch，先应用此前 pending collision，再 snapshot controllers、按 worker 数分批；每批执行 `preparePhysicsTick()` 和 `tick(deltaMillis)`，最后等待所有 futures。
2. `Core/src/main/java/work/art1st/javarailways/core/api/control/TrainController.kt:106`：base tick 算 distance/speed，调用 `moveControlledTrain`。生产 manual 和 ATO 也使用此 seam。`preparePhysicsTick:77` 的 reverse 是独立 movement-free epoch，返回 false。
3. `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt:241`：`moveTrainForwardFromController` 先调用 `detectSweptCollision(this)`；若命中，只清 active motion，不移动。未命中才扫 signal、移动 head bogie、逐车求解、更新 tail，生成 pending swept/traversal。
4. `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:320`：detect 查询 `CoreWorld.querySweptCollision`，向两个 train ID 加 pending 标记，发布 CollisionEvent，记录双方 published frame；结构决策下一 epoch 消费。
5. `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:1418`：query 传入的是 `train.publishedSweptSpans`，即上个完整 epoch 的扫掠，不是本 tick 请求的位移，也不是当前精确 placement。
6. 同文件 `:1211`、`Core/.../api/physics/rail/RailwayOccupancySnapshot.kt:70`：所有 frame 完成发布后，从每车 frame.sweptSpans 建一份 immutable bucket index，随后共享给 Core collision 与 Basic blocker 查询。

所以当前 detect 虽然在本 tick 移动之前，却是在发现上个 tick 已发生的扫掠重叠。没有 next-step prediction，更没有 connector 候选。单改联结接触校验不能解决：两端 connector 各长 0.3m 时，车体端相隔 0.6m 已符合联结，现有 body overlap 仍为 false；等到 body overlap 才触发，车钩已穿过。

当前 sweep 还按 rail ID 合并包络（`CoreTrain.kt:1291`），丢失发生先后；`SweptOccupancyIndex.kt:95` 按 minimum interval separation 排序，所有 overlap 的 separation 都为 0，随后按 train ID，不是按首次接触时间。因此不能继续把 `queryFirstCollision` 返回的第一个 ID 当作本次路径最先碰到者。

### 2. 未提交草稿解决了什么，没解决什么

- `CoreTrain.kt:908` 的 internal `linkFromCollision`：依据冻结前 frame 验证至少一方静止，检查四端 contact，插件决策，准备 controller/extension/geometry，最后无位移提交。
- `CoreTrain.kt:1010` 的 `couplersContact`：真实三维车体端点间距减双方 connector length，使用既有 5mm 容差，要求相向，再验证连接路径。
- `PhysicsCoordinator.kt:613`：草稿开始消费结构 Future 的成功/失败，并在失败时默认脱轨。
- 仍未解决：非零 connector 无生产碰撞候选、上一个 tick 才发现穿透、同时工作的另一个 controller 已移动、按 ID 而非首次接触选候选。

本文不建议扩大 5mm 容差，也不建议把完成移动后的车体回退 0.3m，更不建议调用 setLocation/rebuild 去吸附两个编组。

### 3. 必须先解决的并发点

旧 published frame speed=0 不证明对方在本 tick 不移动。对方有 controller 时，可以先完成加速/写 active geometry，然后另一 worker 才 `trainsPendingCollision.add`。标记只拦截尚未开始的 tick，既不撤销已开始的 tick，也不撤销其 active buffers。

这不是极端咽喉或罕见坏输入，是正常“驻车列车同时起步”的服务器调度情况。不能把支持对象偷偷缩成 controllerless train，不能拿冻结之后的速度0充当碰撞前静止，也不能在查询时读另一个 worker 的 active geometry。

建议最小且清晰的顺序：

```text
previous immutable frames/index
→ parallel controller prepare/tick: collect one movement intent per participating train
→ await all intent workers
→ determine relevant local candidates/contact and accepted distance
→ parallel apply accepted movement using existing rigid geometry
→ await all movement workers
→ existing buffers/frame/processor/occupancy/world publication
```

这里的 intent 仅是本 epoch 的 `(train, requestedDistance, requestedEndSpeed)` 值及是否 reverse/frozen；不是通用 action queue、不是持久化模型、不是新的 physics framework。无 controller 的 train 以0位移参与候选，不需要给它新 controller。

**任务/同步成本**：现有 B 个 batch submissions + 一次 join；建议保留原 join 作为 intent barrier，额外 B 个 geometry batch submissions + 一次 join，B<=workerCount，不按列车数量新增 Future。原 publication join 变成 geometry join。新增一处明确的阶段边界，整体时间仍包含两次计算，不能称为零开销或 O(1) 全世界 tick。

**能否不增加提交？** 可以让每个原 batch 完成意图后 countDown，等待 coordinator release，再在同一 worker task 执行 geometry，coordinator最后复用原 futures.get。但需要两个 latch、plan异常传播、所有 workers在 finally到达/release、close/interrupt失败路径处理。它减少 B 次 executor.submit，却增加本次改动的协调状态和死锁风险；不是默认更小的实现。建议先用两组既有 worker batches，只有实际 profiling 证明 submit 成本重要才优化。不要在 workerPool 内提交并等待同池后续工作。

不要为每个普通列车完整预测整个编组，更不要重建世界空间索引。无局部候选时，直接执行原正常几何一次。

### 4. Controller 与 ATO 需要同步的实际语义

- `TrainController.kt:126` 的 protected seam 改为在该 controller 的 coordinator planning 阶段记录值；独立 command/test/direct movement 路径可保留立即应用，但必须明确不属于 coordinator intent 阶段。
- 更稳妥的接入点是 `CoreTrain.moveTrainForwardFromController` 内检查本 epoch 所属的 collecting context：公共 `Train.moveTrainForward` 也委托这里，第三方子类直接调用它时仍不能绕过 coordinator 收集。不要只在 protected seam 拦截、留下 public move 绕行。
- 同一个 controller 每 tick 是否允许多次 move 当前没有明示保证。第一方每 tick 只有一次。最小实现应检查一次请求，不能无声 last-wins；若要支持多次顺序移动，必须显式设计，不可把多个 delta 简单相加同时丢掉中间 signal/方向/失败语义。这个公共合同变化需主会话说明。
- reverse：保留现有 prepare=false，独立发布方向且0 movement。不能先收集旧方向 movement，再在 geometry phase 改方向。
- pending collision：planning 前已 pending 的列车照旧不运行控制计算；本 epoch 新发生 contact 的列车，控制器已算出需求，Core仅约束本次实际位移/几何，保留手柄、命令、ATO需求，不擅自 cancel command。
- base controller/`RealisticManualController.kt:118` 在 move 后没有依赖刚刚更新的 train geometry，所以第一方分阶段可行；其 executor 状态表示已计算的动力学输出，不能把它假装成回滚后的真实位移。
- **必须改实际里程计账**：`SimpleATO/.../api/ato/AbstractATO.kt:102–104` 在 move 后立即把 `step.distanceMeters` 写 lastTravel 并加到 lifetime。截短后必须记 actualDistance，不能仍记 requestedDistance。
- 建议 Core 在 geometry应用后、publication前给 controller 一次本 epoch 的 movement-result 通知，或通过确定的 Core值由原 `onPhysicsStatePublished` 读取实际位移；ATO把计账移到该处。这个 hook 必须只接收已应用的 value，不触发再次移动，异常保持既有 epoch失败策略。不需要新增全局事件。
- ATO plan 仍在同一 previous published epoch 上算，actuator仍一步，不重算整次 plan。`onPhysicsStatePublished:126` 发布的 snapshot、lastTravel、lifetime 必须与实际 movement 同 epoch。
- 速度：接触后的车/转向架运动冻结0不等于要求立即删除 controller command。成功联结仍由已批准的 structural stop提交 scalar0；等待决策期间 controller.scalar 与物理冻结速度目前有不同语义，需要文档明确，不能临时改成永久制动。
- 自定义子类如果依赖 move返回后读取新 active geometry，则新阶段合同是行为变化；现在没有仓库外插件，但应更新 KDoc并用一个直接 public move 的 consumer测试证明不绕过收集。

### 5. 复用索引，而非复用错误的 occupancy 语义

`SweptOccupancyIndex.kt:40` 的 bucket key是 `(railId, floor(arc/32m))`，可以复用。应区分 body扫掠候选与端部 connector/contact候选：

- 同一 bucket storage 支持带用途的候选记录；body blocker/release只读原 body记录。
- connector记录只供collision broad-phase，不能把它直接塞进公开 `sweptSpans` 导致Basic闭塞占用增加、release延迟、持久化语义改变。
- collision查询要能取得所有与本次 ordered sweep有关的局部候选及对应端部，而不提前按旧 separation 返回一个 ID；精确求接触后选最小前进距离/时间。
- 可以把 body/end candidate共享桶，但不能在 consumer侧再建另一份世界 swept index。
- 实现要明确 immutable index何时捕获 connector metadata，以及与哪个 frame/拓扑epoch一致。可从已有 frame和Core端配置生成内部额外元数据，无需为此改存档。

**必须注意的未闭合几何点**：直接把 canonical端点向前扩大 `connectorLength` 弧长，不是三维connector距离的严格包络。曲线上的弦长与弧长不同，TrainEnd也只是沿轨offset的保守端，不一定等于三维车体端点在轨上的精确投影。当前源码没有现成的“connector swept rail interval”函数。实现不能把这个估算标为正确exact算法，也不能为了通过直线测试偷偷扩大5mm容差。

建议把候选投影作为一个明确的小几何步骤：沿本 train已选实际连续路径记录connector可能触及的有序片段，基于curve真实piecewise-linear几何/车体端点投影产生保守rail intervals；确切接触仍交下面的几何残差。若实现阶段无法证明所选包络覆盖真实车钩，应先报告这一点，不交付仅在直线可用的“正式版”。不要引入任意世界最近轨或每tick扫描整个咽喉。

### 6. 接触求解的可复用入口与正确边界

- `CoreCar.kt:315` 的 `calculatePositionByHeadBogie(newHead,deltaS)` 已经是只读几何求解，返回 `IntermediateResult`，之后 `updateByHeadBogie:405` 才写 active状态。
- 可增加窄 internal preview方法：从本次移动起点定位领转向架 `s` 处、调用同一只读solver，返回领车车体端点/方向；不要复制另一套刚体公式，不要靠先move再restore做预测。
- 对一个静止目标，仅运动方运营前车要preview，不必每次试探重算整列。用双方物理端点与connector长度，解“相向、有正确纵向侧的三维间距等于长度之和”。zero connector也必须有能跨越的有符号残差，不能只用非负distance在跨越后继续同号。
- 最终选得 `sContact` 后仅一次正常 `updateAfterHeadBogie(sContact)` 移动全列；pass-signal及head/tail traversal都用实际截短距离，不能先发布原deltaS范围内本不该通过的信号。
- `RailLocationWithFacing.kt:100` 的 `peekCurve` 给出按active连接排列的 `RailSegment`，`RailSegment.kt:40` 支持分段root search，`CoreCar.kt:212` 的 SolvePath缓存一次路径、`solveDistance`提供有界扩张/收敛手法，可复用思路。
- 不能把 `RailSegments.searchRailLocation` 直接当任意curve的首次根保证：它每段只检查首尾符号，区间内先穿过再离开时可能同号，50次二分不能修复没有包住的根。应先按实际curve分段/接触候选有序区间建立局部bracket，再从近到远求第一根。
- 普通有效铁路几何上的小step是主场景，不需要通用连续刚体世界碰撞引擎；但曲线、接缝、不同floorHeight和非对称offset已经是本插件正式能力，不能以“不拟真”为由退化成纯弧长相减。

### 7. 各场景的结论

**同向追碰、目标不动**：查询运动方向的前端，只读preview领车，用目标后端固定frame求首次接触。四种稳定端A/B都可能映射到运营前/后，不可以固定B-A。截短前进距离后正常摆位并冻结，不回退。

**对向，但至少一方不动**：同一算法，目标端面朝向相反，按路径和physical端配置检查。不是按world X比较速度或按train ID决定主编组。

**两方都动**：已确认不能联结；这不代表可以仅按前方静止快照算距离。相同方向前车驶离时不能凭候选就制造碰撞；对向则不能让双方各向对方旧位置前进而穿透。intent barrier让双方requested motion已知，但要精确首次接触仍需局部双轨迹求解，用共同epoch内的归一化进度 `t∈[0,1]` 求相对接触，不是两个独立distance各减一次gap。若本批仅实现一静一动联结，双方运动碰撞的旧保守fallback必须明确列为剩余行为，不能号称完成完整预测碰撞。是否在本批同步升级这段，应由主会话向用户说明范围。

**上epoch静止、本epoch起步**：intent barrier能看到它的正位移。还需明确“允许联结的一方静止”指起点published frame静止，还是本次接触轨迹中实际不动；当前TARGET明确取冻结前已完成epoch，所以不能擅自改成 `deltaS==0` 的更严格准入。如果双方本tick都移动却其中一个previous speed0，不能一边按旧frame通过准入、一边用静止目标几何计算。需说明这种情况的产品语义，或实际用双方轨迹处理后保留原准入。

**跨两条rail/接缝**：用ordered segments的累计距离和canonical AB转换。connector接触可恰在连接点两侧，此时railId不同；必须将连接端附近的候选覆盖到真实相连片段，再按路径验证。不可以仅same railId返回空，也不可以world空间距离近就联结。

**道岔/菱形交叉**：只沿当前实际前行路径，目标端必须属于该路径且相向；普通菱形空间相交不构成可联结的path。背向分岔不能依赖反向active选择来重建已占用路径，应复用已captured bodyTraversal。`placementSegments:387` 当前依赖activeRail，作为接触路径证明也需检查是否与forward captured path一致。

**闭环**：按本tick有限距离有序遍历；候选是路径中的一次occurrence，不是“rail ID出现过即全程相同”。`peekCurve`靠剩余distance推进，正常正长环会终止；目标不可达的path search则必须像现有placementSegments那样有visited判断。零长环不能无限循环。无需全世界环检测或跨tick路径缓存。

**多个候选**：按已求得的首次contact时间/距离取最小，不按train ID。若三车真正在同一位置同时接触、无法唯一指定端部，不自动挑一对；保留默认不可联结/脱轨。2026-10-14用户已决定移除32 candidate cap：当前碰撞查询必须穷尽同一有限occupancy snapshot中的局部候选，不因数量上限返回“无碰撞”。未来若改为本step连续TOI，仍须保证候选顺序由实际接触时间/距离决定；本决定不要求现在增加连续双车TOI、JS扫描预算或其他兜底。

### 8. 最小真实复现与验收

既有 `PhysicsCoordinatorTest.kt:978` 只把两列摆在同一位置，证明已重叠对象下一epoch脱轨，不证明首次contact。新测试用 `automaticFallback=false` 的真实 `stepSimulation(50.0)`，bounded await exact ticket；真实 `TickManagerImpl` drain结构队列，不只直接调用internal link。

固定直线复现，独立手写expected：

- 轨长200m；单车body长度10m，offset ±5m，floor0；两列的car ID必须不同（现有 `createTrainWithConfig` 每列都生成 `car-0`，直接复用会因重复stable ID让联结失败）。
- 后车body head=50.0m，前车body tail=50.8m，双方相向connector各0.3m，实际剩余contact gap=0.2m。
- 后车10m/s、50ms请求0.5m；前车无controller或零movement controller。期望后车只前进0.2m，body gap保持0.6m；不能原地不动，也不能先0.5m后回退0.3m。
- contact epoch只冻结/排决策；下一epoch consume LINK后主编组缺省前车，车厢实例/模型位姿不变，成功事件一次，scalar与car/bogie速度为0；失败/veto时保持结构且按默认脱轨。
- 事件listener只记录结果，assert放listener外，避免总线吞断言。

有效覆盖最少包含：

1. 上述非零connector+超越step；同时断言截短之外的signal未触发、head/tail traversal里程为实际值。
2. AB/BA、四种稳定端，以及一个非对称offset/connector固定fixture。
3. 在rail接缝两侧首次接触、普通曲线首次接触，独立验证最终三维端点gap≤5mm残差且body/bogies无后置吸附。
4. 两候选使用反向字典序ID，物理近者先；没有根据ID择近。
5. 静止目标也有controller，交换worker运行顺序（latch控制，不sleep）结果一致；目标本epoch起步案例必须按确认后语义写断言，不能跳过。
6. 前车同向驶离不碰撞、双方对向运动不联结；若预测collision未同步升级，明确这两个是缺口，不删测试来宣称完成。
7. 分岔未选支路/空间菱形不能联结；闭环有限step不重复候选/死循环。
8. ATO lastTravel/lifetime记0.2m而非0.5m，手柄/需求保留；reverse独立epoch、零movement意图、原pending冻结保持；public直接move子类不能绕过收集。

### 9. 涉及文件与复杂度

- `Core/.../api/control/TrainController.kt`：单epoch intent、actual movement result、KDoc；保留command/handle，不加公开active link。
- `Core/.../physics/tick/PhysicsCoordinator.kt`：两worker阶段、局部候选选择、明确接触pending及nextepoch消费；状态/close/ticket门禁保留。
- `Core/.../physics/train/CoreTrain.kt`：分开request/apply；按accepted delta移动、实际signal/traversal；只读前端预测与已知contact传递。
- `Core/.../physics/train/CoreCar.kt`：窄internal preview，复用既有rigid solver，不复制算法。
- `Core/.../physics/rail/SweptOccupancyIndex.kt`、`CoreWorld.kt`、`api/physics/rail/RailwayOccupancySnapshot.kt`：共享bucket的候选用途隔离及内部connector元数据入口，不污染Basic occupancy。
- `SimpleATO/.../api/ato/AbstractATO.kt`：actual里程计账；必要时其snapshot相关测试。
- `Core/.../physics/rail/RailLocationWithFacing.kt`/`RailSegment.kt`：仅在确需安全的bounded path/root工具时最小修改，不另建图。
- `PhysicsCoordinatorTest.kt`、`CoreTrainLinkTest.kt`及ATO motion测试：真实epoch+结果队列闭环。

索引保持原 `O(KH)` bucket引用构建，加端部局部候选的引用数；查询 `O(H+M)` 候选枚举，多个候选精确结果排序 `O(M log M)`。M是本step所经bucket的局部候选，不是全世界train数。正常无候选列车仍一次既有全编组几何解算。真正contact附近增加 `O(M·I·Gfront)` preview，其中I为bracket/refinement次数、Gfront是领车既有刚体solver成本；不是严格O(1)，不把候选试探变成 `O(M·I·整列cars)`。两动相对contact若要求全轨迹几何，复杂度另列，不能沿用front-only估计。

## Related specs / External references

- `.trellis/spec/javarailways/core/train-physics.md`：CURRENT移动/几何与TARGET四端联结、至少一静止、无吸附/5mm容差。
- `.trellis/spec/javarailways/core/runtime-publication.md`：worker barrier、exact ticket、close、结构准入、单epoch一致性。
- `.trellis/spec/javarailways/core/rail-topology-and-occupancy.md`：唯一共享bucket index与body/release语义。
- `.trellis/spec/javarailways/core/train-controller.md`：controller tick、protected move seam、publication hook。
- `.trellis/spec/javarailways/core/testing.md`：真实coordinator、fixture释放与独立expected。
- 无外部库方案/版本；本研究不依赖网络资料。

## Caveats / Not Found

- 无源码修改、无测试执行、无性能实测、无游戏验证。文件行号对应研究时工作区，其他代理后续编辑会漂移。
- 一静一动first contact并非把既有 `queryFirstCollision`换参数就能完整解决；connector几何候选覆盖和intent barrier都是真实必要工作。
- 需要主会话明确向用户说明额外范围：controller从即时移动变成提交movement intent，以及ATO里程延后按actual计账。
- 需要确认/明确的真正行为点：previous epoch静止但本epoch起步怎样解释准入；本批是否把双方运动的首次contact也同步升级；第三方controller每tick多次move的合同。不要重复询问已批准的一方静止、主编组选静止方、失败默认脱轨等规则。
- 不建议为省一组B个worker提交优先引入latch协调，也不建议在未证明connector区间投影覆盖时以直线smoke替代正式实现。

## Superseding follow-up: 有限空间邻域的 connector 候选（2026-10-12）

### 最新范围取代上文建议

用户后续批准沿用上一完整tick碰撞记录，在新tick workers开始前统一检测/冻结，结构准备时以静止方为锚点重算越界方。**不实施intent barrier、不新增跨tick历史、不升级连续首次接触模拟。** Owner TARGET已更新，本节取代上文有关必须增加intent phase及延迟actual里程计账的实施建议。本次仅研究一个有界候选替代，不恢复此前方案。

### 可复用的唯一替代：现有rail空间query → 局部曲线精确裁剪 → collision-only intervals

结论：现有primitive可以支持，不需要遍历任意分支，也不需要新增世界索引；但缺少一个小的**piecewise-linear curve与有限3D邻域裁剪成canonical arc intervals**操作。候选覆盖范围仍须从真实几何构造，不能仅拿connectorLength当弧长或凭端点两次采样声称整tick保守。

具体入口：

- `CoreWorld.kt:1150 getRailsNear(position,radius)`：在topology read lock内调用已有 `RailSpatialIndex.query`；这是局部query，不是 `getNearestRail` 的全world扫描。
- `RailSpatialIndex.kt:47 query`：枚举有限AABB对应的16m cells并去重rails，没有候选数量截限。
- `Core/.../physics/rail/RailGeometryBreakpoints.kt:10,15`：`CoreRail.geometryBreakpoints()` / `PrimeCurve.geometryBreakpoints()`，委托 `PrimeCurveImpl.kt:330` 的真实断点及其arcLength。不能使用可能含1m虚拟点的 `Curve.stream()`，也不能用getNearestPoint代替完整区间裁剪。
- `AtGradeRailCrossingIndex.kt:67 prepareGeometry` 已用这些断点生成 `Sample(position,arcLength)`，说明piecewise-linear数据可复用；不把crossing检测器接入tick，也不另建它的samples registry。

对一个有限球 `B(c,R)`，每个真实线段 `p(t)=a+t(b-a), t∈[0,1]` 解 `|p(t)-c|²≤R²`，与[0,1]相交得到[lo,hi]；按 `s(t)=sA+t(sB-sA)` 转canonical AB interval。无交集跳过；相邻区间合并，分离区间保留。AABB也可用逐轴slab clipping，数学更简单但候选更松；此处建议球裁剪，不另列一套实现。算法不依赖rail长度，曲线分割为任意多短rail仍由空间query全部枚举，不会漏掉“第二个相邻rail”。

**现有spatial footprint并非严格supercover。** `RailSpatialIndex.addSegmentCells:145` 按每轴不超过cellSize/2的步长取样，只把样点所属cell入桶；不能证明每个相交cell都被写入。保守使用方式是query半径加一个 `cellSize/2`（默认8m）粗相位padding，再用原R精确裁剪：任意真实线段点到一个索引采样点的L∞距离不超过该界，故它的采样cell会落入扩大的query AABB。这里只多取候选，不扩大物理接触容差，也不扩大写入的最终interval。无需为此重写世界spatial index；最好由该index提供internal几何query包装，避免调用方硬编码16/8。

### 邻域的数学域与不能偷换的假设

球/球并集描述**车钩可能涉及的有限空间区域**，不是“轨道上离TrainEnd前进L米”。同一已完成tick的before/after车体端点、connector两端、bogie位置必须来自对应immutable frame及已有ordered traversal，不能混用新ticklive值。

真实车体端点为 `C=carPosition±direction·length/2`。领转向架rail点 `H` 与C的偏移包含floorHeight、bogieOffset、body方向；从Core既有中心公式可得一个保守几何偏移界，例如 `|C-H|≤|floorHeight|+|headBogieOffset|+length/2`（normal需满足既有单位frame合同）。若用TrainEnd轨迹作基线，还要加入bogie到TrainEnd的沿轨offset界。以上是候选半径的几何量，不是新的可吸附距离。不能只用C到rail的当前nearest投影距离当整个tick偏移界。

可用该tick已有端部有序traversal的真实曲线片段作carrier，加上述偏移界和connector length形成有限tube，再以覆盖这些片段的有限球集合查询/裁剪。最简单的覆盖是每个carrier直线片段用中点球，半径=半段长度+几何偏移界+connectorLength；保守但会随长片段变松。查询和精确裁剪都可在本次局部调用中处理，无需跨tick缓存/历史。只使用before/after两点的包围盒不能覆盖弯曲中间轨迹；车尾轨迹、反向运营端的偏移必须从其自己的bogie/offset取值，不能套用车头配置。

这些界保证“给定carrier邻域内的rail几何不会因空间query/裁剪遗漏”。它们**不自动证明两个三维connector相交必定在双方产出的rail intervals里存在共同的rail witness**：需要在实施时根据普通有效曲线的body/端部到连接路径关系核对。不能只说clip精确就跳过这一层，也不能许诺任意自交loop/零半径折返都正确。最新范围允许已排除的极紧几何继续不支持，但普通曲线、坡道、接缝和任意短rail分割不可排除。若connector interval overlap作为唯一候选准入，必须用这些普通几何fixture证明候选可达；不要把缺失证明用固定“一条相邻rail”替代。

### False positives 与 exact pair verification

有限空间邻域会包含未选支路、平行线路、菱形交叉、同一rail的多次近邻段；偏移界和spatial padding也会增加候选。这是正常broad phase结果。**interval overlap只能成为候选，不能立刻发CollisionEvent/冻结/默认脱轨**，否则把不可联结的空间近邻制造为事故。

需要在claim之前，对双方对应frame的端部扫掠/connector几何作实际碰撞核验；已有普通body sweep命中和新增connector-only命中应区分。connector-only至少验证实际长度/物理侧/相向关系及该tick已有实际可连接路径证据，而不是当前activeRail无界寻路。后续anchor重算再次做严格最终5mm接触/刚体校验；后者不能代替前者，因为它失败会默认脱轨，不能用来筛掉无事故的broad false positive。不得在候选阶段从一个空间clip片段沿nextRails不断追分支，亦不得用仅一个相邻rail的特殊支持缩减正确范围。

只依赖old/new姿态的connector line-segment sweep是否足够，当前没有形式保证；普通曲线中间端点可能偏离线性插值，必须复用已有carrier及真实几何，或明确该保守包络还需精确pair核验，不能将端点线性插值当真实运动。这里没有完成新的continuous TOI求解设计。

### 工作量、验证与边界

新增最小operation是内部curve→sphere clip helper及带spatial sampling padding的局部查询包装；世界索引、持久化、worker阶段不变。单次成本为所覆盖cells数+附近rails的真实断点数，再加局部pair精确核验；不受固定candidate cap、不遍历世界graph，但密集局部布局自然会增加实际成本。长rail有大量真实断点时扫描该candidate rail的断点可能较贵，这是现有primitive的粒度，不能宣称严格O(1)或只与球内断点成正比。

必要局部验证：非零floorHeight/非对称bogie偏移，曲线中间轨迹，坡道接缝，多条连续极短rail，未选分支/菱形/平行轨的false-positive排除，以及空间线段穿过未采样cell的query-padding回归。该研究未新增或执行测试、未修改源码/规范、未构建/部署；不是完整设计验收，也不承诺所有任意闭环/自交几何。
