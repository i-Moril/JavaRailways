package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RailWandGeometryDisplayTest {
    @Test
    fun `sloped straight rail receives a maximum grade display`() {
        assertEquals(
            "最大坡度 25 ‰\nCURVE 80 / SUBGRADE 120 km/h",
            buildRailGeometryDisplayText(
                radiusMeters = null,
                maximumAbsoluteGrade = 0.025,
                speedLine = "CURVE 80 / SUBGRADE 120 km/h"
            )
        )
    }

    @Test
    fun `radius and grade share one geometry display`() {
        assertEquals(
            "R 200 m\n最大坡度 12.5 ‰",
            buildRailGeometryDisplayText(
                radiusMeters = 200.2,
                maximumAbsoluteGrade = 0.0125,
                speedLine = null
            )
        )
    }

    @Test
    fun `flat straight rail does not create a geometry display`() {
        assertNull(buildRailGeometryDisplayText(null, 0.0, "SUBGRADE 120 km/h"))
    }
}
