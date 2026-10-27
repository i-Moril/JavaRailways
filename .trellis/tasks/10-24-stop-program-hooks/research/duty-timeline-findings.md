# Research: Duty timeline model and measured program duration

- Query: Replace duty Blockly sequence with timed tasks; determine correct RUN spans and custom-program point/manual/simulated spans without weakening execution gates.
- Scope: internal
- Date: 2026-10-27

## Findings

### Files found and current contracts

- `WebUI/frontend/src/authoring.ts:30-53`: duty selector, RUN/FUNCTION/PROGRAM/INLINE discriminated elements and referenceVehicle. PROGRAM start is `call.tick`; INLINE tick currently optional (immediate continuation). FUNCTION PLACE_INITIAL is not a timed task.
- `WebUI/frontend/src/authoring.ts:316-317`: origin strings are `duty:<id>/element:<id>[/run:<id>][/function:<id>]`.
- `WebUI/frontend/src/authoring.ts:415-493`: assignment/location/chronology validation. Effective RUN end is `max(effective terminal arrival + minimum dwell, terminal departure)`, beginning is first departure. Arrival override is authoritative. Duplicate assignment and movement continuity must remain validated.
- `WebUI/frontend/src/authoring.ts:603-703`: source generator emits array order as actual service order. RUN is an origin-scoped runSegment. PROGRAM `.at(call.tick).program(...)`; INLINE optional `.at(tick)`. PLACE_INITIAL extracted into timetable initialization independently of element order.
- `WebUI/frontend/src/types.ts:117-147`: parameter values and immutable bound snapshot plus call arguments. Programs call by programId and bound revision/contentIdentity, not mutable live library entry.
- `WebUI/frontend/src/DutyEditor.tsx:101-133,308-318`: current full simulation uses every duty referenceVehicle, detects same selector mapped to conflicting profiles, rejects stale async responses by owner/world/input key and raw-draft revision. Input signature includes world, generated documentHashInput, threshold, sorted profile assignments.
- `WebUI/frontend/src/api.ts:16`: POST `/api/v1/worlds/{world}/timetables/simulate` with timetable/profileAssignments/delayThresholdTicks. Existing endpoint sufficient; response can be extended.
- `WebUI/src/main/java/work/art1st/javarailways/webui/WebUiApplication.java:447-449`: authenticated forwarding to Bukkit; no independent simulation model.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt:631-681`: current response serializes only final per-duty snapshots, no execution history.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt:68-96,160-205`: Completed/DelayExceeded/Failed/PeriodEnded, all with final runs. Real 50ms physics/dispatcher stepping; failed/threshold/max-time simulations are valid partial outcomes, not successful measurements of uncompleted tasks.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt:191-233,6737-6795`: snapshots expose active/next step address and timetable clock only. Polling once after tick loses multiple instant steps and cannot reliably identify program entry/exit.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt:305-416`: `origin` scoped tracing; program expanded into ordinary steps, with nested program step origin suffixed. `at(tick)` requires nondecreasing ticks. `appendProgram` requires prior physical CHECK or boundary acquisition.
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationPrograms.kt:94-165`: WAIT_DURATION compiles STOP (actual blocking duration); WAIT_UNTIL compiles scheduled gate; MOVE has actual route runtime; EXTENSION may block, reverse/formation/storage have real completion semantics. Static sum does not determine duration.
- `WebUI/src/main/java/work/art1st/javarailways/webui/EditorDocumentValidator.java:173-195`: INLINE has exact allowed field list, so adding timeline planning metadata needs backend validation and tests. PROGRAM shape checked additionally through shared program validation.

### Recommended implementation boundary

1. Timeline visual model/pure helpers own planned projection (RUN start/effective end, PROGRAM/INLINE start, chosen duration mode, point as zero duration), chronological insertion/move, interval packing, selection/context removal. Preserve IDs, bound snapshots and existing source provenance.
2. Keep actual execution order deterministic (sort/order timed elements on edits, define tie ordering), enforce current lower-bound/location guards. Planned duration is a player estimate for visualization/conflict warnings; do not inject STOP or forcefully finish work after this estimate. Actual next task still waits for previous real completion plus planned gate.
3. Manual duration metadata should live on the invocation, because arguments, profile and preceding task change duration. Point is explicit zero. Simulated mode pending until a matching successful real measurement exists; do not render unknown as measured zero.
4. Simulation duration must be `actual completed task time - actual started task time`, excluding waiting until another task's schedule. Requires task boundary telemetry from real Dispatcher advancement, including final action completion. Current results cannot produce this value.
5. Strong minimal telemetry option: compiled task boundary ranges/markers, or explicit opt-in observer in Dispatcher that reports actual entry and completion on source-origin scope transitions. A pure per-tick snapshot collector is insufficient. If using scope transition on `advanceService`, emit previous scope end before assigning next task address or waiting for next schedule, and flush last scope only when the real blocking operation has completed. Starting observer before checking future scheduled gate wrongly includes preceding idle time.
6. Address top-level index plus branch path is required for generic executed step telemetry. Scope identity should use element identity/compiled origin, not programId (same library called more than once).
7. For transient measurements reuse existing `simulationState` input-key gate. Do not persist raw measured duration as permanently valid. If persisted measurement desired, store explicit input identity and invalidate on definition/arguments/start time/profile/infrastructure/signals change; client document hash alone cannot verify live infrastructure revisions.
8. Full isolated timetable simulation is the correct context: includes other tasks/traffic and reference profile, and executes actual extension/door providers. Measuring program alone by summing blocks or cutting surrounding timetable can omit prerequisites/resource contention and is not equivalent.
9. Failure/period-ended result may include completed earlier tasks; expose only individually completed telemetry as measurements and mark incomplete tasks unknown. Never use whole service elapsed time as one program duration.

### Existing execution pitfalls to cover

- Dispatcher `advanceService` line 4115 tests cursor completion, then assigns active address at 4117. Generic STOP at 4439-4450 advances cursor immediately but completes after DWELLING countdown at 1620-1624; next index is not completion.
- Reverse/Halt similarly advance cursor before asynchronous completion at 4410-4433. Movement has its own runtime. `BeginStationStop` advances before station program completes (4136-4150). Active/next cursor-only inference must account for all barriers.
- Last task completion has several terminal paths (2396-2414,2965,4538), not just switching to a next step. Last WAIT and last MOVE need explicit tests.
- Delayed service can wait before program starts; duration should exclude this pre-entry delay. WAIT_UNTIL inside program must count its wait.
- Zero emitted operations after compile-time branch selection cannot silently masquerade as a measured invocation unless a task boundary survives compilation.
- Station-only ASYNC/AWAIT/door/END capability is different from general duty library expansion; timeline rewrite must not claim newly executable action categories without runtime work.

### Suggested verification

- Pure helpers: origin parsing/multiple same program calls, arrival override RUN end, explicit terminal departure, manual 0/positive, unknown simulated interval, stale signature, equal-start stable ordering, out-of-period end, collisions as warnings (not fake blocking).
- Dispatcher/simulation: final WAIT_DURATION, WAIT_UNTIL, instant-only, multi-step same tick, reverse/extension completion, last MOVE, blocked/incomplete failure, two tasks separated by idle schedule, late start after predecessor.
- Cross-layer: HTTP timing DTO actual values and bounded output; compiler provenance / program expansion range; editor field validation round-trip and rejected invalid duration.

## Related specs

- `.trellis/spec/javarailways/shared/versioning.md`: current undeployed/unreleased development format updated directly, no invented schema generations or legacy runtime branches.
- `.trellis/spec/javarailways/simpleato/simulation.md`: actual isolated providers/physics, no duration-only fake gateway; feature closure/cleanup remain intact.
- `.trellis/spec/webui/frontend/studio-authoring.md`: same profile conflict gate, current compilation gate, stale report invalidation.
- `.trellis/spec/webui/frontend/program-blocks.md`: library snapshot binding, raw incomplete drafts, prior duty Blockly scope (must update to timeline).
- `.trellis/spec/webui/backend/index.md`: bridge authenticated, JSON DTO changes test frontend+backend.

## External references

None required; findings concern current repository contracts. No additional timeline package is necessary for a local SVG/React view.

## Caveats / Not Found

- No current per-step/program execution duration telemetry found. Existing final snapshot response cannot truthfully fulfill simulated duration by itself.
- No live game/browser acceptance performed; this is source research only.
- Memory registry only located refactor context (`MEMORY.md:71-80`); all contracts above rechecked from current source.
