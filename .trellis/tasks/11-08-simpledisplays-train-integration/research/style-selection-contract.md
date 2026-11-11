# Style selection continuation — 2026-11-10

User confirms NO extra culling; complete end-to-end flow first. Continue the prior authorized style selection/override work after distance. Integrated tests/Paper/Folia/client remain deferred until the work is complete. No commit/push/deployment; preserve all operational/distance changes in ddf5.

## Independent static selection now

Smallest gap: DisplayPartProvider only reads package defaults[category], while a screen cannot declare its own template default. Owner is Decorations, not host model schema. Add nullable optional `template:String?=null` to DisplayPartConfig and current decorations display payload schema. Absence preserves category-default behavior, explicit value must be a nonblank valid template ID, null/wrong scalar/unknown keys fail. No format version bump: additive field with explicit null-in-memory/absent-on-disk default. No old-format reader or resource schema change.

Precedence for this independent slice: part.config.template > captured resources.defaults[category]. A missing default keeps a non-rendering group; an explicitly configured unknown template is invalid even when a category fallback exists. Validate explicit references at package validation/open boundaries against exact owning CAR package snapshot, including imported geometry; no current disk/catalog/name alias lookup. Explicit reference with no resources fails validation rather than silently becoming inactive. Existing no-reference/no-resource loads remain legal. Existing XML/JS source/cache semantics remain unchanged.

Expected owned files for implement sub-agent: Decorations DisplayPartConfig.kt, DisplayPartProvider.kt, schemas/display-part-v1.schema.json, corresponding config/provider tests. Root owns README/spec/task research. No host source change for static default. Author deferred tests for point wins/category fallback/no default/wrong reference/missing resources/type strictness and existing interior policy preservation. Do not run tests/builds or commit; root only compiles.

## Runtime override scope pending user text clarification

Previous plan proposed train-instance+category, runtime-only, clear on split/join. Task design explicitly deferred addressing/lifetime until agreed. Async text question has been presented: train instance/runtime, fixed-unit inheritance, or cross-restart persistence. These are domain behaviors, not interchangeable implementation details. Do not implement scope-dependent store/command/persistence yet; static selection above is independent and reviewable.

Once scope is selected, update this contract with actual owner/token, cleanup, package validation and session swap semantics before implementing override API. Do not infer identity from train display name or pick latest timetable cycle. Do not invent persisted compatibility paths.

## Scope comparison requested by user

The user answered the scope question by asking for implementation differences, not by selecting one. Train-runtime scope needs exact process-local train instance plus formation-change detection: a surviving CoreTrain can keep its publicationStreamId after a split, so that UUID alone cannot implement 'clear on every split/join'. Unit scope uses actual world/unitId and preserves override across physical formation changes, but unregistered/partial units have no binding. Persistence is an independent axis and can apply to either scope; the three question options were common combinations, not mutually exclusive architecture fundamentals. Persisted train binding cannot store process-local publicationStreamId and must resolve durable identity on load, retaining unknown template/changed package errors rather than silently redirecting.

## Confirmed fixed-unit runtime slice (user selected option2)

User selected fixed unit+category. Runtime-only as the compared option; preserve through reverse/split/couple and model rebuild, clear on plugin disable/restart and accepted world unload. No additional culling. Tests/runtime acceptance still deferred; only compile source/test source now. No Core/SimpleATO or persisted format changes.

Smallest gap: static provider chooses a template once. Decorations must bind each live screen to real unit facts even while JS is paused, validate unit-category override against ALL matching captured source catalogs, select override > part default > category default, swap only affected private sessions and reject old host results. Root owns generic host/public seams and docs. Core-style agent owns addon service/store/routing/provider/tests. Command agent owns plugin/command/descriptor/tests, imports only the service API and RuntimeDisplayStyles lifecycle.

### Exact host additions (root)

- `PartExtensionRenderContext.worldId:UUID?=null` appended; ModelTreeBuilder supplies spawnLoc.world.uid. World value only, no live object.
- `PartExtensionRenderSession.update(input:PartExtensionRenderInput)` default Unit, worker-serialized before optional draw, receives bounded latest metadata even hidden. No platform access/heavy evaluation here.
- `PartExtensionRenderSession.renderRevision:Long` default0; cheap thread-safe value-only, may be read concurrently on owner/control threads. Changes only when routing binding/effective selection changes; not on speed/time data. Nonnegative, not persisted/schema version.
- `PartExtensionRegistration.refreshRendering()` default Unit; real registration calls its attachments outside registry lock. Groups compare session.renderRevision, and only changed groups invalidate request generations/retire generated leaves/reset throttle, then submit latest input respecting draw visibility. Source clock retained. Root commit also checks revision so a notification cannot be the only stale-output defense.
- Attachment request has `draw:Boolean=true`. Worker always session.update(input) for accepted input, but invokes draw only when requested. Per-attachment queue remains one in-flight+latest, no new worker/scheduler. Last-viewer loss still rejects invisible late frames; later metadata-only requests update binding without JS.

### Exact addon public API / store (core-style agent)

Package `work.art1st.javarailways.decorations.api.display`:

`DisplayStyleService` methods:
```
fun unitIds(worldId:UUID):Set<String>
fun categories(worldId:UUID,unitId:String):Set<String>
fun inspect(worldId:UUID,unitId:String,category:String):DisplayStyleSelection
fun set(worldId:UUID,unitId:String,category:String,templateId:String):DisplayStyleSelection
fun reset(worldId:UUID,unitId:String,category:String):DisplayStyleSelection
```
`DisplayStyleSelection` immutable owned result properties `worldId:UUID,unitId:String,category:String,templateId:String?,availableTemplates:Set<String>,screenCount:Int`. templateId means override only, null means default resolution. No Bukkit/SDL/private host types.

Class `decorations.display.RuntimeDisplayStyles(onChanged:()->Unit={}) : DisplayStyleService,AutoCloseable` owns binding and override maps under one short lock. `clearWorld(UUID)` clears bindings+overrides and invalidates stale old-world binding registrations with a captured world epoch. `close()` closes store/clears maps, rejects late bind/set. Core agent chooses internal bind/unbind/revision helpers shared by routing. No external callbacks, compilation or close under store lock.

Bindings are keyed by private session token, carrying world/epoch/actual unitId/category/exact source template catalog/package identity. Source unit is ONLY input.data.unit.id captured by domain owner; null/unknown means no unit override. `set` requires at least one bound screen of this unit/category and validates requested ID exists in EVERY matching catalog before atomic mutation. No arbitrary first package. AvailableTemplates is intersection; inspect may report an existing override even if no current screen binding. reset may clear retained overrides while no bindings exist. Unbind last screen does NOT delete override, because rebuild/split must preserve it. No observed screen unit -> cannot set phantom override. IDs are not guessed from train name/index.

Store revisions use real changes only; expose per-key cheap revision to routing session. Keep enough revision evidence after reset so host detects clearing. Routing uses world epoch captured at open to reject old world late metadata after unload/reload. New sessions in reloaded world can bind at new epoch.

`DisplayPartProvider` gets optional `runtimeStyles:RuntimeDisplayStyles?=null`. Existing null-injection consumers retain static behavior. Actual plugin injects store. If resources/catalog is nonempty, runtime provider opens a routing session even without static default so an override can activate it; resource-less stays normal inactive. Router publishes existing interior policy, binds/unbinds from session.update, exposes current binding's store revision, and on draw lazily opens the effective private template using existing openTemplate callback. Same selected ID/source reuses session; real selection change closes old private template on worker and opens replacement. No optional API descriptors/class loading in this layer.

Unknown existing override in a newly observed source must fail locally/diagnostically, not silently fallback. Catalog binding must still exist so reset fixes it. No script evaluation in update/revision getter. Session.close unbinds and closes owned underlying template exactly once, including partial failure; optional connection retains its established ownership/cancellation.

### Commands/lifecycle (command agent)

Existing `/decorations display-distance ...` remains. Add player-only `/decorations display-style <unitId> <category> [<templateId>|reset]`: three tokens incl subcommand queries override/default status+available common templates; fourth token sets/resets. Use player's current world UUID on ENTITY scheduler; do not read live player world in async tab completion. Permission `decorations.display-style` default OP, distance remains default true. Remove root command's distance-only permission restriction; each subcommand checks its own permission. Route root dispatcher to old distance and new style handler, reuse existing entity scheduling patterns, no private host imports.

Plugin constructs `RuntimeDisplayStyles { registration?.refreshRendering() }`, injects provider, registers DisplayStyleService in Bukkit ServicesManager with owner this, registers world-unload MONITOR+ignoreCancelled listener calling clearWorld, unregisters service and closes store during disable/enable-failure cleanup. World unload cancellation preserves overrides. Quit does NOT reset unit overrides. Provider unregister retires live groups; preserve existing optional connection cleanup. Root README/spec/docs own all prose.

### Deferred checks to author, not execute

Two units/cats/worlds isolated; repeated unchanged set no revision; default override/reset; multi-package intersection and no partial writes; unbound unit cannot set but retained override query/reset; unregister/rebuild preserves override; unit movement to another train rebinds same unit; unit null detaches; late old-world epoch rejected; callback outside lock; warm metadata binding without JS; private template reuse/swap/close/failure; host command refresh rejects in-flight old style and only changed group retires; permission/query/set/reset/entity scheduler. Compile only now.
