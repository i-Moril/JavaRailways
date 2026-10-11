# Coordinate constraint performance comparison

2026-10-11 · same machine, JVM 17.0.20, 512 MiB test heap, 4 workers, 15 cars/train, 10 m/s, 50 ms logical tick.
Each cell aggregates three repeated scenario/world runs in one JVM per shape/arm (not independent JVM forks), each with 200 warmup + 1000 measured epochs. Percentile cells are medians of per-run percentiles, not percentiles of pooled samples.

The before arm uses arc-length bogie spacing (known incorrect on curves); after enforces 3D connector spacing. This is a correctness repair comparison, not an equal-accuracy algorithm contest.

Independent tracks are translated by index × 100 m in both arms. The initial baseline-straight precheck and aborted overlapping-track curved setup are excluded.

## Wall p95

| Track | Scenario | Trains | Before p95 median [range], ms | After p95 median [range], ms | Change | Before/after |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| straight | isolated | 100 | 4.236 [4.130, 4.355] | 3.266 [3.070, 3.319] | -22.9% | 1.30× |
| straight | isolated | 500 | 28.321 [28.132, 28.759] | 24.136 [23.807, 25.158] | -14.8% | 1.17× |
| straight | mixed | 100 | 3.923 [3.369, 3.932] | 2.958 [2.811, 2.963] | -24.6% | 1.33× |
| straight | mixed | 500 | 24.605 [24.148, 24.699] | 22.816 [20.863, 23.426] | -7.3% | 1.08× |
| straight | shared | 100 | 4.601 [4.437, 4.735] | 3.232 [3.099, 3.653] | -29.7% | 1.42× |
| straight | shared | 500 | 25.856 [25.011, 26.716] | 24.499 [20.595, 24.508] | -5.2% | 1.06× |
| curved | isolated | 100 | 4.347 [4.314, 4.557] | 4.920 [4.674, 5.149] | +13.2% | 0.88× |
| curved | isolated | 500 | 28.677 [28.585, 29.403] | 31.055 [30.219, 31.253] | +8.3% | 0.92× |
| curved | mixed | 100 | 3.574 [3.532, 3.612] | 4.119 [3.888, 4.309] | +15.2% | 0.87× |
| curved | mixed | 500 | 25.927 [25.220, 26.321] | 27.222 [25.971, 27.554] | +5.0% | 0.95× |
| curved | shared | 100 | 4.770 [4.639, 5.048] | 5.900 [4.767, 6.252] | +23.7% | 0.81× |
| curved | shared | 500 | 31.856 [31.493, 32.253] | 29.262 [27.817, 30.710] | -8.1% | 1.09× |

## Other wall and execution metrics

Values are medians across the three repetitions; max is the median of per-run maxima.

| Track/scenario/trains | Arm | p50 ms | p99 ms | max ms | worker max p95 ms | worker total p95 ms | coordinator p95 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| straight/isolated/100 | before | 3.062 | 5.711 | 8.481 | 2.016 | 6.902 | 1.481 |
| straight/isolated/100 | after | 1.973 | 4.641 | 9.071 | 0.971 | 3.348 | 1.485 |
| straight/isolated/500 | before | 22.217 | 30.749 | 34.642 | 16.056 | 62.448 | 13.401 |
| straight/isolated/500 | after | 18.422 | 27.361 | 47.325 | 8.541 | 27.247 | 17.213 |
| straight/mixed/100 | before | 2.544 | 4.973 | 7.020 | 1.621 | 5.379 | 1.312 |
| straight/mixed/100 | after | 1.814 | 4.162 | 8.253 | 0.860 | 2.868 | 1.358 |
| straight/mixed/500 | before | 19.294 | 27.302 | 31.744 | 12.830 | 50.208 | 12.880 |
| straight/mixed/500 | after | 17.442 | 25.631 | 28.926 | 9.244 | 32.448 | 14.707 |
| straight/shared/100 | before | 3.223 | 5.787 | 7.877 | 3.088 | 11.184 | 1.474 |
| straight/shared/100 | after | 2.101 | 4.691 | 6.620 | 1.061 | 3.706 | 1.514 |
| straight/shared/500 | before | 20.387 | 28.616 | 31.262 | 15.131 | 58.876 | 11.613 |
| straight/shared/500 | after | 17.994 | 27.085 | 30.552 | 9.820 | 37.890 | 14.136 |
| curved/isolated/100 | before | 3.228 | 5.431 | 7.342 | 2.381 | 7.367 | 1.561 |
| curved/isolated/100 | after | 3.213 | 6.302 | 10.912 | 2.217 | 7.555 | 1.758 |
| curved/isolated/500 | before | 22.955 | 31.465 | 39.795 | 15.958 | 61.513 | 14.375 |
| curved/isolated/500 | after | 23.996 | 34.985 | 61.203 | 16.746 | 65.236 | 15.408 |
| curved/mixed/100 | before | 2.592 | 4.681 | 6.821 | 1.772 | 5.593 | 1.357 |
| curved/mixed/100 | after | 2.623 | 5.328 | 7.067 | 1.821 | 5.923 | 1.565 |
| curved/mixed/500 | before | 20.164 | 28.268 | 46.917 | 13.322 | 51.438 | 13.209 |
| curved/mixed/500 | after | 21.221 | 28.702 | 35.427 | 13.875 | 53.641 | 14.478 |
| curved/shared/100 | before | 3.272 | 5.615 | 7.253 | 3.137 | 11.545 | 1.458 |
| curved/shared/100 | after | 3.371 | 7.753 | 11.224 | 3.326 | 11.505 | 1.749 |
| curved/shared/500 | before | 23.218 | 36.179 | 62.059 | 19.452 | 73.587 | 14.003 |
| curved/shared/500 | after | 23.510 | 31.197 | 37.172 | 17.032 | 64.791 | 13.709 |

## Interpretation of observed wall time

- Initial matrix, 500 trains: straight wall p95 medians decreased 5.2–14.8%; smooth-curve isolated/mixed increased 8.3%/5.0%, shared decreased 8.1%. These are measured differences, not a blanket speedup claim.
- Run-to-run variability and grouped run order limit causal attribution. For example straight shared500 after p95 spans 20.595–24.508 ms, wider than its 5.2% median decrease. Curved shared100 wall p95 rises 23.7% while worker-total p95 changes by only -0.3% and coordinator p95 rises about 20%.
- Worker and coordinator percentiles are measured separately and must not be summed. Whole-epoch differences cannot be attributed entirely to coordinate solving.

## Targeted order-reversal confirmation

After the initial fixed matrix, original sources were rebuilt and shared500 was repeated (straight then curved), followed by fixed sources (straight then curved). Each has the same 200/1000 epochs and three repeats. This is a small run-order check, not a randomized controlled experiment.

- straight, shared500: before p95 [28.164787, 27.818302, 29.865371] ms; after [22.755085, 21.403899, 23.765819] ms; median change -19.2%.
- curved, shared500: before p95 [29.941001, 29.583016, 31.701684] ms; after [30.479653, 30.188106, 30.108254] ms; median change +0.8%.

All original samples remain in the initial matrix; confirmation is reported separately, not selectively pooled.
Straight shared500 confirms the direction of lower wall p95, with variable magnitude (initial -5.2%, confirmation -19.2%). Curved shared500 changes from an initial -8.1% to +0.8% in confirmation: no stable improvement is established; treat this case as approximately unchanged at the observed noise level.


## Absolute worst measured epoch

- before: 80.965 ms (maximum across all 42 successful runs including confirmation, not median).
- after: 64.851 ms (maximum across all 42 successful runs including confirmation, not median).

## Correctness and interpretation

- Baseline geometry regression: R30/6m axle error 9.9953 mm, S curve 6.2349 mm, banked slope 20.5298 mm; all exceed 5 mm. Straight restored centre also drifted 0.722656 mm against the new 5 µm consistency assertion. Evidence: baseline-red.xml.
- Corrected geometry regression: all four cases pass; full gate has 1093 tests, zero failures/errors and six existing skips (1087 executed). No Paper/Folia runtime validation was performed.
- Curved stress tracks are the existing smooth sinusoidal fixture (20 m amplitude, 500 m wavelength), not the R30 correctness fixture. Performance numbers do not establish the worst-case cost of R30, S bends or banked slopes.
- Raw stress reports retain per-run p50/p95/p99/max and worker/coordinator figures, plus environment/config. Harness checks epoch/controller counts, no derailment/collision, and no mailbox capacity/stale drop.
- 50 ms is a headless p95 budget, not a zero-overrun guarantee. Maximum values must be read separately. No capacity ceiling was searched.
- This harness uses a constant-speed controller, not real ATO/dispatcher; Paper/Folia rendering/network are excluded. No server/client runtime claim follows from these numbers.
- User excluded extremely tight near-halfcircle curves. The local bracket solver does not guarantee discovery of arbitrary nonmonotonic roots.
- Existing movement failure follows derail/publication handling without whole-consist rollback; this task does not add transactionality.
- Run order: corrected before-straight → before-curved → fixed regression → after-straight → after-curved. Source and harness hashes are in provenance JSON; repeat confirmation results, if any, are recorded separately.

## Raw reports

- [before-straight](before-straight/physics-capacity.json)
- [confirmation-before-straight](confirmation-before-straight/physics-capacity.json)
- [before-curved](before-curved/physics-capacity.json)
- [confirmation-before-curved](confirmation-before-curved/physics-capacity.json)
- [after-straight](after-straight/physics-capacity.json)
- [confirmation-after-straight](confirmation-after-straight/physics-capacity.json)
- [after-curved](after-curved/physics-capacity.json)
- [confirmation-after-curved](confirmation-after-curved/physics-capacity.json)
