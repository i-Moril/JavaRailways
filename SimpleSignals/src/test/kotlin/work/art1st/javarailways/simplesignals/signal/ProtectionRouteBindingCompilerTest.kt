package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.route.DirectedRouteOccurrence
import java.util.UUID

class ProtectionRouteBindingCompilerTest {
    @Test
    fun `tiny negative canonical endpoint is clamped to the rail start`() {
        val rail = UUID(0L, 101L)
        val occurrence = DirectedRouteOccurrence(
            railId = rail,
            facing = ViewDirection.BA,
            canonicalStartAbMeters = 5.60331368308286,
            canonicalEndAbMeters = 0.0,
            routeStartMeters = 271.11414005041536,
            routeEndMeters = 276.71745373349825
        )
        val traversal = protectionRouteTraversal(
            occurrence,
            first = 5.60331368308286,
            last = -2.842170943040401E-14,
            railLength = 5.60331368308286
        )
        requireNotNull(traversal)
        assertEquals(0.0, traversal.lowerAbMeters, 0.0)
        assertEquals(5.60331368308286, traversal.upperAbMeters, 0.0)
    }
}
