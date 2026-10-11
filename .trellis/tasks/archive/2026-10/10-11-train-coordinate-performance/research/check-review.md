# Independent static review — fixed source snapshots

Reviewed `/tmp/train-coordinate-fixed/Core/src/main/java/work/art1st/javarailways/core/physics/train/{CoreCar,CoreTrain}.kt` against `/tmp/train-coordinate-baseline/`, plus workspace rigid-geometry and persisted-placement tests. Production files intentionally remained baseline during measurements. No Gradle/build/source modifications were performed in this review.

## Confirmed behavior

- Runtime leading bogie target remains exact: axle solve returns `positionFromBogies(headBogieNewLocation, result)`, never reconstructing the controller target.
- Axle residual uses 3D connector points including floorHeight/normal; internal tolerance is 1um. Coupler residual uses actual 3D end gap, expected following-car longitudinal side, and 10um tolerance. No exhausted-iteration/small-step success shortcut.
- Runtime nested candidate solves reuse one forward SolvePath. Negative correction stays on the current exact RailView and cannot traverse a reverse turnout. Existing negative-correction cap cannot recover behind the current rail start; that limitation was already present in baseline.
- Candidate geometry calculations are now pure with respect to geometryPhysicsDirty, preventing rejected candidate evaluation from selecting an unwritten inactive geometry slot.
- Head-anchored centre reconstruction is consistent across head/tail solve and persisted restore. Reversed offset mapping is consistent. Restore adjacency checks traverse tail-to-head and correctly use behind-car head connector and ahead-car tail connector.
- Existing path/facing/order/endpoint validation and failed-load cleanup remain. No schema field/version/legacy reader was introduced.
- New rigid regression exercises three cars, asymmetric offsets, varying banked floorHeight, fixed controller target, reverse, stop, precise restore, and finite-end movement. Persisted test adds internal-car shift rejection without disturbing axle spacing/order.

## Findings not fixed / limitations

1. **Deferred / excluded by explicit user scope: extremely tight curves. Search does not establish arbitrary-root completeness.** Expansion checks only two expanding endpoints and can skip a narrow positive island. Analytic circle R=3.001m, axle L=6m, prediction=6m, maximum=13m has valid chord roots at 9.2729700989m and 9.5828690079m. The sampled upper bounds are 6.9505720342, 7.9011440683, 9.8022881367, 13m; all residuals are negative, as are the lower bounds, so solveDistance returns null. This is a tight near-semicircle case beyond explicit R30 acceptance. The user subsequently explicitly excluded extremely tight curves as practically impossible. This finding is therefore not a blocker. Retain prediction, bounded local bracketing and safeguarded secant/bisection; do not add finite-difference/backtracking complexity or near-semicircle tests. If arbitrary curves are requested in a future task, revisit bracket discovery separately.
2. **Existing movement failure is not transactional.** moveTrainForwardFromController mutates the leading bogie and sequentially updates cars, then catches PhysicsException with motion clear + derail. publishPhysicsState commits remaining dirty geometry. A later-car failure can therefore publish some moved and some previous cars. Main confirmed scope preserves existing derail behavior and will narrow PRD/spec wording rather than add an entire-consist rollback contract. Do not claim this task provides movement rollback or guarantees valid coupled geometry after failed movement. The new residual checks do prevent a failed solve from being reported as successful.
3. **Failure coverage remains worth adding.** Current new regression validates successful residuals; it does not deliberately force a no-root/invalid candidate and prove finite termination + derail boundary. Also consider explicit zero-speed controller tick after a successful curved solve (the new stop test removes controller) if existing velocity tests do not already cover it. These are suggestions, not established test failures.

## Deferred verification

- Lint / diff whitespace: not run during snapshot-only pass.
- TypeCheck: not run; baseline measurement owns build output.
- Tests: not run; baseline-red evidence belongs to main, and fixed full regression remains required.
- Benchmark fairness review: harness independent-rail offsets must be identical in both arms; initial precheck reports must remain excluded; source hashes/run order/isolated build path must identify each arm. Performance claims cannot be made from static review.

## Actual-workspace follow-up review

Reviewed restored production diff, updated train-physics/persistence specs, final rigid test main-thread bridge, persisted corruption assertion, and stress harness. The production solver retains the original bounded algorithm; finite/nonpositive chord normalization now fails with BogieException. No additional ordinary-path defect was identified.

- The rigid test registers a ThreadGuardPlatformBridge on the calling owner thread for reversed finite-end maintenance placement and closes it in finally; worker execution is not classified as main thread.
- Interior corruption shifts both middle-car bogies by 0.1m, preserving spacing and order; expected exception text proves rejection is specifically from adjacent coupler geometry. Existing helper also checks world/rail/frame/mailbox cleanup.
- Updated specs accurately distinguish path arc distance, 3D chord, centre anchoring, runtime residual budgets, excluded extreme curves and existing nontransactional derail boundary. No obsolete arc-axle acceptance claim remains in the changed specs.
- Stress harness only translates independent physical lines; shared track remains unchanged. Translation preserves breakpoint arc lengths/tangents/normals and uses fixed 100m spacing versus 20m curve amplitude. Recorded harness SHA256 matches actual workspace: `3405343cc0ec4b1077ff8629c539861bf7862ad686fef7c3f378ab61e38ef5ef`.
- Task-scoped `git diff --check`: PASS.
- Build/type/test execution still exclusively owned by implementer/main; no competing Gradle was launched.

### Turnout shared-endpoint assertion follow-up

The asymmetric-turnout helper formerly required the first body traversal rail to equal frame.tail. Exact rigid placement can end precisely at a departing rail endpoint, while existing `traversalSnapshots` filters zero-length segments. The revised assertion permits only that exact equivalence: tail local arc equals rail length within 1e-9m, first body rail and facing equal the tail RailView's selected active forward continuation, and first canonical AB coordinate equals that continuation's START within 1e-9m. Frame head/tail rail/facing/coordinate assertions, the scenario's specific previous-rail expectation, repeated endpoint-to-bogie checks, and occupied rail removal assertions remain. This test adjustment does not accept alternate branches or omission of any positive-length segment.

## Final independent quality gate sign-off

**Reviewer:** dispatched trellis-check agent `/root/coordinate_review`.

**Disposition:** production code and focused regressions approved within the user-defined scope. No remaining blocking code findings. Main owns performance measurements, reporting and commit. This approval does not extend to Paper/Folia runtime capacity or excluded extreme-curve completeness.

### Evidence independently inspected

Main executed the isolated full gate; reviewer did not run a competing build. Reviewer parsed the actual `/tmp/train-coordinate-build/{module}/test-results/test/TEST-*.xml` files and matched saved `results/full-gate-counts.json`:

- Core: 316 tests, 0 failures, 0 errors, 6 skipped. RigidGeometry 4, PersistedPlacement 15, AsymmetricTurnout 8, PublicationBoundary 6 all pass. Six existing skipped circle-loop cases are in TrainUniformMotionTest; remaining four cases in that class pass.
- Basic: 385 tests, 0 failures/errors/skips.
- BukkitPlugin: 361 tests, 0 failures/errors/skips.
- TunnelBridgeBuilder: 31 tests, 0 failures/errors/skips.

`results/full-gate.log` confirms BUILD SUCCESSFUL and 41 actionable tasks executed, including compile/test-fixture tasks and Core Dokka. Dokka has 266 warnings concerning unchanged API/context declarations; other warnings concern unchanged Basic/Bukkit/test source, dependencies and Gradle deprecations. No warning names the changed production or regression files. This is a successful gate with pre-existing warning debt, not a warning-free build claim.

- TypeCheck: PASS through actual Kotlin/Java compilation in main-run full gate, independently verified from log.
- Tests: PASS with the six existing skips explicitly retained above.
- Lint/format: task-scoped `git diff --check` PASS, independently run; no dedicated Kotlin lint/checkstyle task is configured in root/Core build files.
- API/schema: no `core.api` changes, public signature changes, persistence field changes, schema version change, or legacy runtime compatibility branch. Production edits remain CoreCar/CoreTrain geometry and load validation.
- Scope: unrelated pre-existing dirty files are not reviewed or authorized for this task's commit. Main must continue selecting only task files.

One minor planning wording item was sent to main: design.md's older phrase about not publishing a partial solution as a successful frame should use PRD R3's precise residual-failure wording and explicit lack of whole-consist rollback. PRD and executable specs already state the correct boundary. No production change is needed.

## Measurement review (pending after-curved completion)

Before/after straight JSON configs/environment match exactly; before-curved uses the same matrix. Actual fixed production hashes match fixed-provenance, and corrected harness matches benchmark-provenance. Initial baseline-provenance records the original precheck harness, intentionally different; excluded precheck data must not enter comparison.

compare.py correctly computes medians of three per-run percentiles, delta milliseconds, percent change and before/after ratio, and labels the median-of-maxima aggregation explicitly. Three repetitions instantiate separate worlds/scenarios within the same test JVM; they are not independent JVM forks. Prefer 'three repeated runs' over an implication of independent process replication.

Noise and tails require explicit interpretation: after-straight shared500 p95 values are [20.595,24.499,24.508]ms (16% range/median), shared100 [3.099,3.232,3.653]ms (17.1%), mixed500 [22.816,23.426,20.863]ms (11.2%). Shared500 median improvement is only about5.2%, smaller than within-arm spread; do not call it robust without further confirmation. Grouped before→after execution also leaves run-order drift unseparated. All three current reports pass p95<50ms, yet absolute worst per-run maxima are before-straight60.903ms, before-curved72.917ms, after-straight51.277ms. The final report should expose absolute maxima and avoid a zero-overrun claim.

### Completed primary performance matrix

Independently parsed all four saved raw reports and comparison.json after after-curved completion. Each shape/arm has 18 unique scenario/count/repetition rows, with repetitions exactly1/2/3 and requested workers/cars/warmup/measurement counts. Configs and full captured environments match across before/after. Recomputed every median/min/max field, p95 delta and percentage; all match. after-curved Gradle log reports BUILD SUCCESSFUL. All72 primary runs have p95<50ms; worst absolute epochs are72.916994ms before and64.850616ms after, correctly shown in comparison.md.

Primary wall p95 results are straight improvement5.2–29.7%, curved isolated/mixed regression5.0–15.2%, curved shared100 regression23.7%, curved shared500 improvement8.1%. These are observations from grouped execution, not noise-controlled causal effects. In curved isolated/mixed, workerTotal p95 rises2.6–6.1%, coordinator p95 rises7.2–15.3%; phase percentiles are not additive and cannot alone attribute the full wall change to the axle solver. Curved shared100 wall+23.7% versus workerTotal−0.3% and coordinator+20% reinforces the need for cautious interpretation.

The report now explicitly states repetitions share a JVM, exposes absolute maxima, distinguishes smooth benchmark curves from R30 correctness, excludes old precheck, and avoids zero-overrun/server-capacity claims. Primary matrix/report arithmetic is approved. Main is adding targeted shared500 confirmation to evaluate the smaller/noisier gains; those supplementary runs require their own results review and must not silently replace the primary matrix.

### Supplementary shared500 confirmation review

Independently checked all four confirmation raw reports against their corresponding primary configs (only scenario/count narrowed to shared/500), full environment, repetitions1/2/3, pass status, and confirmation.json arithmetic. All84 successful measured runs pass the p95 budget. Final CoreCar/CoreTrain/harness SHA256 match fixed-provenance.

- Straight shared500 supplemental median changes28.164787→22.755085ms (−19.2073%). Direction supports straight improvement, while magnitude varies from the original−5.2% observation.
- Curved shared500 changes29.941001→30.188106ms (+0.8253%). The original−8.1% improvement is not stable across this check; interpret as approximately unchanged within noise, not a demonstrated speedup.
- Supplementary original-source maxima are74.955695ms straight and80.964809ms curved. Across all42 successful runs per arm, worst BEFORE is80.964809ms, AFTER64.850616ms. Sent main a correction because comparison.md's previous 'Absolute worst' section still covered only36 primary runs per arm. Exposing the84-run worst prevents a misleading overall tail claim.
- Failed confirmation-after-straight build/classpath attempt remains separately saved in compile-classpath-failure.log. Successful rerun raw is the only data included; build failures are not measured epochs or extra repetitions.

No new production findings. Supplemental measurement arithmetic and provenance approved. Main should make the overall maxima scope explicit and include the near-flat curved conclusion plus supplemental raw links in the generated report. Commit remains pending user confirmation under the workflow; this review did not commit, build, or modify production source.

**Final regenerated report approved:** comparison.md now includes all42 successful runs per arm in absolute maxima (80.965ms before/64.851ms after), explicitly rejects a stable curved shared500 improvement, reports approximately unchanged behavior at observed noise, and links all eight primary/supplemental raw reports. Previously requested reporting corrections are resolved. No remaining blocking code, test, provenance or report findings within agreed scope.
