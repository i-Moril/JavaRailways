# ATP validation cache and allocation reduction

## Goal

Remove duplicate ATP validation, reuse candidate judgements, and stop per-candidate list allocation in the executor hot path

## Requirements

- Remove per-device `listOf(...).any` allocations from ATP validation.
- Validate the source state once per `afterCandidate`; do not revalidate the derived prefix.
- Shift target distances in place instead of allocating a shifted target list per candidate.
- Reuse per-`choose` judgement maps without `Pair` keys; clear them at call start.
- Reuse a reserve-safe held judgement as the final no-reserve judgement only when the
  commanded command is unchanged.
- Replace the per-tick constraint `List<Pair>` signature with an allocation-free 64-bit hash.
- Skip the per-tick nominal held-distance model while the nearest zero stop is far outside
  an ideal-distance multiple; keep the 0.2 s decision and per-tick hard certificates.
- Preserve every ATP status, witness, planning-reserve and intervention semantic.

## Acceptance Criteria

- [x] Full `SimpleATO` suite passes.
- [x] S100 500 m STOP/STATION_RUN/AUTHORITY ticks, speed, error and precision are unchanged.
- [x] 100 km preview benchmark improves from the 4.48 s original / 1.47 s previous baseline.
- [x] No per-candidate target list or Pair-key map remains in the hot path.

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.

## Evidence

- `SimpleATO` 739 tests, 0 failures.
- S100 500 m: 1343 ticks / 67.15 s for STOP and STATION_RUN; 1335 / 66.75 s for AUTHORITY;
  errors `-9.607312313164584E-5 m` and `-3.2385034501203336E-4 m`, unchanged.
- 100 km synthetic five-car preview, four runs: 996 / 553 / 527 / 503 ms; warm ~503-553 ms.
  Previous implementation measured ~1470 ms; original pre-optimization ~4480 ms.
- Hot-path profile after the change: nominal stopping no longer runs per tick during a long
  cruise; remaining work is the required per-tick emergency/held/final certificate.
