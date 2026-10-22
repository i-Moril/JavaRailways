# Shared station-program library transport (implementation contract)

Owner: WebUI sidecar repository; programs are authoring inputs, never a mutable runtime registry. Existing authenticated API filter applies to all routes below. No bridge bearer token reaches the browser.

Base: `/api/v1/worlds/{world}/station-programs`.

- GET base: 200 array of complete records, stable ID order, including archived entries so provenance/update state remains visible.
- GET `/{program}`: 200 record;404 missing.
- POST base: `{id,name,definition}`;201 record at revision1;409 duplicate ID.
- PUT `/{program}`: `{expectedRevision,name,definition}`;200 new record;404 missing;409 stale revision or archived entry.
- POST `/{program}/archive`: `{expectedRevision}`;200 archived record;404 missing;409 stale revision. Matching already archived record is unchanged. No delete or restore route in first batch.

All writes validate their complete input before atomic replacement. Invalid payload/path400; session401; I/O502. Expected revision is a positive safe integer. Revision is optimistic concurrency metadata, not schema version. One sidecar repository instance serializes read/check/write; two browser writes to the same revision cannot both succeed. Repository is not a multi-process shared-filesystem database.

Record: `{formatVersion:1,id,name,revision,archived,contentIdentity,definition}`. `contentIdentity` is SHA-256 of definition JSON with recursively sorted object keys, preserving array order; name/archive/revision are excluded. New library is its own persisted boundary at initial format1. Existing editor document gains additive boundPrograms default empty; no version bump or arbitrary legacy reader.

Bound snapshot: `{programId,revision,contentIdentity,definition}`; definitions are immutable document inputs. Editing/archiving a library record never writes into a timetable. Explicit adoption is one history transaction and may require call parameter revalidation. Archived records cannot be newly bound but existing snapshots remain reproducible. Step and parameter payload contract must be agreed with SimpleATO/Bukkit before production routes accept definitions or JS generation is written. No placeholder step DSL is enabled.

## Agreed sequential definition (parent confirmed)

`parameters:[{id,type}]`, types `TICK|DURATION|DESTINATION|DEPOT|STRING|ID_LIST`; `steps` is a finite list with stable IDs. Every expression has exactly `{value:literal}` or `{parameterId:string}`. No STRING parameter substitution into a typed DEPOT/DESTINATION field. Tick/duration values are safe nonnegative integers; WAIT_DURATION is strictly positive after binding. Arrays contain nonblank IDs. No unknown fields, duplicate parameter/step IDs, nested calls or runtime JS expressions. Limits:64 parameters,256 steps,256 IDs per list.

Step variants: `WAIT_UNTIL {tick}`, `WAIT_DURATION {durationTicks}`, `REVERSE {}`, `MOVE {destinationId,viaIds}`, `STORE_AT_DEPOT {depotId,stablingPointIds}` (each also has id/type). MOVE uses existing destination options+movement expansion. WAIT_UNTIL is a real action scheduled at its absolute tick, not a cursor-only no-op; remaining actions inherit current schedule cursor. Store must be final at both program/duty boundaries, enforced by authoring and runtime validation. Relative waits retain existing STOP duration semantics.

Invocation element: `{id,type:'PROGRAM',call:{id,programId,tick,arguments}}`; arguments are typed literals. `boundPrograms` stores one snapshot per program. Generated JS defines used program objects once, then calls `scoped.at(call.tick).program(programId,definition,args)` inside existing `t.origin(duty/element/call)`. ScriptWorker appends `/program:<id>/step:<id>` to each generated action trace. It validates the complete expansion before appending any steps and requires prior CHECK or service-boundary acquisition.

Initial acquisition remains the existing duty TAKE_FROM_DEPOT/TAKE_FROM_WAYPOINT selector. There is no callback acquisition control and no library ACQUIRE node in this sequential subset. The full approved acquisition-capable program/branch/coupling/separation work remains later scope, not accepted by completion of this subset.

## Registered extension actions (approved addition)

New step: `{id,type:'EXTENSION',typeId,version,parameters:Value<JsonObject>}`. typeId is a static registered namespaced ID and version a positive32-bit integer. Parameter type JSON_OBJECT binds the complete object; nested literals are retained, not recursively scanned for parameter references. Empty object{} is valid; empty input, nonobject or malformed JSON is invalid. The UI chooses registered descriptors from current world metadata and displays their advisory schema; it does not claim a JSON-Schema form engine or invent installed providers. Library retention of an unavailable provider is visible, while compilation still requires the actual exact type/version registration and provider.validate.

All three structural mirrors enforce JSON depth<=16 (object root at0), node count<=4096 (including containers and null), individual keys/strings<=8192 UTF-16 units, finite IEEE754 numbers, and safe integers whenever the numeric value is integral. No runtime JavaScript, nested-field substitutions, arbitrary-precision number contract or silent numeric rounding. Content identity normalizes finite numbers (including1/1.0 and signed zero), after validation rejects unsafe integral values; this survives JSON roundtrip through the browser. Definition/argument JS embedding uses JSON.parse of quoted JSON data to preserve special object keys such as __proto__.

The JSON field retains local raw text, applies on blur/Ctrl+Enter once, discards on Escape and invalidates drafts with field identity. Parsing/validity feedback does not update authoritative timetable data on each keystroke.

Error navigation resolves only current duty/element/call/program/step identities or a compilation trace whose document input still matches. The duty page shows the immutable bound step read-only; an explicit Open library action navigates to the shared editable definition. Editing and adopting remain separate actions. No second editable bound-definition owner is created.
