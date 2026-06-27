package work.art1st.mineRails.core.physics.train

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator

class CoreCarPositioningRegressionTest {
    @Test
    fun `command style train relocation on circle succeeds`() {
        val world = createTestWorld()
        val rail = createCurvedRail(world, 20.0)
        val train = createCommandStyleTrain(world, rail, carLength = 10.0)
        val targetLocation = RailLocationWithFacing(rail.viewAB, 25.0)

        assertDoesNotThrow {
            train.setLocation(targetLocation)
        }
    }

    @Test
    fun `test helper train relocation on circle succeeds`() {
        val world = createTestWorld()
        val rail = createCurvedRail(world, 20.0)
        val train = createTrainWithConfig("test-helper-train", rail, world, listOf(10.0), isLoop = true)
        val targetLocation = RailLocationWithFacing(rail.viewAB, 25.0)

        assertDoesNotThrow {
            train.setLocation(targetLocation)
        }
    }

    private fun createCurvedRail(world: CoreWorld, radius: Double): CoreRail {
        val curve = PrimeCurveGenerator.buildCircle(radius, segmentCount = 1).single()
        return CoreRail(world, curve, mutableListOf(), mutableListOf())
    }

    private fun createCommandStyleTrain(world: CoreWorld, rail: CoreRail, carLength: Double): CoreTrain {
        val currentLocation = RailLocationWithFacing(rail.viewAB, 0.0)
        val bogieA = CoreBogie.create(currentLocation)
        val bogieB = CoreBogie.create(currentLocation.forward(carLength) ?: error("cannot place bogieB"))
        val car = CoreCar.create(
            id = "command-style-car",
            bogieA = bogieA,
            bogieB = bogieB,
            length = carLength,
            world = world
        )
        return CoreTrain.create("command-style-train", listOf(car), world)
    }
}
