# B3 fixed formation authoring / DSL contract proposal

Read-only source research after the B2 extension checkpoint. This is a proposal for the runtime/compiler owners, not an implemented API, accepted schema or enabled UI. Existing evidence remains141 frontend tests,20 WebUI tests and40 focused Bukkit tests green; no new source edits or Gradle run for this research.

## Verified owners and gaps

- `SimpleATO/.../timetable/Timetable.kt`: TimetableService owns one TrainSelector and a linear steps list. TrainSelector is AnyTrain/TrainId; actual acquisition is a first action. It has no event-output acquisition or formation handoff today.
- `BukkitPlugin/.../scriptworker/Dsl.kt`: ServiceTrainAcquisition already carries a scheduled first action into a service, while service callbacks append normal actions. This is the correct shape to extend for a new output-source selector, rather than invent a second train selector owner.
- `SimpleATO/.../api/depot/FixedFormationChange.kt`: operational change records contain actual train/assignment/car IDs and conserve all cars. Both output disposition variants retain explicit ownership, including HOLD.
- `SimpleATO/.../api/depot/FixedEmuUnit.kt` and DepotStore: permanent unit IDs and car membership are separate from Core physical train containers. `fixedUnitsForFormation` resolves complete units in physical order; claims and structural changes require exact ownership.
- `Basic/.../api/route/FormationChange.kt` and PreparedFormationAdmission: prepared changes retain original grants and publish held output evidence. OUTPUTS_HELD explicitly does not authorize departure.
- `Core/.../physics/train/TrainCoupling.kt:inspectApproach`: moving participant must approach using its operating head. The stationary end is part of the exact physical proof. Contact geometry is solved by Core, not supplied by a timetable.

Therefore visual authoring must refer to event/duty/output identities. It must not require physical train IDs, car IDs, assignments, grants, process-local handles or publication proofs from the user.

## Central fixed-event catalog

Propose one optional/default-empty `formationEvents` catalog on the authoring/runtime timetable, compiled alongside services. One central declaration prevents each participant from choosing a different partner, meeting target or output disposition. Nested items do not get independent schema versions. Any actual persisted changes still need the shared release-baseline policy; this proposal does not authorize a version bump.

Coupling example:

```json
{
  "id": "join-east",
  "kind": "COUPLE",
  "coordinatorDutyId": "approach-duty",
  "approachingDutyId": "approach-duty",
  "stationaryDutyId": "standing-duty",
  "stationaryEnd": "TAIL",
  "rendezvous": {
    "approachDestinationId": "yard-approach",
    "stationaryDestinationId": "yard-standing"
  },
  "maximumApproachSpeedMetersPerSecond": 1.0,
  "output": {
    "id": "combined",
    "disposition": {"type": "DUTY", "dutyId": "approach-duty"}
  }
}
```

The coordinator is exactly one of the two distinct participants. Both duties explicitly reach this same event. The approaching end is the actual operating HEAD; it is not freely configurable against the Core contract. HEAD/TAIL for the stationary participant resolves to A/B using the actual published formation frame. Speed is a positive finite static upper bound, still constrained by real train/authority capability;1.0 above is illustrative, not an approved operational default.

The two rendezvous references identify directed pre-contact roles/anchors, not an instruction to place both heads at one marker. Actual controlled contact uses the single event's accepted target and Core proof, normal Basic admission and stopped preparation. No teleportation or independent alternative platform choice. B4 may extend the event's jointly selected target to validated candidate pairs; participants must not independently choose incompatible targets.

## Split partition and output identity

```json
{
  "id": "divide-west",
  "kind": "SPLIT",
  "coordinatorDutyId": "combined-duty",
  "partition": {"from": "HEAD", "firstUnitCount": 1},
  "outputs": [
    {"id": "first", "group": "FIRST", "disposition": {"type": "DUTY", "dutyId": "combined-duty"}},
    {"id": "rest", "group": "REST", "disposition": {"type": "DUTY", "dutyId": "branch-duty"}}
  ]
}
```

SPLIT has one source: the coordinator's actual assigned formation. firstUnitCount is a fixed positive integer and must be less than the actual whole-unit count. from HEAD means the actual operating head at the event; the entire partition is resolved once and frozen against that composition/mission identity. Use DepotStore's ordered complete units to locate the real car boundary. An internal-unit cut, absent unit registration, incompatible control configuration, empty output or later composition change rejects before mutation.

FIRST/REST are logical output groups, independent of which Core container ID is retained. In a reversed source, the HEAD group need not be Core's retained car-list prefix. Runtime must map groups by actual unit/car membership rather than assume that the original train ID always belongs to FIRST. If the lower operation supports only a stricter partition/orientation, reject or settle that contract before exposing this form; do not silently swap output duties.

Each event output has a stable local ID. Exactly two distinct output IDs/groups are required for SPLIT; COUPLE has one. A disposition is either `{type:'DUTY',dutyId}` or `{type:'HOLD'}`. No implicit depot-AVAILABLE state, discarded formation, or free train. HOLD gets an event-scoped safe assignment and retained movement/resource holds. An output group cannot be assigned to two duties; two split outputs cannot both become the same single-train duty.

## Existing service shape and output handoff

For an output explicitly continuing an already participating duty, keep that one run/Dispatcher owner and atomically replace its actual formation identity after publication, ledger reconciliation and output authority preparation. For COUPLE the other consumed participant cannot continue movement under its old assignment; its event wait finishes only after the coordinated transition is safe. Do not merely mark it COMPLETED and release its old claims.

For an output assigned to another duty, propose a new service-boundary acquisition source:

```ts
timetable.service('branch-duty',
  timetable.takeFromFormationOutput('divide-west', 'rest', 18000),
  t => { /* validated onward actions */ })
```

Its visual selector would be `{type:'TAKE_FROM_FORMATION_OUTPUT',eventId,outputId,tick}`. It resolves one exact already-created event output, not any compatible train or a second depot allocation. The catalog must point this output to this target duty, and the selector must point back to that exact output. Before the event completes the target waits observably. After success there is one assignment/control handoff and one movement owner, never a duplicate claim.

The runtime event instance must include timetable identity and source cycle as well as eventId. A later cycle must not accidentally claim an old held output with the same name. B3's minimal same-cycle link may finish after the period boundary while retaining its original cycle identity; this does not itself prove B4 circulation timing. Deliberate cross-cycle handoffs need explicit static cycle-edge/lag semantics agreed with B4 before they are exposed. Do not guess the newest output or erase unfinished old-cycle events.

TrainSelector.AnyTrain plus a new first acquisition action follows the current shape; no authored physical ID is necessary. Update initial-acquisition validation, first-movement state propagation, runtime selection, snapshots, isolation and UI together. A Check at a guessed nominal destination is not a substitute: split outputs have real positions/directions, potentially between markers. Retained onward-route evidence must originate from the actual selected output state under its operational revision. This is a required runtime gap, not a browser mapping problem.

## Program AST / JS construction

Propose typed library steps `{id,type:'COUPLE',eventId:Value<string>}` and `{id,type:'SPLIT',eventId:Value<string>}` with a dedicated FORMATION_EVENT_ID parameter type. References resolve from literals/static call arguments only. Event partner/output/partition declarations stay in the central fixed catalog so two calls cannot rebind opposite sides differently.

Possible DSL surface, pending the common runtime contract:

```ts
timetable.formationEvent('divide-west', {kind:'SPLIT', /* fixed declaration */})
t.at(18000).split('divide-west')
t.at(12000).couple('join-east')
```

The library expansion emits these same structured actions; it does not execute physics or fetch mutable library state. Event declarations may be validated at final build so source order is not an accidental dependency. The existing duty/element/call/program/step trace remains; contextual event IDs can be added without replacing that origin.

After parameter binding and invocation expansion, the common validator must reject structural/storage actions under any runtime conditional depth, any conditionally invoked program, and conditionally activated services. Equivalent events in both branches are still invalid. Runtime-dependent event IDs, counterpart duties, split partitions and output destinations are forbidden. A compile-time ordinary JS choice produces one fixed definition and must not be confused with runtime branching. Do not rely on hidden controls as enforcement.

## Required invariants and tests before UI

- Exactly one coordinator, distinct/complete participant references, one declared event invocation per participant occurrence, no self-coupling or conflicting event ownership. Detect missing references, cycles/deadlocks and contradictory dispositions contextually.
- Resolve actual assigned unit identities, stopped geometry and control compatibility; freeze mission/composition before one preparation/publication path. Poll a pending ticket instead of starting every tick.
- Preserve all source assets/grants until exact published outputs and ledger ownership are reconciled. OUTPUTS_HELD is not movement permission. Replan/claim actual output routes before releasing Core holds; failures retain protective claims and require explicit recovery.
- Missing/late counterpart waits or fails visibly; never invent a partner or automatically select a different duty. Restarts restore unresolved intent/holds, not executable process-local tickets or automatic replay.
- Test two complete units -> one -> two with reversed source ordering, preserved unit homes, split between units only, one consumed duty, both continuing/new/HOLD outputs, duplicate output claims, failures before/after publication, exact cycle ownership and old-cycle overlap.
- Keep structural conservation, schedule feasibility, simulation and game evidence separate. Existing Core collision/split and Basic held-output tests alone do not establish a scheduled rendezvous lifecycle.

No structural UI/AST/runtime implementation was made for this proposal. The parent/runtime owners must settle catalog/partition/handoff/cycle details before enabling authoring controls.
