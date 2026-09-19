package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `request carries one throat window`() {
        val window = window("test:region/a", 0.0, 80.0, 100.0)

        assertSame(window, request(window).window)
    }

    @Test
    fun `route without a controlled throat may omit window`() {
        assertNull(request(null).window)
    }

    @Test
    fun `clearance must remain inside frozen route`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(window("test:region/a", 0.0, 80.0, 101.0))
        }
    }

    private fun request(window: ProtectedRouteWindow?) = ProtectedRouteRequest(
        requestId = "test:request/main",
        owner = InfrastructureOwner("test:route/main", 1L),
        trainId = "train",
        topologyRevision = 4L,
        route = RouteSnapshot(
            "route",
            2L,
            listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0))
        ),
        window = window
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
