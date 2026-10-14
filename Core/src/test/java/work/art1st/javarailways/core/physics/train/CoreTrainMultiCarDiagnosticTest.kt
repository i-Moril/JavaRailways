package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit

class CoreTrainMultiCarDiagnosticTest {
    @Test
    fun `command style two-car relocation is visible only after publication`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
        val train = createCommandStyleTrain(world, rail, carCount = 2, carLength = 2.0)
        val beforeFrame = train.publishedFrame
        val beforeCarPositions = train.cars.map { it.position.x() }

        train.world.onOwnerForTest {
            train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
            assertSame(beforeFrame, train.publishedFrame)
            assertEquals(beforeCarPositions, train.cars.map { it.position.x() })
            train.commitMaintenancePlacement()
        }

        assertEquals(beforeFrame.revision + 1L, train.publishedFrame.revision)
        assertEquals(80.0, train.headLocation.position.x(), Curve.TOLERANCE)
        assertEquals(75.4, train.tailLocation.position.x(), Curve.TOLERANCE)
        assertEquals(79.0, train.cars[0].position.x(), Curve.TOLERANCE)
        assertEquals(76.4, train.cars[1].position.x(), Curve.TOLERANCE)
        assertEquals(2.6, train.cars[0].position.x() - train.cars[1].position.x(), Curve.TOLERANCE)
        assertEquals(4.6, train.headLocation.position.x() - train.tailLocation.position.x(), Curve.TOLERANCE)
        assertEquals(79.0, train.publishedFrame.cars[0].state.positionX, Curve.TOLERANCE)
        assertEquals(76.4, train.publishedFrame.cars[1].state.positionX, Curve.TOLERANCE)
    }

    @Test
    fun `published two-car movement preserves fixed consist spacing`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            runCatching(coordinator::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        withWorld(world) { managedWorld ->
            val rail = createStraightRailFromSegments(managedWorld, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))
            val train = createCommandStyleTrain(managedWorld, rail, carCount = 2, carLength = 2.0)
            train.world.onOwnerForTest {
                train.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
                train.commitMaintenancePlacement()
            }
            train.setControllerForTest(MockTrainController(train, managedWorld, 10.0))
            coordinator.start()

            val ticket = coordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)

            assertEquals(ticket.epoch, train.publishedFrame.physicsEpoch)
            assertEquals(80.5, train.headLocation.position.x(), Curve.TOLERANCE)
            assertEquals(75.9, train.tailLocation.position.x(), Curve.TOLERANCE)
            assertEquals(79.5, train.cars[0].position.x(), Curve.TOLERANCE)
            assertEquals(76.9, train.cars[1].position.x(), Curve.TOLERANCE)
            assertEquals(2.6, train.cars[0].position.x() - train.cars[1].position.x(), Curve.TOLERANCE)
            assertEquals(4.6, train.headLocation.position.x() - train.tailLocation.position.x(), Curve.TOLERANCE)
            assertEquals(79.5, train.publishedFrame.cars[0].state.positionX, Curve.TOLERANCE)
            assertEquals(76.9, train.publishedFrame.cars[1].state.positionX, Curve.TOLERANCE)
        }
    }

    @Test
    fun `default endpoint offsets match physical end distances`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1))
        val train = createCommandStyleTrain(world, rail, carCount = 1, carLength = 2.0)
        val car = train.cars.single()

        assertEquals(0.6, car.bogieAOffset, 1e-9)
        assertEquals(-0.6, car.bogieBOffset, 1e-9)
        assertEquals(0.4, car.headBogieToEndPointOffset, 1e-9)
        assertEquals(0.4, car.tailBogieToEndPointOffset, 1e-9)
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

        return world.onOwnerForTest { CoreTrain.create("diagnostic-train", cars, world) }
    }

    private fun closeWorld(world: CoreWorld) {
        var failure: Throwable? = null
        try {
            world.physicsCoordinator.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            (world.tickManager as AutoCloseable).close()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        failure?.let { throw it }
    }

    private inline fun withWorld(
        world: CoreWorld = createTestWorld(),
        action: (CoreWorld) -> Unit
    ) {
        var primaryFailure: Throwable? = null
        try {
            action(world)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                closeWorld(world)
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }
}
