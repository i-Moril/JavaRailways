# Research: fixed-block authority versus operational stop

- Query: Give a train the complete fixed-block path authorized by its entry signal without changing an operational stop inside it.
- Scope: internal; narrow SimpleATO/SimpleSignals admission-route seam investigation, no implementation or builds.
- Date: 2026-10-14

## Implementation follow-up

The analysis below records the original source state, not final acceptance. Subsequent review required guarding the original selected path **before** invoking terminal geometry, then upgrading protection to the completed path before binding/resource callbacks; this supersedes the simpler callback ordering below. The fixed-block return fixture now includes an authored depot block0..40 while preserving block40..140 and operational stops100/20. The user explicitly deferred the universal completion-boundary guard and moving-block physical stop margin to a separate batch. Current owner contract: [terminal route completion](../../../spec/javarailways/simpleato/terminal-route-completion.md).

## Findings

### Current exact failure path

- `SimpleATO/.../routing/FrozenMissionRouteAssembler.kt:67` builds the physical admission route by prepending the observed body to the **remaining mission**. `missionRouteSnapshot` at line 321 copies only mission legs. Thus an operational target at 100m truncates an otherwise authored 40..140m block at 100m.
- `SimpleSignals/.../block/FixedBlockRouteMatcher.kt:38` matches a requested prefix of that route. Its endpoint-inside-block allowance at lines 73..76 deliberately accepts partial terminal runs; its windows at lines 85..90 use the intersection with the route/request end, not the authored block exit. This is appropriate for geometric overlap discovery but insufficient for complete fixed-block admission.
- `SimpleSignals/.../block/FixedBlockInspectionSource.kt:38` compiles `minimumRequestedAuthorityEndRouteDistanceMeters` from those truncated windows. The generic inspection mechanism can round an ordinary rolling request up to a block exit **already on the frozen route**, but cannot manufacture a missing suffix.
- `SimpleSignals/.../block/FixedBlockRouteResourcePolicy.kt:38` contributes only a block token and its release boundary. It cannot grow the requested physical range; Basic's contract explicitly forbids contributions from widening desired authority. Returning a token held to 140m without extending the route is not a fix.
- `SimpleATO/.../timetable/TimetableDispatcher.kt:2935` calculates nominal rolling authority, then invokes `inspectionRuntime.adjustRequestedAuthorityBoundary` at line 2948 before Basic admission. Keep this existing single admission transaction.
- `SimpleATO/.../timetable/TimetableDispatcher.kt:2980` separately publishes the terminal control limit. Do not conflate that scalar with the physical grant returned by Basic.

### Reuse choice: InspectionSource is the smaller owner lifecycle

There is no currently implemented suffix/route-completion contract. A minimal neutral callback must be added; neither existing floor nor token contribution can express it.

Prefer an optional, default-empty **physical terminal continuation callback on the existing `InspectionSource`** over a new registry/service. Its current data class is at `SimpleATO/.../api/inspection/InspectionSource.kt:60`; source IDs, world ownership, atomic replacement, source withdrawal and error handling already exist. Invoke once when constructing a new admission-route candidate, before final inspection binding and edit protection; do not invoke in Dijkstra or every physics tick. The callback must return immutable directed physical traversal values (or a full candidate constrained to an unchanged input prefix), not grant/resource identities and not a desired control limit. The plugin computes geometry; SimpleATO validates/composes its route; Basic still independently validates topology, occupancy, mandatory footprint and claim readiness.

An alternative is extending `RoutePlanningRestrictionSource/Binding`. It is less coherent for this narrow change: `RoutePlanningRestrictionRuntime.compile` currently runs **before** search, knows topology/operating directions, not the selected mission endpoint; the underlying `RoutePlanningRestrictions` is Basic's neutral forbidden/path-replacement query-graph input. Adding terminal authority policy there would mix search restrictions with a post-selection admission extension and expand Basic API unnecessarily. The existing restriction registration could technically own a second callback, but provides no advantage over the inspection owner already representing whole-section request floors.

Do not add persistence fields, a schema version, a second planner, a second resource registry or a second source service. Public Java source consumer compatibility needs a normal compilation check for the additive callback.

### Plugin geometry is already available; no arbitrary branch search

- `SimpleSignals/.../block/FixedBlockCatalog.kt:60` stores immutable complete `CompiledFixedBlock.traversals` and `reverseTraversals`; its snapshot has a rail-to-block index (`:82`). Catalog validation disallows turnout traversal (`FixedBlockValidationCode.TURNOUT_TRAVERSAL`).
- At an endpoint strictly inside a matching directed block visit, compare the selected route's existing overlap with that block traversal and clip the **already authored** remainder from endpoint to the corresponding exit. This chooses no new branch and needs no topology shortest-path search. BA continuation uses the reversed traversal and the actual bidirectional permission, not AB canonical subtraction guessed at a call site.
- An endpoint exactly at the block exit needs no suffix; an endpoint at the next entry should not automatically acquire an unrelated successor. A stop outside blocks leaves the moving-block route unchanged. An unavailable/stale/ambiguous or direction-forbidden block must fail closed, not fall back to an unprotected arbitrary rail extension.
- Existing planning restrictions/operating directions must still apply to the added path. Fixed blocks currently cannot contain a turnout, but do not use that to skip Basic structural validation or plugin direction validation.
- Resolve the suffix against the same immutable SimpleSignals publication used for floor/token matching. If callbacks straddle publication replacement, existing binding/version validation must reject/rebuild; do not silently join old geometry with new resource semantics.

### Full-section trigger, not only terminal extension

Retain the existing `InspectionDeclaration.minimumRequestedAuthorityEndRouteDistanceMeters` contract: when a nominal rolling request crosses a section-entry trigger, raise the desired end to the full exit. After terminal completion, normal matcher windows must expose that true exit, so a request stopping at 80m inside a 40..140m block asks Basic for 140m atomically. A pending/denied claim still cannot publish 140m permission.

Check the start-inside-block case separately: `FixedBlockInspectionSource` filters declarations by `enteredAtBoundary`, so a route starting inside a block currently has no entry declaration/floor. A stopped reversed body is exactly such a route. Supply a neutral request floor for this initial retained/current section without fabricating a signal at a past entry or requiring a second block-token owner. Do not assume terminal completion alone fixes this branch. Existing direction policy remains authoritative.

### Append overlap and reversal are mandatory parts of this change

- `FrozenMissionRouteAssembler.append` at line 226 currently requires the next mission to start at the committed **route end**, then copies its remainder after that end. With a stop100/authority140 arrangement the next forward mission starts at100, inside the already frozen suffix. It must align and reuse the exact existing directed suffix, and append only genuinely new traversal after140. If the next mission diverges before the frozen end, reject/replan rather than rewrite an admitted prefix. Missions entirely contained in the frozen path should need no new geometry or resources.
- The current `FrozenMissionRoute` progress mapping stores body/mission offsets; preserve that mapping to the operational origin, not to the extended physical endpoint. `AdmissionRoute.missionAuthorityMeters` at Dispatcher line4755 clamps into mission length for reporting; that clamp must not be mistaken for actual grant coverage or be used to fabricate a later boundary.
- `rebaseBodyAfterReversal` at assembler line158 must continue to build only the published reversed body and let Basic verify it against the **retained** full old claim, retaining grant/token owner. Then complete the new reverse admission route to the reverse block exit40, leaving return stop20 unchanged. Do not acquire missing resources during the rebase step or create a second token. Tail release remains Basic's physical cursor and the existing plugin-supplied release boundary.
- `routeAdmissionCandidate` at Dispatcher line3009 caches by mission revision and keeps geometry across observation refresh. Complete the route before the first Basic request and preserve it across retries. Do not rerun a completion callback and mutate a committed prefix after each tick/source edit.
- Existing `routeEditProtection.protect` at Dispatcher line2896 must receive the **extended** route, so the extra track and section become protected before admission. Verify applied-timetable dependencies and continuation source withdrawal remain covered; do not claim the original mission-only dependency set necessarily covers new geometry.

## Recommended reviewable batches

1. Define/add the optional neutral continuation on the existing inspection source; integrate immutable terminal geometry completion and exact suffix reuse/mission coordinate mapping. Focused tests: unchanged moving-block mission; endpoint-inside-block AB/BA; at-exit no extension; same-route forward continuation contained/overlapping/diverging; stale/withdrawn source. No Basic signal types.
2. Wire SimpleSignals compiled fixed-block completion plus true-exit rolling request floors and initial-inside/reversal floors; real Basic admission assertions prove physical authority/rail claim reaches140, whole token remains exclusive, denial leaves old grant, mission stop remains100. Keep tail release and same grant ownership.
3. Run the existing real50ms `StoppedReturnToDepotIntegrationTest` without changing stop/block coordinates or faking the actuator. Its 100.322m stop is inside140m authorized range; distinguish stop precision from authority breach. Independently handle the approved Dispatcher issue: genuine actual-authority breaches cannot be treated as successful task completion. A tight stop-position error policy is a separate semantic decision, not equivalent to the authority test.

## Related specs

- `.trellis/spec/javarailways/basic/route-resource-admission.md`: contributors cannot widen authority, physical claim compilation, append-only route and stopped reversal.
- `.trellis/spec/javarailways/simpleato/operational-routing-and-authority.md`: immutable chosen traversal, generic inspection floors and admission lifecycle.
- `.trellis/spec/simple-signals/backend/index.md`: plugin-owned fixed-block geometry, matcher and generic inspection bridge.
- Task PRD/design/implementation plan: Stage2 whole-block exclusive token and real stopped-return integration; user clarification fixes previously assumed mission-end authority semantics.

## External references

None; this is internal source/contract analysis, no internet claims.

## Caveats / Not Found

- No code, tests, builds or Git operations performed. This is not evidence that any proposed seam compiles or passes a real world run.
- There is no pre-existing public route-completion hook; the smallest change is additive but still needs an explicit contract. Existing planning restriction and inspection callbacks must not be presented as already solving completion.
- This research does not diagnose the remaining 0.322m stop-position error or change the terminal control compensation policy. It corrects the fixed-block physical-authorization truncation first.
