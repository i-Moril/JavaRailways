# Research: Basic runtime, control, safety, persistence and observation review

- Query: Complete Basic partition C audit across logic, readability/maintainability, documentation, error handling, obsolete code, consistency and tests; distinguish source-confirmed defects from unverified runtime concerns.
- Scope: Internal; all production and test files enumerated below, plus relevant Core/Bukkit/SimpleATO consumers. No source/spec edits, builds, commits or remote operations.
- Date: 2026-10-12
- Baseline: Current shared checkout used by the parent audit; sources remained unchanged during this partition. Parent owns exact HEAD/build provenance and shared ledger IDs.

## Findings

### Persistence: incomplete prevalidation can leave a partial target (P1, source-confirmed)

`Basic/src/main/java/work/art1st/javarailways/basic/persistence/BasicInfrastructureSnapshot.kt:87-113` checks duplicates and rail existence before materialization, but does not validate each waypoint's nonblank ID. `BasicWorldData.kt:40-42` performs that validation only during insertion.

Concrete case: a current snapshot contains two waypoints on an existing rail, first ID `valid`, second ID ` `; IDs are distinct, both rail references resolve, and the other collections are empty. Restore inserts `valid`, then throws on the second item. `wayPoints.all()` is no longer empty despite failed restore. This violates the documented prevalidation/no-partial-materialization contract. Production caller is `BukkitPlugin/.../logic/RailWorldFactory.kt:235`; factory cleanup prevents publishing the failed world, but does not make Basic's restore contract atomic or permit safe reuse of its target.

Recommendation: resolve and validate every waypoint before any store write; keep the current format/version and owner boundary. A fixed input failure test must assert all three stores remain empty. Reuse validated values for insertion rather than re-parse after mutation. This requires no generic transaction framework or compatibility reader.

Waypoint geometry scope: `WayPoint` itself does not currently validate finite/nonnegative/in-rail arc lengths. Unlike `OperationalDestination` (`SimpleATO/.../api/destination/OperationalDestination.kt:140`), generic waypoint creation is minimally validated. `OperationalRoutePlanner.kt:51-57` converts arcs to `OperationalRouteLocation` whose constructor requires finite values; `TopologyRoutePlanner.kt:454` rejects out-of-range positions. `TimetableDispatcher.kt:4553-4568` directly uses an absolute-distance comparison. Thus corrupted negative/out-of-range waypoint arcs can be loaded and fail later; a plugin-created NaN can even bypass that direct comparison. Default strict JSON rejects NaN, so the NaN example is an API-input concern, not a demonstrated current JSON exploit. Fix the proven blank-ID partial restore first; if strengthening arc validation, state the intended contract and validate existing samples rather than inventing arbitrary tolerances or expanding this into a world transaction rewrite.

### Persistence: metadata UUID aliases silently overwrite (P2, source-confirmed)

`BasicInfrastructureSnapshot.kt:100` detects duplicate metadata by the raw String `railId`, but `:111` constructs the final map by parsed UUID. For the same existing UUID containing letters, lowercase and uppercase spellings are unequal Strings but the same UUID. Both pass the duplicate and missing-rail checks, and the second metadata overwrites the first in `associate`. No corruption error is raised. Direction records already do this correctly by parsing first (`:77-81`).

Recommendation: parse metadata identities once, detect duplicates on parsed UUIDs, then check references and materialize. Add a fixed two-record alias regression with different metadata and prove no waypoint/metadata/direction mutation on rejection. Do not add string-case canonicalization compatibility paths or alter the version.

### Config UI: hidden child groups/items do not survive the producer-consumer boundary (P2, source-confirmed; not a demonstrated existing player report)

`impl/config/ConfigUIRegistry.kt:200` recursively flattens all visible descendants before the build event. `:222-228` filters hidden item IDs from that flat list but filters hidden group IDs only from `effectiveSubGroups`. Calling `event.hideGroup(child.id)` therefore leaves that child's items in `effectiveItems`. The group disappears as a heading but its fields stay editable.

The Bukkit consumer compounds the issue: `BukkitPlugin/.../ui/dialog/DialogRenderer.kt:197-198,291-292,353-354` concatenates `effectiveItems` with the raw `effectiveSubGroups.flatMap { it.items }`. Descendant items already included in the flattened list can be duplicated; a child's item excluded by `visibleWhen` or `hideItem` can be reintroduced from the raw group. The currently reviewed production Wand builders use predominantly flat groups; no current `hideGroup` listener was found in those builders. Do not claim an observed production permission bypass, but the public API's nested-group behavior is deterministically wrong.

Recommendation: make the registry produce one authoritative effective item list after all group/item hiding; render/capture only that list. Retain groups as headings/navigation metadata, not a second item source. Add a single combined nested-group test with item visibility, group hiding and extra contributions, plus a consumer-level assertion that fields are neither duplicated nor resurrected.

### Config UI: claimed snapshot/thread-safety semantics exceed implementation (P2/P3 maintenance, concurrency not dynamically reproduced)

`ConfigUIRegistry.kt:8-10` claims thread safety because maps are concurrent, but `pendingItems`, `pendingSubGroups`, and `buildListeners` hold ordinary mutable lists (`:34-40,84,105,120,175`), while group readers do not take the writers' `synchronized(group)` monitor. `ConfigItemImpl.currentValue/isDirty` are unsynchronized. `createSnapshot` (`:333-350`) returns an object whose accessors read the live registry/session rather than frozen values, whereas `BuiltGroup.snapshot` is documented as values at construction (`api/ui/config/ConfigUI.kt:179`). Multiple evaluations against one built snapshot can observe later changes. This is a concrete mismatch in semantics, not evidence of an observed concurrent exception.

Recommendation: clarify startup-only registration versus per-player runtime owner and live-view versus immutable snapshot. Choose the actual ownership contract before adding locks everywhere. For an immutable advertised snapshot, capture values once. Keep registration validation and session state simple; do not build a general reactive/config framework. Concurrency stress tests alone would not settle the intended semantics.

### Control public boundary (cross-module finding owned by parent)

`api/ato/GradeAwareBrakingEnvelope.kt:58-62` exposes `basic.control.TrainDynamics.TrainProfile/CarProfile` in a public API factory, but Basic Dokka only includes `basic.api.*`. Those profile interfaces are also genuine same-repo production composition contracts, consumed by Bukkit control config and SimpleATO detailed simulation, not obsolete code. `TrainActuator.brakingEnvelope` returns the public envelope and Basic is the production factory caller. This is therefore a documentation/API ownership mismatch, not proof that every dynamics implementation must become a stable third-party API or that physics behavior is broken. Parent owns unified disposition; no duplicate ledger item requested here.

### Graded curvature denominator (shared finding with geometry partition)

`safety/RailSpeedCalculator.kt:58-69` divides horizontal tangent heading change by 3D arc length, as the geometry partition found in radius measurement. At constant grade with horizontal radius R and incline angle theta, this yields `cos(theta)/R`, not horizontal curvature `1/R`. It is not correct to infer unsafe overspeed solely from this dimensional mismatch: published train speed is 3D longitudinal speed, and actual horizontal lateral acceleration is `v_3D²*cos(theta)²/R`, whereas the current uncanted expression estimates `v_3D²*cos(theta)/R`. On a constant grade without cant this approximation is conservative relative to the physical horizontal component, although its returned threshold exceeds the planar `sqrt(a*R)` reference. Parent should unify the intended speed/curvature/acceleration model and add graded analytic cases before claiming which direction the safety error takes. The geometry metadata's falsely labelled horizontal radius is independently confirmed; cant/vertical-curvature combinations were not numerically evaluated here.

## Seven-dimension assessment

1. Logic: queue ordering, immutable observation copies, exact occupancy/active-continuation stamp checks, rail-local boundary indexes and safety request separation are coherent on their declared serialized owner. Proven restore and nested UI defects above need fixes. Existing Core epoch/lifecycle findings are not re-reported as new Basic defects.
2. Maintainability/readability: shared pure `TrainDynamics` plus executor/actuator avoids manual/ATO formula duplication. Area tracker is sizeable but its compiler, boundary search and state handoff responsibilities are explicit; splitting merely for line count is not justified. Config registry mixes mutable registration, view building and session operations, and its broad concurrency claim obscures ownership. It deserves targeted semantic repair, not an indiscriminate rewrite.
3. Documentation: world/runtime, threading, dynamics and area-occupancy specs explain non-trivial algorithms and mostly match code. Persistence currently promises stronger prevalidation than implemented. Safety spec's required test list includes swept-min, distinct trains and stale drain not present in the current three-test monitor suite. Train operation file still describes doors as physical sides while the pending per-car behavior target requires capturing semantic-to-physical conversion; handle this in that approved vertical migration, not a parallel reinterpretation.
4. Errors/over-defence: strict identity/epoch/geometry checks and unknown-field rejection defend reachable input/publication boundaries and should stay. No new scan/time budget or speculative fallback is recommended. `TrainDynamics` has intentional clamps and zero-duration behavior; do not turn them into a defect merely for not being fully realistic. Its pure entrypoint validates gravity but not all arbitrary profile/NaN values; production config validation/composition must be evaluated before adding redundant per-tick validation. Handle actuator validates finite time/speed before stepping; executor/manual wrappers are less explicit, a contract consistency concern rather than a reproduced live tick failure.
5. Obsolete code: `controller/ManualController.kt` has no normal Bukkit player-driving producer, but is used by `BukkitPluginDev/.../PhysTestCommand.kt:197` and multiple integration tests. It is a simplified development controller, not safely deletable as unused. Its emergency-brake KDoc claims immediate zeroing (`:41-45`) while it only changes target input and decelerates on later ticks; correct its documentation/scope, or deliberately move it to test/dev ownership after all consumers are handled. It does not read the Basic safety latch, so it must not be substituted for the production realistic controller. No surviving old Java command path was found in this partition.
6. Consistency: mixed English/Chinese KDoc and large decorative comment banners in ConfigUI differ from direct newer Basic APIs. Lower priority than semantics. `Announce` checks trimmed/nonblank text but not ISO controls, unlike the operation/request stable text helper; this is a small input-contract mismatch, not a demonstrated injection path. Do not add machinery for style-only issues.
7. Tests: retain behavioral queue, publication, safety latch, stamped observation and occupancy continuity regressions. No test here merits deletion solely by count or time. Gaps/cleanup candidates below are more important than deleting valid small constructor-boundary tests.

## Test-quality / coverage findings

- `BasicInfrastructureSnapshotTest`: positive round trips and direction invalid-input no-mutation tests are useful; metadata aliases and later invalid waypoint IDs are absent. The future-version test is writer-generated, while the current additive direction fixture is correctly handwritten. Broaden malformed fixed inputs; do not replace useful round trips indiscriminately.
- `SpeedSafetyMonitorTest`: three scenarios cover operating-only overspeed, grace/same epoch and profile/subgrade cap. They never drive a real coordinator publication and pass arbitrary `physicsEpoch` into a monitor reading a manually committed frame. Keep these as focused algorithm tests, but do not claim they prove frame/epoch integration. Missing explicit multi-rail swept minimum, disabled/derailed reset, distinct train state isolation and future/stale drain cases despite spec requirements.
- `RailObjectStoreTest`: lifecycle removal is covered; duplicate-ref rejection and both-index invariance assertions are absent despite the spec describing them. Add both assertions around the same lifecycle fixture rather than many property-only tests.
- `ConfigUIRegistryVisibilityTest`: one meaningful root item visibility test, but no nested-group path or event hide/add behavior. It cannot detect the registry/renderer split described above.
- `TrainDynamicsTest`: direct numerical force ordering, mass, brake build and gravity assertions are useful. Wrapper-vs-pure comparisons intentionally prove that wrappers share the same engine; they are not worthless circular tests, but must not be cited as independent validation of the engine formula. Actual manual publication test adds independent fixed geometry/velocity facts.
- Fixture cleanup: `BasicWorldDataAccessorsTest`, `RailObjectStoreTest`, `SpeedSafetyMonitorTest`, most `BasicInfrastructureSnapshotTest` cases and the first two world-backed `TrainDynamicsTest` cases do not close world physics/tick/runtime ownership. Registry `unregister` merely clears its weak lookup (`testFixtures/.../TestBasicWorldDataRegistry.kt:29-31`) and does not close a Core world. Core fixture explicitly assigns physics/runtime/tick close responsibility to caller (`Core/src/testFixtures/.../TrainTestUtils.kt:16-21`). This is an existing whole-project cleanup class of issue, not a new P1 server leak. Reuse a managed local helper rather than changing production registry or spawning per-test teardown frameworks.
- `RailwayObservationTest` now independently verifies Basic defensive copying and BA/multi-rail values and closes physics/tick; do not regress it to checking Core's prior copies only. Broader binding cleanup is parent-owned lifecycle work.
- `RailwayAreaOccupancyTrackerTest`: substantial distinct cases exercise baseline versus real crossings, conservative endpoint rules, targeted invalidation and shared-cell replacement continuity. Similar replacement tests have different failure modes; no deletion recommended. Some test formatting is compressed, but cleanup should preserve precise sequence/identity assertions.

## File-by-file coverage

Depth labels: **full/static** = entire assigned file read and its invariants/error paths checked; **consumer/static** = relevant production caller inspected, not the entire downstream module. No dynamic regression execution in this partition.

Production prefix: `Basic/src/main/java/work/art1st/javarailways/basic/`.

- `BasicPlugin.kt` — full/static: slot identity initialization; no world state.
- `BasicWorldData.kt` — full/static: composition owners, WayPoint/store mutation/validation; partial-restore dependency.
- `BasicWorldDataAccessors.kt` — full/static: optional versus required lookup, no fixture fallback.
- `control/HandleTrainActuator.kt` — full/static: handle candidate ordering/progression, freeze, envelope and reaction-time derivation.
- `control/RealisticManualController.kt` — full/static: atomic input, queued direction reversal, safety interlock, same-step distance/speed.
- `control/TrainDynamics.kt` — full/static: forces/adhesion, brake application, jerk and integration, service brake helpers.
- `control/TrainDynamicsExecutor.kt` — full/static: profile order/capture, previews versus state commits and operating-direction grade.
- `controller/ManualController.kt` — full/static: simplified input/controller behavior and remaining dev/test callers.
- `safety/RailSpeedCalculator.kt` — full/static: tangent curvature/cant frame and operating/derail units.
- `safety/RailSpeedLimitPolicy.kt` — full/static: intentional manual curve override capped by derail/subgrade; straight special case.
- `safety/SpeedSafetyMonitor.kt` — full/static: per-train epoch counts, queued request drain, swept threshold and profile offset.
- `safety/TrainSpeedProfile.kt` — full/static: finite offset/store identity and lifecycle consumers.
- `persistence/BasicInfrastructureSnapshot.kt` — full/static: capture exclusion, strict codec, all restore stages and UUID identity.
- `persistence/BasicRuntimeSnapshot.kt` — full/static: current pairing-only codec/version; negative pairing metadata has no DTO init validation, but parent owns outer file-pair checks, no independent game impact established.
- `observation/RailwayAreaOccupancyTracker.kt` — full/static: retained cells, targeted topology invalidation, epoch continuity, placement index, traversal boundary search and deterministic merge.
- `impl/config/ConfigUIRegistry.kt` — full/static: registration/pending contributions, values, events, visibility, live snapshot and session commits.
- `api/ato/GradeAwareBrakingEnvelope.kt` — full/static: work prefix, binary search, unbrakeable cutoff and profile visibility boundary.
- `api/ato/TrainActuator.kt` — full/static: finite demand/result and optional test envelope contract.
- `api/ato/command/RevisionedCommandQueue.kt` — full/static: multi-producer serialized ordering, overflow and immutable drain.
- `api/ato/provider/RailObjectStore.kt` — full/static: duplicate identity, mutation lock, lifecycle and rail/ref indexes.
- `api/ato/snapshot/PerceptionSnapshot.kt` — full/static: observations order, finite values and defensive copy.
- `api/ato/snapshot/RouteSnapshot.kt` — full/static: coordinate direction, revision independence and copied segments.
- `api/operation/DoorSide.kt` — full/static: stable tokens, consumer semantics.
- `api/operation/TrainOperation.kt` — full/static: immutable request/ticket identity, failure-only message, gateway and cancellation contract.
- `api/observation/RailwayAreaOccupancy.kt` — full/static: definitions, boundary uniqueness, immutable updates and replacement owner contract.
- `api/observation/RailwayObservation.kt` — full/static: direct value constructors, exact occupancy/train-ID/topology matching and Core projection.
- `api/observation/RailwayStateStamp.kt` — full/static: identity and independent nonnegative revisions.
- `api/safety/WorldSafetyInterlock.kt` — full/static: monotonic latch/reset only.
- `api/ui/config/ConfigConstraints.kt` — full/static: value/range/enum checks.
- `api/ui/config/ConfigGroup.kt` — full/static: mutable hierarchy and ID equality.
- `api/ui/config/ConfigGroupBuildEvent.kt` — full/static: hide/add payloads and copied collection exposure.
- `api/ui/config/ConfigItem.kt` — full/static: value/dirty lifecycle, no claimed safe publication support.
- `api/ui/config/ConfigKey.kt` — full/static: defaults, constraints, ID equality, DSL.
- `api/ui/config/ConfigSnapshot.kt` — full/static: read-only interface versus registry live implementation.
- `api/ui/config/ConfigUI.kt` — full/static: all API methods, BuiltGroup semantics and lazy dependency traversal.
- `api/ui/config/ConfigValueType.kt` — full/static: value tokens and platform-oriented unresolved KDoc type links.
- `api/ui/config/PlayerConfigSession.kt` — full/static: local/global reset and commit contract.

Tests prefix: `Basic/src/test/java/work/art1st/javarailways/basic/`.

- `BasicWorldDataAccessorsTest.kt` — full/static: missing data and world isolation; cleanup caveat above.
- `api/ato/PhaseAFoundationTest.kt` — full/static: revision, copies, sorting, parallel enqueue and stale drain.
- `api/ato/provider/RailObjectStoreTest.kt` — full/static: owner lifecycle and removal; missing duplicate/index assertions.
- `api/observation/RailwayObservationTest.kt` — full/static: direct Basic copies, stamps, BA/body/head/tail order and cleanup.
- `api/observation/RailwayStateStampTest.kt` — full/static: identity/negative revision boundaries.
- `api/safety/WorldSafetyInterlockTest.kt` — full/static: first-engage/reset lifecycle; retain.
- `control/TrainDynamicsTest.kt` — full/static: all force, envelope, handle and real publication cases.
- `impl/config/ConfigUIRegistryVisibilityTest.kt` — full/static: root-only player/session dependency visibility.
- `observation/RailwayAreaOccupancyTrackerTest.kt` — full/static: all baseline, crossing, invalidation and replacement cases.
- `persistence/BasicInfrastructureSnapshotTest.kt` — full/static: all strict/current/direction/reference/capture tests.
- `persistence/BasicRuntimeSnapshotTest.kt` — full/static: pairing round trip, unknown/removed fields and future version.
- `safety/RailSpeedCalculatorTest.kt` — full/static: analytic curvature/cant, units, manual override and straight behavior.
- `safety/SpeedSafetyMonitorTest.kt` — full/static: three algorithm scenarios and manually committed frame fixture.
- `Basic/src/testFixtures/kotlin/work/art1st/javarailways/basic/testing/TestBasicWorldDataRegistry.kt` — full/static: explicit extension registration and weak lookup cleanup.
- `Basic/src/testFixtures/kotlin/work/art1st/javarailways/basic/api/route/RouteAdmissionTestFixtures.kt` — full/static supplemental tiny fixture: opaque process-local identities only; partition A owns surrounding admission tests.

Consumer/static: Core `TrainController`, relevant `CoreTrain.reverse*` and `TrainTestUtils`; Bukkit `RailWorldFactory`, `TrainControlManager.acquireManualControl`, `TrainControlDialog.directionButton`, `DialogRenderer` build/capture/confirm flow, world/profile cleanup usages; SimpleATO `OperationalRoutePlanner`, `TimetableDispatcher.validateAtWayPoint`, `OperationalDestination` validation and profile/envelope usages; `BukkitPluginDev.PhysTestCommand.startTrain`.

## Related specs / references

Read current repository specs: `.trellis/workflow.md`; `.trellis/spec/javarailways/basic/{api-boundaries,world-runtime,threading-and-publication,speed-and-derail-safety,train-longitudinal-dynamics,train-operations,persistence,testing,ato-control-and-constraints,ato-providers,ato-missions-and-perception,railway-area-occupancy}.md`; shared `versioning.md`. Compared existing `research/issues.md` to avoid re-reporting repaired Core publication and already-fixed Basic observation-copy issues. No external dependency/API facts relied upon; no web research required.

## Caveats / Not Found

- This is one full static partition, not a completed all-module audit or a repair. Parent combines partitions A/B and module build/documentation evidence.
- No added executable reproducer or new test run here. Source-confirmed examples follow straight-line execution; parent may reproduce them as focused regressions before fixing.
- No claim of Folia concurrency, client UI, long-train braking realism or game performance validation. No remote server state read or changed.
- Core coupling/per-car JS work remains separate; stale controller profile lists after structural change must be covered in that vertical transaction, not silently fixed here by main-consist profile fallback.
- Parent owns build/resources/Dokka and all source/spec/shared-ledger edits. This report does not grant permission to change APIs, introduce runtime compatibility or upgrade versions.
