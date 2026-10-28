# Design: timetable station execution and deterministic plans

User authorized implementation after the confirmed two-entry summary on 2026-10-25. R1–R9 remain the scope. The remaining PRD inventory is implementation research, not additional product approval gates.

## Ownership and current contracts

Frontend editor document owns shared `stationPrograms`, `events`, run-call ordered `viaIds`, duty simulation reference and duty action source. Existing schema formatVersion stays 1: repository is development-only and no released/frozen boundary has been identified. No permanent old-data reader. Existing live data is not changed or deployed in this task; any future deployment requires dry-run/audited conversion and recovery points where needed.

Shared station record is `{stationId, arrival:StationProgramDefinition, departurePreparation:StationProgramDefinition, preparationLeadTicks:number}`. Both definitions have no declared parameters. Missing station record yields explicit default open/end and close/end entries, not an empty bypass. Remove per-call arrival editing/current writing. Station execution is instantiated independently per actual stop; PASS never runs; origin preparation only; terminal arrival only even if an optional terminal departure tick exists.

Program actions add OPEN_DOORS, CLOSE_DOORS, END, ASYNC `{taskId,steps}`, AWAIT `{taskId}`. Common expressions continue to use the existing literal/parameter forms. END marks its main entry complete, is invalid inside ASYNC, and cannot bypass outstanding child tasks. ASYNC returns immediately while its body executes in order. Task symbols are local to one stop invocation. Cyclic, forward-invalid or duplicate task references are rejected. Conflicting physical operations fail visibly rather than silently competing. No station movement, acquisition, dynamic berth selection, coupling, split or depot storage; reversal is allowed through the real operation gateway, maintaining station hold.

## Runtime

Use an explicit bounded station executor for branch-local program counters, outstanding gateway tickets, waits and child tasks; do not create additional Dispatcher service ownership or movement scheduler. Dispatcher owns exactly one active stop execution. Arrived main completion opens preparation eligibility, but arrival children may continue. Prep starts no earlier than planned departure minus lead and no earlier than arrival main completion; minimum dwell is enforced at the final departure/handoff barrier. Actual departure requires both applicable mains and all descendants complete, appropriate doors closed, time gate and current movement authority. Terminal returns after arrival tree joins and optional terminal time gate, leaving doors open by default. Failure/cancellation enters existing recovery/manual intervention and emits original throwable context, never success.

Program tick means timetable elapsed tick; duration starts when instruction actually runs. Elapsed absolute waits proceed immediately, same period/24000 ticks-per-day existing contract. Preparation lead default uses authoritative door operation estimate where available; explicit tick value is the editable schedule and never inferred from arbitrary custom action code. No wall-clock or pretending delayed actions ran in the past.

Conditions in authored timetable programs are resolved from planned run/call/duty context during compilation. Reject runtime train model, tags, capabilities and lateness predicates in timetable programs. Use finite known plan predicates and eliminate chosen branches before execution; extension evaluation must not reopen a dynamic condition path. External dynamic infrastructure providers remain outside timetable semantics.

## Routing

Expose explicit query policy for unique physical path and pruning non-target waypoints. Existing unrelated searches keep their policy. Timetable route segments use strict policy and ordered via endpoints. Uniqueness counts distinct physical traversals, not only equal-cost shortest paths; compressed protection alternatives must remain distinguishable. Detect ambiguity after two valid distinct traversals, fail with evidence. Pruning is applied to physical positions including inside atomic spans. Tests cover alternative lengths, protected alternatives, intermediate waypoint, ordered via, endpoint aliases and direction restrictions.

## Editor and source

Use shared Blockly editor contexts station-arrival / station-preparation / library and a duty adapter preserving unique run ownership. Add task statement slots and local task references; do not expose movement in station contexts. Shared station editing belongs to timetable document owner with existing undo/recovery and owner/world isolation. Stop details link to shared station editor, no per-call override.

Events `{id,stationId,tick,label?}` are explicit directionless diagram points with one authoritative tick. WAIT_UNTIL may reference eventId plus signed offsetTicks; normalize to a tick at generation/compile boundaries. Duty reference profile persists in duty, used only for simulation. Duty Blockly emits ordered existing RUN/FUNCTION/PROGRAM and new inline action source as needed, with no fake runtime actions. Editor and Java boundary validators must accept exactly the same current fields. JS generated from source remains one-way and deterministic.

## Verification and scope

Meaningful pure regression tests, Kotlin/Java compile and affected module tests, frontend test/build, full HTTP browser checks. No push or live deployment implied by latest request. Keep primary worktree untouched. Root integrates ownership boundaries, serializes Gradle invocations and reports any remaining evidence gaps truthfully.

## 2026-10-27 duty formation follow-up

The user approved production changes after the formation mockups and explicitly retained this in-progress task. Preserve the current timetable worktree, document/history owner and unreleased format number. One duty uses one horizontal lane; selection, context actions, timing edits and the inspector remain existing component responsibilities. Planned spans stay proportional; short/point/unknown tasks need independent interaction affordances rather than invented occupancy. Existing overlap diagnostics remain visible on the shared lane until domain-level overlap policy is updated in the formation batch.

Formation follow-up moves authored COUPLE/SPLIT calls out of reusable player programs into duty tasks. Receiving duties are manually created and reference an explicitly declared split output; never auto-create duty identities. A split can continue its source duty with one output. Vehicle identities and total composition are runtime data; authored partition rules use complete units from an operating end. Existing dispatcher formation ownership and output handoff should be reused, not replaced by a second physical executor. Keep the existing one-into-two split primitive. The user rejected the added HEAD/TAIL selection as unnecessary UI complexity; do not schedule it as a follow-up. More complex partitioning can be composed from repeated splits.
