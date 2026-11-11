# Retained route foundation checkpoint

Changes are confined to the existing validator/Dispatcher and station resolver, with no schema change or runtime branch relaxation.

`TimetableValidationResult.retainedRoutes` is an immutable transient map. Keys contain service ID, generated source step index, movement origin ID, leg index, target ID and actual leg start direction. Values retain the exact immutable ExpandedRoute, pass/null or stopping offset, and captured Core topology. The existing route/offset lists remain the static dependency projection. Each dynamic acquisition origin validates its complete chain. Undirected input positions retain each feasible facing; subsequent movement and reversal propagate the validated arrival directions rather than discard them.

Applied timetable plans and active runs retain these associations. Automatic and manual movement submission select the matching evidence, revalidate physical topology/incarnation, directed endpoints and stopping/pass options, and construct AtoMissionLegs directly. Missing/stale evidence is refused; ordinary submission never falls back to AtoMissionResolver. Explicit operator replanning remains a distinct selection boundary and protects its newly validated route before queuing the replacement mission.

Station validation and Dispatcher station scheduling derive an exact prefix from the selected movement legs, including an interior berth. StationCallResolver accepts retained geometry plus its topology, rechecks endpoint identity/geometry/direction and Basic physical projection, and still performs actual train-length/marker eligibility and station checks. Allocation, tickets, dwell and tail release remain unchanged. Synthetic station-call target identity is preserved. Neutral terminal-margin completion remains a separate existing safety operation; it is not replacement movement selection.

The existing metadata-free physical depot acquisition path previously had no static routes because compatibleStablingPoints only sees registered records. Its physically present unregistered origins are now included in validation coverage. This exposed a pre-existing reverse-start fixture whose target was unreachable after reversal; the positive fixture now uses a physically reachable reverse destination.

## Evidence

Final frozen-source gate:143 tests passed,0 failures/errors/skips,53s. Suites:Dispatcher44, Validator15, StationCallDispatcher28, StationCallResolver7, SharedAdmission24, CrossDepot6, FixedEmuUnitLedger10, FixedFormationChange7, FormationRecoverySnapshot2. Command used `/tmp/jr-retained-forked-tests.gradle`:1GiB heap, one worker at a time, `forkEvery=1` to isolate test classes. Log:`/tmp/jr-retained-final-tests.log`. `git diff --check` passed.

A pooled-worker run exhausted its heap while constructing later StationCall rail fixtures; both the default heap and pooled1GiB attempt were incomplete. The successful bounded per-class run does not establish a pooled-worker memory/performance guarantee. No product configuration was changed to mask the issue.

Tests distinguish exact consumption from equivalent replanning: a planning-source interceptor installed after validation must not be consulted by delayed movement submission; changing the endpoint under the same ID is refused. Station retained-resolution tests similarly prohibit searches while verifying length and direction checks. Validator tests cover full dynamic-origin legs, immutable metadata, both feasible facings on an actual closed rail loop and provider validation of nested JSON without executing provider actions. Extensions may execute after unique stopped acquisition before the first movement; their existing context does not depend on a mission. Automatic execution holds the existing stop constraint while the action ticket is pending, and options/navigation pairing remains mandatory. Real Depot and Waypoint tests verify one start, correct acquired context, no first mission or movement during pending ticks, and subsequent movement after success.

## Remaining boundary

This is a finite movement-evidence foundation. Structured branch execution, dynamic platform selection policies, rendezvous/formation runtime integration, circulation proofs and simulation/game acceptance remain later batches. No conditional action guard was relaxed. The full approved feature is not complete.

## Independent staged-ledger review

Reviewed FixedFormationChange.kt, DepotStore staging/commit/storage/restore, saved DTOs and runtime recovery-fence composition. Original unit claims remain protected until actual output reconciliation; recovered source/output membership and direction are observed and held without replay. Structurally produced IN_SERVICE assignments can complete storage through exact reservation/point ownership without an obsolete acquisition token.

Found and reported that `assignmentIsOwned` returning null from `reservePhysicallySelected` still let Dispatcher treat conflicts as optional metadata success. The owner changed conflicts to IllegalArgumentException, matching Dispatcher's existing rejection handling; the final gate includes the updated FormationChange regression. Expected reversed direction is now retained and checked in prepare/commit/restore. No remaining concrete blocker found in this staged ledger/recovery slice. This review does not establish an integrated rendezvous coordinator or live game behavior.
