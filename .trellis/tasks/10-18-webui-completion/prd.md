# Complete WebUI timetable studio

## Goal
Finish the missing production UI workflows while retaining the approved Timetable Studio visual design. Work only in the dedicated `codex/webui-completion` worktree, based on `45246995`; preserve unrelated main-worktree edits.

## Background and evidence
The approved visual reference is `WebUI/frontend/prototypes/timetable-studio/`; production contracts remain in `src/`. Foundation, line-scoped three-column timetable workspace, station calls/detail, line administration, pan/zoom and precise run-shift preview already exist.

## Requirements
- R1: Restore accessible duty management: create/delete/rename duties, selectors, run assignment and atomic transfer, ordered elements and existing built-in function parameters. Current handlers have no rendered consumers (`DutyEditor.tsx:185-227`).
- R2: Show actual authoring, compilation and world-validation results, with navigation to the owning run/duty/call where identifiers are available. Replace unconditional success (`RunRouteEditor.tsx:76`). No unexecuted check may appear successful.
- R3: Remove only the standalone top-level JS timetables and line Simulation pages. Retain complete timetable simulation/profile assignments/report inside Validation, and retain internal line estimation for Reset. Server compilation/world validation remain authoritative.
- R4: Retain explicit direction at run creation. Run options only regenerate timings for the selected run, preserving its existing origin departure tick; remove the independent first-departure input and copy controls there. Add a separate Copy run ribbon tool with interval/count dialog; copies shift by successive intervals, receive fresh IDs and start unassigned.
- R5: Remove graph point dragging and the Move run ribbon tool. Clicking an actual arrival/departure point opens a node editing toolbar with Shift. Its dialog accepts an absolute target tick or signed tick offset and shifts that node event and all later events of the same run; earlier events remain unchanged. Show the affected suffix and legality errors before atomic apply; invalid shifts cannot change document/history. Preserve graph canvas pan/zoom.
- R6: Bind command availability and progress to real state; expose undo/redo availability. Editing time inputs updates a local draft only; blur/Enter commits once to the authoritative document and triggers graph redraw/recovery updates. Do not write each intermediate keystroke. Remove standalone JS timetables and Simulation navigation/page bodies; keep generated formal JS save/compile mechanics unless separately requested otherwise.
- R7: Match the approved Ant Design shell, ribbon, panel surfaces, spacing, typography, colors, light/dark themes and bilingual copy. Use the reference duty and validation page layouts; retain the timetable three-column layout.

## Acceptance criteria
- AC1 (R1): A new document can create runs and a duty, assign/transfer/reorder them and configure existing selectors/functions without console or manual JSON edits; operations share undo/redo.
- AC2 (R2,R6): Invalid/unassigned runs produce visible issues; compile errors are readable; valid server compilation enables publication; blocked commands explain why.
- AC3 (R3): No top-level JS timetables or Simulation page remains; Validation retains full timetable simulation/profile mapping/report and input invalidation.
- AC4 (R4,R5,R6): Reset retains the selected run original origin departure. Copy is available only through its dedicated tool. A node suffix shift moves exactly that event and later events, preserves earlier timestamps, rejects illegal changes atomically and forms one Undo. Point dragging does not edit time. Typed time remains uncommitted until blur/Enter, with no duplicate Enter-plus-blur transaction.
- AC5 (R7): Browser checks cover both locales/themes, guest map-only navigation, normal editing, empty/error/loading states, page switching and a narrower viewport; no prototype data or fake successes.
- AC6: Frontend tests/build and diff checks pass. Document actual browser coverage and any limitation separately from test success.

## Out of scope
New backend endpoints, runtime behavior, persistent schemas/migrations, template storage, brick-style station programming, editable map topology, generalized multi-selection/period generation, full simulated trajectory reconstruction, persistent simulation caches, deployment, and pushing changes. Do not persist new panel preferences in this batch. Remove standalone scripts and line-simulation presentation; keep internal line estimation used for Reset. Generated source, formal JS persistence and compiler remain existing contracts.

## Delivery order
Initial implementation is complete and its historical evidence remains in research/validation-2026-10-18.md. The user has now requested a UX revision while reviewing the preview. The user confirmed the previously summarized revision removes only the standalone pages; proceed with the six requested UX changes and preserve Validation simulation. Preserve unrelated main-worktree work and original visual style.

## Confirmed preview decisions
The user confirmed: only remove the two standalone pages. Keep full timetable simulation inside Validation and internal Reset estimation; no backend removal. The six preview requests supersede the original point-drag/whole-run move/copy placement requirements.
