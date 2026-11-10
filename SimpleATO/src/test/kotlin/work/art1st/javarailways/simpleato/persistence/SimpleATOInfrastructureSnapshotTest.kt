package work.art1st.javarailways.simpleato.persistence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotStablingPoint
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class SimpleATOInfrastructureSnapshotTest {
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
    fun `line snapshot requires explicit up and down patterns`() {
        assertThrows(Exception::class.java) {
            SimpleATOInfrastructureSnapshotCodec.decode(
                """
                {"formatVersion":1,"infrastructureRevision":1,"lines":[{
                  "id":"old","displayName":"Old","stationIds":["a","b"]
                }],"lineRevision":0}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `round trips canonical destinations stations and lines`() {
        val source = worldWithStations()
        source.simpleAtoWorldData.lines.put(line(), expectedRevision = 0L)
        val metadata = JsonObject(mapOf("transfers" to JsonArray(listOf(
            JsonObject(mapOf("lineId" to JsonPrimitive("metro-2"), "color" to JsonPrimitive("#0080FF")))
        ))))
        source.simpleAtoWorldData.stations.updateMetadata(StationId("central"), metadata, JsonObject(emptyMap()))
        val sourceRail = requireNotNull(source.getRail(
            source.simpleAtoWorldData.destinations["central@one"]!!.railId
        ))
        source.basicWorldData.operatingDirections.put(sourceRail.id, ViewDirection.BA)
        val basicSnapshot = BasicInfrastructureSnapshotCodec.decode(BasicInfrastructureSnapshotCodec.encode(
            BasicInfrastructureSnapshotV2.capture(source, 7L,
                source.simpleAtoWorldData.destinations.all().mapTo(HashSet()) { it.id })
        ))

        val snapshot = SimpleATOInfrastructureSnapshotCodec.decode(
            SimpleATOInfrastructureSnapshotCodec.encode(
                SimpleATOInfrastructureSnapshotV1.capture(source, infrastructureRevision = 7L)
            )
        )
        val restored = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(restored)
        IsolatedSimulationThreadMarker.run { sourceRail.save().load(CoreRail.SavedRailV1.Dependency(restored)) }

        basicSnapshot.restore(restored)
        snapshot.restore(restored)

        val restoredLines = restored.simpleAtoWorldData.lines.snapshot()
        assertEquals(7L, snapshot.infrastructureRevision)
        assertEquals(metadata, snapshot.stationMetadata["central"])
        assertEquals(metadata, restored.simpleAtoWorldData.stations[StationId("central")]!!.metadata)
        assertEquals(2, basicSnapshot.formatVersion)
        assertEquals(1, snapshot.formatVersion)
        assertFalse(SimpleATOInfrastructureSnapshotCodec.encode(snapshot).contains("operatingDirections"))
        assertEquals(1L, restoredLines.revision)
        assertEquals(ViewDirection.BA, restored.basicWorldData.operatingDirections[sourceRail.id])
        assertEquals(setOf("central@one", "south@one"),
            restored.simpleAtoWorldData.destinations.all().map { it.id }.toSet())
        assertEquals(setOf("central", "south"),
            restored.simpleAtoWorldData.stations.all().map { it.id.value }.toSet())
        assertEquals(setOf("central@one", "south@one"),
            restored.basicWorldData.wayPoints.all().map { it.id }.toSet())
        assertEquals(source.simpleAtoWorldData.destinations.all(), restored.simpleAtoWorldData.destinations.all())
        assertEquals(source.basicWorldData.wayPoints.all(), restored.basicWorldData.wayPoints.all())
        assertEquals(source.simpleAtoWorldData.stations.all(), restored.simpleAtoWorldData.stations.all())
        assertEquals(source.simpleAtoWorldData.lines.snapshot().lines, restoredLines.lines)
        assertNotNull(restored.simpleAtoWorldData.stations.berth(
            (restored.simpleAtoWorldData.destinations["central@one"]!!.role as
                DestinationRole.StationBerthRole).berthRef
        ))
        assertEquals(listOf("central", "south"), restoredLines.lines.single().upStationIds.map { it.value })
        assertEquals(listOf("south", "central"), restoredLines.lines.single().downStationIds.map { it.value })
    }

    @Test
    fun `missing station fails before any line is materialized`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val snapshot = SimpleATOInfrastructureSnapshotV1(
            formatVersion = SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 1L,
            destinations = listOf(SavedOperationalDestinationV1.capture(
                berth("central", 20.0, rail.id)
            )),
            lines = listOf(SavedLineV1.capture(line())),
            lineRevision = 4L
        )

        val failure = assertThrows(IllegalStateException::class.java) { snapshot.restore(world) }

        assertTrue(failure.message.orEmpty().contains("missing stations"))
        assertEquals(0L, world.simpleAtoWorldData.lines.snapshot().revision)
        assertTrue(world.simpleAtoWorldData.lines.snapshot().lines.isEmpty())
        assertTrue(world.simpleAtoWorldData.destinations.all().isEmpty())
        assertTrue(world.simpleAtoWorldData.stations.all().isEmpty())
        assertTrue(world.basicWorldData.wayPoints.all().isEmpty())
    }

    @Test
    fun `restore rejects a non-fresh depot store before materializing destinations`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val depotId = DepotId("existing")
        val pointRef = StablingPointRef(depotId, "one")
        world.simpleAtoWorldData.depots.addStablingPoint(
            depotId,
            "Existing",
            DepotStablingPoint(pointRef, "existing@one", 100.0)
        )
        val snapshot = SimpleATOInfrastructureSnapshotV1(
            formatVersion = SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 1L,
            destinations = emptyList(),
            lines = emptyList(),
            lineRevision = 0L
        )

        val failure = assertThrows(IllegalStateException::class.java) { snapshot.restore(world) }

        assertTrue(failure.message.orEmpty().contains("DepotStore is not empty"))
        assertEquals(pointRef, world.simpleAtoWorldData.depots.stablingPoints(depotId).single().point.ref)
        assertTrue(world.simpleAtoWorldData.destinations.all().isEmpty())
    }

    @Test
    fun `fixed snapshot without metadata reads explicit empty default without version change`() {
        val decoded = SimpleATOInfrastructureSnapshotCodec.decode(
            """{"formatVersion":1,"infrastructureRevision":0,"destinations":[],"lines":[],"lineRevision":0}"""
        )
        assertEquals(emptyMap<String, JsonObject>(), decoded.stationMetadata)
        assertEquals(1, decoded.formatVersion)
        assertTrue(SimpleATOInfrastructureSnapshotCodec.encode(decoded).contains("stationMetadata"))
    }

    @Test
    fun `metadata for unknown stations and oversized metadata fail before materialization`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val snapshot = SimpleATOInfrastructureSnapshotV1(
            formatVersion = 1, infrastructureRevision = 0,
            destinations = listOf(SavedOperationalDestinationV1.capture(berth("central", 20.0, rail.id))),
            lines = emptyList(), lineRevision = 0,
            stationMetadata = mapOf("unknown" to JsonObject(emptyMap()))
        )
        assertThrows(IllegalArgumentException::class.java) { snapshot.restore(world) }
        val oversized = snapshot.copy(stationMetadata = mapOf("central" to JsonObject(mapOf(
            "huge" to JsonPrimitive("x".repeat(65537))
        ))))
        assertThrows(IllegalArgumentException::class.java) { oversized.restore(world) }
        val nonFinite = snapshot.copy(stationMetadata = mapOf("central" to JsonObject(mapOf(
            "bad" to JsonPrimitive(Double.POSITIVE_INFINITY)
        ))))
        assertThrows(IllegalArgumentException::class.java) { nonFinite.restore(world) }
        assertTrue(world.simpleAtoWorldData.destinations.all().isEmpty())
        assertTrue(world.simpleAtoWorldData.stations.all().isEmpty())
        assertTrue(world.basicWorldData.wayPoints.all().isEmpty())
        assertTrue(world.basicWorldData.railObjects.objectsOnRail(rail.id).isEmpty())
    }

    private fun worldWithStations(): CoreWorld {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val destinations = OperationalDestinationService(world)
        destinations.register(berth("central", 20.0, rail.id))
        destinations.register(berth("south", 80.0, rail.id))
        return world
    }

    private fun berth(stationId: String, arc: Double, railId: java.util.UUID) = OperationalDestination(
        id = "$stationId@one",
        railId = railId,
        arcLengthInAbMeters = arc,
        role = DestinationRole.StationBerthRole(
            stationId = StationId(stationId),
            stationDisplayName = stationId,
            berthId = "one",
            doorSide = DoorSide.LEFT
        )
    )

    private fun line() = Line(
        LineId("metro"),
        "Metro",
        listOf(StationId("central"), StationId("south")),
        listOf(StationId("south"), StationId("central"))
    )
}
