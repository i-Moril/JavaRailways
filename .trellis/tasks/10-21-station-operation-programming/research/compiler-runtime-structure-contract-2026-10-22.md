# Compiler, validated routes and structural operations: focused source audit

Read-only product-source research. No implementation or runtime tests were performed.

## Existing compilation path

1. `WebUI/.../WebUiApplication.java:373` forwards source compilation through the existing bridge.
2. `BukkitPlugin/.../http/TimetableHttpBridge.kt:432` calls `TimetableScriptSandbox.compileSource`, then enters the owning world, normalizes destination projections, restores the SimpleATO model and runs `TimetableValidator`.
3. `BukkitPlugin/.../http/TimetableScriptSandbox.kt:96` invokes `scriptworker.executeScript` and decodes its JSON through `TimetableSnapshotCodec`. This is currently an in-process restricted Graal context; do not assume an external worker protocol.
4. `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Main.kt:11` runs `main(TimetableBuilder)` with annotated HostAccess only and no class lookup/IO/thread/native/polyglot access. `Dsl.kt` owns `TimetableBuilder`, `ServiceBuilder`, `TimedActionBuilder` and `RuntimeActions`.
5. The worker's independent serializable DTO copy is `scriptworker/Model.kt`. Its action/condition/trace fields must stay aligned with `SimpleATO/.../persistence/TimetableSnapshot.kt`; `SavedTimetableActionV1.restore/capture`, `SavedTimetableV1.restore/capture`, `TimetableDumper.kt`, frontend `types.ts` and generators are separate touch points.
6. `ServiceBuilder.origin` (`Dsl.kt:210`) already scopes diagnostic origins; `append` (`:311`) records `(stepIndex, origin)` and `TimetableBuilder` adds the service ID. An invocation/program/step path can reuse this attribution channel, but nested branch nodes need addresses beyond one outer step index.

## Current branches are extension-only

`TimedActionBuilder.whenCondition` (`Dsl.kt:337`) constructs both branches at compile time. `RuntimeActions` exposes only registered extension `action`. `TimetableAction.Conditional` (`Timetable.kt:169`) explicitly requires recursively extension-only contents. `TimetableValidator` validates conditions with `RUNTIME_BRANCH`, but keeps a single current waypoint/direction (`:310` onward).

Dispatcher `startNextExtensionAction` (`TimetableDispatcher.kt:3942`) evaluates the condition once, pushes the chosen list into `extensionActionQueue`, starts one registered extension operation and subsequently inspects its ticket. Unavailable/error causes the existing failure lifecycle, not the false branch. This queue is not currently a movement/station-program cursor.

Do not merely relax the constructor guard: movement/reversal/station options, asynchronous completion and successor position/direction need explicit cursor transitions. Keep registration-owned extension semantics and existing Dispatcher ownership.

## Important route-evidence gap

`TimetableValidationResult` (`Timetable.kt:222`) contains a flat `routes` list and an aligned nullable stopping-offset list. It has no service/program/step/input-state route key. `compileAndInstallAppliedPlan` (`Dispatcher:4209`) uses these routes for complete static dependency protection.

However, `startServiceInternal` (`Dispatcher:587`) discards the validation routes and retains only errors. `submitMissionForRun` / `submitMission` (`:4701/:4741`) call `AtoMissionResolver.resolve` again; `AtoMission.kt:151` performs new `OperationalRoutePlanner.buildRoute` calls. Thus retaining validation-to-runtime branch evidence is a real implementation obligation, not an existing facility that can be indexed by list position.

Smallest safe extension:

- Give expanded program nodes stable invocation/step/branch addresses (top-level legacy step indices can be root addresses).
- Validator state includes canonical destination, direction, acquisition/formation constraints and bound choice. Retain movement evidence keyed by node address **and the validated input-state/selected-choice identity**. Merge only equivalent states; reject finite size-limit overflow explicitly.
- Store retained routes and station stop options together. Build complete applied infrastructure dependencies from all retained alternatives without claiming them all.
- Dispatcher selects a previously validated edge using the actual cursor state. Rewrap its exact route into `PlannedRoute` / `AtoMissionLeg` with the new runtime mission revision; `AtoMission` and `SimpleATO.submitMission(AtoMission)` already accept a fully resolved mission (`SimpleATO.kt:286`). Verify topology/physical identity before submission; stale evidence enters stopped replan/refusal rather than silently finding a replacement.
- Continue through existing `submitMissionForRun`, station operation, reverse preparation/publication and Basic admission paths. Dynamic candidate selection acquires one logical allocation and requests only the selected physical route.

## Fixed-event structural validation

After expanding program calls and binding static parameters, recursively reject storage/coupling/separation under any runtime conditional, including conditionally invoked programs. Checking the UI or one immediate branch list is insufficient. Reject runtime-origin bindings for return depot, counterpart/event, split partition and output task references; static parameters remain legal. Fixed events must be reached on every successful path. Validate the coordinated meeting target once per event, not independently per participant.

Existing StoreAtDepot validator (`Timetable.kt:360`) insists on the acquired home depot and final service position; cross-depot B1 must revise it together with Depot reservation/restore/reset ownership, not just remove this error.

## Existing Core structure seams

- `Train.splitBefore(carId,newId)` delegates to `CoreTrain.kt:773`, queues one between-frames structural operation, publishes `TrainSplitEvent`, prepares exact prefix/suffix/controller states, commits both frames/occupancy and emits `TrainSplitSuccessEvent` (`:837`). Prefix retains the original train/controller; suffix is a new train. Post-success callback failures cannot undo committed physics.
- `TrainCoupling.kt:32` is an internal **collision-only** operation. It requires valid recorded connector contact, coherent pre-collision frames and at least one stationary participant. `TrainLinkEvent` can veto/select the exact surviving source and result direction. Core prepares dynamics/extension union and occupancy, stops/removes the retired controller, commits physical ownership and emits `TrainLinkSuccessEvent` (`:142`). There is no public arbitrary two-train coupling API.
- `TrainController.prepareConsistChange`, `AbstractATO.prepareConsistChange`, and Basic actuator/dynamics preparation already refresh constituent-derived physics. They do not transfer timetable missions, depot assignments or route grants.
- Source search found no production SimpleATO/Basic/Bukkit consumer of the four split/link decision/success event types. No existing operational rendezvous coordinator was found.

## Actual B3 prerequisites

`RouteAuthorityAdmissionClient` fixes `trainId` and `InfrastructureOwner` at construction (`.../admission/RouteAuthorityAdmissionClient.kt:31`). `RouteGrantSnapshot` also fixes train identity. `RouteResourceAdmissionService` exposes request/progress, reversal suspend/rebase, and endGrant (`Basic/.../api/route/RouteAdmission.kt:200`), but no formation merge/split ownership transaction. EndGrant explicitly refuses pending or tail-held resources.

`RouteResourceAdmissionEngine.kt:428/:736` checks occupancy excluding only the requesting train. Therefore normal admission cannot simply be reused to authorize movement into a waiting counterpart's occupied body. No admitted scheduled-coupling exception was found. A narrow exact-event/contact approach contract and atomic grant/body-ownership handoff must be specified before B3. Never use general peer occupancy suppression, force-release old grants or treat a retired train's missing observation as successful tail clearance.

The coordinator must prepare both assignments and output dispositions before physical submission, hold/suspend affected release under Basic ownership, observe exactly one physical operation, then reconcile successful car-ID publication into one survivor or all split outputs. Unexpected physical outcome or partial operational publication requires held/intervention recovery; post-success events alone cannot provide rollback. Core split/link and controller preparation remain reusable foundations, not proof this end-to-end contract already exists.

This is an implementation contract gap, not an unresolved product question. B1/B2 can proceed, but B3 cannot be declared complete without the admission/assignment handoff and same-runtime rendezvous tests.

## Minimum concrete retained-route interface to align implementers

Suggested transient validated-plan contract (names are a proposal, not existing APIs):

- `ProgramNodeAddress(serviceId, invocationId, stepId, branchPath)` identifies an expanded authored action. Branch path is structural provenance, not the only input-state key.
- `ValidatedStateKey` identifies destination/selected marker, direction and the relevant bound acquisition/formation constraint domain. Do not key only by destination string or mutable array index.
- `ValidatedMovementKey(nodeAddress, inputStateKey, choiceId)` maps to immutable `ValidatedMovementEvidence`: origin, ordered ATO points, exact `ExpandedRoute` for each leg, stopping options, successor-state key and captured topology/route dependencies. Retain the source state domain and validate its membership before executing. Static dependencies are the union over this map's values.
- `TimetableValidationResult` owns this map; `AppliedTimetablePlan` must retain it alongside its infrastructure dependency owner. `ActiveRun` carries a finite cursor, selected state key, and at most one pending action/operation. It must not persist live grant/ticket handles.
- Extend `MovementPlan` (`Dispatcher:4928`) with the selected evidence/key and create `AtoMission` from those exact legs. Reuse `AtoMissionLeg` and `PlannedRoute` constructors, assigning the accepted mission revision. Both automatic and manual-routed `submitMissionForRun` paths must use this evidence. `SimpleATO.submitMission(AtoMission)` is already the no-replanning submission seam.
- Reusing the old flat route list for static consumers is possible as a projection of this map, but it cannot remain a separate independently compiled route owner. Candidate choice and branch state must select evidence, not rerun the planner.

## Exact B3 change seams

| Existing file/method | Required extension boundary |
| --- | --- |
| `Basic/.../api/route/RouteAdmission.kt`, `RouteResourceAdmissionService` | Add explicit authenticated stopped-formation preparation/commit/abort semantics; define exact participant grants, expected observations and resulting owners. Reversal-only methods cannot change train identity. |
| `Basic/.../infrastructure/admission/RouteResourceAdmissionRuntime.kt`, `request`, `suspendReleaseForReversal`, `rebaseAfterReversal`, `endGrant` | Carry the new operation through the same world-local requester authentication/runtime, rather than exposing registry mutation to SimpleATO. |
| `Basic/.../infrastructure/admission/RouteResourceAdmissionEngine.kt`, `requestValidated:283`, `rebaseValidated:603`, `consumeObservation:796`, `endGrant:814` | Constrain any coordinated approach allowance to the declared exact counterpart/contact range; suspend both relevant tail-release consumers before physical publication; reconcile train/body/route references using the committed composition and one coherent observation. A partner exception must not suppress unrelated occupancy or grant conflicts. |
| `Basic/.../infrastructure/admission/RouteGrantTailRelease.kt`, `consume:118` | Current missing/mismatched train at `:143` fails closed. Preserve this behavior; retired participant IDs must be explicitly rebound under a prepared structure transaction, not treated as cleared. Rebuild release geometry/queues for all outputs without a resource gap. |
| `SimpleATO/.../admission/RouteAuthorityAdmissionClient.kt`, constructor, `request`, reversal methods | Fixed train/owner identity and pending/restriction revision ownership need an explicit controlled formation handoff, not direct field mutation or creation of an unrelated fresh client. |
| `SimpleATO/.../timetable/TimetableDispatcher.kt`, `ActiveRun`, `startServiceInternal`, mission submission and terminal cleanup | Coordinate event ID/counterpart assignments, exactly-once approach/structure operation, selected shared meeting target, resulting mission/cursor state and every output disposition. Existing `canBeReplaced`/tail-held cleanup gates remain mandatory. |
| Core `TrainCoupling.linkFromCollisionOnOwner`, `CoreTrain.splitBefore` and four decision/success events | Reuse physical preparation/publication; add operational veto/matching consumers with lifecycle-scoped registration. Do not mutate operating records in decision listeners or rely on post-success listener exceptions to roll back physics. |

Core's `InfrastructureCoordinationRegistry` already supplies atomic acquire/append/shrink primitives for one logical owner. It does not expose an observed two-owner formation handoff through Basic. Determine whether the new Basic transaction can preserve existing claim owners until actual release; only extend Core registry if a genuine atomic multi-owner resource transformation is required. Avoid prematurely adding a generic transfer framework.
