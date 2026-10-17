# Superseded planning before 2026-10-17

Historical proposal only. The narrowed plan in ../prd.md supersedes it; automatic startup migration is not approved for the current batch.

## prd.md

# Migrate world snapshots and persist explicit rail endpoints

## Goal

Provide one packaged, auditable world-snapshot migration run that upgrades every currently supported
development-world boundary needed by the signal-system refactor: historical Rail metadata ownership,
lossless endpoint-to-endpoint rail connections, and the Dispatcher checkpoint move from Basic runtime
to SimpleATO runtime. Loading and snapshot restore must validate declared connections rather than infer
the target endpoint from geometry. This task remains planning-only until the user separately approves
implementation.

## Background and confirmed facts

- Runtime topology already distinguishes exact ports through `RailEndpointRef`; the information loss
  is confined to the persisted rail DTO and its restore paths.
- `CoreRail.SavedRailV1` currently stores the source side through `nextRailAtA/B`, but each list item
  contains only the target rail UUID. `activeTrackAtA/B` has the same limitation
  (`Core/src/main/java/work/art1st/javarailways/core/physics/rail/CoreRail.kt:95-112`).
- Normal load reconstructs the target side by comparing both endpoint positions. Missing or malformed
  UUIDs are currently dropped by `mapNotNull`, and the selected target side is not checked against a
  colocation tolerance (`CoreRail.kt:133-167`).
- Snapshot-restore preparation independently repeats nearest-endpoint reconstruction, with stricter
  existence, tolerance and symmetry checks
  (`Core/src/main/java/work/art1st/javarailways/core/physics/rail/PreparedRailWorldSnapshotPlan.kt:161-205`).
- Target-endpoint inference is `O(E)` and is not a runtime hot-path problem. The migration is justified
  by complete semantics, one validation path and deterministic failure behavior rather than a material
  asymptotic speedup.
- The independent persistence boundary is the Core infrastructure document, currently format version
  2. `SavedRailV1` is a nested DTO rather than an independently versioned document.
- The repository already has a manual `RailInfrastructureMetadataMigration` for Core infrastructure
  v1 -> v2, but normal world loading does not automatically run it.
- The current outer `RuntimeFileV2` contains the SimpleATO payload, `BasicRuntimeSnapshotV2` no longer
  embeds Dispatcher state, and `SimpleATORuntimeSnapshotV1` owns `SavedDispatcherV1`. The migration
  input still contains the old outer runtime/Basic runtime shape, so the one-time transformer must
  upgrade both runtime boundaries independently from the Core infrastructure change.

## Requirements

- Persist every connection target as a stable rail UUID plus explicit `A` or `B` endpoint. The source
  endpoint remains explicit in the owning `connectionsAtA/B` field.
- Persist active selection as the same complete endpoint reference, not as a rail UUID alone.
- Keep connection data rail-local. Do not introduce a separate world-global connection catalog merely
  to deduplicate the two symmetric records.
- Treat the explicit endpoint identity as authoritative. Geometry verifies that the declared endpoints
  are colocated within the existing Core connection tolerance; geometry must not select or repair an
  endpoint.
- Prevalidate the complete infrastructure document before materialization:
  - rail IDs and endpoint tokens are valid and target rails exist;
  - a source endpoint has no duplicate target endpoint and obeys the supported degree limit;
  - every connection is exactly symmetric;
  - each active selection belongs to that source endpoint's declared connections;
  - each declared endpoint pair satisfies the physical colocation invariant.
- Create all rails disconnected, then restore validated endpoint references directly. Invalid input
  must fail without a partially materialized live world becoming observable.
- Reuse one Core-owned endpoint-state decoder/validator for normal infrastructure load and RailWand
  snapshot restore; remove nearest-endpoint recovery from both paths.
- Update the smallest independent schema boundary according to
  `ai_tasks/persistence_schema_evolution_rules.md`. Do not add a permanent runtime compatibility path.
- Ship one one-way migration orchestrator in the plugin artifact. It composes independent pure steps
  for Core infrastructure v1 -> v2 metadata ownership, Core infrastructure v2 -> current explicit
  endpoints, outer runtime upgrade, Basic runtime removal of Dispatcher state, and new SimpleATO
  runtime creation. Each boundary keeps its own schema version; the orchestrator does not introduce
  one global world-format version.
- The Bukkit world-load boundary detects any supported old member of the infrastructure/runtime pair
  and invokes the orchestrator before current decoding or world materialization.
- Preserve immutable, version-labelled recovery copies of both paired files before replacing either,
  validate the complete migrated pair in memory, and make interrupted apply recoverable without
  accepting a mixed old/new pair.
- Preserve one `infrastructureRevision` across the complete pair and preserve the runtime
  `physicsEpoch`. The outer infrastructure file remains at its current version unless its own shape or
  semantics changes; the outer runtime boundary advances because it gains the SimpleATO payload.
- Expose the same migration as an auditable dry-run/apply administrative operation. Automatic startup
  migration uses the apply path and logs the source version, target version, world/file and recovery
  point.
- If old endpoint UUID data cannot identify exactly one physically valid target endpoint, or any later
  validation fails, leave the source untouched and fail the world load closed. Do not silently choose,
  drop or repair a connection.
- Keep runtime `TopologyState` construction, topology nodes, diamond resources and RailWand edit permits
  outside this migration except for adapting them to the lossless decoded endpoint state.

## Acceptance criteria

- [ ] The current writer serializes the target rail UUID and exact target endpoint for every connection
  and active selection.
- [ ] Round-trip tests cover A-A, A-B, B-A and B-B connection combinations without geometry-based side
  selection.
- [ ] Fixed corrupt fixtures reject missing rails, invalid UUID/endpoint tokens, duplicate references,
  asymmetric references, active selections outside the connection list and non-colocated declared
  endpoints before world publication.
- [ ] Normal world load and RailWand snapshot restore consume the same validated endpoint-state model.
- [ ] No production restore path calls nearest-endpoint inference or silently drops an invalid
  connection reference.
- [ ] Existing turnout active-branch persistence and connection-only snapshot undo retain their exact
  behavior using explicit endpoint references.
- [ ] The Core infrastructure document version and fixed fixtures follow the approved migration policy;
  the writer emits only the current format.
- [ ] Loading a supported old Core infrastructure payload automatically runs the packaged migration,
  creates a recovery point, atomically installs the current payload and then loads only that current
  payload.
- [ ] Dry-run reports the same planned endpoint conversions and failures without writing any file.
- [ ] A migration failure leaves the original and runtime files byte-for-byte unchanged, publishes no
  partial world and reports an actionable error containing the affected rail/endpoint.
- [ ] Migration preserves `infrastructureRevision`; a matching runtime snapshot remains loadable after
  the infrastructure-only upgrade.
- [ ] A world whose runtime needs the Dispatcher ownership upgrade is migrated as one validated pair:
  timetable and rolling-stock state remain in Basic runtime, while the exact Dispatcher checkpoint is
  emitted into SimpleATO runtime with the same revision/epoch.
- [ ] Dry-run/apply and automatic startup report every independently upgraded boundary; a failure or
  interruption never makes the loader accept a mixed old/new pair.
- [ ] Core, Basic and BukkitPlugin tests pass, and persistence documentation describes the new shape,
  validation order and failure atomicity.

## Out of scope

- Changing the in-memory `RailView`, `RailEndpointRef` or normalized `TopologyState` model.
- Changing connection cardinality, turnout semantics or automatic connection policy.
- Moving rail connections into a new global graph persistence document.
- Runtime/business support for multiple historical connection representations; legacy DTOs and
  geometry-based inference exist only inside the one-way migration component.
- Storage-size optimization and the future coarse train-physics/curvature optimization.
- Hiding the independent schema versions behind one global world-format version.

## Key compatibility decision

- Existing supported development-world files are upgraded by one packaged, auditable one-way migration
  orchestrator. Detection and apply happen automatically at the Bukkit load boundary and are also
  available through one dry-run/apply administrative command.
- The migration boundary may decode the old schema and infer an endpoint only when exactly one candidate
  satisfies the old geometry contract. All current business and persistence code sees only the new
  explicit-endpoint model.
- Dispatcher runtime data is preserved and moved; it is not discarded. Normal Core, Basic, SimpleATO
  and Bukkit business code reads and writes only current schemas after migration.

## Support window

The packaged development-world migrator supports the already present Core infrastructure v1 and v2
inputs plus the current pre-split runtime input. It remains isolated at the migration boundary until the
user confirms all retained development worlds have been upgraded; removing those legacy steps is a
separate reviewed cleanup. No legacy DTO or branch enters normal world/runtime business code.


## design.md

# Explicit rail endpoint persistence design

## Ownership and boundaries

```text
Bukkit WorldPersistence
  read paired infrastructure/runtime files and inspect each nested boundary version
  -> packaged pair-migration boundary when any supported member is old
  -> current SplitWorldSnapshotCodec / WorldSnapshotCodec
  -> CoreWorld.load(current model only)

Core migration component
  legacy DTO decode -> pure endpoint conversion -> current DTO validation/encode

SimpleATO migration component
  old Basic runtime Dispatcher checkpoint -> current Basic + SimpleATO runtime documents

Core current persistence
  explicit endpoint references only; no legacy branches or nearest-endpoint inference
```

Core owns endpoint semantic conversion because it owns rail geometry and connection invariants.
SimpleATO owns the Dispatcher checkpoint target model. Bukkit owns pair discovery, orchestration,
recovery points, interruption recovery, replacement and startup logging. Pure steps may rewrite the
Basic payload only to remove data whose sole owner has moved; normal Basic code never reads a legacy
SimpleATO shape.

## Current problem

The current rail-local fields identify the source endpoint by choosing `nextRailAtA` or
`nextRailAtB`, but each target is only a rail UUID. Both normal load and snapshot restore therefore
recover the target endpoint from endpoint positions. This duplicates decoding rules and makes geometry
an identity source rather than a validation input.

The inference remains linear in the number of connections, so this is not a hot-path optimization.
The design change makes the persisted graph complete and corruption handling deterministic.

## Current persisted model

The current Core infrastructure model stores a serializable endpoint reference:

```kotlin
data class SavedRailEndpointRef(
    val railId: String,
    val endpoint: RailEndpoint
)

data class SavedRail(
    val id: String,
    val curveAB: SavedPrimeCurve,
    val connectionsAtA: List<SavedRailEndpointRef>,
    val connectionsAtB: List<SavedRailEndpointRef>,
    val activeConnectionAtA: SavedRailEndpointRef?,
    val activeConnectionAtB: SavedRailEndpointRef?
)
```

The exact Kotlin class names may follow the repository's current DTO naming convention, but the JSON
shape and semantics above are required. The source endpoint remains encoded by the owning A/B field;
the target value always contains both identity parts. Active selection uses the same value type.

Connection data remains symmetric and rail-local. A global connection document would remove duplicate
records but would also add a second world-level ownership/index boundary without improving load
complexity or runtime behavior.

## Current-model validation and materialization

The current decoder builds one immutable map from every `RailEndpointRef` to its declared connected
endpoints and optional active endpoint. Before creating a live world it validates:

1. unique and valid rail IDs;
2. valid endpoint tokens and existing target rail IDs;
3. no duplicate targets, no self-reference to the same endpoint and the supported degree limit;
4. exact symmetric declarations;
5. active selection membership;
6. geometric colocation of the two explicitly declared endpoints within Core tolerance.

Geometry never selects another endpoint. A declared A-to-A connection whose A endpoints are not
colocated is invalid even if one rail's B endpoint is nearby.

After validation, Core creates all rails disconnected and applies the immutable endpoint-state map in
one topology transaction. RailWand snapshot restore calls the same decoder/validator and uses the same
map for currentness checks and application. The old `getDirection`/nearest-endpoint helpers disappear
from all current paths.

Complexity is `O(N + E)` plus curve endpoint evaluation, where `N` is rail count and `E` is the number
of persisted directed connection records. Runtime topology normalization remains unchanged.

## One-way automatic pair migration

The Core migration component owns explicit legacy DTOs for each supported old Core infrastructure
version. These types are used only at the migration boundary. For each legacy directed record
`source endpoint -> target rail UUID`, it:

1. decodes every saved curve without creating a live world;
2. measures the source endpoint against both target endpoints;
3. accepts only when exactly one target endpoint satisfies the existing connection tolerance;
4. emits the corresponding explicit endpoint reference;
5. validates symmetry, degree and active selection after converting the whole document;
6. encodes and decodes the current DTO again before returning success.

If neither or both target endpoints qualify, migration fails with the source and target rail IDs and
source endpoint. It does not reproduce the old tie-breaking fallback. The output preserves
`infrastructureRevision` and upgrades only the Core infrastructure format version.

After infrastructure conversion, the runtime step removes the Dispatcher checkpoint from the new
Basic runtime model and emits the same checkpoint into a new SimpleATO runtime document. Timetable and
rolling-stock state remain in Basic. All three runtime payloads carry the same infrastructure revision
and physics epoch.

The Bukkit pair wrapper supports dry-run and apply through one implementation. Apply order is:

```text
read both primary/backup candidate sets
-> choose one revision-paired source without materializing a world
-> detect every supported old nested boundary
-> run the ordered pure migration steps
-> encode and fully decode/validate the complete current pair in memory
-> create non-overwriting version-labelled recovery copies for both source files
-> stage both current files plus a small apply marker
-> replace the pair and clear the marker
-> decode the installed pair through current codecs
```

Automatic world load calls apply when any selected boundary is old. A completely current pair is a
no-op. If the process stops between the two file replacements, the marker plus immutable recovery
copies make the next startup finish or restore the pair before normal decoding; it never treats a mixed
pair as current. Unsupported future versions, ambiguous old geometry, invalid references, an existing
conflicting recovery point or any I/O failure disable railway features through the existing
corrupted-load result rather than guessing.

Backup selection must not destroy the only valid old candidate. If recovery chooses an old `.bak`
candidate because the primary is invalid, migration copies that selected candidate to its dedicated
recovery point and installs the migrated output as the primary; it does not overwrite the selected
backup with the invalid primary.

## Versioning and compatibility

- The Core infrastructure document receives the next format version because connection fields change
  shape and meaning.
- The outer `InfrastructureFileV1` stays at version 1 because it still contains the same revision and
  opaque Core/Basic payload strings.
- The Basic runtime and outer runtime boundaries each advance because the Dispatcher field moves and a
  SimpleATO payload is added. SimpleATO runtime begins at its own version 1.
- Revision and epoch values are preserved; they are not schema versions.
- The writer emits only the new current format.
- Legacy decoders are private to the migration component. Supported development inputs and removal
  condition are fixed in `prd.md`; removal requires a separately reviewed cleanup.
- No Core world, topology, Basic or Bukkit business class branches on legacy format.

## Failure and audit behavior

- Dry-run performs parsing, conversion and complete current validation, but creates no files.
- Apply never overwrites a recovery point with different content.
- A failure before replacement leaves both primaries byte-for-byte unchanged; an interruption during
  replacement is resolved from the marker and recovery pair before normal load.
- A successfully replaced pair is immediately decoded through current codecs before world creation.
- Logs and command reports include world/files, each from/to boundary version, converted connection and
  checkpoint counts, both recovery paths and the first actionable validation failure.

## Deferred execution

The pure Core endpoint work can be reviewed before the Dispatcher move, but automatic pair
migration cannot be activated until the signal-system task has established the current SimpleATO world
runtime and checkpoint model. Implementation may use several reviewable commits; the startup migrator
is enabled only after every target schema and validator exists.


## implement.md

# Implementation plan

## Phase 1: Freeze source fixtures and orchestration contract

- [ ] Add fixed Core infrastructure-v1/v2 and pre-split runtime fixtures covering Rail metadata,
  ordinary connection, turnout active selection, all four endpoint combinations and live Dispatcher
  checkpoint state.
- [ ] Define one pair-migration report and ordered pure-step registry while retaining independent Core
  infrastructure, outer runtime, Basic runtime and SimpleATO runtime version numbers.
- [ ] Specify recovery-copy names, apply marker and interrupted-apply recovery before filesystem writes.

## Phase 2: Explicit endpoint current model and strict decoder

- [ ] Introduce the explicit persisted endpoint-reference current model and increment only the Core
  infrastructure document version.
- [ ] Update the current writer to serialize exact endpoint references directly from `RailView`.
- [ ] Extract one Core-owned pure endpoint-state decoder/validator for the current DTO.
- [ ] Make `CoreWorld.load` validate the full infrastructure snapshot before live-world materialization,
  then apply the validated endpoint states after creating disconnected rails.
- [ ] Make `PreparedRailWorldSnapshotPlan` consume the same validated endpoint states.
- [ ] Remove current-path nearest-endpoint selection and silent `mapNotNull` reference loss.
- [ ] Add Good/Base/Bad tests for endpoint combinations, symmetry, active membership, degree, missing
  targets and geometric mismatch, including no published partial world on failure.

## Phase 3: Pure infrastructure migration chain

- [ ] Fold the existing Core-v1 Rail metadata conversion into the packaged ordered chain, then add
  migration-private Core infrastructure-v2 DTOs and a pure v2-to-current transformer.
- [ ] Infer a legacy target endpoint only when exactly one endpoint is within tolerance; reject zero or
  two candidates with rail/endpoint diagnostics.
- [ ] Preserve infrastructure revision, connection order and active selection, then validate the result
  by the current decoder.
- [ ] Add fixed migration fixtures for success, ambiguity, missing target, asymmetry, active mismatch and
  unchanged input on failure.
- [ ] Keep legacy inputs private to the documented development-world support window and fixed fixtures.

## Phase 4: SimpleATO runtime target and pure runtime migration

- [x] After the signal-system ownership batch establishes `SimpleATOWorldData`, define the current
  Basic runtime without Dispatcher and SimpleATO runtime with the Dispatcher checkpoint.
- [ ] Add a pure pre-split-runtime transformer that preserves timetable, rolling-stock and exact
  Dispatcher checkpoint data while preserving revision/epoch.
- [ ] Validate current Core/Basic/SimpleATO payload coherence and restore order without materializing a
  partially migrated world.

## Phase 5: Packaged pair tool and automatic startup

- [ ] Replace the metadata-only command wrapper with one world-pair migration wrapper shared by dry-run,
  manual apply and automatic startup apply.
- [ ] Create non-overwriting version-labelled recovery copies, stage the complete pair, and use an apply
  marker so interrupted two-file replacement is completed or restored before normal loading.
- [ ] Integrate migration-aware paired candidate selection into `WorldPersistence` without destroying a
  valid `.bak` source.
- [ ] On successful automatic migration, decode the installed pair through current codecs and log an
  audit report; on failure return the existing corrupted-load result and retain a recoverable pair.
- [ ] Replace the narrow metadata command with one world-snapshot dry-run/apply operation that reports
  every boundary and item count it would migrate.
- [ ] Test current no-op, primary-old, backup-old/primary-corrupt, existing recovery point, staged-write
  failure, interruption after either replacement, revision/epoch pairing and idempotent restart.

## Phase 6: Documentation and full validation

- [ ] Update Core persistence and rail-topology specs with the explicit endpoint shape, shared
  validation order, complexity and fail-closed behavior.
- [ ] Update Bukkit persistence documentation with paired automatic migration, recovery and audit
  behavior.
- [ ] Run `./gradlew :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test`.
- [ ] Run `./gradlew :BukkitPlugin:shadowJar` and verify the migration component is packaged.
- [ ] Run `git diff --check` and inspect fixed fixture diffs.

## Risk and rollback points

- Keep the Core endpoint current-model commits separate from activation of automatic file writes.
- Never delete or overwrite either version-labelled recovery point during rollback.
- If automatic candidate selection cannot prove one coherent infrastructure/runtime source pair, fail
  closed rather than migrating the highest standalone revision.
- Do not enable automatic startup migration until every target schema, pure migration step and pair
  validator exists and the user has reviewed the implementation summary.
