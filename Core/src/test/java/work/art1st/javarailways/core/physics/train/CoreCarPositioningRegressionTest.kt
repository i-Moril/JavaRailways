package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.rail.RailSegments
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit

class CoreCarPositioningRegressionTest {
    @Test
    fun `seat position follows the published car through a physical step`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            try {
                coordinator.close()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            run {
                coordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
                    // The fixture puts the 10m car's ends at x=20 and x=10, with a zero-height floor.
                    val train = createTrainWithConfig("seat-position", rail, world, listOf(10.0))
                    val car = train.cars.single()
                    val seat = world.onOwnerForTest { car.addSeat() }
                    val initialSeatPosition = Vector3d(seat.position)

                    assertEquals(0.0, initialSeatPosition.distance(Vector3d(12.5, 0.5, 0.0)), 1e-6)
                    assertEquals(15.0, train.publishedFrame.cars.single().state.positionX, 1e-6)
                    train.setControllerForTest(MockTrainController(train, 10.0))
                    coordinator.start()

                    val ticket = coordinator.stepSimulation(50.0)
                    ticket.completion.get(5, TimeUnit.SECONDS)

                    assertEquals(ticket.epoch, train.publishedFrame.physicsEpoch)
                    assertEquals(15.5, train.publishedFrame.cars.single().state.positionX, 1e-6)
                    assertEquals(0.0, seat.position.distance(Vector3d(13.0, 0.5, 0.0)), 1e-6)
                    assertEquals(12.5, initialSeatPosition.x, 1e-6, "an earlier position is not mutated")
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `finite captured path prepares either physical front without reading new turnout selection`(
        reversed: Boolean
    ) = withWorld { world ->
        val stem = slopedRail(world, 0.0, 20.0)
        val main = slopedRail(world, 20.0, 60.0)
        val branch = world.createRailForTest(PrimeCurve.fromPoints(listOf(
            rawPoint(Vector3d(20.0, 2.0, 0.0), Vector3d(40.0, 4.0, 20.0), 0.0),
            rawPoint(Vector3d(60.0, 6.0, 20.0), Vector3d(40.0, 4.0, 20.0), Vector3d(40.0, 4.0, 20.0).length())
        )))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, main) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, branch) }
        val car = preparedTestCar(world, stem)
        val before = carState(car)
        val path = RailSegments(listOf(
            RailSegment(stem.viewAB, 10.0, stem.curve.totalArcLength),
            RailSegment(main.viewAB, 0.0, 20.0)
        ))
        stem.viewAB.activeRail = branch.viewAB
        main.viewBA.activeRail = null
        val anchor = Vector3d(26.0, 3.1, 0.0)

        val prepared = car.prepareFollowingCarOnPath(
            path, reversed, anchor, 0.4, maximumTailDistance = 20.0,
            predictedTailDistance = 8.0, predictedBogieSpacing = 5.0
        )

        val a = Vector3d(prepared.bogieALocation.position)
            .add(Vector3d(prepared.bogieALocation.normal).mul(0.5))
        val b = Vector3d(prepared.bogieBLocation.position)
            .add(Vector3d(prepared.bogieBLocation.normal).mul(0.5))
        val direction = prepared.direction.toVector3d()
        val centre = prepared.position.toVector3d()
        val headEnd = Vector3d(centre).add(Vector3d(direction).mul(if (reversed) -5.0 else 5.0))
        assertEquals(5.0, a.distance(b), 1e-6, "3D axle chord")
        assertEquals(0.0, Vector3d(centre).add(Vector3d(direction).mul(3.0)).distance(a), 1e-6)
        assertEquals(0.0, Vector3d(centre).sub(Vector3d(direction).mul(2.0)).distance(b), 1e-6)
        assertEquals(if (reversed) 1.2 else 0.6, headEnd.distance(anchor), 1e-5, "physical front connector")
        assertEquals(0.0, prepared.normal.toVector3d().dot(direction), 1e-12)
        assertEquals(1.0, prepared.normal.toVector3d().length(), 1e-12)
        assertEquals(reversed, prepared.desiredReversed)
        assertEquals(stem, if (reversed) prepared.bogieALocation.rail else prepared.bogieBLocation.rail)
        assertEquals(main, if (reversed) prepared.bogieBLocation.rail else prepared.bogieALocation.rail)
        assertTrue(direction.x() * (if (reversed) -1.0 else 1.0) > 0.99)
        // Publishing after prepare also detects accidental active-buffer/dirty-flag changes.
        car.publishPhysicsState()
        assertEquals(before, carState(car), "prepare preserves actual direction, geometry and nonzero motion")
        assertEquals(branch.viewAB, stem.viewAB.activeRail)
        assertEquals(null, main.viewBA.activeRail)
    }

    @Test
    fun `insufficient captured path fails without installing a partial car`() = withWorld { world ->
        val rail = slopedRail(world, 0.0, 60.0)
        val car = preparedTestCar(world, rail)
        val before = carState(car)
        val shortPath = RailSegments(listOf(RailSegment(rail.viewAB, 10.0, 12.0)))

        assertThrows(BogieException::class.java) {
            car.prepareFollowingCarOnPath(shortPath, true, Vector3d(26.0, 3.1, 0.0), 0.4,
                maximumTailDistance = 20.0, predictedTailDistance = 8.0, predictedBogieSpacing = 5.0)
        }

        car.publishPhysicsState()
        assertEquals(before, carState(car))
    }

    private fun preparedTestCar(world: CoreWorld, rail: CoreRail): CoreCar {
        val start = RailLocationWithFacing(rail.viewAB, 10.0)
        return CoreCar.create("prepared-car", CoreBogie.create(start), CoreBogie.create(start),
            length = 10.0, floorHeight = 0.5, connectorLengthAtA = 0.2, connectorLengthAtB = 0.8,
            bogieAOffset = 3.0, bogieBOffset = -2.0, world = world
        ).also { car ->
            car.updateByHeadBogie(start)
            car.updateMotionStateByHeadVelocity(Vector3d(10.0, 1.0, 0.0))
            car.publishPhysicsState()
            assertTrue(car.velocity.length() > 1.0)
        }
    }

    private fun carState(car: CoreCar): List<Any> = listOf(
        car.save(), car.isReversed, Vector3Snapshot.capture(car.position),
        Vector3Snapshot.capture(car.direction), Vector3Snapshot.capture(car.normal),
        Vector3Snapshot.capture(car.velocity), Vector3Snapshot.capture(car.motionState.angularVelocity),
        car.bogies.map { Vector3Snapshot.capture(it.velocity) }
    )

    private fun slopedRail(world: CoreWorld, start: Double, end: Double): CoreRail =
        world.createRailForTest(PrimeCurve.fromPoints(listOf(
            rawPoint(Vector3d(start, start * 0.1, 0.0), Vector3d(1.0, 0.1, 0.0), 0.0),
            rawPoint(Vector3d(end, end * 0.1, 0.0), Vector3d(1.0, 0.1, 0.0),
                (end - start) * kotlin.math.sqrt(1.01))
        )))

    private fun rawPoint(position: Vector3d, tangent: Vector3d, distance: Double) =
        Curve.RawPoint(position, Vector3d(tangent).normalize(), Vector3d(0.0, 1.0, 0.0), distance)

    @Test
    fun `command style train relocation on circle succeeds`() = withWorld { world ->
        val rail = createCurvedRail(world, 20.0)
        val train = createCommandStyleTrain(world, rail, carLength = 10.0)
        val targetLocation = RailLocationWithFacing(rail.viewAB, 25.0)

        assertDoesNotThrow {
            train.world.onOwnerForTest {
                train.setLocation(targetLocation)
            }
        }
    }

    @Test
    fun `relocation immediately uses normals at the new bogie locations`() = withWorld { world ->
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(50.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.98, 0.20).normalize(), 50.0),
            Curve.RawPoint(Vector3d(100.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.92, 0.39).normalize(), 100.0)
        ))
        val rail = world.createRailForTest(curve)
        val train = createTrainWithConfig("banked-relocation", rail, world, List(3) { 10.0 }, isLoop = false)

        train.world.onOwnerForTest {
            train.setLocation(RailLocationWithFacing(rail.viewAB, 70.0))
            train.commit()
        }

        train.cars.forEach { car ->
            val expected = Vector3d(car.bogies.first().normal).add(car.bogies.last().normal)
            expected.sub(Vector3d(car.direction).mul(expected.dot(car.direction))).normalize()
            assertTrue(Vector3d(car.normal).normalize().dot(expected) > 0.9999)
        }
    }

    @Test
    fun `test helper train relocation on circle succeeds`() = withWorld { world ->
        val rail = createCurvedRail(world, 20.0)
        val train = createTrainWithConfig("test-helper-train", rail, world, listOf(10.0), isLoop = true)
        val targetLocation = RailLocationWithFacing(rail.viewAB, 25.0)

        assertDoesNotThrow {
            train.world.onOwnerForTest {
                train.setLocation(targetLocation)
            }
        }
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            run {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun createCurvedRail(world: CoreWorld, radius: Double): CoreRail {
        val curve = PrimeCurveGenerator.buildCircle(radius, segmentCount = 1).single()
        return world.createRailForTest(curve)
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
        return world.onOwnerForTest { CoreTrain.create("command-style-train", listOf(car), world) }
    }
}
