# Research: independent review of function simulation and unique placement

- Query: Review the complete current round, including untracked implementation files, against the corrected seeding/reversal/shared-executor contract. Focus on actionable defects; no implementation or Gradle execution.
- Scope: internal static review of exported complete diff `/tmp/jr-function-simulation-review.diff` and relevant current source/specs, after parent notification that Core was compile-ready/frozen.
- Date: 2026-10-30

## Findings

### P1: repeated reversals use the wrong rail-local basis for the next route

- File: `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt:2723-2742` (`processReverse`).
- The code captures the current physical head's local `AB`/`BA` before reversing and toggles that enum into `run.requiredNextStartDirection`. However, the next movement route remains anchored at `plan.origin`/`run.currentWayPointId`, which can be on a different rail.
- Concrete case: the original head is on H/AB (physically rightward), and the original tail is on a connected oppositely authored rail T/BA (also physically rightward). The first explicit reversal correctly selects BA in H's route-origin basis. The new physical head is T/AB. A second explicit reversal reads T/AB and writes BA again, although the original anchor H must now use AB. The train's physical reversal is correct, but its next route evidence lookup/mission projection uses the wrong direction and fails or chooses the wrong directional route.
- This affects a simulated function containing another `REVERSE` after the initial `reverseTrain`, as well as normal LIVE repeated reversals while the body spans rails with different canonical orientations. It does not require an implicit reverse operation to occur.
- Fix within scope: preserve the direction in the retained origin's coordinate basis and toggle that existing anchor-basis direction across reversals until an actual movement establishes a new origin. Do not overwrite it using the enum from whichever rail currently carries the physical head.
- The newly added `TimetableDispatcherTest` case `repeated in-place reversals across mixed rail directions keep the original route anchor basis` directly covers this case. The parent was notified before tests; no test execution was performed by this reviewer.

### Boundaries checked with no additional confirmed defect

- `ProgramSimulation.kt`: strict explicit `reverseTrain` boolean; generated source uses existing `service.reverse()` before the measured task. No opposite-facing respawn or new program executor remains. Initial preparation is excluded from the TaskStart→TaskEnd measurement; FAILED/LIMIT responses never expose an adoptable completed duration.
- `TimetableHttpBridge.kt`: owner capture rechecks waypoint and profile references, isolates the snapshot, passes known standalone service IDs, uses the existing bounded simulation worker, and reports setup failures rather than fabricated completion. Metadata exports only direction-resolvable waypoint candidates.
- `IsolatedTimetableSimulationFactory.kt`: ordinary physical timetable seeding and standalone function seeding both call the same `createTrain`; all such creation uses `prepareUniquePlacement` and prepared install before publication. Profile references remain blueprint inputs; live train geometry is not moved.
- `TrainCreation.kt` / `UniqueTrainPlacement.kt`: candidate search is prepare-only, checks all directed topology exits independently of active selection, uses real leading/following Core rigid solves, distinguishes missing prefix support from terminal exhaustion, clips actual occupied body paths for deduplication, and fails with SearchLimit rather than accepting an incompletely explored unique candidate. The accepted path is installed once and revalidated before publication. The finite support is derived from the existing rigid search windows, not a sum-of-car-length approximation.
- `CoreCar.kt`: numerical axle/coupler solving is shared with the existing implementation; mixed physical A/B fronts and asymmetric offsets remain represented explicitly. The new code exposes path distances in the result rather than recovering occurrences by a first rail match.
- UI/sidecar: browser types/request builder, Java validator, bridge parser, required radio input, metadata and result shape agree on `reverseTrain`. Program library uses the accepted unsaved definition; timeline calls use the bound snapshot/arguments. Explicit adoption writes a manual planning duration and does not manufacture a timetable simulation result. Owner/generation checks hide stale responses and invalidate pending results after edits.
- LIVE/SIM direction safety: `submitMission` rejects an unprojectable train head; no `reverse()` call was found in the ATO controller's mission submission path. Explicit reversal continues through the dispatcher preparation/Core reverse/publication route. Regression tests are present but were not executed by this reviewer.

### Clarification applied during review

The parent explicitly clarified the agreed scope: waypoint direction (or its rail direction) chooses the **baseline** facing; unique placement checks directed physical topology and rigid body candidates. Basic operating-direction metadata is a movement permission, not a rule that a stationary car must face that way. Therefore the lack of per-body-segment Basic direction filtering is **not a defect** in this review. Two physically feasible branches remain ambiguous even if one branch is not currently permitted for forward movement. This supersedes the optional forward-direction predicate suggestion in the earlier exploratory `placement-uniqueness.md`; Core must not acquire a Basic dependency.

### Files and specs consulted

Implementation/test paths:

- `Core/src/main/java/work/art1st/javarailways/core/physics/train/{CoreTrain,CoreCar,TrainCreation,UniqueTrainPlacement}.kt` — ownership, shared numerical solver, prepare/install proof and unique search.
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/{RailLocationWithFacing,RailView,RailSegment}.kt` — exact directed topology and selected-path semantics.
- `Core/src/test/java/work/art1st/javarailways/core/physics/train/{UniqueTrainPlacementTest,CoreCarPositioningRegressionTest}.kt` — candidate, geometry, branch, loop and prepare-state fixture coverage.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/{ProgramSimulation,TimetableHttpBridge,IsolatedTimetableSimulationFactory}.kt` — request→capture→seed→existing execution→duration.
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/{ProgramSimulationTest,IsolatedTimetableSimulationFactoryTest,FullFeatureSimulationIsolationTest}.kt` — parser/measurement/reversal/isolation tests.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/{Timetable,TimetableDispatcher}.kt` and `simulation/IsolatedTimetableSimulation.kt` — scoped validation and dispatcher timing/normal mission path.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt` — no hidden reverse in the controller mission execution path.
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcherTest.kt` — LIVE/SIM no-implicit-reverse and repeated mixed-orientation tests.
- `WebUI/src/main/java/work/art1st/javarailways/webui/{ProgramSimulationValidator,WebUiApplication}.java` — authenticated proxy and strict transient request shape.
- `WebUI/frontend/src/{ProgramSimulationDialog,StationProgramLibrary,TimedOperationFields,DutyTimeline,DutyManagement,DutyEditor,App}.tsx` and `{programSimulation,api,types}.ts` — inputs, owner validity and explicit duration adoption.
- Corresponding new frontend and Java route/validator tests from the exported diff.

Specs:

- `.trellis/workflow.md`
- `.trellis/spec/javarailways/core/{index,train-physics,rail-topology-and-occupancy}.md`
- `.trellis/spec/javarailways/basic/operating-directions.md`
- `.trellis/spec/javarailways/simpleato/{simulation,dispatcher-runtime}.md`
- `.trellis/spec/webui/backend/http-repository-packaging.md`
- `.trellis/spec/webui/frontend/components-state-contracts.md`

External references: none; no external API/version choice was needed.

## Caveats / Not Found

- This was source review, not a successful build, executed regression suite, HTTP/browser acceptance or live/game verification. No Gradle command or Git operation was run.
- Only the research file was written; implementation, specs, task lifecycle, staging, commits, deployment and unrelated files were untouched.
- Unique search preserves the existing supported bounded numerical solver semantics; this review does not claim mathematical enumeration of all nonlinear roots on arbitrary pathological curves.
- The parent owns the actual test results and final correction/review of the confirmed repeated-reversal defect.

## P1 follow-up: minimal fix reviewed, regression pending

- The parent reports that the initial `TimetableDispatcherTest` run had 56 cases and only the mixed-rail repeated-reversal case failed, specifically LIVE/2 with incorrect start direction BA. This is parent-provided test evidence; this reviewer did not execute the suite.
- Re-read the frozen correction at `TimetableDispatcher.kt:2723-2743`: `facingBefore` now uses `run.requiredNextStartDirection` when present, falling back to the actual head's facing only for the first reversal. The code then performs the existing actual `run.train.reverse()` and toggles the retained anchor-basis direction. For the concrete H/AB + T/BA case this produces BA after the first reversal and AB after the second, preserving the unchanged origin basis.
- Verified uses/reset sites of `requiredNextStartDirection`: ordinary GoTo/GoVia at lines 4568-4582 and selected-stop preparation at line 5323 consume the retained basis; successful mission installation clears it in the existing branches at lines 5472-5493. No new direction state, hidden reverse call or separate executor was introduced by the fix.
- Static review status: the confirmed P1 is **addressed by the minimal implementation correction**. The original finding above is intentionally retained as the defect/evidence record.
- Execution status: complete Core/Basic/SimpleATO/Bukkit/WebUI regression is running under the parent; passing results, game validation and deployment are not established by this follow-up. No implementation files or tests were changed by this reviewer.

## Follow-up: StoreAtDepot direction handoff and UNIT_COUNT fixture

- The parent reports a 663-case Bukkit gate with two failures: the new real mixed-rail `GoTo(turnback) → Reverse → StoreAtDepot` regression selected AB at the retained origin while validation had retained BA; the UNIT_COUNT fixture nested the same world owner. These are parent-provided executed-test observations, not independent test runs by this reviewer.
- Re-read the frozen production correction at `TimetableDispatcher.kt:4382-4390`: the storage path's ordinary `movement(...)` result now carries `.copy(requiredStartDirection = run.requiredNextStartDirection)` before `submitMissionForRun`. This passes the same retained route-origin basis used by ordinary GoTo/GoVia to storage mission selection. With no pending direction the value remains null and the existing `submitMission` fallback continues to capture actual physical head facing, so normal non-reversed storage does not acquire a new inferred direction or hidden reverse.
- Re-read the real storage regression at `IsolatedTimetableSimulationFactoryTest.kt:205-232`: it travels to a turnback where the body spans oppositely authored rails, explicitly reverses, then requires Completed plus the actual final storage rail, AB-facing head coordinate and reversed state. This exercises the production route/evidence path rather than manually manufacturing a route result.
- Re-read the fixture correction at `IsolatedTimetableSimulationFactoryTest.kt:336-345`: `createTrainWithConfig(...)` now completes before the explicit `source.onOwnerForTest` block; placement/publication and unit/stock registration remain inside that subsequent owner block. The fixture helper itself enters the world owner during construction, so removing the outer wrapping avoids nested owner entry without changing production behavior or weakening the UNIT_COUNT assertions.
- Static review conclusion: both minimal changes address the reported failures within scope. No additional defect was confirmed in these two changes. Focused Factory/Program/Dispatcher/WebUI regressions are running under the parent; their passing status and broader gate completion remain pending.
- No Gradle command, implementation modification, commit, deployment or task lifecycle change was performed by this reviewer.
