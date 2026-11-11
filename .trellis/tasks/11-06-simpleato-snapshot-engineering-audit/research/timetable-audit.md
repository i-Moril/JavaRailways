# Research: SimpleATO timetable / simulation engineering audit

- Query: 审查时刻表、停站程序、隔离模拟、固定编组和相关公共接口的状态来源、语义转换、失败清理与工程冗余。
- Scope: internal；只读审查，不修改产品代码，不启动/停止测试服，不提交或部署。
- Date: 2026-11-06
- Baseline: 主会话指定 `b89b0d5d`；本文行号由当前文件 `nl`/`rg` 复核。生产文件共 35 个、14,114 行，全部完整阅读。测试按风险定向阅读，不能声称全部测试代码已读或已通过。
- 方法：`javarailways-code-review` full + missed-findings；先完整文件，再追生产入口/状态转换/失败清理，最后对照测试是否真的覆盖所述触发条件。
- 已知项去重：已阅读 `known-14219-issues.md`。其中 K01–K03 不在本文重复计数；本审查不将所有运行延误都归因于快照问题。

## Findings

### T01 / P2：转交人工控制后，调度诊断一直读取已脱离 controller 的 ATO 位置

- 定位：`timetable/TimetableDispatcher.kt:7273–7275`；转交 `1105–1113`；人工模式实际 tick 读取 `1431–1434`。
- 触发：正常运行交路 → `enterManualRoutedControl` → 安装/绑定 ManualController → 人工移动；FULL_MANUAL 也受影响。
- 生产链：转交主动把 `run.ato` 从 train controller 脱离。tick 已知道应在此时从 Core 捕获当前位置，但 `ActiveRun.snapshot()` 不区分控制模式，仍取 `ato.publishedState.trainSnapshot.head`。旧 ATO 不再随人工 controller 的物理更新发布，因此状态头位置可长期停留在转交时，不只是 K01 的一个 epoch 滞后。
- 影响：`TimetableServiceRunSnapshot.trainHeadRailId/trainHeadArcLengthInAbMeters` 与实际车头、同一状态的进度字段不一致；影响管理页面定位和人工接管诊断。没有据此断言 Basic 会放行错误资源。
- 契约：Core 是物理状态 owner；诊断运行状态应反映当前车，不能使用已经停止更新的另一 controller 副本。
- 测试证据：`TimetableDispatcherTest.kt:2032–2191` 的人工行驶/切换测试确实让 train 移动，检查模式、终态和 grant，却不比较 status 头位置与 Core frame。
- 最小修正方向：snapshot 从同一次捕获的当前 Core frame 获取物理字段；与全局 TrainSnapshot 收敛一起做，不再增加模式专用副本。
- 相关但不扩大结论：`canBeReplaced/isStoredTerminal` (`7227–7236`) 也读旧 ATO speed。已证存在这个消费者；本文未证明其可绕过后续 controller、实际停车或资源检查，故不另报安全漏洞。
- 证据级别：静态生产可达；未新增动态复现。

### T02 / P2：SelectStop 的两套编译转换丢失停站程序，使折返后的后继路线证明错误

- 定位：`timetable/Timetable.kt:917–920`；`timetable/StructuralTimetableValidation.kt:341–351`。
- 触发：`TimetableAction.SelectStop(stopPrograms=...)` 的实际执行程序包含奇数次 Reverse，后面还有移动/结构事件。模型明确允许此字段 (`Timetable.kt:254–268`)；此结论不依赖当前 WebUI 必定生成该组合。
- 普通编译链：SelectStop 被临时转换成 CallStation + GoTo，以复用候选及后继校验；构造 CallStation 只传前五个参数，遗漏 stopPrograms。正常 CallStation/移动在 `826–844` 从程序 `reverseParity()` 更新下一段方向；这个转换于是让后继按未折返方向校验/保留。
- 结构编译链：同样转换又遗漏 stopPrograms，且 candidate.copy 未设置 `pendingStopReverse`。直接 CallStation 路径在 `StructuralTimetableValidation.kt:145–151,313–314` 会设置该值，`514–516` 用它反向 envelope；SelectStop 没有。
- 运行链：Dispatcher `5685–5686` 却完整保留 `action.stopPrograms`，station call 到达后 `1599–1605` 启动该程序，实际执行 Reverse。因此编译保留的后继方向/几何与运行不一致；有效折返候选可能被拒绝，或具备其他可达方向时留下与实际方向不匹配的证据。
- 契约：同一停站程序必须在静态方向传播与真实执行中具有相同语义。不能靠运行时重新寻路或兜底恢复丢失的语义。
- 测试证据：`TimetableDispatcherTest.kt:3132–3347` selection fixtures 未传 stopPrograms；其中折返场景是在 SelectStop 后另放一个顶层 Reverse。`StructuralTimetableValidationTest.kt:430–465` selection fixture 同样不含程序，不能覆盖本问题。`TimetableBranchValidationTest.kt` 检查候选后继，但没有 station-program reverse 组合。
- 最小修正方向：候选转换保留 stopPrograms，并让结构候选沿已有普通 CallStation 逻辑设置反向语义；不要为 SelectStop 另写一套折返器。加一个有/无结构事件的 SelectStop + 站内 Reverse + 返回移动测试。
- 证据级别：静态生产模型可构造、转换/执行不一致；未现场复现，不声称这是 Tick14219 的原因。

### T03 / P2：force discard 遗漏普通 extension ticket 的取消，导致有副作用的异步工作失去 owner

- 定位：`timetable/TimetableDispatcher.kt:2127–2161`，入口 `2085–2105`；已有严格取消实现 `5008–5039` / `5042–5074`。
- 触发：取车成功，首 Mission 前开始一个返回 PENDING ticket 的普通 `TimetableAction.Extension`，管理员 force-discard 该表。
- 生产链：`forceDiscardTimetable` 只预检结构事件和 active Basic admission。首 Mission 前没有 admission client，因此通过。`discardRun` 取消 station-program/door/legacy operation，却完全没有调用 `cancelExtensionAction`；之后移除 controller、listener、run。provider ticket 继续存在/执行，但 Dispatcher 不再轮询或拥有它。
- 已追到外部入口：`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/JavaRailwaysCommand.kt:991–1006` 的 `/jr timetable reset <id> force confirm` 直接调用该方法，没有先取消 extension；之后 `1021–1023` 还会重新 begin/schedule。
- 真实支持的前提：`TimetableDispatcherTest.kt:142–204` 已有取车 → PENDING extension → GoTo 的 fixture，并明确断言等待期间 missionRevision=0、车不动、extension 只启动一次。不是虚构一个校验无法接受的 service。
- 契约：本模块已有 `cancelPendingOperation` 的 cancel + terminal confirmation 规则；外部效果不能因运行记录被清除而失去生命周期 owner。
- 影响：自定义 action 可能在“重置完成”后继续变更状态，后续重开交路还可能重复提交同类效果。具体副作用由 provider 决定，不宣称某一现成插件已发生该事故。
- 测试缺口：`TimetableDispatcherTest.kt:2865–2975` 检查 stopService 对 extension 取消的严格确认，但不走 discard；`TimetableDispatcherSharedAdmissionTest.kt:152–176` 检查批量 force discard 的 grant preflight，也没有 extension。
- 最小修正方向：复用统一取消/确认路径，成功后才移除 run；取消失败保留 owner 和诊断。legacy ticket 的 `runCatching { cancel() }` 静默忽略也应随这一清理入口核对，但本文不额外声称其所有状态可达。
- 证据级别：静态生产可达；已有相同等待状态 fixture，未新增动态复现。

### T04 / P2：启动一个不存在的 service 会留下新 clock，下一周期自动启动该表的其他 scheduled services

- 定位：`timetable/TimetableDispatcher.kt:702–706`；clock 默认值 `6859–6868`；消费者 `1870–1874,1942–1987`。
- 触发：世界中有一个尚未运行、没有 clock 的有效时刻表；调用 `startService(world, timetableId, "不存在的service")`。
- 生产链：先 `timetableClocks.getOrPut` 写入 epoch/周期，后查 service 并返回 Failure。新 clock 的 nextCycleIndex 默认是 1，autoStartDisabled=false；后续 tick 的 startDueCycles 枚举该 clock，到周期 1 即遍历该表全部 scheduled services，调用 startServiceForCycle。错误请求不是纯失败，实际开启了未来自动运行。
- 已追到外部入口：`JavaRailwaysCommand.kt:935–965` 的 `/jr timetable start <id> <service>` 只先检查表存在，直接调用 startService，不先创建 epoch 或验证 service。因此误输 service 是真实命令可达输入。
- 影响：用户看到“service not found”，却留下调度副作用；例如误输 service 后，一个完整周期后自动启动其他正常交路。
- 契约：不存在目标的请求不应提交可触发运行的调度状态；预检应先于发布。允许正常 startService 建 clock，不等于允许无目标的失败调用建 clock。
- 最小修正方向：至少在 service 存在性/无需启动的拒绝项验证后才写 clock；确认其他失败点的部分提交是否有明确等待语义，不用一个大 rollback 系统代替前置检查。
- 测试缺口：已查看 dispatcher start/自动周期测试名称和相邻范围；未找到 nonexistent-service + no prior clock + later-cycle 的断言。本文没有运行新测试。
- 证据级别：静态可构造、全调用链明确；非服务器现场观察。

### T05 / P3（工程清理）：已经禁止 runtime conditional 的编译入口，仍维护整套不可由合法计划进入的运行时分支执行

- 定位：`timetable/Timetable.kt:411,420–447` 明确拒绝 conditional activation 与任意 runtime Conditional；Dispatcher `4842–4863` 仍计算条件、选择分支、写 branch trace；`1777–1821` 仍维护 conditional activation false/unavailable/error 策略。
- 证据：`TimetableBranchValidationTest.kt:41–48,97–105` 明确要求在路线扩展前拒绝 runtime branches；`StructuralTimetableValidationTest.kt:400–410` 同样拒绝。合法当前计划在进入这些运行分支前就被验证阻止。
- 工程影响：执行器/规范同时表达两种相互冲突的合同；维护/测试读者可能误以为仍支持 runtime branching。当前 `dispatcher-runtime.md` 的“定时分支与真实停车点选择”旧说明与这条编译门禁不一致。
- 删除边界：可评估移除已证不可由合法运行计划进入的 Dispatcher Conditional executor 与对应运行条件激活分支，并在边界明确拒绝。不应把整个 cursor、address、condition DTO、extension registry 或 structural branch trace 全判死代码：SelectStop 仍需要有限候选 continuation、stop decision history；公开 registry/持久化边界另有消费者和迁移约束。
- 重要限定：T04 的失败 clock 泄漏等异常入口仍可能走到 activation evaluation，因此“合法计划不可达”不等于当前代码绝对无法调用该函数。先修发布边界，再按生产消费者缩减；不建议只删条件验证来让旧执行器重新可达。
- 证据级别：工程合同冲突/窄范围冗余，不作为已观察运行故障。

## 待验证候选（不并入确定行为缺陷）

### C-T01：结构证据将整个 TopologyState 根对象身份作为有效性条件，可能比实际依赖合同更强

- `StructuralEventWindow.kt:25–30` 要求 currentTopology === topology；`StructuralOutputCorridor.kt:274–276` 同样要求；contact 在 `StructuralCouplingContact.kt:23,55` 也要求 exact root。
- Dispatcher 应用保护收集相关 rail/node/resource (`5178–5213`)，普通 retained 路径用 `preservesRails` (`5200,5300`)；Split 在 `6001–6002` 消费上述 window/envelope。
- 待验证链：应用结构表后，在完全无关的轨道组件合法编辑并发布新 root；相关 rail/车体未变，但结构作业拒绝 window/corridor。需要真实 edit registry + 分离组件的 test 确认生产编辑是否确实允许、哪些 Core proof 需要重新绑定。
- 当前结构注释明确刻意禁止另一 topology root，因此这里涉及合同过强，不是直接删除比较即可。不能用删除安全证据检查作为修复；应讨论是否以实际 dependency 保真重用或显式局部重绑定。
- 没有现场复现，没有测试此编辑链，不把所有拓扑检查都称为过度设计。

## 状态、清理与语义审查结论

- 调度 owner：start/apply 创建 clock、retained routes 和 edit dependencies；acquisition 建 run/controller；实际 Mission 通过 Basic admission 后推进；wait/doors/extension/stop-program 保持自身 ticket；terminal 不是 grant 释放，Basic 仍按尾部释放；manual transfer 与 stop/replan/discard 是不同出口。T03 就是出口清理分叉造成的遗漏。
- 固定编组：`FixedFormationOperation` 依次 prepared → ledger prepare → sealed physical publication → Held；`FixedFormationOutputHandoff` 先验证全部输出、安装、提交 ledger，再按各输出 grant 解除 movement hold，HOLD 仍保留独立 fence。失败进入 recovery-required，不假装可回滚已经发布的车体。没有证据将该不可逆发布保护整体删除。
- 停站：`StationStopProgramRuntime.kt:56–57,88–104` 的 minimum dwell deadline 从实际到站开始，STOP 才有此 minimum，origin/terminal 不追加；准备启动需满足计划发车减 lead 与实际到站 minimum 减 lead 两个下界，最终 gate 仍等待任务、车门、时间。不是“开门完成后再加一次 minimum”。已完整读其边界测试，未在本轮找到新的已证停站时长 bug。
- post-commit completion 只记录完成证据，下一 tick 仍执行任务/门禁；测试覆盖 400 与 401、后续同步/相对等待不可回溯以及 join。不能仅因这段代码处理边界就断言是多余兜底。
- 隔离模拟：创建独立 Core/Basic/SimpleATO runtime，真实 physics publication 后进入共同 owner phase，再更新 observation/dispatcher；退出先确认 physics/generic workers 结束，再关 feature 与 operation resources。此目录没有另写一套局部运行线物理模型；Bukkit 运行线模拟的创建/回填入口不在本子审查完整覆盖范围。
- 真实循环库存分析显式区分 PROVEN/IMPOSSIBLE/UNPROVEN，时间与容量没有冒充“库存见证=全部可运行”；其界限与有限搜索是有明确合同的，不以代码复杂度直接判过度设计。
- 新旧双状态主要应由主审 TrainSnapshot 分析收敛。Station/program/formation 的业务状态不能被 Core frame 替代；Core frame 只能替换物理事实，不能推导任务完成、时刻表 clock 或外部 ticket。

## Files found / 逐文件覆盖

以下 `timetable/`、`api/` 均相对 `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/`。全部状态为完整阅读（不是只搜关键词）；每项括号内为文件职责。共 35 文件，14,114 行。

- `timetable/CurrentUnitCirculation.kt`（owner 捕获实际单元/车体/ledger）。
- `timetable/FixedFormationOperation.kt`（Core/Basic/ledger 不可逆编组协调）。
- `timetable/FixedFormationOutputHandoff.kt`（输出安装、持有与发车交接）。
- `timetable/FixedUnitCouplePlan.kt`（固定单元重联输出计划）。
- `timetable/FixedUnitSplitPlan.kt`（按实际 HEAD 单元数解编计划）。
- `timetable/FormationEvent.kt`（事件 catalog、参与/输出归属及循环校验）。
- `timetable/FormationOutputMetadata.kt`（输出元数据合并/拆分规则）。
- `timetable/FrozenRouteEditProtection.kt`（完整冻结路线编辑保护）。
- `timetable/InfrastructureDependencyService.kt`（依赖写入边界）。
- `timetable/InitialPosition.kt`（初始放置 selector 模型）。
- `timetable/ManualNavigation.kt`（命令解析和临时表构造）。
- `timetable/RetainedTimetableRoutes.kt`（已验证路线关联键/证据）。
- `timetable/StationPreparationLead.kt`（有限、类型化提前量表达式）。
- `timetable/StationStopProgramRuntime.kt`（异步停站指令/时间/门禁）。
- `timetable/StationStopPrograms.kt`（不可变程序、任务符号、reverse parity）。
- `timetable/StructuralBranchTrace.kt`（有限分支/站台选择身份）。
- `timetable/StructuralCouplingContact.kt`（Core 接触证明限定）。
- `timetable/StructuralEventWindow.kt`（结构事件停稳窗口）。
- `timetable/StructuralOutputCorridor.kt`（完整车体/输出延续几何证明）。
- `timetable/StructuralRouteBindings.kt`（保留路线 metadata 身份）。
- `timetable/StructuralTimetableValidation.kt`（跨固定编组的来源/后继传播）。
- `timetable/Timetable.kt`（表/动作模型、普通路线与候选校验）。
- `timetable/TimetableBranchGuard.kt`（程序规模/允许动作/任务边界）。
- `timetable/TimetableDispatcher.kt`（完整 7,316 行，运行状态与生命周期）。
- `timetable/TimetableDumper.kt`（诊断 JSON 投影，不是恢复 reader）。
- `timetable/TimetableInitialPositioner.kt`（放置事务、重叠检查、失败恢复）。
- `timetable/TimetableProgramCursor.kt`（地址与 continuation 游标）。
- `timetable/TimetableRunMetadata.kt`（运行段事实、单元车次与显示上下文）。
- `timetable/TimetableUnitCirculation.kt`（固定图库存搜索及证据界限）。
- `timetable/TrainInfo.kt`（有限运行车信息投影字段）。
- `timetable/simulation/DefaultSimulationDomainTarget.kt`（隔离 feature 安装/退休）。
- `timetable/simulation/IsolatedTimetableSimulation.kt`（完整隔离世界/运行/资源清理）。
- `api/timetable/TimetableCirculationSummary.kt`（只读库存分析结果）。
- `api/timetable/TimetableExtension.kt`（条件/action provider、ticket、隔离捕获合同）。
- `api/simulation/PreparedSimulationFeature.kt`（插件隔离特性与资源接口）。

### 测试覆盖的真实边界

下列路径相对 `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/`。没有运行本地测试或新增探针；主会话的验证结果应另记，不能把“读过”写成“通过”。

完整读过：

- `timetable/TimetableBranchValidationTest.kt`（145 行）。
- `timetable/StationStopProgramRuntimeTest.kt`（433 行，输出截断范围另行补读）。
- `timetable/StationPreparationLeadTest.kt`（32 行）。
- `timetable/StructuralOutputCorridorTest.kt`（245 行）。
- `timetable/FixedFormationOperationTest.kt`（193 行，截断部分补读）。

按风险定向读过，未覆盖整个文件：

- `timetable/TimetableDispatcherTest.kt`：1–245、1930–2310、2858–2977、3080–3441；另外搜索全文件测试名称/extension/selection/cancel。人工接管、首 Mission 前 extension、stop 取消和选站 fixtures。
- `timetable/TimetableValidatorTest.kt`：1–130；provider 校验及 retained routes；其余仅搜索相关关键字。
- `timetable/StructuralTimetableValidationTest.kt`：400–551；runtime condition 拒绝、选站与结构后继、metadata 漂移；其余仅搜索关键字。
- `timetable/TimetableDispatcherSharedAdmissionTest.kt`：130–260；force discard preflight、drain 与保护；其余仅搜索关键字。
- `timetable/simulation/IsolatedTimetableSimulationTest.kt`：1–294、735–936；任务测量/迟到、物理失败发布、取消等候、真实 fixture 构建；其余仅索引测试名称。

已列入 inventory，但本子审查未完整阅读（不以文件名推断通过）：

- `timetable/CurrentUnitCirculationTest.kt`
- `timetable/FixedUnitCouplePlanTest.kt`
- `timetable/FixedUnitSplitPlanTest.kt`
- `timetable/FormationEventTest.kt`
- `timetable/FormationOutputMetadataTest.kt`
- `timetable/FrozenRouteEditProtectionTest.kt`
- `timetable/GrantingRouteAdmissionTestContext.kt`
- `timetable/LineMaximumSpeedResolverTest.kt`
- `timetable/ManualNavigationTest.kt`
- `timetable/RollingStationAdmissionTest.kt`
- `timetable/StructuralOnwardRoutingTest.kt`
- `timetable/TimetableCirculationSummaryTest.kt`
- `timetable/TimetableFormationDispatcherTest.kt`（仅定位 force discard/metadata/topology 测试行）。
- `timetable/TimetableInitialPositionerTest.kt`
- `timetable/TimetableProgramCursorTest.kt`
- `timetable/TimetableRunMetadataTest.kt`
- `timetable/TimetableStationCallDispatcherTest.kt`（仅搜索 stopPrograms/operation cancel 测试行）。
- `timetable/TimetableUnitCirculationTest.kt`
- `timetable/TrainInfoTest.kt`
- `api/timetable/TimetableExtensionRegistryTest.kt`

跨 owner 只追踪/搜索而未完整审查：`SimpleATO/.../persistence/TimetableSnapshot.kt` 的 SELECT_STOP stopPrograms capture/restore；`BukkitPlugin/.../scriptworker/Model.kt,Dsl.kt,StationPrograms.kt` 的字段/构造入口；完整读取 `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/command/JavaRailwaysCommand.kt:930–1023` 来确认 start/reset 外部调用。未把这些文件算入完整覆盖。

## Related specs / references

- 已读 `.trellis/workflow.md`；review skill 与 `references/missed-findings.md`。
- 已读 `.trellis/spec/javarailways/simpleato/index.md`、`dispatcher-runtime.md`、`simulation.md`、`timetable-model-and-validation.md`、`through-running-and-unit-displays.md`。
- 已读 `.trellis/spec/javarailways/shared/versioning.md` 与 `ai_tasks/persistence_schema_evolution_rules.md`。本轮不修改数据格式，不新增兼容路径、不升版。
- 没有使用外部网络资料；结论基于当前工作区代码及本任务已有证据。没有用旧 memory 作为当前缺陷证明。

## Caveats / Not Found

- 四个 P2 是静态生产可达或模型可构造的确定代码缺陷，不等于本轮已经动态复现；T05 是工程冗余/合同冲突，C-T01 明确只保留待验证候选。
- 没有重复 Tick14219 回放、没有 counterfactual 修复，因此不能量化它们对当前测试服延误的贡献。
- 没有执行现有测试；完整 suite 由主会话单独负责。读测试不证明测试当前通过。
- 主会话另行通知：现有 SimpleATO suite 在临时 3 GB 堆下通过，788 tests / 85 suites，0 fail/skip；默认堆第一次 OOM。此信息为主会话验证结果，不是本子审查自己执行/检查的测试输出，也不能覆盖上述缺失组合。
- 没有因文件大/nullable 多就报告缺陷；Core frame 替代物理快照不意味着可删除业务任务状态、外部操作确认或结构发布安全合同。
- 研究文件是唯一写入。未改产品、测试、规范、task status；未执行 Git、部署或服务操作。
