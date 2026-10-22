# Research: Deployment preflight

- Query: Identify exact deployment target, access/control workflow, current installed lineage and concurrent changes before authorized commit/deployment.
- Scope: internal and live read-only server inspection
- Date: 2026-10-22

## Findings

Target is JavaRailwaysTest on `user@192.168.1.230`, instance `836082a48c4f48fcb1fea4e83c57dc96`, root `/opt/mcsmanager/daemon/data/InstanceData/836082a48c4f48fcb1fea4e83c57dc96`. SSH requires existing host agent: `env SSH_AUTH_SOCK=/run/host-ssh-agent.sock ssh -o BatchMode=yes user@192.168.1.230 ...`; default agent failed authentication. No new credentials created or exposed.

Live status read returned MCSManager status3, PID602412. Live installed SHA256:
- JavaRailways.jar: `1c9ee56713bc0341bbb4c0a129c2dd91b68f42758de3b33494e0f412be41b765`
- SimpleSignals.jar: `404ce959cfd6f61b0381e8f12adeed69653eedf05f707863bd0c61c940dfde00`
- TunnelBridgeBuilder.jar: `12f7732f244f873870a5f4eecb02e27e5cf21843ee6bf4daebb64c7c35065448`

JavaRailways matches the prior reset fix manifest. SimpleSignals is a separate parallel deployed fix: remote `/opt/mcsmanager/backups/javarailways/signal-issues-20261021-awzpfrmq/{source.patch,provenance.json,install-audit.json}` shows base a0206a9c with only `SignalPackEpochRuntime.class` replaced. Source hash `fca0d592e08b89a6cf4038991e696854877b27b9847d1e4c57e11f53ebef3cd2` differs from station worktree `dd4abdf63e39da4990863852faceff3a5cca2ac0484b541064444b61e8ff73c3`.

Exact deployed source delta at SignalPackEpochRuntime.kt:136 replaces `else if (next is SectionSignalState.Available) issueChanged(deviceId, null)` with `else issueChanged(deviceId, null)`. This clears historical world issues on normal restrictive waiting while retaining unavailable/zero-speed safety state. Patch also includes test `startup and script rule issues clear when a current entry returns to normal restrictive waiting` and js-signal-rules spec paragraph. A new full SimpleSignals JAR must retain this fix; main session informed.

## Files found and code patterns

- `/tmp/jr-mcs-control.cjs:3-13`: reads daemon global.json credentials in-process, authenticates local socket.io, supports status, command, stop, open. Never print global.json. Remote copy does not exist; pipe local script over stdin to `/opt/mcsmanager/node-v20.12.2-linux-x64/bin/node`.
- `/tmp/jr-reset-deploy-20261021.py:10-59`: prior template checks no JVM in server/sidecar cwd, validates installed/candidate hashes, snapshots plugin data and installed JAR, verifies archive/inventory, preserves file ownership/mode and atomically replaces only target JAR.
- `/tmp/jr-reset-deployment-manifest-20261021.json`: existing JavaRailways lineage from base a0206a9c plus TopologyRoutePlanner Reset fix and prior UI sidecar.
- `/tmp/jr-signal-deploy-20261021.py`: signal-specific recovery and named-artifact replacement template.
- `.trellis/spec/webui/backend/http-repository-packaging.md:44-57`: actual frontend build required, validate nested WebUI JAR and every referenced asset, then live HTTP200.
- Task prd/design/implement read; research.jsonl absent. Role-isolated implement/check manifests not opened.

Control pattern: set JR_ACTION=status, command (JR_COMMAND=list or normal save/status command), stop, or open in remote environment and pipe existing script. Use normal stop, confirm process exit/save logs, make recovery archive, hash-guard replacement, then open and live health/log verification. Only status was executed by this research agent; no command/stop/open/build/upload executed.

## External references

No public external documentation needed. Existing MCSManager deployment scripts are authoritative local integration evidence. Node runtime path is v20.12.2.

## Caveats / Not Found

Read-only JSON traversal under plugins/JavaRailways found no type/kind strings containing conditional/couple/separate/formation/select_stop. This limited shape scan is not strict decode/restore validation; scripts or other representations may contain relevant behavior. No deployment mutation, build, player count command, stop/save or game acceptance occurred. Installed hashes and PID are a point-in-time snapshot and must be rechecked during install. Preserve parallel SimpleSignals fix, other plugin JARs and plugin data. Main owns deployment and commit.
