package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailLocationPeekCurveTest {
    @Test
    fun `positive peek starts from current arc length`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(4.0))
        val location = RailLocationWithFacing(rail.viewAB, 1.0)

        val segment = location.peekCurve(2.0).single()

        assertEquals(1.0, segment.startArcLength, 1e-9)
        assertEquals(3.0, segment.endArcLength, 1e-9)
    }

    @Test
    fun `negative peek uses reversed current arc length`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(4.0))
        val location = RailLocationWithFacing(rail.viewAB, 1.0)

        val segment = location.peekCurve(-2.0).single()

        assertEquals(3.0, segment.startArcLength, 1e-9)
        assertEquals(4.0, segment.endArcLength, 1e-9)
        assertEquals(rail.viewBA, segment.railView)
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun straightCurve(length: Double): PrimeCurve {
        val tangent = Vector3d(1.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(length, 0.0, 0.0), tangent, normal, length, Curve.SegmentType.LINE_END),
        ))
    }
}
