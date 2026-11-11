# Simulation error reporting and request timeout

User authorized fixing the known issues after read-only diagnosis. Continue the existing task; no automatic commit, deployment or archival.

## Evidence and scope

- Deployed sidecar inherits bridge timeout 5000 ms through TimetableWebUiSidecar, overriding WebUiConfig's standalone 10000 ms default. Program simulation's worker budget is 60000 ms. HttpTimeoutException at 09:31:02 UTC escaped BridgeClient.post and was mislabeled as bridge/draft storage IOException.
- Browser api.ts throws the raw HTTP response text; ProgramSimulationDialog renders its message, exposing JSON instead of a locale-aware explanation.
- The isolated simulation onReverse callback logged animation unavailable: rear_end_light at 09:31:00 UTC. This warning is not proof of timeout causality or of route failure. Investigate actual animation capability capture without swallowing genuine failures.
- Truffle optimization initialization warning near 09:30:57 is not itself evidence of a failed route.

Change owners: WebUI Java transport/error mapping; WebUI frontend API parsing/localization and simulation feedback; Bukkit simulation behavior capability parity. Preserve ordinary request timeout and existing dispatcher/sandbox execution. No new executor, persistence format, runtime migration, UI redesign or route-planning changes.

The animation investigation proved a model-package defect, not missing SIM capture: shared s100 actions demanded both front and rear end lamps on cars which only install one, or neither. Existing JS did not expose a read-only capability query. The necessary scope extension adds per-car `hasAnimation(name,scene?)` using actual model policy in LIVE and captured values in SIM, splits s100 end-lamp actions and conditions declarations/effects on actual capabilities. Missing-animation execution failures remain errors; no old-data compatibility path.

## Acceptance

- Transport timeout, unavailable bridge, storage failure, session/input and simulation failure remain distinguishable; client never presents a raw JSON response as user-facing error.
- API errors use configured zh-CN/en locale, safe fallback and actionable context. Stack traces stay in server logs.
- Simulation HTTP waiting budget exceeds the bounded worker budget; ordinary request behavior stays bounded as before.
- Capture matches live model animation naming/resolution, with a regression for the proven mismatch if found. Missing animation is not fabricated as successful execution.
- Focused backend/frontend regressions, complete affected test suites/build and independent cross-layer check. Report offline checks separately from live-game acceptance.

## Verification

- Final frontend:58 files/345 tests passed; TypeScript/Vite production build passed, bundle `index-BElFvuGJ.js`. Existing large-chunk warning remains.
- Final JVM: BukkitPlugin142 classes/676 tests and WebUI16 classes/51 tests passed, no failures/errors/skips; WebUI shadowJar passed. Logs: `/tmp/jr-simulation-errors-final-jvm.log`, `/tmp/jr-simulation-errors-final-frontend.log`, `/tmp/jr-simulation-errors-final-build.log`.
- Initial complete Bukkit run exhausted default test heap in an existing depot-placement geometry fixture. Complete rerun with temporary `/tmp/jr-simulation-errors-tests.gradle` setting only test maxHeapSize2g/maxParallelForks1 passed; no production/server JVM setting changed.
- Independent error review fixed login exchange bypassing central request, interrupted error-body read leaking native errors, and domain-queue timeout mislabeled as bridge timeout. Final gates include fixes.
- Independent animation review checked actual per-car ownership, direction scenes and unsupported-action rejection. Real six-car-type s100 regression covers reversal, end-lamps off, power-off, metadata and genuine missing-animation failures.
- Production UI served by explicitly local HTTP fixture: native controls selected reference train, depot start, direction and arguments, then fixed HTTP504 SIMULATION_TIMEOUT response. English/Chinese show localized timeout + log reference, no rawJSON. Screenshots `/tmp/jr-simulation-errors-browser/english.png` and `chinese.png`. Fixture is not a real physics/live-game run. Later login/body-read fixes are covered by final frontend tests/build.

## Remaining boundaries

- Domain diagnostic detail remains server text under localized summary. Ordinary components resolve locale when storing error.message; simulation dialog re-renders ApiError against current locale.
- requestId joins browser errors to WebUI logs, not original Bukkit stack logs.
- FOLLOW_PACKAGE model-only reload with unchanged JS text updates capability queries but does not rerun script-initialized declarations; changing installed end lamps may leave menus stale until behavior reload. Does not affect immutable captured simulation models or this s100 update (behavior text changes). No JS lifecycle redesign included.
- Deployment must update s100 model/behavior as well as JARs. Existing SNAPSHOT cars retain captured model/behavior; replacing JAR alone does not repair them. Any necessary existing-data replacement must be audited separately, without runtime compatibility.
- No live request replay, game acceptance, server data modification, commit, deployment or archival in this batch.

## Follow-up: configurable execution budget and internal simulation horizon

User requested removing the scattered hardcoded75s/60s timeout and questioned the player-facing timetable period in function simulation. Continue within this active task, preserving the preceding uncommitted fixes. No automatic commit/deployment/archive.

- Add a single server `timetable-http-bridge.simulation-timeout-millis` budget with an explicit default. All simulation workers use it; managed sidecar receives the same value. Its HTTP deadline derives from execution budget plus existing ordinary request timeout as response allowance, leaving ordinary requests unchanged. External mode documents matching environment configuration.
- Remove the independent function dialog period setting and hidden one-day end clipping. The existing dispatcher still needs an internal horizon; derive it from start and advance limit without introducing a new executor. Preserve actual timetable time context for event-wait resolution when invoked from a timetable.
- Config adds a field with an explicit default, so version stays unchanged under versioning/persistence rules. Function simulation request/response is transient, not a persisted schema; no compatibility reader or version bump.
- Validate nondefault budget propagation, deadline derivation and timeout cancellation, day-boundary/long function horizons, absolute/event time validation and current-owner result invalidation. Final affected suites and independent review remain required.

Follow-up completed locally:

- Single server config defaults60000ms, valid1..3600000, managed propagation plus external env/property documented. HTTP simulation wait derives from budget+ordinary timeout (default managed60000+5000=65000), without altering ordinary requests. Nondefault actual HTTP deadline and validation tested. Existing compile/capture/recheck/algorithm limits are distinct, not a total-endpoint deadline promise.
- Period removed from UI, context and transient request; start+limit safely crosses days. Internal wrapper covers absolute waits, parameters and branches. Real existing factory simulation with WAIT_UNTIL48000/budget100 compiled and returned LIMIT_REACHED without duration.
- Final frontend58 files/347 tests plus TypeScript/Vite build pass, bundle `index-D3VA1nNM.js`; Bukkit680/WebUI55 tests pass with2g test heap, no failures/errors/skips. WebUI shadowJar current JS/CSS entries verified. Logs `/tmp/jr-simulation-budget-frontend.log`, `/tmp/jr-simulation-budget-build.log`, `/tmp/jr-simulation-budget-final-jvm.log`. First JVM attempt caught a new test's incorrect sandbox class qualification; corrected and entire final gate passed.
- Independent follow-up check found no blocking bug. Confirmed relative STOP duration is not compilation-period clipped and all tick/sum boundaries match TS/Java/Kotlin. Existing line detailed algorithm has its own limit; worker budget does not remove it.
- Real final-bundle UI on local HTTP fixture accepted start23990/advance48000 with no period control or request field. Captured request `/tmp/jr-simulation-errors-browser/request.json`; screenshot `no-period-cross-day.png`. Fixture only verifies interaction/wire, not successful physics or live server behavior. Browser and fixture stopped.
- No commit, deployment, server modification or archival. Existing animation/snapshot and domain-error-detail boundaries above remain.

## User correction: per-request UI timeout, not configuration file

User explicitly requested replacing the configuration-file budget with a simulation UI setting. Delete the newly introduced simulation config/environment/property entirely; ordinary bridge timeout remains the response allowance. All three simulation APIs carry required executionTimeoutMillis, validated1..3600000. UI displays real-time seconds separately from simulated tick limits and sends the per-invocation budget to the backend worker; sidecar derives its HTTP wait from that same request budget plus ordinary timeout. No new persistence or compatibility path. Preserve preceding uncommitted fixes; do not commit, deploy or archive automatically.

Acceptance includes missing/invalid/overflowing budget rejection, actual nondefault worker/HTTP deadlines, all three user entrypoints, invalid field execution gating and stale result isolation, plus prior cross-day/no-period behavior.

UI correction completed locally:

- New config/default/environment/property additions removed completely; no runtime fallback or targeted legacy rejection added. Request `executionTimeoutMillis` is required for program/line/timetable worker budget and sidecar HTTP deadline derivation.
- Shared `SimulationTimeoutInput` displays real seconds, initial60, range0.001..3600, wire integer milliseconds. Program dialog owns its value; DutyEditor owns a visible shared temporary value for full timetable simulation/line timing reset. No document/local-recovery schema changes. Raw editing invalidates results; invalid drafts block all simulation triggers; old request failures are guarded for these operations without changing ordinary save error handling.
- Full frontend58 files/351 tests and TypeScript/Vite build passed. Full Bukkit679/WebUI54 tests passed with2g test heap, no failures/errors/skips; tests removed with withdrawn configuration path explain totals decreasing from previous gate. WebUI shadowJar passed. Logs `/tmp/jr-simulation-ui-timeout-frontend.log`, `/tmp/jr-simulation-ui-timeout-build.log`, `/tmp/jr-simulation-ui-timeout-jvm.log`.
- Independent per-request-budget review: no blocking findings; complete config/env removal, three producers/consumers, millisecond boundaries and stale/error guards verified. Existing multi-phase deadline boundary remains documented.
- Actual final production UI served by explicitly local fixture: chose directed depot start/reference, changed timeout to1.001 seconds, submitted and captured executionTimeoutMillis1001; periodDays absent. Invalid0 disabled Start simulation; Duties tab exposes timeout input. Screenshot `/tmp/jr-simulation-errors-browser/ui-timeout-invalid.png`, captured request `/tmp/jr-simulation-errors-browser/request.json`. This proves UI/wire, not live game acceptance. Browser/service stopped after check.
- Still no commit, deployment, server-data change or task archive.
