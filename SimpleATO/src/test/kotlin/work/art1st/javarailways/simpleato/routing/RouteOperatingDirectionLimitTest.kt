package work.art1st.javarailways.simpleato.routing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import java.util.UUID

class RouteOperatingDirectionLimitTest {
    private val single = UUID(0L, 1L)
    private val reversing = UUID(0L, 2L)
    private val topology = buildTestTopology(3L,
        listOf(TestTopologyRail(single, 100.0), TestTopologyRail(reversing, 100.0)), emptyMap())
    private val directions = RailOperatingDirections(topology,
        mapOf(single to ViewDirection.AB, reversing to null))

    @Test
    fun `mixed rail reverse keeps the full protection path and stops at the first illegal rail`() {
        val route = route(segment(reversing, ViewDirection.BA, 20.0, 100.0),
            segment(single, ViewDirection.BA, 0.0, 100.0))
        val limit = RouteOperatingDirectionLimit.calculate("world", route, topology, directions)
        assertEquals(180.0, route.totalLengthMeters)
        assertEquals(80.0, limit.endRouteDistanceMeters)
        limit.requireLegalThrough(75.0)
        limit.requireLegalThrough(80.0)
        assertThrows(IllegalArgumentException::class.java) { limit.requireLegalThrough(80.01) }
        limit.requireMatches(FrozenRouteSelectionResult.Selected("train", RailwayStateStamp("world", 1, 3), route))
        assertThrows(IllegalArgumentException::class.java) {
            limit.requireMatches(FrozenRouteSelectionResult.Selected("train", RailwayStateStamp("other", 1, 3), route))
        }
    }

    @Test
    fun `a reversed body already on a forbidden rail rejects even if the head and target are legal`() {
        val route = route(segment(single, ViewDirection.BA, 95.0, 100.0),
            segment(reversing, ViewDirection.BA, 0.0, 50.0))
        val limit = RouteOperatingDirectionLimit.calculate("world", route, topology, directions)
        assertEquals(0.0, limit.endRouteDistanceMeters)
        assertThrows(IllegalArgumentException::class.java) { limit.requireLegalThrough(10.0) }
    }

    @Test
    fun `forward mixed AB and BA geometry uses each physical rail direction`() {
        val route = route(segment(single, ViewDirection.AB, 10.0, 100.0),
            segment(reversing, ViewDirection.BA, 0.0, 60.0))
        val limit = RouteOperatingDirectionLimit.calculate("world", route, topology, directions)
        assertEquals(route.totalLengthMeters, limit.endRouteDistanceMeters)
        limit.requireLegalThrough(route.totalLengthMeters)
    }

    @Test
    fun `unrelated topology revision preserves a reused frozen route with current exact direction read`() {
        val retained = route(segment(reversing, ViewDirection.BA, 10.0, 80.0))
        val current = buildTestTopology(4L,
            listOf(TestTopologyRail(single, 100.0), TestTopologyRail(reversing, 100.0),
                TestTopologyRail(UUID(0L, 99L), 50.0)), emptyMap())
        val limit = RouteOperatingDirectionLimit.calculate("world", retained, current,
            RailOperatingDirections(current, mapOf(reversing to null)))
        assertSame(retained, limit.route)
        limit.requireLegalThrough(retained.totalLengthMeters)
    }

    @Test
    fun `missing subset entries rails and stale topology are rejected instead of allowing both directions`() {
        val route = route(segment(reversing, ViewDirection.BA, 0.0, 30.0))
        assertThrows(IllegalArgumentException::class.java) {
            RouteOperatingDirectionLimit.calculate("world", route, topology, RailOperatingDirections(topology, emptyMap()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteOperatingDirectionLimit.calculate("world", route(segment(UUID(0L, 99L), ViewDirection.AB, 0.0, 30.0)), topology, directions)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteOperatingDirectionLimit.calculate("world", route, buildTestTopology(4L, listOf(TestTopologyRail(reversing, 100.0)), emptyMap()), directions)
        }
    }

    private fun route(vararg segments: RouteSegmentSnapshot) = RouteSnapshot("test:route", 1, topology.revision, segments.toList())
    private fun segment(id: UUID, facing: ViewDirection, start: Double, end: Double) =
        RouteSegmentSnapshot(id, facing, start, end)
}
