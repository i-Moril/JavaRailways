# SimpleATO targeted deep review

Read-only static follow-up at the existing checkout. No edits, build/tests, task creation, runtime probes or deployment. Earlier ATO-01/03 are not repeated; ATO-02 was withdrawn and ATO-04 was subsequently removed at the user's request (see review.md). Aspect/file/call-chain ledger: /workspace/minecraft/JavaRailways/ai_tasks/bukkit-ato-signals-review-2026-10-23/deep-ato-coverage.tsv.

## ATO-05 / DEEP-ATO-01 [P2 extension API contract] Stopping a completed extension action stalls the world

Anchor: TimetableDispatcher.kt:4097-4114, particularly 4109-4112. cancelExtensionAction always invokes cancel and requires CANCELLED. Unlike ordinary operation handling at4126-4147, it never inspects and accepts an already terminal SUCCEEDED/FAILED/CANCELLED ticket.

Scope correction (2026-10-23): repository search found registry/factory infrastructure and test providers, but no shipped concrete production action provider. This is an error in the supported extension ticket contract, not an observed failure of a built-in action. A synchronously completed or asynchronously completed ticket is permitted by the API; the test provider supplies that condition.

Supported trigger: valid Check(A) -> GoTo(B) -> Extension; provider returns Accepted with snapshot SUCCEEDED and idempotent no-op cancel. This is exactly the implementation in existing TimetableDispatcherTest.kt:2172-2176. startNextExtensionAction3990-3999 still installs the ticket and WAITING_EXTENSION_ACTION, and existing assertions2207-2213 explicitly establish a full dispatcher-tick gap before polling completion. An operator stop in that gap (JavaRailwaysCommand.kt1117 -> stopService1697) calls cancelPendingOperation -> cancelExtensionAction; snapshot remains SUCCEEDED, so stopService stalls the run/world as TrainOperationFailure and returns false, even though no action is pending or failed. suspendForControlTransfer1025 similarly returns Failure. Async completion between ticks reaches the same state.

Minimum direction: inspect current extension state first; only pending work requires cancellation confirmation. Retire terminal extension ownership/queued actions according to the stop/transfer path. Preserve failures to inspect or cancel genuinely pending work. Add a regression using the existing synchronous-success ticket and a stop before the next dispatcher tick. Static call-chain evidence only; not executed. This does not depend on the invalid pre-first-destination extension scenario that caused old ATO-02 to be withdrawn.

## ATO-06 / DEEP-ATO-02 [P2 API/value boundary] Timetable collections remain mutable through running state and saved capture

Anchors: Timetable.kt:30-35,76-82 (caller List retained),55-57 (store retains object); TimetableSnapshot.kt:207-209,220-223 (capture keeps action.wayPointIds rather than a copy); api/timetable/TimetableExtension.kt:10-14 and persistence/TimetableSnapshot.kt:25-29 similarly keep JsonObject backing values.

Concrete public API trigger: construct GoVia with mutableListOf("B", "C"), add service/timetable, capture SavedTimetableV1, then mutate the original IDs list. The supposedly captured saved DESTINATION.wayPointIds changes too, so encoding the earlier capture now writes different targets. This does not need reflection or a Kotlin read-only cast. Similarly caller-owned service.steps is retained by TimetableStore and ActiveRun (Dispatcher709); after initial validation586-592, advanceService3574-3575 rereads that list. Owner-side modification can replace/remove future validated steps without a fresh validation/apply boundary.

Real save consumer: SimpleATORuntimeSnapshot.capture maps SavedTimetableV1.capture; Bukkit WorldManager719-745 captures under owner then749-769 queues to snapshotWriter; WorldPersistence140-141 encodes later. An alias can thus change a captured epoch's payload after owner capture. Existing built-in HTTP edit paths normally replace timetable objects, so do not claim they currently mutate these lists in place or that every save is corrupt. Scope is a confirmed public construction/capture boundary defect; existing published methods admit these objects.

Minimum direction: normalize nested timetable collections and extension JSON to detached immutable values at the owning model/store boundary; capture must also detach its nested DTO lists/maps. Test mutation of original lists after add/start/capture, and verify saved bytes plus pending execution stay unchanged. Do not fold in the previously reported Depot tags/capabilities alias.

### ATO-06 final decision and completion (2026-10-23)

The user selected an explicit immutable ownership contract instead of blanket defensive copying. Timetable, service, extension parameters and capture DTO documentation now require the complete reachable value graph to be frozen before validation/handoff/capture and unchanged thereafter. Editing constructs new values and uses the explicit replacement lifecycle; retained lists or JSON maps may not be mutated. No builtin mutating caller was established. This supersedes the earlier proposed blanket-copy direction above; no copying code or schema change was added. See third-batch-fixes.md.

## DEEP-ATO-03 [P3 engineering/lifecycle] Initial ATO snapshots accumulate for retired train identities

Anchor: api/ato/AtoWorldCoordinator.kt:54,66-70; reset81-85 does not clear the map. AbstractATO constructor99 unconditionally records an initial TrainSnapshot for every ATO-equipped train. AtoWorldCoordinator is owned by the persistent world runtime (OperationalWorldRuntime.create). No remove/retire API exists; repository-wide producer/consumer search finds only this writer and two diagnostic readers (AbstractATO166 and Dispatcher3393). Removing trains or completing/discarding runs cannot release these entries while the world remains loaded.

Trigger: repeatedly create distinct train IDs, assign an ATO, then delete/retire them within one long-running world. Memory for the snapshots increases with historical ATO train IDs instead of live trains. This is a diagnostic value retention leak, not retention of live CoreTrain instances; no heap/time measurement was made. Reusing the same train ID overwrites its entry. Minimum direction: retire diagnostic snapshots through exact train lifecycle or keep initial diagnostics with the controller/run owner; preserve snapshots needed by the retained lastStall report.

## Falsifications and inspected limits

- No CompletableFuture/CompletionStage/Future return declarations were found anywhere under SimpleATO/src/main; no leaked completion-authority finding inferred. Public external tickets are interfaces, not service-owned futures.
- AtoMissionRequest/AtoMission private constructors defensively copy outer lists; resolver carries arrival direction between legs. No new mission-list alias finding.
- DefaultSimulationDomainTarget installs replacement before recording ownership, and close retires exact installed list identity; no handle replacement deletion finding established. Simulation provider/contributor registration uses exact value removal; installation/teardown is explicitly scoped to target-owner pre-run and post-worker termination, so absent concurrent mutation locking alone is not a defect.
- DepotReset reports partial commits after physical publication and separately reports rollback failure; did not mislabel intentional partial-commit reporting as all-or-nothing corruption.
- MissionRouteLayout.project is shared by controller and Dispatcher; repeated projection calls serve separate head/tail/state observations. MinimumConstraintPlanner delegates to the one ConstraintManager reduction. No duplicate competing algorithm or consumerless planner was established by these paths. This is scoped evidence, not an exhaustive unused-symbol proof.
- Infrastructure restore materializes after prechecks and rolls back registered destinations on exceptions. Runtime restore is not treated as whole-world transactional because loader owns disposal on failure, as previously established.
