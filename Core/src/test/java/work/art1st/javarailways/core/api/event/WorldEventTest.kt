package work.art1st.javarailways.core.api.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

@OptIn(AsyncEventApi::class)
class WorldEventTest {
    @Test
    fun `all eight running events preserve exact world with same named trains in both modes`() {
        withWorld(WorldExecutionMode.LIVE) { live ->
            withWorld(WorldExecutionMode.SIMULATION) { simulation ->
                val received = mutableListOf<WorldEvent>()
                val listener = object : EventListener {
                    @HandlesEvent fun main(event: MainThreadRailEvent) { received.add(event as WorldEvent) }
                    @HandlesEvent fun physics(event: PhysicsRailEvent) { received.add(event as WorldEvent) }
                }
                MainRailEventBus.register(listener).use {
                    PhysicsRailEventBus.register(listener).use {
                        listOf(live, simulation).forEach { world ->
                            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0))
                            val first = createTrainWithConfig("same-train", rail, world, listOf(10.0, 10.0))
                            val second = createTrainWithConfig("other-train", rail, world, listOf(10.0))
                            val signal = object : Signal(RailObjectRef("test", "signal"), RailLocationWithoutFacing(rail, 100.0)) {
                                override val lifecycleState = RailObjectLifecycleState.ACTIVE
                                override val velocity = org.joml.Vector3d()
                                override fun destroy(): java.util.concurrent.CompletionStage<Void> = java.util.concurrent.CompletableFuture.completedFuture(null)
                            }
                            val events = listOf(
                                CollisionEvent(7, world, first.id, second.id, 0.5),
                                PassSignalEvent(first, signal),
                                DerailEvent(first, "fixture"),
                                TrainSwitchDirectionEvent(first),
                                TrainLinkEvent(first, second, TrainEndSide.A, TrainEndSide.B, 0.5, 0.0, first.publishedFrame.head, second.publishedFrame.head, second),
                                TrainLinkSuccessEvent(first, second, first, second, TrainEndSide.A, TrainEndSide.B,
                                    first.cars.map { it.id }, second.cars.map { it.id }, false, false),
                                TrainSplitEvent(first, first.cars.last().id, "separated"),
                                TrainSplitSuccessEvent(first, second, first.cars.last().id, first.cars.map { it.id }, false)
                            )
                            events.forEach { event ->
                                when (event) {
                                    is MainThreadRailEvent -> IsolatedSimulationThreadMarker.run { MainRailEventBus.publish(event) }
                                    is PhysicsRailEvent -> AsyncTickTestSupport.run { PhysicsRailEventBus.publish(event) }
                                }
                            }
                            assertEquals(events, received.takeLast(8))
                            received.takeLast(8).forEach { event ->
                                assertSame(world, event.world)
                                assertEquals(world.executionMode, event.world.executionMode)
                            }
                        }
                        assertEquals(16, received.size)
                        assertNotSame(received[0].world, received[8].world)
                        assertEquals(live.getTrain("same-train")!!.name, simulation.getTrain("same-train")!!.name)
                        assertNotSame(live.getTrain("same-train"), simulation.getTrain("same-train"))
                    }
                }
            }
        }
    }

    private fun withWorld(mode: WorldExecutionMode, action: (CoreWorld) -> Unit) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try { createTestWorld(coordinator, mode) } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            run { coordinator.use { action(world) } }
        }
    }
}
