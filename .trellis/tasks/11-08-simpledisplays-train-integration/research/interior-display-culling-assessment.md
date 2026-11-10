# Interior display culling assessment — 2026-11-10

Decision: user explicitly declined additional culling on2026-11-10 and wants the end-to-end flow completed first. The following is retained only as research, not planned implementation.

Scope: source/API feasibility assessment, not a measured performance result. Distance implementation is authorized now; additional culling is assessed only. Whole-system test/Paper/Folia/client acceptance is deferred per user. Exterior content continues ordinary car strategy.

## Baseline and what a cull saves

Host already narrows candidates through RenderChunkManager's car spatial audience. Interior text then uses eye-to-transformed-screen distance. Excluded players receive no generated text spawn/content updates through that visual's audience; root passenger bookkeeping remains shared. When no candidate is eligible, the group invalidates queued/in-flight draw results and pauses new draw requests, preserving latest data/source clock. Existing hidden native entities/root/part animation bookkeeping remain allocated/owner-updated, and private templates may still compile once. This does not claim zero native tick cost.

Server audience culling saves client entity management and network work, and saves shared JS evaluation only when ALL viewers are excluded. One interested viewer keeps the shared frame running. Client-only rendering culling can save graphics work but cannot itself remove host evaluation/network cost. Do not present one as proof of the other.

Let P be existing car-candidate player/screen pairs and K the number of samples/rays. Current sphere tests cost O(P) cheap arithmetic; no screen scans all online players. New view tests should apply only after candidate+distance rejection and reuse bounded/coalesced refresh.

## Backface rejection: feasible, low arithmetic / medium integration complexity

A dot product between the world screen normal and eye-to-screen vector rejects a point behind a one-sided plane. Requires exact car/ancestor/part animation rotation, correct normal transform under scale/mirroring, explicit front-face convention and a small conservative edge margin. Current definitions specify category/interior/width/height but do not declare one-sided versus double-sided semantics. Text/background or custom mesh arrangements must not disappear based on an assumed face convention.

If added later, prefer an explicit optional one-sided policy and conservative side/edge handling. Real validation needs both backends, reversed/nested/mirrored models and viewers at plane edges. It adds no block-world query and fits the current immutable geometry boundary. It still does not know whether a wall covers the front.

## Server camera/frustum rejection: approximate feasible, medium/high correctness complexity

Dot/view-cone or sphere-vs-frustum arithmetic is cheap. Correctness needs camera pose, projection/FOV/aspect, nonzero screen extent, and conservative movement/rotation margins. The Paper1.21.11 ClientOption surface exposes view distance and other client settings, but no FOV/aspect option. Existing code collects player eye position, not a precise third-person/free camera projection. This API/source evidence supports treating an exact frustum as unavailable through the present contract; it is not a claim that every mod/protocol extension is impossible.

A generous view cone may be an optional heuristic; a hard narrow cone risks disappearing content in third person, changed FOV, camera motion and edge-of-screen viewing. Yaw-only player movement events currently encounter the same-position refresh suppression, so camera culling also needs an explicit rotation refresh threshold and coalescing. Additional body animation/long screen extent must be represented by a bound, not just the center point. More ownership complexity than the current distance sphere; not recommended as the next default rule.

Primary API: https://jd.papermc.io/paper/1.21.11/com/destroystokyo/paper/ClientOption.html

## Client Display bounds: feasible separate optimization, medium integration complexity

Both backends already set viewRange; Paper exposes Display displayWidth/displayHeight and transformations. Current host descriptors do not carry full generated-text culling bounds and do not configure those dimensions. A future bounding implementation must handle that generated text is mounted under a shared car root and visually translated/rotated away from its entity origin. A small bound around the root can incorrectly reject a visible screen; a whole-car bound is safe but less selective.

Need matching native and packet metadata, conservative complete text/background/layout extent (including custom fonts and content beyond nominal canvas), parent offsets/scale/rotation, and motion/interpolation margin. Establish exact client behavior with target-version source and real client testing before relying on width/height metadata; this assessment does not claim current defaults already do useful frustum culling. It complements server distance and does not substitute for reducing network/JS work.

Primary API: https://jd.papermc.io/paper/1.21.11/org/bukkit/entity/Display.html

## Occlusion: block-only partial feasible, full car-body occlusion high complexity

World block raycasts could detect terrain/building walls, with O(P*K) traversals depending on ray length and sample count. A center ray alone incorrectly rejects a partly visible screen; several points or a conservative bound are needed. Results need caching, movement invalidation, hysteresis and limited scheduling. Folia block access belongs to the queried region; an eye-to-screen line may cross region boundaries, so it cannot be implemented as arbitrary asynchronous world reads or a ray on the player's scheduler alone. A correct approach needs safe owner snapshots or partitioned queries, with unknown treated conservatively.

Block rays do not represent this repository's item_display/text_display car-body surfaces. Testing an entity AABB as opaque would hide cabin screens even for passengers. Faithful train-body occlusion requires authored occluder volumes/meshes, screen/interior/exterior and door/window semantics, moving transforms plus acceleration structures. It is a separate model/visibility capability, not a small use of hasLineOfSight.

Primary ownership reference: https://docs.papermc.io/paper/dev/folia-support/ (region scheduler for locations, entity scheduler for entities).

## Recommendation and deferred evidence

Keep distance + pause-with-no-eligible-viewer as the initial server policy. After integrated client measurements identify whether network/entity count, shared JS or client graphics is dominant, prioritize conservative client display bounds or explicit one-sided backface rules. Do not add camera/block/mesh heuristics to this batch. No timing/speedup numbers are inferred from arithmetic complexity.

Final validation should compare native/packet entity and packet counts while walking, riding, rotating camera and changing settings; verify stationary-player/moving-screen and partially visible screens; separately measure all-hidden shared draw counts. Include third person, custom FOV/font, animated/mirrored screens, world changes and Folia cross-region movement before enabling stronger default culling.
