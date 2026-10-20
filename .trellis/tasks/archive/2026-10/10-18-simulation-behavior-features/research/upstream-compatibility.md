# Research: upstream compatibility of signal and per-car simulation

- Query: Ensure the uncommitted simulation candidate preserves the new `dev/signal-system` behavior and API contracts.
- Scope: internal; static API/semantic review of Core, Basic, SimpleATO, Bukkit behavior/composition, and SimpleSignals consumers. No builds or product edits.
- Date: 2026-10-20
- Compared baseline: `9ed278181a577ebc1450bd307fe466d47ed91f96`.
- Upstream inspected: `87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7` (`dev/signal-system`). All upstream line references below refer to that commit, not the old candidate files.
- Candidate read: `.worktree/simulation-signal-features`, inherited signal WIP plus behavior WIP. Main session owns snapshots, alignment worktree, conflict resolution and checks.

## Findings

### 1. Compatibility requires semantic migration, not only conflict marker resolution

The old candidate retains removed imports, changed lifecycle signatures and older factory behavior. A successful three-way textual apply cannot prove compatibility. Preserve upstream files first, then adapt the added consumers described below. No compatibility aliases, duplicate registries or former DI lifecycle should be restored.

### 2. Core owns the one world-local infrastructure arbiter

- `Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt:121` creates `infrastructureCoordination`; structural edits use it at line153, and turnout selections use its claim/edit protection.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt:354-355` now borrows `world.infrastructureCoordination` and retains a `registerResourceValidator(::resourceExists)` token. Its construction-failure and normal cleanup close only that token and the admission runtime (lines411/422).
- Therefore the candidate's former private `InfrastructureCoordinationRegistry(...)` and `registry.close()` must be replaced with the upstream world-owned registry/token contract. Otherwise route claims and Core topology edits arbitrate through different owners, allowing unsafe edits despite apparently valid simulation admission.
- Claim-authenticated physical actuation uses `withClaim`; structural edit protection uses `withEditPermit`/`ensureEditProtected`. Do not bypass the new gate in simulation or tests. New simulation initialization remains before first physics wake; initial construction is not a license for later unprotected mutation.

Concrete package migrations for newly added candidate files and test fixtures:

- Infrastructure resources, dependency values, owners and topology-edit request/result values: `basic.api.infrastructure.*` -> `core.api.physics.infrastructure.*`. `ManualTurnoutSelection` remains in Basic; do not globally replace the entire old package.
- Registry and infrastructure claim implementation: `basic.infrastructure.InfrastructureCoordinationRegistry` -> `core.physics.infrastructure.InfrastructureCoordinationRegistry` (and corresponding claim helper types).
- Admission implementation classes: `basic.infrastructure.RouteResourceAdmissionRuntime` and the compiler/tail-release/preparation family -> `basic.infrastructure.admission.*`.
- Rail object store: `basic.api.ato.provider.RailObjectStore` -> `basic.api.rail.RailObjectStore`.
- `RouteAllocationTiming`/`RouteBuildResult` moved from `basic.route` to `simpleato.routing`; check new test consumers rather than resurrecting Basic copies.
- Candidate public `simpleato.api.simulation.SimulationResourceProvider` and new `SimpleSignalsSimulationFeature`/extracted fixed-block domain policy currently refer to the old StableInfrastructureResource package and need the new public Core type.

### 3. Topology preparation now tolerates unrelated edits using actual dependencies

- `Core/.../api/physics/rail/topology/TopologyState.kt:277-289` adds process-local `railIdentities` and `preservesRails(previous,railIds)`. A replacement with the same UUID has a different incarnation. Test fixtures that reconstruct a topology representing the same physical rails must retain identities; a real replacement must change them.
- `PreparedRailCreationBatch` includes a candidate spatial snapshot. Unrelated topology revision or inventory changes alone no longer invalidate the batch.
- `PreparedRailTopologyUpdate.targetRoot` is preview/undo data; commit applies a local delta to current state. Do not reinstall the preview root or restore global root-identity rejection in candidate code.
- `PreparedRailConnectionPlan` validates affected exact rails/endpoints and uses Core edit protection. A two-argument `PreparedRailEndpointConnection` asks capture to select the straightest tangent (input order breaks ties); a supplied third argument, including null, is exact. Do not assume implicit first-connected selection.
- The existing factory's former copy that sorted saved endpoint connections and overwrote active selections is removed upstream (`BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt:125`). Preserve direct `saveInfrastructure(revision)` and the saved physical selection. Candidate feature compilation still must bind target topology, not the captured live root.
- `configureTurnoutForTest` now accepts exactly two branches and delegates `restoreConnections`. Newly added tests should use the latest fixture rather than mutable endpoint internals.

### 4. Core lifecycle and extension access changed

- `CoreWorld.createEmpty` (CoreWorld.kt:227) and `SnapshotDependency` (line1641) no longer take `WorldParentContext`. Core DI/world-parent classes and `releaseRuntimeBinding()` are removed. Delete candidate imports, dependency arguments and close callbacks; preserve physics -> tick cleanup.
- `Destroyable.destroy(): CompletionStage<Void>` is now mandatory (`Core/.../api/util/Destroyable.kt:8`). Completion means all owned cleanup completed; repeated calls return the same result via `Destruction`. `DestructionSteps` starts all cleanup and preserves failures without blocking owners.
- Candidate `SimulationCarBehaviorExtension` and its delayed `TickingObject` implement the old Unit destroy signature. Migrate both and any new test render extension to the new stage contract. Failure completion must remain visible; returning an already-successful stage before real cleanup would violate it.
- CoreTrain.destroy now queues/executes under its coordinator's admitted owner and returns the actual cleanup stage (CoreTrain.kt:1249). Tests that destroy a train and immediately assert occupancy/claim release need to advance/wait the real owner result. Avoid waiting under an owner monitor for a queued operation on that same owner.
- `CoreTrain.create` still exists (CoreTrain.kt:1388). The latest factory intentionally uses `CoreTrain.createOwned(world)` (factory line330): create bogies/cars/train, attach/init resources, set location, and `publish()` inside that ownership scope. This covers partial-construction cleanup. Keep this upstream safety when adding captured per-car behavior, extension data and profile wiring; register rollback where resources are acquired before publication.
- Only `forEachExtensionData(action:(Base)->Unit)` remains (`Extendable.kt:21`); the generic list-returning overload was deleted. Candidate `Car.simulationBehaviorOrNull` currently uses `forEachExtensionData<RenderableExtension<Car>> { it }` and will fail to compile. Use the established exact plugin slot or a Unit visitor; do not revive the generic API.
- `CarExtension` render fallback now uses `self.initialRenderState`, not deleted/obsolete `lastState` behavior. Preserve latest render cleanup/Destruction implementation while adding read-only capture helpers.

### 5. Guest failures are local; unexpected host failures are process terminal

This is an upstream business-contract change, not just a constructor update:

- `BukkitPlugin/.../behavior/TrainBehaviorRuntime.kt:11,50-58,91-96` introduces `TrainBehaviorScriptException`. Guest Polyglot failures become that type; host exceptions are unwrapped and rethrown. Missing method/member remains an ordinary `TrainBehaviorCallResult.Failure`.
- `CarBehavior.kt:18` adds required `scriptFailed` callback. Metadata errors (31-40) and actual guest calls (88-93) invoke it. Normal rejection or unavailable animation does not masquerade as an unknown host failure.
- Latest `CarExtension.stopTrainForScriptFailure` records concrete car/train and submits a frame-boundary derail of its current exact owning train. Candidate SimulationCarBehaviorExtension needs equivalent target-only owner routing/diagnostics, reading current `car.train` when the failure occurs so split/link cannot stop an old owner.
- `Core/.../physics/tick/RailwaySimulationHalt.kt:9-24` is explicitly process-wide. Every PhysicsCoordinator registers (PhysicsCoordinator.kt:201); `stall` now delegates to it (447), unexpected worker failure invokes it (949ff), and all coordinators reject future work. The new CarBehavior host-failure branches also call it.
- This does not reintroduce cross-world normal execution serialization. It does mean a fatal internal/host bug in any SIM can stop LIVE and other SIMs. An unconditional claim of isolation from every possible failure would be false after alignment.
- The latest authoritative spec `.trellis/spec/bukkit-plugin/backend/car-behavior.md:120-122` explicitly establishes guest-local derail versus unknown-host global halt. Preserve that upstream policy while adapting normal script/rejection tests. If the parent task intends stronger failure-domain isolation, main session must resolve that policy explicitly, rather than silently changing Core or reporting full failure isolation.
- Deliberate global-fault tests use upstream `@IsolateGlobalHalt` (`Core/src/testFixtures/kotlin/work/art1st/javarailways/core/testing/IsolateGlobalHalt.kt:28`), running only that invocation in a child JVM. Do not add a production global reset or weaken failure tests to protect the rest of the JVM.

### 6. ATO/control snapshot consumers must retain upstream changes

- `Basic/.../api/ato/TrainActuator.kt:33` changes `freeze()` to Unit, with freezing motion remaining controller-owned. `brakingEnvelope(nonEmptySegments)` is mandatory and non-null at line42. New test actuators must implement it with the actual envelope contract rather than a null/default fallback.
- `PreparedDynamicsChange` now requires finite positive service deceleration and finite nonnegative reaction time. Captured per-car/consist profile composition must pass both; preserve real HandleTrainActuator preparation rather than creating partial dynamics state.
- `BasicWorldData.turnoutSafetyDefaults` and candidate snapshot's corresponding field are removed. Keep new `atoAdmissionRetryTicks` captured from the LIVE dispatcher and passed to target dispatcher (factory lines54/171). This is detached scalar state, not a shared dispatcher reference.
- `SpeedLimitMetadata` is immutable and retains its wire shape. Use its current constructor/value methods; do not restore data-class `copy` assumptions. Existing public target `railSpeedLimits(topology,ids)` is still supported, so only current value capture is required.
- `TrainSnapshot.capture(train,revision)` now captures one published frame, including each end's own facing; old third-facing argument is gone. Newly added event/joint fixtures should follow it. TrainFrameSnapshot gained `derailReason`; local guest-stop assertions can read the real published state/reason.

### 7. Preserve the newer authoring retry while retaining per-world signal execution

- Upstream changed `SimpleSignalsWorldRuntime.kt` imports and authoring, not `SignalLogicRuntime`/SignalPack catalog/epoch implementation. Thus candidate per-world engines, model-neutral catalog rules, detached prepared feature, local close/issue identity fixes should survive alignment; do not reset them to shared-manager execution.
- Upstream manager adds `RETRY_REQUIRED` (line144), `applyDefinitionPatchAttempt(..., topologyRetryAvailable)` (1702), and one retry on an unrelated topology/publication change (1917-1935). It validates only actually selected rails through `preservesRails`, retaining expected definition CAS and original selection root. A second continued unrelated change asks the user to retry unchanged selection.
- The candidate manager substantially shortens directory locking and adds local management/issue/engine ownership. Conflict resolution must preserve both: the new authoring algorithm executes under the existing per-world transaction/preparation boundaries, and must not reintroduce a directory lock over compilation, waits, recursive retry or engine close.
- Selected-rail removal/replacement still rejects; unrelated edits may retry. Candidate fixtures assuming every root change is `STALE_TOPOLOGY` need explicit distinction rather than broad assertion replacement.
- Extracted FixedBlockDomainResourcePolicy must use the migrated Core resource DTO while sharing the exact LIVE policy algorithm. Resource preparation/tail release/reversal must be checked on the new admission implementation.

### Necessary verification after alignment

1. Compile all changed production/test sources plus `:BukkitPlugin:compileSignalApiConsumerJava`; old imports/signatures in untracked added files are easy to miss with ordinary changed-file conflict scans.
2. Re-execute focused CarBehavior, TrainBehaviorRuntime, SimulationCarBehaviorExtension/gateway, capture/factory, profile split/link, and new joint fixture tests. Verify actual XML discovery, not only task success.
3. Guest malformed/load/declaration/call failure: target train derails with its reason; another same-source SIM and LIVE continue physics/signals; failed ticket reaches FAILED; cancellation remains CANCELLED; cleanup doesn't affect another target. Host-fault tests deliberately assert the upstream global policy in an isolated invocation/JVM.
4. Target destruction and delayed effects: stage completion, every cleanup attempted, idempotence, true worker exit before closing Context; accepted effect completion must not revive cancellation. Capture source can close/reload after detached target install.
5. Real topology/claim integration: active grant blocks structural mutation in the same Core registry; unrelated edits are allowed; validator retirement removes only target registration; target close doesn't close the world arbiter.
6. Re-run signal manager official JS/occupancy/protection/inspection/admission/reversal cases, including LIVE-LIVE/LIVE-SIM/SIM-SIM barriers and same-UUID diagnostic race. Test authoring unrelated-edit retry together with existing directory-lock-release scenarios.
7. Run complete Core, Basic, SimpleATO, SimpleATOBukkit, BukkitPlugin, SimpleSignals and remaining repository test/build gates on the new combined source fingerprint. The previous 2411-test baseline and pre-upstream behavior focused run prove neither the aligned source nor upstream/new-test test count.
8. Keep module/JAR boundary verification, public Java consumer, docs/spec consistency, whitespace and task context validation. Latest upstream specs must be the base for task documentation changes; retain both upstream contracts and new simulation additions.

## Files Found

- `Core/.../physics/CoreWorld.kt`, `physics/infrastructure/InfrastructureCoordinationRegistry.kt`: physical edit/claim arbiter now owned by the exact World.
- `Core/.../physics/rail/PreparedRailCreationBatch.kt`, `PreparedRailConnectionPlan.kt`, `PreparedRailTopologyUpdate.kt`: dependency-based preparation and protected local edit semantics.
- `Core/.../api/physics/rail/topology/TopologyState.kt`: rail incarnation identity and local preservation checks.
- `Core/.../api/util/Destruction.kt`, `Destroyable.kt`, `api/util/plugin/Extendable.kt`: asynchronous cleanup and Unit extension visitor contract.
- `Core/.../physics/train/TrainCreation.kt`, `CoreTrain.kt`: owned creation, owner-routed destruction and actual completion stages.
- `Core/.../physics/tick/RailwaySimulationHalt.kt`, `PhysicsCoordinator.kt`: process-terminal host failure gate.
- `Core/src/testFixtures/.../TestWorldFixture.kt`, `TrainTestUtils.kt`, `TopologyTestFixture.kt`, `IsolateGlobalHalt.kt`: changed composition, topology identities and terminal-failure test process ownership.
- `Basic/.../api/ato/TrainActuator.kt`, `control/ConsistDynamics.kt`, `control/HandleTrainActuator.kt`: mandatory braking and complete prepared dynamics.
- `Basic/.../infrastructure/admission/*`: migrated actual admission algorithms.
- `BukkitPlugin/.../behavior/CarBehavior.kt`, `TrainBehaviorRuntime.kt`: new failure classification and required owner callback.
- `BukkitPlugin/.../extension/train/CarExtension.kt`, `render/Animation.kt`: staged cleanup and current render baseline.
- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt`: selection snapshot, owned train construction, ATO retry capture.
- Candidate added `BukkitPlugin/.../operation/SimulationCarBehaviorExtension.kt`: concrete old visitor/destroy/CarBehavior constructor consumers needing adaptation.
- `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt`: borrowed World arbiter and removed DI lifetime.
- Candidate public `SimpleATO/.../api/simulation/PreparedSimulationFeature.kt`: migrated StableInfrastructureResource consumer.
- `SimpleSignals/.../runtime/SimpleSignalsWorldRuntime.kt`: new targeted topology authoring retry intersects candidate local-lock changes.

## Related Specs

Read `.trellis/workflow.md`, task `prd.md`, `design.md`, `implement.md`; no research manifest exists. Research role intentionally does not read implementation/check JSONL.

Current upstream specs consulted directly through `git show dev/signal-system:<path>`:

- `.trellis/spec/bukkit-plugin/backend/car-behavior.md` (especially latest guest/local versus host/global failure contract).
- `.trellis/spec/javarailways/core/di-and-runtime-lifecycle.md`.
- `.trellis/spec/javarailways/core/extension-data.md`.
- `.trellis/spec/javarailways/core/train-physics.md` and `testing.md` (relevant sections).
- `.trellis/spec/javarailways/core/rail-topology-and-occupancy.md` (protected/prepared topology sections).
- `.trellis/spec/javarailways/shared/versioning.md` and `ai_tasks/persistence_schema_evolution_rules.md`: preserve upstream current-format decisions; this alignment does not authorize adding compatibility or bumping schema versions.
- Candidate Bukkit/SimpleSignals indexes consulted for local simulation additions; their old contract text does not override newer upstream implementations/specs.

## External References

None required: the task is source-contract compatibility against the exact local Git revision; no evolving third-party behavior was inferred.

## Caveats / Not Found

- This report is static research, not a successful merge, compile or runtime acceptance claim. The main session owns the actual combined candidate, conflict fixes and gates.
- Latest dispatch selects the behavior task directory; stale injected signal-task header was not used for output ownership.
- No source/spec/task metadata edits, build, Git mutation, commit or merge were performed by this researcher. The only written artifact is this report.
- No relevant historical memory fact is required for these conclusions; the memory registry quick search only reinforced verifying current branch/contract rather than treating historical validation as current evidence.
