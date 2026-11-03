# ATO hot-path and brake-preparation deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`b60df86b92dc6b9e55253a29ae23f26d6e766a5f` to **JavaRailwaysTest**, host
`192.168.1.230`, MCS instance `836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. No schema migration, configuration
change or world-data rewrite was performed. 155 inventoried plugin-data and
other-JAR files were preserved byte-identical.

## Build

Full gate from the committed branch tip:

- Tests: Core 540, Basic 463, SimpleATO 739, BukkitPlugin 728 (1 existing skip),
  SimpleSignals 675, WebUI 77 — **3,222 tests, 0 failures, 0 errors, 1 skipped**.
- `:BukkitPlugin:compileSignalApiConsumerJava`, `verifySignalModuleBoundaries`,
  `:BukkitPlugin:shadowJar` and `:TunnelBridgeBuilder:shadowJar` passed.
- Candidate `JavaRailways.jar` SHA-256
  `463bd5f2250fe70a75a8f002336c32c92d3f5dae1612b9311fab0434002103f9`
  (67,472,590 bytes).

The 100 km synthetic five-car preview benchmark improved from 4.48 s
(before this optimization series) to ~0.50 s; S100 500 m ATO scenarios retained
1343 ticks, 67.15 s and `-9.607312313164584E-5 m` error.

## Stop and recovery

One player (`__ART1st__`) was online. A console warning announced a 30 s
restart, then MCS stopped the instance normally. Original PID `859289` exited,
MCS status became 0/PID 0, and ports 8765/29002 closed before mutation.

External recovery point (server-local, root-only):

- `/opt/mcsmanager/backups/javarailways/ato-hotpath-20261103-7l3fqgpl`
- 156 files; `recovery.zip` SHA-256
  `f1464adf631fe14ab9b97e5de43b6e16f0ec12dd8e2884b537e0920ebfef2713`
- file hashes, ownership and modes are in `backup-manifest.json`

This is a plugin-data backup, not a Minecraft region-file backup.

## Installation and live verification

Atomic install completed at `14:53:33Z`; 155 files remained byte-identical.
Installed JAR is `root:root 644`, 67,472,590 bytes, matching the candidate hash.

New PID `862047`; startup reached `Done (27.094s)` at `14:54:11Z`. One startup
physics epoch of 344.88 ms and one delta clamp occurred; no stall guard or
`World tick stopped` followed.

- Bridge `/health`: HTTP 200 `{"ok":true}`.
- WebUI `/`: HTTP 200 locally and via `http://mc.art1st.work:29002`.
- Online players: 0; TPS 20 (lowest/median/highest all 20.00).
- `ATO world/world_nether/world_the_end: RUNNING`.
- SimpleSignals loaded; primary `definitionRevision` 117.
- No post-start server, JavaRailways, SimpleSignals or WebUI ERROR/SEVERE.
- No ATP event or `AtpSimulationAbort` in the post-start log.
- Known Terra `theUnsafe` startup message is unchanged.

Evidence: `manifest.json`, `preflight-receipt.json`, `install-receipt.json`,
`recovery.json`, `backup-manifest.json` and `runtime-verification.json`.

## Rollback

After a normal save/stop:

```sh
cd /tmp/jr-deploy-20261103-ato-hotpath
sudo python3 deploy.py rollback 862047 \
  /opt/mcsmanager/backups/javarailways/ato-hotpath-20261103-7l3fqgpl
```

This restores the exact pre-deployment JAR and plugin-data set. It discards
edits made after this deployment, so a fresh recovery point is required if
player work must be preserved.

## Limits

Build, startup, HTTP delivery, TPS and read-only ATO/Signal status were
verified. No player client re-joined during verification, no train was driven,
and no in-game ATO/ATP stopping or visual acceptance is claimed. The hot-path
improvement is measured by the offline benchmark, not by live train count.
