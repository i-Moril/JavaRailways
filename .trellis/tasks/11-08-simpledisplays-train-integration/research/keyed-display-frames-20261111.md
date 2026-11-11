# Stable display elements and depth

User approved implementation2026-11-11 after reviewing keyed updates and fixed z-index. Current source baselines: JavaRailways0fad7d54, independent SimpleDisplaysfe2898d. Both worktrees clean before this slice.

## Accepted boundary

Maintain full immutable draw frames; key identifies a logical primitive within one screen attachment, independently of text, list position and viewer. XML explicit id becomes its stable draw identity. Static XML without ids can use deterministic original document paths; runtime-generated script primitives must provide explicit ids rather than random/frame/list-index identities. Duplicate or blank/overlong keys fail validation, not silent overwrite. No cross-viewer mutable DOM state is added.

Agreed integration fields: SimpleDisplays TextDraw adds required `key: String` (nonblank, max1024 characters) and `zIndex: Int = 0` in0..63. Public host PartExtensionText adds required `key: String`; PartExtensionFrame validates unique bounded keys. Decorations passes the key unchanged. z-index is an absolute inherited default: a div's index supplies its descendants' default; an explicit child value overrides, not adds. No z-index means inherited0. Same-layer overlap is the template author's responsibility; no automatic same-layer painter-order offsets.

Decorations z = draw.zIndex /63.0 * min(physicalWidth,physicalHeight)*0.01. This is fixed for a screen's physical size; no current-count or array-order dependency. The library standalone preview uses equivalent fixed spacing for its physical fitting. All geometry must remain finite and bounded. Current development config versions remain unchanged, no runtime legacy reader or schema bump.

Host reconciliation reuses existing TextModel by key, updates only changed content/pose, transactionally creates missing primitives and destroys only removed ones. Existing owner/audience, current anchor/transform, chunk-generation, packet-order/dedup, async cleanup and generation rejection contracts remain. Stage additions before publication; creation failure cannot destroy the prior successful frame or leak new visuals. Reordering a frame cannot recreate unchanged identities. EMPTY retires all generated nodes. Existing actual-RUN off gate, semantic context/style retirement and original model subtree remain unchanged.

Template helpers supply semantic ids for backgrounds/labels, exact stop occurrence+role for route nodes, and stable character-slot ids for vertical text. Ring20/dot21/route10/background0/text30; badge backgrounds use a distinct non-text layer. The next-stop dot always exists for a stopping call, changing its text to empty while off. Empty span output must remain a primitive. Language rotation remains300ticks/15seconds. Update unpacked current model examples and their distributable zips; test-server package edits/deployment are not part of this edit authorization.

## Ownership

- SimpleDisplays owner: key/z-index parse/output, document safety/validation, standalone preview, API+README and meaningful unit tests. Separate worktree `/home/user/.codex/worktrees/ddf5/SimpleDisplays`.
- Host owner: PartExtensionText/Frame validation and ExtensionPartModel reconciliation with focused tests. No train/ATO/domain changes.
- Decorations owner: typed API check/conversion, template helpers/keys/layers, fixtures and distribution examples.
- Root: serial builds, cross-module contract, independent review, task/spec evidence. Do not commit or deploy without current user instruction.

## Verification

Run actual blink frames through existing extension/model/backend fixtures; assert stable IDs and zero Spawn/Destroy/Mount for unchanged primitives and dot text-only changes. Grow/shrink/reorder preserves common IDs; additions/removals touch only their keys, hidden viewers stay hidden, current root/transform preserved, same-layer values independent of node counts, duplicate/invalid key/layer rejected before mutations. Cover partial construction/cleanup, EMPTY, generation/context retirement and provider shutdown. Static/scripted library tests verify id continuity, inherited/explicit/invalid z-index, viewer isolation and empty-span preservation. Decorations tests verify key propagation and geometry invariance. Final packaging uses a fresh local compile-only API publication; all build evidence separate from real client acceptance.


## Result — local implementation complete, not committed/deployed

- SimpleDisplays: required stable TextDraw.key, bounded inherited absolute z-index, original DOM origin tracking across clones/moves, explicit script-generated identities, keyed preview reconciliation. Updated README and regression fixtures; API published only to `/tmp/javarailways-simpledisplays-keyed-maven` for this build.
- JavaRailways: keyed TextModel reconciliation, staged additions, rollback and cleanup tracking, replay of additions retained by a newer frame. Original subtree/audience/context/style/packet ownership boundaries retained.
- Decorations: compatible API getter checks, key passthrough, fixed physical depth; three current XML examples and two distribution ZIPs synchronized. No world/config schema changes or data migration.
- Spec owner documents updated: backend screen-parts/text-display-backends and Decorations backend index.

Validation2026-11-11:

- SimpleDisplays23 tests,0 failures/errors/skips; `test jar publishToMavenLocal` successful. Log `/tmp/jr-keyed-displays-library-20261111.log`.
- BukkitPlugin78 focused extension/model/backend/visibility tests,0 failures/errors/skips. Log `/tmp/jr-keyed-displays-integration-20261111.log` (the final host run passed, then addon fixture failed; addon rerun below completed).
- Decorations73 tests,0 failures/errors/skips, verifyPluginJar/check and BukkitPlugin shadowJar successful. Log `/tmp/jr-keyed-displays-addon-20261111.log`.
- Repaired test fixtures found during verification: initialize the first full transform in the old style-revision case; exclude expected root-only teleport from the blink assertion while still forbidding generated-child/mount/passenger changes; give generated rectangle fixture a required id. Incompatible-binary fixture now parses actual UTF8 constants and guarantees getZIndex is renamed to a different name, with direct reflection proving the getter is absent before testing discovery rejection.
- Actual XML/JS template probe used the built implementation and unchanged250ms production budget after warmup. Cabin ticks0/10:90 nodes and exactly one empty-dot content change; ticks300/310:114 nodes and exactly one dot change. All blink keys and geometry/layers unchanged. Language switch preserves90 common keys at identical layers. Both exterior templates:4 nodes per frame, stable keys/geometry, no blink updates, all4 common language keys retain depth. Probe `/tmp/KeyedDisplayProbe.java`, output `/tmp/jr-keyed-display-template-probe.log`. Initial cold interpreter probe exceeded the existing250ms deadline; no runtime budget change or broader performance fix was made. Warmed probe is functional evidence, not a cold-start/performance acceptance.
- Independent readonly source review found no blocker and checked ZIP entry sets/bytes against source (S10064 files, cabin3). Additional queued replay regression passes. Both worktrees pass diff whitespace checks.
-174 tests above are scoped evidence, not the entire repository suite. No Paper/Folia server or real-client visual acceptance performed this slice. In particular actual flicker/depth precision/readability remain client acceptance items.

Build artifact SHA256 (uncommitted source):

- `/home/user/.codex/worktrees/ddf5/JavaRailways/BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`: `185cab5672fc4a7fd07d79cfa347b7bcae7f21ed1f281ca47ea8ea927e305e2a`
- `/home/user/.codex/worktrees/ddf5/JavaRailways/Decorations/build/libs/Decorations-1.0-SNAPSHOT.jar`: `08d563612c7c7807606bf251b0ade32a830e02bb36ff32d8051c9d860e92650c`
- `/home/user/.codex/worktrees/ddf5/SimpleDisplays/build/libs/SimpleDisplays-0.1.0-SNAPSHOT.jar`: `fd3445631a86fecfd6290894417e63c2f62c89ef09c271b1bfae94f6de984420`
- `/home/user/.codex/worktrees/ddf5/JavaRailways/models/s100.zip`: `837a9ef9ead3aafd6f4387af59004ea3d408932474610f39517e52b53277acb8`
- `/home/user/.codex/worktrees/ddf5/JavaRailways/models/cabin-route-style.zip`: `390a3e0f910473b9415989cba6193aaabe56f798a8b9930e9e3d8ac664825e19`


## Commit authorization

User explicitly requested commit after the local result report. SimpleDisplays committed as `ca37ad2` on `codex/js-rendering`; JavaRailways host, Decorations, examples and specs are committed together in this slice. No push/deployment authorized by this commit request. Parent task remains in_progress pending the remaining acceptance work.
