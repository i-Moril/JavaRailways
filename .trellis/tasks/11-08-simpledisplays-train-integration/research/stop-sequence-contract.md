# Ordered display calls and progress — 2026-11-10

User approved station-sequence interface implementation after discussion. This slice owns only SimpleATO exact progress capture and host/template input interfaces/docs/deferred tests. Door installation/template rendering remain separate work. No separate displayRun or early next-run transition: actual currentRun retained. No commits/deploy/server/tests yet; user requested unified acceptance after all features. Compile production and test sources only.

## Gap and owners

Current JS receives only remainingStops and counts. Domain completed marks both STOP arrival and PASS traversal, so completion is not departure. JS cannot reconstruct history or distinguish arrived from en route. SimpleATO must own exact progress identity/physical station state; host only serializes detached facts. Do not infer route from infrastructure line station lists or station IDs.

## Target JS contract

operational keeps timetableId/operationalTripId/cycleIndex/values/unresolvedRuns/currentRun and proven currentStation. Replace remainingStops/count-only route input with:
- stops: full ordered calls proven for this exact unit's operational trip, preserving repeated stations, PASS, existing serviceId/runId/callId identity and station metadata. Do not send planned/completed/remaining duplicate full lists. Unresolved future composition remains explicit via unresolvedRuns, never fabricate calls.
- stationIndex: zero-based integer or null. at_station -> exact arrived stopping occurrence; in_transit -> first not-yet-reached physical occurrence including PASS; finished/unknown -> null. An ordinary stationary red-signal wait must remain in_transit, not at_station.
- phase: at_station / in_transit / finished / unknown. Derived from runtime call evidence and physical arrival/departure state, never from planned time or speed alone. Arrival can remain at_station when future mission is prepared but train has not departed. Terminal arrival should remain at_station while current active context/physical proof exists; do not guess terminal state after context retirement.
- nextStation: derived STOP/TERMINAL starting at stationIndex for in_transit, at index+1 for at_station; null when progress unknown/finished or unresolved future prevents asserting certainty. Preserve no alias from ambiguous current station.
- keep plannedStopCount/completedStopCount only if useful to existing diagnostic consumers; they are counts of planned/reached calls, not passed-station instructions. No remainingStops legacy reader/alias required for unpublished development input; update consumers/docs/tests directly.

Domain interface details to be supplied by implementer before host integration. Exact current-call identity must survive joined TERMINAL/ORIGIN boundary without station-name matching. Context copies/progress values immutable; owner capture post-dispatcher. Existing semantic invalidation keys must include sequence/phase/index, periodic sampling plus revision wakeups preserve same-stream ownership.

## Expected edits and checks

SimpleATO TimetableUnitDisplayContext + minimal runtime/dispatcher proof/cursor sources and focused tests; host OperationalPartRenderProjection + tests; Decorations README and host/simpleato specs for current input. No WebUI/persistent schema or physical control changes. Default unknown progress preserves manually created/insufficient fixture contexts; never invent index0.

Deferred cases: origin/arrival/departure vs zero-speed mid-leg, PASS progress, repeated station visits, arrival before future mission commit, merged cross-line boundary, final stop, unresolved branch, begin before RUN, inactive/reset, ambiguous/no actual scope, detached copies/context invalidation. Root runs serial source/test-source compilation and static review only; test execution is explicitly deferred.
