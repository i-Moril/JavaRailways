# Research: Folia shutdown model cleanup ordering

- Query: Investigate the confirmed full-server-stop failure in which SimpleSignals starts asynchronous owner cleanup, JavaRailways is disabled before that future finishes, and 13 model/editor handles fail with “disabled before ... cleanup completed”. Determine the smallest correct non-blocking fix, including ordinary model displays, EntityLib packet visuals, and editor text.
- Scope: mixed (repository code/spec/tests plus the exact Paper 1.21.11 API/server sources resolved by this build)
- Date: 2026-11-07

## Findings

### Conclusion

`Bukkit.isStopping()` may be used for the smallest correct fix, but **not** as a reason to call the existing failure path and merely suppress its error, nor as a bare `cleanup.complete(null)` shortcut. The correct distinction is:

1. During an ordinary add-on disable or host hot-disable/reload (`Bukkit.isStopping() == false`), `closeOwner` still means actual target-owner deletion completed. The present fail-loud behavior remains correct if JavaRailways is disabled first.
2. During JavaRailways `onDisable` in an irreversible whole-server stop (`Bukkit.isStopping() == true`), all currently supported visuals are transient. The presentation services may synchronously **retire their logical ownership and hand physical disappearance to the already-running server/client/JVM teardown**, without scheduling region deletion. The same owner-cleanup futures may then complete normally.

This is the smallest fix because it reuses the existing `closeOwner` futures, owner registries, and host `close()` boundary. It does not add a second unload coordinator, does not block a plugin-disable/global/region callback, and covers all causes that reach the server's terminal stop path rather than only the console `stop` command.

The current written model/editor specs do not yet permit this distinction: they say non-persistence does not replace normal cleanup and that early host disable must not report success. The specs and tests must therefore be narrowed explicitly: “normal cleanup” retains actual deletion, while terminal whole-server teardown is a separate successful terminal-retirement outcome. Without that contract change, a `Bukkit.isStopping()` success branch would contradict the present contract even though it is technically safe for the current backends.

### Confirmed failure chain

- `SimpleSignalsPlugin.onDisable` closes local tools, starts `worldRuntimes.clearLocal()`, immediately starts editor-text `closeOwner`, combines both futures, and only observes them asynchronously; it deliberately does not block the disable callback (`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt:144-181`).
- `SimpleSignalsWorldRuntimeManager.clearLocal` first closes every runtime on common-pool workers and only then calls the model service's `closeOwner(plugin)` (`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt:1691-1726`). Therefore the add-on's `onDisable` can return before model owner cleanup is even requested.
- JavaRailways immediately closes editor text and model presentation at the beginning of its own `onDisable` (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt:377-384`).
- Model service close captures all outstanding handles. If the host is already disabled, it completes each exceptionally with “JavaRailways was disabled before model cleanup completed” and clears its task dispatcher (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultModelPresentationService.kt:188-205`). Editor text has the same shape (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultEditorTextService.kt:68-79`). This accounts for the reported repeated failures; it is an ordering/lifecycle diagnosis, not 13 independent render defects.
- The existing `ModelOwnerLifecycleListener` starts add-on owner cleanup on `PluginDisableEvent` but cannot await it (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/ModelOwnerLifecycleListener.kt:10-23`). It cannot create an asynchronous barrier between Paper's sequential plugin disables.

### What Paper/Folia actually makes available

The build targets Paper API/dev bundle `1.21.11-R0.1-SNAPSHOT`, paperweight `2.0.0-beta.19`, and declares Folia support (`BukkitPlugin/build.gradle:1-6,94-107,285-286`). The locally resolved exact server/API sources establish:

- Paper disables plugins in reverse order. For each plugin it fires `PluginDisableEvent`, calls `JavaPlugin.setEnabled(false)` (which invokes `onDisable` after the enabled flag was cleared), then closes the classloader and cancels Bukkit, global-region, and async tasks. There is no future/barrier between dependent plugins (`PaperPluginInstanceManager.java` in the resolved paperweight `output.jar`:161-168,227-312; `JavaPlugin.java` in the resolved API sources:272-286).
- `MinecraftServer.stopServer()` marks `hasStopped=true`, disables all plugins, waits only for Paper's async task shutdown, then removes players and saves worlds (`MinecraftServer.java` in the resolved paperweight `output.jar`:1000-1059). It does not continue region ticks to drain arbitrary entity-owner deletion jobs between SimpleSignals and JavaRailways disable.
- `CraftServer.isStopping()` returns `MinecraftServer.hasStopped()` (`CraftServer.java` in the resolved paperweight `output.jar`:2937-2939). Because `hasStopped` is set before plugin disabling, `Bukkit.isStopping()` is true in both add-on and host disable callbacks during this terminal path; it remains false for an ordinary plugin disable.
- Paper 1.21.11 exposes no pre-stop lifecycle event. Its `LifecycleEvents` contains only command, tag, and datapack-discovery registrations (`paper-api ... sources.jar`, `LifecycleEvents.java`:19-43). The API has command events, but no general cancellable “server is about to stop” event.

Consequently there is no repository-dependency API that can pause the built-in shutdown before plugin disable, asynchronously wait for Folia region work, and then resume it. Service shutdown also cannot safely “drain” already scheduled region work: Paper has entered terminal stop, disables plugins sequentially, and then cancels their schedulers. Blocking JavaRailways `onDisable` waiting for those jobs risks deadlock and violates the existing non-blocking contract.

### Why terminal teardown can own the current visuals

The claim is deliberately limited to the closed set of production backends constructed by `DisplayRenderBackendFactory` (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/DisplayRenderBackendFactory.kt:6-11`):

- Native ordinary item/block displays are configured `isPersistent = false` and hidden by default (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/NativeDisplayRenderBackend.kt:44-51`). Their normal `destroy` must use the entity owner to call `entity.remove()` (`NativeDisplayRenderBackend.kt:110-114`), which is precisely the operation that cannot be awaited from shutdown.
- Editor labels are native `TextDisplay` entities configured hidden by default and `isPersistent = false` (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultEditorTextService.kt:195-207`). Their normal removal also requires entity/location ownership (`DefaultEditorTextService.kt:239-272`). They must receive the same terminal-retirement treatment as ordinary models; fixing only model presentation leaves the editor-text half of `SimpleSignals.modelCleanup` exceptional.
- EntityLib ordinary visuals instantiate `WrapperEntity`, configure packet metadata, and spawn it into EntityLib's in-memory container; no Bukkit entity or world-persistence object is created (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/EntityLibDisplayRenderBackend.kt:26-58`). The pinned EntityLib 3.0.3 source shows `WrapperEntity.spawn` sends spawn/metadata packets and registers in an `EntityContainer`, while `remove` only removes from that in-memory parent and sends destroy packets (`api-3.0.3-SNAPSHOT-sources.jar`, `WrapperEntity.java`:89-105,154-168). On terminal server stop, player disconnect and process teardown eliminate client state and the in-memory container.
- Normal service destruction does more than delete visuals: it withdraws the audience adapter, removes it from `RenderChunkManager`, unregisters glow packet identities, and destroys the model (`DefaultModelPresentationService.kt:392-403`). Terminal retirement must close the service's logical registries/tasks and revoke publication intent, but it must not pretend those region-owned physical delete calls ran.

World save happens after plugin disable. Nonpersistent native display entities are therefore not written back as durable model/editor artifacts. EntityLib wrappers never enter world persistence. This makes terminal handoff safe for the current backends but says nothing about a future persistent backend; a future backend must not be admitted to this contract without proving an equivalent teardown property.

### Recommended minimal design

Keep one lifecycle, centered on the existing owner futures:

1. Add one private predicate/method per service for terminal retirement, based on `Bukkit.isStopping()`. Use it from handle `close`, service `close`, and scheduler/reconcile paths; checking only in service `close()` is racy (details below).
2. Mark the service closed and make worlds/creation unavailable exactly as today. Close highlights and packet-side helpers synchronously where their current close is already safe. Clear/coalesce service task registries so no new service work is launched.
3. If the host is still enabled, retain the existing actual-deletion path. If the host is disabled and this is **not** a terminal server stop, retain the existing exceptional `unavailable/finish` path and diagnostic.
4. If `terminalServerStop` is true, invoke a distinct idempotent handle method such as `retireForServerStop`, not `unavailable(null)` and not backend `destroy()`:
   - under the existing state/registry locks, set `stopped`, drop pending/accepted intent, remove the handle from `active` and `outstanding`, and complete the existing cleanup future normally;
   - withdraw an ordinary audience adapter if that operation is only the current volatile publication flip; do not call `RenderChunkManager.remove`, `DisplayVisual.destroy`, `Entity.remove`, or any dispatcher;
   - for editor text, do not iterate `shown` and call entity removal. Its native text displays are handed to teardown together with the ordinary native displays;
   - do not mutate per-handle fields documented as serial target-task-owned merely to make them null. A region task may already be in flight. The terminal contract permits those transient references/entities to survive until server/JVM teardown; the essential synchronous action is preventing new intent and completing/removing the logical owner records.
5. Let the existing SimpleSignals combined future complete as a result. Do not add a SimpleSignals shutdown special case and do not create a second owner-unregister/unload state machine.

The success semantic should be named/documented “owner resources terminally retired” rather than “every entity remove callback executed”. `closeOwner` remains the one completion object; callers need no new public API. This is a semantic refinement for terminal shutdown only, not a compatibility path or persistence migration.

Concurrency is the main implementation trap. Paper clears `host.isEnabled` immediately before entering JavaRailways `onDisable`. A previously accepted model `dispatch` can observe the disabled host and call `unavailable(failure)` (`DefaultModelPresentationService.kt:281-296`), or editor reconciliation/removal can fail its host-enabled scheduler checks (`DefaultEditorTextService.kt:129-163,274-293`), **before** service `close()` gets to terminally retire the handle. Once that future is exceptionally completed, service `close()` cannot turn it into success. Therefore terminal retirement must be the common outcome when a handle is closed or a host-disabled/scheduler-rejected path is reached while `Bukkit.isStopping()` is true; do not implement the check only in the two service `close()` methods. A genuine failure that completed before terminal shutdown remains observable, but failure to perform physical deletion after terminal shutdown begins is satisfied by the teardown handoff.

`LatestAffinityTaskDispatcher.clear()` only drops its key states (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/LatestAffinityTaskDispatcher.kt:36-38`); it cannot cancel an action already accepted by a region scheduler. Model execution and editor reconciliation both recheck `stopped/closed/host` around creation (`DefaultModelPresentationService.kt:281-387`; `DefaultEditorTextService.kt:129-163,195-216`). An already-running action may still briefly finish creating a transient visual after terminal retirement, so the correctness proof must rely on whole-server teardown, not on a false assertion that no creation can occur after the future completes. The path is invalid for a hot disable, which is why the `isStopping` guard is essential.

### Tests and verification needed

Existing tests encode only the old undifferentiated contract:

- Model test asserts disabled host always makes cleanup exceptional and that backend destruction was not called (`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultModelPresentationServiceTest.kt:341-353`).
- Editor test groups host shutdown together with asynchronous/entity scheduler rejection and requires all three to fail (`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultEditorTextServiceTest.kt:260-275`).
- `BukkitServiceTestSupport` currently returns false for every unhandled boolean server method, hence `isStopping=false`; it needs an explicit controllable stopping value rather than globally changing its default (`BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/BukkitServiceTestSupport.kt:10-40`).

Required regression split:

- `isStopping=false`, disabled host: both model and editor cleanup remain exceptional; no physical deletion is claimed.
- `isStopping=true`, disabled host: both services terminally retire all owner handles, the original pre-close `closeOwner` futures and later repeated `closeOwner` calls complete normally, no region/entity deletion is scheduled, and service task/active/outstanding metrics are empty.
- Cover both ordinary production backend kinds at their contract level: native/editor entities are nonpersistent; EntityLib is packet/container-only. A fake persistent backend must not silently inherit terminal-retirement approval.
- Cover an in-flight model build and an in-flight editor spawn/reconcile so the test does not assume `tasks.clear()` cancels accepted work.
- Add a race regression in which `host.isEnabled` becomes false and an already accepted model/editor callback runs before service `close()`; under `isStopping=true` it must terminally retire rather than win the race by completing the cleanup future exceptionally.
- Run a real Folia start/stop smoke with active SimpleSignals ordinary models and editor text. Acceptance is: no “disabled before model/editor cleanup completed” messages, `SimpleSignals model/editor cleanup completed` appears once, no model/editor entities persist after restart, and hot plugin-disable behavior remains fail-loud. Unit tests cannot prove Folia region shutdown behavior.

### Rejected alternatives

- **Block or join in `onDisable`:** rejected. Physical deletion can require other Folia region owners, while the server is already disabling plugins and will cancel their schedulers. A plugin/global disable callback must not wait on work that needs those owners.
- **Keep dispatching after JavaRailways is disabled and drain later:** rejected. Current scheduling explicitly checks the host enabled state; Paper cancels tasks and closes the classloader after disable. There is no supported post-disable scheduler lifetime.
- **Only suppress the 13 errors / call `complete(null)`:** rejected unless implemented as the explicit terminal-retirement contract above. Otherwise it leaves owner registries semantically live and reports an unperformed normal deletion as success.
- **Intercept console `stop`, cancel it, clean asynchronously, then call shutdown:** technically possible for the currently observed console-driven deployment, because `ServerCommandEvent` is cancellable. It preserves actual deletion while the host is enabled, but it creates a new quiesce/command/state coordinator, must reject new handles during the wait, and fails to cover API-triggered shutdown/restart or other terminal paths. It is not the smallest general fix.
- **Manually disable SimpleSignals before issuing stop:** rejected as a general deployment fix. It runs the ordinary forced-disable/resource-owner path while the server is live and still has no platform barrier guaranteeing all region deletion before host disable.
- **Rely on reverse plugin order alone:** rejected. Reverse order determines when disable callbacks begin, not when asynchronous cross-region cleanup finishes.

## Files Found

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt` — add-on disable starts and observes the combined model/editor cleanup future.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SimpleSignalsWorldRuntime.kt` — delayed model `closeOwner` after asynchronous runtime cleanup.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/BukkitPlugin.kt` — host closes presentation services at the beginning of disable.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultModelPresentationService.kt` — owner registry, task serialization, actual destruction, and current disabled-host failure.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/DefaultEditorTextService.kt` — editor text owner lifecycle and nonpersistent TextDisplay implementation.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/ModelOwnerLifecycleListener.kt` — non-blocking PluginDisableEvent owner cleanup trigger.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/NativeDisplayRenderBackend.kt` — nonpersistent native display creation and region-owned removal.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/backend/EntityLibDisplayRenderBackend.kt` — packet-only EntityLib visual creation/removal.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/render/chunk/RenderChunkManager.kt` — audience removal schedules player-owner hide work.
- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/util/LatestAffinityTaskDispatcher.kt` — bounded in-flight work; clear does not cancel accepted actions.
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultModelPresentationServiceTest.kt` — current disabled-host failure contract.
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/DefaultEditorTextServiceTest.kt` — current editor host/scheduler failure contract.
- `BukkitPlugin/src/test/java/work/art1st/javarailways/bukkit/api/internal/BukkitServiceTestSupport.kt` — server proxy seam needed to test `isStopping`.
- `BukkitPlugin/build.gradle` — exact Paper/paperweight/Folia and EntityLib dependency versions.

## External References

- Local Paper 1.21.11 API source: `/home/user/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT/a3a811fd1e99bce749c4e6db9991e45294438d00/paper-api-1.21.11-R0.1-SNAPSHOT-sources.jar` — `Bukkit.isStopping`, `JavaPlugin.setEnabled`, and the absence of a pre-stop `LifecycleEvents` type.
- Local exact paperweight server source: `/home/user/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_c9a53f01acfb65c4a1dc2a7800c5c82b06fd8bca489383ff45ca9abd7808383a/output.jar` — reverse plugin disable order, disable cleanup sequence, `CraftServer.isStopping`, and `MinecraftServer.stopServer` ordering.
- Local pinned EntityLib source: `/home/user/.gradle/caches/modules-2/files-2.1/io.github.tofaa2/api/3.0.3-SNAPSHOT/52adc4922ba4b56334e6352a0c4fc00cc2dd901e/api-3.0.3-SNAPSHOT-sources.jar` — packet/container-only `WrapperEntity` lifecycle.

## Related Specs

- `.trellis/spec/bukkit-plugin/backend/model-presentation-api.md:273-288` — target-owner scheduling, non-blocking cleanup completion, current strict unload order, and current statement that non-persistence does not replace cleanup.
- `.trellis/spec/bukkit-plugin/backend/editor-markers.md:67-80` — nonpersistent editor text, closeOwner completion, current early-host-disable failure acceptance.
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md:5-16,36-38` — host lifecycle, no blocking host disable, and existing precedent that whole-server shutdown is not a forced owner fault.
- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md:152-165` — shared model/editor ownership and current “queued deletion is not completion” rule.
- `.trellis/spec/simple-signals/backend/index.md:474-478` — SimpleSignals async cleanup observation and no second unload gate.
- `.trellis/spec/simple-signals/backend/model-presentation.md:347-355` — combined model/editor completion and its present “entities deleted” interpretation.

## Caveats / Not Found

- No Paper/Folia pre-stop lifecycle event or shutdown barrier exists in the exact dependency version used by this repository.
- The runtime count of 13 failures is accepted as the confirmed observation supplied with the task; this research traces why each outstanding handle receives that result but did not reproduce the live server stop.
- Terminal retirement is safe only because every current production visual is nonpersistent or packet-only and the process is irreversibly stopping. It must never be selected for `/reload`, manual plugin disable, host replacement while the server remains live, or a future persistent backend.
- Service-owned handle fields have target-task ownership and accepted region work cannot be canceled by `LatestAffinityTaskDispatcher.clear()`. Implementation should not add unsafe cross-thread field clearing merely to make the shutdown path look cleaner.
- A real Folia restart smoke remains necessary; source and unit-test evidence do not prove client-visible disappearance or absence of late shutdown callbacks.
