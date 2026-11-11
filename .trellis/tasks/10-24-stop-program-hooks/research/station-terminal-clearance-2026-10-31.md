# Station terminal authority and stopping advance

User approved the0.3m station authority implementation after the standalone968-case experiment; the initially added0.05m braking advance was not authorized and is removed by explicit correction; no new task, commit, deployment or archive authorized in this batch. Existing task remains open. Preserve unrelated heap dump and experimental artifacts.

## Confirmed boundary

- Nominal station stop100m requests actual lawful route/resources to100.3m; fixed station-authority braking allowance0.3m.
- Station operational braking target is the existing nominal marker/offset, with no added advance. User explicitly rejected the previously implemented0.05m candidate; berth arrival tolerance does not shift the geometric target.
- Partial/pending resource grants and independent red/signal authority restrictions keep configured default5m allowance. Never infer a station-terminal concession from numerical proximity alone.
- Carry explicit resolved terminal station context through frozen selection/admission binding. Apply0.3 only when actual committed physical authority and direction permission cover the station's required end. Real Basic grant boundary remains intact. A complete protection suffix may be farther than the direction boundary; its direction constraint can use station0.3 only with the same complete station proof, avoiding accidental5m reintroduction.
- Replanning, same-binding continuation, reversal and formation handoff must clear/rebind station context; rejection/pending/invalid bindings cannot prematurely relax old constraints. Coupling/contact semantics remain independent.
- No Core/Basic/Signals/WebUI/runtime persistence changes, new executor or compatibility code. Entry-clearance constraint evaluation uses per-constraint allowance; conservative shared entry request floors remain unchanged.

## Implementation ownership

SimpleATO implementer owns production and unit tests. Root owns Bukkit full isolated execution regression, integration, specs and final verification. Independent reviewer checks context validity, failed continuation, raw grant identity and other safety constraints.

## Baseline reproduction

Added actual isolated-factory tests with real Basic resource admission, production HandleTrainActuator and Core physics.100m bidirectional platform is connected to a rail forbidden in approach direction. Station marker leaves0.5m legal suffix, tolerance0.1m. AB/BA both failed at simulation tick0 with the user's exact terminal-margin error before production edits.0.2m insufficient suffix was rejected as intended. Baseline log `/tmp/jr-station-authority-integration-before.log`.

Final matrix uses the real shipped S100 five-car package captured through profileFromTemplate, on300m platform rails; includes station completion, reverse/return, exact0.3m suffix acceptance in both directions,0.2m rejection and unchanged source world/direction state. This is separate from earlier controlled-MA accuracy experiment and is not live server/game acceptance.

## Verification

Implementation and independent review completed. Targeted7classes108tests passed. Full gates: Core538, Basic436, SimpleATO661, SimpleSignals675, Bukkit688 entries: 2997 passed, 1 expected opt-in experiment skip, zero failures/errors. Java signal API consumer compilation passed. All8 real-S100 station integration cases passed. Counts saved beside this note.

First joint gate found one additional zero-reaction Kinematic fixture overshooting a30m station mark by~0.337m; actual0.3m authority validation correctly rejected it. Only that TimetableDispatcherTest case now supplies0.1s reaction; the global fixture, original assertions and tolerance are unchanged. The two dedicated station-state fixture profiles also use0.1s. These proxies do not establish physical accuracy.

The full Bukkit run initially exhausted its default512MiB test heap while allocating an existing PrimeCurve fixture. Investigation with2GiB exposed a pre-existing PacketEvents API initialization dependency in EditorGlowMetadataTest. Its4 methods now use the existing DisplayAppearanceBackendTest packet-version fixture, moved unchanged to a package-level internal helper; original assertions and cleanup are preserved. Final Bukkit gate passed with2GiB, maxParallelForks1 and forkEvery0 (original shared JVM lifetime), via `/tmp/jr-station-authority-bukkit-shared.gradle`. No product/server memory setting changes. Final log: `/tmp/jr-station-authority-bukkit-final.log`.


## Historical measurements before removing the unauthorized0.05m advance

Saved two new CSV files beside this note; earlier pre-change experiment data remains intact. The200ms-step matrix has288 cases (96 each ordinary STOP, STATION and AUTHORITY), S100 five/ten cars, AB/BA, flat/plus-minus35-per-mille grades,2/10/25/33.3m/s caps and30/500m approach lengths. Additional4km high-speed runs contribute6 cases. All294 stopped with no stall, derailment or post-stop drift. All98 station cases emitted BERTHED.

Station final geometric error was -0.1008301102 to -0.0771555655m in the matrix; high-speed station cases were -0.0976375573m. The0.05m fixed advance is the braking target offset, not a guarantee that final head position is exactly5cm early. Existing arrival handling accepts zero-speed frames within the configured0.1m tolerance plus1mm position epsilon; at200ms the controller can accept a temporary stop while brakes release, about8-10cm before the nominal marker. No new precise-docking algorithm or tolerance change is included.

Ordinary STOP remained within approximately0.00022 to0.01709m beyond its marker; AUTHORITY remained approximately4.986 to5.001m before its raw boundary. CSV advance_m is the explicit input mission offset and does not encode the station controller's internal0.05m advance.

This opt-in harness uses controlled/pre-granted authority, homogeneous straight grades and experimental connector/bogie geometry. It does not prove grade-transition, curved-track, other-model, sudden-authority-contraction or live game behavior. The separate8-case isolated factory integration uses the shipped S100 profile and real Basic admission.

Task remains in progress. No commit, deployment, archive or live game acceptance in this batch.


## User correction: remove5cm advance

User explicitly requested cancellation of the unauthorized fixed5cm stopping advance. Remove the controller/profile candidate and test that station StopConstraint uses0.0. Keep station authority extension/margin0.3m, independent red/resource5m constraints, explicit marker offsets and arrival acceptance unchanged. The294-case measurements above belong to the superseded5cm candidate and do not validate the corrected target. No commit/deployment authorized by this correction.

Correction verification: StationCallRuntimeTest5/5 and TimetableStationCallDispatcherTest40/40 passed; TimetableDispatcherTest56/56 passed after one selected-stop Kinematic fixture was given0.1s reaction lookahead (same as the existing adjacent fixture), without changing any assertion or production behavior. The initial zero-reaction fixture exceeded its real0.3m authority by about0.016m; production was not changed to accommodate it. Bukkit IsolatedTimetableSimulationFactoryTest39/39 passed, including all8 shipped-S100 short-suffix station cases. Logs: `/tmp/jr-remove-station-advance-tests-offline.log`, `/tmp/jr-remove-station-advance-dispatcher-final.log`, `/tmp/jr-no-station-advance-integration.log`. Independent focused review found no remaining fixed station advance or lost explicit offset; diff whitespace check passed. No fresh294-case accuracy claim is made for this correction.
