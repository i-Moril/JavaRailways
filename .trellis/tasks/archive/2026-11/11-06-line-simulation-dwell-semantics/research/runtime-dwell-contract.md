# Runtime dwell contract review — 2026-11-06

> Historical research, not the current implementation contract. The final user-approved PRD/design supersede the conditional and exact-close proposals below: enforce STOP minimum from actual arrival, include opening time, retain the existing preparation lead and real operation tickets. Custom programs may close early but cannot depart early. Do not add current-door-state queries, prepared-close objects, close-start gates, or submission-time revalidation.

## Scope and controlling requirement

Read-only source research; no product/spec edits, Gradle, commit, or deployment. Only this research note is written.

Latest user contract (provided in the parent dispatch) overrides the still-unupdated PRD questions: **minimum dwell is the entire interval from actual arrival through completion of closing the doors; apply it only to intermediate STOP calls, not ORIGIN or TERMINAL**. The previous diagnosis records existing behavior, not the desired product semantics.

Clarification from parent before closure: the user has not yet decided whether the minimum is only a timetable-generation constraint or must still be enforced as an actual minimum after a late arrival. Therefore removal of opening-based extension and endpoint injection is confirmed; all actual-arrival-based runtime enforcement recommendations below are **conditional on retaining a runtime minimum**, not a frozen requirement. Under schedule-only semantics, remove the runtime minimum deadline and use the planned departure plus existing real operation/safety gates; do not add an actual-arrival minimum behind the user's back.

## Confirmed defects in shared station runtime

All runtime references below are `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/StationStopProgramRuntime.kt` unless qualified.

1. **Wrong anchor and role scope.** Line 53 initializes `dwellDeadline=stoppedAtDispatcherTick+minimumDwellTicks` for every role. Lines 212–214 move it again to successful OPEN_DOORS completion plus the full minimum. Line 71 gates every role by that deadline. This adds opening time and applies minimum dwell to TERMINAL/ORIGIN; both violate the latest contract.
2. **Late arrival can close doors immediately, then wait with doors closed.** Lines 205–207 compute preparation due only from `plannedDepartureTick-preparationLeadTicks`. Lines 83–86 require arrival main completion but do not consider the actual minimum-dwell deadline. Line 159 can launch preparation in the same tick that arrival main completes. Thus, when the planned preparation deadline is past, ordinary `[OpenDoors, End]` immediately launches `[CloseDoors, End]` after successful opening, but line 71 continues holding departure until opening completion plus the whole minimum.
3. **Existing tests deliberately preserve this wrong behavior.** `StationStopProgramRuntimeTest.kt:51–66` opens successfully at tick 80, closes successfully by 82, reports all tasks complete, refuses release at 100 and accepts at 110 (minimum=30). This test must change; passing it is not evidence of correct dwell semantics.

Concrete source-derived late-arrival example (not a newly run test): actual arrival 1000, minimum 400, planned departure 900, door-close lead/duration 20, opening completes 1030. Current preparation is already due at 880, so closing starts at 1030 and completes at 1050; final gate waits to 1430. Desired normal-program timing is effective departure 1400, preparation 1380, close complete 1400. No independent opening-based dwell timer is necessary.

## Minimal timing consolidation

If runtime minimum enforcement is retained, use one time axis and one effective departure/hand-off deadline per invocation:

- STOP: `effectiveDeparture = max(plannedDeparture, actualArrival + minimumDwell)`.
- ORIGIN: only `plannedDeparture`; no arrival or minimum-dwell contribution.
- TERMINAL: preserve an explicit optional `plannedDeparture` as a duty hand-off deadline, if present; no minimum-dwell contribution and no departure-preparation entry. Without that explicit deadline, join arrival tasks and hand off immediately.

Preparation is eligible after the arrival main sequence has completed and `now >= effectiveDeparture - existingPreparationLead`; final release checks the **same effective deadline**, all tasks, successful door closure where applicable, and the existing physical gates. Remove the opening-completion reset and the separate movable dwell deadline. Deriving a preparation start from the one final deadline is not a second timer; it accounts for work that must finish by that deadline.

Do not mix dispatcher ticks and timetable ticks. Dispatcher provides `absoluteTimetableTick(run, relativeTick)` at `TimetableDispatcher.kt:6665–6666`, and timetable elapsed is dispatcher tick minus cycle start at :6662–6663. Convert once at invocation creation or pass actual arrival in timetable coordinates. Nonzero cycle-start regression is required; tests with both clocks beginning at zero cannot catch this error.

Retain the existing asynchronous, per-train door-close-duration query (:190–202) and its timeout/cancellation handling. It supplies the preparation lead; querying it is not an additional wait budget. Doorless/fixed-expression/TERMINAL branches currently avoid unnecessary duration queries.

### Important qualification: lead versus actual completion

The effective-departure formula is the minimal correct scheduling target for the ordinary program with a lead matching actual closing duration. It prevents the confirmed late-arrival premature-close case without another countdown. However, preparation is an arbitrary author program and `preparationLead` can be a constant or a larger expression. If a custom lead is larger than its actual program duration, doors can still close before effective departure and remain closed while the planned-departure gate waits. Conversely, long arrival/preparation actions or slow real tickets can complete late.

Do not claim that changing this formula mathematically guarantees the actual close-completion instant for every custom program. The final gate prevents early movement, not an already-completed door event. Preserve authored program semantics unless the user additionally wants a strict close-action scheduling rule; do not add a second post-close dwell timer to fake compliance. Regression should at minimum prove the default actual-duration lead case, plus explicit custom-lead behavior, rather than silently changing custom actions.

## Actual arrival must not mean runtime construction

`TimetableDispatcher.kt:3120–3125` receives BERTHED and saves the actual dispatcher arrival tick in `stationCall.stateEnteredDispatcherTick`. The station-program branch at :1558–1567 deliberately begins only when `dispatcherTick > stateEnteredDispatcherTick`. `beginStopProgram` currently passes its own current dispatcher tick at :4089. Therefore substituting `stoppedAtDispatcherTick+minimum` without changing the caller would still anchor minimum dwell one tick late for StationCall.

Minimal correction: pass the already-recorded BERTHED tick for the STATION invocation; use the actual detected stop tick for DESTINATION (`beginDestinationStop`, :2479–2495). Keep role-specific treatment for ORIGIN, which has no new arrival. Reuse the existing notification/field rather than inventing a new clock framework. `stateEnteredDispatcherTick` is mutable during later transitions, so capture its BERTHED value when creating the invocation, not read it again after state changes.

## Required gates: do not remove as “extra waiting”

- Actual stopped train and authority validation: Dispatcher :1531, :2484, :4070–4073, :4101–4103.
- Arrival-main-before-preparation: runtime :83; arrival can still have pending physical opening. Descendant tasks may overlap preparation, but all must join before departure (:59–63 and :123–127).
- Physical ticket completion, operation ownership, failure, timeout, cancellation: runtime :98–100, :140–155, :168–185, :223–237. An estimated door duration is not proof that the doors actually closed.
- Closed-door confirmation: runtime :73, :216; Dispatcher :4114–4119 and movement guard :6466–6474. TERMINAL may remain open, but subsequent movement cannot bypass closure.
- Actual rolling authority beyond the station: Dispatcher :1644–1657. Once doors/time/tasks are ready, unavailable authority may still legitimately delay movement. Do not replace rolling admission with whole-leg reservation.
- Explicit author `WaitDuration` / `WaitUntil` instructions: runtime :129–137. They have distinct semantics and must not be globally deleted as duplicate dwell. Remove only automatically generated waits that encode the same implicit dwell rule, if the authoring pipeline proves they exist.
- Explicit origin action schedule: Dispatcher :4436–4454; standalone future `At` tasks and next-trip departure schedule still matter. “No minimum dwell at endpoints” does not mean ignoring planned departure or user-authored waits.

## Duplicate paths and waits: what evidence says

### Shared program does not also run the old door/dwell branch

While `run.stopExecution != null`, Dispatcher :1529–1533 processes that runtime and returns. The non-program StationCall countdown at :1580–1603 and destination countdown at :1605–1623 are therefore **alternative paths**, not an additional countdown layered on this shared runtime. Do not blame them for the 400-tick second wait in the shared path, or delete them as a supposed compatibility shim without tracing callers.

The non-program paths nevertheless use a different contract: successful door opening invokes `enterStationDwelling` (:2391–2396), whose deadline is based on current tick (:2418–2421); destination equivalent is :2509–2511. Only after that deadline and next action tick does automatic closing begin (:1591–1599 / :1611–1621). These are live explicit StationCall/destination APIs with existing tests, not demonstrated dead code. If the newly confirmed minimum-dwell meaning applies to those API inputs too, they need deliberate alignment or routing through the shared owner, not a new third timer. Their standalone/terminal role inference must not be guessed from STOP-only line semantics.

### Rechecking a common absolute time is not additive waiting

Runtime final departure (:70–73), successor `At` checks, and initial movement scheduling (`startMovementPlan` :5734–5743) can inspect the same planned tick. Once that tick has passed, these checks cannot add another full duration. They protect generic entrypoints that are also reached without a station program; do not globally delete them. Retain actual movement authorization and door gates. No source evidence here establishes a second full dwell countdown after shared runtime completion.

Shared final processing has state hand-off overhead: :4118–4137 releases to READY_TO_DEPART for ordinary StationCall, then the earlier :1529–1533 branch returns. :1632–1657 grants departure on a following dispatcher pass. This accounts for discrete event sequencing, not another minimum dwell interval. Running-line sampling should distinguish “doors closed/ready” from actual movement departure and capture the actual events consistently.

### Terminal → reverse → next origin

Shared TERMINAL joins arrival, hands control to duty suffix (:4133–4136), preserves `stationDoorsSecured` (:4119), and does not run preparation. A following ORIGIN creates a fresh invocation (:4436–4454), skips its arrival entry (runtime :60, :67), and runs preparation against its own planned departure. Correct endpoint behavior removes both implicit minimum-dwell additions; it must not remove explicit reversal publication or origin door closure. Existing `movementDoorFailure` prevents moving after open-door terminal handoff. No new cross-run timer/shared dwell ledger is needed.

## Regression plan

`StationStopProgramRuntimeTest.kt`:

- Replace old opening-anchor test :51–66 with actual-arrival anchor and open+close included in minimum.
- Add late-arrival case above: opening completes while old plan is past, but no close starts until effective deadline minus actual closing lead; close completes at the minimum deadline, no post-close full dwell.
- Add early arrival (planned departure dominates), on-time arrival, minimum=0, doorless stop, actual opening taking longer than the available interval, and long/failed close ticket preventing release.
- ORIGIN and TERMINAL with nonzero incoming minimum must not acquire implicit dwell; retain explicit endpoint planned ticks and authored WaitDuration/WaitUntil.
- Add differing dispatcher/timetable epoch offsets.
- Keep async-main/descendant semantics :17–34, reverse/closed evidence :69–83, dynamic query :184–205 and :236–257. Extend dynamic lead case :208–222 with nonzero minimum/late arrival instead of testing only minimum=0.
- Explicit custom lead > actual preparation duration: document resulting earlier door closure and later release; do not mistake it for the corrected default-duration case.

`TimetableStationCallDispatcherTest.kt`:

- Actual BERTHED tick, not next-tick runtime creation, anchors STOP deadline; verify recorded arrival, close start/completion, and actual grant/departure separately.
- Preserve terminal optional hand-off :315–330, origin preparation-only :333–360, timed origin :363 onward, post-reversal origin :390–418, reversal publication :462–478, open-door store rejection :482 onward, and terminal explicit waits :539–548.
- Add terminal→reverse→origin with nonzero configured dwell to prove neither endpoint adds it and all door/authority gates remain.
- Non-program APIs: :700 onward, :887 onward, :936 onward, :975–1020 currently encode separate dwell timing. Decide their scope explicitly and update only with a traced contract; :1020 currently asserts closing starts at least dwell-start+minimum.
- Preserve authority breach :641 onward and failed door ticket :1253 onward; formula changes must not turn wall-clock completion into operation success.

## Summary recommendation

Confirmed: delete the opening-reset and endpoint minimum contribution. If the user retains an actual runtime minimum for late trains, one effective departure deadline plus existing preparation lead is sufficient; pass actual arrival using the already-recorded event. If minimum dwell is schedule-only, do not introduce that runtime deadline. Preserve physical gates and author tasks. Treat standalone no-program dwell APIs and arbitrary custom preparation leads as explicit boundary decisions, not grounds for adding a new timer or silently deleting unrelated state machines.

## Follow-up confirmation and CloseDoors action boundary

The user has now confirmed that a late arrival **still requires the actual minimum stop interval**, with no extra opening-time addition. This supersedes the conditional decision above. This subsection also refines the earlier claim that effective-departure scheduling alone is sufficient: it is sufficient for the normal matching-duration program, **not for arbitrary custom preparation**.

### Why moving preparation due alone is insufficient

Let actual arrival A=1000, minimum D=400, planned departure P=900, therefore E=max(P,A+D)=1400. With custom preparation lead L=200 and actual closing duration C=20, preparation starts at 1200. If its first action is CloseDoors, doors complete at 1220, violating the required arrival-to-close minimum despite no movement until 1400. Similarly, extra actions after CloseDoors make total preparation duration different from door duration; waiting for the entire program to finish cannot correct an earlier door completion.

The smallest common execution boundary is the existing **CloseDoors submission inside the shared station instruction executor**, not a new countdown or a deferred-success wrapper:

1. Capture actual arrival once; derive immutable minimum close-completion boundary `M=A+D` for STOP only. Derive E=max(P,M) for preparation scheduling and departure. M/E are pure values on the same time axis, not separately running timers.
2. Keep preparation due E-L so display/extension work before closing can begin as authored. Keep arrival-main and physical-operation exclusion gates.
3. Before any STOP CloseDoors action calls `host.start` (runtime :140–150), require `now >= M-C`, where C is the actual duration or a proven duration lower bound for this pending close operation. Before eligibility, leave the task on its existing instruction with no ticket and no physical-operation owner acquired. It is waiting to start a real operation, not pretending the operation is pending/successful.
4. Once eligible, execute the real close normally, and require its real ticket to succeed. Do not hold a successful ticket artificially until M; do not add a dwell period after closure. Explicit WaitUntil/WaitDuration before or after that action remain unchanged and are not reinterpreted as part of a new timer.
5. Apply the same instruction boundary to CloseDoors in arrival, preparation, and async descendants; applying it only to the default preparation list would allow custom placement to bypass the minimum. ORIGIN/TERMINAL have no minimum-close boundary. Explicit author actions may make actual departure later; they must not make the required close event earlier.

Use M rather than E for the CloseDoors action gate: the minimum is arrival-to-close, while P is a departure prohibition. Automatically forcing every custom close to finish at planned departure would additionally forbid intentionally closing earlier than a distant planned departure, a new policy not requested here. Default matching lead still targets E naturally.

### Existing duration query must not be treated as an exact current-state duration

`StationStopProgramRuntime.kt:190–202` currently fetches duration only when `preparationLead.requiresDoorCloseDuration` is true; :192–194 evaluates constant expressions with zero and never calls the gateway. TERMINAL exits immediately. A custom constant lead must therefore trigger a duration query independently when its STOP CloseDoors action needs the new boundary. Reuse the same asynchronous request/poll/cancel machinery, not an invented delay or an estimate based on the custom lead.

However, simply always calling today's API is **not sufficient to guarantee the minimum**:

- `Basic/.../api/operation/TrainOperation.kt:107–111` exposes a read-only *planning estimate*, with no exact-duration/lower-bound guarantee.
- `BukkitPlugin/.../behavior/CarBehavior.kt:58–61` supplies both the opening and closing plans.
- `BukkitPlugin/.../behavior/CarAnimationPlayback.kt:85–113` explicitly measures closing **after a simulated completed opening**. It initializes a synthetic scene map, measures opening, then closing; it does not read current `slots`.
- Actual playback at `CarAnimationPlayback.kt:41–60` instead uses the current scene/transform/active animation. Thus an arbitrary program, prior closure, reversal, or model animation action can produce a shorter actual close.
- Existing `SimulationTrainOperationGatewayTest.kt:431–445` is concrete evidence: query returns 38 ticks, but a first close without a preceding open completes after 600+699+1 ms = 26 ticks. In contrast the full-open normal case :380–402 matches 38 ticks. These are source/test assertions reviewed, not tests rerun in this subtask.

For a not-before-completion proof, subtracting an overestimate is unsafe: M-38 followed by actual 26 closes at M-12. The required C must be exact for the executed operation, or a **lower bound**, never merely an upper/conservative preparation estimate. Waiting for real success protects physical movement but does not undo a too-early close.

### Minimal robust adapter direction and limits

Keep the existing predicted open→close estimate for early preparation planning; at the CloseDoors execution boundary obtain a current-state close-duration value from the same playback owner used to execute the operation. This can be a narrowly extended query mode/method on the existing operation gateway, not another simulation engine or timer. Its owner calculation must use the actual current animation scenes and the same action schedule/quantization as execution; whole-train reduction must match real all-car completion (`SimulationTrainOperationGateway.kt:43–46`, `TrainExtension.kt:65–75` currently use max over cars). Do not silently repurpose the early preparation estimate to mean current closed-state duration before opening; that changes normal preparation timing.

This is a real boundary change to specify before implementation, not something a reviewer should patch mechanically. In particular:

- A cached value taken before opening/reversal or other model effects is not authoritative for the later close. Query at the instruction boundary; retain the result while the physical state is unchanged. Requery/invalidate after an intervening station physical operation. The actual train/side/operating end must match execution.
- Do not claim arbitrary extensions cannot alter animation state; if such effects can run concurrently, current-state measurement and execute must share an owner-stable boundary, or return a proven lower bound valid for those changes. There is no generic proof from the current planning API. A zero lower bound is mathematically safe but would force close to begin only at M, adding its entire duration and defeating the intended normal timing; it should not be introduced as a silent fallback.
- Pending query remains a read-only wait. Its timeout should start when that query is requested, not use `stoppedAtDispatcherTick` from hundreds of ticks earlier (:198), otherwise a late action query can time out immediately. This is reuse of request timeout semantics, not dwell timing. Failure/cancel preserves the existing stop hold and error path.
- Query readiness must not block unrelated eligible task branches. Do not reserve `physicalOperationOwner` while merely waiting for the not-before time; preserve existing exclusivity at actual submission.
- A no-door STOP has C=0 and still needs the actual minimum before departure; no invented door ticket/query. Endpoints have no minimum-close gate.
- Multiple open/close instructions must not reset M. Applying the same M to every close is simple and prevents an early close bypass; user-authored reopening after that can extend actual stop. A custom STOP that never performs an actual open/close needs an explicit contract rather than treating generic task completion as a fabricated door event.

### Additional regressions for this boundary

- Constant lead 200, actual close duration 20: non-door preparation actions may run at E-200, CloseDoors cannot start before M-20, completion at/after M, no second wait.
- Preparation with actions both before and after CloseDoors: preserve order and explicit relative/absolute waits; only the close action gains the shared not-before condition.
- CloseDoors in an arrival branch/async child cannot bypass M, and waiting for M does not seize physical ownership or stop unrelated child progress.
- Constant lead now queries duration once when needed; async pending/failure/cancellation behavior remains real and does not call execute prematurely.
- Full-open versus first-close/current-closed playback demonstrates why the planning estimate cannot certify the action boundary; verify measured completion, not only dispatcher release time.
- Reverse/model-change between prediction and close invalidates or refreshes actual-duration evidence; no stale side/end result.
- Real successful close and final readiness never receive a synthetic delay; negative/invalid duration or unprovable query fails explicitly rather than subtracting an invented number.

No implementation, spec changes, test runs, or commit were performed for this follow-up.

### Bounded implementation shape for current-state duration (additional source check)

It is feasible to compute the normal stable-slot case from the existing playback kernel without rewriting animation playback. Narrow touch points:

1. `Animation.kt:87–128`: factor/extend the pure scene-transition duration helper to share **all** source-selection rules with `activateToScene`. The existing `durationSeconds(scene,currentScene)` is not enough: actual activation uses `currentTransform` when no current scene exists and enforces `MIN_SCENE_DURATION_SECONDS` (:103–109), whereas the helper currently falls back to `firstTime`. Reuse this pure rule; do not create/register a speculative ActiveAnimation just to measure it.
2. `CarAnimationPlayback.kt:28–74,86–113`: add a narrow read-only close-from-current-state calculation. Seed local source state from the actual slot name, completed scene or active transform; for uninitialized slots use the same `initialScenes` resolution as `play` (:34–39). Measure the actual close action list, not hypothetical open+close. Keep the current planning estimate unchanged for the early lead. Include all delayed animations and sounds; do not sum parallel effects or omit the latest completing one.
3. `CarBehavior.kt:58–61`, `CarExtension.kt:243`, `SimulationCarBehaviorExtension.kt:90`: expose this close-only calculation using the existing declarative close plan. Do not evaluate arbitrary live behavior scripts speculatively or execute effects during a query.
4. Existing `TrainOperationGateway`/LIVE+SIM adapters: add a specifically named current-close query rather than changing `estimateDoorCloseDurationTicks`'s meaning. Each car calculation runs on its existing model/playback owner (LIVE uses region dispatch at `TrainExtension.kt:68–75`, SIM uses its existing owner). Aggregate using max across current train cars, side, and operating ends. The station runtime calls it for the STOP CloseDoors boundary even with constant lead, polls via its existing async invocation mechanism, then starts the genuine operation after the shared M-C test.
5. Units: gateway returns nonnegative Long **20 Hz ticks**. Playback/action configuration is in seconds; existing planning quantizes delayed launch and playback separately (`ceil(delaySeconds*20)+ceil(playbackSeconds*20)`, :101–102). Preserve separate boundaries; do not round their sum once. Tests must compare against actual dispatcher/owner-commit ticks, not only wall milliseconds. A different LIVE scheduling lag can complete later; it must not be used to manufacture a duration guarantee.

Owner consistency is the limited hard boundary, not an invitation for a new general evidence framework. A value captured while waiting must be re-read when eligible if its underlying slot/model/operating-end changed. For LIVE asynchronous owner dispatch, the measurement and acceptance of the matching close operation must be tied to that same stable source state; an unguarded world-thread query followed later by a model-owner execute is not atomic. Prefer a narrow source-state check at existing adapter operation submission, returning “re-evaluate” before any effects if stale, over executing early and withholding completion. Do not persist this observation or introduce a train-wide replacement admission system.

For exotic model plans that overwrite the same slot during delayed closing, the measured schedule must use the same launch ordering and source transition rules as playback. A source that can be concurrently changed by an arbitrary extension during the delayed action does not have a generally knowable exact future duration. Keep that limitation explicit: either the existing physical-operation exclusion covers such mutations, or reject an unprovable/currently-changing operation before effects with a clear diagnostic. Do not pretend a snapshot alone proves arbitrary future model behavior. This is a scope/contract decision for that narrow API, not permission to overhaul animation execution.

Acceptance must include both current stable states using real playback: already-open normal S100 close (38 ticks) and first/no-open S100 close (26 ticks), each constrained so actual **ticket success/door completion**, not delayed reporting, is no earlier than M. Include a same-animation transform-without-scene fixture to cover the helper discrepancy above, delayed-launch tick quantization, and a changed-source-before-submission fixture proving no early effects occur.
