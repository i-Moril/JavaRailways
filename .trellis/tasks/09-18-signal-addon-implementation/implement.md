# Stage 2 implementation plan

## Start gate

- [ ] Stage 1 public resource, restriction, inspection and applied-plan dependency/edit APIs are complete and reviewed.
- [x] Freeze the module/plugin/product name as `JapaneseSignals`; package names, persisted type IDs and script identifiers remain separate API decisions.
- [ ] Load SimpleATO, Basic, Bukkit, persistence, interaction/render and shared module specs.
- [ ] Confirm no runtime compatibility path is required; if development data needs migration, design a separate dry-run tool and recovery point first.
- [ ] Re-run the PRD convergence pass and present the final Stage 2 summary for explicit implementation approval.

## Deliverable 1: complete SimpleATO extraction

- [ ] Inventory remaining ATO/operations production owners and callers in Basic/BukkitPlugin.
- [ ] Move ATO/ATP control, operating direction and operational route selection into `SimpleATO` with existing behavior tests.
- [ ] Move Destination, Station, Depot, Line, Timetable, rolling-stock assignment, Dispatcher/applied-plan state and persistence into `SimpleATO` in dependency-safe slices.
- [ ] Create `SimpleATOBukkit` with its own descriptor, lifecycle, service discovery and packaging/boundary tests.
- [ ] Move only SimpleATO platform adaptation and presentation into `SimpleATOBukkit`; keep RailWand/manual driving/host composition in `BukkitPlugin`.
- [ ] Remove each old production owner after its replacement is wired; prove there is one writable state owner and no compatibility wrapper.
- [ ] Implement cached resource inspection declarations, dynamic braking-distance comparison and dependency-driven update scheduling in SimpleATO.
- [ ] Add namespaced most-restrictive restriction integration; plugin output cannot widen Basic grant or existing ATO/ATP limits.

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
- [ ] Use host cursor/release/extension lifecycle and verify plugin disable does not release committed claims beneath trains.
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

- [ ] Add signal-plugin participation in whole-timetable compile/audit with pinned topology/plugin/timetable/train-assignment revisions.
- [ ] Include every referenced topology rail/node and runtime resource in one atomic applied-plan dependency declaration.
- [ ] Implement draft/apply/replace/draining/unapply lifecycle with no dependency changes from save/dry-run and no removal beneath live work.
- [ ] Route all affected editors through the common edit permit and show blocking owner/revision diagnostics.
- [ ] Add create/edit/delete/inspect/validate/dry-run commands/tools and topology-migration recovery points.
- [ ] Add plugin-owned aspect/model rendering with target-aware scheduling, change-only updates and idempotent cleanup.

## Validation

- [ ] Module-boundary tests prove Basic, SimpleATO, SimpleATOBukkit and `JapaneseSignals` have one owner per state and valid dependency direction.
- [ ] Packaging tests prove separate JARs, descriptors, compile-only host dependencies and no shadowed host classes.
- [ ] Region tests cover simple turnout, multi-turnout throat, leak, ambiguity, direction, topology invalidation and unsupported overlap.
- [ ] Resource tests cover mandatory Basic physical floor, valid additions, omitted/invalid additions, disjoint paths and atomic conflict denial.
- [ ] Fixed/moving transition tests cover whole next block versus exact post-exit boundary and sufficient train-tail clearance.
- [ ] Reactive tests cover inspection-distance cache invalidation, host braking distance, affected-only dependency propagation and idle unchanged state.
- [ ] Constraint tests prove plugin output only tightens authority/speed and stale/exceptional state fails restrictive.
- [ ] Lifecycle tests cover enable/disable, missing capability, world unload/reload, live-grant recovery and rendering cleanup.
- [ ] Persistence tests use fixed current/legacy migration fixtures according to the approved support window; current round-trip alone is insufficient.
- [ ] Timetable tests cover audit diagnostics, atomic lease apply/replace, draining and edit blocking.
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
9. Timetable audit/lease integration and lifecycle closure.

Each commit must explain which runtime flow it changes, the mechanism used, the remaining transitional owner and the focused verification performed. Stop for user review after every commit.

## Rollback points

- Keep module extraction, protection migration, route integration, reactive policy, persistence and rendering in separate commits.
- Do not delete the old owner until the new owner is wired and parity tests pass in the same reviewed slice.
- Do not enable permissive aspects until resource/grant/constraint integration tests pass.
- Leave `JapaneseSignals` disabled for a world when persisted state or topology derivation cannot be validated safely.
