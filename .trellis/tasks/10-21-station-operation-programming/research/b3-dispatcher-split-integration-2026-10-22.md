# B3 Dispatcher split integration checkpoint (2026-10-22)

Owner: `ui_check` owns `TimetableDispatcher.kt` and `TimetableFormationDispatcherTest.kt` only. Core/Basic publication, controller preparation, Depot phase APIs, corridor geometry and helper coordinators remain separately owned. This note records an in-progress vertical slice, not final acceptance.

## Implemented Dispatcher path

- Applied plans retain the exact validated timetable plus structural source/output proof maps. Physical and provider dependencies include all source envelopes and retained output routes; structural routes are not replanned or terminal-extended again.
- Source candidate is bound to actual acquired train/origin. At Split, require the certified complete body, exact stopped event window, authoritative IN_SERVICE assignment and committed Basic grant. An initial Check→Split obtains exact actual tail-to-head body-only protection with pending progression, without route search.
- Preflight every declared output before physical mutation. Current first-continuation scope is GoTo after an optional leading Reverse prefix. Leading Reverse waits until authored tick and is folded into the atomic physical split directions; FIRST partition always uses original source HEAD.
- FixedUnitSplitPlan/FixedFormationOperation perform staged ownership and actual queued Core split. All outputs remain physically fenced while binding exact tail-started grants and installing controllers/missions/ledger metadata.
- Continuing/new duty missions use AtoMissionOrigin.FormationOutput anchored to the exact published output head and proof.resolve, without a fabricated Check. Retained physical container preserves its ATO publisher/admission stream; new container gets its own stream. Output extension requests use the authenticated formation-output API until Basic detaches them.
- A new duty’s future TakeFromFormationOutput remains nextStepIndex=0, pending and body-only; its mission and forward admission do not activate early. The other output is independent. A future first GoTo also retains its normal departure hold.
- Later single-stop output navigation consumes the exact structural event/output/service/step/candidate/direction proof. Structural storage uses the post-change authoritative assignment and statically proved classification-compatible point set, rather than stale acquisition tokens or legacy physical-only storage.
- Unresolved event/output protection prevents applied-plan release, replacement and force discard. Failure retains physical/ledger evidence and stalls; no reset/teleport/replan fallback.

## Actual test evidence and discovered seams

- Compile checkpoint 35793 passed before later functional additions.
- Gate 20304 compiled all sources/tests. Split-plan2, metadata4 and real snapshot recovery5 passed. Dispatcher2 failed at queued publication: host drains once before Dispatcher; Split queues for next scope, while the source receives a stationary physics publication in between. Basic guard originally compared the stale plugin observation. Core/Basic owner fixed this using a coherent current Core publication and exact unchanged full physical placement/body/claims; strict prepare/seal remained unchanged. Their real queued positive/moved/reoriented negatives passed.
- Gate 82560 compiled updated sources/tests; split-plan2 and metadata4 passed. Actual split publication now succeeds.
- Gate 24130 exposed the next production seam: Core PhysicsCoordinator skips controller.tick for movementHeld outputs. Installed missions therefore never activate/publish a revision; Dispatcher waits for mission publication before forward admission, and handoff waits for forward authority before lifting the hold. Merely bypassing mission/inspection readiness would be incorrect when providers exist. Root owns the controller-preparation resolution. Exact red XML: `/tmp/jr-dispatcher-split-red-24130.xml`.
- Root/graph gate32282 compiled the latest Dispatcher as dependency and passed21 ledger/recovery/handoff cases; it did not run Dispatcher acceptance cases.
- HOLD owner naming failure was independently corrected: raw event instance contains characters forbidden by InfrastructureOwner, so the owner now uses a deterministic UUID token.

## Focused acceptance tests currently written

`TimetableFormationDispatcherTest` uses actual RouteResourceAdmissionRuntime, Core structural publication, SimpleATO and HandleTrainActuator:
1. FIRST physically moves while REST stays held.
2. New output duty waits unconsumed for Take tick100; FIRST must physically move before that tick; child later departs with separate unit assignment.
3. Four parameter cases: source forward/reversed × FIRST noReverse/Reverse, verifying whole-unit HEAD membership, exact published orientation and real displacement.

At this earlier checkpoint the six cases were not green; the later gate90332 below supersedes that status. Helper/compile success alone must not be presented as scheduled Dispatcher acceptance. Subsequent output GoVia, station operation prefixes and arbitrary waiting/extension prefixes before the first output movement still need explicit cursor integration or contextual preflight rejection. Couple remains unavailable until static contact proof and coordinator integration are both complete.

## Further recovery gate required

Dispatcher checkpoint restoration currently recompiles the original whole timetable against current actual train frames before publishing recoveryRequiredRuns. After a real Split, the original source container can contain fewer units, so replaying the original static Split validation can reject HEAD count or source shape. Core/Depot stage recovery tests do not establish this Dispatcher path. Add a real post-split Dispatcher checkpoint save/restore regression; preserve fail-closed fences and dependency installation without manufacturing a historical TrainFrameSnapshot or claiming live execution resume.

## First physical Dispatcher acceptance green

Gate90332 passed all six Dispatcher cases (0 failures/errors/skips), exact XML `/tmp/jr-dispatcher-split-green-90332.xml`. Core's formation-only held preparation hook activates actual missions/inspection with physical motion forced zero. Basic now retains the exact same output grant ID and committed grant while pending.

The reversal fixture needed a physical correction: reversing FIRST into a HOLD REST necessarily points into the held peer. That zero motion was correct. The four orientation cases instead use a centered actual body and, when reversing, send both outputs in the new direction: the now-leading REST travels to the farther destination before FIRST follows to its nearer destination. These test actual staged resource clearance and motion, not traversal through a held peer.

After this green boundary, root approved the explicit structural recovery-only exception. Production code compiled in root's Bukkit gate; two real Core/operational snapshot round-trip cases (HOLD and pending output acquisition) are now written but await the shared focused gate. They must show that historical composition validation can fail while diagnostic-only restore succeeds with no applied route proof, no live execution and unchanged physical fences/pose/unit assignments.

## Recovery acceptance green

Gate23069 passed37 tests with0 failures/errors/skips: Core StoppedBodyGeometry9 and CouplingApproach3; SimpleATO TimetableFormationDispatcher8, ordinary DispatcherSnapshot6 and graph-owned TimetableUnitCirculation11. Exact XMLs are archived under `/tmp/jr-checkpoint-23069-TEST-*.xml`. The8 Dispatcher cases include both new actual post-split Core/operational codec save/load paths.

Structural restore now retains definitions plus original run diagnostics without reconstructing historical composition or installing an applied route proof. Public `applicationState(id)` returns `RECOVERY_REQUIRED`; `recoveryRequiredTimetableDefinitions()` distinguishes those diagnostic definitions. All structural duties are disabled, no live runs/controllers replay, saved pose/unit ownership and HOLD/pending physical fences remain unchanged. A disabled diagnostic clock persists the requirement through the unchanged current checkpoint shape. Explicit logical disposal changes state to `UNAPPLIED` and retains the physical fences and timetable definition. Ordinary nonstructural checkpoint behavior passed its existing6 regressions.

Dispatcher/source tests and spec changes are frozen/released for coordination; scheduled Couple integration remains next and is not implied by these results.
