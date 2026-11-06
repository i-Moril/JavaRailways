# Independent task check — 2026-11-06

## Scope and controlling contract

Reviewed the current task diff against HEAD `4b918636` on `codex/ato-per-car-rebuild`, including current PRD/design/implementation plan, full dispatcher/runtime and authoring specifications, and relevant cross-layer/ownership guidance. The final user-approved simplification controls: custom programs may close early, but actual arrival + minimum, real tasks/door tickets and existing movement safety still gate departure. No new current-door query, prepared-close or submission-revalidation mechanism is permitted.

Coverage: current frontend request generation → API/proxy → Bukkit parsing/normalization/measurement preparation → shared world and station execution → physical sampling/readiness → ordered response → atomic Reset/history. Reviewed current task production/test changes, sampling failure/cleanup paths, and deletion consumers. Did not claim whole-module review. Unrelated dirty files were left untouched; no commit, deployment or server mutation.

## Findings (fixed)

### 1. DEADHEAD minimum exemption was lost at the request boundary

- File: `WebUI/frontend/src/authoring.ts`, `authoringLineSimulationRequest`; regression in `lineSimulationAuthoring.test.ts`.
- Before: local validation correctly exempted DEADHEAD, but the request still sent the document-wide passenger minimum. The backend accepts an already-applicable minimum and rejects any intermediate STOP whose fixed interval is lower. Thus a valid deadhead STOP with fixed 0 and document minimum 40 failed at HTTP despite frontend validation succeeding.
- Fix by this reviewer: validate the document scalar, then send zero for DEADHEAD and the configured value for PASSENGER. Extended the deadhead request assertion to require both `fixedDwellTicks:0` and `minimumDwellTicks:0`. No new backend compatibility or run-type policy.

### 2. Normal null origin offset was rejected as a nonnumeric value

- File: `BukkitPlugin/.../http/IsolatedLineSimulator.kt`, `LineMeasurementRequest.parse`.
- Before: `createRunFromLine` creates `stoppingOffsetMetersBefore:null`, and request mapping retains that value. The parser treated `JsonNull` as a supplied number and rejected ordinary newly-created runs.
- Fix coordinated with the active file owner (`exact_projection`): null is treated like absence/zero. Its parser regression covers absent/null/zero acceptance and retains the explicitly approved rejection of nonzero origin offsets. Reviewer did not edit that concurrently-owned file.

### Sampling correction independently rechecked

The line owner found that waypoint completion can precede real BERTHED confirmation (fixture: waypoint 890 versus BERTHED 915). Reviewed the resulting source and regression: PASS uses its waypoint event; STOP/TERMINAL waypoint events only establish ordered call identity. The sampler captures one pending frame in exact BERTHED state, then requires strict equality with the station-ready event's recorded arrival tick. Ordinary non-StationCall destinations use their actual same-tick waypoint frame. Arrival, target departure, leg interval, actual distance and stopping error are corrected together before successful output. No fixed tick compensation, tolerance, second execution loop or door mechanism was added.

The normal replay now compares PASS waypoint times and STOP/TERMINAL recorded arrival times, not two instances of the premature waypoint event. The same real signal feature and local rail limits remain installed.

## Findings (not fixed) / remaining acceptance boundaries

Post-review acceptance update from main: copied realS100 replay subsequently found a definite remaining defect. Door ticket succeeds at target3997 but Dispatcher reports ready3998, causing false `LINE_DWELL_INSUFFICIENT`. The bounded review above predates that trace and is not a completion approval. See main-verification.md; no tick tolerance or additional door mechanism has been applied.

- No further definite production defect was found in this bounded current-diff review after the fixes above. This is not full backend or deployed acceptance.
- Spec owner was notified that the newly updated `simulation.md` must distinguish PASS waypoint time from STOP/TERMINAL actual arrival; its earlier wording still said all arrival ticks came from waypoint events. Main owns the doc synchronization and final recheck.
- Nonzero ORIGIN offset remains intentionally unsupported and explicitly rejected per the approved boundary; changing initial placement/CHECK would expand this task.
- The line owner's actual execution found that a 2m intermediate stop offset can fail onward continuation (`not facing the planned route`). The final valid fixture uses 0.1m intermediate offset and 2m terminal offset. Do not claim arbitrary intermediate offsets are now supported. This reviewer did not redesign continuation.
- Existing `Line.validatePattern` forbids repeated station IDs. Ordered response handling covers repeated call identity without a station map, but the helper test does not prove HTTP accepts a repeated-station line. Expanding that model is outside this task.
- Initial REVERSE, explicit via competition and a controlled resource-wait duration comparison lack dedicated new end-to-end line regressions in the inspected candidate. The actual signal feature is present, but that alone is not proof of every waiting scenario. Main owns further offline replay and final acceptance decisions.
- Full JVM module gates, browser behavior and copied real-S100 replay are main-session work; this review does not replace them or a game/client test.

## Verification

- Lint/whitespace: `git diff --check` PASS. No standalone frontend lint script is configured.
- TypeCheck/build, independently executed after the request fix: `npm --prefix WebUI/frontend run build` PASS (`tsc -b` and Vite). Existing large-chunk warning remains nonfatal.
- Frontend tests, independently executed: `npm --prefix WebUI/frontend test -- --maxWorkers=2` PASS, **68 files / 501 tests**, 31.30s.
- JVM focused tests: not launched by this reviewer while the line worker held the Gradle lock. Inspected the current XML from its 14:34 UTC run: `IsolatedLineSimulatorTest` **2**, `LineSimulationMaximumSpeedTest` **2**, normalized `IsolatedTimetableSimulationFactoryTest` integration **1**; all **5 pass**, zero failures/errors/skips.
- Source boundary check: no task changes remain under Basic or Bukkit door/animation owners. Removed estimator/helper names have no remaining product code consumers; remaining search hits are historical task artifacts.

Only the two frontend files and this check report were edited directly by this reviewer. Final combined verification remains necessary after concurrent work ends.

## Follow-up review: factual ticket completion versus interpreter observation

The subsequent authorized repair was reviewed while the runtime owner implemented it; this reviewer did not edit production or run Gradle concurrently. The chosen bounded mechanism uses the existing isolated `afterTick` callback, after all effect commits, to record the first observed successful pending ticket for the specifically subscribed run. It does not advance instructions, mark tasks complete, alter door state, release the physical-operation owner, or grant movement. No Basic ticket API, door query, physics-clock conversion, tick reordering, or one-tick tolerance is required.

Correctness requirements shared with the implementation owner:

- Ticket success recorded at T may be consumed by the normal interpreter at T+1; END/natural task completion may retain T. Without such evidence, success observed at T+1 remains T+1.
- Pure AWAIT joins retain the maximum of the parent's previous actual work and the joined task's entire descendant completion tree. They cannot overwrite later parent work with an earlier child timestamp.
- Any subsequent real action, new ASYNC, WAIT_UNTIL release, or WAIT_DURATION start/resumption uses its actual execution tick, not the preceding ticket's timestamp. No side effects are backdated.
- Repeated observations preserve the first success time. Subsequent normal polling still enforces failure/cancellation; stored evidence cannot override a failed ticket.
- A true completion at deadline+1 must still fail the fixed-dwell check. A completion at the deadline with observation-only END/join delay must pass. The normal movement gate remains unchanged.

The first implementation exposed a definite ownership mistake on follow-up review: the outer world execution lock does not establish a physics between-frames scope. `step()` exits `processBetweenFrames` before effect commits, so its later `afterTick` call initially failed the Dispatcher ownership assertion. The implementation owner corrected the call to enclose only the record operation in the existing `withStructuralMutation` boundary after commits. Final source review confirms that it establishes the required scope, does not drain queued work or repeat Dispatcher execution, and releases the boundary in `finally`. The ownership assertion remains intact.

Final source inspection satisfies the completion-evidence requirements above. Every created task is included in the overall maximum, including detached descendants; AWAIT also preserves the parent's later work. The existing simulation wrapper catches observation exceptions and its enclosing `use`/`finally` paths still release subscriptions and owned resources. `git diff --check` passes.

Independently inspected current XML at 15:03 UTC: runtime **23/23** and line integration **2/2**, zero failures/errors/skips. The latter includes real two-tick close completion accepted at the deadline versus a one-tick-late close rejected. These results precede the final regression additions. Subsequently reviewed their source: new ASYNC after the recorded close finishes at 401 rather than 400; a parent's WAIT completed at 405 survives AWAIT of a child completed at 400; the real-door integration now asserts rejected completion is exactly one tick past its target. Their final test run and the final copied real-S100 replay remain under the implementation/main owners; this reviewer has not run Gradle. No additional production defect was found in this bounded final follow-up.
