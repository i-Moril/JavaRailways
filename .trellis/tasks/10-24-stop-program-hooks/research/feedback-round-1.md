# User feedback rounds — 2026-10-26

User explicitly authorized choosing clear items and implementing/deploying in several rounds, rather than completing all12 together. Existing task stays in_progress/unarchived. This supersedes a whole-batch planning gate: carry out the bounded first round now; no new task or approval request.

## Round1 boundary

- Make the duty canvas actually support creating available RUN/custom/library blocks and explicit deletion, including a real right-click delete action. Keep RUN global document vs duty-reference deletion separate: deleting a duty step removes its reference only. Preserve disconnected-draft recovery and safety validation; do not silently discard detached blocks. Full semantic action unification is a later round.
- Put preparationLeadTicks into the departure-preparation ENTRY block, not an outer form. Arrival and departure entries must be clearly labeled. Keep current wire/runtime semantics and schema; this is editing/presentation only.
- Simplify Scratch toolbars to icon Undo/Redo; retain toolbox/parameter inputs necessary for dragging/editing. Remove redundant append/move/delete controls. Add spacing in affected program/duty/event surfaces.
- Add graph right-click creation of directionless event points at the station/time under the pointer, plus editing/deletion of existing points. Use one document history transaction, existing event references and explicit errors for deleting a referenced point. Do not implicitly spawn new asynchronous runtime tasks: a point is the existing shared time anchor referenced by WAIT_EVENT.
- Remove the redundant infrastructure-line selector from a run; retain the existing run.lineId inherited when created from its chosen line, for limits/Reset/compilation. Do not change line caps or routing behavior.
- Use simple numeric visible IDs for new event points in this round. Broader naming/program-file management follows later.

## Defer, do not present as fixed

Program-library file management and real deletion/rename semantics (feedback4/5); full common duty/library action catalog and moving formation configuration into blocks (1/10); broader visible numeric ID policy (8). Remove operational-trip/formation standalone UI only once corresponding block configuration exists. Display action behavior needs an honest explanation: currently updates per-registered-unit operational-trip JSON, exposed via state APIs; no automatic train model/display-screen adapter exists. Include this scope/limitation in final explanation and later UI help.

## Ownership and acceptance

Program editor worker: ProgramBlockEditor, StationStopProgramEditor, their CSS/tests. Duty worker: DutyBlockEditor, dutyBlocks, DutyManagement/tests. Root: Graph/event UI and helpers, DutyEditor wiring, RunRouteEditor selector removal, i18n, specs and integration. Do not overwrite another owner's edits; coordinate required APIs.

Run frontend full tests/build, affected Java boundary tests if payloads change, package shadow artifacts, actual HTTP browser including first-page locale, root lead roundtrip, toolbox drag/create/delete/context menu, graph coordinates after pan/zoom, event save/reload. Deploy via normal save/stop, checksum snapshot and exact artifact replacement, verify service and public assets; preserve task for user game testing. No automatic archival or push.

## Verification completed 2026-10-26
- Frontend: 36 test files, 229 tests passed; TypeScript/Vite build passed. WebUI tests and both shadow JAR packaging passed.
- Real browser, insecure local HTTP, packaged Java WebUI: duty RUN toolbox drag, numeric ID allocation, save, right-click delete, and preservation of underlying run verified.
- Station preparation ENTRY edited 20 to 40 and persisted; actual toolbox step drag, connection, and persisted arrival steps verified. No captured browser errors.
- Graph right-click creates numeric event point, persisted through Java editor-document API.
- Fixture is isolated local QA data; compile/game simulation acceptance remains user validation.
- Trellis review recorded in feedback-round-1-check.md. Task stays open for later rounds.
