# ATO decision hot path

## Goal

Reduce the per-tick cost of `VehicleControlExecutor.choose` without changing ordinary
driving or stopping behaviour. The measured 100 km preview costs 4.48 s, of which 98% is
ATO decision work rather than `TrainDynamics.step`.

## Scope

Two complementary fast paths, both valid only while every hard target remains far:

1. Nominal stopping model. `DrivingReferenceStrategy` currently evaluates
   `NominalServiceBrakeModel.stoppingDistanceMeters` for every ordinary handle whenever
   any zero-speed target exists, even when that target is tens of kilometres away.
   Compute one conservative upper bound over all candidates and skip the per-candidate
   models while the target is beyond it.
2. Per-candidate ATP. `VehicleControlExecutor` currently runs `candidateJudgement` for
   every ordinary handle on every decision. When the current emergency judgement is SAFE
   and every target is beyond a conservative protection horizon, select from the ordinary
   catalogue without per-candidate checks, then hard-check the selected command once.
   If that check fails, recompute with the strict per-candidate predicate. An unsafe
   command must never be executed without the normal hard check.

## Requirements

- No change to target geometry, stopping accuracy, hysteresis, planning reserve, ATP
  intervention/release semantics, or persistence.
- Fast paths must be conservative: a false negative only restores the existing slow path.
- The nominal upper bound must be derived from the strongest-traction ordinary candidate
  and include one maximum supervision interval of slack.
- Every selected command still receives the existing final hard judgement; the relaxed
  path falls back to the strict predicate before committing an unsafe command.
- Add unit regressions for the bound, far-target selection, and fallback safety.
- Keep the existing `ReferenceStrategyTest`, `VehicleControlExecutorTest`,
  `ReferenceProtectionIntegrationTest` and S100 experiment green.

## Acceptance Criteria

- [x] Far-target decisions no longer evaluate the nominal model per candidate.
- [x] Far-target decisions no longer evaluate ATP per candidate, but still hard-check the
      selected command and fall back to strict selection on failure.
- [x] Bound/fast-path unit tests cover far and near boundary cases.
- [x] `:SimpleATO:test` and `:BukkitPlugin:test --tests '*AtoStoppingAccuracyExperiment'`
      pass.
- [x] 100 km preview benchmark improves materially versus the 4.48 s baseline.

## Evidence

- 100 km synthetic five-car preview, three warm runs: 4.48 s baseline -> 1.47 s
  (3.0x). `choose` dropped from ~88 us to ~22 us per 50 ms step in the component probe.
- `SimpleATO` 737 tests, 0 failures (4 g test worker heap; default 512 MiB still OOMs the
  pre-existing dispatcher suite).
- S100 five-car 100 m / 500 m / 2000 m STOP, STATION_RUN and AUTHORITY scenarios remain
  byte-for-byte equal in ticks, speed, precision state and error; 500 m example 1343 ticks,
  67.15 s, -9.6e-5 m.
- The relaxed catalogue always commits through the existing final hard judgement; a failure
  restores strict per-candidate selection before commit. Near-target and protection
  integration tests continue to exercise the strict path.
