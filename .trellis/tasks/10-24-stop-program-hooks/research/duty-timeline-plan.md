# Duty timeline rewrite — 2026-10-27

User approved replacing dutyScratch with timeline: runintervals, customprogram points/intervals, manual or real-simulation duration. Existing task/worktree continues.

## Current-model decisions
- Duty editor schedules RUN and PROGRAM tasks only; program logic lives in library. Existing FUNCTION/INLINE contents are converted offline into library programs+bound snapshots, not kept as runtime compatibility variants. PLACE_INITIAL becomes duty initialPosition waypoint setting, not a scheduled task.
- Program call retains explicit tick and arguments; optional duration:{type:MANUAL,ticks:nonnegativeInteger}|{type:SIMULATED}. Missingduration is point0. Manualduration is planning-only, never a timeout or injected wait.
- Timeline runinterval uses origin departure through max(effective terminal arrival+dwell,optional terminal departure). Run times edited in runeditor, not duplicated duty fields. Customprogram start/length editable; drag moves start, resize manualduration. Accessible number inputs provide same edits.
- Timeline shows planned overlaps/warnings and actual measured spans. Existing sequential runtime gates remain; failed/incomplete simulation must not produce fabricated duration.
- Programs use real task boundary actions around expansion; START/END actual dispatcher ticks yield durations, including final asyncphysical completion and zero-operation programs. Source identity uses duty elementId, distinguishes repeated library calls. No deriving completion from nexttaskstart or finalrun snapshot.
- Simulation timings are transient output tied to current compiled document/profiles. Program/profile/path/time changes invalidate report via existing simulationInputKey. No persisted derived timing result.
- Currentdevelopment format remains1; no oldfieldrecognition/runtimecompat. One-time migration defaults dry-run, backs up affected library+documents, preserves snapshots and source recoverability, validates current model before install.

## Ownership
- timing_backend: current task boundary actions/DSL/compiler/runtime measurements and real simulation API/tests.
- timeline_model: authoring current RUN/PROGRAM model, timing helper/validation/generator, Java editor validator, exact offline conversion/tests.
- timeline_ui: standalone timeline React UI + CSS/i18n, no Scratch in duty editor; shared helper contract with model.
- root: DutyEditor integration, removal old dutyBlockly and recovery modules/tests when unused, docs/tests/browser/commit/deploy.

## Acceptance
RUNintervals update with timetablecalls/overrides; program point/manualinterval/simulatedinterval visibly distinct; edit/drag/resize one historytransaction; add/remove libraryreference never deletes run/librarydefinition; source editing opens library; zero/missing/stale/failedsimulation truthful; current document survives save/restart; actual isolated simulation WAIT/instant/idle-gap/lastoperation duration tests. New TASK boundary metadata cannot change movement/door/failure ownership.
