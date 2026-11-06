# Frontend implementation handoff

## Changes

- `authoringMinimumDwellForCall` is the shared PASSENGER + STOP-only owner for generator, document validation, copy/shift, graph transactions and graph bounds. ORIGIN/TERMINAL emit explicit zero, preventing accidental default injection at the transport boundary.
- Removed `pendingDwellUntil` and minimum inheritance into later runs/program movements. Explicit terminal departure remains the earliest next task time; connection editing also preserves arrival ordering and an existing explicit terminal departure.
- Added `authoringLineSimulationRequest`: reuse the existing generator for one selected run, including original run/duty planned context, shared station programs, exact route/vias/offsets/doors and absolute event waits. Temporary plan bounds are firstDeparture with zero minimum; outer fixedDwell/minimum remain independent. No user document is changed. Unassigned and partly untimed runs work; unrelated duty tasks/library references are not compiled into the measurement.
- HTTP input is `LineSimulationRequest`: prior route/profile fields plus `measurementSource`, `firstDepartureTick`, `fixedDwellTicks`, `minimumDwellTicks`, ordered `calls` identities (including optional offset). No old published timetable/service lookup fields.
- Reset accepts ordered integer `calls` results only, checks identity/status/chronology/fixed dwell/origin, and never computes authoritative ticks from display seconds. Partial/invalid results or an explicit terminal departure before measured arrival reject before history mutation. PASS has no stopping-error observation; metric type is optional.
- Current input revisions cover world/document/drafts plus selected run/profile/fixed dwell/infrastructure metadata. Changing inputs and then changing them back does not revive an old request. Errors or stale responses cannot write the document. Successful Reset uses the existing single `replaceRun` history transaction.
- `LINE_DWELL_INSUFFICIENT` has bilingual summary plus real station/tick diagnostic. Local fixed-dwell/minimum conflict also has localized summary. Latest user simplification is reflected in tooltip: custom early door closing is allowed, early departure is not.
- Demo-only fixtures now return ordered calls and have explicit AP101/AP102 infrastructure line IDs. They remain frontend mock data, not physical simulation evidence. Updated direction label from estimate to simulation.

## Checks

- Targeted initial dwell tests: 100 tests passed.
- Full frontend `npm --prefix WebUI/frontend test`: 68 files, 501 tests passed (14:16 UTC run), zero failures.
- `npm --prefix WebUI/frontend run build`: final TypeScript/Vite rerun passed; existing large-bundle warning remains.
- `git diff --check`: passed during implementation.
- No Gradle, commit, deployment or server mutation performed by this agent. Main owns real browser smoke and Java forwarding/compiler checks.

## Integration / review follow-up

- Backend owner `/root/exact_projection` agreed request/response shape and code `LINE_DWELL_INSUFFICIENT`. Verify backend keeps PASS/TERMINAL departure absent/null, ORIGIN arrival absent/null and STOP integer departure minus arrival exactly fixedDwell. Backend may include extra stationId in results; frontend identity authority is callId + order + kind.
- Main owns updates to `studio-authoring.md` (replace obsolete terminal minimum/carry/connection/timeline statements) and shared simulation spec. This agent was scoped to frontend source and this note.
- Main owns real-browser failure/no-history/success/undo/stale evidence, real generated-source → Graal → domain evidence, and physical same-input line/global simulation comparison. Passing frontend mock/transaction tests does not establish backend physics correctness.
- Origin offsets are transmitted explicitly. Backend owner confirmed the approved boundary: nonzero origin offset is explicitly rejected because existing source generation cannot represent it; intermediate/terminal offsets remain supported. No silent offset loss or speculative initial placement change.
