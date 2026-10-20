package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import kotlinx.serialization.json.Json
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import java.util.UUID
import kotlin.math.sqrt

class CoreTrainPersistedPlacementTest {
    @Test
    fun `fixed current input with detached ends is rejected without registrations`() = withWorld { world ->
        val rail = IsolatedSimulationThreadMarker.run { world.railTopologyTransaction {
            world.createRailInTransaction(
                UUID.fromString("00000000-0000-0000-0000-000000000023"),
                straight(0.0, 300.0)
            )
        } }
        val original = createTrainWithConfig("original", rail, world, listOf(10.0))
        val text = requireNotNull(javaClass.getResource("/persistence/detached-train-placement.json")).readText()
        val saved = Json.decodeFromString(SavedTrainV1.serializer(), text)

        assertRejectedWithoutRegistrations(world, saved, original, "outside its tail-to-head path")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "swapped-bogies", "opposite-facing", "disconnected-bogies", "disconnected-ends",
        "adjacent-car", "bogie-spacing", "head-offset", "tail-offset"
    ])
    fun `individually plausible bogies cannot disguise inconsistent placement`(corruption: String) = withWorld { world ->
        val rail = world.createRailForTest(straight(0.0, 300.0))
        val coincident = world.createRailForTest(straight(0.0, 300.0))
        val original = configuredTrain(world, rail, "original")
        val saved = original.save().copy(id = "invalid", name = "Invalid")
        val car = saved.cars.last()
        val invalid = when (corruption) {
            "swapped-bogies" -> saved.copy(cars = saved.cars.dropLast(1) + car.copy(
                bogieA = car.bogieB, bogieB = car.bogieA
            ))
            "opposite-facing" -> saved.copy(cars = saved.cars.dropLast(1) + car.copy(
                bogieA = car.bogieA.copy(location = car.bogieA.location.copy(direction = ViewDirection.BA)),
                bogieB = car.bogieB.copy(location = car.bogieB.location.copy(direction = ViewDirection.BA))
            ))
            "disconnected-bogies" -> saved.copy(cars = saved.cars.dropLast(1) + car.copy(
                bogieA = car.bogieA.copy(location = car.bogieA.location.copy(railId = coincident.id.toString())),
                bogieB = car.bogieB.copy(location = car.bogieB.location.copy(railId = coincident.id.toString()))
            ))
            "disconnected-ends" -> saved.copy(
                headLocation = saved.headLocation.copy(railId = coincident.id.toString()),
                tailLocation = saved.tailLocation.copy(railId = coincident.id.toString())
            )
            "adjacent-car" -> saved.copy(cars = saved.cars.dropLast(1) + car.copy(
                bogieA = car.bogieA.copy(location = car.bogieA.location.copy(
                    arcLengthInCurveAB = car.bogieA.location.arcLengthInCurveAB - 20.0
                )),
                bogieB = car.bogieB.copy(location = car.bogieB.location.copy(
                    arcLengthInCurveAB = car.bogieB.location.arcLengthInCurveAB - 20.0
                ))
            ))
            "bogie-spacing" -> saved.copy(cars = saved.cars.dropLast(1) + car.copy(
                bogieA = car.bogieA.copy(location = car.bogieA.location.copy(
                    arcLengthInCurveAB = car.bogieA.location.arcLengthInCurveAB + 0.02
                ))
            ))
            "head-offset" -> saved.copy(headLocation = saved.headLocation.copy(
                arcLengthInCurveAB = saved.headLocation.arcLengthInCurveAB + 0.02
            ))
            "tail-offset" -> saved.copy(tailLocation = saved.tailLocation.copy(
                arcLengthInCurveAB = saved.tailLocation.arcLengthInCurveAB - 0.02
            ))
            else -> error(corruption)
        }

        val expectedReason = when (corruption) {
            "swapped-bogies" -> "bogies out of order"
            "bogie-spacing" -> "bogie connector spacing"
            "head-offset" -> "head endpoint offset"
            "tail-offset" -> "tail endpoint offset"
            else -> "outside its tail-to-head path"
        }
        assertRejectedWithoutRegistrations(world, invalid, original, expectedReason)
    }

    @Test
    fun `an interior car gap is rejected even when axle spacing and path order remain valid`() = withWorld { world ->
        val rail = world.createRailForTest(straight(0.0, 300.0))
        val original = configuredTrain(world, rail, "original")
        val saved = original.save().copy(id = "invalid", name = "Invalid")
        val middle = saved.cars[1]
        fun shifted(bogie: SavedBogieV1) = bogie.copy(location = bogie.location.copy(
            arcLengthInCurveAB = bogie.location.arcLengthInCurveAB + 0.1
        ))
        val invalid = saved.copy(cars = listOf(saved.cars.first(), middle.copy(
            bogieA = shifted(middle.bogieA), bogieB = shifted(middle.bogieB)
        ), saved.cars.last()))
        assertRejectedWithoutRegistrations(world, invalid, original, "adjacent coupler geometry")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `valid straight consist preserves exact placement in both operating directions`(reverse: Boolean) = withWorld { world ->
        val rail = world.createRailForTest(straight(0.0, 300.0))
        val original = configuredTrain(world, rail, "straight")
        if (reverse) reverse(original)

        assertExactRestore(world, original)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `valid curved consist preserves exact placement in both operating directions`(reverse: Boolean) = withWorld { world ->
        val rail = world.createRailForTest(PrimeCurveGenerator.buildCircle(30.0, 1, step = 0.02).single())
        val original = configuredTrain(world, rail, "curved")
        if (reverse) reverse(original)

        assertExactRestore(world, original)
    }

    @Test
    fun `restored slope consist publishes a normal perpendicular to its direction`() = withWorld { world ->
        val tangent = Vector3d(1.0, 1.0, 0.0).normalize()
        val normal = Vector3d(-1.0, 1.0, 0.0).normalize()
        val rail = world.createRailForTest(PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(300.0, 300.0, 0.0), tangent, normal,
                300.0 * sqrt(2.0), Curve.SegmentType.LINE_END)
        )))
        val original = configuredTrain(world, rail, "slope")
        val saved = original.save()
        val loaded = world.onOwnerForTest {
            original.discardFailedLoad()
            world.drainPublishedTrainFrames()
            saved.load(world)
        }

        loaded.cars.forEach { car ->
            assertEquals(0.0, car.direction.dot(car.normal), 1e-9,
                "restored car normal must be perpendicular to the restored direction")
            assertEquals(1.0, car.normal.length(), 1e-9)
            assertTrue(car.direction.y() > 0.0)
        }
    }

    @Test
    fun `valid turnout consist restores with a different backward branch selection`() = withWorld { world ->
        val previous = world.createRailForTest(straight(0.0, 100.0))
        val junction = world.createRailForTest(straight(100.0, 200.0))
        val alternate = world.createRailForTest(PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(100.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(100.0, 0.0, 50.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 50.0)
        )))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(previous, junction) }
        IsolatedSimulationThreadMarker.run { CoreRail.connect(alternate, junction) }
        IsolatedSimulationThreadMarker.run { junction.viewBA.activeRail = previous.viewBA }
        val original = configuredTrain(world, previous, "turnout")
        original.world.onOwnerForTest {
            original.setLocation(RailLocationWithFacing(junction.viewAB, 8.0))
            original.commitMaintenancePlacement()
        }
        assertSame(previous, original.tail.location.rail)
        assertSame(junction, original.head.location.rail)
        assertExactRestore(world, original) { junction.viewBA.activeRail = alternate.viewAB }
    }

    private fun assertRejectedWithoutRegistrations(
        world: CoreWorld,
        saved: SavedTrainV1,
        original: CoreTrain,
        expectedReason: String
    ) {
        val originalFrame = original.publishedFrame
        val ends = world.allRails.associate { rail ->
            rail.id to (rail.viewAB.dynamicRailObjects + rail.viewBA.dynamicRailObjects).toSet()
        }
        world.drainPublishedTrainFrames()
        val failure = assertThrows(IllegalStateException::class.java) { world.onOwnerForTest { saved.load(world) } }
        assertTrue(failure.message.orEmpty().contains(saved.id), "placement rejection must identify the train")
        assertTrue(failure.message.orEmpty().contains(expectedReason), failure.message)
        assertEquals(listOf(original), world.allTrains.toList())
        assertNull(world.getTrain(saved.id))
        assertNull(world.getTrain(saved.name))
        assertSame(originalFrame, original.publishedFrame)
        assertEquals(ends, world.allRails.associate { rail ->
            rail.id to (rail.viewAB.dynamicRailObjects + rail.viewBA.dynamicRailObjects).toSet()
        })
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
    }

    private fun assertExactRestore(world: CoreWorld, original: CoreTrain, beforeRestore: () -> Unit = {}) {
        val expected = original.save()
        world.onOwnerForTest { original.destroy() }.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS)
        val loaded = world.onOwnerForTest {
            world.drainPublishedTrainFrames()
            beforeRestore()
            expected.load(world)
        }
        assertEquals(expected, loaded.save(), "loading must preserve exact serialized bogie and end positions")
        assertEquals(expected.reversed, loaded.isReversed)
        assertTrue(loaded.publishedFrame.bodyTraversal.isNotEmpty())
        assertEquals(listOf(loaded.publishedFrame), world.drainPublishedTrainFrames())
    }

    private fun configuredTrain(world: CoreWorld, rail: CoreRail, id: String): CoreTrain {
        val start = RailLocationWithFacing(rail.viewAB, 60.0)
        val cars = List(3) { index -> CoreCar.create(
            "$id-car-$index", CoreBogie.create(start), CoreBogie.create(start),
            length = 10.0, floorHeight = 0.5, connectorLengthAtA = 0.3, connectorLengthAtB = 0.3,
            bogieAOffset = 3.0, bogieBOffset = -3.0, world = world
        ) }
        return world.onOwnerForTest { CoreTrain(id, cars, world, emptyMap()).also {
            it.setLocation(RailLocationWithFacing(rail.viewAB, 80.0))
            it.commitMaintenancePlacement()
        } }
    }

    private fun reverse(train: CoreTrain) {
        val owner = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread() = Thread.currentThread() === owner
        })
        try { train.reverse() } finally { registration.close() }
    }

    private fun straight(from: Double, to: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(from, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(to, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), to - from, Curve.SegmentType.LINE_END)
    ))

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try { block(world) } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
