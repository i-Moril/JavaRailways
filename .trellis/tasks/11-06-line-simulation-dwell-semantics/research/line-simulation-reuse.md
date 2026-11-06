# Research: 运行线模拟复用真实隔离执行链

- Query: 替换长度/平均坡度估时，复用真实隔离 World、Dispatcher、车辆和信号资源链，生成可回填排图的单运行线时刻。
- Scope: internal；产品代码只读；未构建、未修改运行环境。
- Date: 2026-11-06
- Requirement: 最短停站从实际到站到关门完成计算，晚到也必须满足；不额外加开门时间；首尾不加全局 minimum。该停站修复是本方案复用实际执行链的依赖，不可复制旧计时错误。

## Findings

### 现有 HTTP 合同与具体问题

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt:782-904` 已处理 lineId、方向、子区间、exact destinationIds、有序 viaIds、stopStationIds、AUTO/CONTINUE/REVERSE 起始方向、已编译 timetable/service/run 线路速度和所选 profile。应保留这些输入校验和方向歧义拒绝，不换成任意选择第一条路线。
- 同文件 `905-924` 将每条真实路线压成长度、长度加权中点坡度、是否终点停车，丢掉沿途几何和约束。
- 同文件 `930-937` 调用 `IsolatedLineSimulator.simulate`；`963-976` 返回 lineRevision、totalTravelSeconds 和逐相邻站 leg 的 length/travelSeconds/peak/stoppingError。
- `IsolatedLineSimulator.kt:32-66` 只是 DTO 适配，委托 `DetailedTrainTravelSimulator`，并没有真正隔离 World。
- `SimpleATO/.../simulation/DetailedTrainTravelSimulator.kt:78-137` 按连续 PASS 合成一段运动；每 STOP 重置局部距离/时间但保留积分车辆状态。PASS 事件是插值穿过累积距离；STOP 是速度零且在停车误差内。没有站台程序、门动画、Dispatcher 阶段、信号/滚动资源和实际每车曲线坡度。

### 已有可复用的真实骨架

- `IsolatedTimetableSimulationFactory.kt:111-196` 的 capture 校验 profile assignment、精确解析 profile、独立车厢蓝图、速度安全、实际 admissionRetryTicks、全部必需 domain features、扩展和 stationCallResolver；快照只复制静态设施，不复制 LIVE 车、锁、票据。
- 同文件 `199-274` 的 create 装配真实 SimulationTrainOperationGateway、HandleTrainActuator、SimpleATO、Dispatcher、signalRestrictions、inspection 和资源准入。
- 同文件 `278+` 的 seed 按所选 profile 创建目标车辆，并按 acquisition/check 确定唯一合法放置。**不能自行补默认车型**；请求缺失或 profile 不可解析应失败。WORLD_TRAIN 是捕获其真实配置而非把正在运营的列车搬入模拟。
- `TimetableHttpBridge.kt:551-602` 的函数模拟已经具备 compile→normalize→capture→factory.create.use→simulate 的完整模式，包括 required/prepared domain feature 接线。单运行线应复用同样工厂配置，不能传默认 empty feature provider 后声称拥有信号。
- `SimpleATO/.../simulation/IsolatedTimetableSimulation.kt:118-229` 是唯一实际 tick 循环：50ms 物理帧、发布、共同领域 runtime、Dispatcher、提交。应继续使用它，不在 Bukkit 新写 physics tick 循环/红灯兜底。

### 现成取样接口及缺口

- `TimetableDispatcher.kt:184-195,505-519` 提供 `observeWaypointProgress(serviceRunId,cycleIndex=0,listener): AutoCloseable`。
- `TimetableDispatcher.kt:4011-4060` 按 Mission leg 顺序报告 waypoint、pass 与 timetableElapsedTicks；STOP 需实际停止及既有授权校验，PASS 依据真实 mission progress。回调必须有界、只采样；订阅 finally 关闭。
- `BukkitPlugin/.../IsolatedTimetableSimulationFactoryTest.kt:685-711` 已有真实隔离世界注册 listener 的样例，并验证同列车跨线路限速与不污染源 World。
- 现有 reached 事件未带 callId/当前 runSegment 或 leg index；会包含 via 路点。采样器必须按请求的有序 calls/vias 消费，不能按 waypointId 建 Map 覆盖重复出现，也不能把任意 via 当乘客站。
- 当前没有对称的“发车计划门/关闭完成/开始运动”精确事件。`TimetableServiceRunSnapshot`（:198-236）只有当前状态，不带逐站到发时间历史；taskTimings 是 PROGRAM START/END，不是车站出发。
- `SimpleATO.kt:1076-1078` 的 DEPARTED 是越过停车位置容差后的站台通知，不是关门完成或计划 departure。`TimetableDispatcher.kt:1644-1655` 的 DEPARTING 要求站外授权、:5679-5681 的 recordScheduledAction 在提交 Mission 前，二者也不是同一个起点。
- 推荐在既有 Dispatcher/stop runtime 的真实 gate 提交点加入一个窄的可选计时观察通知（或扩展既有 progress 事件），含 call identity、事件 kind、timetable elapsed tick。复用 owner 和订阅生命周期，不维护产品长期历史，不依赖解析 message/状态轮询猜事件。真实物理 first-motion/DEPARTED 可以作诊断，但不能替代计划时间边界。
- 峰值速度和最终停车误差仍需从真实 published frame/ATO 停车目标采样；不能为了保留响应字段填写 0。可在共用 simulate step 后加入可选、owner 内、默认无分配的采样回调，供本次运行累积最大值和误差。不要开放任意外部修改 tick 循环的框架。
- 已有 PASS reporter 使用 `POSITION_TOLERANCE_METERS`（:4024）。这是现存事件判定口径，不应在本任务暗中叠加另一套投影；验收须明确与全局共用事件的一致性。若后续认为该 PASS 判定需另修，应明确归属，不在取样器做补偿。

### 不能直接将未知计划全部设 0 然后按 1200 阈值运行

- `IsolatedTimetableSimulationRequest:58-67` 要求 maxTicks 在 period 内，threshold>0；主循环 :188-199 每 tick 检查 observed lateness，先于完成判定。
- 所有 sequential action `At(0)` 可表达“前一个实际完成后尽快执行”，但它不是真实排图计划；长运行线会被默认 1200 假延误中断。
- 函数模拟已有明确解法：`TimetableHttpBridge.kt:594-596` 用 `delayThresholdTicks = maximumSimulationTicks + 1`，仍保留 max tick horizon。单运行线测量可沿用，**只用于无已知时刻的测量请求**。这不等于放宽全局验证阈值，不得用于宣称原全局模拟已通过。
- 使用安全整数/有界 horizon 计算 period 和 threshold，预先检查 `+1` 溢出。若达到 horizon、ATP/运行失败或取消，返回明确失败/limit，不用已完成一部分 legs 回填成功。
- `StationStopProgramRuntime.kt:204-207` 当前 STOP/ORIGIN 必须有 plannedDepartureTick，null 会失败。只把所有到发字段删掉不够；“未知到达”需要一个明确但很小的测量时间构造，见下文。

### 最小推荐方案：一辆车、一个 World、一条 sequential measurement run

1. 保持 HTTP 单运行线入口与现有路线/方向校验，将结果交给一个小的 `LineSimulation` 请求/采样适配器。工厂、物理、ATO、信号、资源、车门执行全部复用，不增加另一套运行器。
2. 捕获同一完整设施快照和选定车辆。合成一个 service/run，保留基础设施 lineId/cap、精确 calls/vias、停车偏移/门侧、STOP/PASS 身份与实际静态计划上下文。所有非初始移动按顺序执行，不需要先估算到达时刻。
3. 请求必须增加 Reset 生成所需的 `firstDepartureTick`、`fixedDwellTicks`，以及当前运行线必要的停站选项/共享程序快照。现行 `SimulationProfile`（`WebUI/frontend/src/types.ts:67-73`）和 `lineSimulationRoute`（`authoring.ts:237-245`）**没有 fixedDwell、minimum、station programs、stopping offsets/door overrides**；只换后台算法不补这些输入，不能声称与实际相同。
4. 起点保留所选 firstDeparture；中间未知计划可用一个已到期的非未来占位 tick，不能拿旧运行线尚未 Reset 的到发时刻约束测量。实际每个 STOP 的测量目标为 `actualArrival + fixedDwell`，准备/关门运行同一个已修复的实际到站 deadline 机制，不另建关门估时器。
5. 一个可收敛的实现方式是在**仅合成测量输入**中，把每 STOP 的局部停站下限设成 fixedDwell，计划 tick 为已到期值；共享 runtime 按 `max(计划目标, 实际到站+局部下限)` 调准备及 final gate。前置校验 fixedDwell >= 用户全局 minimum，不能偷偷取 max 改用户值。此处 fixedDwell 仍是 Reset 希望生成的总停站间隔，并不是把产品的两项字段合并。该方式须在 design 中明确标成测量输入变换，不写回用户站点配置。
6. 若自定义站点程序/门操作无法在 fixedDwell 内完成，要记录真实完成时刻并给出明确不可实现原因；不能在响应中把该额外停站藏到“纯运行秒数”，也不能擅自把固定停站改长。可以先限定能完整采集并一致回填的请求范围，但不能回退到旧平均坡度算法。
7. PASS 与 STOP 连续运行，不能逐站新建车/World 或让 PASS 变零速。停车点的真实轻微欠停、车辆制动/动力历史、车门状态、同车资源占用和信号状态均自然保留。
8. 终点取实际 arrival 完成区间测量，不让终到后的开门/折返/后继运行混入该区间时间。首站准备和 acquisition 也不能偷偷混入第一个区间；必须由一致的起点事件/首 departure 目标明确归属。可在共有 simulate 的采样接口观察“全部目标已采集”作为窄的成功结束条件，随后仍按同一安全 close 清理，不能强关未退出 worker。

### travelSeconds 与 UI Reset：必须先明确算式，不能只改调用

当前 `authoring.ts:248-273` 的回填式：首 departure 保持；每 leg arrival = 前一 cursor + ceil(travelSeconds*20)；中间 STOP cursor = arrival + fixedDwell；PASS 不加，终点不加。旧 simulator 的 travelSeconds 是从运动组起步到 STOP/插值 PASS，不含门和站间停站。

建议响应以**整数 tick 事件**为权威，保留 travelSeconds 仅用于显示或薄适配，不再把整数采样转秒再 ceil；至少包含有序 call 的 arrival/pass offset，以及 STOP departure 目标与实际 gate 完成时间，以便检测 interval 不可实现。

- 区间计划计时锚点：STOP 起点的已验证 departure 目标（到站+fixedDwell，且实际关门/程序已可满足）；首站是指定 firstDeparture；PASS 起点则是同一真实 crossing tick。
- 终点：下一 STOP 的实际到达，或下一 PASS 的实际 crossing。
- 红灯/资源等待发生在 departure 目标之后，仍属于到下一站所需时间，**不能以 first-motion 或 departureGranted 为起点把等待扣掉**。
- 若 STOP 实际门/程序晚于目标，响应明确区分“停站间隔不可实现”，而不是声称固定停站可行、把晚关门偷记运行时间。
- 同一连续 run 内若全部固定停站可实现，则 `arrivalNext - departureTarget` 与原 Reset 加 fixedDwell 公式可以无损复现观测序列；实际 publication/阶段 tick 必须包含在观测，不额外每站手加常数。
- 最稳妥是 Reset 直接采用服务器返回的经检验整数事件时刻（整体平移只允许严格无绝对时间依赖时），同时保留 firstDeparture 不变。自定义 WAIT_UNTIL/事件依赖时间，不能测在 0 后随意整体平移冒充相同执行。

这里需主设计明确选择“保持旧 legs 合同但增加精确 ticks/验证”还是“直接返回 call timings”；本研究不擅自确定用户未审阅的 API。无论选哪种，不能仅用 arrival 差值当 travelSeconds，因为那会将 dwell 加两次。

### 时间窗口、取消和隔离

- `ProgramSimulation.kt:225-244` 已有 bounded executor/future.get(timeout)/cancel(true)/中断恢复；可以把 helper 名称和消息参数泛化到两种模拟，不新建线程池。
- `IsolatedTimetableSimulation.kt:162-171` 每 tick 检查中断；:231+ 的 close 先确认 worker 终止再释放 operation/features。沿用 `.use` 和监听器 finally close；timeout 返回不证明 worker 已退出，测试须确认无资源泄漏。
- 使用明确 finite simulated horizon（运行线允许的最大测量 tick）与用户已有墙钟 timeout，两者不能混为一个参数。包含真实绝对 startTick/WAIT_UNTIL 的请求需覆盖 period；不能只按一日默认让跨日运行线无原因中止。
- live capture 在 World owner，耗时模拟在既有 executor。结果采用前仍校验源 revision；现入口只校验 lines revision（HTTP:955-960），无法证明轨道/信号/profile 同时未变。最低限度结果绑定实际 snapshot 身份，前端沿既有 generated-input/owner epoch 丢弃过期请求；若承诺捕获后最新设施不变，须使用各真实现存 revision，不凭 lineRevision 冒充所有设施版本。

### 删除/保留范围

- 必删排图消费链：HTTP 的 `LineSimulationSnapshot` 简化 legs、平均坡度计算、`IsolatedLineSimulator.simulate` 排图调用；新失败不能回退该估时。
- `rg` 当前生产引用：`DetailedTrainTravelSimulator` 只被 Bukkit `IsolatedLineSimulator` 调用；`IsolatedLineSimulator` 只被 HTTP 单线入口调用。其余引用为自身测试，没有查到独立诊断命令或其他产品 consumer。
- 因此不能假称已有不可替代诊断用途而保留整套旧包装；若无明确用途，移除 wrapper/旧 DTO 及只验证其自身的测试，替换为真实隔离链测试。
- 纯 `DetailedTrainTravelSimulator` 可作为明确标注的车辆控制算法离线试验保留，但仅当主设计决定确有测试研究价值；绝不能继续显示成排图模拟或作为备用路径。不需要为删除它牵连删除共享 TrainDynamics、VehicleControlExecutor 等真实执行仍使用的组件。

### 必需回归与准确性证据

- 同快照、同 profile、同 run：线路结果回填后，仅该 service 的全局回放逐站事件一致；最终门禁保留原 1200 全局阈值，不能用测量阶段的大阈值当验收。
- 局部 rail cap、曲线、坡度变化、真实信号及 rolling admission 都影响实际运行时间；至少一项旧平均模型无法表达的约束必须有对照。
- 首站 AUTO 双方向歧义、REVERSE 实际放置、停车偏移、明确 via、PASS 连续速度和重复 waypoint occurrence。
- fixedDwell 包含开关门；晚到 min 从实际到站起；首尾不加；固定间隔不足真实门时清楚拒绝，不修改用户时刻。
- 不依赖旧 arrival/departure；长于 1200 tick 的测量不会因占位 schedule 提前失败，但 horizon/永久红灯能以 limit 结束。
- 到发观察事件区分 door/gate、departureGranted、first-motion、DEPARTED；资源等待不能从 travel 时间扣掉；连续多站没有固定每站漏 tick。
- timeout/cancel/失败/create失败均释放订阅和 isolated resources，源 World 不增车、不改路权/信号状态；feature 缺准备器/profile 缺失明确失败。
- 真实源数据回放资产应固化为可重复 fixture 或可审计输入说明；不猜测缺失的历史浏览器车辆指派。

## Files Found / Minimal Change Candidates

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt` — 替换 line simulate capture/run/result，保留请求路线和速度校验。
- 同目录 `IsolatedTimetableSimulationFactory.kt` — 原工厂复用；不要复制实现。
- 同目录 `ProgramSimulation.kt` — bounded execution 和 horizon 模式可借用/小范围抽取。
- 同目录 `IsolatedLineSimulator.kt` — 无真实隔离能力的旧适配 consumer，应删除或替换。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt` — 必要的可选观测/测量结束缝隙，继续唯一 tick 循环。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt` — 既有 waypoint 进度和窄的精确时刻观察。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/StationStopProgramRuntime.kt` — 依赖已规划的真实到站 deadline 修复；不复制第二套停站执行。
- `WebUI/frontend/src/{types.ts,api.ts,authoring.ts,DutyEditor.tsx}` — 传完整测量输入、精确 ticks 回填、原 owner/input 过期保护。
- Tests: `IsolatedTimetableSimulationFactoryTest.kt`、`ProgramSimulationTest.kt`、`LineSimulationMaximumSpeedTest.kt`、新真实 line integration tests、`authoring.test.ts` / 相关组件输入回归。

## Related Specs

- `.trellis/spec/javarailways/simpleato/simulation.md` — isolated lifecycle、真实领域阶段、features 与结果；详细 preview 的“用于排图”边界需修订。
- `.trellis/spec/javarailways/simpleato/lines.md` — 精确路线、方向与线路速度 owner。
- `.trellis/spec/bukkit-plugin/backend/simulation-car-behavior.md` — 捕获真实车厢行为、动画完成和资源释放；文档前段有旧效果启动完成表述，末尾“门动画完整时序”已明确须真实播放完成，不可借旧段落简化。
- `.trellis/spec/webui/frontend/studio-authoring.md` — fixedDwell/minimum 独立、Reset 不变更首 departure、异步过期输入不得写回。

## External References

无。方案由仓库已有接口/调用链推导；未依赖外部库新能力。

## Caveats / Not Found

- 没有运行编译/测试；本方案是代码研究的设计候选，不是已验证实施。
- 没有现成发车精确计时接口，也没有直接支持“未知到发”的完整 line simulation API；不能声称只改一行调用即可复用。
- 未恢复历史浏览器 profile，原数据只能在明确指定的 profile 条件下对照。
- 仍需主设计确定返回 call timings 的 API 与自定义程序超出 fixedDwell 的用户表现；不应通过隐式补时掩盖这一决定。
