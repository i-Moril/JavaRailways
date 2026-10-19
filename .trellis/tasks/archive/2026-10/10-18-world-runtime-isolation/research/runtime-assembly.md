# Research: World-local common runtime assembly

- Query: Compare live and isolated simulation composition, identify a minimal common factory/registration seam and safe verification boundary for batch 2.
- Scope: internal; source-only at baseline `8f156900`, worktree `.worktree/world-runtime-isolation`.
- Date: 2026-10-19

## Findings

### Current owners and exact assembly gaps

1. `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/logic/RailWorldFactory.kt:89-102` creates dedicated TickManager/PhysicsCoordinator/CoreWorld; `:114-156` constructs Basic data, interlock, restriction store, inspection and SignalEpochRuntime; `:181-235` constructs Dispatcher and attaches SimpleATOWorldData. Platform effects/config are genuinely platform-owned: renderer profile resolution, BukkitTrainOperationGateway, diagnostics and Koin parent. Do not move Bukkit accesses into SimpleATO.
2. `BukkitPlugin/.../logic/WorldManager.kt:176-233` completes composition before `physicsCoordinator.start()` at `:241`: topology invalidation subscription, InfrastructureCoordinationRegistry registration, real RouteResourceAdmissionRuntime, admission updates to SignalEpochRuntime (`:209`), signal source service registration, and SpeedSafetyMonitor registration. This order matters; restored Dispatcher runtime is applied after admission attach at `:211`.
3. `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt:245-318` already owns dedicated pools and SIMULATION CoreWorld, new Basic and SimpleATO data, restrictions/inspection/SignalEpochRuntime, real Basic registry/admission, then restores facilities and seeds synthetic trains. Runtime state is not cloned. However it repeats the common domain wiring manually.
4. The simulation omits **OperationalSpeedLimitSignalMaterializer**. LIVE calls it in `RailWorldFactory.kt:240`; `Basic/.../persistence/BasicInfrastructureSnapshot.kt:111-113` restores metadata/directions only. `Basic/.../rail/OperationalSpeedLimitSignalMaterializer.kt:13-21` creates both AB and BA ephemeral transitions, including no-limit release transitions. The simulation must call this real implementation after static restore; creating only metadata is insufficient.
5. Simulation omits **SpeedSafetyMonitor** registration and **owner consumption of derail requests**. LIVE creates it in `WorldManager.kt:223-233`, then `:523-534` publishes physics-origin derail notifications and drains speed requests through the exact completed epoch. Simulation `step():193-209` has neither action. `Basic/.../safety/SpeedSafetyMonitor.kt:45-98` already implements grace, swept-span minimum, offsets, SUBGRADE cap and dedup; reuse it, with captured config. Its mutable train states/queue belong to one World instance.
6. Simulation **does** use Basic's real physical resource admission (`IsolatedTimetableSimulation.kt:382-393`) and observation-driven release (`:396-399`), but hardcodes no contributions/no preparation and omits `publishAdmissionUpdate`. LIVE passes it at `DefaultInfrastructureResourceService.kt:223-241`. `SignalEpochRuntime.kt:85-100` stores owner updates and routes changes/baseline on the next physics epoch; simulation must connect the same callback. An empty catalog is valid only when preparation proves there are no required installed domain contributors.
7. `SimpleATO/.../SimpleATOWorldData.kt:20-45` is already the one mutable operational container per CoreWorld. It binds a unique Dispatcher, coordinator, inspection/restriction/signal runtime, destination/station/depot/line/timetable stores and extensions. It verifies shared Basic RailObjectStore and safety interlock. Do not add a parallel Environment owner or put ATO owners into BasicWorldData.

### Current stage order and reusable common phase

LIVE `WorldManager.kt:470-477`: wake physics, generic preTick. LIVE `:489-566`: processBetweenFrames obtains exact successful ticket, drains finite operation batch, resolves UI issues, publishes observation and consumes admission, publishes pending physics derail notifications/consumes epoch-tagged speed requests, schedules render frame consumption, ticks Dispatcher, generic postTick. Platform commits are drained outside the boundary at `:568+` and dispatched to real platform affinity.

SIM `IsolatedTimetableSimulation.kt:193-208`: generic preTick, explicit 50 ms step+await, processBetweenFrames/drain operations, construct RailwayObservation from current frame, admission consume, Dispatcher, postTick; pending commits run locally. Startup `:131-135` correctly runs beginTimetable/scheduleInitialCycle in the same frame boundary.

Minimal common phase should preserve current ordering rather than introduce a second loop:

```
exact successful ticket + existing between-frames admission
  drainBetweenFramesOperations once
  platform pre-domain bookkeeping (LIVE issue cleanup, if retained here)
  publish coherent observation -> real admission.consumeObservation
  publishPendingDerailEvents
  monitor.drainRequests(ticket.epoch)
    apply only if owning train publishedFrame epoch == request epoch
  platform frame-consumption hook (LIVE only)
  same Dispatcher.tick
  generic postTick
release boundary
platform commit dispatch (LIVE affinity / SIM local logical effects)
```

`Core/.../physics/CoreWorld.kt:294-298` publishes queued physics derail events; `CoreTrain.kt:1272-1290` is the existing owner-side derail implementation and updates publication before event notification. Never execute a copied derail algorithm on workers. No safety/admission/Dispatcher work may proceed after an exceptional ticket. Hook naming/location is implementation choice; common domain ordering must be expressed once and used by both production and simulation. Do not require common *host* timing: LIVE wall clock + fallback and SIM deterministic 50 ms remain distinct.

### Minimal common assembly design

Use a concrete SimpleATO runtime/composition helper (implementation package, not new generic public DI API) to own the known domain chain: restrictions -> inspection -> SignalEpochRuntime -> Basic data using its direction callback -> AtoWorldCoordinator using the same interlock -> Dispatcher -> SimpleATOWorldData, topology subscription, speed monitor and paired cleanup. Existing factory-specific inputs are enough: worldId for existing value stamps, target CoreWorld, Basic-data builder, Dispatcher builder, captured speed safety config, diagnostics callback and observation/admission endpoint factory.

The helper should return the actual known objects and its close registrations. It must not capture live Bukkit world/service callbacks in a simulation prepared input. Existing plugins remain slot identities; state belongs to target CoreWorld. Platform adapters retain renderer/profile/operation gateway resolution, real authenticated service wrappers and rendering.

Install order: create Core -> attach common Basic/ATO domain data -> restore Basic/SimpleATO static snapshots -> materialize speed transitions -> attach local registry/admission and topology/source hooks -> install required prepared domain features against target World -> seed synthetic trains -> start physics. LIVE runtime checkpoint restore must remain after admission attach. Exact assembly split can accommodate live deferred service registration without null/fallback algorithms.

`InfrastructureCoordinationRegistry` and `RouteResourceAdmissionRuntime.create` are already platform-independent. The latter's existing explicit parameters (`Basic/.../infrastructure/RouteResourceAdmissionRuntime.kt:173-205`) cover contribution collection/validation, resource preparation, stall and admission updates. Prefer exposing these concrete endpoints through the World runtime to prepared feature installation instead of another service framework. LIVE authenticated wrappers can keep their service API. In SIM use new registry/requester/runtimes and local providers/handlers; do not reuse Plugin-backed live ProviderRegistration callbacks.

### Capture/target-install seam and required Feature coverage

A small concrete registry of domain feature preparations in the host composition is sufficient. An installed domain participant has a stable namespaced ID and can capture detached preparation input inside the source World's frame boundary. That prepared input installs into a **target CoreWorld plus the concrete local domain endpoints**, creates fresh mutable feature objects, and returns AutoCloseable registration(s). It can reference immutable source text/definitions/profile values; it cannot retain the source World, mutable provider, live service, reservation or JS context.

Target endpoints needed by current components are not speculative: SignalEpochRuntime source installation, InspectionRuntime source installation, RoutePlanningRestrictionRuntime source installation, Basic local resource existence/contribution/preparation registration, and the admission-update handoff. They are already consumed by live SimpleSignals through separate public adapters. `SimpleSignals/.../SimpleSignalsPlugin.kt:65-76` registers fixed-block provider/contributor; `runtime/SimpleSignalsWorldRuntime.kt:1421-1453` installs section/signal sources, route-planning binding, and both fixed-block/protection-region inspection sources. Capturing only SignalEpochSource would silently omit required behavior. Batch 3 should provide the actual SimpleSignals preparation and install all these endpoints together, compiled for the target root, with fresh SignalLogicRuntime.

Required feature discovery should come from actual domain registrations (source/resource/provider/contributor ownership and explicit platform/domain classification), not all installed Bukkit plugins or a second hardcoded Core/ATO/Signals list. At capture, compare installed domain participant IDs against usable detached preparers. Missing required participant must reject preparation with its stable ID before worker startup; do not `emptyList()` it into support. Until batch 3 installs SimpleSignals, a source World containing those required registrations should give an explicit unsupported-preparation result. Plain Core/Basic/ATO fixtures with no extra domain registrations remain supported. Do not install dummy safety sources merely to pass preparation.

Capture needs a **source-specific `processBetweenFrames/withStructuralMutation` boundary**. HTTP currently calls `factory.capture` under generic `await/withWorld` (`TimetableHttpBridge.kt:481-489`), and `withWorld` only looks up map entry (`:834-837`); this alone is not a stable source snapshot. Global ownership historically serialized host code, but fallback physics remains separate. Batch 2 must queue/execute capture in the source's frame boundary and complete the detached capture before worker submission. Profile/feature callbacks that need platform access must obey existing capture ownership; worker reads only prepared values.

Existing `TimetableExtensionRegistry.createSimulationRegistry` is not sufficient future support: `TimetableExtension.kt:238-246` filters providers without simulation factories, silently omitting them. The factories also take no target World (`:168-189`). Required feature coverage at preparation must reject any required omitted extension; do not clone live provider. Batch 4 may evolve this actual provider creation to target-World creation rather than generic cloning.

There is a current isolation trap: Bukkit snapshot stores a **created mutable TimetableExtensionRegistry** (`IsolatedTimetableSimulationFactory.kt:52,129`) and `create(snapshot)` passes that very instance into each isolated target (`:154`). Reusing one capture twice shares provider instances. Detached snapshot should retain preparation/factory input, then create a fresh registry/provider instance **per target creation**, not per capture. Tests must cover two simultaneous creates from the same snapshot. Avoid treating a closure calling the live provider later as detached capture.

### Batch boundaries

Batch 2 owns truthful common Core/Basic/ATO assembly, target-local endpoints/registration cleanup, preparation-time completeness checks and execution isolation. It does not implement SimpleSignals logical compilation or car behavior JS. It must expose a usable installer seam and demonstrate it using existing real source/inspection/admission primitives, not fake a full signal plugin. Full installed Signal feature comparison is batch 3. Domain onReverse/car operations and removing duration-only simulated success are batch 4. Current SimulationTrainOperationGateway (`.../simulation/SimulationTrainOperationGateway.kt:28-50`) estimates operation success using durations; do not claim that path is full behavior fidelity at batch 2 completion.

Do not change source clone semantics: Bukkit capture canonicalizes active continuations (`IsolatedTimetableSimulationFactory.kt:110-115`), captures static Core/Basic/ATO infrastructure, and seeds only synthetic timetable trains (`:202-275`). Preserve named train reuse and selector/profile validation. Fresh simulation reservations/Dispatcher/store identities remain required even when source and both targets all have identical IDs and literal worldId `isolated`.

### Minimal necessary file change candidates

- SimpleATO new implementation runtime assembly/phase helper: concrete known domain owners and reusable after-publication order; no Core API promotion unless actual host executor needs it.
- `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt`: consume common assembly/phase, speed/derail/admission wiring, target-local feature install/cleanup; replace private empty-only host with extensible concrete local endpoints.
- `BukkitPlugin/.../logic/RailWorldFactory.kt`: use common domain assembly; leave platform profile/operation/effect bindings local.
- `BukkitPlugin/.../logic/WorldManager.kt`: use same domain phase and paired close, retaining live observation/render/issue adapters. World scheduling owner changes are researched separately.
- `BukkitPlugin/.../api/internal/DefaultInfrastructureResourceService.kt`: expose actual installed domain provider/contributor facts or concrete runtime endpoints only as needed; keep authenticated external API semantics.
- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt`: detached safety config + prepared features/extension inputs, per-target install, unchanged static profile/seed semantics.
- `BukkitPlugin/.../http/TimetableHttpBridge.kt`: capture within source boundary, then submit worker with detached values; no all-simulation shared context needed.
- Actual source registration service classes may need required participant discovery/registration facts. Modify only when implementing that seam; do not rewrite SimpleSignals runtime in this batch.
- Tests and owner specs listed below. Basic monitor/materializer/engine algorithms need no duplicated implementation or runtime format change.

### Reusable real fixtures and necessary assertions

- `SimpleATO/.../simulation/IsolatedTimetableSimulationTest.kt:157-225`: real Core straight rail + Basic snapshots + real Basic admission + SimpleATO Dispatcher/controller; expand to exact stage ordering, two concurrent targets from one snapshot, source/target store identity and reservation isolation, with deterministic barriers and finally close. Existing `same static inputs ... parallel physics` test (`:61-73`) runs simulations **sequentially**, so is not evidence of simultaneous isolation.
- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactoryTest.kt:57-85,89-159,164-190`: real profile-based synthetic trains, line cap and shared selector cases. Reuse for speed materialization from captured metadata, copied overspeed config, source mutations after capture not affecting target, and fresh extension runtime per target. Preserve synthetic-only/named reuse assertions.
- `Basic/.../safety/SpeedSafetyMonitorTest.kt`: real monitor grace/swept thresholds/stale request behavior. New integration must show a real overspeed request applies via owner phase, current epoch only; disabling config does not derail; no duplicate notification; same train ID in another World untouched.
- `Basic/.../rail/OperationalSpeedLimitSignalTest.kt`: both directions, no-limit release and idempotence. New simulation fixture should assert these exist and affect real ATO/actuation; no copied speed formula.
- `Basic/.../infrastructure/RouteResourceAdmissionEngineTest.kt` and `BukkitPlugin/.../api/internal/DefaultInfrastructureResourceServiceTest.kt`: real grant/prepare/tail-release assertions. New target installer test should route an actual admission update into target SignalEpochRuntime and show its target source sees it while source/other target does not.
- `SimpleATO/.../signal/SignalEpochRuntimeTest.kt`: real source callback and owner admission update routing; reuse source fixture to check exact-root installation and local cleanup. `IsolatedTimetableSimulationTest.kt:76-105` already proves a real replaceable SignalEpochSource executes before simulation completion; this proves an endpoint, not full SimpleSignals feature support.
- `BukkitPlugin/.../logic/WorldConstructionResourcesTest.kt` and `WorldManagerUnloadTest.kt`: failure cleanup pattern. Add partial prepared install failure closes earlier registrations in reverse order and closes both pools, preserves original failure with cleanup suppressed, and does not unregister another target/source. Existing SIM `close():216-220` and creation catch `:326-330` can skip later cleanup if the first close throws; actual new close resources need independently attempted cleanup.
- Required unsupported domain participant: capture rejects by ID before target/pool creation; no silent omission or placeholder success. Target installation failure also releases captured/created ownership.
- Real failed/stalled physical ticket: no observation/admission/Dispatcher/postTick continuation; close terminal and unresolved request futures remain deterministic. No sleeps as concurrency proof.
- Source capture barrier: while source epoch is paused, capture not yet complete; unrelated World and independent prepared simulation progress; after source epoch completes, captured Basic/ATO/Core revisions match. Global lock proof is another research topic.

## Related Specs

Read `.trellis/workflow.md`; Core `index.md`, `runtime-publication.md`, `di-and-runtime-lifecycle.md`; Basic `index.md`, `world-runtime.md`, `speed-and-derail-safety.md`; SimpleATO `index.md`, `simulation.md`, `dispatcher-runtime.md`; Bukkit backend `index.md`. Authoritative parent requirements/design are under `10-18-isolated-simulation-runtime`, child artifacts under this task.

Existing simulation spec's provider-opt-in and duration-only wording describe historical behavior; parent accepted design explicitly replaces silent domain omission/full-behavior shortcut in subsequent batches. Document present limitations without treating them as the desired final contract. No persistence schema/version changes required for runtime assembly/config capture.

## External References

None required: findings and proposed seams are based on current repository code. No external API/version assumptions used.

## Caveats / Not Found

- Source-only research: no builds/tests/server/Folia runtime were executed here. Counts and live deployment claims are not inferred from source.
- All cited paths are repository-relative; `...` abbreviates the unchanged package prefix named in the same paragraph.
- No global lock call audit performed; parallel researcher owns it.
- No implementation/jsonl or check/jsonl loaded because research role context is isolated, despite generic dispatch wording.
- Shared CPU/GC/JVM failure remains outside World execution isolation.
- Prior memory was used only to retain exact ticket/publication and partial-coverage discipline; current behavior claims above were checked against source.
