package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.train.FormationMovementHold
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FormationMovementHoldTest {
    @Test
    fun `unresolved snapshot recovery fences only the exact current formation`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = createTrainWithConfig("restored", rail, world, listOf(10.0, 10.0))
            world.onOwnerForTest {
                val cars = train.cars.map { it.id }
                assertInstanceOf(work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Rejected::class.java,
                    world.restoreFormationMovementHold(train.id, cars.reversed()))
                assertFalse(train.movementHeld)
                val accepted = assertInstanceOf(
                    work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted::class.java,
                    world.restoreFormationMovementHold(train.id, cars))
                assertTrue(train.movementHeld)
                assertSame(accepted.hold, assertInstanceOf(
                    work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted::class.java,
                    world.restoreFormationMovementHold(train.id, cars)).hold)
                train.setMovementHeld(false)
                assertTrue(train.movementHeld)
                assertTrue(world.releaseFormationMovementHold(accepted.hold))
                assertFalse(train.movementHeld)
            }
        }
    }

    @Test
    fun `published split outputs remain fenced through listener failure and config hold release`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        try {
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
                val retained = createTrainWithConfig("source", rail, world, listOf(10.0, 10.0))
                var retainedHold: FormationMovementHold? = null
                var separatedHold: FormationMovementHold? = null
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun decide(event: TrainSplitEvent) { event.requestMovementHold() }
                    @HandlesEvent
                    fun committed(event: TrainSplitSuccessEvent) {
                        retainedHold = event.retainedMovementHold
                        separatedHold = event.separatedMovementHold
                        assertTrue((event.train as CoreTrain).movementHeld)
                        assertTrue((event.separatedTrain as CoreTrain).movementHeld)
                        error("post-publication listener failure must not remove fences")
                    }
                }).use {
                    val split = retained.splitBefore(retained.cars.last().id, "separated")
                    world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
                    val separated = split.get(5, TimeUnit.SECONDS) as CoreTrain
                    val retainedTicks = AtomicInteger()
                    val separatedTicks = AtomicInteger()
                    fun controller(train: CoreTrain, counter: AtomicInteger) = object : TrainController(train) {
                        override fun tick(deltaT: Double) {
                            counter.incrementAndGet()
                            speed = 0.2
                            moveControlledTrain(speed * deltaT * 0.001, speed)
                        }
                    }
                    retained.setControllerForTest(controller(retained, retainedTicks))
                    separated.setControllerForTest(controller(separated, separatedTicks))
                    coordinator.start()
                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                    assertEquals(0, retainedTicks.get())
                    assertEquals(0, separatedTicks.get())
                    val heldHead = retained.publishedFrame.head
                    world.onOwnerForTest {
                        retained.moveTrainForward(1.0)
                        assertEquals(heldHead, retained.publishedFrame.head)
                        retained.setMovementHeld(true)
                        assertTrue(world.releaseFormationMovementHold(checkNotNull(retainedHold)))
                        assertFalse(world.releaseFormationMovementHold(checkNotNull(retainedHold)))
                        assertTrue(retained.movementHeld, "formation release must preserve config quarantine")
                        separated.setMovementHeld(false)
                        assertTrue(separated.movementHeld, "config recovery must preserve formation quarantine")
                    }
                    TestWorldFixture.create().use { foreign ->
                        assertFalse(foreign.world.onOwnerForTest {
                            foreign.world.releaseFormationMovementHold(checkNotNull(separatedHold))
                        })
                    }
                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                    assertEquals(0, retainedTicks.get())
                    assertEquals(0, separatedTicks.get())
                    world.onOwnerForTest {
                        retained.setMovementHeld(false)
                        assertTrue(world.releaseFormationMovementHold(checkNotNull(separatedHold)))
                    }
                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                    assertEquals(1, retainedTicks.get())
                    assertEquals(1, separatedTicks.get())
                }
            }
        } finally { coordinator.close() }
    }
}
