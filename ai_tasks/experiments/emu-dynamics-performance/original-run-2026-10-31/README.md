# EMU dynamics performance experiment (2026-10-31)

This is an isolated cost prototype, not a calibrated vehicle model or a production implementation.
No production source, schema, model package, server, or existing door work is changed.

## Boundaries

- Baseline directly calls current production `TrainDynamics.step`, with synthetic immutable vehicle profiles.
- Prototype adds per-car cylinder pressure, electric-brake and traction force states, demand age and previous demand; pressure charging/exhaust, command delay, electric low-speed fade, electric/pneumatic blending, and traction inhibition while residual braking remains. Both paths retain grade/resistance/adhesion/jerk and trapezoid motion integration.
- Illustrative parameters: half motor cars, 32t each, 350kPa nominal cylinder pressure, 1.2s full apply/1.8s full release, 0.1s command delay, electric fade below 3m/s. These are workload parameters, not a claim about S100 or a specific real train.
- Prototype does not implement reservoir/compressor/pipe networks, wheel slip, thermal/power networks, complete parking/rollback control, persistence, consist state transfer, or a new braking envelope. No accuracy claim.
- `step`: one isolated dynamics advance from an immutable scenario snapshot. It excludes world/car lookup, geometry and collision, Core publication, Bukkit, and actual ATO planning.
- `selection`: a standalone workload kernel following the structure of current `desiredHandle`, progressive selection and `canRecoverBelowTarget`. It uses N as initial handle and omits emergency special case/tie-breaking/actual motion boundary. It is NOT a measurement of a full `SimpleATO.tick` or a drop-in implementation. It compares workload costs, not identical control outputs between models.
- `recovery256`: deliberately executes all 256 steps regardless of early exit. This is a stress workload, not observed normal ATO behavior. Normal selection records the actual recovery iteration count separately.
- Each speculative state owns its arrays; no trial mutates the starting snapshot. Allocation cost includes benchmark wrapper objects, as well as dynamics states. This intentionally straightforward prototype is not allocation-optimized.
- Functional sanity checks precede each fork: pressure-release traction inhibition, eventual startup, braking to rest and speculative input isolation. They do not validate physical realism.

## Method and reproduction

Run from repository root:

```sh
./gradlew :Basic:emuPerformanceClasspath --offline --no-daemon -I ai_tasks/experiments/emu-dynamics-performance/run.gradle
python3 ai_tasks/experiments/emu-dynamics-performance/run.py
python3 ai_tasks/experiments/emu-dynamics-performance/summarize.py
```

The init script adds this directory only to the test compilation source set; it does not change the normal build configuration. Uses existing offline dependencies. The runner uses PATH Java; environment.json records its actual version and source hashes.

Three sequential independent JVMs, 512MiB initial/max heap, G1. Each JVM shuffles cells with a different fixed seed. Each cell warms for at least 150ms, chooses a batch lasting approximately 15ms or more (bounded at 1,048,576 operations), then measures five batches. Results are the median of each fork's five batches, then the median of three fork medians. Fork ranges are included; no confidence-interval or statistical-significance claim. Wall-time includes GC/scheduling; allocation is current-thread bytes via ThreadMXBean, not retained heap. Results are local synthetic microbenchmarks, not server capacity guarantees or p99 latency tests.

Inputs: 4/8/16 cars; acceleration, steady-speed control, brake application, and startup from applied brakes. Active scenarios start with matching established demands; startup deliberately changes from hold to traction. All timings are single-threaded. CSV files preserve all 810 measurements.

## Results

See `summary.json` for per-scenario timings, allocation and fork variation, and `environment.json` for host/JVM/source identity. The research conclusion and representative final values are recorded below after measurement.

Final local results (microseconds/operation, median of three JVM fork medians):

- Eight-car dynamics: acceleration 0.086 -> 0.102; cruise 0.084 -> 0.099; braking 0.084 -> 0.096; release 0.083 -> 0.096. Ratios 1.15–1.19; allocation 368 -> 616 bytes/operation.
- Eight-car selection kernel: acceleration 1.801 -> 2.295; cruise 2.237 -> 3.025; braking 0.954 -> 1.220; release 1.514 -> 2.013. Ratios 1.27–1.35. Acceleration allocation 5896 -> 10120 bytes; cruise 7392 -> 13072 bytes. These fixtures use 8–10 recovery steps when recovery is applicable, not 256.
- Eight-car forced 256-step stress: 21.388 -> 24.671 us; allocation 88088 -> 157720 bytes. It does not include candidate selection. This is not evidence that real ATO usually reaches the iteration cap.
- Four-car single-step ratios 1.01–1.11; sixteen-car 1.48–1.59. Sixteen-car selection ratios 1.25–1.39. Do not generalize one constant multiplier across consists or workloads.
- Host: local virtualized Intel Core Ultra 9 275HX environment, eight visible CPUs, OpenJDK 21.0.12.1. No server hardware/JVM measurement. Source, environment and raw samples retained.

Interpretation: per-car state/force arithmetic remains linear and the tested device features do not inherently require a large CPU budget. However speculative state allocation grows materially; reducing disposable arrays/snapshots is a more concrete follow-up than assuming the device arithmetic is too expensive. A production implementation must preserve isolation when optimizing reusable state. With real slope/route/controller calls, braking-envelope construction, consist transitions and GC tails included, an integrated benchmark is still required. No claim about production milliseconds-per-tick, server train capacity, actual vehicle accuracy, or game acceptance is made.

The prototype represents the equipment-state additions, not all future simulation features. It is intentionally local research only; no commit/deployment was requested or performed.
