package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class CarFrameRevisionGateTest {
    @Test
    fun `old high revision cannot suppress new owner or return after it`() {
        val fixture = Fixture()
        try {
            val gate = CarFramePublicationGate()
            val movedCar = fixture.removedOwner.cars.single()
            val removedStream = fixture.removedOwner.publicationStreamId
            val survivorStream = fixture.survivor.publicationStreamId
            assertNotEquals(removedStream, survivorStream)

            assertTrue(gate.accept(movedCar, removedStream, 100L))
            assertFalse(gate.accept(movedCar, removedStream, 100L))
            assertFalse(gate.accept(movedCar, removedStream, 99L))

            fixture.link()

            assertTrue(gate.accept(movedCar, survivorStream, 1L))
            assertFalse(gate.accept(movedCar, removedStream, 101L))
            assertFalse(gate.accept(movedCar, survivorStream, 1L))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val world = createTestWorld()
        private val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
        val survivor: CoreTrain = createTrainWithConfig("survivor", rail, world, listOf(10.0))
        val removedOwner: CoreTrain = createTrainWithConfig("removed", rail, world, listOf(10.0))

        fun link() = world.onOwnerForTest {
            survivor.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 50.0))
            removedOwner.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, 35.0))
            survivor.linkWith(removedOwner)
            world.tickManager.postTick()
        }

        fun close() {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
