# Research: Boundary display names

- Query: Locate boundary identity/persistence, rename entrypoints, route labels, and metadata preservation hazards; recommend a minimal coherent scope.
- Scope: internal
- Date: 2026-10-17

## Findings

### Product scope must distinguish two representations

Protection boundaries are first-class authored objects; ordinary fixed-block boundaries are not. `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/region/ProtectionRegionDefinition.kt:22` defines undirected `ProtectionBoundaryCut(boundaryId, position)` and line 28 defines directional `ProtectionBoundaryDefinition(boundaryId, position)`. Both have stable namespaced identity, neither has a display name.

`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockDefinition.kt:42` stores block ID/revision/traversals/bidirectional only. Its endpoints are derived from traversal ends by `authoring/SectionAuthoringActions.kt:34`. `authoring/SectionOverlayDrawing.kt:87` aggregates block boundary captions by captured physical identity, including actual endpoint connections, and merges adjacent block captions (lines 90–115). This display aggregation is not a persistent shared boundary identity.

**Required product decision:** first support protection boundaries (directly solves indicator route identification), or all player-visible boundaries, including ordinary blocks? The latter needs an explicit ownership/identity design, not merely an extra field. Co-located region boundaries may have distinct IDs/owners; do not infer one global name from geometric coincidence. Do not silently broaden first-class protection names into a global coordinate registry.

### Persistence, no schema bump

`persistence/SimpleSignalsWorldDefinitions.kt:85` owns the independent sidecar file; `CURRENT_FORMAT_VERSION` is 1 at line 241. Nested `SavedProtectionCut` at line 108 and `SavedProtectionBoundaryV1` at line 111 have no name. Accepted boundary write occurs at lines 288–300; directional restore at 389–402; cut save/restore at 405–408. A name must survive accepted boundaries, pending cuts, and region boundaryDraft.

Read first: `.trellis/spec/javarailways/shared/versioning.md` and `ai_tasks/persistence_schema_evolution_rules.md`. An additive `displayName: String? = null` with a clear default requires no schema increment and no migration reader. Retain current format 1; fixed old JSON fixture must verify missing field becomes null, and current round-trip must preserve duplicate names. Do not add nested schema numbering.

### Conversion/reconstruction hazards

- `region/ProtectionRegionDefinition.kt:24` cut→directional and line 38 directional→cut reconstruct objects and must propagate any name.
- `region/ProtectionRegionDefinition.kt:60–63` currently decides whether a draft still contains an accepted entrance using **whole data-class equality** (`draftCut == accepted.cut()`). Adding names directly changes equality. A rename must not make a geometrically unchanged entrance disappear: explicitly compare identity and position for this check; do not require metadata equality. Otherwise route overlay and selection availability could change merely on rename.
- `authoring/ProtectionBoundarySearch.kt:138` reconstructs directional results via `cut.facing(expected)`; preserving cut→definition propagation covers this search.
- `authoring/SectionBoundaryComponents.kt:25–26` deduplicates co-located cuts by rail+arc; first occurrence wins. Name ownership cannot be established from this deduplication.
- `authoring/SectionAuthoringActions.kt:149–157` restores original accepted cuts during repair; lines 168–174 update drafts/pending cuts, often via copy.
- `authoring/SectionAuthoringActions.kt:224–233` reconstructs accepted component boundaries and may replace IDs. In particular `accepted != null` currently copies **only boundaryId** from the retained cut; preserve its intended display metadata as well. For new adjacent-region identities, decide whether copied display name is desirable rather than silently sharing ownership.
- `authoring/SectionAuthoringActions.kt:193–194` synthesizes fixed-block boundary IDs, unsuitable as a new universal persistent naming identity.
- `authoring/SectionAuthoringActions.kt:213–216` consumes pending cuts into an ordinary block, which currently cannot retain endpoint names. This is an additional reason to decide scope before implementation.

### Route data and label consumers

`region/ProtectionRouteDefinition.kt` defines routes with stable routeId plus entryBoundaryId/exitBoundaryId and traversals. The persisted equivalent is `persistence/SimpleSignalsWorldDefinitions.kt:128–132`. Names must never replace IDs in routing, bindings, mapping keys, sort identity, or PDC.

`authoring/SignalWandBackend.kt:402–419` provides indicator choices: scan **authored** definition route tables, filter entryBoundaryId, sort routeId. This includes authored routes rather than only currently usable compiled routes. `RouteIndicatorWandOptions` at line 65 only contains routeIds, variants, mappings; it lacks endpoints/names. Extend the immutable option presentation to carry per-route endpoint labels while retaining exact route ID as callback identity.

`authoring/RouteIndicatorWandEditor.kt:31–41` renders route-name fallback + local index + mapped variant. It uses `authoring/SectionAuthoringNames.kt:3–10`, which hides UUID-containing IDs; it has no way to infer an actual name. Indicator confirm at line 47 also renders entry identity rather than metadata.

Other useful name consumers: `authoring/RoutePreviewWandHandler.kt:180` already shows entry→exit in deletion choices, but uses ID-based fallback; line 229 reports selected entry. `authoring/SignalWandHandler.kt:1215–1218` renders protection/indicator binding descriptions using ID fallback. `authoring/SectionOverlayDrawing.kt:124–132` displays boundary and pending-cut labels; these should display authored names to connect list choices with physical boundary markers. A shared label formatter should use actual display metadata and locale fallbacks, with exact object IDs retained only internally.

Names may repeat. Local numbering can disambiguate duplicate route captions within a page; positions/physical marker labels should help connect a chosen label to its destination. Same entry/exit can have multiple authored paths (`authoring/CurrentRouteRecording.kt:79–85`), so endpoint names alone do not guarantee unique route identity. Do not assign indicator digits from names or row numbers.

### Existing editor and transaction patterns

`authoring/SectionWandHandler.kt:100–103` reserves sneaking right-click for settings and sneaking left-click for clearing current selection. Settings at lines 186–210 presently only toggle retained devices, and use session, world, held-tool state, permission, dialogToken and one-shot callback guards. Avoid stealing an existing gesture without an explicit UX decision. A settings action to enter a boundary naming/select mode is a plausible minimal route.

`authoring/WandDialogs.kt:34–53` provides the existing native Paper text-input pattern: initial text, maxLength, customClick view.getText, audience identity check, one-use callbacks, submit/cancel. It is placement-specific, so add a small dedicated name input rather than treating names as placement numbers. Always render user text with Component.text; no MiniMessage parsing or command interpolation.

`authoring/SectionWandBackend.kt:83–91,153–155` demonstrates exact capture checks and manager.applyDefinitionPatch. Reuse the sole manager authoring transaction, worker preparation, definitionRevision, original runtime/topology, and active callback checks. No direct disk write from the dialog. `definition-edit-impact.md` describes full candidate compile/staged save/publication and edit permits. Pure metadata may still trigger region edit protection under the existing comparison; decide whether retaining conservative behavior is sufficient, rather than adding a metadata-only bypass incidentally.

Region revisions and route-table regionRevision need care: `region/ProtectionRouteTableCatalog.kt:225` checks region-target revision and subsequently revalidates older routes. A name-only rename must preserve route IDs, paths, signal bindings and indicator mappings and cannot accidentally invalidate operational geometry. Use regression tests, not assumptions about revision increments.

### Smallest coherent implementation, after scope approval

For protection-only scope: add nullable name to accepted/draft/pending boundary shapes, preserve it across conversion/repair, add guarded selection+text submission to the existing section tool, and use names in physical captions and route mapping choices (entry→exit). Clear trims to null; duplicate names accepted; ID never changes. Define input limit and control-character behavior explicitly. Retain sort-by-stable-ID and row identity even when labels collide.

Target tests: persistence/SimpleSignalsWorldDefinitionStoreTest (missing-field fixture, accepted/pending/draft names); persistence/SimpleSignalsDefinitionDraftsTest; authoring/SectionTopologyTest and SectionWandBackendTest (repair/closure preservation); authoring/SectionWandHandlerTest (submit/cancel/stale/permission/duplicate callback); authoring/SectionOverlayDrawingTest; authoring/SignalWandBackendTest and RouteIndicatorWandEditorTest where present; runtime/SimpleSignalsWorldRuntimeManagerTest (single transaction, mappings/geometry unchanged). Verify exact test file availability before planning commands.

## Related Specs / External References

- `.trellis/workflow.md`: planning artifacts and user implementation approval required before task start.
- `.trellis/spec/simple-signals/backend/index.md`: sidecar, authored boundaries and region route tables, quality gate.
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`: tool gestures, confirmation/session validity, overlays, indicator mapping.
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md`: sole definition transaction and edit protection.
- `.trellis/spec/simple-signals/backend/section-topology-revalidation.md`: accepted geometry versus pending/draft repair and retained route/device state.
- `.trellis/spec/simple-signals/backend/current-route-recording.md`: entry/exit pairing and exact saved paths.
- No external research required for this internal planning question. Paper dialog usage is grounded in the existing repository API usage, not a new platform capability claim.
- Prior decision checked in `/home/user/.codex/memories/MEMORY.md:368`: route-to-appearance mappings remain explicit; no route-ID-derived placeholders. Verified current mapping flow in the source above.

## Caveats / Not Found

- Research only. No implementation, task activation, tests, deployment, or git operation performed.
- Block boundaries lack independent persisted identity; all-boundary naming needs further design after user scope decision.
- Exact final naming gesture, optional character limit, disambiguation presentation, and whether same physical cut owned by separate regions shares text are product decisions still open.
- Full manager metadata-only behavior and every fixed-block conversion/revision path were not exhaustively audited; keep them as implementation review checkpoints.
