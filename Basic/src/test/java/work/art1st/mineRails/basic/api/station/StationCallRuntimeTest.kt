package work.art1st.mineRails.basic.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.ato.AtoSafetyProfile
import work.art1st.mineRails.basic.api.ato.AtoWorldCoordinator
import work.art1st.mineRails.basic.api.ato.KinematicTrainActuator
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.AsyncTickTestSupport

class StationCallRuntimeTest {
    private val profile = AtoSafetyProfile(
        accelerationMetersPerSecondSquared = 1.0,
        serviceDecelerationMetersPerSecondSquared = 1.0,
        reactionTimeSeconds = 0.0,
        safetyDistanceMeters = 1.0,
        stopToleranceMeters = 0.25,
        stoppedSpeedThresholdMetersPerSecond = 0.05
    )

    @Test
    fun `berth detector requires position and speed together`() {
        assertFalse(StationBerthDetector.isBerthed(0.05, 1.0, 0.1, 0.05))
        assertFalse(StationBerthDetector.isBerthed(0.2, 0.0, 0.1, 0.05))
        assertTrue(StationBerthDetector.isBerthed(0.05, 0.0, 0.1, 0.05))
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
            if (notifications.any { it.type == StationCallNotificationType.BERTHED }) break
        }

        assertEquals(
            listOf(StationCallNotificationType.APPROACHING, StationCallNotificationType.BERTHED),
            notifications.map(StationCallNotification::type),
            "speed=${fixture.train.speed} head=${fixture.train.head.location.arcLengthIn(ViewDirection.AB)} " +
                "berth=${fixture.berth.location.arcLengthIn(ViewDirection.AB)} " +
                "stall=${fixture.data.atoCoordinator.lastStall?.message}"
        )
        assertEquals(0.0, fixture.train.speed, profile.stoppedSpeedThresholdMetersPerSecond)
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
        assertFalse(fixture.data.atoCoordinator.isStalled)
        assertTrue(notifications.zipWithNext().all { (first, second) ->
            second.physicsEpoch >= first.physicsEpoch
        })
    }

    @Test
    fun `removing an active observed berth stalls only its world`() {
        val fixture = fixture("removed")
        val otherCoordinator = AtoWorldCoordinator("other", reportSink = {})
        assertTrue(fixture.ato.submitStationCall(fixture.request(1L)) is StationCallSubmissionResult.Accepted)
        fixture.tick()
        assertEquals(
            StationCallNotificationType.APPROACHING,
            fixture.ato.drainStationCallNotifications().single().type
        )

        fixture.data.stations.remove(fixture.station.id)
        fixture.tick()

        assertTrue(fixture.data.atoCoordinator.isStalled)
        assertTrue(fixture.data.atoCoordinator.lastStall?.message?.contains("StationBerth") == true)
        assertFalse(otherCoordinator.isStalled)
    }

    private fun fixture(id: String): Fixture {
        val world = createTestWorld()
        val coordinator = AtoWorldCoordinator("station-$id", reportSink = {})
        val data = BasicWorldData(atoCoordinator = coordinator)
        TestBasicWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T-$id", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        data.wayPoints.add(WayPoint("B", "B", rail.id, originArc + 10.0))
        data.wayPoints.add(WayPoint("C", "C", rail.id, originArc + 55.0))
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
        data.stations.register(station, listOf(berth))
        val ato = SimpleATO(train, world, KinematicTrainActuator(6.0, profile), profile)
        train.setController(ato)
        return Fixture(data, train, station, berth, ato)
    }

    private inner class Fixture(
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
