# Research: Remaining original feedback after current train information

- Query: Choose the next narrow batch from original feedback, checking actual controls rather than inventing functionality.
- Scope: internal
- Date: 2026-10-28

## Findings

## Clearly unfinished original item 10

`research/feedback-backlog.md` item 10 requests removing independent operational-trip and formation forms from the duty page once replacements exist. Formation configuration is now in duty tasks, but the old operational-trip panel remains.

- `WebUI/frontend/src/DutyManagement.tsx:56` mounts DutyGraphEditor in the Advanced collapse beneath the main duty page.
- `WebUI/frontend/src/DutyGraphEditor.tsx:25-35` shows a separate formation-duty graph, selectable boundaries and buttons adding runs to a selected operational trip.
- The same file, lines 36-41, contains independent operational-trip creation with a manually entered ID, trip selection/deletion, default-display JSON, run/unit-slot segment selection, segment reorder/delete and add-run selector. These are real active controls, not archived code.
- The replacement for explicit display changes exists in program SET_DISPLAY, including the newly added current-train data source. Formation tasks already edit physical operations in the timeline.

This is the best substantive next original-list item; there is no need to invent another formation capability.

## Scope trap: underlying metadata is not only a UI duplicate

- `WebUI/frontend/src/authoring.ts:608-611` emits timetable.operationalTrip definitions, and RUN generation emits operational-trip starts. Saving still preserves operationalTrips.
- `SimpleATO/.../timetable/TimetableRunMetadata.kt:128-147,232-281,410-446` validates unit continuity across runs/formation events, provides defaults, and controls per-unit display lifecycle.
- Therefore deleting/hiding the panel while continuing to save/execute its invisible configuration would violate the backlog's explicit warning against leaving configuration with no editor. Automatically translating it to SET_DISPLAY also does not preserve continuity/future-display semantics by itself.
- A deliberate batch can retire the operationalTrips field from structured WebUI authoring, its validator/generator/graph-only editor, and related frontend tests; any currently saved development documents need audited dry-run migration/recovery points according to persistence rules. Direct runtime/DSL APIs need separate consideration rather than deleting them merely because a WebUI editor is removed.
- If the batch instead removes only the old panel, it must provide an explicit way to see and remove existing operational-trip configuration. Silently ignoring or preserving hidden behavior is not a complete fix.

## Already addressed original item 14

`DutyManagement.tsx:44-51` places vehicle source in duty properties, shows initial placement only for named trains, reuses the selected train name, clears initial placement when changing source, and includes the initialization/reset explanation. `authoring.ts:615-617` emits one global setInitialPosition with that selector. The former sequential PLACE_INITIAL block is absent. Current Chinese help at i18n.tsx:43 explicitly says initialization/reset, not a timeline task. Do not claim this needs another full redesign.

## Original item 7: spacing candidates, not confirmed visual defects

- Duty snapshot rows at DutyManagement.tsx:54 use one Flex per binding with horizontal/wrap gap only; a wrapping stack of rows has no parent row gap.
- Old DutyGraphEditor has fixed minimum 230/280px selectors and combined 200px select + 230px input. These deserve narrow-screen browser inspection if the old editor survives.
- Main duty property spacing is already defined by studioPages.css:144-146; initial-position/timeline/advanced-panel spacing is already defined by DutyTimeline.css:35-45. Formation fields now use a 16px grid gap. Do not report the whole page as missing margin without rendered evidence.
- The old graph panel appears to have no dedicated CSS rules for duty-network, trip-editor, trip-boundary, or trip-leg in current frontend styles (rg lookup). This alone is not proof of a visible defect.

### Narrow, actionable next batch: JSON argument overflow and snapshot row gaps

After inspecting the actual new program call controls, original item 7 has two concrete CSS/layout causes suitable for a small independent batch, without the semantic retirement decisions of item 10:

1. `TimedOperationFields.css:2` uses auto-fit columns with minimum 190px and an 18px column gap. `ProgramFields.tsx:9` places ProgramJsonInput inside a `duty-program-argument` grid item for JSON_OBJECT parameters. `studioPages.css:220` forces `.program-json-input { min-width:220px; max-width:100%; }`. At a grid width of 398–457px, two columns are 190–219.5px wide, so a JSON editor necessarily overflows its assigned column and consumes the inter-field gutter or overlaps the next parameter. `max-width:100%` cannot win against the larger min-width. This affects the duty program task inspector and the graph-owned timed-operation modal because both reuse TimedOperationFields.
   - Actual-component browser reproduction: program declares `payload:JSON_OBJECT` then `label:STRING`; open its scheduled call, constrain field-grid content to approximately 420px (two 201px columns), compare JSON editor right edge to next field left edge. Also cover JSON followed by UNIT_SLOTS, narrow 320/360px window, and switching argument kinds.
   - Minimal change: allow the JSON child to shrink within `.duty-program-argument` (`min-width:0; width:100%` scoped to that consumer, or correct the reusable JSON input if globally valid). Preserve the actual 14/18px layout gap. Verify columns can collapse at narrow viewports rather than introducing larger fixed widths.
2. `DutyManagement.tsx:54` renders multiple independent Flex rows immediately adjacent within the bound-program snapshot collapse. Each row has horizontal/wrap gap 8 but the parent fragment supplies no vertical row gap. When consecutive bindings have newer revisions, their default-height update buttons touch vertically. A shared vertical-gap wrapper around the rows provides explicit spacing; also wrap/break long program names without shrinking the update button into illegibility. Reproduce with at least three actual bound snapshots and newer library revisions, one long name, open the bound snapshot collapse in both desktop and narrow layouts.

These two production controls are the recommended next narrow original-list batch. They need no runtime/model/schema changes, no new UI features, and actual rendered/browser verification before claiming visual correction.

## Related specs and external references

- `.trellis/spec/webui/frontend/program-blocks.md`: real action/data authority, typed expressions, current program replacement.
- `.trellis/spec/webui/frontend/studio-authoring.md`: single timeline duty UI, manual recipients and source ownership.
- `.trellis/spec/javarailways/simpleato/through-running-and-unit-displays.md`: runtime operational-trip metadata and continuity semantics.
- `.trellis/spec/javarailways/shared/versioning.md` and `ai_tasks/persistence_schema_evolution_rules.md`: unreleased saved-development document changes and audited one-time migration.
- No external documentation needed; no tools, dependencies or versions change for this research.

## Caveats / Not Found

- Read-only source inspection only; no browser, tests, source modification, commit or deployment performed.
- This report is a recommendation, not approval to remove independent runtime functionality or to mutate development-server documents.
- Items 1-6,8-9,11-13,15-16 already have recorded implemented rounds; item 17's approved four properties are implemented in the current batch. Do not reopen completed wording as a substitute for an actually unfinished item.
