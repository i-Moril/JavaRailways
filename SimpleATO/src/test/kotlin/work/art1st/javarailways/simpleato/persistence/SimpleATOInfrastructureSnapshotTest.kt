package work.art1st.javarailways.simpleato.persistence

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
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
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
    fun `round trips canonical destinations stations and lines`() {
        val source = worldWithStations()
        source.simpleAtoWorldData.lines.put(line(), expectedRevision = 0L)
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
        val restored = createTestWorld().also(worlds::add)
        TestSimpleATOWorldDataRegistry.getOrCreate(restored)
        sourceRail.save().load(CoreRail.SavedRailV1.Dependency(restored))

        basicSnapshot.restore(restored)
        snapshot.restore(restored)

        val restoredLines = restored.simpleAtoWorldData.lines.snapshot()
        assertEquals(7L, snapshot.infrastructureRevision)
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
        assertNotNull(restored.simpleAtoWorldData.stations.berth(
            (restored.simpleAtoWorldData.destinations["central@one"]!!.role as
                DestinationRole.StationBerthRole).berthRef
        ))
        assertEquals(listOf("central", "south"), restoredLines.lines.single().upStationIds.map { it.value })
        assertEquals(listOf("south", "central"), restoredLines.lines.single().downStationIds.map { it.value })
    }

    @Test
    fun `missing station fails before any line is materialized`() {
        val world = createTestWorld().also(worlds::add)
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
        val world = createTestWorld().also(worlds::add)
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
