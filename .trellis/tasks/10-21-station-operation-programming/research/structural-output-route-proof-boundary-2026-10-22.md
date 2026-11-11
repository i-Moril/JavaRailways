# Structural output route proof boundary

Read-only source investigation after the physical grant-speed integration gate. No structural authoring/runtime implementation is authorized by this note.

## Existing reusable seams

- Basic `route/TopologyRoutePlanner.kt`: `TopologyRoutePlanningInput` accepts exact canonical rail positions, start/arrival facing, immutable topology and composed restrictions. It can retain routes starting between markers; no fake Check or persistent waypoint is necessary.
- SimpleATO `routing/OperationalRoutePlanner.kt`: its internal `plan` already composes operating directions and provider restrictions around that Basic API. The public wrapper currently accepts WayPoints; a structural endpoint adaptation should reuse the internal path rather than a second planner.
- `timetable/Timetable.kt`: `ValidatedTimetableRouteKey` currently keys service/step/origin/leg/target/start direction, while evidence retains the exact ExpandedRoute, stop offset and captured topology. Structural evidence additionally needs a typed event/output origin and a finite composition/geometry variant identity, not a guessed marker string.
- `api/depot/FixedEmuUnit.kt` and DepotStore's fixed-unit/rolling-stock snapshots provide finite membership identities. Unit declarations do not contain car lengths, bogie geometry or connector dimensions; actual Core cars remain authoritative for those.

## Actual missing geometry contract

Core does not currently expose a pure hypothetical formation-placement preview at a future event anchor.

- `CoreTrain.kt:887` `splitEndpoints` and `:932` `prepareSplitGeometry` are private and depend on actual split-car bogie locations plus the selected occupied path. `splitLocationOnPath` preserves occurrence order, so neither grouping rail IDs nor subtracting total car lengths reproduces it.
- `CoreTrain.kt:944` `prepareStructureGeometry` is internal and accepts already solved endpoints. It is not a public hypothetical endpoint solver.
- `TrainCoupling.kt:44` `inspectApproach` is read-only but resolves actual world trains, exact current frames, collision envelopes, selected bridge and rigid leading-car contact solve. Its proof becomes stale on relevant frame/configuration changes. It cannot certify placement at a future declared anchor.
- `TrainCoupling.kt:526` `prepareCoupling` is private and uses actual CoreCar geometry and solved contact. Curved geometry cannot be replaced by scalar summed car lengths.

Therefore enumerating finite possible whole-unit compositions is necessary but insufficient to prove arbitrary REST/coupled-output head positions. Publication readiness cannot silently defer this proof to runtime.

## Conservative validation contract

1. Enumerate every eligible acquisition composition and orientation under the declared fixed constraints, then propagate exact ordered whole-unit output variants through the acyclic same-cycle event graph. Reject missing membership, ambiguous partition, unsupported orientation, cycles or an unbounded/unsupported state with event/duty context. Do not truncate candidates and report success.
2. For each supported output variant, require a proven exact operating-head location/facing and sufficient body/path evidence. Plan and retain every supported successor from that origin, with station stop offsets/length checks and provider restrictions preserved.
3. With present APIs, unchanged-direction HEAD/FIRST output can preserve the source operating-head anchor, provided the source stop position is itself exact. A moving REST output or any output whose operating head must be newly derived needs the missing shared geometry solver. A smaller initial supported subset must explicitly reject these cases; it must not present them as validated continuation. HOLD has no onward movement edge, but still needs runtime protective body/claim reconciliation and is not AVAILABLE stock.
4. A general implementation needs Core-owned read-only hypothetical placement/partition/contact preview using the same geometry implementation and immutable selected path plus physical car configuration. Do not duplicate the rigid solve in SimpleATO. Exact stop tolerances must also be accounted for; one nominal future frame is not a proof of every permitted physical placement.
5. Runtime OUTPUTS_HELD must additionally match actual cars/direction/frame against one validated variant, verify current topology/dependencies and explicitly prepare/protect actual onward evidence before activating output movement. A fresh runtime replan is a safe-boundary verification, not a substitute for static coverage. Ordinary submission retains its no-fallback contract.

## Separate completed speed-consumer evidence

`AdmittedSpeedPhysicalIntegrationTest` passed one JUnit test containing grant-only 2 m/s and tighter independent-signal 0.5 m/s cases. Real SimpleATO, HandleTrainActuator, PhysicsCoordinator and Core publications verify pending startup hold/progression, pending extension cap retention, full-route authority cap retention and actual movement bounded by both caps. Admission results are controlled inputs; this does not establish real Basic rendezvous/coupling completion.

## Alternate conservative corridor proof (subsequent review)

The missing hypothetical endpoint solver is not intrinsically required for split continuation. A conservative corridor can prove all possible output heads without calculating their exact future offsets. This supersedes the earlier HEAD-only limitation **when the following corridor premises are actually established**.

Core source support:

- `CoreTrain.splitEndpoints` selects the first/last output-car bogies plus their actual endpoint offsets on the source occupied path; `splitLocationOnPath` rejects positions outside that path. Both output geometries therefore lie within the selected source path on a successful split.
- `CoreTrain.prepareSplit` reverses endpoints and ordered traversal when output direction changes; it does not invent another physical branch. This supports FIRST and REST in either declared direction using an appropriately oriented corridor.
- `CoreTrain.placementSegments` follows the selected physical path and rejects directed loops before the head. Runtime still must compare the complete actual ordered traversal to the retained corridor; a rail-set overlap is insufficient.
- Basic `TopologyRouteProjector` validates an already selected ordered traversal against immutable topology without path search. `CompiledRouteProgressPath` represents ordered occurrences, but its runtime cursor chooses the first forward match; it does not by itself prove a unique initial corridor occurrence. Add an explicit uniqueness check before using that mechanism.

Sufficient proof obligations:

1. Capture a finite, connected, occurrence-ordered corridor including the **entire initial occupied body** and every validated incoming traversal up to the complete permitted stopping range. Never extrapolate an unknown body prefix from total length. Reject an absent/already ambiguous body path, uncontrolled topology/configuration, unsupported stop-range bound, or any variant not covered. A nominal target alone does not establish the forward bound under tolerance/overshoot.
2. Establish the source train cannot leave that corridor before the event: incoming route selection is retained/protected, initial membership is unchanged, and movement remains on the retained path. For multiple finite acquisition variants retain all corridors separately. Repeated event outputs must propagate proven corridors, not reset to a guessed marker. Cyclic/unbounded cases remain contextual failures.
3. For each resulting fixed direction, orient the complete uncertain head interval accordingly. Build one frozen physical route whose initial portion is exactly that oriented corridor and whose target (including effective station stop offset) lies strictly beyond its far endpoint. The worst-case output head then has positive onward distance. The planner's arbitrary shortest path must not bypass any corridor portion: prove ordered coverage or concatenate the mandatory corridor with a validated extension and validate every connection.
4. Prove every permitted starting suffix is admissible under the **compiled planning restrictions**, not merely topological connectivity. `TopologyRoutePlanner` turns path replacements into indivisible macro arcs; a full corridor route can be legal while starting inside a replaced region is unsupported. Conservatively reject uncertain-origin intervals intersecting replacement interiors (and any forbidden directed interval) unless a deliberate authoritative suffix rule proves them. Also preserve existing provider-specific resource/admission checks; a topologically valid suffix does not confer ownership.
5. At OUTPUTS_HELD uniquely map actual head **and complete body** to an ordered corridor subpath, check exact output membership/direction and current dependency/topology validity, and reject any outcome outside it. Derive movement by slicing the retained route at the projected actual head; derive admission's frozen route from the actual tail/body plus the retained onward suffix. Basic progress initialization expects observed tail at route start, so a head-only trim is not a valid admission route. Duplicate boundary matches may be normalized only if they represent the same route coordinate; loops with multiple coordinates reject.
6. Request output body/departure protections and provider clearances against this derived frozen route before releasing holds. Runtime projection/trim performs no search; topology changes or projection failure cannot silently choose another branch. A newly authorized explicit revalidation boundary remains separate from ordinary submission.

This supports normal straight/curved FIRST+REST departures and reversals **when** all six premises hold, regardless of unknown exact split offsets. It deliberately rejects a next target inside the uncertain interval, an unavailable initial body prefix, ambiguous retracing/loops, and restricted starts. It proves route coverage, not that a planned structural operation will necessarily succeed or that every possible composition is physically controllable; whole-unit conservation and actual structural preflight remain separate gates.

Coupling needs an additional containment proof. `TrainCoupling.prepareCoupling` constructs the output from both occupied paths plus the exact contact bridge, and rigid contact corrections remain on captured collision evidence. Incoming routes alone do not automatically contain that bridge/correction footprint. For a coupled-output corridor, retain and validate the finite complete union as **one ordered simple physical path**, including bounded approach/contact range and both source bodies; reject branching or an unproved contact bound. Current live `inspectApproach` can validate actual contact later, but cannot retroactively make an unproved static corridor publication-ready. This is the remaining distinct investigation for general coupling, not a reason to block sound split-corridor validation.

## Implemented pure split-corridor foundation

New `timetable/StructuralOutputCorridor.kt` exposes internal `StructuralOutputCorridor.prove(initial: TrainFrameSnapshot, incoming: RouteSnapshot, onward: RouteSnapshot, topology, restrictions, reverse, stoppingOffsetMeters)` and `resolve(actualFrame, currentTopology, expectedCarIds, expectedReversed)`.

- The initial published frame must be stopped/aligned with unique nonempty membership and complete ordered body matching its published tail/head. A free list cannot masquerade as full initial-body proof.
- `incoming` must start at that head and explicitly cover the caller-proven entire stop range; the helper does not infer future stop tolerance/overshoot. The origin interval is always the **entire** oriented body-plus-incoming corridor; no arbitrary narrower interval is accepted.
- The factory validates selected physical geometry/restrictions without a search, mandatory full-corridor coverage, effective target strictly beyond the entire interval, and atomic replacement preservation. It rejects uncertain starts in replacement interiors, forbidden traversal and repeated overlapping physical occurrences.
- Resolution requires the identical captured topology root and a stopped output with exact expected membership/direction. It uniquely projects actual endpoints and compares the entire actual body to the retained subpath. It derives `movement` from actual head and `admission` from actual tail by slicing the original RouteSnapshot, preserving explicit zero-length port markers. These are geometry values, not grants; the coordinator must acquire output protections before releasing holds.
- No Dispatcher, Timetable model, persisted format, event validation or coupling-static-proof integration is included in this foundation.

Focused gate: `StructuralOutputCorridorTest` 11 tests, zero failures/errors/skips; eight real Core straight/curved split combinations exercise both source directions and both output directions, resolving both physical halves and rejecting identity/body/origin mismatches. Rejection groups cover missing/partial initial prefix, target inside uncertainty, incorrect mandatory path, atomic restrictions, duplicates and forbidden traversal. Log `/tmp/jr-structural-corridor-tests.log`, BUILD SUCCESSFUL in 40s.

### Opaque descendant envelope API (next foundation increment)

`CertifiedStructuralCorridor.fromInitial(initialFrame, incomingThroughEntireStopBound, topology)` is now the sole initial source constructor. Read-only `retainedPath`, `topology`, `memberCarIds`, `reversed`, and `initialFrame` expose the certified inputs needed by the static validator. Parent proof/envelope ancestry is retained privately.

- `source.requireContains(actualFrame, currentTopology, expectedCarIds, expectedReversed)` checks exact source membership/orientation and the complete stopped body against the envelope before event preparation. The coordinator separately enforces the narrower event eligibility window.
- `source.splitOutput(expectedChildCarIds)` requires a nonempty proper contiguous stable-order partition and retains the **identical entire source path**. The validator must already have proved the whole-unit split; this method does not infer unit membership. A held child can therefore become a later source before any onward mission is selected.
- `source.reoriented(reverse)` reverses the complete path and orientation without movement, rear trimming or a fabricated future frame.
- `StructuralOutputCorridor.prove(source, onwardThroughNextStopBound, restrictions, reverse, stoppingOffsetMeters)` proves the full oriented source corridor plus selected continuation. `proof.outputEnvelope(expectedOutputCarIds)` retains the complete proved onward path and a contiguous source-member subset for the next event. It does not infer that a moving head has cleared an old rear.
- Event/output/candidate identity and fixed event DAG membership remain owned by the validator/coordinator map. Unsupported cycles, ambiguous path occurrences and targets inside accumulated uncertainty must reject contextually.

Before this increment, combined gate `/tmp/jr-structural-contact-integration.log` passed 18 tests: corridor11, new actual SimpleATO/Handle/Basic/Core contact3, fixed-unit split plan2, fixed operation2. Both straight and curved contacts physically linked after an independent zero cap was cleared. Changed peer caused no movement and Basic's explicit protective failure; it was not a successful continuation. The new opaque-envelope chain test requires its own post-increment gate.

Verification correction: the first opaque-envelope run reported 11 tests, because the new expression-bodied chain test inherited the exception return type of its final `assertThrows`; JUnit did not discover that non-void method. Its signature now explicitly returns Unit. Compilation and the original11 cases passed; final runtime evidence for the chain must come from the subsequent XML showing12 discovered tests. Do not treat source annotations or Gradle success alone as evidence that a new test executed.

Final post-fix opaque-envelope gate: session94315, `/tmp/jr-certified-corridor-tests.log`, BUILD SUCCESSFUL35s. XML confirms12 discovered tests, zero failures/errors/skips, including the named actual-Core certified-child ancestry/held-membership/reorientation test (0.201s). Helper APIs are frozen; source and Gradle ownership released to graph/ui coordination. This proves the pure split-envelope foundation and resolution; static catalog and Dispatcher integration remain separate owners.

### Formation-output mission-origin consumer tests

Added only the assigned tests in `routing/MissionRouteProgressTest.kt` and `controller/SimpleATOTest.kt`; production origin/layout/controller code remains root-owned. Layout cases use actual Core published AB/BA anchors without Basic WayPoints and reject a0.25m shifted route start. Controller cases run actual Core physics with the existing fixture and deterministic manual coordinator: exact AB/BA heads activate the submitted mission and publish movement; a real1m head move between submission and activation stalls before active mission/movement, with exact origin-change reason and zero speed. The initial fixture incorrectly used automaticFallback=true with stepSimulation; corrected the new cases to supply/own automaticFallback=false while preserving existing fixture defaults.

Evidence: layout3 passed in the shared74-case gate (other70 cases passed, four new controller fixtures initially failed). Corrected controller-only gate session95630 passed20/20, zero failures/errors/skips, BUILD SUCCESSFUL38s. XML retained at `/tmp/jr-formation-origin-controller-tests.xml`. Source and Gradle freeze explicitly released to root/graph afterward. No production fix was required.
