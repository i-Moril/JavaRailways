package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ExpandedRouteTest {
    @Test
    fun `expanded route detaches the selected traversal from caller collections`() {
        val source = mutableListOf(
            RouteSegment(RailViewRef(UUID(0L, 1L), ViewDirection.AB), 5.0, 10.0)
        )

        val route = ExpandedRoute("from", "to", source)
        source.clear()

        assertEquals(1, route.segments.size)
        assertEquals(5.0, route.totalLength, 1.0e-9)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (route.segments as MutableList<RouteSegment>).clear()
        }
    }
}
