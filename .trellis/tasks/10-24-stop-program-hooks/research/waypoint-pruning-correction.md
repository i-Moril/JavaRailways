# Waypoint pruning correction — 2026-10-27

User confirmed pruning should skip same-station and differing-direction cases. Existing task remains active, no new task or data migration.

## Proven failure
Current developer server Reset: UP etchujima@2→hatchobori@2U/4U returns422 Lineleg3; DOWN5stations succeeds. Independent JVM using installedclasses/current savedtopology+Basicdirections+SimpleSignalsrestrictions proves each UPpath unique with protectionenabled and pruningdisabled. Blocking onlyhatchobori@2D/4D reproduces failure. These are derived destinationwaypoints at sameplatform oppositeend; genericerror wrongly suggests atomicendpointissue.

## Boundary
- SimpleATO owns semantic selection: exclude stationberth waypoints belonging to either endpoint station (stable StationId, not stringprefix). Preserve ordinarywaypoints and otherstation candidates. Use destination approachDirection when present.
- Basic owns physical query: directional prune candidate blocks only matching actual traversal direction on that rail, including macrosegments and structural aliases; null direction retains bothdirections. Do not compare differentrails' rawAB/BA as global travel direction.
- Keep uniqueness, protectedpaths, trackoperatingdirections and explicitviabehavior unchanged. No persistedformat change, compatibility readers, selector hacks, blanketpruningdisable or errorUI overhaul.
- Expectedfiles Basicplanner+focusedtests, OperationalRoutePlanner+focusedtests and necessary existingcallers adapting queryinput. Root owns specnotes/tests/integration/commit/deploy; implementationagent owns production+tests.

## Acceptance
Sameendpointstation otherberths traversable; unrelatedstation correctdirection prunes, oppositedirection traversable, null direction stillprunes; ordinaryvia stillprunes; direction evaluated locally across reversedrail orientation; protectedmacro interiors obey directionalpruning and uniqueness remains strict. CurrentUPReset2U/4U must succeed with protection/unique/pruning enabled after candidate deployment; DOWNroute retained.

## Implementation and review
- Current query uses OperationalRouteBlocker(location,facing?), noold bare-location compatibility form. Canonical StationId filtering and approachDirection stay inSimpleATO; StructuralTimetableValidation shares helper.
- Focused17tests passed:Basicuniqueness8,OperationalRoutePlanner4,LinePlanner4,StructuralOnward1. Final unrelatededge earlyskip is covered by rootfullgate.
- Independent trellis-check found no correctnessissue; allocationnit resolved by rejecting unrelatednormalizededge before segmentresolution. Existing rawBasicWayPoint opt-in/explicitvia regression retained.
- FullBasic/SimpleATO/SimpleSignals/Bukkit/WebUI tests+candidatepackage pending. NoUI/persistence edits or migrations.

## User clarification
User objected to unexplained endpoint-station wording; concrete A→B example clarified: B's alternate parkingpoint is exempt, unlisted Cstation enroute mustprune. Preserve Cstation candidates, direction mismatch remains independentexception. No deployment/commit occurred during clarification.

## Full-gate findings
Basic435 and SimpleATO634 pass. SimpleSignals656 has4failures in twofixtures whose intermediatebarewaypoints now conflict with strict policy; actualold-artifact baseline is being checked before adapting testfixtures. No productionrule relaxed to pass tests.

## Baseline verification
Actual pre-fix8ae deployedfatjar /tmp/jr-duty-timeline-20261027-8ae62d56/JavaRailways.jar placed first before normal SimpleSignals test runtimeclasspath. Same8 tests reproduce exact4failures (log /tmp/jr-pruning-baseline.log):ProtectionRegionTimetableDependencyTest finalA→B+threeStoppedReturnToDepotIntegrationTest second→home scenarios. Therefore these fail on prior deployment, not introduced by currentfix.

Fixture-only corrections: C/D are negativeinternal-protection endpoint setup, registeronlyafter optionalinitiallegalA→B and removebeforefinalfreshlegalA→B. Insideblock stop is outboundAB operationaldestination, returningBA maypass; do notaddmission steps or weakenphysics/grant/clock/rollbackchecks.

## Final gates and candidate preflight
Allaffected backend gates pass:Basic435,SimpleATO634,SimpleSignals656,Bukkit640,WebUI36=2401total,zero failures/errors/skips. Candidate mainSHA256 bab7b783e7b1cc179eae1338e2160255d585e9e517097e43608e5a9d956c6d03.
Independent candidateJVM on current59rails/31waypoints/26operatingdirections/6protectedpaths confirms etchujima@2→hatchobori@2U length1049.7148951690315m and4U1050.0301977782992m, protected=true/prune=true/unique=true success withrealABstart. InvalidBAstart stillunreachable. Full liveReset isnext acceptance afterdeployment; no savedruns/parkingpoints changed.
