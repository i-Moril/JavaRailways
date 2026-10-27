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
