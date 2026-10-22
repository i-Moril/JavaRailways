# Implementation plan

Status: implementation in progress after final user approval on 2026-10-22.

Checkpoint: B1–B5 implementation is integrated, including shared visual programs, fixed-unit
coupling/separation, finite movement branches, actual-length station choice and circulation
findings. Local browser persistence and frontend checks passed. Broad backend gates passed
before the final held-output reversal/route-extension follow-up; its focused gate51036 also
passed. Final affected-module regression passes: Basic427, SimpleATO576, Bukkit575.
Prior unchanged Core524/WebUI26 and frontend153/build passed. Game acceptance remains open.
Unchecked acceptance items below may combine local evidence with outstanding game scenarios;
see chronological `research/formation-authoring-and-simulation-2026-10-22.md` for exact coverage.

## Dependency order

Use one planning task with five independently reviewable batches. Dependencies are explicit: B2 requires B1; B3 requires B1+B2; B4 requires B1+B2+B3; B5 requires B1 through B4. Library/DSL contracts are fixed in preflight so later UI work does not invent a different program representation. Child tasks may be split at execution time without changing these gates.

## Preflight

- [x] Obtain final planning approval, then task.py start and curated Trellis implementation/check dispatch.
- [x] Establish a fresh isolated baseline, preserving concurrent primary signal changes, the UI worktree's uncommitted rename/cap/restoration changes and the backend worktree's route/Reset fixes.
- [x] Record exact owners/files for DSL worker, DTOs, snapshots, authoring library and editor document validator. Define request/response/error contracts, expected-revision conflict and archive semantics before writes.
- [x] Inventory affected persistence shapes and evidence of release/frozen baselines; determine minimal current-model change and any necessary offline migration. Do not infer version increments from development commits or deployment alone.
- [x] Define program/call/step identity and bound-definition provenance across generation, compilation and diagnostics.

## B1 — fixed EMU units, depot and stopping infrastructure (AC3, AC4, AC11)

- [x] Register disjoint fixed EMU unit membership using existing stable car IDs; preserve Core physical ownership and unit home affiliation across changing formations. Validate complete contiguous units and independent/combined operating compatibility.

- [x] Separate acquisition location/physical storage from home affiliation in all Depot paths, including reservations, restore and scoped Reset.
- [x] Add canonical stopping-area/shared-capacity and marker eligibility data with correct projection ownership.
- [ ] Validate actual train envelope/direction and absence of partial mutations.
- [ ] Test A acquire -> B store -> B acquire, assignment conflicts, cancellation/restore, reset membership, marker length/direction and shared capacity.
- [x] Review and update affected depot/station/destination/persistence specs.

## B2 — shared library, sequential programs and generation (AC1, AC2, AC6)

- [x] Implement authenticated world-scoped library storage, revision checks and archive; server validation and atomic writes.
- [x] Add bound definition snapshots/calls to editor contract and deterministic safe JS generation with source attribution.
- [x] Extend existing JS DSL/compiler -> structured timetable contract; execute sequential programs through existing Dispatcher transitions.
- [x] Implement absolute/relative time semantics and existing station-operation handoff, keeping acquisition/storage ownership rules explicit.
- [ ] Test two timetables sharing one definition, update/archival leaving embedded plans unchanged, explicit adoption invalidation, generation/compile and sequential turnaround/depot lifecycle.
- [ ] Test pending operations start once, cancellation and recovery-required behavior; review cross-layer serialization.

## B3 — EMU rendezvous, coupling and separation (AC6, AC7, AC11)

- [x] User confirmed complete-unit scope and fixed storage/coupling/separation events.
- [x] Implement structural and bound-parameter validation for these restrictions in the common compiler/validator, including nested conditional program invocations.
- [x] Define explicit counterpart/event bindings, single coordinator, arrival waiting and cancellation/failure outcomes.
- [x] Coordinate normal physical approach and Core structure publication with task/resource handoff; preserve every constituent and assign each split output.
- [x] Refresh formation dynamics, braking, length, markers and mission/admission references; reject internal-unit splitting and incompatible combinations before mutation.
- [ ] Test two units -> coupled formation -> two units, coordinated meeting target, dependency cycles, delayed/missing counterpart, duplicate ownership, cancellation, failed preparation/publication and no premature resource release.

## B4 — branches, dynamic choice and unit circulation (AC4, AC5, AC6, AC7, AC9, AC10)

- [x] Propagate possible positions/directions and relevant state through finite branches, retaining validated route evidence and complete candidate dependencies.
- [x] Implement marker filtering and fixed/available priority station choice with coherent observations and normal allocation/admission.
- [x] Connect actual chosen outcome to subsequent run movement without modifying authoring input.
- [ ] Test distinct branch endpoints/directions, no-compatible candidate, all busy, simultaneous claims, pending/denied route, stale topology and safe replan; verify no tentative-reservation leaks or early tail release.
- [x] Reject conditional storage/coupling/separation and check fixed event/target provenance; implement unit-based cycle conservation, event-time formation availability and storage capacities, including cross-cycle tasks. Distinguish local movement validity from unproven global circulation.
- [x] Run isolated simulation with the same definitions/selection/structural runtime, including repeated deterministic runs under fixed inputs.

## B5 — visual editing and integrated acceptance (AC1–AC11)

- [x] Shared library list/editor, parameter form, sequence/branch editing, usage/update indication and explicit bound-definition adoption.
- [x] Duty program invocation, stable step error navigation and actual execution/waiting/selection diagnostics.
- [x] Preserve sole document/history/recovery owner, stable IDs, epoch guards and current Ant Design theme/locales.
- [ ] Browser: create/share/update program in two timetables; save/reload/undo; generate embedded JS; branch error attribution; displayed actual platform selection.
- [ ] Integrated simulation and controlled game checks for full turnaround, A-to-B-to-B acquisition, complete-unit coupling/separation and follow-on duties, varied lengths, competing trains, failure/cancel and restart handling.
- [x] Record exact build/source provenance and acceptance gaps; no claim that unit tests establish game behavior.

## Verification targets

Use focused tests as each change lands, then affected module tests/build at batch completion. Relevant existing suites: DepotStoreTest, DepotResetCoordinatorTest, TimetableValidatorTest, TimetableDispatcherTest, TimetableStationCallDispatcherTest, TimetableDispatcherSharedAdmissionTest, TimetableSnapshotTest, DispatcherSnapshotTest and isolated simulation suites. Add behavior cases to the actual owners rather than mirror implementation details.

Frontend: npm --prefix WebUI/frontend test; npm --prefix WebUI/frontend run build. Backend: corresponding :SimpleATO:test, :BukkitPlugin:test and :WebUI:test suites with real frontend resources available for HTTP startup. Check both nested JAR resources when a release is prepared. Run git diff --check per batch.

## Release gate

No commit/deploy during planning. Before a later authorized release: review batch diffs, preserve runtime data with a recovery point, prove any migration by dry-run, build from reconciled source, verify installed resources and perform live health/game checks separately. Keep completion statements scoped to evidence actually collected.
