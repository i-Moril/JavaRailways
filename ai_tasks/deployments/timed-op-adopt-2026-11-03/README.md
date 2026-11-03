# Timed-operation snapshot adoption deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`e97875ae6df17a484bc1d0c65d694c061819809e` to **JavaRailwaysTest**,
host `192.168.1.230`, MCS instance
`836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. No schema migration, configuration
change or world-data rewrite was performed. 155 inventoried plugin-data and
other-JAR files were preserved byte-identical.

This build carries the fix for the reported “wrote an estimated duration but the
timed operation still says unknown” case:

- the timed-operation editor detects when its bound program snapshot is older
  than the program-library record (revision, content identity or estimated
  duration);
- it shows the latest revision and estimate with an explicit **Adopt new
  revision** button;
- adopting commits the latest snapshot through the existing document/history
  path, so the function-estimate hint updates immediately;
- changing the duration mode now also requires saving the timed operation
  before running the whole-table simulation, because the projection reads the
  saved document.

## Build

Built from the committed branch tip:

- `:WebUI:test` — **77 tests, 0 failures, 0 errors**.
- `npm test` in `WebUI/frontend` — **505 tests, 0 failures** (including the
  adoption-click regression test added after the candidate build).
- `npm run build` — TypeScript and Vite production build passed.
- `:BukkitPlugin:shadowJar` — passed.

Candidate `JavaRailways.jar` SHA-256
`d02282d369e642343c10ac6bffb936f6c603635474b90fb586aa2752a0dd3e3a`
(67,472,860 bytes). Embedded `webui/JavaRailways-WebUI.jar` SHA-256
`02829835d323d0fe18e888e7b56bf1bc199ce258df6d43fa9d5e5dd4dc469b60`;
main web script `/assets/index-dEJzqrcw.js` SHA-256
`c1e0300e9b5a17144dd74afac0dbaa5b6737308ba10ea3d2d0e25b6c44eb2253`.

## Stop, recovery and installation

Online count was 0. Normal MCS shutdown saved all worlds at `15:32:45Z`.
Original PID `862047` exited, MCS status became 0/PID 0, and ports 8765/29002
closed before mutation.

External recovery point (server-local, root-only):
`/opt/mcsmanager/backups/javarailways/timed-ops-20261103-q2ly0nnx`
with 156 plugin data/JAR files. `recovery.zip` SHA-256
`1b8f187f7dfe23b09ee9c10ebaa11b6c358f431cf5940b86f37b18a4c54c283d`.
This is a plugin-data backup, not a Minecraft region-file backup.

Atomic installation at `15:33:10Z` replaced only the main JAR. New main JAR
SHA-256 is `d02282d369e642343c10ac6bffb936f6c603635474b90fb586aa2752a0dd3e3a`.

## Live verification

New PID `864359`; startup reached `Done (25.953s)` at `15:33:43Z`. Two startup
physics overloads (maximum 293.76 ms) and one delta clamp occurred; no third
overload, stall guard or `World tick stopped` followed.

- Installed main JAR matches the candidate.
- Embedded WebUI hash matches the candidate.
- WebUI `/` returns HTTP 200 locally and via
  `http://mc.art1st.work:29002`.
- Public main script bytes match the candidate hash and contain the new
  adoption UI strings.
- Bridge `/health`: HTTP 200 `{"ok":true}`.
- TPS 20; online players 0.
- SimpleSignals primary `definitionRevision` 117, `.bak` 116.
- No post-start JavaRailways, WebUI or SimpleSignals ERROR/SEVERE; known Terra
  `theUnsafe` startup message unchanged.

Full evidence is in `manifest.json`, `preflight-receipt.json`,
`install-receipt.json`, `recovery.json`, `backup-manifest.json` and
`runtime-verification.json`.

## Rollback

After a normal save/stop:

```sh
cd /tmp/jr-deploy-20261103-timed-op-adopt
sudo python3 deploy.py rollback 864359 \
  /opt/mcsmanager/backups/javarailways/timed-ops-20261103-q2ly0nnx
```

This restores the exact pre-deployment plugin JAR and plugin-data set. It
discards edits made after this deployment, so a fresh recovery point is
required if later player work must be preserved.

## Limits

Automated build, startup, backup/install/rollback guards, HTTP delivery and
asset identity were verified. No authenticated browser session or in-game
client joined, and no interactive adoption click was performed. The running
bundle is proven to contain the adoption code; the user still needs to click
**Adopt new revision** on the affected timed operation and save the event.
