# Fixed units, per-RUN trips and actual-run display gate deployment

User authorized commit and deployment2026-11-11. Source commit `be8b75ec9cc2ba9a084169849d4f7a000328b2c5` includes the prior fixed-unit/inventory-removal commit `4b6583ad` and stable-ID cleanup. No push.

## Target and artifacts

JavaRailwaysTest, `user@192.168.1.230`, MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96`, Folia26.1.2-8, Java25.
Instance root: `/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`.
Previous main artifact matched the recorded `46d72935fbff28f54030df0ea090f2a8a6aac6a4` build exactly; this commit is an ancestor of the candidate, preserving its Paper/Cloud/GentleUnload fixes.

- `plugins/JavaRailways.jar`: old SHA256 `86d90bcb4d2352d5f5e5e065ab74b95fb284c296c88187032d4b42ba8f8f0b86`; deployed SHA256 `e7902cd3551e64bb28c7e5a70923af55c2a21b87918c5682a5df49268cf128e9`.
- `plugins/Decorations.jar`: old SHA256 `595700717e1f976d8de3b5983b35fc9302e46b33c10e6ec89b52bfff247ee1f4`; deployed SHA256 `a5b3c8ff7e07126402fcbdb0c6d52a3a87c2a950f11ccfd6314ea0d2fa066e0f`.

Fresh main shadowJar and Decorations jar/verifyPluginJar passed (`/tmp/jr-trips-deploy-build-20261111.log`). The deployment uses the Mojang-mapped shadow JAR and retains `paper-plugin.yml`. Existing PacketEvents2.14.0, PlugManX3.2.1, SimpleDisplays, SimpleSignals and other JARs were retained and hash-checked. 85 vehicle/rail package, Decorations and SimpleDisplays configuration/resource files remained byte-identical.

## Offline conversion and recovery

Actual legacy `rollingStock` and `fixedUnits` tables were empty in every current/backup runtime file. The one-shot converter removes only these empty obsolete fields from12 runtime files (6 worlds, current and .bak); it rejects nonempty values, is idempotent, checks source bytes, defaults to dry-run, and writes only a new independent output directory. Core train IDs, cars, positions, checkpoint state, infrastructure files, revisions and epochs are unchanged by this conversion. No schema version increase or runtime compatibility reader was introduced.

The converter and its report are retained in the recovery directory as `remove-empty-ledger.py`, `stopped-conversion-dryrun.json` and `stopped-conversion-output.json`. Its output was overlaid on a complete local copy, then validated through the existing offline strict decoder and actual Core→Basic→SimpleATO restore. The endpoint-mode validator produced identical source/target hashes for all24 files; no rail transformation was applied. Online-copy and final stopped-copy restore checks both passed. Negative nonempty-ledger checks and repeated-conversion no-op checks passed.

Normal MCSManager stop began16:17:32UTC, with completed world/player save and confirmed process exit (old PID1052245). No timetable reset, placement, resume or discard command was run.

Full recovery: `/opt/mcsmanager/backups/javarailways/trips-be8b75ec-20261111-u5ogze4v`.
`recovery.zip` contains198 files (all installed plugin JARs and the full selected plugin data directories); archive membership and every file hash were verified, then source stability and exact installation delta were checked.
Archive SHA256: `f9e93fc8a8b1dd0e4d2c1cd40d0742ed21ed28de334fd23321345c31cef27fba`.

The install replaced exactly14 files:2 JARs and12 converted runtime documents. Installer restores changed files from the verified archive if installation fails. For a later rollback, normal-stop first and restore matching old JARs **together with** their saved plugin data and original metadata from `backup-manifest.json`; never boot the old reader on newly saved current data. The backup directory also retains the final install manifest, script, receipts and health results.

## Observed result and limits

MCSManager restarted normally, new PID1056336/status3; server Done at16:20:17UTC. JavaRailways and Decorations enabled; Decorations found the SimpleDisplays API service; SimpleSignals enabled its real-world route/occupancy runtime. Bridge8765 `/health` and WebUI29002 `/` returned HTTP200. The existing console command reported `ATO world: RUNNING`.

At16:21:57UTC the new plugin's automatic runtime save (16:21:48) contained all5 original trains/25 cars, each with one Core unit reusing its unchanged train ID; all5 dispatcher checkpoints remained. The5-service timetable contains16 declared RUNs and16 independent default operational trips. Obsolete ledger fields are absent. No train-restore, dispatcher rejection or affected-plugin load failure appeared. Existing Terra `theUnsafe` and TruffleAttach warnings appeared in both previous and new startup logs; no changes were made for them.

Code validation before this submission:1048 related domain/adapter/display tests passed, plus prior UUID Core34/Bukkit6 tests, independent review and diff checks. The previously recorded unrelated full-host failures were not reworked or described as passing.

The extra authenticated HTTP inspection requiring process-token access was rejected by automatic approval and was not executed. Verification instead used existing console/logs, ordinary health endpoints and actual newly written runtime files; no credentials were extracted.

This confirms deployment, restoration and server-side health. Game-client train movement, in/out-of-RUN screen behavior and visual rendering still require acceptance. The parent task remains in progress.
