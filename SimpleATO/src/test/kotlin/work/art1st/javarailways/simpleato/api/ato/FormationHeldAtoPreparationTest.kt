package work.art1st.javarailways.simpleato.api.ato

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.ato.*
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class FormationHeldAtoPreparationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `real mission prepares and publishes perception while held without actuation including failure`(stale: Boolean) {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            TestWorldFixture.create(physics).use { fixture ->
                val world = fixture.world
                val coordinator = AtoWorldCoordinator("held-ato", reportSink = {})
                TestSimpleATOWorldDataRegistry.register(world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator)
                try {
                    val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, 1))
                    val train = createTrainWithConfig("output", rail, world, listOf(10.0))
                    val profile = AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0,
                        serviceDecelerationMetersPerSecondSquared = 1.0, reactionTimeSeconds = 0.0,
                        safetyDistanceMeters = 0.0, stopToleranceMeters = 0.1)
                    val delegate = KinematicTrainActuator(10.0, profile)
                    var steps = 0
                    var freezes = 0
                    val actuator = object : TrainActuator by delegate {
                        override fun step(currentSpeedMetersPerSecond: Double, demand: ControlDemand, deltaTimeSeconds: Double): ActuationStep {
                            steps++
                            return delegate.step(currentSpeedMetersPerSecond, demand, deltaTimeSeconds)
                        }
                        override fun freeze() { freezes++ }
                    }
                    val ato = SimpleATO(train, world, actuator, profile) { 0.0 }
                    train.setControllerForTest(ato)
                    val anchor = train.publishedFrame.head
                    val origin = AtoMissionOrigin.FormationOutput("split", "FIRST", anchor)
                    val mission = AtoMission("onward", 1, origin,
                        listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef("target")),
                            PlannedRoute("onward", 1, ExpandedRoute(origin.id, "target",
                                listOf(RouteSegment(RailViewRef(rail.id, anchor.facing),
                                    anchor.arcLengthInAbMeters, anchor.arcLengthInAbMeters + 20.0)))))))
                    assertInstanceOf(AtoMissionSubmissionResult.Accepted::class.java, ato.submitMission(mission))
                    ato.setMovementAuthority(mission.revision, 20.0)
                    val hold = world.onOwnerForTest {
                        if (stale) {
                            train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(
                                rail.viewAB, anchor.arcLengthInAbMeters + 1.0))
                            train.commitMaintenancePlacement()
                        }
                        assertInstanceOf(work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted::class.java,
                            world.restoreFormationMovementHold(train.id, train.cars.map { it.id })).hold
                    }
                    val stopped = train.publishedFrame
                    physics.start()
                    repeat(2) { physics.stepSimulation(50.0).await() }
                    assertEquals(0, steps)
                    assertEquals(0, freezes, "held preparation must not mutate actuator braking history even on failure")
                    assertEquals(stopped.head, train.publishedFrame.head)
                    assertEquals(stopped.tail, train.publishedFrame.tail)
                    assertEquals(0.0, ato.speed)
                    assertEquals(0.0, ato.lifetimeTravelDistanceMeters)
                    assertEquals(stale, coordinator.isStalled, coordinator.lastStall.toString())
                    if (stale) {
                        assertNull(ato.currentMission)
                    } else {
                        assertSame(mission, ato.currentMission)
                        assertNotNull(ato.publishedState.perception)
                        assertTrue(ato.publishedState.perception!!.route.segments.isNotEmpty())
                        world.onOwnerForTest { assertTrue(world.releaseFormationMovementHold(hold)) }
                        physics.stepSimulation(50.0).await()
                        assertEquals(1, steps)
                        assertTrue(train.publishedFrame.head != stopped.head)
                    }
                } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
            }
        }
    }
}
