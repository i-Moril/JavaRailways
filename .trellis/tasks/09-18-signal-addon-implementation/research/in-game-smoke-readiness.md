# Research: in-game smoke readiness after 370f0190

> Implementation outcome: the blockers below were resolved in the subsequent playable-smoke batch. The final path is the
> isolated `:BukkitPlugin:runSignalSmoke` task, not the stale-data `run/` directory. Live Paper 1.21.11 reached `Done`, all
> three required plugins enabled, `/phys` reported ready, and `/simplesignals inspect world` reported an installed runtime.
> Client interaction/rendering and Folia remain unverified.

- Query: What is the shortest repeatable path to test JavaRailways + SimpleSignals and the Signal Wand in a real Paper/Folia client?
- Scope: internal
- Date: 2026-10-08

## Findings

### Hard blockers for the first useful client smoke

1. **The development servers do not deploy the SimpleSignals JAR.** `runServer` currently stages the JavaRailways shadow JAR, TunnelBridgeBuilder and JavaRailwaysDev; `runFolia` stages JavaRailways and TunnelBridgeBuilder only (`BukkitPlugin/build.gradle:44-50,127-136`). The built add-on itself is otherwise deployable: `SimpleSignals/build/libs/SimpleSignals-1.0-SNAPSHOT.jar` contains `plugin.yml`, declares `depend: [JavaRailways]`, Java 25/Folia support and its Paper libraries, and contains neither host classes nor embedded `org/graalvm` classes (`SimpleSignals/build.gradle:24-35,61-65,87-139`). The next change must add `:SimpleSignals:jar` as a dependency and `pluginJars` input to both local server tasks (Paper first is sufficient for the first smoke).

2. **There is no runnable, visible production signal pack to populate `/simplesignals wand signal`.** Startup creates and reads `<dataFolder>/signal-packs`, and the Wand explicitly refuses an empty catalog (`SimpleSignalsPlugin.kt:75-79`; `SignalWandHandler.kt:91-110`). The only unified `pack.yml + model/ + logic/ + signal/` example is a test fixture whose model part is `type: dummy` (`SimpleSignals/src/test/resources/signal-packs/automatic/**`), so it can exercise logic but cannot prove client rendering. The JR four-light example has real client assets, but its server side is still the old model-only `manifest.yml/models/parts` layout and explicitly contains no logic (`SimpleSignals/examples/jr-four-light/README.md:1-37`). It cannot be copied into `signal-packs/` as-is because the current loader requires `pack.yml`, `model/`, paired `logic/*.yml`/`*.js`, and `signal/` (`SignalPackLoader.kt:91-113,141-193`).

3. **The client pack must be installed separately.** The JR client assets are valid only for Minecraft 1.21.11/resource format 75 and are not automatically zipped, served, or sent to players (`SimpleSignals/examples/jr-four-light/README.md:14-25,50-63`; `resourcepack/pack.mcmeta`). Both local `server.properties` files currently have an empty `resource-pack=`. Therefore the fastest first visual smoke is Paper 1.21.11 with a manually installed ZIP; the existing `runFolia` target is `26.1.2`, so that JR pack must not be treated as verified there (`BukkitPlugin/build.gradle:44-49,127-130`).

There is no static evidence of another production-code blocker. SimpleSignals already acquires all seven host services, loads packages off-thread, exposes the Wand command, persists through the normal manager transaction, and binds unified-pack models to the host presentation service (`SimpleSignalsPlugin.kt:33-106`; `SimpleSignalsWorldRuntime.kt:958-981,1075-1157`). The first live launch is still required to verify Paper's download of the declared Graal libraries and the real client display lifecycle.

### Smallest coherent change

Make one reviewable **development-smoke slice**, without changing production defaults or auto-installing a demo on normal servers:

- `BukkitPlugin/build.gradle`: add `evaluationDependsOn(":SimpleSignals")`; make `runServer` and `runFolia` depend on `:SimpleSignals:jar` and include its ordinary JAR in `pluginJars`.
- `SimpleSignals/examples/jr-four-light/signal-pack/pack.yml`
- `SimpleSignals/examples/jr-four-light/signal-pack/model/head.yml`: current unified model form of the existing real JR head.
- `SimpleSignals/examples/jr-four-light/signal-pack/logic/automatic.yml` and `automatic.js`: minimal fixed-block smoke rule (`self.occupied` selects `stop/0`, otherwise `proceed/25`). It intentionally does not claim Japanese multi-aspect behavior.
- `SimpleSignals/examples/jr-four-light/signal-pack/signal/automatic-four-light.yml`: player-visible signal type linking that model and logic.
- `SimpleSignals/src/test/kotlin/.../presentation/JrFourLightDemoTest.kt` (or a focused signal-pack test): load the shipped unified example directly and assert its real model, type, logic, variants and captured JS.
- `SimpleSignals/examples/jr-four-light/README.md`: exact opt-in copy/ZIP/run/player steps. Do not copy the example into a production data folder automatically.

The example can then be staged explicitly before launch:

```bash
mkdir -p BukkitPlugin/run/plugins/SimpleSignals/signal-packs/jr_four_light
cp -R SimpleSignals/examples/jr-four-light/signal-pack/. \
  BukkitPlugin/run/plugins/SimpleSignals/signal-packs/jr_four_light/
(cd SimpleSignals/examples/jr-four-light/resourcepack && \
  zip -r ../../../../SimpleSignals/build/jr-four-light-resourcepack.zip .)
```

`run-folia/` may be staged separately after the Paper smoke; do not reuse the 1.21.11 client ZIP on 26.1.2 without updating/verifying its pack metadata.

### Verification commands and minimum player workflow

Use the workspace Gradle home and cached JDK 25 (the interactive shell itself currently reports Java 21, while the modules emit Java 25 bytecode):

```bash
GRADLE_USER_HOME=/workspace/.gradle ./gradlew \
  :SimpleSignals:test :SimpleSignals:verifyPluginJar \
  :BukkitPlugin:shadowJar :BukkitPluginDev:shadowJar
GRADLE_USER_HOME=/workspace/.gradle ./gradlew :BukkitPlugin:runServer
```

First client smoke (OP, therefore `simplesignals.admin` is granted by default):

1. Install `SimpleSignals/build/jr-four-light-resourcepack.zip` in the 1.21.11 client.
2. Check `/plugins` and server log: JavaRailways, JavaRailwaysDev and SimpleSignals must all enable; `/simplesignals inspect world` must report an installed runtime.
3. `/phys create` creates a four-rail oval quickly (`BukkitPluginDev/.../PhysTestCommand.kt:64-79,82-161`). Alternatively use `/jr wand rail`.
4. `/simplesignals wand block create`, then right-click two positions on an unbranched rail section to create a fixed block (`SectionWandHandler.kt:57-75`; `SimpleSignals/README.md:142-157`).
5. `/simplesignals wand signal`; the catalog must contain `jr_four_light:automatic-four-light`. Keep fixed-block/placement mode, left-click the directed block entrance while looking into the block, then right-click a rail at the desired display position (`SignalWandHandler.kt:91-120,305-340`).
6. Empty block should show `proceed`; `/phys train 2` places an occupying train and should change the same signal to `stop`. This minimal JS only reads `self`, so it is valid on a simple block with no downstream signal. Protection-signal permissive behavior is not a first-smoke expectation: without a matching ATO request/ready route, protection control correctly remains restrictive.

## Caveats / Not Found

- No live server was started in this read-only research pass. The current JARs were inspected, but the host shadow JAR predates HEAD and must be rebuilt.
- Existing `BukkitPlugin/run/logs/latest.log` is from 2026-08-16 and shows an old port conflict and stale plugin-classpath failures; there is currently no Java server process or listener on the relevant ports. Those old failures are not evidence against the current sources, but the smoke must check fresh logs.
- A test-fixture-only pack would make the Wand catalog non-empty but would not validate real rendering; presenting that as the requested game test would be misleading.
- Optional later work: attached-signal placement, protection-route permissive scenario, hot reload, automatic resource-pack delivery, and Folia 26.1.2 pack-format validation. None should delay the first Paper fixed-block smoke.
