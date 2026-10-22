# B2 registered actions / source navigation checkpoint

This adds to the sequential-library checkpoint, not completion of the full task. All product/test files are frozen for independent review/browser checks. No commit or deployment.

## Changes

- EXTENSION step binds a static namespaced typeId/version and an entire JSON_OBJECT expression. Current registered descriptors populate the selector; schema is shown as advisory data. No installed providers are invented. Existing SimpleATO registry/provider validation remains authoritative.
- JSON_OBJECT supports nested JSON literals or whole-object parameter binding only. Empty object{} is valid. No recursive substitutions or arbitrary JS. Java/TypeScript/Kotlin enforce the same finite values, safe integral numbers and depth16/node4096/string8192 limits.
- Numeric content identity normalizes1/1.0 and signed zero after validation. A real HTTP editor-document save covers server record -> browser-normalized numeric JSON -> server snapshot with the original identity. Unsafe integral values are rejected rather than rounded silently.
- Generated data uses JSON.parse(quoted JSON), preserving special keys such as __proto__ instead of invoking JS object-literal prototype semantics.
- JSON text remains local until blur/Ctrl+Enter, commits once across subsequent blur, rejects invalid drafts and discards on Escape. Identity/unmount guards prevent stale commits.
- Current document/trace origins navigate to a call and readonly bound step. An explicit Open library button focuses the shared editable node, keeping adoption a separate document transaction. Old traces, removed steps, different calls and prefix collisions are not guessed.
- Independent review fixes: timed program builders pass their captured Tick; expression raw keysets are checked before nullable Kotlin decoding, so value+parameterId:null is rejected consistently. Compiler step failures retain current call/program/step context.

New files since the sequential checkpoint: ProgramJsonValidator.java; ProgramJsonInput.tsx; ProgramJsonInput.events.test.tsx; programNavigation.ts; programNavigation.test.ts. Existing library, validator, generation, compiler, types, UI and tests are extended in place; no SimpleATO runtime edits by this implementer.

## Actual checks

Frontend:141 tests in26 files pass; TypeScript+Vite build passes; git diff --check clean. Existing large-bundle warning remains. Tests add bounded JSON/nested literals, whole-object binding, special keys, raw event-boundary commit/Escape/dedup and current/stale origin resolution. Event-boundary tests model the public Ant callback interface; they are not mounted-browser evidence.

Serialized backend command (bounded1g, one test fork):

```
./gradlew -I /tmp/jr-program-tests-heap.init.gradle :WebUI:test :BukkitPlugin:test \
  --tests '*StationProgramHttpTest' --tests '*VisualProgramRepositoryTest' \
  --tests '*StationProgramScriptTest' --tests '*ScriptWorkerCompatibilityTest' \
  --tests '*DefaultInfrastructureResourceServiceTest' -x :WebUI:installFrontend
```

Build passed in49s. XML results: WebUI20 tests (including3 StationProgramHttpTest +4 VisualProgramRepositoryTest); Bukkit40 tests (8 StationProgramScriptTest +1 ScriptWorkerCompatibilityTest +31 DefaultInfrastructureResourceServiceTest). Zero failures, errors or skips. The requested service tests include root-owned formation forwarding/disabled/stale-world guards; this note reports their results without claiming ownership of those edits. Gradle slot was released to ui_check/root for their separate SimpleATO/recovery gate.

Real GraalJS tests cover nested payload and current snapshot restoration, exact type/version and stable origin, special keys, unsafe/nonfinite/nonobject/resource-limit rejection, captured Tick, strict nullable-key rejection, and no partial program expansion.

## Boundaries

No live library/browser/client acceptance, installed action provider, simulation or game result is established by this gate. Actual world provider validation/missing-provider behavior uses existing domain authority; the new compatibility test proves correct action transport/restoration and exact missing registry lookup, not a new world integration fixture. Parent owns the latter follow-up and broader module gate.

B3 coupling/separation authoring remains read-only contract research until its runtime/DSL contract is settled. No conditional/structural controls or second editable bound-snapshot owner were added.
