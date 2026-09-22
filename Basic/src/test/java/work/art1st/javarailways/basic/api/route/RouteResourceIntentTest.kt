package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class RouteResourceIntentTest {
    @Test
    fun `intent defensively copies unique additions`() {
        val additions = mutableListOf(requirement("test:signal", "entry", 60.0))
        val intent = intent(additionalResources = additions)

        additions.clear()

        assertEquals(1, intent.additionalResources.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (intent.additionalResources as MutableList<AdditionalResourceRequirement>).clear()
        }
    }

    @Test
    fun `preparation arguments are copied sorted and immutable`() {
        val arguments = linkedMapOf(
            "target" to PreparationScalar.Text("reverse"),
            "delay" to PreparationScalar.Integer(20L)
        )
        val descriptor = ResourcePreparationDescriptor("test:turnout", arguments)

        arguments.clear()

        assertEquals(listOf("delay", "target"), descriptor.arguments.keys.toList())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (descriptor.arguments as MutableMap<String, PreparationScalar>).clear()
        }
    }

    @Test
    fun `route and release boundaries use the containing route range`() {
        assertThrows(IllegalArgumentException::class.java) {
            intent(desiredAuthorityMeters = 100.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            intent(
                desiredAuthorityMeters = 80.0,
                additionalResources = listOf(requirement("test:signal", "entry", 80.1))
            )
        }
    }

    @Test
    fun `intent rejects a route from another topology revision`() {
        val route = route()

        assertThrows(IllegalArgumentException::class.java) {
            RouteResourceIntent(
                requestId = "test:request/main",
                owner = InfrastructureOwner("test:route/main", 2L),
                trainId = "train-1",
                observationStamp = RailwayStateStamp("world", 7L, route.topologyRevision + 1L),
                route = route,
                desiredAuthority = RouteBoundary(80.0),
                additionalResources = emptyList()
            )
        }
    }

    @Test
    fun `intent rejects blank identity empty route and duplicate additions`() {
        assertThrows(IllegalArgumentException::class.java) { intent(requestId = "request") }
        assertThrows(IllegalArgumentException::class.java) { intent(trainId = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            intent(route = RouteSnapshot("route", 1L, 3L, emptyList()))
        }

        val repeated = requirement("test:signal", "entry", 60.0)
        assertThrows(IllegalArgumentException::class.java) {
            intent(additionalResources = listOf(repeated, repeated.copy()))
        }
    }

    @Test
    fun `boundaries preparation identifiers and scalar values validate at construction`() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RouteBoundary(value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePreparationDescriptor("turnout", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePreparationDescriptor("test:turnout", mapOf(" " to PreparationScalar.Flag(true)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparationScalar.Decimal(Double.NEGATIVE_INFINITY)
        }
    }

    private fun intent(
        requestId: String = "test:request/main",
        trainId: String = "train-1",
        route: RouteSnapshot = route(),
        desiredAuthorityMeters: Double = 80.0,
        additionalResources: Collection<AdditionalResourceRequirement> = emptyList()
    ) = RouteResourceIntent(
        requestId = requestId,
        owner = InfrastructureOwner("test:route/main", 2L),
        trainId = trainId,
        observationStamp = RailwayStateStamp("world", 7L, route.topologyRevision),
        route = route,
        desiredAuthority = RouteBoundary(desiredAuthorityMeters),
        additionalResources = additionalResources
    )

    private fun route() = RouteSnapshot(
        routeId = "route",
        revision = 1L,
        topologyRevision = 3L,
        segments = listOf(
            RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.BA, 0.0, 100.0)
        )
    )

    private fun requirement(
        typeId: String,
        resourceId: String,
        releaseMeters: Double
    ) = AdditionalResourceRequirement(
        resource = StableInfrastructureResource(typeId, resourceId),
        releaseAfterTailPasses = RouteBoundary(releaseMeters)
    )
}
