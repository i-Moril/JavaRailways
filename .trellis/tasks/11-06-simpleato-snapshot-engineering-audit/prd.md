# SimpleATO 全模块审查与列车快照统一评估

## 目标与阶段

记录已发现的运行故障，回答 Core `TrainSnapshot` 的全部实际用途是否能由 `TrainFrameSnapshot` 覆盖，并审查整个 SimpleATO 模块的行为错误、过度设计与工程质量。审查已完成。用户已在最终四项方案摘要后明确回复“可以”，批准首批 F1–F5 实施：统一物理事实来源，消除错误全路线申请，修正人工状态，并补齐自动驾驶整车限速。不授权提交、部署或数据迁移。

## 基线

- 分支 `codex/ato-per-car-rebuild`，HEAD `b89b0d5d`；业务修复基线 `5d5a4b7a`。
- 原有未提交项：TODO.md、两个 deployment research 文件、三个 ai_tasks 审查文件、tmp.1；全部保留。
- 已确认问题与回放证据见 `research/known-14219-issues.md`，不以历史报告代替当前源码。

## 要求

- R1：先记录帧发布错序、跨快照比较丢弃有效数据、空车长扩大至整条路线的完整因果链。
- R2：清点 TrainSnapshot 的定义、全部生产者/消费者、字段语义、序列化/诊断/API/测试用途；分别回答物理数据覆盖、时序语义与迁移成本，不先入为主保留或删除类型。
- R3：覆盖 SimpleATO 全部生产源码、公开 API、测试/fixture、构建和模块文档；跨模块追踪必要调用链但不宣称审完其他模块。
- R4：检查状态发布、重复事实源、资源与生命周期、运行/模拟一致性、持久化、错误处理与抽象必要性。确定缺陷、待验证风险、可选结构简化分开记录。
- R5：每项发现记录严重性、位置、触发条件、生产调用链、影响、证据等级和最小处置方向。逐文件列明覆盖深度与未检查项。

## 审查验收（已完成）

- [x] 已确认故障具有可追溯源码与回放证据。
- [x] TrainSnapshot 所有仓库调用点已分类，结论不把模型可替换等同于机械替换。
- [x] SimpleATO 文件清单与审查状态完整，无遗漏却声称全覆盖。
- [x] 发现经实际生产调用链复核，不将 nullable、异常或长文件本身当作缺陷。
- [x] 报告区分静态分析、动态复现及未验证项，给出建议修复顺序。
- [x] 除本任务文档与临时探针外不修改任何代码；不提交、不部署。

## 首批修复要求（已批准）

- F1 / K01–K02 / P1：Core frame 成为所有物理字段的唯一来源，删除 TrainSnapshot 类型及字段复制；修正 PhysicsCoordinator 与显式 controller commit 的通知顺序。位置：`PhysicsCoordinator.kt:676–700`、`TrainController.kt:150–174`、`AbstractATO.kt:198–235`、`TimetableDispatcher.kt:1423–1435,3739–3744`。证据与全仓消费者见 `research/known-14219-issues.md`、`research/train-snapshot-role.md`。
- F2 / K03 / P1：有效运行列车使用同一帧 bodyTraversal 得到车身跨度，不再由新旧车头比较制造 null 长度；删除未知长度申请全路线的兜底。真实缺帧/无效车体不得获得新增授权，使用已有错误通道说明原因。位置：`InspectionRuntime.kt:648–663`；同步改正 `entry-clearance.md` 和 `EntryClearanceRuntimeTest:59–60` 中认可错误兜底的合同。
- F3 / T01 / P2：自动/人工接管状态报告都使用当前 Core 物理帧；历史故障仍保留当时帧，不能事后读取最新状态替换现场。位置：`TimetableDispatcher.kt:7273–7275`；证据见 `research/timetable-audit.md`。
- F4 / C01 / P1：自动与手动驾驶均保留车身仍占据轨道的原生限速，只在车尾实际离开后解除；前方限速仍提前制动。位置：`SimpleATO.kt:742–755,839–860,1118–1130`、`ManualProtectionFrame.kt:31–40`、`RailwayControlRuntime.kt:74–81`；动态反例见 `research/control-audit.md` 和 `research/validation.md`。
- F5：不引入第二套物理缓存、备用资源申请器、通用新框架或旧开发 API 兼容壳；不改变停车语义和持久化模型。Core 只管物理事实，SimpleATO 继续管理任务/运行业务状态，Basic 仍是资源授予与释放 owner。

## 首批修复验收（限定修复通过，完整时刻表存在后续车门阻塞）

- [x] A1→F1：真实 PhysicsCoordinator epoch 与显式 commit 均证明 hook 读取本次完整 frame；head/tail/speed/body 来自同一帧，close/stall/失败不出现迟到 Core 发布，扩展 callback 不进入核心锁。
- [x] A2→F1/F3：所有生产与测试引用迁移并删除 TrainSnapshot；人工移动后的 status 位置与 Core 相同；历史帧在后续移动后仍保持原值，诊断不输出完整 render payload。
- [x] A3→F2：完整 Dispatcher→inspection→Basic 路径中，移动车辆有实际车长，滚动边界只受真实净空需求抬高；不因数据缺失跳到路线末端；真实缺帧/无效输入不新增授权。
- [x] A4→F4：短限速轨道位于车身下且无转向架落其上时，自动/手动 reference 与 protection 都有当前限速；尾部离开后解除，前方降低限速仍提前响应；AB/BA 及真实 Basic 设备回归通过。
- [x] A5→F1–F5：相关 Core/Basic/SimpleATO/SimpleSignals 测试、Bukkit 编译/诊断消费者检查通过；用现有本地真实服数据回放比较 Tick14219 请求，单独记录剩余延误，不将原因消除等同全部模拟必定通过。
- [x] A6→F5：规范同步纠正；无 schema 升版、兼容副本、额外申请器、远程写操作或无关源码改动。

A5首轮模块检查3306通过、1个原有opt-in跳过，但回放在Tick2002外部授权假负距离受阻，证据保留于research/implementation-validation.md。追加F6–F8后联合3320通过、1跳过，原输入正常回放越过2002及14219，后于18402遇到车门作业阻塞。原故障对照已完成，不等于完整时刻表通过；详见research/authority-handoff-validation.md。

## 已有交付与规划资料

审查总结见 `research/review-summary.md`；逐文件范围见 `research/coverage.md`；788项原有测试及两项定向反例见 `research/validation.md`。首批技术方案在 `design.md`，执行顺序和验证在 `implement.md`，子代理上下文在两个 jsonl 清单。审查勾选不代表修复完成。

## 追加修复（用户在诊断后明确要求“你修复”）

- F6：Mission 切换、退休及相关过渡期间，仍有效的外部授权必须按当前真实车头在其冻结路线上的位置检查；删除 Mission 不一致时使用路线总长度冒充车头的分支。旧授权不可因新 Mission 生效而被跳过或撤销，不新建备用授权系统。
- F7：SimpleSignals 固定闭塞及保护区域匹配复用已有精确 occurrence 边界；已有端点不得通过往返转换产生另一个近似边界。内部交点仍按必要几何计算，不新增容差或截断越界距离。
- F8：用真实控制器回归覆盖新 Mission 激活、旧绑定仍有效的过渡帧，以及完整/部分授权、真实越界、AB/BA 和重复轨道的适用边界；信号精确端点回归须逐值相等而非误差窗口。
- [x] A7：上述单元/交接回归、SimpleATO/SimpleSignals 完整检查与消费者检查通过。
- [x] A8：原始输入、正常滚动申请和 ATP 的真实全局回放越过 Tick2002，继续核查原 Tick14219。新的独立故障单列，不绕过保护或扩大修复。Tick18402车门作业阻塞已单列，未修改。

本追加覆盖此前“授权坐标修复待确认”的范围。用户随后在确认上一部署遗漏 F1–F8 后明确要求部署；F1–F8 已提交为 `31e4cf17` 并部署，范围与恢复证据见 `research/f1-f8-deployment-2026-11-07.md`。该授权不扩展到未列出的审查项。
诊断证据与实施边界见 research/authority-handoff-repair.md。

## 追加修复：跨车次车门状态静态验证（用户已批准）

- F9：同一确定性交路中，若已执行的停站/显式动作能够证明车门处于开启状态，则在进入 `doorSide=NONE` 的停站、移动或其他现有运行期已经要求车门关闭的动作前，必须存在可证明的 `CLOSE_DOORS`。`NONE`只表示本次停站不执行车门动作，不能清除既有开门事实。单纯换向沿用当前运行期语义，不要求关门；终到开门、换向、下一始发继续使用车门是合法模式。
- F9 的错误必须在 `TimetableValidator.validate` 阶段返回，包含 timetable、service/交路和可定位的步骤地址，并说明前序开门、当前无门动作以及需要在此前显式关门；无效定义不得开始模拟、安装controller或申请资源。
- 终到停站的 `departurePreparation` 不执行，因此其中的 `CLOSE_DOORS` 不得被静态分析当作已发生。独立 `OpenDoors/CloseDoors`、普通非终到停站、跨runSegment的同一service和确定性分支按实际执行语义传播；无法证明的动态/结构交接不伪装为已关闭。
- [x] A9：真实 `106P终到开门 → REVERSE → 115N doorSide=NONE` 定义在115N无门始发步骤的验证阶段明确失败；在该始发前增加显式CLOSE_DOORS后通过。`终到开门 → REVERSE → 有门始发`、初始状态未知的无门停站和普通开关门时刻表保持通过，运行期安全检查保留。

本追加只增加现有validator的静态提示与测试，不自动插入关门动作、不改变StationStopProgramRuntime或Dispatcher车门执行语义，不提交部署。

## 范围外

除已批准的 F1–F8 外，不包含 T02–T05、D1–D4、I01、C02 或其他待验证候选，它们保留在审查总结作为后续批次；不能为通过本批测试顺手扩大修复。尤其不改条件解释器、选站策略、扩展任务清理、车库模型或无关性能缓存。提交、部署、测试服操作另等明确授权。
