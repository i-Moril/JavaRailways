# Review P2: failed signal installation retirement

## Confirmed path

LIVE installation registers signal epoch, route-planning and inspection source categories in sequence. Earlier `unavailable()` cleared complete owner categories using planned replacement calls; each could fail independently or address a later same-UUID instance. The final failed-install cleanup then closed the candidate's independent JS engine regardless of host detach success.

## Implementation ownership

This worker changes only `SimpleSignalsWorldRuntime.kt`, `SimpleSignalsWorldRuntimeManagerTest.kt` and this report. Root's host-retirement worker owns the public exact-identity retirement API, host implementation and host regressions; root owns shared specs/task metadata. All original candidate WIP preserved. No build, stage, commit, merge or deployment by this worker.

## Resulting behavior

- `unavailable()` now only reports the issue and returns its install result. Failed-install rollback runs while the original runtime management gate is still held.
- The host receives this candidate's exact epoch source objects, inspection sources and route-planning binding. Host retirement precedes any engine/model close or release of the reservation.
- The existing independent route-planning fail-closed fallback remains after invalid definitions. Its callback captures only the install outcome, with no candidate or engine reference. It is installed only while the directory still owns the failed candidate identity.
- `sourceRetirementPending` is volatile because directory-only readers and local-management writers use distinct monitors. If host detach throws, the closed candidate and engine remain reachable in the directory. Reinstallation reports unavailable rather than orphaning those resources.
- Explicit `remove()` and `clearLocal()` retry pending retirement before resource closure. A failed retry retains the reservation/engine; repeated clear after the host recovers closes them. Successful cleanup preserves the original independent asynchronous engine/model close and error aggregation.

## Regression coverage added

1. Existing route-install and inspection-install failure cases now require exact retirement and unchanged independent fail-closed routing, without broad empty category replacement.
2. A real compiled engine is open at exact rollback. Holding its actual engine monitor delays only old asynchronous close while same-UUID reinstall succeeds; after releasing that monitor, old engine closes and replacement callbacks/engine survive.
3. A retirement exception is reported in diagnostics and the existing failure reporter. Its candidate engine and registered sources remain reachable through refusal of reinstallation and failed remove/clear. Lifting the injected host failure and retrying clear detaches the source and closes the engine.

## Validation

- `git diff --check`: passed during implementation.
- JVM/API compilation and focused/full regression: root owns the shared Gradle token; pending at static handoff.
- Host identity/other-provider/domain-requirement and active-world stall tests are owned and reported separately by the host-retirement worker.

## In-flight epoch boundary retained

The existing host replacement contract lets one already-admitted epoch finish its captured old source snapshot. `SignalLogicRuntime.evaluate()` and `close()` already share the exact runtime monitor, and `evaluate()` checks open state before creating a Context. Thus no actual JS starts on a closed Engine. An old, already-captured callback arriving after closure is converted by the existing signal rules path to Unavailable; retirement does not add a global epoch drain or change exception classification. Persistent host registrations are detached before closure. A host world missing its exact topology keeps the prior no-fallback behavior.

### Static follow-up before focused execution

A concurrency edge was reported to root after the source freeze: `clearLocal()` can remove an in-progress uninstalled directory entry while waiting for its management gate; that installer can subsequently set `sourceRetirementPending` after host detach fails. If clear's retry fails too, its resources need the retained directory entry for another explicit retry. Proposed narrow correction is to defer identity removal for all clear entries until the management gate confirms detach success. Root approved the narrow correction after the first focused build stopped before SimpleSignals compilation. `clearLocal()` now retains all closed directory identities; `closeRuntimeResources()` waits for the candidate management gate, retries detach and only then removes its exact identity before resource closure. The new barrier regression holds real route registration while clear starts, makes both detach attempts fail, verifies the original engine/reservation remain reachable, then verifies the next successful clear closes them. Product/tests are frozen for root execution.
