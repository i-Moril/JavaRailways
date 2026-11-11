# Research: Operational-trip UI retirement dependency map

- Query: Map both pending choices for original feedback 10: replace the separate editor while preserving per-unit continuity/future stops, or retire the concept and its corresponding features without breaking ordinary display programs.
- Scope: internal
- Date: 2026-10-28

## Findings

## Critical prerequisite: display actions currently depend on operational trips

`SimpleATO/.../timetable/TimetableRunMetadata.kt:480-483` resolves every selected unit's **active operational-trip context** before SET_DISPLAY or RESET_DISPLAY. SET_DISPLAY merges values into that context; RESET_DISPLAY restores its trip defaults. Removing the UI/catalog alone makes ordinary display programs fail with `unit ... has no active operational trip`.

`TimetableDispatcher.kt:6341-6346` also suppresses every unit display snapshot when timetable.operationalTrips is empty. `enterRunSegment` at 6365-6376 only binds registered units to a run if an operational-trip segment references it. An ordinary display action may temporarily assign currentUnitIds, but the empty-catalog publication guard still discards its snapshot. These guards must change coherently if display state becomes independent.

## Full producer/consumer chain

1. **Editor:** DutyManagement.tsx:56 -> DutyGraphEditor.tsx:35-41 owns operational trip identity/defaults/segment+unit-slot CRUD; authoring.ts persists operationalTrips and updates/removes references on run/duty changes.
2. **Validation:** frontend authoring/interline helpers validate definitions; WebUI EditorDocumentValidator.java:81,88-117 validates saved HTTP documents.
3. **Source:** authoring.ts:608-611 emits `timetable.operationalTrip`. RUN generation emits required boundaries. Worker Dsl.kt:67-76 registers catalog, compile collects it at 226-244.
4. **Early display context:** Dsl.kt:252-300 `prepareDisplayBoundaries` scans gaps before RUN scopes, after structural changes, and inserts BEGIN_OPERATIONAL_TRIP before displays or station programs using displays. It updates every affected run-segment range and compilation-trace index. Timed/untimed DSL exposes beginOperationalTrip at 350-356,592-593.
5. **Runtime metadata:** TimetableRunMetadata.kt:46-58 defines trip+segment catalog; 128-281 validates declared selection and continuity. `TimetableDisplayRuntime` at 323 onward tracks context by (timetable, cycle, stable unitId), proving future physical bindings across reversal/split/couple and resolving only real registered units.
6. **Lifecycle:** begin/enter establishes trip defaults and owner; continuation requires the same physical unit; reached marks occurrence IDs; finish/retire controls active status; structural handoff retains stable unit identity; cycle cleanup prevents stale scope reuse.
7. **Persistence/clone:** SimpleATO persistence/TimetableSnapshot.kt contains catalog and BEGIN fields; Bukkit worker Model.kt mirrors it; TimetableDumper emits it; isolated simulation uses capture/restore so any replacement must survive both boundaries.
8. **Read consumers:** TimetableDispatcher.unitDisplaysForTrain at 1944-1951 returns facts for real trains including HOLD outputs. TimetableServiceRunSnapshot includes displays. TimetableHttpBridge.kt:102-117,666 publishes values, operationalTripId, planned/completed/remaining stops and unresolved runs. frontend AuthoringValidation.tsx:26 shows the per-unit simulation data.

No actual model LCD/renderer consumer of these unitDisplays was found in the production source search. The visible consumer is HTTP/simulation diagnostics. Do not claim removing/changing the metadata directly updates in-game model displays.

## Keep runSegments in either choice

`TimetableRunSegment` and `TimetableRunCall` (TimetableRunMetadata.kt:16-43) are physical run-scope metadata independent from operational-trip grouping. `Timetable.kt:112` uses segmentAtOrBefore for line-speed ownership. `TimetableDispatcher.enterRunSegment:6350-6386` checks actual directed origin, stopped through-running transitions, speed-limit changes and current run/call state. Removing operational-trip catalogs must not remove these checks, run metadata, call IDs, currentRunId/currentLineId, or run reports.

## Choice A: preserve continuity/per-unit future stops

Smallest coherent replacement retains existing trip/catalog/runtime semantics and moves **all existing editable fields** to an accepted owner in the current UI. Merely deleting the old form while preserving hidden operationalTrips is insufficient. At minimum the replacement must edit/default/clear:

- The logical grouping identity and ordered physical run occurrences.
- Whole-unit selection for each occurrence, including split/couple participants and reversals.
- Display defaults and the point where they reset.
- Existing saved groupings, not only newly created ones.

A task/run-inspector replacement could reuse the current catalog rather than create a second runtime model; a program-owned replacement would need to compile an explicit static declaration that still supplies the same cross-run information. Neither UI shape was approved in the pending question; do not implement a new block arbitrarily. SET_DISPLAY by itself supplies no future-run grouping or physical-continuity intent.

Preserve worker boundary insertion and validation until an equivalent explicit initialization boundary exists. A minimal relocation should avoid runtime, schema and migration changes altogether; only the editing owner changes. Remove DutyGraphEditor and graph-only helpers only after the replacement can edit every retained config.

## Choice B: retire operational-trip concept and its features

If the user approves removing cross-run per-unit continuity/future-stop grouping, the smallest coherent runtime replacement still needs independent ordinary display state:

- Separate display value store keyed by existing timetable/cycle/stable-unit identity from trip ownership. SET_DISPLAY must initialize state at a legitimate acquired train/unit, without a synthetic automatically-created operational trip. Preserve bounded immutable JSON, atomic multi-unit update and removal semantics.
- RESET_DISPLAY needs an explicit replacement baseline. With trip defaults removed, clearing to empty is the natural minimal behavior but it changes the old documented meaning; specify this before implementation and update program help/tests.
- Define ownership/retirement precisely using existing run/cycle/structural handoff events. Reuse real stable unit IDs and existing lifecycle; do not add new user concepts or erase shared peer/HOLD output state on one participant finishing.
- Remove trip-only fields and future-stop collections from the snapshot/HTTP/frontend table, or deliberately mark them optional with an actual new meaning. Do not publish invented empty planned data as though the old projection was still operating. Preserve per-unit values and actual unit identity.
- Remove authoring operationalTrips, its validators/generator/boundary insertion/old editor and BEGIN actions where no longer supported. Retain runSegments and physical call facts.
- Update worker saved model, SimpleATO capture/restore/dumper, immutable catalog freeze and direct DSL together if the concept is retired globally. Saved development documents need audited dry-run one-time migration with recovery; no runtime readers for obsolete fields and no guessed version bump.

Deleting only the structured authoring field while preserving direct DSL operational trips is possible as a separately stated scope, but creates two supported display semantics. It is not automatically simpler than global retirement. Avoid accidentally preserving old paths under a compatibility justification.

## Public API and compatibility boundaries

- `SimpleATO/build.gradle.kts:69-72` suppresses all non-api packages from Dokka. Operational-trip models/display runtime live under `.timetable`, not the published `.api.*` surface. There is no discovered API release baseline requiring this internal concept to remain.
- Existing public `.api.timetable.TimetableExtensionCall`, provider validation/start and TimetableRuntimeContext are separate contracts. Display retirement must not remove train info provider parameters, change extension ticket lifecycle, or loosen static planned-condition restrictions.
- `TimetableDispatcher.unitDisplaysForTrain` is a callable Kotlin method but in the suppressed internal package; nevertheless it has production tests and explicit ownership semantics. Changes should be intentional and reflected in internal consumers/specs, not labelled a published API guarantee without evidence.
- HTTP and saved document boundaries are persisted/client contracts even when internal runtime classes are not published. Follow the repository unreleased-format/migration policy.

## Existing tests to preserve/adapt

- SimpleATO TimetableRunMetadataTest: display merge/reset, immutable JSON, exact unit continuity, explicit begin before stationary updates, cycle cleanup, repeated station occurrences, future split projection, ordinary run scope speed cap across program gap.
- SimpleATO TimetableFormationDispatcherTest: unit display state through actual split/couple and retirement; ensure structural safety tests survive any removed grouping tests.
- Bukkit ScriptWorkerTest and ScriptWorkerCompatibilityTest: source declaration, auto BEGIN insertion and shifted ranges/traces.
- Bukkit IsolatedTimetableSimulationFactoryTest: per-unit D/E future projection and unitDisplaysForTrain on real isolated train membership.
- Bukkit UnitDisplayProjectionTest: JSON snapshot shape and repeated stop occurrences.
- Frontend interlineAuthoring.test.tsx, dutyGraph tests, authoring tests and AuthoringValidation snapshots.
- New train-info tests must still prove actual SET_DISPLAY execution without requiring the user to configure an obsolete hidden editor in choice B.

## Related specs and references

- `.trellis/spec/javarailways/simpleato/through-running-and-unit-displays.md`: existing default/reset/boundary/future-stop semantics.
- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md`: serialized owner, accepted-action lifecycle and real handoff.
- `.trellis/spec/webui/frontend/{program-blocks,studio-authoring}.md`: current task/editor ownership.
- `.trellis/spec/javarailways/shared/versioning.md`, `ai_tasks/persistence_schema_evolution_rules.md`: current saved development-format changes.
- No external references or dependency-version research required.

## Caveats / Not Found

- The user's choice is pending; this document authorizes neither removal nor a new replacement GUI.
- Research only; no source edits, tests, commit, deployment or data mutation.
- Source search found no model-renderer consumption of unit displays; an external plugin may exist outside this repository, which this research cannot establish.
