# One-time waypoint identity cleanup

This offline development-save tool deletes only `basicSnapshotJson.wayPoints[*].displayName`
and `simpleAtoSnapshotJson.destinations[*].displayName`. The nested snapshots are JSON strings
inside the split infrastructure document. It does not add a runtime reader, alias, fallback,
special rejection rule or schema version. Retire this tool after retained development saves
have been converted and recovery has been verified; it is not a plugin startup migration.

The input is an **extracted copy** of `plugins/JavaRailways/worlds`. Only regular files matching
`*-infrastructure.json` and `*-infrastructure.json.bak` are selected. Primary and backup are
independently converted and validated; a bad selected file fails the whole plan. Other files,
including runtime snapshots, are neither modified nor copied into the candidate directory.
Compression is detected from gzip magic, independent of filename. Missing backups are not created.

The converter removes member/comma spans directly from the original UTF-8 text. A character-boundary
map into each outer JSON string preserves unrelated escaping as well as nested number spelling,
precision, whitespace, field order, IDs, revisions/epochs and format versions. Station/depot names,
line display names, unrelated fields and nested properties of the same name are retained. Already
current files keep their exact original bytes, including original gzip headers. Changed gzip
documents are recompressed with a deterministic timestamp; their decompressed unrelated text is
unchanged. Malformed JSON, duplicate keys and non-regular selected paths fail without touching input.

Python parsing is only the conversion guard. Every successful CLI dry-run/output also invokes
`ValidateInfrastructure.java` with the supplied **current JavaRailways shadow JAR**. The helper calls
the actual `SplitWorldSnapshotCodec.decodeInfrastructure`, then `core()`, `basic()` and `simpleAto()`
to validate all nested current schemas and matching revisions. It does not start Bukkit, restore a
live world, modify a save, or claim topology/game acceptance. JAR/helper SHA256 values are recorded
and checked again after validation; use the artifact built from the reviewed source, never a stale
installed JAR. Java source launch requires Java 25; Python requires 3.10+ and no third-party modules.

## Dry-run

Use a fresh copied directory. Keep reports outside it. This command writes no source or candidate
files; a temporary directory is used solely as current-codec input.

```sh
python3 scripts/waypoint-identity-migration/migrate.py \
  --source /absolute/extracted/plugins/JavaRailways/worlds \
  --classpath /absolute/current/JavaRailways-1.0-SNAPSHOT-all.jar \
  --java /absolute/java25/bin/java \
  > /absolute/audit/waypoint-dry-run.json
```

Review the per-file source/candidate hashes, exact removed paths/IDs and `currentCodec` result.
Only `CURRENT_INFRASTRUCTURE_OK files=N` means the helper validated every candidate file.
The CLI accepts an explicit JAR list separated by the platform path separator if dependencies
are supplied separately; wildcards and class directories are not accepted.

## Create an independent candidate

```sh
python3 scripts/waypoint-identity-migration/migrate.py \
  --source /absolute/extracted/plugins/JavaRailways/worlds \
  --classpath /absolute/current/JavaRailways-1.0-SNAPSHOT-all.jar \
  --java /absolute/java25/bin/java \
  --expected-report /absolute/audit/waypoint-dry-run.json \
  --output /absolute/new-waypoint-candidate \
  > /absolute/audit/waypoint-candidate.json
```

The reviewed report must match source/candidate hashes and validator identity. Source inventory
is checked during planning, after current-codec validation, and before/after output. The output
must be a new independent directory, neither inside nor containing the source; existing output
is never overwritten. Staging files are fsynced and atomically renamed. `migration-report.json`
with `complete: true` is written last. A partial directory without that successful final report
must not be installed; the source remains untouched. The candidate contains only selected
infrastructure files and its report, not a complete world/plugin recovery archive.

Direct current-codec validation of a candidate is also available:

```sh
/absolute/java25/bin/java \
  -cp /absolute/current/JavaRailways-1.0-SNAPSHOT-all.jar \
  scripts/waypoint-identity-migration/ValidateInfrastructure.java /absolute/new-waypoint-candidate
```

## Server cutover and recovery

This tool never contacts a server, stops it, replaces a JAR or applies files in place. An online
copy is useful for inspection, not an atomic recovery point or permission to install candidates.
Before an actual cutover, stop normally and confirm the final save. Retain a complete external,
checksummed recovery archive containing the matching old JARs and data, outside rotation and
candidate directories. Obtain a new stopped-save copy and repeat the dry-run/candidate process.
Check the old live hashes against the reviewed source before replacing only the named primary
and backup infrastructure files together with the matching new JAR. Do not copy the audit report
into the server worlds directory. Preserve file ownership/permissions and verify installed hashes.
Start normally and verify restoration, services and actual in-game behavior. Rollback requires a
normal stop followed by restoring matching old JARs and data from the external recovery archive;
there is no runtime down-conversion. Do not mix a new save with the old plugin.

## Checks

```sh
python3 -m unittest discover -s scripts/waypoint-identity-migration/tests -v
```

The Python suite covers precise deletion, unchanged names/numbers/escape bytes, gzip/no-op identity,
primary/backup handling, unchanged sources, independent exclusive output, dry-run/hash guards and
malformed/duplicate JSON. Its CLI/validator process tests use explicit test doubles; they are not
real-JAR codec evidence. Run the mandatory actual-codec CLI against the current artifact and the
copied real saves, and retain those reports separately.
