# Research: SimpleATO controller, ordinary control, ATP and policy APIs

- Query: Review all assigned control/ATP/controller files, producer–consumer chains, unnecessary duplication, failure semantics and tests; identify additional issues beyond the known Tick 14219 snapshot/admission defect.
- Scope: internal, read-only review; production source and directly corresponding tests. No fixes, Git operations, deployment or server operations.
- Date: 2026-11-06
- Baseline: main-session supplied `b89b0d5d`; source read from the shared working directory. Other agents own timetable, inspection/admission/routing, domain and persistence.
- Evidence: complete reading of the 21 assigned production files and 13 directly corresponding test files listed below; targeted reading of adjacent producers/consumers. No tests executed by this researcher. A separate audit-only probe is prepared but not yet compiled/run.

## Findings

Main-session validation addendum: the existing 788-test suite passed with a temporary 3GB test heap. The audit-only automatic-body-speed probe was compiled and run against unchanged production code; its geometry prerequisites passed and its target-presence assertion failed. The observed automatic targets contained only perception-boundary and vehicle-maximum (10m/s), not the occupied-rail 1.5m/s cap. This confirms missing control input, not an acceleration/derailment integration reproduction. Full commands and limits are recorded in `validation.md`; the researcher's original not-run statements below describe the state before main-session validation.

### C01 — P1, safety input omission: automatic driving drops a native rail limit while the train body still occupies that rail

**Locations** (all repository-relative):

- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/controller/SimpleATO.kt:839-860`: native constraints consist of the remembered current head-rail limit plus upcoming `OperationalSpeedLimitSignal` objects.
- Same file `:1118-1130`: crossing onto another rail replaces the current limit with the new **head rail** metadata.
- Same file `:1144-1149,1215-1254`: perception begins at the head and only observes forward segments (plus the separate 0.3 m allowance discussed below); it does not cover rail sections remaining under the train body.
- Same file `:742-755`: automatic control-frame conversion does not add occupied-body native speed targets.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/control/RailwayControlRuntime.kt:74-81`: the common execution boundary adds occupied-body **grade**, but not occupied-body speed limits.

**Trigger and reachable chain:** a multi-car train crosses from a restricted rail onto a higher-limit/unrestricted rail, while its rear/body remains on the restricted rail. `AbstractATO.tick → SimpleATO.plan → synchronizeInfrastructureLimitWithHead/updateBuiltInConstraints → motionControlFrame → RailwayControlRuntime.automatic → VehicleControlExecutor.choose` supplies no old-rail operating cap to either the ordinary reference or ATP. With no other tighter limit, the controller can request acceleration before the body has cleared the restricted section. The same omission applies to a newly attached ATO on a train already straddling that boundary.

**Contract and impact:** these are `CURVE/SUBGRADE` rail metadata restrictions, not signal-system restrictions that intentionally end when the **head** passes their exit. Basic's speed/derail safety spec explicitly explains that checking only the head misses a strict curve still under the tail. `Basic/src/main/java/work/art1st/javarailways/basic/safety/SpeedSafetyMonitor.kt:73-98` checks all published swept spans, so it can still reject/derail physical overspeed that the automatic driving/protection input omitted. This is not a claim that ATP can bypass Basic route admission; it is an independent native speed-safety input gap.

**Symmetric implementation evidence:** `SimpleATO/.../control/ManualProtectionFrame.kt:31-40` explicitly gathers `publishedFrame.bodyTraversal` rails and preserves native limits at distance zero. `ManualProtectionFrameTest.kt:24-63` constructs a one-metre restricted rail between bogies, behind the head but under the actual body, and asserts preservation. That test has no automatic counterpart. Native **forward** metadata is correctly materialized as entry signals by `Basic/.../rail/OperationalSpeedLimitSignalMaterializer.kt:16-22`, so the initial suspicion that ATO never anticipates the next rail's limit is rejected.

**Evidence level:** source-confirmed missing input along the production chain; acceleration/derail consequences are inferred, not replayed. `research/AutomaticBodySpeedAuditProbe.kt` prepares an isolated short assertion using the same real Core geometry as the manual regression and the genuine ATO constraint builder. It uses a test actuator but does not integrate it (`tick(0)`), then asserts that automatic hard targets retain the 1.5 m/s body cap. Expected failure on baseline; execution belongs to the main session. Do not describe it as a completed reproduction until it runs.

**Minimal remedy:** derive the native current cap from the one published body frame, preferably share the small occupied-rail cap calculation with manual protection. Keep forward entry signals for anticipatory braking, and release the old current cap only after actual body clearance. Do not create a second route allocator or new signal policy.

### C02 — P2, engineering: the concrete controller constructs a scalar plan that its own execution path discards

**Locations and full chain:**

- `SimpleATO/.../controller/SimpleATO.kt:727-737` calls `MinimumConstraintPlanner.plan` each active-Mission tick.
- `SimpleATO/.../api/ato/AtoPlanner.kt:25-39` reduces constraints to a scalar speed and constructs a `BrakingConstraints` object (grade segments, limits, maximum delta).
- `SimpleATO/.../api/ato/AbstractATO.kt:120-130` passes that `ControlDemand` only to `motionControlFrame(demand)`; actuator integration consumes the returned `VehicleControlCommand`, not `ControlDemand`.
- The concrete `SimpleATO.motionControlFrame` at `:742-755` **never reads `demand`**. It reconstructs raw spatial targets directly from `constraintManager` and appends the absent-Mission hold itself.
- `publishRuntimeDiagnostics` at `:1064-1080` already performs the scalar constraint reduction once, then repeats each constraint's permitted-speed query for string descriptions. The returned/discarded planner adds a third pass in the same tick.

**Contract/impact:** the current reference/ATP executor uses physical spatial targets, not the legacy dynamic scalar speed. Keeping the scalar results as diagnostics can be intentional; performing a separate legacy planner call whose whole result is discarded is not needed for those diagnostics. It performs extra envelope queries and allocations on every physical tick, obscures the real control owner, and makes the `ControlDemand.brakingConstraints` comment (“actual-candidate admission”) misleading for this production path. This is an engineering defect, not a demonstrated command/safety violation or measured performance regression.

**Producer/consumer check:** repository searches find `ControlDemand.brakingConstraints` consumed by tests, not the current production actuator execution. `BrakingConstraintProjectionTest` verifies construction but cannot prove a production consumer. The public `AbstractATO` default implementation does use `demand.targetSpeedMetersPerSecond`, and `AtoPlanner` lives in a documented API namespace; this report does **not** conclude that every public type can immediately be deleted. There are also old `ai_tasks/experiments` callers, which are not current production.

**Minimal remedy:** remove the unused concrete-controller computation first; capture the constraints once per tick and compute any explicitly retained diagnostics from that same capture. Decide public API retirement separately from the current implementation cleanup. Do not substitute another planning abstraction. If keeping diagnostic permitted speeds, clearly label them as diagnostic legacy envelope values, not the ATP limit that selected the actual command.

## Risks / decisions requiring further evidence

### R01 — generic perception still borrows station tolerance, delaying PASS

`SimpleATO.kt:1231-1234` accepts any observed rail object up to `stopToleranceMeters` behind the segment start and maps it to distance zero. `:1180-1197` then reports UPDATE, not PASS, as long as that reference remains in the current observation map. For an object at s=100 and a head at s=100.1 on the same rail, the observation remains at distance 0 until the head moves beyond 100.3. This is a direct source-level counterexample to exact crossing semantics; it is not a floating-point issue.

Scope matters: this generic collector includes native speed signals, train ends and arbitrary registered objects, not only parking targets. The private `stopAt` handling at `:919-929` can reasonably need arrival semantics, but that is not a reason to apply parking tolerance to every observed object. For a same-rail custom speed-release signal, `processSpeedLimitTransitions` at `:1132-1142` would delay release until the delayed PASS. However, the only in-repository production `OperationalSpeedLimitSignal` producer materializes **rail-entry** signals, and head-rail metadata refresh already handles those native boundaries. Therefore no current ordinary native-signal failure is claimed from this counterexample, nor is it the demonstrated SimpleSignals red-signal bug from the prior task. It is a generic API/semantic risk and an explicit remaining non-parking tolerance site; a real custom-signal consumer or targeted transition test should precede a stronger severity claim.

Minimal direction: exact forward membership for generic observations; retain parking acceptance only at parking-specific consumers. Inspect object-transition consumers before deletion, rather than blanket-removing all `coerceAtLeast` calls.

### R02 — nominal reference profile failure is classified as ATP failure

`VehicleControlExecutor.kt:47-50` suppresses the cause of nominal-profile preparation failure with `runCatching(...).getOrNull()`. Even after `current` emergency protection is SAFE, `:93-97` converts missing grade-specific **ordinary** reference into `AtpStatus.UNAVAILABLE / INVALID_MODEL`, then triggers the ATP latch or simulation abort at `:169-177`. `MotionReference.kt:98-104` has a nominal grade domain [-0.2, 0.2], whereas the hard ATP validation accepts [-1,1] (`ConservativeAtpModel.kt:88`). An uphill grade outside the nominal lookup range can therefore produce an “ATP intervention” solely because ordinary planning lacks a profile.

This is distinct from missing device snapshots, which really do invalidate hard protection, and distinct from an actual hard-safe candidate set being empty. The spec explicitly separates operating model/precision from hard ATP. The source branch is confirmed; current deployed railway data has not been shown to reach it. At minimum preserve/report the real nominal failure reason and distinguish an automatic-controller availability failure from an ATP witness; do not blindly continue moving when no ordinary strategy exists. A deliberate domain restriction at setup may be simpler than a second runtime fallback strategy.

### R03 — SIMULATION conflates execution mode with failure policy

`RailwayControlRuntime.supervise` at `:68-69` passes a manual command but substitutes `VehicleExecutionMode.SIMULATION` for `MANUAL` when the world simulation flag is set. `VehicleControlExecutor.choose` at `:85-98` treats every non-MANUAL mode as automatic, so a manual simulation proposal is ignored and an automatic ordinary strategy chooses the command. `automatic` also uses SIMULATION to request abort-on-intervention. This is a type-design conflation: driver mode and abort policy are independent dimensions.

The public/internal call combination is reachable, and `Basic/.../control/RealisticManualController.kt:161` is the manual supervisor caller. The normal isolated-timetable setup installs automatic controllers; no current UI path that intentionally simulates a manual controller was established during this review. Record as a future/manual simulation risk, not as the cause of Tick 14219. If manual simulation is supported, preserve manual selection while applying the same simulation abort policy.

### R04 — initial-state diagnostic retention has no per-train retirement

`AtoWorldCoordinator.kt:52,66-70` retains every `TrainSnapshot` ever registered in `initialTrains`. `AbstractATO` records at construction; `reset` does not clear the map, and there is no removal API. In contrast `RailwayControlRuntime.retireMissing` has an actual live-train reconciliation consumer in `OperationalWorldRuntime`. A long-lived world repeatedly creating/deleting unique-ID trains accumulates these diagnostic records. Severity is low (small snapshots; no measured leak or lifecycle load test); decide whether “initial” belongs to the current registered physical train, the current controller, or the lifetime of an ID before adding the minimal removal hook. Do not introduce another lifecycle manager.

## Verified exclusions / proportionate existing mechanisms

- **Not missing ahead-of-train native speed limits:** metadata setter materializes direction-filtered entry signals; ATO sees them and existing controller tests assert the future braking target.
- Exact controller/physical-train identity is already checked in `RailwayControlRuntime.entry`; a same-ID new physical train does not inherit the old ATP latch. Retaining the latch across driver/controller replacement on the same train is intentional and tested.
- Candidate caches are cleared on every `choose`; the far-target fast path still hard-checks the selected command and falls back to strict candidate checking. This is not an unprotected bypass.
- Ordinary operating stops are intentionally excluded from physical ATP endpoints; physical MA retains signed distances and an explicit hard target. Do not remove this distinction to “simplify” the types.
- Nominal event algebra and conservative ATP algebra serve different contracts. Similar-looking brake calculations do not by themselves justify combining them; actual device advance is shared with Basic, and the tests compare analytic predictions against real Basic dynamics.
- Mission/request list copying uses private data-class constructors and defensive collection copies. No new mutable-alias issue was found in the assigned mission value types.
- The scalar diagnostic envelope is documented as retained; C02 targets the unused extra plan construction, not indiscriminate deletion of all diagnostic queries or entry-clearance geometry.
- Known K01–K03 publication/frame/length/request-floor defects are recorded separately in `known-14219-issues.md`; they are not counted as newly found here.

## Files found and per-file coverage

All paths in the following production list are under `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/`. **Read** means complete source read; **chain** means relevant producer/consumer tracing; no new runtime result is claimed.

- `controller/SimpleATO.kt` — Read + chain: commands, mission/station state, scan, native limits, restrictions, frames/publication, diagnostics; C01/C02 and R01.
- `control/AffineActuatorResponse.kt` — Read: finite affine segment/event arithmetic; matched tests' real-kernel comparisons; no mathematical formal proof.
- `control/ManualProtectionFrame.kt` — Read + chain: native body/forward limits, obstacles, observation boundary; symmetry evidence for C01.
- `control/MotionReference.kt` — Read + chain: nominal grade variants/interception/latch; R02.
- `control/NominalServiceBrakeModel.kt` — Read: known-interval shared device update, finite future event approximation, missing-state handling.
- `control/RailwayControlRuntime.kt` — Read + chain: exact train identity, per-train executor, manual/automatic routing, history/cleanup; C01/R03.
- `control/ReferenceStrategies.kt` — Read: ordinary safety filter, nominal reachability, terminal holding, command-delay retention, target extension.
- `control/RouteGradeBounds.kt` — Read + chain: body traversal coverage and full-curve conservative grade cache; intentionally broad, not a missing-native-speed substitute.
- `control/TrainControlFrame.kt` — Read + chain: signed raw physical endpoints vs operating marks, owner-injective IDs, list copies.
- `control/VehicleControlExecutor.kt` — Read + chain: per-tick ATP, candidate cache, nominal profile, intervention/ack/release; R02/R03.
- `atp/ConservativeAtpModel.kt` — Read: capability/state validation, current/candidate response, grade table, EB/service guarantees, rounding; no formal numerical proof.
- `atp/GradeBrakeTable.kt` — Read: adverse-bin rounding and positive braking domain.
- `api/ato/AbstractATO.kt` — Read + chain: actuator call, freeze, prepared consist/config changes, publication and stall; known K01 corroborated.
- `api/ato/AtoPlanner.kt` — Read + all repository symbol references: C02, retained public/default-controller caveat.
- `api/ato/AtoSafetyProfile.kt` — Read: SI validation, shared parking policy, station authority margin.
- `api/ato/AtoStallReportJson.kt` — Read + producer: structured diagnostic writer; no schema edits or version judgement. StopConstraint JSON omits its explicit margin/operatingTarget, so report fidelity could be improved, but this is not a runtime safety defect.
- `api/ato/AtoWorldCoordinator.kt` — Read + chain: first-failure interlock, report/initial-state map, reset; R04.
- `api/atp/AtpModel.kt` — Read: public typed judgments/witnesses/targets.
- `api/constraint/AtoConstraint.kt` — Read + chain: scalar diagnostic limits and raw control-frame conversion; no unintended signed-MA truncation in ATP.
- `api/constraint/ConstraintManager.kt` — Read: owner replacement, lock, duplicate keys, snapshots.
- `api/mission/AtoMission.kt` — Read + resolver/controller consumers: immutable values, continuity/directions, exact physical origins. Route planner internals are assigned to main.

Corresponding test files, all under `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/`, completely read (not executed):

- `controller/SimpleATOTest.kt` — command activation, publication visibility, hold/MA distinction, real formation origin, stops/following, native forward signals, realistic equipment parking. Most helper ticks directly call `ato.tick/commit`, so these do not replace true PhysicsCoordinator epoch assertions. No automatic occupied-body native cap test.
- `control/ManualProtectionFrameTest.kt` — real body coverage between bogies and missing-body rejection; C01's independent geometry template.
- `control/ReferenceProtectionIntegrationTest.kt` — real per-car Basic dynamics plus executor, stops/positive limits/holding; no world/native-rail composition.
- `control/ReferenceStrategyTest.kt` — curve/reference behavior, nominal-vs-hard distinction, stopping latch, real-kernel event comparisons; includes `ReferenceEquipmentFixture`/`ImmediateEquipmentFixture`.
- `control/TrainControlFrameTest.kt` — operating vs physical target distinction, signed passed MA, injective IDs.
- `control/VehicleControlExecutorTest.kt` — latch/ack, command cache, reserve/far-target behavior, simulation abort; no manual simulation or nominal grade-domain failure test.
- `atp/ConservativeAtpModelTest.kt` — real kernel parameterized certificates, candidate intervals, current/positive limits, state validation, conservative grade bin tests; numerical test space is broad but not a proof over all configurations.
- `api/ato/AbstractATOConsistTest.kt` — prepared split/reload state, failed setup preservation.
- `api/ato/AbstractATOMotionPublicationTest.kt` — true PhysicsCoordinator train/car/bogie motion and freeze; does not compare the ATO snapshot's epoch/head against the resulting frame (known K01 gap).
- `api/ato/AtoPolicyTest.kt` — scalar envelope transfer/owner replacement/world isolation/diagnostic JSON.
- `api/ato/FormationHeldAtoPreparationTest.kt` — real held mission preparation without actuator calls; stale origin rejection.
- `api/mission/AtoMissionTest.kt` — immutable lists, waypoint failure, direction carry and no partial mission.
- `api/constraint/BrakingConstraintProjectionTest.kt` — constructs legacy scalar braking demand but does not establish that production consumes it (C02).

Adjacent files read for actual chain closure, **not** claimed as a complete review of their modules:

- `Basic/.../rail/OperationalSpeedLimitSignal.kt`, `OperationalSpeedLimitSignalMaterializer.kt`, `RailInfrastructureMetadataStore.kt` — native signal production/lifecycle/metadata access.
- `Basic/.../safety/SpeedSafetyMonitor.kt` — actual-body/swept-span safety consumer.
- `Core/.../physics/rail/RailView.kt:93-103` — direction-filtered static signals and dynamic train ends.
- `SimpleATO/.../inspection/EntryClearance.kt` — determines whether scalar legacy planner output has another consumer; entry clearance actually reads constraints directly.
- Targeted references in `OperationalWorldRuntime`, `TimetableDispatcher`, `RealisticManualController`, simulation composition and Basic actuator API.

## Related specs and instructions read

- `.trellis/workflow.md`; `.agents/skills/javarailways-code-review/SKILL.md`; its full `references/missed-findings.md`.
- `.trellis/spec/javarailways/simpleato/index.md`, `controller-runtime.md`, `reference-control.md`, `ato-policy-runtime.md`.
- `.trellis/spec/javarailways/basic/speed-and-derail-safety.md`, `ato-missions-and-perception.md`.
- `.trellis/spec/javarailways/shared/versioning.md`, `ai_tasks/persistence_schema_evolution_rules.md` before examining diagnostic format semantics.
- Quick memory registry pass used only for review-scope/method context (`MEMORY.md:418-425`); all source findings above are verified against current files, not historical findings.

## External references

None required: this is an internal implementation audit. No web factual claims, version recommendations or external API behavior relied upon.

## Caveats / Not Found

- No production/test/spec file was edited and no test/build/remote command was run by this researcher. The only extra source is the audit-only probe under this task's research directory.
- No complete review of Core, Basic, SimpleSignals or timetable/inspection internals is claimed. Static adjacent-chain verification does not prove runtime scheduling, stress behavior, or the absence of other issues.
- No user-facing statement should call the whole SimpleATO implementation “wrong”: C01 is a specific missing automatic safety input, C02 is unused work, and R01–R04 have deliberately narrower evidence and reachability limits.
