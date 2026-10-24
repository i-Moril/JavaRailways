# JavaRailwaysTest deployment — 2026-10-24

- Target: MC / `192.168.1.230`, MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96`.
- Source: `62442fdbbca554573f5f6a63e91c30cb3d6bd8bb`, branch `deploy/station-blocks-20261024`, deployment integration worktree `/tmp/javarailways-blockly-deploy`.
- Integrated station feature `621384a1` with previously deployed `a59606ba`; retained LogsPage and owner-aware editor/library bindings. Scoped merge review found no additional issue.
- Includes shared Blockly editor and arrival programs, infrastructure line maximum speeds, and reopening the last saved editor document.

## Validation and artifact

Frontend: 199 tests passed; production build passed. Backend: Core 524, Basic 427, SimpleATO 610, BukkitPlugin 634, Diagnostics 4, WebUI 32 tests passed (2,231 total, no failures/errors/skips). `:BukkitPlugin:shadowJar` passed. Nested sidecar frontend matched all 17 production files before deployment.

Only `plugins/JavaRailways.jar` replaced (66,791,280 bytes).

- Previous SHA-256: `e35364db1338c2d2e574e9575a516b334790d5ef4b8ae81d9aec698f94351155`
- Installed SHA-256: `c4bdf0ec44b5431dd88e25ba2853a52b383680ce1236ff14256f4dbaa9704b7b`
- Candidate and plan: `/tmp/jr-blockly-deploy-20261024-62442fdb` (local and remote).

## Recovery and installation

Normal MCSManager stop completed at 08:10:18 UTC; all worlds, global world data and player data saved; status 0 / PID 0. Existing deployment tool also checked target JVMs stopped. Recovery directory:

`/opt/mcsmanager/backups/javarailways/merged-review-20261023-ho2mam6a`

Historical directory prefix is retained by the existing tool; this backup was made on October 24. `plugins-and-data.tar.gz` SHA-256: `5067ae432cd4d6259c8bc8bcef48f31fa0fb972a970a9956671fdf8e1112ec0c`. Backup manifest records 134 data files and all plugin JAR hashes. Install audit at 08:10:50 UTC confirms plugin data byte-identical during replacement and every other plugin JAR unchanged. No rollback performed.

## Live verification and limits

- Normal MCSManager start; server `Done` at 08:11:30 UTC.
- `jr ato status world`: RUNNING.
- `simplesignals inspect world`: installed; definition revision 46, topology revision 1; 6 regions/tables/blocks; 2 fixed-block and 2 protection signals.
- Bridge `/health` HTTP 200, `ok: true`.
- Extracted WebUI sidecar exactly matches nested JAR. All 17 served assets, including Blockly media, match packaged bytes. Public `http://mc.art1st.work:29002/` index matches.
- New startup has no JavaRailways/SimpleSignals ERROR/SEVERE. Existing Terra `Field theUnsafe not found` error is also present in previous `2026-10-23-4.log.gz`. Existing HTTP exposure warnings and transient startup physics overload/clamp warnings remain.
- **Observed before replacement during old-version shutdown:** 5 JavaRailways SEVERE records for editor/model cleanup of SimpleSignals objects and 1 SimpleSignals cleanup failure. Host was disabled before asynchronous closeOwner cleanup completed. World/player saving still completed. This deployment does not fix that lifecycle issue; evidence remains in both plugins' disk error logs at 08:10:17 UTC.
- Live game/client acceptance of Blockly authoring, arrival execution, line caps and editor reopen remains outstanding. Health and resource checks do not establish those behaviors.
