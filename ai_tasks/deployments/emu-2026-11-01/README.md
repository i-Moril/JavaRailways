# EMU equipment and door deployment — 2026-11-01

User explicitly requested commit and deployment. Product commit `d81f17e7`; preceding array experiment/TODO commit `e00c5441`; deployment migration tools `9ca034b9`. No push or task archival. Unrelated `java_pid454886.hprof` remains untouched.

Target: existing JavaRailwaysTest on MC/user@192.168.1.230, MCSManager instance836082a48c4f48fcb1fea4e83c57dc96. Live baseline main SHA256 `18b2f5d79197c1c7c01d4ac74b833355afb7c74f598f5d0d67257e3210a8ab25` corresponded to835bd940, so the previously committed door work was also included. Existing host SSH agent used; credentials stayed in their agents/config readers.

## Gates and scope

- Source gate:3151 JVM tests passed,1 existing skip; public consumer compile,Core/Basic Dokka,shadowJar passed. Final candidate byte hash remained pinned throughout provenance checks and deployment.
- Final deployment migration suite19/19 (actual installed sourceJAR and candidate codecs); author-audit4/4; driver guard/rollback18/18. Driver tests exercised stopped PID/MCS/listeners, baseline/plan/archive mismatches, source changes, migration/artifact failures, permissions, created-file ownership and receipt failures. Unknown/foreign new files are retained with an explicit incomplete-rollback failure.
- Both before and after normal stop, all8 author documents validated with actual old/new WebUI codecs. Valid minimal probes demonstrated that helper processes worked. No numeric preparation leads,compiled timetables,standalone timetables or generatedJS required conversion;12 runtime primary/fallback files inspected. Author documents remained unchanged.
- Equipment migration changed4 files:one package snapshot renamed/rehashed and two runtime files updated,12 typed references. Six car-model snapshots were validated with unchanged hashes. Known exact default S100 behavior from the older frozen snapshot was upgraded alongside equipment rehash;controls/actions were preserved. No runtime compatibility or special old-format rejection was added.
- Live six-car YAML did not match repository model files; all were preserved. New optional equipment fields use their explicit defaults. Only installed mainJAR and exact known defaultS100 behavior.js were replaced;other plugins/configuration retained.

## Stop, recovery and installation

Online count0. Normal MCS shutdown saved worlds at05:38:47UTC; status0/PID0,original PID794592 gone and ports8765/29002 closed before mutation. Final stopped-source audit/dryrun matched the preflight counts.

External server-local recovery: `/opt/mcsmanager/backups/javarailways/emu-20261101-l3k5r_qn`,154 plugin data/JAR files,archiveSHA256 `cadff1fef6a2464c13c1b6d394e8db004c670f31c803ed59c5c0a6fd717e535e`. Complete recovery inventory and archive bytes verified before applying. This is plugin data recovery, not a Minecraft region-file backup.

Install completed05:42:07.621490UTC. Main SHA256 `e00aaa9c06b8a2494a36467b4ab34a879c1e56d514a3148c5054799972a0c2c1`;S100 behavior `bda9dc43a030c7ad11b750c09b938e0744df3bbadd2573513294d2328eaed3c3`. Migration converged under actual candidate codecs.149 original files remained byte-identical at cutover. Source modes/ownership retained.

## Runtime verification

New PID811628; startup done05:43:31UTC. ATO confirmed RUNNING at05:44:08 and again05:45:28UTC. SimpleSignals remained byte-identical SHA256 `0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39`,installed definitionRevision117/topologyRevision1 with14 blocks.

Bridge health passed. Embedded/managed WebUI SHA256 `89433e50eb7173fb63048a6073bebd89ab1d5505bc621fbb5df496f727145349`;all17 local/public HTML,JS,CSS/Blockly resources matched candidate bytes. Main script `/assets/index-DZoECBD4.js` includes the new doorCloseTicks contract;map31 markers.35 draft/signal/custom-model/other-plugin files separately verified unchanged after startup.

No new ERROR/SEVERE in JavaRailways,SimpleSignals or TunnelBridgeBuilder logs. JavaRailways had4 startup warnings from existing WebUI HTTP exposure settings and physics coordinator timing;unchanged third-party Terra theUnsafe startup error observed. No authenticated editor save,live function simulation,game-client driving or visual acceptance claimed.

## Operational evidence and rollback

Remote staging `/tmp/jr-deploy-20261101-emu` retains manifests,stopped dryrun,source/candidate codecs,helper receipts,prestart offsets and runtime-verification.json. Driver/test source is preserved beside this record. All save content and recovery archives stayed on the server. Automatic review refused exporting live stores; remote-local-only audit/migration was used. A delegated code upload was initially refused for insufficient authorization evidence; after the main session provided the direct current deployment instruction and verified existing target, the same upload action was approved.

Rollback requires normal save/stop and a new recovery point for post-deploy player edits,then a coherent restoration of matching JAR,behavior,snapshot hashes and affected runtime references. Do not replace only the oldJAR or overwrite newer unrelated data. Existing development task remains open for game acceptance.
