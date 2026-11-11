# Research: SimpleSignals presentation boundary

- Query: Which existing public host capabilities can independently installed SimpleSignals use for real model presentation, where can changed state be published, and what bounded contract needs review next?
- Scope: internal; current workspace source, requested baseline HEAD `2eab80f2` supplied by main session (this research role did not run Git).
- Date: 2026-10-01

## Findings

Follow-up direction: use the neutral host machinery extraction rather than build a second renderer. The
subsequent TARGET drafts live in `model-presentation-api.md` (Bukkit owner) and `model-presentation.md`
(SimpleSignals owner). The alternatives below remain research history; exact API/configuration details
are still pending review, and no service or renderer was implemented by this research.

### Public host capabilities and actual gaps

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/JavaRailwaysServiceRegistration.kt:26` registers exactly seven public services: RailTarget, PlayerOperation, RailwayObservation, InfrastructureResource, SignalEpochSource, InspectionSource, RoutePlanningRestrictionSource. There is no model loader, render backend, render handle, or scheduler service.
- `.../bukkit/api/infrastructure/InfrastructureResourceService.java:45` exposes `currentTopology(UUID)`: exact immutable topology root safe for retained reads. **It does not provide world-space curve geometry.** `Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/topology/TopologyState.kt:265` contains revision, nodes, edges, rail projection and crossings, with rail-local lengths/cuts but no spatial positions/tangents/normals. Do not propose sampling a curve from this root.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/rail/RailTargetService.java:19` exposes `findLookedAtRail(Player,double,double)` for authoring. Its `RailTarget(Rail,double,double)` returns an immutable rail curve; the callback can finish on any thread. `Core/.../api/physics/rail/Rail.kt:22` supplies `curve`; `Core/.../api/physics/curve/Curve.kt:12,43` supplies `getPointAt(abMetres)` and position/tangent/normal. This is usable while targeting a rail, but **there is no public UUID lookup for saved placement restoration**.
- `BukkitPlugin/.../config/model/ModelManager.kt:7,127` offers train/rail configuration loading and `createTreeBuilder(World,...)`; `.../extension/ModelLoader.kt:5` returns implementation `CompositeModel`. These are Kotlin-public implementation classes, not reviewed add-on contracts; independent SimpleSignals must not directly import them.
- `BukkitPlugin/.../util/PlatformDispatcher.kt:14` is a host implementation interface, and `.../util/LatestAffinityTaskDispatcher.kt:13` is explicitly internal. Neither is a public service. The existing SimpleSignals command already invokes Paper's own public async/entity schedulers (`SimpleSignals/.../command/SimpleSignalsAdminCommand.kt:30,35`), so a plugin-owned adapter can similarly use Paper/Folia location/entity schedulers without a SimpleATOBukkit bridge.
- `SimpleSignals/build.gradle` uses compileOnly host dependencies and Paper API `1.21.11-R0.1-SNAPSHOT`, with descriptor `folia-supported: true`. Its JAR check forbids copying host classes. There is no installed SimpleATOBukkit dependency or reusable model service to silently assume.

### Definitions, placements and state publication

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/FixedBlockSignalDefinition.kt:72`: stable signal ID/revision, controlled block, lead time, occupied/clear outputs and downstream dependencies. **No explicit physical placement or model asset/style reference.** Its logical entry can be derived from the controlled block's first traversal; this is not a configured physical pole/display transform.
- `.../signal/ProtectionSignalDefinition.kt:11`: FIXED_ROUTE/CURRENT_ENTRY plus binding ID and rule data. Again **no physical placement or model asset/style reference**. A logical entry comes from the fixed route/entry binding; position/offset/rotation still require an authoring contract.
- `.../signal/ProtectionSignalDefinition.kt:43`: `RouteIndicatorDefinition` alone has explicit `RailPositionSnapshot position`, plus no-route variant and route-to-variant map. It lacks a concrete model package/config binding and local offset/rotation/scale. `ProtectionSignalCompiler.kt:113` validates referenced rail and arc-length range, not a world transform.
- `.../runtime/SectionSignalRuleRuntime.kt:10,24` carries `Available(SectionSignalOutput(speed,opaqueVariant))` or `Unavailable(reason)`; unavailable is zero-speed safety state with **no invented visual variant**. `SectionSignalSnapshot` at line 158 owns immutable stamp/states/changedSignalIds. Line 307 compares complete rule output for change propagation, so a speed-only change can appear in changedSignalIds even when the visual variant is unchanged.
- `.../runtime/SectionOccupancyRuntime.kt:129` is the existing single serialized signal callback. It first updates selection, then indicators and rules. Lines 139–149 use changed selection entries and `indicatorsByEntry` to reach relevant indicators; line 195 swaps occupancy/signals/selection/indicators together through one volatile `latest` reference. **No presentation callback/subscription currently exists.** This post-swap point is the natural plugin-internal place to enqueue immutable visual updates; Bukkit work must not run here or delay signal completion.
- Indicators currently expose `Map<String,RouteIndicatorState>` with selection and modelVariant (`ProtectionSignalDefinition.kt:70`). There is no changed-indicator ID set; derive candidate IDs from the existing dirty-entry reverse index and compare actual visual variant before enqueueing, instead of scanning all devices or treating selection metadata changes as visual changes.
- `.../runtime/SimpleSignalsWorldRuntime.kt:276` exposes snapshot getters for occupancy/signals/selection/indicators; `commitPrepared(...):464` swaps definition and section runtime at line 482. Definition replacement/removal and world install/unload must feed the same presentation owner, independently of occupancy changes. An old runtime callback can finish after a replacement; scheduled visuals must validate exact world runtime/publication identity and newest per-object presentation revision.

### Existing authoring/model patterns worth following

- `BukkitPlugin/.../config/model/PackageLoader.kt:24,36` loads self-contained train/rail packages; existing/invalid referenced config fails rather than silently disappearing. These are patterns, not APIs SimpleSignals can call.
- `.../config/model/PartDefinition.kt:18` expresses item display parts and local offset/rotation/children; `PartItemConfig.kt:12` expresses material, item-model resource key or intentionally chosen numeric custom model data, and scale. `models/s100/parts/s100/head_light.yml` demonstrates real resource-pack assets and configurable item swaps/brightness.
- `BukkitPlugin/.../config/RailModelConfig.kt:15,42` and `models/rails/standard/rail_model.yml` demonstrate typed item/block display elements, transforms, scale and brightness. Rail repetition and train seats/interactions/physics are unrelated requirements; do not import them into signal MVP.
- A minimal signal-owned config can map each opaque variant to a list of actual ItemDisplay/BlockDisplay parts with explicit asset keys/materials, transform and optional brightness. The installed config/pack owns aspect appearance. This requires no national color vocabulary, Unicode proxy, train behavior script, or mandatory JS runtime. A native display implementation is a concrete bounded backend choice, not an already-existing SimpleSignals feature.

### Two concrete choices to present for review

1. **Independent plugin-owned model package/renderer.** SimpleSignals owns signal packages analogous to Train/Rail: real parts/assets/transforms, variant selection, loader, renderer and target scheduling. Any Java/YAML/optional JS customization remains signal-owned; no mandatory JS renderer. A reviewed neutral geometry read capability is still required for restoring rail-relative placement: resolve exact world/runtime/topology plus rail ID/AB metres to immutable position/tangent/normal with missing/stale/unloaded semantics. This is a proposed gap, not an existing API. Explicit world transforms could avoid it only if the user first accepts static physical placement; they must not silently replace authored rail positions. Native displays would be a separately accepted backend scope, not a claim to have delivered the complete existing train-model renderer.
2. **Extract reviewed neutral host model presentation and rail-placement contracts.** Expose generic model package/parts loading and presentation handles through `bukkit.api.*`, with precise immutable input, target ownership, latest-only update and lifecycle semantics. Add the missing rail-relative geometry/placement seam to that review. SimpleSignals continues owning signal definitions, packages, rules, opaque variants and presentation state, consuming the neutral service; SimpleATOBukkit is uninvolved. Do not expose ModelManager/CompositeModel or train-specific seats/physics wholesale, and do not claim such a service already exists. This allows shared existing rendering machinery but adds a cross-plugin contract batch.

Recommended next decision: permit extraction of a neutral public host model presentation service (including the reviewed rail-relative placement seam), or keep a separate renderer inside SimpleSignals. In both cases preserve signal-owned model packages, YAML/Java and optional JS customization, and real multipart assets analogous to Train/Rail. Freeze exact format/backend/animation requirements after this ownership choice. A reduced native-display batch is valid only if explicitly accepted as that batch; a single placeholder item lamp cannot stand in for the requested model system. World-space persisted transforms are not an assumed replacement for rail position.

### Acceptance boundaries for that bounded batch

- Initial baseline, actual variant change, configured unavailable state, definition create/update/remove, reload and unload all converge to configured real assets. Missing variants/assets reject configuration or disable presentation explicitly; they never modify safety output or substitute fake models.
- After the existing atomic signal publication, a non-blocking plugin-local sink produces immutable per-object visual revisions. Per object allow one scheduled/in-flight task plus one newest pending value; apply only current world/runtime/binding/revision. Speed-only or selection-metadata-only changes with the same visible variant perform no entity update.
- All creation, update and removal execute under the owning location/entity scheduler. Plugin disable/world unload closes pending tasks and cleans entities; queued old-world work cannot create displays in a reloaded world with the same UUID. Rendering failures do not throw back through the signal epoch callback.
- Exact asset configuration and persistence defaults must be reviewed alongside the chosen placement semantics. Native displays and any resource pack installation limitations must be stated honestly; passing backend state tests is not evidence of visible Folia rendering.

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md`: independent plugin/API boundary; model rendering and JS/YAML remain unimplemented.
- `.trellis/spec/simple-signals/backend/protection-region-signal-selection.md`: same-epoch immutable publication, opaque variants and change-only dependencies.
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`: target ownership; latest-only helper is explicitly not add-on API.
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md`: public service boundary and signal-plugin presentation ownership.
- `.trellis/tasks/09-18-signal-addon-implementation/design.md:239` and `prd.md:41`: plugin-owned rendering and per-object immutable presentation revisions, independently of safety epoch.

## External References

- None fetched: this investigation is source-bound; dependency target is Paper API 1.21.11 as declared in `SimpleSignals/build.gradle`. Exact Paper API signatures for any implementation should be checked from the resolved dependency/source during implementation.

## Caveats / Not Found

- No code implementation or Git operation performed; only this research file was written. User `TODO.md`, docs-site/models and the separate 09-24 migration work were not modified.
- No public by-UUID world-space rail geometry, public model loader/render service, or public latest-only platform scheduler found. A public authoring ray target does not solve reload restoration of a saved rail-relative position.
- Physical placement semantics and exact model/config vocabulary remain user-owned choices. World-coordinate placement is a real alternative only if accepted as the intended behavior, not a hidden substitute for rail-relative attachment.
- Existing specs prohibit an extra rollback/revision protocol for shared rule publication; visual per-object revisions required by PRD should remain disposable presentation sequencing, not new safety state, schema numbering, or persistence compatibility.
