# Research: Deployment operating metadata migration

- Query: Determine the removed `RailInfrastructureMetadata.operating` semantics and an information-preserving one-time migration / current-code validation path for the stopped JavaRailwaysTest deployment.
- Scope: internal; source, retained earlier source trees, and local deployment audit artifacts only.
- Date: 2026-10-20

## Findings

### Exact semantics

`operating` is NOT an alias for today's public operating directions. In retained `.worktree/simulation-signal-features/Basic/src/main/java/work/art1st/javarailways/basic/rail/RailOperatingMetadata.kt:5-21`, the latest old group contains ONLY `turnoutAtA` and `turnoutAtB`, nullable `TurnoutEndpointMetadata` values. Each endpoint value contains `protectionDistanceMeters` and `lockingDistanceMeters`, both finite and nonnegative, with locking <= protection. `RailInfrastructureMetadataStore.kt:12-17` in that retained tree embeds this group alongside authoring, speedLimits and curveSafety.

Current `Basic/src/main/java/work/art1st/javarailways/basic/rail/RailInfrastructureMetadataStore.kt:12-17` has only authoring/speedLimits/curveSafety. `.trellis/spec/javarailways/basic/rail-infrastructure.md:23-27` explicitly records deletion of the unused operating distance group, with unchanged schema version and no compatibility reader. Prior code review `.trellis/tasks/10-09-stage2-code-review/research/basic-review-geometry-routes.md:61-66` found that endpoint distance payload was copied/converted but had no admission, stopping or locking runtime consumer. This is removal of abandoned configuration, not replacement by direction state.

Public direction data has a separate current boundary: `BasicInfrastructureSnapshot.kt:49-68` defines `SavedOperatingDirectionV1(railId, permittedDirection)` and Basic `operatingDirections`. `.trellis/spec/javarailways/basic/operating-directions.md:19-39` records Basic as the only mutable owner. Local deployment shape artifact `/tmp/jr-simulation-data-shapes-20261020.json` already reports 26 direction records independently of 106 metadata rows in the main world. Do not synthesize, replace or merge direction entries from the removed distance group.

The older ownership migration DTO still has `allowedDirection` (migration-only `RailInfrastructureMetadataMigration.kt:236-239`); that is an earlier input shape and must NOT be assumed to describe this deployed grouped payload. Exact keys must be checked on actual stopped input.

### Safe bounded transformation

For every current AND `.bak` infrastructure/runtime pair from a verified stopped-server recovery archive:

1. Verify the exact retained input shape / versions and outer pair revision/epoch contracts; reject unexpected keys instead of blanket deletion.
2. At the exact path `basicSnapshotJson.railMetadata[*].metadata.operating`, accept only the known object keys turnoutAtA/turnoutAtB, with values null (or absent only where the old defaults unambiguously apply). Delete only this empty/default group.
3. Preserve all other parsed values, in particular all 26 public directions, all metadata rows, authoring/geometry/lineage, each typed speed-limit entry, derail threshold, destinations/lines, Core connections/curves, runtime documents, counters, revisions and epochs. Do not filter an entire document to a whitelist and silently lose unknown values.
4. Reject any non-null endpoint object before generating output. Current runtime has no equivalent carrier for these distances; deleting non-null values is NOT information-preserving, even though no runtime consumer existed. Such a case requires an explicit decision to retire the obsolete settings and a durable audit copy, not invented mapping to operatingDirections or turnout world defaults.
5. Write a new independent candidate only after whole-batch validation. Keep full plugin directory/JAR archive and source byte hashes. Overlay only validated changed world pairs into a full copy; do not replace the whole worlds directory with the converter's subset output.

### Reusable exact current validation

Existing offline source set provides the needed validators; no old reader or new compatibility framework is necessary.

`scripts/offline-world-migration/DevelopmentWorldMigration.kt:118-132`, `convertEndpoints`, accepts already ownership-migrated outer format 2 documents. Already explicit current Core connections pass through unchanged. It performs current outer decoding, checks pair revision, calls ALL six current nested Core/Basic/SimpleATO decoders, then `materialize` at lines 174-205 runs real `CoreWorld.load`, Basic infrastructure/runtime restore, and SimpleATO infrastructure/runtime restore in a fresh isolated world without physics movement or resource grants.

`run` at lines 211-243 processes both current and `.bak` pairs, rejects missing pairs / orphan runtime, preserves runtime bytes in endpoint mode, and rechecks source bytes. Reuse on the independent narrowly transformed candidate:

```sh
./gradlew :BukkitPlugin:migrateDevelopmentWorlds --offline \
  -PmigrationSource=/absolute/new-candidate/JavaRailways/worlds \
  -PmigrationEndpointsOnly
```

Do not use the default ownership mode: it expects outer1/Basic1 and has a different, restricted legacy-operation contract. No Gradle was launched by this researcher; the main session owns the build token.

For candidate configuration/package validation, existing `-PmigrationValidateDataDirectory=/absolute/new-candidate/JavaRailways` invokes current config and train/rail package loaders. It does NOT cover SimpleSignals or rendering sidecars. SimpleSignals has its own strict `SimpleSignalsWorldDefinitionsCodec.decode(contents, expectedWorldId)` at `SimpleSignals/.../persistence/SimpleSignalsWorldDefinitions.kt:246-282` (schema1 and world identity validation). Its loader/runtime startup must separately be checked before claiming full deployed readiness.

### Other shape changes to audit, not blindly migrate

A read-only filesystem comparison against the retained simulation-signal-features source tree found another removed serialized field: `Core/.../physics/rail/RailLocationWithFacing.kt` old `SavedRailLocationWithFacingV1` had `minorDeltaS` (old lines181-208); current lines179-204 omit it. The deployed primary runtime reports zero trains, but ALL current/backups must be scanned. Never recursively delete any field named minorDeltaS; only exact known location boundaries, and only if its semantic value is known safe. A nonzero old minorDeltaS contributes to arcLength/facing and cannot be discarded as default.

`Basic/.../rail/RailSpeedMetadata.kt:64-78` changed implementation to a custom serializer but explicitly preserves its wire payload `entries`; do not recalculate or flatten speed limits.

Physics `turnoutSafety` DTO / YAML reads were removed, but current `PhysicsConfig.fromYaml` reads known required keys rather than rejecting all unknown YAML keys. Existing turnout-safety YAML is not the JSON unknown-field failure and need not be changed during this narrow migration.

No additional persisted Core/Basic/SimpleATO or SimpleSignals field removal was found in this *bounded earlier-source-tree comparison*. This is not a proof for arbitrary historical backups; whole-batch current strict decode and actual restore are the acceptance gate. A strict failure must be investigated and explicitly mapped, not converted into ignoreUnknownKeys.

### Independent stopped-copy audit (completed)

Read only local stopped copy `/tmp/jr-stopped-worlds-20261020-xkjc2rkv/plugins/JavaRailways/worlds`; no files in the copy were modified. Independently decoded all 24 current/backup split files (12 infrastructure/runtime pairs, six world UUIDs), including all six nested documents per pair. All pair revisions and all nested revisions match; runtime nested physics epochs match their outer epoch. The full version tuple (outerInfra, CoreInfra, BasicInfra, SimpleATOInfra, outerRuntime, CoreRuntime, BasicRuntime, SimpleATORuntime) is exactly `(2, 2, 2, 1, 2, 1, 3, 1)` for every pair.

Exactly **212** old operating groups exist: 106 in the main world current infrastructure and 106 in its backup. Every group has exactly the keys `turnoutAtA` and `turnoutAtB`, both null. There are zero unexpected operating shapes and zero configured endpoint distance values. Therefore removal of precisely these 212 default empty groups can preserve all configured business information; no direction mapping or nonempty obsolete configuration decision is necessary for this actual batch.

Main current: infrastructure revision **3716945**, physics epoch **3717015**; main backup: revision **3716400**, epoch **3716463**. Both contain 106 rails, 106 metadata rows, 26 independent operating-direction records, 30 destinations, one line, and zero trains. All 12 runtime files have zero trains. Recursively scanned outer documents and every decoded nested document: **zero `minorDeltaS` fields**. Thus this second removed location field requires no transformation for this actual batch.

The SHA-256 map of all 24 original files was checked again after the audit and is unchanged. Canonical compact sorted-map fingerprint: `a6416cdef25e8a1deb6600457411d8832d2185c58e49a1fc067b93390169fdaa`. Main infrastructure SHA-256: current `2b2c9199d62c860d2fab5977193eb9829e0c301fc17b7cf73c141147fe131741`, backup `b50792ba9678a17c2d2e247cd633b5b341cf77870ee6da99bf71d9c1e86271d1`.

Git provenance supplied by the main session (not independently executed by this Git-restricted researcher): `git show 87fe3e45^:Basic/src/main/java/work/art1st/javarailways/basic/rail/RailOperatingMetadata.kt` confirms the two nullable endpoint-only fields. Main session identified `87fe3e4567c4b9fddbcee3c0b5975e460a7e05c7` as the deletion commit. This supports the directly inspected retained earlier source.

This Python shape/value audit is not a substitute for Kotlin current strict serializers and real restore; the main session is running the existing offline validator on its separate narrowly transformed candidate. Sidecar validation remains separate.

## Files Found

- `Basic/.../rail/RailInfrastructureMetadataStore.kt`: current three-group metadata model.
- `.worktree/simulation-signal-features/Basic/.../rail/RailOperatingMetadata.kt`: retained exact former distance payload.
- `Basic/.../persistence/BasicInfrastructureSnapshot.kt`: metadata and independent public-direction persistence and restore validation.
- `Core/.../physics/rail/RailLocationWithFacing.kt`: current saved location; earlier retained tree includes minorDeltaS.
- `Basic/.../rail/RailSpeedMetadata.kt`: immutable current speed values with unchanged serialized entries shape.
- `BukkitPlugin/.../persistence/SplitWorldSnapshot.kt`: strict outer and nested current decoders with pair checks.
- `scripts/offline-world-migration/DevelopmentWorldMigration.kt`: all-pairs dry-run/current decoders/real restore.
- `scripts/offline-world-migration/README.md`: archive, candidate-only output and mode contracts.
- `SimpleSignals/.../persistence/SimpleSignalsWorldDefinitions.kt`: separately strict sidecar decoder.

## Related Specs

- `.trellis/spec/javarailways/shared/versioning.md`
- `ai_tasks/persistence_schema_evolution_rules.md`
- `.trellis/spec/javarailways/basic/rail-infrastructure.md`
- `.trellis/spec/javarailways/basic/operating-directions.md`
- `.trellis/spec/javarailways/basic/persistence.md`
- `.trellis/spec/bukkit-plugin/backend/offline-development-migration.md`

## External References

None required; this is repository/source-specific research. No network/remote operation performed.

## Caveats / Not Found

- Exact deletion commit evidence was supplied by the main session as recorded above; this researcher did not independently execute Git operations.
- The stopped copy has now been independently audited as recorded above; every removed group is empty. Arbitrary historical files outside this copy were not audited. No Kotlin strict validation was run by this researcher.
- No product files, tests, or migration tools changed; only this research file is written. No Gradle, server commands, remote operations, or subagents run.
