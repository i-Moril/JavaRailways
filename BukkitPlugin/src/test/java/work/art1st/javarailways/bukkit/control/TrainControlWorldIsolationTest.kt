package work.art1st.javarailways.bukkit.control

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrainControlWorldIsolationTest {
    @Test
    fun `concurrent different world registration for one player installs exactly one session`() {
        val worlds = List(2) { createTestWorld(PhysicsCoordinator(1, automaticFallback = false)) }
        val executor = Executors.newFixedThreadPool(2)
        val manager = TrainControlManager { error("registration must not access Bukkit") }
        val playerId = UUID.randomUUID()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val trains = worlds.map { world ->
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
                createTrainWithConfig("same-train", rail, world, listOf(10.0)).also { train ->
                    train.setControllerForTest(RealisticManualController(
                        train, TrainControlConfig(), listOf(CarControlConfig()), 9.80665))
                }
            }
            val requests = trains.map { train -> executor.submit<Boolean> {
                train.world.onOwnerForTest {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    try {
                        manager.registerManualControl(playerId, train, train.controller as RealisticManualController, null)
                        true
                    } catch (failure: IllegalStateException) {
                        check(failure.message.orEmpty().contains("Cross-world"))
                        false
                    }
                }
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(1, requests.count { it.get(5, TimeUnit.SECONDS) })
            val winner = trains.single { manager.sessionFor(it) != null }
            val loser = trains.single { it !== winner }
            assertEquals(playerId, manager.ownerOf(winner))
            assertNull(manager.ownerOf(loser))
            manager.retireWorld(loser.world)
            assertEquals(playerId, manager.ownerOf(winner))
            manager.retireWorld(winner.world)
            assertNull(manager.ownerOf(winner))
        } finally {
            release.countDown()
            executor.shutdownNow()
            worlds.forEach(::close)
        }
    }

    private fun close(world: CoreWorld) {
        world.physicsCoordinator.close()
        (world.tickManager as AutoCloseable).close()
    }
}
