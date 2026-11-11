# Foundation checkpoint and current B3 work

Worktree: `.worktree/station-operation-programming`, branch `codex/station-operation-programming`, base `a0206a9ca9698e0bc2cbd3f7dc149f02463feeb2`. Prior dirty UI/backend baseline is preserved by the implementation-baseline manifest. No commit/deployment.

Before the subsequent retained-route, EXTENSION and B3 changes, a serialized affected-module checkpoint passed: Core 505, Basic 395, SimpleATO 402, BukkitPlugin 551, WebUI 19 tests; all failure/error/skip counts zero. Frontend at that checkpoint passed 132 tests/24 files and TypeScript/Vite build. Do not apply those counts to later edits.

The broad gate found two actual integration issues and they were fixed before the checkpoint: direct service start had not bound the Dispatcher world for stopping-area lookup; acquisition followed by a wait was mistaken for a missing departure Mission. The latter is covered by actual depot/waypoint acquisition, relative/absolute waits and on-time/late starts. A native-editor visibility test was updated for its new actual station fields. Concurrent Gradle builds caused transient class loading failures; all later gates are serialized.

Browser: separate preview at 127.0.0.1:5181 and named `station-program-ui` browser session. The Ant shell/program page, add-step control and HTTP stable UID generation were inspected. Screenshot `/tmp/station-program-ui.png`. Demo explicitly rejects shared-library persistence, so this is visual/basic editing evidence, not authenticated persistence or game acceptance. User's existing 5178 preview was not replaced.

## Subsequent work: evidence is scoped

- Retained route foundation: validator retains exact candidate origin/direction/leg evidence, movement consumes it, station resolution uses retained leg prefixes. Explicit stopped replan remains its own validation boundary. 116 focused cases passed before a further feasible-direction correction; that correction needs its own gate.
- EXTENSION/JSON_OBJECT and readonly bound-step navigation: frontend 141 tests/26 files, build and diff check passed. Backend roundtrip/strict validation tests are pending the serialized gate.
- Depot staged structural ledger: 21 focused tests passed (cross-depot6, unit ledger10, formation change5). These use exact formation membership values and validate ownership conservation, no partial mutation, whole-unit boundaries and before/after publication recovery. They do not alone prove physical structural execution.
- Core host-derived contact proof and post-publication movement fences: focused actual straight/curved coupling and split tests passed. A new exact stopped-formation restoration fence and Basic prepared source-grant freeze/observation stage are being tested separately.
- Root added actual Core save/load before and after physical split, plus SimpleATO recovery fencing integration; this new integration test is not yet run.

## Remaining gate

The approved full feature remains in progress. No scheduled structural event executor, admitted contact movement/aggregate output clearance, branching state machine, circulation validator or final integrated game acceptance is claimed complete. B3 must not be represented by raw Core link/split calls bypassing Basic. Recovery records retain original claims while Core contains either exact sources or exact outputs; fresh Core movement holds are re-established before dispatcher clocks. No live token is serialized and no physical operation is replayed.
