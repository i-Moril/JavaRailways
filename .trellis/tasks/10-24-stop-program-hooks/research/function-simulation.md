# Standalone function duration simulation — 2026-10-29

User approved implementation after UI prototypes; the 2026-10-30 correction below supersedes all prior opposite-facing initialization semantics. Existing task remains in_progress, no new task, no commit/deploy/archive in this batch. Worktree /tmp/javarailways-blockly-deploy; baseline33c44cb5, preserve untracked heap dump.

## Behavior boundary

Simulate exactly one current library function invocation through existing sandbox expansion, isolated factory and real Dispatcher/operations. Inputs: exact definition/arguments, start tick, period, reference vehicle blueprint, eligible existing waypoint and explicit reverseTrain boolean. First establish unique complete placement in configured direction, then perform one actual in-place reverse if requested. Only allow waypoint own approachDirection or rail operatingDirection; own direction wins, no AB fallback. Metadata provides eligible points; server resolves again on owner boundary. UI never guesses topology/placement from map geometry. No arbitrary map placement, language expansion, detached live runtime train copying or persistence/schema changes.

Preserve original waypoint identity, destination semantics and routing constraints. Use an explicit SIM-only standalone service scope for no-movement functions and real configured initial direction; do not create synthetic aliases that affect routing/pruning, weaken LIVE validation, or modify live direction/turnout state. Validate placement against real captured infrastructure and report failure honestly. WORLD_TRAIN is a reference blueprint, not a live-state clone. Registered units are captured when required, never invent units for templates.

Current function library supports its existing synchronous actions (wait/reverse/movement/extensions/display/store); station-only ASYNC/AWAIT/door language is not added. Total actual task boundary duration is measured, excluding pre-start idle/acquisition. Return failure/current source step when available; no invented per-step timing telemetry. Scenario advance tick bound (including initialization/reverse, not just function execution) and existing queue/wall timeout apply; lateness cutoff must not reject a legitimate long wait prematurely.

## Wire contract (transient, no schema version)

GET metadata provides `programSimulationWayPoints: {id,railId,direction:'AB'|'BA',directionSource:'WAYPOINT'|'RAIL'}[]` (empty array if none). The 2026-10-31 identity-only requirement uses the actual waypoint ID for display and filtering; see waypoint-identity-only.md.

POST `/api/v1/worlds/{world}/programs/simulate` (sidecar and bridge):

```
{programId, definition, arguments, startTick, executionTimeoutMillis,
 profile:{type:'WORLD_TRAIN'|'TRAIN_TEMPLATE',id},
 initialPosition:{wayPointId,reverseTrain:boolean},
 plannedContext?:object, maximumDurationTicks}
```

Response:

```
{status:'COMPLETED'|'FAILED'|'LIMIT_REACHED',simulatedTicks,
 durationTicks?:number,startTick?:number,endTick?:number,reason?:string,
 failedProgramStepId?:string,
 initialPosition:{wayPointId,direction:'AB'|'BA',directionSource:'WAYPOINT'|'RAIL',reverseTrain:boolean}}
```

Require all execution fields and explicit reverseTrain true/false; the uncommitted facing enum is removed, with no compatibility path. CURRENT Oct31 correction: independent function simulation has no player-facing or wire periodDays. startTick and positive maximumDurationTicks define a safe-integer bounded horizon (UI initial limit24000); the dispatcher wrapper derives enough days for the horizon and absolute WAIT_UNTIL targets, including parameters/branches. Waits beyond the advance budget yield LIMIT_REACHED rather than an artificial period compilation failure. Failed/partial result never carries successful duration. Frontend resolves WAIT_EVENT against exact current document context where available before request; library has no fabricated event context. Planned context contains only actual caller-known plan fields (library empty; call dutyId only or known equivalent). Server compiles safe JSON as data via tiny DSL wrapper: acquisition at real waypoint + optional actual reverse() + task('function-call') + program invocation. The setup reverse precedes task timing, leaves body positions unchanged, and is not opposite-side placement. Normal validation remains authoritative.

## UI ownership

Shared ProgramSimulationDialog from library and timed operation controls. Library sends unsaved accepted canvas definition; invalid canvas blocks simulation. Timed call uses bound snapshot and arguments, even if source removed. Model/profile initial point/reversal form is transient and keyed by owner/world/source. No default reverse choice. Label the limit as a simulation advance limit; setup may consume it before task timing begins. Exact input changes invalidate old result; request generation prevents A→B→A, close/unmount/world/owner stale writeback. Metadata failures visible, no fake demo simulation.

A result remains standalone, never fabricated into TimetableSimulationResult. Timed operation may explicitly adopt completed measured duration as authored fixed plan duration through existing document history (MANUAL protocol retained; UI calls this fixed duration). This does not force runtime waiting. Existing full-timetable SIMULATED measurements and all-references uniqueness unchanged. Library result has no adopt-to-document action.

## Ownership and gates

Runtime implementer: SimpleATO transient initialization + meaningful real dispatcher tests; IsolatedTimetableSimulationFactory + related helpers/tests (coordinate exact capture signature). Backend implementer: bridge/sidecar endpoints, metadata, sandbox wrapper, request/result/error/queue/auth tests. Frontend implementer: types/api/shared dialog/library/call integration/i18n/styles and focused stale-input/contract tests. Root: docs/spec/contract decisions, serialize Gradle checks, frontend gates/browser integration, independent check. All workers know they share the tree and may not revert others.

Run relevant frontend tests/build and backend/runtime checks sequentially for Gradle. Verify no live changes and no persistent versions/compatibility readers. Failures, undirected/removal, direction precedence/both explicit reversal options, configured initial direction does not alter return destination/route pruning, long wait/time bound, exact wait duration/reverse/movement, real operation behavior, partial unsupported provider, input invalidation/owner isolation and explicit adoption/history. No test success inferred from build alone; inspect XML execution counts.

## User correction — 2026-10-30 (CURRENT)

All actual train-seeding simulations share one Core-owned unique-placement boundary. Resolve declared waypoint/rail direction, enumerate the relevant directed topology including turnout alternatives, and solve real configured-car geometry. Current active turnout choice alone does not prove uniqueness. Count distinct actual complete body placements: 0→unplaceable, 1→accept only after all alternatives checked, 2→ambiguous. Solver support bounds/cancellation/branch budget cannot silently turn an incomplete search into proof; report unproven instead. Do not use nominal sum of car lengths as a curve arc bound. Alternatives wholly beyond the actual tail deduplicate. No new program executor, topology guess, live mutation or hidden respawn.

Then optional reverse is an actual in-place operation after initial acquisition and before task measurement. Head/tail exchange without changing occupied geometry; original placement waypoint is now at the opposite operating end, not the new head. Remove prior SimulationInitialCondition user-facing overrides. A small explicit simulation-only standalone-service scope may remain to permit current WAIT-only programs; it derives actual configured initial direction, not a user facing override.

Require both SIMULATION and LIVE regressions proving ATO startup does not issue an implicit reversal or drive backwards to reach a target behind the current operating head. Explicit reverse actions remain supported. Test mixed rail-local AB bases: the physical head direction and retained route-origin direction have different rail-local bases. Keep each in its correct coordinate basis; repeated reversals must toggle the retained origin direction rather than overwrite it from another rail's local AB/BA value. More than one directionally/physically viable placement must fail before any simulation execution.

Core ownership moved to reused function_sim_backend implementer after HTTP source freeze; function_sim_runtime owns Factory/Dispatcher/Validator integration and ATO regressions; function_sim_frontend owns boolean UI/wire/tests. Root owns final gates, independent review, specs and browser verification. No commit/deploy/archive in this batch.

## Final validation checkpoint — 2026-10-30

Implemented current contract and fixed actual mixed-rail repeated-reverse / reverse-to-depot direction regressions. Final Core538/Basic435/SimpleATO643/Bukkit663/WebUI49 tests pass (2328 total); frontend323/53files and production build pass. Browser checked actual inputs/false-true wire/error/narrow layout with an explicit UI-only422 fixture; no live game acceptance. Independent review passed after fixes. See function-simulation-validation-2026-10-30.md for command/result/coverage/limits. Still uncommitted/undeployed, taskin_progress, noarchive.
