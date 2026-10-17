# One-time development-world migration

This tool is an independent `offlineMigration` source set, not a runtime reader and not part of
the JavaRailways plugin JAR. It has two explicitly selected modes. The default accepts the audited pre-SimpleATO outer format 1 /
Basic infrastructure and runtime format 1 save. `-PmigrationEndpointsOnly` accepts already ownership-migrated
outer format 2 pairs and converts UUID-only Core infrastructure 2 connections to exact target endpoints.
Both strictly decode all current Core/Basic/SimpleATO documents, then run their real business restore methods.
The default ownership conversion composes endpoint conversion before producing current output.

The default ownership mode intentionally rejects non-empty legacy physical trains, timetables, rolling-stock ledgers and
dispatcher clocks/runs. Those records need a separately audited mapping, not guessed defaults.
Dispatcher counters, infrastructure revisions and physics epochs are preserved. It does not run
physics, install resource claims or apply timetables.

## Exact endpoint conversion

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds --offline \
  -PmigrationSource=/absolute/read-only-copy/worlds -PmigrationEndpointsOnly
```

This mode only replaces `nextRailAtA/B` and `activeTrackAtA/B` inside Core infrastructure with
`connectionsAtA/B` and `activeConnectionAtA/B`, each reference containing `railId` and `endpoint`
(`A` or `B`). It does not increment any version. Each UUID-only target must have exactly one end
within Core's 5 mm connection tolerance of the source endpoint. Missing, zero-candidate or ambiguous
targets, malformed active selections, unknown fields, mixed old/current shapes, asymmetric graphs
and other current restore failures reject the entire candidate before output is created. The
current decoder and real restore validate already explicit input too; successful current input is
copied byte for byte. Current and `.bak` each require a matching runtime file and revision.

Runtime files retain their original bytes, including gzip encoding; this mode does not impose the
old ownership converter's empty-operations restriction. Outer, Basic and SimpleATO business values,
rail curves, connection order, valid null selections, revisions and epochs are preserved. Every
inferred connection is reported. Curve repair cannot be combined with endpoint mode.

Use `-PmigrationOutput=/absolute/NEW-output` only after reviewing the dry-run. An online read-only
copy is useful for investigation, but is not an atomic stopped-server recovery point. Before actual
installation, stop writers, take the complete external archive described below, and revalidate a
fresh source. The converter never contacts, stops, replaces files on or deploys to the development
server at `192.168.1.230`. Its legacy-input support window lasts only until the retained UUID-only
development saves have been converted and the user authorizes removing the independent tool.

## Before running

Stop all writers normally. Archive the **whole plugin configuration/data directory** to a durable
directory outside it, verify the archive and file hashes, and extract a separate read-only source.
Do not run this against a live data directory. The tool itself does not create backups.

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds \
  -PmigrationSource=/absolute/extracted/JavaRailways/worlds
```

This is a dry-run. Existing malformed repeated curve points fail unless the caller explicitly adds
`-PrepairRedundantCurvePoints`. This bounded offline repair only removes an interior coincident
DISCRETE point when position/normal are exactly equal, adjacent arc difference is at most `1e-12`,
and each tangent-component difference is at most `1e-7`. It retains the earlier point, all retained
arc coordinates, total length, endpoints and rail identities/connections. Every removed point and
frame difference is reported; distinct geometry or ambiguous segment semantics are rejected.
These tolerances are repair criteria for the audited old development data, not runtime policy.

After reviewing the dry-run, add `-PmigrationOutput=/absolute/NEW-world-output` to write converted
current and `.bak` pairs to a fresh independent directory. Existing destinations and paths under
the source (including through parent symlinks) are refused. Input bytes are rechecked before any
output. Gzip encoding is detected by magic and preserved. The output contains only converted world
pairs and `migration-report.txt`; **it is not a complete replacement plugin/worlds directory**.
Unmodified render sidecars and other configuration belong to the full archived candidate copy.

The separately migrated full candidate configuration can be checked without Bukkit startup:

```bash
./gradlew :BukkitPlugin:migrateDevelopmentWorlds \
  -PmigrationValidateConfigDirectory=/absolute/candidate/JavaRailways
```

This strictly parses YAML then uses current `PhysicsConfig` and `JavaRailwaysConfig`. The additional
`-PmigrationValidateDataDirectory=...` mode also calls current train/rail `PackageLoader` methods and
prints each completed check. It bootstraps the real cached Paper registries for rail-item parsing,
as existing model tests do; it does not start a server, use mocks or download a runtime.
Neither mode modifies files or instantiates the Bukkit plugin. They do not prove client resource-pack
compatibility, train JS behavior, render-sidecar loading, SimpleSignals sidecars, Folia startup or
game behavior. Those require their own validation before deployment.

The separate `scripts/migrate_physics_config.py` pure `migrate` function can prepare physics YAML in
another candidate. Do not use its old `--apply` mode for this workflow: it creates same-directory
individual backups, contrary to the required whole-directory archive policy.

Focused verification:

```bash
./gradlew :BukkitPlugin:test --tests '*DevelopmentWorldMigrationTest' --tests '*EndpointWorldMigrationTest'
```
