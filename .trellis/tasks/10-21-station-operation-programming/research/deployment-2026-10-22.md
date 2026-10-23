# Deployment 2026-10-22

User explicitly authorized commit and deployment, with subsequent fixes deferred to observed behavior.

- Feature commit: e38c67a5 (shared station programs, fixed EMU operations and recorded prior UI/Reset baseline).
- Deployment source: 3b09e17ba514de1147fe39d5143617398e96dd56. Preserves the already-deployed SignalPackEpochRuntime issue-clearing change, its regression and spec; no unrelated primary-worktree changes.
- Build: :BukkitPlugin:shadowJar and :SimpleSignals:jar. SimpleSignals full651 tests passed before preservation; focused runtime-manager161 tests passed after applying the existing patch. Prior local implementation gates are documented separately.
- Target: JavaRailwaysTest, MCSManager instance836082a48c4f48fcb1fea4e83c57dc96 on192.168.1.230.
- Online players0 before stop. Normal MCSManager stop; status0/PID0 and all-world/player save completion observed.
- Recovery: /opt/mcsmanager/backups/javarailways/station-programs-20261022-577ibrdo; plugins-and-data.tar.gz SHA25659d1799d2fcdd4a68cd079371514a75599c847bde7eccea3bfdce7d5b4540c87. Contains both plugin directories and previous JARs, manifest and install audit. No world-data migration.
- Installed JavaRailways.jar SHA256af04cd96d88912a3205ba74316495fa6f502b304b6841eee0a764a0b6d63610e.
- Installed SimpleSignals.jar SHA256eea60c9f6ff273999dc371637e1b0a9f6a566813fec13ae6ad041ba60d0bdd82.
- Installation preserved plugin data and every other plugin JAR byte-for-byte.
- Normal MCSManager restart completed. At16:14UTC: ATO world RUNNING; no world-load errors; SimpleSignals inspect installed revision46, topology1, regions/tables/blocks6each, fixed/protection signals2each.
- Bridge health200/ok. WebUI200; extracted sidecar and index/assets match deployed build exactly (index-B-SiDn2y.js, index-DsCDzRaI.css).
- JavaRailways/SimpleSignals error files contain no new ERROR/SEVERE entries. Existing Terra ReflectionUtils sun.misc.Unsafe message remains; plaintext WebUI and one startup physics overload warning remain. No fixes attempted for these unrelated observations.
- Remote live-verification.json retains exact checks. Deployment health does not establish interactive game/client acceptance of all station operations. Further changes deferred per user.
