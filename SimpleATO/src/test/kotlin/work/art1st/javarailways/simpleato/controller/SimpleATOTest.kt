package work.art1st.javarailways.simpleato.controller

import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.CoreWorld
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.core.physics.train.setControllerForTest

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.basic.api.route.ExpandedRoute
import work.art1st.javarailways.basic.api.route.PlannedRoute
import work.art1st.javarailways.basic.api.route.RailViewRef
import work.art1st.javarailways.basic.api.route.RouteSegment
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg
import work.art1st.javarailways.simpleato.api.mission.AtoMissionOrigin
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
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.simpleato.api.restriction.MovementAuthorityRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
import work.art1st.javarailways.simpleato.routing.RouteAllocationTiming
import work.art1st.javarailways.simpleato.admission.RollingRouteAuthorityPlanner
import work.art1st.javarailways.simpleato.admission.RouteAuthorityAdmissionState
import work.art1st.javarailways.basic.api.route.RouteBoundary

class SimpleATOTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    private fun createTestWorld(physicsCoordinator: PhysicsCoordinator? = null) = TestWorldFixture.create(physicsCoordinator).also(worldFixtures::add).world.also { TestSimpleATOWorldDataRegistry.getOrCreate(it) }

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 2.0
    )

    @Test
    fun `permission hold remains distinct from an issued zero authority behind the actual head`() {
        val fixture = fixture("hold-versus-issued-zero")
        val rail = fixture.train.head.location.rail
        val head = fixture.train.head.location.arcLength
        // Initial placement is valid within the shared origin tolerance, but is already 0.1m
        // past mission coordinate zero. A waiting permission is not a physical boundary there.
        fixture.data.wayPoints.add(WayPoint("hold-origin", rail.id, head - 0.1))
        fixture.data.wayPoints.add(WayPoint("hold-target", rail.id, head + 40.0))
        val accepted = fixture.ato.submitMission(AtoMissionRequest("hold-zero", 1L,
            WayPointRef("hold-origin"), listOf(AtoMissionPoint.Stop(WayPointRef("hold-target")))))
            as AtoMissionSubmissionResult.Accepted
        fixture.ato.holdForMovementAuthority(accepted.mission.revision)
        tickAndPublish(fixture.ato, 50.0)
        val runtime = fixture.world.simpleAtoWorldData.controlRuntime
        assertEquals(0.0, fixture.train.speed)
        assertTrue(runtime.incidents.isEmpty(), runtime.incidents.toString())
        assertFalse(fixture.coordinator.isStalled)

        fixture.ato.setMovementAuthority(accepted.mission.revision, 0.0)
        tickAndPublish(fixture.ato, 50.0)

        val incident = requireNotNull(runtime.latestIntervention).event
        val authority = incident.targets.single { it.id.contains("authority/1") }
        assertEquals(-0.1, authority.distanceMeters, 1e-9)
        assertEquals(0.0, authority.targetSpeedMetersPerSecond)
        assertEquals(0.0, incident.state.speedMetersPerSecond)
        assertTrue(incident.judgement.status != work.art1st.javarailways.simpleato.api.atp.AtpStatus.SAFE)
    }

    @Test
    fun `perception keeps maximum speed and declared inspection lead coverage`() {
        val fixture = fixture("perception-horizon")

        assertEquals(52.0, fixture.ato.inspectionDynamicLookaheadMeters(10.0), 1.0e-12)
        assertEquals(62.0, fixture.ato.routePerceptionHorizonMeters(), 1.0e-12)
        assertEquals(62.0, fixture.ato.routePerceptionHorizonMeters(5.0), 1.0e-12)
        assertEquals(150.0, fixture.ato.routePerceptionHorizonMeters(15.0), 1.0e-12)
    }

    @Test
    fun `resource window starts small and grows with braking setup and reaction distance`() {
        val fixture = fixture("rolling-request-window")
        val timing = RouteAllocationTiming(0.25, 0.25, 2.0)

        assertEquals(22.0, fixture.ato.routeAllocationRequestHorizonMeters(timing, 0.0), 1e-12)
        assertEquals(32.0, fixture.ato.routeAllocationRequestHorizonMeters(timing, 4.0), 1e-12)
        assertEquals(62.0, fixture.ato.routeAllocationRequestHorizonMeters(timing, 10.0), 1e-12,
            "the request window cannot outrun the shared perception horizon")
        assertEquals(62.0, fixture.ato.routePerceptionHorizonMeters(), 1e-12,
            "shorter resource requests do not shorten perception")
    }

    @Test
    fun `fast train does not reserve a whole short mission while stopped`() {
        val fixture = fixture("fast-train-startup-window", maximumSpeedMetersPerSecond = 40.0)
        val timing = RouteAllocationTiming(0.25, 0.25, 2.0)
        val request = fixture.ato.routeAllocationRequestHorizonMeters(timing, 0.0)

        assertTrue(fixture.ato.routePerceptionHorizonMeters() > 603.0)
        assertEquals(RouteBoundary(112.0), RollingRouteAuthorityPlanner.nextBoundary(
            routeLengthMeters = 693.0,
            headProgressMeters = 90.0,
            requestHorizonMeters = request,
            extensionTriggerMeters = fixture.ato.routeAllocationTriggerDistanceMeters(timing),
            admissionState = RouteAuthorityAdmissionState.EMPTY
        ))
    }

    @Test
    fun `very slow train still extends from a conservative stop before its authority margin`() {
        val fixture = fixture("very-slow-rolling-window", atoProfile = profile.copy(
            serviceDecelerationMetersPerSecondSquared = 2.0, safetyDistanceMeters = 5.0),
            maximumSpeedMetersPerSecond = 0.1)
        val timing = RouteAllocationTiming(0.25, 0.25, 2.0)
        val request = fixture.ato.routeAllocationRequestHorizonMeters(timing, 0.0)

        assertEquals(15.0025, request, 1e-12)
        assertEquals(RouteBoundary(109.7025), RollingRouteAuthorityPlanner.nextBoundary(
            routeLengthMeters = 200.0,
            headProgressMeters = 94.7,
            requestHorizonMeters = request,
            extensionTriggerMeters = fixture.ato.routeAllocationTriggerDistanceMeters(timing),
            admissionState = RouteAuthorityAdmissionState(RouteBoundary(100.0),
                pending = false, reversalPending = false)
        ))
    }

    @Test
    fun `rolling admission extends before a slow train stops at the global safety margin`() {
        val fixture = fixture("slow-default-margin", atoProfile = profile.copy(
            serviceDecelerationMetersPerSecondSquared = 2.0, safetyDistanceMeters = 5.0),
            maximumSpeedMetersPerSecond = 2.0)
        assertEquals(0.0, fixture.train.speed)
        assertEquals(25.0, fixture.ato.routeAllocationTriggerDistanceMeters(
            RouteAllocationTiming(0.25, 0.25, 2.0)), 1e-12,
            "the stopped train retains a positive reserve beyond the global5m safety margin")
        assertEquals(42.0, fixture.ato.routeAllocationTriggerDistanceMeters(
            RouteAllocationTiming(0.25, 0.25, 40.0)), 1e-12,
            "a larger configured setup threshold is preserved")
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
        fixture.data.wayPoints.add(WayPoint("A", rail.id, originArc))
        fixture.data.wayPoints.add(WayPoint("B", rail.id, originArc + 20.0))
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
        assertTrue(fixture.ato.publishedState.runtimeDiagnostics.constraints.any {
            it.contains("mission/stop/2/0") && it.contains("stoppingMarginMeters=0.0")
        }, "ordinary mission targets must not be shifted backward by the arrival tolerance")
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
            WayPoint("A", currentRail.id, fixture.train.head.location.arcLength)
        )
        fixture.data.wayPoints.add(WayPoint("B", disconnected.id, 20.0))

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
        lateinit var next: CoreRail
        val fixture = fixture("speed-signal", railLengthMeters = 30.0, configureTrack = { world, rail ->
            next = world.createRailForTest(
                straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
            )
            world.onOwnerForTest { CoreRail.connect(rail, next) }
        })
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
        lateinit var next: CoreRail
        val fixture = fixture("layered-speed", railLengthMeters = 30.0, configureTrack = { world, rail ->
            next = world.createRailForTest(
                straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
            )
            world.onOwnerForTest { CoreRail.connect(rail, next) }
        })
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
        fixture.world.onOwnerForTest {
            front.setLocation(fixture.train.head.location.forward(11.0)!!)
            front.commit()
        }

        tickAndPublish(fixture.ato, 1_000.0)

        assertEquals(0.0, fixture.train.speed, 1.0e-9)

        fixture.world.onOwnerForTest {
            front.setLocation(fixture.train.head.location.forward(200.0)!!)
            front.commit()
        }
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
        lateinit var nextRail: CoreRail
        val fixture = fixture("duplicate-rail-object-ref", railLengthMeters = 30.0, configureTrack = { world, rail ->
            nextRail = world.createRailForTest(
                straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(130.0, 0.0, 0.0))
            )
            world.onOwnerForTest { CoreRail.connect(rail, nextRail) }
        })
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
        world.onOwnerForTest { approach.viewAB.selectNextRail(straight.viewAB) }
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
        world.onOwnerForTest { approach.viewAB.selectNextRail(branch.viewAB) }
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

    @OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `formation mission activates only at its exact physical head before any movement`(reversed: Boolean, stale: Boolean) {
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
            val fixture = fixture("formation-origin-$reversed-$stale", physicsCoordinator = physics)
            val train = fixture.train
            if (reversed) fixture.world.onOwnerForTest { train.reverse() }
            val anchor = train.publishedFrame.head
            val rail = train.head.location.rail
            val origin = AtoMissionOrigin.FormationOutput("cycle-0/split", "first", anchor)
            val start = if (anchor.facing == ViewDirection.AB) anchor.arcLengthInAbMeters
                else rail.curve.totalArcLength - anchor.arcLengthInAbMeters
            val mission = AtoMission("formation-origin", 1, origin,
                listOf(AtoMissionLeg(0, AtoMissionPoint.Stop(WayPointRef("target")),
                    PlannedRoute("selected", 1, ExpandedRoute(origin.id, "target",
                        listOf(RouteSegment(RailViewRef(rail.id, anchor.facing), start, start + 5.0)))))))
            assertNull(fixture.data.wayPoints[origin.id])
            assertTrue(fixture.ato.submitMission(mission) is AtoMissionSubmissionResult.Accepted)
            fixture.ato.setMovementAuthority(mission.revision, 5.0)
            assertNull(fixture.ato.currentMission, "submission is queued, not evidence of activation")
            if (stale) fixture.world.onOwnerForTest { train.moveTrainForward(1.0); train.commit() }
            val before = train.publishedFrame.head
            fixture.world.physicsCoordinator.start()
            repeat(2) { fixture.world.physicsCoordinator.stepSimulation(50.0).await() }
            if (stale) {
                assertTrue(fixture.coordinator.isStalled)
                assertTrue(fixture.coordinator.lastStall?.message?.contains("formation Mission origin changed before activation") == true)
                assertNull(fixture.ato.currentMission)
                assertEquals(before, train.publishedFrame.head)
                assertEquals(0.0, train.publishedFrame.speedMetersPerSecond)
            } else {
                assertFalse(fixture.coordinator.isStalled, fixture.coordinator.lastStall.toString())
                assertSame(mission, fixture.ato.currentMission)
                assertTrue(train.publishedFrame.head != before, "the activated mission must drive real Core publication")
                assertEquals(anchor.facing, train.publishedFrame.head.facing)
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `formation retirement clears only consumed mission and preserves later submission`(submitNext: Boolean) {
        val fixture = fixture("retire-formation-$submitNext")
        authorizeMovement(fixture)
        val consumed = checkNotNull(fixture.ato.currentMission)
        fixture.ato.setSpeedLimit("retained-external", 0.7)
        fixture.world.onOwnerForTest {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                fixture.ato.retireMissionForFormation()
            }
            fixture.train.setMovementHeld(true)
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                fixture.ato.retireMissionForFormation()
            }
            fixture.train.setMovementHeld(false)
            val held = fixture.world.restoreFormationMovementHold(fixture.train.id, fixture.train.cars.map { it.id })
                as work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted
            fixture.ato.retireMissionForFormation()
            assertSame(consumed, fixture.ato.currentMission, "retirement is queued")
            if (submitNext) {
                val next = AtoMission(consumed.id, 2, consumed.origin, consumed.legs.map { leg ->
                    AtoMissionLeg(leg.index, leg.point, PlannedRoute(leg.plannedRoute.routeId, 2, leg.plannedRoute.expandedRoute))
                })
                assertTrue(fixture.ato.submitMission(next) is AtoMissionSubmissionResult.Accepted)
            }
            assertTrue(fixture.world.releaseFormationMovementHold(held.hold))
        }
        tickAndPublish(fixture.ato, 0.0)
        assertEquals(if (submitNext) 2L else null, fixture.ato.currentMission?.revision)
        assertSame(fixture.ato, fixture.train.controller)
        assertTrue(fixture.ato.runtimeDiagnostics.constraints.any { it.contains("retained-external") })
        assertFalse(fixture.coordinator.isStalled, fixture.coordinator.lastStall.toString())
    }

    @Test fun `real equipment releases held brakes and stops at station mark within independent authority`() {
        val dynamics = object : work.art1st.javarailways.basic.control.TrainDynamics.TrainProfile {
            override val maxSpeed = 8.0
            override val powerHandles = listOf("P1", "P2")
            override val brakeHandles = listOf("B1", "B2", "EB")
            override val brakeApplyTime = 0.8
            override val brakeReleaseTime = 1.2
            override val adhesionCoefficient = 0.25
            override val tractionJerk = 1.0
            override val brakeJerk = 1.0
            override fun normalizedPowerScale(handle: String) = when(handle) { "P1" -> 0.5; "P2" -> 1.0; else -> 0.0 }
            override fun normalizedBrakeScale(handle: String) = when(handle) { "B1" -> 0.5; "B2", "EB" -> 1.0; else -> 0.0 }
            override fun resistanceForceAt(speed: Double) = 300.0
        }
        val car = object : work.art1st.javarailways.basic.control.TrainDynamics.CarProfile {
            override val mass = 30_000.0
            override val effectiveMass = 32_000.0
            override val motorAdhesionMassRatio = 1.0
            override val brakeMaxForce = 60_000.0
            override val electricBrakeMaxForce = 20_000.0
            override val brakeAdhesionMassRatio = 1.0
            override fun motorForceAt(speed: Double) = 40_000.0
            override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = defaultResistance(speed)
        }
        val safety = AtoSafetyProfile(
            serviceDecelerationMetersPerSecondSquared = work.art1st.javarailways.basic.control.TrainDynamics.flatServiceDeceleration(dynamics, listOf(car), 9.80665),
            reactionTimeSeconds = work.art1st.javarailways.basic.control.HandleTrainActuator.serviceBrakeReactionTimeSeconds(dynamics, listOf(car), 9.80665),
            safetyDistanceMeters = 5.0)
        lateinit var actuator: work.art1st.javarailways.basic.control.HandleTrainActuator
        val fixture = fixture("equipment-station", atoProfile = safety, actuatorFactory = { train ->
            work.art1st.javarailways.basic.control.HandleTrainActuator(train, dynamics, listOf(car), 9.80665).also { made ->
                actuator = made
                repeat(80) { made.step(0.0, work.art1st.javarailways.basic.control.TrainDynamics.commandForHandle(dynamics,
                    work.art1st.javarailways.basic.control.TrainDynamics.strongestServiceBrakeHandle(dynamics)), 0.05) }
            }
        })
        val source = fixture.train.cars.single()
        assertTrue(checkNotNull(fixture.ato.dynamicsDeviceStateFor(source)).pressureKpa > 100.0)
        assertEquals(actuator.dynamicsDeviceStateFor(source), fixture.ato.dynamicsDeviceStateFor(source))
        val rail = fixture.train.head.location.rail
        val origin = fixture.train.head.location.arcLength
        fixture.data.wayPoints.add(WayPoint("equipment-origin", rail.id, origin))
        fixture.data.wayPoints.add(WayPoint("equipment-stop", rail.id, origin + 100.0))
        val accepted = fixture.ato.submitMission(AtoMissionRequest("equipment-mission", 1,
            WayPointRef("equipment-origin"), listOf(AtoMissionPoint.Stop(WayPointRef("equipment-stop"), stationStop = true))))
            as AtoMissionSubmissionResult.Accepted
        assertEquals(100.3, fixture.ato.terminalMovementAuthorityLimitMeters(100.0, true), 1e-12)
        assertEquals(105.0, fixture.ato.terminalMovementAuthorityLimitMeters(100.0, false), 1e-12)
        fixture.ato.setMovementAuthority(accepted.mission.revision, 100.3)
        var peak = 0.0
        val trace = mutableListOf<String>()
        repeat(6000) { tick ->
            tickAndPublish(fixture.ato, 50.0)
            if ((tick < 100 && tick % 10 == 0) || tick % 500 == 0) trace +=
                "tick=$tick speed=${fixture.train.speed} handle=${actuator.selectedHandle} head=${fixture.train.head.location.arcLength - origin} " +
                    "device=${actuator.dynamicsDeviceStateFor(source)} demand=${fixture.ato.runtimeDiagnostics.permittedSpeedMetersPerSecond}"
            assertFalse(fixture.coordinator.isStalled, fixture.coordinator.lastStall.toString())
            val progress = fixture.train.head.location.arcLength - origin
            assertTrue(progress <= 100.3 + 1e-6, "actual head crossed independent authority: $progress")
            peak = maxOf(peak, fixture.train.speed)
        }
        assertTrue(peak > 1.0, "held-brake departure must not remain neutral: peak=$peak\n${trace.joinToString("\n")}")
        assertEquals(100.0, fixture.train.head.location.arcLength - origin, 0.15)
        assertTrue(fixture.ato.isStopped(fixture.train.speed))
    }

    private fun authorizeMovement(fixture: Fixture) {
        val rail = fixture.train.head.location.rail
        val originArc = fixture.train.head.location.arcLength
        val originId = "${fixture.train.id}-mission-origin"
        val targetId = "${fixture.train.id}-mission-target"
        fixture.data.wayPoints.add(WayPoint(originId, rail.id, originArc))
        fixture.data.wayPoints.add(WayPoint(
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
        configureTrack: (CoreWorld, CoreRail) -> Unit = { _, _ -> },
        physicsCoordinator: PhysicsCoordinator? = null,
        atoProfile: AtoSafetyProfile = profile,
        maximumSpeedMetersPerSecond: Double = 10.0,
        actuatorFactory: ((work.art1st.javarailways.core.physics.train.CoreTrain) -> work.art1st.javarailways.basic.api.ato.TrainActuator)? = null,
        inspectionMaximumLeadTimeSeconds: (String) -> Double = { 0.0 }
    ): Fixture {
        val world = createTestWorld(physicsCoordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(railLengthMeters, segmentCount = 1)
        )
        configureTrack(world, rail)
        val train = createTrainWithConfig(id, rail, world, listOf(10.0))
        val coordinator = AtoWorldCoordinator("world-$id", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val ato = SimpleATO(
            train = train,
            world = world,
            actuator = actuatorFactory?.invoke(train) ?: KinematicTrainActuator(maximumSpeedMetersPerSecond, atoProfile),
            safetyProfile = atoProfile,
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
