package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class RouteResourceIntentTest {
    @Test
    fun `intent contains no signalling resource model`() {
        val fields = RouteResourceIntent::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(false, "additionalResources" in fields)
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
    fun `desired authority uses the containing route range`() {
        assertThrows(IllegalArgumentException::class.java) {
            intent(desiredAuthorityMeters = 100.1)
        }
    }

    @Test
    fun `intent accepts older route provenance for current structural validation`() {
        val route = route()

        val intent = RouteResourceIntent(
                requestId = "test:request/main",
                owner = InfrastructureOwner("test:route/main", 2L),
                trainId = "train-1",
                observationStamp = RailwayStateStamp("world", 7L, route.topologyRevision + 1L),
                route = route,
                desiredAuthority = RouteBoundary(80.0)
            )
        assertEquals(route, intent.route)
    }

    @Test
    fun `intent rejects blank identity and empty route`() {
        assertThrows(IllegalArgumentException::class.java) { intent(requestId = "request") }
        assertThrows(IllegalArgumentException::class.java) { intent(trainId = " ") }
        assertThrows(IllegalArgumentException::class.java) {
            intent(route = RouteSnapshot("route", 1L, 3L, emptyList()))
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
        desiredAuthorityMeters: Double = 80.0
    ) = RouteResourceIntent(
        requestId = requestId,
        owner = InfrastructureOwner("test:route/main", 2L),
        trainId = trainId,
        observationStamp = RailwayStateStamp("world", 7L, route.topologyRevision),
        route = route,
        desiredAuthority = RouteBoundary(desiredAuthorityMeters)
    )

    private fun route() = RouteSnapshot(
        routeId = "route",
        revision = 1L,
        topologyRevision = 3L,
        segments = listOf(
            RouteSegmentSnapshot(UUID.randomUUID(), ViewDirection.BA, 0.0, 100.0)
        )
    )
}
