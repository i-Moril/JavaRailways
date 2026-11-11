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
