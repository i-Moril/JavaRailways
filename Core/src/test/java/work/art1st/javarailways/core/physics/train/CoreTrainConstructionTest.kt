package work.art1st.javarailways.core.physics.train

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
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CoreTrainConstructionTest {
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
        world.renameTrain(original, "Local")
        val controller = CountingController(original)
        original.setController(controller)
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
                saved.load(world)
            } else {
                CoreTrain(rejectedId, original.cars, world, emptyMap(), rejectedName)
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
        assertEquals(originalBogieLocations, originalCar.bogies.map { it.location })
        assertEquals(0, destroys.get())

        step(world)
        assertEquals(1, controller.ticks.get(), "the original controller must remain registered")
    }

    @Test
    fun `unplaceable tail does not register a head or take ownership of cars`() = withWorld { world, rail ->
        val car = createCar(world, rail, headBogieArc = 4.0, tailBogieArc = 0.5)
        val destroys = AtomicInteger()
        observeDestruction(car, destroys)
        val bogieLocations = car.bogies.map { it.location }

        assertThrows(CannotCreateTrainException::class.java) {
            CoreTrain("unplaceable", listOf(car), world, emptyMap())
        }

        assertRailEnds(rail)
        assertTrue(world.allTrains.isEmpty())
        assertNull(world.getTrain("unplaceable"))
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        assertEquals(bogieLocations, car.bogies.map { it.location })
        assertEquals(0, destroys.get())
    }

    @Test
    fun `extension initialization failure removes owned registrations and permits a successful retry`() =
        withWorld { world, rail ->
            val original = createTrainWithConfig("original-id", rail, world, listOf(10.0))
            val originalController = CountingController(original)
            original.setController(originalController)
            val originalFrame = original.publishedFrame
            val car = createCar(world, rail, headBogieArc = 72.5, tailBogieArc = 67.5)
            val destroys = AtomicInteger()
            observeDestruction(car, destroys)
            val bogieLocations = car.bogies.map { it.location }
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
                    override fun linkWith(otherTrain: Train) = error("not used")
                })
            }
            world.publishCurrentTrainFrame(original.id)

            val thrown = assertThrows(IllegalStateException::class.java) {
                CoreTrain("failed-id", listOf(car), world, emptyMap(), "Failed", extensions)
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

            val retry = CoreTrain("failed-id", listOf(car), world, emptyMap(), "Failed")
            val retryController = CountingController(retry)
            retry.setController(retryController)
            assertSame(retry, world.getTrain("failed-id"))
            assertSame(retry, world.getTrain("Failed"))
            assertSame(car, retry.cars.single())
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
        override fun destroy() {
            destroys.incrementAndGet()
        }
    }

    private fun step(world: CoreWorld) {
        world.physicsCoordinator.start()
        world.physicsCoordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
    }

    private fun withWorld(action: (CoreWorld, CoreRail) -> Unit) {
        val world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        try {
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(120.0, 1))
            action(world, rail)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private class CountingController(train: CoreTrain) : TrainController(train, train.world) {
        val ticks = AtomicInteger()
        override fun tick(deltaT: Double) {
            ticks.incrementAndGet()
        }
    }
}
