# Formal production EMU actuator microbenchmark

This experiment invokes the current `HandleTrainActuator`, its real `TrainDynamicsExecutor`, equipment kernel and braking envelope. It does not reuse the earlier prototype and adds no production flags or alternative model.

An owned Core test world creates each 4/8/16-car train. Synthetic mixed motor/trailer profiles use engineering parameters, not calibrated S100 measurements. Each 50 ms frame carries actual actuator speed and device state into the next frame. Repeated 60-second scenarios hold brakes, release, accelerate, reduce speed and brake to rest. Moving-frame assertions prevent an idle-only result. Core geometry remains fixed on flat track; future braking grades include ascent and descent. Geometry movement, route scanning, world scheduling, JS/rendering and networking are excluded.

Two measured modes are `actuation-only` and `envelope-and-actuate` (real envelope construction, permitted-speed query, spatial candidate admission and actuator step). The envelope mode passes actual `BrakingConstraints` with the same grade segments, a target of 0 m/s at 5,000 m, zero additional reaction time and the fixture world’s actual maximum physics delta; `step` therefore performs production spatial candidate admission. The actuation-only mode deliberately omits spatial constraints to separate its cost. Constraint creation and demand allocation occur inside each measured frame. Outputs are consumed. Thread allocation uses `ThreadMXBean`; frame timing records mean and percentiles. Three independent JVMs shuffle case order and each case warms up before measuring. The experiment compilation disables incremental compilation, up-to-date skipping and build-cache reuse for Core main/test-fixture and Basic main/test-fixture/test Kotlin tasks. Each compiler invocation captures the source inventory before compiling and verifies it afterwards; the final receipt records those sources, emitted class hashes and runtime-classpath file hashes. Compiled directories precede project JARs on the benchmark classpath. The runner requires that receipt to match current sources and classes before launching any JVM and rechecks it before/after every fork. This rejects sources edited before runner startup while stale classes remain. The inventory includes `ServiceBrakingResponse`, constraints and the Core world/coordinator timing seam. Results are not a full-world capacity estimate.

After the main session coordinates the Gradle slot and freezes the shared API:

```sh
./gradlew :Basic:productionEmuPerformanceClasspath --offline --no-daemon -I ai_tasks/experiments/production-emu-performance/run.gradle
python3 ai_tasks/experiments/production-emu-performance/run.py --java /path/to/jdk25/bin/java --output /tmp/jr-production-emu-performance-NEW
```

The default budget is 2,400 warm-up and 4,800 measured frames per case, six cases per JVM, three sequential JVMs. Raw CSV/stdout/stderr, the compile receipt, source/class hashes, commands and summary are saved in the newly created output directory. Record any changed budget when reporting results.

Receipt-validation unit tests (no Gradle or benchmark execution):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s ai_tasks/experiments/production-emu-performance -p 'test_*.py' -v
```
