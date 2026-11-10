# Ordered display calls: implementation evidence — 2026-11-10

Dedicated ddf5 worktree, last committed baseline09da5fbc. This slice is uncommitted, with prior task-owned changes preserved. No push, deployment, world/package replacement, server run or test execution.

## Implemented

SimpleATO captures TimetableDisplayProgress(phase,stationIndex) in detached TimetableUnitDisplayContext. The display sequence is existing exact-unit plannedStops, with original call IDs retained and legal contiguous TERMINAL/ORIGIN identities mapped to the same occurrence index. Unknown future composition stays explicit, does not bridge merge boundaries or fabricate cursor positions. Owner/range ambiguity yields UNKNOWN; actual currentRun retains its execution semantics and no presentation switching action was introduced.

TimetableDisplayPosition keeps transient actual arrival placement separate from confirmedDoorStop/next mission. Submitted mission alone cannot imply departure. Physical movement invalidates arrival; stationary interval waiting remains in transit, stopped committed reversal rebases the exact occurrence, and formation/derail changes clear evidence. Initial successful CHECK may seed an exact origin only before scoped movement was consumed.

TimetableRunCallCursor consumes the validator-proven full ordered via/call event sequence. This fixes an existing producer ambiguity where an ordinary via B could prematurely consume a later PASS B, including a same-ID via immediately preceding that PASS. A later CHECK at a repeated origin waypoint cannot seed the initial origin again and rewind progress.

Host serializes operational.stops,stationIndex,phase plus station metadata, existing values/currentRun/proven currentStation and STOP/TERMINAL nextStation. No duplicate remainingStops array in this unpublished template input. Diagnostic reached counts remain; completed does not mean departed. HTTP diagnostic projection keeps its own original three-list interface. Whole sequence/phase/index participate in existing contextKey invalidation. Existing input limits remain, without speculative increases or truncation.

## Verification actually performed

- Independent Trellis check agent read-only review passed after the via/call identity and repeated-CHECK fixes; no remaining blocking findings. Review included alias mapping, unresolved boundaries, zero-speed enroute, reversal, ownership, immutable copies and context invalidation.
- `git diff --check` passed.
- Source and test-source compilation passed, BUILD SUCCESSFUL in5s (39 actionable tasks, not39 tests):

```
./gradlew :SimpleATO:compileKotlin :SimpleATO:compileTestKotlin :BukkitPlugin:compileKotlin :BukkitPlugin:compileTestKotlin :Decorations:compileKotlin :Decorations:compileTestKotlin --offline --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx4g -PsimpleDisplaysRepository=/tmp/javarailways-simpledisplays-xml-dom-maven
```

Log: /tmp/jr-stop-sequence-compile-final.log. Initial attempt compiled SimpleATO/host but Decorations could not resolve API from default ~/.m2; final uses the already-existing isolated XML/document-JS API Maven repository, with no independent library source changes or rebuild. A queued mission assertion in the new dispatcher test was corrected to submitted missionRevision because the controller consumes its queued command on a later tick.

Authored but NOT EXECUTED: TimetableDisplayProgressTest (arrival/departure/prepared mission/interval wait/reversal/PASS/repeated visits/merged boundary/unresolved/BEGIN/retire/ordered via events/repeated CHECK), actual TimetableStationCallDispatcherTest owner capture at origin/intermediate stop/prepared future mission/real departure, host OperationalPartRenderProjectionTest full history/metadata/exact ticks/repeated occurrences/phase/index/next selection/invalidation/unknown states.

## Remaining acceptance and implementation

Unified test suites, actual Paper/Folia and game-client verification remain deferred by user. Actual cabin/exterior XML and glyph rendering, per-screen opening-side integration and test-room package deployment are not implemented or claimed by this interface slice. Existing15-second shared language and vehicle-information timing TODO decisions remain unchanged.
