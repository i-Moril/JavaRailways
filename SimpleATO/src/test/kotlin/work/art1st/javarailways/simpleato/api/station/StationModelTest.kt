package work.art1st.javarailways.simpleato.api.station

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        assertEquals(0.3, StationBerth(berthRef, location).positionToleranceMeters)
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
        firstStations.updateMetadata(stationId, JsonObject(mapOf("world" to JsonPrimitive("first"))), JsonObject(emptyMap()))
        assertEquals(JsonObject(emptyMap()), secondStations[stationId]!!.metadata)
        firstStations.remove(stationId)

        assertNull(firstStations[stationId])
        assertSame(secondBerth, secondStations.berth(berthRef))
        assertEquals(RailObjectLifecycleState.ACTIVE, secondBerth.lifecycleState)
        assertNotSame(firstStations, secondStations)
        assertTrue(secondStations.all().single().id == stationId)
    }

    @Test
    fun `metadata is recursively immutable and object key order does not create conflicts`() {
        val location = fixture("metadata").location
        val store = StationStore(RailObjectStore())
        val id = StationId("central")
        val ref = StationBerthRef(id, "one")
        val nested = linkedMapOf<String, JsonElement>("name" to JsonPrimitive("Line 2"))
        val transfers = mutableListOf<JsonElement>(JsonObject(nested))
        val source = linkedMapOf<String, JsonElement>(
            "transfers" to JsonArray(transfers), "notice" to JsonPrimitive("hello")
        )
        store.register(Station(id, "Central", listOf(ref), metadata = JsonObject(source)),
            listOf(StationBerth(ref, location)))
        nested.clear()
        transfers.clear()
        source.clear()
        val original = requireNotNull(store[id]).metadata
        assertEquals("Line 2", original["transfers"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertThrows(UnsupportedOperationException::class.java) {
            (original["transfers"]!!.jsonArray.subList(0, 1) as MutableList<JsonElement>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (original["transfers"]!!.jsonArray.single().jsonObject.values as MutableCollection<JsonElement>).clear()
        }
        val expectedReordered = JsonObject(original.entries.reversed().associate { it.toPair() })
        val updated = store.updateMetadata(id, JsonObject(mapOf("notice" to JsonPrimitive("changed"))), expectedReordered)
        assertEquals("changed", updated.metadata["notice"]!!.jsonPrimitive.content)
        assertThrows(StationMetadataConflict::class.java) { store.updateMetadata(id, original, original) }
        assertEquals(updated, store[id])
        assertThrows(NoSuchElementException::class.java) {
            store.updateMetadata(StationId("missing"), JsonObject(emptyMap()), JsonObject(emptyMap()))
        }
    }

    @Test
    fun `metadata survives berth edits but deleting the final berth removes it`() {
        val location = fixture("metadata-berths").location
        val store = StationStore(RailObjectStore())
        val id = StationId("central")
        val first = StationBerthRef(id, "one")
        val second = StationBerthRef(id, "two")
        val metadata = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
        store.addBerth(id, "Central", StationBerth(first, location))
        store.updateMetadata(id, metadata, JsonObject(emptyMap()))
        store.addBerth(id, "Central", StationBerth(second, location))
        assertEquals(metadata, store[id]!!.metadata)
        store.removeBerth(first)
        assertEquals(metadata, store[id]!!.metadata)
        store.removeBerth(second)
        assertNull(store[id])
        store.addBerth(id, "Central", StationBerth(first, location))
        assertEquals(JsonObject(emptyMap()), store[id]!!.metadata)
    }

    @Test
    fun `metadata rejects invalid numbers excessive depth size and node count`() {
        assertThrows(IllegalArgumentException::class.java) {
            StationMetadata.immutableCopy(JsonObject(mapOf("bad" to JsonPrimitive(Double.NaN))))
        }
        var deep: JsonElement = JsonNull
        repeat(StationMetadata.MAX_DEPTH + 1) { deep = JsonArray(listOf(deep)) }
        assertThrows(IllegalArgumentException::class.java) {
            StationMetadata.immutableCopy(JsonObject(mapOf("deep" to deep)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationMetadata.immutableCopy(JsonObject(mapOf("large" to JsonPrimitive("界".repeat(StationMetadata.MAX_BYTES / 2)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StationMetadata.immutableCopy(JsonObject(mapOf("many" to JsonArray(List(StationMetadata.MAX_NODES) { JsonNull }))))
        }
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
