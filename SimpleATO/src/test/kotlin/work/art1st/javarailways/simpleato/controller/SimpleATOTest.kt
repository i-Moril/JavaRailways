package work.art1st.javarailways.simpleato.controller

import work.art1st.javarailways.core.physics.train.setControllerForTest

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.constraint.AtoConstraint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.api.constraint.ConstraintKey
import work.art1st.javarailways.simpleato.api.constraint.CurrentSpeedLimitConstraint
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.timetable.LineMaximumSpeedResolver
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableLineSpeedLimit
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.physics.rail.configureTurnoutForTest
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
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult

class SimpleATOTest {
    private fun createTestWorld() = createCoreTestWorld().also { TestSimpleATOWorldDataRegistry.getOrCreate(it) }

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `resource admission reuses the controller forward perception horizon`() {
        val fixture = fixture("perception-horizon")

        assertEquals(52.0, fixture.ato.inspectionDynamicLookaheadMeters(10.0), 1.0e-12)
        assertEquals(62.0, fixture.ato.routePerceptionHorizonMeters(), 1.0e-12)
        assertEquals(62.0, fixture.ato.routePerceptionHorizonMeters(5.0), 1.0e-12)
        assertEquals(150.0, fixture.ato.routePerceptionHorizonMeters(15.0), 1.0e-12)
    }

    @Test
    fun `physics scan reads the published inspection maximum`() {
        var inspectionMaximum = 15.0
        val fixture = fixture("inspection-perception-horizon") { inspectionMaximum }

        tickAndPublish(fixture.ato, 50.0)
        assertEquals(150.0, fixture.ato.publishedState.perceptionHorizonMeters, 1.0e-12)
        assertEquals(
            150.0,
            requireNotNull(fixture.ato.publishedState.perception).route.totalLengthMeters,
            1.0e-12
        )

        inspectionMaximum = 20.0
        tickAndPublish(fixture.ato, 50.0)
        assertEquals(200.0, fixture.ato.publishedState.perceptionHorizonMeters, 1.0e-12)
        assertEquals(
            200.0,
            requireNotNull(fixture.ato.publishedState.perception).route.totalLengthMeters,
            1.0e-12
        )
    }

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
    fun `resource authority restrictions use the controller constraint manager`() {
        val fixture = fixture("resource-authority")
        authorizeMovement(fixture)
        val head = fixture.ato.publishedState.trainSnapshot.head
        val topologyRevision = fixture.world.topologyState.revision
        val route = RouteSnapshot(
            routeId = "simpleato:test/resource-authority",
            revision = 1L,
            topologyRevision = topologyRevision,
            segments = listOf(
                RouteSegmentSnapshot(
                    railId = head.railId,
                    facing = head.facing,
                    startArcLengthMeters = 0.0,
                    endArcLengthMeters = fixture.train.head.location.rail.curve.totalArcLength
                )
            )
        )
        val stamp = RailwayStateStamp(fixture.coordinator.worldId, 0L, topologyRevision)
        val publication = fixture.ato.trainRestrictionPublisher.publish(
            TrainRestrictionSnapshot(
                sourceId = "simpleato:test-admission",
                revision = 0L,
                trainId = fixture.train.id,
                observationStamp = stamp,
                route = FrozenRouteBinding.from(route),
                restrictions = listOf(MovementAuthorityRestriction("simpleato:grant", 0.0))
            )
        )

        assertEquals(TrainRestrictionPublicationStatus.ACCEPTED, publication.status)
        fixture.ato.bindTrainRestrictionRoute(
            observationStamp = stamp,
            route = FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(fixture.train.id, stamp, route),
                headDistanceFromRouteStartMeters = 0.0,
                missionHeadProgressAtSelectionMeters = 0.0
            ),
            missionRevision = checkNotNull(fixture.ato.currentMission).revision,
            signalSnapshots = TrainRestrictionSnapshotProvider { emptyList() }
        )
        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertTrue(fixture.ato.runtimeDiagnostics.constraints.any {
            it.contains("SIGNALLING/simpleato:external-restrictions:${fixture.train.id}") &&
                it.contains("simpleato:test-admission/simpleato:grant")
        })
        assertFalse(fixture.coordinator.isStalled)
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
        assertEquals(null, fixture.ato.currentMission)

        val beforePublication = fixture.ato.publishedState
        AsyncTickTestSupport.run { fixture.ato.tick(50.0) }

        assertEquals(beforePublication, fixture.ato.publishedState)
        assertEquals(null, fixture.ato.currentMission)
        assertEquals(0.0, fixture.ato.lifetimeTravelDistanceMeters, 1.0e-9)

        fixture.ato.commit()

        assertEquals(2L, fixture.ato.currentMission?.revision)
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
        assertTrue(fixture.train.speed < 1.0)
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
        rail.metadata = rail.metadata.copy(
            speedLimits = SpeedLimitMetadata(listOf(SpeedLimitEntry(0.01, SpeedLimitType.CURVE)))
        )

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
        nextRail.signals.add(OperationalSpeedLimitSignal(
            ref, RailLocationWithoutFacing(nextRail, 0.0), nextRail.viewAB.viewDirection, null
        ))
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
        configureTurnoutForTest(approach, listOf(straight, branch))
        approach.viewAB.selectNextRail(straight.viewAB)
        val train = createTrainWithConfig("cross-tick-duplicate", approach, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("cross-tick-duplicate", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val ref = RailObjectRef("test", "cross-tick-duplicate")
        straight.signals.add(OperationalSpeedLimitSignal(
            ref, RailLocationWithoutFacing(straight, 0.0), straight.viewAB.viewDirection, null
        ))
        data.railObjects.register(object : RailObject {
            override val ref = ref
            override val lifecycleState = RailObjectLifecycleState.ACTIVE
            override val location = RailLocationWithFacing(branch.viewAB, 1.0)
        })
        val ato = SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile) { 0.0 }
        train.setControllerForTest(ato)

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
        fixture.ato.setMovementAuthority(
            accepted.mission.revision,
            fixture.ato.terminalMovementAuthorityLimitMeters(route.expandedRoute.totalLength)
        )
        tickAndPublish(fixture.ato, 0.0)
    }

    private fun fixture(
        id: String,
        railLengthMeters: Double = 500.0,
        inspectionMaximumLeadTimeSeconds: (String) -> Double = { 0.0 }
    ): Fixture {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(railLengthMeters, segmentCount = 1)
        )
        val train = createTrainWithConfig(id, rail, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("world-$id", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val ato = SimpleATO(
            train = train,
            world = world,
            actuator = KinematicTrainActuator(10.0, profile),
            safetyProfile = profile,
            inspectionMaximumLeadTimeSeconds = inspectionMaximumLeadTimeSeconds
        )
        train.setControllerForTest(ato)
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
