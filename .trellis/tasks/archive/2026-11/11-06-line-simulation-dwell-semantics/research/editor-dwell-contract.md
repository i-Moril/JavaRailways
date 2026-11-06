# Research: 编辑器最短停站合同与编译传输审计

- Query: 从 WebUI 编辑、校验、时间轴、脚本生成到 station program 编译，核对最短停站语义及不必要的首尾站/跨运行线注入。
- Scope: internal；只读产品代码，未构建、未提交、未部署。
- Date: 2026-11-06
- Authoritative requirement: 用户本轮明确最短停站为“到站到关门完成的全部时间”，仅用于中间停站；首尾站为简化逻辑不添加。旧 spec 与测试不能反过来覆盖这一需求。

## Findings

### 1. fixedDwell 与 minimumDwell 是两个不同参数

- `WebUI/frontend/src/authoring.ts:78-84`：`minimumDwellTicks` 为文档级字段，缺省 0；目前 `authoringMinimumDwellForRun` 仅区分 PASSENGER/DEADHEAD，不看 call role，这是首尾站误用的共同入口。
- `WebUI/frontend/src/DutyEditor.tsx:115,561,639,648`：`resetDwellTicks` 为组件状态，默认 200，仅传给 Reset 的 `resetRunTimings`；全局 minimum 则写入文档。不能合并成一个字段或默默替换用户填写值。
- `WebUI/frontend/src/i18n.tsx:790-791,1641-1642`：模拟用停站时间明确独立于全局最短停站。
- `WebUI/frontend/src/authoring.ts:248-273`：Reset 从首站 departure 出发，区间 travelSeconds 转 ticks；仅中间 STOP 以 `arrival + fixedDwellTicks` 生成 departure，PASS 不加，终点仅记录 arrival。此处**适用范围符合用户本轮要求**，并将 fixedDwell 表示为到发之间总间隔，而不是开门后的额外等待。
- 全局 minimum 是计划可行性下限；fixedDwell 是生成计划使用的间隔。若 fixedDwell 小于 minimum，应由一致校验给出问题，不宜无提示改写。全局下限的运行时起止事件仍需按本轮用户合同修正，不能按旧程序的开门完成事件定义。

### 2. 首尾站的额外 minimum 从生成器主动注入，不是后端凭空生成

- `WebUI/frontend/src/authoring.ts:638-645`：destinationOptions 对所有非 PASS 目标赋 `authoringMinimumDwellForRun`；包括 TERMINAL。
- `WebUI/frontend/src/authoring.ts:704-707`：ORIGIN 的 beginStationStop 同样赋全局 minimum。
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt:441-445`：origin options 接受 minimum；只检查 role 为 ORIGIN，没有排除首站 minimum。
- 同文件 `486-510`：destination options 的 minimum 被赋给外层 `SavedTimetableActionV1`，并把同一个 options 交给 `compileStationStopPrograms`。
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationStopPrograms.kt:144-149`：minimum 只校验非负，原样放入 `SavedStationStopPrograms`，没有按 role 重解释。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/StationStopProgramSnapshot.kt:63-71`：保存/还原继续保留该值。
- 因而 TERMINAL minimum 同时存在于外层 destination options 和内层 stopPrograms；它们是同一输入的两个传输位置，不应未经运行时证据就称为“两次等待”。只改其中一处仍可能被另一处覆盖。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt:2495,4089`：destination 默认可回落到 role.defaultDwellTicks，runtime 构造优先 programs.minimumDwellTicks。若仅把生成的首尾站字段改成 absent/null 而不修运行时角色作用域，存在默认值再次注入的风险；显式 0 或统一 role 判断需由整体设计定稿。

### 3. 编辑校验也已经超出“中间站”，不是只修执行层就够

- `authoring.ts:345-350,426`：call 不为 PASS 且同时存在 arrival/departure 就加 minimum；未限制 STOP。通常 ORIGIN 无 arrival、TERMINAL 无 departure，因此普通首尾站行**看起来不受限制**，并不证明逻辑本身正确。
- `authoring.ts:306-314`：shiftRunFromDeparture 同样对非 PASS 加 minimum。
- `authoringGraphEditing.ts:66-68,95-97`：后续平移和单点编辑也是非 PASS 判定，TERMINAL 有可选 departure 时会误受 minimum 限制。
- `DutyEditor.tsx:201-203`：图点允许范围只豁免 PASS/DEADHEAD，未豁免 ORIGIN/TERMINAL。
- `authoring.ts:765-771`：`editRunConnection` 将相邻 RUN 的 terminal arrival 与 next origin departure 视为共享停站，强制二者差值达到前 RUN 的全局 minimum；与用户明确首尾站不添加的简化合同冲突。

### 4. 一套跨任务 pendingDwellUntil 规则是明确的额外复杂性

- `authoring.ts:458-461`：引入“终到停站门”的局部状态 `pendingDwellUntil`。
- `authoring.ts:489-495`：每个 RUN 后设为 `max(effectiveTerminalArrival + minimum, terminalDeparture)`；下一 RUN 开始时校验。
- `authoring.ts:518-522`：扫描程序首个移动时刻，MOVE/STORE/SELECT_STOP 太早即拒绝，移动后消费此门；REVERSE/开关门/等待保留此门。
- 这套规则明确把终点 minimum 延伸至后续 RUN/程序，用户本轮合同下不应继续保留其 minimum 语义。
- **不要机械删除所有关联校验**：其中还混有用户显式 terminalDeparture 的计划门、任务时间先后和位置连续性校验。移除隐式 terminal minimum 不等于忽略显式时刻、允许先发后到或删除物理安全检查。建议删除“终到 minimum 跨任务继承/消费”分支，并保留独立明确的计划时刻约束。

### 5. 时间轴实现已不加 terminal minimum，注释与 spec 却有残留矛盾

- `dutyTimeline.ts:33-43`：RUN endTick 取有效 terminal arrival（缺省才退回 terminal departure），**当前不加 minimum**，该范围可以保持。
- `dutyTimeline.ts:13` 注释仍声称包含 terminal arrival/dwell/departure，与本文件实际投影不一致。
- `i18n.tsx:154,953` 声称 minimum 限制“下一次离开该停站”，仍推广了终到跨任务门，应按用户合同调整。
- `.trellis/spec/webui/frontend/studio-authoring.md:131,137` 写入了 terminal minimum 和跨任务门；:143 又声称时间轴用 arrival + minimum，而 :137 及代码实际只到 arrival，相互矛盾。
- 同 spec :103 将 RunConnectionEditor 的相邻 RUN 接续绑定 minimum，也需修正。
- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md:274-280` 描述所有适用角色的 minimum 与 finalGate，应补充仅中间 STOP、arrival→closed-complete 的明确合同，删除错误适用范围，而非据其保留现行行为。
- `i18n.tsx:664,1515` 当前全局提示仅写“营运停站，回送豁免，不自动改时刻”，**未找到当前字符串明确写到站→关门完成**。这不否定用户合同；不能向用户声称当前检出的文案已经完整说明起止事件，也不能以此为理由重新争论已明确需求。历史版本/浏览器缓存内容本研究未调查。

### 6. 现有测试中有明确固化错误行为的断言

- `WebUI/frontend/src/authoring.test.ts:466-485` 名为 generates the global minimum on every station destination and origin；断言 ORIGIN=40、STOP=40、TERMINAL=40。应改为仅 STOP 获取全局值，首尾/通过不受该下限。
- `authoring.test.ts:164-179` 明确校验终到后程序/下个 RUN 的 dwell gate，应重写为不自动继承 minimum，同时单独保留显式 terminalDeparture 和时间顺序测试。
- `arrivalAuthoring.test.ts:62-69` 固化 terminalDeparture 与 effectiveArrival+dwell 的组合，需拆分。
- `interlineAuthoring.test.tsx:28` 对 TERMINAL options 断言 minimum=20，需改；相邻运行线合并不应自动恢复被用户排除的首尾停站下限。
- `authoring.test.ts:448-463` 对 STOP/PASS 和 arrival override 的正向覆盖可保留；增加 TERMINAL 有 departure、ORIGIN 有可选 arrival 的范围回归。
- `authoringGraphEditing.test.ts`：补充首尾站图编辑不受 minimum、STOP 仍受 minimum；`DutyEditor.tsx` 的显示上下界也必须一致。
- `dutyTimeline.test.ts:63-70` 验证 minimum 改变不延长终到 RUN 占用，可保留；:8 测试名仍提 dwell/departure，应核对表述。
- `BukkitPlugin/src/test/kotlin/work/art1st/javarailways/scriptworker/BrowserGeneratedStationProgramTest.kt:41-56` 覆盖真实浏览器生成→worker→snapshot/domain，现仅断言 incoming.first() 即 STOP 的 minimum=20。应增加 ORIGIN/TERMINAL 无全局 minimum 的断言，并确认外层 DestinationOptions 与内层 stopPrograms 一致。
- 此研究未运行测试；上述是源代码证据，不是通过报告。

### 7. 最小建议修复范围（规划，不是实施授权）

1. WebUI 引入或改造一个 call-aware 最短停站取值点：只有 PASSENGER + STOP 返回全局值。生成、校验、图编辑、bounds 共用；不增另一份配置。
2. 删掉 terminal minimum 的跨任务继承/移动消费逻辑；相邻 RUN 接续不重新拼出全局 minimum。保留显式计划时刻、连续性和实际门关闭安全要求。
3. 固定停站 Reset 的首尾不添加行为保持不变；STOP 固定间隔仍与 minimum 分离。不引入“开门时间 + minimum + 关门时间”的重复累计。
4. 编译和快照不需要新增字段；整体设计确认 canonical role-aware 值后，检查两层载荷及默认值，防止 null 回落到基础设施 defaultDwell 重建终到等待。
5. runtime 计时由主会话独立研究；编辑层本身无法证明“关门完成”事件，不能只改 tooltip/公式宣称执行已修复。
6. 同步删除陈旧 spec 和测试里的首尾最短停站要求，新增真实生成编译链回归；无需为当前语义修正发明兼容新旧停站策略的 runtime 分支。

## Files Found

- `WebUI/frontend/src/authoring.ts` — 文档级最短停站、Reset、校验、跨 RUN 门和 JS 生成。
- `WebUI/frontend/src/authoringGraphEditing.ts` — 图时刻事务及最短停站校验。
- `WebUI/frontend/src/DutyEditor.tsx` — minimum 与 fixedDwell 的独立输入、图点范围、Reset 使用。
- `WebUI/frontend/src/dutyTimeline.ts` — RUN 占用投影，不再添加 terminal dwell。
- `WebUI/frontend/src/i18n.tsx` — 两项参数提示及陈旧终到跨任务说明。
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt` — 生成脚本 options 到 saved action。
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationStopPrograms.kt` — minimum 原样编译到 shared program。
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/StationStopProgramSnapshot.kt` — program minimum 保存和还原。
- `WebUI/src/main/java/work/art1st/javarailways/webui/EditorDocumentValidator.java:27` — 文档 minimum 仅做安全非负整数检查，不负责起止事件语义。

## Related Specs

- `.trellis/spec/webui/frontend/studio-authoring.md`（上述 42、103、129–152 处相关合同；部分已与实现/本轮要求冲突）。
- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md:270-288`（共享停站程序与最终执行 gate；须以用户最新合同修订）。

## External References

无。此问题是仓库内部产品语义与实现漂移，不依赖外部协议或库版本。

## Caveats / Not Found

- 未调查历史 UI 文案，没有证明当前浏览器所部署版本与本地字符串完全相同。
- 未运行浏览器或构建；只读追踪不能替代真实门动作完成事件测试。
- 基础设施 defaultDwell 的独立用途、手动导航/无 stationProgram 的旧入口仍存在；不要在修复 WebUI 自动注入时顺带删除所有底层 dwell 能力，需由整体设计明确其作用域。
- 不认为“有多个安全 gate”等同过度设计；本研究明确指出应移除的是首尾站/跨 RUN 的隐式最短停站机制，保留真实动作完成、门关闭和物理授权。
