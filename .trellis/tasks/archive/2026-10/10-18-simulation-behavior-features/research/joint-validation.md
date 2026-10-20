# Joint fixture alignment (2026-10-20)

Candidate: `.worktree/simulation-world-alignment`, based on `87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7`.

Owned source paths:

- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/http/FullFeatureSimulationIsolationTest.kt`
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/signal/SimpleSignalsSimulationFeatureFactory.java`

## Replaced partial proof

The earlier two-target case created targets but never called `simulate`; the earlier door case did not install SimpleSignals and copied a simplified behavior script/actions. These are replaced, rather than counted as completed parent acceptance.

The new first case goes through the real HTTP factory capture/create, official `jr-four-light` loader and `SimpleSignalsSimulationFeature`, the real Core/Basic/SimpleATO simulation loop and Dispatcher. Train behavior/action values come from `PackageLoader(models/s100)` and animation capabilities from `SavedCarModelTree.build(...).captureAnimationCapabilities()`; no copied JS, declarative action timings, animation indices, fake signal sources or fabricated ticket success. The small physical/control blueprint is explicit fixture input, not a complete model/resource catalog test.

Two targets use the same capture. Holding target A's actual execution owner must leave it at epoch zero while B completes its whole simulation. Owner-side generic commits collect immutable Dispatcher/effect diagnostics. The fixture checks real SimpleSignals source restriction publications, mixed installed direction, the pending door-close gate and the official 0.6-second delayed animation/sound starts before completion. Both targets complete deterministically. Closing A must leave B able to commit another real signal/physics epoch. The source's exact train frame/controller/Dispatcher remain unchanged.

The second case starts the real factory-injected ATO via Dispatcher, then calls the actual Core collision-owner transaction with its real captured geometry/dependency envelopes. The Java bridge calls existing JVM-public internal constructors/methods directly, without reflection or changing production visibility. It checks exact-car extension/JS identity, pending effects across link, persistent JS state, original accepted target membership, and the existing ATO's captured per-car resolver accepting an incoming car that has no controller. It deliberately does not exercise physical broad-phase collision detection; existing Core collision tests own that independent path. The fixture only appends a stateful test declaration to the loaded official script to make context continuity observable.

Upstream alignment keeps Core resource DTO packages and exact owner scopes. All source worlds/targets and event registrations are lexical resources. A held owner barrier is always released and the executor shut down in finally. No production files or other test files are owned here.

## Evidence status

- Source statically reviewed; owned diff whitespace check passed.
- No build/test command run by this agent. Root owns the sole build token and executes compilation/focused tests.
- Root-run focused `:BukkitPlugin:test` succeeded (`/tmp/simulation-alignment-joint-focused.log`, BUILD SUCCESSFUL in57s). Actual XML `BukkitPlugin/build/test-results/test/TEST-work.art1st.javarailways.bukkit.http.FullFeatureSimulationIsolationTest.xml` timestamp2026-10-20T14:40:23.971Z: tests2, failures0, errors0, skipped0. Both named testcases are present: full two-target signal/door scenario8.262s; collision-owner transaction0.663s.
- Earlier actual failures exposed missing fixture authoring, not relaxed business contracts: Java bridge now supplies `bidirectional=false`; source rails now have real SUBGRADE10m/s metadata and explicit AB operating direction matching their forward-only blocks. Official compile/admission rejection remained unchanged and all original assertions remain.
- Tested source SHA256: Kotlin `fc563241b51e18f458369ea16fa826796f9dc5d876b7f1a75392e68e84adb73d`; Java `68121e784dd36e4df4ce4174bb5c587bc53d411f3b2381487a054da09a7327cd`.
- This focused2-test proof does not replace the final complete candidate/module gate or independent check; root records those separately.
