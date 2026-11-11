# Research: Minimal fixed-block and protection-region Wand backend

- Query: Smallest production integration for standalone directed pending region boundaries, bounded automatic completion, retained unavailable regions after boundary deletion, and shared fixed-block validation.
- Scope: internal; source inspection only, no builds or Git operations.
- Date: 2026-10-04

## Findings

### Agreed contract and recommended implementation seam

The current PRD's guided-authoring contract (prd.md:124-169) supersedes the earlier turnout-only batch notes still present in design.md:270 and implement.md:190. Signal Wand, route recording, measuring and presets remain excluded by this dispatch. This research role did not load implement.jsonl/check.jsonl, because its role-isolation contract forbids those manifests.

Reuse `SimpleSignalsWorldRuntimeManager.applyDefinitionPatch`, its candidate preparation, edit permit, staged save and atomic host publication. Do not create another persistence store or bypass the manager for pending-boundary clicks. The host already supports local planning prohibitions through `RoutePlanningRestrictions.forbiddenTraversals`; global world-unavailable mode is unnecessary for a intentionally incomplete region.

### Minimal authored model

1. Add `pendingProtectionBoundaries: List<ProtectionBoundaryDefinition> = emptyList()` to world definitions, codec and patch/diff. It represents placed directed boundaries which do not yet belong to an accepted region. Pending boundaries alone do not impose a protection-region operating policy. They still have physical rail edit impact and go through the normal transaction.
2. Keep the last accepted region's stable ID and complete authored boundary set. A nullable per-region `boundaryDraft: List<ProtectionBoundaryDefinition>? = null` can represent an incomplete edit: null means no pending edit; a non-null list, including an empty list, is the remaining currently placed boundaries. This is internal durable state, not a user-visible repair mode. It avoids destroying the last known closed territorial envelope or requiring a second persisted derived subgraph.
3. Runtime compiles explicit available/unavailable region state with diagnostics. The original accepted authored cuts provide a restart-safe local containment envelope while the draft is incomplete. A purely in-memory last interior is insufficient: after restart, deletion of the last boundary otherwise loses both ownership association and the geometry to block locally.
4. Each placement explores inward from the newly placed boundary, using pending cuts plus currently placed region/draft cuts. Associate the discovered component with existing region ownership through remaining owned boundaries or intersection with that region's retained envelope. Exactly one owner means repair under that ID; more than one means ambiguous/overlapping edit and must be rejected. No owner means a new region. Do not require preselecting a region.
5. Once the draft closes and passes separation checks, replace the accepted boundary set with the new set and clear the draft in the same patch. Preserve route-table, device and placement definitions. Revalidate individual routes/bindings and restore only valid ones. A table's old `regionRevision` mismatch cannot simply be ignored or treated as proof all routes remain valid: advance the binding only as part of successful revalidation.
6. Fixed-block points can be derived from persisted block traversal endpoints and the currently selected first point in player state; a separate durable collection of orphan block points is unnecessary for the requested interaction. Shared physical coordinates do not merge block deletion and region deletion ownership.

The two fields above are additive defaults under the repository schema policy; no schema bump or runtime legacy reader is required if existing field meanings remain unchanged. Name the draft field clearly: the old accepted cuts are retained safety/repair geometry, not boundaries still rendered as placed by the player.

### Exact current gaps

- `region/ProtectionRegionDefinition.kt:23-40`: region requires a non-empty boundary list; there is no standalone boundary list, incomplete state or ownership record for the zero-boundary case.
- `region/ProtectionRegionGraphResolver.kt:70-99,125-149`: BFS follows the full cut component without a metre bound, then returns Resolved even when a free branch ends at a terminus. It validates connected inside halves and outside non-reconnection, but not completeness of explicitly placed boundary cuts.
- `region/ProtectionRegionTopology.kt:61-107`: derives all rails/continuations in O(world topology), including all legal turnout continuations, not current turnout selection. `ProtectionRegionCatalog.kt:107-128` derives it again for every new catalog and aborts on the first invalid region. `FixedBlockCatalog.kt:170-177` independently derives the same topology.
- `runtime/SimpleSignalsWorldRuntime.kt:456-507`: preparation serially requires every region, table, route selection, block and signal to compile. Omitting unavailable regions without changing downstream compilers creates unknown-region/route errors. This is the main required integration change, not a Wand event-handler problem.
- `region/ProtectionRouteTableCatalog.kt:190-206,217-240`: binds an exact region revision and aborts an entire table at its first invalid route. Existing `PathReplacement` requires at least one permitted path; use `forbiddenTraversals` when a region is unavailable or a configured table has zero usable paths, never an empty replacement and never silently unrestricted travel.
- `signal/ProtectionSignalCompiler.kt:33-46,104-113`: fixed-route signals, entry signals and indicators require known compiled routes. Retained devices of an unavailable region need an explicit unavailable output/presentation path. Filtering their definitions out would lose intended configuration/model retention.
- `runtime/SimpleSignalsDefinitionEditImpact.kt:105-118`: loops all authored regions and calls compiled `regionsById.getValue(regionId)`. Partial publication therefore requires collecting retained/draft boundaries, retained territorial geometry and authored routes directly for unavailable regions. Its topology-change comparison also only walks candidate compiled regions (lines 34-49), so disappearing compiled objects must seed impact explicitly.
- `runtime/SimpleSignalsWorldRuntime.kt:1069-1074`: an edit with no affected rails is rejected. Pending-boundary changes must contribute their rail IDs to edit impact even if no block/region has compiled yet.

### Bounded automatic completion

Implement a pure worker-side bounded graph-cut search adjacent to the existing resolver. Reuse one immutable `ProtectionRegionTopology` per exact `TopologyState` object, shared by click discovery and candidate catalogs. The current manager always creates fresh catalogs, so merely adding an instance cache to a catalog does not avoid rebuilding the world index on each click; pass the prepared derived topology into each catalog or keep the exact-root cache on the world runtime.

The search must stop only at explicit cuts, detect inward/outward boundary consistency, report every unmarked terminus as incomplete, and stop expansion at the configured path-distance limit. Carry accumulated rail distance separately along each branch; never sum all explored branch lengths or use Euclidean position. Check the remaining partial rail length before adding the next segment, so a single long rail cannot bypass the cap. Handle repeated segments/cycles deterministically and preserve outside-reconnection validation. A bounded search result is not an accepted region until the existing component, overlap and route/binding checks pass.

For cyclic topology, define and test the distance metric explicitly (e.g. farthest point by shortest track distance from the starting cut), because “longest walk” on a loop is unbounded. Do not use first-visited unweighted BFS distance on reconverging branches as a metric. A weighted frontier can stop safely at the limit; segment interiors may require considering distance from both ends.

For deletion-only edits on an unchanged topology, the retained accepted boundary set reconstructs the exact previous envelope. Topology changes which invalidate those cuts/rails are a distinct hard case: use conservative bounded local fallback geometry/diagnostics, not an assumed still-valid old envelope. Do not claim the minimal boundary-deletion implementation solves arbitrary destroyed-topology containment without a regression for it.

### Local enforcement, including frozen routes

- Planning source today is `SimpleSignalsWorldRuntime.kt:248-254`, which simply returns `routeTables.restrictionSource`. Compose valid table replacements with AB and BA forbidden spans covering each unavailable region's retained envelope. Basic's neutral API supports this directly (`Basic/.../route/planning/RoutePlanningRestrictions.kt:75-117`). Unrelated routes retain their existing results. The existing unavailable installer at runtime lines 1444-1479 throws from the world source and is inappropriate for an incomplete individual region.
- Planning restrictions alone do not prevent a caller submitting an already frozen route. `region/ProtectionRegionInspectionSource.kt:24-27,61-64` already rejects route-binding diagnostics, and `signal/ProtectionRouteBindingCompiler.kt:33-49` is the shared exact-route binding seam. Add unavailable-territory intersection diagnostics before dropping unavailable regions from ordinary window matching; partial inside routes must also be caught.
- If the requirement includes all Basic resource admissions, use a contributor guard over the requested authority interval before mutation. The existing plugin installs `FixedBlockRouteResourcePolicy` as its single contributor (`SimpleSignalsPlugin.kt:48-55`); its `contribute` method at `block/FixedBlockRouteResourcePolicy.kt:37-60` already rejects malformed matches before producing claims. Extend it with the world publication's unavailable-span guard, or install a small region guard under the same plugin owner. Do not pretend a planning-only filter rejects direct admission. Route-local rejection may throw through the established contributor failure handling, but must only test affected route intervals.
- Retained unavailable signal devices need their existing unavailable/zero output and model configuration retained. Do not replay stale permissive outputs or delete handles just because their routes are temporarily absent from the usable compiled index.

### Fixed-block validator trap

`FixedBlockCatalog.kt:225-270,322-340` verifies continuity but currently accepts turnout traversals. The no-turnout rule must apply to authored fixed blocks in the shared world-definition preparation used by Wand and JSON import. Do not unconditionally add this prohibition to the generic geometry compiler: `signal/ProtectionSignalCompiler.kt:48-59` intentionally feeds protection routes through `FixedBlockCatalog` to derive occupancy geometry, and those routes must be able to traverse turnouts. A distinct authored-block validation call or explicit compilation purpose keeps this legitimate reuse.

### File ownership / smallest work split

- Domain/codec owner: `persistence/SimpleSignalsWorldDefinitions.kt`, `SimpleSignalsDefinitionDrafts.kt`, region definition/resolver/topology/catalog, and a pure authoring service. Own pending boundaries, drafts, closure, ID reuse, block path/direction inference and shared authored-block validation.
- Runtime owner: `runtime/SimpleSignalsWorldRuntime.kt`, `SimpleSignalsDefinitionEditImpact.kt`, route-table/route-selection and signal compilation, local restrictions and contributor/inspection guards. Own retained authored vs usable compiled split, unavailable diagnostics and atomic edit behavior. One owner should hold the large runtime file to avoid conflicts.
- Wand/platform owner: plugin command/listener, player state, public rail targeting, worker/entity scheduling, permissions and display messages. Submit patches through the manager and retain runtime/topology/definition identity guards. Do not import host implementation packages or own save/publication code.

### Minimum meaningful regression matrix

Pending boundary survives codec round-trip/reload; first incomplete placement succeeds without creating a region; unmarked dead end remains pending; branching distance limit uses per-branch distance; one overlong rail stops early; repaired region reuses its ID; deletion of one and all boundaries retains routes/devices and local blocking after restart; untouched region and unrelated route remain usable; table with some invalid routes restores valid subset only; pending edit affected rails receive normal Basic edit protection; failed permit/save leaves old disk/publication; no-turnout authored block rejection does not reject protection-route occupancy geometry.

## Files Found

All paths below are under `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/` unless stated otherwise.

- `persistence/SimpleSignalsWorldDefinitions.kt` — immutable full authored document and strict schema-1 codec.
- `persistence/SimpleSignalsDefinitionDrafts.kt` — JSON document diff feeding the existing patch transaction.
- `region/ProtectionRegionDefinition.kt` — stable region/boundary IDs and directed cut model.
- `region/ProtectionRegionTopology.kt` — immutable full topology adjacency derivation.
- `region/ProtectionRegionGraphResolver.kt` — component derivation and region overlap checks.
- `region/ProtectionRegionCatalog.kt` — all-valid region publication and rail reverse index.
- `region/ProtectionRouteTableCatalog.kt` — exact-region route validation and Basic path replacements.
- `block/FixedBlockCatalog.kt` — normalized fixed-block and reused occupancy geometry compilation.
- `block/FixedBlockRouteResourcePolicy.kt` — host resource provider/contributor admission seam.
- `signal/ProtectionSignalCompiler.kt` — route-bound signal/indicator validation and occupancy geometry reuse.
- `signal/ProtectionRouteBindingCompiler.kt` — frozen-route region/table matching shared by inspection.
- `region/ProtectionRegionInspectionSource.kt` — frozen-route binding rejection and authority floors.
- `runtime/SimpleSignalsWorldRuntime.kt` — candidate compile, publication, world manager and single edit transaction.
- `runtime/SimpleSignalsDefinitionEditImpact.kt` — local old/new structural impact and host edit permit inputs.
- `Basic/src/main/java/work/art1st/javarailways/basic/api/route/planning/RoutePlanningRestrictions.kt` — existing neutral forbidden intervals and replacement composition.

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md` — plugin ownership, persistence and publication contracts.
- `.trellis/spec/simple-signals/backend/authoring-commands.md` — shared JSON draft/patch transaction, scheduler and stale-runtime rules.
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md` — affected territory and dependency protection; no new locking owner.
- `ai_tasks/persistence_schema_evolution_rules.md` — additive fields with defaults retain schema version; no new legacy runtime reader.
- Task `prd.md:124-169,232-239,313-327` — approved automatic completion and retained-unavailable authoring behavior.

## External References

None required. Recommendations derive from current workspace source and task contracts; no network or library-version assumptions.

## Caveats / Not Found

- Static inspection only; no compilation, tests, live Folia or client validation performed.
- Current design/implementation task notes still describe turnout-only approval; the dispatch explicitly identifies the newly approved block/region scope.
- Current catalogs assume every authored entity is usable. A UI-only patch or simply filtering invalid regions cannot satisfy retained-device, local enforcement and restart contracts.
- Memory was used only to orient prior owner boundaries; current implementation findings above were verified from source.
