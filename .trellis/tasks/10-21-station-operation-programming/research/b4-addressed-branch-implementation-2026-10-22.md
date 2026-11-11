# B4 addressed branch implementation handoff

Scoped source inspection after the circulation summary slice; proposed changes below are not implemented. Dispatcher/coupling sources remain under their current owners. This refines `b4-remaining-contract-inventory-2026-10-22.md`.

## One execution representation

Replace Conditional's `List<TimetableAction>` children with `List<TimetableStep.At>` (`thenSteps`/`elseSteps`), preserving top-level Check only. Every child has its own absolute earliest tick. A conditional itself is an At and must pass its tick gate before evaluating once. No legacy action-list execution queue alongside the new representation.

Use an immutable `TimetableStepAddress(topLevelIndex, branchPath)` where each path element contains typed THEN/ELSE plus child index. Defensive-copy every path. Empty path is top-level. A cursor value carries the current address and immutable parent continuation frames. `enter(arm)` chooses once; `advance()` exits exhausted nested frames to the captured successor; end is explicit. Empty arms immediately resume the enclosing successor. No cursor operation evaluates a condition, mutates the world or searches routes. Root/top-level index remains a diagnostic projection, not a second execution pointer.

MovementPlan must capture the exact successor cursor and source address when prepared. A pending movement/admission/replan resumes that captured cursor on completion; it cannot recompute `current index + 1` against a changed branch. Extension tickets similarly own their current addressed leaf until terminal, replacing the bare extensionActionQueue. Check/WAIT/Reverse/StationCall retain existing lifecycle gates.

## Concrete source seams

- `Timetable.kt:180-229`: replace bare children and extension-only predicate only after common recursive fixed-event validation is present. `ValidatedTimetableRouteKey` around234 needs full address alongside origin/direction/leg/target; structural route/storage keys need the same. Existing top-level diagnostics can derive the index.
- `TimetableDispatcher.kt`: `advanceService` around3717 is one consumer, not the complete change. Completion near1558, `nextActionTick` near2268, pending mission/replan around3867/3906/4038/4089, `MovementPlan` around5503, ActiveRun around5627 and snapshot's `nextScheduledTick` around5754 must all consume the same cursor.
- Structural continuation assembly near5017/5133/5176, structural route lookup near5260, ordinary retained lookup near5298 also use integer successor arithmetic. Fixed events stay top-level, but their successors may enter a branch; preserve exact physical origin/candidate as cursor transitions.
- `startNextExtensionAction` near4144 currently recursively enqueues only bare Extension/Conditional; replace this branch mechanism rather than widen it to a second movement executor. Preserve provider ticket start-once, cancellation and unavailable/error failures.
- `StationCallSpec.stepIndex` and station call identity near973 need full-address uniqueness for multiple nested station calls under one top-level branch.
- `persistence/TimetableSnapshot.kt:145-146,196-199,265-269`, ScriptWorker `Model.kt`, `Dsl.kt:397-405`, frontend `types.ts` and `authoring.ts` must change together. Current unpublished shape is edited in place; no speculative V2/dual reader.
- ScriptWorker compilation trace (`Model.kt:36`, `Dsl.kt:202-203,274-275,364`) currently identifies only top-level index. Add full addressed trace attribution so nested program step origins remain distinguishable.

## Timed expansion and validation

Each branch arm starts from the conditional's inherited tick. Expand WAIT_UNTIL independently in each arm; emit explicit At children. A sequential program suffix inherits the maximum final authored tick of both arms so the resulting authored schedule is nondecreasing on every path. This is an earliest-tick schedule join, not a physical endpoint or completion-time join. Direct JS callers supplying explicit ticks must satisfy this invariant rather than having their input silently retimed.

Use one recursive post-expansion fixed-obligation guard in the authoritative model/validator. At any branch depth reject TakeFromDepot/Waypoint/FormationOutput, StoreAtDepot, Couple, Split and Halt. Conditional service activation may not bypass fixed storage/structural obligations. Program/static parameter binding occurs before this check. Both true and false arms still undergo type/provider validation. Condition Unavailable/Error is failure, never false. Finite total node/depth limits reject with addressed context.

The geometric validator must propagate full successor states: actual waypoint or physical structural envelope/candidate, direction, composition constraints, acquisition state, pending station/destination options and selected destination binding. Evaluate both arms of an unknown predicate and retain each legal successor; merge only equal full states. A suffix move must have retained evidence for each reachable origin. Do not reset to the pre-branch waypoint, force common endpoints, or merge pending options from different arms. Every successful path reaches the fixed suffix; only explicit operational failure may stop it.

Circulation's current Search.next ignores non-inventory actions. Once branch grammar opens, invoke the same fixed-obligation guard first; only then can movement-only branches safely be abstracted for inventory. Its timing/capacity must remain UNPROVEN without completion bounds.

## Dynamic selection follow-on

Declare finite candidate IDs with deterministic policy (fixed preferred waits versus available-priority fallback). Validation produces incoming AND successor routes per eligible actual composition/direction. Runtime resolves at a safe stopped divergence boundary, tries one normal logical berth allocation plus actual Basic admission at a time, and retains selected destination run-locally. All busy waits with bounded retry; no eligible candidate fails contextually. Pending admission progresses rather than resubmits. Rejected tentative choices release only uncommitted reservations; existing source/tail claims remain authoritative.

Selection's chosen address/state must feed this same successor cursor and retained route key. It is not implemented by rewriting authored GoTo IDs or bypassing admission. Length predicates/filtering need actual physical length (current extension condition carCount is insufficient). Shared stopping-area allocator exclusion already exists and should remain authoritative.

## Implementation/gate order

1. Land immutable address/cursor + recursive guards and focused pure tests, without opening current grammar yet.
2. Coordinated model/DTO/compiler/trace change and recursive state validator; retain conservative rejection until runtime understands addressed nodes.
3. Dispatcher conversion including every integer-index seam above; activate timed branch execution and remove old conditional queue path in one coherent gate.
4. Dynamic candidate model/validator then real allocation/admission integration; UI structured branch/choice editors and strict Java validation mirrors last against stabilized types.

Required tests: nested empty/nonempty arms resume exactly once; conditional waits before evaluate; child WAIT_UNTIL and relative dwell; pending extension/movement never restart; reversed/different endpoint arms have valid distinct onward routes; asynchronous completion/replan returns captured successor; fixed event/acquisition/Halt rejected at every nesting and after program expansion; full-address trace/snapshot mapping; shared-area contention, all busy, stale topology and tentative admission unwind; same-runtime simulation for both branch outcomes. Pure cursor green does not establish any physical branch or platform acceptance.
