# UI completion validation — 2026-10-18

## Provenance and isolation
- Worktree `/home/user/.codex/worktrees/webui-completion/JavaRailways`, branch `codex/webui-completion`, base `45246995`. All product writes here; no main-worktree source edits, push, migration or deployment.
- Temporary dependency copy and browser tools; no project dependency/lock changes. Local Vite ports 5178 (dev demo) and 5179 (normal API mode with browser-scoped response fixtures).

## Offline evidence
- Final implementer gate: 89/89 tests, frontend production build and diff check pass. No configured lint script. Existing bundle-size warning remains.
- Independent Trellis reviewer: first pass identified late world/document response writes, duplicate duty identity and scope-selection failures; recheck identified edited-during-delete identity retention. All fixed. Reviewer independently ran 11 focused tests and diff check; final responsive/localization recheck found no blockers.

## Browser checks executed
- Fresh final Chromium session loads and navigates to Duties with zero page exceptions. Earlier development HMR provider errors were confined to the mutable dev session; full reload/new session confirmed clean final loading. Existing Ant Design Button.Group deprecation warning is unchanged.
- Existing graph point keyboard editing: 2000→2002 with stable focus and Undo enabled.
- Pointer cancellation leaves AP102 departure5600 unchanged; a12px drag commits5950; Undo restores5600. After +X zoom and pan,12px commits5869; Undo restores5600.
- Reject duplicate duty name without modifying either identity. Create/rename DutyB, move AP102 from A to B: counts1/1; Undo2/0; Redo1/1. Page navigation retains document edits.
- Compile fixture result makes Publish available; missing profiles block simulation. Same selector mapped to different profiles shows conflict and disables both simulation controls; hides old report. Matching profiles allows returned COMPLETED report with both service rows.
- DOWN creation gives AirportT2→Harbour station order. Untimed/unassigned run shows actual path-addressed issues and disables compilation/publication.
- Reset uses selected profile and fills all down-route times. Copy creates new unassigned run shifted1200ticks; Undo removes the copy. Injected line-simulation failure shows error and leaves document timings unchanged.
- Chinese/dark and English/light layouts exercised. At1000×800, NewDuty right984 and language control right986, both inside1000px viewport; at1600×1000 normal design retained.
- Normal API-mode guest fixture exposes only RailMap. Two-world fixture: pending B metadata clears old LineA/NewRun; switching backA then releasingB response retains LineA.
- Delayed A document response released after switchingB leaves B run library empty; NewRun then creates BStart→BEnd.
- Delayed DELETE while editing TimetableID: successful same-owner response retains `changed-during-delete`, clears document association to NewLocalDraft and marks Unsaved.

## Limits
Browser compile/simulation were dev-demo or HTTP-fixture responses. No live Bukkit/Basic simulation, actual publish, world runtime, exhaustive function-type interaction matrix or performance-scale claim. Browser scenarios are recorded evidence, not a newly committed end-to-end test runner. Local screenshots are external review artifacts.
