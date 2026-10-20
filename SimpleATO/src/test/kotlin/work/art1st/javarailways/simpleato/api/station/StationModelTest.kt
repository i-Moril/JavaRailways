package work.art1st.javarailways.simpleato.api.station

import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.AfterEach
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

class StationModelTest {
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
    fun `station values reject invalid identities distances and times`() {
        val location = fixture("invalid").location
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")

        assertThrows(IllegalArgumentException::class.java) { StationId(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            StationBerthRef(stationId, "bad/id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationBerth(berthRef, location, positionToleranceMeters = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationBerth(berthRef, location, usableLengthMeters = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationBerth(berthRef, location, defaultDwellTicks = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DwellPlan(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationCall(
                callId = "call",
                missionRevision = -1L,
                stationId = stationId,
                berthRef = berthRef,
                dwellPlan = DwellPlan(0L)
            )
        }
    }

    @Test
    fun `station defensively copies berth refs and dwell plan resolves departure`() {
        val stationId = StationId("central")
        val mutableRefs = mutableListOf(
            StationBerthRef(stationId, "one"),
            StationBerthRef(stationId, "two")
        )
        val station = Station(stationId, "Central", mutableRefs, defaultDwellTicks = 40L)
        mutableRefs.clear()

        assertEquals(2, station.berthRefs.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (station.berthRefs as MutableList).clear()
        }
        assertEquals(150L, DwellPlan(20L, plannedDepartureTick = 150L).earliestDepartureTick(100L))
        assertEquals(170L, DwellPlan(70L, plannedDepartureTick = 120L).earliestDepartureTick(100L))
        assertEquals(Long.MAX_VALUE, DwellPlan(10L).earliestDepartureTick(Long.MAX_VALUE - 5L))

        val berth = StationBerth(
            station.berthRefs.first(),
            fixture("dwell").location,
            defaultDwellTicks = 60L
        )
        assertEquals(60L, DwellPlanResolver.resolve(station, berth).minimumDwellTicks)
        assertEquals(
            25L,
            DwellPlanResolver.resolve(station, berth, minimumDwellTicksOverride = 25L)
                .minimumDwellTicks
        )
    }

    @Test
    fun `station store atomically owns passive berth rail objects`() {
        val fixture = fixture("store")
        val data = BasicWorldData()
        val stations = StationStore(data.railObjects)
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(
            berthRef = berthRef,
            location = fixture.location,
            approachDirection = ViewDirection.AB,
            stoppingOffsetMetersBefore = 1.5,
            positionToleranceMeters = 0.2,
            usableLengthMeters = 120.0,
            defaultDwellTicks = 100L,
            releaseDistanceMetersAfter = 5.0,
            doorSide = DoorSide.LEFT
        )
        val station = Station(stationId, "Central", listOf(berthRef), defaultDwellTicks = 80L)

        stations.register(station, listOf(berth))

        assertSame(station, stations[stationId])
        assertSame(berth, stations.berth(berthRef))
        assertSame(berth, data.railObjects.get(berth.ref))
        assertEquals(listOf(berth), data.railObjects.objectsOnRail(fixture.railId))
        assertEquals(RailObjectLifecycleState.ACTIVE, berth.lifecycleState)
        assertThrows(IllegalStateException::class.java) {
            stations.register(station, listOf(berth))
        }

        assertSame(station, stations.remove(stationId))
        assertNull(stations[stationId])
        assertNull(stations.berth(berthRef))
        assertNull(data.railObjects.get(berth.ref))
        assertEquals(RailObjectLifecycleState.REMOVED, berth.lifecycleState)
    }

    @Test
    fun `same station identity remains isolated between worlds`() {
        val firstFixture = fixture("first")
        val secondFixture = fixture("second")
        val firstData = BasicWorldData()
        val secondData = BasicWorldData()
        val firstStations = StationStore(firstData.railObjects)
        val secondStations = StationStore(secondData.railObjects)
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val firstBerth = StationBerth(berthRef, firstFixture.location)
        val secondBerth = StationBerth(berthRef, secondFixture.location)
        val station = Station(stationId, "Central", listOf(berthRef))

        firstStations.register(station, listOf(firstBerth))
        secondStations.register(station, listOf(secondBerth))
        firstStations.remove(stationId)

        assertNull(firstStations[stationId])
        assertSame(secondBerth, secondStations.berth(berthRef))
        assertEquals(RailObjectLifecycleState.ACTIVE, secondBerth.lifecycleState)
        assertNotSame(firstStations, secondStations)
        assertTrue(secondStations.all().single().id == stationId)
    }

    private fun fixture(id: String): Fixture {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("station-$id", rail, world, listOf(10.0))
        return Fixture(rail.id, train.head.location)
    }

    private data class Fixture(
        val railId: java.util.UUID,
        val location: work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
    )
}
