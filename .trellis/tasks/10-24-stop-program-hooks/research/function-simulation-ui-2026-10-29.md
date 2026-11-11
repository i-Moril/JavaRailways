# Research: standalone program duration simulation UI and metadata

Historical exploration only. The 2026-10-30 user correction and function-simulation.md supersede initial-facing/active-turnout proposals below: unique configured-direction placement, then optional real in-place reverseTrain; no opposite-side respawn.

- Query: Reuse one function-call simulation entry from the program library and timed operations; identify directed initial waypoint metadata, input ownership, stale-result handling, and a minimal transient HTTP contract.
- Scope: internal
- Date: 2026-10-29
- Worktree: `/tmp/javarailways-blockly-deploy`

## Findings

### Current implementation and exact file owners

- `WebUI/frontend/src/StationProgramLibrary.tsx:23-44`: the library remounts for owner/world, stores selected library baseline separately from the accepted editable definition, and has an existing alive/request generation guard. The `definition` state, not `selected.definition`, is the current valid unsaved program.
- `StationProgramLibrary.tsx:66-69,82-84,93-97`: saving captures the editable definition; renaming intentionally saves the original server definition without discarding the dirty canvas. A simulation button belongs next to Save, using the current `definition` and `valid`, with independent simulation busy state. It must neither save nor rename implicitly.
- `WebUI/frontend/src/ProgramBlockEditor.tsx:59-69,85-91`: incomplete/disconnected blocks retain raw draft state and report invalid immediately; accepted semantic `onChange` only publishes a valid complete definition. Simulating the previous accepted definition while `valid=false` would silently measure the wrong canvas. Disable simulation for invalid drafts, including transient invalid field edits.
- `WebUI/frontend/src/TimedOperationFields.tsx:10-17,27-34`: one existing field component handles graph and duty-owned calls. It resolves a document binding before a live library record; current SIMULATED button invokes a callback for the entire timetable. This is the correct common entry for opening a function-call simulator, rather than another per-page form.
- `WebUI/frontend/src/ProgramFields.tsx:7-13`: reuse `ProgramLiteralInput` for every argument type. It preserves existing JSON-object, integer commit and unit-slot semantics. `dutyTimeline.ts:10-11` provides existing argument defaults; `stationPrograms.ts:15-27` validates missing, wrong-type and unknown arguments.
- `WebUI/frontend/src/DutyEditor.tsx:106-138,314-330`: the current complete simulation is owned by DutyEditor. Its validity is keyed by world, generated execution input, profile assignments and threshold, plus owner epoch and invalid-draft revision for late responses. Independent call simulation must not reuse this busy/validity gate, which requires every duty to have a valid vehicle/profile and the whole document to compile.
- `WebUI/frontend/src/DutyManagement.tsx:43,52-54`: profile selection already uses `${type}:${id}` and metadata descriptors; initialPosition currently uses unrestricted `wayPoints:string[]`. The new standalone initial-position picker should have its own explicitly directed descriptors; do not silently alter unrelated timetable initialPosition persistence in this batch.
- `WebUI/frontend/src/DutyTimeline.tsx:125-127`: referenced graph operations are read-only in duty view; owned operations use the common fields. A simulation preview is not a mutation, but graph-bound results need their own reference-call identity if displayed from a duty.
- `WebUI/frontend/src/App.tsx:104-118,163-164`: App owns world metadata and passes it into DutyEditor, but passes only extension descriptors into StationProgramLibrary. Pass current-world metadata (or directed waypoints/profiles) into the library too; do not fetch a second potentially stale copy of metadata inside each simulator.
- `WebUI/frontend/src/types.ts:56-58,116-154`: wire types live here. `api.ts:4-18,26-30` owns browser HTTP/auth, using one request wrapper. A new call belongs here, not in a component.
- `WebUI/src/main/java/work/art1st/javarailways/webui/WebUiApplication.java:156-179,415-429,447-449`: the standalone Java WebUI owns authenticated API routes and metadata merge, then forwards execution requests to the Bukkit bridge. Library records live here, so a request identified only by library ID would not carry unsaved definitions or immutable bound definitions to Bukkit.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt:352-410`: actual metadata producer. It currently sends waypoint IDs, destinations with approachDirection, profiles, stations and depots. No effective waypoint direction/rail operating direction is currently exposed in the TS model.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/SimulationControlProfiles.kt:49-85,87-120`: existing catalog distinguishes WORLD_TRAIN and TRAIN_TEMPLATE and captures actual controls/geometry/behavior. WORLD_TRAIN selects a source blueprint here; that does **not** imply cloning its current position, doors or runtime state. UI wording must reflect the actual new initial-placement contract.

### Directed starting points

Add an additive transient metadata member, for example:

```ts
interface SimulationStartWaypoint {
  id: string;
  displayName: string;
  direction: 'AB' | 'BA';
  directionSource: 'WAYPOINT' | 'RAIL';
}
interface WorldMetadata {
  // Existing fields remain unchanged.
  simulationStartWaypoints?: SimulationStartWaypoint[];
}
```

Produce it server-side over **actual Basic waypoints**, including station/depot role destinations only when these are already mirrored into that waypoint catalog. Resolve direction using destination approachDirection first, then the waypoint rail's Basic operating direction. Missing rail or both-null direction means no eligible descriptor. The priority mirrors current `TimetableInitialPositioner.kt:110-123`, but **omit its last-resort `ViewDirection.AB`** under the approved new standalone simulation contract.

Require the caller to select `ALONG` or `OPPOSITE` explicitly; unset selection disables simulation. Avoid assigning either option as a hidden default. This is relative to the descriptor direction, not UP/DOWN, geographic east/west, track screen-left/right, or a line destination. The backend must independently re-resolve current direction and existence at capture; client filtering is presentation only. Opposite stationary facing must not be prohibited solely because a rail has an operating direction: placement facing and subsequent permitted movement are separate checks.

Actual placement remains backend physics geometry with the current turnout selection. Metadata eligibility means a direction can be stated, not that the entire train will fit. Insufficient connected track, invalid curve arc, occupied/illegal placement or inability to prepare a required simulation feature needs a real error; no green success preview from metadata alone.

### Minimal transient request/response proposal

An authenticated `POST /api/v1/worlds/{world}/station-programs/simulate` can carry one complete call without touching saved library/editor schemas:

```ts
interface ProgramSimulationRequest {
  definition: StationProgramDefinition;
  arguments: Record<string, StationProgramLiteral>;
  startTick: number;
  profileType: 'WORLD_TRAIN' | 'TRAIN_TEMPLATE';
  profileId: string;
  initialPosition: {wayPointId: string; orientation: 'ALONG' | 'OPPOSITE'};
  maximumSimulationTicks: number;
}
interface ProgramSimulationResult {
  status: 'COMPLETED' | 'FAILED' | 'LIMIT_REACHED';
  simulatedTicks: number;
  startTick?: number;
  endTick?: number;
  reason?: string;
  // Add source-located steps only if the backend captures real execution facts.
}
```

Names/statuses should be settled with the runtime owner and can reuse existing result status vocabulary. A completed duration is `endTick-startTick` for the observed call. Failed/limit/cancelled responses must not be given a completed end or fabricated duration. A full result should identify the captured infrastructure/profile inputs if later freshness checks can use them; currently metadata exposes no infrastructure revision, so frontend cannot honestly guarantee automatic freshness after server-side infrastructure or profile edits.

The UI should assemble a full definition from current accepted library canvas or the document's bound snapshot. Never have the backend re-fetch only the live library ID, and never silently substitute a newer library revision for a deleted/old bound snapshot. Program name/ID is display/source identity, not an execution dependency if the body was transmitted.

Absolute WAIT_UNTIL requires startTick, not only a duration sum. WAIT_EVENT currently resolves from document events in `stationPlans.ts:6-16`. BRANCH providers are restricted to planned timetable fields in `stationPrograms.ts:59-60`; normal duty programs receive only dutyId from `authoring.ts:686`. These dependencies need an explicit runtime decision: either add typed event anchors/plan context to the request when actually available, or reject missing context with a source-located reason. Do not guess nonexistent station/run values or interpret a missing context condition as false. A display station attached to a timed operation is not an initial physical position.

### Shared UI and state ownership

Suggested new owners:

- `ProgramSimulationDialog.tsx`: shared dialog only; selected profile, eligible waypoint, mandatory relative orientation, start time, argument form, input validation, busy/errors/result. Metadata/definition/owner supplied by caller; no library write, no authoring-history write.
- `programSimulation.ts`: pure request construction, canonical input key, completed-duration extraction and transient measurement lookup. Keep execution inputs separate from label/station/manual-duration edits.
- `useProgramSimulation.ts` if useful: one request-generation/owner guard shared by both callers; otherwise keep this guard solely inside the shared dialog.
- Existing `StationProgramLibrary.tsx` opens the dialog for the current accepted unsaved definition. Passing definition changes invalidates displayed result and pending writes; changing selection/owner closes/remounts it.
- Existing `DutyEditor.tsx` owns transient per-call measurement results so duty/graph page changes do not destroy them. It passes an `onSimulateOperation` callback through DutyManagement/DutyTimeline into TimedOperationFields; this should not call the global `simulate()` callback.
- Existing `App.tsx`, `types.ts`, `api.ts`, i18n maps and dialog CSS need narrow wiring changes. `frontendDev.ts` must implement the typed endpoint explicitly or report it unsupported in dev-demo mode; `api` has an exact `typeof liveApi` union at `api.ts:42`.

Keep arguments owned once: when invoking from a timed operation, seed the dialog with its actual committed arguments; if the simulator allows exploratory argument changes, mark the output as a preview and only apply to timeline when the result still matches the operation call. Do not mutate arguments through a second hidden owner. An incomplete uncommitted JSON/numeric argument draft disables simulation even if its previous accepted value exists.

### Duration cache and timeline reuse

Current measurements are intentionally transient. `timedOperations.ts:22-27` requires a COMPLETED **whole timetable** result and exactly one serviceId/taskId pair with complete integer endpoints. `projectTimetableOperations:33-43` shows a graph span only when every referenced duty invocation measured exactly the same duration; `dutyTimeline.ts:32-52` consumes that same result. There is no persisted measured-duration cache.

Store standalone results separately, keyed by at least owner/world/document identity, dutyId/elementId (or graph operation ID for preview), exact bound/unsaved definition, arguments, startTick, selected profile, initial waypoint/relative orientation and max ticks. Include explicit event/plan context if supported. Name/display station/manual planning duration are not execution inputs. Merely matching taskId or programId is unsafe across documents/services.

Do **not** synthesize a fake `TimetableSimulationResult{status:'COMPLETED'}` with made-up tasks from an isolated function measurement. That would assert that all duties completed and erase the distinction between full-context execution and a standalone scenario. Add an optional measurement lookup/source argument to existing timeline/graph projections, or show the isolated result separately until a matching invocation can use it. Keep whole-timetable results authoritative for an actual contextual invocation when available. A graph operation referenced by multiple duties must not get one shared measured span from a single arbitrary reference.

### Required tests for implementation

1. Metadata: waypoint own direction takes precedence over opposite rail direction; rail-only eligible; neither/null omitted; missing rail omitted/error per producer contract. Backend must reject a now-undirected or removed waypoint even if it was in cached metadata.
2. Pure UI request validation: mandatory profile/eligible waypoint/orientation/startTick; parameter missing/type/extra checks; no default AB or default relative orientation. Library unsaved definition is used; invalid raw canvas blocks calls.
3. Snapshot ownership: current library definition differs from bound snapshot; timed-operation simulation sends bound definition, including a snapshot whose library source was deleted. Live library refresh/adoption only invalidates when the actual bound input changes.
4. Async: defer request A, switch program/call/world/owner or modify body/args/tick/profile/position, resolve A; no stale result/error/busy writes, no document/history mutation. A second request supersedes the first. A→B→A needs a generation/epoch, not just JSON equality. Unmount/cancel disables writeback and permits the next owner to simulate.
5. Argument raw fields: unresolved numeric minus or invalid JSON disables submit; Escape restores validity; blur/CtrlEnter commit once. Reuse ProgramLiteralInput contracts.
6. API: exact encoded endpoint and body; unsaved definition transmitted verbatim; no save/fetch-library prerequisite; 401 dispatches existing unauthorized event.
7. Result/cache: only actual COMPLETED result with coherent start/end yields duration; failed, cancelled, duplicate/missing timings and tick limit remain unknown. Parameter/start/profile/waypoint/orientation/context changes invalidate. Label/station/planning-duration edits do not fabricate a fresh execution.
8. Projection: distinguish standalone scenario timing from whole-timetable timing; independent duty references with different durations retain per-duty spans and no falsely shared graph duration. No fabricated timetable success report.

Existing test patterns: `lineSimulationApi.test.ts:5-18` checks fetch request serialization; `TimedOperationFields.test.tsx` and `DutyTimeline.test.tsx` cover controlled rendering; `timedOperations.test.ts:21-45` already guards scoped identities, stale semantic inputs and differing graph-reference durations. `ProgramJsonInput.events.test.tsx:15-27` demonstrates committed/invalid argument handling. Current library tests use server render and do not prove async mounted lifecycle; use a real mounted DOM/browser or an independently exercised request owner for stale-response acceptance rather than asserting markup only.

### Related specs and external references

- `.trellis/spec/webui/frontend/index.md`: centralized API/types, current-world metadata, no second document owner.
- `.trellis/spec/webui/frontend/studio-authoring.md`: owner epochs, transient full-simulation identity, explicit bound snapshots and source deletion semantics.
- `.trellis/spec/webui/frontend/components-state-contracts.md`: local committed inputs/recovery ownership; actual server validation remains authority.
- `.trellis/spec/webui/frontend/program-blocks.md`: invalid raw workspace versus accepted definition, execution semantics and source identities.
- `.trellis/spec/webui/backend/http-repository-packaging.md`: authenticated proxy routing, real transport errors and resource ownership.
- `.trellis/spec/javarailways/simpleato/simulation.md`: detached capture, isolated execution, current turnout state and real timing boundaries.
- `.trellis/spec/javarailways/shared/versioning.md`: this proposal adds only transient HTTP/UI state; no authoring/library/recovery schema changes or version bump required.
- External references: none; findings verified against current source rather than external library documentation.

## Caveats / Not Found

- This is research only. No endpoint, UI or runtime implementation was modified.
- A source body may contain WAIT_EVENT or planned-context conditions; the minimal example request alone does not solve those dependencies. Runtime owner must explicitly support or reject them rather than silently changing behavior.
- Reusing the existing profile picker does not clone WORLD_TRAIN runtime state. Initial doors/speed/reversal behavior must describe the actual supported seed state and not promise current-live-state capture.
- Current library toolbox omits ASYNC/AWAIT/door actions despite the broader StationProgramDefinition union supporting them (`programBlocks.ts:4-6`). Do not expand available blocks incidentally while adding simulation; coordinate with the runtime contract if broader library actions are required.
- Exact metadata field and new endpoint names above are proposals to align owners, not preexisting API contracts.
