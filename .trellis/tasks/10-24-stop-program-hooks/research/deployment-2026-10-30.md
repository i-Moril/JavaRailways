# Deployment — standalone function simulation, 2026-10-30

User explicitly requested commit and deployment. Product source `4c19f7f4eacd755ebd7f1132dfb625b641901789`, branch `codex/timetable-station-programs`, worktree `/tmp/javarailways-blockly-deploy`. No push or task completion/archive. Existing unrelated heap dump excluded.

## Candidate and data checks

The previous full source gate passed Core538/Basic435/SimpleATO643/Bukkit663/WebUI49 (2328 total), frontend323 and production build; see function-simulation-validation-2026-10-30.md. Deployment rebuilt the actual frontend and `:BukkitPlugin:shadowJar`, with `:WebUI:test` up-to-date and successful. JDK25/offline/one worker; only cached dependency installation was excluded.

Main Mojang-mapped shadow JAR SHA256 `876ee4dcfe89471447b886381a44ccc604834c751f9b2799304403b979df4728`; embedded/managed WebUI `f4cd3ba53c9003ccfc867ec5508e8b7720019b270779f0050cdba11e7696ae81`. Verified new Core unique-placement and program-request classes, exact embedded sidecar bytes and all17 resources against current frontend dist. JS `assets/index-DLbd-GXK.js`, CSS `assets/index-CdCGfk_W.css`.

Candidate validators accepted current server7 editor documents and6 library records. After shutdown, all13 files still matched preflight fingerprints. No data migration or persistent-version change.

## Save, recovery and replacement

Target `user@192.168.1.230`, MCSManager instance836082a48c4f48fcb1fea4e83c57dc96. Existing host SSH agent `/run/host-ssh-agent/agent.sock`; management Node `/opt/mcsmanager/node-v20.12.2-linux-x64/bin/node`. Credentials stayed in existing agent/config readers; no secrets copied into this record.

Normal stop saved all worlds/global/player data06:34:38 UTC; management status0/PID0 and deployment helper's no-target-JVM check confirmed before backup/replacement. One player was online before the user-authorized restart.

Recovery `/opt/mcsmanager/backups/javarailways/function-simulation-20261030-dgrrp6mb`, archive SHA256 `1b173c78a2cfa7c445a532869747d71922db46f53ee982e49eada4003f756886`,142 plugin-data files inventoried. Recovery holds prior plugin/data archive, manifest, helper and install audit. Stage `/tmp/jr-deploy-20261030-4c19f7f4` locally/remotely.

Hash-guarded atomic replacement06:36:11 UTC changed only `plugins/JavaRailways.jar`, previous SHA256 `33dd28e14df42eb1ee118d5a483edec0f910d1710c2430a5164ff05189872cd7`. Plugin data and all other plugin JARs remained byte-identical at installation. No rollback was needed.

## Runtime verification

Normal startup completed06:37:00 UTC in28.076s. Final management status3/PID753535; actual `jr ato status world` logged `ATO world: RUNNING`06:37:13 UTC. Bridge127.0.0.1:8765/health200/ok; managed WebUI matches embedded bytes. All17 resources served on localhost29002 and `http://mc.art1st.work:29002/` returned200 and exactly matched candidate contents. New simulate POST without session returned401.

No JavaRailways/SimpleSignals ERROR/SEVERE startup entries. Existing Terra/Seismic Unsafe reflection ERROR, plaintext HTTP warnings and transient startup physics warnings persist; no new plugin startup failure observed. Build/installation/health/resource delivery and authentication gate are verified; actual browser/game function behavior remains for user acceptance. Task remainsin_progress, completedAtnull; no archive.
