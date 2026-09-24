package work.art1st.javarailways.bukkit.tool

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld

class RailWandTopologyEditPlanTest {
    @Test
    fun `plan binds the exact topology root and existing rail instances`() {
        val world = createTestWorld()
        try {
            val affected = world.createRailForTest(straightCurve(0.0, 10.0))
            val plan = RailWandTopologyEditPlan.captureReplacementOrRemoval(world, listOf(affected.id))

            assertTrue(plan.isCurrent(world))

            world.createRailForTest(straightCurve(20.0, 30.0))

            assertFalse(plan.isCurrent(world))
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun straightCurve(fromX: Double, toX: Double): PrimeCurve {
        val tangent = Vector3d(1.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(fromX, 0.0, 0.0), Vector3d(tangent), Vector3d(normal), 0.0),
            Curve.RawPoint(
                Vector3d(toX, 0.0, 0.0),
                Vector3d(tangent),
                Vector3d(normal),
                toX - fromX
            )
        ))
    }
}
