# Stage 2: SimpleATO extraction and JapaneseSignals

## Goal

Finish separating the operational ATO stack from the JavaRailways host, then implement the first independently deployable Java/Kotlin signal-system plugin using only the completed Stage 1 observation, resource, restriction, inspection, applied-plan dependency and lifecycle contracts.

The result must prove that protection regions and fixed blocks are optional policy supplied by a concrete signal system rather than universal Basic/SimpleATO concepts.

## Confirmed facts

- Stage 1 is complete and archived. It provides the Basic physical-resource floor, plugin-contributed resources, one claim/preparation/grant lifecycle, suffix extension, tail release, compiled inspection bindings, namespaced restrictive outputs, applied-plan edit dependencies and one owner-level unload gate.
- `SimpleATO` owns its controller, mission/constraint policy, world coordinator/diagnostics, frozen-route/authority client, inspection runtime, signal epoch runtime, restriction adapter, timetable dispatcher, manual navigation and SimpleATO runtime snapshot. The legacy `AtoProvider` registry/boundary switching path has been deleted; signal extensions use inspection plus namespaced restrictive outputs. Destination, Station, Depot, Line, Timetable and rolling-stock assignment also belong to SimpleATO; remaining extraction work is platform adaptation and the operating-direction authoring/persistence seam.
- `SimpleATOBukkit` does not yet exist as a separate plugin/module.
- Basic currently contains a transitional protection-region catalog, graph-cut resolver, route window and admission seam. Generic topology projection, resource identities, claims, preparation and release remain useful; protection-specific ownership must move out.
- Basic derives a non-removable physical footprint and allows signal plugins only to add validated resources or stricter holds/restrictions.
- SimpleATO owns operating-route selection, resource inspection scheduling and the single most-restrictive ATO/ATP pipeline.
- Generic longitudinal dynamics, actuation contracts and physical safety mechanisms shared by manual driving and automation remain in Basic; SimpleATO owns the automated operating policy that consumes them.
- Each independent signalling system will be a separately compiled Java/Kotlin plugin. It may choose its own JS, YAML or other customization layer; no common script engine is mandatory.

## Requirements

### A. SimpleATO module extraction

- Move the remaining automated operating policy, operating direction, Destination, Station, Depot, Line, Timetable, rolling-stock assignment, operational route planning, timetable apply state and their persistence into platform-neutral `SimpleATO`.
- Keep generic longitudinal dynamics, train-actuation primitives and physical safety floors shared with manual driving in Basic; do not move them merely because SimpleATO consumes them.
- Keep generic graph algorithms and physical/resource validation in Basic; do not move concrete signalling policy into SimpleATO.
- Create `SimpleATOBukkit` as the Bukkit/Paper adapter for SimpleATO lifecycle, service lookup, ATO presentation and common platform interaction. It owns immutable revisioned presentation publication and per-object latest-only Folia dispatch; this is not backfilled into the Stage 1 host plugin.
- Leave low-level `RailWand`, manual driving and host world/composition behavior in `BukkitPlugin`.
- Remove old Basic/BukkitPlugin owners as each slice moves; never keep two writable production implementations or add runtime compatibility wrappers for development data without separate approval.

### B. Independent `JapaneseSignals` plugin

- Create a separately deployable Java/Kotlin plugin/artifact with its own descriptor, lifecycle, persistence boundary, commands/tools and rendering.
- Depend on JavaRailways/SimpleATO public contracts at compile time and host services at runtime; do not shadow host classes.
- Own its fixed-block definitions, protection-region boundaries/interiors, interlocking policy, signal definitions/aspects, renderer state, authoring and optional script/configuration runtime.
- Publish model/aspect changes as immutable per-object presentation revisions. The adapter may skip obsolete pending visual revisions but must converge to the newest value without delaying the railway safety epoch.
- Migrate the current protection-region domain code out of Basic: definition, catalog, graph-cut/interior derivation, route-window matching, authoring validation, persistence and diagnostics. Reuse Basic topology/resource APIs instead of copying the physical graph or claim registry.
- Allow one player-authored region to protect a simple turnout or a complete station throat. Do not synthesize one stop/protection boundary per turnout.
- At authoring/update time, cut the immutable directed topology at region gates, derive the selected bounded interior and reject leaks, ambiguity, invalid directions and unsupported overlap. Cache/index the result by topology and plugin-definition revision; do not flood-fill each tick.
- At runtime, match only regions/blocks reached by the train's frozen route and shared perception. The plugin chooses the requested authority boundary and additive resources needed by its policy; Basic independently restores all traversed rail/turnout/diamond resources.
- For a protection-region entry, request enough frozen-route range for the train to leave the region and satisfy the plugin's exit policy. If the following territory is a fixed block, the plugin may require that whole block; in moving-block territory it may choose an exact route boundary. Basic does not make this policy choice.
- A region ID must not be an exclusive whole-region lock by default. Physical path/conflict resources and explicit flank/approach resources determine conflicts, allowing disjoint paths through one throat when the concrete policy permits it.
- Implement fixed blocks as plugin-owned authority/resource policy. Protection regions and blocks may expose a common plugin-local section observation API, but Basic and SimpleATO do not own or persist that abstraction.
- Derive every permissive aspect from a confirmed Basic grant plus current SimpleATO authority. Plugin output can only keep or tighten speed/authority; missing, stale, corrupt or disabled state is restrictive.
- Use SimpleATO's existing shared route perception, inspection scheduler and reviewed declaration contract while keeping dynamic braking distance host-owned per tick. Do not invoke unchanged signal policy every tick, add a second forward scan or move the concrete plugin's downstream dependency graph into SimpleATO.
- Do not add a second per-train route scanner, world-wide per-signal tick loop or `allTrains × allSignals` lookup.
- Support diamond conflicts through Basic's route-bound physical resources without treating a diamond as a switch or topology continuation.
- Release resources through the host grant/cursor lifecycle. Planned plugin disable must first remove its applied-plan dependencies and then pass the single owner-unregister gate; the gate rejects disable while that owner still has pending requests, live claims, referenced resource types, active inspection bindings or non-empty restrictions. If Bukkit has already forced the plugin off, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds instead of widening authority.

### C. Timetable, editing and persistence

- Extend the existing selected-route timetable compilation before apply with required signal-plugin health, definitions, references and route-policy validity for the assigned train envelope; do not perform another path search in the plugin.
- Extend the existing atomic applied-plan dependency declaration with the signal plugin's exact referenced runtime resource IDs. Draft save/dry-run publishes no dependencies; draining unapply blocks new work and removes dependencies only after live grants clear.
- Route every covered topology, region, block, signal and operational-object edit through the common edit guard with owner/revision diagnostics.
- Store each plugin's data in its own independently versioned sidecar using stable namespaced IDs, rail UUIDs, canonical AB positions/directions and explicit references.
- Decode/migrate only at the plugin persistence boundary; business logic sees the current model and writers emit only the current version. No reflection from stored class names and no unreviewed runtime compatibility path.
- Provide in-game create/edit/delete/inspect/validate/dry-run operations and recovery points for destructive topology-related migration.

## Acceptance criteria

- [ ] `SimpleATO` contains the platform-neutral operational stack and Basic has no production owner for the migrated state.
- [ ] `SimpleATOBukkit` is a separate plugin/artifact and does not become the owner of any concrete signalling system.
- [ ] Signal presentation is applied through target-aware latest-only dispatch: an unchanged or superseded model revision creates no replay backlog and cannot delay logical signal evaluation.
- [ ] The `JapaneseSignals` JAR installs beside the host, is absent from host JARs and imports only documented public contracts.
- [ ] Protection-region/fixed-block classes, persistence and authoring are absent from Basic's target API and owned by `JapaneseSignals`.
- [ ] One region protects both a simple turnout and a multi-turnout throat without internal per-turnout stop boundaries.
- [ ] Region validation rejects leaks, ambiguity, bad gate direction and unsupported overlap outside the runtime tick.
- [ ] Basic still inserts every traversed rail, turnout and diamond resource when the plugin supplies no or incomplete additions.
- [ ] Plugin-added flank/approach/block resources join the same atomic claim; denial leaves no partial ownership or wider authority.
- [ ] Disjoint route footprints through one region may coexist when policy allows; overlapping rail/conflict/additional resources cannot.
- [ ] Fixed-block, protection-region and moving-block transitions choose their authority boundary in the signal/ATO layer without a Basic contributor closure.
- [ ] ATO stops before an unavailable grant and applies plugin speed limits/model variants only as stricter outputs.
- [ ] Unchanged signal state does not execute every signal script/update every tick; dependency changes propagate to affected upstream resources.
- [ ] Diamond crossings prevent conflicting passages without cross-line routing or runtime geometry scans.
- [ ] Disable, failure, world unload/reload and stale-state tests prove authority never widens and live claims are not dropped beneath trains.
- [ ] Timetable apply/replace/unapply and draft/draining tests prove atomic applied-plan dependency replacement, edit blocking and separation from transient live claims/occupancy waits.
- [ ] Plugin persistence round-trips current fixtures and rejects/preserves corrupt or orphaned input with actionable diagnostics.
- [ ] Unit, integration, packaging and Folia smoke checks pass.

## Out of scope

- Requiring every signalling system to use `JapaneseSignals` protection-region model, aspects, scripts or persistence.
- Multiple concrete national signalling systems in the first Stage 2 implementation.
- Certification-grade communication, redundant control channels or train-integrity proving.
- Permanent runtime compatibility for arbitrary pre-release Basic/Signal data.
- Final WebUI module ownership during the extraction and first reference-plugin batches.

## Dependency

- `09-18-signal-addon-host-apis` is complete and archived. Stage 2 must consume its public contracts rather than reopening their ownership model.
- The separate `09-24-explicit-rail-endpoint-connections` world-format migration does not block new Stage 2 runtime work. Any need to import pre-release protection definitions must be added to that auditable one-time migrator instead of becoming a runtime compatibility path.

## Deferred decisions

- Exact plugin-local JS/YAML API, script budget and model-variant vocabulary. These are reviewed after the Java/Kotlin safety/runtime boundary exists.
- Guided/shunting workflow details and WebUI packaging.

## Blocking open questions

None. The Gradle module, plugin and product name is `JapaneseSignals`. Java package names, persisted type IDs and script API names remain intentionally unfrozen until their owning implementation batches.
