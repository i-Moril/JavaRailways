# Research: missing same-epoch section observation

- Query: Explain the real-game `section ... has no same-epoch observation` signal failure, find the exact graph/monitoring mismatch, and recommend a minimal production regression.
- Scope: internal, source-level read-only investigation; no builds, remote access, or production edits.
- Date: 2026-10-17

## Findings

### Deterministic graph-versus-monitoring mismatch

The error is thrown by `signalpack/SignalPackEpochRuntime.kt:146-151` when an observation-plan node's `SectionRef` is absent from the current occupancy map. The wording does **not** prove that two physics epochs were mixed. The exact input/occupancy stamp equality is already checked at lines 71-73, before projection.

There are currently two different definitions of the protection sections to observe:

1. `signalpack/SignalObservationGraphPublication.kt:103-134` builds section nodes for **every compiled protection route** in `routes.routesById`. This includes routes with no signal or route-indicator directly installed, and all legal sibling branches. `SignalObservationPlanCompiler` can therefore reach these sections from an upstream fixed-block signal.
2. `signal/ProtectionSignalCompiler.kt:71-79` compiles occupancy geometry only for `observedRouteIds`. That set is assembled from the routes directly referenced by protection signals (`routeIdsBySignal`) and entries referenced by route indicators. It does not include otherwise reachable, un-signalled protection sections.
3. `runtime/SectionOccupancyRuntime.kt:487-494` registers Basic occupancy areas solely from `publication.protectionSignals.routeGeometry.blocksById`. An omitted route consequently never has a `SectionRef(PROTECTION_ROUTE, routeId)` baseline entry.
4. `runtime/SectionOccupancyRuntime.kt:373-405` correctly establishes observations only from registered area updates. Its first-baseline completeness test compares against registered bindings, so it passes even though an observation plan references additional unregistered sections.
5. `signalpack/SignalPackEpochRuntime.kt:190-215` recursively projects every branch, including non-selected branches, before the JS can inspect its `selection` flag. Encountering any omitted section deterministically throws and makes the signal unavailable. JS cannot recover or ignore the branch, because it never receives the input.

This is a production registration/compilation defect. It does not need rare topology edits, stale snapshots, trains present, or malformed saved definitions to reproduce. A physical baseline with zero trains is sufficient.

### Minimal reproducible configuration

- One valid fixed block with a fixed-block signal and a scan distance reaching the next protection region.
- A valid protection region with a compiled route table, but no protection signal or indicator covering that route's entry.
- Deliver an ordinary empty-world physical baseline via `SignalEpochRuntime`.
- The graph contains the protection route; the registered area list does not. Upstream signal evaluation becomes unavailable with the reported message.

Variant: install a fixed-route signal only for branch A, leave sibling B without a direct signal, and scan the entry tree from the approach. B is still projected and triggers the same error even when A is the physically selected route.

The researcher did not access the live world definition. Subsequent main-agent read-only inspection retrieved definition revision 40: three protection regions and 16 authored routes, with only one protection signal covering one outgoing route and no route indicators. This confirms the configuration prerequisite, not the exact missing route ID in the user's diagnostic. No remote definitions were modified.

### Minimal coherent repair

Compile protection occupancy geometry for all **successfully compiled** routes in `routes.routesById`, matching the observation graph's section universe. Reuse existing `FixedBlockCatalog`, area IDs, immutable geometry retention, Basic occupancy subscription, and one atomic occupancy/signal publication. This is a low-frequency compilation/registration change, not a second tick scanner.

Do not remove the safety check; do not turn absent occupancy into an empty section; do not stop projecting unselected branches, because JS explicitly supports observing the full branch tree. The invariant is that every usable section referenced by a prepared plan has a registered observation source. Unavailable topology sections remain unavailable, not fabricated empty.

`signal/ProtectionRouteSelectionRuntime.kt:82-84,157-174` also uses this same geometry catalog to process route-tail crossings. Extending the catalog preserves the existing code path and additionally makes clearance evidence available for legitimate routes without directly installed devices; it does not itself reserve resources or select a route. The selection index already includes all compiled route entries at line 48.

Nominal lengths do not explain this failure: graph protection lengths are derived from authored traversal spans; runtime occupancy length comes from compiled route geometry. This error occurs at missing key lookup, before any geometric length comparison.

## Files Found and Regression Ownership

- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionSignalCompiler.kt` — owns the too-narrow geometry selection; primary production fix location.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalObservationGraphPublication.kt` — all-route graph construction; source of the plan section universe.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/runtime/SectionOccupancyRuntime.kt` — area registration and coherent occupancy baseline owner.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalPackEpochRuntime.kt` — missing-key failure and safe unavailable output; leave fail-closed semantics intact.
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/signalpack/SignalObservationGraphPublicationTest.kt:104-176` — reusable real approach/branching-region fixture. Existing test checks the graph but installs a current-entry signal covering both branches, hiding this mismatch.
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/signal/ProtectionSignalControllerHandoffTest.kt:185-207` — existing real catalog/JS/epoch wiring pattern, suitable for a focused cross-layer regression.
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/runtime/SectionOccupancyRuntimeTest.kt` — current tests cover fixed blocks only; a real compiled un-signalled protection route baseline belongs here if avoiding a separate test suite.
- `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/signalpack/JrFourLightSignalPackTest.kt` — JS tests use hand-built observation JSON and therefore cannot catch missing monitoring registration.

One useful regression should exercise real definition compilation -> prepared JS plan -> actual `SignalEpochRuntime` baseline -> available upstream result, asserting the protection `SectionRef` exists and that a subsequent actual occupant update reaches the rule. A selected/unselected sibling variant is useful only if covered compactly by the same fixture; avoid a broad combinatorial suite or mocking the missing map entry into existence.

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md:406-416` — projection must consume coherent occupancy/selection, first baseline establishes all signal results, unavailable propagates safely.
- `.trellis/spec/simple-signals/backend/index.md:429-434` — real section graph prepared before catalog plans; unavailable/stale candidates must not bypass correctness.
- `.trellis/spec/simple-signals/backend/js-signal-rules.md` — tree observation belongs to JS and includes branch selection facts.

## External References

None needed. This is an internal deterministic source mismatch, not an API/version question.

## Caveats / Not Found

- No test execution or live-server diagnosis was performed by this researcher. Source-level repro is deterministic, but the exact missing route in the user's current diagnostic still needs matching.
- Lifecycle/retention might produce separate unavailable states; no such mechanism is necessary to explain this defect. Existing exact stamp checks should remain.
- The exception text could eventually be clearer (`section is not registered in occupancy publication`) but changing text alone is not a fix.

## Implementation verification follow-up

The main agent reproduced the exact exception against the original production compiler using real definitions, the prepared graph, registered areas and `SignalEpochRuntime`. After compiling geometry for all usable routes, the same test verifies empty-baseline availability, actual occupancy on the non-selected branch, recovery after departure and immutability of the old publication. The test does not insert fabricated observations. An existing route-table-edit test now distinguishes unchanged fixed-block geometry from correctly replaced protection-route geometry rather than asserting the entire area collection remains unchanged.
