package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreBogie
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport

class RailDirectionRestrictionTest {
    @Test
    fun `physical topology and bogie geometry remain direction neutral`() = withWorld { world ->
        val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(40.0, segmentCount = 1))
        val secondCurve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(40.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(80.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 40.0)
        ))
        val second = world.createRailForTest(secondCurve)
        RailView.connect(first.viewAB, second.viewBA)
        assertEquals(second.viewAB, first.viewAB.activeRail)

        assertEquals(second.viewAB, first.viewAB.activeRail)
        val operationallyForbiddenBogie = CoreBogie.create(second.viewAB.getRailLocationAt(1.0))
        val operationallyAllowedBogie = CoreBogie.create(second.viewBA.getRailLocationAt(1.0))
        assertDoesNotThrow { operationallyForbiddenBogie.moveForward(1.0) }
        assertDoesNotThrow { operationallyAllowedBogie.moveForward(1.0) }

        val behind = second.viewAB.getRailLocationAt(1.0).forward(-2.0)
        assertEquals(first.id, behind?.rail?.id)
        assertEquals(ViewDirection.AB, behind?.facing)
        assertEquals(39.0, requireNotNull(behind).arcLength, 1.0e-9)

        val train = createTrainWithConfig("one-way-boundary", first, world, listOf(17.2))
        train.setLocation(second.viewAB.getRailLocationAt(5.0))
        assertEquals(first.id, train.tailLocation.rail.id)
        AsyncTickTestSupport.run { train.moveTrainForward(0.54) }
        train.commit()
        assertFalse(train.isDerailed, train.derailReason)
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }
}
