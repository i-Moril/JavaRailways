# Main verification — 2026-11-06

## Scope and current status

Latest user-approved simplification is implemented: all task-owned changes in nine Basic/Bukkit door-operation production files and two dedicated test files were removed. No current-state door query, prepared-close object, close-start gate or submission revalidation remains. Runtime uses actual arrival, STOP-only immutable minimum, existing preparation lead and real tickets. Custom early close remains allowed; final movement gates remain.

Task remains in progress, without commit/deployment authorization. The earlier real S100 acceptance found a strict measurement rejection caused by observing completed operations one dispatcher tick later. The follow-up repair now records actual post-commit success without advancing execution; see follow-up verification below. No tolerance, stretched fixedDwell or removed door mechanism is introduced.

## Browser UI acceptance

Isolated agent-browser session `jr-dwell-20261106`, local Vite demo at127.0.0.1:4318. No remote server or persisted user document changes. Demo API results are mocks; this proves UI behavior only.

- AP101 original first departure1200; arrivals2000/2900/3700/4700, departures2100/3020.
- Fixed99 versus global minimum100: readable insufficient-dwell error with old-town and both values; original graph unchanged, Undo disabled.
- Fixed200, Demo Express: successful Reset retains first1200, writes2440/2640,4020/4220,PASS5740,terminal7400. One Undo restores all original times, Undo disabled/Redo enabled afterward.
- Injected a typed `ApiError(422,LINE_DWELL_INSUFFICIENT,...)` into the active demo API only: readable summary says original timetable unchanged, detail preserves station/target/completion; Undo stays disabled and Redo enabled. Initial injection into a distinct hot-reload module did not affect active API and is not counted as a failure-path test.
- Held a demo response pending: busy UI disables mutations and exposes one progressbar. There is no cancel button in this existing UI; cancellation was not falsely claimed as verified. Releasing the mock completes normally.

## Automated checks

- Frontend independently rerun after DEADHEAD request-minimum fix:68 files/501 tests pass, tsc/Vite build pass.
- Runtime focused18 + Dispatcher55 =73 pass; generated-source→Graal regression1 pass.
- Normalized real-signal line integration/request/speed group5 pass, XML confirmed. Includes actual BERTHED rather than earlier waypoint timing; uses test one-car profile, not actualS100.
- Java WebUiApplicationTest8 pass: authenticated raw request/success integer calls/422 details. Not physical engine evidence.
- Full JVM gate passed in4m6s: Core540 and Basic467 were up-to-date cached results; SimpleATO776, SimpleSignals677, Bukkit724 (1 skipped), WebUI66. XML totals3250, zero failures/errors,1skip. Signal Java public API consumer compilation also passed. Frontend build/install excluded only from this JVM command because the complete frontend build/test was independently run above. Existing JDK/deprecation warnings nonfatal.

Command: `./gradlew -I /tmp/jr-rolling-tests.gradle :Core:test :Basic:test :SimpleATO:test :SimpleSignals:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava :WebUI:test -x :WebUI:installFrontend -x :WebUI:buildFrontend --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g`. Final `git diff --check` passes. Automated gates do not negate the real-S100 acceptance defect below.

## Copied real test-server data — historical pre-repair evidence

Reproduction scripts, input hashes and logs: `/tmp/jr-dwell-replay-20261106/README.md`. Actual copied infrastructure revision450097, real5-car S100 and installed real signal rules. No remote calls or deployment.

- 102P first departure2000, fixed/minimum400. At first STOP actual A3597, target3997, actual door ticket already SUCCEEDED after commit3997. Dispatcher consumes completion and reports ready3998. The new readiness comparison incorrectly rejects this as insufficient dwell. Lead38 and close start3959 are correct; this is not a close-duration estimate error.
- The existing tick ordering runs Dispatcher before pending commits. Do not subtract1 from arbitrary events or change the shared tick phases without a separate justified decision. Preserved exact trace in `trace-old-single.log`.
- Original-timing independent102P completes tick9513 under original1200 threshold; actual terminal arrival9484 versus old planned8738. This is not proof of correct old times.
- Original five-duty global replay fails tick10928: `train head left the StationCall Mission route before its berth: hatchobori/2D`, duty4/103P step22. This is a separate unresolved existing execution issue, not a successful global run.
- Measurement did not succeed; no Reset/new-timing replay performed or claimed.

## Follow-up repair verification

The approved repair is limited to StationStopProgramRuntime, TimetableDispatcher and IsolatedLineSimulator plus regressions. At the existing afterTick point, the adapter enters the existing withStructuralMutation owner boundary without draining another operation batch. The runtime records first real ticket success for measurement only. Normal interpretation still consumes tickets, runs later actions/WAIT/ASYNC at their actual tick, and applies all original movement gates. END/pure joins aggregate completed work rather than treating notification latency as additional work.

Final real S100 measurement and actual frontend Reset followed by normal single-duty replay succeeded with the final withStructuralMutation scope. All four actual arrival ticks matched exactly:3597,5562,7842,9484; first departure remains2000 and intermediate targets3997,5962,8242 preserve400tick dwell. Replay Completed at9513 under the original1200tick threshold. Final logs: `/tmp/jr-dwell-replay-20261106/measure-final.log`, `measurement-result.json`, and `reset-single-final.log` ending in EXACT_COMPARE_SUCCESS. Original failure logs are retained, not overwritten. This does not resolve the separate five-duty route failure recorded above.

Final focused XML results: StationStopProgramRuntime24, TimetableStationCallDispatcher56, and real line integration2 all pass. Negative actual door completion is asserted to exceed target by exactly1tick; exact400 succeeds. New ASYNC executes at401, pure join preserves parent WAIT405 over child400, failed/cancelled tickets cannot use recorded success to bypass failure. Independent final source review found no further timing/ownership issues.

Final affected-module gate passed in2m54s: `./gradlew -I /tmp/jr-rolling-tests.gradle :SimpleATO:test :BukkitPlugin:test :BukkitPlugin:compileSignalApiConsumerJava -x :WebUI:installFrontend -x :WebUI:buildFrontend --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g`. XML independently counted SimpleATO783 and Bukkit725, zero failures/errors, one existing skipped experiment (1507 executed passes). Java public API consumer compiles; final `git diff --check` passes. This follow-up did not change frontend or Basic production code; their earlier results above remain historical rather than claimed reruns. No commit or deployment.

## Other explicit boundaries

Nonzero origin offset rejected because current initial placement/CHECK cannot express it; null/zero valid. Existing2m intermediate offset may fail Mission continuation;0.1m intermediate and2m terminal are tested, not a claim about arbitrary offsets. Current line validation still rejects repeatedstation patterns despite ordered result mapping being repetition-safe. The subsequent necessary acceptance additions below cover the previously missing initial-reversal/resource-blocked measurement comparisons.

No commit, push, deployment or persistence migration performed. Unrelated dirty files remain untouched.

## Necessary acceptance additions — completed

Only tests and task records changed in this follow-up, not production logic. Independent evidence mapping is in research/acceptance-audit.md.

- Backend final XML15:38:07/26: factory5 and input parser2, zero failures/errors/skips. Normal/reverse/blocked scenarios use real physical execution and official four-light signals. Reverse asserts original BA/non-reversed placement, actual head/tail exchange with unchanged occupied intervals before first departure, and exact normal replay event ticks. Blocked installs a real Core rail claim31..80m, observes RESOURCE_CONFLICT/no grant/stationary head, releases at400, retains departure100, includes at least300tick waiting in target-to-arrival measurement, then reproduces all integer events under the original1200 threshold.
- Missing profile assignment fails before resolver invocation; unknown selected profile propagates a controlled resolver error without fallback (not a real catalog integration claim). Supported offsets remain in all positive replay scenarios. Intermediate2m continuation failure is asserted as `not facing the planned route from c@one`; no partial success is returned. Existing LineStoreTest rejects repeated station patterns.
- Mounted DutyEditor4/4 and `tsc -b` pass. Real editor/history/Reset guards discard a delayed success after edit→Undo returns identical full JSON; generic error and typed504 leave document/history unchanged; current success applies once and one Undo restores the original. Only network/presentation surfaces are controlled. This is UI-state verification, not real HTTP timeout or browser input through disabled busy controls.
- Final backend command/log is retained at `/tmp/jr-dwell-replay-20261106/focused-acceptance-verified.log`. Main independently inspected the final XML and source; checker independently reviewed genuine resource competition and reversal. Existing full-module gates above remain historical passes, not falsely claimed reruns after test-only additions.

The proportionate technical acceptance is complete within these explicit boundaries; PRD checkboxes are updated. Task remains in_progress for authorized commit/finish, not automatically archived. Separate five-duty route failure is still unresolved.

## Bug analysis: completion versus consumption time

1. Root cause: cross-layer contract and implicit assumption (B/E), with an integration coverage gap (D). Dispatcher publishes readiness after consuming a ticket, but effect commits can succeed after that tick's Dispatcher pass. The line validator assumed these times were identical.
2. Why the preceding candidate failed: fixture success did not exercise the real S100 commit boundary. Existing close lead38/start3959/finish3997 were correct; adding exact-close machinery would target the wrong cause. The first new observation hook also lacked a legal between-frames scope; the existing synchronous owner boundary fixes that without draining requests twice.
3. Prevention: actual completion observation is separate from execution; exact-deadline and real late-one-tick integration regressions plus WAIT/async/join/epoch tests exercise the distinction. No tolerance or unconditional timestamp subtraction.
4. Systematic scope: the same timing issue applies to any asynchronous station ticket, not only doors; the bounded shared interpreter records all ticket kinds. Subsequent work remains timed at actual execution. No global scheduler or door API redesign is needed.
5. Knowledge capture: owner specs simulation.md and dispatcher-runtime.md now define these contracts. No spec template copy applies to this repository, and no commit is made without user authorization.
