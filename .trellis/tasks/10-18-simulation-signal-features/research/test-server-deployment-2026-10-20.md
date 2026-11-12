# Test-server deployment — 2026-10-20

## Result and scope

Deployed merged `dev/signal-system` at `a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2` to **JavaRailwaysTest**, host `192.168.1.230`, instance `836082a48c4f48fcb1fea4e83c57dc96`. Includes world isolation, full domain simulation and already-merged WebUI completion. Only JavaRailways.jar, SimpleSignals.jar and the installed companion TunnelBridgeBuilder.jar were replaced. Other plugin JARs remained byte-identical. No production source changes, runtime compatibility reader, schema bump or remote push.

Validated worktree `.worktree/simulation-world-alignment` matched main deployment inputs; 53-path source fingerprint `c1d805b99ba0bff1ed7cd2c18b49b6bf6152b6785a31431c93ac26a959fdabda`.

## Build and installed artifacts

Deployment build/check succeeded (40 tasks, 3 executed): BukkitPlugin shadowJar; SimpleSignals check; external signal API consumer; module boundaries; TunnelBridgeBuilder shadowJar. Previously accepted 2507 JVM and 108 frontend tests were not all rerun for deployment. Final remote JAR hashes were independently reread:

- `JavaRailways.jar`: `5a9021f0327054ce87fe42902b1dd9439f61f8cceefe1040bac36c4d4ed0f98f` (64964004 bytes).
- `SimpleSignals.jar`: `df374694603199531b1c0c58a70dda845072c072ee0746f235bba209df159c21` (1937146 bytes).
- `TunnelBridgeBuilder.jar`: `12f7732f244f873870a5f4eecb02e27e5cf21843ee6bf4daebb64c7c35065448` (132179 bytes).

## Stop, backup and migration

Both stops used MCSManager's normal stop; logs confirmed all worlds/player data saved and original PIDs exited. No force termination.

Initial checks covered versions and explicit endpoints but missed the nested removed Basic `metadata.operating` member. First new startup therefore rejected main-world infrastructure; this was a failed deployment attempt, despite healthy HTTP endpoints. We normally stopped again and preserved that state before any conversion.

Root evidence/research: `deployment-operating-migration-research.md`. Former operating contains only nullable turnout endpoint distances and is independent of the 26 operating-direction records. All actual 212 groups were exactly `{turnoutAtA:null,turnoutAtB:null}`; none held configured distances. No minorDeltaS occurred in any nested document. Independently reviewed conversion deleted only those groups, 106 each from main infrastructure current and `.bak`. All other 25 world/render/signal files were byte-identical, and every other business field/version/revision/epoch was preserved. No sidecar definitions changed.

The current offline `migrateDevelopmentWorlds -PmigrationEndpointsOnly` entrypoint strictly decoded all outer and six nested Core/Basic/SimpleATO snapshots, and ran actual business restore for all **12 pairs / 6 UUIDs**, including backups. Dry-run BUILD SUCCESSFUL, no files written by validator. A separate reviewer returned PASS before applying. Apply guarded stopped process, full recovery archive and source hashes, exact two-file identities, candidate hashes and complete semantic equality, then replaced each file atomically with retained owner/mode and rechecked all other files.

- Original installed version and all plugin data: `/opt/mcsmanager/backups/javarailways/world-isolation-20261020-w0xaeobz/configs-and-installed-jars.tar.gz`; SHA-256 `cd5dd41b7ee1387c51a5ba97603e2c13bc7dac34ef6cf5c804c84ee144ef3462`; 127 inventoried files.
- Failed-startup state before migration: `/opt/mcsmanager/backups/javarailways/world-isolation-20261020-2upb_g4l/configs-and-installed-jars.tar.gz`; SHA-256 `012d59d59a17d5ab74a9d401a07800e0f54f0ca59af71f3f982aa9c1a1d21bcf`; 127 inventoried files.

Recovery archives are external to the instance, root-only, and preserve numeric ownership/ACL/xattrs. They cover all three plugin directories and old installed JARs. For full rollback: first normal stop and save current state to a new recovery point, then restore the matching original JARs and plugin data together, with archive/source validation. Never restore this stopped snapshot over a live server or discard subsequent user edits silently.

## Live verification

- Normal MCSManager start; Folia 26.1.2 build 8 / Java 25; final process PID 585469, manager status 3.
- Final startup at 17:23:44 UTC, Done in 25.977s; model packages: 1 train and 3 rail.
- Main world read-only command: `ATO world: RUNNING`.
- SimpleSignals loaded **primary** definitions; read-only inspect: installed, definitionRevision **46**, topologyRevision 1; regions **6**, tables **6**, blocks **6**, fixedBlockSignals **2**, protectionSignals **2**, routeIndicators 0, attachedSignals 0.
- Bridge `/health`: HTTP 200, `{"ok":true}`. WebUI `/`: HTTP 200 both server-local and from workspace over LAN.
- Served JS `/assets/index-CfCgs3im.js`, SHA `8a7c18085ca375f90888f3841680b909b775672277a5cd48fb8d1262b54d5bb8`; CSS `/assets/index-Be3IdvAb.css`, SHA `3d1a70f24f7249f46370e282163a4c94722b7169bc47b8c8db3df62754b3c6b1`; both exactly match the deployed embedded WebUI.
- Final startup had no world-load/Signals unavailable error; Signals errors.log did not grow after second backup. Historical failed-start logs retained. Existing HTTP-binding warnings and unrelated Terra Unsafe error remain; a single 259ms startup physics epoch caused an overload/clamp warning, without repeat through the observation checkpoint.

This confirms deployment/startup/data restoration and HTTP asset delivery. No player joined for in-game acceptance; train movement, visual rendering, and interactive full-feature simulation were not exercised on this server. It is not a claim of game-client acceptance.

## Audit artifacts

Durable remote audit bundle is retained with the second recovery directory: build, strict validation, migration plan, apply audit, final health/asset verification and the exact offline transform/apply scripts. Local `/tmp/jr-*20261020*` files are working copies, not the sole recovery evidence. Deployment did not modify production code; this report and the migration research are the only repository additions.
