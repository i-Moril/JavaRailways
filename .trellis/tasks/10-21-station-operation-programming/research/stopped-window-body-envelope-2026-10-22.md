# Core-certified stopped-window body envelope — 2026-10-22

## Boundary and ancestry

`StoppedBodyGeometry` captures actual Core car/bogie objects and immutable configured dimensions, connector lengths and orientation parity from a coherent current stopped publication. Every car and bogie velocity must be zero. It permits body-end bogies, requires finite dimensions, nonnegative connectors, and A>B within the inclusive body ends. In either operating orientation `tailOffset>=0` and `length-tailOffset>0`, so the following-car search cannot enter its nonpositive-distance fallback without the rigid coupler predicate.

The issued `StoppedBodyEnvelope` is conditional on the caller's independently proved complete ancestor traversal. It does not mint a route, fake future train frame or movement authority. SimpleATO consumes it only from an existing opaque `CertifiedStructuralCorridor` at an explicit stopped marker window. Initial frame, original objects/configuration, topology, rail/curve incarnations and parent proof ancestry remain captured. Resolve/event validation checks the actual current published frame, exact original car/bogie objects, dimensions and orientation parity; same IDs do not authorize replaced/configured cars.

## Conservative spatial construction

Exact `PrimeCurveImpl.geometryBreakpoints` supplies every affine position primitive. No sampled stream or generic-curve fallback is accepted. The exact class constructor already certifies nonzero scaled tangent/normal cross products over each DISCRETE interval with its Bernstein hull, excluding cancelling frames; LINE intervals use their actual raw frames. Raw subnormal/overflow norm domains reject.

The endpoint reach bound uses actual configured rigid car lengths, all nonnegative connector lengths, accepted axle/coupler residuals, both extreme bogie-to-published-end arc offsets, Core endpoint-search `Curve.TOLERANCE`, floor/normal offsets, and outward arithmetic bounds. Published endpoints are rail-offset points, so rigid length alone is insufficient. Position Lipschitz bounds are derived from actual primitive coordinate/arc differences, not an assumption that arc coordinates have unit Euclidean speed. All positional gaps at connected rail boundaries are included. Per-predicate floating error bounds enumerate vector subtraction, norm products/additions/sqrt, normalization, and affine anchor/tip operations. Trial solver iterations recompute the predicate and do not multiply this residual bound. The initial rigid witness uses all original member/frame coordinate magnitudes, even for a later short child at a distant marker.

The entire head window gets a primitive position enclosure. Its box expanded by endpoint reach is intersected conservatively with **every** retained path occurrence. The result retains the complete ordered hull, including intervening loop excursions outside the spatial box. It never drops intermediate path merely because its world position is distant. Overflow or numerically unresolved axle scales reject explicitly.

SimpleATO first retains the complete movement proof, then narrows only the future stopped source envelope. A→B→A can therefore return beyond the bounded B body without retaining all historical A→B uncertainty. The movement proof used at the actual departure still includes the actual full source body and selected route. Repeated ambiguous physical corridor traversals remain rejected by the existing SimpleATO corridor validator; Core's conditional spatial helper preserves all occurrences rather than hiding them.

## Verification

Final gate session **55470**, log `/tmp/jr-stopped-body-geometry-gate.log`, **BUILD SUCCESSFUL in 1m32s**. One worker, 1g test heap, forkEvery=1:

- Core `StoppedBodyGeometryTest`: **9 passed**. Actual straight bodies, 12-car train, actual curved rear, nonunit arc endpoint-offset counterexample, full loop occurrence hull, stale publication/config/topology, large-coordinate rejection, nonzero connected endpoint gap and cancelling-frame rejection.
- SimpleATO `StructuralTimetableValidationTest`: **13 passed**, including actual outbound movement→split→reversed return to original marker and actual child proof resolution.
- `StructuralOutputCorridorTest`: **12 passed**.
- `FormationOutputMetadataTest`: **4 passed** (includes root's aggregate metadata addition).

**38 tests, 0 failures/errors/skips**, XML audited. First attempt compiled and Core9 passed but exposed a head-window coverage check requiring one raw route segment; coalescing contiguous same-rail/facing spans corrected target+margin coverage, and the entire focused command was rerun green. `git diff --check` clean.

This is local static-proof/actual geometry fixture evidence, not a scheduled Dispatcher or live Paper/Folia acceptance claim. The numerical implementation has targeted boundary regressions and source-based derivation; no independent formal numerical audit is claimed. No schema changes, commit or deployment.

Owned files: new Core `StoppedBodyGeometry.kt` and test; `CoreCar.kt` only changes AXLE_TOLERANCE visibility to internal so the certificate shares the solver constant; SimpleATO `StructuralOutputCorridor.kt`, `StructuralTimetableValidation.kt` and related validation test. Existing CoreWorld/CoreTrain public APIs are unchanged.

## Exact initial coupling checkpoint (2026-10-22)

Static service cursors now rendezvous at the same declared fixed coupling event and enumerate finite disjoint source pairs. Each pair retains both source window/body proofs, an immutable parent-pair candidate lineage, the actual Core contact template, the complete approaching-body/bridge/stationary-body route, and configured two-root output geometry. Runtime must provide a freshly host-issued contact proof matching the captured geometry and current topology; the static template does not extend epoch validity. Output classification combines only common tags/capabilities, minimum speed and common model identity; permanent unit home affiliations remain separate.

New parameterized real-Core validation tests cover both stationary orientations, both source evidence entries, stable output order, pair lineage, fresh-proof acceptance and moved-peer rejection. The focused static XML from the combined gate records 16 tests, zero failures; final combined gate and actual Dispatcher contact acceptance are reported separately by their owners. The earlier standalone checkpoint additionally passed four metadata tests.

Future stopped-window contact remains explicitly rejected. A sound straight-rail extension must derive connector contact for the full Cartesian product of head windows, account for rigid solver residuals and raw rail endpoint offsets (especially non-unit arc speed), and certify the complete bridge/body union without creating hypothetical TrainFrameSnapshot values. Conditional onward-route safety alone is not proof that contact exists. Draft derivation is in /tmp/jr-straight-contact-next.txt and has not been implemented or tested.

Timetable validation now exposes the public circulation summary produced by the atomic current-world adapter. Only findings explicitly marked blocking become compilation errors; inventory, timing and capacity remain separate conclusions. Existing result constructors retain an absent summary rather than inventing proof.

### First future-window contact domain

The cardinal straight, unit-arc, constant orthonormal-frame, equal-floor case is now implemented in Core `StoppedBodyGeometry.straightContact`. This is an explicit supported domain, not a general affine/curved proof. It derives physical member length, internal connector gaps and contact connector lengths from captured actual configuration, encloses rigid-solver/arithmetic residuals, and requires separated forward contact for the entire head-window Cartesian product. The resulting full body/bridge interval feeds the same two-root output corridor. Runtime still requires a fresh host token, exact source membership/direction, both event windows and source envelopes, and containment of the actual contact/protected traversal.

Combined gate 99290 static suite: 16 green, including an actual straight future-contact check at all four window corners for the same-direction stationary source. Subsequent test edits extend this to opposite-facing stationary HEAD and explicitly reject overlapping extreme windows; these await the next gate. Non-cardinal, non-unit-arc, varying-frame or different-floor geometry is rejected with an explicit contact feasibility reason, and arbitrary future curves remain unsupported.

## 2026-10-22 finite runtime choices checkpoint

Gate 3055 confirmed the corrected opposite-facing HEAD fixture and all existing structural contact tests; both future orientations now exercise all four stopped-window corners. Gate 46394 additionally passed ordinary selected-stop continuation filtering (six branch/selection tests total) and both real Core source-branch/split/output-resolution variants. These are static/physical-frame proof tests, distinct from the root's Dispatcher and Bukkit factory acceptance.

Structural histories now include namespaced conditional decisions and selected-berth decisions, inherit through outputs, and merge only compatible participant histories. Split source evidence publishes exact output candidate keys. Aggregate structural GoVia retains exact ordered intermediate occurrences as distances from its retained end; the null-origin ordinary planner no longer fabricates a partial suffix before structural proof. New immediate-output selected-stop and aggregate marker fixtures remain pending the next coordinated gate. Full station selection at structural output handoff still needs runtime held-output selection/admission; static proof is not permission to release its hold.
