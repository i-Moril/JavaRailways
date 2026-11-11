# Revised work commit plan

All paths are relative to `codex/webui-completion`. Every dirty path belongs to this task; no unrecognized paths or main-worktree changes. This replaces the earlier point-drag plan. No binaries, dependency files or screenshots.

## 1. feat(webui): add node-based timetable editing

- WebUI/frontend/src/Graph.tsx
- WebUI/frontend/src/Graph.test.tsx
- WebUI/frontend/src/graph.ts
- WebUI/frontend/src/graph.test.ts
- WebUI/frontend/src/authoringGraphEditing.ts
- WebUI/frontend/src/authoringGraphEditing.test.ts

Optional node activation callback plus explicit single-event/suffix authoring transactions and tests. No point dragging or keyboard time nudging.

## 2. feat(webui): complete studio authoring workflows

- WebUI/frontend/src/App.tsx
- WebUI/frontend/src/DutyEditor.tsx
- WebUI/frontend/src/RunRouteEditor.tsx
- WebUI/frontend/src/AuthoringValidation.tsx
- WebUI/frontend/src/DutyManagement.tsx
- WebUI/frontend/src/CommittedNumberInput.tsx
- WebUI/frontend/src/CommittedNumberInput.test.tsx
- WebUI/frontend/src/authoringCommands.ts
- WebUI/frontend/src/authoringViews.test.tsx
- WebUI/frontend/src/auth.test.ts
- WebUI/frontend/src/i18n.tsx
- WebUI/frontend/src/studioPages.css

- WebUI/frontend/WEBUI_REWRITE.md
- .trellis/spec/webui/frontend/index.md
- .trellis/spec/webui/frontend/components-state-contracts.md
- .trellis/spec/webui/frontend/studio-authoring.md
- .trellis/tasks/10-18-webui-completion/check.jsonl
- .trellis/tasks/10-18-webui-completion/design.md
- .trellis/tasks/10-18-webui-completion/implement.jsonl
- .trellis/tasks/10-18-webui-completion/implement.md
- .trellis/tasks/10-18-webui-completion/prd.md
- .trellis/tasks/10-18-webui-completion/research/preview-revision-validation-2026-10-18.md
- .trellis/tasks/10-18-webui-completion/research/validation-2026-10-18.md
- .trellis/tasks/10-18-webui-completion/task.json
- .trellis/tasks/10-18-webui-completion/commit-plan.md

Includes shared authoring pages, real validation/simulation, explicit deferred time commits, revised navigation, origin-preserving regeneration and dedicated copy, async/world guards and matching documentation. Depends on commit1.

## Verification and permission
104 tests and production build passed; independent review clear and browser revision checks passed. No push/deploy. User explicitly authorized the work commits with “你先提交”. Batch1 is7366d1b4; batch2 contains the remaining UI/spec/task changes. No push or deployment. The task remains available for ongoing preview review; no archive requested in this commit-only step.
