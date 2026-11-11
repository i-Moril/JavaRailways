package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import java.util.concurrent.TimeUnit

class CarFrameRevisionGateTest {
    @Test
    fun `old high revision cannot suppress new owner or return after it`() {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
                    val original = createTrainWithConfig("original", rail, world, listOf(10.0, 10.0))
                    val movedCar = original.cars.last()
                    val originalStream = original.publicationStreamId
                    val gate = CarFramePublicationGate()
                    assertTrue(gate.accept(movedCar, originalStream, 100L))
                    assertFalse(gate.accept(movedCar, originalStream, 100L))
                    assertFalse(gate.accept(movedCar, originalStream, 99L))

                    // Exercise real ownership transfer, not collision admission or link geometry.
                    val owner = Thread.currentThread()
                    val separated = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                        override fun isMainThread(): Boolean = Thread.currentThread() === owner
                    }).use {
                        val future = original.splitBefore(movedCar.id, "separated")
                        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
                        future.get(5, TimeUnit.SECONDS) as CoreTrain
                    }
                    val newStream = separated.publicationStreamId
                    assertNotEquals(originalStream, newStream)
                    assertTrue(gate.accept(movedCar, newStream, 1L))
                    assertFalse(gate.accept(movedCar, originalStream, 101L))
                    assertFalse(gate.accept(movedCar, newStream, 1L))
                }
            }
        }
    }
}
