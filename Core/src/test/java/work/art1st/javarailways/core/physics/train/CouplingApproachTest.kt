package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.TrainLinkEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit

@OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
class CouplingApproachTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `host proof solves actual straight and curved contact without moving either source`(curved: Boolean) {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        try {
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, if (curved)
                    PrimeCurveGenerator.buildCircle(30.0, 1, step = 0.02) else PrimeCurveGenerator.buildStraight(200.0, 1))
                fun train(id: String, head: Double): CoreTrain = world.onOwnerForTest {
                    val anchor = RailLocationWithFacing(rail.viewAB, head)
                    val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                        length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                        connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
                    CoreTrain(id, listOf(car), world, emptyMap()).also {
                        it.setLocation(anchor)
                        it.commitMaintenancePlacement()
                    }
                }
                val moving = train("approaching", 99.0)
                val target = train("stationary", 110.5)
                val originalMoving = moving.publishedFrame
                val originalTarget = target.publishedFrame
                val request = CouplingApproachRequest(moving.id, TrainEndSide.A, target.id, TrainEndSide.B)
                val proof = world.onOwnerForTest {
                    val result = world.inspectCouplingApproach(request)
                    assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                        result, result.toString()).proof.also {
                        assertTrue(world.validateCouplingApproach(it))
                    }
                }
                assertSame(originalMoving, moving.publishedFrame)
                assertSame(originalTarget, target.publishedFrame)
                assertTrue(proof.distanceToContactMeters > 0.0)
                assertTrue(proof.distanceToContactMeters < 1.5)
                assertTrue(proof.couplerGapMeters > 0.0)
                if (curved) assertTrue(kotlin.math.abs(proof.couplerGapMeters - proof.distanceToContactMeters) > 1e-4,
                    "curved coupler gap must not be replaced by head travel")
                assertEquals(originalMoving.head.arcLengthInAbMeters,
                    proof.headTraversal.first().startArcLengthInAbMeters, 1e-8)
                moving.setControllerForTest(object : TrainController(moving) {
                    override fun tick(deltaT: Double) {
                        speed = 0.2
                        check(world.validatePublishedCouplingApproach(proof))
                        moveControlledTrain(world.clampCouplingApproachDistance(proof, speed * deltaT * 0.001), speed)
                    }
                })
                var observedContactHead: RailPositionSnapshot? = null
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun link(event: TrainLinkEvent) {
                        observedContactHead = moving.publishedFrame.head
                    }
                }).use {
                PhysicsRailEventBus.register(object : EventListener {
                    @HandlesEvent
                    fun collision(event: CollisionEvent) { event.requestLink() }
                }).use {
                    coordinator.start()
                    var steps = 0
                    while (world.allTrains.size == 2 && steps++ < 200) {
                        coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                        world.onOwnerForTest { coordinator.drainBetweenFramesOperations() }
                    }
                    val observed = checkNotNull(observedContactHead)
                    assertEquals(proof.contactHead.railId, observed.railId)
                    assertEquals(proof.contactHead.arcLengthInAbMeters, observed.arcLengthInAbMeters, 0.02)
                }
                }
                assertEquals(1, world.allTrains.size)
                val survivor = world.allTrains.single()
                assertEquals(setOf("approaching-car", "stationary-car"), survivor.cars.map { it.id }.toSet())
                assertEquals(0.0, survivor.publishedFrame.speedMetersPerSecond)
                assertFalse(world.onOwnerForTest { world.validateCouplingApproach(proof) })
            }
        } finally { coordinator.close() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `rear coupling mode checks actual coupler gap and selects operational ends`(reversed: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val moving = createTrainWithConfig("entry-moving", rail, world, listOf(10.0),
                connectorLength = 0.3, carIdPrefix = "moving-")
            val target = createTrainWithConfig("entry-target", rail, world, listOf(10.0),
                connectorLength = 0.3, carIdPrefix = "target-")
            world.onOwnerForTest {
                moving.setLocation(RailLocationWithFacing(rail.viewAB, if (reversed) 114.5 else 99.0))
                target.setLocation(RailLocationWithFacing(rail.viewAB, if (reversed) 99.0 else 114.5))
                moving.commitMaintenancePlacement(); target.commitMaintenancePlacement()
                if (reversed) { moving.reverse(); target.reverse() }
                val movingFrame = moving.publishedFrame
                val targetFrame = target.publishedFrame
                val accepted = assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                    world.inspectCouplingModeEntry(moving.id, target.id, 5.0))
                assertEquals(4.9, accepted.proof.couplerGapMeters, 1e-8)
                assertEquals(if (reversed) TrainEndSide.B else TrainEndSide.A, accepted.proof.request.approachingEnd)
                assertEquals(if (reversed) TrainEndSide.A else TrainEndSide.B, accepted.proof.request.stationaryEnd)
                assertSame(movingFrame, moving.publishedFrame)
                assertSame(targetFrame, target.publishedFrame)
                assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                    world.inspectCouplingModeEntry(moving.id, target.id, accepted.proof.couplerGapMeters))
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                    world.inspectCouplingModeEntry(moving.id, target.id, 4.8))
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                    world.inspectCouplingModeEntry(target.id, moving.id, 5.0),
                    "approaching train must be behind the target's operating tail")
                assertEquals(2, world.allTrains.size)
            }
        }
    }

    @Test
    fun `both participants must already be stopped before entering coupling mode`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                val world = fixture.world
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                val moving = createTrainWithConfig("entry-moving", rail, world, listOf(10.0), carIdPrefix = "moving-")
                val target = createTrainWithConfig("entry-target", rail, world, listOf(10.0), carIdPrefix = "target-")
                world.onOwnerForTest {
                    moving.setLocation(RailLocationWithFacing(rail.viewAB, 99.0)); moving.commitMaintenancePlacement()
                    target.setLocation(RailLocationWithFacing(rail.viewAB, 113.0)); target.commitMaintenancePlacement()
                }
                fun driver(train: CoreTrain) = object : TrainController(train) {
                    override fun tick(deltaT: Double) {
                        speed = 0.2
                        moveControlledTrain(speed * deltaT * 0.001, speed)
                    }
                }
                moving.setControllerForTest(driver(moving))
                coordinator.start()
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                world.onOwnerForTest {
                    val before = moving.publishedFrame to target.publishedFrame
                    val rejection = assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                        world.inspectCouplingModeEntry(moving.id, target.id, 5.0))
                    assertTrue(rejection.reason.contains("both trains must be stopped"))
                    assertSame(before.first, moving.publishedFrame)
                    assertSame(before.second, target.publishedFrame)
                }
                moving.setControllerForTest(null)
                target.setControllerForTest(driver(target))
                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                world.onOwnerForTest {
                    assertEquals(0.0, moving.publishedFrame.speedMetersPerSecond)
                    assertTrue(target.publishedFrame.speedMetersPerSecond > 0.0)
                    assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                        world.inspectCouplingModeEntry(moving.id, target.id, 5.0))
                }
            }
        }
    }

    @Test
    fun `nearby disconnected rail and opposite target direction cannot enter rear coupling mode`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val disconnected = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
            val moving = createTrainWithConfig("entry-moving", rail, world, listOf(10.0), carIdPrefix = "moving-")
            val target = createTrainWithConfig("entry-target", disconnected, world, listOf(10.0), carIdPrefix = "target-")
            world.onOwnerForTest {
                moving.setLocation(RailLocationWithFacing(rail.viewAB, 99.0)); moving.commitMaintenancePlacement()
                target.setLocation(RailLocationWithFacing(disconnected.viewAB, 110.5)); target.commitMaintenancePlacement()
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                    world.inspectCouplingModeEntry(moving.id, target.id, 5.0))
                target.setLocation(RailLocationWithFacing(rail.viewAB, 110.5)); target.commitMaintenancePlacement()
                target.reverse()
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java,
                    world.inspectCouplingModeEntry(moving.id, target.id, 5.0))
                assertEquals(2, world.allTrains.size)
                assertEquals(99.0, moving.publishedFrame.head.arcLengthInAbMeters, 1e-8)
            }
        }
    }

    @Test
    fun `wrong end and distant target reject and frame changes invalidate issued proof`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
            val moving = createTrainWithConfig("moving", rail, world, listOf(10.0))
            val target = createTrainWithConfig("target", rail, world, listOf(10.0))
            world.onOwnerForTest {
                moving.setLocation(RailLocationWithFacing(rail.viewAB, 50.0)); moving.commitMaintenancePlacement()
                target.setLocation(RailLocationWithFacing(rail.viewAB, 65.0)); target.commitMaintenancePlacement()
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java, world.inspectCouplingApproach(
                    CouplingApproachRequest(moving.id, TrainEndSide.B, target.id, TrainEndSide.B)))
                val request = CouplingApproachRequest(moving.id, TrainEndSide.A, target.id, TrainEndSide.B)
                val result = world.inspectCouplingApproach(request)
                val proof = assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
                    result, result.toString()).proof
                target.setLocation(RailLocationWithFacing(rail.viewAB, 66.0)); target.commitMaintenancePlacement()
                assertFalse(world.validateCouplingApproach(proof))
                target.setLocation(RailLocationWithFacing(rail.viewAB, 200.0)); target.commitMaintenancePlacement()
                assertInstanceOf(CouplingApproachInspection.Rejected::class.java, world.inspectCouplingApproach(request))
                assertEquals(2, world.allTrains.size)
                assertEquals(50.0, moving.publishedFrame.head.arcLengthInAbMeters, 1e-8)
            }
        }
    }
}
