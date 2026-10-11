package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

class CoreTrainLinkTest {
    @Test
    fun `linkWith removes linked train endpoints from rails`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
            val lead = createTrainWithConfig("lead", rail, world, listOf(10.0), isLoop = false)
            val follower = createTrainWithConfig("follower", rail, world, listOf(10.0), isLoop = false)
            lead.setLocation(RailLocationWithFacing(rail.viewAB, 50.0))
            follower.setLocation(RailLocationWithFacing(rail.viewAB, 35.0))
            val movedCar = follower.cars.single()
            val oldStream = follower.publicationStreamId

            assertTrue(movedCar.isOwnedByPublicationStream(oldStream))
            assertSame(follower, movedCar.train)
            assertEquals(2, world.allTrains.size)
            assertEquals(4, rail.trains.size)

            lead.linkWith(follower)
            world.tickManager.postTick()

            assertFalse(movedCar.isOwnedByPublicationStream(oldStream))
            assertTrue(movedCar.isOwnedByPublicationStream(lead.publicationStreamId))
            assertSame(lead, movedCar.train)
            world.removeTrain(follower)
            assertSame(lead, movedCar.train, "retiring the old owner must not clear transferred cars")
            assertEquals(listOf("lead"), world.allTrains.map { it.id })
            assertEquals(2, rail.trains.size)
            rail.trains.forEach { end ->
                assertSame(lead, end.trainOrNull)
            }
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `removing a train retires its car publication ownership`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val train = createTrainWithConfig("removed", rail, world, listOf(10.0), isLoop = false)
            val car = train.cars.single()

            assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId))
            assertSame(train, car.train)
            world.removeTrain(train)
            assertFalse(car.isOwnedByPublicationStream(train.publicationStreamId))
            assertNull(car.train)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `repeated removal of an old instance does not remove its same-id replacement`() {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
            val old = createTrainWithConfig("reused", rail, world, listOf(10.0), isLoop = false)
            world.removeTrain(old)
            val replacement = createTrainWithConfig("reused", rail, world, listOf(10.0), isLoop = false)
            world.publishCurrentTrainFrame(replacement.id)

            world.removeTrain(old)

            assertSame(replacement, world.getTrain("reused"))
            assertSame(replacement.publishedFrame, world.drainPublishedTrainFrames().single())
            assertTrue(replacement.cars.single().isOwnedByPublicationStream(replacement.publicationStreamId))
            assertSame(replacement, replacement.cars.single().train)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
