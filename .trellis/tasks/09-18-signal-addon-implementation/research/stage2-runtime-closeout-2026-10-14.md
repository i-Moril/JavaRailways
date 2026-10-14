# Research: Stage 2 remaining runtime closeout

- Query: distinguish unimplemented Stage 2 runtime work from historical unchecked aggregate items; identify the smallest approved next batch after Core closeout.
- Scope: internal; targeted source and existing test inspection, not a new whole-module audit.
- Date: 2026-10-14.
- Evidence level: static source/test inspection only; no builds, Git mutation, SSH, deployment or game validation performed by this researcher.

## Findings

### 1. The task summary needs reconciliation, not a new runtime roadmap

`implement.md:3-21` is explicitly dated 2026-10-09. Its dated historical slices are not a current inventory. In particular:

- The generic guided-Wand checkbox at `implement.md:555` cannot mean that no tools exist: section, current-route recording, protection-primary, fixed-block-primary and attached-signal tools already have production implementations and subsequent recorded checks.
- Missing measurement, instance/preset UI, route deletion and route-indicator creation mapping UX remain real authoring gaps. Their exact interaction is not all decided; do not invent model variants/default behavior to clear a checkbox.
- `implement.md:495` says concrete flank/approach resources are required **when the first interlocking rule requires them**. No such concrete rule was found in this bounded inspection. This is a conditional future item, not authorization for placeholder resources or a generic locking framework.
- Core review closure does not prove Basic/SimpleATO/adapter semantic ownership review complete. Keep the project-wide review item separate from bounded readiness tests and known maintenance work.

### 2. The moving-territory post-exit safety mechanism is already present

Old `implement.md:492` still asks for a plugin-chosen moving-block post-exit boundary. The current owner contracts and production code implement the necessary approved policy through neutral entry clearance:

- `SimpleSignals/.../region/ProtectionRegionInspectionSource.kt:29-60`: one publication-owned route-binding compiler supplies the static authority floor and an `InspectionEntryClearance(entry, region exit)` per visit.
- Same file `:87-114`: a unique immediate fixed block supplies its route-local end; no successor returns the region exit. It does not recursively call another contributor.
- `SimpleATO/.../inspection/InspectionRuntime.kt:599-620`: the same train's coherent length and existing authorization stopping margin raise the floor to `exit + length + margin`, bounded for the request by route end. Clipping does not prove clearance.
- `SimpleATO/.../inspection/EntryClearance.kt:83` and controller integration check the currently reachable stopping boundary, not only possession of resources.
- `.trellis/spec/javarailways/simpleato/entry-clearance.md` is CURRENT and owns this algorithm; `.trellis/spec/simple-signals/backend/protection-region-authority-transition.md` explicitly describes the no-successor case, actual space, one forward scan and physical tail release.

Existing tests include `ProtectionRegionInspectionSourceTest:78` (no-block floor), `:143` (whole direct block), `:176` (internal stopping point), `:305` (contributor integration), and `ProtectionSignalControllerHandoffTest:69-85` (far downstream stop, too-short endpoint, chained entrances, following train despite grant). EntryClearance unit/runtime tests cover length, authority and occurrence behavior. These should be mapped onto the old aggregate validation checkbox; implementing a second plugin moving-boundary scanner would duplicate current behavior.

Caveat: the handoff test uses a stationary actuator and constructed secondary-occupant observations; it proves production constraint/signal composition, not an entire physically driven trip.

### 3. Actual remaining integration gap: stopped return journey plus fixed-block identity

The unchecked `implement.md:486` still accurately distinguishes complete integration from separate slices:

1. `SimpleSignals/.../block/FixedBlockStopContinuationTest.kt:123-164` uses real Basic registry/admission and the real block policy. It proves one shared token and one grant survive suspension/rebase, then releases the token by the reversed tail. However, train positions are constructed observations and there is no Dispatcher driving a real train.
2. `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulationTest.kt:108-133` really simulates forward movement, stopping, reversing and returning. Its fixture has ordinary origin/destination WayPoints only (`:154-175`), not block definitions or a protection region; therefore no shared SimpleSignals block token is asserted.
3. `SimpleATO/.../timetable/TimetableDispatcherTest.kt:716-850` covers TakeFromDepot, travel, StoreAtDepot, ledger availability and subsequent departure. But the helper `:2137-2158` uses `GrantingRouteAdmissionTestContext`, not real Basic resource admission; it cannot establish fixed-block release correctness.
4. `SimpleSignals/.../signal/ProtectionSignalControllerHandoffTest.kt:92-158` already composes real Core, Basic registry/admission, inspection/epoch runtime, Dispatcher and SimpleSignals. Its current actuator is deliberately stationary and its `collectContributions` is empty (`:115-118`). It is the closest existing test-side composition pattern to reuse.

Production reversal is already implemented: `SimpleATO/.../timetable/TimetableDispatcher.kt:2390-2490` reverses on the owner, waits for coherent reversed observation, removes old inspection bindings, protects the union of old/reversed routes, and rebases the retained grant before narrowing edit protection. Do not assume a missing feature merely because complete integration evidence is absent.

### 4. Smallest next approved batch

Add one bounded production-class integration regression in SimpleSignals tests, borrowing the handoff composition and the fixed-block admission adapter rather than introducing a test business framework:

- A straight track, one bidirectional block, and two operational stopping positions, with the origin representing a Depot stabling point outside the block and the turnaround destination inside it.
- A real physical train driven by the actual Basic `HandleTrainActuator` plus SimpleATO, real Basic registry/admission and `FixedBlockRouteResourcePolicy`. Use explicit small test control profiles, not scripted granted responses or a stationary actuator.
- Drive complete 50ms coordinator steps. Publish each real physical observation into Basic admission and the actual signal epoch; run Dispatcher only in the existing between-frame owner context.
- Execute TakeFromDepot -> forward GoTo turnaround -> stop -> Reverse -> return/StoreAtDepot. The intended returned route revisits the **same physical block in a new frozen-route coordinate/direction**, not two occurrences in one continuous bulb-line route. A protection region is not necessary for this missing slice; its repeated windows are separately covered already.
- Assert actual forward and reverse motion and actual zero speed at reversal; no teleporting to target positions.
- Assert the exact stable `simplesignals:fixed-block/<block ID>` token stays held at the internal stop, during release suspension, and after reversal rebase; preserve the grant ID rather than reacquiring the token under a new artificial identity.
- On the return trip, assert the reversed head leaving the block does not prematurely release it, while the real reversed tail clearing the authored entrance does release it. At final Depot stop assert ledger/stabling-point state, successful run state and no world Stall. Other still-occupied physical resources/grants may legitimately remain held at rest.
- Inspect observed state every frame for these facts; do not infer correct intermediate retention solely from final successful completion.

Likely file scope: one new focused SimpleSignals integration test beside the existing block/handoff tests, or a tightly scoped extension of an existing scenario. Existing production classes should require no new API. A demonstrated regression may justify a narrow owner-layer fix, but do not preemptively rewrite production admission/Dispatcher.

Why not simply extend the existing headless simulation? `SimpleATO/.../timetable/simulation/IsolatedTimetableSimulation.kt:382-390` hardcodes `collectContributions = { emptyList() }` in its private admission host, and its registry knows only physical resources (`:404-410`). Adding a signal-policy/provider hook solely to inject this test would expand production API/ownership. Also, the existing simulation reversal fixture's `KinematicTrainActuator` lives in SimpleATO test source, not a public reusable test fixture. Reuse the proven 50ms owner-step pattern, not a reverse module dependency or another smoke runtime.

Actual risk: this composition may expose a real resource release/rebase or controller/Depot lifecycle bug that its separated existing tests cannot detect. That is the point of this planned integration regression; no such new bug is claimed reproduced by this research.

### 5. Editor permits: partly complete, not a blanket unchecked feature

- SimpleSignals definition mutations funnel through `SimpleSignalsWorldRuntime.kt:1523-1736`, acquiring the public permit at `:1578`, rechecking topology and closing it in all exits. `SimpleSignalsDefinitionEditImpact` supplies old/new logical, placement and downstream impact.
- `SimpleSignalsWorldRuntimeManagerTest:2365` proves unrelated static/live dependencies do not block local changes; `:2414-2544` covers transitive/successor impact; `:2617-2969` covers all-device placement, indicator and signal deletion with static/live blockers. Admin command formatting is already implemented and tested (`:2310`, `:2331`).
- Host RailWand uses its public edit-permit adapter (`RailWandTopologyEditPlan.kt:72`) and complete transactions (e.g. RailWand `:3425`, `:5896`).
- Do **not** mark the full operational editor aggregate complete: `BukkitPlugin/.../http/TimetableHttpBridge.kt:551-565` and `:739-752` write `LineStore.put/remove` directly with revision checks, not a visible edit permit in those methods. `OperationalDestinationService.register` similarly updates derived stores. This static observation does not by itself prove a live applied plan is affected: the exact dependency semantics/callers must be scoped before changing behavior. It is a remaining owner/editor audit boundary, not a reason to insert permits into every store or launch the entire Basic review unannounced.

### 6. Lifecycle, persistence and packaging aggregates have extensive existing coverage

- `SimpleSignalsHostServicesTest.java:40` rejects every missing required service.
- `SimpleSignalsWorldRuntimeManagerTest:890-951` covers disabled/reloaded attachment continuations, asynchronous cleanup and unload during preparation; `:3491` covers corrupted definitions installing only fail-closed planning state.
- Host `DefaultInfrastructureResourceServiceTest:676` covers unregister rejection with active route resources; `:768` forced disable retains claims/stalls affected worlds. `DefaultSignalEpochSourceServiceTest:290`, `:334`, `:462`, `:596` cover forced disable, unload and callback cleanup. These are offline lifecycle behavior evidence, not live Paper loading/reload proof.
- `SimpleSignalsWorldDefinitionStoreTest:45`, `:189-268` already exercises strict current-model fixture, unknown/version/world rejection, corrupted primary/backup handling and atomic save. Manager attachment tests include orphan references (`:612`). A global unchecked persistence item is not evidence that these tests must be rewritten or a legacy runtime reader added.
- `build.gradle:47` implements `verifySignalModuleBoundaries`; SimpleSignals `build.gradle:111` implements `verifyPluginJar`, wired into `check`. CI `.github/workflows/build.yml:49-52` runs those checks. SimpleSignals host dependencies are compile-only (`SimpleSignals/build.gradle:34-37`); independent plugin JAR and embedded adapter assertions already exist.
- Re-run current source gates and inspect actual artifacts at final handoff; historical passing counts are not a fresh gate. No artifact/build validation was run in this research.

### 7. Physical return regression exposes stop overshoot

后续用户纠正：下文保留首次失败的真实记录，但其中“下一批先修动力包络”的优先级已被取代。测试的真实闭塞出口本来就在140米；应先修正授权路线被运营停车点100米截断的问题，实际取得完整区间，再分别判断停车精度与真实授权越界。不能把补齐正式固定闭塞物理授权称为掩盖超调。当前执行合同见[停车目标与授权终点分离](../../../spec/javarailways/simpleato/terminal-route-completion.md)。

Follow-up execution by the main session, after the initial read-only inventory:

- The new `StoppedReturnToDepotIntegrationTest` uses real 50ms Core steps, `HandleTrainActuator`, Dispatcher, Basic admission/registry and the fixed-block policy, not a stationary/scripted actuator. It does not exercise JS aspect evaluation or the live Bukkit host.
- The initial setup used a one-way home stabling point. Timetable validation also considers compatible stabling points as departure candidates; the home-to-turnaround route was unreachable. The fixture now uses a bidirectional home point, while the explicit Reverse action still determines this journey's return direction. No validator was bypassed.
- The next actual run stopped with the forward head at `100.32199829892238m`, beyond the target and original physical claim end at `100m`. The reversed body required `[90.32199829892238, 100.32199829892238]`, and Basic rejected rebase with `INVALID_ROUTE`. This is a reproduced failure, not a completed journey.
- Basic's contract is correct: `RouteAdmission.kt` describes rebase as coordinate rebinding only; `RouteResourceAdmissionEngine` checks the complete actual body against already retained resources. Its `0.005m` geometric tolerance is not a general stopping margin. Neither release suspension nor rebase may acquire missing resources, and the failed rebase preserves the old claim.
- Test braking/deceleration parameters use the same helpers as the formal `RailWorldFactory` composition. A source-level concern is that the service-brake reaction estimate accounts for steady deceleration/jerk but not necessarily current positive acceleration and the extra displacement during the transition. A short recorded dynamics trace is still required before attributing this particular overshoot to a unique numerical cause.
- Dispatcher currently accepts a stopped train after the target's lower completion bound without an upper overshoot bound. That explains why this journey proceeded to Reverse instead of reporting its out-of-authority stop earlier; it does not justify broadening the claim.

Next bounded batch: reproduce/trace the actual stopping trajectory, fix the demonstrated movement-side envelope/control defect at its owner, and add an explicit regression against successful mission completion beyond authority. Keep Basic resource coverage unchanged. Do not change the stop point, fake the observation/grant, disable the test, or add arbitrary extra resources simply to obtain green results. Until this is resolved the new test remains uncommitted WIP and Stage2 runtime closure is blocked by this known defect. The independent preset feature is validated from a candidate that excludes this unfinished test.

## Related specs / files found

- `.trellis/spec/simple-signals/backend/index.md`: current add-on startup, tool and lifecycle ownership.
- `.trellis/spec/simple-signals/backend/protection-region-authority-transition.md`: region/no-successor/fixed-successor policy and clearance handoff.
- `.trellis/spec/javarailways/simpleato/entry-clearance.md`: actual length-aware floor and one-scan movement proof.
- `.trellis/spec/javarailways/simpleato/simulation.md`: existing isolated-world owner stepping; its old test-matrix phrase “unsupported reversal fail-closed” is stale relative to current successful reversal test.
- `.trellis/tasks/10-09-stage2-code-review/research/basic-module-review.md`: distinct known Basic audit findings; do not silently treat all of them as the next Stage 2 integration batch.

## External references

None needed: this question concerns the repository's approved plan and current implementation rather than external railway standards.

## Caveats / Not Found

- No complete physically driven Dispatcher + SimpleSignals block-token + stopped Depot-return test was found in the searched existing tests.
- No tests were executed; cited assertions are existing source, not fresh green runtime proof.
- Full Basic, adapter and host code audit remains separate. No claim of complete editor-permit coverage or all lifecycle permutations.
- Production-scale behavior, actual Paper/Folia, player UX, client/resource-pack rendering and final current-JAR loading remain field-validation gates.
- One-time old development-world discovery/conversion/dry-run/apply/recovery is owned by its separate migration task. It is not justification to reintroduce runtime compatibility or postpone a clean-world formal test indefinitely.
