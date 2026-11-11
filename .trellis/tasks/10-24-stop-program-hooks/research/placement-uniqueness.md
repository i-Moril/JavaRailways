# Research: unique physical placement for simulation seeds

- Query: Find the smallest correct reuse of Core rigid geometry to establish exactly one full train placement before an optional actual in-place reverse and simulation execution.
- Scope: internal; read-only source and specs, no implementation or Git operations.
- Date: 2026-10-30

## Findings

### Confirmed current behavior and available reuse

Files found:

- `BukkitPlugin/src/main/java/work/art1st/javarailways/bukkit/http/IsolatedTimetableSimulationFactory.kt`: shared world-seeding factory; `createTrain` currently creates coincident initial bogies then calls selected-path `CoreTrain.setLocation`.
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreTrain.kt`: state/ownership/publication, selected-path placement, actual in-place reverse, and prepare/install structure values.
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/CoreCar.kt`: real nested 3D axle and coupler solver; limited existing explicit-path prepare API.
- `Core/src/main/java/work/art1st/javarailways/core/physics/train/TrainCreation.kt`: creation resource owner, not a pure candidate-placement scope.
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailLocationWithFacing.kt`: selected-active continuation traversal, including backward traversal through reversed views.
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailSegment.kt`: ordered directional arc segments and internal `RailSegments` value.
- `Core/src/main/java/work/art1st/javarailways/core/physics/rail/RailView.kt`: all directed `nextRails`, separately selected `activeRail`, and geometry-validated topology connections.
- `Core/src/test/java/work/art1st/javarailways/core/physics/train/CoreCarPositioningRegressionTest.kt`: existing real path-aware solver regressions suitable as fixtures.
- `Basic/src/main/java/work/art1st/javarailways/basic/route/TopologyRoutePlanner.kt`: prior finite unique-path search result/budget pattern, not a replacement for physical placement.

Code patterns:

1. Factory `IsolatedTimetableSimulationFactory.kt:378-423` builds all cars at the same head location, installs behavior/config extensions, calls `created.setLocation(head)` and publishes. Its current direction expression at line 390 falls back to AB. All actual seeding should funnel through a revised common method here; isolated travel-time-only integrators do not place a physical train.
2. `CoreTrain.kt:390-420` `setLocation` mutates staged geometry, temporarily changes direction when already reversed, then calls `applyHeadLocation`. `applyHeadLocation` at lines 530-539 derives leading bogie by an offset, updates motion to zero, and calls sequential car placement. This is the implementation to share, not a separate approximation.
3. `RailLocationWithFacing.kt:56-116` `forward`/`peekCurve` follow `activeRail`, not all legal connections. Neither method proves uniqueness, and selecting an arbitrary active branch before placing cannot prove it either.
4. `CoreCar.kt:383-414` leading-car placement solves a real 3D bogie chord using `SolvePath` and `solveDistance`; head anchor and car center use floor normals and asymmetric offsets. Its no-movement search window is `2 * bogieDistance + FORWARD_SEARCH_RADIUS_METERS` (1m). It currently gets its path from active `peekCurve`.
5. `CoreCar.kt:457-500` `prepareFollowingCarOnPath` already accepts explicit ordered `RailSegments`, desired physical front, predecessor body end and connector length, then returns `PreparedCarGeometry` without mutation. It shares the real inner axle and outer coupler solve. It handles a finite short path using a rigid chord endpoint solve, not the sum of nominal lengths. `PreparedCarGeometry` at lines 257-265 provides A/B bogie locations and center/direction/normal values.
6. `CoreCar.kt:581-616` explicit following placement currently nests selected-path searches. Keeping this implementation and writing a second simplified placement test would let measurement disagree with actual construction.
7. `CoreCarPositioningRegressionTest.kt:69-136` already tests explicit-path preparation on slopes with asymmetric bogie offsets, both physical fronts and nonzero existing motion, after selecting another turnout branch and setting a reverse active continuation to null. The prepared geometry succeeds, active switches stay unchanged, and published geometry remains unchanged. This is a strong reuse seam.
8. `CoreTrain.kt:505-527` body path construction walks selected tail-to-head continuity and checks repeated directed rails; it cannot be reused as a proof that all other body paths are infeasible.
9. `TrainCreation.kt:66-75` `train()` constructs the CoreTrain immediately; `CoreTrain.kt:203-236` registers TrainEnds, world train index and initializes extensions inside construction, before explicit `publish()`. Therefore `createOwned` provides cleanup for failure, not read-only throwaway trials. Repeatedly creating/rejecting candidate trains would execute lifecycle side effects and is unnecessary.
10. `CoreTrain.kt:955-970` `prepareStructureGeometry` prepares interval/traversal publication from already-known endpoints/cars; it does not solve candidate car geometry. `prepareFollowingCarOnPath` is the only near-complete existing explicit-path solver seam found.

### Recommended smallest correct algorithm/API

Keep topology enumeration and physical geometry together at Core's placement boundary. Basic supplies the permitted **forward-facing** direction predicate for each traversed rail; searching backwards for the body must compare the *reversed search view* to that predicate, not accidentally forbid a legal forward-facing train merely because the enumeration walks backwards.

Expose one narrow owner-only placement operation, e.g. `prepareUniquePlacement(head, car geometry inputs, forwardDirectionAllowed) -> Unique(prepared) / Unplaceable / Ambiguous / SearchLimit`. The exact naming is optional. The returned value must retain ordered actual body occurrences and prepared per-car geometry so the eventual install does not solve again against a different active selection.

Internally:

1. Resolve one baseline facing from waypoint direction, falling back to a declared rail direction, never arbitrary AB. This is unchanged by the player's `reverse` request. Validate point/rail/arc and world identity first.
2. Enumerate directed backward topology continuations from `head.reversed` using **all** `nextRails`. Do not prune based on active selection. Validate actual directed connections; proximity or a generic junction degree does not imply a legal connection.
3. Evaluate each candidate with the SAME rigid Core numerical routines. Refactor the leading-car solve to accept an explicit path (its current numerical implementation remains shared); reuse/refactor `prepareFollowingCarOnPath` for subsequent cars. Resolve both endpoint offsets on that exact path. No bogie, car, train, switch or world registration is changed during candidate preparation.
4. Only a complete rigid placement counts. A branch which has sufficient nominal length but fails 3D axle/coupler geometry does not count. A short dead-end which cannot contain the real last body endpoint does not count. Do not reject at the first fork merely because there are two structural exits.
5. Clip the successfully prepared path to actual tail/head endpoints and normalize adjacent same-view contiguous segments. Count distinct actual occupied body traversals (preserving rail occurrences) with the same anchored geometry. Alternative continuations used only by a solver search window or lying beyond the final tail must deduplicate to one placement.
6. Stop early after two genuinely different valid complete placements: ambiguity is proven. With zero valid placements return unplaceable only after complete bounded exploration. With exactly one, accept only after all relevant alternatives were checked.
7. Install the one prepared geometry and its exact body path in the isolated owner phase. If subsequent publication/movement requires selected continuation consistency, install only the unique candidate's local selected links in the isolated world as part of creation. Do not mutate the live source world or globally change turnout selections during candidate trials.
8. After complete baseline placement, call actual `CoreTrain.reverse()` once if requested. This swaps operating ends on the same occupied body; it is not respawning with the head at the original point. Then pass the train to existing execution.

The preparation code can live beside CoreTrain/CoreCar; do not export a set of arbitrary state setters or duplicate the rigid equations in Bukkit. A shared internal solver over geometry config values is an option if preparing prior to car/bogie resource construction is desired. Alternatively new unowned car geometry may be prepared in one creation scope before constructing the train, with no extensions initialized until a unique candidate is established. Neither option needs another function executor.

### Bounded exploration and loop cautions

A conservative route-distance bound may bound **search**, but cannot prove successful physical fit. Derive each requested support interval from the existing lead/follow solver's own finite windows and endpoint offsets. Do not invent `sum(car.length)` as the body path length or silently truncate after a heuristic distance. A demand-driven topology-path provider can expand only windows actually requested by the common solver; an eager enumerator must cover all those same support windows.

Loop occurrences require care. An initial partial rail followed by one loop and a final disjoint partial occurrence of that rail can describe a legal train shorter than the whole loop. A global visited-rail ban can falsely reject it. Preserve occurrence order and reject genuinely overlapping occupied intervals/self-overlap. Positive segment lengths plus finite per-solver support bounds prevent unlimited traversal; retain cancellation/deadline and a branch/expansion budget. If the budget expires before checking alternatives, report SearchLimit rather than accepting the one candidate already found. The existing route planner's `SEARCH_LIMIT_EXCEEDED` pattern at `TopologyRoutePlanner.kt:345` is useful precedent.

The current numeric solver selects one supported root within its bounded search. This research does not claim that it mathematically enumerates every possible nonlinear root on arbitrarily looping/self-crossing curves. The requested implementation should preserve Core's existing supported rigid-placement semantics, test supported curved geometry and fail explicitly outside the supported bounded search; it must not advertise proof over unsupported root families.

### Required tests

- Straight rail, both canonical direction choices, directional waypoint overriding declared rail, no-direction input rejection.
- Baseline placement + optional real reverse leaves all car positions, stable physical A/B ends and occupied intervals unchanged; operating head/tail exchange. Original baseline point becomes the tail after reverse.
- Two turnout branches both physically accommodate the train: Ambiguous, regardless of which branch is currently active, and even if one active choice is null.
- One branch too short and one rigidly feasible: Unique. Do not reject simply at a degree-two directed view.
- A fork entirely beyond the solved tail: Unique, including when a numerical search probe sees the fork. Identical solved body traversals deduplicate.
- Length nominally fits but curved/sloped axle or coupler constraints fail: that candidate does not count; use real CoreCar solver, asymmetric offsets and nonzero floor height.
- Multiple turnouts, legal facing transformations across AB/BA connections, and no traversal between physically close but disconnected rails.
- Short/ordinary loop placement, legal initial/final disjoint same-rail occurrence, true self-overlap rejection, and exploration budget/deadline which cannot return false Unique.
- Every rejected/ambiguous/search-limit preparation leaves source train inventory, TrainEnds, geometry frames, topology identity/active selections and extension callbacks untouched. A successful install uses the prepared result once.
- Both ordinary timetable seeding and standalone function seeding use the same placement boundary; reference profile and depot-source flows are covered.

### Related specs

- `.trellis/spec/javarailways/core/train-physics.md`: real 3D axle/coupler geometry, explicit-path prepare reuse, state ownership, creation/publication, in-place reverse and exact structural path semantics.
- `.trellis/spec/javarailways/core/rail-topology-and-occupancy.md`: directed topology, isolated ownership and actual occupied interval semantics.
- `.trellis/spec/javarailways/basic/operating-directions.md`: Basic owns rail operating-direction constraints; Core does not assume operational metadata ownership.
- `.trellis/spec/javarailways/simpleato/simulation.md`: captured isolated world, shared execution semantics and truthful failure/limit reporting.

### External references

None required or consulted. This is a source-grounded internal API/algorithm investigation; no library/version decision is involved.

## Caveats / Not Found

- No existing complete pure unique-placement API was found. Implementing only a `nextRails` fork check would not meet physical-feasibility/uniqueness requirements.
- `createOwned` is lifecycle staging, not side-effect-free candidate evaluation.
- Existing solver methods sometimes conflate finite-path exhaustion and failure to find a numerical root; a demand-driven expansion API must distinguish "needs more support" from "infeasible" rather than treating every null identically.
- No runtime, tests, live topology, or deployment was changed/run during this bounded research.
- ATO's automatic reverse behavior is assigned to the parent agent, outside this research scope.
