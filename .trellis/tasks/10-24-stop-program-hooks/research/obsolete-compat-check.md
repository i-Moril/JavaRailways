# Obsolete compatibility removal review

Reviewed 2026-10-27 against baseline HEAD 4ce0cc11, in /tmp/javarailways-blockly-deploy. Scope: changed archive/current-library paths, offline migration, per-call arrival DSL/snapshot/cursor/dispatcher removal, dead rows/aliases and corresponding spec updates. This is a focused changed-path review, not a whole-repository behavioral audit.

## Findings (fixed)

- Specs previously claimed archived library records remained accepted and arrivalSteps/ARRIVAL cursor were supported. Reported exact locations; root updated studio-authoring.md, http-repository-packaging.md, program-blocks.md, dispatcher-runtime.md, persistence.md and timetable-model-and-validation.md. Re-read updated contracts; they now describe strict current inputs and offline migration.
- Reviewer made no production-code changes.

## Findings (not fixed)

No concrete remaining code regression identified in the reviewed changes. No design changes recommended for this cleanup.

## Actual path checks

- Library create emits no archived field. Find/list/update/delete all pass strict validateRecord, whose allowlist excludes archived; true and false old records are both rejected, without modification. Normal revision conflicts and content identity validation remain.
- Duty/library consumers no longer branch on archive status. Bound immutable program snapshots remain independent from library record existence; deleted library definitions are not silently substituted with defaults.
- Offline migration examines only the exact world-scoped station-programs tree. It rejects unexpected paths, symlinks, hard-linked source files, malformed/duplicate JSON keys and invalid top-level metadata. Apply requires equality with the complete saved root/action/before/after hash plan; all files are backed up and checked before mutation, then the entire source tree is rechecked. Rewrites preserve file stat/ownership; replacement and deletion fsync the affected directory. Archived=false strips metadata, archived=true removes the library entry, unchanged records remain byte-identical, document/runtime bound snapshots remain untouched. Tool intentionally delegates deep action semantics/canonical content-hash validation to Java, so deployment must run the current Java validator before startup acceptance.
- Old arrivalSteps is removed from both worker and SimpleATO snapshot DTOs and domain actions. DSL destination allowlist no longer permits arrivalProgram; tests explicitly reject empty and nonempty old inputs. ARRIVAL branch enum/traversal/trace UI paths and secondary dispatcher continuation are removed together. Strict snapshot tests reject old arrivals/ARRIVAL addresses/rows/DISABLED calls.
- Shared stopExecution handling still precedes ordinary station/destination dwell handling on every tick. Shared startup, arrival/preparation scheduling, subtree join, minimum dwell, planned departure, door-security gate, failure cancellation, physical reversal and final hold release remain in the same current code paths; legacy removal does not bypass them. Existing current tests retain origin-only prep, terminal arrival-only/optional handoff time, reversal, door failure, shutdown cancellation and no-motion-with-open-doors coverage. New tests preserve absolute terminal wait and missing-provider pre-install rejection.
- Ordinary low-level station/destination dwell without a custom stopPrograms remains current functionality; it is not a legacy per-call program fallback. Runtime physical compatibility and explicit offline migration tools were not misclassified as obsolete schema support.
- Removed service rows had no remaining production imports/callers; current runSegments, run-call metadata and THEN/ELSE provenance remain. Unused Core aliases removed without renaming actual persisted DTOs or changing schema numbers.

## Verification

- git diff --check: pass at review time.
- Offline migration: reviewer ran all 8 tests; pass, covering dry-run immutability, conversion/delete/recovery, invalid input, hash conflicts, links, backup path/existence and source mutation during backup.
- Frontend: root reports 222 tests passed. Final TypeScript/build and backend suites are owned by root and were still running at review handoff; reviewer did not duplicate them.
- Lint: package exposes no separate lint script. No new lint gate claimed.
- TypeCheck: root-owned final build gate; no independent reviewer run.
- Backend tests/live deployment: pending root verification; source review is not game/client acceptance.
