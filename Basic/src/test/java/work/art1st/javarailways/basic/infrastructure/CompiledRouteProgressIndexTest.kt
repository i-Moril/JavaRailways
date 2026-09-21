package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import java.util.UUID

class CompiledRouteProgressIndexTest {
    private val revision = 7L

    @Test
    fun `AB and BA positions use canonical rail coordinates`() {
        val abRail = rail(1)
        val baRail = rail(2)
        val topology = topologyState(
            revision,
            listOf(abRail, baRail),
            emptyList()
        )
        val ab = index(topology, segment(abRail, ViewDirection.AB, 2.0, 8.0))
        val ba = index(topology, segment(baRail, ViewDirection.BA, 2.0, 8.0))

        assertResolved(3.0, ab.project(position(abRail, ViewDirection.AB, 5.0)))
        assertResolved(3.0, ba.project(position(baRail, ViewDirection.BA, 5.0)))
    }

    @Test
    fun `AB segment bounds use geometry tolerance and clamp only matched candidates`() {
        val rail = rail(1)
        val index = index(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.AB, 2.0, 8.0)
        )

        assertResolved(
            0.0,
            index.project(position(rail, ViewDirection.AB, 2.0 - Curve.TOLERANCE / 2.0))
        )
        assertEquals(
            RouteProgressProjection.OffRoute,
            index.project(position(rail, ViewDirection.AB, 2.0 - Curve.TOLERANCE * 2.0))
        )
        assertResolved(
            6.0,
            index.project(position(rail, ViewDirection.AB, 8.0 + Curve.TOLERANCE / 2.0))
        )
        assertEquals(
            RouteProgressProjection.OffRoute,
            index.project(position(rail, ViewDirection.AB, 8.0 + Curve.TOLERANCE * 2.0))
        )
        assertEquals(
            RouteProgressProjection.OffRoute,
            index.project(position(rail, ViewDirection.BA, 5.0))
        )
    }

    @Test
    fun `BA segment bounds use canonical coordinates at both endpoints`() {
        val rail = rail(1)
        val index = index(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.BA, 2.0, 8.0)
        )

        assertResolved(
            0.0,
            index.project(position(rail, ViewDirection.BA, 8.0 + Curve.TOLERANCE / 2.0))
        )
        assertResolved(
            6.0,
            index.project(position(rail, ViewDirection.BA, 2.0 - Curve.TOLERANCE / 2.0))
        )
        assertEquals(
            RouteProgressProjection.OffRoute,
            index.project(position(rail, ViewDirection.BA, 2.0 - Curve.TOLERANCE * 2.0))
        )
    }

    @Test
    fun `adjacent and zero length occurrences collapse to one boundary progress`() {
        val rail = rail(1)
        val index = index(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.AB, 0.0, 5.0),
            segment(rail, ViewDirection.AB, 5.0, 5.0),
            segment(
                rail,
                ViewDirection.AB,
                5.0 - Curve.TOLERANCE / 2.0,
                10.0
            )
        )

        assertResolved(5.0, index.project(position(rail, ViewDirection.AB, 5.0)))
    }

    @Test
    fun `adjacent BA occurrences map their shared canonical boundary to one progress`() {
        val rail = rail(1)
        val index = index(
            lineTopologyState(revision, rail),
            segment(rail, ViewDirection.BA, 0.0, 5.0),
            segment(rail, ViewDirection.BA, 5.0, 5.0),
            segment(rail, ViewDirection.BA, 5.0, 10.0)
        )

        assertResolved(5.0, index.project(position(rail, ViewDirection.BA, 5.0)))
    }

    @Test
    fun `repeated physical position at distinct route distances is explicitly ambiguous`() {
        val first = rail(1)
        val second = rail(2)
        val third = rail(3)
        val topology = topologyState(
            revision,
            listOf(first, second, third),
            listOf(
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.B) to endpoint(third, RailEndpoint.A),
                endpoint(third, RailEndpoint.B) to endpoint(first, RailEndpoint.A)
            )
        )
        val index = index(
            topology,
            segment(first, ViewDirection.AB),
            segment(second, ViewDirection.AB),
            segment(third, ViewDirection.AB),
            segment(first, ViewDirection.AB, 0.0, 5.0)
        )

        val ambiguous = assertInstanceOf(
            RouteProgressProjection.Ambiguous::class.java,
            index.project(position(first, ViewDirection.AB, 3.0))
        )
        assertEquals(listOf(3.0, 33.0), ambiguous.routeDistancesMeters)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (ambiguous.routeDistancesMeters as MutableList<Double>).clear()
        }
        assertResolved(8.0, index.project(position(first, ViewDirection.AB, 8.0)))
    }

    private fun index(
        topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState,
        vararg segments: RouteSegmentSnapshot
    ): CompiledRouteProgressIndex {
        val projection = assertInstanceOf(
            TopologyRouteProjectionResult.Projected::class.java,
            TopologyRouteProjector.project(
                RouteSnapshot("test:route/progress", 1L, revision, segments.asList()),
                topology
            )
        )
        return CompiledRouteProgressIndex.compile(projection.route)
    }

    private fun segment(
        railId: UUID,
        facing: ViewDirection,
        startMeters: Double = 0.0,
        endMeters: Double = 10.0
    ) = RouteSegmentSnapshot(railId, facing, startMeters, endMeters)

    private fun position(railId: UUID, facing: ViewDirection, canonicalMeters: Double) =
        RailPositionSnapshot(railId, facing, canonicalMeters)

    private fun rail(value: Long): UUID = UUID(0L, value)

    private fun assertResolved(expected: Double, actual: RouteProgressProjection) {
        val resolved = assertInstanceOf(RouteProgressProjection.Resolved::class.java, actual)
        assertEquals(expected, resolved.routeDistanceMeters)
    }
}
