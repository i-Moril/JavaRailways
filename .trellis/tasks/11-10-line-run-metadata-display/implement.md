# Execution

- [x] Add common bounded immutable JSON policy and line/run model/snapshot fields/deferred cases.
- [x] Add line+run JSON editors, wire types/Java validation/generator preservation/deferred cases.
- [x] Wire host line metadata HTTP and run DSL input; capture exact current run and world line index to screen.
- [x] Compile JVM production/test source and frontend type-check only; static consistency review.
- [x] Record docs/spec/task status and deferred unified acceptance, no commit/deploy.


## Implementation record 2026-11-10

Line/run default{} metadata implemented across authoritative SimpleATO model/snapshot and worker mirror DTO, JSON editors/authoring document/generator/Java validation, host line wire+DSL allowlist, exact active-run capture and complete world line index. Added deferred domain/default/immutability/revision/codec, UI lifecycle/generator, actual dispatcher capture, host DTO/projection/cache and worker mirror compatibility cases.

Compiler found worker's separately owned SavedTimetableRunSegmentV1 mirror; fixed mirror field rather than introducing domain-only linkage into the worker. Authoritative restore enforces JSON budget, worker preserves exact data shape. JVM source/test-source final compilation passed23s: /tmp/jr-line-run-metadata-compile-final.log. Frontend TypeScript build/typecheck passed exit0: /tmp/jr-line-run-metadata-typecheck.log. git diff --check passed. No test suites, browser/Vite packaging, server/Folia/client validation, commit/push/deploy. All prior dirty work retained. World line index sampled once per fresh world input; existing aggregate guest input limits remain. Parent unified acceptance still outstanding.
