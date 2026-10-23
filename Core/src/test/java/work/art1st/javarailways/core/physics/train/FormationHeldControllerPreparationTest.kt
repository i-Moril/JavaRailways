package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator

class FormationHeldControllerPreparationTest {
    enum class Hold { FORMATION, CONFIGURATION, BOTH, DERAILED, COLLISION }

    @ParameterizedTest
    @EnumSource(Hold::class)
    fun `only unquarantined formation hold prepares controller and all held motion stays frozen`(hold: Hold) {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            TestWorldFixture.create(physics).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                val train = createTrainWithConfig("held", rail, world, listOf(10.0))
                world.onOwnerForTest {
                    if (hold != Hold.CONFIGURATION) world.restoreFormationMovementHold(train.id, train.cars.map { it.id })
                    if (hold == Hold.CONFIGURATION || hold == Hold.BOTH) train.setMovementHeld(true)
                    if (hold == Hold.DERAILED) train.derail("test")
                }
                if (hold == Hold.COLLISION) createTrainWithConfig("obstacle", rail, world, listOf(10.0))
                var ticks = 0
                var preparations = 0
                val controller = object : TrainController(train) {
                    override fun tick(deltaT: Double) { ticks++; speed = 2.0; moveControlledTrain(1.0, speed) }
                    override fun prepareWhileFormationHeld(deltaT: Double) {
                        preparations++
                        // A faulty callback cannot escape the independent Core physical fence.
                        speed = 2.0
                        moveControlledTrain(1.0, speed)
                    }
                }
                train.setControllerForTest(controller)
                val before = train.publishedFrame
                physics.start()
                physics.stepSimulation(50.0).await()
                assertEquals(0, ticks)
                assertEquals(if (hold == Hold.FORMATION) 1 else 0, preparations)
                assertEquals(0.0, controller.speed)
                val after = train.publishedFrame
                assertEquals(before.head, after.head)
                assertEquals(before.tail, after.tail)
                assertEquals(0.0, after.speedMetersPerSecond)
                assertTrue(after.cars.all { car -> (car.bogies + car.state).all {
                    it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
                } })
            }
        }
    }
}
