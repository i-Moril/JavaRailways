package work.art1st.javarailways.basic.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PathReplacement
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class TopologyRouteRestrictionTest {
    @Test
    fun `public restriction values detach caller collections`() {
        val rail = rail(1, 10.0)
        val spans = mutableListOf(rail.span(ViewDirection.AB))
        val path = PhysicalTraversalPath(spans)
        spans.clear()
        val paths = mutableListOf(path)
        val replacement = PathReplacement(path.spans, paths)
        paths.clear()
        val replacements = mutableListOf(replacement)
        val restrictions = RoutePlanningRestrictions(1L, pathReplacements = replacements)
        replacements.clear()

        assertEquals(1, path.spans.size)
        assertEquals(1, replacement.permittedPaths.size)
        assertEquals(1, restrictions.pathReplacements.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (path.spans as MutableList).clear()
        }
    }

    @Test
    fun `replacement suppresses free alternatives and exposes only complete permitted path`() {
        val fixture = alternatives()
        val restrictions = RoutePlanningRestrictions(
            fixture.topology.revision,
            pathReplacements = listOf(
                PathReplacement(
                    replacedTraversals = listOf(
                        fixture.shorter.span(ViewDirection.AB),
                        fixture.longer.span(ViewDirection.AB)
                    ),
                    permittedPaths = listOf(
                        PhysicalTraversalPath(listOf(fixture.longer.span(ViewDirection.AB)))
                    )
                )
            )
        )

        val planned = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.exit.id, fixture.exit.lengthMeters),
            restrictions
        ) as TopologyRoutePlanningResult.Planned

        assertEquals(
            listOf(fixture.entry.id, fixture.longer.id, fixture.exit.id),
            planned.route.segments.map { it.railId }
        )
    }

    @Test
    fun `search cannot leave an atomic permitted path at an interior goal`() {
        val fixture = alternatives()
        val restrictions = RoutePlanningRestrictions(
            fixture.topology.revision,
            pathReplacements = listOf(
                PathReplacement(
                    replacedTraversals = listOf(fixture.longer.span(ViewDirection.AB)),
                    permittedPaths = listOf(
                        PhysicalTraversalPath(listOf(fixture.longer.span(ViewDirection.AB)))
                    )
                )
            )
        )

        val rejected = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.longer.id, fixture.longer.lengthMeters / 2.0),
            restrictions
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.reason)
    }

    @Test
    fun `forbidden traversal also removes a permitted replacement path`() {
        val fixture = alternatives()
        val longPath = fixture.longer.span(ViewDirection.AB)
        val restrictions = RoutePlanningRestrictions(
            topologyRevision = fixture.topology.revision,
            forbiddenTraversals = listOf(longPath),
            pathReplacements = listOf(
                PathReplacement(
                    replacedTraversals = listOf(
                        fixture.shorter.span(ViewDirection.AB),
                        longPath
                    ),
                    permittedPaths = listOf(PhysicalTraversalPath(listOf(longPath)))
                )
            )
        )

        val rejected = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.exit.id, fixture.exit.lengthMeters),
            restrictions
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.reason)
    }

    @Test
    fun `forbidden direction cannot be selected only as a zero length goal marker`() {
        val fixture = alternatives()
        val restrictions = RoutePlanningRestrictions(
            topologyRevision = fixture.topology.revision,
            forbiddenTraversals = listOf(fixture.exit.span(ViewDirection.AB))
        )

        val rejected = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.exit.id, 0.0),
            restrictions
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.reason)
    }

    @Test
    fun `required goal facing is enforced at the target location`() {
        val fixture = alternatives()
        val input = TopologyRoutePlanningInput(
            topology = fixture.topology,
            restrictions = RoutePlanningRestrictions(fixture.topology.revision),
            start = OperationalRouteLocation(fixture.entry.id, 0.0),
            end = OperationalRouteLocation(fixture.exit.id, fixture.exit.lengthMeters),
            routeId = "test:goal-facing",
            routeRevision = 1L,
            startFacing = ViewDirection.AB,
            endFacing = ViewDirection.AB
        )

        assertEquals(
            TopologyRoutePlanningRejection.UNREACHABLE,
            (TopologyRoutePlanner.plan(input.copy(endFacing = ViewDirection.BA)) as
                TopologyRoutePlanningResult.Rejected).reason
        )
        val planned = TopologyRoutePlanner.plan(input) as TopologyRoutePlanningResult.Planned
        assertEquals(ViewDirection.AB, planned.route.segments.last().facing)
    }

    @Test
    fun `disconnected permitted path is rejected before search`() {
        val fixture = alternatives()
        val restrictions = RoutePlanningRestrictions(
            fixture.topology.revision,
            pathReplacements = listOf(
                PathReplacement(
                    replacedTraversals = listOf(
                        fixture.shorter.span(ViewDirection.AB),
                        fixture.longer.span(ViewDirection.AB)
                    ),
                    permittedPaths = listOf(
                        PhysicalTraversalPath(
                            listOf(
                                fixture.shorter.span(ViewDirection.AB),
                                fixture.longer.span(ViewDirection.AB)
                            )
                        )
                    )
                )
            )
        )

        val rejected = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.exit.id, fixture.exit.lengthMeters),
            restrictions
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.INVALID_RESTRICTIONS, rejected.reason)
    }

    @Test
    fun `two path replacements cannot own the same directed interval`() {
        val fixture = alternatives()
        val shared = fixture.longer.span(ViewDirection.AB)
        val replacement = PathReplacement(
            replacedTraversals = listOf(shared),
            permittedPaths = listOf(PhysicalTraversalPath(listOf(shared)))
        )
        val restrictions = RoutePlanningRestrictions(
            fixture.topology.revision,
            pathReplacements = listOf(replacement, replacement)
        )

        val rejected = plan(
            fixture,
            OperationalRouteLocation(fixture.entry.id, 0.0),
            OperationalRouteLocation(fixture.exit.id, fixture.exit.lengthMeters),
            restrictions
        ) as TopologyRoutePlanningResult.Rejected

        assertEquals(TopologyRoutePlanningRejection.INVALID_RESTRICTIONS, rejected.reason)
    }

    private fun plan(
        fixture: AlternativeFixture,
        start: OperationalRouteLocation,
        end: OperationalRouteLocation,
        restrictions: RoutePlanningRestrictions
    ) = TopologyRoutePlanner.plan(
        TopologyRoutePlanningInput(
            topology = fixture.topology,
            restrictions = restrictions,
            start = start,
            end = end,
            routeId = "test:restricted-route",
            routeRevision = 1L,
            startFacing = ViewDirection.AB
        )
    )

    private fun alternatives(): AlternativeFixture {
        val entry = rail(1, 5.0)
        val shorter = rail(2, 10.0)
        val longer = rail(3, 20.0)
        val exit = rail(4, 5.0)
        val topology = buildTestTopology(
            revision = 30L,
            rails = listOf(entry, shorter, longer, exit),
            continuations = listOf(
                endpoint(entry, RailEndpoint.B) to endpoint(shorter, RailEndpoint.A),
                endpoint(entry, RailEndpoint.B) to endpoint(longer, RailEndpoint.A),
                endpoint(shorter, RailEndpoint.B) to endpoint(exit, RailEndpoint.A),
                endpoint(longer, RailEndpoint.B) to endpoint(exit, RailEndpoint.A)
            ).toContinuationMap()
        )
        return AlternativeFixture(topology, entry, shorter, longer, exit)
    }

    private fun TestTopologyRail.span(facing: ViewDirection) =
        DirectedRailSpan(id, facing, 0.0, lengthMeters)

    private fun rail(number: Int, lengthMeters: Double) =
        TestTopologyRail(UUID(0L, number.toLong()), lengthMeters)

    private fun endpoint(rail: TestTopologyRail, endpoint: RailEndpoint) =
        RailEndpointRef(rail.id, endpoint)

    private fun List<Pair<RailEndpointRef, RailEndpointRef>>.toContinuationMap():
        Map<RailEndpointRef, Set<RailEndpointRef>> {
        val result = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        forEach { (first, second) ->
            result.getOrPut(first, ::linkedSetOf) += second
            result.getOrPut(second, ::linkedSetOf) += first
        }
        return result.mapValues { (_, targets) -> targets.toSet() }
    }

    private data class AlternativeFixture(
        val topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState,
        val entry: TestTopologyRail,
        val shorter: TestTopologyRail,
        val longer: TestTopologyRail,
        val exit: TestTopologyRail
    )
}
