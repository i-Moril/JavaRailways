package work.art1st.javarailways.core.physics.train

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreTrainMixedInstallationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed cars move and reverse without changing physical ends or model directions`(reversed: Boolean) =
        withWorld { world ->
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = world.onOwnerForTest { savedMixedTrain(rail, reversed).load(world) }
            val cars = train.cars.toList()
            val bogies = cars.map { it.bogies.toList() }
            assertPlacement(train.publishedFrame, 0.0, reversed)
            val controller = ConstantSpeedController(train)
            train.setControllerForTest(controller)
            world.physicsCoordinator.start()

            val offset = if (reversed) -0.5 else 0.5
            val first = world.physicsCoordinator.stepSimulation(50.0)
            first.completion.get(5, TimeUnit.SECONDS)
            assertEquals(first.epoch, train.publishedFrame.physicsEpoch)
            assertPlacement(train.publishedFrame, offset, reversed, if (reversed) -10.0 else 10.0)

            val direction = controller.reverse()
            IsolatedSimulationThreadMarker.run {
                world.physicsCoordinator.processBetweenFrames { world.physicsCoordinator.drainBetweenFramesOperations() }
            }
            direction.get(5, TimeUnit.SECONDS)
            assertEquals(first.epoch, train.publishedFrame.physicsEpoch)
            assertPlacement(train.publishedFrame, offset, !reversed)
            assertEquals(listOf(true, false, true), train.save().cars.map { it.installedReversed })

            val next = world.physicsCoordinator.stepSimulation(50.0)
            next.completion.get(5, TimeUnit.SECONDS)
            assertEquals(next.epoch, train.publishedFrame.physicsEpoch)
            assertPlacement(train.publishedFrame, 0.0, !reversed, if (reversed) 10.0 else -10.0)
            cars.zip(train.cars).forEach { (original, current) -> assertSame(original, current) }
            cars.indices.forEach { index ->
                assertSame(bogies[index][0], cars[index].bogieA)
                assertSame(bogies[index][1], cars[index].bogieB)
            }
            assertFalse(train.isDerailed)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed split independently reverses suffix and preserves installation through world codec`(reversed: Boolean) =
        withWorld { source ->
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = source.onOwnerForTest { savedMixedTrain(rail, reversed).load(source) }
            val originalCars = train.cars.toList()
            val originalFrames = train.publishedFrame.cars
            val split = train.splitBefore("car-1", "separated")
            val owner = Thread.currentThread()
            ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                override fun isMainThread() = Thread.currentThread() === owner
            }).use {
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun decide(event: TrainSplitEvent) {
                        event.requestSeparatedDirection(!reversed)
                    }
                }).use { source.onOwnerForTest { source.physicsCoordinator.drainBetweenFramesOperations() } }
            }
            val separated = split.get(5, TimeUnit.SECONDS) as CoreTrain
            assertEquals(originalCars.take(1), train.cars)
            assertEquals(originalCars.drop(1), separated.cars)
            assertEquals(reversed, train.isReversed)
            assertEquals(!reversed, separated.isReversed)
            assertCarFramesEqual(originalFrames, train.publishedFrame.cars + separated.publishedFrame.cars)
            assertEquals(if (reversed) 75.0 else 85.0, train.publishedFrame.head.arcLengthInAbMeters, Curve.TOLERANCE)
            assertEquals(if (reversed) 85.0 else 75.0, train.publishedFrame.tail.arcLengthInAbMeters, Curve.TOLERANCE)
            assertEquals(if (reversed) 74.5 else 53.0, separated.publishedFrame.head.arcLengthInAbMeters, Curve.TOLERANCE)
            assertEquals(if (reversed) 53.0 else 74.5, separated.publishedFrame.tail.arcLengthInAbMeters, Curve.TOLERANCE)

            val infrastructure = source.saveInfrastructure(7L)
            val runtime = source.saveRuntime(7L, 0L)
            val decoded = WorldSnapshotCodec.decodeRuntime(WorldSnapshotCodec.encodeRuntime(runtime))
            assertEquals(listOf(true), decoded.trains.single { it.id == train.id }.cars.map { it.installedReversed })
            assertEquals(listOf(false, true), decoded.trains.single { it.id == separated.id }.cars.map { it.installedReversed })
            withWorld(load = { tick, physics ->
                IsolatedSimulationThreadMarker.run { CoreWorld.load(infrastructure, decoded, CoreWorld.SnapshotDependency(tick, NoOpWorldParentContext, physics)) }
            }) { restored ->
                listOf(train, separated).forEach { original ->
                    val loaded = requireNotNull(restored.getTrain(original.id))
                    assertEquals(original.save(), loaded.save())
                    original.publishedFrame.cars.zip(loaded.publishedFrame.cars).forEach { (before, after) ->
                        assertEquals(before.carId, after.carId)
                        assertEquals(before.state.positionVector(), after.state.positionVector())
                        assertEquals(0.0, before.state.directionVector().distance(after.state.directionVector()), 0.0)
                        assertEquals(before.bogies.map { it.positionVector() }, after.bogies.map { it.positionVector() })
                    }
                }
            }
        }

    @Test
    fun `saved car without installation field has forward installation`() {
        val saved = Json.decodeFromString(SavedCarV1.serializer(), """
            {
              "id":"old-forward",
              "bogieA":{"location":{"railId":"00000000-0000-0000-0000-000000000001","direction":"AB","arcLengthInCurveAB":63.0},"isSameDirectionAsFacing":true,"extension":{}},
              "bogieB":{"location":{"railId":"00000000-0000-0000-0000-000000000001","direction":"AB","arcLengthInCurveAB":58.0},"isSameDirectionAsFacing":true,"extension":{}},
              "config":{"length":10.0,"floorHeight":0.0,"bogieAType":null,"bogieAOffset":3.0,"bogieBType":null,"bogieBOffset":-2.0,"connectorLengthAtA":0.25,"connectorLengthAtB":0.75,"extension":{}}
            }
        """.trimIndent())
        assertFalse(saved.installedReversed)
        assertEquals(3.0, saved.config.bogieAOffset)
        assertEquals(-2.0, saved.config.bogieBOffset)
    }

    private fun savedMixedTrain(rail: CoreRail, reversed: Boolean): SavedTrainV1 {
        val facing = if (reversed) ViewDirection.BA else ViewDirection.AB
        fun location(x: Double) = RailLocationWithFacing.SavedRailLocationWithFacingV1(rail.id.toString(), x, facing)
        val cars = listOf(80.0, 69.5, 58.0).mapIndexed { index, centre ->
            val installedReversed = index != 1
            val physicalDirection = if (installedReversed) -1.0 else 1.0
            val sameDirectionAsFacing = installedReversed == reversed
            SavedCarV1(
                id = "car-$index",
                bogieA = SavedBogieV1(location(centre + physicalDirection * 3.0), sameDirectionAsFacing, emptyMap()),
                bogieB = SavedBogieV1(location(centre - physicalDirection * 2.0), sameDirectionAsFacing, emptyMap()),
                config = CarConfigV1(10.0, 0.0, null, 3.0, null, -2.0, 0.25, 0.75, emptyMap()),
                installedReversed = installedReversed
            )
        }
        return SavedTrainV1("mixed", "mixed", cars, reversed, emptyMap(),
            location(if (reversed) 53.0 else 85.0), location(if (reversed) 85.0 else 53.0))
    }

    private fun assertPlacement(frame: TrainFrameSnapshot, offset: Double, reversed: Boolean, speedX: Double = 0.0) {
        assertEquals(reversed, frame.reversed)
        assertFalse(frame.derailed)
        assertEquals(if (reversed) ViewDirection.BA else ViewDirection.AB, frame.head.facing)
        assertEquals((if (reversed) 53.0 else 85.0) + offset, frame.head.arcLengthInAbMeters, Curve.TOLERANCE)
        assertEquals((if (reversed) 85.0 else 53.0) + offset, frame.tail.arcLengthInAbMeters, Curve.TOLERANCE)
        listOf(80.0, 69.5, 58.0).zip(frame.cars).forEachIndexed { index, (centre, car) ->
            val direction = if (index == 1) 1.0 else -1.0
            assertEquals("car-$index", car.carId)
            assertEquals(centre + offset, car.state.positionX, Curve.TOLERANCE)
            assertEquals(direction, car.state.directionX, 1e-10)
            assertEquals(speedX, car.state.velocityX, 1e-10)
            assertEquals(centre + direction * 3.0 + offset, car.bogies[0].positionX, Curve.TOLERANCE)
            assertEquals(centre - direction * 2.0 + offset, car.bogies[1].positionX, Curve.TOLERANCE)
            car.bogies.forEach { bogie ->
                assertEquals(direction, bogie.directionX, 1e-10)
                assertEquals(speedX, bogie.velocityX, 1e-10)
            }
        }
    }

    private fun withWorld(
        load: (TickManagerImpl, PhysicsCoordinator) -> CoreWorld = { tick, physics ->
            CoreWorld.createEmpty(tick, NoOpWorldParentContext, physics)
        },
        block: (CoreWorld) -> Unit
    ) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            var world: CoreWorld? = null
            AutoCloseable { world?.releaseRuntimeBinding() }.use {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = load(tick, physics)
                    world = created
                    block(created)
                }
            }
        }
    }

    private class ConstantSpeedController(train: CoreTrain) : TrainController(train, train.world) {
        override fun tick(deltaT: Double) {
            speed = 10.0
            moveControlledTrain(10.0 * deltaT * 0.001, 10.0)
        }
    }
}
