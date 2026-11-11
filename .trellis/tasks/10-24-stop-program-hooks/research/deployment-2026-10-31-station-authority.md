# Station authority correction deployment — 2026-10-31

User explicitly authorized commit and deployment. Product commit `d57b899fa392be3d246d1b237d999c3a619fda4e`; no push, task closure or archive. Existing unrelated heap dump preserved.

Station nominal stopping target has no extra5cm advance. Real station authority/route covers nominal stop+0.3m; independent red/resource constraints retain default5m. No production changes outside SimpleATO and no persistence/schema migration.

Validation: earlier five-module gate2997pass/1expected opt-in skip; after final removal of5cm advance, focused SimpleATO101pass and isolated Bukkit39pass including8 actual shipped-S100 short lawful suffix cases. One zero-reaction Kinematic fixture corrected to0.1s lookahead without weakening assertions. Independent review and diff checks passed. Historical accuracy CSVs explicitly belong to earlier candidates; not final-target accuracy claims. Packaging shadowJar passed offline; development server consumes Mojang-mapped all.jar, no failed/stale reobf artifact used. Four deployment backup/rollback/guard tests passed.

Live baseline mainSHA256 `41d96b900a86c489bcc5a71b1ea92204372edfc04277049471b62151d8f835d5`, originalPID783432. Normal MCS stop at13:10:50UTC confirmed Saved all worlds, status0/PID0, original JVM gone and listeners closed. New model-snapshot file was root-owned0600; initial unprivileged backup failed before archive/install. Retried with existing sudo management privilege, preserving original permissions.

Complete154-file recovery: `/opt/mcsmanager/backups/javarailways/station-authority-20261031-4rici9s4`, recovery archiveSHA256 `0e53ccddf5e19749d0d9382fde1e70f592c084a2431db7c02e43868edfb6b4fb`. Only plugins/JavaRailways.jar replaced13:11:52.930965UTC;153other files byte-identical at cutover. New mainSHA256 `47e2ab7029a3c8e0d4aa86514d9326df80af5f548a050f124c57b8e89b772a3e`. Embedded/managed WebUI unchanged `5dfd7cc7b21558751c19b93c09c7b61bba4737025efd36f636bc1acd09cd3b7d`; SimpleSignals unchanged `0346443a02bfd0a7a14a11cd6b3893e4c321005e732fc7db5e427a336f35ac39`.

NewPID785446, Done13:12:29UTC. Read-only console: ATO RUNNING; SimpleSignals installed, definitionRevision117/topology1. Bridge health passes,17local/public resources match embedded bytes,31map markers,23draft/signal/otherJAR files unchanged after start. Workspace independently confirmed publicHTTP200 and actual trains SSE frame. An initial incorrect /trains/stream address returned401; corrected per source to /rail-map/trains and obtained200 event:trains.

Project errors since install0. Old-version shutdown emitted known JR13/Signals1cleanup errors; third-party Terra theUnsafe startup error still1. Initial startup overload/delta-clamp warnings observed; no claim these pre-existing issues are fixed. No live authenticated simulation or game parking acceptance performed.

Local/remote stage `/tmp/jr-deploy-20261031-station-authority`; machine evidence saved beside this note. Rollback: normal save/stop, preserve any newer player edits in a new recovery point, restore matching old mainJAR from verified recovery; do not overwrite newer world/data edits. Task stays in_progress pending game acceptance.
