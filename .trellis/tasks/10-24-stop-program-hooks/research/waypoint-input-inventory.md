# Editable waypoint inputs — inventory and proposed contract (2026-10-30)

> 2026-10-31用户纠正：本文关于扩展局部berth/stabling后缀控件的提议/实现仅是前批历史，已撤回。完整路径点要求不适用于这些局部字段；当前合同见waypoint-suffix-correction-2026-10-31.md。

Worktree `/tmp/javarailways-blockly-deploy`, baseline `5e674d12`. Existing task stays in progress. This inventory precedes implementation; no backend, storage, schema, deployment or archive change.

## Actual waypoint/destination inputs

- `DutyManagement.tsx`: `TAKE_FROM_WAYPOINT.wayPointIds` is currently a closed multiple Select over `metadata.wayPoints`; named-train `initialPosition` is a closed optional single Select over the same IDs.
- `RunRouteEditor.tsx`: ordered `selectedCall.viaIds` is a closed multiple Select, disabled for ORIGIN calls.
- `FormationEventsEditor.tsx`: couple rendezvous `approachDestinationId` and `stationaryDestinationId` are closed single Selects. Receiving/coordinating duties and station binding are different identities.
- `ProgramSimulationDialog.tsx`: initial waypoint is a searchable closed single Select over `metadata.programSimulationWayPoints`. It only accepts existing points with an explicit own/rail direction. The request builder and server independently enforce membership and placement. This boundary must remain strict when adding manual entry.
- `ProgramFields.tsx`: typed `DESTINATION` library-call arguments are already free text but have no candidate list. Used by `TimedOperationFields` and editable library arguments in `ProgramSimulationDialog`.
- `ProgramBlockEditor.tsx`: `rail_literal_DESTINATION` is already free text both on the block and in its inspector, with no candidate list. `MOVE.viaIds` is an `ID_LIST` literal edited as raw JSON; it can offer waypoint suggestions only when actually connected to that MOVE input. Unconnected/generic `ID_LIST` and declared ID_LIST parameters can also represent display keys, berth IDs or depot-local point IDs, so their meaning must not be guessed from their type or name.

## Related selectors that are different domain identities

- `RunRouteEditor`: berth Select uses a station-local `berthId` and resolves `targetId` from that station's metadata. It is an indirect stop selection, not an arbitrary waypoint picker. Unknown berth input would have to preserve the berth draft and remain invalid; it cannot silently pick a waypoint from a different station.
- `LinesPage` and run endpoints select `stationId`; do not replace these with waypoint IDs.
- `DutyManagement.TAKE_FROM_DEPOT.depotId` and program `DEPOT` literals select depots, not waypoints. The current DEPOT literal is already text.
- Program `STORE_AT_DEPOT.stablingPointIds` are depot-local stabling-point IDs, not destination IDs. Current raw JSON/tag input already accepts manual values. Any suggestions must come from the selected depot's `depotDetails.stablingPoints[].id`, not `.destinationId`.
- Program `SELECT_STOP.berthIds` similarly contains station-local berth IDs; SET_DISPLAY.removeKeys contains arbitrary display keys.
- `AuthoringValidation` selects reference profiles only; the rail map renders markers and does not expose an editable waypoint selector. No additional waypoint control exists on the infrastructure Lines page.

## Existing UI patterns

There is no `CommittedTextInput` or native datalist in current production frontend. `RunTitleEditor` and `CommittedNumberInput` retain local drafts, commit once on Enter/blur, cancel on Escape and reset when field identity changes. Existing `Select mode="tags"` inputs in ProgramFields and duty tag editing already support manually entered arrays. Ant Design AutoComplete is available through the installed Ant Design dependency.

## Proposed shared behavior

Use a small shared editable ID control with single and ordered-multiple variants; pass domain-specific candidates explicitly. Single inputs keep the actual ID editable, offer matching options by case-insensitive ID and visible label, commit on option selection/Enter/blur, and cancel pending text on Escape. Multiple inputs use tags and preserve entered order; typing filters suggestions and Enter/selection adds one value. Neither input rewrites IDs, derives IDs from display names or silently removes values no longer present in metadata.

Authoring controls accept unknown/deleted IDs as authored values so existing issue/compile validation remains authoritative. The simulation scenario accepts manual text for discovery but disables execution until it resolves to one of the eligible directed IDs; incomplete text must invalidate an old simulation result immediately and cannot retain an earlier request behind a new draft.

Known candidate names can come from existing directed-point descriptors and station/depot metadata for the same actual waypoint ID. No new metadata endpoint is needed; ID-only candidates remain searchable when no display name is supplied. Program canvas/body/history and call argument ownership remain unchanged. No generic JSON/data marker is interpreted as a waypoint, no persistent input preference is added.

The coordinator approved station-local berth controls and context-specific SELECT_STOP/STORE_AT_DEPOT list suggestions as part of this batch. Their values remain local berth/stabling-point IDs and their candidates stay within the current station/depot. Unknown berth text is retained as `berthId`; an unresolved target stays empty and is reported by the existing required-target issue. It never resolves against another station or the global waypoint list.

The backend already exposes OperationalDestination `{id,displayName}` metadata. The frontend now declares that existing descriptor and uses its name when available, alongside existing directed-point/station/depot names. Standalone Basic waypoint names absent from metadata remain ID-only. Simulation candidates remain exactly the directed descriptor set, including directed depot points such as `kasaidpt@1`.

## Implementation and checks to perform

Add shared control and focused tests for ID/name filtering, manual/unknown values, commit deduplication, Escape, owner replacement and ordered multi-value input. Wire the six existing closed controls plus typed DESTINATION arguments and Blockly DESTINATION/MOVE.viaIds inspector suggestions. Keep ordinary station/depot/local-ID controls distinct unless separately included by the coordinator. Test the simulation unknown/undirected rejection and stale-result invalidation through the actual dialog, then run frontend tests/build and a real browser flow. Gradle belongs to the root coordinator.

## Implemented frontend boundary checks

- `EditableIdSelect.test.tsx`: mounted drafts, exact unknown values, ID/name filtering, selection/Enter/blur deduplication, Escape, owner replacement and ordered multi-value additions.
- `idOptions.test.ts`: real canonical depot ID and display-name differences, existing destination names, directed eligibility and local station/depot candidate domains.
- `ProgramSimulationDialog.test.tsx`: actual dialog request uses canonical depot ID; raw query edits immediately remove old measurements/adoption and cancel pending responses before blur; Escape does not restore invalidated results; committed unknown/undirected IDs cannot start.
- `RunRouteEditor.input.test.tsx`: mounted unknown berth entry remains visible and in the authored run with an invalid empty target, known manual IDs resolve only inside the current station, Enter/blur commits once and Escape cancels.
- `FormationTaskFields.input.test.tsx`: mounted DutyTimeline passes the existing simulation metadata to both rendezvous selectors. Display-name search and selection commit canonical IDs, preserve the original candidate-ID domain and do not add another update on blur.
- Multi-value selection clears its controlled search text on accepted change/selection. The mocked Ant Design boundary deliberately does not emit an artificial `onSearch('')` when choosing a candidate; a name search followed by canonical-ID selection and blur cannot add the leftover display-name query as a second ID.
- Blockly list inspector converts only valid unique string-array literals in the three known input positions to ordered tag controls. Malformed JSON, invalid list members and generic ID_LIST inputs retain the original raw text editor; references retain the parameter selector. The existing raw workspace serialization/recovery and Blockly transaction remain authoritative.

These unit tests mock the Ant Design component event boundary; real Ant Design keyboard, dropdown and browser history checks belong to the root coordinator. No backend product, schema, persistence version, commit, deployment or task-lifecycle change is part of this frontend implementation.
