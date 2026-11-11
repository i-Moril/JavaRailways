# B3 admitted formation handoff — proposal for review

Status: source-grounded design, **not implemented**. No existing API provides this end-to-end transaction. The scope below is fixed-event coupling and fixed-unit-boundary split in one world under one authenticated Dispatcher binding. It deliberately excludes arbitrary owner transfer, train-ID occupancy exemptions, and cross-plugin coordination.

## Existing seams and limits

- `Core/.../physics/train/TrainCoupling.kt:linkFromCollisionOnOwner` is collision-only. It proves unique recorded coupler contact, same-epoch source frames, captured topology/consist dependencies, at least one stationary participant, and prepares physical/controller/extension changes before publication. There is no public arbitrary `link(first, second)` operation.
- `TrainLinkEvent` permits veto, survivor and direction choices; `TrainSplitEvent` permits veto/directions. Decision-listener failure prevents physical submission. Decision callbacks must not mutate operational records. `TrainLinkSuccessEvent` and `TrainSplitSuccessEvent` occur after full physical publication; failures are isolated and cannot undo it.
- `CoreTrain.splitBefore` retains the stable prefix/controller and creates a new stable suffix at rest. Core structural stop and derived-dynamics preparation do not reconcile timetable/depot assignments or Basic grants.
- `CollisionEnvelope` and its coupler bridge/dependency proof are internal. Reimplementing connector geometry in SimpleATO would duplicate Core's physical truth. No existing public host-derived approach/contact descriptor was found.
- `RouteResourceAdmissionEngine.requestValidated` checks occupancy excluding only the requester train and acquires/appends one logical owner's resources. A partner ID cannot simply be removed from this query: both precise body geometry and claim conflicts still matter.
- `consumeObservation` drives each grant's original train ID; `RouteGrantTailRelease` fails closed when that train disappears. `endGrant` refuses held resources. Neither supports split/merge rebinding. Existing reversal suspend/rebase retains one train/owner and is insufficient.
- Core registry already supports authenticated shrink of each original owner. A new generic multi-owner transfer primitive is avoidable if Basic retains original claims and owns their aggregate release lifecycle.

## Exact invariant

At every observable stage, each pre-existing infrastructure resource remains held by its original registry owner until authoritative post-change physical clearance proves that **every output requiring that resource** has cleared it. No release/reacquire window, force release, copied claim, or disappearance-as-clearance is allowed.

Each source train, unit claim and mission has at most one prepared event. During approach the target has zero movement authority, the approaching train has only bounded contact authority, and ordinary unrelated occupancy/conflict checks remain active. Following physical publication all outputs remain stopped until Basic rebinding and the depot/Dispatcher mapping have both committed. Coupling creates exactly one movement owner; splitting creates an explicit disposition for both outputs, each either one assigned movement owner or an operationally safe-held formation. Safe-held means retained unit/infrastructure ownership and zero movement authority; it is not AVAILABLE stock.

## Proposed minimal API boundary

Names below are proposed API, not current symbols. All calls are owner-thread, immediate/nonblocking and scoped to the existing authenticated world-local service. Handles are opaque process-local identities and never persisted.

1. Core exposes a **read-only host-issued coupler approach proof**, not a linking command:

   `inspectCouplingApproach(approacher, approachEnd, stationaryTarget, targetEnd, selectedTraversal): CouplingApproachInspection`

   Accepted inspection contains immutable participant/car/end identities, observation/dependency identity, exact canonical approach corridor and contact boundary, allowed contact-overlap geometry and maximum valid correction/contact envelope. Core computes these from its own connectors/traversal, rejecting ambiguous/non-facing/unsupported geometry. The caller cannot supply an arbitrary allowance interval or ignored train ID. The proof must be revalidated against each newer observation while approach authority is active; any target motion, unexpected consist/direction/topology change, unrelated occupant or geometry mismatch revokes further approach authority and stops the operation. The final collision still runs Core's existing physical contact solve and decision event.

2. Extend Basic `RouteResourceAdmissionService` with purpose-bound methods:

   - `prepareFormationChange(intent: FixedFormationChangeIntent): FormationPreparationResult`
   - `requestCouplingApproach(handle, intent: RouteResourceIntent): FormationApproachResult`
   - `sealFormationPublication(handle): FormationPublicationResult`
   - `reconcileFormationPublication(handle): FormationReconciliationResult`
   - `abortFormationChange(handle): FormationAbortResult`

   Intent is a sealed `Couple` or `Split`, containing one fixed event ID, exact source grants/owners/train IDs/car sets, coherent observed directions and expected output IDs/car order/dispositions. Couple adds exact two ends, designated survivor/direction, positive finite approach speed ceiling and host-issued proof. Split adds stable `beforeCarId` at a whole-unit boundary and exact output directions. No caller-supplied arbitrary owner set, occupancy exclusion set or resource list. Both source grants must belong to the same authenticated service binding; missing, busy, stale, wrong-world, pending-provider or unowned participants reject before mutation. Stopped sources without a grant first acquire an ordinary host-owned body hold; absence of a grant is not absence of physical protection.

   `prepare` is atomic within Basic and retains all original claims. It freezes ordinary grant extension/reversal/end for those participants and installs one coordinated release owner. The target remains stopped; only the designated approach path can move. All provider preparation is polled through existing nonblocking preparation semantics, never bypassed.

   `requestCouplingApproach` compiles the ordinary requested geometry. Only overlap explained by the host proof's exact target contact envelope may be accepted; target occupancy elsewhere and **all third-party occupancy** still reject. Already-held partner resources are covered through the prepared transaction rather than reacquired. Provider descriptors/preparations must agree; a conflicting switch/section configuration rejects even if the other owner is the partner. New approach resources are acquired normally and remain held after timeout until actual tail clearance. Permission must cap movement at the host contact boundary and enforce the dedicated speed ceiling in the actual controller/trajectory path. A speed value in a DTO or a late event veto alone is insufficient.

   `seal` requires both stopped for split, or a valid bounded approach/contact authorization for coupling; all output assignments/units/dynamics preparation must already be validated by SimpleATO. Basic suspends **all** participant tail-release consumers before physical submission/publication and returns one seal receipt. This is the boundary after which an arbitrary caller cannot report an abort. A decision-event veto/physical preparation rejection, with coherent evidence of unchanged original structures and stopped sources, can explicitly cancel that seal; otherwise retain it for recovery.

   `reconcile` is host-observed, not `commit(handle, callerReportedSuccess)`. A lifecycle-scoped Core decision/success adapter matches the exact sealed event/source instances/car IDs/ends and records the physical result. Success notifications alone do not release claims. The next coherent observation must contain the complete exact expected output car sets/directions, with no lost/duplicated car. The same owner operation installs Basic output/body bindings and staged depot/Dispatcher mappings while all outputs remain stopped. Only after both are installed can an output receive fresh movement authority. Any exception or disagreement enters held recovery, with no physical rollback claim.

3. Basic internal aggregate holds replace the participant release consumers for this fixed event. Keep each old registry owner and its provider resources; do not rewrite owners in Core. Coupling maps both hold sets to the survivor's proved body/retained route projections. Split maps each retained resource to every output that needs it; shrinking that original resource requires clearance from all such outputs. Unknown projection, uncertain overlap or an unassigned stationary child retains the resource. Later departure uses ordinary admission for genuinely new resources, while the aggregate hold supplies exact already-held coverage. Retired clients cannot extend/release these owners. Requester/provider activity and plugin-disable checks must include aggregate component holds, so a retired route is not incorrectly reported idle.

4. SimpleATO stages a narrow `PreparedFixedFormationMapping` inside DepotStore/Dispatcher: expected ledger revision/assignments/full units, resulting exact Core formation IDs and output disposition. Prepare does not alter live claims. Commit after observed publication replaces the whole participating mapping atomically under the shared ledger lock; no release-then-claim gap. Existing ordinary single-formation claim APIs must reject prepared units. A post-publication failure retains the prepared mapping and old claims as a recovery hold. One resulting mission/cursor owner is installed for coupling; both split outputs are recorded. No operational writes occur in decision callbacks.

## State/failure contract

`PREPARED -> PUBLICATION_SEALED -> APPROACHING (couple only) -> PHYSICAL_OBSERVED -> OUTPUTS_HELD -> ACTIVE/SAFE_HELD`. A contact-reaching approach permission must not be applied to the controller before publication is sealed; an unsealed ordinary approach must stop outside contact. This avoids an automatic collision publishing before participant release is suspended.

- Reject before prepare: no mutation.
- Abort while prepared/approaching: revoke approach movement; require an observed safe stop; cancel only uncommitted provider work, restore original participant bindings. Retain every acquired resource until ordinary authoritative tail clearance; being an aborted event is not clearance. Only then can the event handle finish without recovery.
- Veto/preparation rejection after sealing: unseal only after Core outcome plus observation prove unchanged stopped original structures. A lost callback or timeout is uncertain, not a successful abort.
- Any physical publication: irreversible. Missing callback, observer disagreement, ledger revision mismatch, mapping exception, provider failure or plugin unload keeps outputs stopped, claims retained and recovery required. No success, AVAILABLE state or auto-resume is synthesized.
- Recovery persistence records physical current composition plus operational unresolved event/output disposition; no live handle/grant serialization. Current snapshot restore remains fail closed. The precise additive recovery DTO must be agreed before implementation; this proposal does not claim the current B1 snapshot can already recover an in-flight B3 transaction.

## Required proof tests before claiming B3

1. Real same-runtime ATO approach reaches exact stationary counterpart and Core physical collision coupling, yields expected survivor/car order, one mission owner, complete unit mapping, no resource gap. Actual lower contact speed is observed in published frames.
2. Partner body beyond the contact envelope, wrong end, moving target, wrong car set, stale topology/observation, third train in the corridor and third-owner claim each reject. Same partner with incompatible provider preparation also rejects.
3. Inject failure at every prepare/provider/seal/decision/pre-publication/post-publication/ledger stage. Before publication prove originals remain intact; after publication prove exact physical result remains stopped and every source hold remains protected. Success-listener exceptions cannot produce fake rollback.
4. Duplicate/reordered callbacks, duplicate reconcile/abort, unrelated manual collision/split, conflicting simultaneous event and plugin unregister cannot double-commit or release another event's resources.
5. Actual Core split at a whole-unit boundary creates both expected outputs; partial-unit split rejects. Shared resource remains held when one output clears and the other remains. Each output has exactly one assignment or explicit held disposition; missing train never counts as clearance.
6. Subsequent survivor/output departure extends normally through aggregate-held resources; old owners/provider activity remain visible until all appropriate tails clear. End/disable/reset cannot discard unresolved holds. Recovery load preserves actual car identities and requires intervention rather than replaying the event.

## Review decision needed

The smallest viable implementation still needs the host-issued contact proof, Basic aggregate hold/rebinding, staged ledger mapping and coordinated physical event consumers. Existing Core collision/split commits can remain authoritative. Approve these narrow seams and their same-runtime tests before B3 product writes; implementing just event listeners or adding an `ignorePeer` parameter would not satisfy the invariant.
