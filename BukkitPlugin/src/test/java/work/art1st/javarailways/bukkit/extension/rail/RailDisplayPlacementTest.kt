package work.art1st.javarailways.bukkit.extension.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.config.OffsetConfig

class RailDisplayPlacementTest {
    @Test
    fun `moves light probe in curve-local axes and compensates visual translation`() {
        val placement = computeRailDisplayPlacement(
            curvePosition = Vector3d(10.0, 20.0, 30.0),
            tangent = Vector3d(0.0, 0.0, 1.0),
            normal = Vector3d(0.0, 1.0, 0.0),
            visualOffset = OffsetConfig(x = 1.0, y = 2.0, z = 3.0),
            lightProbeOffset = OffsetConfig(x = 4.0, y = 5.0, z = 6.0)
        )

        // tangent=+Z, normal=+Y, binormal=tangent×normal=-X.
        assertVectorEquals(Vector3d(7.0, 22.0, 31.0), placement.visualPosition)
        assertVectorEquals(Vector3d(1.0, 27.0, 35.0), placement.lightProbePosition)
        assertVectorEquals(Vector3d(6.0, -5.0, -4.0), placement.visualTranslation)
    }

    @Test
    fun `zero light probe offset preserves current entity placement`() {
        val placement = computeRailDisplayPlacement(
            curvePosition = Vector3d(1.0, 2.0, 3.0),
            tangent = Vector3d(1.0, 0.0, 0.0),
            normal = Vector3d(0.0, 1.0, 0.0),
            visualOffset = OffsetConfig(x = 0.5, y = -0.25, z = 1.0),
            lightProbeOffset = OffsetConfig()
        )

        assertVectorEquals(placement.visualPosition, placement.lightProbePosition)
        assertVectorEquals(Vector3d(), placement.visualTranslation)
    }

    private fun assertVectorEquals(expected: org.joml.Vector3dc, actual: org.joml.Vector3dc) {
        assertEquals(expected.x(), actual.x(), 1.0e-9)
        assertEquals(expected.y(), actual.y(), 1.0e-9)
        assertEquals(expected.z(), actual.z(), 1.0e-9)
    }
}
