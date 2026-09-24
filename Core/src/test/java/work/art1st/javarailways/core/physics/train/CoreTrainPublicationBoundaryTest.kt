package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

class CoreTrainPublicationBoundaryTest {
    @Test
    fun `train frame preserves ordered head and tail traversal across rail boundaries`() {
        val world = createTestWorld()
        val first = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(60.0, segmentCount = 4)
        )
        val second = requireNotNull(first.viewAB.activeRail).rail
        val third = requireNotNull(second.viewAB.activeRail).rail
        val fourth = requireNotNull(third.viewAB.activeRail).rail
        val train = createTrainWithConfig("ordered-traversal", first, world, listOf(10.0))

        train.moveTrainForward(30.0)
        train.commit()

        val frame = train.publishedFrame
        assertEquals(listOf(second.id, third.id, fourth.id), frame.headTraversal.map { it.railId })
        assertEquals(listOf(first.id, second.id, third.id), frame.tailTraversal.map { it.railId })
        assertEquals(listOf(ViewDirection.AB, ViewDirection.AB, ViewDirection.AB), frame.headTraversal.map { it.facing })
        assertEquals(5.0, frame.headTraversal.first().startArcLengthInAbMeters, 1e-6)
        assertEquals(15.0, frame.headTraversal.first().endArcLengthInAbMeters, 1e-6)
        assertEquals(0.0, frame.headTraversal[1].startArcLengthInAbMeters, 1e-6)
        assertEquals(15.0, frame.headTraversal[1].endArcLengthInAbMeters, 1e-6)
        assertEquals(0.0, frame.headTraversal.last().startArcLengthInAbMeters, 1e-6)
        assertEquals(5.0, frame.headTraversal.last().endArcLengthInAbMeters, 1e-6)

        train.moveTrainForward(0.0)
        train.commit()

        assertTrue(train.publishedFrame.headTraversal.isEmpty())
        assertTrue(train.publishedFrame.tailTraversal.isEmpty())
    }

    @Test
    fun `BA traversal keeps travel order while canonical AB arcs decrease`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("ba-traversal", rail, world, listOf(10.0))
        train.setLocation(rail.viewBA.getRailLocationAt(60.0))
        train.commit()

        train.moveTrainForward(10.0)
        train.commit()

        val headTraversal = train.publishedFrame.headTraversal.single()
        val tailTraversal = train.publishedFrame.tailTraversal.single()
        assertEquals(ViewDirection.BA, headTraversal.facing)
        assertEquals(ViewDirection.BA, tailTraversal.facing)
        assertEquals(40.0, headTraversal.startArcLengthInAbMeters, 1e-6)
        assertEquals(30.0, headTraversal.endArcLengthInAbMeters, 1e-6)
        assertTrue(tailTraversal.startArcLengthInAbMeters > tailTraversal.endArcLengthInAbMeters)
    }

    @Test
    fun `published rail positions preserve BA facing with canonical AB arc lengths`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("ba-frame", rail, world, listOf(10.0))

        train.setLocation(rail.viewBA.getRailLocationAt(60.0))
        train.commit()

        val frame = train.publishedFrame
        assertEquals(ViewDirection.BA, frame.head.facing)
        assertEquals(ViewDirection.BA, frame.tail.facing)
        assertEquals(train.head.location.arcLengthIn(ViewDirection.AB), frame.head.arcLengthInAbMeters)
        assertEquals(train.tail.location.arcLengthIn(ViewDirection.AB), frame.tail.arcLengthInAbMeters)
    }

    @Test
    fun `physics reverse remains private until train state is published`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("reverse-publication", rail, world, listOf(10.0))
        val publishedHead = train.headLocation
        val publishedTail = train.tailLocation

        train.reverseFromPhysics()

        assertFalse(train.isReversed)
        assertFalse(train.cars.single().isReversed)
        assertEquals(publishedHead, train.headLocation)
        assertEquals(publishedTail, train.tailLocation)

        train.publishPhysicsState()

        assertTrue(train.isReversed)
        assertTrue(train.cars.single().isReversed)
        assertEquals(publishedTail.position, train.headLocation.position)
        assertEquals(publishedHead.position, train.tailLocation.position)
    }

    @Test
    fun `physics derail remains private until train state is published`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("derail-publication", rail, world, listOf(10.0))

        train.markDerailedFromPhysics("test derail")

        assertFalse(train.isDerailed)
        assertEquals(null, train.derailReason)

        train.publishPhysicsState()

        assertTrue(train.isDerailed)
        assertEquals("test derail", train.derailReason)
    }
}
