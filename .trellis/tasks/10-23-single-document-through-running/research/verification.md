# Verification — 2026-10-23

Worktree: `.worktree/station-operation-programming`; branch `codex/single-document-through-running`; base `f045499d`. Uncommitted implementation; deployed 2026-10-23 after user authorization, see deployment-2026-10-23.md.

## Automated gates

- Frontend full suite: 164 passing; production build passed.
- BukkitPlugin full suite: 585 passing.
- WebUI Java full suite: 28 passing.
- Final SimpleATO full suite: 591 passing, zero failures/errors/skips. Final combined JVM gate completed successfully (2 minutes).
- JVM command: `./gradlew --max-workers=1 --continue -I /tmp/jr-retained-forked-tests.gradle :SimpleATO:test :BukkitPlugin:test :WebUI:test`.
- The temporary test init script bounds SimpleATO worker heap and runs one test class per fork; it does not replace test assertions or production implementations.
- `git diff --check`: clean.

## Targeted behavioral evidence

- Real isolated physics: A→C→E and E→C→A, one physical train, stopped connection, distinct line caps affect travel duration, stable repeated simulation and unchanged source world.
- Registered complete-unit identity through couple/split; display snapshots are looked up by actual unit identity, including held output trains.
- Compiler-created operational-trip boundary before station display programs, including precise step-range/source-trace adjustment.
- Cross-service physical continuity, range and canonical call validation, JSON bounds, display reset and conditional future projection.

## Local browser

Demo on `http://localhost:5182/`: created T-E, associated AP101/AP102 slot 1, edited destination/category defaults, invalid JSON disabled Save draft, undo/redo restored defaults, full page reload retained group and defaults via local recovery. Existing Ant Design appearance retained. This checks local recovery, not authenticated backend saving/publishing.

## Remaining acceptance boundary

Remote deployment health verified; no live Minecraft/Paper/Folia client feature acceptance. Shared signalling, manual intervention and door-ticket combinations remain covered only to the extent of the existing automated module suites, not a new exhaustive game scenario matrix. LCD device rendering is outside this feature.
