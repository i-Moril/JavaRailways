package work.art1st.javarailways.basic.infrastructure

import work.art1st.javarailways.core.api.physics.infrastructure.TurnoutResourceIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyNode
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

class TopologyRouteProjectorTest {
    private val revision = 7L

    @Test
    fun `partial route through ordinary degree two chain projects onto one continuous edge`() {
        val first = rail(1)
        val middle = rail(2)
        val last = rail(3)
        val topology = lineTopologyState(revision, first, middle, last)
        val route = route(
            segment(first, ViewDirection.AB, 2.0, 10.0),
            segment(middle, ViewDirection.AB),
            segment(last, ViewDirection.AB, 0.0, 7.0)
        )

        val projected = project(route, topology)

        assertEquals(3, projected.occurrences.size)
        assertEquals(1, projected.occurrences.map { it.edgeId }.distinct().size)
        assertTrue(projected.occurrences.all {
            it.edgeDirection == ProjectedEdgeDirection.FIRST_TO_SECOND
        })
        assertEquals(listOf(0.0, 8.0, 18.0), projected.occurrences.map { it.routeStartMeters })
        assertEquals(25.0, projected.occurrences.last().routeEndMeters)
        assertTrue(projected.turnoutTraversals.isEmpty())
        assertSame(route, projected.route)
        assertSame(topology, projected.topology)
    }

    @Test
    fun `zero length BA segment has deterministic canonical position and edge direction`() {
        val rail = rail(1)
        val topology = lineTopologyState(revision, rail)
        val route = route(segment(rail, ViewDirection.BA, 3.0, 3.0))

        val occurrence = project(route, topology).occurrences.single()

        assertEquals(ProjectedEdgeDirection.SECOND_TO_FIRST, occurrence.edgeDirection)
        assertEquals(7.0, occurrence.canonicalStartAbMeters)
        assertEquals(7.0, occurrence.canonicalEndAbMeters)
        assertEquals(7.0, occurrence.edgeStartMeters)
        assertEquals(7.0, occurrence.edgeEndMeters)
        assertEquals(0.0, occurrence.routeStartMeters)
        assertEquals(0.0, occurrence.routeEndMeters)
    }

    @Test
    fun `legal turnout traversal is derived from the selected path`() {
        val fixture = turnoutFixture()
        val route = route(
            segment(fixture.trunk, ViewDirection.AB),
            segment(fixture.straight, ViewDirection.AB)
        )

        val traversal = project(route, fixture.topology).turnoutTraversals.single()
        val turnout = fixture.topology.nodes.values.filterIsInstance<TopologyNode.Turnout>().single()

        assertEquals(turnout.id, traversal.nodeId)
        assertEquals("javarailways:turnout", traversal.resource.typeId)
        assertEquals(
            listOf(
                endpoint(fixture.trunk, RailEndpoint.B),
                endpoint(fixture.straight, RailEndpoint.A),
                endpoint(fixture.branch, RailEndpoint.A)
            ).sorted().joinToString("|") { "${it.railId}@${it.endpoint.name}" },
            traversal.resource.resourceId
        )
        assertEquals(endpoint(fixture.trunk, RailEndpoint.B), traversal.fromPort)
        assertEquals(endpoint(fixture.straight, RailEndpoint.A), traversal.toPort)
        assertEquals(10.0, traversal.routeDistanceMeters)
    }

    @Test
    fun `reverse traversal derives the same turnout node identity`() {
        val fixture = turnoutFixture()
        val forward = project(
            route(
                segment(fixture.trunk, ViewDirection.AB),
                segment(fixture.branch, ViewDirection.AB)
            ),
            fixture.topology
        ).turnoutTraversals.single()
        val reverse = project(
            route(
                segment(fixture.branch, ViewDirection.BA),
                segment(fixture.trunk, ViewDirection.BA)
            ),
            fixture.topology
        ).turnoutTraversals.single()

        assertEquals(forward.nodeId, reverse.nodeId)
        assertEquals(forward.resource, reverse.resource)
        assertEquals(forward.fromPort, reverse.toPort)
        assertEquals(forward.toPort, reverse.fromPort)
    }

    @Test
    fun `turnout resource identity survives topology revision rebuild`() {
        val fixture = turnoutFixture()
        val rebuilt = topologyState(
            revision = revision + 1L,
            railIds = listOf(fixture.trunk, fixture.straight, fixture.branch),
            connections = listOf(
                endpoint(fixture.trunk, RailEndpoint.B) to endpoint(fixture.straight, RailEndpoint.A),
                endpoint(fixture.trunk, RailEndpoint.B) to endpoint(fixture.branch, RailEndpoint.A)
            )
        )
        val before = project(
            route(
                segment(fixture.trunk, ViewDirection.AB),
                segment(fixture.branch, ViewDirection.AB)
            ),
            fixture.topology
        ).turnoutTraversals.single()
        val after = project(
            route(
                segment(fixture.branch, ViewDirection.BA),
                segment(fixture.trunk, ViewDirection.BA),
                topologyRevision = revision + 1L
            ),
            rebuilt
        ).turnoutTraversals.single()

        assertTrue(before.nodeId != after.nodeId)
        assertEquals(before.resource, after.resource)
    }

    @Test
    fun `turnout resource identity is order independent and changes with its port set`() {
        val first = endpoint(rail(1), RailEndpoint.B)
        val second = endpoint(rail(2), RailEndpoint.A)
        val third = endpoint(rail(3), RailEndpoint.A)

        val canonical = TurnoutResourceIdentity.fromPorts(listOf(first, second, third))
        val reordered = TurnoutResourceIdentity.fromPorts(listOf(third, first, second))
        val changed = TurnoutResourceIdentity.fromPorts(
            listOf(first, second, endpoint(rail(4), RailEndpoint.A))
        )

        assertEquals(canonical, reordered)
        assertNotEquals(canonical, changed)
        assertThrows(IllegalArgumentException::class.java) {
            TurnoutResourceIdentity.fromPorts(listOf(first, second, second))
        }
    }

    @Test
    fun `illegal turnout continuation is rejected without selecting another branch`() {
        val fixture = turnoutFixture()
        val before = fixture.topology
        val route = route(
            segment(fixture.branch, ViewDirection.BA),
            segment(fixture.straight, ViewDirection.AB)
        )

        val rejected = reject(route, fixture.topology)

        assertTrue(rejected.detail.contains("illegal turnout continuation"))
        assertSame(before, fixture.topology)
    }

    @Test
    fun `disconnected edges and non-endpoint transitions are rejected`() {
        val first = rail(1)
        val second = rail(2)
        val disconnected = topologyState(revision, listOf(first, second), emptyList())
        val disconnectedResult = reject(
            route(segment(first, ViewDirection.AB), segment(second, ViewDirection.AB)),
            disconnected
        )

        val connected = lineTopologyState(revision, first, second)
        val partialResult = reject(
            route(
                segment(first, ViewDirection.AB, 0.0, 8.0),
                segment(second, ViewDirection.AB)
            ),
            connected
        )

        assertTrue(disconnectedResult.detail.contains("do not share a structural node"))
        assertTrue(partialResult.detail.contains("before its endpoint"))
    }

    @Test
    fun `revision provenance does not replace current structural rail validation`() {
        val rail = rail(1)
        val topology = lineTopologyState(revision, rail)
        val staleRoute = route(
            segment(rail, ViewDirection.AB),
            topologyRevision = revision - 1L
        )
        assertInstanceOf(
            TopologyRouteProjectionResult.Projected::class.java,
            TopologyRouteProjector.project(staleRoute, topology)
        )
        val missing = reject(
            route(segment(rail(99), ViewDirection.AB)),
            topology
        )
        val outOfRange = reject(
            route(segment(rail, ViewDirection.AB, 0.0, 11.0)),
            topology
        )

        assertTrue(missing.detail.contains("missing rail"))
        assertTrue(outOfRange.detail.contains("exceeds rail"))
    }

    @Test
    fun `projection collections are immutable`() {
        val rail = rail(1)
        val projected = project(
            route(segment(rail, ViewDirection.AB)),
            lineTopologyState(revision, rail)
        )

        assertThrows(UnsupportedOperationException::class.java) {
            (projected.occurrences as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (projected.turnoutTraversals as MutableList).clear()
        }
    }

    private fun turnoutFixture(): TurnoutFixture {
        val trunk = rail(1)
        val straight = rail(2)
        val branch = rail(3)
        return TurnoutFixture(
            trunk = trunk,
            straight = straight,
            branch = branch,
            topology = topologyState(
                revision = revision,
                railIds = listOf(trunk, straight, branch),
                connections = listOf(
                    endpoint(trunk, RailEndpoint.B) to endpoint(straight, RailEndpoint.A),
                    endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)
                )
            )
        )
    }

    private fun project(
        route: RouteSnapshot,
        topology: TopologyState
    ): ProjectedTopologyRoute = assertInstanceOf(
        TopologyRouteProjectionResult.Projected::class.java,
        TopologyRouteProjector.project(route, topology)
    ).route

    private fun reject(
        route: RouteSnapshot,
        topology: TopologyState
    ): TopologyRouteProjectionResult.Rejected = assertInstanceOf(
        TopologyRouteProjectionResult.Rejected::class.java,
        TopologyRouteProjector.project(route, topology)
    )

    private fun route(
        vararg segments: RouteSegmentSnapshot,
        topologyRevision: Long = revision
    ) = RouteSnapshot("test:route", 1L, topologyRevision, segments.asList())

    private fun segment(
        railId: UUID,
        facing: ViewDirection,
        startMeters: Double = 0.0,
        endMeters: Double = 10.0
    ) = RouteSegmentSnapshot(railId, facing, startMeters, endMeters)

    private fun rail(value: Long): UUID = UUID(0L, value)

    private data class TurnoutFixture(
        val trunk: UUID,
        val straight: UUID,
        val branch: UUID,
        val topology: TopologyState
    )
}
