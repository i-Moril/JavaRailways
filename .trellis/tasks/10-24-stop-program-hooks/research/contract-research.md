# Shared station program contract research

Inspected current source on 2026-10-25. Research only; proposed contracts below are not implemented facts. Latest user implementation authorization supersedes PRD's earlier planning-only prose.

## Owner and current gaps

Timetable model/store/validator/runtime belong to SimpleATO, not Basic. Basic owns route algorithms and `TrainOperationGateway` only. Current definition is per-call `arrivalProgram` compiled to `arrivalSteps`; stop runtime opens doors automatically before entering that tree. This must be replaced to let a fully editable arrival program own OPEN_DOORS. There is no departure-preparation program, async task model, explicit END or door action in StationProgramDefinition.

Current runtime At ticks are absolute earliest offsets in the timetable cycle. One day is 24000 ticks, `periodDays * 24000` is checked arithmetic. WAIT_DURATION currently expands to STOP and counts actual elapsed ticks. Preserve these semantics: past absolute waits continue immediately, never wrap implicitly to tomorrow; users choose the day offset within a multi-day period. Event references resolve against the same timetable document and cycle, never machine wall clock.

## Proposed authoring JSON

```
stationPrograms: [{stationId, arrival: {parameters: [], steps: [...]},
 departurePreparation: {parameters: [], steps: [...]}, preparationLeadTicks: 0}]
events: [{id, stationId, tick, marker?: string}]
duties: [{id, selector, referenceVehicle: {profileType: 'WORLD_TRAIN'|'TRAIN_TEMPLATE', profileId}, elements: [...]}]
runs[].calls[]: {..., viaIds?: string[]}
```

`viaIds` belongs to the incoming leg to that call: previous stopping target -> each explicit via waypoint in order -> current call target. It does not cause a station program or alter stop status. Existing PASS calls remain via markers, with a single unambiguous order relative to explicit via IDs documented by the compiler.

Keep library definitions and bound snapshots; reuse StationProgramDefinition actions and typed values rather than a separate visual program format. New actions: OPEN_DOORS, CLOSE_DOORS, END, ASYNC `{id,type,taskId,steps}`, AWAIT `{id,type,taskId}`. Door side comes from resolved stop metadata, NONE is a successful no-op. Wait target may be a literal tick or `{eventId}`; preserve event identity in authoring and resolve its current tick during compilation. Do not duplicate copied event times in duty elements. Existing FUNCTION/PROGRAM/RUN semantic duty elements can remain domain AST behind new Blockly editor; if adding general waits/display/etc, use program elements rather than reintroducing another generic action union.

`taskId` is a lexical symbol scoped to one concrete stop invocation, unique across both entry definitions. Two trains at the same station must never share runtime task state. ASYNC creates a child task and its descendants; parent continues. AWAIT observes the named task including descendant completion; unknown/self/ancestor/cyclic references fail validation. A conservative first validator accepts only definitely spawned task references, and rejects branch-dependent unavailable symbols. No magic global task lookup.

END is only allowed on entry main branches, not ASYNC descendants. Each statically selected main path ends exactly once, no statements may follow END. Arrival END marks arrival main complete and allows departure preparation once its deadline is met; it does not release station resources. Actual departure or terminal return joins both mains and all descendants. Terminal optional departureTick is earliest duty handoff, does not imply CLOSE_DOORS. Tasks must not survive leaving the stop.

## Compile-time conditions

All currently registered builtins in `BuiltinTimetableConditions.kt` are runtime dependent: train_model, train_has_tags, train_has_capabilities, lateness_at_least. None is a valid authored static predicate under R4. Existing `TimetableRuntimeContext` includes actual train/lateness, so adding a supportedScopes flag to providers is insufficient proof.

Use a finite planned-context whitelist, e.g. run ID/type, station ID, line ID/direction and authored call status. Shared definitions resolve each occurrence separately at compile time. Fold selected static branch into compiled program; retain step origin for diagnostics. Reject runtime providers at source validation and model validator, not merely toolbox filtering. Reference vehicle must never be passed as actual-train condition context. External action providers remain async side-effect adapters, with existing error/ticket/timeout semantics.

## Runtime compilation and execution

Compile shared definitions into immutable stop-scoped executable definitions referenced by concrete stop occurrence (runId/callId and full execution address), not station-global mutable runtime. Missing station entry uses explicit default definitions: arrival OPEN_DOORS/END, departure CLOSE_DOORS/END. ORIGIN only preparation, STOP both, TERMINAL only arrival, PASS neither. Preparation starts at max(actual arrival main completion, planned departure-preparationLeadTicks); origin has no arrival prerequisite. Minimum dwell remains an independent elapsed-time/safety gate, not an implicit duplicated OPEN/CLOSE operation.

Existing single cursor cannot represent concurrent async tasks. Add a dispatcher-owned cooperative task tree with independent immutable cursors and per-task wait/ticket state, all progressed on the same world owner. Keep the existing stop/mission/admission/berth owner; no independent scheduler/controller. Shared helper should poll tickets using exact request/ticket identity, cancellation and timeout checks. Close/open/reverse must serialize physical train operations; conflicting parallel operations must be rejected or fail closed, not last-writer-wins. END joins every descendant including tasks launched by tasks. Stop/error/control-transfer cleanup traverses every active ticket and confirms cancellation; failure or unexpected cancellation cannot unblock departure.

REVERSE is required by the new PRD; it cannot be whitelisted as a trivial leaf. Existing beginReverse/processReverse rebases route grant and changes revision; integrate its completion with the station scope, update stop identity and resolved door orientation, retain departure hold, and test subsequent close/departure. No MOVE, SELECT_STOP, storage, formation, acquisition or next-run action in a station program. Those remain duty actions.

## Exact affected boundaries

- `WebUI/frontend/src/types.ts`: StationProgramStep extensions, time/event references, wire model actions/branch paths.
- `authoring.ts`: stationPrograms/events/referenceVehicle/viaIds, save whitelist, rename/delete references, validation, generator and document hash.
- `stationPrograms.ts`, `programBlocks.ts`, `ProgramBlockEditor.tsx`: context validation, typed mappings, ASYNC nested chain and task references; no Blockly JavaScript generator.
- `authoringRecovery.ts`, `authoringRecovery.test.ts`: current document acceptance and field preservation.
- `DutyEditor.tsx`: profileAssignments currently only UI/recovery state at lines96/173; move authority to duty.referenceVehicle, simulation requests derive it. Avoid retaining two profile authorities.
- `RunRouteEditor.tsx`, `DutyManagement.tsx`, graph editing modules: shared station programs entry, optional terminal departure, events and explicit via, duty blocks.
- `WebUI/.../EditorDocumentValidator.java`, `StationProgramValidator.java`: repository shape, context/action/task/time validation. Existing public route API need not change if data travels in editor documents.
- `BukkitPlugin/.../scriptworker/Model.kt`, `Dsl.kt`, `StationPrograms.kt`: strict generated JS DSL and snapshot shape, static branch expansion, source trace, per-call compiled station program bindings, origin scope.
- `SimpleATO/.../timetable/Timetable.kt`, `TimetableArrivalProgram.kt`, `TimetableProgramCursor.kt`, `TimetableBranchGuard.kt`: immutable executable model, both entry trees/async children, bounded validation, task graph and context-specific actions, traversal addresses.
- `SimpleATO/.../persistence/TimetableSnapshot.kt`: capture/restore new compiled fields; scriptworker Model.kt must match this shape exactly.
- `SimpleATO/.../timetable/TimetableDispatcher.kt`: cooperative station invocation lifecycle, launch/join/deadline/door/reverse/END gates.
- `SimpleATO/.../api/timetable/BuiltinTimetableConditions.kt`, `TimetableExtension.kt`: static context boundary separate from actual runtime provider context; metadata must not advertise unsupported conditions.
- `SimpleATO/.../simulation` and Bukkit `TimetableHttpBridge.kt`: persisted duty reference vehicle feeds existing detached profile assignment path; all new operation/task state progresses under logical simulation ticks.
- Basic `TopologyRoutePlanner.kt` and SimpleATO `OperationalRoutePlanner.kt`: explicit query policy and true physical-path uniqueness, separate research owner.

Dispatcher checkpoint restore does not resume live tasks: it retains recovery-required diagnostics and disables services. Preserve that safety policy; don't claim new async task auto-resumption. Definition round-trip must include both entry trees/tasks. Keep schema version until a released/frozen baseline is evidenced; no permanent legacy development reader. A one-time dry-run/backed-up migration must explicitly reject conflicting prior per-call definitions for one shared station rather than silently choosing one.

## Essential checks

Definition and source round trips, unknown fields/actions and static runtime-provider rejection; all stop roles; preparation lead in future/past; arrival main vs descendants ordering; nested async/transitive AWAIT; missing/cyclic task symbols; END reachability and async prohibition; operation failure/cancel/timeout and stop cleanup; reverse then door close with changed orientation; terminal optional handoff time and retained open doors; event edits update references; persisted reference profile drives real simulation; HTTP incomplete block drafts cannot be silently dropped.
