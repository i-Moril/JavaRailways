package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.mission.WayPointRef
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.simpleato.simpleAtoWorldData

class StationCallResolverTest {
    @Test
    fun `resolves explicit berth direction offset and route revision`() {
        val fixture = fixture()
        val call = fixture.call(
            revision = 7L,
            stoppingOffsetMetersBeforeOverride = 2.5,
            doorSideOverride = DoorSide.RIGHT
        )
        val result = StationCallResolver(fixture.world).resolve(
            StationCallRequest("tt/service", WayPointRef("A"), call)
        ) as StationCallResolutionResult.Success

        assertEquals(7L, result.resolved.plannedRoute.revision)
        assertEquals(ViewDirection.AB, result.resolved.plannedRoute.expandedRoute.segments.last().railViewRef.direction)
        assertEquals(2.5, result.resolved.stoppingOffsetMetersBefore, 1.0e-9)
        assertEquals(DoorSide.RIGHT, result.resolved.doorSide)
        assertEquals(fixture.berth.berthRef, result.resolved.berthRef)
    }

    @Test
    fun `reports missing origin station and berth before route compilation`() {
        val fixture = fixture()
        val resolver = StationCallResolver(fixture.world)
        val call = fixture.call(1L)

        val missingOrigin = resolver.resolve(
            StationCallRequest("run", WayPointRef("missing"), call)
        ) as StationCallResolutionResult.Failure
        assertEquals(StationCallResolutionFailure.ORIGIN_UNRESOLVED, missingOrigin.reason)

        val missingBerthRef = StationBerthRef(fixture.station.id, "missing")
        val missingBerth = resolver.resolve(
            StationCallRequest("run", WayPointRef("A"), fixture.call(2L, berthRef = missingBerthRef))
        ) as StationCallResolutionResult.Failure
        assertEquals(StationCallResolutionFailure.BERTH_UNRESOLVED, missingBerth.reason)

        fixture.world.simpleAtoWorldData.stations.remove(fixture.station.id)
        val missingStation = resolver.resolve(
            StationCallRequest("run", WayPointRef("A"), call)
        ) as StationCallResolutionResult.Failure
        assertEquals(StationCallResolutionFailure.STATION_UNRESOLVED, missingStation.reason)
    }

    @Test
    fun `distinguishes wrong approach direction from unreachable route`() {
        val wrongDirection = fixture(approachDirection = ViewDirection.BA)
        val directionFailure = StationCallResolver(wrongDirection.world).resolve(
            StationCallRequest("run", WayPointRef("A"), wrongDirection.call(1L))
        ) as StationCallResolutionResult.Failure
        assertEquals(
            StationCallResolutionFailure.BERTH_DIRECTION_UNAVAILABLE,
            directionFailure.reason
        )

        val fixture = fixture(registerStation = false)
        val disconnected = createStraightRailFromSegments(
            fixture.world,
            PrimeCurveGenerator.buildStraight(60.0, segmentCount = 1)
        )
        val berthRef = StationBerthRef(fixture.station.id, "disconnected")
        val berthTrain = createTrainWithConfig("berth", disconnected, fixture.world, listOf(10.0))
        val berth = StationBerth(
            berthRef,
            berthTrain.head.location,
            approachDirection = ViewDirection.AB
        )
        val station = Station(fixture.station.id, "Central", listOf(berthRef))
        fixture.world.simpleAtoWorldData.stations.register(station, listOf(berth))
        val unreachableCall = fixture.call(2L, berthRef = berthRef)
        val routeFailure = StationCallResolver(fixture.world).resolve(
            StationCallRequest("run", WayPointRef("A"), unreachableCall)
        ) as StationCallResolutionResult.Failure
        assertEquals(StationCallResolutionFailure.ROUTE_UNAVAILABLE, routeFailure.reason)
    }

    @Test
    fun `rejects a train longer than the explicit berth`() {
        val fixture = fixture()
        val result = StationCallResolver(fixture.world).resolve(
            StationCallRequest("run", WayPointRef("A"), fixture.call(1L)),
            trainLengthMeters = 101.0
        ) as StationCallResolutionResult.Failure

        assertEquals(StationCallResolutionFailure.TRAIN_TOO_LONG, result.reason)
    }

    @Test
    fun `rejects a removed berth retained by an active station record`() {
        val fixture = fixture()
        fixture.berth.markRemoved()

        val result = StationCallResolver(fixture.world).resolve(
            StationCallRequest("run", WayPointRef("A"), fixture.call(1L))
        ) as StationCallResolutionResult.Failure

        assertEquals(StationCallResolutionFailure.BERTH_REMOVED, result.reason)
        assertTrue(result.message.contains("removed"))
    }

    private fun fixture(
        approachDirection: ViewDirection = ViewDirection.AB,
        registerStation: Boolean = true
    ): Fixture {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        data.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef = berthRef,
            location = train.head.location.forward(35.0)!!,
            approachDirection = approachDirection,
            stoppingOffsetMetersBefore = 1.0,
            positionToleranceMeters = 0.2,
            usableLengthMeters = 100.0,
            defaultDwellTicks = 40L,
            releaseDistanceMetersAfter = 4.0,
            doorSide = DoorSide.LEFT
        )
        val station = Station(stationId, "Central", listOf(berthRef), defaultDwellTicks = 20L)
        if (registerStation) world.simpleAtoWorldData.stations.register(station, listOf(berth))
        return Fixture(world, data, station, berth)
    }

    private data class Fixture(
        val world: work.art1st.javarailways.core.physics.CoreWorld,
        val data: BasicWorldData,
        val station: Station,
        val berth: StationBerth
    ) {
        fun call(
            revision: Long,
            berthRef: StationBerthRef = berth.berthRef,
            stoppingOffsetMetersBeforeOverride: Double? = null,
            doorSideOverride: DoorSide? = null
        ): StationCall = StationCall(
            callId = "call-$revision",
            missionRevision = revision,
            stationId = berthRef.stationId,
            berthRef = berthRef,
            plannedArrivalTick = 10L,
            dwellPlan = DwellPlan(20L, plannedDepartureTick = 30L),
            stoppingOffsetMetersBeforeOverride = stoppingOffsetMetersBeforeOverride,
            doorSideOverride = doorSideOverride
        )
    }
}
