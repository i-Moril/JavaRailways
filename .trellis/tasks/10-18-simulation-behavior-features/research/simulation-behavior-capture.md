# Research: Detached per-car behavior capture and isolated operation composition

- Query: Capture real per-car behavior/action/direction inputs, reuse LIVE behavior execution in SIM, remove duration-only operations, and identify real integration/cleanup seams.
- Scope: internal
- Date: 2026-10-19
- Workspace: `/workspace/minecraft/JavaRailways/.worktree/simulation-signal-features`; existing verified signal candidate remains untouched.
- Active task: `.trellis/tasks/10-18-simulation-behavior-features` from explicit dispatch. The injected hook still named the preceding signal task; no task state was changed by this research agent.

## Findings

### 1. Minimal composition does not require a module move

`IsolatedTimetableSimulationFactory` already lives in BukkitPlugin and constructs the operation gateway before handing it to the target Dispatcher (`http/IsolatedTimetableSimulationFactory.kt:157-160,184-205`). `TimetableDispatcher` already accepts the public Basic `TrainOperationGateway` (`SimpleATO/.../timetable/TimetableDispatcher.kt:273-283`). The existing `dispatcherFactory` callback receives the exact target CoreWorld (`SimpleATO/.../simulation/IsolatedTimetableSimulation.kt:248-254`).

Therefore keep `CarBehavior`, `CarOperationBatch`, `TrainBehaviorRuntime`, and persisted Script/Action data classes in the Bukkit module. Add an **internal** SIM operation/effect adapter there, instantiate it in the factory, and inject it through the existing gateway parameter. No new public behavior SPI, Basic Graal dependency, persistence type relocation, generic environment/DI/clone registry, or module graph change is needed for production. Excluding Bukkit effects does not require removing BukkitPlugin classes from the JVM classpath.

`CarBehavior` already separates script/actions, animate, sound, schedule, and log callbacks (`BukkitPlugin/.../behavior/CarBehavior.kt:11-18`). Both LIVE `CarExtension` and the SIM adapter can directly reuse this implementation. `CarOperationBatch` already aggregates exact targets, completes only after acceptance plus all effects, preflights custom-action metadata/permissions, and prevents dispatch of pending-cancelled targets (`behavior/CarOperationBatch.kt:8-73`). Reuse it; a SIM gateway must not reimplement supporter selection or effect completion.

### 2. HTTP capture is already on the correct boundary

`TimetableHttpBridge` validates and calls `factory.capture` inside the selected source World's owner/frames boundary, then submits only the snapshot to its executor (`http/TimetableHttpBridge.kt:465-483`). Timeout and interruption cancel the Future and the `.use` scope closes the simulation (`:489-505`). No extra HTTP environment or live-World reference should be introduced.

The static facility snapshot plus synthetic timetable trains remains the initial condition. Do not copy live JS Context/global variables, active operation tickets, reservations, existing Dispatcher runs, driver sessions, or renderer instances.

### 3. Capture exact car package inputs, never merged train durations

Current `SimulationControlProfile` carries geometry/controls plus `modelActionDurationTicks` (`http/SimulationControlProfiles.kt:31-44`), and the factory computes completion by action-name maximum (`http/IsolatedTimetableSimulationFactory.kt:361-388`). This loses per-car JS, action ownership, rejection, supporter sets, and independent state.

For WORLD_TRAIN, each car's live behavior/actions come from `CarExtension.operationalSnapshot()` (`extension/train/CarExtension.kt:146-164`). SNAPSHOT uses that car's pinned source package; FOLLOW_PACKAGE uses the latest successful resource version's `carPackages[carModelName]`. Its behavior resolver is currently private. Add an internal, value-only capture method under the existing car monitor which reads one exact operational source and produces detached script/actions/capability values. Capture must not initialize JS, touch region-owned model state, or retain the CarExtension/source World/Plugin.

For TRAIN_TEMPLATE, `ModelManager.resourceVersion(templateId)` already supplies `composition`, `models`, and **per-car** `carPackages` (`config/model/ModelManager.kt:98-120`; `TrainResourceVersion.kt:4-9`). Use `version.carPackages[qualifiedModelName]` for each car's behavior/actions/control. Current `loaded.actions` is the train package's map and is wrong for mixed source packages. Do not choose another package merely because it contains a missing action/script.

Suggested internal blueprint values (names illustrative): script value, copied per-car action map/lists, immutable animation/scene capability values, and `installedReversed`. Existing immutable configuration values can be shared, but no mutable provider/runtime/model graph can be retained. Add these to each `SimulationCarBlueprint`, preserving one-to-one geometry/control/behavior correspondence.

Both source types must yield the same internal blueprint. Same snapshot may create multiple targets; every target/car creates its own CarBehavior/Context and timing state.

### 4. Direction capture must separate installation from operating state

LIVE accepts an operation by capturing exact car objects and:

`relativeTail = controlTail xor (car.isReversed xor train.isReversed)`

(`extension/train/TrainExtension.kt:51-58`). Script receives semantic side and `operatingEnd()`. The no-script YAML fallback performs its own one-time physical-side conversion in `CarBehavior.door` (`behavior/CarBehavior.kt:38-54`). Do not pre-flip sides in the gateway and then let the script flip them again.

For WORLD_TRAIN, capture static **installed direction** as `car.isReversed xor train.isReversed`, matching the existing persistence calculation (`Core/.../train/CoreTrain.kt:1363`). This is a car installation property, not a copy of current live driving state. Template composition currently contains only names (`TrainDefinition.kt:16`), so template car installation defaults to false.

`CoreCar.reverse()` is public and selects reversed bogie/end state (`CoreCar.kt:656-659`). The factory can apply the captured installation orientation to freshly constructed standalone cars before `CoreTrain.create`; geometry/waypoint direction remains the existing synthetic placement. Validate mixed installation through real Core creation/placement, not just a Boolean helper.

At **each new operation**, obtain current target train/car direction and fix the relative end for that target set. Delayed effects must not re-read later direction or train.cars. SIM has no copied manual-driver session: the existing LIVE no-session policy uses TAIL iff the target train is reversed (`control/TrainControlManager.kt:91-94`). A small shared Boolean direction helper may be used by LIVE and SIM, without moving platform ownership logic into Basic.

### 5. Existing car extension lifecycle is available without a new plugin identity

`CoreCar.create` constructs a new empty `ExtensionDataContainer` by default (`CoreCar.kt:46,718-744`). Its constructor initializer only initializes already attached extensions (`:251-253`); it does not inject a CarExtension. `NoOpWorldParentContext` only returns a no-op binding (`Core/.../di/NoOpWorldParentContext.kt:8-13`).

LIVE Bukkit car extensions are attached **explicitly** after creation by `TrainCommand.kt:284-287` or during restoration by `WorldManager.kt:313-317`. The existing isolated factory directly creates CoreCars and attaches none (`http/IsolatedTimetableSimulationFactory.kt:331-350`). Thus the factory can attach a minimal no-display `RenderableExtension<Car>` in the **existing Bukkit plugin slot**, initialize it with the exact synthetic car, and let it own the reusable CarBehavior. No new global Plugin registration/identifier is required, and there is no existing renderer slot to overwrite in this path.

The extension's commit performs no display action; save returns null because its state is runtime-only. Plugin identity is used at seed registration only and should not be retained in the SIM behavior object/effect closures. Production uses the already registered Bukkit marker; tests may supply an already registered core Plugin marker at composition without inventing a production identity. Never hard-code a slot number or modify Plugin.identifier.

Actual destruction chain is `CoreTrain.destroyOnOwner` -> every `CoreCar.destroy` -> `ExtendableRenderableTrait.destroy` -> each attached extension.destroy (`CoreTrain.kt:1222-1232`; `CoreCar.kt:691-694`; `core/util/trait/render/ExtendableRenderableTrait.kt:13-17`). This is the correct place to fail pending per-car effects, cancel scheduled delays, and close that Context. Link/split retaining the car does not destroy this extension, so JS state and accepted effects survive naturally.

Do not replace exact-car ownership with a map keyed only by car ID. Gateway/world close may keep a collection of exact attached owners for shutdown, but the true car destruction callback must also retire each owner; waiting until World.close would leak destroyed-car JS and permit stale delayed effects.

### 6. Logical time must follow existing stage order

SIM performs `preTick(logicalMillis)` -> successful physics ticket -> `OperationalWorldRuntime.afterPublication` (observation, pending derail/safety, Dispatcher, generic `postTick`) -> pending commit drain (`IsolatedTimetableSimulation.kt:202-216`; `runtime/OperationalWorldRuntime.kt:42-56`). Each logical tick is 50ms. Use that target's TickManager and the same elapsed-seconds calculation as `CarExtension.scheduleBehaviorEffect` (`CarExtension.kt:215-235`), with SIM commit executing the recorded effect rather than Bukkit scheduler/renderer/sound. Delays are declared effect start times, not animation playback duration.

Generic tick may run asynchronously before Dispatcher/postTick completes (`Core/.../util/TickManagerImpl.kt:78-103`); per-car monitor must serialize behavior/effect completion/close exactly as LIVE. A newly scheduled effect after the current preTick snapshot starts counting on the next logical tick. Reuse this ordering; do not increment an unrelated gateway counter and complete on a guessed maximum.

Cancellation semantics remain the existing LIVE contract: ticket cancellation prevents late SUCCEEDED and later un-dispatched cars, while already accepted effects can continue. Closing/destroying the owner fails pending per-car operations and cancels its effects. Failed/rejected script/effect paths must become FAILED or Rejected according to existing gateway behavior, not automatic success.

### 7. Gateway resource shutdown needs one explicit owner seam

Current isolated close knows installed Feature tokens, route admission, domain runtime, and World binding, but not a closeable operation gateway (`IsolatedTimetableSimulation.kt:223-232`). Construction-failure cleanup has the same resource list (`:322-329`). New SIM behavior owns Contexts/scheduled state, so registration as TickingObject alone is insufficient.

Add the smallest explicit operation-resource closeable to `IsolatedTimetableSimulationWorld.create`/its owned resource list, or equivalent concrete operation-owner factory result. Do not introduce a generic service registry or use an outer factory wrapper that closes the gateway **before** workers exit. `closeSimulationResources` must still establish true physics/generic callback termination before closing per-car operation dependencies. Both create failure and normal close must attempt all resources in reverse order and preserve primary/suppressed failures. The existing public PreparedSimulationFeature target need not expose CoreWorld just for this host-internal resource.

### 8. Existing duration-only consumers can be removed precisely

Repository source search found:

- `SimulationTrainOperationGateway` and `SimulationTrainOperationDurationResolver`: production consumer only Bukkit's isolated factory; its dedicated SimpleATO test is the only other source consumer. Delete the production duration-only implementation and replace its tests with real shared-runtime gateway tests at its actual new owner. Keep Dispatcher ticket/timeout regressions in SimpleATO.
- `SimulationControlProfile.modelActionDurationTicks`: only declaration/validation and the same factory consumer; no preview consumer was found. Delete field, `actionDurations`, factory `operationDuration`, imports, and related constructor arguments.
- `TrainExtension.resolveActions()`: **also used by `JavaRailwaysCommand.kt:630` to infer operational capabilities**. Do not delete this method solely because simulation stops using its merged results. Its train-level capability consumer is a separate existing contract.
- Cab lights/announcement: old SIM returns zero duration success, but LIVE `BukkitTrainOperationGateway` explicitly rejects every operation except open/close/model (`operation/BukkitTrainOperationGateway.kt:59-63`). New SIM must match actual supported LIVE operations instead of preserving this false success.

### 9. Timetable extensions already enforce detached target creation

`TimetableExtensionRegistry.prepareSimulation()` already fails for every nonbuiltin registered condition/action lacking its corresponding preparation factory (`api/timetable/TimetableExtension.kt:274-288`). It no longer silently filters old opt-in providers. Each prepared `create(world)` builds a new registry and invokes provider factories for that target (`:199-220`). Source provider state must not be retained by its preparation; no further clone framework is necessary.

Existing `TimetableExtensionRegistryTest.kt:98` proves distinct provider objects and unsupported failure; Bukkit factory test `one captured snapshot creates new extension and domain state for every target` at `IsolatedTimetableSimulationFactoryTest.kt:250` covers actual capture/target wiring. Extend with action mutable state/cancellation and two-target isolation if not already covered, rather than changing this public protocol gratuitously.

### 10. Real integration and concurrency entry points

- Host capture/seed/Dispatcher/operation path: `BukkitPlugin/.../http/IsolatedTimetableSimulationFactoryTest.kt`. Existing fixtures create static source waypoints, inject a profile resolver, capture under owner/frame boundary, then run `factory.create(...).simulate(...)`; this is the right place for door completion delaying actual timetable departure, failure/timeout hold behavior, captured source survives retirement/reload, and no source trains copied.
- Shared real JS logic: `BukkitPlugin/.../behavior/CarBehaviorTest.kt` and `TrainBehaviorRuntimeTest.kt`. Existing tests cover independent mutable state, relative door sides, delayed failure/destroy, declaration initialization restrictions. New tests must invoke same CarBehavior/Batch through both actual adapters; do not duplicate JS or implement a fake interpretation.
- Official signal chain: `SimpleSignals/.../runtime/SimpleSignalsWorldRuntimeManagerTest.kt:5971` has real LIVE/LIVE, LIVE/SIM, SIM/SIM barriers; `:6044` detached official compile/occupancy/release, `:6107` actual admission/claim/tail release/direction, `:6200` protection + real Dispatcher/inspection, `:6418` reversal. Helpers `actualLive:6522` and `actualSimulation:6533` use real runtimes and official rules. Reuse their setup facts, not empty sources.
- Collision parity: `Core/.../tick/PhysicsEpochEventsTest.kt:303` runs real collision in both modes and asserts source World/event count and resulting derail. Joint fixture must include actual collision decision/application in the same target carrying real signal and behavior/Dispatcher state, rather than calling that existing separate test proof of joint integration.
- Final concurrency needs actual behavior runtime/owner barrier in A with independent B physically advancing, completing its own delayed effects and signal/Dispatcher output. Run LIVE/SIM and SIM/SIM; same target must remain ordered. Closing A cannot close B, including same profile/source IDs.

Test ownership can remain at Bukkit for internal factory/adapter access. To add real SimpleSignals setup there, a test-only project dependency is an option; production module graph stays untouched. SimpleSignals tests already compileOnly/testRuntime against Bukkit main (`SimpleSignals/build.gradle:46-53`), so alternatively use existing public install contracts with test-only fixtures. Do not expose an internal host adapter as public API or add production classloader dependence solely to make an integration fixture convenient. Main session should choose the smallest acyclic test setup and record it.

## Files found

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/SimulationControlProfiles.kt` — source/template profile resolution and duration approximation to replace.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt` — exact source capture, target composition, synthetic car seed, current duration-only gateway injection.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt` — owner-bound capture, background request, timeout/cancel cleanup.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/operation/BukkitTrainOperationGateway.kt` — actual supported operations, fixed target future completion, atomic ticket state.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/{CarBehavior,CarOperationBatch,TrainBehaviorRuntime}.kt` — reusable true business/runtime implementation.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/{CarExtension,TrainExtension}.kt` — exact source provenance, per-car monitor, physical effects, target direction/cancellation and aggregation.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/{ModelManager,TrainResourceVersion,SavedCarModelTree,PartAnimationRuntime}.kt` — exact per-car resource source and model animation availability definitions.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/{SimulationTrainOperationGateway,IsolatedTimetableSimulation}.kt` — old estimator to remove; real target owner/cleanup/stage integration.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt` — existing gateway injection, operation identity/timeout/control-transfer rules.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/TimetableExtension.kt` — already detached per-target extension preparation and explicit missing capability failure.
- `Core/src/main/java/work/art1st/javarailways/core/{physics/train/CoreCar.kt,physics/train/CoreTrain.kt,util/trait/render/ExtendableRenderableTrait.kt,di/NoOpWorldParentContext.kt}` — synthetic geometry/direction and exact destruction lifecycle.

## Related specs

- `.trellis/workflow.md` — task/research role and source-backed validation boundaries.
- `.trellis/spec/bukkit-plugin/backend/index.md` — composition root and lifecycle quality gates.
- `.trellis/spec/bukkit-plugin/backend/car-behavior.md` — exact-car state, direction conversion, supporter union, accepted-effect/cancellation/destroy semantics.
- `.trellis/spec/javarailways/basic/train-operations.md` — ticket identity, timeout, cancellation, failure hold. Its duration-only SIM section must be updated after implementation.
- `.trellis/spec/javarailways/simpleato/simulation.md` — static facilities plus synthetic train initial conditions, owned workers, current duration placeholder, detached provider protocol.
- `.trellis/spec/simple-signals/backend/simulation-runtime.md` — actual formal signal install path and LIVE/SIM isolation acceptance.
- Parent and child PRD/design/implement files — all-feature scope excludes actual Bukkit rendering/sounds but includes business behavior and extension decisions.

## External references

No external research needed; all claims above are current local source contracts. GraalJS dependency is already `24.2.2` in BukkitPlugin/build.gradle; this research proposes no version/dependency changes or novel API behavior.

## Coordinated implementation handoff

After direct coordination with the main session and sibling runtime researcher:

- **A owns** an internal `CapturedCarBehavior(script: TrainBehaviorScript?, actions: Map<String, TrainActionConfig>, animations: Map<String, Set<String>>)` value plus shared-runtime SIM gateway/no-display car extension, tickets/logging/close, and the animation capability projection helper. Map key presence means a runnable ordinary animation; each immutable set contains its normalized valid scenes and may be empty. Reuse each node's existing `toRuntimeAnimationMap()` to preserve filtering and duplicate-name precedence, and derive scenes through a small read-only view of existing `Animation.sceneTimes` rather than a second parser. Merge capabilities across actual model nodes only.
- **B owns** SimulationCarBlueprint `installedReversed`, exact per-car source capture (including the small CarExtension capture method), profile catalog/factory rewiring and synthetic seed. B passes the exact SavedCarModelTree to A's projection helper and calls A's lifecycle binding for each exact CoreCar using the already registered Bukkit plugin marker; it does not manufacture a Plugin identity or attach a real renderer.
- Root chose the existing RenderableExtension lifecycle seam described above; use it instead of polling all car IDs for destruction. Existing Bukkit slot is empty for directly created synthetic cars.
- Root confirmed the LIVE not-rendered held-scene permissiveness remains a platform distinction, excluded from domain parity; do not silently alter LIVE applyAnimation in this task. SIM still validates captured actual declared animation/scene capability, not unconditional success.
- This is an interface/ownership handoff, not evidence that these proposed classes or helpers have already been implemented or tested.

## Caveats / Not Found

1. **Animation availability is not currently independent of LIVE display state.** `CarExtension.applyAnimation(name, scene)` stores any requested held scene and returns true when no model exists (`CarExtension.kt:468-475`), while no-scene calls fail without actual body/bogie models (`:460-465`). Generated models validate animation and scene recursively (`render/model/AbstractModel.kt:49-91`). A static SIM capability view can reuse true runtime configuration conversion, but cannot honestly claim exact parity with this unrendered LIVE permissive branch. Either scope renderer failures as platform-only or deliberately unify validation in LIVE/SIM; this is a concrete design choice requiring main-session decision, not a reason to make every SIM animation succeed. Coordinated with sibling behavior-runtime research, which owns detailed timeline/capability findings.
2. Current `SimulationProfileCatalog.descriptors` swallows invalid profile exceptions to omit unavailable descriptors; the explicit resolve/capture path throws. This task should not expand into generic catalog defense cleanup.
3. No builds/tests/Git writes/product edits were performed by this research agent. Findings are static review of the named chain, not runtime or Paper/Folia proof.
4. The task has no `research.jsonl`; implement/check manifests are intentionally not read in the isolated research role. Parent dispatch paths and artifact/spec files supplied the scope.
5. Gateway teardown parameter/name and test-only official-signal fixture placement remain composition choices for the main session; neither requires a public arbitrary resource service or a production module move.
