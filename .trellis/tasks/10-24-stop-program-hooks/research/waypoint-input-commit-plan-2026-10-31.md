# 路径点输入批次提交/部署计划 — 2026-10-31

用户本轮明确要求提交并部署，沿既有任务执行，无需重复确认。父任务保持in_progress，不归档、不push。

1. `feat(webui): support editable waypoint selectors and depot origins`：本批共享输入、接线、回归、规范和证据（下列精确清单，加本计划和release-check报告）。
2. 独立merge提交合入当前dev/signal-system，保留已上线信号编辑、保护区域与方向解耦修复；相对该线上源的产品差异只应为本批。最终合并源重新验证后构建部署。
3. 部署后单独记录恢复点、工件hash和真实运行核验，不结束任务。

不属于本批：java_pid454886.hprof，原样保留。

## 本批清单

- .trellis/spec/webui/frontend/components-state-contracts.md
- .trellis/tasks/10-24-stop-program-hooks/check.jsonl
- .trellis/tasks/10-24-stop-program-hooks/implement.jsonl
- .trellis/tasks/10-24-stop-program-hooks/implement.md
- .trellis/tasks/10-24-stop-program-hooks/prd.md
- .trellis/tasks/10-24-stop-program-hooks/research/depot-simulation-start.md
- .trellis/tasks/10-24-stop-program-hooks/research/waypoint-input-batch.md
- .trellis/tasks/10-24-stop-program-hooks/research/waypoint-input-inventory.md
- .trellis/tasks/10-24-stop-program-hooks/research/waypoint-input-review.md
- .trellis/tasks/10-24-stop-program-hooks/research/waypoint-input-validation.md
- .trellis/tasks/10-24-stop-program-hooks/task.json
- BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt
- WebUI/frontend/src/DutyManagement.tsx
- WebUI/frontend/src/DutyTimeline.tsx
- WebUI/frontend/src/EditableIdSelect.test.tsx
- WebUI/frontend/src/EditableIdSelect.tsx
- WebUI/frontend/src/FormationEventsEditor.tsx
- WebUI/frontend/src/FormationTaskFields.input.test.tsx
- WebUI/frontend/src/ProgramBlockEditor.tsx
- WebUI/frontend/src/ProgramFields.tsx
- WebUI/frontend/src/ProgramSimulationDialog.test.tsx
- WebUI/frontend/src/ProgramSimulationDialog.tsx
- WebUI/frontend/src/RunRouteEditor.input.test.tsx
- WebUI/frontend/src/RunRouteEditor.tsx
- WebUI/frontend/src/StationProgramLibrary.tsx
- WebUI/frontend/src/TimedOperationFields.tsx
- WebUI/frontend/src/i18n.tsx
- WebUI/frontend/src/idOptions.test.ts
- WebUI/frontend/src/idOptions.ts
- WebUI/frontend/src/types.ts
