package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

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
    fun `relocation immediately uses normals at the new bogie locations`() {
        val world = createTestWorld()
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(50.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.98, 0.20).normalize(), 50.0),
            Curve.RawPoint(Vector3d(100.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.92, 0.39).normalize(), 100.0)
        ))
        val rail = CoreRail(world, curve, mutableListOf(), mutableListOf())
        val train = createTrainWithConfig("banked-relocation", rail, world, List(3) { 10.0 }, isLoop = false)

        train.setLocation(RailLocationWithFacing(rail.viewAB, 70.0))
        train.commit()

        train.cars.forEach { car ->
            val expected = Vector3d(car.bogies.first().normal).add(car.bogies.last().normal)
            expected.sub(Vector3d(car.direction).mul(expected.dot(car.direction))).normalize()
            assertTrue(Vector3d(car.normal).normalize().dot(expected) > 0.9999)
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
