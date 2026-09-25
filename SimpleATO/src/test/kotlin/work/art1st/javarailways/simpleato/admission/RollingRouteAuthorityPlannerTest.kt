package work.art1st.javarailways.simpleato.admission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.RouteBoundary

class RollingRouteAuthorityPlannerTest {
    @Test
    fun `first request covers one perception horizon from the train head`() {
        val boundary = next(
            headProgressMeters = 20.0,
            admissionState = RouteAuthorityAdmissionState.EMPTY
        )

        assertEquals(RouteBoundary(100.0), boundary)
    }

    @Test
    fun `healthy remaining authority does not request another extension`() {
        val boundary = next(
            headProgressMeters = 40.0,
            admissionState = committed(75.0)
        )

        assertNull(boundary)
    }

    @Test
    fun `low remaining authority extends by one horizon without requesting the whole route`() {
        val boundary = next(
            headProgressMeters = 55.0,
            admissionState = committed(75.0)
        )

        assertEquals(RouteBoundary(135.0), boundary)
    }

    @Test
    fun `route end caps the requested boundary`() {
        val boundary = next(
            routeLengthMeters = 120.0,
            headProgressMeters = 55.0,
            admissionState = committed(75.0)
        )

        assertEquals(RouteBoundary(120.0), boundary)
    }

    @Test
    fun `pending device preparation never starts another request`() {
        val boundary = next(
            headProgressMeters = 55.0,
            admissionState = RouteAuthorityAdmissionState(RouteBoundary(75.0), pending = true)
        )

        assertNull(boundary)
    }

    @Test
    fun `fully committed route does not submit an empty extension`() {
        val boundary = next(
            routeLengthMeters = 120.0,
            headProgressMeters = 110.0,
            admissionState = committed(120.0)
        )

        assertNull(boundary)
    }

    private fun next(
        routeLengthMeters: Double = 300.0,
        headProgressMeters: Double,
        admissionState: RouteAuthorityAdmissionState
    ): RouteBoundary? = RollingRouteAuthorityPlanner.nextBoundary(
        routeLengthMeters = routeLengthMeters,
        headProgressMeters = headProgressMeters,
        perceptionHorizonMeters = 80.0,
        extensionTriggerMeters = 20.0,
        admissionState = admissionState
    )

    private fun committed(boundaryMeters: Double) = RouteAuthorityAdmissionState(
        RouteBoundary(boundaryMeters),
        pending = false
    )
}
