# Static extension text mounting — 2026-11-10

User authorized next batch after committed text backend3aaa3af3. Scope is generic runtime mount + actual Decorations category-default XML first frame, not merely a fake provider/example. Work only in ddf5; report before commit, no push/deploy.

## Public value boundary (no Bukkit/world/car/backend handles)

Add capability `PartExtensionRenderer : PartExtensionProvider { fun prepare(context: PartExtensionRenderContext): PartExtensionFrame }`. Metadata-only providers have no runtime capability and retain their group behavior.

- `PartExtensionRenderContext(packageName:String, packageHash:String, part:PartExtensionPart, resources:PartExtensionResources?)`: exact owning CAR source package snapshot and its captured resources, not catalog or disk. Imported installation geometry uses this car's package styles; do not infer resource ownership from part namespace.
- `PartExtensionFrame(texts:List<PartExtensionText>)`: detached immutable list, max1024 nodes; empty is inactive.
- `PartExtensionText(text:Component, pose:ModelPose=IDENTITY, scale:ModelScale=UNIT)`: reuse existing finite/unit-quaternion/nonnegative scale contracts. Coordinates/scales already physical host model space, no SimpleDisplays types in host.

The host owns preparation dispatch, all entities, registration/model identity, transform/visibility/cleanup. Addon code returns only values. Keep existing compile-on-validation/reload rejection behavior unchanged; current startup validation still runs in the existing startup phase. New runtime prepare/compile/draw must never execute in a render region/owner callback.

## Worker and attachment ownership

DefaultPartExtensionService owns a small worker pool (2workers), one preparation job/pending result per live attachment. Queue/memory bounded by live mounts (no historical updates); destroy/retire cancels/removes queued job. Tasks that already acquired provider may finish, but exact entry + attachment token invalidates before publication; no stale results/owners can install. Service shutdown retires mounts and shuts its workers, with no waiting on main thread for addon callbacks. Do not create addon executors or use per-frame jobs.

Worker publishes only immutable result/failure. Prefer consuming pending data on existing host-owned Composite update/root-only commits, not scheduling Bukkit work from future callbacks. Composite keeps a list of extension groups built once; far/root-only updates also drain pending content. Extension group owns emitted text separately from inherited attachedModels/animations and behavior animation enumeration. Never mutate the saved tree or splice into its animation-slot ordering.

Native and packet text leaves reuse shared root/passenger motion, initial/group/animation transforms, smoothing, viewRange and backend cleanup. Bind group to Composite root during synchronous construction; apply latest committed group transform/root position before exposing late text. No captured initial spawn location for a moving model. Registry preparation starts only after construction succeeds/first commit, not while a partial ModelConstruction tree is owned.

Late audience uses only inherited show/hide membership, not global scans. Replay on player owner, with exact viewer membership + attachment validity checked at execution. Child spawn must precede refreshed parent passenger packet; current EntityLib addViewer sends only the child's own passenger state, so add internal backend refreshPassengersFor(player) as necessary through existing trainPacketBundles using owner-captured IDs, not cross-owner Bukkit getters. No independent sender or second audience system. Keep ordinary child/root show/hide ordering.

Retirement first invalidates registration/model tokens, clears pending output and cancels queued preparation. It removes only generated visuals, keeping original group/children. Track in-progress owned apply and all partial created visuals so cleanup completion cannot finish before rollback/removal. Backend destroy self-dispatches; no Bukkit side effects under registry locks. Model rebuild/world unload destroy exact group identity; queued show and late worker output cannot resurrect it. Expose asynchronous completion for registration close (closeAsync) if needed so consumers can observe real cleanup, without blocking on region work.

Provider installed/re-enabled after a tree already exists activates on next ordinary model rebuild; no runtime registry scan/hot auto-rebind. Missing provider/metadata-only/failed prepare => unchanged group, one contextual runtime diagnostic per failed preparation. No per-tick retries or current-disk fallback.

## Actual Decorations adapter

DisplayPartProvider implements runtime capability, keeps current strict validate semantics. On host worker, parse exact captured resources/config, resolve category default, ask optional API-free bridge for first frame. Missing SimpleDisplays/default template returns empty, normal car remains usable.

Use fresh privately-owned `DisplayService.compile` -> `draw(emptyMap(),0L)` -> copy primitives -> `finally template.close`. Never borrow the evictable validation cache; close on success/failure, preserve original errors with cleanup suppressed. Bridge and runtime public DTO descriptors stay API-free. Shared DisplayService is never closed. Connection retired during prepare cannot publish through that bridge. Once a frame has been detached, existing static text is independent of SimpleDisplays engine lifetime and may remain until model rebuild/Decorations retirement; no hidden library reference remains. Decorations retirement always removes its generated text.

Fit entire logical canvas centered with preserved aspect ratio: `u=min(physicalWidth/logicalWidth, physicalHeight/logicalHeight)`, x=(draw.x-logicalWidth/2)*u, y=(logicalHeight/2-draw.y-draw.size)*u, z=0. Text glyph scale on all axes is draw.size*u*40/9, matching standalone renderer's nine-pixel line-height and 0.025m TextDisplay pixel scale. Keep native/packet conventions identical. Validate derived finite coordinates/scales; no renderer guesses or schema fields. This static pass evaluates XML/embedded JS once with empty data and elapsed0; missing required data errors only affect addon visuals. No periodic/animated/per-viewer refresh or train/PAPI adapter in this batch.

## Ownership / implementation

Host implement: new public DTO/capability; internal service worker/attachments; internal ExtensionPartModel/TextModel/root hook; builder/manager/CarExtension exact source plumbing; backend late passenger refresh if needed; tests. Do NOT edit BukkitPlugin.kt, Decorations, task/spec/docs/schema.

Decorations implement: provider/runtime optional bridge fresh compile/draw/conversion; tests; no Gradle. Wait exact agreed API names above before code. Root: composition if needed, docs/task/spec, serial builds and actual Paper probes. Reviewer: read-only whole scope.

## Required verification

- Exact captured/pinned source isolation, imports/inline names, transformed/animated group and child preservation, real root binding, current pose after moving while preparation pending.
- Worker-only provider, missing capability, failure, cancel/retire/rebuild/worlddestroy and late result, partial backend create/mount failure and cleanup completion.
- Existing and late viewers: child-before-parent passenger packet, removed membership suppresses queued show, no player lookup offowner, no network/global audience scan.
- Real Decorations plain XML output/geometry/fitting + malformed/draw failure/close, optional API-hidden classloader new methods, shared service remains open.
- Focused host/addon suites, fullhost known expressionfailure separated, shadowJar/Devcompile/thinaddon gate.
- Actual isolated Paper exercises generic host runtime + real Decorations XML with both backends, latest movement and retirement cleanup. No real client/Folia acceptance without separate runtime evidence.
