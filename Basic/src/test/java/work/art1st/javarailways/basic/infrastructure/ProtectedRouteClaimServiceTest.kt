package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.route.DirectedRouteBoundary
import work.art1st.javarailways.basic.api.route.ExitClearanceEnvelope
import work.art1st.javarailways.basic.api.route.ProtectedRouteClaimStatus
import work.art1st.javarailways.basic.api.route.ProtectedRouteRequest
import work.art1st.javarailways.basic.api.route.ProtectedRouteWindow
import work.art1st.javarailways.basic.api.route.ProtectionRegionRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ProtectedRouteClaimServiceTest {
    private val railId = UUID.randomUUID()
    private var topologyRevision = 4L
    private val validResources = mutableSetOf(
        resource("turnout/a"),
        resource("turnout/b")
    )
    private val registry = InfrastructureLeaseRegistry(
        currentTopologyRevision = { topologyRevision },
        resourceExists = { it in validResources }
    )

    @Test
    fun `route without controlled resources succeeds without creating a claim`() {
        val service = service { ProtectedRouteValidation.NotRequired }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.NOT_REQUIRED, result.status)
        assertTrue(result.successful)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `missing protection is typed and leaves no partial claim`() {
        val service = service {
            ProtectedRouteValidation.Rejected(
                ProtectedRouteClaimStatus.PROTECTION_REQUIRED,
                "turnout/a is outside the protection window"
            )
        }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.PROTECTION_REQUIRED, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `validation failure is typed and leaves no partial claim`() {
        val service = service {
            error("broken validator")
        }

        val result = service.acquire(Any(), request(null))

        assertEquals(ProtectedRouteClaimStatus.VALIDATION_FAILED, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `one throat window acquires one atomic footprint and releases by exact owner`() {
        val platformOwner = Any()
        val routeOwner = InfrastructureOwner("test:route/main", 1L)
        val service = service {
            ProtectedRouteValidation.Validated(
                InfrastructureFootprint(listOf(resource("turnout/a"), resource("turnout/b")))
            )
        }
        val request = request(
            window("test:region/a", 0.0, 80.0, 100.0),
            routeOwner
        )

        val acquired = service.acquire(platformOwner, request)

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, acquired.status)
        assertEquals(
            setOf(resource("turnout/a"), resource("turnout/b")),
            registry.claimSnapshots().single().footprint.resources.toSet()
        )
        assertEquals(ProtectedRouteClaimStatus.RELEASED, service.release(platformOwner, routeOwner).status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `invalid member rejects the whole resolved footprint`() {
        val service = service {
            ProtectedRouteValidation.Validated(
                InfrastructureFootprint(listOf(resource("turnout/a"), resource("turnout/missing")))
            )
        }

        val result = service.acquire(
            Any(),
            request(window("test:region/a", 0.0, 80.0, 100.0))
        )

        assertEquals(ProtectedRouteClaimStatus.INVALID_RESOURCE, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `topology change during resolution rejects before claim mutation`() {
        val service = service {
            topologyRevision = 5L
            ProtectedRouteValidation.Validated(
                InfrastructureFootprint(listOf(resource("turnout/a")))
            )
        }

        val result = service.acquire(
            Any(),
            request(window("test:region/a", 0.0, 80.0, 100.0))
        )

        assertEquals(ProtectedRouteClaimStatus.STALE_TOPOLOGY, result.status)
        assertTrue(registry.claimSnapshots().isEmpty())
    }

    @Test
    fun `conflicting complete requests expose blocker and preserve first claim`() {
        val service = service {
            ProtectedRouteValidation.Validated(
                InfrastructureFootprint(listOf(resource("turnout/a")))
            )
        }
        val window = window("test:region/a", 0.0, 80.0, 100.0)
        val first = service.acquire(Any(), request(window, InfrastructureOwner("test:route/first", 1L)))

        val second = service.acquire(Any(), request(window, InfrastructureOwner("test:route/second", 1L)))

        assertEquals(ProtectedRouteClaimStatus.ACQUIRED, first.status)
        assertEquals(ProtectedRouteClaimStatus.RESOURCE_CONFLICT, second.status)
        assertEquals("test:route/first", second.blockers.single().owner.ownerId)
        assertEquals(listOf("test:route/first"), registry.claimSnapshots().map { it.owner.ownerId })
    }

    private fun service(validate: (ProtectedRouteRequest) -> ProtectedRouteValidation) =
        ProtectedRouteClaimService(
            registry,
            currentTopologyRevision = { topologyRevision },
            validate = validate
        )

    private fun request(
        window: ProtectedRouteWindow?,
        owner: InfrastructureOwner = InfrastructureOwner("test:route/main", 1L)
    ) = ProtectedRouteRequest(
        requestId = "test:request/main",
        owner = owner,
        trainId = "train",
        route = RouteSnapshot(
            "route",
            2L,
            4L,
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

    private fun resource(id: String) = StableInfrastructureResource("test:controlled", id)
}
