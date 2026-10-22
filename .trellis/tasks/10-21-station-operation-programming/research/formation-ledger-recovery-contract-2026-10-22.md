# Formation ledger and interrupted-publication recovery

Status: implementation contract, not implemented. Supplements the admitted-formation proposal; no separate controller or physical consist owner.

DepotStore stages a purpose-bound whole-unit change under its existing lock. Inputs identify exact source train/assignment IDs and complete ordered Core car membership. Outputs identify exact anticipated train IDs, full ordered unit partitions, and assignment or explicit held disposition. Two inputs/one output is coupling; one input/two outputs is separation. Every input unit appears exactly once in outputs, no internal unit splitting, no unrelated stock/assignment is overwritten, and all source stock is IN_SERVICE with departure occupancy cleared and no storage reservation. Unit home affiliation remains unchanged.

A prepared event prevents ordinary acquisition/release/storage/reset from changing its source claims. Preparation preserves live input claims and records expected immutable membership; it does not manufacture output stock. Basic must hold infrastructure before the Core physical action. Commit consumes a coherent observed output formation and actual derived stock metadata; it checks the prepared identity and complete fixed-unit membership again, then replaces all participating stock/claims atomically. There is never a release-then-claim gap. Held outputs remain assigned and unavailable, even without a follow-on movement duty. Abort only follows Basic's explicit proof that the original sources remain unchanged and stopped; post-publication rollback is forbidden.

## Persistence is a required part of this transaction

Core publication may precede Basic's next coherent-observation reconciliation. A save can therefore observe a survivor/new child while the ledger still retains original source owners. The current B1 `restoreOperationalLedger` deliberately rejects claims whose train ID does not match physical membership; simply persisting the old claims would make a legitimate interrupted B3 save unloadable.

Add a default-empty collection of unresolved fixed-formation records to the existing current runtime boundary. Each record contains fixed event identity, exact source assignments and car/unit sets, explicit output identities/partitions/dispositions, and the last known operational phase. It stores neither live Core/Basic handles nor a promise of successful publication. A saved phase is not physical authority.

At restore, validate the actual restored Core composition against the record:

- Exactly unchanged sources: preserve original claims and require intervention; do not resubmit the physical action.
- Exactly expected complete outputs: retain the unresolved event as a recovery hold over all constituent units and expose all physical outputs, regardless of whether the last saved phase was pre-publication. Do not synthesize active missions, AVAILABLE stock or successful timetable events.
- Neither exact relation, missing/duplicated cars, partial units or conflicting events: fail restoration with contextual diagnostics before publishing operational state.

Unit definitions are validated against actual Core formations in every case. Do not invent temporary fake source formations to trick the existing validator. Normal claims retain the existing train-ID invariant; only units explicitly covered by a validated unresolved event have the pending-event ownership relation. This is a new current-model capability, not a reader for old development formats. The runtime schema version remains unchanged for defaulted additive fields under the project policy.

A post-publication operational exception keeps the real physical outputs stopped, keeps Basic's aggregate holds, and preserves this unresolved record. The public issue/report must identify event, sources, observed outputs and the failed stage. Reset/stop may not discard the record or units while any physical or Basic hold remains; recovery must be an explicit existing-owner operation with its own full preflight.

Required tests: snapshot before publication; after physical coupling before ledger commit; after physical split before ledger commit; callback loss; wrong saved phase with exact outputs; missing/duplicated car; conflicting unit claims; unknown output; restore does not replay; safe-held output remains unavailable. Same-runtime coupling/split tests must exercise this capture/restore boundary, beyond isolated ledger round-trips.

## Implemented ledger checkpoint

The staged ledger, snapshot field and restore fencing described above are now implemented. `FixedFormationOwner` includes expected operating direction as well as exact ordered cars. Preparation/commit/restore compare actual Core direction; same car IDs with a wrong direction reject. Assignment conflict checks include staged outputs and committed structural stock, not only old depot allocation tokens; conflict throws at physical selection so Dispatcher cannot interpret it as optional absent metadata. Structural output storage uses its exact storage reservation and current assignment, without requiring a retired acquisition token.

The latest focused gate passed 143 tests, including seven formation ledger tests and two actual Core before/after-split save/load tests, with one 1GiB worker per test class. This establishes the ledger/recovery foundation, not the unimplemented full fixed-event timetable executor, admitted coupling movement or output departure. Source claims deliberately remain unresolved after load; no automatic release/replay. Opaque Core fences are rebuilt and retained privately by SimpleATOWorldData.
