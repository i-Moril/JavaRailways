# Withdrawn Batch D (2026-10-28)

User rejected the extra HEAD/TAIL selection as unnecessary functionality and UI complexity. It was not a separate item in the original feedback list; repeated existing one-into-two splits provide composition without a new selector. Withdraw the Batch D implementation, tests and current-behavior documentation. Do not retain compatibility paths or mark the extension as a future task.

Preserve Batch B/C duty-owned formation authoring and manually created receiving duties. Restore from the SHA256-audited prior baseline `/tmp/jr-formation-before-tail`; D-only tracked code files return to HEAD. Keep the original Trellis task in_progress; no commit/deploy/archive.

## Rollback verification

- 35 non-task baseline files match SHA256 exactly; the remaining studio-authoring spec only replaces the future TAIL plan with repeated-split composition. All SimpleATO files and worker Model.kt match HEAD. No added compatibility or special rejection paths.
- Independent trellis-check verified exact restoration and preservation of B/C, without claiming a new full B/C review. PRD, design and implement records now follow the latest user decision.
- Frontend269 tests and TypeScript/Vite build passed. Backend gate initially stopped due Gradle daemon GC thrashing at512MiB; retry uses temporary -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=1g', no repository configuration change. Backend retry passed (exit0): WebUI41 and BukkitPlugin641 tests, zero failures/errors/skips. Final git diff --check passed.
- Recovery backup: `/tmp/jr-rejected-tail-extension/restore-manifest.json` records25 restored files and hashes; original contents in files/ and before-tracked.patch.
