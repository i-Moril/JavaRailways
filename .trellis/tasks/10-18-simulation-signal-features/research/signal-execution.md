# Research: Signal execution ownership and manager lock boundaries

- Query: S1/S3 — remove cross-World JS execution ownership and preparation/owner waits under the manager directory lock while preserving fresh realms, same-World serialization, atomic source replacement and real teardown.
- Scope: internal; product inspection only, no source/test/spec changes or builds.
- Date: 2026-10-19
- Checkout: `.worktree/simulation-signal-features`, parent-supplied baseline `9ed27818`.

## Findings

### Exact current sharing

`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt` contains both the world runtime and manager. `PreparedSignalPacks` owns **one** catalog and **one** logic runtime (959–962). `loadSignalPacks` reads the catalog and constructs that logic once on the startup worker (983–1005). Every bound world goes through `prepareSectionOccupancy` and receives that exact `packages.logic` in `SignalPackEpochRuntime.compile` (1015–1045). The compile result retains the reference; the actual epoch calls `logic.evaluate` (`signalpack/SignalPackEpochRuntime.kt:45–50,110–120`). There is no per-World executor hidden in this path.

`signalpack/SignalLogicRuntime.kt:21–38` owns one Graal Engine and Source map, validates every rule syntax and entry point in temporary contexts during construction. Both evaluate (41–59) and close (61–65) synchronize on the runtime instance. Each evaluation creates its own Context/realm and force-closes it in finally (96–112); it does **not** retain a mutable Context between calls. The same instance monitor currently serializes all LIVE worlds. Preserve evaluate/close synchronization after changing ownership; removing the annotation is not the isolation fix.

`signalpack/SignalPackCatalog.kt:8–20,45–52,63–76` captures source text and immutable catalog maps. Source files are not reread during evaluation. Catalog also includes read-only compiled model assets and variant names; these are not live model handles, callbacks or Bukkit World references. Sharing this immutable input between world runtimes is acceptable. The simulation researcher suggested a smaller neutral rule-catalog projection if module/API layering actually requires it; avoid maintaining a second catalog solely to rename these read-only values. No shared Engine optimization is necessary for this batch.

### Lifecycle and failure semantics that must survive

- `SimpleSignalsWorldRuntime.retire` (282–287) marks closed, retires previews/topology subscription/model handles, but currently has no JS ownership to release.
- Manager `remove` (1543–1552) deletes the world under manager lock and asynchronously closes that world's models. It deliberately does not close the shared engine.
- `clearLocal` (1554–1570) marks all worlds retired under manager lock, then asynchronously closes models and the one shared engine. New design must instead close every retired world's logic outside that lock. Teardown waiting for A's evaluate monitor must not prevent B's ordinary operations or a SIM's close.
- Catalog is load-once, not hot reload (`loadSignalPacks:1001` rejects a second successful load). World reload is a fresh world runtime, not a catalog runtime reload. `SimpleSignalsWorldLifecycleListener.kt:17–33` installs already loaded/on-load worlds and removes on unload; tests also exercise same UUID replacement. `SimpleSignalsPlugin.kt:85–91` loads catalog on async scheduler after registering current worlds; startup binds restrictive sources until catalog attachment succeeds.
- Startup `attachSignalPacks` (1125–1172) compiles outside manager lock, then atomically replaces sources only if captured runtime, publication and exact topology still match. Preserve this guard and startup unavailable/0 behavior.
- Topology revalidation (1479–1528) compiles a candidate on its worker, retains epoch continuity and uses the existing atomic replacement. A delayed result may not overwrite newer publication or retired world.
- Definition edits strictly prepare against the captured catalog; initial install/topology safety recovery may fall back to unavailable sources (`prepareSectionOccupancy:1022–1045`, `prepareCandidateSectionOccupancy:1109–1121`). Catalog becoming available during authoring causes a typed stale/unavailable result rather than installing restrictive preparation over the new catalog (`applyDefinitionPatch:1736–1738,1828–1830`).
- Runtime JS failure becomes device Unavailable/0; only an actual changed failure state reports original Throwable plus world/device/type/logic/stamp/revision (`SignalPackEpochRuntime.kt:110–142`). A rule error is not a reason to close other worlds' engines. Normal red/expected unavailable remains non-error.

### S3: real locks and waits, distinguished precisely

| Site | Current work under manager `lock` | Required handling |
| --- | --- | --- |
| `loadSignalPacks:996–1004` | Directory/catalog install; closed branch calls `prepared.logic.close()` | Keep catalog/directory exchange short; dispose candidates outside lock. No world engine construction here. |
| `install:1327–1470` | Definitions load, topology/definition compile, section preparation, source registration, subscriptions and directory publish | Single-world preparation and every host call outside shared directory lock. Do not put fresh per-world Graal construction in this existing large block. |
| `applyDefinitionPatch:1729–1901` | Permit acquire, root reads, staged save, `replaceSourcesAtomically`, persistence callback | Preserve same-world transaction order, but remove the manager directory lock across these operations. Candidate compilation is already before this block. |
| `attachModels:1266–1274` | Root check and local model swap | No JS evaluation here. Keep identity/swap bounded; do not grow this into an engine wait. |
| `remove`, `clearLocal` | Directory removal/retire; model/engine close already async | Keep blocking per-world logic close outside manager lock. Retire all removed runtimes before scheduling cleanup. |

**Exact host owner evidence:** `BukkitPlugin/.../api/internal/DefaultSignalEpochSourceService.kt:80–85,348–353` invokes atomic source replacement through `withWorld`, which acquires that target's `WorldExecutionOwner`; manager `install/applyDefinitionPatch` currently hold the directory lock across that wait. By contrast `DefaultInfrastructureResourceService.kt:131–134` is a short service lock and immutable topology-root getter, not an explicit owner acquisition. `acquireEditPermit:649–670` uses the coordination registry. Do not claim every root read or permit request inherently waits for the host World owner.

Atomic source callback only runs after host candidate source preparation (`DefaultSignalEpochSourceService.kt:108–124`). It must retain exact root/base/catalog checks, already staged primary atomic rename and prepared immutable swap; no JS constructor, rule compile, engine close, graph search or ordinary file write there. `SimpleSignalsWorldRuntime.commitPrepared:684–711` is the existing bounded runtime-publication swap seam. Physics signal callbacks continue using captured source/runtime directly and never acquire the host execution owner.

### Minimal ownership and lock shape

1. Manager owns immutable prepared rule input, directory, plugin-wide shutdown flag and cross-world registration decisions. Each actual `SimpleSignalsWorldRuntime` owns at most one logic execution lifetime for the prepared catalog; each SIM installation owns its own equivalent lifetime. Definition/topology replacements **within the same World** reuse that World's logic runtime because evaluation already uses fresh realms. World unload/reinstall allocates fresh execution ownership.
2. Put engine construction/initial validation on existing preparation workers and outside the manager lock. A local world-only preparation/lifecycle gate can serialize lazy startup attachment versus edits and teardown. Keep it separate from the short publication lock if holding the publication lock would include construction or waits. The engine's evaluate/close monitor remains the same-World guest exclusion boundary.
3. Manager lock protects only map/identity/read-view exchanges and closed checks. A per-world management transaction may cover that world's installation/authoring host calls; it must not be a shared cross-world mutex. Snapshot identity under the directory lock, release it before world-local transaction/owner waits, and check captured identity again at the bounded commit point. Existing runtime object identity + closed flag are enough; no new environment/currentWorld, public generation, tombstone or universal lifecycle framework is required.
4. Installation must preserve single winner and remove/reinstall correctness when moving compilation out of manager lock. Reserve the **actual runtime object** under the short directory lock, keep incomplete candidates out of `runtimeReadView`, then prepare/install on its world-local gate. Failure cleanup must only retire/remove its own identity; a late old failure must not clear a newer instance's sources. This is the concrete race to review, not a reason to retain the old giant lock. If implementer chooses prepare-first rather than reserve-first, it must show equivalent winner/retirement handling.
5. Safe order is world-local management/preparation gate → host target owner → bounded publication/identity commit. Never take a world-local gate, host owner, engine monitor or engine close while holding the manager directory lock. Remove/disable marks identities retired and removes visibility in a short block, then waits/closes outside it. No callback should reverse into a world-local gate while holding a different global gate.
6. A rule compilation failure disposes only its newly created local candidate. A late per-world engine construction after retirement must be disposed instead of becoming reachable. `clearLocal` must compose all close completions and preserve errors/observable completion; one paused world must not keep already-independent worlds from processing. No watchdog/time budget or fabricated successful rule output.

### Concrete implementation ownership

Primary files for the SimpleSignals implementer:

- `runtime/SimpleSignalsWorldRuntime.kt`: shared PreparedSignalPacks removal/refinement, explicit per-world execution lifetime, prepareSectionOccupancy callers (initial install, attach, topology revalidation, validate/apply), install/apply directory lock scopes, remove/clear cleanup.
- `signalpack/SignalLogicRuntime.kt`: retain production sandbox/fresh realm/synchronized evaluate-close; only adjust constructor input if a real neutral prepared input is extracted.
- `signalpack/SignalPackEpochRuntime.kt`: preserve reference ownership and failure/reporting/variant validation; adapt only prepared-input type if needed.
- `SimpleSignalsPlugin.kt`, `SimpleSignalsWorldLifecycleListener.kt`: existing worker/startup lifecycle scheduling only where world construction must leave owner threads; do not compile a Graal engine from an event callback.
- `runtime/SimpleSignalsWorldRuntimeManagerTest.kt`, `signalpack/SignalLogicRuntimeTest.kt`, existing formal fixture tests, plus focused new isolation test if clearer than enlarging the current manager test.
- A small shared rule/catalog or domain assembler file is reasonable only if required for actual LIVE/SIM reuse. Host public target endpoints and Feature registry stay with the other implementer; avoid overlapping ownership or reversing module dependencies.

### Reusable fixtures and deterministic proof

- `signalpack/JrFourLightSignalPackTest.kt:18–20,41–49,132–155` loads the **distributed** `/examples/jr-four-light/signal-packs`, runs real Graal and asserts all four aspects and explicit green clear.
- `runtime/SimpleSignalsWorldRuntimeManagerTest.kt:2623–2676` installs that same formal catalog via real manager, uses real `SignalEpochRuntime`/same-epoch occupancy and verifies short/mixed-block downstream speeds/aspects, release and immutable old snapshot. Reuse its definitions/topology/static-speed setup; this fixture alone does not advance a physical Core world.
- Manager test `2878–2937` provides original Throwable, fail/recover/fail and no duplicate-report coverage. `2792` catalog-attachment race, `2979` stale model preparation, `3148–3175` delayed atomic callback/disable, `6022` delayed topology/source publication and `1180` same UUID/disable cleanup are important existing regression boundaries.
- `SignalLogicRuntimeTest.kt:109–116` fresh realm state reset; `217–230` serial reuse/idempotent close/closed rejection; syntax/missing entry and async rejection nearby. Existing tests do not prove concurrent world independence.
- Deterministic barrier may hold **A's actual SignalLogicRuntime instance monitor** on a helper thread, then enter the real manager-installed source/evaluate path and assert it is waiting there. This is the approved execution-lock branch of S2 and permits using the unchanged distributed formal JS. Obtain that instance through a narrowly scoped internal test helper/reflection if needed; do not add exported guest hooks or host access capabilities. B must then run real physics + signal publication and assert correct stamp, movement and formal aspect/limit. Pausing an unrelated outer executor or only asserting distinct object addresses is insufficient.
- Run LIVE–LIVE, LIVE–SIM and SIM–SIM with the same barrier mechanism; second same-A evaluation and A close must remain pending until monitor release. Close/failure of A must leave B usable; reinstall A must allocate a new lifetime while B retains its own. Add blocked A host source replacement/engine preparation tests proving B install/edit/catalog snapshot is not stuck on manager directory lock.
- The manager's current capturing source fixture (`6238–6268`) has plain mutable maps/lists and a shared mutable callback. If reused for concurrent tests, make the fixture concurrency-safe or isolate captured maps per test/world; otherwise test data races can masquerade as product failures. Always release barriers in finally and assert thread outcomes externally.

## Related Specs

- `.trellis/spec/simple-signals/backend/js-signal-rules.md:160–190` explicitly documents today's shared manager logic; update it to actual per-world ownership after implementation, retaining sandbox/fresh realm/no-timeout/failure semantics.
- `.trellis/spec/simple-signals/backend/index.md:349–399` requires worker candidate preparation, one staged durable commit and exact immutable source replacement, no new lock framework.
- `.trellis/spec/javarailways/simpleato/reactive-signal-epochs.md` requires synchronous same-epoch source evaluation, captured immutable source sets, atomic replacement and target-only stall on callback failure.
- `.trellis/spec/javarailways/shared/modules-and-build.md` forbids lower layers depending on SimpleSignals/Bukkit internals. Add-on talks through public host API.
- Parent remaining-cross-world-locks and child PRD/design provide S1–S3 acceptance. Simulation feature capture/wiring is owned by the separate research topic.

## External References

No internet lookup was needed: this is source-grounded internal ownership research, with no recommendation to alter Graal behavior. Dependency/version evidence is `SimpleSignals/build.gradle:30–31,99–102` (Graal 24.2.2). Runtime API behavior above is limited to observable use in this code and must be validated by the formal Graal tests, not inferred as new engine cancellation guarantees.

## Caveats / Not Found

- No Gradle/test execution was performed by this researcher. The previous batch's green tests do not cover new S1–S3 behavior.
- There is no current catalog hot-reload API; preserve load-once behavior rather than adding one.
- Initial install currently runs via world lifecycle callbacks and performs existing definition I/O there. Moving new Graal initialization to a worker is required; a full unrelated lifecycle redesign is not implied.
- Exact neutral facade/domain-assembler signature must be agreed with the simulation/host researcher and implementers before edits. Full platform split is out of scope.
- The proposed install reservation/gating is a minimal implementation direction, not a verified algorithm; independent review must exercise remove/late failure/same UUID races and retained host source registration.
