# Graph rendering and selection refinement deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`ce6693e1b9450a89370c3ca6f84caf631e541d47` to **JavaRailwaysTest**,
host `192.168.1.230`, MCS instance
`836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. No schema migration, config change
or world-data rewrite was performed.

Graph UI changes:

- “Add timed operation” moved from the graph header into the graph page top
  ribbon; the graph header retains only document settings;
- run lines have a transparent ~12px selection band with the visual line not
  intercepting pointer events;
- timed-operation diamonds shrink to a 4px visual radius with a transparent
  9px hit circle; duration lines use the same 1.25px stroke as run lines instead
  of a thick filled bar;
- run labels now cover only their text, sit directly above/below the origin and
  connect with a same-colour 1px line;
- run lines, labels and stop hit targets render above timed-operation markers so
  stop points remain selectable;
- duty-page graph colours now use run colour first and the owning infrastructure
  line colour second, matching the main graph.

## Build and tests

- Frontend `npm test`: **474 tests, 0 failures**.
- WebUI JVM `:WebUI:test`: **65 tests, 0 failures**.
- `npm run build` and `:BukkitPlugin:shadowJar` passed.
- Candidate JAR SHA-256
  `a946a169474a7ccf30d8b52553f60c2485aa957ddda97b80add0f33c244e8c9c`
  (67,476,294 bytes).
- Embedded WebUI SHA-256
  `157c660fd74148daf568b4c8f600f505b0c259ed1875c91ec5a5373459fa2c29`;
  main script `/assets/index-Cn2u6Zkx.js` SHA-256
  `05fd06bdb770404d9e6937877703cb94c849044e851c11718c1665c1d0743544`.

## Stop, recovery and installation

Online count was one; a console warning announced the restart. MCS stopped the
instance normally, all worlds saved, PID `867372` exited and ports 8765/29002
closed before mutation.

Recovery point (root-only):
`/opt/mcsmanager/backups/javarailways/timed-ops-20261103-g4rs3p1z` with 148
plugin data/JAR files; `recovery.zip` SHA-256
`e3417beb0549a9d6bc2a924d27e7661fcda3598976c1d88c72d3abd85337bf7c`.

Atomic install at `17:04:26Z`; 147 inventoried files remained unchanged.

## Live verification

New PID `868263`; startup reached `Done (26.845s)` at `17:05:02Z`. One startup
physics overload (351 ms) and one delta clamp occurred; no stall guard or
`World tick stopped` followed.

- Installed JAR matches the candidate.
- WebUI `/` returns HTTP 200 locally and via
  `http://mc.art1st.work:29002`; public script bytes match the candidate.
- Live bundle contains the timed-operation toolbar command.
- Bridge `/health`: HTTP 200 `{"ok":true}`.
- TPS 20; online players 0.
- No post-start JavaRailways, WebUI or SimpleSignals ERROR/SEVERE.

Evidence: `manifest.json`, `preflight-receipt.json`, `install-receipt.json`,
`recovery.json`, `backup-manifest.json` and `runtime-verification.json`.

## Rollback

After a normal save/stop:

```sh
cd /tmp/jr-deploy-20261103-graph-refine
sudo python3 deploy.py rollback 868263 \
  /opt/mcsmanager/backups/javarailways/timed-ops-20261103-g4rs3p1z
```

This restores the pre-deployment JAR and plugin-data set. It discards edits made
after this deployment; take a fresh recovery point first if later work matters.

## Limits

Automated build, startup, backup/install guards, HTTP delivery and asset identity
were verified. No authenticated browser session or in-game client joined after
restart, so graph hit areas, label placement, layer order and duty-page colours
were not visually clicked in a live browser; they are covered by the frontend
build/tests and live bundle identity.
