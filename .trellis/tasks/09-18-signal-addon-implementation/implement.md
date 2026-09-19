# Stage 2 implementation plan

## Preparation

- [ ] Confirm Stage 1 API version and consumer example.
- [x] Fix the MVP at player-authored protection regions plus the host's existing dynamic-following constraint, using one protection pipeline.
- [ ] Load Bukkit, persistence, interaction/render, and shared cross-layer specs.

## First deliverable: physical module extraction

- [ ] Scaffold `SimpleATO` and `SimpleATOBukkit`, the independent plugin descriptor, compile-only host dependency, and packaging/boundary tests.
- [ ] Move ATO, operating direction, Destination/Station/Depot/Line/Timetable, rolling-stock assignment, operational route planning, and their persistence into `SimpleATO`; retain generic pathfinding in `Basic`.
- [ ] Move Bukkit signal/ATO rendering, `RailObjectWand`, service lookup, lifecycle, commands, and platform interaction into `SimpleATOBukkit`.
- [ ] Remove the old Basic/BukkitPlugin owners and prove there is one implementation before starting signal JavaScript/runtime work.
- [ ] Keep WebUI packaging and the exact signal JavaScript API explicitly undecided during this deliverable.

## Domain and runtime

- [ ] Add whole-timetable compile/audit results, immutable applied-plan revisions, exact infrastructure-footprint derivation, and atomic host lease acquisition/replacement/unapply.
- [ ] Separate draft storage from applied-plan state and implement explicit apply plus draining/unapply transitions; draft save/validate must not mutate leases.
- [ ] Implement stable Signal-owned boundary/region/route definition and store types plus bounded-subgraph validators.
- [ ] Implement directional gate authoring, interior selection/flood-fill, leak detection, and entry/exit/clearance diagnostics.
- [ ] Implement occupancy/authority policy for player-authored regions without per-turnout stop synthesis.
- [ ] Implement all-or-nothing route requests, composite adjacent-region claims, exit clearance, conflict handling, tail release, deterministic waiting, and recovery state.
- [ ] Integrate host diamond nodes into route compilation, inspection, and conflict claims without exposing turnout-like switching.
- [ ] Publish namespaced train restrictions through the Stage 1 service.
- [ ] Derive aspects from confirmed allocations and authority state.
- [ ] Before implementing the signal-package runtime slice, finalize its downstream indication interoperability and guided/shunting scope without reopening the completed host/module extraction.

## Platform integration

- [ ] Add service lookup and version/capability checks at enable.
- [ ] Add commands/tools for create, edit, delete, inspect, validate, and dry-run.
- [ ] Add aspect renderer with owner-aware scheduling and idempotent cleanup.
- [ ] Add world/plugin lifecycle handling and actionable diagnostics.
- [ ] Add common edit-denial presentation showing the applied timetable/revision that leases each affected resource.

## Persistence

- [ ] Add strict current-model codec, stable IDs/types, primary/backup atomic store, and fixed fixtures.
- [ ] Add orphan/corruption diagnostics and topology migration dry-run with recovery point.

## Validation

- [ ] Unit tests for region partitioning, directional boundaries, leak/ambiguity rejection, policy, aspects, conflicts, exit clearance, authority calculation, and fail-safe defaults.
- [ ] Integration tests for host observations, no-partial atomic allocation, composite adjacent regions, ATO enforcement, tail release, topology revisions, and diamond conflict/no-turn behavior.
- [ ] Lifecycle tests for enable/disable, missing host service, world unload/reload, host shutdown, and failure recovery.
- [ ] Persistence tests for fixed fixtures, strict decode, round-trip, orphan references, corruption, backup recovery, and migration.
- [ ] Packaging test proving separate JARs and no shadowed host classes.
- [ ] Folia smoke test for authoring, rendering, train passage, and cleanup.
- [ ] Apply/replace/unapply tests for all-or-nothing validation/lease commit, shared-lease overlap, edit blocking, reload reconstruction, and transient runtime conflict separation.
- [ ] Draft/applied/draining lifecycle tests proving service-start gating, no lease mutation from draft operations, and no lease release beneath live work.
- [ ] Run `git diff --check`.

## Rollback points

- Keep storage, runtime policy, authority integration, and rendering in separate reviewable commits.
- Gate permissive aspects until resource and authority integration tests pass.
- Keep the add-on disabled for a world when persisted state cannot be validated or migrated safely.
