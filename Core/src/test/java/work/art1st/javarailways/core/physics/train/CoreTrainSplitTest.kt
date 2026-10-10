package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoreTrainSplitTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `same car id produces stable halves while operating views follow direction`(reversed: Boolean) =
        withConsist { world, rail, train ->
            if (reversed) reverse(train)
            val originalStorageOrder = train.cars.toList()
            val originalFrame = train.publishedFrame
            val sourceExtension = RecordingSplitExtension()
            train.extensionDataContainer.setExtensionDataAt(2, sourceExtension)

            val separated = executeSplit(train, carId = "car-2", newId = "separated")

            assertEquals(originalStorageOrder.take(2), train.cars)
            assertEquals(originalStorageOrder.drop(2), separated.cars)
            assertEquals(if (reversed) train.cars.asReversed() else train.cars, train.operatingCars)
            assertEquals(if (reversed) separated.cars.asReversed() else separated.cars, separated.operatingCars)
            assertEquals(reversed, train.isReversed)
            assertEquals(reversed, separated.isReversed)
            assertTrue(train.controller is NoOpController)
            assertNull(separated.controller)
            assertEquals(setOf(train, separated), world.allTrains.toSet())
            assertEquals(4, rail.trains.size)
            assertEquals(setOf(train, separated), rail.trains.map { it.train }.toSet())
            assertEquals(train.cars.map { it.id }, train.publishedFrame.cars.map { it.carId })
            assertEquals(separated.cars.map { it.id }, separated.publishedFrame.cars.map { it.carId })
            assertEquals(originalFrame.revision + 1L, train.publishedFrame.revision)
            assertEquals(originalFrame.physicsEpoch, train.publishedFrame.physicsEpoch)
            assertEquals(
                RailPositionSnapshot.capture(train.head.location, train.head.location.facing),
                train.publishedFrame.head
            )
            assertEquals(
                RailPositionSnapshot.capture(train.tail.location, train.tail.location.facing),
                train.publishedFrame.tail
            )
            assertEquals(train.occupiedRailIntervals, train.publishedFrame.occupiedIntervals)
            assertEquals(separated.occupiedRailIntervals, separated.publishedFrame.occupiedIntervals)
            assertTrue(train.publishedFrame.bodyTraversal.isNotEmpty())
            assertTrue(separated.publishedFrame.bodyTraversal.isNotEmpty())
            assertTrue(train.publishedFrame.sweptSpans.isNotEmpty())
            assertTrue(separated.publishedFrame.sweptSpans.isNotEmpty())
            train.cars.forEach { car ->
                assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId))
                assertFalse(car.isOwnedByPublicationStream(separated.publicationStreamId))
            }
            separated.cars.forEach { car ->
                assertFalse(car.isOwnedByPublicationStream(train.publicationStreamId))
                assertTrue(car.isOwnedByPublicationStream(separated.publicationStreamId))
            }
            assertSame(sourceExtension, train.extensionDataContainer.extensionData[2])
            val installed = separated.extensionDataContainer.extensionData[2]
            assertSame(sourceExtension.splitResult, installed)
            assertSame(separated, sourceExtension.splitResult.initializedHost)
            assertEquals(1, sourceExtension.splitCalls.get())
            assertEquals("car-2", sourceExtension.observedCarId)
            assertEquals("separated", sourceExtension.observedNewId)
            val mailboxFrames = world.drainPublishedTrainFrames().associateBy { it.trainId }
            assertEquals(setOf(train.id, separated.id), mailboxFrames.keys)
            assertSame(train.publishedFrame, mailboxFrames[train.id])
            assertSame(separated.publishedFrame, mailboxFrames[separated.id])
        }

    @Test
    fun `last stable car can be split into a one-car train`() = withConsist { world, rail, train ->
        val originalStorageOrder = train.cars.toList()

        val separated = executeSplit(train, carId = originalStorageOrder.last().id, newId = "last")

        assertEquals(originalStorageOrder.dropLast(1), train.cars)
        assertEquals(listOf(originalStorageOrder.last()), separated.cars)
        assertEquals(2, world.allTrains.size)
        assertEquals(4, rail.trains.size)
        assertEquals(1, separated.length)
    }

    @Test
    fun `moving split publishes one completed epoch and clears detached motion`() {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = createTestWorld(coordinator)
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = createTrainWithConfig("moving-source", rail, world, List(4) { 10.0 })
            train.setController(ConstantSpeedController(train, 10.0))
            coordinator.start()
            val completedEpoch = coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS).epoch
            assertEquals(completedEpoch, train.publishedFrame.physicsEpoch)
            assertEquals(10.0, train.publishedFrame.speedMetersPerSecond)
            assertTrue(train.publishedFrame.cars.all { it.state.velocityX > 0.0 })

            val separated = executeSplit(train, carId = "car-2", newId = "stopped-separated")

            assertEquals(completedEpoch, train.publishedFrame.physicsEpoch)
            assertEquals(completedEpoch, separated.publishedFrame.physicsEpoch)
            assertEquals(10.0, train.publishedFrame.speedMetersPerSecond)
            assertEquals(0.0, separated.publishedFrame.speedMetersPerSecond)
            separated.publishedFrame.cars.forEach { car ->
                assertEquals(0.0, car.state.velocityX)
                assertEquals(0.0, car.state.velocityY)
                assertEquals(0.0, car.state.velocityZ)
                car.bogies.forEach { bogie ->
                    assertEquals(0.0, bogie.velocityX)
                    assertEquals(0.0, bogie.velocityY)
                    assertEquals(0.0, bogie.velocityZ)
                }
            }
            assertEquals(
                setOf(train.id, separated.id),
                world.drainPublishedTrainFrames().mapTo(linkedSetOf()) { it.trainId }
            )
        } finally {
            coordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "car-0"])
    fun `unknown or first stable car has no side effects`(carId: String) = withConsist { world, rail, train ->
        val before = State.capture(world, rail, train)

        val failure = executeFailedSplit(train, carId, "invalid-$carId")

        assertTrue(failure is CannotCreateTrainException)
        before.assertUnchanged(world, rail, train)
    }

    @Test
    fun `duplicate target car identity is rejected without choosing an ambiguous boundary`() =
        withWorld { world, rail ->
            val cars = listOf(
                createCar(world, rail, "car-0", headArc = 80.0, tailArc = 75.0),
                createCar(world, rail, "duplicate", headArc = 70.0, tailArc = 65.0),
                createCar(world, rail, "duplicate", headArc = 60.0, tailArc = 55.0)
            )
            val train = CoreTrain("duplicate-car-source", cars, world, emptyMap())
            val before = State.capture(world, rail, train)

            val failure = executeFailedSplit(train, "duplicate", "duplicate-car-result")

            assertTrue(failure is CannotCreateTrainException)
            before.assertUnchanged(world, rail, train)
        }

    @Test
    fun `duplicate identity is rejected before extension preparation`() = withConsist { world, rail, train ->
        val extension = RecordingSplitExtension()
        train.extensionDataContainer.setExtensionDataAt(2, extension)
        val before = State.capture(world, rail, train)

        val failure = executeFailedSplit(train, "car-2", train.id)

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, extension.splitCalls.get())
        before.assertUnchanged(world, rail, train)
    }

    @Test
    fun `extension preparation failure preserves original consist and ownership`() =
        withConsist { world, rail, train ->
            val first = RecordingSplitExtension()
            val expected = IllegalStateException("split preparation failed")
            val failing = RecordingSplitExtension(splitFailure = expected)
            train.extensionDataContainer.setExtensionDataAt(1, first)
            train.extensionDataContainer.setExtensionDataAt(3, failing)
            val before = State.capture(world, rail, train)

            val failure = executeFailedSplit(train, "car-2", "extension-failure")

            assertSame(expected, failure)
            assertEquals(1, first.splitCalls.get())
            assertEquals(1, failing.splitCalls.get())
            before.assertUnchanged(world, rail, train)
        }

    @Test
    fun `new extension initialization failure removes prepared train registrations`() =
        withConsist { world, rail, train ->
            val expected = IllegalStateException("new extension initialization failed")
            val extension = RecordingSplitExtension(initializationFailure = expected)
            train.extensionDataContainer.setExtensionDataAt(2, extension)
            val before = State.capture(world, rail, train)

            val failure = executeFailedSplit(train, "car-2", "install-failure")

            assertSame(expected, failure)
            assertEquals(1, extension.splitCalls.get())
            before.assertUnchanged(world, rail, train)
        }

    @Test
    fun `endpoint preparation failure does not publish either half`() = withWorld { world, rail ->
        val cars = listOf(
            createCar(world, rail, "car-0", headArc = 80.0, tailArc = 1.0),
            createCar(world, rail, "car-1", headArc = 65.0, tailArc = 60.0),
            createCar(world, rail, "car-2", headArc = 50.0, tailArc = 45.0)
        )
        val train = CoreTrain("endpoint-source", cars, world, emptyMap())
        val before = State.capture(world, rail, train)

        val failure = executeFailedSplit(train, "car-1", "endpoint-failure")

        assertTrue(failure is CannotCreateTrainException)
        before.assertUnchanged(world, rail, train)
    }

    @Test
    fun `separated half must have a connected body before either train is published`() =
        withWorld { world, rail ->
            val cars = listOf(
                createCar(world, rail, "car-0", headArc = 80.0, tailArc = 75.0),
                createCar(world, rail, "car-1", headArc = 30.0, tailArc = 25.0),
                createCar(world, rail, "car-2", headArc = 70.0, tailArc = 65.0)
            )
            val train = CoreTrain("separated-geometry-source", cars, world, emptyMap())
            val before = State.capture(world, rail, train)

            val failure = executeFailedSplit(train, "car-1", "separated-geometry-failure")

            assertTrue(failure is IllegalStateException)
            before.assertUnchanged(world, rail, train)
        }

    private fun executeSplit(train: CoreTrain, carId: String, newId: String): CoreTrain {
        val future = train.splitBefore(carId, newId)
        train.world.tickManager.postTick()
        return future.get() as CoreTrain
    }

    private fun executeFailedSplit(train: CoreTrain, carId: String, newId: String): Throwable {
        val future = train.splitBefore(carId, newId)
        train.world.tickManager.postTick()
        return assertThrows(ExecutionException::class.java) { future.get() }.cause!!
    }

    private fun reverse(train: CoreTrain) {
        val owner = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            train.reverse()
        } finally {
            registration.close()
        }
    }

    private fun createCar(
        world: CoreWorld,
        rail: CoreRail,
        id: String,
        headArc: Double,
        tailArc: Double
    ): CoreCar = CoreCar(
        id = id,
        bogies = listOf(
            CoreBogie.create(rail.viewAB.getRailLocationAt(headArc)),
            CoreBogie.create(rail.viewAB.getRailLocationAt(tailArc))
        ),
        bogiesOffsets = listOf(2.5, -2.5),
        length = 10.0,
        floorHeight = 0.0,
        connectorLengthAtA = 0.0,
        connectorLengthAtB = 0.0,
        world = world,
        savedExtensionData = emptyMap()
    )

    private fun withConsist(action: (CoreWorld, CoreRail, CoreTrain) -> Unit) = withWorld { world, rail ->
        val train = createTrainWithConfig("source", rail, world, List(4) { 10.0 })
        train.setController(NoOpController(train))
        action(world, rail, train)
    }

    private fun withWorld(action: (CoreWorld, CoreRail) -> Unit) {
        val world = createTestWorld()
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            action(world, rail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private class RecordingSplitExtension(
        private val splitFailure: Throwable? = null,
        initializationFailure: Throwable? = null
    ) : TrainExtension {
        val splitCalls = AtomicInteger()
        var observedCarId: String? = null
        var observedNewId: String? = null
        var initializedHost: Train? = null
        val splitResult = if (splitFailure == null) {
            RecordingSplitExtension(
                splitFailure = IllegalStateException("split result must not split again"),
                initializationFailure = initializationFailure
            )
        } else {
            this
        }
        private val initializeFailure = initializationFailure

        override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
            initializedHost = host
            initializeFailure?.let { throw it }
        }

        override fun save(): Pair<String, SerializableExtensionData>? = null

        override fun splitBefore(carId: String, newId: String): TrainExtension {
            splitCalls.incrementAndGet()
            observedCarId = carId
            observedNewId = newId
            splitFailure?.let { throw it }
            return splitResult
        }

        override fun linkWith(otherTrain: Train) = Unit
    }

    private class NoOpController(train: CoreTrain) : TrainController(train, train.world) {
        override fun tick(deltaT: Double) = Unit
    }

    private class ConstantSpeedController(
        train: CoreTrain,
        private val commandedSpeed: Double
    ) : TrainController(train, train.world) {
        override fun tick(deltaT: Double) {
            speed = commandedSpeed
            moveControlledTrain(commandedSpeed * deltaT * 0.001, commandedSpeed)
        }
    }

    private data class State(
        val cars: List<CoreCar>,
        val head: TrainEnd,
        val tail: TrainEnd,
        val headLocation: Any,
        val tailLocation: Any,
        val frame: Any,
        val reversed: Boolean,
        val carDirections: List<Boolean>,
        val controller: Any?,
        val worldTrains: List<CoreTrain>,
        val railEnds: List<TrainEnd>,
        val extensionSlots: List<TrainExtension?>
    ) {
        fun assertUnchanged(world: CoreWorld, rail: CoreRail, train: CoreTrain) {
            assertEquals(cars, train.cars)
            cars.zip(train.cars).forEach { (expected, actual) -> assertSame(expected, actual) }
            assertSame(head, train.head)
            assertSame(tail, train.tail)
            assertSame(headLocation, train.head.location)
            assertSame(tailLocation, train.tail.location)
            assertSame(frame, train.publishedFrame)
            assertEquals(reversed, train.isReversed)
            assertEquals(carDirections, train.cars.map { it.isReversed })
            assertSame(controller, train.controller)
            assertEquals(worldTrains, world.allTrains.toList())
            assertEquals(railEnds, rail.trains.toList())
            assertEquals(extensionSlots.size, train.extensionDataContainer.extensionData.size)
            extensionSlots.forEachIndexed { index, extension ->
                assertSame(extension, train.extensionDataContainer.extensionData[index])
            }
            cars.forEach { car -> assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId)) }
        }

        companion object {
            fun capture(world: CoreWorld, rail: CoreRail, train: CoreTrain): State = State(
                cars = train.cars.toList(),
                head = train.head,
                tail = train.tail,
                headLocation = train.head.location,
                tailLocation = train.tail.location,
                frame = train.publishedFrame,
                reversed = train.isReversed,
                carDirections = train.cars.map { it.isReversed },
                controller = train.controller,
                worldTrains = world.allTrains.toList(),
                railEnds = rail.trains.toList(),
                extensionSlots = train.extensionDataContainer.extensionData.toList()
            )
        }
    }
}
