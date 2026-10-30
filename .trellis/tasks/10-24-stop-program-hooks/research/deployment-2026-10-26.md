# Deployment — 2026-10-26

User authorized deployment after local commit aa5cb68d4c730dbbfef05a4be011b540f6a93685, explicitly keeping the task unarchived pending in-game testing.

Target: JavaRailwaysTest on user@192.168.1.230, MCSManager instance836082a48c4f48fcb1fea4e83c57dc96. Current host-agent mount is /run/host-ssh-agent/agent.sock (the older /run/host-ssh-agent.sock is absent). Credentials stayed inside existing agents/config readers.

## Preflight and preservation

Installed JavaRailways matched the prior deployment4deaae617700dda832b99b9650b49856542f458c93d982c536f0c0e489713f79. Other plugin hashes were inventoried and preserved, including separately installed SimpleSignals a968a036d39067a2327f8e53d99faf63d794ad1385e5672344c4e8f3274c6d0a and TunnelBridgeBuilder831a0c25179c9c98f2a7135256080c9f402713b18215781976ab2eb5fce3d722.

The current Java EditorDocumentValidator ran **on the server** against all6 existing editor documents: all accepted. Shape inspection of decoded nested SimpleATO snapshots found no dynamic CONDITIONAL/BRANCH/SELECT_STOP records; no old per-call arrivalProgram was found. No data migration was necessary. This check does not claim every old authored route satisfies the new strict routing policy; new compilation/world validation remains authoritative.

Player list reported0/20. Normal MCSManager stop completed15:44:44 UTC with all worlds, global data and player data saved; status0/PID0 confirmed and the deployment tool checked no server/sidecar JVM remained.

Recovery: `/opt/mcsmanager/backups/javarailways/merged-review-20261023-tlenk1ii`. The historical prefix belongs to the existing deployment tool; backup was made October26. Archive plugins-and-data.tar.gz SHA25662364ddf91d3fbd7a41c3599a38850b0cf2947549a42309464193c3b07b8107f,134 plugin data files inventoried. Backup includes old main/signal/tunnel JARs and plugin data. Keep source backup and manifests for rollback. No rollback performed.

## Replacement

Stage: `/tmp/jr-station-refactor-deploy-20261026-aa5cb68d` locally/remotely. Existing hash-guarded deployment tool atomically replaced **only plugins/JavaRailways.jar**, retaining owner/mode. Install audit15:45:30 UTC confirms plugin data byte-identical during replacement and all other plugin JARs unchanged.

Installed main JAR SHA2567d1305d67ea0c5b272bae8672bb0ba412a42ea6d826119acbe02c00b1d432e28. Uses verified Mojang-mapped shadow artifact, not the failed optional reobf output. Source is the exact approved commit; only administrative task-record changes preceded that commit since the tested source.

## Live results

- Normal start: Done at15:46:18 UTC,28.379s; final MCSManager status3/PID689014.
- `jr ato status world`: RUNNING at15:46:49.
- `simplesignals inspect world`: installed, definitionRevision46/topologyRevision1,6 regions/tables/blocks,2 fixed-block and2 protection signals.
- Bridge127.0.0.1:8765/health HTTP200/ok true.
- Managed sidecar equals nested WebUI JAR SHA256d5de9a7d3226cb572a37d884349251f4b4b99253b4558b99826db5d7d2f9c3c4.
- All17 locally served WebUI resources match nested bytes. Public http://mc.art1st.work:29002/ index and referenced JS/CSS match; current JS assets/index-BK8UlDIk.js, CSS assets/index-BSabZs3P.css.
- No JavaRailways/SimpleSignals ERROR/SEVERE in new startup log. Existing Terra Unsafe reflection error remains, plus existing plaintext-HTTP exposure and transient startup physics-overload warnings.

These checks establish installation, startup and correct resource delivery. Actual station-program, routing, reversal and train/client behavior awaits the user's game test. Task remains in_progress and is not archived.
