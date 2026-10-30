# Refactor validation — 2026-10-25

Checkout `/tmp/javarailways-blockly-deploy`, branch `codex/timetable-station-programs`, base42b6f3e8. The user approved the workflow3.4 local commit on 2026-10-26, with task archival explicitly deferred until in-game testing. The primary checkout's unrelated untracked files were left intact.

## Final automated gates

- Frontend:34 files,210 tests passed; `npm run build` passed (TypeScript + Vite). Existing large-chunk advisory remains.
- Core:524, Basic:433, SimpleATO:634, BukkitPlugin:642, WebUI:36 tests; all failures/errors/skips0. Total2269 backend tests.
- Migration:3 Python unit tests passed.
- `git diff --check` passed.
- Final full-scope reviewer report: `final-check.md`, including confirmed fixes and coverage limits.

Full backend gate used JDK25 Gradle, one worker, in-process Kotlin,2GiB test heap, test JVM recycled every10 classes. The previous unisolated2GiB suite encountered OOM allocating curve points in SimpleATOTest; the same controller class passed alone at2GiB. This does not prove a production leak or identify a cumulative test retainer. All634 SimpleATO tests executed in the final batched run; no filters or exclusion were used. Temporary init scripts and diagnostic logs live under /tmp and are not changes to repository test policy.

Core/Basic/BukkitPlugin/WebUI final results are from the complete combined gate (`/tmp/station-final-gate.log`); SimpleATO was subsequently rerun in full after its five strict-waypoint fixture fixes (`/tmp/station-final-ato-gate.log`). Earlier route fixtures depended on implicit intermediate waypoints or invalid candidate paths and were replaced with explicit vias/valid branch geometries; dual-candidate occupancy and storage ownership assertions remain.

Bukkit `BrowserGeneratedStationProgramTest` runs actual captured browser-generated JavaScript through Graal, worker output, snapshot decode and domain restore. `TimetableScriptExecutionTest` passed the final full run; its earlier timeout was in a capacity-probe Graal Context construction before close(), not evidence that cancellation failed.

## Final real HTTP browser checks

Latest WebUI fat JAR ran in an isolated Java process on `http://dev:18884/`, with `isSecureContext=false`, `crypto.randomUUID` undefined. A fixture bridge supplies world metadata and auth only; compilation/game endpoints explicitly return501 and never report fake success. Authenticated editor document storage is the real Java repository under `/tmp/station-ui-qa/drafts`, isolated from any server/world.

Verified:
- Saved/reloaded shared station definitions, ordered incoming via, optional terminal departure600, event point and duty reference vehicle using real Java APIs.
- B station modal displays both canvases/entry tabs, no browser errors. Adding WAIT_DURATION through the UI saved OPEN_DOORS→WAIT_DURATION→END, confirming insertion before END.
- Duty canvas and INLINE inspector display correctly. Clearing optional start tick and saving yielded an INLINE object without tick in the persisted server document (HTTP200 readback).
- Actual pointer drag disconnected RUN from the duty entry. Save and compile became disabled, an explicit incomplete-connection warning appeared, and both domain elements remained on server.
- Reload preserved the disconnected draft and blocked saving even before entering Duties. Direct first visit to Duties displayed both canvases with zero window errors, exercising the locale-before-inject fix without first initializing a station/library editor.
- Explicit discard restored both persisted elements (`display`,`run`) and enabled saving; no domain element was silently deleted.

Captured initial generated source/document are fixed test resources. Browser evidence does not establish live Paper/Folia operation, train model animation, real reverse callbacks, world routing or multiplayer client acceptance.

## Packaging and remaining environment limitation

WebUI shadow JAR built successfully; BukkitPlugin shadow JAR built successfully. The main JAR embeds exactly the latest WebUI JAR (matching SHA256):

- Main shadow JAR SHA256:7d1305d67ea0c5b272bae8672bb0ba412a42ea6d826119acbe02c00b1d432e28.
- WebUI SHA256:d5de9a7d3226cb572a37d884349251f4b4b99253b4558b99826db5d7d2f9c3c4.

An extra `:BukkitPlugin:reobfJar` attempt failed because TinyRemapper0.12 cannot read Java25 class major69. This limitation is already explicitly documented in BukkitPlugin/build.gradle; development servers consume the Mojang-mapped shadow JAR. The reobfuscated artifact is not a successful output of this task. No deployment or real data migration was performed.
