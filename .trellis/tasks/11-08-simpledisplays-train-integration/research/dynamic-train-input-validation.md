# Dynamic train input validation — 2026-11-10

Dedicated worktree `/home/user/.codex/worktrees/ddf5/JavaRailways`, branch `codex/simpledisplays-train-integration`. Previous static slice committed as `a4604a43` at user request; this dynamic slice remains uncommitted for report. No push/deploy or main-checkout source edits. User excluded Bukkit PlaceholderAPI from the parent task.

## Implemented scope

- Current unpublished PartExtensionRenderer uses open→owned session draw→close, with immutable Json/time inputs; replaces one-shot prepare directly, no development compatibility layer or version bump.
- Each attachment has one queued/running work item, one overwritten latest request and one completed result. Completed frames can progress despite later pending samples, avoiding slow-render starvation. Open once, serialized draw/close on host workers, one failure diagnostic per episode with recovery on later input. Retirement observes callback exit and real close; idle session work drains before worker shutdown. Service also tracks already-detached registration retirement futures to close all sessions under concurrent unregister/shutdown.
- Same-count text updates reuse entities and avoid unchanged metadata/transforms. Count changes build before replacing, EMPTY clears, obsolete/partial cleanup remains observed. Original groups/children/animations stay unchanged. Existing owner commit/root-only path and exact viewer token replay are reused.
- Basic train/car Json captures the same immutable TrainFrameSnapshot as the pending car geometry on simulation owner, including exact train identity/name/type, speed m/s, reversed/derailed, physical car index/count and XML string aliases. Existing publication stream/revision gate runs before data install. New accepted stream rebuilds active model, including a model built before its first accepted input, so old session output cannot cross split/join identity.
- Existing world tick supplies a10-tick bucket heartbeat only to cars with active extension models, excluding cars already queued. Stationary Core mailbox suppression and dispatcher phase order remain unchanged. Exact-world clock buckets clear on stop/unload; unavailable/retired sessions stop later heartbeats after the owner refresh.
- Decorations owns one freshly compiled template per runtime session, independently of validation LRU. Real DisplayContext receives maps/lists/scalars and elapsed source ticks; active connection retirement defers private close to draw exit, idle retirement closes outside locks; shared DisplayService stays open. API-free bridge and hidden-API behavior retained.

No operational station/destination/run-type guessing. TimetableUnitDisplaySnapshot is the identified later source for actual ordered stop occurrences/fixed-unit membership/unresolved facts; current visual dispatch precedes dispatcher updates, so that follow-up must explicitly define its publication boundary. No per-viewer mounted output, interior distance settings/commands, style switching or schema consistency work in this slice.

## Automated verification

Serial offline Gradle with shared dependency cache, max-workers2, test heap2GiB, one fork, ActiveProcessorCount2; explicit local SimpleDisplays API repository `/tmp/javarailways-simpledisplays-xml-dom-maven` (independent source410af2e).

- Focused host43 tests: pass. Session lifecycle/latest coalescing/cleanup/recovery and concurrent registration/service close; dynamic model text reuse/pose/growth/EMPTY/rollback/audience; actual same-frame projection/unknown type/physical reversal order and immutability; stationary heartbeat/dedup/retired-filter plus existing frame gate/backend regressions.
- Decorations39 tests: pass, including repeated structured DisplayContext+time, compile once, geometry, draw failure/recovery, open/draw/idle/concurrent retirement close and hidden-API new open entry.
- `:Decorations:check` including verifyPluginJar, `:BukkitPlugin:shadowJar`, `:BukkitPluginDev:compileKotlin`: pass.
- Full host787 tests: only existing ProgramExpressionsTest failure (expected `[central@1]`, actual `[]`),1skipped,0errors; no additional failures. Known test remains unchanged/out of scope.
- Frozen-source independent review: no remaining blocker after detached-retirement shutdown race and first accepted source identity edge fixes.
- `git diff --check` and untracked whitespace scan: clean.

## Actual isolated Paper

Java25/Paper1.21.11-132. Probe explicitly loads/holds target chunk, builds actual captured car via ModelManager→saved resolver/source snapshot→ModelTreeBuilder→CompositeModel, then drives its owner commits with immutable input samples. Actual SimpleDisplays plugin evaluates the captured XML plus embedded document JS. No fake rendering service/provider substituted.

`/tmp/jr-dynamic-extension-mount-paper-smoke/console.log` contains native+packet `DYNAMIC_MOUNT_BACKEND_PASS` and `DYNAMIC_MOUNT_PROBE_PASS`.

For each real backend, the probe checks:

- structured train.name and numeric car.number reach JS; normalized elapsed ticks0/10/20/30/40 reach repeated evaluations;
- First→Second updates actual text while preserving the exact visual/entity/wrapper identity;
- Grow changes one→two leaves, retires old visual and matches actual parent passenger count;
- Empty produces no leaves and removes actual passenger IDs; Recovered renders again;
- fitting still gives translation(1,1.57,0), scale0.4 for128×32 logical pixels fitted to1.28×0.32m and part offset(1,1.5,0);
- latest moving shared root position, original child retention, source pinning despite conflicting foreign XML/current disk overwrite;
- Decorations disable invalidates/removes output and composite cleanup futures complete after true session close, without waiting on region/main thread.

Missing-service matrix also passed with the same final artifacts:

- `/tmp/jr-dynamic-extension-mount-missing-matrix/no-simpledisplays/console.log`.
- `/tmp/jr-dynamic-extension-mount-missing-matrix/no-addon/console.log`.

Both contain `STATIC_MOUNT_PROBE_PASS missing renderer preserves tree both backends` (reuse of the preservation harness). All isolated servers stop normally.

Artifact SHA256 (worktree builds match actual dynamic probe plugin copies):

- Host `8b6faf157f428a748d8dbf33a7bc56d457f7d7a3ab61a92078c012fa2c09c43b`.
- Decorations `d981b85d02aacad0dea3f8eb5748091455c4ae40b5d2780fad6b04e94923db7b`.
- Independent SimpleDisplays runtime `eefe743285dc8b385254a32a9679f198aea3669a6e10b3d7b0d8a1e307be7391`.

Harnesses: `/tmp/DynamicMountProbe.java`, `/tmp/jr-dynamic-extension-mount-smoke.py`, `/tmp/jr-dynamic-extension-mount-missing-matrix.py`.

## Limits

Actual Paper probe uses real production builder/entities/library and manually supplied immutable inputs/movement; unit tests exercise the real Core frame projection and parked-frame selection separately. It does not prove a spawned train's complete game/client experience. No game client/network viewer or real Folia runtime was exercised. Shared mounted output is current behavior; the independent library's per-viewer API capability is not a claim that both mounted backends already implement viewer-specific output. Nominal refresh is2Hz, not a claim of20Hz smooth animation or measured large-fleet performance.

Ready for pre-commit report; this slice has not been committed or deployed.
