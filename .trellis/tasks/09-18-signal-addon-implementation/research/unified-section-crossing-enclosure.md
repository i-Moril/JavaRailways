# Research: Unified section wand and crossing enclosure

- Query: Minimal topology changes required for auto-classifying a closed wand component as a block or protection region, including diamonds without allowing cross-line routes.
- Scope: internal, bounded source inspection only
- Date: 2026-10-16

## Findings

### Existing representation and ownership

- `Core/src/main/java/work/art1st/javarailways/core/api/physics/rail/topology/TopologyState.kt:163` defines `RailCrossingPoint` and `AtGradeRailCrossing`; the latter is explicitly geometry-only, NOT a structural node or legal continuation. `atGradeCrossingsOn(railId)` supplies a root-bound immutable incident index (line 337).
- `Basic/src/main/java/work/art1st/javarailways/basic/infrastructure/PhysicalTopologyResources.kt:20` creates one shared diamond resource with anchors on both rails; identity uses ordered rail UUIDs plus crossing arc coordinates (line 40). Existing physical resource compilation includes route-bound resource hits and both occupancy anchors (`PhysicalRouteResourceCompiler.kt:127`). No new resource identity/claim mechanism is needed.
- `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/authoring/SectionTopology.kt:27` exposes only real endpoint continuations. `ProtectionRegionTopology.kt:73` likewise contains only real turnout/ordinary/loop connections. Keep these traversal connections unchanged.

### Current enclosure limitation

- `ProtectionBoundarySearch.kt:83` expands only rail endpoint continuations; it does not read crossing observations. `ProtectionRegionGraphResolver.kt:72` computes a component from the first inward boundary; its BFS (line 139) likewise uses endpoints only. Thus two physically unconnected crossing rails cannot currently form one four-boundary protection region: the other line is rejected as `DISCONNECTED_INTERIOR`.
- Merely classifying a finished two-cut line as protection because it crosses another line is insufficient: the other line would remain outside the region. Enclosure search and final candidate resolver must both treat crossing points as conflict connectivity.
- Minimal design: add crossing-point links ONLY to enclosure/component traversal. Link the cut-segment containing each crossing point to the corresponding segment on the other rail. Do not insert crossing links into rail endpoint connections or operating route traversal.
- Search has a shortest-distance radius contract. Its current distances live at the two ends of each boundary-cut segment; crossing links enter at interior arc positions. Preserve exact distances by locally splitting searchable intervals at crossing positions, linking the two crossing points at zero cost, and retaining original authored boundary cuts as stopping boundaries. Offering the other rail's endpoints at the current endpoint distance would produce wrong distance/range results. This is a local build-time projection, not a new world graph.
- Cuts exactly on crossing points require an explicit unambiguous policy (e.g. reject cuts through the conflict point); assigning such a crossing to both cut sides can accidentally reconnect outside. Do not accidentally merge unrelated rails from spatial proximity; use published crossing observations only.

### Route discovery remains separate

- `ProtectionRouteDiscovery.kt:33` builds directed edges only through `topology.continuations`, so it already avoids turning at a diamond. With a four-cut two-line interior, it should generate the four directed straight routes on two bidirectional lines, never any cross-line route.
- `ProtectionRouteDiscoveryTest.kt:101` already verifies diamond geometry does not create cross-line routes, but its fixture has ONLY two cuts on the first line. Once enclosure follows conflict connectivity, replace/extend this fixture with four inward cuts and direction entries for both rails; otherwise the fixture no longer represents an enclosed region.
- Shared fixture `SimpleSignals/src/test/kotlin/work/art1st/javarailways/simplesignals/region/ProtectionTopologyTestFixtures.kt:20` accepts `atGradeCrossings` and passes them into Core `buildTestTopology`. Reuse rather than inventing another graph builder.

### Closed non-conflict component to block

- `BlockPathBuilder.path` (`SectionTopology.kt:64`) walks both AB/BA and requires exactly one path; passed snapshot facing does not select its direction. It refuses turnouts, ambiguous loop paths, and repeated directed rail visits.
- A block conversion should happen only after successful complete enclosure and exactly two inward boundaries. Verify the discovered first traversal faces inward at the first cut and the final traversal faces opposite to the second inward cut. `BlockPathBuilder.definition` already applies public operating direction consistency, reversal, and bidirectionality.
- Existing enclosure stops completion for an unmarked rail end, wrong cut facing/outside reconnection, and distance overflow (`ProtectionBoundarySearch.kt:109`). Retain these rules to prevent premature completion at the first or wrong-facing second cut. A no-conflict loop with two alternative paths must remain rejected rather than guessed.
- Existing protection builder requires cuts strictly inside rails (`SectionAuthoringActions.kt:108`); existing block builder permits endpoints. Unifying those interactions must consciously preserve or explain this difference, especially adjacent blocks at ordinary rail joints. Also existing blocks have no protection-radius cap (`SectionTopologyTest.kt:47`); a blanket protection search cap would newly limit long ordinary blocks.

## Relevant specs and focused tests

- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`: directed-cut closure, topology reuse, shortest-distance radius, pending durable boundaries.
- `.trellis/spec/simple-signals/backend/automatic-protection-routes.md`: route edges only from legal continuations; boundary cuts stop routes, shortest route per ordered pair.
- Owner tests: `SectionTopologyTest.kt`, `SectionAuthoringActionsTest.kt`, `RuntimeSectionWandBackendTest.kt`, `ProtectionRegionGraphResolverTest.kt`, `ProtectionRegionTopologyTest.kt`, `ProtectionRouteDiscoveryTest.kt`.
- Useful focused behavior cases: a diamond with a missing arm cut stays pending; four inward cuts resolve one region; no cross-line routes; crossing beyond an authored cut is ignored; a crossing-free two-cut line becomes a block; wrong-facing cut does not close; long block does not silently inherit protection-only radius restrictions.

## Caveats / Not Found

- No Gradle, server access, network research, deployment, or code changes performed. This is source-level evidence, not runtime validation.
- Existing graph resolver does not reject unmarked dead ends by itself; that check belongs to the interactive boundary search. Do not claim final resolver alone enforces the same interactive completion contract.
- Main session owns implementation scope and any necessary clarification; no schema/API redesign is proposed here.
