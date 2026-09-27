# Stage 2 implementation plan

## Start gate

- [x] Stage 1 public resource, restriction, inspection, applied-plan dependency/edit and owner-lifecycle APIs are complete, reviewed and archived.
- [x] Freeze the module/plugin/product name as `JapaneseSignals`; package names, persisted type IDs and script identifiers remain separate API decisions.
- [x] Load SimpleATO, Basic, Bukkit, persistence, interaction/render and shared module specs.
- [x] Keep runtime code current-model only. If pre-release protection definitions need importing, extend the separate auditable world-format migrator with dry-run and recovery rather than adding a Stage 2 runtime compatibility path.
- [x] Re-run the PRD convergence pass against the completed Stage 1 contracts and present the final Stage 2 summary for fresh implementation approval.

## Deliverable 1: complete SimpleATO extraction

Current extraction progress:

- [x] Move Timetable model, validation, runtime store, extension registry, persistence, simulation helpers and Bukkit callers into SimpleATO; remove the Basic owner without aliases or runtime fallback.
- [x] Move Line model, revisioned store, planner, infrastructure persistence, isolated simulation capture and Bukkit callers into SimpleATO; restore after SimpleATO Destination/Station materialization without aliases or runtime fallback.
- [x] Move Destination/Station model, stores, StationCall runtime/resolver, berth allocation, infrastructure persistence and Bukkit callers into SimpleATO; keep only the derived route-anchor projection in Basic WayPointStore, with no Basic aliases or mirror stores. Move Depot reset coordination to SimpleATO to avoid a reverse dependency.
- [x] Move Depot/DepotStore, rolling-stock assignment, runtime persistence and Bukkit callers into SimpleATO; keep Basic runtime as a strict revision/epoch pairing document without a mirror ledger or compatibility reader.

- [ ] Inventory remaining ATO/operations production owners and callers in Basic/BukkitPlugin.
- [ ] Move the remaining automated operating policy, ATO/ATP constraint owners, operating direction and operational route selection into `SimpleATO` with existing behavior tests; retain Basic's generic dynamics/actuation and physical safety contracts shared with manual driving.
- [ ] Create `SimpleATOBukkit` with its own descriptor, lifecycle, service discovery and packaging/boundary tests.
- [ ] Move only SimpleATO platform adaptation and presentation into `SimpleATOBukkit`; define immutable per-object revisions,
  keep one in-flight plus one newest pending value per object, reject stale revisions and apply through the target's Folia
  owner. Keep RailWand/manual driving/host composition in `BukkitPlugin`.
- [ ] Remove each old production owner after its replacement is wired; prove there is one writable state owner and no compatibility wrapper.
- [ ] Integrate concrete signal sources with the existing SimpleATO inspection declarations, single shared perception scan and dynamic braking-distance comparison; do not build another inspection runtime.
- [ ] Wire `JapaneseSignals` through the existing namespaced most-restrictive restriction pipeline; plugin output cannot widen Basic grants or existing ATO/ATP limits.

## Deliverable 2: JapaneseSignals foundation

- [ ] Scaffold the independent Java/Kotlin plugin, descriptor, compile-only host dependencies, capability/version checks and separate JAR packaging.
- [ ] Establish plugin-owned world runtime, strict current-model sidecar, stable namespaced IDs and atomic primary/backup store.
- [ ] Move `ProtectionRegionDefinition`, boundary/catalog, graph-cut/interior derivation, route-window matching and validation tests out of Basic.
- [ ] Move protection-region authoring, diagnostics, RailObjectWand mode and persistence/composition callers into the plugin.
- [ ] Remove Basic's protection-region/fixed-block target API, `AuthoritySection` ownership and `PROTECTION_REQUIRED` policy after all callers migrate.
- [ ] Add plugin-owned fixed-block definitions and the plugin-local common section observation interface if the reference rules need it.

## Route and resource integration

- [ ] Index protection-region gates/interiors and fixed blocks by topology plus plugin-definition revision; rebuild outside the tick loop.
- [ ] Match only resources reached by the shared frozen-route perception and validate entry/interior/exit traversal.
- [ ] Implement the plugin route-decision layer that chooses a post-exit authority boundary for fixed- or moving-block territory without recursive Basic contributors.
- [ ] Submit only additive flank/approach/block resources; rely on Basic to derive every rail/turnout/diamond physical resource.
- [ ] Keep region identity non-exclusive by default; test disjoint paths through one throat and conflicts from actual shared/additional resources.
- [ ] Derive permissive aspects only from a committed Basic grant and valid SimpleATO authority; missing or stale plugin state remains restrictive.
- [ ] Use the existing host cursor/release/extension lifecycle and unified owner-unregister gate; verify planned disable is rejected while plugin resources remain active and forced disable retains committed claims/dependencies while stalling only affected worlds.
- [ ] Integrate route-bound diamonds for conflict/inspection without cross-line continuation or selectable state.

## Reactive signal state and customization

- [ ] Review the deferred reactive design with the user before implementing this section; settle scheduler/dependency ownership and the minimum cache/index needed.
- [ ] Implement only the dependency/invalidation mechanism approved in that review; update affected signals without a world-wide scan.
- [ ] Prove unchanged resources remain idle and stale permissive outputs cannot survive invalidation.
- [ ] Propagate changed downstream state only as far as needed; stop when an output is unchanged and fail closed if an actual feedback loop cannot settle.
- [ ] Define `JapaneseSignals` Java/Kotlin aspect/block/interlocking rules before adding scripts.
- [ ] In a separately reviewed batch, define optional JS/YAML input views, setters such as speed/model variant, validation, execution budget and failure behavior.
- [ ] Keep scripts away from mutable world objects and raw Basic claim mutation; all outputs pass plugin validation and host restriction/resource APIs.

## Timetable, authoring and rendering

- [ ] Add signal-plugin participation to the existing selected-route timetable compile/audit with pinned topology/plugin/timetable/train-assignment revisions; do not run pathfinding again.
- [ ] Extend the existing atomic applied-plan dependency declaration with every exact JapaneseSignals runtime resource referenced by those selected routes; keep this non-exclusive edit protection distinct from live train claims.
- [ ] Implement draft/apply/replace/draining/unapply lifecycle with no dependency changes from save/dry-run and no removal beneath live work.
- [ ] Route all affected editors through the common edit permit and show blocking owner/revision diagnostics.
- [ ] Add create/edit/delete/inspect/validate/dry-run commands/tools and topology-migration recovery points.
- [ ] Add plugin-owned aspect/model rendering through the `SimpleATOBukkit` revisioned latest-only bridge, with target-aware
  scheduling, change-only updates, stale-revision rejection and idempotent world/plugin cleanup.

## Validation

- [ ] Module-boundary tests prove Basic, SimpleATO, SimpleATOBukkit and `JapaneseSignals` have one owner per state and valid dependency direction.
- [ ] Packaging tests prove separate JARs, descriptors, compile-only host dependencies and no shadowed host classes.
- [ ] Region tests cover simple turnout, multi-turnout throat, leak, ambiguity, direction, topology invalidation and unsupported overlap.
- [ ] Resource tests cover mandatory Basic physical floor, valid additions, omitted/invalid additions, disjoint paths and atomic conflict denial.
- [ ] Fixed/moving transition tests cover whole next block versus exact post-exit boundary and sufficient train-tail clearance.
- [ ] Reactive tests cover inspection-distance cache invalidation, host braking distance, affected-only dependency propagation and idle unchanged state.
- [ ] Constraint tests prove plugin output only tightens authority/speed and stale/exceptional state fails restrictive.
- [ ] Lifecycle tests cover enable/disable, missing capability, world unload/reload, live-grant recovery and rendering cleanup.
- [ ] Persistence tests use fixed current-model fixtures plus corruption/orphan cases. Add a legacy fixture only for a separately approved sidecar migration support window.
- [ ] Timetable tests cover audit diagnostics, atomic applied-plan dependency apply/replace, draining and edit blocking without treating dependencies as train claims.
- [ ] Run focused module tests, then `./gradlew test`, plugin packaging tasks and Folia smoke checks.
- [ ] Run `git diff --check`.

## Reviewable commit order

1. SimpleATO production slice migrations, one owner at a time.
2. `SimpleATOBukkit` scaffold and platform adapter extraction.
3. `JapaneseSignals` scaffold and persistence boundary.
4. Protection-region domain/index migration from Basic.
5. Generic Basic intent integration and removal of old protection policy.
6. Fixed blocks and Japanese signal policy.
7. Reactive state/dependency scheduling.
8. Authoring/rendering and optional customization runtime.
9. Timetable audit/applied-plan dependency integration and lifecycle closure.

Each commit must explain which runtime flow it changes, the mechanism used, the remaining transitional owner and the focused verification performed. Stop for user review after every commit.

## Rollback points

- Keep module extraction, protection migration, route integration, reactive policy, persistence and rendering in separate commits.
- Do not delete the old owner until the new owner is wired and parity tests pass in the same reviewed slice.
- Do not enable permissive aspects until resource/grant/constraint integration tests pass.
- Leave `JapaneseSignals` disabled for a world when persisted state or topology derivation cannot be validated safely.
