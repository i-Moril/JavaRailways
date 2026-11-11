# Research: Signal Wand settings and persistent status

- Query: Replace the command-selected/PDC-discriminated Signal Wand UX in `4a0189b6` with one Wand identity, settings-page signal type selection, and a persistent held-tool status display while preserving the current stale-operation guards.
- Scope: internal
- Date: 2026-10-08

## Findings

### 1. There is a Wand settings convention, but no shared settings page

`AbstractWand` supplies the common gesture: while a recognized Wand is in the main hand, Shift + right-click cancels the interaction and calls the Wand-specific `showSettings` implementation (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/tool/AbstractWand.kt:170-200`). Shift + F cycles the enum mode (`AbstractWand.kt:162-168,224-234`); held-slot mode switching is deliberately disabled (`AbstractWand.kt:41-47`).

Each host Wand owns its own page and renderer rather than contributing to a global page:

- `RailWand` owns a private `ConfigUIRegistry` and `DialogRenderer` (`RailWand.kt:584-589`). Its root settings menu is opened by `showSettings -> showModeSettings`; the root offers general, geometry, and decoration pages, and confirmations copy the per-player ConfigUI session back into the Wand state (`RailWand.kt:1056-1058,6135-6169`; state/session synchronization is at `RailWand.kt:992-1047`).
- `RailObjectWand` also owns a separate private registry/renderer (`RailObjectWand.kt:200-202`). In waypoint mode, Shift + right-click opens a menu of destination roles; choosing one immediately re-reads the active item state, changes `defaultWayPointType`, persists it, and reports the new choice (`RailObjectWand.kt:402-427`). Other modes currently report that they have no settings.
- `DialogRenderer.showMenu` builds a Paper native multi-action dialog whose callbacks have one use and the Paper default callback lifetime (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/ui/dialog/DialogRenderer.kt:50-97`). `showConfigDialog` builds against a player-specific session (`DialogRenderer.kt:100-125`).

Therefore “the Wand settings page” means the established Shift + right-click convention plus a tool-owned Paper dialog. It is not one central page into which SimpleSignals can currently register a selector.

### 2. Existing Wand state is item-bound; ConfigUI sessions are only editing buffers

`AbstractWand` writes a Wand marker, random item-instance UUID, and encoded state to the item PDC when it gives the tool (`AbstractWand.kt:49-66,150-159`). Its in-memory cache is keyed by player but bound to the item UUID. On tool change it writes back the old bound state and decodes the new item's PDC (`AbstractWand.kt:96-141`). On quit it persists and removes the cache (`AbstractWand.kt:215-221`). Corrupt encoded data is preserved rather than silently replaced (`AbstractWand.kt:102-129`). This means mode/config survives tool swaps and server restart because the authoritative durable copy travels with the individual item.

`ConfigUIRegistry` player sessions are not durable settings ownership. They are a concurrent per-player local-value map (`Basic/src/main/java/work/art1st/javarailways/basic/impl/config/ConfigUIRegistry.kt:21-46,354-404`); RailWand explicitly copies item state into the session before opening and copies confirmed values back into item state (`RailWand.kt:992-1047`). RailWand removes that session on quit (`RailWand.kt:1064-1074`).

The rejected implementation in `4a0189b6` differed: the PDC key `signal_wand_type` was both item recognition and the selected type; the command either listed types or issued a type-specific item. Its operation session was transient and only protected one in-flight click. This was exactly the rejected UX: a type was selected before the Wand existed, and changing type required another command/item. The current correction described below supersedes that shape in place.

### 3. There is no general persistent Wand status bar

No reusable boss bar, sidebar, or persistent Wand action-bar owner exists in the current source. `AbstractWand` sends one-shot action bars after mode changes, selection clearing, and missing-settings responses (`AbstractWand.kt:180-200,224-240`). RailWand and RailObjectWand also use one-shot action bars for operation feedback.

The closest proven persistent action-bar pattern is manual train control. `TrainHandleInputListener` starts one repeating **entity scheduler** task per active player, refreshes the action bar every five ticks, cancels and removes the task when control ends, handles the scheduler's retired callback, removes it on quit, and cancels everything on stop (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/control/TrainHandleInputListener.kt:15-31,56-82,99-107,120-145`). This matches the Folia rule that player inventory/message/dialog work belongs on `runForEntity*`, not the global or async scheduler (`.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md:93-103`).

SimpleSignals already uses the same lower-level Paper entity scheduler for repeating route/section previews, wraps the task in `AutoCloseable`, and closes on retired/player/world/tool/plugin lifecycle changes (`SectionWandHandler.kt:35-55,154-190`; `RoutePreviewWandHandler.kt:31-65,168-212`). A Signal Wand status task should follow that lifecycle. It must not inspect a player's hand or call `sendActionBar` from a global/async task.

There is currently no arbitration between persistent action-bar producers. A Signal Wand bar would overwrite the train-control bar if both were active; ordinarily holding/using an administrative Wand should be treated as the active presentation. A future generic status API should define owner/priority semantics before multiple simultaneous persistent producers are encouraged.

### 4. No current public extension seam can accept the SimpleSignals selector

The `ConfigUI` interface is public and supports extension contributions (`Basic/src/main/java/work/art1st/javarailways/basic/api/ui/config/ConfigUI.kt:5-55`), and `ConfigGroup` allows extensions by default (`ConfigGroup.kt:21-29`). However, RailWand's and RailObjectWand's registry instances are private implementation fields and are not registered as Bukkit services. `DialogRenderer` is under the host implementation namespace, not `bukkit.api`, and no dialog/Wand/status service appears in the registered public service set (`BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/api/internal/JavaRailwaysServiceRegistration.kt:15-40`). SimpleSignals currently loads only observation, infrastructure, signal, rail-target, and model services (`SimpleSignals/src/main/java/work/art1st/javarailways/simplesignals/SimpleSignalsHostServices.java:15-59`).

SimpleSignals therefore cannot register into the existing RailWand page without importing private host implementation or reaching into a private registry. It also should not subclass `AbstractWand`: the constructor requires the concrete `BukkitPlugin` and the class lives in the host tool implementation package (`AbstractWand.kt:49-56`), contrary to the add-on dependency boundary.

For this corrected vertical slice, no new host seam is required: SimpleSignals owns the Signal Wand and may build its own Paper native selection dialog, following the same Shift + right-click convention. This keeps dependency direction `SimpleSignals -> Bukkit/Paper public API + JavaRailways public services`; BukkitPlugin does not learn SimpleSignals signal types.

If multiple add-ons later need the same mechanics, the smallest honest host seam is a registered `bukkit.api.ui.WandUiService` (or narrower `PlayerDialogService` plus separately designed status arbitration), implemented in `bukkit.api.internal`. It should accept the add-on `Plugin` owner, player, immutable bounded choice values, current choice, and callback; all callbacks/UI must run on the player owner and be invalidated when owner/player retires. A generic status portion must be an owner-keyed lease/registration with priority and explicit close/owner-disable cleanup, not a raw global action-bar timer. Adding that API now would broaden this fix and still would not replace SimpleSignals' item state or click stale checks.

### 5. Recommended corrected reviewable batch (amend `4a0189b6`)

Keep the already-reviewed backend mutation and its catalog/publication/topology/revision checks. Replace only the item/command/player UI shell:

1. `/simplesignals wand signal` gives one generic Signal Wand. Remove the command's `signalType` argument and list-as-command UX. Require a ready, non-empty catalog and initialize the new Wand deterministically to the first entry in sorted `signalType` order. The command should not encode a distinct item kind per type.
2. Give the item two separate PDC concepts: a stable Signal Wand marker and encoded current state. Current unpublished state should contain at least a random `itemId`, a monotonic `stateRevision`, and `signalType`. `signalType` remains a namespaced catalog identity, not the item discriminator. This directly corrects the unpublished shape; do not add an old-reader/upcaster or bump an unrelated world schema.
3. Shift + right-click opens a Signal Wand-owned Paper dialog populated from the **current** catalog, sorted by `signalType`, with the current type marked. Selection callback must re-read the current main-hand item and require the same `itemId` and opening `stateRevision`, permission, enabled plugin, and a still-current catalog entry before incrementing `stateRevision` and writing the new type. A stale dialog tells the player to reopen instead of mutating another/swapped Wand.
4. Ordinary click captures the full item state `(itemId, stateRevision, signalType)`. Preserve the current world/catalog/publication capture, worker lookup, topology/direction checks, and manager patch transaction. Every return to the player owner re-reads the main-hand item and requires exact state equality. This preserves current invalidation and additionally closes the “change away and back” hole that comparing only `SignalWandTool(signalType)` leaves (`SignalWandHandler.kt:104-160`).
5. While the Wand is in the main hand, a repeating player-entity task sends an action bar such as `Signal Wand | type: automatic:jr-four-light`; if the selected type is no longer in the catalog, show it as missing/unavailable but do not silently rewrite it. Refresh at a moderate interval (the existing persistent control bar proves five ticks; 10 ticks is enough for a tool status and gives transient feedback more visibility). Start/refresh after give, join, held-slot/swap/inventory/drop/pickup changes and interaction; cancel when no Signal Wand is held, permission/plugin/player validity fails, the entity scheduler retires, player quits, or the plugin closes. Keep status lifecycle separate from the one-in-flight world edit session.
6. Selection is item-bound and therefore intentionally survives tool swap and restart. It should **not** be player-global: two separate Wands may retain different selected types, item transfer retains the tool's configuration, and a new Wand receives the deterministic catalog default. Transient click sessions/status task handles do not survive restart.
7. Do not add a BukkitPlugin-to-SimpleSignals dependency, modify RailWand's private settings registry, or create a generic cross-plugin status framework in this amendment. Those are separable host-UX work.

This batch remains reviewable because the world definition mutation, signal model/JS behavior, and placement semantics from `4a0189b6` do not change. The item state is the only persistence boundary being corrected, and it is unpublished tool data.

### 6. Affected files and tests

Expected source changes:

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandler.kt`: command contract, marker/state codec, settings routing/dialog, state-aware stale guards, and held-status lifecycle. Splitting the codec/dialog/status helper into adjacent files is reasonable if the handler becomes difficult to review.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/SimpleSignalsPlugin.kt`: only if already-online players need an explicit status bootstrap or a separately owned helper must be closed.
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/authoring/SignalWandHandlerTest.kt`: replace type-specific command/PDC expectations and retain all existing deterministic worker/owner/staleness coverage.
- SimpleSignals locale resources if this batch localizes the new settings/status text; the current handler is English-only, so localization can be a separate explicit scope decision rather than silently expanding the amendment.
- Current task artifacts and `.trellis/spec/simple-signals/backend/section-authoring-wands.md`: replace the rejected command/listing contract and document item state, settings selection, status lifecycle, exact stale equality, and no live Folia validation claim.

Required focused tests:

- one marker recognizes every Signal Wand regardless of selected type; state round-trip includes `itemId`, `stateRevision`, and namespaced `signalType`; malformed/corrupt state fails closed without becoming an editable tool;
- command has no type argument, rejects not-ready/empty catalog, and chooses the stable sorted default;
- settings are sorted/current-marked; same-item current callback persists the new type and increments revision; swapped item, changed revision, removed catalog type, lost permission, or disabled plugin rejects without writing;
- status text reflects current type/missing type; exactly one task per holder; it stops on hand change, retired callback, quit, and close; all player access occurs on the entity owner;
- duplicate clicks still coalesce; permission/world/tool invalidation still discards callbacks; same item/type with a changed revision is now also stale;
- existing backend and real manager test still proves one patch changes definition revision, type, JS output, model, placement, and atomic persistence.

### 7. Ambiguous UX decisions

No decision is required to correct the rejected command flow if the deterministic-first-type default and item-bound persistence are acceptable.

One broader ambiguity should be surfaced but need not block this amendment: does “常驻状态栏” mean Signal Wand only, or a new common status system for RailWand, RailObjectWand, TurnoutWand, and add-ons? Current code has no common arbitration mechanism. The signal-only action bar is the smallest faithful correction; making all Wands participate should be a separate BukkitPlugin API/design batch because it changes host ownership and conflict behavior with train-control action bars.

## Related Specs

- `.trellis/spec/bukkit-plugin/backend/interaction-render-persistence.md`: item-instance PDC state, corruption behavior, dialogs, add-on service boundary.
- `.trellis/spec/bukkit-plugin/backend/lifecycle-and-threading.md`: entity-owner scheduling and retired callback rules.
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`: current Signal Wand command, intent, mutation, stale/failure, and performance contract that must be corrected.
- `.trellis/spec/javarailways/shared/persistence.md` and `.trellis/spec/javarailways/shared/versioning.md`: unpublished persisted format correction without speculative runtime compatibility/version bump.

## Caveats / Not Found

- No generic persistent Wand status bar, boss bar, sidebar, `WandService`, dialog service, or externally reachable host ConfigUI registry was found.
- This is a source/static audit. Paper native dialog rendering, action-bar persistence, entity scheduler retirement, and inventory event ordering were not tested on a live Folia server.
- `DialogRenderer` is reusable as source code precedent but is not a declared `bukkit.api` contract. SimpleSignals should not import it as though it were a stable add-on API.
- The corrected Signal Wand tests use injected owner/worker, dialog and scheduled-status seams. They verify the offline
  state/lifecycle contract without converting the suite into a mock-server-only test; native Paper rendering and real
  scheduler/event ordering remain part of the live Folia boundary above.
