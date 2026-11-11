# Design — station operation programming

Status: user approved the revised plan on 2026-10-22; implementation in progress in the isolated station-operation-programming worktree.

## Authoring, generation and execution

World-scoped shared visual program definitions -> bound immutable definitions in timetable authoring -> generated embedded JavaScript -> existing sandbox compiler extended to produce validated structured actions/branches -> existing Dispatcher and operational runtime.

JS remains a construction language. Runtime conditions become structured condition nodes; generation does not evaluate live world conditions or run a second JavaScript control loop. The visual library is the editing source; arbitrary handwritten JS is not reverse-imported. Compiled metadata cannot bypass SimpleATO validation.

Reuse existing service ownership, operation tickets and movement/reversal code. Introduce only the structural nodes and execution cursor needed to compose and branch those actions. Do not create a second train controller or duplicate Dispatcher lifecycle.

## Shared program library

Definitions are scoped to a world and have stable ID, editable name, revision, typed parameters and stable step IDs. Revision detects edits; it is not a schema version. Begin with self-contained programs; nested/recursive program calls are outside this batch.

Use the authenticated WebUI authoring repository layer, isolated by world, for this authoring-only library. Runtime uses embedded compiled definitions, not a mutable library registry. Library create/read/update/archive uses server validation and atomic file replacement. Updates carry expected revision; conflicts return an explicit conflict instead of overwriting another editor. Exact route names are to be written in the transport contract before code changes.

Timetable documents retain library references plus immutable bound definition snapshots and provenance (program ID, revision and content identity). These are compilation inputs, never another editable library owner. Multiple calls reuse one bound snapshot per program in a document. Loading a document does not silently upgrade it. The editor shows newer library revisions and offers explicit adoption; acceptance is one document/history change and invalidates previous compilation/simulation. An archived library definition cannot be newly selected, while existing bound snapshots remain reproducible. There is no physical deletion UI in the first batch.

Generation reads only one coherent bound document snapshot. It emits each used program definition once and embeds call parameters; no external JS fetch is needed. Persisting the generated source uses existing formal-script save/rollback behavior. Updating or archiving a library entry cannot change an applied timetable. Applied plan replacement follows existing drain/validation rules.

## Program shape and semantics

A program contains typed parameters, a finite structured sequence/conditional tree, preconditions and outcome diagnostics. Parameters reference actual world IDs, directed destinations, candidate sets, tick values and registered extension parameters; validate after binding, rather than interpolating arbitrary source strings.

Initial actions: acquire from physical depot; select compatible stopping target; move with optional via points; wait until timetable tick; wait duration from actual entry; stopped reverse; invoke registered extension; fixed complete-unit coupling/separation; store in a depot. Service ownership restricts acquisition to an unassigned start and storage to ending vehicle use. A program may have several resulting positions/directions; no invented common endpoint is required.

Absolute times use the existing timetable cycle clock. A missed earliest-start time does not skip required actions. Relative waits start when their step actually begins. Reuse station arrival/dwell/door completion gates, with one explicit handoff into the station program. Avoid duplicate automatic and programmed door operations. Keep extension unavailable/error distinct from false; report failure through the existing stopped/intervention lifecycle, never execute an arbitrary alternative on observation failure.

Each invocation/step is attributed to timetable, duty element, program and step identity. Start asynchronous operations once and subsequently inspect their ticket. Repeated polling must not restart movement, reversal or storage.

## Cross-depot vehicle lifecycle

Home affiliation, actual stabling-point reference and current assignment are distinct. Taking from B means physically acquiring a suitable train at B, regardless of home affiliation; real stopped geometry and existing control/assignment checks remain authoritative. Storage may target a different depot while retaining home affiliation. Complete storage only after the full envelope and existing resource-clearance gates succeed; reservation-to-occupancy and AVAILABLE transition remain atomic.

Audit acquisition, reserveStorage, cancellation, ledger restore and Depot Reset together. In particular, restore must accept valid foreign-depot storage without weakening point uniqueness, compatibility or assignment checks. Scope Depot Reset by actual physical storage/reservations associated with the selected depot, not by home affiliation alone; preserve its existing full preflight, publication and partial-commit behavior. An in-service train without a physical storage association is not pulled into a reset merely by home ownership. Explicit home transfer is outside this batch.

## Physical stopping areas and markers

Use canonical destination/infrastructure ownership; Station/berth projections must not become parallel persisted owners. A stopping area/track groups the logical capacity shared by its stopping markers. Geometry and Basic resource admission still enforce physical exclusion. Shared identity must be explicit, not inferred by proximity or display name. Initial logical capacity is one train per configured stopping area; independent areas must have independent capacity and compatible physical geometry.

A marker has physical location, approach direction, applicable train-length constraints and stopping/door configuration. Validate the actual stopping envelope, not only nominal platform length. Length and eligibility are derived from the acquired physical train; a later composition change invalidates affected planning through existing operational revision/replan boundaries.

## Runtime selection and validated continuation

Static candidate eligibility includes length domain, direction, route reachability and onward feasibility. It need not require every candidate to fit every allowed train, but every supported input state must have an explicit valid continuation or a declared refusal outcome.

At a stopped or otherwise proven safe decision boundary before route divergence, observe current occupancy/reservations, rank eligible candidates by configured priority with a deterministic stable tie break, and attempt the existing allocation/admission mechanisms. Logical selection and physical authority are separate results. Resolve conflicts under their owners; unwind only tentative uncommitted reservations on failure and retain occupied/committed resources. Never retain several alternate platform reservations while waiting for one route. Inspect pending handles rather than resubmit every tick.

Provide fixed-preference and available-candidate priority policies. All busy means observable waiting with existing bounded retry cadence; malformed/unresolvable definitions are errors. No predicted-release optimizer in this batch. Once the selection has become an executing route, changes use existing cancellation/stopped-replan boundaries. There is no background retargeting by changing a target ID.

Nominal station/time intent remains in authoring. Selected marker, final position and direction are run-local results consumed by subsequent segments and displayed in reports. Do not mutate the authored timetable to record runtime choice.

## Branch validation and route evidence

Propagate possible successor states through structured program nodes. State includes position, direction, relevant vehicle constraints, acquisition/storage status and any bound choice used by later actions. Validate both sides of unknown runtime predicates. Merge only semantically equivalent states; do not merge solely by node or endpoint while discarding constraints.

Cache compatible node/state results and route edges rather than enumerate every complete path. Explicit finite resource limits may reject an excessively large program with context; do not truncate candidates silently. Candidate route evidence is retained in the validated plan, indexed by the selected origin/choice; Dispatcher must execute a validated edge rather than discover an unvalidated replacement. Current topology invalidation remains authoritative.

Static infrastructure protection covers all referenced candidate route dependencies without claiming all candidate routes for trains. Actual admission/occupancy covers the selected movement only. Advance onward from the actual selected outcome, not a placeholder original run berth. Thread this contract through timetable serialization, JS builder/parser, snapshots, API projections, simulation and runtime diagnostics.

## Persistence and integration boundaries

Read shared/versioning.md and persistence policy before changing any saved shape. Inventory library, editor document/local recovery, canonical infrastructure, rolling-stock and timetable/runtime snapshot boundaries. Confirm actual frozen/released baseline before any version decision; no speculative bump or legacy runtime fallback. Preserve required development data with audited offline dry-run migration/recovery points when a concrete shape change requires it. Do not replace old data with guessed platform groups.

Existing UI worktree has uncommitted rename/cap and earlier deployed restorations; primary has concurrent signal work. Implementation must use an isolated checkout after reconciling the exact required baselines. Do not overwrite these with an old branch or copy a whole worktree. Planning files alone are being written in the primary checkout.

## Verification and rollout

Five dependent batches are listed in implement.md. Each needs local tests and independent review; overall acceptance requires same-runtime simulation and game evidence recorded separately. Runtime snapshots remain recovery-required after interrupted execution; no blind automatic ticket/action replay. No deployment or migration is performed as part of planning. Runtime changes and UI must be packaged together from a reconciled baseline when release is later requested.

## Confirmed EMU unit model and circulation

The user confirmed complete fixed EMU units only. Each operating unit has a stable unit ID and a registered fixed ordered set of existing stable car IDs. Core remains the authoritative owner of current physical train membership, order, orientation and structure publication; unit definitions describe permanent operating membership, not a second mutable physical train store. Unit membership must be disjoint, complete and physically consistent. Unit registration does not imply driving compatibility: validate independent operation and combination/control compatibility.

Example: physical train T1 contains U1, T2 contains U2. Coupling yields one formation containing U1 and U2; separation yields two formations still containing those same units. Preserve two units throughout regardless of which Core train container ID survives or is newly created. Reject splitting inside a unit before mutation. Home affiliation attaches to operating units, so units from different home depots may share one formation and later separate without losing provenance.

Build on existing Core split/couple and derived actuator-safety hooks. The operational transaction must coordinate both participant assignments, actual endpoint/orientation and contact, stopped preparation/publication, exact resource ownership and subsequent disposition. Reuse the admitted controlled coupling approach where available; do not teleport formations together. One participant/coordinator owns the coupling event, both participants explicitly reference it, and pending arrival/action is observed rather than repeatedly started. Every split output has an explicit next duty or safe-held disposition; it is never silently discarded or automatically marked depot-available. The combined formation has one movement owner. Partial publication failures follow existing stall/recovery rules rather than fabricate rollback of published physics.

Refresh derived length, vehicle count, mass, traction/braking, profile compatibility, marker eligibility and route/authority bindings after successful structure changes. Detect stale program results using composition/mission identity. Simulations must run the same structural and operational paths; existing collision coupling tests alone are insufficient evidence of scheduled rendezvous success.

StoreAtDepot and complete-unit coupling/separation are forbidden inside runtime conditional branches. Fixed cross-depot storage remains supported. Target-binding rules must prevent a runtime conditional depot value from bypassing fixed return planning. Storage may retain a coupled formation; all constituent units retain their identities and home affiliation while actual storage belongs to the physically stored formation. Acquisition must claim the whole formation unless a planned separation operation has explicitly produced independent units.

Repeating timetables require unit conservation, compatible formation availability at each departure time, storage capacity and cross-cycle active-task accounting. Coupling two trains does not consume an asset and splitting one does not create an asset. Check both net depot/unit-class balance and event-time matching; finite extra initial stock cannot repair recurring net loss. Branch-dependent travel times still require bounded scenario/time evidence; no proof is not a guarantee.

## Fixed circulation events and runtime branches

User confirmed that storage, coupling and separation are fixed duty/circulation events. The compiler validates this after expanding visual program invocation bindings, not merely by disabling a UI control. Reject these actions in any nested runtime conditional or any program whose invocation is conditional. A branch is rejected even if both sides happen to contain equivalent forbidden actions; move the event after the branch explicitly.

Fix the return depot, coupling event/partner duty references, separation partition at complete-unit boundaries and output task references in authoring/static call parameters. Runtime conditions cannot rebind these to bypass the restriction. Actual unit identity may be established by the declared acquisition/assignment process; a fixed event references that bound participant rather than requiring all physical train IDs to be known at authoring time. Station approach route/marker decisions can still vary and must converge to a compatible input state for the fixed event, not necessarily the same physical location for every program globally.

A successful branch cannot silently terminate before a required event. Failure/cancellation is an explicit operational failure, not an alternative successful circulation. Rendezvous participants cannot choose mutually incompatible meeting locations independently: their selected meeting target belongs to the single coordinated event and must be accepted before approach. Cyclic or missing rendezvous dependencies are contextual validation failures; an operational late/missing participant is observable waiting/intervention, never an invented partner or automatic reassignment.

Circulation validation uses this fixed event graph with unit-preserving input/output relations. Branch travel time remains variable; distinguish structural/unit inventory feasibility, scheduled timing feasibility and actual simulation results. Do not claim robustness against arbitrary delay or failure from a net-balanced graph or one successful simulation.

Locomotive-hauled operation, arbitrary internal-car splits, and runtime-conditional storage/coupling/separation remain outside scope. The user approved this revised plan before implementation.
