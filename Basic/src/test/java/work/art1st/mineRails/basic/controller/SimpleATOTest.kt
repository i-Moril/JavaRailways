package work.art1st.mineRails.basic.controller

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.ato.constraint.AtoConstraint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.api.ato.constraint.ConstraintKey
import work.art1st.mineRails.basic.api.ato.constraint.CurrentSpeedLimitConstraint
import work.art1st.mineRails.basic.api.ato.provider.AtoProvider
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderBoundary
import work.art1st.mineRails.basic.api.ato.provider.AtoProviderContext
import work.art1st.mineRails.basic.api.ato.provider.ProviderBoundaryAction
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.rail.RailAutoConnector
import work.art1st.mineRails.basic.route.ConfirmedRouteWindow
import work.art1st.mineRails.basic.route.RouteSectionAllocationKey
import work.art1st.mineRails.basic.route.RouteSectionOwner
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.api.physics.rail.RailObject
import work.art1st.mineRails.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.mineRails.core.api.physics.rail.RailObjectRef
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.AsyncTickTestSupport

class SimpleATOTest {
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `rail without configured limit uses actuator maximum speed`() {
        val fixture = fixture("max-speed")

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
        assertEquals(fixture.train.id, fixture.data.dispatcher.initialTrain(fixture.train.id)?.trainId)
    }

    @Test
    fun `mission revisions replace atomically at a physics tick boundary`() {
        val fixture = fixture("mission-revision")
        val rail = fixture.train.head.location.rail
        val originArc = fixture.train.head.location.arcLength
        fixture.data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        fixture.data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 20.0))
        fun request(revision: Long) = AtoMissionRequest(
            id = "service-1",
            revision = revision,
            origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("B")))
        )

        assertTrue(fixture.ato.submitMission(request(1L)) is AtoMissionSubmissionResult.Accepted)
        val second = fixture.ato.submitMission(request(2L)) as AtoMissionSubmissionResult.Accepted
        assertTrue(fixture.ato.submitMission(request(1L)) is AtoMissionSubmissionResult.StaleRevision)
        val plannedRoute = second.mission.legs.single().plannedRoute
        val window = ConfirmedRouteWindow(
            RouteSectionAllocationKey(
                RouteSectionOwner("service-1", fixture.train.id),
                plannedRoute.routeId,
                plannedRoute.revision,
                sectionIndex = 0
            ),
            plannedRoute.sections.single()
        )
        fixture.ato.confirmRouteWindow(window)
        assertEquals(null, fixture.ato.currentMission)

        tickAndPublish(fixture.ato, 50.0)

        assertEquals(2L, fixture.ato.currentMission?.revision)
        assertEquals(listOf(window), fixture.ato.currentConfirmedRouteWindows)
        assertFalse(fixture.coordinator.isStalled)

        fixture.ato.releaseRouteWindow(window.key)
        tickAndPublish(fixture.ato, 50.0)
        assertTrue(fixture.ato.currentConfirmedRouteWindows.isEmpty())
    }

    @Test
    fun `unresolved mission is rejected without changing active mission`() {
        val fixture = fixture("mission-unresolved")
        val result = fixture.ato.submitMission(
            AtoMissionRequest(
                id = "service-1",
                revision = 1L,
                origin = WayPointRef("missing-origin"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("missing-target")))
            )
        )

        assertEquals(
            AtoMissionSubmissionResult.TargetUnresolved(WayPointRef("missing-origin")),
            result
        )
        assertEquals(null, fixture.ato.currentMission)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `unavailable mission route is rejected before activation`() {
        val fixture = fixture("mission-unavailable")
        val currentRail = fixture.train.head.location.rail
        val disconnected = createStraightRailFromSegments(
            fixture.world,
            PrimeCurveGenerator.buildStraight(50.0, segmentCount = 1)
        )
        fixture.data.wayPoints.add(
            WayPoint("A", "A", currentRail.id, fixture.train.head.location.arcLength)
        )
        fixture.data.wayPoints.add(WayPoint("B", "B", disconnected.id, 20.0))

        val result = fixture.ato.submitMission(
            AtoMissionRequest(
                id = "service-1",
                revision = 1L,
                origin = WayPointRef("A"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("B")))
            )
        )

        assertTrue(result is AtoMissionSubmissionResult.RouteUnavailable)
        assertEquals(null, fixture.ato.currentMission)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `configured rail limit overrides actuator maximum speed`() {
        val fixture = fixture("rail-limit")
        val rail = fixture.train.head.location.rail
        rail.metadata = rail.metadata.copy(speedLimitMetersPerSecond = 0.0)

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `following constraint holds behind a nearby stopped train`() {
        val fixture = fixture("following")
        val front = createTrainWithConfig("front", fixture.train.head.location.rail, fixture.world, listOf(10.0))
        front.setLocation(fixture.train.head.location.forward(11.0)!!)
        front.commit()

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)

        front.setLocation(fixture.train.head.location.forward(200.0)!!)
        front.commit()
        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `stopAt brakes to the observed RailObject`() {
        val fixture = fixture("stop-target")
        val head = fixture.train.head.location
        val targetArc = head.arcLength + 20.0
        val target = object : RailObject {
            override val ref = RailObjectRef("test", "stop-target")
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(head.railView, targetArc)
        }
        fixture.data.railObjects.register(target)
        fixture.ato.stopAt("station", target)

        repeat(600) { tickAndPublish(fixture.ato, 50.0) }

        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertEquals(targetArc, fixture.train.head.location.arcLength, 0.4)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `observed stop target disappearance stalls its world`() {
        val fixture = fixture("lost-stop-target")
        val head = fixture.train.head.location
        val target = object : RailObject {
            override val ref = RailObjectRef("test", "lost-stop-target")
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(head.railView, head.arcLength + 20.0)
        }
        fixture.data.railObjects.register(target)
        fixture.ato.stopAt("station", target)
        tickAndPublish(fixture.ato, 50.0)

        fixture.data.railObjects.unregister(target.ref)
        tickAndPublish(fixture.ato, 50.0)

        assertTrue(fixture.coordinator.isStalled)
        assertTrue(fixture.coordinator.lastStall?.message?.contains("disappeared") == true)
        assertEquals(0.0, fixture.train.speed, 1.0e-9)
    }

    @Test
    fun `route end is treated as a stop target`() {
        val fixture = fixture("route-end", railLengthMeters = 30.0)

        repeat(600) { tickAndPublish(fixture.ato, 50.0) }

        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
        assertEquals(
            fixture.train.head.location.rail.curve.totalArcLength,
            fixture.train.head.location.arcLength,
            0.4
        )
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `unknown provider stalls all ATO in its world only`() {
        val fixture = fixture("provider-failure")
        val otherWorld = AtoWorldCoordinator("other", reportSink = {})
        val secondTrain = createTrainWithConfig(
            "same-world-second",
            fixture.train.head.location.rail,
            fixture.world,
            listOf(10.0)
        )
        secondTrain.setLocation(fixture.train.head.location.forward(100.0)!!)
        secondTrain.commit()
        val secondAto = SimpleATO(
            secondTrain,
            fixture.world,
            KinematicTrainActuator(10.0, profile),
            profile
        )
        secondTrain.setController(secondAto)
        tickAndPublish(secondAto, 1_000.0)
        assertEquals(1.0, secondTrain.speed, 1.0e-9)

        fixture.ato.setProvider("missing")
        tickAndPublish(fixture.ato, 50.0)
        tickAndPublish(secondAto, 50.0)

        assertTrue(fixture.coordinator.isStalled)
        assertFalse(otherWorld.isStalled)
        assertEquals(0.0, fixture.train.speed)
        assertEquals(0.0, secondTrain.speed)
        assertTrue(fixture.coordinator.lastStall?.message?.contains("not registered") == true)
    }

    @Test
    fun `passing provider deactivation boundary removes its constraints`() {
        val fixture = fixture("provider-deactivation")
        fixture.data.atoProviders.register("slow-provider") {
            object : AtoProvider {
                override val id = "slow-provider"
                override fun constraints(context: AtoProviderContext): Collection<AtoConstraint> = listOf(
                    CurrentSpeedLimitConstraint(ConstraintKey("test", "provider-slow"), 2.0)
                )
            }
        }
        val head = fixture.train.head.location
        fixture.data.railObjects.register(
            AtoProviderBoundary(
                ref = RailObjectRef("test", "provider-deactivation"),
                location = RailLocationWithFacing(head.railView, head.arcLength + 0.5),
                providerId = "slow-provider",
                action = ProviderBoundaryAction.DEACTIVATE,
                direction = head.facing
            )
        )
        fixture.ato.setProvider("slow-provider")

        repeat(3) { tickAndPublish(fixture.ato, 1_000.0) }

        assertEquals(3.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `turnout change during planning stalls the world`() {
        val world = createTestWorld()
        val approach = CoreRail(
            world,
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        val straight = CoreRail(
            world,
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        val branch = CoreRail(
            world,
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 20.0)),
            mutableListOf(),
            mutableListOf()
        )
        RailAutoConnector.connectIfAllowed(approach, straight)
        RailAutoConnector.connectIfAllowed(approach, branch)
        approach.viewAB.selectNextRail(straight.viewAB)

        val train = createTrainWithConfig("turnout-change", approach, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("turnout-world", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        data.atoProviders.register("switch-during-plan") {
            object : AtoProvider {
                override val id = "switch-during-plan"
                override fun constraints(context: AtoProviderContext): Collection<AtoConstraint> {
                    approach.viewAB.selectNextRail(branch.viewAB)
                    return emptyList()
                }
            }
        }
        val ato = SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile)
        train.setController(ato)
        ato.setProvider("switch-during-plan")

        tickAndPublish(ato, 50.0)

        assertTrue(coordinator.isStalled)
        assertTrue(coordinator.lastStall?.message?.contains("turnout changed") == true)
        assertEquals(0.0, train.speed, 1.0e-9)
    }

    @Test
    fun `passing provider boundary activates its constraints`() {
        val fixture = fixture("provider-boundary")
        fixture.data.atoProviders.register("stop-provider") {
            object : AtoProvider {
                override val id: String = "stop-provider"

                override fun constraints(context: AtoProviderContext): Collection<AtoConstraint> = listOf(
                    CurrentSpeedLimitConstraint(ConstraintKey("test", "provider-stop"), 0.0)
                )
            }
        }
        val head = fixture.train.head.location
        val boundary = AtoProviderBoundary(
            ref = RailObjectRef("test", "provider-boundary"),
            location = RailLocationWithFacing(head.railView, head.arcLength + 0.5),
            providerId = "stop-provider",
            action = ProviderBoundaryAction.ACTIVATE,
            direction = head.facing
        )
        fixture.data.railObjects.register(boundary)

        tickAndPublish(fixture.ato, 1_000.0)
        assertEquals(1.0, fixture.train.speed, 1.0e-9)
        repeat(3) { tickAndPublish(fixture.ato, 1_000.0) }

        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    private fun fixture(id: String, railLengthMeters: Double = 500.0): Fixture {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(railLengthMeters, segmentCount = 1)
        )
        val train = createTrainWithConfig(id, rail, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("world-$id", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val ato = SimpleATO(
            train = train,
            world = world,
            actuator = KinematicTrainActuator(10.0, profile),
            safetyProfile = profile
        )
        train.setController(ato)
        return Fixture(world, train, ato, coordinator, data)
    }

    private fun tickAndPublish(ato: SimpleATO, deltaMillis: Double) {
        AsyncTickTestSupport.run { ato.tick(deltaMillis) }
        ato.commit()
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(normal), length, Curve.SegmentType.LINE_END)
        ))
    }

    private data class Fixture(
        val world: work.art1st.mineRails.core.physics.CoreWorld,
        val train: work.art1st.mineRails.core.physics.train.CoreTrain,
        val ato: SimpleATO,
        val coordinator: AtoWorldCoordinator,
        val data: BasicWorldData
    )
}
