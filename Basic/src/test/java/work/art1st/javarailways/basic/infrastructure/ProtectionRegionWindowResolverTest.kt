package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ExitClearanceEnvelope
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectionBoundaryDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionCatalogStatus
import work.art1st.javarailways.basic.api.route.ProtectionRegionDefinition
import work.art1st.javarailways.basic.api.route.ProtectionRegionProvider
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ProtectionRegionWindowResolverTest {
    private val west = UUID.randomUUID()
    private val railA = UUID.randomUUID()
    private val railB = UUID.randomUUID()
    private val railC = UUID.randomUUID()
    private val east = UUID.randomUUID()
    private var topologyRevision = 7L
    private val topology = lineTopology(topologyRevision, west, railA, railB, railC, east)

    @Test
    fun `catalog resolves one forward traversal against authored boundaries`() {
        val request = request(
            route = forwardRoute(),
            window = window(
                entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 5.0),
                exit = routeBoundary("test:boundary/exit", railC, ViewDirection.AB, 5.0, 25.0),
                clearUntil = routeBoundary("test:boundary/clear", east, ViewDirection.AB, 5.0, 35.0)
            )
        )

        val result = assertInstanceOf(
            ProtectionRegionWindowResolution.Resolved::class.java,
            catalog().resolveWindow(request)
        )

        assertEquals("test:region/main", result.window?.region?.definition?.region?.regionId)
    }

    @Test
    fun `BA traversal converts view-local distance to canonical boundary position`() {
        val request = request(
            route = reverseRoute(),
            window = window(
                entry = routeBoundary("test:boundary/exit", railC, ViewDirection.BA, 5.0, 5.0),
                exit = routeBoundary("test:boundary/entry", railA, ViewDirection.BA, 5.0, 25.0),
                clearUntil = routeBoundary("test:boundary/clear", west, ViewDirection.BA, 5.0, 35.0)
            )
        )

        assertInstanceOf(
            ProtectionRegionWindowResolution.Resolved::class.java,
            catalog().resolveWindow(request)
        )
    }

    @Test
    fun `exit using inward direction is rejected`() {
        val request = request(
            route = forwardRoute(),
            window = window(
                entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 5.0),
                exit = routeBoundary("test:boundary/exit", railC, ViewDirection.BA, 5.0, 25.0),
                clearUntil = routeBoundary("test:boundary/clear", east, ViewDirection.AB, 5.0, 35.0)
            )
        )

        val rejected = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog().resolveWindow(request)
        )

        assertEquals(ProtectionRegionWindowRejection.INVALID_ROUTE, rejected.reason)
    }

    @Test
    fun `declared route distance must identify the same canonical position`() {
        val request = request(
            route = forwardRoute(),
            window = window(
                entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 6.0),
                exit = routeBoundary("test:boundary/exit", railC, ViewDirection.AB, 5.0, 25.0),
                clearUntil = routeBoundary("test:boundary/clear", east, ViewDirection.AB, 5.0, 35.0)
            )
        )

        val rejected = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog().resolveWindow(request)
        )

        assertEquals(ProtectionRegionWindowRejection.INVALID_ROUTE, rejected.reason)
    }

    @Test
    fun `disconnected frozen route is rejected before region membership`() {
        val disconnected = RouteSnapshot(
            "test:route/disconnected",
            1L,
            listOf(
                segment(railA, ViewDirection.AB),
                segment(railC, ViewDirection.AB)
            )
        )
        val request = request(
            route = disconnected,
            window = window(
                entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 5.0),
                exit = routeBoundary("test:boundary/exit", railC, ViewDirection.AB, 5.0, 15.0),
                clearUntil = routeBoundary("test:boundary/clear", railC, ViewDirection.AB, 8.0, 18.0)
            )
        )

        val rejected = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog().resolveWindow(request)
        )

        assertEquals(ProtectionRegionWindowRejection.INVALID_ROUTE, rejected.reason)
    }

    @Test
    fun `missing and stale regions are rejected from one catalog snapshot`() {
        val validWindow = window(
            entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 5.0),
            exit = routeBoundary("test:boundary/exit", railC, ViewDirection.AB, 5.0, 25.0),
            clearUntil = routeBoundary("test:boundary/clear", east, ViewDirection.AB, 5.0, 35.0)
        )
        val missing = validWindow.copy(region = ProtectionRegionRef("test:region/missing", 1L))
        val stale = validWindow.copy(region = ProtectionRegionRef("test:region/main", 2L))

        val missingResult = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog().resolveWindow(request(forwardRoute(), missing))
        )
        val staleResult = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog().resolveWindow(request(forwardRoute(), stale))
        )

        assertEquals(ProtectionRegionWindowRejection.MISSING_REGION, missingResult.reason)
        assertEquals(ProtectionRegionWindowRejection.STALE_REGION, staleResult.reason)
    }

    @Test
    fun `topology change after catalog publication is rejected`() {
        val catalog = catalog()
        val request = request(
            route = forwardRoute(),
            window = window(
                entry = routeBoundary("test:boundary/entry", railA, ViewDirection.AB, 5.0, 5.0),
                exit = routeBoundary("test:boundary/exit", railC, ViewDirection.AB, 5.0, 25.0),
                clearUntil = routeBoundary("test:boundary/clear", east, ViewDirection.AB, 5.0, 35.0)
            )
        )
        topologyRevision = 8L

        val rejected = assertInstanceOf(
            ProtectionRegionWindowResolution.Rejected::class.java,
            catalog.resolveWindow(request)
        )

        assertEquals(ProtectionRegionWindowRejection.STALE_TOPOLOGY, rejected.reason)
    }

    private fun catalog(): ProtectionRegionCatalog {
        val catalog = ProtectionRegionCatalog(
            currentTopologyRevision = { topologyRevision },
            topologySource = ProtectionRegionTopologySource { _, expectedRevision ->
                check(expectedRevision == topologyRevision)
                ProtectionRegionTopologyCapture.Captured(topology)
            }
        )
        val provider = object : ProtectionRegionProvider {
            override val providerId: String = "test:provider/main"

            override fun definitions(topologyRevision: Long): Collection<ProtectionRegionDefinition> = listOf(
                ProtectionRegionDefinition(
                    ProtectionRegionRef("test:region/main", 1L),
                    topologyRevision,
                    listOf(
                        ProtectionBoundaryDefinition(
                            "test:boundary/entry",
                            RailPositionSnapshot(railA, ViewDirection.AB, 5.0)
                        ),
                        ProtectionBoundaryDefinition(
                            "test:boundary/exit",
                            RailPositionSnapshot(railC, ViewDirection.BA, 5.0)
                        )
                    )
                )
            )
        }
        assertEquals(ProtectionRegionCatalogStatus.REGISTERED, catalog.register(provider).status)
        return catalog
    }

    private fun request(route: RouteSnapshot, window: ProtectedRouteWindow) =
        ProtectedRouteRequest(
            requestId = "test:request/main",
            owner = InfrastructureOwner("test:route/main", 1L),
            trainId = "train",
            topologyRevision = topologyRevision,
            route = route,
            window = window
        )

    private fun window(
        entry: DirectedRouteBoundary,
        exit: DirectedRouteBoundary,
        clearUntil: DirectedRouteBoundary
    ) = ProtectedRouteWindow(
        region = ProtectionRegionRef("test:region/main", 1L),
        entry = entry,
        exit = exit,
        exitClearance = ExitClearanceEnvelope(clearUntil)
    )

    private fun forwardRoute() = RouteSnapshot(
        "test:route/forward",
        1L,
        listOf(
            segment(railA, ViewDirection.AB),
            segment(railB, ViewDirection.AB),
            segment(railC, ViewDirection.AB),
            segment(east, ViewDirection.AB)
        )
    )

    private fun reverseRoute() = RouteSnapshot(
        "test:route/reverse",
        1L,
        listOf(
            segment(railC, ViewDirection.BA),
            segment(railB, ViewDirection.BA),
            segment(railA, ViewDirection.BA),
            segment(west, ViewDirection.BA)
        )
    )

    private fun segment(railId: UUID, facing: ViewDirection) =
        RouteSegmentSnapshot(railId, facing, 0.0, 10.0)

    private fun routeBoundary(
        id: String,
        railId: UUID,
        facing: ViewDirection,
        canonicalArcMeters: Double,
        routeDistanceMeters: Double
    ) = DirectedRouteBoundary(
        boundaryId = id,
        position = RailPositionSnapshot(railId, facing, canonicalArcMeters),
        routeDistanceMeters = routeDistanceMeters
    )

    private fun lineTopology(revision: Long, vararg railIds: UUID): ProtectionRegionTopologySnapshot {
        val rails = railIds.mapIndexed { index, railId ->
            railId to ProtectionTopologyRail(
                railId = railId,
                lengthMeters = 10.0,
                connectionsFromA = if (index == 0) emptyList() else {
                    listOf(ProtectionTopologyConnection(railIds[index - 1], ViewDirection.BA))
                },
                connectionsFromB = if (index == railIds.lastIndex) emptyList() else {
                    listOf(ProtectionTopologyConnection(railIds[index + 1], ViewDirection.AB))
                }
            )
        }.toMap()
        return ProtectionRegionTopologySnapshot(revision, rails)
    }
}
