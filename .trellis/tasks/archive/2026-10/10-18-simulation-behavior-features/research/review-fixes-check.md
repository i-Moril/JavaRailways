# Independent review of the three lifecycle follow-up fixes

Review scope: the three user P2 findings only, in `codex/simulation-world-alignment` on `87fe3e45`. Compared the 48-source `ae862ac8` snapshot in `../simulation-review-fixes-baseline-20261020/manifest.json`, and Git HEAD for newly touched host-service files. Reviewed 12 changed/new source and test files, their directly relevant consumers, and the real Core destruction/structural-transfer paths. This is not a new whole-repository audit. No source edits, builds, index changes, commits or merges were performed by this reviewer.

Final verdict: PASS for the three lifecycle fixes and their affected-module validation. No unresolved product finding or pending gate remains within this follow-up review scope.

## Findings (fixed)

- File: `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt`
  - Issue: concurrent `clearLocal()` could remove an installing reservation before that installation changed to `sourceRetirementPending`. If both initial and shutdown retirement failed, the engine had to stay open, but subsequent `remove`/`clear` could no longer find it to retry.
  - Fix: independently identified and relayed to root; the signal implementer made `clearLocal` retain reservations and remove the exact identity only after cleanup takes the candidate's management gate and confirms retirement. Reviewed the resulting code and barrier regression, `clear racing a partial install retains failed retirement for the next clear`. Root owns execution evidence.

## Findings (not fixed)

No unresolved product defect found in the reviewed follow-up source after the reservation-race fix. Documentation synchronization was completed by root under its explicit ownership and rechecked: the behavior spec and threading-model prose describe optional test observation rather than permanent production effect recording; host API documentation distinguishes failed-install retirement from ordinary planned owner withdrawal.

## Behavior verified by source inspection

- Failed installation retires the exact epoch/inspection/planning objects before engine close. The host matches both owner and source object identity on the exact current World binding, preserving replacement instances with the same IDs, other owners and unrelated categories. Missing/retired bindings are no-ops.
- Restriction inspection failure or active safety state requires a successful affected-World stall before epoch/inspection callback removal. A failed stall retains those callbacks. Independent planning cleanup is still attempted; individual successful removals update ownership for safe retry. Normal planned withdrawal gates and infrastructure registrations are unchanged.
- Manager retains pending reservations and live engine resources on uncertain host detach. New install cannot take that reservation. Stateless fail-closed planning fallback contains no runtime/engine and is published only while the old exact reservation is still owned. Async old close cannot delete a new same-UUID reservation.
- Source replacement retains the existing admitted-epoch contract. Retirement does not introduce a global drain. Already-captured old epoch callbacks may finish; the existing synchronized engine evaluation/close and unavailable-rule projection remain the safety boundary.
- `SimulationCarBehaviorExtension` has no effect list or snapshot API. Nullable observer defaults to null; safe-call argument evaluation avoids allocating effect records without an observer. Existing animation/scene validation and accepted-effect completion remain active. Only test collectors retain optional history.
- Gateway no longer has `ownedCars`. Real `CoreTrain.destroy()` awaits exact `CoreCar.destroyOwnedResources()` extensions before unregistering; destruction attempts independent resources and retains errors. Coupling transfers cars without destroying them. Gateway close snapshots only currently owned World cars after worker termination and attempts every current behavior close independently.
- Existing and new tests exercise opt-in observers, null observer valid/invalid effects, exact-car split/destruction, retained failed destruction stages, independent current-car shutdown, partial install failure, same-UUID reinstall, repeated failed detach, concurrent clear, host identity/category preservation, disabled/global-halt cleanup and failed-stall behavior. These are source assertions until the current build results below are filled in.

## Verification

- Whitespace/lint: `git diff --check` passed for the current candidate. The affected JVM modules have no separate lint command in their quality sections.
- TypeCheck/compilation: the current focused run compiled Kotlin/Java and the public Java consumer successfully. First compilation caught a prohibited Kotlin member-extension callable reference; implementer replaced it with the equivalent lambda before the passing run. Reviewer did not contend for the sole Gradle token.
- Focused tests: directly inspected current-run XML before the separate RED experiment: `DefaultSignalEpochSourceServiceTest` 23, `SimulationTrainOperationGatewayTest` 14, `FullFeatureSimulationIsolationTest` 2 and `SimpleSignalsWorldRuntimeManagerTest` 159; total 198, zero failures/errors/skips.
- Fresh full affected-module gate: `/tmp/simulation-review-final-gate.log` reports `BUILD SUCCESSFUL in 3m 48s`. Independently parsed its final XML: BukkitPlugin 542 tests / 122 suites, SimpleSignals 647 / 59, total 1189 tests / 181 suites with zero failures/errors/skips. All 13 added regression cases were individually found: six host-service, four gateway and three manager tests. Historical 2491-test success belongs to the earlier candidate and is not reused as proof for the modified bytes; the 1189 figure is the fresh affected-module scope, not a claim that the entire repository was rerun.
- Regression RED proof: root temporarily restored only the old manager/gateway implementation, retained new tests/API, and preserved failing XML in `../simulation-review-fixes-baseline-20261020/evidence/red`. Reviewer inspected both failures: old gateway re-observed retired car destruction and threw `CompletionException` with `retired completion failed`; old manager never entered the exact-retirement barrier. Both selected tests passed in the prior current-source focused run. The temporary source was restored by root before this final byte check.
- API consumers: inspected the updated Java example's exact-source rollback call, the only concrete host implementation, Kotlin test implementation and proxy consumers; actual `compileSignalApiConsumerJava` passed in the current focused run.
- Packaging/API final gates: current `compileSignalApiConsumerJava`, `verifySignalModuleBoundaries`, `BukkitPlugin:shadowJar` and `SimpleSignals:check` all passed; confirmed task completion in the final gate log.
- Docs: current documentation `npm run check` passed; reviewed its output and the synchronized behavior/retirement prose.
- Source identity: after RED restoration and again after the final gate, independently checked all 52 paths in `research/review-candidate.json`: no byte drift, fingerprint `c781ed4f569c5bc2810d4e20faa465e32d1c8bb96f29adf330f741dc4807452f`. Earlier scoped hashes remain in `/tmp/simulation-review-independent-source.json`.

Runtime limits remain the existing task limits: no live Paper/Folia/plugin-classloader or GC benchmark proof; process-terminal unknown host failures keep upstream `RailwaySimulationHalt` policy.
