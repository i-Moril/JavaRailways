# Research: Basic infrastructure / route-admission complete partition review

- Query: 全模块审查 partition A：基础设施、资源准入、释放与相关公开值、测试/fixture；七维质量检查，优先实际安全缺陷。
- Scope: internal; Basic assigned files in full, selected Core/Bukkit/SimpleATO downstream references. No production writes, builds, commits or remote operations.
- Date: 2026-10-12
- Baseline: current workspace on `dev/signal-system`; source inspected before subsequent fixes. Stable CR IDs intentionally left to the main review ledger.

## Findings

### A1 — P1 confirmed static defect: reversal release index can release a still-occupied retained interval

`Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/PhysicalRouteResourceCompiler.kt:182-205` groups retained intervals by rail, sorts by **start**, but calls `lowerBoundByEnd` (`:355-363`), which assumes **end** is monotonic. That is valid for the disjoint intervals built within one physical compilation (`:224-235` checks this), but not for retained identities accumulated across rolling extensions. Registry and tail-release explicitly allow independent overlapping identities.

Minimal reachable lifecycle, using real engine inputs rather than forged registry/private cursor state:

1. Create a pure degree-2 loop with `TestTopologyRail(R,100)` and `TestTopologyRail(L,20)`; connect `R.B↔L.A`, `L.B↔R.A`. This contains no turnout, so repeated-turnout range rejection does not apply.
2. Freeze route `[R AB 0..100, L AB 0..20, R AB 0..100]`, length 220. Observe train tail 0/head 10 on R, speed 0. Request authority 140.
3. This claims exact `R[0,100]` (the first visit and repeated `R[0,20]` merge), with release boundary **140**, plus `L[0,20]` at 120. Same-compilation merging is `PhysicalRouteResourceCompiler.kt:293-327`; final repeated-occurrence release is `:237-282`.
4. Publish newer observation with tail 50/head 60 on first R visit. No old resource has cleared.
5. Extend authority to 150 on the identical frozen route. Suffix is repeated R local 20..30, so delta exact identity `R[20,30]` is appended with boundary 150; the old `R[0,100]` remains. Engine filters held resources by **exact identity**, not overlap (`RouteResourceAdmissionEngine.kt:440-472`). This is an intentionally supported shape, not malformed input.
6. Suspend release using the committed grant, reverse the stopped train, publish BA tail 60/head 50. Rebase to route `[R BA 40..100]` (canonical 60..0), desired authority **10**. Mandatory body interval `R[50,60]` is covered by old `R[0,100]`; engine coverage check succeeds (`RouteResourceAdmissionEngine.kt:736-744`, `:1015-1044`).
7. Rebase retained R index is `[(start0,end100),(start20,end30)]`. Searching end ≥50 probes element 1, finds 30<50, and returns index 2, visiting nothing. Both R entries retain default release boundary **0**. Correct boundary for long `R[0,100]` is **10**, and short `R[20,30]` is correctly 0 because it is outside the reversed body.
8. On the next newer **stationary** epoch tail progress is 0. `LiveClaimTailRelease.advance` (`:239-245`) makes both 0 entries eligible and removes the long still-occupied resource. Rebase preserved claim identity correctly; it installed incorrect release evidence.

Evidence tier: production control-flow proof plus an independently executed small numeric reproduction of the binary-search predicate, which returned `binary_search_start=2`, `visited=[]`, expected long boundary10 versus actual default0. **No repository regression was executed in this research partition**; the lifecycle above should be encoded and run before declaring a fix complete.

Minimal repair: reuse the start-sorted + prefix-maximum-end overlap index pattern already in `RouteBoundResourceCatalog.kt:241-272`, or use a simple correct low-frequency overlap scan if the chosen complexity is acceptable. Do not sort by end alone and keep the existing start-based early-break. Do not reject/merge retained resource identities, because their independent exact identities and registry ownership are intentional.

Regression suggestions:

- In `PhysicalRouteResourceCompilerTest`, compile an ordinary BA body `R 40..50` on a 100m rail, pass retained `[R[0,100],R[20,30]]`; independently assert long boundary10, short boundary0, unchanged identities. This directly catches the index error.
- In `RouteResourceAdmissionEngineTest`, execute the full two-rail loop / two grants / suspension / reversal sequence above. Assert exact retained identities before and after rebase; newer stationary epoch must retain long R. Then progress reversed tail to canonical50 and assert physical release only at route10.
- Include partially overlapping and equal-start retained intervals plus AB and BA traversal, rather than only disjoint/single-interval reversal fixtures.
- Update the index explanation in `route-resource-admission.md:500` to document the actual overlap-safe structure.

### A2 — P2 confirmed API lifecycle gap: Basic accepts ordinary authority extension during reversal suspension

`RouteResourceAdmissionService` KDoc (`api/route/RouteAdmission.kt:201-207`) explicitly requires a successful suspension to be followed by `rebaseAfterReversal` **before ordinary admission continues**. `RouteResourceAdmissionEngine.requestValidated` (`:276-301`) checks requester, owner, train, route and pending handle but never `reversalFromTrainState`. Only observation consumption checks this flag (`:818`).

Minimal input: obtain straight-route grant at authority20, suspend release while stopped, leave physical direction unchanged, then request same route authority40 using the still-current observation. The engine can grant40 and invoke ordinary `tailRelease.consume` during that request despite suspension. After physical reversal but before rebase, request can instead drive old-facing cursor into fail-closed Stall. Both violate the documented explicit boundary.

Downstream qualification: **current SimpleATO client already guards this** (`SimpleATO/.../admission/RouteAuthorityAdmissionClient.kt:87-92`), so this is not evidence that current Dispatcher normally triggers it. Public authenticated Bukkit binding forwards to Basic without an equivalent guard (`BukkitPlugin/.../api/internal/DefaultInfrastructureResourceService.kt:407-410`), and any in-repository/add-on caller may use that API. Enforce the invariant once in Basic; return `ROUTE_STATE_MISMATCH` before any cursor/provider/registry mutation. Add one real engine test preserving prior grant, claim, preparation count and suspended state. No new timeout/recovery protocol is needed.

### A3 — P3 / existing CR-020 extension: weak tests and test-only lifecycle methods

1. `CompiledRouteProgressPathTest.kt:312-322`: helper `assertProgress` **does not call** `cursor.update(position)` when expectedDistance==0. Thus the lower/upper-tolerance zero-progress assertions at `:75` and `:78` only assert initialization and never inspect the supplied test position. Rewrite to always execute/update and assert resolved state. Preserve these meaningful boundary cases; do not delete the entire test.
2. `CompiledRouteProgressPathTest.kt:250-264`: constructs impossible overlapping route-distance occurrences (second starts5 while first length10). `TopologyRouteProjector` always derives cumulative starts from previous end, so production cannot generate this shape. Candidate for deletion/consolidation into the already retained real-route monotonicity regression (`:234-247`); no need to keep a separate impossible-state branch test as a release gate.
3. `api/route/RouteAdmissionTest.kt:15-18`: only verifies two freshly constructed empty identity classes are unequal. Candidate to remove as default JVM identity behavior; real engine tests already prove wrong/unknown handle isolation, same grant identity through extension/reversal and repeatable terminal results.
4. `api/route/RouteResourceIntentTest.kt:16-21`: reflection checks that a single old field name `additionalResources` is absent. Weak architecture proof: another resource-bearing field would pass. Remove this legacy regression if public-source/signature review and consumer compile fixtures are the enforced boundary, or replace with a meaningful API shape check; do not present it as proof the entire intent is signal-agnostic.
5. Production `RouteGrantTailRelease.installCommittedSuffix/validateCommittedSuffix` (`:113-121`) and `LiveClaimTailRelease.installCommittedSuffix/validateCommittedSuffix` (`:183-216`) have **no production caller**. Engine uses `validateExtendedSuffix/installExtendedSuffix`. The former methods, `committedQueueTail` state and corresponding old suffix tests remain from an earlier staged implementation. Candidate to remove as one focused maintenance batch after checking no public contract promises them (both classes internal), preserving engine rolling-extension/empty-queue/safe-abort tests. The spec's long separate explanation of this old seam in `resource-allocation.md` should be removed with it. This is unnecessary parallel lifecycle surface, not a second production allocator.
6. `PhysicalRouteResourceCompiler.Compiled.controlledResources` is consumed only by one compiler test; production reads `admission` and already has topology-derived resource existence set. Small redundant result field candidate; do not confuse with used local controlled-resource computation or `PhysicalTopologyResources.controlledResources` which is production-used.

All of these are maintenance, not prerequisites before fixing A1. Most infra tests have useful independent ownership, refusal/no-partial-mutation, exact-resource or observed-value assertions; test count alone is not a deletion argument.

### A4 — P3 / existing CR-020: real-world infrastructure test teardown omits runtime binding

The shared Core `createTestWorld` owner contract (`Core/src/testFixtures/kotlin/.../train/TrainTestUtils.kt:16-19`) requires physics, runtime binding and tick teardown. Assigned real-world tests close physics/tick but omit binding release:

- `ManualTurnoutSelectionRuntimeTest.kt:28-33`.
- `LiveInfrastructurePreparationActuatorTest.kt:29-34`.
- `BulbLineAdmissionTest.kt:221-224`.
- All ten `finally` blocks of `PhysicalTopologyReplacementOrRemovalImpactResolverTest.kt`.

This is the same previously recorded CR-020 fixture-ownership family, not a newly numbered production leak. Batch fix the four files together using the existing supported binding cleanup pattern, preserve test bodies, and ensure a cleanup failure cannot skip later resources or mask the original assertion. No new universal fixture framework needed. Pure DTO/topology tests have no world lifecycle to close.

## Seven-dimension conclusion

- **Logic:** A1 is a real safety-evidence calculation error; A2 is a public lifecycle invariant missing at its owner. Other inspected claim conflict/atomic append/shrink, invalidation index, compile floor, bidirectional selection and pending READY/ABORT behavior are internally coherent within the specified serialized host context. Do not infer concurrent timer safety from these static checks.
- **Readability/maintainability:** compiler/projector/catalog and small immutable value types have clear duties. Engine is ~1089 lines but one state owner is justified; no wholesale split requested. Request and rebase contain duplicated contribution validation; shared small helper may be worthwhile only alongside touched behavior, not a framework/refactor gate. Obsolete test-only committed-suffix seam is clearer removal target than splitting the engine by line count.
- **Documentation:** owner specs explain non-trivial algorithms well, including atomic registry and occurrence progress. Rebase index claim assumes overlap-safe query while source is not; A1 repair must align it. Runtime KDoc says add-ons use the “eventual” service (`RouteResourceAdmissionRuntime.kt:26`), although actual bound service exists; stale wording only. Public grant values are detached but schema-/epoch-independent; this distinction is consistently stated. Duplicate old suffix explanation is obsolete per A3.
- **Error handling:** provider exceptions before reservation return typed denial; after unprovable side effects retain claim and Stall, which is justified. Wrong owner, stale observation, exact-root catalog and per-route topology invalidation guard distinct risks, not redundant paranoia. No broad removal of these guards recommended. A2 needs a direct current-state check, not additional fallback machinery.
- **Unused/legacy:** no old region allocator/window API found in assigned production. Remaining older internal suffix methods/result field are A3, not functioning alternative allocation system. `compileResolved` is used internally by `compile`; its old “transitional adapter” wording does not mean the method body is dead.
- **Consistency:** production uses Kotlin, Core API types, immutable copies and stable endpoint/resource identity. Test aliases/wildcard imports and fixture teardown differ between old files; batch normalize only if touched. Raw internal registry request being public outside `basic.api` is deliberate composition access and excluded from Dokka; do not make it add-on API.
- **Tests:** broad behavioral coverage for refusal, grant identity, release order, topology invalidation and provider lifecycle. No test/build rerun in partition research; concrete A1/A2 gaps and A3 quality candidates are listed rather than arbitrary reduction targets. Existing single-interval reversal tests explain why A1 escaped.

## File-by-file inventory / review depth

Paths below are relative to `Basic/src/main/java/work/art1st/javarailways/basic/` for production, `Basic/src/test/java/work/art1st/javarailways/basic/` for tests, unless prefixed otherwise. **FULL** means all lines read with behavior/spec or assertion review, not only rg hits. All inventories below are FULL static source reads; no runtime claim is implied.

### Production infrastructure (18)

- `infrastructure/CompiledRouteAdmission.kt` — union, duplicate preparation protection, one-footprint invariants.
- `infrastructure/CompiledRouteProgressPath.kt` — initialization, canonical projection, monotonic cursor/latch, extension.
- `infrastructure/CompiledTailReleaseQueue.kt` — deterministic order, exact identity, immutable queue.
- `infrastructure/InfrastructureCoordinationRegistry.kt` — dependency/edit exclusion, opaque tokens, acquire/append/shrink/rollback/close, lock ownership.
- `infrastructure/InfrastructurePreparationActuator.kt` — pre-resolution, bidirectional device operation, composite pending/abort/unsafe.
- `infrastructure/LiveClaimTailRelease.kt` — moving head, staged holds, exact registry evidence, obsolete committed-suffix seam.
- `infrastructure/LiveTurnoutSelection.kt` — current topology and live view proof, symmetric writes.
- `infrastructure/ManualTurnoutSelectionRuntime.kt` — temporary claim, wait, locking occupancy, finally release.
- `infrastructure/OccupancyQueryGeometry.kt` — same-rail sorting/merging and immutable query geometry.
- `infrastructure/PhysicalRouteDependencyCompiler.kt` — exact rails/node/diamond dependencies and empty routes.
- `infrastructure/PhysicalRouteResourceCompiler.kt` — physical floor, repeats, geometry, final clearance, reversal overlap index; A1.
- `infrastructure/PhysicalTopologyResources.kt` — deterministic turnout/diamond resources, replacement/addition/snapshot impacts, pure-loop anchor.
- `infrastructure/RouteBoundResourceCatalog.kt` — root publication, defensive anchors, prefix-maximum interval index, route hit mapping.
- `infrastructure/RouteGrantTailRelease.kt` — epoch/identity, project→shrink→commit, single Stall latch.
- `infrastructure/RouteResourceAdmissionEngine.kt` — complete request/progress/rebase/release/provider/requester/invalidation lifecycles; A2.
- `infrastructure/RouteResourceAdmissionRuntime.kt` — world binding/create failure/close, catalog refresh and service forwarding.
- `infrastructure/TopologyRouteProjector.kt` — cumulative projection, ordinary edge/turnout/loop transition validation.
- `infrastructure/TurnoutResourceIdentity.kt` — canonical sorted port identity independent of direction/revision.

### Production public values (10)

- `api/infrastructure/InfrastructureDependencies.kt` — stable structural keys and immutable dependency categories.
- `api/infrastructure/InfrastructureResource.kt` — overlap semantics, owner identity, exact footprint and typed results.
- `api/infrastructure/ManualTurnoutSelection.kt` — stable-port request and immutable typed diagnostics.
- `api/infrastructure/TopologyEdit.kt` — independent edit categories/overlap/blockers.
- `api/route/ActiveRouteAdmissionSnapshot.kt` — detached active state and released-prefix meaning.
- `api/route/ExpandedRoute.kt` — view-local metre semantics, defensive copy, detached ref.
- `api/route/PlannedRoute.kt` — caller revision value versus topology provenance.
- `api/route/ResourcePreparation.kt` — runtime ticket/state/abort contract.
- `api/route/RouteAdmission.kt` — opaque handles/grants, failure invariants, service lifecycle.
- `api/route/RouteResourceIntent.kt` — route-only input, immutable scalar descriptor and authority constraints.

Excluded by ownership: `api/route/RailOperatingDirections.kt`, `api/route/planning/**` and `route/**` belong partition B.

### Tests / test-source fixture (22)

- `infrastructure/BulbLineAdmissionTest.kt` — real Core preparation + synthetic coherent observations; long/short loops, authority ceiling and re-use.
- `infrastructure/CompiledRouteProgressPathTest.kt` — all cursor tests/helpers; zero-progress weak helper and impossible-state deletion candidate.
- `infrastructure/CompiledTailReleaseQueueTest.kt` — order/duplicate/copy/complete metadata contract.
- `infrastructure/InfrastructureCoordinationRegistryTest.kt` — full atomicity/owner/dependency/edit/append/shrink and concurrent validation tests.
- `infrastructure/InfrastructureTopologyTestFixtures.kt` — pure topology and progress-path builders, fixed lengths and symmetric endpoint connection.
- `infrastructure/LiveClaimTailReleaseTest.kt` — all release/hold/failure/old suffix tests and registry fixture.
- `infrastructure/LiveInfrastructurePreparationActuatorTest.kt` — real turnout writes, complete preflight refusal, heterogeneous tickets/abort.
- `infrastructure/ManualTurnoutSelectionRuntimeTest.kt` — wait/claims/occupancy/stale cases and actual topology fixture.
- `infrastructure/OccupancyQueryGeometryTest.kt` — exact sorted merged values.
- `infrastructure/PhysicalRouteDependencyCompilerTest.kt` — no known route, exact dependencies, illegal branch.
- `infrastructure/PhysicalRouteResourceCompilerTest.kt` — floor, held repeated turnout ceiling, contributions, physical/metadata identities.
- `infrastructure/PhysicalTopologyReplacementOrRemovalImpactResolverTest.kt` — all snapshot/addition/replacement/loop current-identity tests.
- `infrastructure/PhysicalTopologyResourcesTest.kt` — diamond identity/two anchors and endpoint clipping.
- `infrastructure/RouteBoundResourceCatalogTest.kt` — root unavailable/empty/stale, AB/BA, publication refusal, repeated hits and contact.
- `infrastructure/RouteGrantTailReleaseTest.kt` — stamp, cursor, exact registry failure and old suffix seam.
- `infrastructure/RouteResourceAdmissionEngineTest.kt` — all 1515 lines, mutable-ticket/coherent-frame helpers, pending/continuation/reversal/provider/claim/invalidation regressions.
- `infrastructure/RouteResourceConflictIntegrationTest.kt` — shared diamond, independent routes, no implicit region token, exact blockers.
- `infrastructure/TopologyRouteProjectorTest.kt` — coordinates, node identity, illegal/disconnected transitions, defensive outputs.
- `api/route/ExpandedRouteTest.kt` — collection detachment.
- `api/route/ResourcePreparationTest.kt` — unsafe diagnostic validation.
- `api/route/RouteAdmissionTest.kt` — failure/result identity and collection rules; JVM-only identity candidate.
- `api/route/RouteResourceIntentTest.kt` — constructor/descriptor/range rules and weak old-field reflection.

### Contract / shared testFixtures (3)

- `Basic/src/test/java/.../contract/CoreTopologyApiConsumerCompileFixture.kt` — FULL; separate module uses only Core API topology, compile boundary not executed test.
- `Basic/src/testFixtures/kotlin/.../api/route/RouteAdmissionTestFixtures.kt` — FULL; opaque process-local handle factory, intentionally test-only.
- `Basic/src/testFixtures/kotlin/.../testing/TestBasicWorldDataRegistry.kt` — FULL; shared Basic extension fixture ownership, weak-key/weak-value registration, no production fallback. Also relevant to partition B; no changes made.

## Related specs / call sites

- `.trellis/spec/javarailways/basic/index.md`, `api-boundaries.md`, `threading-and-publication.md`: package ownership, detached API and coherent owner context.
- `.trellis/spec/javarailways/basic/route-resource-admission.md`: physical floor, rebase, exact resources and index explanation.
- `.trellis/spec/javarailways/basic/resource-allocation.md`: registry exclusion, exact-identity release, older suffix lifecycle prose.
- Core fixture lifecycle definition cited above; prior `research/issues.md` checked for existing CR-020 ownership family and fixed observation work; those are not rediscovered as new defects.
- `SimpleATO/.../admission/RouteAuthorityAdmissionClient.kt:80-110,131-215`: existing caller reversal guard and rebase flow; selected sections read, not full SimpleATO module review.
- `BukkitPlugin/.../api/internal/DefaultInfrastructureResourceService.kt:390-440`: manual selection and authenticated direct service forwarding; selected sections read, not full Bukkit review.
- External references: none needed; all findings concern repository-owned algorithms and contracts, not recalled library behavior.

## Caveats / Not Found

- No Gradle tasks or repository regression executed, no gameplay/Folia timing, cross-region race, unload stress or performance measurements. Numeric trace is not presented as a production execution.
- No source/spec/shared ledger edits. This research file is the sole owned output.
- Entire Basic completion requires partitions B/C and module docs/build integration; this inventory covers only assigned files.
- A1 production-lifecycle regression is intentionally specified before repair; until executed it remains **confirmed static** rather than an executed whole-engine reproducer.
- General world Stall recovery reset, real-world physics admission pause and wider provider callback reentrancy were not fully audited here and are not promoted to new confirmed defects without cross-layer evidence.
