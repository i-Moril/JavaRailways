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

        // Forward=+Z, up=+Y, lateral=normal×tangent=+X.
        assertVectorEquals(Vector3d(11.0, 22.0, 33.0), placement.visualPosition)
        assertVectorEquals(Vector3d(15.0, 27.0, 39.0), placement.lightProbePosition)
        assertVectorEquals(Vector3d(-4.0, -5.0, -6.0), placement.visualTranslation)
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

    @Test
    fun `banked slope rotation and both offsets use the same model frame`() {
        val forward = Vector3d(0.48, 0.6, 0.64)
        val up = Vector3d(-0.36, 0.8, -0.48).rotateAxis(Math.PI / 3.0, forward.x, forward.y, forward.z)
        val lateral = Vector3d(up).cross(forward)
        val rotation = computeRailCurveRotation(forward, up)
        assertVectorEquals(lateral, rotation.transform(Vector3d(1.0, 0.0, 0.0)))
        assertVectorEquals(up, rotation.transform(Vector3d(0.0, 1.0, 0.0)))
        assertVectorEquals(forward, rotation.transform(Vector3d(0.0, 0.0, 1.0)))
        val origin = Vector3d(10.0, 20.0, 30.0)
        val placement = computeRailDisplayPlacement(origin, forward, up,
            OffsetConfig(1.0, 2.0, 3.0), OffsetConfig(4.0, 5.0, 6.0))
        val visual = Vector3d(origin).fma(1.0, lateral).fma(2.0, up).fma(3.0, forward)
        val probe = Vector3d(visual).fma(4.0, lateral).fma(5.0, up).fma(6.0, forward)
        assertVectorEquals(visual, placement.visualPosition)
        assertVectorEquals(probe, placement.lightProbePosition)
        assertVectorEquals(visual, Vector3d(placement.lightProbePosition).add(placement.visualTranslation))

        // Element rotation follows the curve frame, while placement stays in the unrotated frame.
        val turned = org.joml.Quaterniond(rotation).rotateY(Math.PI / 2.0)
        assertVectorEquals(lateral, turned.transform(Vector3d(0.0, 0.0, 1.0)))
        assertVectorEquals(Vector3d(forward).negate(), turned.transform(Vector3d(1.0, 0.0, 0.0)))
    }

    private fun assertVectorEquals(expected: org.joml.Vector3dc, actual: org.joml.Vector3dc) {
        assertEquals(expected.x(), actual.x(), 1.0e-9)
        assertEquals(expected.y(), actual.y(), 1.0e-9)
        assertEquals(expected.z(), actual.z(), 1.0e-9)
    }
}
