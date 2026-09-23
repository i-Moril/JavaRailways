package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp

class RouteAdmissionTest {
    @Test
    fun `handles and grant IDs are opaque process-local identities`() {
        assertNotEquals(RouteAdmissionHandle(), RouteAdmissionHandle())
        assertNotEquals(RouteGrantId(), RouteGrantId())
    }

    @Test
    fun `grant snapshot validates route and observation identity`() {
        assertThrows(IllegalArgumentException::class.java) { grant(trainId = " ") }
        assertThrows(IllegalArgumentException::class.java) { grant(routeId = " ") }
        assertThrows(IllegalArgumentException::class.java) { grant(routeRevision = -1L) }
        assertEquals(3L, grant().observationStamp.topologyRevision)
    }

    @Test
    fun `occupancy failure accepts only immutable occupying train identities`() {
        val source = mutableListOf<RouteAdmissionBlocker>(
            RouteAdmissionBlocker.OccupyingTrain("train-2")
        )
        val failure = RouteAdmissionFailure(
            RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
            "route is physically occupied",
            source
        )

        source.clear()

        assertEquals(1, failure.blockers.size)
        assertEquals(
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                "route is physically occupied",
                listOf(RouteAdmissionBlocker.OccupyingTrain("train-2"))
            ),
            failure
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (failure.blockers as MutableList<RouteAdmissionBlocker>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                "wrong blocker",
                listOf(conflictingResource())
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                "missing blocker"
            )
        }
    }

    @Test
    fun `resource conflict accepts only resource and owner identities`() {
        val blocker = conflictingResource()
        val failure = RouteAdmissionFailure(
            RouteAdmissionFailureCode.RESOURCE_CONFLICT,
            "resource belongs to another grant",
            listOf(blocker)
        )

        assertEquals(blocker, failure.blockers.single())
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.RESOURCE_CONFLICT,
                "wrong blocker",
                listOf(RouteAdmissionBlocker.OccupyingTrain("train-2"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.RESOURCE_CONFLICT,
                "missing blocker"
            )
        }
    }

    @Test
    fun `other failure codes reject blockers and every failure requires detail`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_ROUTE, " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouteAdmissionFailure(
                RouteAdmissionFailureCode.INVALID_ROUTE,
                "route is disconnected",
                listOf(RouteAdmissionBlocker.OccupyingTrain("train-2"))
            )
        }
    }

    @Test
    fun `pending result distinguishes initial request from extension`() {
        val initial = RouteAdmissionResult.Pending(RouteAdmissionHandle(), null)
        val extension = RouteAdmissionResult.Pending(RouteAdmissionHandle(), grant())

        assertEquals(null, initial.committedGrant)
        assertEquals(40.0, extension.committedGrant?.committedAuthority?.distanceFromRouteStartMeters)
        assertEquals(extension, RouteAdmissionResult.Pending(extension.handle, extension.committedGrant))
    }

    private fun grant(
        trainId: String = "train-1",
        routeId: String = "route-1",
        routeRevision: Long = 2L,
        stamp: RailwayStateStamp = RailwayStateStamp("world", 8L, 3L)
    ) = RouteGrantSnapshot(
        grantId = RouteGrantId(),
        owner = InfrastructureOwner("test:route/main", 2L),
        trainId = trainId,
        routeId = routeId,
        routeRevision = routeRevision,
        observationStamp = stamp,
        committedAuthority = RouteBoundary(40.0)
    )

    private fun conflictingResource() = RouteAdmissionBlocker.ConflictingResource(
        resource = StableInfrastructureResource("test:signal", "entry"),
        owner = InfrastructureOwner("test:route/other", 4L)
    )
}
