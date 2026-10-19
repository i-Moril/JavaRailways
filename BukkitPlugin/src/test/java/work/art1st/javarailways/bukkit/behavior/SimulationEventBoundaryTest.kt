package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.debug.TrainDebugEventListener
import work.art1st.javarailways.bukkit.debug.TrainDebugLog
import work.art1st.javarailways.core.api.event.DerailEvent
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class SimulationEventBoundaryTest {
    @Test
    fun `simulation events never access a Bukkit renderer or production CSV even when logging enabled`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try { createTestWorld(coordinator, WorldExecutionMode.SIMULATION) } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                coordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                    val train = createTrainWithConfig("simulation", rail, world, listOf(10.0))
                    val previous = TrainDebugLog.enabled
                    try {
                        TrainDebugLog.enabled = true
                        // This world has no Bukkit extension/renderer or plugin instance. Any access fails.
                        assertDoesNotThrow {
                            TrainReverseBehaviorListener().onTrainSwitchDirection(TrainSwitchDirectionEvent(train))
                            TrainDebugEventListener().onDerail(DerailEvent(train, "simulation"))
                        }
                    } finally { TrainDebugLog.enabled = previous }
                }
            }
        }
    }
}
