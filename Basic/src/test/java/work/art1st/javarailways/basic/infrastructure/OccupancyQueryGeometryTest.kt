package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import java.util.UUID
import work.art1st.javarailways.basic.infrastructure.admission.OccupancyQueryGeometry

class OccupancyQueryGeometryTest {
    @Test
    fun `geometry sorts rails and merges only same-rail touching intervals`() {
        val first = UUID(0L, 1L)
        val second = UUID(0L, 2L)

        val geometry = OccupancyQueryGeometry.create(
            listOf(
                RailInterval(second, 4.0, 6.0),
                RailInterval(first, 8.0, 10.0),
                RailInterval(first, 1.0, 3.0),
                RailInterval(first, 3.0 + Curve.TOLERANCE, 9.0)
            )
        )

        assertEquals(
            listOf(
                RailInterval(first, 1.0, 10.0),
                RailInterval(second, 4.0, 6.0)
            ),
            geometry.intervals
        )
    }
}
