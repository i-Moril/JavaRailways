# Display integration deployment — 2026-11-10 UTC

## Source and scope

- Display/style/assets commit: `22025409`.
- Test-room baseline was JavaRailways `e5809088` (SHA256 `4051ce76e493b0926ca7766168c9cc9269b242202768ee706d753321c888857e`). Its three missing commits (curve endpoint correction, derailed-run isolation, incomplete junction preservation) were merged, without conflicts, into the display branch as `a7dbe621846c6b20415a004f7f68fece5727ffce` before rebuilding. No deployed fix was intentionally downgraded.
- SimpleDisplays source/API revision: `410af2e`; clean source rebuilt as a deployment JAR, no library source edits.
- Build: `/tmp/jr-displays-merged-build.log`, production and test-source compilation plus host shadowJar and Decorations verifyPluginJar passed (29s). SimpleDisplays jar build passed (7s). Unified tests remain deferred by user decision.
- Scope: 39 replaced/added files: JavaRailways, Decorations, SimpleDisplays, S100 display/schema sources, independent cabin style, config.zip and client resource pack. Seven unrelated installed plugin JARs retained and verified.
- Mounted resources: two front exterior and thirty interior door LCDs per five-car composition. Existing 8:3 side template is present but its generated screen nodes are not yet mounted. The mistaken 2:1 standalone template was removed; its former geometry is merely two halves of the same interior housing.

## Recovery and install

Target `user@192.168.1.230`, MCSManager JavaRailwaysTest instance `836082a48c4f48fcb1fea4e83c57dc96`, Folia26.1.2-8. Existing authenticated manager control helper was reused without exposing credentials.

Zero players before maintenance. Normal stop completed all world/player saves at16:38:32; manager status/PID became0 and prior Java PID1012702 exited. No process kill or timetable reset.

Recovery directory: `/opt/mcsmanager/backups/javarailways/displays-20261110-ocm2dm0l`.

- `recovery.zip`:160 files, installed plugin JARs and complete JavaRailways/SimpleSignals/TunnelBridgeBuilder plugin data (including railway snapshots); not Minecraft region files.
- SHA256 `fca8df8f534d64211bbfd341362430717f004158192917db386a53bcd3a8def5`.
- Membership, every archived byte hash, and source stability verified before cutover.
- Atomic install checked candidate and old baseline hashes; full inventory matched the expected39-file delta before restart.

Server resource pack was patched from its actual existing archive, retaining custom assets and pack metadata/overlays rather than replacing it with a generic example pack. Display font/backing and LCD texture references were added. Server `resource-pack` property was empty and remains empty: clients must load the updated pack manually. Local deploy-specific copy: `/tmp/jr-displays-deploy-20261110/resourcepack-after.zip`; installed SHA256 `0504698bdc4f6682a16ddfc1a8af51dbf34e5ef1c9ce6c577dc9cea0b9b8a4c4`.

## Geometry correction during activation

Initial resource reload correctly refused S100 because local example cars use length17.8/connectors0.1, while existing server cars use17.2/0.4. Root had checked control/behavior but initially missed car geometry. The transaction retained existing trains and model bindings.

Corrected all six server car source files from their hash-verified original server definitions, changing only rotated door-display imports. This preserves original geometry and all other control fields rather than rebuilding trains or weakening geometry validation. Updated config.zip accordingly. Seven correction files were separately backed up as `geometry-before.zip` under the recovery directory, atomically replaced, and recorded in geometry-fix-plan/install.json. No world snapshot was manually changed.

At16:42:38, public `jr resources reload` succeeded:1 train package,3 rail packages, no rejected type. Server-specific physical parameters deliberately differ from repository examples.

## Observed result and limits

- Folia startup `Done (28.591s)` at16:40:02; new PID1028217.
- SimpleDisplays and Decorations enabled, service connection available; real compiler accepted installed external/package XML and JS syntax.
- JavaRailways follow-package policy and EntityLib display backend active. Initial no-provider preservation log occurs before Decorations enable; final transaction rebuilds bindings after registration.
- Bridge8765 and WebUI29002 loopback health HTTP200; ATO world RUNNING at16:43:24.
- Final audit verifies all39 effective deployed hashes and7 retained plugin JARs; no error after successful corrected reload. Existing Terra Unsafe reflection error and startup physics clamp warnings remain unrelated.
- External public-URL probe did not establish reachability; do not equate loopback HTTP health with public ingress acceptance.
- No player was online, so actual dynamic frame rendering, font loading, physical orientation/fit, translated pages, distance/style commands and movement/turnback behavior were not visually accepted. No full test suites, Paper/native-backend matrix or plugin-replacement lifecycle acceptance claimed.

Audit/staging directory on both local and remote: `/tmp/jr-displays-deploy-20261110`. It contains source/old/new hashes, immutable candidates, manager helper, recovery receipt, original/fixed config plans, install receipts and post-deployment audit.

Rollback: normal-stop this exact instance first. Restore the old JARs and matching source/config/resource files from the verified recovery archive; remove additions listed only in the install manifest. Preserve later world snapshots unless an explicit full plugin-data rollback is intended. Review any new metadata before reverting to old readers. No Git push.
