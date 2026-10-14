package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class CoreTrainRigidGeometryTest {
    @ParameterizedTest
    @ValueSource(strings = ["straight", "R30", "S", "banked-slope"])
    fun `real connector geometry survives placement movement reverse stop and reload`(shape: String) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                coordinator.use { exercise(world, shape) }
            }
        }
    }

    private fun exercise(world: CoreWorld, shape: String) {
        val curve = if (shape == "R30") {
            PrimeCurveGenerator.buildCircle(30.0, 1, step = 0.02).single()
        } else sampledCurve(shape)
        val rail = world.createRailForTest(curve)
        val start = RailLocationWithFacing(rail.viewAB, 90.0)
        val cars = List(3) { index ->
            CoreCar.create(
                "rigid-$index", CoreBogie.create(start), CoreBogie.create(start),
                length = 10.0, floorHeight = if (shape == "banked-slope") 0.4 + index * 0.05 else 0.5,
                connectorLengthAtA = if (shape == "straight") 0.0 else 0.3,
                connectorLengthAtB = if (shape == "straight") 0.0 else 0.3,
                bogieAOffset = if (index == 1) 3.5 else 3.0,
                bogieBOffset = if (index == 1) -2.5 else -3.0,
                world = world
            )
        }
        var train = CoreTrain("rigid", cars, world, emptyMap()).also {
            it.setLocation(start)
            it.commitMaintenancePlacement()
        }
        assertGeometry(train, "$shape placement")
        train.setController(MockTrainController(train, world, 10.0))
        world.physicsCoordinator.start()
        repeat(60) { tick ->
            val head = train.headCar.headBogie.location
            val expected = requireNotNull(head.forward(0.5))
            val ticket = world.physicsCoordinator.stepSimulation(50.0)
            ticket.completion.get(5, TimeUnit.SECONDS)
            assertEquals(ticket.epoch, train.publishedFrame.physicsEpoch)
            assertFalse(train.isDerailed, "$shape tick $tick")
            assertEquals(expected.railView, train.headCar.headBogie.location.railView)
            assertEquals(expected.arcLength, train.headCar.headBogie.location.arcLength, 1e-9, "fixed leading target")
            assertGeometry(train, "$shape tick $tick")
        }

        val reversed = train.controller!!.reverse()
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames { world.physicsCoordinator.drainBetweenFramesOperations() }
        }
        reversed.get(5, TimeUnit.SECONDS)
        assertTrue(train.isReversed)
        assertGeometry(train, "$shape reverse")
        repeat(60) { tick ->
            val expected = requireNotNull(train.headCar.headBogie.location.forward(0.5))
            world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            assertFalse(train.isDerailed, "$shape reversed tick $tick")
            assertEquals(expected.railView, train.headCar.headBogie.location.railView)
            assertEquals(expected.arcLength, train.headCar.headBogie.location.arcLength, 1e-9)
            assertGeometry(train, "$shape reversed tick $tick")
        }
        train.setController(null)
        val stoppedPositions = train.cars.map { Vector3d(it.position) }
        world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
        train.cars.zip(stoppedPositions).forEach { (car, expected) ->
            assertEquals(0.0, car.position.distance(expected), 1e-12)
            assertEquals(0.0, car.velocity.length(), 1e-12)
        }
        val saved = train.save()
        train.discardFailedLoad()
        train = saved.load(world)
        assertEquals(saved, train.save(), "reload preserves exact bogie and endpoint positions")
        train.cars.zip(stoppedPositions).forEach { (car, expected) ->
            assertEquals(0.0, car.position.distance(expected), 5e-6, "$shape restored centre")
        }
        assertGeometry(train, "$shape reload")
        if (shape == "R30") {
            // Reversed AB placement near the finite start exercises the reachable-path cap for
            // trailing candidates, without asking the train head to cross a track endpoint.
            val owner = Thread.currentThread()
            val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread() = Thread.currentThread() === owner
            })
            try {
                train.setLocation(RailLocationWithFacing(rail.viewBA, curve.totalArcLength - 1.0))
            } finally {
                registration.close()
            }
            train.commitMaintenancePlacement()
            train.setController(MockTrainController(train, world, 10.0))
            world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
            assertFalse(train.isDerailed, "valid movement near finite track end")
            assertGeometry(train, "$shape finite end")
        }
    }

    private fun assertGeometry(train: CoreTrain, context: String) {
        train.cars.forEach { car ->
            val a = Vector3d(car.bogieA.position).add(Vector3d(car.bogieA.normal).mul(car.floorHeight))
            val b = Vector3d(car.bogieB.position).add(Vector3d(car.bogieB.normal).mul(car.floorHeight))
            assertEquals(car.bogieAOffset - car.bogieBOffset, a.distance(b), Curve.TOLERANCE, "$context axle")
            assertEquals(0.0, Vector3d(car.position).add(Vector3d(car.direction).mul(car.bogieAOffset)).distance(a),
                Curve.TOLERANCE, "$context A offset")
            assertEquals(0.0, Vector3d(car.position).add(Vector3d(car.direction).mul(car.bogieBOffset)).distance(b),
                Curve.TOLERANCE, "$context B offset")
        }
        val operating = if (train.isReversed) train.cars.asReversed() else train.cars
        operating.zipWithNext().forEach { (leading, following) ->
            val leadingDirection = Vector3d(leading.operatingDirection)
            val followingDirection = Vector3d(following.operatingDirection)
            val tail = Vector3d(leading.position).sub(leadingDirection.mul(leading.length / 2.0))
            val head = Vector3d(following.position).add(Vector3d(followingDirection).mul(following.length / 2.0))
            val gap = tail.sub(head)
            assertEquals(leading.tailConnectorLength + following.headConnectorLength, gap.length(),
                Curve.TOLERANCE, "$context coupler")
            assertTrue(gap.dot(followingDirection) >= -1e-6, "$context coupler side")
        }
    }

    private fun sampledCurve(shape: String): PrimeCurve {
        var previous: Vector3d? = null
        var arc = 0.0
        return PrimeCurve.fromPoints((0..15000).map { index ->
            val x = index * 0.02
            val curved = shape != "straight"
            val slope = shape == "banked-slope"
            val point = Vector3d(x, if (slope) 2.0 * sin(x / 18.0) else 0.0,
                if (curved) 6.0 * sin(x / 15.0) else 0.0)
            previous?.let { arc += it.distance(point) }
            previous = point
            val tangent = Vector3d(1.0, if (slope) cos(x / 18.0) / 9.0 else 0.0,
                if (curved) 0.4 * cos(x / 15.0) else 0.0).normalize()
            val normal = Vector3d(0.0, 1.0, if (slope) 0.2 * sin(x / 12.0) else 0.0)
            normal.sub(Vector3d(tangent).mul(normal.dot(tangent))).normalize()
            Curve.RawPoint(point, tangent, normal, arc)
        })
    }
}
