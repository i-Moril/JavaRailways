# B2 shared library / sequential authoring checkpoint

Implementation is limited to the agreed sequential subset. The complete five-batch feature is not complete. All changes are in the independent station-operation-programming worktree; baseline UI rename/cap, diagnostics/map and Basic/HTTP fixes are preserved. No commit or deployment.

## Implemented contract

See library-transport-contract-2026-10-22.md for exact method/path/status, immutable snapshot and typed AST contracts. New library records use initial formatVersion1; editor documents/recovery gain additive optional boundPrograms with empty default. Existing format/version values remain unchanged; no arbitrary legacy reader or migration is introduced.

Authenticated world-scoped records have stable IDs, positive safe revisions, expectedRevision writes, explicit archive and canonical SHA-256 content identity. Same-process repository synchronization covers read/check/atomic replacement; it is not a multi-process shared filesystem database. Archive retains content and rejects subsequent edits. Old returned/bound values never mutate when the library changes.

The Ant/bilingual library editor supports typed parameters and five sequential step kinds, revision conflicts, save/reload and archive. Duty calls bind a copied definition only on initial selection; selecting an already bound active program uses that document's snapshot. New revision adoption is explicit and forms one document/history transaction. Parameters are edited from the bound snapshot and validation invalidates compiled/simulation input. Numeric fields retain existing blur/Enter committed drafts. One DutyEditor remains the timetable owner.

Generated JS declares each used program once and calls the real ScriptWorker program builder. Compilation validates all steps/arguments before appending actions, enforces typed parameter references, and retains duty/element/call/program/step origins. Absolute waits emit a real WAIT_UNTIL action (runtime provided by the separate domain owner); relative waits retain positive STOP duration semantics. Movement/storage/reversal use existing actions. Prior CHECK or service-boundary acquisition is required. Library acquisition is not fabricated as a callback action.

Known locations are retained across nonmoving/consecutive programs; MOVE updates the nominal continuation location. Full world routes/operational validity remain the existing server validator's authority.

## Owned files

- WebUI Java: new VisualProgramRepository.java, ProgramContentIdentity.java, StationProgramValidator.java; WebUiApplication.java routes and EditorDocumentValidator.java bound/call structure.
- Java tests: new VisualProgramRepositoryTest.java and StationProgramHttpTest.java.
- Frontend new files: ProgramFields.tsx, StationProgramLibrary.tsx, stationPrograms.ts, stationPrograms.test.ts.
- Frontend integrations: App.tsx, DutyEditor.tsx, DutyManagement.tsx, api.ts, types.ts, frontendDev.ts, authoring.ts, authoringRecovery.ts, auth.test.ts, i18n.tsx, studioPages.css.
- ScriptWorker: new StationPrograms.kt; Dsl.kt and Model.kt; new StationProgramScriptTest.kt. Main.kt needs no change because existing annotated-host access already exposes the new builder methods.

These paths include prior dirty content in some existing files; do not stage whole baseline deltas without the parent manifest review.

## Actual checks

- `npm --prefix WebUI/frontend test`:132 tests/24 files pass (final07:35UTC).
- `npm --prefix WebUI/frontend run build`:TypeScript+Vite pass; existing large-bundle warning remains.
- `git diff --check`:clean.
- `./gradlew :WebUI:test --tests '*StationProgramHttpTest' --tests '*VisualProgramRepositoryTest' -x :WebUI:installFrontend`:6 tests pass with real frontend resources built.
- `./gradlew :BukkitPlugin:test --tests '*StationProgramScriptTest' -x :WebUI:installFrontend`:3 actual GraalJS compilation tests pass.

Frontend tests cover two documents retaining separate snapshots, explicit one-history adoption and Undo/Redo, archive refusal/embedded preservation, deterministic single definition generation, quoted argument injection safety, call attribution, duplicate call IDs, continuity after nonmoving/consecutive programs, typed/invalid arguments/schedules, malformed recovery and optional-default old documents, encoded transport and conflict propagation.

Repository/HTTP tests cover real authentication, world isolation, create/update/archive status, concurrent one-winner revision writes, failed write preservation/temp cleanup, reloading library files, canonical identity and bound editor documents staying unchanged after library update/archive. Strict validation rejects unknown structure, unsupported ACQUIRE, incompatible parameter type and invalid duration/numeric values.

ScriptWorker tests assert exact action order/ticks/movement/storage, attribution to a stable step, prior acquisition requirement, wrong types/unknown nodes, true absolute action emission and no partial expansion when a later step fails.

## Remaining evidence and scope

Independent review/browser and parent's coherent broad module gate are pending at this checkpoint. These tests do not establish live world/game operation, full simulation or deployment. The demo API explicitly reports that shared library operations require a live authenticated server, rather than inventing fake library persistence.

Acquisition-capable program boundary expansion, conditions/dynamic candidates, complete-unit coupling/separation/circulation and integrated simulation/game acceptance remain later approved scope. Existing STOP-before-first-movement rule is preserved; any startup relative-wait change belongs to the domain owner. Library local drafts do not provide server-wide Undo; bound definitions/calls use the existing timetable Undo/Redo owner. No arbitrary JS reverse import.
