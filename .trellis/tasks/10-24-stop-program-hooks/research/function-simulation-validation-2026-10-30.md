# Function simulation and placement validation — 2026-10-30

Candidate: `/tmp/javarailways-blockly-deploy`, branch `codex/timetable-station-programs`, baseline `33c44cb5` plus this uncommitted batch. No commit, push, deployment or archive. Existing task remains in_progress; game acceptance is pending.

## Final automated gate

The final serial Gradle run succeeded in 1m56s on JDK25, offline dependencies, one worker, Kotlin in-process, test heap2g/forkEvery10. It ran `:Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test :WebUI:test`; frontend install/build tasks were excluded because the actual current frontend had already been tested and built. Core/Basic/WebUI were up-to-date from successful complete runs; SimpleATO/Bukkit reran after the evidenced fixes. XML counts were inspected after completion:

- Core: 538 tests, 0 failures/errors/skips.
- Basic: 435 tests, 0 failures/errors/skips.
- SimpleATO: 643 tests, 0 failures/errors/skips.
- BukkitPlugin: 663 tests, 0 failures/errors/skips.
- WebUI: 49 tests, 0 failures/errors/skips.

Total backend2328. Frontend:323 tests in53 files and TypeScript/Vite production build passed earlier in this same run; no frontend source changed afterwards. The build also compiled affected SimpleATOBukkit and SimpleSignals consumers. Existing deprecation/JDK native-access warnings are not test failures. `git diff --check` and task context validation passed.

## Executed behavior evidence

- Core UniqueTrainPlacementTest:14 actual tests. Full rigid candidates include two feasible forks, one infeasible short fork, fork beyond the clipped body, curve/3D/asymmetric/mixed installation, AB/BA seams, nonoverlapping repeated-rail loop occurrences, true self-overlap, unplaceable, stale proof and budget exhaustion after finding a feasible candidate. Original CoreCar positioning regressions:7 tests. Ordinary setLocation keeps its selected-path behavior.
- TimetableDispatcherTest:56 tests. New regressions exercise both LIVE and SIMULATION, reject an unreachable target behind an unchanged train without implicit reverse/controller installation, and execute explicit one/two in-place reversals across opposite local rail bases while checking exact published head/tail/body occupancy and real mission installation.
- Bukkit IsolatedTimetableSimulationFactoryTest:26 tests; actual factory→Core placement→physics→Dispatcher covers configured direction precedence/no fallback, real optional reversal with unchanged occupancy, behind-target refusal, ambiguous body placement despite an active turnout, true registered units, long waits/advance bounds and mixed-rail reverse→depot arrival. ProgramSimulationTest:6 tests cover actual sandbox wrapping/timing/result/queue contracts.
- Sidecar authentication/strict transient request tests are included in WebUI49; the current program definition and arguments are sent as exact data, not reloaded from the library by ID. Failed/incomplete runs never acquire a fabricated successful duration.

## Failures found and fixed before final gate

1. Initial Dispatcher56 had one failure: LIVE/2 mixed-rail repeated reversal selected BA instead of the origin anchor's AB. Retain and toggle pending origin-basis direction instead of overwriting it from another rail's local facing. Both LIVE/SIM cases now pass.
2. Initial Bukkit663 had two failures: reverse→StoreAtDepot used actual head local AB to look up origin-key BA evidence at tick751; Store plan now passes the same pending direction. A new UNIT_COUNT fixture also nested an owner scope through createTrainWithConfig; move construction outside the later owner block, preserving production owner validation. Focused Factory26 + Program6 + Dispatcher56 passed, then complete SimpleATO643/Bukkit663 passed.
3. Independent source review and follow-up confirmed both production corrections and no remaining confirmed findings within the reviewed scope; see function-simulation-review-2026-10-30.md.

## Browser evidence and limits

The real current production WebUI dist was served locally with an explicitly identified UI-only HTTP fixture, session `jr-function-sim`. Checked required reference/eligible directed waypoint/reversal choice, initial disabled execution, only directed point offered, and captured requests for reverseTrain false and true with exact definition/arguments. The fixture returned422 `no simulation engine attached`; the UI displayed this failure and no successful duration. Browser had no script errors. Desktop and390px screenshots show the actual dialog; scrolling exposes bottom arguments/error/buttons. Fixture and browser were stopped. These checks prove UI interaction/wire/failed-result presentation, not successful backend physics or game behavior; factory tests supply the separate physics evidence.

All physical factory seeding consumers (full timetable and standalone function) share the unique placement boundary. The existing distance-only line travel preview integrates TrainDynamics without a physical train/ATO seeding stage. No new function executor was created. Uniqueness uses existing Core finite-window numerical solver support; no proof of every nonlinear root on arbitrary pathological curves is claimed. Search budgets/cancellation cannot produce a false Unique. Basic operating-direction restrictions govern movement; stationary full-body facing is not silently treated as a new parking rule.

No persistent schema/version/cache changes or old-facing compatibility paths. Live worlds, trains and server artifacts were not changed. Task remains open for user game validation.
