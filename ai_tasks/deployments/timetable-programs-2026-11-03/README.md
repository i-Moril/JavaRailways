# Timetable-owned program library deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`a397ac7d1b2dc42acf0cc711be73b74c26f20a84` to **JavaRailwaysTest**,
host `192.168.1.230`, MCS instance
`836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. The one-time development-data
migration moved the six global programs into `new-timetable-9`, removed its
`boundPrograms` snapshot list, deleted the other eight editor documents, and
deleted the global program library. No Minecraft world data was rewritten.

Current model:

- programs live at `webui/drafts/timetable-programs/<world>/<documentId>/<programId>.json`;
- program records contain only `formatVersion:1`, `id`, `name`, `definition` and
  optional `estimatedDurationTicks`;
- no program revision, content identity, bound snapshot or adoption flow;
- importing from another timetable is a one-time copy;
- opening/editing a timetable or its programs acquires an in-memory exclusive
  document lock; a second session receives 409 `DOCUMENT_LOCKED`.

## Build and tests

- Frontend `npm test`: **474 tests, 0 failures**.
- WebUI JVM `:WebUI:test`: **65 tests, 0 failures**.
- `npm run build` and `:BukkitPlugin:shadowJar` passed.
- Candidate `JavaRailways.jar` SHA-256
  `9eb8d3668e07730b764d3a454329df4ac11d39c14dfab5fcf221914b19d645a0`
  (67,475,288 bytes).
- Embedded WebUI SHA-256
  `f3ad0bb18c120d8f939ecb470cd4aa108655876f4ebef4cdfe5d1a8af672f831`;
  main script `/assets/index-CP1sUo4C.js` SHA-256
  `f8a0a38ab69f3a89e905ab0d0aeb07c5c0e3f22481299bd6da45ba7f73c052d5`.

## Stop and recovery

One player was online; a console warning announced the migration/restart, then
MCS stopped normally at `16:03:20Z`. `Saved all worlds` completed at
`16:03:21Z`; PID `864359` exited and ports 8765/29002 closed before mutation.

Deployment recovery point (pre-migration, root-only):
`/opt/mcsmanager/backups/javarailways/timed-ops-20261103-bowf9hu9` with 156
plugin data/JAR files; `recovery.zip` SHA-256
`9e42e865fcb24c0794c6eaa8ce92284a11eea02c4e8176939af6a911a7e47553`.

Migration recovery point:
`/opt/mcsmanager/backups/javarailways/timetable-programs-20261103-pregraft`
with the original editor documents and six global program records, plus
`backup-manifest.json`.

## Migration

Dry-run (`migration-dry-run.json`) found nine editor documents, six global
programs, five referenced programs and eight documents to delete; the sixth
program (`kasai入库`) was still copied to preserve the current library.

Apply (`migration-apply.json`):

- copied all six programs to
  `drafts/timetable-programs/world/new-timetable-9/`;
- removed `boundPrograms` from `new-timetable-9.json`;
- deleted `new-timetable-2..8` and the legacy `new-timetable.json`;
- deleted all six global `drafts/station-programs/world/*.json` files and the
  empty directory.

Verification after apply and before JAR install: only
`new-timetable-9.json` remained, six local programs existed, the global program
count was zero, and no program reference was unresolved.

## Installation and live verification

Atomic JAR install completed at `16:04:17Z`; 147 inventoried files remained
unchanged by the JAR replacement. New PID `865720`; startup reached
`Done (26.454s)` at `16:04:51Z`.

- Installed JAR matches the candidate.
- WebUI `/` returns HTTP 200 locally and via
  `http://mc.art1st.work:29002`; public main script bytes match the candidate.
- Live bundle contains the timetable program import UI.
- Bridge `/health`: HTTP 200 `{"ok":true}`.
- TPS 20; online players 0.
- Editor documents: only `new-timetable-9`; timetable programs: 6;
  global programs: 0.
- No post-start JavaRailways, WebUI or SimpleSignals ERROR/SEVERE; known Terra
  `theUnsafe` startup message unchanged.
- No `World tick stopped` or ATO stall marker in the post-start log.

Evidence: `manifest.json`, `preflight-receipt.json`, `install-receipt.json`,
`recovery.json`, `backup-manifest.json`, `migration-dry-run.json`,
`migration-apply.json`, `migration-backup-manifest.json` and
`runtime-verification.json`.

## Rollback

After a normal save/stop, remove the migrated program directory and restore the
pre-migration deployment recovery point:

```sh
cd /tmp/jr-deploy-20261103-timetable-programs
sudo python3 install_after_migration.py rollback 865720 \
  /opt/mcsmanager/backups/javarailways/timed-ops-20261103-bowf9hu9
```

This restores the original JAR, editor documents and global program library.
It discards player work created after this deployment, so take a fresh recovery
point first if later work must be preserved.

## Limits

Automated build, migration dry-run/apply verification, backup/install guards,
HTTP delivery and file ownership were verified. No authenticated browser
session or in-game client joined after restart, so the program library page and
second-session lock were proven by JVM HTTP tests rather than an interactive
browser session.
