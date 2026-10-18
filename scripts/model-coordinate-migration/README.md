# One-time model coordinate migration

Converts **old +X-forward** rail and signal assets and saved placements to **+Z-forward**.
This is an offline tool for an **extracted full backup of a stopped server**. It never connects
to a server or replaces live files. Keep the original archive as the recovery point.
Do not run it on already converted assets or on its own output: this semantic migration is
intentionally not idempotent, and unchanged schema numbers cannot identify old/new coordinates.

Requires Python 3.10+ and PyYAML. NBT and region handling use the Python standard library.

```sh
python3 scripts/model-coordinate-migration/migrate.py --source /absolute/extracted-backup
python3 scripts/model-coordinate-migration/migrate.py --source /absolute/extracted-backup --output /absolute/new-patch
```

The first command is a dry-run. Both commands plan and validate every transformation before
writing anything, then verify the source file inventory and SHA256 checksums are unchanged.
The second creates a **new changed-files-only patch tree**, plus `migration-report.json` with
source fingerprints and before/after fingerprints. It refuses an existing output, an output
inside the source, and symlink inputs. Apply the reviewed patch to the corresponding stopped
server separately; preserve the original full backup and verify the source fingerprints before
applying. The patch is not a complete runnable backup. YAML comments/formatting are rewritten;
unrelated parsed values remain intact. Reports contain paths and hashes, never item payloads.

Scope:

- JavaRailways default `rail_model.yml`, `rails/*/manifest.yml` `rail-config` targets,
  and `worlds/*-rail-render.json` and `*-rail-render.json.bak`. Rail offsets and light probe offsets
  become `(-z,y,x)`; rotation becomes `Ry(-90) * Rx(x) * Ry(y) * Rz(z)` with verified Euler
  recomposition. Scale and item payloads remain unchanged. Render template IDs are deterministic
  SHA256 of canonical transformed template content; references are remapped. Arc segmentation
  and geometry metadata remain unchanged.
- SimpleSignals `signal-packs/*/model/**/*.yml` and model roots listed by
  `models/*/manifest.yml`. A dummy root with Y rotation -90 preserves each complete old model;
  root selection boxes and rail presets change basis. Complete-model `imports` and any signalpack part import are refused;
  imports inside unchanged parts are preserved. Scenes, animation and item payloads are retained.
- SimpleSignals `worlds/*-definitions.json` and `*-definitions.json.bak` schema 1 device placements. Rail adjustments
  change translation basis and conjugate quaternion rotation; ground positions stay fixed,
  with quaternion rotation multiplied by `Ry(+90)`. Revisions and schema versions stay unchanged.
- Every world directory immediately under the source containing `level.dat`: recursively scans
  `.dat`, `.dat_old` and `.mca`, skipping binary UUID `uid.dat`. This includes player inventories,
  containers, nested item components and entity regions. Only NBT strings named
  `simplesignals:signal_wand_state` with a non-null `railPreset` are rewritten; preset translation
  and quaternion change basis. Malformed NBT/region input fails the whole planning stage.

This has no runtime compatibility path, no deployment step and no version increment.
ZIP model packages must be rebuilt separately from reviewed converted assets. Unknown or custom
storage outside these discovery paths requires a separate audit before applying the patch.

Tests:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_model_coordinate_migration*.py'
```

Support window: this tool supports only the audited pre-unification development backup.
Retire it after that backup and its model ZIPs have been migrated, restored and verified.
It must never become an automatic startup migration or an ongoing old-format reader.

`CoordinateJarPatch.java` builds a narrowly scoped companion release from the **installed** JARs
and reviewed coordinate classes. It rejects unrelated method/field changes and proves all other
ZIP entry payloads unchanged. It replaces only the numeric ground-placement method inside the
old Signal Wand companion, preserving the rest of that deployed class. It needs ASM and ASM-tree
9.9.1 at build/run time; these tools never enter either plugin JAR.

`CoordinateValidate.java` invokes the real current package, signal-definition and render readers
through reflection, with the server's own Paper runtime libraries and real registry bootstrap.
Use the exact library list from the server launcher rather than mixing runtime versions.
On Folia 26.1.2, standalone ItemStack deserialization can fail with unbound components even for
unchanged valid data. `--defer-render-reader` explicitly leaves that reader check to the subsequent
real server startup; it does not report the deferred check as successful. The migration still
checks transformed template references and keeps every ItemStack payload unchanged. Do not claim
render recovery complete until the actual server has loaded the candidate without render-store
corruption or missing-template errors.


## Audited development-server run — 2026-10-18

Completed against JavaRailwaysTest instance `836082a48c4f48fcb1fea4e83c57dc96`.
Coordinate implementation: `6548a850`; migration tooling: `760cc43c`, `01384724`.
The server was stopped normally before backup and conversion, then restarted normally.
All server data, backup and conversion work remained on the server.

Recovery directory:
`/opt/mcsmanager/backups/javarailways/model-coordinates-20261018-a6s24mkk`.
The full original world, both plugin data directories and matching old JARs are in
`server-data-and-jars.tar.gz`, SHA256
`9090b0207e08764edf194d274108881d73e2deae7cd7f0ca66f404bb3895e280`.
The directory also retains source inventory, migration report, candidate audit,
installed-file manifest and startup verification. Restore only with the instance stopped:
verify the archive checksum, preserve the current state separately, then restore the original
world, plugin data and both matching JARs together, preserving ownership and permissions.

Results:

- Seven data files and two JARs changed; the other 446 inventoried files were unchanged
  before restart. All nine installed hashes matched the reviewed candidates.
- Preserved 106 decorated rails and one render template; converted three rail configurations,
  one signal model and seven ground placements across primary and backup definitions.
- Scanned 319 NBT/storage files, including 275 region files; no copied Signal Wand presets
  required conversion, and migration changed no world/player files. Rail Wand saved state
  contains no render snapshots. World geometry, definition revision and schema versions stayed unchanged.
- The companion JARs were patched from the installed baseline to exclude unrelated current
  development changes: JavaRailways changed three entries (26,257 unchanged), SimpleSignals
  four (672 unchanged), with no added or removed entries.
- Installed JavaRailways SHA256:
  `f2282294089a16e676ee29e2b2f7f12a91cfd0d3cda62ade4b69c346e2c4c58a`.
- Installed SimpleSignals SHA256:
  `996ba0b8dd9fcbf785cfbcfc69877da7f82482ed2f554f4e4bd788f5b6728543`.

Validation: 1,078 JVM tests and 15 Python migration tests passed. Independent candidate
matrix checks and real package/definition readers passed. The standalone render-reader
check was deferred for Folia component bootstrap, then actual server startup loaded the
render store without parse or missing-template errors. At 13:36 UTC both plugins enabled;
Bridge health, WebUI health and homepage returned HTTP 200. Live signal inspection reported
definition revision 46, six regions/tables/blocks and two fixed-block plus two protection signals.
No new coordinate/render/linkage errors appeared. Existing Terra and startup physics warnings
remain outside this change. Client visual and gameplay acceptance has not been performed.

Local `models/rails.zip` and `models/jr-route-indicator.zip` were also rebuilt with only
converted configuration entries; original copies and hashes are retained in
`/tmp/jr-model-coordinate-zip-backups/`. Those local ZIP backups are temporary, while the
server recovery archive above is the durable restoration point.
