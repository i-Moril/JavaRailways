package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.annotation.TrainStructureApi
import work.art1st.javarailways.core.control.train.MockTrainController
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit

class CoreTrainAsymmetricTurnoutTest {
    @Test
    @OptIn(TrainStructureApi::class)
    fun `reversed split publishes original turnout path without consulting unset reverse selection`() =
        withFixture { fixture ->
            val train = fixture.train
            val oldTail = train.publishedFrame.tail
            val originalCars = train.publishedFrame.cars
            val originalSuffixHead = requireNotNull(train.cars.last().headBogie.location.forward(
                train.cars.last().headBogieToEndPointOffset
            ))
            fixture.junction.viewBA.activeRail = null
            val owner = Thread.currentThread()
            val result = train.splitBefore("car-4", "reversed-suffix")
            ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread() = Thread.currentThread() === owner
            }).use {
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun decide(event: TrainSplitEvent) = event.requestSeparatedDirection(true)
                }).use { fixture.world.tickManager.postTick() }
            }
            val detached = result.get(5, TimeUnit.SECONDS) as CoreTrain

            assertTrue(detached.isReversed)
            assertCarFramesEqual(originalCars, train.publishedFrame.cars + detached.publishedFrame.cars)
            assertEquals(oldTail.railId, detached.publishedFrame.head.railId)
            assertEquals(oldTail.arcLengthInAbMeters, detached.publishedFrame.head.arcLengthInAbMeters, 1e-9)
            assertEquals(listOf(fixture.junction.id, fixture.previous.id),
                detached.publishedFrame.bodyTraversal.map { it.railId })
            assertEquals(listOf(ViewDirection.BA, ViewDirection.AB),
                detached.publishedFrame.bodyTraversal.map { it.facing })
            assertEquals(null, fixture.junction.viewBA.activeRail)
            assertEquals(0.0, detached.tail.location.position.distance(originalSuffixHead.position), 1e-9)
        }

    @Test
    fun `removing an active rail selects the first remaining candidate even in invalid multi-branch topology`() =
        withWorld { world ->
            val stem = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
            val first = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
            val second = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
            val removed = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, -5.0))
            CoreRail.connect(stem, first)
            CoreRail.connect(stem, second)
            CoreRail.connect(stem, removed)
            stem.viewAB.activeRail = removed.viewAB

            world.removeRail(removed)

            assertEquals(listOf(first.viewAB, second.viewAB), stem.viewAB.mutableNextRails)
            assertTrue(stem.viewAB.activeRail === first.viewAB)
        }

    @Test
    fun `removing an occupied rail is rejected without changing topology`() = withWorld { world ->
        val occupied = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0))
        createTrainWithConfig("occupied-remove", occupied, world, listOf(8.0))

        assertThrows(IllegalArgumentException::class.java) { world.removeRail(occupied) }

        assertTrue(world.getRail(occupied.id) === occupied)
    }

    @Test
    fun `removing a rail crossed only by the train body is rejected`() = withWorld { world ->
        val before = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        val middle = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(35.0, 0.0, 0.0))
        val after = rail(world, Vector3d(35.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        CoreRail.connect(before, middle)
        CoreRail.connect(middle, after)
        val train = createTrainWithConfig("body-only", before, world, listOf(20.0, 20.0))
        train.setLocation(RailLocationWithFacing(after.viewAB, 25.0))
        train.commitMaintenancePlacement()
        assertTrue(train.head.location.rail !== middle && train.tail.location.rail !== middle)

        assertThrows(IllegalArgumentException::class.java) { world.removeRail(middle) }

        assertTrue(world.getRail(middle.id) === middle)
    }

    @Test
    fun `loading rejects a declared active target that disappeared`() = withWorld { sourceWorld ->
        val stem = rail(sourceWorld, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val removed = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, -5.0))
        val first = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val second = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
        CoreRail.connect(stem, removed)
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        stem.viewAB.activeRail = removed.viewAB
        val saved = sourceWorld.allRails.filter { it !== removed }.map(CoreRail::save)

        withWorld { restoredWorld ->
            val dependency = CoreRail.SavedRailV1.Dependency(restoredWorld)
            assertThrows(IllegalArgumentException::class.java) {
                restoredWorld.railTopologyTransaction {
                    val disconnected = saved.associateWith { it.loadDisconnected(dependency) }
                    disconnected.forEach { (snapshot, rail) -> snapshot.restoreConnections(rail, dependency) }
                }
            }
        }
    }

    @Test
    fun `moving a long train uses its own tail path when reverse active path is unset`() =
        withFixture { fixture ->
            fixture.junction.viewBA.activeRail = null
            val startHeadX = fixture.train.headLocation.position.x()
            val startTailX = fixture.train.tailLocation.position.x()

            val ticket = moveByPublishedEpoch(fixture, speedMetersPerSecond = 10.0)

            assertPublishedPlacement(fixture, ticket.epoch)
            assertEquals(startHeadX + 0.5, fixture.train.headLocation.position.x(), Curve.TOLERANCE)
            assertEquals(startTailX + 0.5, fixture.train.tailLocation.position.x(), Curve.TOLERANCE)
            assertEquals(fixture.previous.id, fixture.train.publishedFrame.bodyTraversal.first().railId)
            assertFalse(fixture.train.isDerailed, fixture.train.derailReason)
        }

    @Test
    fun `tail endpoint is re-anchored without accumulating head delta on repeated ticks`() =
        withFixture { fixture ->
            fixture.junction.viewBA.activeRail = null
            val train = fixture.train
            val startHeadX = train.headLocation.position.x()
            val startTailX = train.tailLocation.position.x()
            train.setController(MockTrainController(train, fixture.world, 2.0))
            fixture.world.physicsCoordinator.start()

            repeat(80) { index ->
                val ticket = fixture.world.physicsCoordinator.stepSimulation(50.0)
                ticket.completion.get(5, TimeUnit.SECONDS)
                assertPublishedPlacement(fixture, ticket.epoch)
                assertFalse(train.isDerailed, train.derailReason)
                val projectedBogie = train.tail.location.forward(train.tailCar.tailBogieToEndPointOffset)
                assertTrue(projectedBogie != null)
                assertTrue(
                    projectedBogie!!.position.distance(train.tailBogie.location.position) <= Curve.TOLERANCE,
                    "tail endpoint drifted from solved bogie at tick $index"
                )
            }

            assertEquals(startHeadX + 8.0, train.headLocation.position.x(), Curve.TOLERANCE)
            assertEquals(startTailX + 8.0, train.tailLocation.position.x(), Curve.TOLERANCE)
            assertFalse(train.isDerailed, train.derailReason)
        }

    @Test
    fun `moving the same long train succeeds when reverse active path is selected`() =
        withFixture { fixture ->
            fixture.junction.viewBA.selectNextRail(fixture.previous.viewAB)
            val startHeadX = fixture.train.headLocation.position.x()
            val startTailX = fixture.train.tailLocation.position.x()

            val ticket = moveByPublishedEpoch(fixture, speedMetersPerSecond = 10.0)

            assertPublishedPlacement(fixture, ticket.epoch)
            assertEquals(startHeadX + 0.5, fixture.train.headLocation.position.x(), Curve.TOLERANCE)
            assertEquals(startTailX + 0.5, fixture.train.tailLocation.position.x(), Curve.TOLERANCE)
            assertFalse(fixture.train.isDerailed, fixture.train.derailReason)
        }

    @Test
    fun `exact saved train ends restore across the same unset reverse active path`() =
        withFixture { fixture ->
            val saved = fixture.train.save()
            fixture.world.removeTrain(fixture.train)
            fixture.junction.viewBA.activeRail = null

            assertDoesNotThrow {
                saved.load(fixture.world)
            }
        }

    private fun fixtureWithTailAcrossJunction(): Fixture = fixture()

    private fun fixture(): Fixture {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            runCatching(coordinator::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try {
            // Both branches expose their A endpoints at the same physical anchor, matching the
            // persisted s100-2 topology (4c9... A connected to b15... A and an alternate branch).
            val previous = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
            val alternate = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(30.0, 0.0, 20.0))
            val junction = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(51.0, 0.0, 0.0))
            val next = rail(world, Vector3d(51.0, 0.0, 0.0), Vector3d(61.0, 0.0, 0.0))
            val long = rail(world, Vector3d(61.0, 0.0, 0.0), Vector3d(261.0, 0.0, 0.0))
            val staging = rail(world, Vector3d(-300.0, 0.0, 0.0), Vector3d(-100.0, 0.0, 0.0))
            CoreRail.connect(previous, junction)
            CoreRail.connect(alternate, junction)
            CoreRail.connect(junction, next)
            CoreRail.connect(next, long)
            previous.viewBA.selectNextRail(junction.viewAB)
            junction.viewBA.selectNextRail(previous.viewAB)
            junction.viewAB.selectNextRail(next.viewAB)
            next.viewAB.selectNextRail(long.viewAB)
            val stagingLocation = RailLocationWithFacing(staging.viewAB, 100.0)
            val cars = List(5) { index ->
                CoreCar(
                    id = "car-$index",
                    bogies = listOf(CoreBogie.create(stagingLocation), CoreBogie.create(stagingLocation)),
                    bogiesOffsets = listOf(6.25, -6.25),
                    length = 17.2,
                    floorHeight = 1.0,
                    connectorLengthAtA = 0.4,
                    connectorLengthAtB = 0.4,
                    world = world,
                    savedExtensionData = emptyMap()
                )
            }
            val train = CoreTrain("asymmetric-turnout", cars, world, emptyMap())
            train.setLocation(RailLocationWithFacing(long.viewAB, 56.0))
            train.commitMaintenancePlacement()
            check(train.tailBogie.location.rail.id == junction.id) {
                "tail bogie=${train.tailBogie.location.rail.id}@${train.tailBogie.location.arcLength}"
            }
            return Fixture(world, previous, junction, train)
        } catch (failure: Throwable) {
            runCatching { closeWorld(world) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun moveByPublishedEpoch(fixture: Fixture, speedMetersPerSecond: Double) =
        fixture.world.physicsCoordinator.let { coordinator ->
            fixture.train.setController(MockTrainController(fixture.train, fixture.world, speedMetersPerSecond))
            coordinator.start()
            coordinator.stepSimulation(50.0).also { ticket ->
                ticket.completion.get(5, TimeUnit.SECONDS)
            }
        }

    private fun assertPublishedPlacement(fixture: Fixture, physicsEpoch: Long) {
        val train = fixture.train
        val frame = train.publishedFrame
        assertEquals(physicsEpoch, frame.physicsEpoch)
        assertEquals(train.headLocation.rail.id, frame.head.railId)
        assertEquals(train.head.location.facing, frame.head.facing)
        assertEquals(
            train.headLocation.arcLengthIn(ViewDirection.AB),
            frame.head.arcLengthInAbMeters,
            Curve.TOLERANCE
        )
        assertEquals(train.tailLocation.rail.id, frame.tail.railId)
        assertEquals(train.tail.location.facing, frame.tail.facing)
        assertEquals(
            train.tailLocation.arcLengthIn(ViewDirection.AB),
            frame.tail.arcLengthInAbMeters,
            Curve.TOLERANCE
        )
        val firstBodySegment = frame.bodyTraversal.first()
        if (frame.tail.railId != firstBodySegment.railId) {
            // Exact rigid placement can land on a rail endpoint. Body traversal omits the
            // zero-length departing segment, so its first positive segment starts at the same joint.
            val tailLocation = train.tail.location
            assertEquals(tailLocation.railView.curve.totalArcLength, tailLocation.arcLength, 1e-9)
            val continuation = requireNotNull(tailLocation.railView.activeRail)
            assertEquals(continuation.rail.id, firstBodySegment.railId)
            assertEquals(continuation.viewDirection, firstBodySegment.facing)
            assertEquals(continuation.getRailLocationAt(0.0).arcLengthIn(ViewDirection.AB),
                firstBodySegment.startArcLengthInAbMeters, 1e-9)
        }
        assertEquals(frame.head.railId, frame.bodyTraversal.last().railId)
    }

    private inline fun withWorld(
        world: CoreWorld = createTestWorld(),
        action: (CoreWorld) -> Unit
    ) = withCleanup({ closeWorld(world) }) { action(world) }

    private inline fun withFixture(action: (Fixture) -> Unit) {
        val fixture = fixtureWithTailAcrossJunction()
        withCleanup({ closeWorld(fixture.world) }) { action(fixture) }
    }

    private inline fun withCleanup(close: () -> Unit, action: () -> Unit) {
        var primaryFailure: Throwable? = null
        try {
            action()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

    private fun closeWorld(vararg worlds: CoreWorld) {
        var failure: Throwable? = null
        worlds.forEach { world ->
            try {
                world.physicsCoordinator.close()
            } catch (caught: Throwable) {
                failure?.addSuppressed(caught) ?: run { failure = caught }
            }
            try {
                (world.tickManager as AutoCloseable).close()
            } catch (caught: Throwable) {
                failure?.addSuppressed(caught) ?: run { failure = caught }
            }
        }
        failure?.let { throw it }
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail =
        world.createRailForTest(straightCurve(from, to))

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }

    private data class Fixture(
        val world: CoreWorld,
        val previous: CoreRail,
        val junction: CoreRail,
        val train: CoreTrain
    )
}
