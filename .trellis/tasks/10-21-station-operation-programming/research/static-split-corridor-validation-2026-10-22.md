# Static split corridor integration checkpoint — 2026-10-22

## Implemented boundary

`TimetableValidator` now returns transient structural evidence alongside ordinary retained routes. `StructuralEventWindow` shares the existing Check origin eligibility tolerance, **1.0 m**, clipped to the same physical rail and optionally intersected with the final station berth tolerance. This is structural event eligibility, not a claim about automatic stopping precision. Runtime must verify the actual stopped frame against this eligibility and the exact certified full body before preparing a change.

`StructuralTimetableValidation` records sources by event and finite initial-frame lineage, and onward movement by event/output/duty/step/candidate/direction/target. Source snapshots, topology identity, exact stable car membership and direction are retained. All declared acquisition origins require actual admissible physical witnesses; unknown future assets require revalidation. Validation does not invent future frames. Chained split outputs propagate opaque certified whole corridors and complete-unit member subsets through the fixed catalog dependency graph. Reversed HEAD partitions use physical stable member order and actual unit sizes.

Pre-event movement consumes ordinary validated route selections. Output movement selects a static continuation beyond the entire retained uncertainty corridor and stores the resulting exact route proof. Unique ordinary terminal continuation extends the selected route through the event stop window; an unselected junction, missing margin, wrong operating direction, target inside the source uncertainty, or missing physical witness rejects validation. Partial evidence maps accompanying errors are not usable validation success.

## Gate and evidence

Command: `./gradlew :SimpleATO:test` with the nine filters below, `--max-workers=1 -I /tmp/jr-structural-validator-tests.init.gradle`. Init sets 1g test heap, one fork and `forkEvery=1`. Shared source was frozen during the gate. Log: `/tmp/jr-structural-validator-gate.log`, session 56927; production and test compilation passed, gate finished in 47 seconds.

- `StructuralTimetableValidationTest`: **7 passed**. Real Core geometry covers two initial directions, incoming movement, chained held-child acquisition and reverse, missing witness/interior target, all declared origins, operating-direction/window checks.
- `StructuralOutputCorridorTest`: **12 passed**; opaque source ancestry and actual body checks.
- `FormationEventTest`: **10 passed**.
- `TimetableValidatorTest`: **15 passed**.
- `TimetableSnapshotTest`: **3 passed**.
- `FixedUnitSplitPlanTest`: **2 passed**.
- `FixedUnitCouplePlanTest`: **2 passed**.
- `MissionRouteProgressTest`: **3 passed**.
- `SimpleATOTest`: **16 passed, 4 failed**. All four new physical origin cases stop in the fixture before their activation assertions: `stepSimulation requires a coordinator with automaticFallback=false`, at `SimpleATOTest.kt:557`. Owner ui_check was notified to correct the fixture and rerun this class.

The original shared gate was **74 cases, 70 pass, 4 fixture failures**. Follow-up by fixture owner ui_check: corrected the coordinator setup; `SimpleATOTest` rerun session 95630 completed **20/20 green in 38 seconds**, including all four exact/stale physical-head cases. Together the original eight other classes (**54/54**) and the corrected controller class (**20/20**) cover all **74 selected cases green across the two runs**; the full nine-class command was not repeated after the fixture-only correction. XML under `SimpleATO/build/test-results/test` confirmed discovery, no errors/skips. Physical split tests explicitly mutate test trains to realize the certified candidate and resolve against actual published bodies; they are static proof integration, not scheduled Dispatcher execution or live Paper/Folia acceptance.

## Explicit incomplete scope

Coupling still rejects without a certified static contact corridor. Output StoreAtDepot still rejects without a retained onward storage proof. Runtime structural execution is independently gated/owned by the root agent; this checkpoint does not enable UI or assert end-to-end scheduled structural execution. No persistence schema bump, commit or deployment.

The conservative source envelope retains the entire ancestral body and onward path. A common A→B→A destination inside that accumulated uncertainty therefore rejects. This is sound rejection, **not full turnaround support**.

## Read-only tighter stop-window envelope research

Existing Core capabilities:

- `CoreCar.prepareFollowingCarOnPath` prepares one following car against explicit ordered `RailSegments`, desired physical facing and a fixed predecessor connector, without installing state. It uses the real rigid 3D coupler and axle chord solver; acceptance checks distance residuals.
- `CoreCar.calculatePositionByHeadBogie` is not currently the corresponding pure explicit-path head-car API. Its current overloads obtain paths through `peekCurve`, and its movement overload uses previous active-tail state and predictions.
- `CoreTrain.prepareStructureGeometry` packages supplied endpoints and ordered body intervals. It does not solve arbitrary hypothetical whole-consist placement.
- `CoreTrain.prepareStructureFrame` can capture a prepared frame without installation. This is useful after a sound pure placement solve, but does not itself establish interval geometry.
- `CoreTrain.placementSegments` follows current selected live continuation. It must not choose paths for static hypothetical proof; the frozen retained route must supply the ordered path.

A point solver returning a valid prepared frame at each stop-window endpoint is insufficient to trim the entire historical corridor. On curved track, the current scalar solver does not certify uniqueness or monotonicity of every axle/coupler root as the head varies. Interior head positions can follow a different feasible root branch or fail even if two endpoints succeed. Dense sampling also lacks a conservative error bound.

The required new evidence is a Core-owned immutable stop-window placement certificate over the **entire** allowed head interval: frozen exact topology and selected ordered path; exact complete-unit car geometry/order/direction; a unique continuous reachable rigid-solver branch for all heads; conservative rail-distance bounds for every bogie/body endpoint; numeric residual and interval enclosure bounds; and proof that no loop, branch or unresolved geometry can escape those bounds. Runtime still validates the actual stopped body/membership against the certificate before prepare. A mismatch rejects, never replans.

A useful API direction is a pure explicit-path whole-consist point preparer first, then a separate interval-certification API that may reject unsupported geometry. Only the latter may mint a narrowed opaque `CertifiedStructuralCorridor`; arbitrary lists or sampled frame unions must not mint it. A restricted straight/affine-path proof could be a bounded first implementation only if exact geometry capability and translation invariance are explicitly established; it must not label arbitrary PrimeCurve geometry supported. No such API or interval theorem was implemented in this checkpoint. Coupled source construction additionally needs the independently certified contact/frame-union proof; partner ownership alone cannot mint geometry evidence.

## Subsequent finite output storage checkpoint

New shared `FormationOutputMetadata.split(sourceRecord, sourceCarIds, outputCarIds, carLengthsMeters)` returns immutable declared classification without inventing a future train ID. It verifies exact original physical count/length and a proper contiguous member subset, recalculates actual output car count and configured car-length sum, and inherits declared source home/model/tags/capabilities/speed cap. `toRecord(actualOutputTrainId)` binds only the actual runtime container. It does not mutate permanent unit membership or unit home affiliation. This helper implements **split inheritance only**, not reconstruction of original per-unit classification after mixed coupling.

Static StoreAtDepot now enumerates every declared depot/stabling target compatible with this exact output classification and capacity. Occupancy/reservations are deliberately not a static route filter: a currently occupied eligible point may become free and be selected later. Every compatible target receives a frozen corridor movement proof; one unsafe compatible alternative rejects the whole candidate set. Unknown requested point IDs, no compatible targets and missing registered source classification reject explicitly. Structural outputs cannot obtain proof through the existing physical-only storage fallback.

`StructuralTimetableValidation.storageRoutes` keys event/output/duty/step/source-lineage/direction/exact StablingPointRef. Values retain copied point constraints, shared output metadata and the movement proof. The ordinary structural `routes` map also contains the selected target proof. Runtime still must match the exact reserved point and current output classification, and resolve the proof against the actual body/topology; no live route fallback is authorized by this map. The narrow generic validator exception permits a checked structural owner to store and accepts TakeFromOutput→Reverse→Store as a terminal proved movement.

Focused gate session 18920, `/tmp/jr-structural-storage-gate.log`: **38 tests, 0 failures/errors/skips, BUILD SUCCESSFUL in 40 seconds**. Filters: `FormationOutputMetadataTest`3; `StructuralTimetableValidationTest`12 (five new storage cases); `TimetableValidatorTest`15; `CrossDepotStoreTest`6; `FixedUnitSplitPlanTest`2. First gate found the generic explicit-destination requirement incorrectly rejecting the newly proved output storage; corrected narrowly before the successful full focused rerun. Tests cover all compatible points including current occupancy, capacity/tag exclusion, unsafe alternative invalidating the set, unknown/missing metadata rejection, and actual reversed child proof resolution. No scheduled Dispatcher storage execution or live server evidence claimed.

## Read-only spatial reach alternative and metadata boundary

A conservative Euclidean endpoint reach bound can avoid proving a unique rigid placement root for every head in the stop window. However, simply summing car lengths/connectors around the published head marker is not presently justified by Core representation:

- Published head/tail markers are selected-path arc offsets from the extreme bogies, not rigid body tips. Endpoint displacement must be conservatively bounded on the exact frozen rail geometry.
- Head-bogie connector positions add `normal * floorHeight`. PrimeCurve raw endpoint frames can retain non-unit normals; account for their actual norm or reject unsupported inputs.
- PrimeCurve positions are piecewise linear in supplied arc coordinates. Construction verifies finite monotonic coordinates but does not enforce Euclidean chord distance no greater than arc gap; a path-specific displacement bound is required instead of assuming unit-speed parametrization.
- Car create accepts general dimensions and updateByPrevCar has a nonpositive-search-distance fallback that does not evaluate the normal coupler error predicate. Core must certify the supported geometry/invariant, including relevant numeric residuals, rather than treating arbitrary configured dimensions as an established rigid-gap bound.
- Bound all possible endpoint **occurrences** on the retained path, then retain their entire ordered interval hull with the head window. Directly removing every rail point outside a spatial ball could wrongly remove a looping intermediate rail span connecting reachable endpoints. Multiple matching occurrences keep their full conservative hull.

Recommended Core-owned certificate input: captured immutable configured member geometry and exact order/facing, fixed topology and selected ordered path, same-rail head window, explicit supported-dimension checks. Output: an opaque conservative ordered path envelope containing every full published body admitted by that window, with actual membership/topology/geometry provenance and a runtime actual-body containment check. The geometry owner may use exact piecewise-linear segment enclosures/intersections with outward numeric bounds; SimpleATO must not mint the certificate from sampled points or guessed rail length. This research was sent to graph for the Core-owned stage; no spatial trimming implementation was added here.

Mixed-model classification remains unsettled. Making RollingStockRecord.modelPackageId nullable also affects SavedRollingStockV1 and metadata validation, plus command fixed-unit registration currently using the record model as a nonnull control-compatibility key. TimetableTrainContext is already nullable and builtin model conditions already treat null as no value; command display and Dispatcher pass-through are straightforward. No WebUI modelPackageId DTO was found. Current task design explicitly preserves unit IDs, members and homes, but does not explicitly promise per-unit tags/models/capabilities. Existing FixedEmuUnit has no independent classification, so intersections on coupling followed by split cannot recover original labels without a separately decided authoritative metadata policy. No Depot schema or mixed-coupling classification change was made.
