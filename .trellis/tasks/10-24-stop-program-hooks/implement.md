# Implementation plan

1. Establish current shared document/runtime contracts; preserve current schema number and remove runtime old-development compatibility assumptions.
2. Contracts worker: program actions, station definitions, worker/compiler/snapshot/validation and static condition expansion. Coordinate runtime types with runtime worker.
3. Runtime worker: station executor and Dispatcher lifecycle integration, gateway operation ownership, arrival/preparation eligibility and final descendant join; regression tests.
4. Routing worker: opt-in unique physical paths and waypoint pruning, strict timetable caller, tests including protection atomic alternatives.
5. Root: frontend document/writer/generator, shared station editing and Blockly new constructs; ordered via, event points/waits, duty Blockly and persisted reference profile; tests.
6. Integrate producers/consumers, migrate affected current fixtures/tests; add dry-run conversion tooling only where new current data requires preserving existing deployed documents, without runtime compatibility readers.
7. Run npm test/build, affected Gradle tests sequentially with shared JDK/cache and bounded heap. Inspect all failures, run scoped cross-layer reviewer, fix real issues.
8. Verify production HTTP interface lifecycle with isolated fixtures, including statically compiled callbacks and honest runtime acceptance limits. Update specs, evidence and task status. Commit only task-owned changes if authorized; no live deployment in this task.

## Completed implementation and validation

All eight batches are implemented and reviewed. Final backend2269/frontend210/migration3 gates passed, latest HTTP browser first-Duties initialization and invalid-draft recovery verified, shadow packaging verified. `research/validation.md` records precise evidence and the existing Java25 reobfuscation limitation. The user approved `research/commit-plan.md` and its exact file manifest on 2026-10-26. Record the local work commit and keep the task in_progress pending user in-game testing; archival is explicitly deferred. No push or deployment is authorized by this commit request.

## Feedback round1

User authorized incremental fixes and deployment on 2026-10-26. First-round behavior/ownership/acceptance is in research/feedback-round-1.md; preserve the remaining feedback for later rounds. Current source baseline aa5cb68d is already deployed; initial dirty files are task deployment evidence and task notes only.

## Feedback round2

User approved program library management round on 2026-10-27. Boundary, ownership and acceptance: research/feedback-round-2.md. Existing snapshots survive real library deletion; task remains unarchived.

## Obsolete development compatibility cleanup

User requested removal on 2026-10-27. Inventory and implementation boundary: research/obsolete-compat-inventory.md and research/obsolete-compat-removal.md. Remove archived status and obsolete per-call arrivals/rows code; strict current inputs, offline migration only.

## Duty formation follow-up (existing task, user-authorized)

- [x] Batch A: put one duty's existing tasks on one shared horizontal track; preserve real timing, interaction and unknown-duration semantics. Full frontend254/build and independent review passed. Actual app selection/shared geometry and explicit real-component fixture drag/resize/context/navigation verified; details and mobile limitation in research/duty-formation-follow-up.md.
- [x] Batch B: add duty-owned formation task authoring and local participant/output editing; remove formation blocks from reusable player programs and synchronize generated source and backend current-model validation. HEAD physical partition retained; details in research/duty-formation-batch-b.md.
- [x] Batch C: reciprocal assignment to manually created receiving duties, source-task/output selection, source renames/removal/history/recovery, planned-time conflict gates and formation dependency validation. Frontend269/WebUI41/BukkitPlugin641 tests, production build and actual React editor flow passed; no live game acceptance claimed.
- Withdrawn Batch D: user rejected extra HEAD/TAIL selection as unnecessary; restore the existing one-into-two primitive and compose repeated splits. This is not a pending feature. See research/duty-formation-batch-d.md.
- [x] Retained B/C integration: frontend269/build, WebUI41/BukkitPlugin641 pass after exact D rollback; independent SHA audit confirms preserved B/C. Previous B/C browser checks remain evidence; real game acceptance remains pending. Keep task in_progress; no automatic deployment/archive.

## Original feedback17 batch

- [x] Shared readonly data bindings through authoring/Blockly, Java validation, worker expansion, saved/runtime actions and both Dispatcher execution paths.
- [x] Real frame data and fail-visible unit count; resolved extension validation before start. Literal and planned-condition rules unchanged.
- [x] Frontend276/build, SimpleATO641/WebUI44/BukkitPlugin645 and signal API compile; snapshot/compiler/HTTP restart, real component browser, scoped independent review and spec sync passed. No commit/deploy/archive; live game acceptance pending.
