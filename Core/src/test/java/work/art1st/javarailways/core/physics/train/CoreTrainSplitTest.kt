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
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.TrainSplitSuccessEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.control.ControllerConsistState
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.train.Car
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
import java.util.concurrent.atomic.AtomicLong

class CoreTrainSplitTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `retained controller state installs only after new train initialization succeeds`(failInitialization: Boolean) =
        withConsist { world, _, train ->
            val originalCars = train.cars.toList()
            val controller = object : TrainController(train, world) {
                val consist = ControllerConsistState<List<Car>>(originalCars)
                override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> {
                    assertEquals(originalCars, train.cars)
                    assertSame(originalCars, consist.value)
                    originalCars.forEach { assertSame(train, it.train) }
                    return listOf(consist.prepare(cars.toList()))
                }
            }
            train.setControllerForTest(controller)
            if (failInitialization) {
                train.extensionDataContainer.setExtensionDataAt(2, RecordingSplitExtension(
                    initializationFailure = IllegalStateException("new host initialization failed")
                ))
                executeFailedSplit(train, "car-2", "controller-failed")
                assertSame(originalCars, controller.consist.value)
                assertEquals(originalCars, train.cars)
                originalCars.forEach { assertSame(train, it.train) }
            } else {
                executeSplit(train, "car-2", "controller-separated")
                assertEquals(originalCars.take(2), controller.consist.value)
            }
            assertSame(controller, train.controller)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `split holds epoch admission through decisions preparation and notification`(veto: Boolean) {
        val clock = AtomicLong(1_000_000_000L)
        val coordinator = PhysicsCoordinator(
            workerThreads = 1,
            nanoTime = clock::get,
            mainHeartbeatTimeoutMillis = 1L,
            epochOverloadThresholdMillis = 1_000_000L,
            automaticFallback = false
        )
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            coordinator.close()
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                coordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                    val train = createTrainWithConfig("admission-source", rail, world, List(4) { 10.0 })
                    val observedEpochs = mutableListOf<Long>()
                    fun observeTimerWake() {
                        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
                        coordinator.timerWake()
                        observedEpochs += coordinator.captureCurrentTicket().epoch
                    }
                    train.extensionDataContainer.setExtensionDataAt(2, object : TrainExtension {
                        override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
                        override fun save(): Pair<String, SerializableExtensionData>? = null
                        override fun linkWith(otherTrain: Train) = Unit
                        override fun splitBefore(carId: String, newId: String): TrainExtension {
                            observeTimerWake()
                            return RecordingSplitExtension()
                        }
                    })
                    coordinator.start()
                    val previous = coordinator.stepSimulation(50.0)
                    previous.completion.get(5, TimeUnit.SECONDS)
                    withEvents(
                        onDecision = {
                            observeTimerWake()
                            if (veto) it.reject("fixed unit")
                        },
                        onSuccess = { observeTimerWake() }
                    ) {
                        if (veto) {
                            assertTrue(executeFailedSplit(train, "car-2", "admission-suffix") is IllegalStateException)
                            assertEquals(4, train.length)
                            assertNull(world.getTrain("admission-suffix"))
                        } else {
                            val detached = executeSplit(train, "car-2", "admission-suffix")
                            assertEquals(previous.epoch, train.publishedFrame.physicsEpoch)
                            assertEquals(previous.epoch, detached.publishedFrame.physicsEpoch)
                        }
                    }
                    // Listener assertions belong outside dispatch, whose exception isolation must
                    // not turn a failed assertion into an apparently passing integration test.
                    assertEquals(List(if (veto) 1 else 3) { previous.epoch }, observedEpochs)
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100L))
                    coordinator.timerWake()
                    val next = coordinator.captureCurrentTicket()
                    assertEquals(previous.epoch + 1L, next.completion.get(5, TimeUnit.SECONDS).epoch)
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true"
    )
    fun `plugins select both directions and success observes complete committed results`(
        originalReversed: Boolean,
        retainedReversed: Boolean,
        separatedReversed: Boolean
    ) = withConsist { world, _, train ->
        if (originalReversed) reverse(train)
        val originalCars = train.cars.toList()
        val originalStates = train.publishedFrame.cars
        val originalFrame = train.publishedFrame
        var decision: TrainSplitEvent? = null
        var success: TrainSplitSuccessEvent? = null
        var successCalls = 0
        var completeAtSuccess = false
        withEvents(
            onDecision = {
                decision = it
                it.requestRetainedDirection(retainedReversed)
                it.requestSeparatedDirection(separatedReversed)
                it.requestRetainedDirection(retainedReversed)
                it.requestSeparatedDirection(separatedReversed)
            },
            onSuccess = {
                success = it
                successCalls++
                val detached = it.separatedTrain as CoreTrain
                completeAtSuccess = world.getTrain(train.id) === train &&
                    world.getTrain(detached.id) === detached &&
                    train.publishedFrame.reversed == retainedReversed &&
                    detached.publishedFrame.reversed == separatedReversed &&
                    train.publishedFrame.cars.map { car -> car.carId } == listOf("car-0", "car-1") &&
                    detached.publishedFrame.cars.map { car -> car.carId } == listOf("car-2", "car-3") &&
                    train.cars.all { car -> car.isOwnedByPublicationStream(train.publicationStreamId) } &&
                    detached.cars.all { car -> car.isOwnedByPublicationStream(detached.publicationStreamId) } &&
                    world.drainPublishedTrainFrames().map { frame -> frame.trainId }.toSet() == setOf(train.id, detached.id)
            }
        ) {
            // Exercise the public Train API, without casting at the call site.
            val publicTrain: Train = train
            val future = publicTrain.splitBefore("car-2", "selected-directions")
            withMainThread { world.tickManager.postTick() }
            val detached = future.get(5, TimeUnit.SECONDS) as CoreTrain
            assertEquals(retainedReversed, train.isReversed)
            assertEquals(separatedReversed, detached.isReversed)
            assertEquals(originalCars.take(2), train.cars)
            assertEquals(originalCars.drop(2), detached.cars)
            assertEquals(originalFrame.physicsEpoch, train.publishedFrame.physicsEpoch)
            assertEquals(originalFrame.physicsEpoch, detached.publishedFrame.physicsEpoch)
            assertCarFramesEqual(originalStates, train.publishedFrame.cars + detached.publishedFrame.cars)
            listOf(train, detached).forEach { result ->
                assertEquals(result.isReversed, result.publishedFrame.reversed)
                assertEquals(result.isReversed, result.cars.first().isReversed)
                assertEquals(
                    RailPositionSnapshot.capture(result.head.location, result.head.location.facing),
                    result.publishedFrame.head
                )
                assertEquals(
                    RailPositionSnapshot.capture(result.tail.location, result.tail.location.facing),
                    result.publishedFrame.tail
                )
                assertEquals(result.head.location.facing, result.publishedFrame.bodyTraversal.single().facing)
                assertEquals(result.publishedFrame.tail.arcLengthInAbMeters,
                    result.publishedFrame.bodyTraversal.single().startArcLengthInAbMeters, 1e-9)
                assertEquals(result.publishedFrame.head.arcLengthInAbMeters,
                    result.publishedFrame.bodyTraversal.single().endArcLengthInAbMeters, 1e-9)
                result.cars.forEach { car ->
                    assertEquals(result.head.location.facing, car.bogieA.location.facing)
                    assertEquals(0.0, car.bogieA.direction.distance(originalCars.first().bogieA.direction), 1e-9)
                }
            }
            assertEquals(1, successCalls)
            assertTrue(completeAtSuccess)
            val successEvent = requireNotNull(success)
            assertSame(train, successEvent.train)
            assertSame(detached, successEvent.separatedTrain)
            assertEquals(originalReversed, successEvent.originalReversed)
            assertEquals(originalCars.map { it.id }, successEvent.originalCarIds)
            assertThrows(UnsupportedOperationException::class.java) {
                (decision!!.originalCarIds as MutableList<String>).clear()
            }
            withMainThread {
                assertThrows(IllegalStateException::class.java) { decision!!.requestSeparatedDirection(false) }
                assertThrows(IllegalStateException::class.java) { decision!!.reject("too late") }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `opposing direction requests reject regardless of registration order`(firstReversed: Boolean) =
        withConsist { world, rail, train ->
            val extension = RecordingSplitExtension()
            train.extensionDataContainer.setExtensionDataAt(2, extension)
            val afterExtension = State.capture(world, rail, train)
            var successCalls = 0
            withEvents(onDecision = { it.requestSeparatedDirection(firstReversed) }, onSuccess = { successCalls++ }) {
                withEvents(onDecision = { it.requestSeparatedDirection(!firstReversed) }) {
                    val failure = executeFailedSplit(train, "car-2", "conflict")
                    assertTrue(failure is IllegalStateException)
                    assertTrue(failure.message!!.contains("Conflicting separated"))
                }
            }
            assertEquals(0, extension.splitCalls.get())
            assertEquals(0, successCalls)
            afterExtension.assertUnchanged(world, rail, train)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `veto cannot be cleared by later direction requests`(rejectFirst: Boolean) =
        withConsist { world, rail, train ->
            val before = State.capture(world, rail, train)
            val rejecting: (TrainSplitEvent) -> Unit = { it.reject("fixed unit") }
            val selecting: (TrainSplitEvent) -> Unit = { it.requestRetainedDirection(true) }
            var successCalls = 0
            withEvents(onDecision = if (rejectFirst) rejecting else selecting, onSuccess = { successCalls++ }) {
                withEvents(onDecision = if (rejectFirst) selecting else rejecting) {
                    val failure = executeFailedSplit(train, "car-2", "vetoed")
                    assertTrue(failure.message!!.contains("fixed unit"))
                }
            }
            assertEquals(0, successCalls)
            before.assertUnchanged(world, rail, train)
        }

    @Test
    fun `decision listener failure continues dispatch but leaves original state intact`() =
        withConsist { world, rail, train ->
            val before = State.capture(world, rail, train)
            var laterCalls = 0
            var successCalls = 0
            var decision: TrainSplitEvent? = null
            withEvents(onDecision = {
                it.requestRetainedDirection(true)
                throw IllegalStateException("compatibility check crashed")
            }, onSuccess = { successCalls++ }) {
                withEvents(onDecision = { decision = it; laterCalls++ }) {
                    val failure = executeFailedSplit(train, "car-2", "listener-failure")
                    assertTrue(failure.message!!.contains("listener failed"))
                }
            }
            assertEquals(1, laterCalls)
            assertEquals(0, successCalls)
            withMainThread {
                assertThrows(IllegalStateException::class.java) { decision!!.reject("late") }
            }
            before.assertUnchanged(world, rail, train)
        }

    @Test
    fun `success listener failure does not fail committed split or stop later notification`() =
        withConsist { world, _, train ->
            var laterCalls = 0
            withEvents(onSuccess = { throw IllegalStateException("operating record update crashed") }) {
                withEvents(onSuccess = { laterCalls++ }) {
                    val detached = executeSplit(train, "car-2", "success-despite-listener")
                    assertSame(detached, world.getTrain(detached.id))
                    assertEquals(2, train.length)
                    assertEquals(2, detached.length)
                }
            }
            assertEquals(1, laterCalls)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selected reversals remain unapplied when extension preparation or initialization fails`(
        failDuringInitialization: Boolean
    ) = withConsist { world, rail, train ->
        val expected = IllegalStateException("extension rejected prepare")
        val extension = if (failDuringInitialization) RecordingSplitExtension(initializationFailure = expected)
            else RecordingSplitExtension(splitFailure = expected)
        train.extensionDataContainer.setExtensionDataAt(2, extension)
        val before = State.capture(world, rail, train)
        var successCalls = 0
        withEvents(onDecision = {
            it.requestRetainedDirection(true)
            it.requestSeparatedDirection(true)
        }, onSuccess = { successCalls++ }) {
            assertSame(expected, executeFailedSplit(train, "car-2", "failed-selected-direction"))
        }
        assertEquals(0, successCalls)
        before.assertUnchanged(world, rail, train)
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
    }

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
                assertSame(train, car.train)
                assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId))
                assertFalse(car.isOwnedByPublicationStream(separated.publicationStreamId))
            }
            separated.cars.forEach { car ->
                assertSame(separated, car.train)
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

            world.removeTrain(train)
            train.cars.forEach { assertNull(it.train) }
            separated.cars.forEach { assertSame(separated, it.train) }
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `moving split retains controller and clears detached and direction-change motion`(reverseRetained: Boolean) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try {
            createTestWorld(coordinator)
        } catch (failure: Throwable) {
            runCatching(coordinator::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                coordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                    val train = createTrainWithConfig("moving-source", rail, world, List(4) { 10.0 })
                    train.setControllerForTest(ConstantSpeedController(train, 10.0))
                    coordinator.start()
                    val completedEpoch = coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS).epoch
                    assertEquals(completedEpoch, train.publishedFrame.physicsEpoch)
                    assertEquals(10.0, train.publishedFrame.speedMetersPerSecond)
                    assertTrue(train.publishedFrame.cars.all { it.state.velocityX > 0.0 })

                    lateinit var separated: CoreTrain
                    withEvents(onDecision = { it.requestRetainedDirection(reverseRetained) }) {
                        separated = executeSplit(train, carId = "car-2", newId = "stopped-separated")
                    }

                    assertEquals(completedEpoch, train.publishedFrame.physicsEpoch)
                    assertEquals(completedEpoch, separated.publishedFrame.physicsEpoch)
                    assertEquals(10.0, train.publishedFrame.speedMetersPerSecond)
                    assertEquals(reverseRetained, train.isReversed)
                    assertTrue(train.controller is ConstantSpeedController)
                    train.publishedFrame.cars.forEach { car ->
                        assertEquals(if (reverseRetained) 0.0 else 10.0, car.state.velocityX, 1e-9)
                        car.bogies.forEach { bogie ->
                            assertEquals(if (reverseRetained) 0.0 else 10.0, bogie.velocityX, 1e-9)
                        }
                    }
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
                }
            }
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

            assertTrue(failure is CannotCreateTrainException)
            before.assertUnchanged(world, rail, train)
        }

    private fun executeSplit(train: CoreTrain, carId: String, newId: String): CoreTrain {
        val future = train.splitBefore(carId, newId)
        withMainThread { train.world.tickManager.postTick() }
        return future.get(5, TimeUnit.SECONDS) as CoreTrain
    }

    private fun executeFailedSplit(train: CoreTrain, carId: String, newId: String): Throwable {
        val future = train.splitBefore(carId, newId)
        withMainThread { train.world.tickManager.postTick() }
        return assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }.cause!!
    }

    private fun reverse(train: CoreTrain) {
        withMainThread { train.reverse() }
    }

    private fun <T> withMainThread(action: () -> T): T {
        val owner = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            return action()
        } finally {
            registration.close()
        }
    }

    private fun withEvents(
        onDecision: (TrainSplitEvent) -> Unit = {},
        onSuccess: (TrainSplitSuccessEvent) -> Unit = {},
        action: () -> Unit
    ) {
        MainRailEventBus.register(object : EventListener {
            @HandlesEvent
            fun decide(event: TrainSplitEvent) = onDecision(event)

            @HandlesEvent
            fun succeeded(event: TrainSplitSuccessEvent) = onSuccess(event)
        }).use { action() }
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
        train.setControllerForTest(NoOpController(train))
        action(world, rail, train)
    }

    private fun withWorld(action: (CoreWorld, CoreRail) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                    action(world, rail)
                }
            }
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
        val bogieLocations: List<Any>,
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
            cars.flatMap { it.bogies }.zip(bogieLocations).forEach { (bogie, location) ->
                assertSame(location, bogie.location)
            }
            assertSame(controller, train.controller)
            assertEquals(worldTrains, world.allTrains.toList())
            assertEquals(railEnds, rail.trains.toList())
            assertEquals(extensionSlots.size, train.extensionDataContainer.extensionData.size)
            extensionSlots.forEachIndexed { index, extension ->
                assertSame(extension, train.extensionDataContainer.extensionData[index])
            }
            cars.forEach { car ->
                assertSame(train, car.train)
                assertTrue(car.isOwnedByPublicationStream(train.publicationStreamId))
            }
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
                bogieLocations = train.cars.flatMap { it.bogies }.map { it.location },
                controller = train.controller,
                worldTrains = world.allTrains.toList(),
                railEnds = rail.trains.toList(),
                extensionSlots = train.extensionDataContainer.extensionData.toList()
            )
        }
    }
}
