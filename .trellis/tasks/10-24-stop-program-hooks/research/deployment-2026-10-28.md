# Deployment — 2026-10-28

User explicitly authorized deployment. Source: `codex/timetable-station-programs`, `f8b80012e3d9262872b9da666ba8f6cb09dfa4a7`, built from `/tmp/javarailways-blockly-deploy`. Product working tree clean except pre-existing untracked heap dump, excluded from deployment. Task remains in_progress pending user game acceptance.

## Build and preflight

JDK25 offline `:BukkitPlugin:shadowJar :WebUI:test` succeeded; frontend TypeScript/Vite build included. Deployed Mojang-mapped shadow JAR. Main SHA256 `d214efe12142bfcc77666f086f6942e8c81a069935af81ad3e868d87ce830905`; embedded WebUI SHA256 `eb093ec8f331f859656710c2831738cae96581866fc68458b076aaaae47a81d2`.

Downloaded current dev-server drafts and validated all6 editor documents and1 program-library record using the candidate JAR validators. Unified-timed-operations dry-run:0 rewrites. After stop, all7 source files still matched the validated preflight hashes. No data migration applied.

## Save, backup and replacement

Target user@192.168.1.230, MCSManager instance836082a48c4f48fcb1fea4e83c57dc96. Normal stop saved all worlds/global/player data at18:20:58 UTC; management status0/PID0 and no target JVM confirmed before backup/replacement.

Recovery: `/opt/mcsmanager/backups/javarailways/merged-review-20261023-mj_igch1`. Historical directory prefix comes from established helper; this backup was created October28. Archive SHA256 `c3672b5d08a469c9c521fa0d2bab5595308b0806ec0fe2df05baf858dce597f5`;136 plugin data files inventoried. Recovery contains previous plugin/data archive, manifest and deployment/install audit. No rollback performed.

At18:21:29 UTC hash-guarded atomic replacement changed only `plugins/JavaRailways.jar` from SHA256 `5efbcd9a78a2879ed01a4387a0375a4cdd4b7e361759ac9a29162962711e95a0`. Plugin data remained byte-identical during installation, all other plugin JAR hashes unchanged. Stage locally/remotely: `/tmp/jr-deploy-20261028-f8b80012`.

## Live verification

Normal startup completed18:22:01 UTC in27.299s; final manager status3/PID729147. `jr ato status world` returned RUNNING at18:22:25 UTC. Bridge127.0.0.1:8765/health returned200/ok. Managed sidecar matches embedded candidate WebUI. All17 WebUI resources served both locally and via `http://mc.art1st.work:29002/` matched candidate bytes, including JS `assets/index-BQIEcxnJ.js` and CSS `assets/index-BJG04iiE.css`.

No JavaRailways/SimpleSignals ERROR/SEVERE in startup log. Existing Terra Unsafe reflection error, plaintext HTTP warnings and transient startup physics warnings persist. Initial health probe before startup completed refused connection; post-start checks all passed.

Installation/startup/resource delivery are verified. Actual browser interaction and game/train behavior remain for user acceptance. No push or task archive.

## Follow-up deployment: program deletion synchronization

User explicitly requested commit+deploy while keeping task open. Product commit `5d588574ac22d54bc5a013ba874f2fdedd07912a`. Frontend301 tests/49 files and TypeScript/Vite build already passed on unchanged source; deployment shadowJar rebuild and WebUI tests passed. Main JAR SHA256 `33dd28e14df42eb1ee118d5a483edec0f910d1710c2430a5164ff05189872cd7`; embedded/managed WebUI `a5e3c6424c2e752f311a96301ae4d29f0216ded121b7f7b2d2c07ed843772a87`.

Candidate validator accepted all6 current editor documents and0 library records. Normal stop saved all worlds/global/player data18:36:32 UTC; management status0/PID0. Stopped drafts matched preflight hashes. No migration required. Recovery `/opt/mcsmanager/backups/javarailways/merged-review-20261023-i47zdrxw`, archive SHA256 `2005547ead0f7e5c5930e07676249f3cbb710096ce9ebe03f78ff46e99c5b2ba`;135 data files inventoried. Hash-guarded atomic replacement18:36:57 UTC preserved plugin data and all other plugin JAR hashes. Stage `/tmp/jr-deploy-20261028-5d588574`.

Normal startup Done18:37:28 UTC in27.114s; bridge /health200/ok, managed sidecar matches embedded bytes, all17 resources both local/public match candidate. New JS `assets/index-Bq52Cfge.js`, CSS `assets/index-BJG04iiE.css`. No JavaRailways/SimpleSignals ERROR/SEVERE startup markers; existing HTTP and transient physics startup warnings remain. Game/browser interaction acceptance remains with user. No push, completion or archive; task explicitly remains in_progress.

Final follow-up status: manager3/PID730077; actual `jr ato status world` returned RUNNING18:37:33 UTC.
