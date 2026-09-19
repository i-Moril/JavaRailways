package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ProtectedRouteTest {
    private val railId = UUID.randomUUID()

    @Test
    fun `composite request copies ordered windows and allows clearance into next region`() {
        val mutable = mutableListOf(
            window("test:region/a", 0.0, 40.0, 60.0),
            window("test:region/b", 40.0, 80.0, 100.0)
        )

        val request = request(mutable)
        mutable.clear()

        assertEquals(2, request.windows.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (request.windows as MutableList<ProtectedRouteWindow>).clear()
        }
    }

    @Test
    fun `region extents cannot overlap even when submitted as one composite request`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(
                listOf(
                    window("test:region/a", 0.0, 50.0, 70.0),
                    window("test:region/b", 40.0, 80.0, 100.0)
                )
            )
        }
    }

    @Test
    fun `clearance must remain inside frozen route`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(listOf(window("test:region/a", 0.0, 80.0, 101.0)))
        }
    }

    private fun request(windows: Collection<ProtectedRouteWindow>) = CompositeProtectedRouteRequest(
        requestId = "test:request/main",
        owner = InfrastructureOwner("test:route/main", 1L),
        trainId = "train",
        topologyRevision = 4L,
        route = RouteSnapshot(
            "route",
            2L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
        ),
        windows = windows
    )

    private fun window(
        regionId: String,
        entryMeters: Double,
        exitMeters: Double,
        clearanceMeters: Double
    ) = ProtectedRouteWindow(
        ProtectionRegionRef(regionId, 1L),
        boundary("$regionId/entry", entryMeters),
        boundary("$regionId/exit", exitMeters),
        ExitClearanceEnvelope(boundary("$regionId/clear", clearanceMeters))
    )

    private fun boundary(id: String, routeDistanceMeters: Double) = DirectedRouteBoundary(
        boundaryId = id,
        position = RailPositionSnapshot(railId, ViewDirection.AB, routeDistanceMeters),
        routeDistanceMeters = routeDistanceMeters
    )
}
