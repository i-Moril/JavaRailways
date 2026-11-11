# Follow-up: consume latest published frame for pending visual refreshes

User requested inspection and a straightforward implementation of draining pending requests and rendering the latest frame. Scope is the Bukkit consumer, preserving the existing bounded Core mailbox and downstream region dispatch.

## Change boundary

- Existing behavior gap: an initial/final stationary refresh remains queued while physics replaces publishedFrame; the consumer rejects the older queued object and no later motion update replaces it.
- Owner: WorldManager consumption after a successful physics ticket, before location-owned visual application.
- Intended behavior: atomically drain the existing per-train mailbox; use each drained train ID as a refresh request, look up the current train, capture its complete immutable publishedFrame once, and schedule its car frames. New offers after drain remain queued for the next batch.
- Expected production boundary: the existing WorldManager drain/dispatch loop, with a small local testable function if necessary. No new lifecycle state, operational checks, queue implementation, runtime compatibility, persistence change or per-tick stationary publication.
- Existing publicationStreamId is a Core runtime-instance UUID and the downstream region-task gate prevents stale target application. It does not encode a duty, operational trip or timetable. Latest snapshot selection does not reuse the old queued frame with a different train.
- Verification: real stationary initial publication surviving a physics epoch; last stop surviving further stationary epochs; requests arriving after drain retained; existing stream/revision tests and affected Bukkit suite.

Old-behavior reproduction: `:BukkitPlugin:test --tests '*PendingTrainFramesTest'` ran 5 tests; created/restored initial visibility and terminal stop all failed with expected 1 consumed frame but actual 0. Batch re-offer and removed-train cases passed. Evidence `/tmp/jr-render-refresh-red.xml`, `/tmp/jr-render-refresh-red.log`. Final fix: only `WorldManager.kt` production code changed. `consumePendingTrainFrames` captures current immutable frame once; redundant old-frame identity/car-list checks removed. Existing Core mailbox and downstream stream/revision gate unchanged.

Final gate `./gradlew --max-workers=1 :BukkitPlugin:test`: 590 passing, zero failures/errors/skips (36 seconds). Includes all 5 new regression invocations. Independent static reviewer found no issues; `git diff --check` passed. Logs: `/tmp/jr-render-refresh-green.log`. Live Minecraft verification remains pending; server was not modified during this fix. User subsequently authorized commit and deployment; rollout record follows separately.
