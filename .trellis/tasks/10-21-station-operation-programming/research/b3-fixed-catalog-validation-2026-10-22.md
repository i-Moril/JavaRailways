# B3 fixed event catalog / DSL checkpoint — 2026-10-22

## Scope and owner boundary

This checkpoint implements an immutable domain catalog, static reciprocal validation, current snapshot projections and literal/static JavaScript DSL. It does not enable structural WebUI controls or implement Dispatcher structural execution, physical acquisition, formation mutation, output route admission or release. Root owns Dispatcher execution/start guards; other workers own Core/Basic and operational route foundations.

Owned source/test files:

- `SimpleATO/.../timetable/FormationEvent.kt` (new).
- `SimpleATO/.../timetable/Timetable.kt` (additive field/actions and narrow validator integration; preserve earlier retained-route edits).
- `SimpleATO/.../persistence/TimetableSnapshot.kt` (current DTO mirrors and capture/restore).
- `SimpleATO/.../timetable/TimetableDumper.kt`.
- `BukkitPlugin/.../scriptworker/Dsl.kt`, `Model.kt` (preserve previous sequential/extension program work).
- `SimpleATO/.../timetable/FormationEventTest.kt` and `BukkitPlugin/.../scriptworker/FormationEventScriptTest.kt` (new).

## Concrete contract

`Timetable.formationEvents` defaults to an empty `TimetableFormationCatalog`. The catalog implements read-only List, defensively copies incoming collections and supplies List value equality/hashCode, preserving Timetable default/roundtrip equality. Declarations contain immutable scalar/nested values, not physical train/car/grant identities.

- `TimetableFormationEvent.Couple`: stable event ID; one coordinator among distinct approaching/stationary duties; stationary HEAD/TAIL; directed approach/stationary rendezvous destination IDs; finite positive maximum approach speed; exactly one explicit output.
- `.Split`: stable event ID/coordinator, positive `firstUnitCount`, explicit `first` and `rest` outputs. Partition is always from actual operating HEAD and in complete operational units. Actual unit count, membership/order and physical boundary remain runtime checks; the catalog cannot prove those from names alone.
- `TimetableFormationOutput`: event-local ID and `FormationOutputDisposition.Duty(dutyId)` or `.Hold`. Two split outputs cannot share output identity or duty owner. HOLD has no implicit depot availability or claim recipient.
- Actions: `Couple(eventId)`, `Split(eventId)`, `TakeFromFormationOutput(eventId,outputId)`. Acquisition is first `At` with AnyTrain, targets exactly the matching DUTY output, and has no cycle-lag/latest-output selector. Its runtime meaning is the exact same source timetable cycle.

`FormationEventValidator` is pure static catalog/service validation. It checks declared/duty uniqueness where referenced, statically scheduled participants/recipients, one matching-kind invocation per participant, no foreign participant calls, explicit consumed-duty termination, exact output/acquisition reciprocity, continuing-duty no-reacquisition, HOLD non-acquisition and exclusive new output claims. It checks acquisition dependency cycles and event-order dependencies from each participant action sequence and acquisition source event to later invoked events; crossed rendezvous ordering rejects with event IDs. Local constructors reject self-coupling, bad numeric partition/cap and contradictory output ownership.

World `TimetableValidator` additionally checks rendezvous destination existence and preserves normal tick/acquisition/destination rules. It clears pre-event origin/direction/profile assumptions after structural actions. Without a retained output-route proof, onward movement/storage emits `formation output has no retained onward-route proof`; a valid pure catalog is not evidence of a valid output movement route. This is an explicit unfinished integration boundary, not a runtime replanning fallback.

Runtime conditional branches still only accept extension actions, so structural actions cannot be nested. Fixed event participants/recipients require scheduled activation. Root added explicit Dispatcher start/action rejection until execution is implemented, preventing partial normal acquisition under unsupported structural declarations.

## Current saved/DSL shape

Snapshot and ScriptWorker DTOs mirror stable `kind: COUPLE|SPLIT`, disposition `type: DUTY|HOLD`, split `partition:{from:HEAD,firstUnitCount}` and `outputs:[{id,group:FIRST|REST,disposition}]`. COUPLE has `output`, rendezvous and approach settings. Invalid mixed shapes, unknown keys, duplicate groups/owners and TAIL/partial-car partition requests reject. `formationEvents` defaults empty, action event/output fields default null. These are additive current-format fields; no schema version change or legacy reader was added.

DSL:

```js
timetable.formationEvent('divide', {
  kind: 'SPLIT', coordinatorDutyId: 'source',
  partition: {from: 'HEAD', firstUnitCount: 1},
  outputs: [
    {id: 'head', group: 'FIRST', disposition: {type: 'DUTY', dutyId: 'source'}},
    {id: 'tail', group: 'REST', disposition: {type: 'DUTY', dutyId: 'branch'}}
  ]
});
timetable.service('branch', timetable.takeFromFormationOutput('divide', 'tail', 100), t => {
  t.at(200).destination('branch-end');
});
// In each declared participant's existing acquired duty:
t.at(100).split('divide');
// Coupling participants similarly use t.at(tick).couple('join').
```

References must be actual string values, not arbitrary selector objects/numbers; ordinary compile-time JS choices yield fixed declarations. No runtime rebinding, conditional structural builder method, arbitrary JS reverse parser or enabled UI operation was added. Existing `origin(...)` trace follows the emitted structural action. Declaration source order is independent of service source order; reciprocal semantic validation occurs on the decoded domain model, like other world validation.

## Verification

Successful bounded command in the dedicated station-operation-programming worktree:

```sh
./gradlew -I /tmp/jr-program-tests-heap.init.gradle --max-workers=1 \
  :SimpleATO:test --tests '*FormationEventTest' --tests '*TimetableSnapshotTest' --tests '*TimetableValidatorTest' \
  :BukkitPlugin:test --tests '*FormationEventScriptTest' --tests '*StationProgramScriptTest' --tests '*ScriptWorkerCompatibilityTest' \
  -x :WebUI:installFrontend
```

`BUILD SUCCESSFUL in 15s`; log `/tmp/jr-b3-formation-catalog-gate.log`. Tests use 1 GiB heap, one worker/fork. XML UTC timestamps:

- FormationEventTest: 10 tests, 08:58:03.
- TimetableSnapshotTest: 3, 08:58:03.
- TimetableValidatorTest: 15, 08:58:04.
- FormationEventScriptTest: 5, 08:58:16.
- StationProgramScriptTest: 8, 08:58:16.
- ScriptWorkerCompatibilityTest: 1, 08:58:15.

Total 42; zero failures/errors/skips. New tests include defensive copy/value equality/default-empty current decode, snapshot/dump roundtrip, reciprocal new/continuing/HOLD outputs, bad partition/ownership, missing/duplicate claims, wrong event kind, conditional rejection, service dependency cycles, crossed participant event order and a legal consistent coupling chain. A world validator regression asserts that a valid split catalog invents no retained route from its pre-event origin. Real Graal JS tests cover typed strict DTO generation, same-cycle acquisition shape, origin trace and rejection of malformed/static-reference/branch calls. No runtime structural completion is claimed.

The separately requested follow-up `:SimpleATO:test --tests '*FixedUnitSplitPlanTest'` did not reach tests: concurrent in-progress Basic `FormationOutputAdmission.kt` failed compilation (missing movementHeld/prepared-claim methods/COMPLETED and nullable frame accesses). Log `/tmp/jr-b3-fixed-unit-split-plan-gate.log`; its owner/root were notified and the Gradle slot released. This is not a split-plan assertion result and does not change the successful catalog gate above.

No frontend changes, game/client acceptance, deployment, commit or archive in this checkpoint.
