# Duty page graph selection deployment — 2026-11-03

## Result and scope

Deployed `codex/ato-per-car-rebuild` at
`b43e28bb0bc269877fb02edcdb71db73c72ca374` to **JavaRailwaysTest**,
host `192.168.1.230`, MCS instance
`836082a48c4f48fcb1fea4e83c57dc96`.

Only `plugins/JavaRailways.jar` was replaced. No schema migration, config change
or world-data rewrite was performed.

UI changes:

- the duty page now reuses the rendered running graph to select a RUN or a graph
  timed operation instead of using dropdowns; clicking a run moves/assigns that
  run to the selected duty, clicking a programmed event adds one EVENT reference;
- run lines are clickable in the graph, and graph event markers call the
  selection callback instead of opening the graph editor on the duty page;
- duty ID, selector/reference vehicle, and initial-position fields moved into a
  right sidebar split into “Duty” and “Initial position” sections;
- the duty page has its own top ribbon: add operation program, couple, uncouple,
  undo/redo and save;
- the validation page no longer renders the timetable command ribbon;
- the graph page keeps the original timetable ribbon.

## Build and tests

- Frontend `npm test`: **474 tests, 0 failures**.
- WebUI JVM `:WebUI:test`: **65 tests, 0 failures**.
- `npm run build` and `:BukkitPlugin:shadowJar` passed.
- Candidate JAR SHA-256
  `dd161b8f4431ecaad811a59ea3d4c5fb6525639f59004d1c9ab28f0ce98873dc`
  (67,476,114 bytes).
- Embedded WebUI SHA-256
  `a15f6d5ee87cdbd0d5b841dc393a2f152b522f700987db5983ef4359fca959b1`;
  main script `/assets/index-DTo70vgn.js` SHA-256
  `e4b468fcbb09be06a6f5102684570e4840902fc466080baa37277235de814c0b`.

## Stop, recovery and installation

Online count was one; a console warning announced the restart. MCS stopped the
instance normally, all worlds saved, PID `865720` exited and ports 8765/29002
closed before mutation.

Recovery point (root-only):
`/opt/mcsmanager/backups/javarailways/timed-ops-20261103-pz5nxgpa` with 148
plugin data/JAR files; `recovery.zip` SHA-256
`82e94b07db8a33228bf556212cce978e28612f185854e68f04fe58a86c735cdf`.

Atomic install at `16:52:41Z`; 147 inventoried files remained unchanged.

## Live verification

New PID `867372`; startup reached `Done (28.312s)` at `16:53:18Z`. One startup
physics overload (352 ms) and one delta clamp occurred; no stall guard or
`World tick stopped` followed.

- Installed JAR matches the candidate.
- WebUI `/` returns HTTP 200 locally and via
  `http://mc.art1st.work:29002`; public script bytes match the candidate.
- Live bundle contains the duty graph-selection and toolbar strings.
- Bridge `/health`: HTTP 200 `{"ok":true}`.
- TPS 20; online players 0.
- Editor documents: only `new-timetable-9`; timetable programs: 6;
  global programs: 0.
- No post-start JavaRailways, WebUI or SimpleSignals ERROR/SEVERE.

Evidence: `manifest.json`, `preflight-receipt.json`, `install-receipt.json`,
`recovery.json`, `backup-manifest.json` and `runtime-verification.json`.

## Rollback

After a normal save/stop:

```sh
cd /tmp/jr-deploy-20261103-duty-graph
sudo python3 deploy.py rollback 867372 \
  /opt/mcsmanager/backups/javarailways/timed-ops-20261103-pz5nxgpa
```

This restores the pre-deployment JAR and plugin-data set. It discards edits made
after this deployment; take a fresh recovery point first if later work matters.

## Limits

Automated build, startup, backup/install guards, HTTP delivery and asset identity
were verified. No authenticated browser session or in-game client joined after
restart, so the new graph-click selection, right sidebar and page-specific
ribbon were not clicked in a live browser; they are covered by the frontend build
and component tests, while live asset delivery is verified byte-for-byte.
