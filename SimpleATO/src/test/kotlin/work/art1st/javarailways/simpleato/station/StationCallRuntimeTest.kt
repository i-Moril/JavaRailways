package work.art1st.javarailways.simpleato.station

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.setControllerForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.mission.AtoMissionPoint
import work.art1st.javarailways.simpleato.api.mission.AtoMissionRequest
import work.art1st.javarailways.simpleato.api.mission.AtoMissionSubmissionResult
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.api.station.DwellPlan
import work.art1st.javarailways.simpleato.api.station.Station
import work.art1st.javarailways.simpleato.api.station.StationBerth
import work.art1st.javarailways.simpleato.api.station.StationBerthDetector
import work.art1st.javarailways.simpleato.api.station.StationBerthRef
import work.art1st.javarailways.simpleato.api.station.StationCall
import work.art1st.javarailways.simpleato.api.station.StationCallNotification
import work.art1st.javarailways.simpleato.api.station.StationCallNotificationType
import work.art1st.javarailways.simpleato.api.station.StationCallRequest
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionFailure
import work.art1st.javarailways.simpleato.api.station.StationCallSubmissionResult
import work.art1st.javarailways.simpleato.api.station.StationCallResolver
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionResult
import work.art1st.javarailways.simpleato.api.mission.AtoMission
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolver
import work.art1st.javarailways.simpleato.api.mission.AtoMissionResolutionResult
import work.art1st.javarailways.simpleato.timetable.stationCallRoutePrefix
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotProvider
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.util.AsyncTickTestSupport

class StationCallRuntimeTest {
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

    @Test
    fun `station call without an accepted mission is rejected`() {
        val fixture = fixture("no-mission")

        val selected = (AtoMissionResolver(fixture.world).resolve(AtoMissionRequest("unsubmitted", 1L,
            WayPointRef("A"), listOf(AtoMissionPoint.Stop(WayPointRef("C"))))) as AtoMissionResolutionResult.Success).mission
        val result = fixture.ato.submitStationCall(fixture.resolvedCall(1L, selected))

        assertEquals(
            StationCallResolutionFailure.MISSION_REVISION_MISMATCH,
            (result as StationCallSubmissionResult.ResolutionFailure).reason
        )
    }

    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        // The test actuator integrates in 50ms steps; include its discrete control response.
        reactionTimeSeconds = 0.1,
        safetyDistanceMeters = 1.0
    )

    @Test
    fun `berth detector requires position and speed together`() {
        assertFalse(StationBerthDetector.isBerthed(0.05, 1.0))
        assertFalse(StationBerthDetector.isBerthed(0.05, Double.MIN_VALUE))
        assertFalse(StationBerthDetector.isBerthed(0.31, 0.0))
        assertTrue(StationBerthDetector.isBerthed(0.05, 0.0))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = [0.2995, 0.3005])
    fun `unberthed overrun uses the same position boundary as berth acceptance`(overrun: Double) {
        val fixture = fixture("overrun-$overrun")
        val mission = fixture.ato.submitMission(AtoMissionRequest(
            id = "tt/service", revision = 1L, origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
        )) as AtoMissionSubmissionResult.Accepted
        assertTrue(fixture.ato.submitStationCall(fixture.resolvedCall(1L, mission.mission)) is
            StationCallSubmissionResult.Accepted)
        fixture.tick(0.0)

        fixture.placeHeadBeyondBerth(overrun)
        fixture.tick(0.0)

        val shouldStall = !StationBerthDetector.isBerthed(-overrun, 0.0)
        val coordinator = fixture.world.simpleAtoWorldData.atoCoordinator
        assertEquals(shouldStall, coordinator.isStalled, coordinator.lastStall?.message)
        if (shouldStall) {
            assertTrue(coordinator.lastStall?.message?.contains("before BERTHED") == true)
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = [0.2995, 0.3005])
    fun `departure is reported immediately outside the berth position boundary`(overrun: Double) {
        val fixture = fixture("departure-$overrun")
        val mission = fixture.ato.submitMission(AtoMissionRequest(
            id = "tt/service", revision = 1L, origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
        )) as AtoMissionSubmissionResult.Accepted
        assertTrue(fixture.ato.submitStationCall(fixture.resolvedCall(1L, mission.mission)) is
            StationCallSubmissionResult.Accepted)
        fixture.ato.setMovementAuthority(1L, fixture.ato.terminalMovementAuthorityLimitMeters(
            mission.mission.legs.single().plannedRoute.expandedRoute.totalLength
        ))
        var berthed = false
        for (tick in 0 until 2_000) {
            fixture.tick()
            if (fixture.ato.drainStationCallNotifications().any { it.type == StationCallNotificationType.BERTHED }) {
                berthed = true
                break
            }
        }
        assertTrue(berthed, fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message)

        fixture.ato.grantStationDeparture("call-1", 1L)
        fixture.placeHeadBeyondBerth(overrun)
        fixture.tick(0.0)

        val departed = fixture.ato.drainStationCallNotifications().any {
            it.type == StationCallNotificationType.DEPARTED
        }
        assertEquals(!StationBerthDetector.isBerthed(-overrun, 0.0), departed)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
    }

    @Test
    fun `station call revision must match the latest accepted mission`() {
        val fixture = fixture("revision")
        val mission = fixture.ato.submitMission(
            AtoMissionRequest(
                id = "tt/service",
                revision = 2L,
                origin = WayPointRef("A"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
            )
        )
        assertTrue(mission is AtoMissionSubmissionResult.Accepted)

        val result = fixture.ato.submitStationCall(fixture.resolvedCall(1L, (mission as AtoMissionSubmissionResult.Accepted).mission))

        assertEquals(
            StationCallResolutionFailure.MISSION_REVISION_MISMATCH,
            (result as StationCallSubmissionResult.ResolutionFailure).reason
        )
    }

    @Test
    fun `station call reports each transition once and holds until departure grant`() {
        val fixture = fixture("transitions")
        val mission = fixture.ato.submitMission(
            AtoMissionRequest(
                id = "tt/service",
                revision = 1L,
                origin = WayPointRef("A"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
            )
        ) as AtoMissionSubmissionResult.Accepted
        val accepted = fixture.ato.submitStationCall(fixture.resolvedCall(1L, mission.mission))
        assertTrue(accepted is StationCallSubmissionResult.Accepted)
        fixture.ato.setMovementAuthority(
            1L,
            fixture.ato.terminalMovementAuthorityLimitMeters(
                mission.mission.legs.single().plannedRoute.expandedRoute.totalLength
            )
        )
        assertTrue(
            fixture.ato.submitStationCall(fixture.resolvedCall(1L, mission.mission)) is
                StationCallSubmissionResult.StaleRevision
        )

        val notifications = mutableListOf<StationCallNotification>()
        for (tick in 0 until 2_000) {
            fixture.tick()
            notifications += fixture.ato.drainStationCallNotifications()
            if (tick == 0) {
                val stopping = fixture.ato.runtimeDiagnostics.constraints.single {
                    it.contains("namespace=station") && it.contains("StopConstraint")
                }
                assertTrue(stopping.contains("stoppingMarginMeters=0.0"),
                    "station stopping target has no advance despite fixture stopTolerance0.25 and berth tolerance0.15: $stopping")
            }
            if (notifications.any { it.type == StationCallNotificationType.BERTHED }) break
        }

        assertEquals(
            listOf(StationCallNotificationType.APPROACHING, StationCallNotificationType.BERTHED),
            notifications.map(StationCallNotification::type),
            "speed=${fixture.train.speed} head=${fixture.train.head.location.arcLengthIn(ViewDirection.AB)} " +
                "berth=${fixture.berth.location.arcLengthIn(ViewDirection.AB)} " +
                "stall=${fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message}"
        )
        assertEquals(0.0, fixture.train.speed)
        val held = fixture.actuator.controlState(fixture.train.speed)
        assertTrue(held.lastCommand.brakeRatio > 0.0)
        assertTrue(held.actualBrakeRatio > 0.999999)
        assertTrue(held.devices.all { it.pressureKpa > 0.0 })
        assertEquals(
            fixture.berth.location.arcLengthIn(ViewDirection.AB),
            fixture.train.head.location.arcLengthIn(ViewDirection.AB),
            fixture.berth.positionToleranceMeters
        )
        repeat(20) { fixture.tick() }
        assertEquals(0.0, fixture.train.speed, 1.0e-9)
        assertTrue(fixture.ato.drainStationCallNotifications().isEmpty())

        fixture.ato.grantStationDeparture("call-1", 1L)
        for (tick in 0 until 2_000) {
            fixture.tick()
            notifications += fixture.ato.drainStationCallNotifications()
            if (notifications.any { it.type == StationCallNotificationType.DEPARTED }) break
        }

        assertEquals(
            listOf(
                StationCallNotificationType.APPROACHING,
                StationCallNotificationType.BERTHED,
                StationCallNotificationType.DEPARTED
            ),
            notifications.map(StationCallNotification::type)
        )
        assertNull(fixture.ato.currentStationCall)
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(notifications.zipWithNext().all { (first, second) ->
            second.physicsEpoch >= first.physicsEpoch
        })
    }

    @Test
    fun `station call reuses signed Mission progress when the head rebounds behind its origin`() {
        val fixture = fixture("origin-rebound")
        val mission = fixture.ato.submitMission(AtoMissionRequest(
            id = "tt/service",
            revision = 1L,
            origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
        )) as AtoMissionSubmissionResult.Accepted
        val resolved = fixture.resolvedCall(1L, mission.mission)
        assertTrue(fixture.ato.submitStationCall(resolved) is StationCallSubmissionResult.Accepted)
        fixture.ato.setMovementAuthority(1L, resolved.stoppingDistanceMeters)

        val frame = fixture.train.publishedFrame
        val rail = fixture.train.head.location.rail
        val initialHeadArc = frame.head.arcLengthInAbMeters
        val stamp = RailwayStateStamp(
            fixture.world.simpleAtoWorldData.atoCoordinator.worldId,
            frame.physicsEpoch,
            fixture.world.topologyState.revision
        )
        val physicalRoute = RouteSnapshot(
            "origin-rebound",
            1L,
            stamp.topologyRevision,
            listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 0.0, rail.curve.totalArcLength))
        )
        fixture.ato.bindTrainRestrictionRoute(
            observationStamp = stamp,
            route = FrozenMissionRoute(
                FrozenRouteSelectionResult.Selected(fixture.train.id, stamp, physicalRoute),
                headDistanceFromRouteStartMeters = initialHeadArc,
                missionHeadProgressAtSelectionMeters = 0.0
            ),
            missionRevision = mission.mission.revision,
            signalSnapshots = TrainRestrictionSnapshotProvider { emptyList() }
        )

        val reboundMeters = 0.002224686929650943
        fixture.world.onOwnerForTest {
            fixture.train.setLocation(
                work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(
                    rail.viewAB,
                    initialHeadArc - reboundMeters
                )
            )
            fixture.train.commit()
        }

        fixture.tick(0.0)

        assertFalse(
            fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
            fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message
        )
        assertEquals(-reboundMeters, fixture.ato.runtimeDiagnostics.projectedProgressMeters!!, 1.0e-12)
        assertEquals(
            resolved.stoppingDistanceMeters + reboundMeters,
            fixture.ato.runtimeDiagnostics.stationPositionErrorMeters!!,
            1.0e-12
        )
    }

    @Test
    fun `removing an active observed berth stalls only its world`() {
        val fixture = fixture("removed")
        val otherCoordinator = AtoWorldCoordinator("other", reportSink = {})
        val mission = fixture.ato.submitMission(AtoMissionRequest(
            id = "tt/service",
            revision = 1L,
            origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
        )) as AtoMissionSubmissionResult.Accepted
        assertTrue(fixture.ato.submitStationCall(fixture.resolvedCall(1L, mission.mission)) is StationCallSubmissionResult.Accepted)
        fixture.tick()
        assertEquals(
            StationCallNotificationType.APPROACHING,
            fixture.ato.drainStationCallNotifications().single().type
        )

        fixture.world.simpleAtoWorldData.stations.remove(fixture.station.id)
        fixture.tick()

        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled)
        assertTrue(fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message?.contains("StationBerth") == true)
        assertFalse(otherCoordinator.isStalled)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = [0.0, 2.0])
    fun `multi leg station target retains selected prefix value despite distinct projection rounding`(offset: Double) {
        val boundaries = listOf(19.306968958376174, 1087.5810622639672, 1976.8556093617758,
            3110.751620704097, 3692.9318303905316)
        val splits = listOf(154.62172305288743, 1200.222534538841, 2120.48247526167, 3184.494466670386)
        val fixture = fixture("rounded-prefix", 4000.0, boundaries.first(), boundaries.last())
        val rail = fixture.train.head.location.rail
        val view = work.art1st.javarailways.basic.api.route.RailViewRef(rail.id, ViewDirection.AB)
        val legs = splits.indices.map { index ->
            val from = if (index == 0) "A" else "via-$index"
            val to = "via-${index + 1}"
            fixture.data.wayPoints.add(WayPoint(to, rail.id, boundaries[index + 1]))
            val point = if (index == splits.lastIndex) AtoMissionPoint.Stop(WayPointRef(to), offset)
                else AtoMissionPoint.Via(WayPointRef(to))
            work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg(index, point,
                work.art1st.javarailways.basic.api.route.PlannedRoute("leg-$index", 1L,
                    work.art1st.javarailways.basic.api.route.ExpandedRoute(from, to, listOf(
                        work.art1st.javarailways.basic.api.route.RouteSegment(view, boundaries[index], splits[index]),
                        work.art1st.javarailways.basic.api.route.RouteSegment(view, splits[index], boundaries[index + 1])))))
        }
        val mission = AtoMission("rounded-prefix", 1L, WayPointRef("A"), legs)
        val resolved = fixture.resolvedCall(1L, mission, offset)
        val layout = work.art1st.javarailways.simpleato.routing.MissionRouteLayout(fixture.world, mission)
        val target = work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot(rail.id,
            ViewDirection.AB, boundaries.last())
        assertEquals(3673.6248614321557, resolved.berthDistanceMeters)
        assertEquals(3673.6248614321553, layout.project(target))
        assertNull(layout.project(target, resolved.berthDistanceMeters), "the old static reprojection rejects this exact endpoint")
        assertTrue(resolved.isTerminalOf(mission))
        assertFalse(resolved.copy(stoppingOffsetMetersBefore = offset + 1.0).isTerminalOf(mission))
        assertTrue(fixture.ato.submitMission(mission) is AtoMissionSubmissionResult.Accepted)
        assertTrue(fixture.ato.submitStationCall(resolved) is StationCallSubmissionResult.Accepted)
        fixture.ato.setMovementAuthority(1L, resolved.berthDistanceMeters + 1.0)
        fixture.tick()
        assertFalse(fixture.world.simpleAtoWorldData.atoCoordinator.isStalled,
            fixture.world.simpleAtoWorldData.atoCoordinator.lastStall?.message)
        val diagnostics = fixture.ato.runtimeDiagnostics
        assertEquals(resolved.berthDistanceMeters, diagnostics.stationTargetProgressMeters)
        assertEquals(resolved.stoppingDistanceMeters - diagnostics.projectedProgressMeters!!,
            diagnostics.stationPositionErrorMeters)
        assertTrue(diagnostics.constraints.any { it.contains("stoppingMarginMeters=0.3") },
            "exact terminal occurrence uses the station authority margin")
    }

    @Test
    fun `same rail endpoint on an earlier occurrence is not the terminal station`() {
        val fixture = fixture("repeated-target")
        val original = (AtoMissionResolver(fixture.world).resolve(AtoMissionRequest("first", 1L,
            WayPointRef("A"), listOf(AtoMissionPoint.Stop(WayPointRef("C"))))) as AtoMissionResolutionResult.Success).mission
        val first = fixture.resolvedCall(1L, original)
        assertFalse(first.isTerminalOf(original), "the station lies inside the final leg")
        val route = first.plannedRoute.expandedRoute
        val outward = route.segments.single()
        val railLength = fixture.train.head.location.rail.curve.totalArcLength
        val back = work.art1st.javarailways.basic.api.route.RouteSegment(
            outward.railViewRef.copy(direction = ViewDirection.BA), railLength - outward.endArcLength,
            railLength - outward.startArcLength)
        val traversals = listOf(outward, back, outward)
        val mission = AtoMission("repeat", 1L, WayPointRef("A"), traversals.mapIndexed { index, segment ->
            val from = if (index == 0) "A" else "turn-$index"
            val to = "turn-${index + 1}"
            work.art1st.javarailways.simpleato.api.mission.AtoMissionLeg(index,
                if (index == 2) AtoMissionPoint.Stop(WayPointRef(to)) else AtoMissionPoint.Via(WayPointRef(to)),
                work.art1st.javarailways.basic.api.route.PlannedRoute("repeat-$index", 1L,
                    work.art1st.javarailways.basic.api.route.ExpandedRoute(from, to, listOf(segment))))
        })
        assertEquals(outward, mission.legs.last().plannedRoute.expandedRoute.segments.last())
        assertFalse(first.isTerminalOf(mission), "same rail, direction and endpoint do not identify the last occurrence")
        val last = first.copy(plannedRoute = first.plannedRoute.copy(expandedRoute =
            work.art1st.javarailways.basic.api.route.ExpandedRoute(route.fromWayPointId, route.toWayPointId, traversals)))
        assertTrue(last.isTerminalOf(mission))
        assertFalse(last.isTerminalOf(AtoMission("new-revision", 2L, mission.origin,
            mission.legs.map { it.copy(plannedRoute = it.plannedRoute.copy(revision = 2L)) })))
    }

    private fun fixture(id: String, railLength: Double = 120.0, originOverride: Double? = null,
        berthOverride: Double? = null): Fixture {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val coordinator = AtoWorldCoordinator("station-$id", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(railLength, segmentCount = 1)
        )
        val train = createTrainWithConfig("T-$id", rail, world, listOf(10.0))
        if (originOverride != null) work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
            world.physicsCoordinator.processBetweenFrames {
                train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, originOverride))
                train.commit()
            }
        }
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 10.0))
        data.wayPoints.add(WayPoint("C", rail.id, originArc + 55.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef = berthRef,
            location = if (berthOverride == null) train.head.location.forward(25.0)!! else
                work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, berthOverride),
            approachDirection = ViewDirection.AB,
            defaultDwellTicks = 20L
        )
        val station = Station(stationId, listOf(berthRef))
        world.simpleAtoWorldData.stations.register(station, listOf(berth))
        val actuator = KinematicTrainActuator(6.0, profile)
        val ato = SimpleATO(train, world, actuator, profile) { 0.0 }
        train.setControllerForTest(ato)
        return Fixture(world, data, train, station, berth, ato, actuator)
    }

    private inner class Fixture(
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val data: BasicWorldData,
        val train: CoreTrain,
        val station: Station,
        val berth: StationBerth,
        val ato: SimpleATO,
        val actuator: KinematicTrainActuator
    ) {
        fun placeHeadBeyondBerth(distance: Double) = world.onOwnerForTest {
            train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(
                train.head.location.rail.viewAB,
                berth.location.arcLengthIn(ViewDirection.AB) + distance
            ))
            train.commit()
        }

        fun resolvedCall(revision: Long, mission: AtoMission, offset: Double = 0.0): work.art1st.javarailways.simpleato.api.station.ResolvedStationCall {
            val request = request(revision).let { it.copy(call = it.call.copy(stoppingOffsetMetersBeforeOverride = offset)) }
            val prefix = requireNotNull(stationCallRoutePrefix(world, mission.origin.id, request.call,
                mission.legs.map { it.plannedRoute.expandedRoute }))
            return (StationCallResolver(world).resolve(request, train.cars.sumOf { it.length },
                retainedRoute = prefix, retainedTopology = world.topologyState) as StationCallResolutionResult.Success).resolved
        }

        fun request(revision: Long): StationCallRequest = StationCallRequest(
            serviceRunId = "tt/service",
            origin = WayPointRef("A"),
            call = StationCall(
                callId = "call-$revision",
                missionRevision = revision,
                stationId = station.id,
                berthRef = berth.berthRef,
                dwellPlan = DwellPlan(20L)
            )
        )

        fun tick(deltaMillis: Double = 50.0) {
            AsyncTickTestSupport.run { ato.tick(deltaMillis) }
            ato.commit()
        }
    }
}
