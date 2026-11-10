# Static extension mounting validation — 2026-11-10

Base: isolated worktree `/home/user/.codex/worktrees/ddf5/JavaRailways`, branch `codex/simpledisplays-train-integration`, committed HEAD `3aaa3af3`; this batch remains uncommitted for the requested report. No main-checkout source, live server, push or deployment changed.

## Implemented boundary

- Public `PartExtensionRenderer`, exact `PartExtensionRenderContext`, detached `PartExtensionFrame` (max1024 texts), public ModelPose/Scale values; registration exposes asynchronous cleanup completion. No Bukkit/world/car/backend objects cross to provider.
- Host-owned two-worker, once-per-attachment preparation. Existing full/root-only Composite commits install results, bind text to shared root, use latest committed group transforms/held animation and inherited audience. Generated leaves remain outside original tree/animation traversal.
- Retirement invalidates queued/late work, waits for partial apply/rollback deletion and removes only generated text. Existing viewer replay checks exact Player/membership and sends children before refreshed parent passengers via the existing packet bundle path. Actual EntityLib parent passenger state is also cleared on child destruction.
- One coherent captured resource version pairs model geometry with the owning car source package, including imported geometry. Missing/metadata-only provider retains group behavior; later provider registration requires an ordinary model rebuild.
- Decorations resolves category default from captured resources; fresh private compile → draw(emptyMap(),0L) → physical immutable text → finally close. Validation LRU behavior remains unchanged and is never borrowed for mounting. Shared service stays open. Connection retirement rejects late output; detached first frames do not retain the engine.
- Logical canvas is fitted with preserved aspect ratio and centered margins; glyph baseline/scale follows the standalone text convention. No persistence/schema version change.

## Automated checks

Serial Gradle, offline shared dependency cache, max-workers2, test heap2GiB/one fork/ActiveProcessorCount2. Explicit API repository `/tmp/javarailways-simpledisplays-xml-dom-maven`, independent SimpleDisplays baseline `410af2e`.

- Host focused50 tests: pass. Attachment worker/cancel/queue/failure; ExtensionPartModel transform/root-only/audience/replacement/retirement/partial cleanup; real backend passenger refresh; model reload/builder and held-scene regressions.
- Decorations35 tests: pass, including fresh real binding draw/fit/private close/failure/retirement, captured resources/defaults and API-hidden classloader prepare entry.
- `:Decorations:check` (`verifyPluginJar` included), `:BukkitPlugin:shadowJar`, `:BukkitPluginDev:compileKotlin`: pass.
- Full host regression: 776 tests, 1 existing ProgramExpressionsTest failure, 1 skipped, 0 errors. The same expression test still expects `[central@1]` but receives `[]`; this batch does not modify it. No additional failure.
- `git diff --check` and untracked-file whitespace scan: clean.

The initial new backend tests lacked PacketEvents initialization and EntityLib settings. Their fixtures were corrected to use the existing packet-version binding and real APIConfig, without suppressing actual wrapper state changes. The final focused run passes all50 tests.

## Actual isolated Paper evidence

Paper1.21.11-132, Java25; target chunk explicitly loaded and held with probe plugin ticket. Final probe builds through real ModelManager resourceVersion → saved car resolver/exact source package → production ModelTreeBuilder → CompositeModel. It uses real SimpleDisplays XML and both actual native and EntityLib backend instances; it does not call provider.prepare directly.

Final logs:

- `/tmp/jr-static-extension-mount-paper-smoke-final/console.log`: both `STATIC_MOUNT_BACKEND_PASS`, `STATIC_MOUNT_PROBE_PASS`.
- `/tmp/jr-static-extension-mount-paper-smoke-matrix/no-simpledisplays/console.log`: PASS, both backends preserve group+child and support movement/cleanup.
- `/tmp/jr-static-extension-mount-paper-smoke-matrix/no-addon/console.log`: PASS, same preservation.

Final with-service case imports `shapes/panel` geometry. The foreign package supplies conflicting XML and the owning car's XML on disk is overwritten after snapshot capture. Both renders still show captured owning-car text `Mounted`, proving no foreign-namespace/current-disk fallback. The physical frame uses1.28×0.32m for128×32 logical pixels, span(64,0,size9), part offset(1,1.5,0): local text translation(1,1.57,0), scale0.4. Probe checks actual text metadata/transformation, shared root passenger binding and latest moving root position. Disabling Decorations removes texts and actual packet parent passenger IDs, keeps original group/child/root until normal composite destroy, then observes completed deletion. Every server is stopped normally after the probe.

Artifacts (the final probe copies match the worktree builds):

- Host all-JAR SHA256 `e26adbf98d2c9ecfd483808d3d62377a52228d02f0fb9522dc21206fdc6fc75c`.
- Decorations thin-JAR SHA256 `18c62fb41d0f4faee8f2326692d4f882b878a719bc0d1f94db70db0963bfb18f`.
- SimpleDisplays runtime SHA256 `eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391`.

Harnesses: `/tmp/StaticMountProbe.java`, `/tmp/jr-static-extension-mount-smoke-final.py`, `/tmp/jr-static-extension-mount-smoke-matrix.py`. Earlier inline-geometry Paper probe also passed but final evidence above additionally covers imported source isolation.

## Practical limits / next work

These are server-side production builder/entity tests with manual Composite movement, not live player/client or spawned train rail-following acceptance. No real Folia execution or network-viewer game client was used; owner/replay races are unit-covered. No claim of those acceptance targets.

Static output only: no periodic/per-viewer redraw, train DTOs, Bukkit PlaceholderAPI adapter or interior-distance config/commands in this batch. The previously agreed interior distance policy remains next-stage work. Missing required JS data can fail this first draw contextually without breaking the original car tree. Schema consistency stays a separate child task after functional work and before acceptance.

## Review and status

Independent frozen-source review found no remaining blocker after fixes for cancellation/enqueue race, exact Player token replacement, coherent source capture and actual wrapper passenger removal. The later captured-policy fix also prevents configuration replacement from mixing FOLLOW_PACKAGE geometry with SNAPSHOT resources during one construction. Final checks are complete for this slice, with the pre-existing full-suite expression failure explicitly retained. Work is ready for the requested pre-commit report; no commit/push/deploy performed.
