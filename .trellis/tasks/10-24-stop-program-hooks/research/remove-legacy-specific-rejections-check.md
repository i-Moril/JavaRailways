# Review: remove dedicated legacy-format rejections

Reviewed current working diff against ff98993692d4bd1c51367d7ee1256851ec048069 on 2026-10-27. Scope: frontend authoring/recovery/save, Java editor-document validation, and removed obsolete-format tests across WebUI, SimpleATO and ScriptWorker. No implementation edits made by reviewer.

## Findings (fixed)

None. No confirmed correctness issue in reviewed changes.

## Findings (not fixed)

No code finding. The root owns documentation updates: studio-authoring.md's wording about runtime rejecting old fields should describe generic current-schema validation instead of a dedicated old-field policy. Backend repository documentation can similarly enumerate current fields without referring to a removed field. This is documentation synchronization, not a requested runtime compatibility branch.

## Evidence

- Removed authoring validation, recovery filtering and save guard no longer recognize the old per-call arrival field or emit migration-specific messages.
- EditorDocumentValidator now uses the existing generic fields helper. Required run-call keys are id/stationId/status/targetId; optional keys are berthId/arrivalTick/departureTick/minimumDwellTicks/stoppingOffsetMetersBefore/doorSide/viaIds. These exactly match AuthoringRunCallV1; nullable optional keys remain accepted. No synthetic historical field has been added.
- New validation regression accepts all current fields and the required-only shape, then tests an ordinary unexpectedField through the same generic helper.
- Deleted tests target removed old fields/enums/routes/APIs only. Current shared-station round trips, current DSL operations and invalid definitions, generic HTTP extra-field checks, revision checks, deletion and bound snapshots remain covered.
- Scoped searches across frontend source, Java WebUI main source, ScriptWorker source, SimpleATO persistence and timetable source find no arrivalProgram/arrivalSteps/archived recognition. Remaining 'obsolete' locals in dispatcher cleanup and one historical waypoint comment do not implement format recognition.
- Offline migration tools are unchanged and outside runtime.

## Verification

- Diff whitespace check: pass (`git diff --check`).
- Lint: no standalone lint script in frontend package; not run by reviewer.
- TypeCheck: delegated to root's active frontend build gate, not independently run.
- Tests: root running frontend, WebUI and focused SimpleATO/Bukkit suites; no duplicate heavy test/build execution by reviewer.
