package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataContainer
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoreTrainConstructionTest {
    @Test
    fun `extension initialization can read detached composition before the initial frame exists`() = withWorld { world, rail ->
        val car = createCar(world, rail, 40.0, 30.0)
        var observed: work.art1st.javarailways.core.api.physics.train.TrainComposition? = null
        val extensions = ExtensionDataContainer<TrainExtension>()
        extensions.setExtensionDataAt(0, object : TrainExtension {
            override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
                observed = host.composition
                assertEquals(listOf(car.id), observed!!.units.single().carIds)
            }
            override fun save(): Pair<String, SerializableExtensionData>? = null
            override fun splitBefore(carId: String, newId: String): TrainExtension = error("unused")
            override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
        })
        val train = world.onOwnerForTest { CoreTrain("bootstrap-unit", listOf(car), world, emptyMap(), extensionDataContainer = extensions) }
        assertEquals(observed, train.composition)
        assertEquals(observed, train.publishedFrame.composition)
    }

    @Test
    fun `default unit is stable across publication reversal and save`() = withWorld { world, rail ->
        val train = createTrainWithConfig("default-unit", rail, world, listOf(10.0, 10.0))
        val original = train.composition
        assertEquals(1, original.units.size)
        assertEquals(train.id, original.units.single().unitId)
        assertEquals(train.cars.map { it.id }, original.units.single().carIds)
        val frame = train.publishedFrame
        world.onOwnerForTest { train.publishMetadataFrame() }
        assertSame(original, train.composition, "unchanged composition is reused by each published frame")
        world.onOwnerForTest { train.reverse() }
        assertSame(original, train.composition)
        assertEquals(original, frame.composition)
        assertEquals(original.units.single().unitId, train.save().composition.single().unitId)
        assertEquals(original.units.single().carIds, train.save().composition.single().carIds)
    }

    @Test
    fun `restoring an unmarked train initializes its unit from the existing saved train identity`() = withWorld { world, rail ->
        val original = createTrainWithConfig("existing-identity", rail, world, listOf(10.0))
        val saved = original.save().copy(composition = emptyList())
        world.onOwnerForTest { original.destroy() }.toCompletableFuture().get(5, TimeUnit.SECONDS)
        val restored = world.onOwnerForTest { saved.load(world) }
        assertEquals(saved.id, restored.composition.units.single().unitId)
        assertEquals(saved.cars.map { it.id }, restored.composition.units.single().carIds)
        assertEquals(saved.id, restored.save().composition.single().unitId)
    }

    @Test
    fun `explicit duplicate unit ownership rejects creation without replacing existing composition`() = withWorld { world, rail ->
        val first = createTrainWithConfig("existing-unit", rail, world, listOf(10.0))
        val composition = first.composition
        assertThrows(IllegalArgumentException::class.java) {
            createTrainWithConfig("duplicate-unit", rail, world, listOf(10.0), composition = composition)
        }
        assertSame(composition, first.composition)
        assertNull(world.getTrain("duplicate-unit"))
        assertEquals(1, world.allTrains.size)
    }

    @Test
    fun `ordinary train creation preflight rejects an identity retained by another consist`() = withWorld { world, rail ->
        val memberIds = listOf("joined-car-0", "joined-car-1")
        val composition = work.art1st.javarailways.core.api.physics.train.TrainComposition.create(listOf(
            work.art1st.javarailways.core.api.physics.train.TrainUnitComposition("S100-01", listOf(memberIds[0])),
            work.art1st.javarailways.core.api.physics.train.TrainUnitComposition("S100-02", listOf(memberIds[1]))
        ), memberIds)
        val existing = createTrainWithConfig("Joined", rail, world, listOf(10.0, 10.0),
            composition = composition, carIdPrefix = "joined-car-")
        val before = existing.publishedFrame
        assertNull(world.getTrain("S100-02"))
        assertThrows(IllegalStateException::class.java) { world.validateDefaultTrainCreationIdentity("New") }
        world.onOwnerForTest {
            world.validateDefaultTrainCreationIdentity("New")
            assertThrows(IllegalArgumentException::class.java) {
                world.validateDefaultTrainCreationIdentity("S100-02")
            }
            // Direct Core creation remains authoritative even when callers skip the preflight.
            assertThrows(IllegalArgumentException::class.java) {
                CoreTrain.createOwned(world) {
                    val location = RailLocationWithFacing(rail.viewAB, 100.0)
                    val car = car("new-car-0", bogie(location), bogie(location), length = 10.0,
                        connectorLengthAtA = 0.0, connectorLengthAtB = 0.0)
                    train("S100-02", listOf(car))
                }
            }
        }
        assertNull(world.getTrain("S100-02"))
        assertEquals(listOf(existing), world.allTrains.toList())
        assertSame(before, existing.publishedFrame)
        assertSame(composition.units[1], existing.composition.units[1])
        existing.cars.forEach { assertSame(existing, it.train) }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `split at permanent unit boundary preserves both identities and preparation failure is atomic`(fail: Boolean) = withWorld { world, rail ->
        val composition = work.art1st.javarailways.core.api.physics.train.TrainComposition.create(listOf(
            work.art1st.javarailways.core.api.physics.train.TrainUnitComposition("front", listOf("car-0", "car-1")),
            work.art1st.javarailways.core.api.physics.train.TrainUnitComposition("rear", listOf("car-2"))
        ), listOf("car-0", "car-1", "car-2"))
        val train = createTrainWithConfig("boundary-source", rail, world, listOf(10.0, 10.0, 10.0), composition = composition)
        val old = train.composition
        val initialized = AtomicInteger()
        train.extensionDataContainer.setExtensionDataAt(3, object : TrainExtension {
            override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
            override fun save(): Pair<String, SerializableExtensionData>? = null
            override fun splitBefore(carId: String, newId: String): TrainExtension = object : TrainExtension {
                override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
                    initialized.incrementAndGet()
                    val visibleUnits = world.allTrains.flatMap { it.composition.units }
                    assertEquals(listOf("front", "rear"), visibleUnits.map { it.unitId })
                    assertEquals(visibleUnits.size, visibleUnits.map { it.unitId }.distinct().size,
                        "a read-only fixed unit provider must never see two committed owners during initialization")
                    assertEquals(listOf("rear"), host.composition.units.map { it.unitId })
                    assertSame(host, world.getTrain(newId), "staging still reserves the exact train identity")
                    assertEquals(listOf(train), world.allTrains.toList())
                    if (fail) error("new host initialization failed")
                }
                override fun save(): Pair<String, SerializableExtensionData>? = null
                override fun splitBefore(carId: String, newId: String): TrainExtension = error("unused")
                override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
            }
            override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
        })
        val split = train.splitBefore("car-2", "boundary-separated")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        assertEquals(1, initialized.get())
        if (fail) {
            assertThrows(java.util.concurrent.ExecutionException::class.java) { split.get(5, TimeUnit.SECONDS) }
            assertSame(old, train.composition)
            assertNull(world.getTrain("boundary-separated"))
            assertEquals(listOf("car-0", "car-1", "car-2"), train.cars.map { it.id })
        } else {
            val separated = split.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("front"), train.composition.units.map { it.unitId })
            assertEquals(listOf("rear"), separated.composition.units.map { it.unitId })
            assertEquals(listOf("front", "rear"), old.units.map { it.unitId })
            assertEquals(2, world.allTrains.size)
            assertEquals(setOf("front", "rear"), world.allTrains.flatMap { it.composition.units }.map { it.unitId }.toSet())
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `internal unit split partitions canonical membership and fails without modifying the source`(canonicalReversed: Boolean, fail: Boolean) = withWorld { world, rail ->
        val canonical = listOf("car-0", "car-1", "car-2").let { if (canonicalReversed) it.asReversed() else it }
        val composition = work.art1st.javarailways.core.api.physics.train.TrainComposition.single(canonical, "internal-source")
        val train = createTrainWithConfig("internal-source", rail, world, listOf(10.0, 10.0, 10.0), composition = composition)
        val original = train.composition
        if (fail) train.extensionDataContainer.setExtensionDataAt(3, object : TrainExtension {
            override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
            override fun save(): Pair<String, SerializableExtensionData>? = null
            override fun splitBefore(carId: String, newId: String): TrainExtension = error("prepare failed")
            override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
        })
        val split = train.splitBefore("car-1", "internal-separated")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        if (fail) {
            assertThrows(java.util.concurrent.ExecutionException::class.java) { split.get(5, TimeUnit.SECONDS) }
            assertSame(original, train.composition)
            assertEquals(listOf("car-0", "car-1", "car-2"), train.cars.map { it.id })
            assertNull(world.getTrain("internal-separated"))
        } else {
            val separated = split.get(5, TimeUnit.SECONDS)
            assertEquals("internal-source", train.composition.units.single().unitId)
            assertEquals(listOf("car-0"), train.composition.units.single().carIds)
            assertEquals("internal-separated", separated.composition.units.single().unitId)
            assertEquals(canonical.filter { it != "car-0" }, separated.composition.units.single().carIds)
            assertEquals(canonicalReversed, separated.composition.units.single().isReversedIn(listOf("car-1", "car-2")))
            assertEquals(original, composition)
        }
    }

    @Test
    fun `staged internal split reserves its new unit identity against reentrant explicit creation`() = withWorld { world, rail ->
        val source = createTrainWithConfig("reserved-source", rail, world, listOf(10.0, 10.0))
        val checked = AtomicInteger()
        source.extensionDataContainer.setExtensionDataAt(3, object : TrainExtension {
            override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
            override fun save(): Pair<String, SerializableExtensionData>? = null
            override fun splitBefore(carId: String, newId: String): TrainExtension = object : TrainExtension {
                override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
                    assertEquals(listOf(source), world.allTrains.toList())
                    assertEquals(newId, host.composition.units.single().unitId)
                    assertThrows(IllegalArgumentException::class.java) {
                        world.validateDefaultTrainCreationIdentity(newId)
                    }
                    val intruderComposition = work.art1st.javarailways.core.api.physics.train.TrainComposition.single(listOf("intruder-0"), newId)
                    assertThrows(IllegalArgumentException::class.java) {
                        CoreTrain.createOwned(world) {
                            val location = RailLocationWithFacing(rail.viewAB, 100.0)
                            val car = car("intruder-0", bogie(location), bogie(location), length = 10.0,
                                connectorLengthAtA = 0.0, connectorLengthAtB = 0.0)
                            train("intruder", listOf(car), composition = intruderComposition)
                        }
                    }
                    assertNull(world.getTrain("intruder"))
                    checked.incrementAndGet()
                }
                override fun save(): Pair<String, SerializableExtensionData>? = null
                override fun splitBefore(carId: String, newId: String): TrainExtension = error("unused")
                override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
            }
            override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("unused")
        })
        val split = source.splitBefore("car-1", "reserved-suffix")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        val suffix = split.get(5, TimeUnit.SECONDS)
        assertEquals(1, checked.get())
        assertEquals("reserved-suffix", suffix.composition.units.single().unitId)
        assertEquals(2, world.allTrains.size)
    }

    @Test
    fun `internal split rejects a new unit identity already owned by another train`() = withWorld { world, rail ->
        val source = createTrainWithConfig("source", rail, world, listOf(10.0, 10.0))
        val otherCars = listOf("other-0")
        val otherComposition = work.art1st.javarailways.core.api.physics.train.TrainComposition.single(otherCars, "new-unit-collision")
        val other = createTrainWithConfig("other-train", rail, world, listOf(10.0), composition = otherComposition, carIdPrefix = "other-")
        val before = source.composition
        val split = source.splitBefore("car-1", "new-unit-collision")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        assertThrows(java.util.concurrent.ExecutionException::class.java) { split.get(5, TimeUnit.SECONDS) }
        assertSame(before, source.composition)
        assertSame(otherComposition.units.single(), other.composition.units.single())
        assertNull(world.getTrain("new-unit-collision"))
    }

    @Test
    fun `destroy detaches immediately on owner and waits for every child cleanup once`() = withWorld { world, rail ->
        val train = createTrainWithConfig("async-cleanup", rail, world, listOf(10.0))
        val pending = java.util.concurrent.CompletableFuture<Void>()
        val calls = AtomicInteger()
        train.cars.single().extensionDataContainer.extensionData.add(object : RenderableExtension<work.art1st.javarailways.core.api.physics.train.Car> {
            override fun initialize(host: work.art1st.javarailways.core.api.physics.train.Car, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
            override fun save(): Pair<String, SerializableExtensionData>? = null
            override fun commit() = Unit
            override fun destroy(): java.util.concurrent.CompletionStage<Void> {
                calls.incrementAndGet()
                return pending
            }
        })
        val result = world.onOwnerForTest { train.destroy() }
        assertNull(world.getTrain(train.id))
        assertEquals(RailObjectLifecycleState.REMOVED, train.head.lifecycleState)
        assertEquals(RailObjectLifecycleState.REMOVED, train.tail.lifecycleState)
        assertTrue(!result.toCompletableFuture().isDone)
        assertSame(result, train.destroy())
        assertEquals(1, calls.get())
        pending.complete(null)
        result.toCompletableFuture().get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `creation scope rejects borrowed car without destroying its owner`() = withWorld { world, rail ->
        val original = createTrainWithConfig("borrowed-owner", rail, world, listOf(10.0))
        val destroyed = AtomicInteger()
        observeDestruction(original.cars.single(), destroyed)
        world.onOwnerForTest {
            assertThrows(IllegalArgumentException::class.java) {
                CoreTrain.createOwned(world) {
                    train("rejected", original.cars)
                }
            }
        }
        assertSame(original, world.getTrain(original.id))
        assertSame(original, original.cars.single().train)
        assertEquals(0, destroyed.get())
    }

    @Test
    fun `creation scope rejects borrowed bogies without adopting them`() = withWorld { world, rail ->
        val original = createTrainWithConfig("bogie-owner", rail, world, listOf(10.0))
        val borrowed = original.cars.single()
        world.onOwnerForTest {
            assertThrows(IllegalArgumentException::class.java) {
                CoreTrain.createOwned(world) {
                    car("rejected", borrowed.bogies[0], borrowed.bogies[1])
                }
            }
        }
        assertSame(original, borrowed.train)
        assertSame(original, world.getTrain(original.id))
    }

    @Test
    fun `unpublished creation cleans new car and closes escaped scope`() = withWorld { world, rail ->
        val source = createTrainWithConfig("location-source", rail, world, listOf(10.0))
        val location = source.cars.single().bogies.first().location
        val destroyed = AtomicInteger()
        lateinit var escaped: TrainCreation
        world.onOwnerForTest {
            assertThrows(IllegalStateException::class.java) {
                CoreTrain.createOwned(world) {
                    escaped = this
                    val created = car("new-car", bogie(location), bogie(location))
                    observeDestruction(created, destroyed)
                    // Returning without publishing is a failed creation, not ownership transfer.
                }
            }
            assertThrows(IllegalStateException::class.java) { escaped.bogie(location) }
        }
        assertEquals(3, destroyed.get(), "one car and its two newly created bogies must be cleaned")
        assertSame(source, world.getTrain(source.id))
    }

    @ParameterizedTest(name = "id={0}, name={1}, saved load={2}")
    @CsvSource(
        "original-id, Other, false",
        "other-id, Local, false",
        "other-id, original-id, false",
        "Local, Other, false",
        "original-id, Other, true",
        "other-id, Local, true",
        "other-id, original-id, true",
        "Local, Other, true"
    )
    fun `identity rejection preserves the existing train and exact rail endpoints`(
        rejectedId: String,
        rejectedName: String,
        savedLoad: Boolean
    ) = withWorld { world, rail ->
        val original = createTrainWithConfig("original-id", rail, world, listOf(10.0))
        IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames {
                world.renameTrain(original, "Local")
            }
        }
        val controller = CountingController(original)
        original.setControllerForTest(controller)
        val destroys = AtomicInteger()
        observeDestruction(original.cars.single(), destroys)
        val originalFrame = original.publishedFrame
        val originalCar = original.cars.single()
        val originalBogieLocations = originalCar.bogies.map { it.location }
        world.drainPublishedTrainFrames()
        world.publishCurrentTrainFrame(original.id)
        val saved = original.save().copy(id = rejectedId, name = rejectedName)

        assertThrows(IllegalArgumentException::class.java) {
            if (savedLoad) {
                world.onOwnerForTest { saved.load(world) }
            } else {
                world.onOwnerForTest { CoreTrain(rejectedId, original.cars, world, emptyMap(), rejectedName) }
            }
        }

        assertSame(original, world.getTrain("original-id"))
        assertSame(original, world.getTrain("Local"))
        listOf(rejectedId, rejectedName).filter { it != "original-id" && it != "Local" }
            .forEach { assertNull(world.getTrain(it)) }
        assertEquals(listOf(original), world.allTrains.toList())
        assertRailEnds(rail, original.head, original.tail)
        assertSame(originalFrame, original.publishedFrame)
        assertSame(originalFrame, world.drainPublishedTrainFrames().single())
        assertSame(controller, original.controller)
        assertSame(originalCar, original.cars.single())
        assertSame(original, originalCar.train)
        assertEquals(originalBogieLocations, originalCar.bogies.map { it.location })
        assertEquals(0, destroys.get())

        step(world)
        assertEquals(1, controller.ticks.get(), "the original controller must remain registered")
    }

    @ParameterizedTest(name = "unplaceable {0} endpoint")
    @CsvSource("HEAD, 119.0, 115.0, 119.0, 2.5", "TAIL, 4.0, 0.5, 0.5, -2.5")
    fun `unplaceable endpoint does not register a head or take ownership of cars`(
        endpoint: String,
        headBogieArc: Double,
        tailBogieArc: Double,
        expectedAnchorArc: Double,
        expectedEndOffset: Double
    ) = withWorld { world, rail ->
        val car = createCar(world, rail, headBogieArc, tailBogieArc)
        val destroys = AtomicInteger()
        observeDestruction(car, destroys)
        val bogieLocations = car.bogies.map { it.location }
        assertNull(car.train)

        val failure = assertThrows(CannotCreateTrainException::class.java) {
            world.onOwnerForTest { CoreTrain("unplaceable", listOf(car), world, emptyMap()) }
        }
        assertEquals(
            "Cannot place train $endpoint endpoint: train=unplaceable, anchorRail=${rail.id}, " +
                "anchorFacing=AB, anchorArcMeters=$expectedAnchorArc, endOffsetMeters=$expectedEndOffset",
            failure.message
        )

        assertRailEnds(rail)
        assertTrue(world.allTrains.isEmpty())
        assertNull(world.getTrain("unplaceable"))
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        assertEquals(bogieLocations, car.bogies.map { it.location })
        assertEquals(0, destroys.get())
        assertNull(car.train)
    }

    @Test
    fun `extension initialization failure removes owned registrations and permits a successful retry`() =
        withWorld { world, rail ->
            val original = createTrainWithConfig("original-id", rail, world, listOf(10.0))
            val originalController = CountingController(original)
            original.setControllerForTest(originalController)
            val originalFrame = original.publishedFrame
            val car = createCar(world, rail, headBogieArc = 72.5, tailBogieArc = 67.5)
            val destroys = AtomicInteger()
            observeDestruction(car, destroys)
            val bogieLocations = car.bogies.map { it.location }
            assertNull(car.train)
            val failure = IllegalStateException("extension initialization failed")
            lateinit var failedTrain: CoreTrain
            lateinit var failedController: CountingController
            val extensions = ExtensionDataContainer<TrainExtension>().apply {
                extensionData.add(object : TrainExtension {
                    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) {
                        failedTrain = host as CoreTrain
                        assertSame(host, world.getTrain("Failed"))
                        assertRailEnds(rail, original.head, original.tail, failedTrain.head, failedTrain.tail)
                        failedController = CountingController(failedTrain)
                        failedTrain.setController(failedController)
                        throw failure
                    }

                    override fun save(): Pair<String, SerializableExtensionData>? = null
                    override fun splitBefore(carId: String, newId: String): TrainExtension = error("not used")
                    override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension = error("not used")
                })
            }
            world.publishCurrentTrainFrame(original.id)

            val thrown = assertThrows(IllegalStateException::class.java) {
                world.onOwnerForTest {
                    CoreTrain("failed-id", listOf(car), world, emptyMap(), "Failed", extensions)
                }
            }

            assertSame(failure, thrown)
            assertNull(world.getTrain("failed-id"))
            assertNull(world.getTrain("Failed"))
            assertEquals(listOf(original), world.allTrains.toList())
            assertRailEnds(rail, original.head, original.tail)
            assertEquals(RailObjectLifecycleState.REMOVED, failedTrain.head.lifecycleState)
            assertEquals(RailObjectLifecycleState.REMOVED, failedTrain.tail.lifecycleState)
            assertNull(failedTrain.controller)
            assertSame(originalController, original.controller)
            assertSame(originalFrame, original.publishedFrame)
            assertSame(originalFrame, world.drainPublishedTrainFrames().single())
            assertEquals(bogieLocations, car.bogies.map { it.location })
            assertEquals(0, destroys.get(), "a failed constructor must leave caller-owned cars intact")
            assertNull(car.train)

            val retry = world.onOwnerForTest { CoreTrain("failed-id", listOf(car), world, emptyMap(), "Failed") }
            val retryController = CountingController(retry)
            retry.setControllerForTest(retryController)
            assertSame(retry, world.getTrain("failed-id"))
            assertSame(retry, world.getTrain("Failed"))
            assertSame(car, retry.cars.single())
            assertSame(retry, car.train)
            assertEquals("failed-id", retry.publishedFrame.trainId)
            assertRailEnds(rail, original.head, original.tail, retry.head, retry.tail)
            step(world)
            assertEquals(0, failedController.ticks.get())
            assertEquals(1, originalController.ticks.get())
            assertEquals(1, retryController.ticks.get())
        }

    private fun createCar(world: CoreWorld, rail: CoreRail, headBogieArc: Double, tailBogieArc: Double) =
        CoreCar(
            id = "caller-owned",
            bogies = listOf(
                CoreBogie.create(rail.viewAB.getRailLocationAt(headBogieArc)),
                CoreBogie.create(rail.viewAB.getRailLocationAt(tailBogieArc))
            ),
            bogiesOffsets = listOf(2.5, -2.5),
            length = 10.0,
            floorHeight = 0.0,
            connectorLengthAtA = 0.0,
            connectorLengthAtB = 0.0,
            world = world,
            savedExtensionData = emptyMap()
        )

    private fun assertRailEnds(rail: CoreRail, vararg expected: TrainEnd) {
        val actual = rail.trains.toList()
        assertEquals(expected.size, actual.size)
        expected.forEach { end ->
            assertEquals(1, actual.count { it === end }, "rail must retain the exact endpoint instance")
            assertEquals(RailObjectLifecycleState.ACTIVE, end.lifecycleState)
        }
    }

    private fun observeDestruction(car: CoreCar, destroys: AtomicInteger) {
        car.extensionDataContainer.extensionData.add(destructionObserver(destroys))
        car.bogies.forEach { it.extensionDataContainer.extensionData.add(destructionObserver(destroys)) }
    }

    private fun <T> destructionObserver(destroys: AtomicInteger) = object : RenderableExtension<T> {
        override fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
        override fun save(): Pair<String, SerializableExtensionData>? = null
        override fun commit() = Unit
        override fun destroy(): java.util.concurrent.CompletionStage<Void> {
            destroys.incrementAndGet()
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }
    }

    private fun step(world: CoreWorld) {
        world.physicsCoordinator.start()
        world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
    }

    private fun withWorld(action: (CoreWorld, CoreRail) -> Unit) {
        val worldCoordinator = PhysicsCoordinator(1, automaticFallback = false)
        val world = try { createTestWorld(worldCoordinator) } catch (failure: Throwable) {
            runCatching(worldCoordinator::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(120.0, 1))
            action(world, rail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private class CountingController(train: CoreTrain) : TrainController(train) {
        val ticks = AtomicInteger()
        override fun tick(deltaT: Double) {
            ticks.incrementAndGet()
        }
    }
}
