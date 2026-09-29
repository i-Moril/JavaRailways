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
- [x] Integrate the first concrete fixed-block signal source with the existing SimpleATO inspection declarations, single shared perception scan and dynamic braking-distance comparison; do not build another inspection runtime. Fixed blocks and protection regions compile route-local authority floors through existing inspection bindings, while the production fixed-block rule uses persisted configurable outputs and downstream thresholds.
- [x] Wire injected `SimpleSignals` fixed-block rule output through the existing namespaced most-restrictive restriction pipeline; signal-epoch invalidation marks only current same-source inspection bindings, each callback publishes from the complete active declaration set, and plugin output cannot widen Basic grants or existing ATO/ATP limits. Production concrete rule bindings remain a separate item below.

## Deliverable 2: SimpleSignals foundation

- [x] Scaffold the independent Java/Kotlin plugin, descriptor, compile-only host dependencies, capability checks and separate JAR packaging. The descriptor hard-depends on JavaRailways and declares the Bukkit API target; the current host exposes typed services rather than a numeric signal-API version, so startup validates the five required service capabilities instead of inventing string-version comparison.
- [x] Establish the planning-only plugin-owned world runtime: each exact host world load gets fresh candidate catalogs and exactly one namespaced fixed-route source; startup publishes restored definitions or an unwritten empty revision 0 when missing, corrupt/unregistered host worlds remain locally disabled, and topology changes fail closed until explicit republish.
- [x] Add the strict current-model SimpleSignals world-definition sidecar with stable namespaced IDs, canonical UUID/AB positions and complete fixed-route traversals. The plugin loads primary/backup and compiles the full candidate against the current host topology before publishing a usable planning source; missing starts empty without writing, while corrupt/orphan/rejected input installs only a rejecting fail-closed source and no usable runtime. Writes use same-directory temp, last-known-valid backup rotation and atomic-move fallback with no runtime legacy reader.
- [x] Move `ProtectionRegionDefinition`, boundary/catalog, graph-cut/interior derivation, route-window matching and validation tests out of Basic. The plugin catalog atomically publishes one topology/definition-revision snapshot and indexes candidate regions by rail; Basic still performs independent structural route validation.
- [x] Define SimpleSignals-private fixed protection route tables and compile each region table atomically into one neutral Basic `PathReplacement`. Publication validates gate correspondence, direction, continuity and interior containment; the cached source rejects stale topology/region publications and is registered by the planning-only world runtime.
- [x] Add the plugin-local protection definition patch transaction: expected-revision create/update/delete composition, fresh prepare/compile, exact old+new affected-rail edit permit, persistence-before-publication commit and typed failure diagnostics. Retained region/table revisions are monotonic, and every failure preserves the old disk/live state.
- [ ] Connect the completed authoring transaction and diagnostics to a SimpleSignals-owned RailObjectWand/command mode; no UI caller exists yet.
- [x] Remove Basic's protection-region request/claim/validator API and `PROTECTION_REQUIRED` policy after confirming the generic route-resource runtime is the production path. Keep and rename the generic tail-release controller used by that runtime.
- [x] Add plugin-owned fixed-block definitions, exact-topology compilation, route-local reverse index, derived Basic occupancy geometry, protection-interior non-overlap validation and current-model persistence. The patch model validates fixed-block edits, while live geometry publication remains gated by the atomic source-replacement item below.
- [x] Add the first plugin-local common section observation slice for fixed blocks: one host physical-epoch source publishes immutable baseline/changed occupancy and directed crossings, while unchanged blocks remain idle. Protection regions join only with concrete interlocking rules.
- [x] Define an atomic host transaction for live fixed-block geometry source replacement. Candidate sources compile before the caller commit callback; fixed-block sidecar/publication/occupancy runtime and host areas cross one simulation barrier, while edit permits still reject operationally protected rails before save. Wand/command callers remain separate work.

## Route and resource integration

- [x] Index protection-region gates/interiors by topology plus plugin-definition revision and rebuild only on catalog publication, outside the tick loop.
- [x] Index fixed blocks by exact topology root plus plugin-definition revision outside the tick loop.
- [x] Match fixed-block resources only on the frozen-route authority prefix: reuse one plugin-local directed-route projection,
  discover candidates through the rail reverse index, require a complete entry-to-exit traversal and fail closed on a partial block.
- [x] Match protection-region windows on the exact frozen-route inspection binding, validate one complete entry/interior/exit traversal and compile an entry-to-exit authority floor. If the selected route has exactly one fixed block beginning at the exit, extend that floor through its exit; multiple same-point matches are conflicting definitions, not route branches, and fail closed. Basic derives the physical resources for the expanded route prefix, and no exclusive region token is added. See the [owner contract](../../spec/simple-signals/backend/protection-region-authority-transition.md).
- [ ] Implement the remaining plugin policy that chooses a moving-block post-exit boundary without recursive Basic contributors. Fixed-block entry-to-exit floors and the protection-region-to-immediate-fixed-block transition are complete; moving-block policy and its exact clearance contract remain.
- [x] Submit fixed-block tokens only as additive resources through the host contributor; rely on Basic to derive every
  rail/turnout/diamond physical resource and its existing atomic claim/release lifecycle.
- [ ] Add concrete flank/approach resources when the first interlocking rule requires them; do not invent generic placeholders.
- [ ] Keep region identity non-exclusive by default; test disjoint paths through one throat and conflicts from actual shared/additional resources.
- [ ] Derive permissive aspects only from a committed Basic grant and valid SimpleATO authority; missing or stale plugin state remains restrictive.
- [x] Hold each contributed fixed-block identity to its last matched exit and use the existing host cursor/release/extension lifecycle;
  do not create a plugin release queue.
- [ ] Verify the unified owner-unregister gate rejects planned disable while plugin resources remain active and forced disable retains committed claims/dependencies while stalling only affected worlds.
- [ ] Integrate route-bound diamonds for conflict/inspection without cross-line continuation or selectable state.

## Reactive signal state and customization

- [x] Review the deferred reactive design: SimpleSignals owns a one-way provider-to-upstream-consumer invalidation chain; no fixed-point solver or host-owned dependency graph. Record the future Core active-continuation snapshot prerequisite without implementing it in this batch.
- [x] Publish Core's immutable active-turnout continuations in the coherent world frame and project them unchanged through Basic observation. Rebuild the turnout-port map only when topology or live selection revision changes; reject a candidate frame if the selection changes during epoch processing.
- [x] Implement only the dependency/invalidation mechanism approved in that review: precompile an acyclic one-way graph and evaluate only reached consumers, stopping unchanged branches without a world-wide scan. Concrete aspect/limit rules remain separate.
- [x] Prove unchanged resources remain idle and stale permissive outputs cannot survive invalidation: unchanged host area state skips the plugin callback, unchanged rule output stops the branch, and rule/downstream failure publishes an unavailable zero-limit state instead of retaining an old permissive result.
- [x] Propagate changed downstream state only as far as needed; stop when an output is unchanged and reject dependency construction that would introduce reverse/downstream feedback.
- [x] Define the `SimpleSignals` Java/Kotlin rule execution boundary before adding scripts: immutable controlled-section/downstream views enter an injected rule and a validated speed plus opaque model variant leaves it; no concrete aspect vocabulary or speed table is hard-coded.
- [ ] Implement concrete rule providers through that boundary without moving policy into Basic or SimpleATO. The persisted fixed-block provider is complete; protection-region/interlocking providers remain.
- [ ] In a separately reviewed batch, define optional JS/YAML input views, setters such as speed/model variant, validation, execution budget and failure behavior.
- [ ] Keep scripts away from mutable world objects and raw Basic claim mutation; all outputs pass plugin validation and host restriction/resource APIs.

## Timetable, authoring and rendering

- [ ] Add signal-plugin participation to the existing selected-route timetable compile/audit with pinned topology/plugin/timetable/train-assignment revisions; do not run pathfinding again.
- [ ] Extend the existing atomic applied-plan dependency declaration with every exact SimpleSignals runtime resource referenced by those selected routes; keep this non-exclusive edit protection distinct from live train claims.
- [ ] Implement draft/apply/replace/draining/unapply lifecycle with no dependency changes from save/dry-run and no removal beneath live work.
- [ ] Route all remaining affected editors through the common edit permit and show blocking owner/revision diagnostics. The protection region/fixed-route definition transaction already preserves the host `TopologyEditDecision`; UI presentation remains.
- [ ] Add create/edit/delete/inspect/validate/dry-run commands/tools and topology-migration recovery points.
- [ ] Add plugin-owned aspect/model rendering in `SimpleSignals`, with target-aware latest-only scheduling, change-only
  updates, stale-revision rejection and idempotent world/plugin cleanup. Use a host platform API only if a concrete
  cross-plugin requirement is reviewed first; do not route rendering through `SimpleATOBukkit`.

## Cross-cutting online edit latency

- [x] Remove cross-world simulation-lock ownership from online signal source replacement and Basic
  edit-permit acquisition. A blocked commit callback must not prevent an old immutable signal epoch
  from completing; the next epoch alone observes the replacement.
- [x] Stage SimpleSignals JSON encoding, validation, temporary write and valid-primary backup copy
  before the final source/publication commit. The bounded callback may only recheck identity,
  atomically rename the staged primary and swap immutable references; backup rotation/cleanup follows
  publication.
- [x] Audit every RailWand create/replace/split/merge/remove path. Move final normalized
  `TopologyState` construction and any whole-world undo/persistence capture into asynchronous prepare;
  the platform/world commit may only validate the captured root, apply the prepared delta and publish
  the prepared root for the affected world.
- [x] RailWand create/rebuild/smooth/split/merge/remove/snapshot-restore now build the complete normalized
  `TopologyState` on the existing async worker. Prepared commits validate the exact base root plus only
  affected rail/endpoint results and publish the prebuilt root; stale preparation rejects before action,
  with no full-world fingerprint or synchronous builder fallback.
- [x] RailWand structural-edit undo captures both complete `before` and `expectedAfter` values in the
  asynchronous prepare pipeline. Core first freezes complete saved-rail values for the exact base/target roots;
  Bukkit then acquires the exact affected-infrastructure permit before a second worker stage prepares metadata,
  direction, render and operational-object values. The same permit remains held through owner revalidation and
  commit, so the prepared side-state cannot be superseded by an overlapping edit. The owner callback only consumes
  those prepared undo values and rejects a stale base root;
  it does not rescan the world to create the undo record. The worker now also stages the complete render sidecar
  and old-primary recovery copy; owner publication is an identity check plus atomic rename, and commit failure restores
  the old primary before releasing the edit permit. A per-world writer slot prevents two whole-file writers from racing,
  while stale record snapshots reject instead of overwriting a preceding disjoint edit. User-triggered application of an existing undo record
  deliberately keeps its complete owner-thread current-state checks until non-topology stores have one reviewed
  revision/stale boundary; optimizing that separate flow is not part of this item. Transient
  `SavedRailV1.activeTrackAtA/B` is now normalized out of structural undo before/after equality. Undo apply preserves
  the live selection when it remains in the ordered target connections, otherwise selects the first target or null;
  unrelated turnout switching therefore does not stale or overwrite the structural undo.
- [x] Prepared topology failure handling now compares immutable `O(delta)` base rail identities,
  endpoint continuations and crossing pairs: failures before mutation or after complete rollback keep
  the world healthy, while an incomplete rollback faults it. Snapshot restore reuses the exact topology
  root for active-branch-only changes and advances the revision only for structural changes.
- [ ] Add concurrency regressions proving signal edits and edit-permit scans do not wait for the
  cross-world simulation lock, plus track-edit tests proving another world continues ticking while a
  prepared edit is delayed.

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
