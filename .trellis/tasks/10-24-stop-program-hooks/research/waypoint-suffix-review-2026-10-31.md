# 停车点后缀纠正：独立窄范围复核（2026-10-31）

## 范围与结论

工作目录 `/tmp/javarailways-blockly-deploy`，继续 `10-24-stop-program-hooks`。仅阅读冻结的五文件 diff：RunRouteEditor.tsx、ProgramBlockEditor.tsx、idOptions.ts、idOptions.test.ts、RunRouteEditor.input.test.tsx，以及更新后的 components-state-contracts 和 waypoint-suffix-correction-2026-10-31.md。按用户最新纠正：完整路径点手输/筛选不得扩大到局部后缀。

**未发现本次纠正的阻断项。** 原 release-check 对局部候选控件的认可仅对应旧范围理解，已被本次用户纠正及本报告替代，不能继续作为保留该扩展的依据。

## 源码复核

- RunRouteEditor 的泊位列恢复为 `5e674d12` 的原始无边框 Select 表达式：候选只取 `berthsFor(call.stationId)`，value/label 均为局部 berthId；没有完整路径点名称、AutoComplete 或手输入口。原 changeBerth 仍以当前 stationId 加 berthId 解析 destinationId，不把相同后缀跨站解析。运行线 viaIds 仍使用完整 waypoint 的 EditableIdList。
- ProgramBlockEditor 删除 SELECT_STOP.berthIds 和 STORE_AT_DEPOT.stablingPointIds 两条候选分支。它们现在落回原 ID_LIST raw Input，仍由原字段事务写入原始 JSON 文本；未改成完整路径点 ID，也未改变局部字段模型或校验。
- 明确的 DESTINATION 字面量继续使用完整路径点 EditableIdSelect；只有直接连接 MOVE.viaIds 的合法字符串数组继续使用完整路径点 EditableIdList。参数引用仍走参数 Select；泛型列表、局部列表、非法 JSON/成员/重复项仍保留 raw Input，不静默丢弃或重建草稿。
- idOptions 仅删除已撤回局部候选的 berthOptions/stablingPointOptions；全局 waypointOptions、directedWaypointOptions 的 canonical 值、ID/名称筛选元数据及模拟方向候选均未改变。两处生产调用者的对应 imports 一并移除。

## 测试与规格复核

- RunRouteEditor.input.test.tsx 不再要求手输局部泊位；改为检查每站只显示自己的后缀，并通过两站共享后缀 2 分别解析到 a@2、b@2，保留单次更新和另一站不被改写断言。
- 同文件新增实际 RunRouteEditor 的完整 via 路径：手输未知 removed@1 后，按 Junction 名称选择 canonical junction@1，断言数组有序、回调次数及原 targetId 不变。它验证保留完整路径点能力，没有把后缀恢复扩大为撤回全局功能。
- idOptions.test.ts 删除对应已移除局部 helper 的测试，保留全局 ID/名称、canonical depot 身份、方向候选与未知过滤断言。
- 更新后的 components-state-contracts 与本次实现一致，明确区分局部后缀和完整路径点；无模型/API/后端/持久化变更需要补充同步。

## Findings (fixed)

本 reviewer 未改动生产/测试；上述修正由 implementer 完成，静态复核通过。

## Findings (not fixed)

无新增已确认问题。

## Verification

- 本轮仅阅读 diff、必要的局部原代码和规范并新增本报告，未运行 lint、type-check、测试、Gradle/npm 或其他检查。
- 主会话报告完整前端 335 tests / 57 files 与 TypeScript/Vite build 已通过；本轮未独立执行，不把它表述为 reviewer 的执行结果。
- 主会话正在通过真实 App fixture 验证恢复/保留控件；该浏览器结论不由本静态复核提前确认。
- 未改生产/测试、提交、部署或归档，未触碰 hprof；当前纠正没有提交/部署授权。
