package work.art1st.javarailways.simpleato.persistence

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class SimpleATOInfrastructureSnapshotTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun cleanup() {
        worlds.forEach(TestSimpleATOWorldDataRegistry::unregister)
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
    fun `round trips lines after Basic stations are restored`() {
        val source = worldWithStations()
        source.simpleAtoWorldData.lines.put(line(), expectedRevision = 0L)

        val snapshot = SimpleATOInfrastructureSnapshotCodec.decode(
            SimpleATOInfrastructureSnapshotCodec.encode(
                SimpleATOInfrastructureSnapshotV1.capture(source, infrastructureRevision = 7L)
            )
        )
        val restored = worldWithStations()

        snapshot.restore(restored)

        val restoredLines = restored.simpleAtoWorldData.lines.snapshot()
        assertEquals(7L, snapshot.infrastructureRevision)
        assertEquals(1L, restoredLines.revision)
        assertEquals(listOf("central", "south"), restoredLines.lines.single().upStationIds.map { it.value })
        assertEquals(listOf("south", "central"), restoredLines.lines.single().downStationIds.map { it.value })
    }

    @Test
    fun `missing station fails before any line is materialized`() {
        val world = createTestWorld().also(worlds::add)
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val snapshot = SimpleATOInfrastructureSnapshotV1(
            formatVersion = SimpleATOInfrastructureSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 1L,
            lines = listOf(SavedLineV1.capture(line())),
            lineRevision = 4L
        )

        val failure = assertThrows(IllegalStateException::class.java) { snapshot.restore(world) }

        assertTrue(failure.message.orEmpty().contains("missing stations"))
        assertEquals(0L, world.simpleAtoWorldData.lines.snapshot().revision)
        assertTrue(world.simpleAtoWorldData.lines.snapshot().lines.isEmpty())
    }

    private fun worldWithStations(): CoreWorld {
        val world = createTestWorld().also(worlds::add)
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
        displayName = "$stationId one",
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
