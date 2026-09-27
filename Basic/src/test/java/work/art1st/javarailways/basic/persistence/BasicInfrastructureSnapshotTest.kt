package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailCurveKind
import work.art1st.javarailways.basic.rail.RailGeometryMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class BasicInfrastructureSnapshotTest {
    @Test
    fun `current Basic infrastructure rejects removed line fields`() {
        assertThrows(Exception::class.java) {
            BasicInfrastructureSnapshotCodec.decode(
                """
                {"formatVersion":2,"infrastructureRevision":1,"destinations":[],"wayPoints":[],
                 "lines":[],"lineRevision":0,"railMetadata":[]}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `infrastructure snapshot rejects unsupported format version`() {
        val encoded = BasicInfrastructureSnapshotCodec.encode(
            BasicInfrastructureSnapshotV2(
                formatVersion = BasicInfrastructureSnapshotV2.CURRENT_FORMAT_VERSION + 1,
                infrastructureRevision = 0L,
                destinations = emptyList(),
                wayPoints = emptyList()
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            BasicInfrastructureSnapshotCodec.decode(encoded)
        }
    }

    private val worlds = mutableListOf<work.art1st.javarailways.core.physics.CoreWorld>()

    @AfterEach
    fun cleanup() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
    }

    @Test
    fun `capture rejects orphan destinations before writing a corrupt snapshot`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        OperationalDestinationService(world).register(
            OperationalDestination("orphan", "Orphan", rail.id, 20.0)
        )
        world.removeRail(rail)

        val failure = assertThrows(IllegalStateException::class.java) {
            BasicInfrastructureSnapshotV2.capture(world, infrastructureRevision = 1L)
        }

        assertTrue(failure.message.orEmpty().contains("orphan"))
        assertTrue(failure.message.orEmpty().contains(rail.id.toString()))
        OperationalDestinationService(world).remove("orphan")
        world.basicWorldData.wayPoints.add(WayPoint("legacy", "Legacy", rail.id, 30.0))
        val wayPointFailure = assertThrows(IllegalStateException::class.java) {
            BasicInfrastructureSnapshotV2.capture(world, infrastructureRevision = 1L)
        }
        assertTrue(wayPointFailure.message.orEmpty().contains("legacy"))
    }

    @Test
    fun `round trips rail infrastructure metadata`() {
        val source = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(source)
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val metadata = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(
                designSpeedMetersPerSecond = 30.0,
                geometry = RailGeometryMetadata(curveKind = RailCurveKind.STRAIGHT),
                curveSpeedLimitAutomatic = false
            ),
            speedLimits = SpeedLimitMetadata(
                listOf(SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE))
            )
        )
        source.basicWorldData.railMetadata.put(rail.id, metadata)
        val snapshot = BasicInfrastructureSnapshotV2.capture(source, infrastructureRevision = 2L)

        val restored = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(restored)
        rail.save().load(CoreRail.SavedRailV1.Dependency(restored))
        snapshot.restore(restored)

        assertEquals(metadata, restored.basicWorldData.railMetadata.getValue(rail.id))
    }

    @Test
    fun `round trips canonical station and depot destinations`() {
        val source = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(source)
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(source)
        service.register(
            OperationalDestination(
                id = "central@one",
                displayName = "Central one",
                railId = rail.id,
                arcLengthInAbMeters = 25.0,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.StationBerthRole(
                    stationId = StationId("central"),
                    stationDisplayName = "Central",
                    berthId = "one",
                    doorSide = DoorSide.LEFT,
                    stoppingOffsetMetersBefore = 1.5,
                    usableLengthMeters = 90.0,
                    defaultDwellTicks = 80L,
                    releaseDistanceMetersAfter = 4.0
                )
            )
        )
        service.register(
            OperationalDestination(
                id = "south@one",
                displayName = "South one",
                railId = rail.id,
                arcLengthInAbMeters = 60.0,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.StationBerthRole(
                    stationId = StationId("south"),
                    stationDisplayName = "South",
                    berthId = "one",
                    doorSide = DoorSide.RIGHT
                )
            )
        )
        service.register(
            OperationalDestination(
                id = "north@road-1",
                displayName = "North road 1",
                railId = rail.id,
                arcLengthInAbMeters = 75.0,
                role = DestinationRole.DepotStablingPointRole(
                    depotId = "north",
                    stablingPointId = "road-1",
                    depotDisplayName = "North Depot",
                    usableLengthMeters = 120.0,
                    allowedTags = setOf("metro")
                )
            )
        )

        val encoded = BasicInfrastructureSnapshotCodec.encode(
            BasicInfrastructureSnapshotV2.capture(source, infrastructureRevision = 7L)
        )
        val snapshot = BasicInfrastructureSnapshotCodec.decode(encoded)

        val restored = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(restored)
        rail.save().load(CoreRail.SavedRailV1.Dependency(restored))
        snapshot.restore(restored)

        assertEquals(7L, snapshot.infrastructureRevision)
        assertEquals(setOf("central@one", "south@one", "north@road-1"),
            restored.basicWorldData.wayPoints.all().map { it.id }.toSet())
        val stationRole = restored.basicWorldData.destinations["central@one"]?.role as
            DestinationRole.StationBerthRole
        assertEquals(80L, stationRole.defaultDwellTicks)
        assertNotNull(restored.basicWorldData.stations.berth(stationRole.berthRef))
        val depotRole = restored.basicWorldData.destinations["north@road-1"]?.role as
            DestinationRole.DepotStablingPointRole
        assertEquals(setOf("metro"), depotRole.allowedTags)
    }
}
