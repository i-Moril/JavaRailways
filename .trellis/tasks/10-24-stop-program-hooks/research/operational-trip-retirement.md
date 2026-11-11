# Original feedback10: replacing independent operational-trip authoring

User requested commit spacing and continue; spacing committed0900f865. Existing task remainsin_progress, no deploy/archive. User answered: retain cross-run/per-unitfuture-stop capabilities with a replacement owner. Timeline RUN inspector chosen; active current data/runtime authority retained. Concrete contract: research/run-display-plans.md. This is a real behavior choice, not task-creation/implementation permission.

## Confirmed current contract

- DutyManagement advanced Collapse still mounts DutyGraphEditor, whose operational-trip catalog controls edit defaults and ordered service/run/unit-slot segments. Removing only the panel hides executable/persistent configuration.
- authoring.ts emits catalog definitions and per-run begin markers; WorkerDsl.prepareDisplayBoundaries also inserts BEGIN_OPERATIONAL_TRIP around stationary gaps. TimetableMetadataValidator verifies claims/continuity.
- TimetableDisplayRuntime.set requires every selected registered unit to have active operationalTrip; reset restores trip.defaults. Thus simply deleting the catalog breaks existing SET_DISPLAY/RESET_DISPLAY, including readonly train data used as display values.
- TimetableRunSegment/call facts remain independently needed for current run/line, simulation/report and route semantics. They must survive either choice.
- Per-unit snapshots currently expose operationalTripId, values and planned/completed/remaining stops. HttpBridge and frontend AuthoringValidation render those facts; no automatic physicalscreen/renderer consumer was confirmed.

## Coherent options and verification boundary

Retain capabilities: replace old catalog form with an explicitly editable program/timeline-owned declaration while preserving per-unit identity/continuity, ordered run stops and default/reset lifetime. Avoid generating invisible synthetic trips or leaving old configuration without an editor. Need finalize source representation and field ownership after user chooses.

Retire concept: remove structured authoring catalog/editor/generation/validation and current development-data fields through an auditable offline conversion if preserving documents is needed; do not add legacy readers or obsolete-field rejection branches. Decouple unit display values from trip grouping in the same slice so SET_DISPLAY and RESET_DISPLAY continue working on actual registered units. Preserve legitimate directruntime/DSL callers only if inventory shows ongoing purpose, not as development compatibility. Remove or explicitly update exposed stop-history/future fields and consumers; no silently empty or fabricated metadata.

Both choices need current authoring save/restart/history, compile origins, stationary/duty display actions, split/couple identity and cancellation/period cleanup tests, plus actual editor checks. This research predates the choice; implementation now follows research/run-display-plans.md. No server data or live runtime is changed.
