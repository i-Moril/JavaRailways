package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

class CoreTrainPublicationBoundaryTest {
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
