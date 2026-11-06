# Deployment — 2026-11-06 UTC

User explicitly approved one task commit and deployment to the existing JavaRailwaysTest server, not a Git push. Work commit: `ee4dde6d280971692e67106f41633f21ddb84f78`. Unrelated TODO, older task notes, ai_tasks files and tmp.1 were excluded and remain untouched.

## Baseline and artifact

- Target: `user@192.168.1.230`, MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96` (JavaRailwaysTest).
- Used the existing host agent at `/run/host-ssh-agent/agent.sock`; no credential/configuration changes. Initial root login failed; the verified historical user account worked. Manager authentication stayed local to the server and no key was output or copied.
- Installed baseline SHA256 `bb556bcd86f50340180101624d66927831bc71f6a905a0020063d39c183fd5a3` was matched to this thread's prior `edc58462` build/deployment record. It is an ancestor of the candidate; the deployment includes subsequent committed rolling-authority and exact-route-position fixes as well as this task. SimpleSignals, Core and companion plugin source changes were checked; the previous Basic route-position fix is included, not dropped.
- Built with `./gradlew :BukkitPlugin:shadowJar -x :WebUI:installFrontend --offline --console=plain --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g`: success. Existing frontend build inputs were up-to-date; the embedded UI was independently hash-verified after startup. This Folia server consumes the Mojang-mapped shadow JAR, not reobf output.
- Candidate JavaRailways SHA256: `636d383407e83c1d81686ad88dbc335e2a2c027a7eea610f9a1124db8776577e`.
- Retained SimpleSignals SHA256: `36657ce6a312e4498aba656060e831b8a0f4beaa6677ecab4cc3d6d715d2f29c`.
- Local/remote staging: `/tmp/jr-dwell-deploy-20261106-VMADu3cD`; includes candidate, exact old/new manifest, reviewed existing deployment helper and manager controller. No persistence/schema migration or timetable Reset was executed.

## Stop, recovery and replacement

One player was online; the user was notified before the authorized normal manager stop. Original PID935939 exited, manager status0 and ports closed. Logs at15:51:57 confirm all world and player data saved; no forced termination.

Recovery directory: `/opt/mcsmanager/backups/javarailways/line-dwell-20261106-f1m35v88`.

- `recovery.zip` contains150 files: installed plugin JARs plus JavaRailways/SimpleSignals/TunnelBridgeBuilder plugin data.
- SHA256 `033ffdd5dceb67420f9a1c1b6166d6d0dd8f725aa08f411e52671e568c5ef345`.
- The helper checked archive membership and every file hash, source stability, stopped PID/listeners, manifest identity and installed baseline before replacement.
- At15:52:47 it atomically replaced only `plugins/JavaRailways.jar`, preserving ownership/mode, and verified the other149 inventoried files remained unchanged before startup. A replacement validation failure would restore the original JAR from the verified archive.
- For later rollback: normal-stop this exact instance, verify the recovery manifest/archive, restore its `plugins/JavaRailways.jar` atomically preserving file metadata, and start/check again. Do not overwrite unrelated later user data as part of a JAR-only rollback.

## Startup and health evidence

- Manager status3, new server PID946187, startup Done at15:53:18.
- `jr ato status world`: **RUNNING**.
- `simplesignals inspect world`: installed, definitionRevision117; regions5/tables5/blocks14/fixedBlockSignals8/protectionSignals4/routeIndicators1. Signal JAR hash unchanged.
- Bridge `http://127.0.0.1:8765/health`: `{"ok":true}`.
- WebUI local and public `http://mc.art1st.work:29002/health`: `{"status":"ok"}`.
- Extracted WebUI JAR matches embedded SHA256 `d837a58b2d84f8aa4ba935bb6256eef7bd9f06d1487f9afe530c89273199d722`.
- Served index, JS `index-DpR71t2G.js`, CSS `index-C3pepVSS.css` hash-match candidate resources exactly: index `82ac0b1199eb827d853be07cb59bf851c788073fb7561b5d596443a845e90746`, JS `0205481b4c5a756a1a79f66bfa27e65f4226587be36e77fb974a775922fa97a9`, CSS `5eb6b037d8162279090891e75bc02a27a16af10c3d17fbb0da7edc7724110e4c`.
- Original editor document `plugins/JavaRailways/webui/drafts/editor-documents/world/new-timetable-9.json` remains SHA256 `9d9f7df71b83c0c3f33b8937780c9b0b117832bc0fe15a41f33bf02a9c0e23e6` after startup.
- No new JavaRailways/SimpleSignals startup error was found. Third-party `com.dfsek.seismic` Unsafe reflection error also exists in prior `2026-11-06-2.log.gz`; existing HTTP exposure warnings and startup physics overload/clamping warnings are not hidden or mislabelled as new deployment failures.

This proves successful installation, domain startup and HTTP asset delivery. It does not claim new online global-simulation or game-client driving acceptance. Previously documented intermediate-offset continuation and five-duty route issues remain separate; no new server simulation or user document mutation was performed.
