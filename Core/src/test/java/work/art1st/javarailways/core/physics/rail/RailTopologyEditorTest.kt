package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailTopologyEditorTest {
    @Test
    fun `merge is rejected when another rail connects to the split anchor`() = withWorld { world ->
        val before = rail(world, 0.0, 4.0)
        val after = rail(world, 4.0, 10.0)
        IsolatedSimulationThreadMarker.run { CoreRail.connect(before, after) }
        val branch = world.createRailForTest(
            PrimeCurve.fromPoints(
                listOf(
                    rawPoint(Vector3d(4.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, Curve.SegmentType.LINE_START),
                    rawPoint(Vector3d(4.0, 0.0, 5.0), Vector3d(0.0, 0.0, 1.0), 5.0, Curve.SegmentType.LINE_END)
                )
            )
        )
        IsolatedSimulationThreadMarker.run { CoreRail.connect(before, branch) }

        val error = RailTopologyEditor.mergeEligibilityError(before, after)

        assertFalse(error.isNullOrBlank())
        assertTrue(error!!.contains("其他轨道"))
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun rail(world: work.art1st.javarailways.core.physics.CoreWorld, from: Double, to: Double): CoreRail =
        world.createRailForTest(straightCurve(from, to))

    private fun straightCurve(from: Double, to: Double): PrimeCurve {
        val length = to - from
        return PrimeCurve.fromPoints(
            listOf(
                rawPoint(Vector3d(from, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
                rawPoint(Vector3d(to, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), length, Curve.SegmentType.LINE_END)
            )
        )
    }

    private fun rawPoint(
        position: Vector3d,
        tangent: Vector3d,
        arc: Double,
        type: Curve.SegmentType
    ): Curve.RawPoint = Curve.RawPoint(position, tangent, Vector3d(0.0, 1.0, 0.0), arc, type)
}
