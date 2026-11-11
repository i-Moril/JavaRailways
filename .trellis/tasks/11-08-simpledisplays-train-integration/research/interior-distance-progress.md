# Interior distance implementation status — 2026-11-10

Implemented in dedicated ddf5 worktree on codex/simpledisplays-train-integration; last commit remains09da5fbc. Prior operational batch dirty changes preserved. No new commit/push/deployment.

User direction: implement distance now, player-to-SCREEN not car center, and perform integrated verification once all work is complete. Extra culling is an assessment, not enabled functionality.

## Modules

Host public extension API gains immutable/thread-safe per-viewer maximum-distance policy and registration.refreshAudience(UUID). Private session publishes policy after warm worker open; retirement releases the provider policy reference. Group stores detached screen world point (full parent/car/part animation origin) and player eye snapshots, filters generated leaves through existing car candidates, coalesces existing-viewer entity refresh, rechecks audience on late replay and retains ordinary children. Root-only translations plus independent animation use the latest shared-root location. No extra repeating scheduler or online-player scanning.

Groups warm-open without default drawing, retain newest detached input/initial source tick while hidden, invalidate pending/in-flight output on last viewer loss, and request current input on return. Already allocated hidden visuals remain: no claim of eliminating native entity tick cost.

Decorations owns strict config (default32/max128 blocks, max<=4096), login-only UUID overrides, /decorations display-distance [blocks|reset], default-true permission, command-triggered refresh, quit/disable cleanup, interior-only policy wrapper around the real template session. No SimpleDisplays/Core/SimpleATO production changes in this slice; no train payload/schema change, version bump, legacy reader or player persistence.

## Compilation evidence only

- Source compilation: `/tmp/jr-interior-distance-compile.log`, BUILD SUCCESSFUL.
- Final host/addon source and test-source compilation: `/tmp/jr-interior-distance-final-compile.log`, BUILD SUCCESSFUL (4s).
- Commands use `:Decorations:compileTestKotlin :BukkitPlugin:compileTestKotlin`, offline existing API repository/shared cache, one worker and temporary Kotlin compiler4GiB heap.
- `git diff --check` clean.

No test task, Paper/Folia process, client test or performance benchmark was run for this slice. Source/test-source compilation is NOT acceptance. Existing previous operational Paper probe renders without an audience and must be adapted for the new visibility requirement before reuse.

Prepared cases cover addon strict config, zero/default/reset/limits/independent players, command scheduling/permission/refresh, interior versus exterior delegation, transformed rotated/animated screen point, root-only motion with stationary viewer and refresh coalescing, hidden warm/no-draw and resume source time. Earlier renderer fixture cases were adapted to supply explicit candidate viewers. These cases have compiled but remain unexecuted.

Final integrated validation must additionally exercise real native/packet visibility/metadata exclusion, two independently configured real players, live train and animation movement, world/quit/disable/retirement races, zero-viewport all-hidden draw counts, plus real Folia/client behavior. Culling assessment is in interior-display-culling-assessment.md with primary API references and no measured speedup claims.
