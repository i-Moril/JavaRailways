# B4 remaining contract inventory

Read-only scoped inventory of current task PRD/design/implement versus SimpleATO timetable/compiler, station selection, saved DTO and frontend sources. No product writes or tests. Core/Basic and root Dispatcher ownership were not modified.

## Actual current state

- Task requirements: `implement.md:45-52`, `design.md:45-61,73-95`, PRD R4/R5/R9/R10 and AC5/AC9/AC10 require finite branches with differing endpoints, actual dynamic marker choice, fixed storage/formation events and recurring unit conservation/timing/capacity evidence.
- `SimpleATO/.../timetable/Timetable.kt:180-188`: `Conditional(condition,thenActions,elseActions)` exists, but its constructor recursively permits only Extension/Conditional. `validateService` around366,526,545 keeps scalar currentWayPointId plus direction/dynamic-acquisition candidate sets; it does not propagate arbitrary branch successor states. `validateExtensionAction` around691 likewise rejects movement leaves.
- `TimetableDispatcher.kt:4008-4075`: `startNextExtensionAction` evaluates a branch once and pushes selected actions into an extension queue. Non-extension leaves explicitly stall. Reuse the actual extension ticket lifecycle, but this queue is not a general movement/wait/reversal program cursor.
- `SimpleATO/.../persistence/TimetableSnapshot.kt:145-146,196-199` and `BukkitPlugin/.../scriptworker/Model.kt:26-27`: saved branch children are bare actions. `WAIT_UNTIL` has no independent tick; it gets the enclosing At.tick. A full branch sequence with distinct absolute waits needs a shared timed-node contract, not just removing the constructor guard.
- ScriptWorker `Dsl.kt:397-405` exposes whenCondition through RuntimeActions, whose only exported leaf is action(extension). `StationPrograms.kt:11-19` has six sequential library step kinds: WAIT_UNTIL/WAIT_DURATION/EXTENSION/REVERSE/MOVE/STORE_AT_DEPOT. `programAt/appendProgram` around259-280 expands a bound definition into flat At steps and adds program/step trace; no nested invocation/branch AST exists there today.
- Matching six-step surfaces: `WebUI/src/.../StationProgramValidator.java`, frontend `types.ts:81-87`, `stationPrograms.ts:33-49`, `ProgramFields.tsx:47-49`. The visual shared library has neither branches nor runtime choice; even B3 couple/split authoring remains a separate UI gap although direct JS/runtime catalog types exist. B5 owns the finished visual workflow.
- Station foundation is reusable: `api/station/Station.kt:53-86` has stopping area, direction, actual length domains and offsets; `StationCallResolver.kt:75-83,124-125` resolves one explicit berth and checks actual length; `StationBerthAllocator.kt:36-69` atomically excludes duplicate berth/shared-area owners. Dispatcher has WAITING_BERTH and existing allocate/release/admission integration around1915-1961 and2788-2800. No fixed/available-priority candidate selection action or bound-choice state was found in the inspected timetable/program types.
- `api/timetable/TimetableExtension.kt:53-80` supplies tags/capabilities/model/carCount but no actual length in condition context. A registered length predicate or built-in finite marker filter must consume actual composition; carCount is not a length substitute.
- `api/depot/Depot.kt` owns actual rolling-stock state, complete unit claims, cross-depot reserve/completeStorage, physical point capacity and fixed formation conservation. `FormationEvent.kt:81+` validates fixed catalog participant/output reciprocity and same-cycle dependency cycles, not repeating stock availability.
- No timetable unit-circulation proof was found in the inspected SimpleATO sources and compile HTTP path. `TimetableHttpBridge.kt:436-450` restores compiled DTO and calls TimetableValidator; result currently returns local errors/routes plus structural evidence. `IsolatedTimetableSimulation.kt:53-61,220-222` is explicitly one deterministic cycle, maximumTicks<=period, reporting cycle0 only. That cannot establish AC10 cross-cycle conservation or outstanding-task timing.

## Fixed-event guard that B4 must retain

Current safety comes partly from the extension-only constructor/DSL restriction. Before relaxing it, introduce one common recursive post-expansion invariant:

1. At any runtime branch depth, reject STORE_AT_DEPOT/COUPLE/SPLIT, including identical events in both arms. Also reject any conditional invocation whose expanded body contains them. Static literal/call-parameter expansion outside runtime branches remains legal.
2. Reject runtime-conditional rebinding of return depot, event/partner, partition or output duty. Existing statically scheduled catalog participants (`FormationEventValidator.scheduled`) and conditional-activation STORE rejection (`Timetable.kt:430-433`) must remain authoritative at restore/validator boundaries, not merely in UI.
3. Every successful path reaches the same declared fixed event suffix in declared order. HALT/early successful termination inside a branch cannot bypass required storage/event obligations. Condition unavailable/error is explicit operational failure, not a successful bypass. Dynamic approach/platform choice may vary geometry but cannot retarget a fixed event or let its two participants choose independent rendezvous locations.
4. Validate after immutable program bindings/invocations expand and again at authoritative model restore/validation. Recursive depth/state-count limits must reject contextually, never truncate. Keep program/step/branch-address diagnostic provenance.

Existing `Conditional` action lists cannot express independently timed child steps. Settle one minimal shared timed sequence/branch representation first, with stable node IDs and child addresses. Reuse leaf actions and a stack cursor; do not add another controller. The saved boundary decision must follow existing versioning policy, not infer a schema bump from the new feature.

## Smallest coherent implementation split

### A. Shared timed branch contract + pure state validator

Own new structured-node/cursor-address and validation files/tests first; touch Timetable/DTO/DSL mirrors only at a coordinated interface freeze. Update ScriptWorker and WebUI library validator mirrors together. Keep Extension-only Conditional safe until the new authoritative path is complete.

State must include exact position/evidence origin (marker/actual structural envelope), direction, finite composition/eligibility constraints, acquisition/storage state, bound choice and pending destination/station options. Unknown predicates validate both successors. Merge only equivalent full states. Retained route keys need node/branch address and selected origin/choice, not only top-level stepIndex/legIndex. Retain all candidate dependencies; executing cursor consumes one validated edge and existing leaf runtime machinery. Branch end positions need not match.

Tests: nested distinct endpoints/directions, different waits/reversal, fixed event rejection through nested expanded calls, successful early-exit bypass rejection, non-equivalent state merge, limit overflow and route evidence chosen from the actual branch result. Root should own Dispatcher integration after B3 stabilizes; no parallel edits there.

### B. Pure candidate policy/resolution + owner integration

Own a new station candidate resolver/policy file and focused tests, reading existing StationCallResolver/allocator contracts. Input finite declared candidates with validated incoming AND onward evidence, actual composition, fixed-preference versus available-priority policy and coherent observation. Produce an immutable chosen run-local result; do not mutate authored intent.

Sort deterministic priority+stable-ID; at the safe pre-divergence boundary attempt only one normal station allocation/admission at a time. Busy-all waits; no-compatible is a definition/eligibility failure. Pending handles are progressed, not resubmitted. Release only tentative unexecuted claims on denial/staleness, retain occupied/committed berth/tail resources, and preserve source berth until its normal release. Physical occupancy/admission remains authoritative even if logical area allocation succeeds. Policy must specify whether fixed preference waits on the preferred occupied candidate rather than silently falling back.

Tests: length-domain partition with at least one continuation per supported input, differently directed outcomes, concurrency, all busy, no-compatible, denied/pending route, stale topology, no alternate-reservation leaks and no premature source tail release. Root/Dispatcher owner wires the selected outcome and reports after pure API freeze.

### C. Independent pure unit-circulation analysis

Can proceed in parallel in new files/tests without Dispatcher edits, consuming the fixed catalog/normalized flow and immutable Depot snapshots. Return separate structural-inventory, timing and capacity proof statuses/errors; do not call net balance a timing guarantee.

Track each permanent unit and its ordered physical formation group, compatibility, actual storage depot/point, assignment/output ownership and availability time/cycle lag. Cross-depot A→B changes actual location, not unit home. COUPLE unions disjoint units; SPLIT partitions whole units; neither consumes/creates stock. A parked coupled formation occupies one physical stabling point and cannot supply its constituent units separately without a declared split. HOLD remains owned/unavailable.

Check fixed physical-depot/compatible-class net flow over the repeat graph (finite initial stock cannot repair recurring deficit), time-local acquisition matching of compatible complete formations and point/length/storage-capacity occupancy. Preserve identity through split/join; do not count Core container IDs as assets. Permit compatible periodic unit permutations rather than requiring every unit ID to return to the same home each single period. Exact selectors still constrain the matching.

True missing timing contract: At.tick is action entry/earliest execution, not proof that movement/storage completed. Runtime can keep unfinished cycleN while cycleN+1 starts. B3 output links are deliberately same-cycle; do not infer cross-cycle ownership from the latest held output. Cross-cycle edges/lag and outstanding task intervals need explicit bounded evidence. Without a completion upper bound, report timing/capacity unproven; guaranteed contradictions (e.g. demand preceding the earliest possible return) can still reject. Branch-dependent times require bounded scenarios, not optimistic schedule substitution.

Tests: sustainable A↔B, recurring A→B deficit despite spare stock, type mismatch, early reacquisition, capacity overflow, coupled storage then required split, split/join conservation, delayed cross-boundary return, overlapping cycles and balanced-but-timing-unproven cases.

### D. B5 UI and same-runtime acceptance after A/B contracts

Update `ProgramFields.tsx`, `StationProgramLibrary.tsx`, `stationPrograms.ts`, `authoring.ts`, `DutyEditor.tsx`, types/i18n and strict backend library mirrors from the agreed timed nodes/choice results. Preserve bound snapshots/revisions, single history/recovery owner, stable nested step navigation and stale-world guards. Extend isolated simulation deliberately for multi-cycle evidence; its current cycle0 result must retain its bounded meaning. No arbitrary JS runtime or alternate simulation/controller implementation.

## Immediate delegation boundary

C (new pure circulation files) is the cleanest independent B4 work while root integrates B3. B can implement pure candidate filtering/ranking contracts concurrently without changing the allocator/runtime. A starts with the timed branch contract and recursive fixed-event validator after model shape agreement; final model/DTO/Dispatcher edits must be serialized. B3 physical coupling/split completion is not implied by this inventory or by a B4 local validator result.
