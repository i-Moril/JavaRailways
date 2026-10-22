# 站内作业编程与动态停靠

## Goal

可视化站内程序生成并嵌入 JS 时刻表，支持跨库出入库、折返、完整动车组重联／解编，以及动态停车标和站台选择

## Confirmed requirements

- R1: Visual authoring is the editable source. Generate JavaScript and embed it in the JS timetable; no reverse conversion from arbitrary JS is required (user confirmed 2026-10-21).
- R2: Reusable parameterized station programs are called from duties before, between or after runs. Support turnaround, departure from storage and return to storage.
- R3: Cross-depot operation is required: acquire at A, store at B, subsequently acquire at B. Separate home affiliation, physical storage and active assignment.
- R4: Support train-length-dependent stopping markers and runtime platform selection, with validated candidates and legal onward connections.
- R5: Branches may finish at different positions/directions. Validation must propagate possible successor states rather than require one common endpoint.
- R6: Existing Dispatcher remains execution owner. Core movement, Basic admission, physical occupancy/tail release, station operation tickets and simulation semantics remain authoritative.
- R7: Preserve established WebUI styling and document/history ownership. Generated code must retain program/call/step identity for diagnostics.
- R8: World-scoped shared program library is reusable across multiple timetables (user confirmed). Generated JS embeds the selected definitions; runtime does not fetch the mutable library.
- R9: Storage, complete-unit coupling and complete-unit separation must not appear in runtime conditional branches (user confirmed 2026-10-22). They are fixed duty/circulation events; cross-depot storage remains supported.
- R10: Repeating timetables require compatible-vehicle circulation checks across cycle boundaries, including acquisition time, storage capacity and unfinished cross-boundary tasks. Extra initial stock cannot make a recurring net deficit sustainable.
- R11: Coupling and separation of complete fixed EMU units are required in this implementation (user confirmed 2026-10-22); arbitrary car-level recomposition is excluded. Locomotive-hauled operation, locomotive exchange/run-around and detached coach operation are deferred.

## Scope and operational defaults for final review

- Distinguish station, physical stopping area/track and stopping marker; express shared occupancy explicitly.
- Finite structured sequence/branches and parameters; acquire, select destination, move/via, absolute/relative wait, reverse, registered extension actions, fixed unit coupling/separation and store.
- Candidate filtering by actual length, direction, reachability and onward feasibility; deterministic priority among eligible candidates, with reservation/admission before execution.
- Cross-depot storage retains home affiliation by default; transfer of affiliation is a separate operation.
- Fixed applied definitions; editing a visual program requires regeneration, validation and application before affecting execution.
- Runtime status shows current step, selected destination, waiting/rejection reason and completed operations. Restart initially requires explicit recovery handling rather than blind step replay.
- Library edits use optimistic revision checks. Timetable authoring keeps an immutable bound definition snapshot with library identity/revision provenance; adopting a library update is explicit and invalidates compiled/simulation results. No arbitrary JS import or library mutation from the timetable snapshot.
- Five dependent delivery batches: fixed-unit/depot/stopping model; shared programs and sequential execution; EMU rendezvous/coupling/separation; branch selection and unit circulation; UI and integrated acceptance.

## Existing evidence and constraints

- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt:242`: existing movement/reverse/depot/extension actions; validator currently tracks one current waypoint/direction; storage requires matching home depot and terminal position in a service.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/depot/Depot.kt:503`: acquisition, storage reservations and restoration assume home-depot placement; removing only the validator check is insufficient.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/station/StationCallResolver.kt:68`: explicit berth resolution checks length and direction; it does not select candidates automatically.
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/station/StationBerthAllocator.kt:34`: logical ownership is currently keyed by individual berth identity; multiple stopping markers need deliberate shared-resource semantics.
- `WebUI/frontend/src/authoring.ts:30`: existing visual duty FUNCTION nodes generate JS with origin attribution.
- `docs-site/docs/timetable_js_syntax.md:449`: JavaScript builds structured definitions at compile time. Runtime branches currently admit extension actions only.

## Acceptance criteria

- [ ] AC1/R1,R7,R8: Visual program save/reload and undo/redo preserve definitions; generated embedded JS compiles, and diagnostics identify the visual invocation and step.
- [ ] AC2/R2,R6: Same-platform and siding turnaround complete with real movement, stopped reversal and onward service continuity.
- [ ] AC3/R3: A-to-B storage and subsequent B acquisition preserve one assignment and correct physical storage occupancy, including rejected/cancelled attempts.
- [ ] AC4/R4: Different actual train lengths select suitable markers; incompatible candidates are explained; markers sharing a physical area cannot falsely create independent capacity.
- [ ] AC5/R4,R5: Concurrent platform selection, all-candidates-busy, admission conflict and differing branch endpoints behave correctly; onward routes use actual selected state.
- [ ] AC6/R6: Pending operations are started once; failure/cancellation cannot prematurely release occupied resources; explicit restart recovery is observable.
- [ ] AC7/R6: Isolated simulation uses the same selection, execution and admission logic; local tests, simulation and game acceptance are recorded separately.
- [ ] AC8/R8: Two timetables share one library definition; editing/archiving it does not change their bound or applied definitions; explicit adoption updates one document atomically, and stale concurrent library writes are rejected.
- [ ] AC9/R9: Reject storage/coupling/separation inside any nested runtime branch or conditionally invoked program. Reject runtime-conditional bindings of return depot, coupling partner/event or split-output destinations that evade fixed circulation; static call parameters remain supported. Dynamic approach paths/platforms remain legal and cannot remove or retarget the fixed event.
- [ ] AC10/R10: Detect recurring depot/type inventory loss, time-local acquisition shortages and storage overflow; account for cross-cycle tasks. Conditional movement timing may require scenario analysis; absence of a proof must not be reported as a circulation guarantee.
- [ ] AC11/R11: EMU split/couple preserves constituent identity, assigns every resulting formation, prevents duplicate asset use and premature resource release, and updates length/dynamics/stopping eligibility; splits occur only between complete registered EMU units; internal car splits are rejected before mutation.

## Out of scope proposed for first implementation

Arbitrary car-level splitting/recomposition, locomotive-hauled operation and exchange/run-around, arbitrary JS reverse import, unrestricted runtime JS execution, arbitrary loops/parallel train-control branches, predictive earliest-release platform optimization and automatic crash continuation.

## Final review readiness

The authoring direction, world-level sharing, cross-depot requirement, complete-unit EMU scope and fixed storage/coupling/separation rules are confirmed. The user approved the operational defaults and five-batch plan on 2026-10-22. Exact implementation APIs and saved-format baseline audit are preflight obligations; they do not authorize silently changing this scope.

Evidence for current consist foundations and the deferred locomotive scope is in research/consist-and-locomotive-model-2026-10-22.md. The latest design supersedes the earlier four-batch/future-only EMU proposal.

## Status

Implementation is in progress in `.worktree/station-operation-programming` after explicit approval. Required prior UI/backend changes are preserved in the recorded baseline; concurrent primary-worktree signal edits are excluded. B1–B5 implementation is integrated, including scheduled physical EMU coupling/separation, branches, station selection and circulation diagnostics. Local browser persistence, frontend and broad backend checks passed; the final held-output reversal/route-extension follow-up passed both its focused gate and the final affected-module regression (Basic427, SimpleATO576, Bukkit575). No commit or deployment performed. Live game acceptance remains outstanding; exact local coverage is recorded in research/formation-authoring-and-simulation-2026-10-22.md.
