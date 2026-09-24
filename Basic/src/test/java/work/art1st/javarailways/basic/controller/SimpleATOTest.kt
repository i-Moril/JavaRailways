package work.art1st.javarailways.basic.controller

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.basic.api.ato.constraint.AtoConstraint
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionPoint
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionRequest
import work.art1st.javarailways.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.api.ato.constraint.ConstraintKey
import work.art1st.javarailways.basic.api.ato.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.basic.api.ato.provider.AtoProvider
import work.art1st.javarailways.basic.api.ato.provider.AtoProviderBoundary
import work.art1st.javarailways.basic.api.ato.provider.AtoProviderContext
import work.art1st.javarailways.basic.api.ato.provider.ProviderBoundaryAction
import work.art1st.javarailways.basic.pathFinding.RailDestination
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.rail.RailAutoConnector
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.api.line.LineId
import work.art1st.javarailways.basic.timetable.LineMaximumSpeedResolver
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableLineSpeedLimit
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.basic.route.ConfirmedRouteWindow
import work.art1st.javarailways.basic.route.RouteSectionAllocationKey
import work.art1st.javarailways.basic.route.RouteSectionOwner
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld as createCoreTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport

class SimpleATOTest {
    private fun createTestWorld() = createCoreTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `ATO without a Mission remains stopped on an unconstrained rail`() {
        val fixture = fixture("no-mission")

        repeat(5) { tickAndPublish(fixture.ato, 1_000.0) }

        assertEquals(null, fixture.ato.currentMission)
        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertEquals(0.0, fixture.ato.lifetimeTravelDistanceMeters, 1.0e-9)
        assertTrue(fixture.ato.isStopped(0.0))
        assertFalse(fixture.ato.isStopped(Double.MIN_VALUE))
        assertFalse(fixture.coordinator.isStalled)
        assertEquals(fixture.train.id, fixture.coordinator.initialTrain(fixture.train.id)?.trainId)
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

        val beforePublication = fixture.ato.publishedState
        AsyncTickTestSupport.run { fixture.ato.tick(50.0) }

        assertEquals(beforePublication, fixture.ato.publishedState)
        assertEquals(null, fixture.ato.currentMission)
        assertEquals(0.0, fixture.ato.lifetimeTravelDistanceMeters, 1.0e-9)

        fixture.ato.commit()

        assertEquals(2L, fixture.ato.currentMission?.revision)
        assertEquals(listOf(window), fixture.ato.currentConfirmedRouteWindows)
        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)

        fixture.ato.setMovementAuthority(
            second.mission.revision,
            fixture.ato.terminalMovementAuthorityLimitMeters(plannedRoute.expandedRoute.totalLength)
        )
        tickAndPublish(fixture.ato, 1_000.0)
        assertEquals(1.0, fixture.train.speed, 1.0e-9)

        assertTrue(fixture.ato.submitMission(request(3L)) is AtoMissionSubmissionResult.Accepted)
        tickAndPublish(fixture.ato, 50.0)
        assertEquals(3L, fixture.ato.currentMission?.revision)
        assertEquals(listOf(window), fixture.ato.currentConfirmedRouteWindows)
        assertTrue(fixture.train.speed < 1.0)

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
        rail.metadata = rail.metadata.copy(speedLimitMetersPerSecond = 0.01)

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `upcoming speed signal creates a route braking constraint`() {
        val fixture = fixture("speed-signal", railLengthMeters = 30.0)
        val next = fixture.world.createRailForTest(
            straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        )
        CoreRail.connect(fixture.train.head.location.rail, next)
        next.metadata = RailInfrastructureMetadata(
            speedLimits = SpeedLimitMetadata(
                listOf(SpeedLimitEntry(5.0, SpeedLimitType.CURVE))
            )
        )

        tickAndPublish(fixture.ato, 50.0)

        val constraints = fixture.ato.publishedState.runtimeDiagnostics.constraints
        assertTrue(constraints.any {
            it.contains("infrastructure-signal/") && it.contains("maximumSpeedMetersPerSecond=5.0")
        })
        assertFalse(constraints.any { it.contains("route-speed/") })
    }

    @Test
    fun `removing curve limit restores subgrade then timetable line maximum speed`() {
        val fixture = fixture("layered-speed", railLengthMeters = 30.0)
        val next = fixture.world.createRailForTest(
            straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        )
        CoreRail.connect(fixture.train.head.location.rail, next)
        val lineId = LineId("metro")
        fixture.ato.setLineMaximumSpeed(
            "line",
            LineMaximumSpeedResolver(Timetable(
                "tt",
                lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, 8.0))
            )),
            TimetableService("service", lineId = lineId)
        )
        next.metadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(5.0, SpeedLimitType.CURVE),
            SpeedLimitEntry(7.0, SpeedLimitType.SUBGRADE)
        )))
        tickAndPublish(fixture.ato, 50.0)
        assertTrue(fixture.ato.publishedState.runtimeDiagnostics.constraints.any {
            it.contains("infrastructure-signal/") && it.contains("maximumSpeedMetersPerSecond=5.0")
        })

        next.metadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(7.0, SpeedLimitType.SUBGRADE)
        )))
        tickAndPublish(fixture.ato, 50.0)
        assertTrue(fixture.ato.publishedState.runtimeDiagnostics.constraints.any {
            it.contains("infrastructure-signal/") && it.contains("maximumSpeedMetersPerSecond=7.0")
        })

        next.metadata = RailInfrastructureMetadata()
        tickAndPublish(fixture.ato, 50.0)
        val constraints = fixture.ato.publishedState.runtimeDiagnostics.constraints
        assertFalse(constraints.any { it.contains("infrastructure-signal/") })
        assertTrue(constraints.any {
            it.contains("external/line") && it.contains("maximumSpeedMetersPerSecond=8.0")
        })
    }

    @Test
    fun `following constraint holds behind a nearby stopped train`() {
        val fixture = fixture("following")
        authorizeMovement(fixture)
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
        authorizeMovement(fixture)
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

        assertEquals(0.0, fixture.train.speed)
        assertEquals(targetArc, fixture.train.head.location.arcLength, 0.4)
        assertFalse(fixture.coordinator.isStalled)
    }

    @Test
    fun `observed stop target disappearance stalls its world`() {
        val fixture = fixture("lost-stop-target")
        val head = fixture.train.head.location
        val target = object : RailObject {
            override val ref = RailObjectRef("test", "lost-stop-target")
            override var lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(head.railView, head.arcLength + 20.0)
        }
        fixture.data.railObjects.register(target)
        fixture.ato.stopAt("station", target)
        tickAndPublish(fixture.ato, 50.0)

        target.lifecycleState = RailObjectLifecycleState.REMOVED
        fixture.data.railObjects.unregister(target.ref)
        tickAndPublish(fixture.ato, 50.0)

        assertTrue(fixture.coordinator.isStalled)
        assertTrue(fixture.coordinator.lastStall?.message?.contains("disappeared") == true)
        assertEquals(0.0, fixture.train.speed, 1.0e-9)
    }

    @Test
    fun `distinct observed objects with the same world reference stall instead of shadowing`() {
        val fixture = fixture("duplicate-rail-object-ref", railLengthMeters = 30.0)
        val head = fixture.train.head.location
        val nextRail = fixture.world.createRailForTest(
            straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(130.0, 0.0, 0.0))
        )
        CoreRail.connect(head.rail, nextRail)
        val ref = RailObjectRef("test", "duplicate")
        fixture.data.railMap.addDestination(
            nextRail.viewAB,
            RailDestination(nextRail.viewAB, "legacy", ref)
        )
        fixture.data.railObjects.register(object : RailObject {
            override val ref = ref
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(nextRail.viewAB, 1.0)
        })

        tickAndPublish(fixture.ato, 50.0)

        assertTrue(fixture.coordinator.isStalled)
        assertTrue(
            fixture.coordinator.lastStall?.message?.contains("duplicate RailObjectRef") == true,
            fixture.coordinator.lastStall?.message
        )
    }

    @Test
    fun `same reference switching to a different live object across ticks stalls`() {
        val world = createTestWorld()
        val approach = world.createRailForTest(
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0))
        )
        val straight = world.createRailForTest(
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        )
        val branch = world.createRailForTest(
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 20.0))
        )
        RailAutoConnector.connectIfAllowed(approach, straight)
        RailAutoConnector.connectIfAllowed(approach, branch)
        approach.viewAB.selectNextRail(straight.viewAB)
        val train = createTrainWithConfig("cross-tick-duplicate", approach, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("cross-tick-duplicate", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val ref = RailObjectRef("test", "cross-tick-duplicate")
        data.railMap.addDestination(
            straight.viewAB,
            RailDestination(straight.viewAB, "straight", ref)
        )
        data.railObjects.register(object : RailObject {
            override val ref = ref
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(branch.viewAB, 1.0)
        })
        val ato = SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile)
        train.setController(ato)

        tickAndPublish(ato, 50.0)
        approach.viewAB.selectNextRail(branch.viewAB)
        tickAndPublish(ato, 50.0)

        assertTrue(coordinator.isStalled)
        assertTrue(coordinator.lastStall?.message?.contains("duplicate RailObjectRef") == true)
    }

    @Test
    fun `route end is treated as a stop target`() {
        val fixture = fixture("route-end", railLengthMeters = 30.0)
        authorizeMovement(fixture)

        repeat(600) { tickAndPublish(fixture.ato, 50.0) }

        assertEquals(0.0, fixture.train.speed)
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
        assertEquals(0.0, secondTrain.speed, 1.0e-9)

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
        authorizeMovement(fixture)
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
        val approach = world.createRailForTest(
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0))
        )
        val straight = world.createRailForTest(
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        )
        val branch = world.createRailForTest(
            straightCurve(Vector3d(40.0, 0.0, 0.0), Vector3d(100.0, 0.0, 20.0))
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
        authorizeMovement(fixture)
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

    private fun authorizeMovement(fixture: Fixture) {
        val rail = fixture.train.head.location.rail
        val originArc = fixture.train.head.location.arcLength
        val originId = "${fixture.train.id}-mission-origin"
        val targetId = "${fixture.train.id}-mission-target"
        fixture.data.wayPoints.add(WayPoint(originId, originId, rail.id, originArc))
        fixture.data.wayPoints.add(WayPoint(
            targetId,
            targetId,
            rail.id,
            rail.curve.totalArcLength
        ))
        val accepted = fixture.ato.submitMission(AtoMissionRequest(
            id = "${fixture.train.id}-mission",
            revision = 1L,
            origin = WayPointRef(originId),
            points = listOf(AtoMissionPoint.Stop(WayPointRef(targetId)))
        )) as AtoMissionSubmissionResult.Accepted
        val route = accepted.mission.legs.single().plannedRoute
        val window = ConfirmedRouteWindow(
            RouteSectionAllocationKey(
                RouteSectionOwner("${fixture.train.id}-mission", fixture.train.id),
                route.routeId,
                route.revision,
                sectionIndex = 0
            ),
            route.sections.single()
        )
        fixture.ato.confirmRouteWindow(window)
        fixture.ato.setMovementAuthority(
            accepted.mission.revision,
            fixture.ato.terminalMovementAuthorityLimitMeters(route.expandedRoute.totalLength)
        )
        tickAndPublish(fixture.ato, 0.0)
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
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val train: work.art1st.javarailways.core.physics.train.CoreTrain,
        val ato: SimpleATO,
        val coordinator: AtoWorldCoordinator,
        val data: BasicWorldData
    )
}
