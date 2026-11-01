# Running graph styles deployment — 2026-11-01

User requested commit and deployment. Product commit `d561c3f767630f9b8780953e49a3ca70c7d34906`; no push or task archival. Unrelated `java_pid454886.hprof` retained.

Running graph paths are thinner (1.25px normally, 2px selected, 1px dimmed). Run options allow a per-run color and reset to automatic; saved optional authoring color does not affect simulation semantics. No schema migration.

## Validation

427 frontend tests and 68 WebUI JVM tests passed; TypeScript/production frontend build and Bukkit shadowJar passed. Deployment recovery/rollback guards: 4 tests passed. Local HTTP browser fixture verified two independent run colors, automatic swatches, themes, reset, undo/redo, save and reload. Native color input events were exercised; the OS picker dialog was not automated. This fixture does not prove live game acceptance.

## Deployment

Target JavaRailwaysTest, MCS instance `836082a48c4f48fcb1fea4e83c57dc96` on MC. Baseline main JAR `e00aaa9c06b8a2494a36467b4ab34a879c1e56d514a3148c5054799972a0c2c1` retained the latest equipment-model deployment. ZIP entry comparison confirms only `webui/JavaRailways-WebUI.jar` changed in the main JAR.

Normal shutdown saved all worlds at 08:01:42 UTC. Original PID811628 exited; MCS status0/PID0 and closed bridge/WebUI listeners were checked before mutation. Recovery verified all154 plugin data/JAR files at `/opt/mcsmanager/backups/javarailways/graph-colors-20261101-62a7w99i`; archive SHA256 `6049d20234727e41079c6e8a8c0363bdc93cf457504d8503f6ea6cc2c7d16e1b`. Recovery content remains server-local. It is a plugin-data backup, not a Minecraft region-file backup.

Atomic installation at08:02:06 UTC replaced only the main JAR and preserved153 inventoried files byte-for-byte. No model/configuration or save migration. New main SHA256 `ef04282cfcf1786f23002dfd5a566c32b2a29f911ff87ec237a31cef7d963ea7`; embedded and managed WebUI SHA256 `4995639a5206aaf3f59eabbe01e0b87d59836ec4617f598db07c49af6efb36cf`.

New PID814147; Done08:02:36 UTC. ATO RUNNING; SimpleSignals unchanged, definitionRevision117/topologyRevision1,14 blocks. Bridge healthy; all17 local/public resources match candidate bytes. Public homepage/script200; public `trains` SSE contains a JSON array. Map31 markers; guest simulation401 with structured error.23 draft/signal/other-plugin files separately verified unchanged after startup.

No post-install ERROR/SEVERE in the checked JavaRailways, WebUI and SimpleSignals logs. Pre-install shutdown produced13 JavaRailways and1 SimpleSignals errors, recorded separately in the verification summary. Existing Terra `theUnsafe` startup error and5 JavaRailways warnings remain. No live authenticated editing or game-client acceptance claimed.

## Recovery

Remote staging `/tmp/jr-deploy-20261101-graph-colors` retains deployment receipts and MCS control helper. Guard/backup/install driver and tests accompany this record. Rollback requires normal save/stop and a new backup of subsequent edits, then restoration of the pinned prior main JAR from the recovery archive. Preserve newer player data rather than blindly restoring all saved files. Existing development task remains open.
