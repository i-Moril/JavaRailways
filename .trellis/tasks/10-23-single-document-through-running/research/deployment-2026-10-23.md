# Deployment — 2026-10-23

User authorized deployment. Completed to JavaRailwaysTest (192.168.1.230, instance 836082a48c4f48fcb1fea4e83c57dc96).

## Source and package

- Independent worktree `.worktree/station-operation-programming`, branch `codex/single-document-through-running`, base f045499d; validated task changes remain uncommitted. Local `/tmp/jr-interline-package-20261023/source-state.json` and source.patch identify the build inputs. Source inventory SHA256 8f1acfd17327ee4050979e450aded42a250f32fa6d502b3e05b21e374e423790.
- `:BukkitPlugin:shadowJar` succeeded; independent packaging check passed.
- Installed JavaRailways.jar SHA256 b1515abc4ccb00b0a95debe0a74c8c851c32975b95b37b1cfd880a72550cc8c6.
- Embedded WebUI SHA256 b2ea38d55f7bc0d5b371a626c0807a1bc077bf32051644803ab63ae4e2600c39.
- New domain/worker classes and DSL exports present; nested frontend matches standalone build.

## Stop, recovery and installation

- Initial host agent did not respond; user supplied a working agent. Authentication restored; no credentials stored in task artifacts.
- Online players 0. Normal MCSManager stop; status 0/PID 0 and all-world/player save completion verified.
- Recovery archive: `/opt/mcsmanager/backups/javarailways/station-programs-20261022-vv7xchpa/plugins-and-data.tar.gz`; SHA256 973a3ea05696df9d4c8496d3b923c3d98b46aa6e8ad0cc96531bdd8fff603615. Directory prefix comes from reusing the existing server-side deployment tool; backup created 2026-10-23.
- Backup covers previous JavaRailways/SimpleSignals JARs and both plugin data directories (132 files inventoried), with manifest and installation audit.
- Only JavaRailways.jar uploaded and replaced after old/new hash checks. Automatic review rejected the earlier broader source-material upload; that command did not execute. Source materials and new deployment scripts stayed local; existing server-side deployment tool was reused.
- Plugin data and every other plugin JAR byte-identical at installation. SimpleSignals preserved SHA256 eea60c9f6ff273999dc371637e1b0a9f6a566813fec13ae6ad041ba60d0bdd82.

## Startup verification

- Normal restart completed, Done at 11:53:00 UTC. At 11:54:22, `jr ato status world`: ATO world RUNNING.
- SimpleSignals inspect: definitionRevision 46, topologyRevision 1; regions/tables/blocks 6 each, fixed/protection signals 2 each.
- Bridge `/health`: HTTP 200 and ok:true. WebUI HTTP 200; extracted sidecar, served index and referenced JS/CSS match candidate bytes. Public `http://mc.art1st.work:29002/` index also matches.
- Assets: `/assets/index-C3D6XtS_.js`, `/assets/index-BABVbenA.css`.
- No world-load errors or new ERROR/SEVERE lines in either plugin errors.log. Existing Terra sun.misc.Unsafe error and startup physics warning remain outside this change.
- Local machine-readable evidence: `/tmp/jr-interline-live-verification.json`.
- This establishes deployment health and correct frontend delivery, not real game/client feature acceptance.
