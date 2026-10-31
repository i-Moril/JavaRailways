# 路径点输入与存车线模拟入口：提交前独立复核（2026-10-31）

## 范围与结论

工作目录 `/tmp/javarailways-blockly-deploy`，分支 `codex/timetable-station-programs`，比较基线及复核时 HEAD 均为 `5e674d12b2150a0f98d20608ae3419bd38c23797`。复核当前冻结批次的完整 tracked diff、新增输入组件/辅助函数/测试、check.jsonl 所列规范及既有研究、验证记录。不是全仓审查，也不涵盖后续合并版本。

**未确认新的提交阻断项；上一轮 P1/P2 的当前源码修正与记录一致。** 本轮仅新增本报告，没有改动生产/测试文件、运行 Gradle、重复前端测试或构建、提交、部署或归档。未跟踪的 `java_pid454886.hprof` 未读取、未修改，必须继续排除在提交之外。任务仍为 `in_progress`，`completedAt=null`。

当前变更涉及 13 个前端生产文件和 6 个测试文件（包括 1 个 Bukkit 测试文件）。这 19 个产品/测试文件按路径排序，以 `path + NUL + sha256(contents) + LF` 拼接后计算的 SHA256 为 `ddd70090f1c027323eb87f178afffdbdff963fb11c11d7acd814b15f2555af5e`。Bukkit 测试文件 SHA256 为 `0f75be4eab321bcf283e7a5d285d0af6f3d5ffa08ce553111b62c8887a54bd21`，供后续整合时区分候选。

## 已复核的行为与边界

- `EditableIdSelect.tsx`：fieldKey 更换重建局部草稿；Enter/blur/选择通过 last 值去重；Escape 恢复已提交 ID；卸载后的旧回调不能提交。输入保留 canonical/未知 ID，不自动替换为名称或第一个候选。
- `EditableIdList`：受控 search 在接受 onChange 和 onSelect 时显式清空，故 P1 的“名称筛选→选择 canonical ID→blur 追加名称标签”路径已修正。测试替身不再代替组件清空 search，并验证随后 blur 不增加值/回调；保留有序手输与 Escape。
- `idOptions.ts` 和调用者：全局 waypoint、站内 berthId、库内 stablingPointId 分别构建候选；metadata 名称只注释原 ID。RunRouteEditor 的未知 berth 保留草稿并留下空 target 供原校验报告，已知 berth 只在当前站内解析。
- `DutyEditor → DutyManagement → DutyTimeline → FormationTaskFields` 的现有实际路径始终提供 programSimulation metadata；两个会合点按名称过滤并提交 canonical ID，同时以原 destinations 限定候选范围。P2 修正及真实字段回归存在，无聚合 depot/station ID 混入。
- 程序库与调用参数传递 metadata；Blockly 仅在明确的 MOVE.viaIds、SELECT_STOP.berthIds、STORE_AT_DEPOT.stablingPointIds 字面量位置提供各域候选。参数引用、泛型 ID_LIST、非法 JSON/列表保留原输入处理；changeField 仍交给既有 Blockly transaction/history。
- `ProgramSimulationDialog` 的 raw query 改变经 validity 立即 invalidate 请求 owner、旧结果及采纳资格；Escape 不恢复旧测量。请求仍由现有 builder 检查 programSimulationWayPoints，未知/无方向 ID 不可运行，选择起点仍清除 reverseTrain 要求重新明确选择。
- Bukkit 生产源码未改；新增真实注册、当前格式 decode/restore、capture/create/Dispatcher 执行测试证明已有 depot waypoint 镜像和方向规则。断言包括真实 7 tick、显式换向/占用保持、源库存/列车不变，以及无方向/移除拒绝。没有为不存在的角色过滤增加生产 workaround。
- `components-state-contracts.md` 已同步共享输入、身份域、raw query 失效及受控 tags 清空规则。本批不新增持久化字段、schema 版本、旧输入 reader、模拟执行器或放宽 Core 放置证明，未发现额外 spec 同步需求。

## 验证证据复核

本轮独立执行 `git diff --check`：通过。package.json 无独立 lint script，因此不把 diff 检查称作 eslint 通过。

本轮独立解析现存 JUnit XML：

- `BukkitPlugin/build/test-results/test/TEST-*.xml`：141 个 suite，667 tests，failures/errors/skipped 均为 0；时间范围 `2026-10-30T18:47:16.152Z` 至 `18:48:30.505Z`。
- `WebUI/build/test-results/test/TEST-*.xml`：15 个 suite，49 tests，failures/errors/skipped 均为 0；时间范围 `2026-10-30T18:48:31.924Z` 至 `18:48:34.558Z`。
- Factory XML 为 30 tests、0 失败/错误/跳过，包含保存恢复、无方向/移除和布尔参数化的执行实例；已包含在 667 中，不重复计总。

前端既有最终验证记录为 335 tests / 57 files、TypeScript 与 Vite build 通过。本轮独立读取 Vitest 4.1.10 的 `.vite/vitest/*/results.json`，确认 57 文件、0 failed，并包含 EditableIdSelect、idOptions、RunRouteEditor.input、FormationTaskFields.input、ProgramSimulationDialog 测试。缓存只存文件结果，不从它独立推导 335 条用例。

现存 `dist/index.html` 引用 `assets/index-CjPiqCNe.js`，与最终浏览器验证记录一致；该 JS SHA256 为 `cd950482755389c8b862f00eeb6db41aa440e72b5435464d68edef2dd77ce4ce`。TypeScript/Vite 通过属于上一轮已记录的执行证据，本轮未重新执行，后续合并候选需要自己的最终检查与构建。

`/tmp/jr-waypoint-input-browser/` 保留 request.json、multi-before.png、multi-after.png、mobile-inputs.png 等证据；本轮独立读取 request.json 的 initialPosition，确认 `{wayPointId:"kasaidpt@1",reverseTrain:false}`。浏览器操作及历史/窄屏结论沿用 waypoint-input-validation.md 和 waypoint-input-review.md 的实际操作记录，本轮未新开浏览器。

## 未修复发现与后续门禁

没有新增已确认且未修复的本批代码问题。以下为证据范围，不是已确认缺陷：

- 本地 HTTP fixture 明确返回模拟 422，证明真实 App/Ant 输入行为，不证明物理执行成功；物理恢复/执行由后端回归提供。线上认证 metadata 内容及游戏客户端验收仍未由本复核验证。
- 10-30 validation 文档中的“已部署 4c19f7f4、用户尚未授权提交/部署”是当时快照。当前主会话已取得用户授权，并报告独立核实线上 JAR 对应 `32d1c413`；计划本批单独提交后合入 `dev/signal-system` 的 `6a6db614`。本复核未接触线上环境，不把其最新状态冒充本 agent 的独立验证。
- 此结论仅适用于上述 5e674d12 上的冻结批次。合入线上修复后的产品、测试、嵌入 WebUI 静态资源和部署产物需另做整合门禁；本报告不提前认证那个候选。主会话已计划在合并后执行一次完整最终检查/构建。

## Findings (fixed)

本轮没有生产/测试代码修复；P1/P2 已由上一轮实现并在本轮确认。

## Findings (not fixed)

无新增已确认问题。

## Verification

- Lint：无配置的独立 lint；本轮 diff whitespace 检查通过。
- TypeCheck：上一轮最终 TypeScript build 通过的执行记录；本轮未重跑。
- Tests：既有 Bukkit667/WebUI49 XML 和前端57文件缓存全部通过；前端335用例数来自最终验证记录。本轮未执行测试。
