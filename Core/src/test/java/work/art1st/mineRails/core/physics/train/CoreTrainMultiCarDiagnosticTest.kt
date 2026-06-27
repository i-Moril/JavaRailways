package work.art1st.mineRails.core.physics.train

import org.joml.Vector3dc
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.util.CommitBuffer

class CoreTrainMultiCarDiagnosticTest {
    @Test
    fun `command style two-car train should relocate on long straight rail`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
        val train = createCommandStyleTrain(world, rail, carCount = 2, carLength = 2.0)
        val targetLocation = RailLocationWithFacing(rail.viewAB, 80.0)

        assertDoesNotThrow {
            train.setLocation(targetLocation)
        }
    }

    @Test
    fun `previous car public position is stale before commit after updateByHeadBogie`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
        val train = createCommandStyleTrain(world, rail, carCount = 2, carLength = 2.0)
        val firstCar = train.cars.first()
        val headLocation = RailLocationWithFacing(rail.viewAB, 80.0)
        val headBogieLocation = headLocation.forward(-firstCar.headBogieToEndPointOffset)
            ?: error("cannot place first head bogie")

        firstCar.headBogie.updateLocation(headBogieLocation)
        firstCar.updateByHeadBogie(firstCar.headBogie.activeLocation)

        val activePosition = activeValue<Vector3dc>(firstCar, "_position")

        assertTrue(
            firstCar.position.distance(activePosition) > 1.0,
            "firstCar.position is committed state, not the just-computed active position"
        )
    }

    @Test
    fun `default endpoint offsets match physical end distances`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1))
        val train = createCommandStyleTrain(world, rail, carCount = 1, carLength = 2.0)
        val car = train.cars.single()

        val expectedHeadBogieToHeadEnd = car.length / 2 - car.bogieAOffset
        val expectedTailBogieToTailEnd = car.length / 2 + car.bogieBOffset

        assertEquals(expectedHeadBogieToHeadEnd, car.headBogieToEndPointOffset, 1e-9)
        assertEquals(expectedTailBogieToTailEnd, car.tailBogieToEndPointOffset, 1e-9)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> activeValue(instance: Any, fieldName: String): T {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val buffer = field.get(instance) as CommitBuffer<T>
        return buffer.getActive()
    }

    private fun createCommandStyleTrain(
        world: CoreWorld,
        rail: CoreRail,
        carCount: Int,
        carLength: Double
    ): CoreTrain {
        var currentLocation = RailLocationWithFacing(rail.viewAB, 0.0)
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { index ->
            val bogieA = CoreBogie.create(currentLocation)
            val nextLocation = currentLocation.forward(carLength) ?: error("cannot place bogieB")
            val bogieB = CoreBogie.create(nextLocation)
            cars += CoreCar.create(
                id = "diagnostic-car-$index",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carLength,
                world = world
            )
            currentLocation = nextLocation
        }

        return CoreTrain.create("diagnostic-train", cars, world)
    }
}
