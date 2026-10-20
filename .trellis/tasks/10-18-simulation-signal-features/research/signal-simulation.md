# Research: full SimpleSignals simulation installation

- Query: Reuse the actual SimpleSignals domain in isolated simulation, identify neutral capture values, required public installation endpoints, minimal file ownership and real business fixtures.
- Scope: internal, read-only product inspection at `9ed27818`, worktree `.worktree/simulation-signal-features`; S1/S3 manager lock refactoring delegated separately.
- Date: 2026-10-19

## Findings

### Feasibility and current missing link

Feasible without another environment or alternate signal implementation. The previous batch already supplies real Core/Basic/ATO epoch/admission lifecycles, but does **not** currently install SimpleSignals in simulations:

- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt:120-130` captures detached features and rejects missing required feature IDs.
- `BukkitPlugin/.../http/TimetableHttpBridge.kt:461` passes only `requiredDomainFeatures`; `prepareDomainFeatures` remains the factory default empty function. There is no public preparer registration service. Installed SimpleSignals therefore prevents simulation capture by design, rather than silently running without signals.
- `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt:280-308` restores infrastructure, directions and static speed materialization, creates an actual target owner/domain, installs features **before train seed and physics start**. Its existing target/install types reside in implementation packages and expose `CoreWorld`, `OperationalWorldRuntime`, `SimulationRouteAdmissionHost`; they cannot become the add-on's API as currently shaped.
- `.trellis/spec/simple-signals/backend/index.md:464-465` permits only Core/Basic/SimpleATO/Bukkit `.api` imports in the add-on. Keep that rule. Do not import existing simulation implementation classes from SimpleSignals or loosen source-boundary checks.

### Proposed minimal public contract for root/implementer alignment

Consolidate the existing feature seam into `simpleato.api.simulation`, with a narrow target interface implemented by a host-local adapter. These are recommended signatures, to finalize consistently before parallel implementation; they are **not existing source**:

```kotlin
// simpleato.api.simulation; only current public value/interface types in signatures
interface PreparedSimulationFeature {
    val id: String
    fun install(target: SimulationDomainTarget): AutoCloseable
}
interface SimulationDomainTarget {
    val worldId: UUID                 // actual target runtime UUID, never the captured LIVE UUID
    val topology: TopologyState       // exact target immutable root
    fun operatingDirections(topology: TopologyState, railIds: Collection<UUID>): RailOperatingDirections
    fun railSpeedLimits(topology: TopologyState, railIds: Collection<UUID>): Map<UUID, Double?>
    fun registerSignalSources(sources: Collection<SignalEpochSource>): AutoCloseable
    fun registerInspectionSources(sources: Collection<InspectionSource>): AutoCloseable
    fun registerRoutePlanningSources(sources: Collection<RoutePlanningRestrictionBinding>): AutoCloseable
    fun registerResourceProvider(typeId: String, provider: SimulationResourceProvider): AutoCloseable
    fun registerRouteContributor(id: String, collect: (RouteResourceIntent) -> List<ContributedRouteResource>): AutoCloseable
}
// Move/retain the existing three-method SimulationResourceProvider shape in the public API:
interface SimulationResourceProvider {
    fun exists(resource: StableInfrastructureResource): Boolean
    fun validate(contributorId: String, contribution: ContributedRouteResource): RouteAdmissionFailure?
    fun prepare(resource: StableInfrastructureResource, descriptor: ResourcePreparationDescriptor):
        (() -> ResourcePreparationTicket)?
}
```

The target offers **registration**, not caller access to a global runtime or complete-source replacement. Internally collect the registered public source values by stable source ID and call the existing `replaceSources` implementations, preserving other features; close removes only that registration. Install/close are already within the target owner, before physics starts/after true termination. No per-tick registration lock or generic environment framework is necessary. Reject duplicate IDs exactly as the existing runtimes do. The returned feature close token owns these registrations and its own JS execution instance; no Bukkit Plugin object is required at install time.

`SimulationResourceProvider` currently exists at `IsolatedTimetableSimulation.kt:483-493`; its signature already uses public Basic values. Move it into `.api` rather than creating a second competing provider API. Internal `SimulationRouteAdmissionHost` remains the actual adapter/claim implementation.

At the Bukkit boundary add authenticated preparation registration only:

```java
// bukkit.api.simulation (exact class names up to root, one coherent contract)
interface SimulationFeaturePreparationService {
    AutoCloseable register(Plugin owner, SimulationFeaturePreparer preparer);
}
interface SimulationFeaturePreparer {
    PreparedSimulationFeature prepare(UUID liveWorldId);
}
```

The host tags the registration/required feature ID with the same `owner.name.lowercase()` identity used today. The preparer may return an explicitly detached feature for the captured world or throw with a precise unavailable diagnostic. Host capture invokes registered preparers at the existing target LIVE owner boundary, **outside a registry-wide lock**; only lookup/copy and registration use a short directory monitor. Snapshotting registrations must not itself cause cross-World compile/evaluation under the host directory lock. Plugin-disable removal uses the existing owner cleanup path. Do not expose a service query for mutable CoreWorld or install through LIVE Bukkit source services in SIM.

Important ownership detail: captured feature preparation should only capture neutral definitions/config/rule values. It must not retain the manager, Plugin, LIVE services, runtime, publication, model handles, or LIVE-bound diagnostic callbacks. Error callbacks for SIM should append/report diagnostics with the target UUID via an explicitly neutral sink if one is required; otherwise propagate real preparation errors and keep runtime rule failures in the existing unavailable state with target identity. Do not invent a global diagnostic environment.

### Existing target internal endpoints are sufficient

- `IsolatedTimetableSimulation.kt:343-357`: real simulation provider and contributor registration exist.
- `IsolatedTimetableSimulation.kt:394-421`: actual `RouteResourceAdmissionRuntime.create` collects these contributors, validates providers, resolves preparation and forwards `publishAdmissionUpdate = domain.signalEpochRuntime::publishAdmissionUpdate`.
- `IsolatedTimetableSimulation.kt:426-429`: publication first stores target observation then calls real `consumeObservation`, preserving Basic claim/tail release.
- `simpleato/runtime/OperationalWorldRuntime.kt:96-121`: same inspection, restrictions, signal epoch runtime, directions-change notifications and physics epoch processor as LIVE.
- `simpleato/inspection/InspectionRuntime.kt:234`: public-class implementation already replaces inspection sources.
- `simpleato/routing/RoutePlanningRestrictionRuntime.kt:20`: existing target-local replacement validates stable source IDs and combines them with operating directions.
- `simpleato/signal/SignalEpochRuntime.kt:112-147`: existing replacement and exact epoch processing; no new epoch loop needed.

Host adapter may read its target Basic direction store/static materialized speed values directly because it owns implementation-side access. It returns only the same public coherent subset values that the LIVE infrastructure service returns. Use actual current target root checking and existing helper algorithms; no copied static-speed formula or mutable LIVE supplier. Infrastructure restoration already preserves authored rail IDs, but target topology and definitions must be newly compiled together.

### Exact reusable SimpleSignals compilation/install chain

1. At LIVE capture take `runtime.currentDefinitions()` (immutable current collections, definition revision) and immutable neutral rule catalog. `SimpleSignalsWorldDefinitions` is the current in-memory model (`.../persistence/SimpleSignalsWorldDefinitions.kt:35-84`), not a need for encode/decode or a schema change. Capture failure if no usable installed runtime/catalog; follow startup/error semantics, never treat unavailable data as empty.
2. At target install construct a **new** `SimpleSignalsWorldRuntime(target.worldId, FixedBlockDirectionPolicy(target::operatingDirections), captured protectionRegionMaxDistanceMeters)`.
3. Recreate the current definition value with target UUID and captured authored lists, preserving all revisions/IDs/placements/pending drafts/boundary names; call the exact same `initializeDefinitions(target.topology, copiedDefinitions)` / `prepareDefinitions` pipeline. No JSON roundtrip or shared compiled publication.
4. The existing candidate pipeline (`SimpleSignalsWorldRuntime.kt:530-707`) handles protection interiors, route tables, exact route selection index, fixed blocks, signal devices/indicators, observation graph, separation checks and topology index. It already fails invalid configurations and represents authored unavailable regions/routes restrictively.
5. Allocate one target-owned `SignalLogicRuntime` from captured immutable rule sources. Compile `SignalPackEpochRuntime.compile` with target publication, actual catalog types/bindings and target static speed supplier (`.../signalpack/SignalPackEpochRuntime.kt:292-401`). Do not replace with `unavailable` for valid rules or use empty bindings to pass simulation acceptance.
6. `SectionOccupancyRuntime.from(publication, rules)` (`.../runtime/SectionOccupancyRuntime.kt:448-516`) installs all fixed-block areas **and every usable protection-route geometry**, even routes without their own visible device. It creates the real `ProtectionRouteSelectionRuntime`; do not install a simplified occupancy adapter.
7. Commit/attach occupancy through `runtime.replaceSectionOccupancy` so all public source/inspection reads use the same target state. Register `occupancy.sources`, `runtime.routePlanningBinding`, `runtime.fixedBlockInspectionSource` and `runtime.protectionRegionInspectionSource` with target endpoints. The real LIVE installation at `SimpleSignalsWorldRuntime.kt:1419-1454` shows the complete source set.
8. Register the same neutral fixed-block route resource policy core (below), both provider and contributor, under existing `simplesignals:fixed-block`. No model bindings, topology authoring worker, filesystem store, Bukkit subscriptions or renderer are installed in SIM. The target is an immutable infrastructure scenario; existing target domain still handles its actual physics/turnout/admission updates.
9. Close feature source/provider/contributor tokens and target-owned JS after the existing simulation's true physics/generic callback termination. Partial installation closes acquired tokens in reverse, retaining first error and suppressed cleanup failures per existing batch-2 helper semantics. Each install creates fresh runtime/logic; a captured feature may install into multiple simulations independently.

### Neutral rule-catalog extraction: small but useful

`SignalPackCatalog.kt` has immutable maps, and `SignalPackLogic.source` already captures JS text; no files need reading after capture. However `SignalPackSignal.model` retains `SignalPackModel.materialized: CompiledModelAsset`, `LoadedPackageModel` and path/catalog objects. `SignalPackEpochRuntime.kt:116` only needs the **set of declared variant strings**, while compile needs type/logic metadata. A detached capture should not retain model assets just to validate strings.

Recommended one add-on-local immutable value: a prepared **rule catalog** with copied `SignalPackLogic` scalar/source fields and signal type -> logic ID + declared variant names. Derive it once from the fully validated SignalPackCatalog. `SignalLogicRuntime` and `SignalPackEpochRuntime` consume that same rule view for LIVE/SIM; presentation keeps existing full catalog. This is not a second loader or JS implementation and does not alter config/schema. Avoid introducing new Engine/Context sharing; each World keeps its own serial JS runtime as S1 requires.

If implementers choose sharing the full read-only catalog instead, they must prove retained `CompiledModelAsset` and related materialized assets are platform-neutral immutable values and that no Bukkit item access occurs during SIM conversion/evaluation. Merely saying catalog maps are immutable does not prove platform-neutral capture. Neutral variant-name extraction is the smaller proof surface.

### Fixed-block policy extraction

`block/FixedBlockRouteResourcePolicy.kt:18-87` mixes two responsibilities:

- Bukkit owner authentication (`Plugin` identity in `validateRouteContribution`).
- Shared real domain behavior: stamp/target world match, unavailable territory validation, exact `FixedBlockRouteMatcher.matchAuthority(detectReverse=true)`, `FixedBlockDirectionPolicy.normalWindows`, one stable token per block held to the last requested exit.

Extract the latter into an add-on-local neutral policy accepting target UUID, snapshot supplier, direction policy and admission validator. Existing Bukkit wrapper keeps Plugin authentication, delegates domain contribution/exists. SIM provider checks known feature contributor ID and rejects preparation (fixed blocks do not have a new preparation handler); actual protection/turnout preparation remains Basic. Both adapters invoke the exact same neutral matcher/policy. Do not copy this 50-line algorithm into a separate simulation implementation.

### Business fixtures and concrete assertions

Current tests are valuable algorithm fixtures, but most are hand-assembled immutable frames and are **not evidence** that the real simulation installation is complete. Add actual feature capture/install + real Core step assertions, retaining existing fixtures:

- Official distribution `examples/jr-four-light/signal-packs`; `signalpack/JrFourLightSignalPackTest.kt:42-120` directly loads official JS for four aspects, short sections, selected branch, explicit clear and termination handling.
- `runtime/SimpleSignalsWorldRuntimeManagerTest.kt:2624-2678` is the clearest business oracle: lengths 500/300/150/500 m, 100 km/h prepared reference speed, last block occupied -> `[null,65/3.6,45/3.6,0]` and `[proceed,slow,caution,stop]`; short 10/10/10/10 -> all zero/stop. After release last section is still 65/3.6 because of physical track end, so do **not** assert every emptied block becomes green. Static speed supplier is called once during preparation.
- `runtime/SectionOccupancyRuntimeTest.kt:62-96`: enter/left crossing and immutable baseline; ordinary occupancy update reuses prepared direction controls at 99-125.
- `signal/ProtectionRouteSelectionRuntimeTest.kt:217-350`: requested route readiness, no request=no selected entry, sufficient clearance while distant suffix prepares, whole successor fixed block floor, ambiguity, BA reversal clearing old entrance association.
- `signal/ProtectionSignalDevicesTest.kt:88-205`: fixed/current signals share actual ready route but a request for route B cannot open fixed route A; requester tail exit clears selection/indicator while retained long resources stay independent.
- `signal/ProtectionSignalControllerHandoffTest.kt:61-85`: real controller sees zero signal as a requestable entrance, clearance depends on downstream/foreign red, whole train length and current grant do not magically make route ready; inspect real actuation state and state changes, not just nullable output.
- `block/FixedBlockRouteResourcePolicyTest.kt:30-154,173-206,304-380`: exact permitted directions, reverse bidirectional stable resource identity, internal starting/stopping positions, latest-exit release, provider auth and missing-runtime fail closed. Combine with actual SIM Basic claims/tail movement/rebase instead of asserting contribution lists alone.
- Unknown type/missing catalog/invalid JS syntax must fail capture/install with diagnostic or retain documented restrictive unavailable semantics. Runtime JS throw/unknown variant/missing same-epoch facts -> unavailable/0; valid `clearSpeedLimit` remains null, never rewritten to red. No stale green fallback.

S2 concurrency/manager lock proofs belong execution researcher; source sharing should use this same neutral rule catalog and per-target installation path.

## Files found / minimal suggested ownership

Paths below are repository-root relative; `...` in findings above abbreviates each listed source root.

**SimpleSignals implementation owner**:

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`: capture neutral state; existing domain compilation; per-World JS/lifecycle and manager lock work coordinated with execution researcher.
- New add-on-local `runtime/SimpleSignalsSimulationFeature.kt`: detached capture object, target compiler/installation with existing domain chain and cleanup.
- `signalpack/SignalPackCatalog.kt` (or one new adjacent neutral-rule-catalog file), `SignalLogicRuntime.kt`, `SignalPackEpochRuntime.kt`: source/metadata/variants view shared by LIVE/SIM.
- `block/FixedBlockRouteResourcePolicy.kt` (one adjacent neutral core if needed): split Plugin authentication from actual common policy.
- `SimpleSignalsPlugin.kt`, `src/main/java/.../SimpleSignalsHostServices.java`: discover/register the one new host capability; plugin-disable removes prep registration.
- Tests: new real simulation feature integration fixture, existing manager/logic tests for S1/S3, existing host-services capability fixture; do not globally restructure presentation/authoring.

**Host implementation owner**:

- New `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/simulation/...`: public feature/target/resource-provider interfaces; only public value types in signatures.
- `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt`: replace existing implementation-package feature/target declaration with imported public interfaces and private target adapter; forward existing domain source/admission endpoints, no new domain algorithm.
- Existing `SimpleATO/.../IsolatedTimetableSimulationTest.kt`: adapt prior reserved-seam tests, test preserving multiple feature source registrations and close isolation.
- New `BukkitPlugin/src/main/java/.../bukkit/api/simulation/...`: authenticated prep capability and neutral preparer interface.
- New `BukkitPlugin/.../api/internal/DefaultSimulationFeaturePreparationService.kt`: short owner/preparer directory; capture outside shared lock, existing disable cleanup integration.
- `BukkitPlugin/.../BukkitPlugin.kt`: service lifecycle and actual capture callback, owner removal integration.
- `BukkitPlugin/.../http/TimetableHttpBridge.kt`: pass actual `prepareDomainFeatures` function to existing factory.
- `BukkitPlugin/.../http/IsolatedTimetableSimulationFactory.kt`: imports/public feature type adaptation only if needed, existing required feature check remains.
- Existing public Java consumer fixture / service tests: ensure signatures use public-only types and unchanged plugin/JAR dependency direction.

Root owns task/spec/docs updates. No Gradle execution or product modifications performed by this researcher.

## Related specs

- `.trellis/spec/simple-signals/backend/index.md`: complete installation/source dependencies, public-import and plugin deployment boundary.
- `.trellis/spec/simple-signals/backend/js-signal-rules.md`: same epoch facts, static scanning prepare, restrictive failures; §5 still documents old shared engine and must be updated in implementation.
- `.trellis/spec/simple-signals/backend/formal-four-light.md`: official JS oracle, track-end behavior, quantized speed semantics.
- `.trellis/spec/simple-signals/backend/bidirectional-fixed-block.md`: same token/reversal/directions, inspection floors, tail release.
- `.trellis/spec/javarailways/simpleato/simulation.md`, `reactive-signal-epochs.md`: detached target lifecycle, runtime identity, source installation/admission.
- `.trellis/spec/simpleato-bukkit/backend/index.md`: platform/domain dependency direction.

## External references

No external research required: implementation is existing local domain reuse. Local build declares GraalVM Polyglot/JS community 24.2.2 (`SimpleSignals/build.gradle:26-27`), Kotlin 2.3.0, Java toolchain 25. No recommendation to upgrade dependencies.

## Caveats / Not Found

- Public signatures above are a proposed narrow contract, not compiled API. Root must give both implementers the same finalized names/shapes to avoid an adapter mismatch.
- Existing target seam is necessary but its exposed implementation types are not acceptable as add-on public API. This is the sole required host boundary extension found; do not expand Basic.
- Exact helper used to read target materialized finite static speed still needs implementer lookup/reuse; semantics must match LIVE `railSpeedLimits`, including explicit null vs missing entry and exact topology.
- Model neutrality can be achieved by copying just variant strings; no evidence gathered that all retained compiled model assets are Bukkit-free. Treat full-catalog sharing as unproven until checked.
- Capture of a currently topology-degraded LIVE definition must preserve/reject unavailability consistently; fresh target compilation must not silently resurrect a draft/degraded definition into permission. Implementer must inspect captured authored draft and current guard state when selecting capture failure vs strict target compile.
- No Gradle/build/runtime tests run. This is source-backed research, not verification of future implementation, full Feature support, Paper/Folia server behavior or performance.
