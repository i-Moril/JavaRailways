# Line/run metadata and screen lookup

User approved extending station-style metadata to infrastructure lines and specific timetable runs, direct JSON editors, persistence/compiler/publication roundtrip, immutable JS line-ID lookup. Line/run metadata independent; no implicit merging. Defaults{}. Current run must reflect actual execution, not infer from upcoming stops. Preserve prior dirty work, no commit/deploy. Unified verification remains deferred: author cases, compile/type-check only now.

Acceptance: line store/revision/save/restore retains bounded immutable metadata; authoring run copies/edit/save/validation/generatedJS/DSL/snapshot/runtime roundtrip retains metadata; JS ctx.data.lines[id].metadata reads current world's known line, missing ID undefined; ctx.data.operational.currentRun supplies serviceId/runId/lineId/metadata for exact active scope or null, no cross-world data. Existing station metadata remains intact.
