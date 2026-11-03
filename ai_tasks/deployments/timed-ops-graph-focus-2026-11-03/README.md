# Timed-operation copy / unfocused graph deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`c3e510fe3d97ab00ef224a64239f2dda2b34a08e` to **JavaRailwaysTest**,
host `192.168.1.230`, MCS instance
`836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. The deployment carries the three
WebUI changes requested in this session:

- line-simulation reset uses its own player-entered dwell instead of the global
  minimum dwell;
- graph timed operations can be copied with an interval and count;
- a blank-canvas click can clear the run focus so every run keeps its original
  colour.

No schema migration, configuration change or world-data rewrite was performed.

## Build

Built from the committed branch tip:

- `:WebUI:test` — **77 tests, 0 failures, 0 errors**.
- `npm test` in `WebUI/frontend` — **503 tests, 0 failures**.
- `npm run build` — TypeScript and Vite production build passed.
- `:BukkitPlugin:shadowJar` — passed.

Candidate `JavaRailways.jar` SHA-256
`fc6de4b22a9a2cc590f541da33bc0279e289230a2d209c06c2fbb7379f84c622`
(67,469,877 bytes). Embedded `webui/JavaRailways-WebUI.jar` SHA-256
`ed87319df78a8675fec5aeac9af81b18afe1020d3a11946cf2c232785a84b09c`;
main web script `/assets/index-MhTbxg_e.js` SHA-256
`c26fe17f52777abc6e1fd1d00b827b0dadc53b7874f62a99c255f0816f9d8ba5`.

## Stop, recovery and installation

Online count was one before shutdown; a console warning was broadcast, then a
normal MCS shutdown saved all worlds at `13:00:31Z`. Original PID `854357`
exited, MCS status became 0/PID 0, and ports 8765/29002 closed before mutation.

External recovery point (server-local, root-only):
`/opt/mcsmanager/backups/javarailways/timed-ops-20261103-ppqnpsyl`
with 156 plugin data/JAR files. `recovery.zip` SHA-256
`f534db9e6c3e0273ab3663aa30ef7b429cd668a7c55762896a2d52a06c791222`.
This is a plugin-data backup, not a Minecraft region-file backup.

Atomic installation at `13:01:04Z` replaced only the main JAR. 155 inventoried
files remained byte-identical. New main JAR SHA-256 is
`fc6de4b22a9a2cc590f541da33bc0279e289230a2d209c06c2fbb7379f84c622`.

## First start and live verification

New PID `859289`; startup reached `Done (28.460s)` at `13:01:40Z`. One startup
physics epoch of 303 ms and one delta clamp occurred; no stall guard or
`World tick stopped` followed.

- Installed main JAR matches the candidate.
- Embedded WebUI hash matches the candidate.
- WebUI `/` returns HTTP 200 locally and via
  `http://mc.art1st.work:29002`; public main script bytes match the candidate.
- Live bundle contains the new Chinese/English UI strings for the simulated
  dwell, timed-operation copy and clear-focus hint.
- Bridge `/health`: HTTP 200 `{"ok":true}`.
- TPS 20; online players 0 after restart.
- SimpleSignals primary `definitionRevision` 117, `.bak` 116.
- No post-start JavaRailways, WebUI or SimpleSignals ERROR/SEVERE; known Terra
  `theUnsafe` startup message unchanged. Existing WebUI HTTP-exposure warnings
  remain.

Full evidence is in `manifest.json`, `preflight-receipt.json`,
`install-receipt.json`, `recovery.json`, `backup-manifest.json` and
`runtime-verification.json`.

## Rollback

After a normal save/stop:

```sh
cd /tmp/jr-deploy-20261103-timed-ops
sudo python3 deploy.py rollback 859289 \
  /opt/mcsmanager/backups/javarailways/timed-ops-20261103-ppqnpsyl
```

This restores the exact pre-deployment plugin JAR and plugin-data set. It
discards edits made after this deployment, so a fresh recovery point is
required if later player work must be preserved.

## Limits

Automated build, startup, backup/install/rollback guards, HTTP delivery and
asset identity were verified. No authenticated browser session or in-game
client joined, and no interactive UI acceptance of the three controls is
claimed. The live bundle is proven to contain the new code, not that every
dialog was clicked in a real browser.
