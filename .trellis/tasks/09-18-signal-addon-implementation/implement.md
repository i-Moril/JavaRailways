# Stage 2 implementation plan

## Start gate

- [x] Stage 1 public resource, restriction, inspection, applied-plan dependency/edit and owner-lifecycle APIs are complete, reviewed and archived.
- [x] Freeze the module/plugin/product name as `SimpleSignals`; the scaffold fixes the Java package root as `work.art1st.javarailways.simplesignals`, while persisted type IDs and script identifiers remain separate API decisions.
- [x] Load SimpleATO, Basic, Bukkit, persistence, interaction/render and shared module specs.
- [x] Keep runtime code current-model only. If pre-release protection definitions need importing, extend the separate auditable world-format migrator with dry-run and recovery rather than adding a Stage 2 runtime compatibility path.
- [x] Re-run the PRD convergence pass against the completed Stage 1 contracts and present the final Stage 2 summary for fresh implementation approval.

## Deliverable 1: complete SimpleATO extraction

Current extraction progress:

- [x] Move Timetable model, validation, runtime store, extension registry, persistence, simulation helpers and Bukkit callers into SimpleATO; remove the Basic owner without aliases or runtime fallback.
- [x] Move Line model, revisioned store, planner, infrastructure persistence, isolated simulation capture and Bukkit callers into SimpleATO; restore after SimpleATO Destination/Station materialization without aliases or runtime fallback.
- [x] Move Destination/Station model, stores, StationCall runtime/resolver, berth allocation, infrastructure persistence and Bukkit callers into SimpleATO; keep only the derived route-anchor projection in Basic WayPointStore, with no Basic aliases or mirror stores. Move Depot reset coordination to SimpleATO to avoid a reverse dependency.
- [x] Move Depot/DepotStore, rolling-stock assignment, runtime persistence and Bukkit callers into SimpleATO; keep Basic runtime as a strict revision/epoch pairing document without a mirror ledger or compatibility reader.

- [x] Inventory remaining ATO/operations production owners and callers in Basic/BukkitPlugin.
- [x] Move ATO mission, route progress, planner, constraint manager/types and world coordinator/diagnostics into `SimpleATO`; retain Basic's generic dynamics/actuation, route/perception snapshots, RailObject store and physical safety contracts shared with manual driving. Preserve manual fail-close through a generic Basic world safety interlock driven by the SimpleATO coordinator.
- [x] Delete the legacy `AtoProvider` registry/boundary/controller-switching path instead of migrating or replacing it. Signal extensions use the Stage 1 inspection and namespaced restrictive-output contracts.
- [x] Finish the separate operating-direction authoring/persistence seam: SimpleATO owns the sparse store, topology-bound overlay, authoring, route predicate and infrastructure persistence; Basic has no direction field or fallback owner.
- [x] Collapse Stage 2 persistence numbering to the real boundaries: infrastructure migrates once from outer V1/Basic V1 to outer V2/Basic V2/SimpleATO V1; Basic runtime moves once from V2 to V3 while additive SimpleATO runtime fields remain V1. Keep the converter pure and leave packaged file migration/recovery to the 09-24 task.
- [x] Create `SimpleATOBukkit` as an independent adapter library module with direct main-plugin composition and no descriptor, second `JavaPlugin` or internal service discovery. Package it through the main plugin's project dependency, and move SimpleATO Core-slot startup wiring plus SimpleATO diagnostics into it as the first real slice.
- [x] Audit the platform presentation boundary: keep the existing generic latest-only scheduler with its actual
  `BukkitPlugin` consumers, keep concrete signal rendering in `SimpleSignals`, and do not turn `SimpleATOBukkit` into a
  generic platform bridge merely to share one helper. Move only platform behavior with a real SimpleATO owner/caller.
- [x] Remove each old production owner after its replacement is wired; a final source audit confirms Basic has no Destination/Station/Depot/Line/Timetable/operating-direction/ATO-policy store or compatibility facade, while BukkitPlugin only composes or presents the SimpleATO-owned stores. Basic's remaining RailObject/WayPoint projections and generic pathfinding/actuation/resource code have distinct shared owners; protection-region ownership has now moved to SimpleSignals.
- [x] Move the immutable-topology search implementation into Basic and add the neutral forbidden-span/path-replacement contract. Compile SimpleATO operating direction into the same restrictions; keep complete replacement paths atomic in the query graph.
- [x] Add owner/world-scoped route-planning source registration through the existing Bukkit signal owner registry. Compile one stable source snapshot once per immutable-topology query, combine it deterministically with operating directions, and reuse the unified owner unregister/world lifecycle without a second token framework.
- [x] Migrate Mission, Station, Line, Timetable and the Bukkit route diagnostic to one SimpleATO immutable-topology planning seam, preserve precomputed timetable routes and typed direction failures, then delete the live-`RailView` planner and compiler.
- [ ] Integrate concrete signal sources with the existing SimpleATO inspection declarations, single shared perception scan and dynamic braking-distance comparison; do not build another inspection runtime.
- [ ] Wire `SimpleSignals` through the existing namespaced most-restrictive restriction pipeline; plugin output cannot widen Basic grants or existing ATO/ATP limits.

## Deliverable 2: SimpleSignals foundation

- [x] Scaffold the independent Java/Kotlin plugin, descriptor, compile-only host dependencies, capability checks and separate JAR packaging. The descriptor hard-depends on JavaRailways and declares the Bukkit API target; the current host exposes typed services rather than a numeric signal-API version, so startup validates the five required service capabilities instead of inventing string-version comparison.
- [x] Establish the planning-only plugin-owned world runtime: each exact host world load gets fresh candidate catalogs and exactly one namespaced fixed-route source; startup publishes empty definitions, corrupt/unregistered host worlds remain locally disabled, and topology changes fail closed until explicit republish.
- [ ] Add the strict current-model SimpleSignals sidecar, stable persisted namespaced IDs and atomic primary/backup store; no persistence is implied by the planning runtime.
- [x] Move `ProtectionRegionDefinition`, boundary/catalog, graph-cut/interior derivation, route-window matching and validation tests out of Basic. The plugin catalog atomically publishes one topology/definition-revision snapshot and indexes candidate regions by rail; Basic still performs independent structural route validation.
- [x] Define SimpleSignals-private fixed protection route tables and compile each region table atomically into one neutral Basic `PathReplacement`. Publication validates gate correspondence, direction, continuity and interior containment; the cached source rejects stale topology/region publications and is registered by the planning-only world runtime.
- [ ] Move protection-region authoring, diagnostics, RailObjectWand mode and persistence/composition callers into the plugin.
- [x] Remove Basic's protection-region request/claim/validator API and `PROTECTION_REQUIRED` policy after confirming the generic route-resource runtime is the production path. Keep and rename the generic tail-release controller used by that runtime.
- [ ] Add plugin-owned fixed-block definitions and the plugin-local common section observation interface if the reference rules need it.

## Route and resource integration

- [x] Index protection-region gates/interiors by topology plus plugin-definition revision and rebuild only on catalog publication, outside the tick loop.
- [ ] Index fixed blocks by topology plus plugin-definition revision outside the tick loop.
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
- [ ] Define `SimpleSignals` Java/Kotlin aspect/block/interlocking rules before adding scripts.
- [ ] In a separately reviewed batch, define optional JS/YAML input views, setters such as speed/model variant, validation, execution budget and failure behavior.
- [ ] Keep scripts away from mutable world objects and raw Basic claim mutation; all outputs pass plugin validation and host restriction/resource APIs.

## Timetable, authoring and rendering

- [ ] Add signal-plugin participation to the existing selected-route timetable compile/audit with pinned topology/plugin/timetable/train-assignment revisions; do not run pathfinding again.
- [ ] Extend the existing atomic applied-plan dependency declaration with every exact SimpleSignals runtime resource referenced by those selected routes; keep this non-exclusive edit protection distinct from live train claims.
- [ ] Implement draft/apply/replace/draining/unapply lifecycle with no dependency changes from save/dry-run and no removal beneath live work.
- [ ] Route all affected editors through the common edit permit and show blocking owner/revision diagnostics.
- [ ] Add create/edit/delete/inspect/validate/dry-run commands/tools and topology-migration recovery points.
- [ ] Add plugin-owned aspect/model rendering in `SimpleSignals`, with target-aware latest-only scheduling, change-only
  updates, stale-revision rejection and idempotent world/plugin cleanup. Use a host platform API only if a concrete
  cross-plugin requirement is reviewed first; do not route rendering through `SimpleATOBukkit`.

## Validation

- [ ] Module-boundary tests prove Basic, SimpleATO, SimpleATOBukkit and `SimpleSignals` have one owner per state and valid dependency direction.
- [ ] Packaging checks for the independently installed `SimpleSignals` plugin prove it has its own descriptor/JAR, compile-only host dependencies and no shadowed host classes; embedded `SimpleATOBukkit` needs no separate deployable-artifact verification.
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
2. Embedded `SimpleATOBukkit` library scaffold and platform adapter extraction.
3. `SimpleSignals` scaffold and persistence boundary.
4. Protection-region domain/index migration from Basic.
5. Generic Basic intent integration and removal of old protection policy.
6. Fixed blocks and SimpleSignals policy.
7. Reactive state/dependency scheduling.
8. Authoring/rendering and optional customization runtime.
9. Timetable audit/applied-plan dependency integration and lifecycle closure.

Each commit must explain which runtime flow it changes, the mechanism used, the remaining transitional owner and the focused verification performed. Stop for user review after every commit.

## Rollback points

- Keep module extraction, protection migration, route integration, reactive policy, persistence and rendering in separate commits.
- Do not delete the old owner until the new owner is wired and parity tests pass in the same reviewed slice.
- Do not enable permissive aspects until resource/grant/constraint integration tests pass.
- Leave `SimpleSignals` disabled for a world when persisted state or topology derivation cannot be validated safely.
