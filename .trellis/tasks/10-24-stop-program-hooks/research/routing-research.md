# R5/R6 routing research — 2026-10-25

Research only. No runtime edits. Read the current PRD and Basic route-planning / SimpleATO operational-routing specs. Current PRD explicitly remains planning; proposals below need the design gate.

## Current owners and evidence

- `Basic/.../route/TopologyRoutePlanner.kt`: `TopologyRoutePlanningInput` has immutable topology, neutral restrictions, canonical AB endpoints and optional endpoint facing. `plan` builds `QueryGraph`, then Dijkstra `Search`; `offer` retains one predecessor per oriented point and discards every non-shorter alternative. Therefore both equal-cost and unequal-cost alternatives are lost. Shortest-path success is not uniqueness evidence.
- Query graph cuts normalized track edges at restrictions and query endpoints. `GraphArc` retains its actual `RouteSegmentSnapshot` list. `RestrictionCompiler` suppresses replacement coverage and emits **one macro arc per permitted physical path**, even if alternatives share the same oriented endpoints. Distinct macro arcs must remain distinct during uniqueness analysis. They already contain the expanded physical path; do not interrogate signal plugin state or build a second physical topology.
- Atomic replacement interiors cannot be entered/exited (`TopologyRouteRestrictionTest`: `search cannot leave an atomic permitted path at an interior goal`). Adding a via waypoint in a protection interior does NOT by itself make that interior a legal route endpoint. Splitting macros to satisfy via points would violate current safety/ownership contracts. Prefer reject such a waypoint with an actionable message; supporting interior passage constraints while retaining the whole macro would require a separate whole-route via-matching algorithm.
- `SimpleATO/.../routing/OperationalRoutePlanner.kt` captures topology and compiles registered restrictions + shared operating directions. It is the operational query seam; Basic should not query `WayPointStore`. `BasicWorldData.kt` owns generic waypoint IDs/locations; SimpleATO destinations materialize into that store.
- `Timetable.kt:1174` `validateGoTo` calls the operational seam; its movement actions already validate an ordered waypoint list by adjacent legs. `AtoMissionResolver` in `api/mission/AtoMission.kt:177` separately resolves actual mission legs, carrying actual end facing to the next leg. `TimetableDispatcher.kt:1256` constructs the mission request. Validation and runtime must select the same explicit policy; changing validation alone is insufficient.
- `StationCallResolver` has retained-route validation and fallback searches; do not reselect a different shortest route once timetable traversal was frozen. `FrozenRouteSelector` and the diagnostic command are additional seam consumers that should retain existing defaults unless their task contract explicitly opts in.
- `api/line/LinePlanner.kt` still minimizes over berth/orientation alternatives. `compileResetCandidates` groups by origin/end direction and keeps shortest; this would hide ambiguity even after Basic learns uniqueness. Reset must use exact authoring targets + explicit policy and report ambiguity, rather than discard failure and silently choose another orientation/path.
- `WebUI/frontend/src/authoring.ts:7` owns run calls (`targetId`), `lineSimulationRoute` currently sends just station target IDs. Generation already accumulates PASS calls as `.pass(id)` before `.destination(id)`. Script worker `Dsl.kt:421,506` lowers pending passes plus target to ordered `wayPointIds`. Thus explicit via points can reuse actual route semantics without manufacturing station calls or stops.

## Proposed contracts

### Basic pure query

Add explicit default-preserving policy to `TopologyRoutePlanningInput`, for example:

```kotlin
enum class RouteSelectionPolicy { SHORTEST, REQUIRE_UNIQUE }
val selectionPolicy: RouteSelectionPolicy = SHORTEST
val pruneLocations: List<OperationalRouteLocation> = emptyList()
```

Use defensive immutable snapshots for collection-valued inputs. `pruneLocations` is neutral physical data, already excludes this leg's source/target physical locations at the SimpleATO seam. It is not a world-wide configuration. Add typed `AMBIGUOUS` rejection; any search-limit/inconclusive state must be another explicit rejection, never `Planned`.

SimpleATO provides query options such as `requireUniquePath` and `pruneNonTargetWaypoints` (both default false), snapshots all waypoint locations once, excludes physical source/goal aliases, and passes the remaining blocked locations. Timetable execution/validation and Reset opt into both. Generic diagnostics, free navigation and physical distance remain unchanged. Exact final public names are design choices, not an already-existing API.

Pruning must operate on **expanded traversals**, not just reached graph vertices:

- insert blocked edge positions in query-local cut sets for base traversal;
- reject continuations through blocked points;
- reject macro arcs containing a blocked interior point, including any constituent rail boundary; endpoint-only checks miss protection paths;
- use canonical AB positions for comparisons (BA route segments use different local coordinates);
- reject invalid supplied locations, rather than silently ignoring stale waypoint references;
- decide aliases by physical location, not waypoint string ID: two IDs on one position must not make their shared source/goal unusable.

### Physical route identity and algorithm

Identity is the ordered positive-length `(railId, facing, canonicalStart, canonicalEnd)` traversal after merging adjacent contiguous pieces on the same rail/facing. Ignore query-local cuts and synthetic zero-length endpoint markers; keep different internal protection rails distinct. Duplicate declarations of the same permitted physical path do not create ambiguity. Retain endpoint port/facing validation separately; do not conflate identity normalization with authorization.

Recommended implementation starts by reusing the same compiled directed multigraph, preserving distinct macro arcs. Build the reachable subgraph and reverse-reachable-to-goal pruning. Enumerate **loopless oriented-state paths**, stop on the second distinct physical route, and return the first only after exhaustive completion. Use an iterative stack, not recursive JVM call depth. Normalize/deduplicate identical macro arcs before enumeration. Add an explicit explored-state/path budget with typed inconclusive rejection if needed; do not call a capped one-result search unique. This is readily auditable but can be exponential on reconvergent cyclic graphs; record the bound and test it. A loopless K=2 shortest-path algorithm is a possible optimized alternative, but it must distinguish parallel macro arcs and de-duplicate expanded physical identities, not merely endpoint paths.

Loop policy must be stated in design: forbid repeating the same oriented query state in one candidate, while allowing a required single wrap on a normalized loop when target lies behind the start. Do not count arbitrary extra laps as infinitely many alternatives. Macro internal traversal repetition must obey the same physical-loop rule; endpoint-only visited sets can otherwise hide repeated internal traversal. Query endpoint locations that normalize to the same physical point remain invalid positive movement, consistent with current planner; no arbitrary lap is inferred.

### Ordered via authoring

Proposed `AuthoringRunCallV1.viaWayPointIds?: string[]` belongs to the incoming leg of the call. Origin must have none. Validate existence, nonblank stable IDs, no adjacent duplicate/same-position endpoints, and deterministic ordering. Do not give via points station dwell, arrival programs or graph station rows. For each following call emit its via IDs as `.pass(...)` before that call's existing PASS or destination; an intermediate PASS station and a via waypoint remain separate authoring concepts but both contribute to the same ordered movement list.

Carry the exact same ordered sequence into Reset HTTP input and `LinePlanner`/simulation; do not implement via only in generated JS. Preserve actual arrival direction across every sub-leg. A unique result for each explicit adjacent endpoint pair yields one selected concatenation under the chosen direction contract; unknown origin direction needs explicit ambiguity treatment across candidate directions, not shortest selection. If only one orientation completes the entire ordered sequence, report that one; if multiple complete physical sequences remain, reject.

Keep persisted run-call metadata and compilation trace in sync so inspector errors can identify `run/call/via[index]`. Current development format changes retain version per repository policy; no runtime legacy inference/migration is implied.

## Concrete fixtures and gates

1. Reuse `TopologyRoutePlannerTest` two-turnout alternatives fixture: unequal lengths must be AMBIGUOUS with REQUIRE_UNIQUE; SHORTEST remains unchanged. Equal lengths and shuffled topology iteration must also be deterministic rejection.
2. `TopologyRouteRestrictionTest.alternatives()`: one replacement with short + long permitted paths sharing endpoints => AMBIGUOUS; one forbidden => unique; duplicate identical permitted physical path => unique; split-vs-merged equivalent spans => unique.
3. Straight rail A→C with waypoint B in its interior: default query succeeds; prune policy fails; explicit A→B→C succeeds. Repeat in BA and at normalized rail boundaries.
4. Macro containing waypoint B internally is pruned even though B is not a macro vertex. Selecting B as endpoint remains rejected by atomic protection contract. Do not silently split the macro.
5. Two IDs at source/goal's same physical location do not block the leg; a true unrelated interior waypoint does. Same physical source/goal rejects without guessing a loop.
6. Required single loop wrap remains possible, extra laps do not fabricate alternatives; disconnected/dead-end cycles terminate; directed constraints can remove exactly one alternate.
7. Operational seam tests prove waypoint snapshot + policy forwarded and default consumers unaffected. Timetable validator and mission resolver reject the same ambiguous route; Reset returns ambiguity rather than shortest candidate.
8. Ordered via generated-source, DTO persistence roundtrip, Reset transport and whole sequence direction continuity tests. Missing/reordered via IDs must fail/report correct location; via must not create stop operations.
9. Search work-bound test, if budget chosen, asserts explicit inconclusive rejection and no unique claim.

## Affected files

- Basic `route/TopologyRoutePlanner.kt`, `TopologyRoutePlannerTest.kt`, `TopologyRouteRestrictionTest.kt`; optionally a separate pure uniqueness search/identity file to keep the existing ~750-line planner readable.
- SimpleATO `routing/OperationalRoutePlanner.kt`, `routing/RouteBuildResult.kt` if preserving typed reasons; `timetable/Timetable.kt`, `api/mission/AtoMission.kt`, `timetable/TimetableDispatcher.kt`, `api/line/LinePlanner.kt`, relevant mission/station/line tests.
- WebUI `authoring.ts`, `RunRouteEditor.tsx`, `types.ts`, `api.ts` only if transport shape needs it, `frontendDev.ts`, authoring/route tests; Java editor document validation for new field.
- Bukkit `http/TimetableHttpBridge.kt`, scriptworker model/DSL if run-call trace metadata includes via, SimpleATO timetable snapshot codec for persisted run metadata.
- Owner specs: Basic route planning, SimpleATO operational routing/lines/timetable simulation, WebUI studio authoring. No implementation/spec edits performed here.

## Decisions to settle before implementation

1. Confirm loopless oriented physical path semantics and explicit inconclusive budget behavior.
2. Confirm protection interior via rejection versus a much larger whole-macro ordered-constraint design.
3. Decide whether all timetable movement actions (including depot and arbitrary duty moves) opt into uniqueness/pruning, or only RUN-generated movement. PRD says timetable waypoint pairs generally; do not quietly leave duty movements unrestricted.
4. Decide static final berth ownership after removal of dynamic selection; exact waypoint identity is necessary for strict adjacent-waypoint queries.
