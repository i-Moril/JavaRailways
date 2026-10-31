# Implementation plan

1. Establish current shared document/runtime contracts; preserve current schema number and remove runtime old-development compatibility assumptions.
2. Contracts worker: program actions, station definitions, worker/compiler/snapshot/validation and static condition expansion. Coordinate runtime types with runtime worker.
3. Runtime worker: station executor and Dispatcher lifecycle integration, gateway operation ownership, arrival/preparation eligibility and final descendant join; regression tests.
4. Routing worker: opt-in unique physical paths and waypoint pruning, strict timetable caller, tests including protection atomic alternatives.
5. Root: frontend document/writer/generator, shared station editing and Blockly new constructs; ordered via, event points/waits, duty Blockly and persisted reference profile; tests.
6. Integrate producers/consumers, migrate affected current fixtures/tests; add dry-run conversion tooling only where new current data requires preserving existing deployed documents, without runtime compatibility readers.
7. Run npm test/build, affected Gradle tests sequentially with shared JDK/cache and bounded heap. Inspect all failures, run scoped cross-layer reviewer, fix real issues.
8. Verify production HTTP interface lifecycle with isolated fixtures, including statically compiled callbacks and honest runtime acceptance limits. Update specs, evidence and task status. Commit only task-owned changes if authorized; no live deployment in this task.

## Completed implementation and validation

All eight batches are implemented and reviewed. Final backend2269/frontend210/migration3 gates passed, latest HTTP browser first-Duties initialization and invalid-draft recovery verified, shadow packaging verified. `research/validation.md` records precise evidence and the existing Java25 reobfuscation limitation. The user approved `research/commit-plan.md` and its exact file manifest on 2026-10-26. Record the local work commit and keep the task in_progress pending user in-game testing; archival is explicitly deferred. No push or deployment is authorized by this commit request.

## Feedback round1

User authorized incremental fixes and deployment on 2026-10-26. First-round behavior/ownership/acceptance is in research/feedback-round-1.md; preserve the remaining feedback for later rounds. Current source baseline aa5cb68d is already deployed; initial dirty files are task deployment evidence and task notes only.

## Feedback round2

User approved program library management round on 2026-10-27. Boundary, ownership and acceptance: research/feedback-round-2.md. Existing snapshots survive real library deletion; task remains unarchived.

## Obsolete development compatibility cleanup

User requested removal on 2026-10-27. Inventory and implementation boundary: research/obsolete-compat-inventory.md and research/obsolete-compat-removal.md. Remove archived status and obsolete per-call arrivals/rows code; strict current inputs, offline migration only.

## Duty formation follow-up (existing task, user-authorized)

- [x] Batch A: put one duty's existing tasks on one shared horizontal track; preserve real timing, interaction and unknown-duration semantics. Full frontend254/build and independent review passed. Actual app selection/shared geometry and explicit real-component fixture drag/resize/context/navigation verified; details and mobile limitation in research/duty-formation-follow-up.md.
- [x] Batch B: add duty-owned formation task authoring and local participant/output editing; remove formation blocks from reusable player programs and synchronize generated source and backend current-model validation. HEAD physical partition retained; details in research/duty-formation-batch-b.md.
- [x] Batch C: reciprocal assignment to manually created receiving duties, source-task/output selection, source renames/removal/history/recovery, planned-time conflict gates and formation dependency validation. Frontend269/WebUI41/BukkitPlugin641 tests, production build and actual React editor flow passed; no live game acceptance claimed.
- Withdrawn Batch D: user rejected extra HEAD/TAIL selection as unnecessary; restore the existing one-into-two primitive and compose repeated splits. This is not a pending feature. See research/duty-formation-batch-d.md.
- [x] Retained B/C integration: frontend269/build, WebUI41/BukkitPlugin641 pass after exact D rollback; independent SHA audit confirms preserved B/C. Previous B/C browser checks remain evidence; real game acceptance remains pending. Keep task in_progress; no automatic deployment/archive.

## Original feedback17 batch

- [x] Shared readonly data bindings through authoring/Blockly, Java validation, worker expansion, saved/runtime actions and both Dispatcher execution paths.
- [x] Real frame data and fail-visible unit count; resolved extension validation before start. Literal and planned-condition rules unchanged.
- [x] Frontend276/build, SimpleATO641/WebUI44/BukkitPlugin645 and signal API compile; snapshot/compiler/HTTP restart, real component browser, scoped independent review and spec sync passed. No commit/deploy/archive; live game acceptance pending.

## Original feedback7 spacing batch

User requested split commits then continue. Formation committed5be9d190; train-info committed07678147. Next bounded scope in research/feedback-spacing.md.

- [x] Shrink JSON parameter controls to shared call grid and retain field gap; narrow layout.
- [x] Bound snapshot rows vertical spacing and long-name wrapping; behavior unchanged.
- [x] Browser before/after geometry and native editing/update callbacks, frontend276/build and independent scoped review passed; new batch uncommitted, no deployment/archive.

## Original feedback10 next batch

Spacing committed0900f865 per user request; user game validation deferred. Investigate coherent replacement/retirement of old operational-trip authoring, research/operational-trip-retirement.md. Existing display actions depend on trip lifetime; do not hide controls or drop configuration. User chose retained capabilities; follow timeline RUN inspector contract below, current schema/runtime unchanged. No deployment/archive.

User answered: preserve continuous display and per-unit future stops, configured through program/timeline. Adopt timeline RUN inspector with unchanged authoritative catalog/runtime model; see research/run-display-plans.md.

- [x] Pure immutable display-plan edits, claims/identity and current-format roundtrip/generation tests.
- [x] RUN inspector editor, orphan visibility and removal of old independent panel.
- [x] Frontend287/build, actual App browser/history/recovery/invalid gate/narrow screen and scoped independent review pass. User authorized separate local commit; no deployment/archive, game acceptance deferred.

## Standalone function duration simulation (2026-10-29)

User approved explicit directed waypoint + ALONG/OPPOSITE initial facing. Execute bounded contract in research/function-simulation.md; preserve original waypoint routing semantics, reference blueprint boundary and current persistence. Implement runtime transient initial-condition flow, bridge/sidecar endpoint and shared frontend dialog; independently check before reporting. No task archive or automatic deployment.

User correction2026-10-30: all actual simulation seeding proves unique whole-train placement in configured direction; optional actual in-place reverse follows placement; then existing execution. Remove opposite-facing respawn draft. Add SIM+LIVE ATO no-implicit-reverse regression and mixed rail-local orientation check. See CURRENT correction in research/function-simulation.md and research/placement-uniqueness.md.


- [x] Current Oct30 contract implemented: shared Core whole-body unique placement, real optional reverse, original dispatcher/sandbox execution; previous facing override removed.
- [x] Actual regression failures for mixed-rail repeated reverse and reverse→depot fixed with retained origin direction; test fixture owner nesting corrected without weakening production guards.
- [x] Final Core538/Basic435/SimpleATO643/Bukkit663/WebUI49 tests (2328 total), frontend323/build, actual local browser input/error/narrow-layout check, independent review, spec/context validation pass. See research/function-simulation-validation-2026-10-30.md.
- [ ] User game validation, commit/deployment follow-up and remaining parent issues. Keep task in_progress; no finish/archive.


## 2026-10-30 下一批：路径点输入与存车线入口

边界：存车线已由运营目标映射Basic路径点，并未被现有metadata/执行器按角色排除。先补真实当前格式恢复和实际隔离执行回归，避免为不存在的过滤缺陷增加后端代码；网页统一手动输入/候选筛选是本批实际可见改动。输入/草稿/history归UI，既有方向、拓扑、未知引用校验归原owner；不改DSL、执行器、持久化或schema。

- [x] 库存所有直接路径点、站内泊位/库内存车点输入，复用一个可编辑ID控件但显式传入各域候选。
- [x] 输入时按ID/名称筛选；Enter/blur/选择提交一次，Esc取消，字段/owner变化清理旧输入；有序多选保序。
- [x] 库参数/Blockly DESTINATION和MOVE.viaIds保留参数引用、未知/无效草稿及撤销历史；泛型ID_LIST不猜路径点语义。
- [x] 模拟起点可输入canonical depot@point ID；任何未提交文本编辑立即作废旧测量，非eligible点仍不能运行。
- [x] 真depot注册/保存恢复/源状态隔离+显式换向回归；前端完整测试/build、真实浏览器输入/筛选/历史和独立审查。
- [x] 更新spec及验证记录。无自动提交、部署或归档。

最终验证：前端335/57文件+build、Bukkit667/WebUI49、真实App浏览器筛选/输入/单步历史/窄屏和独立复审通过。详见research/waypoint-input-validation.md。线上认证metadata响应及部署后游戏验收未验证；本批未提交/部署，父任务继续in_progress。

2026-10-31用户明确授权本批提交部署：功能574975bf、线上整合bc7d6db1、仅测试方向预期修正976f4eac；最终3029后端/335前端和构建、正常保存/恢复点/安装/ATO与信号/17网页资源核验通过。见research/deployment-2026-10-31.md。任务继续in_progress，游戏验收待用户，不归档。

## 2026-10-31 停车站后缀范围纠正

用户纠正：完整路径点手输/筛选不能扩到停车站局部后缀。本批撤回RunRouteEditor泊位控件及程序局部berth/stabling列表的新控件扩展，保留真正完整路径点功能。边界/所有权见research/waypoint-suffix-correction-2026-10-31.md；仅前端检查，不自动提交/部署/归档。

- [x] 恢复站内后缀Select及局部程序列表原输入，清理无用helper/错误预期测试。
- [x] 完整前端test/build及窄范围差异复核；记录实际未部署状态。

验证完成：完整前端335/57+build、独立窄复核、真实App后缀选择/本站解析/单步撤销与via名称筛选/手输/失焦通过。见research/waypoint-suffix-correction-2026-10-31.md。未提交/部署，在线976f4eac，任务保持进行中。

用户随后明确提交部署：6563e4f9已上线，源/候选JAR逻辑差异仅嵌入WebUI，WebUI49/打包、正常保存/恢复/安装、ATO/信号/17资源及公开SSE真实帧通过。见research/deployment-2026-10-31-suffix.md。任务继续进行中，未归档。
