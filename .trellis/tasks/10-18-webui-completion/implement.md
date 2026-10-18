# Execution plan

## Preconditions
- [x] User approved task creation/planning and required original design style.
- [x] Dedicated clean worktree on `codex/webui-completion`, base `45246995`.
- [x] PRD/design/implementation plan and curated context prepared.
- [x] User explicitly approves the latest final planning summary before `task.py start`.

## Batch 1: Authoring workflow
- [x] Load phase 2.1 and dispatch Trellis implement agent with active-task path and curated context.
- [x] Add controlled duty view and original-style navigation while retaining one mounted document/history owner.
- [x] Wire real authoring/compile/world issues and actionable command prerequisites.
- [x] Wire existing profile mapping and complete-simulation result display in Validation.
- [x] Verify page-switch state preservation, invalid draft feedback, profile completeness/conflicts and stale results.
- [x] Dispatch Trellis check agent, fix findings and review the batch.

## Batch 2: Run editing
- [x] Add explicit creation direction and restore existing Reset and copy/shift entrypoints.
- [x] Connect graph arrival/departure editing to pure transactions and history; retain existing move preview.
- [x] Complete command busy/disabled/undo/redo state.
- [x] Verify both directions, unique copy IDs, failure-no-mutation, timing overrides and graph gesture behavior.

## Final checks
- [x] `npm --prefix WebUI/frontend test`
- [x] `npm --prefix WebUI/frontend run build`
- [x] Browser verification: production timetable/duties/validation, profile setup/results, both locales/themes, guest map-only, narrow viewport, empty/error/loading and history.
- [x] API/wire changes are out of scope; if discovered revise planning and include WebUI Java contract checks.
- [x] `git diff --check`
- [x] Update affected specs and the stale `WEBUI_REWRITE.md` progress with verified evidence.
- [x] Final Trellis check and two task-owned work commits authorized by user; actual validation and runtime limits recorded. No deploy/push.

## Risk and rollback points
Navigation must preserve the sole editor owner; compile/simulation results must stay tied to current inputs. Preserve untouched primary-worktree files. Do not treat pure-function tests as browser workflow proof. Stop and revise scope if implementation needs new API or persistence behavior.

## Final evidence

Implementation and independent review passed; see [validation record](research/validation-2026-10-18.md). Product code is complete; user subsequently authorized the two work commits. Task stays in_progress for ongoing preview review, without archive in this commit-only step.

## Preview UX revision — implementation checked

- [x] Resolve standalone versus Validation simulation removal: only standalone pages.
- [x] Finalize revised PRD/design/context; user confirmed the summarized revision and page-only removal.
- [x] Remove JS timetables and standalone Simulation pages and unreachable UI helpers.
- [x] Replace point dragging/Move run with clicked-node toolbar and validated atomic suffix shift.
- [x] Commit local time drafts only on blur/Enter, once; verify redraw/history/recovery boundaries.
- [x] Regenerate current run using original departure; dedicate ribbon tool/dialog to interval/count copy.
- [x] Recheck pure transactions, tests/build, independent review and browser interaction; update evidence/specs and replace stale commit plan.

Earlier implementation checkmarks and89-test evidence describe the pre-revision preview, not acceptance of these new changes. No work commits have been made.

Revision implementation/check complete; final104-test and browser evidence is in research/preview-revision-validation-2026-10-18.md. The old point-drag commit plan was replaced; user explicitly authorized the revised two work commits. First commit7366d1b4 contains graph/node transactions; remaining UI and documentation are in the second work commit.
