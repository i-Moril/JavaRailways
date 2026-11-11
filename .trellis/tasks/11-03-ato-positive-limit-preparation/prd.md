# ATO positive-limit brake preparation

## Goal

Close the observed gap where the nominal positive-speed reference curve allowed a speed from
which no ordinary service-brake interval remained hard-safe. The simulation aborted at
`8.2394 m/s`, target `7.7505 m/s @ 7.2467 m`, because the nominal curve ignored the 0.933 s
ATP brake preparation.

## Requirements

- Positive speed targets reserve a conservative preparation interval before nominal
  deceleration.
- Reservation `T` covers the largest per-car command delay or electric-brake release time,
  1.25x the nominal air apply time for emergency build, and one supervision margin.
- Use the conservative constant-speed preparation envelope
  `v = max(vt, -aT + sqrt((aT)^2 + vt^2 + 2ad))`.
- Zero-speed stopping, terminal jerk and `stoppingDistanceMeters` remain unchanged.
- Direct `NominalMotionReference(acceleration, deceleration, releaseJerk)` tests keep their
  existing zero-preparation behavior via a default parameter.
- Add a regression that a positive limit is met without ATP intervention.

## Acceptance Criteria

- [x] Positive-target curve is lower than the immediate-deceleration curve and never below
      the target speed at distance zero.
- [x] Zero-speed branch is byte-identical.
- [x] `SimpleATO` suite passes.
- [x] S100 STOP/STATION_RUN/AUTHORITY ticks, speed and error are unchanged.
- [x] A positive-speed integration scenario reaches the limit without ATP.

## Evidence

- `SimpleATO` 739 tests, 0 failures.
- S100 500 m STOP/STATION_RUN/AUTHORITY: 1343 ticks (AUTHORITY 1335), 67.15 s / 66.75 s,
  error `-9.6e-5 m` / `-3.2e-4 m`, unchanged from before the reservation.
- New `positive speed limit is met without ATP intervention` integration test.
