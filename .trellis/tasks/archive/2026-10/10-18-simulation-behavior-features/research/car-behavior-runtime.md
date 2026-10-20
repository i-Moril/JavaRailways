# Research: Shared per-car behavior runtime for isolated simulation

- Query: Reuse actual TrainBehaviorRuntime / CarBehavior / CarOperationBatch semantics in LIVE and SIM, with independent state and minimal effects/time boundaries; identify owner, source capture, lifecycle and real-path acceptance.
- Scope: internal; inspected current production and tests in the existing candidate worktree, no product edits, builds, commits or dependency changes.
- Date: 2026-10-19
- Worktree: `/workspace/minecraft/JavaRailways/.worktree/simulation-signal-features`; existing third-batch signal candidate is inherited and must be preserved.

## Findings

### Recommended boundary (after coordination with root and capture researcher)

Keep the existing behavior implementation and serialized config values in **BukkitPlugin**, and create the isolated adapter in that same module. Module location does not require accessing Bukkit at runtime: `IsolatedTimetableSimulationFactory` already constructs the operation gateway and injects the existing Basic `TrainOperationGateway` interface into SimpleATO Dispatcher (`.../http/IsolatedTimetableSimulationFactory.kt:153,184`). No Core, Basic, SimpleATO or SimpleATOBukkit source currently imports `CarBehavior`, `TrainBehaviorRuntime`, `CarOperationBatch`, `TrainBehaviorScript`, `TrainActionConfig` or `TrainModelOperationResult` (whole-module symbol search performed).

An initial possible Basic extraction was discussed and rejected as unnecessary after inspecting the actual factory. Do **not** move persisted action/script types, their parser helpers, or Graal dependencies to Basic just to make a SIM adapter consume them. There is no requirement for a Bukkit-free JVM classpath; the requirement is no Bukkit effects and no LIVE mutable objects retained. The previous signal batch also keeps platform adapters within its current module.

`CarBehavior` already has the exact useful boundary (`.../behavior/CarBehavior.kt:9`):

```kotlin
script: () -> TrainBehaviorScript?
actions: () -> Map<String, TrainActionConfig>
animate: (String, String?) -> Boolean
sound: (String, Double, Double) -> Unit
schedule: (Double, () -> Unit) -> AutoCloseable
log: (String) -> Unit
```

`Double` delay is **seconds**, while generic tick delta is **milliseconds**. All entry points and delayed callbacks must execute under one exact car's owner/monitor. `CarBehavior` itself does not synchronize and its current class documentation assigns this to the renderer monitor. LIVE continues using CarExtension's existing monitor and region dispatch; SIM owns separate car runtimes and a local logical-time effect outlet, executes no renderer or scheduler platform method, and records accepted effect values. No new Environment, DI container, JS engine abstraction, generic clock framework or compatibility loader is needed.

For shared fixed-car dispatch/acceptance/metadata logic, reuse `CarOperationBatch` directly; any small metadata merge or operating-direction calculation extracted from TrainExtension must also be used by LIVE so SIM does not copy business rules.

Minimal proposed captured value owned by the adapter implementer (capture/factory implementer constructs it):

```kotlin
internal data class CapturedCarBehavior(
    val script: TrainBehaviorScript?,
    val actions: Map<String, TrainActionConfig>,
    val animations: Map<String, Set<String>>
)
```

`animations` keys are actual non-empty runnable animation names; each set contains actual scene index keys, empty means ordinary no-scene playback only. `canAnimate(name, scene)` can use the existing `Animation.normalizeSceneName` rule. A minimal internal immutable scene-name accessor on `Animation` can expose its existing index without copying timeline derivation; capture uses existing `toRuntimeAnimationMap()` and recursive resolved model traversal, copies values, and discards the temporary Animation instances. Alternatively the implementation can supply an equivalently immutable capability view using the exact existing parser. Installed car direction belongs on `SimulationCarBlueprint`, separate from behavior state. Do not carry runtime Animation or model objects into the detached tuple.

Root assigned A to adapter/common helpers/value and B to capture/factory/old duration removal/lifecycle. A should own any new scene-index accessor; B only calls it, avoiding competing writes to Animation. Final class names can be agreed by the implementers before editing.

### Actual per-car semantics, not duration inference

- `TrainBehaviorRuntime.kt:30-53`: one lazy persistent Graal Context per initialized CarBehavior, HostAccess.EXPLICIT, no host class lookup, no threads/process/IO, ECMAScript 2023. Existing behavior runtime uses no process-wide mutable script state. Keep source and values shareable, Context and bindings per car. Initialization failure closes newly created Context.
- `TrainBehaviorRuntime.kt:55-95`: real action declaration query, dynamic `defineAction`, missing member/non-callable failure, optional missing method success, script false return rejection and source-location diagnostics. Do not replace evaluation with action name lookup or hand-coded door logic.
- `CarBehavior.kt:28-84`: door uses actual JS `door(side, scene)` when script exists. YAML-only fallback maps semantic side to physical side once. Explicit false, missing required effects, thrown errors, missing action, or unavailable animation are visible failures.
- `CarBehavior.kt:86-139`: actual action config enumerates **every** animation and sound; captures callbacks at acceptance time; each accepted effect increments pending count. No re-read of current train cars or direction on delayed execution.
- `CarBehavior.kt:142-178`: source equality detects replacements. Script replacement closes old JS and resets globals/declarations but retains already accepted old physical effects. Metadata queries refresh declarations without rebuilding Context; initialization and metadata scanning forbid animation/sound effects.
- `CarBehavior.kt:180-216`: close is idempotent, fails pending operations, cancels registered delays and closes its Context; cleanup continues across independent failures and preserves suppressed errors. Effects complete exactly once only after the collection is sealed and all accepted effects are terminal, or after failure. Successful completion means **latest accepted effect has started**, not animation playback has ended.
- `CarOperationBatch.kt:13-66`: freezes the exact Target list; normal operations include every car, custom action preflights all metadata/permissions before any side effect then selects supporters. Aggregation waits both acceptance result and effects callback so early synchronous callback cannot hide a subsequent rejection. Empty supporter set is failure. `isPending` is checked before dispatch.

Existing metadata visibility is a JS/YAML union with JS declaration winning same-name (`CarExtension.kt:172-176`), and train-level requiresControl OR / executable AND (`TrainExtension.kt:97-109`). These are business rules to reuse, not reinvent. Existing custom action batch checks requiresControl but does not separately enforce executable; do not silently introduce a new LIVE policy as a SIM-only check.

### Exact direction and identity

- `TrainExtension.kt:54-57`: capture on simulation owner, `controlTail xor (CoreCar.isReversed xor train.isReversed)` for each exact car renderer. The formula belongs in a tiny shared helper if SIM needs it; delayed callbacks use the already captured physical side.
- `TrainControlManager.kt:91-94`: manual session contributes its control end; without a player session actual control end is TAIL iff CoreTrain.isReversed. SIM has no manual session and must use that actual fallback, not always HEAD.
- `SimulationControlProfiles.kt:14-28,89-123`: current blueprint does not carry car installation orientation, so existing WORLD_TRAIN capture loses mixed directions. Capture installation direction relative to consist (`car.isReversed xor train.isReversed`) and restore the synthetic car appropriately using the existing Core construction/placement contract.
- `CoreCar.kt:51-61`, `core/api/physics/train/Car.kt:19-26`: `car.train` is exact current owner identity, nullable before attachment/after retirement. Behavior state should be keyed by exact car object rather than train ID/car ID, retained through link/split. Same textual ID reconstruction must not inherit old Context/delays.
- `CoreTrain.kt:677-704`: real reversal publishes TrainSwitchDirectionEvent after structural state/frame publication. `TrainReverseBehaviorListener.kt:12-18` currently skips SIM entirely. A gateway replacement alone therefore misses real `onReverse`. SIM needs a world-owned registration on existing MainRailEventBus (retain close token; filter `event.world === targetWorld`) and must route the event to the same car behavior broadcast with optional onReverse. Do not add a second event bus or make the global LIVE listener access a SIM renderer.
- `models/s100/behavior.js`: formal distributed script contains semantic-to-physical door mapping and onReverse light-refresh actions, suitable for actual integration rather than a hand-written signal/door substitute.

### Effect acceptance and animation capability caveat

The SIM effect sink must not unconditionally return true for every animation name. Actual `AbstractModel.kt:49-90` recursively accepts an animation iff at least one model has that name, and for a scene iff `Animation.hasScene(scene)` succeeds. `PartAnimationRuntime.kt:15-33` converts the current declared commands/keyframes and omits empty animations; map construction resolves duplicate names using existing last-entry semantics. `Animation.kt:73` uses normalized requested scene against real runtime scene index.

Capture an immutable animation-capability view from the exact car's resolved model tree using existing parser/runtime conversion and recursive resolution; do not construct displays or call activate/region methods. Body and bogie trees contribute; attached part definitions and cross-package part resolution matter. Same scene-name normalization and empty-animation rejection should be reused. Sound effect has Unit return in the current car callback and ordinarily succeeds or throws; it needs no fake player/location.

**Existing LIVE visibility caveat:** `CarExtension.kt:468-476` saves any explicit scene request and returns true when the model is not built (semantic held-scene behavior); the no-scene variant at 460-466 returns false without built models. Therefore strict static capability acceptance is not literally identical to every current visibility-dependent renderer state. Root resolved the scope after research: keep LIVE invisible-renderer acceptance unchanged; SIM uses static real-definition capability because actual model visibility/display state is excluded Bukkit behavior. Record and test this comparison boundary; do not silently claim equivalence of excluded renderer availability or use unconditional SIM success. No LIVE renderer acceptance correction is authorized by this research.

### Capture: source ownership and immutability

- `CarExtension.kt:147-164`: each car actions and private script come from its operationalSnapshot. SNAPSHOT uses pinned savedPackageSnapshotHash, unavailable snapshot fails explicitly; FOLLOW_PACKAGE uses resourceType current successfully published version **carPackages[carModelName]**. Capture a read-only behavior/action source tuple there without executing metadata or initializing JS.
- `ModelManager.kt:98-120`: resourceVersion already resolves composition model names and each **source package** independently into immutable `models` and `carPackages`. Use those values for TRAIN_TEMPLATE; `loaded.actions` from the main package is insufficient for cross-package consist cars.
- `ModelManager.kt:163-184`: specified package queries never fall back to another package. Null-package legacy query is not a fallback for known missing sources.
- `PackageLoader.kt:134-162`: actions.yml is optional empty map, behavior.js optional null; actual parser/source text loaded at package boundary. Detached capture must contain source text and immutable action/effect declarations, not ModelManager, Plugin, live CarExtension, source World, Context, deferred supplier into live state or filesystem query.
- `SavedTrainPackageSnapshot.kt`, `TrainPackageSnapshotTest.kt`: behavior/actions already persist as existing serializable values and affect snapshot hash. This task requires no format or version change and no runtime compatibility path.

### Gateway, cancellation, timing and close

Current `SimulationTrainOperationGateway.kt:17-79` is a duration-only success substitute. `IsolatedTimetableSimulationFactory.kt:361-389` computes maximum YAML delay, ignores JS, supporter choice, failure and direction, and even returns zero ticks for unsupported cab lights/announcement. `SimulationControlProfiles.kt:38,151-155` retains only duration summary, not real car behavior. Replace this factory path; remove the duration resolver/gateway once all consumers have moved. Whole-source search found no production consumer beyond this factory. Existing two SimpleATO duration tests should be replaced by real behavior integration evidence, not preserved as an alternate production mode.

LIVE `BukkitTrainOperationGateway.kt:49-64` supports OpenDoors, CloseDoors and ExecuteModelAction; unsupported SetCabLights/Announce explicitly reject. SIM must match supported behavior and not fabricate immediate success. Reuse existing gateway/ticket terminal transition mechanics where practical, retaining real target execution and no region side effects in SIM.

Ticket cancellation is terminal PENDING -> CANCELLED only; accepted physical effects may continue, late result never changes cancelled state (`.../BukkitTrainOperationGateway.kt:67-79`). Cancellation before dispatch prevents unaccepted target execution. It is not a rollback protocol for effects already applied to other cars.

`WorldModelOperations` lives in `BukkitWorldData.kt:22-43`, not a standalone file; it tracks aggregate futures, fails unresolved/queued work during unload, rejects late completion/new work. This helper itself is platform-neutral and can be reused from the same module in SIM with world-correct diagnostics if needed. Car close and aggregate-future retirement are separate necessary boundaries.

`CarExtension.kt:215-233` supplies existing generic-tick logical seconds + Synchronizable commit outlet, retaining CarExtension monitor and PlatformCommitAffinity. SIM can use local TickingObject plus Synchronizable for the same delay accounting while committing directly on SIM owner, without PlatformCommitAffinity. Do not run JS/effect closures on generic worker `tick` threads.

`IsolatedTimetableSimulation.kt:202-215`: preTick schedules generic workers, then physics step/await, then world owner afterPublication and drainPendingCommits. `OperationalWorldRuntime.afterPublication` performs TickManager.postTick; ensure newly registered effects obey this existing registration/next-tick ordering, rather than inventing a second clock whose first tick differs. A deterministic logical-time outlet can also be advanced at this exact owner stage if no new shared scheduling framework is created.

`IsolatedTimetableSimulation.kt:223-234,324-331,485-505`: worker true termination precedes domain/feature close; failed construction cleans owned resources. Ensure the new per-world behavior owner is included before any operation can run and closes in both success and failed seed/install paths; event registration/tickets/delays/Contexts must all retire. A raw local variable captured by Dispatcher is not sufficient lifecycle ownership.

## Files found

Paths below are relative to the worktree; package prefixes abbreviate only in the preceding narrative.

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainBehaviorRuntime.kt` — existing Graal runtime, persistent JS context, declarations/bindings/errors.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/CarBehavior.kt` — per-car actual logic, effects completion and source replacement.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/CarOperationBatch.kt` — fixed targets, preflight and actual acceptance/effects aggregation.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/behavior/TrainReverseBehaviorListener.kt` — real reverse callback currently skipped in SIM.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/CarExtension.kt` — exact source, monitor, location effects, delays, lifecycle and held-scene exception.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainExtension.kt` — current owner direction capture and operation batch facade.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/train/TrainComponentExtension.kt` — model audience/build/retirement platform boundary.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/extension/BukkitWorldData.kt` — standalone-neutral WorldModelOperations class embedded in this file.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/operation/BukkitTrainOperationGateway.kt` — public gateway adapter plus region dispatcher, ticket atomic transitions.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/SimulationControlProfiles.kt` — old blueprint/profile source and duration-only capture.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt` — existing same-module composition seam and only duration production consumer.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/TrainActionConfig.kt` — serialized actions plus current parser and effect declarations.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/TrainBehaviorScript.kt` — immutable serialized JS source value.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/ModelManager.kt` — exact cross-package source and immutable resource versions.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/TrainResourceVersion.kt` — prepared models/carPackages/composition tuple.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/SavedCarModelTree.kt` — snapshot model graph/part resolver usable at capture.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PackageLoader.kt` — real actions.yml and behavior.js loading.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/config/model/PartAnimationRuntime.kt` — declared-animation conversion and empty/duplicate resolution.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/model/AbstractModel.kt` — existing model animation/scene acceptance and descendant traversal.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/Animation.kt` — scene index and timing semantics, activation remains platform.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainControlManager.kt` — actual session/no-session operating end.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/SimulationTrainOperationGateway.kt` — removable duration-only execution bypass.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt` — actual owner/tick/close lifecycle seam.
- `Core/src/main/java/work/art1st/javarailways/core/util/TickManagerImpl.kt` — generic worker scheduling, deferred registration and owner postTick barriers.
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt` — real reverse/link/split and event publication.
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt` — exact train ownership and car direction.
- `models/s100/behavior.js` — current official per-car train script, no runtime substitute required.

## Existing tests reusable and new acceptance

Reuse current tests unchanged where semantics are preserved; add real factory/owner integration rather than copies of implementation:

- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/behavior/TrainBehaviorRuntimeTest.kt`: 4 current methods cover real JS door, custom sound/log, declared action, missing method.
- `.../behavior/CarBehaviorTest.kt`: 8 current cases cover independent persistent globals, source reload preserving old accepted effects, dynamic declarations, direction and multiple delayed effects, missing/delayed failure, close/late tasks, scriptless metadata, forbidden initialization/metadata effects.
- `.../behavior/CarOperationBatchTest.kt`: 5 cases cover supporter union, permission preflight, metadata/no-support/cancel, whole-train failure, completion-before-acceptance race.
- `.../operation/BukkitTrainOperationGatewayTest.kt`: 3 cases cover completion/cancel, failed future, sync completion and dispatch exception; should continue protecting LIVE while adapter wiring is shared.
- `.../extension/WorldModelOperationsTest.kt`: unload fails unresolved queued work and does not revive after late callback.
- `.../extension/train/CarExtensionHeldSceneTest.kt`: current held-scene semantic state without spawned models, relevant to acceptance caveat.
- `.../config/model/TrainPackageSnapshotTest.kt`: exact behavior/actions snapshot hash and tamper rejection; no serialization shape changes needed.
- `.../config/model/ModelManagerReloadTest.kt` and `.../logic/TrainResourceConfigurationTest.kt`: source/version invariants, relevant to detached capture.
- `.../http/IsolatedTimetableSimulationFactoryTest.kt`: current real Core seed/runtime fixture, 6 cases including feature prepare failure and snapshot reuse; expand here for actual script path where practical.
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt`: lifecycle and generic tick integration; use actual operation gateway seam without exposing Bukkit values downward.

Required additions:

1. Same formal script and action values through LIVE/shared behavior and factory SIM; two cars with opposite installed orientation and reversal. Assert selected physical effects and completion ticks rather than just distinct instances.
2. Two SIM installs from one captured value: mutable JS counter state, tickets, delay queues, effects and close independent; source World/cars may be retired before installation without touching live services.
3. Real two-delay animation+sound action remains PENDING until **all** effects start; explicit false, missing action, unavailable animation and source syntax/runtime failure produce actual FAILED/rejected results. Unsupported cab/announce matches LIVE rejection.
4. Cancel before car dispatch does no unaccepted effects; cancel after acceptance retains CANCELLED despite later effect completion; world close fails unresolved aggregate and per-car operations, no late effects or Context resurrection.
5. Real Core.reverse event reaches optional onReverse in SIM, own-World filter prevents another SIM/LIVE execution; existing LIVE global listener must not fetch SIM renderer.
6. Real link/split while delay pending: same exact car runtime/JS counters survives, original target/effect/physical side fixed, new car excluded; car destroy and same-ID reconstruction cannot inherit delayed work.
7. Run real formal SimpleSignals plus timetable fixture with door/custom-action completion gate, collision/event chain and concurrent Worlds; record missing field/Folia visual acceptance as unexecuted rather than claiming it from JVM tests.

## Related specs

Read workflow, task/parent PRD/design, `.trellis/spec/bukkit-plugin/backend/index.md`, complete `car-behavior.md`, relevant full lifecycle/threading/World lifecycle sections, `.trellis/spec/javarailways/basic/train-operations.md`, shared versioning and `ai_tasks/persistence_schema_evolution_rules.md`.

Current Basic operation spec describes duration-only SIM; it must be updated alongside its production-path removal. Car behavior spec explicitly defines exact car instance state, once-captured direction, full accepted-effects aggregation, cancellation and world retirement; none may be weakened by adapter extraction. Parent design permits local adapter ownership and does not require moving module packages.

## External references (docs, versions)

No external reference was necessary: all assertions above are grounded in checked-in current code/specs. Build dependency versions: current BukkitPlugin embeds `org.graalvm.polyglot:polyglot:24.2.2` and `js-community:24.2.2` (`BukkitPlugin/build.gradle:103-104`). No dependency upgrade proposed or internet claims made.

## Caveats / Not Found

- This is source research, not an implementation or passing-test claim. No build/test/runtime/client action was run.
- Serialized TrainBehaviorScript/TrainActionConfig should remain unchanged; new detached runtime capture values need no schema version. No obsolete-data reader justified.
- Full extension provider completeness is the parallel capture/extension researcher scope; this report only traces TrainExtension/CarExtension behavior and operation consumer boundaries.
- Animation acceptance has an actual preexisting renderer-visibility exception described above; root chose static definition capability for SIM and unchanged LIVE visibility semantics, explicitly excluded from display-state equivalence. No performance or Folia/client result is inferred.
- The native hook initially named the previous signal task, but explicit dispatch selected this behavior task; only this task research output is written.
