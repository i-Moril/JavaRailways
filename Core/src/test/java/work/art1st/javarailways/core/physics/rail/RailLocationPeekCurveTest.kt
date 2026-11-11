package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

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

    @Test
    fun `peek ending inside current rail does not follow active successor on floating residual`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(0.0, 20.0))
        val successor = world.createRailForTest(straightCurve(20.0, 5.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(rail.viewAB, successor.viewBA) }
        assertSame(successor.viewAB, rail.viewAB.activeRail)

        val startArcLength = 10.0
        val distance = 0.1
        val segments = RailLocationWithFacing(rail.viewAB, startArcLength).peekCurve(distance)

        val segment = segments.single()
        assertSame(rail.viewAB, segment.railView)
        assertEquals(startArcLength, segment.startArcLength, 0.0)
        assertEquals(startArcLength + distance, segment.endArcLength, 0.0)
    }

    @Test
    fun `negative peek ending inside current rail does not follow active predecessor on floating residual`() = withWorld { world ->
        val predecessor = world.createRailForTest(straightCurve(-5.0, 5.0))
        val rail = world.createRailForTest(straightCurve(0.0, 20.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(rail.viewBA, predecessor.viewAB) }
        assertSame(predecessor.viewBA, rail.viewBA.activeRail)

        val startArcLength = 10.0
        val distance = -0.1
        val segments = RailLocationWithFacing(rail.viewAB, startArcLength).peekCurve(distance)

        val segment = segments.single()
        assertSame(rail.viewBA, segment.railView)
        assertEquals(10.0, segment.startArcLength, 0.0)
        assertEquals(10.1, segment.endArcLength, 0.0)
    }

    @Test
    fun `zero peek at rail end stays on current rail`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(0.0, 20.0))
        val successor = world.createRailForTest(straightCurve(20.0, 5.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(rail.viewAB, successor.viewBA) }

        val segment = RailLocationWithFacing(rail.viewAB, 20.0).peekCurve(0.0).single()

        assertEquals(RailSegment(rail.viewAB, 20.0, 20.0), segment)
    }

    @Test
    fun `peek ending exactly at rail end does not enter successor`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(0.0, 20.0))
        val successor = world.createRailForTest(straightCurve(20.0, 5.0))
        IsolatedSimulationThreadMarker.run { RailView.connect(rail.viewAB, successor.viewBA) }

        val segment = RailLocationWithFacing(rail.viewAB, 19.5).peekCurve(0.5).single()

        assertEquals(RailSegment(rail.viewAB, 19.5, 20.0), segment)
    }

    @Test
    fun `peek genuinely crossing multiple rails follows each active successor`() = withWorld { world ->
        val rail = world.createRailForTest(straightCurve(0.0, 20.0))
        val successor = world.createRailForTest(straightCurve(20.0, 5.0))
        val secondSuccessor = world.createRailForTest(straightCurve(25.0, 7.0))
        IsolatedSimulationThreadMarker.run {
            RailView.connect(rail.viewAB, successor.viewBA)
            RailView.connect(successor.viewAB, secondSuccessor.viewBA)
        }

        val segments = RailLocationWithFacing(rail.viewAB, 19.5).peekCurve(6.0)

        assertEquals(3, segments.size)
        assertEquals(RailSegment(rail.viewAB, 19.5, 20.0), segments[0])
        assertEquals(RailSegment(successor.viewAB, 0.0, 5.0), segments[1])
        assertEquals(RailSegment(secondSuccessor.viewAB, 0.0, 0.5), segments[2])
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun straightCurve(length: Double): PrimeCurve {
        return straightCurve(0.0, length)
    }

    private fun straightCurve(startX: Double, length: Double): PrimeCurve {
        val tangent = Vector3d(1.0, 0.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(startX, 0.0, 0.0), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(startX + length, 0.0, 0.0), tangent, normal, length, Curve.SegmentType.LINE_END),
        ))
    }
}
