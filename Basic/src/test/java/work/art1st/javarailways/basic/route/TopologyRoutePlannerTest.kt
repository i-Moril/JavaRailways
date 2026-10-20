package work.art1st.javarailways.basic.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.route.OperationalRouteLocation
import work.art1st.javarailways.basic.route.TopologyRoutePlanner
import work.art1st.javarailways.basic.route.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.route.TopologyRoutePlanningRejection
import work.art1st.javarailways.basic.route.TopologyRoutePlanningResult
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class TopologyRoutePlannerTest {
    @Test
    fun `facing-constrained route at an edge end retains its exact zero-length start`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val topology = topology(
            revision = 6L,
            rails = listOf(first, second),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
            )
        )

        val result = TopologyRoutePlanner.plan(
            input(topology, location(first, 10.0), location(second, 5.0)).copy(
                startFacing = ViewDirection.AB
            )
        )

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(
            listOf(
                segment(first, ViewDirection.AB, 10.0, 10.0),
                segment(second, ViewDirection.AB, 0.0, 5.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `facing-constrained edge-end start still obeys operating direction`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val topology = topology(
            revision = 6L,
            rails = listOf(first, second),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
            )
        )

        val result = TopologyRoutePlanner.plan(
            input(
                topology,
                location(first, 10.0),
                location(second, 5.0),
                allowedDirections = mapOf(first.id to ViewDirection.BA)
            ).copy(startFacing = ViewDirection.AB)
        )

        val rejected = result as TopologyRoutePlanningResult.Rejected
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE, rejected.reason)
    }

    @Test
    fun `same normalized edge is virtually split and emitted in physical rail order`() {
        val first = rail(1, 10.0)
        val middle = rail(2, 20.0)
        val last = rail(3, 30.0)
        val topology = topology(
            revision = 7L,
            rails = listOf(first, middle, last),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(last, RailEndpoint.A)
            )
        )

        val result = plan(topology, location(first, 2.0), location(last, 25.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(7L, planned.route.topologyRevision)
        assertEquals(23L, planned.route.revision)
        assertEquals("test-route", planned.route.routeId)
        assertEquals(53.0, planned.route.totalLengthMeters, 1e-12)
        assertEquals(
            listOf(
                segment(first, ViewDirection.AB, 2.0, 10.0),
                segment(middle, ViewDirection.AB, 0.0, 20.0),
                segment(last, ViewDirection.AB, 0.0, 25.0)
            ),
            planned.route.segments
        )
        assertEquals(1, topology.edges.size, "query-local virtual endpoints must not split TopologyState")
    }

    @Test
    fun `reverse traversal converts canonical AB positions to increasing BA coordinates`() {
        val first = rail(1, 10.0)
        val middle = rail(2, 20.0)
        val last = rail(3, 30.0)
        val topology = topology(
            revision = 8L,
            rails = listOf(first, middle, last),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(middle, RailEndpoint.A),
                endpoint(middle, RailEndpoint.B) to endpoint(last, RailEndpoint.A)
            )
        )

        val result = plan(topology, location(last, 25.0), location(first, 2.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(
            listOf(
                segment(last, ViewDirection.BA, 5.0, 30.0),
                segment(middle, ViewDirection.BA, 0.0, 20.0),
                segment(first, ViewDirection.BA, 0.0, 8.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `rail stored backwards inside an edge still emits its canonical BA coordinates`() {
        val first = rail(1, 10.0)
        val reversed = rail(2, 20.0)
        val topology = topology(
            revision = 81L,
            rails = listOf(first, reversed),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(reversed, RailEndpoint.B)
            )
        )

        val result = plan(topology, location(first, 2.0), location(reversed, 5.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(
            listOf(
                segment(first, ViewDirection.AB, 2.0, 10.0),
                segment(reversed, ViewDirection.BA, 0.0, 15.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `legal turnout continuation selects one turnout route without reading live selection`() {
        val trunk = rail(1, 12.0)
        val straight = rail(2, 15.0)
        val branch = rail(3, 18.0)
        val topology = topology(
            revision = 9L,
            rails = listOf(trunk, straight, branch),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
            )
        )

        val result = plan(topology, location(trunk, 1.0), location(branch, 10.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(
            listOf(
                segment(trunk, ViewDirection.AB, 1.0, 12.0),
                segment(branch, ViewDirection.AB, 0.0, 10.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `turnout boundary goal emits a zero length target marker without traversing past it`() {
        val trunk = rail(1, 12.0)
        val straight = rail(2, 15.0)
        val branch = rail(3, 18.0)
        val topology = topology(
            revision = 91L,
            rails = listOf(trunk, straight, branch),
            connections = listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
            )
        )

        val result = plan(topology, location(trunk, 1.0), location(branch, 0.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(11.0, planned.route.totalLengthMeters, 1e-12)
        assertEquals(
            listOf(
                segment(trunk, ViewDirection.AB, 1.0, 12.0),
                segment(branch, ViewDirection.AB, 0.0, 0.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `direction overlay filters alternatives and two turnout route uses actual metre cost`() {
        val entry = rail(1, 5.0)
        val shorter = rail(2, 10.0)
        val longer = rail(3, 20.0)
        val exit = rail(4, 5.0)
        val topology = alternativeTopology(10L, entry, shorter, longer, exit)

        val unrestricted = plan(topology, location(entry, 0.0), location(exit, 5.0))
            as TopologyRoutePlanningResult.Planned
        assertEquals(listOf(entry.id, shorter.id, exit.id), unrestricted.route.segments.map { it.railId })
        assertEquals(20.0, unrestricted.route.totalLengthMeters, 1e-12)

        val constrained = plan(
            topology,
            location(entry, 0.0),
            location(exit, 5.0),
            allowedDirections = mapOf(shorter.id to ViewDirection.BA)
        ) as TopologyRoutePlanningResult.Planned
        assertEquals(listOf(entry.id, longer.id, exit.id), constrained.route.segments.map { it.railId })
        assertEquals(30.0, constrained.route.totalLengthMeters, 1e-12)
    }

    @Test
    fun `equal cost alternatives select the same deterministic traversal for reordered fixture input`() {
        val entry = rail(1, 5.0)
        val firstBranch = rail(2, 10.0)
        val secondBranch = rail(3, 10.0)
        val exit = rail(4, 5.0)
        val forward = alternativeTopology(12L, entry, firstBranch, secondBranch, exit)
        val reordered = topology(
            revision = 12L,
            rails = listOf(exit, secondBranch, firstBranch, entry),
            connections = alternativeConnections(entry, firstBranch, secondBranch, exit).reversed()
        )

        val first = plan(forward, location(entry, 0.0), location(exit, 5.0))
            as TopologyRoutePlanningResult.Planned
        val second = plan(reordered, location(entry, 0.0), location(exit, 5.0))
            as TopologyRoutePlanningResult.Planned

        assertEquals(first.route.segments, second.route.segments)
        assertEquals(listOf(entry.id, firstBranch.id, exit.id), first.route.segments.map { it.railId })
    }

    @Test
    fun `pure loop can wrap through its anchor when direct direction is forbidden`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val third = rail(3, 30.0)
        val topology = topology(
            revision = 13L,
            rails = listOf(first, second, third),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
                endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
            )
        )

        val result = plan(
            topology,
            location(first, 8.0),
            location(first, 2.0),
            allowedDirections = mapOf(first.id to ViewDirection.AB)
        )

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(54.0, planned.route.totalLengthMeters, 1e-12)
        assertEquals(
            listOf(first.id, second.id, third.id, first.id),
            planned.route.segments.map { it.railId }
        )
        assertTrue(planned.route.segments.all { it.facing == ViewDirection.AB })
        assertEquals(8.0, planned.route.segments.first().startArcLengthMeters, 1e-12)
        assertEquals(2.0, planned.route.segments.last().endArcLengthMeters, 1e-12)
    }

    @Test
    fun `loop anchor cut goal stops at the matching port instead of taking another lap`() {
        val first = rail(1, 10.0)
        val second = rail(2, 20.0)
        val third = rail(3, 30.0)
        val topology = topology(
            revision = 131L,
            rails = listOf(first, second, third),
            connections = listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
                endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
            )
        )

        val result = plan(topology, location(third, 25.0), location(first, 0.0))

        val planned = result as TopologyRoutePlanningResult.Planned
        assertEquals(5.0, planned.route.totalLengthMeters, 1e-12)
        assertEquals(
            listOf(
                segment(third, ViewDirection.AB, 25.0, 30.0),
                segment(first, ViewDirection.AB, 0.0, 0.0)
            ),
            planned.route.segments
        )
    }

    @Test
    fun `disconnected topology and invalid inputs return typed rejection`() {
        val first = rail(1, 10.0)
        val second = rail(2, 10.0)
        val topology = topology(14L, listOf(first, second), emptyList())

        assertRejected(
            TopologyRoutePlanningRejection.UNREACHABLE,
            plan(topology, location(first, 1.0), location(second, 9.0))
        )
        assertRejected(
            TopologyRoutePlanningRejection.INVALID_LOCATION,
            plan(topology, location(first, 11.0), location(first, 9.0))
        )
        assertRejected(
            TopologyRoutePlanningRejection.INVALID_LOCATION,
            plan(topology, location(first, 3.0), location(first, 3.0))
        )
        assertRejected(
            TopologyRoutePlanningRejection.INVALID_RESTRICTIONS,
            plan(
                topology,
                location(first, 1.0),
                location(first, 9.0),
                allowedDirections = mapOf(UUID(0L, 999L) to ViewDirection.AB)
            )
        )

        val stale = TopologyRoutePlanner.plan(
            input(
                topology,
                location(first, 1.0),
                location(first, 9.0),
                overlayTopologyRevision = topology.revision - 1
            )
        )
        assertTrue(stale is TopologyRoutePlanningResult.Planned)
    }

    private fun plan(
        topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState,
        start: OperationalRouteLocation,
        end: OperationalRouteLocation,
        allowedDirections: Map<UUID, ViewDirection> = emptyMap()
    ): TopologyRoutePlanningResult = TopologyRoutePlanner.plan(
        input(topology, start, end, allowedDirections = allowedDirections)
    )

    private fun input(
        topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState,
        start: OperationalRouteLocation,
        end: OperationalRouteLocation,
        overlayTopologyRevision: Long = topology.revision,
        allowedDirections: Map<UUID, ViewDirection> = emptyMap()
    ) = TopologyRoutePlanningInput(
        topology = topology,
        restrictions = RoutePlanningRestrictions(
            topologyRevision = overlayTopologyRevision,
            forbiddenTraversals = allowedDirections.map { (railId, permitted) ->
                val projection = topology.railProjection[railId]?.singleOrNull()
                val railLength = projection?.let {
                    kotlin.math.abs(it.railEndAbMeters - it.railStartAbMeters)
                } ?: 1.0
                DirectedRailSpan(
                    railId,
                    if (permitted == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB,
                    0.0,
                    railLength
                )
            }
        ),
        start = start,
        end = end,
        routeId = "test-route",
        routeRevision = 23L
    )

    private fun alternativeTopology(
        revision: Long,
        entry: TestTopologyRail,
        firstBranch: TestTopologyRail,
        secondBranch: TestTopologyRail,
        exit: TestTopologyRail
    ) = topology(
        revision,
        listOf(entry, firstBranch, secondBranch, exit),
        alternativeConnections(entry, firstBranch, secondBranch, exit)
    )

    private fun alternativeConnections(
        entry: TestTopologyRail,
        firstBranch: TestTopologyRail,
        secondBranch: TestTopologyRail,
        exit: TestTopologyRail
    ) = listOf(
        endpoint(entry, RailEndpoint.B) to endpoint(firstBranch, RailEndpoint.A),
        endpoint(entry, RailEndpoint.B) to endpoint(secondBranch, RailEndpoint.A),
        endpoint(firstBranch, RailEndpoint.B) to endpoint(exit, RailEndpoint.A),
        endpoint(secondBranch, RailEndpoint.B) to endpoint(exit, RailEndpoint.A)
    )

    private fun topology(
        revision: Long,
        rails: List<TestTopologyRail>,
        connections: List<Pair<RailEndpointRef, RailEndpointRef>>
    ) = buildTestTopology(
        revision = revision,
        rails = rails,
        continuations = connections.toContinuationMap()
    )

    private fun List<Pair<RailEndpointRef, RailEndpointRef>>.toContinuationMap():
        Map<RailEndpointRef, Set<RailEndpointRef>> {
        val result = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        forEach { (first, second) ->
            result.getOrPut(first, ::linkedSetOf).add(second)
            result.getOrPut(second, ::linkedSetOf).add(first)
        }
        return result.mapValues { (_, targets) -> targets.toSet() }
    }

    private fun assertRejected(
        expected: TopologyRoutePlanningRejection,
        result: TopologyRoutePlanningResult
    ) {
        assertEquals(expected, (result as TopologyRoutePlanningResult.Rejected).reason)
    }

    private fun rail(number: Int, lengthMeters: Double) =
        TestTopologyRail(UUID(0L, number.toLong()), lengthMeters)

    private fun endpoint(rail: TestTopologyRail, endpoint: RailEndpoint) =
        RailEndpointRef(rail.id, endpoint)

    private fun location(rail: TestTopologyRail, abMeters: Double) =
        OperationalRouteLocation(rail.id, abMeters)

    private fun segment(
        rail: TestTopologyRail,
        facing: ViewDirection,
        start: Double,
        end: Double
    ) = RouteSegmentSnapshot(rail.id, facing, start, end)
}
