# Design

## Presentation and navigation
Use the approved prototype and existing studio CSS, theme and bilingual Ant Design tokens. Keep Timetable, Rail map, Lines, Duties and Validation. Remove standalone JS timetables and line Simulation navigation/page composition and UI-only dead code, while preserving formal generated JS save/compile/publish and full timetable simulation inside Validation. Keep the existing three-column timetable workspace and sole mounted editor owner.

## State and ownership
`App` owns authenticated navigation and world-tagged reference resources. `DutyEditor` remains the single authoritative document/history/recovery owner across timetable/duties/validation. Controlled view components project that owner. Preserve current world/document epoch guards and snapshot/input binding for asynchronous results.

## Time input commit boundaries
Time inputs keep a local editable value. Only blur/Enter commits to the authoritative caller; Enter followed by blur must not produce two transactions. Escape or a changed world/run/call identity discards stale draft input. Keystrokes do not mutate document/history/recovery or the running graph; graph and translated committed display update only after accepted commit. Apply the same focused input mechanism to time-valued node/dialog/function controls without introducing a new form framework. Non-time fields retain their existing behavior.

## Node menu and suffix shift
Graph points remain selectable/keyboard-activatable, with canvas pan/zoom retained. Remove point dragging, keyboard time nudging and their obsolete gesture helpers/tests. Clicking an actual point opens a node editing toolbar near its displayed location. The parent owns selected node identity and the current run, receives typed point identity plus positioning information from Graph, and exposes direct time edit and Shift.

Remove the old Move run ribbon action and whole-run movement dialog. The replacement Shift dialog uses the clicked event as fixed anchor and accepts either an absolute target tick or signed offset. Its pure transaction operates the selected event and all later events of that same run: an arrival includes its paired departure and following calls; a departure leaves its own arrival/earlier calls unchanged; PASS has one effective passage event. Earlier timestamps/overrides must retain their values.

Resolve actual authoring values and effective arrival overrides before calculating delta, never invented graph projection fallback ticks. Apply one delta to the selected suffix, preserve relative durations within it, validate safe integers, non-negative/period boundaries, chronological order and stop minimum dwell at the unchanged/shifted boundary. Reject illegal input with actionable feedback; do not silently clamp to another target. Preview the affected suffix/count, then one Apply transaction produces one Undo. Unrelated incomplete duty assignments do not block a time-local legality check.

## Regeneration and copying
The selected run timing tool regenerates only its own calls using existing line estimation, profile and dwell parameters. Its start tick comes from the existing origin departure, without a separate first-departure override input. A missing origin departure shows a requirement to enter it in the calls table; no silently invented departure. Reset failure/stale result leaves the document unchanged.

A dedicated Copy run ribbon action opens interval/count inputs. For count N and interval D, create fresh copies at D,2D,...,ND relative to the selected effective run. Copies have fresh run/call IDs, remain unassigned and retain route/direction. Reject the complete batch if any generated time is invalid; successful copies enter history once. Keep these controls out of regeneration settings.

## Existing validation and contracts
Full simulation remains within Validation with existing profile mapping and report behavior. Authoring issues, generated source, server compile/world validation and runtime report remain separate contracts; show only actual results. Browser API/types/backend/persisted shapes and schema versions are unchanged. No legacy data migration, new backend route, runtime algorithm, template storage or persistent panel preference is introduced.

## Verification and integration
Run meaningful pure suffix tests for event order, overrides/PASS, arrival versus departure, absolute/offset legality, rejection atomicity and history. Exercise controlled deferred input commit behavior, removed navigation/tool absence and retained Validation simulation. Browser checks use production components, explicitly labeled demo/response fixtures; verify local typing versus committed graph/history, node click/shift, earlier event preservation, regeneration origin, dedicated copy and removed point drag. Preserve prior world race fixes. Work only in the UI worktree; no deploy/push. Previous commit plan is superseded and will be replaced after revision checks.
