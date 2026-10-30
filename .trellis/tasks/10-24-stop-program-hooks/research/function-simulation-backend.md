# Research: Standalone function duration simulation backend

Historical exploration only. The 2026-10-30 user correction and function-simulation.md supersede initial-facing/active-turnout proposals below: unique configured-direction placement, then optional real in-place reverseTrain; no opposite-side respawn.

- Query: Reuse the actual isolated timetable runtime for one custom function with explicit directed waypoint and ALONG/OPPOSITE initial placement.
- Scope: internal; read-only source research, no product edits or Git operations
- Date: 2026-10-29

## Findings

### Proposed transient request and response

Authenticated `POST /api/v1/worlds/{world}/programs/simulate`, forwarded to Bukkit `/worlds/{world}/programs/simulate`:

```json
{
  "programId": "p",
  "definition": {"parameters": [], "steps": []},
  "arguments": {},
  "startTick": 100,
  "profile": {"type": "WORLD_TRAIN", "id": "actual-train-id"},
  "initialPosition": {"wayPointId": "point", "facing": "OPPOSITE"},
  "plannedContext": {"dutyId": "actual-duty-id"},
  "maximumDurationTicks": 24000
}
```

- Exact current definition/arguments are supplied, allowing unsaved library edits and embedded bound snapshots. Do not dereference latest library by ID behind the caller's back.
- `plannedContext` is optional for library simulation. Only editable-plan fields actually known should be supplied; missing required context must produce an honest compilation error. Existing duty calls only supply dutyId. Do not fabricate run/station/line context from reference vehicles.
- Effective base direction: destination.approachDirection, else actual rail operatingDirections; reject if both absent. ALONG uses base, OPPOSITE uses base.opposite(). Capture this on live world owner with infrastructure/profile snapshot, then execute detached state.
- No persisted simulation duration/position model or formatVersion change is needed. Measurement remains transient and input-bound.
- Return existing result status, simulatedTicks, runs/tasks plus compilationTrace and requested/effective initial-position values. A completed matching task's `endTick-startTick` is the measured duration. Do not use total simulatedTicks (includes scheduled idle time), next task, or final run time as a substitute.
- Existing task timing supports full-call boundaries, not per-instruction timing. compilationTrace provides source identities only; a detailed per-step timing timeline requires new actual execution observation and cannot be claimed from current output.

### Existing reusable flow and files

- `WebUI/src/main/java/work/art1st/javarailways/webui/WebUiApplication.java:162,447`: authenticated timetable simulation proxy. Add dedicated transient route with the same session/filter/bridge error ownership.
- `WebUI/src/main/java/work/art1st/javarailways/webui/StationProgramValidator.java:16-26`: strict current library AST validation (including explicit library-unavailable actions); `validateArguments` checks exact declaration bindings.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt:149-158`: shared bounded simulation executor, 2 workers + queue8; stop shuts it down.
- Same file `:488-519`: source worker compilation -> TimetableScriptNormalizer -> runtime restore -> live-world validation. Dedicated function setup should use worker compilation but defer validation of any ephemeral origin to the actual detached target where it exists.
- Same file `:544-628`: profile DTO parsing, capture inside await/withWorld, worker create/use/simulate, 429 queue-full, 60s wall limit with task.cancel(true), failure mappings.
- Same file `:631-693`: truthful result JSON and flattened `tasks` timings.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableScriptSandbox.kt:109-143`: `compileSource(source,id)` creates pure saved model in isolated compiler process, has independent bounded permits/deadline, source512KiB/output8MiB.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt:317-327,400-430`: `task(taskId,tick,callback)` emits paired native boundaries; `program` expands current AST and preserves program/step trace origins.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationPrograms.kt:33-178`: exact argument checks, native actions and static plan branch folding. Reuse it by JSON-quoting definition/arguments into the tiny DSL source; do not write a second interpreter or sum durations.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt:98-178`: static capture, immutable control/behavior profile capture, required domain features, real extension preparations, same revision stamp.
- Same file `:181-240`: actual isolated world, prepared domain features, per-car model operation gateway, actual Dispatcher/ATO/control wiring.
- Same file `:243-343`: seeding and acquisition-ledger ownership. Named selectors currently reuse one seeded train per train name; first acquisition/first CHECK determines placement waypoint.
- Same file `:379-423`: actual CoreTrain geometry creation and `setLocation(head)` under frame boundary. Current implementation has `destination.approachDirection ?: AB` and does not honor operatingDirections here; dedicated simulation must not inherit AB fallback.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt:57-99,140-205`: request max ticks within period, actual isolated stepping at50ms, Completed/DelayExceeded/Failed/PeriodEnded.
- Same file `:224-239`: operation/domain cleanup waits for actual worker termination; preserve existing create/use failure paths.

### Minimal single-call DSL shape and initial-origin issue

A conceptual tiny source is:

```js
function main(timetable) {
  timetable.setPeriodDays(periodDays);
  timetable.service('function-simulation', timetable.takeFromWaypoint([originId], startTick), t => {
    t.task('function', startTick, call => call.program(programId, definition, arguments, plannedContext));
  });
}
```

Use acquisition rather than only `t.check(originalWaypoint)`:

1. `TimetableValidator` in `Timetable.kt:695-716` requires prior acquisition or movement before STOP/EXTENSION. A CHECK-only standalone WAIT function therefore fails even though movement programs can previously pass.
2. `TimetableDispatcher.validateAtWayPoint:6270-6287` requires original destination.approachDirection. Seeding OPPOSITE then CHECK/acquire(original) immediately fails for a directed destination. A seed-only facing override is insufficient.
3. Neither normal CHECK nor normal waypoint acquisition should be weakened. Opposite is an explicit simulation initial condition, not a general relaxation of real directed waypoints.

An isolated-only origin anchor at the exact physical rail/arc can support acquisition and selected facing, but a naive generic alias is **not semantically equivalent** for movement:

- `OperationalRoutePlanner.kt:98-109` derives same-station pruning exemptions from endpoint destination roles, and every stored waypoint is a potential blocker. A REGULAR alias loses the original source station identity; a differently directed alias adds a new intermediate blocker for later paths.
- Therefore an alias requires a narrowly scoped, transient alias-to-real-origin association used consistently by route-origin metadata and blocker projection: canonical original identity for station exemptions; aliases excluded from blocker enumeration; actual start-facing remains the user's explicit selection. Do not remove real original blockers, change original targets, alter source-world destination direction, disable pruning/uniqueness, or ignore rail-direction restrictions.
- Alternative is a transient service-initial-condition context understood by seed, initial acquisition and validator, while preserving original waypoint identity throughout. This avoids extra graph waypoints but needs explicit consistent injection across begin/start/revalidation paths (`TimetableDispatcher.kt:532,676,4835`) and validation's initial requiredStartDirections.
- `SimpleATOInfrastructureSnapshot.kt:143-263` can materialize an ephemeral destination after Basic restore; Basic capture excludes destination-derived waypoints. Such a detached snapshot change is not a persisted schema change. However, that mechanics alone does not solve the pruning issues above.
- Opposite facing on a rail with one-way operating rules may be a valid stationary placement yet have no legal onward MOVE. Keep real route rejection; do not implicitly reverse the rail rule.

### Actual current function action support

Library worker AST supports SET_DISPLAY, RESET_DISPLAY, WAIT_UNTIL, WAIT_DURATION, EXTENSION, BRANCH, REVERSE, MOVE, STORE_AT_DEPOT. Worker retains a SELECT_STOP DTO, but current WebUI library validator explicitly rejects SELECT_STOP. WAIT_EVENT is frontend authoring sugar resolved before compilation, requiring event context; an unresolved wait cannot be guessed for library-only simulation.

OPEN_DOORS/CLOSE_DOORS/END/ASYNC/AWAIT are station-only; the current library validator rejects them and the library expansion does not interpret them. Do not widen function language incidentally or claim its duration is based on parallel child tasks that it cannot currently define. Runtime selected-extension/model effects can still be asynchronous internally and must await real tickets.

### Fixed-unit and real operation semantics

- `IsolatedTimetableSimulationFactory.kt:137-149` captures `SimulationFormationBlueprint` only for duties involved in formation events today. Normal standalone functions therefore have no cloned fixed-unit authority.
- `SimulationFormationBlueprint.kt:27-63` requires WORLD_TRAIN, registered rolling-stock record, exact physical membership and complete registered units, normalizes operating order, then remaps actual car IDs to isolated IDs.
- For programs requiring SET_DISPLAY/RESET_DISPLAY or trainInfo UNIT_COUNT, opt into this exact capture even without formation events and register its existing in-service stock/claims in seed. Other programs must not suddenly require registered units.
- TRAIN_TEMPLATE has physical/model profiles but no complete-unit authority in this boundary. Fail explicitly for fixed-unit-dependent functions unless a real source of unit membership is added separately; never invent one unit or count cars as units.
- `SimulationControlProfiles.kt:87-123` captures WORLD_TRAIN model/physical blueprints but not location, runtime tickets, reservations, or active controller. This request's explicit placement supplies location.
- `SimulationTrainOperationGateway.kt:39-77` executes actual per-car model JS and completion tickets without Bukkit rendering effects; unsupported operations reject.
- `TimetableExtensionRegistry.prepareSimulation:279-293` rejects any registered provider lacking a simulation preparer. Captured domains/features are real prepared isolated instances, never live provider closures.

### Bounds and completion

- Dedicated simulation must not use default delayThreshold1200: a valid WAIT_DURATION>1200 appears late at final task end and is otherwise truncated as DelayExceeded. Use a threshold strictly beyond the bounded total simulation horizon for single-call measurement; retain real failures.
- Keep both bounded total simulated ticks and existing60s worker wall deadline/cancellation. `startTick+maximumDurationTicks` must be safe/finite and not exceed server policy; `periodDays=ceil((endTick+1)/24000)` is sufficient to contain horizon. Existing engine starts at logical0, so scheduled idle time consumes steps/wall budget; do not subtract it from total work limits.
- At horizon/cancellation/failure preserve an observed start and absent end, returning no successful duration. Empty/instant programs can legitimately measure0; actual tick discretization comes from the engine.
- Existing transient task timings clear on checkpoint restore by design; this isolated run observes its own START and END.

## Related specs

- `.trellis/workflow.md` (loaded; active task supplied by parent)
- `.trellis/spec/javarailways/simpleato/simulation.md`
- `.trellis/spec/javarailways/shared/versioning.md` (read: no persisted change/version bump)
- `.trellis/spec/bukkit-plugin/backend/index.md`
- `.trellis/spec/bukkit-plugin/backend/simulation-car-behavior.md` (index linked; source tracing used for specific behavior)
- `.trellis/spec/webui/backend/index.md`
- `.trellis/spec/webui/backend/http-repository-packaging.md`

## Tests to extend

- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactoryTest.kt`: real profile fixture, AB/BA, point override rail direction precedence, missing-direction rejection, explicit opposite head/body placement, short-track failure, no live mutations; complete-unit info/display opt-in.
- `BukkitPlugin/src/test/kotlin/work/art1st/javarailways/scriptworker/StationProgramScriptTest.kt`: tiny one-acquisition/task/program source, argument/plan-condition errors, exact compilation source trace.
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt`: full wait result/task duration, scheduled-start exclusion, no fabricated duration at bound/failure, reversal and actual movement.
- `WebUI/src/test/java/work/art1st/javarailways/webui/WebUiApplicationTest.java`: auth requirement/new route proxy/status and body, no token leakage.
- Add dedicated request boundary tests for invalid shape/missing enum/unknown waypoint/reference, bounded queue/time horizon, and MOVE with original station waypoint same-station/directional pruning preserved under chosen initial-state strategy.

## External References / Versions

No external references needed. All findings verified against supplied current worktree source; no internet or third-party API assumptions.

## Caveats / Not Found

- Research only, no tests executed or product code changed. The ephemeral-origin strategy needs the pruning/identity acceptance tests before it can be considered behavior-equivalent.
- No per-instruction execution duration stream exists in the inspected current HTTP/runtime output.
- Memory registry `MEMORY.md:74` used solely to locate/confirm the task's implementation worktree; all technical findings above were verified in current source.
