# Research: Tick 10026 delay accounting and duty 2 terminal/reverse semantics

- Query: Does duty 2 / 102P REVERSING at Tick 10026 prove a blocked reverse, and why is observed delay 1226?
- Scope: internal; read-only code, saved timetable, and existing offline replay log
- Date: 2026-11-06

## Findings

### Confirmed: the reverse has just started, not waited 1226 ticks

`/tmp/jr-rolling-global-replay/replay.log:1670` records `DelayExceeded(simulatedTicks=10026, thresholdTicks=1200, observedDelayTicks=1226)` and duty 2 snapshot:

- `state=REVERSING`, message `waiting for atomic ATO reverse preparation`;
- `scheduledActionTick=8800`, `lastLatenessTicks=1226`, `nextScheduledTick=9300`;
- reverse task `element-mw1waqpl-845wu` has **startTick=10026, endTick=null**;
- preceding initial task started at 20 and ended at 1207;
- `currentRunId=102P`, waypoint `kasaipark@1`.

`IsolatedTimetableSimulation.kt:188` chooses the maximum observed run delay and stops at `>= threshold`. Its `observedDelayTicks()` at :550 is `max(lastLatenessTicks, max(elapsed-nextScheduledTick, 0))`. Thus this failure is `max(1226, 10026-9300)=1226`, not accumulated waiting for reverse preparation.

`TimetableDispatcher.kt:4418` calls `recordScheduledAction` for TaskStart, then stores the actual start tick. :6652 records current elapsed minus scheduled tick. Same-time sequential actions within an active task skip reassessment. Reverse at :4729 records the same checkpoint, advances the cursor, calls `beginReverse`, and returns. `beginReverse` at :2754 publishes exactly the observed REVERSING/message; it must wait for later ATO preparation publication (:2770), then reverse direction and observe a following dispatcher tick (:2793–2802). This is the expected first-tick state, not evidence of blockage. Existing `IsolatedTimetableSimulationTest.kt:271` tests that a task beginning late reports lateness at TaskStart.

### Confirmed: the authored terminal dwell and reverse schedule already conflict

`/tmp/jr-rolling-global-replay/timetable.js:6` defines program2 as a single REVERSE action. Duty 2 at :65–72 plans:

- depart Hatchobori 2000; Etchujima arrive 3525, depart 3925;
- Shiomi arrive 5288, depart 5688;
- Shinkiba arrive 7240, depart 7640;
- Kasai Park terminal arrive **8738**, with **minimumDwellTicks=400**;
- standalone reverse task starts at **8800**;
- next operating run 109P origin departure **9300** (:76–77).

The terminal arrival-to-reverse interval is only 62 ticks. `StationStopProgramRuntime.kt:70` requires all arrival tasks complete AND the minimum dwell deadline even for a TERMINAL call. Its :214 moves the dwell deadline to at least OPEN_DOORS completion + 400. `TimetableDispatcher.kt:4117–4136` waits for that gate, then returns to authored duty suffix and only then starts the reverse task. Therefore even ideal arrival at 8738 cannot execute this reverse at 8800: at least 338 ticks of task-start lateness, plus door opening/dispatch timing, are inevitable under current authored inputs and runtime semantics.

This finding alone does not explain all 1226 ticks. Main-session movement/door trace is required to partition prior travel delay, signal/resource waits, and terminal processing. The hypothesis `10026 = door completion 9626 + 400` is consistent with code but **not directly confirmed by this subtask's existing log**, which did not trace duty 2's full stop sequence.

### Reporting/accounting caveats

`TimetableDispatcher.kt:7209` intentionally returns `nextScheduledTick=null` while `stopExecution != null`, and :6653 bypasses ordinary scheduled-action lateness updates within station tasks. Thus terminal work can pass the reverse-start deadline while delay reporting retains an earlier assessment; at the later TaskStart it jumps to the newly measured lateness. Investigate whether suppressing the external checkpoint during station tasks is intended UI/product behavior; it is not proof that physical delay is fabricated.

`TimetableHttpBridge.kt:708–715` reports `currentRunId` and the task timing active at failure tick. Consequently the displayed "first delay source / task" is a snapshot attribution, not a causal trace: a just-started reverse inherits previous travel/station delay. The current run label remains 102P until next run handling; do not state that Reverse caused the delay simply because it is displayed.

`nextDelayCheckpointTick` (:7109) excludes TaskEnd and same-time actions within an active task. TaskEnd (:4422) records end time only; it does not create a scheduled deadline. Existing tests (:195–289) explicitly preserve these distinctions. Do not reintroduce TaskEnd-as-deadline or count all task duration as lateness.

## Files found

- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulation.kt`: threshold and observed-delay reduction.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt`: task measurements, station lifecycle, reverse handshake, snapshot checkpoint semantics.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/StationStopProgramRuntime.kt`: terminal gate and dwell-after-door-open calculation.
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/simulation/IsolatedTimetableSimulationTest.kt`: existing task start/end delay contracts.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/TimetableHttpBridge.kt`: delayed run/task/state reporting, not root-cause attribution.
- `/tmp/jr-rolling-global-replay/timetable.js`, `/tmp/jr-rolling-global-replay/replay.log`: saved input and result evidence, temporary and not versioned.

## Related specs / external references

- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md`: reverse waits for physics publication; dwell and task lifecycle.
- `.trellis/spec/javarailways/simpleato/simulation.md`: isolated delay threshold and lifecycle.
- No external references needed; conclusions concern current repository and captured local replay.

## Caveats / Not Found

- No production, test, fixture, specification, Git state, server, or deployment mutation performed.
- No new replay launched by this subtask. Full movement delay breakdown belongs to main-session runtime trace.
- Reporting semantics and terminal schedule conflict are confirmed; they do not by themselves identify why travel arrived late or establish a new physical-control defect.

## Follow-up: controlled replay and complete timing accounting

Main-session read-only diagnostic runs on 2026-11-06 retained the current uncommitted position fixes. Only the temporary `/tmp/jr-rolling-global-replay` harness was edited; product code, timetable source, and server configuration were not changed. All runs use the same captured infrastructure, S100 template, gravity 9.80665, and prepared SimpleSignals definitions. The historical browser's selected profiles remain unverified.

### Original five-duty run, threshold 1200

Evidence: `/tmp/jr-rolling-global-replay/delay-all-duty-1200.log`.

- Hatchobori departure: planned 2000, actual 2001.
- Etchujima: planned arrival/departure 3525/3925, actual 3604/4034.
- Shiomi: planned 5288/5688, actual 5597/6027.
- Shinkiba: planned 7240/7640, actual 7927/8357.
- Kasai Park terminal arrival: planned 8738, actual 9597.
- Reverse task start: planned 8800, actual 10026; simulation stops with delay 1226.

Exact accounting: initial departure +1; four travel intervals +78, +200, +348, +142; three intermediate stops +30 each; terminal processing 429 ticks versus the scheduled 62 ticks adds +367. Total = 1226. Equivalently terminal arrival is 859 late, then the terminal-to-reverse difference adds 367.

### Same runtime with only duty 2, threshold still 1200

Evidence: `/tmp/jr-rolling-global-replay/delay-solo-duty2-1200.log`.

The replay contains only duty 2. Etchujima and Shiomi times are identical. Shinkiba arrival/departure are 7918/8348; terminal arrival is 9588; reverse starts 10017 with delay 1217, still exceeding 1200. Removing the other four duties improves the result by only 9 ticks. Thus other-train interaction is not the main cause of this failure; this comparison does not claim that it has zero effect.

### Bounded reverse-completion diagnostic (not acceptance)

Evidence: `/tmp/jr-rolling-global-replay/delay-reverse-diagnostic-10040.log`.

All five duties retained; diagnostic threshold raised to 20000 only for this run and maximum simulation length set to 10040. Reverse starts 10026, publishes reversed direction 10027, and completes 10028. Task timing records endTick=10028, then the duty enters 109P origin station tasks. The reported PeriodEnded at the diagnostic cap does NOT establish full-period acceptance or success under the original 1200 threshold.

### Running-line preview reproduces all four planned intervals exactly

Evidence: `/tmp/jr-rolling-global-replay/delay-line-preview-102p.log`.

Compiled the exact five destination IDs in 102P with the existing LinePlanner reset-candidate path (unique BA direction), computed the same length-weighted midpoint grades as TimetableHttpBridge, and called the existing IsolatedLineSimulator with the same S100 profile, gravity, and line maximum speed 33.333333333333336 m/s.

- Hatchobori–Etchujima: 76.25 s -> 1525 ticks.
- Etchujima–Shiomi: 68.15 s -> 1363 ticks.
- Shiomi–Shinkiba: 77.60 s -> 1552 ticks.
- Shinkiba–Kasai Park: 54.90 s -> 1098 ticks.

These exactly match the saved timetable's four planned intervals. This is a reproduced numerical match, not recovered provenance of the historical UI action.

Confirmed implementation gap: TimetableHttpBridge around 907–924 reduces each route to length, average grade, and stopAtEnd. IsolatedLineSimulator passes these to DetailedTrainTravelSimulator; around 95–100 it builds endpoint-only reference/protection constraints, not the actual route inspection/signalling/admission environment. Consequently this preview omits local infrastructure speed restrictions, actual per-car grade variation, signals/resource admission, and dispatcher/door lifecycle. The global trace demonstrably includes local infrastructure limits (e.g. duty 2 Tick 7000 anticipates a 10.523498 m/s restriction, while the corresponding preview leg peaks at 25.948163 m/s). No claim is made that one particular restriction explains every excess tick.

The schedule reset also assigns departure=arrival+fixedDwellTicks (`WebUI/frontend/src/authoring.ts:269`), whereas runtime minimum dwell extends through OPEN_DOORS completion plus that duration (`StationStopProgramRuntime.kt:214`). For this fixture, intermediate stop/departure intervals are 430 rather than the planned 400. The terminal task is separately scheduled just 62 ticks after planned arrival, below even the 400-tick minimum. These are scheduling/runtime-semantic differences, not a blocked reverse.
