# One-time fixed-block direction field removal

Supports only the audited, unpublished SimpleSignals schema-1 development sidecars that differ
from the current model by `fixedBlocks[*].bidirectional`. It deletes that member only when its
value is a JSON Boolean. Schema stays 1. No runtime reader, compatibility policy or plugin build
dependency is added. Retire the tool when the retained development saves have been converted
and recovery has been verified; it is never an automatic startup migration.

Use an extracted copy of a **normally stopped server's complete external recovery archive**.
The archive must include the old matching plugin JARs and data; retain and checksum it outside
all rotation/output directories. This tool creates independent candidates, not recovery archives,
and never contacts a server or installs files. Do not use an online copy as an atomic recovery point.

The source is the extracted `plugins/SimpleSignals/worlds` directory. It must contain only regular,
canonically named `<world UUID>-definitions.json` and `.json.bak` files. Both primary and backup
are planned and validated; one invalid file rejects the entire plan. Missing backup is allowed;
it is not synthesized. Unknown source files, symlinks, unknown root/block layouts, invalid versions,
duplicate JSON keys, filename/world mismatches, and non-Boolean old flags are rejected.

Conversion removes the old member and an adjacent comma directly from the original UTF-8 text.
Every other byte is retained, including number spelling, array order, ID, revision, signal entrance,
placement and schema. Already-current documents retain their exact bytes. The tool does not map
other old fields, rebuild blocks, swap entrances or infer current defaults into the saved document.

The CLI **always** calls `ValidateDefinitions.java` against the supplied current JARs before it
can emit a successful report or output. That helper uses reflection to invoke the actual Kotlin
`SimpleSignalsWorldDefinitionsCodec.decode(contents, expectedWorldId)`, including strict unknown-key
rejection and current model invariants. It does not bootstrap Bukkit/Paper, start a plugin, create
a world or write data. Decode success does not establish topology/catalog compilation or game
behavior; verify those at the normal server startup. The older `CoordinateValidate.java` also
loads Paper registries and unrelated coordinate/model data, so it is not used here.

Requires Python 3.10+ and Java 25; no Python third-party packages. Supply explicit current JAR paths,
without wildcards. Both JavaRailways and SimpleSignals must come from the same reviewed build.
The JavaRailways `-all.jar` contains the shared Kotlin/serialization/domain dependencies used by
this local validation. The deployable `-reobf.jar` can be used if it has the same current classes
and dependencies; never substitute a stale artifact. The JAR and helper hashes are checked before
and after validation and recorded; a concurrent change rejects the run.

```sh
python3 scripts/fixed-block-direction-migration/migrate.py \
  --source /absolute/extracted/plugins/SimpleSignals/worlds \
  --classpath '/absolute/SimpleSignals-1.0-SNAPSHOT.jar:/absolute/JavaRailways-1.0-SNAPSHOT-all.jar' \
  --java /absolute/java25/bin/java \
  > /absolute/audit/dry-run.json
```

This dry-run writes no source or candidate files. It uses a temporary local directory solely for
the current codec input. Review its per-file source/candidate SHA256 values and exact removal list.
Keep audit reports outside the source; redirection creates the report, not the converter.

```sh
python3 scripts/fixed-block-direction-migration/migrate.py \
  --source /absolute/extracted/plugins/SimpleSignals/worlds \
  --classpath '/absolute/SimpleSignals-1.0-SNAPSHOT.jar:/absolute/JavaRailways-1.0-SNAPSHOT-all.jar' \
  --java /absolute/java25/bin/java \
  --expected-report /absolute/audit/dry-run.json \
  --output /absolute/new-candidate-worlds \
  > /absolute/audit/apply.json
```

The output must not exist, be inside the source, or contain the source. The reviewed dry-run must
match the current source inventory and planned candidate hashes; all source hashes are checked
again after codec validation and before/after writing. The tool reserves a new output directory,
fsyncs each staging file and atomically renames it into that directory. `migration-report.json`
with `complete: true` is written last. If an IO/concurrent-change failure leaves a partial output,
it has no successful final report and must not be installed; the original source remains intact.
Existing candidates/recovery points are never overwritten.

The candidate contains only sidecars plus its report. It is not a complete plugin data directory.
Install only the manifest's named sidecars into the matching stopped server after checking the
live old hashes against the reviewed source and retaining the full external recovery archive.
Do not install the audit report as a world file. Verify candidate/installed hashes, preserve file
ownership and permissions, then use the normal server startup and health/definition checks.
Rollback restores the original matching JARs and old sidecars from the external recovery archive
with the server stopped; there is no down-writer or in-process recovery conversion.

Direct read-only validation of a candidate directory is also available:

```sh
/absolute/java25/bin/java \
  -cp '/absolute/SimpleSignals-1.0-SNAPSHOT.jar:/absolute/JavaRailways-1.0-SNAPSHOT-all.jar' \
  scripts/fixed-block-direction-migration/ValidateDefinitions.java /absolute/new-candidate-worlds
```

The helper accepts only canonical sidecar filenames and the optional migration report. It prints
`CURRENT_DEFINITIONS_OK` only after at least one sidecar exists and every sidecar passed the actual current decoder; failures exit
nonzero with the underlying cause.

Tests:

```sh
python3 -m unittest discover -s scripts/fixed-block-direction-migration/tests -v
```

The fixed old fixture contains a BA traversal, two independent entrances and a non-null ground
placement. Python tests verify lossless deletion, strict migration shape/type handling, both files,
no-op current data, hash guards, independent output and failure preservation. They do not replace
the mandatory real-codec check. The task's `results/migration-*.json` records those real-JAR checks
and, when available, the stopped-server candidate audit.
