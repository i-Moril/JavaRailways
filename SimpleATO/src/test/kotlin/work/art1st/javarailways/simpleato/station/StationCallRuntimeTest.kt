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
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
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

        val result = fixture.ato.submitStationCall(fixture.request(1L))

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
        safetyDistanceMeters = 1.0,
        stopToleranceMeters = 0.25
    )

    @Test
    fun `berth detector requires position and speed together`() {
        assertFalse(StationBerthDetector.isBerthed(0.05, 1.0, 0.1))
        assertFalse(StationBerthDetector.isBerthed(0.05, Double.MIN_VALUE, 0.1))
        assertFalse(StationBerthDetector.isBerthed(0.2, 0.0, 0.1))
        assertTrue(StationBerthDetector.isBerthed(0.05, 0.0, 0.1))
    }

    @Test
    fun `station call revision must match the latest accepted mission`() {
        val fixture = fixture("revision")
        val mission = fixture.ato.submitMission(
            AtoMissionRequest(
                id = "tt/service",
                revision = 2L,
                origin = WayPointRef("A"),
                points = listOf(AtoMissionPoint.Stop(WayPointRef("B")))
            )
        )
        assertTrue(mission is AtoMissionSubmissionResult.Accepted)

        val result = fixture.ato.submitStationCall(fixture.request(1L))

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
        val accepted = fixture.ato.submitStationCall(fixture.request(1L))
        assertTrue(accepted is StationCallSubmissionResult.Accepted)
        fixture.ato.setMovementAuthority(
            1L,
            fixture.ato.terminalMovementAuthorityLimitMeters(
                mission.mission.legs.single().plannedRoute.expandedRoute.totalLength
            )
        )
        assertTrue(
            fixture.ato.submitStationCall(fixture.request(1L)) is
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
    fun `removing an active observed berth stalls only its world`() {
        val fixture = fixture("removed")
        val otherCoordinator = AtoWorldCoordinator("other", reportSink = {})
        assertTrue(fixture.ato.submitMission(AtoMissionRequest(
            id = "tt/service",
            revision = 1L,
            origin = WayPointRef("A"),
            points = listOf(AtoMissionPoint.Stop(WayPointRef("C")))
        )) is AtoMissionSubmissionResult.Accepted)
        assertTrue(fixture.ato.submitStationCall(fixture.request(1L)) is StationCallSubmissionResult.Accepted)
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

    private fun fixture(id: String): Fixture {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val coordinator = AtoWorldCoordinator("station-$id", reportSink = {})
        val data = BasicWorldData(safetyInterlock = coordinator.safetyInterlock)
        TestSimpleATOWorldDataRegistry.register(world, data, coordinator)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T-$id", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", rail.id, originArc + 10.0))
        data.wayPoints.add(WayPoint("C", rail.id, originArc + 55.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef = berthRef,
            location = train.head.location.forward(25.0)!!,
            approachDirection = ViewDirection.AB,
            positionToleranceMeters = 0.15,
            defaultDwellTicks = 20L
        )
        val station = Station(stationId, "Central", listOf(berthRef))
        world.simpleAtoWorldData.stations.register(station, listOf(berth))
        val ato = SimpleATO(train, world, KinematicTrainActuator(6.0, profile), profile) { 0.0 }
        train.setControllerForTest(ato)
        return Fixture(world, data, train, station, berth, ato)
    }

    private inner class Fixture(
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val data: BasicWorldData,
        val train: CoreTrain,
        val station: Station,
        val berth: StationBerth,
        val ato: SimpleATO
    ) {
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
