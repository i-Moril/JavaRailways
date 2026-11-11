# Research: SimpleATO domain, persistence, signal-host and composition audit

- Query: Audit all assigned SimpleATO domain/composition/persistence/signal files for unnecessary state, incorrect fallback, lifecycle, mutable values and broken API contracts.
- Scope: internal; read-only source review, not implementation.
- Date: 2026-11-06
- Baseline: main-session supplied `b89b0d5d`. No Git operation performed by this researcher. Shared working tree; no production, test or spec edits.
- Method: full production-file reading, producer/consumer searches, selected test-body review. No Gradle/test/probe executed; findings below are static, not measured server failures.

## Findings

### D1 — P2: Empty signal-source runtime retains every retired admission owner

Location: `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/signal/SignalEpochRuntime.kt:87-102,149-169`.

`publishAdmissionUpdate` removes a retired owner from `activeAdmissions` but always inserts its final removal into `changedAdmissions`. `process` returns at line 159 when `sources` is empty, before `takeAdmissionUpdates` clears that latter map. Thus a valid world without installed signal sources, or after sources have been removed, retains one pending record per distinct admission owner indefinitely. A sequence of create/cancel owner A, process; create/cancel B, process; etc. leaves A/B/... in the map even though no active request remains. Depending on when the previous baseline was consumed, records can additionally retain previous frozen route values. It is not just one latest value per currently live owner.

Producer/consumer: isolated production-domain assembly wires Basic's `publishAdmissionUpdate` to this runtime (`timetable/simulation/IsolatedTimetableSimulation.kt:499`); admission handoff is independent of whether any source is installed. Installed sources call `takeAdmissionUpdates`, but empty worlds deliberately still process and complete epochs.

Contract: reactive-signal-epochs.md says bounded latest-per-owner handoff, no persistent deleted-owner history; active current values should remain for later baseline, retired change records should not accumulate indefinitely.

Impact: retained memory and unnecessary historical baseline work when a source is later installed. Not evidence for the Tick 14219 blocking error and not a claim of an observed OOM.

Minimal remedy: drain/coalesce pending admission changes at the existing empty-source epoch boundary while retaining only the current `activeAdmissions` baseline. Check source removal/reinstallation expectations for withdrawal delivery; no new event queue/cache framework needed.

Tests: `SignalEpochRuntimeTest.kt:223-303` tests installed-source changes/removal/replacement, while `831-845` only checks completed stamp/live IDs in empty state. Proposed additional test: with no sources, publish 100 distinct owner create/removal pairs across completed frames; verify pending handoff stays bounded/empty and installing a source yields only genuinely current admissions. A temporary reflection assertion can expose the retained-map bug without adding a production test hook.

### D2 — P2: Depot records and canonical destination roles expose mutable collection aliases

Locations:

- `api/depot/Depot.kt:28-98`: `Depot.stablingPointRefs`, `DepotStablingPoint.allowedTags`, `RollingStockRecord.tags/capabilities`, `DepotTrainSelector.requiredTags/requiredCapabilities` are un-copied `List`/`Set` constructor inputs.
- `api/depot/Depot.kt:634-683`: store directly retains `point` and `record` supplied by caller.
- `api/depot/Depot.kt:1042-1057,1080-1092,1124-1137`: getters return nested original values and `pointAccepts` consumes their current sets.
- `api/destination/OperationalDestination.kt:102-117,220-228`: canonical depot role accepts and forwards its `allowedTags` set.
- `persistence/SimpleATORuntimeSnapshot.kt:48-61`: capture assigns existing record tags/capabilities directly.
- `persistence/SimpleATOInfrastructureSnapshot.kt:94`: capture retains canonical depot tags.

Trigger chain: construct a record with `mutableSetOf("metro")`, register it, capture a `SavedRollingStockV1`, then mutate the original set (or cast the getter's set to `MutableSet`). The registered record, selector results and already-captured saved DTO all change, without a DepotStore method, lock, validation or new snapshot. The same issue applies to depot eligibility tags. `OperationalDestinationService` documents returned destination values as immutable, but depot role sets violate that promise. Depot aggregate lists made by `current.stablingPointRefs + point.ref` are also not protected against a Java caller changing the returned list.

Contract/impact: state ownership and frozen value boundaries are not guaranteed; a caller can change availability filtering and persistence contents outside the owner transaction. This is public-API constructible; no current platform caller was shown mutating these collections after registration, and no concurrent-save failure was reproduced. Both canonical role and live projection share the same tag set in the normal registration path: this finding is **not** a claim that the two immediately diverge.

Minimal remedy: freeze value collections once at construction and protect copy paths, using the existing `FixedEmuUnit`/`Station` private constructor + defensive collection constructor pattern. Keep immutable values shared downstream; do not add parallel mutable models or repeatedly deep-copy every tick. `DepotOperationalSnapshot.kt:10-12` currently copies selected nested sets, demonstrating a narrower boundary already compensates for the issue but does not fix public getters/capture.

Tests: `FixedEmuUnitLedgerTest.kt:32-39` correctly checks mutable input and Java mutation for fixed-unit membership, but that protection is not present for stock/point models. Proposed regression should verify original input mutation, returned collection mutation, selector outcome and captured DTO stability, including `copy` paths; no physics fixture required.

### D3 — P2 API correctness, current UI reachability limited: Line compilation can join incompatible intermediate berths

Location: `api/line/LinePlanner.kt:185-219`.

`compileStations` optimizes each station pair independently. It does not carry the selected previous destination or actual arrival direction into the next pair. With one AB straight rail and A@a at 0 m, B@near at 20 m, B@far at 80 m, C@c at 100 m, it can return A@a→B@near (20 m) followed by B@far→C@c (20 m), as `Success(totalLength=40 m)`. The missing 60 m is not a train movement. Even exact berth IDs with null approach direction can lose the physical end-direction continuity between legs.

The adjacent `compileResetCandidates` implementation at lines 74-126 already propagates actual end direction and whole-candidate path cost for exact berth choices; this is a divergent second compilation algorithm, not a missing generic routing system.

Real consumer limitation: `BukkitPlugin/.../http/TimetableHttpBridge.kt:815-820` invokes this branch when `destinationIds` is omitted. However lines 864-865 subsequently require `measurement.calls.map { it.destinationId } == selectedDestinationIds`; a non-null list cannot equal null. Therefore this particular UI branch is currently rejected later rather than driving a train along the discontinuous result. Public `LinePlanner.compile/compileStations` still advertises a successful compilation; tests and route-planning tests consume it. Do not report this as observed teleportation, admitted unsafe motion or the cause of the reported global simulation stall.

Minimal remedy: either require exact berth input at the actual intended API boundary and remove the unusable optional branch, or reuse a single continuous candidate-selection mechanism if auto-berth compilation is an intended supported capability. Preserve actual direction. Decide this API intent before deleting the published experimental method.

Tests: full `LineStoreTest.kt` and `LinePlannerTest.kt:110-213` read; current plain compile tests cover two stations and exact-berth cases, not a three-station continuity requirement. Proposed probe: adapt existing straight-rail fixture to the A/B-near/B-far/C example; assert adjacent destination IDs and directed segment endpoints match, plus independently computed complete length. No Gradle execution performed.

### D4 — P3: Direct LineStore restore failure leaves a partially populated store

Location: `api/line/Line.kt:92-97`.

`values.forEach { check(lines.put(line.id,line)==null) }` mutates before duplicate checking. Restoring `[A, B, A2]` throws after replacing A with A2 and retaining B; revision remains 0, and a retry rejects the now nonempty store. This is a public restore API failure-atomicity problem.

Limit: `SimpleATOInfrastructureSnapshotV1.restore` prevalidates duplicate IDs at `persistence/SimpleATOInfrastructureSnapshot.kt:155-162`, so normal infrastructure restore through that entry does not currently reach this defect. It is not a claim that valid server saves are partially restored. Existing LineStoreTest does not exercise restore.

Minimal remedy: build/check unique entries before writing the map, then commit once under existing lock. Add a direct malformed-batch test asserting empty state/revision and successful valid retry.

## Optional simplifications, documentation drift and unresolved risks

1. **Depot allocation duplication (not a confirmed runtime defect).** `DepotStore.allocate` (`api/depot/Depot.kt:852`) selects by logical ledger and has only test calls in searched production modules; Dispatcher actually uses physical selection followed by `reservePhysicallySelected` (`timetable/TimetableDispatcher.kt:5495`). The spec and Dokka explicitly document the older public method, so absence of internal production callers alone does not authorize deletion. Decide whether it remains an intended extension API; if not, tests should exercise the production reservation path instead of preserving a second selection policy.
2. **World runtime `data class` copy surface.** `SimpleATOWorldData` owns live dispatcher/coordinators/registries and private recovery-capability maps yet is a data class. `copy` can share the live owners while initializing new private hold maps. No production copy caller found in this review; prefer an ordinary composition class if generated structural equality/copy has no contract. This is simplification, not evidence of current duplicated runtime construction.
3. **Close registration symmetry needs lifecycle context.** `OperationalWorldRuntime.create` registers the signal epoch processor at line 114, but `close` only unregisters the speed monitor at line 71 and topology subscription. Current WorldManager cleanup also closes Core; simulation owns/disposes its Core world, so an independently live closed runtime was not established. Before treating this as a bug, verify whether `OperationalWorldRuntime.close` is permitted separately from Core teardown. Do not infer that removing a callback is sufficient safe shutdown while workers run.
4. **Persistence DTO shape complexity.** `SavedTimetableActionV1` has a large nullable field union, and several known but irrelevant fields are silently ignored per action; examples: extra `tick` on a station OPEN_DOORS snapshot, extra call/conditions on nonmatching condition variants. Strict JSON unknown-key checking does not validate semantic field applicability. No corrupt source producing such a case was shown. A sealed current DTO or centralized shape validator could improve errors, but this is a format/validation decision, not authorization for a schema bump or compatibility layer.
5. **Stale specs.** destinations.md still contains a CURRENT stopping-area/length-bound design absent from current roles; stations.md explicitly labels that design historical. persistence.md's early home-depot-only storage statement is superseded later by the cross-depot section. These contradictions should be reconciled by main/spec owner, not used to add back removed capacity locks or reject valid cross-depot storage.
6. **Station coordinate conversion remains.** `OperationalDestinationService.registerStationBerth` converts AB arc to BA (`railLength - AB`) and stores a directed RailLocation; StationCallResolver converts back to AB when creating target. This observation overlaps main's existing coordinate/snapshot investigation and is not counted as a new defect here. No tolerance added and no claim made that all projection is removable.
7. **Nullable station validation length is not the rolling-length fallback.** `StationCallResolutionContext.trainLengthMeters` explicitly allows unknown length during static validation. Runtime physical-origin resolution separately requires actual length. Do not delete every nullable length in the module solely because the runtime rolling request should always have a valid current train frame.

## Coverage / files found

All paths below are under `SimpleATO/`; production files read completely unless explicitly marked otherwise. Each listed group names exact files, not inferred directory coverage.

### Complete production reads

- `src/main/kotlin/work/art1st/javarailways/simpleato/SimpleATOPlugin.kt`: Core extension-slot identity.
- `.../SimpleATOWorldData.kt`: world composition, recovery movement-hold restoration.
- `.../runtime/OperationalWorldRuntime.kt`: assembly, after-publication work and teardown.
- `.../signal/SignalEpochRuntime.kt`: topology/admission/clearance/continuation handoff, source replacement, epoch validation.
- `.../depot/DepotReset.kt`: preflight, physical relocation rollback, publication/ledger partial commit and JSON report.
- `.../api/SimpleAtoApiValidation.kt`: namespaced ID grammar.
- `.../api/depot/Depot.kt`: model values, inventory/reservations, fixed-unit/formation ownership, restoration and Reset matching.
- `.../api/depot/DepotOperationalSnapshot.kt`: detached aggregate ledger capture.
- `.../api/depot/FixedEmuUnit.kt`: immutable permanent membership and claims.
- `.../api/depot/FixedFormationChange.kt`: immutable event tokens and recovery shape.
- `.../api/destination/OperationalDestination.kt`: canonical values, registration/projection, replacement/migration/removal.
- `.../api/line/Line.kt`: immutable line values and revision store.
- `.../api/line/LinePlanner.kt`: pairwise and exact-reset compilation.
- `.../api/station/Station.kt`: passive berth, station store, calls/dwell.
- `.../api/station/StationCallRegistry.kt`: non-exclusive exact call registrations.
- `.../api/station/StationCallResolutionProvider.kt`: resolver lifecycle, extension validation and simulation capture.
- `.../api/station/StationCallResolver.kt`: default/retained route resolution, cached stop targets.
- `.../api/station/StationCallRuntime.kt`: call results, notifications and berth detector.
- `.../api/station/StationStopSelection.kt`: candidate selection and registration.
- `.../api/signal/SignalEpochSource.kt`: immutable public signal bindings/input/output.
- `.../persistence/DispatcherSnapshot.kt`: logical checkpoint DTO and strict codec.
- `.../persistence/SimpleATOInfrastructureSnapshot.kt`: destination/line snapshot and full prevalidation.
- `.../persistence/SimpleATORuntimeSnapshot.kt`: inventory, fixed formation and disabled dispatcher restoration.
- `.../persistence/StationStopProgramSnapshot.kt`: stop-program DTO mapping.
- `.../persistence/TimetableSnapshot.kt`: timetable/action/formation/run DTO mappings and codec.
- `.../persistence/TrainInfoSnapshot.kt`: display metadata enum.
- `build.gradle.kts`, `src/main/dokka/Module.md`, `src/main/dokka/SimpleATOPackages.md`: dependencies/toolchain, API suppression and published API descriptions.

### Test body coverage completed

- `src/test/kotlin/work/art1st/javarailways/simpleato/SimpleATOWorldDataTest.kt`: complete.
- `.../api/depot/CrossDepotStoreTest.kt`: complete.
- `.../api/depot/FixedEmuUnitLedgerTest.kt`: complete.
- `.../api/line/LineStoreTest.kt`: complete.
- `.../api/line/LinePlannerTest.kt`: lines 110-213 only.
- `.../api/depot/DepotStoreTest.kt`: lines 1-150 plus full-file symbol/alias searches only.
- `.../signal/SignalEpochRuntimeTest.kt`: lines 1-150, 220-305 and 830-847 plus full test-name inventory only.

### Inventoried but test bodies NOT reviewed

- `.../depot/DepotResetCoordinatorTest.kt`.
- `.../persistence/{DispatcherSnapshotTest,FormationRecoverySnapshotTest,SavedLineMaximumSpeedTest,SimpleATOInfrastructureSnapshotTest,StationStopProgramSnapshotTest,TimetableSnapshotTest}.kt`.
- `.../api/depot/FixedFormationChangeTest.kt`.
- `.../api/destination/OperationalDestinationTest.kt`.
- `.../api/station/{StationCallRegistryTest,StationCallResolverRegistryTest,StationCallResolverTest,StationModelTest,StationStopSelectionTest}.kt`.
- Remaining ranges of the three partially read tests above.

These test gaps prevent claiming full test-effectiveness review of the domain slice. Production inventory reading is complete; exhaustive transitive cross-module consumer/lifecycle review is not.

## Related specs and external references

Read `.trellis/workflow.md`, code-review skill and its `references/missed-findings.md`, SimpleATO index plus destinations/stations/depots/lines/persistence/reactive-signal-epochs specs, shared versioning policy and `ai_tasks/persistence_schema_evolution_rules.md`. Historical cases were used only as inspection prompts, not current defect evidence. No external web references required; claims are repository source facts. Kotlin/JVM target 17; Kotlin serialization plugin 2.3.0; JSON dependency 1.9.0 as declared in module build.

## Caveats / Not Found

- No repository edits other than this report; no tests, deployed process attachment, new replay or server interaction.
- Main owns TrainSnapshot equivalence, Core publication ordering and rolling request floor diagnosis; this report does not re-prove those findings.
- Did not identify a concrete current successful Reset that violates its stated rollback/publication contract from production reading; this is not a claim of exhaustive dynamic safety verification.
- Did not treat immutable interface types or passing preexisting tests as proof of actual deep immutability.
- Memory keyword search was performed for routing only; no historical memory-derived claim is relied on here.
