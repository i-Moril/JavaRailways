package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.rail.RailAuthoringMetadata
import work.art1st.javarailways.basic.rail.RailCurveKind
import work.art1st.javarailways.basic.rail.RailGeometryMetadata
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class BasicInfrastructureSnapshotTest {
    @Test
    fun `later blank waypoint refuses all Basic materialization`() = withFreshWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[
                {"id":"valid","displayName":"Valid","railId":"${rail.id}","arcLengthInAbMeters":10.0},
                {"id":" ","displayName":"Invalid","railId":"${rail.id}","arcLengthInAbMeters":20.0}],
                "railMetadata":[{"railId":"${rail.id}","metadata":{}}],
                "operatingDirections":[{"railId":"${rail.id}","permittedDirection":"AB"}]}"""
        )
        assertThrows(IllegalArgumentException::class.java) { snapshot.restore(world) }
        assertTrue(world.basicWorldData.wayPoints.all().isEmpty())
        assertTrue(world.basicWorldData.railMetadata.snapshot().isEmpty())
        assertTrue(world.basicWorldData.operatingDirections.entries().isEmpty())
        snapshot.copy(wayPoints = snapshot.wayPoints.take(1)).restore(world)
        assertEquals(listOf("valid"), world.basicWorldData.wayPoints.all().map { it.id })
        assertEquals(ViewDirection.AB, world.basicWorldData.operatingDirections[rail.id])
    }

    @Test
    fun `metadata UUID aliases refuse all Basic materialization`() = withFreshWorld { world ->
        val original = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val railId = "abcdef01-2345-6789-abcd-ef0123456789"
        original.save().copy(id = railId).load(CoreRail.SavedRailV1.Dependency(world))
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[
                {"id":"valid","displayName":"Valid","railId":"$railId","arcLengthInAbMeters":10.0}],
                "railMetadata":[
                    {"railId":"$railId","metadata":{"authoring":{"designSpeedMetersPerSecond":20.0}}},
                    {"railId":"${railId.uppercase()}","metadata":{"authoring":{"designSpeedMetersPerSecond":30.0}}}],
                "operatingDirections":[{"railId":"$railId","permittedDirection":"BA"}]}"""
        )
        val failure = assertThrows(IllegalStateException::class.java) { snapshot.restore(world) }
        assertTrue(failure.message.orEmpty().contains("duplicate Rail metadata"))
        assertTrue(world.basicWorldData.wayPoints.all().isEmpty())
        assertTrue(world.basicWorldData.railMetadata.snapshot().isEmpty())
        assertTrue(world.basicWorldData.operatingDirections.entries().isEmpty())
        snapshot.copy(railMetadata = snapshot.railMetadata.take(1)).restore(world)
        assertEquals(20.0, world.basicWorldData.railMetadata.getValue(UUID.fromString(railId))
            .authoring!!.designSpeedMetersPerSecond)
    }

    private fun withFreshWorld(block: (work.art1st.javarailways.core.physics.CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use {
                    try {
                        TestBasicWorldDataRegistry.getOrCreate(world)
                        block(world)
                    } finally {
                        TestBasicWorldDataRegistry.unregister(world)
                    }
                }
            }
        }
    }

    @Test
    fun `current format 2 without directions defaults to both directions without a version change`() {
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[],"railMetadata":[]}"""
        )
        assertEquals(2, snapshot.formatVersion)
        assertTrue(snapshot.operatingDirections.isEmpty())
        assertTrue(BasicInfrastructureSnapshotCodec.encode(snapshot).contains("\"operatingDirections\": []"))
    }

    @Test
    fun `fixed current directions round trip and restore in a Basic standalone world`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[],"railMetadata":[],
                "operatingDirections":[{"railId":"${rail.id}","permittedDirection":"BA"}]}"""
        )
        snapshot.restore(world)
        assertEquals(ViewDirection.BA, world.basicWorldData.operatingDirections[rail.id])
        val captured = BasicInfrastructureSnapshotV2.capture(world, 7L, emptySet())
        assertEquals(snapshot, BasicInfrastructureSnapshotCodec.decode(BasicInfrastructureSnapshotCodec.encode(captured)))
    }

    @Test
    fun `invalid duplicate and orphan directions refuse all Basic materialization`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val direction = SavedOperatingDirectionV1.capture(rail.id, ViewDirection.AB)
        val base = BasicInfrastructureSnapshotV2(
            2, 7L,
            listOf(SavedWayPointV1("standalone", "Standalone", rail.id.toString(), 20.0)),
            listOf(SavedRailInfrastructureMetadataV1.capture(
                rail.id,
                RailInfrastructureMetadata(
                    authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0),
                    speedLimits = SpeedLimitMetadata(listOf(SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE)))
                )
            ))
        )
        val invalidInputs = listOf(
            listOf(direction.copy(railId = "invalid-uuid")),
            listOf(direction, direction.copy(railId = rail.id.toString().uppercase())),
            listOf(direction.copy(railId = UUID.randomUUID().toString()))
        )
        for (directions in invalidInputs) {
            assertThrows(Exception::class.java) { base.copy(operatingDirections = directions).restore(world) }
            assertTrue(world.basicWorldData.wayPoints.all().isEmpty())
            assertTrue(world.basicWorldData.railMetadata.snapshot().isEmpty())
            assertTrue(world.basicWorldData.operatingDirections.entries().isEmpty())
        }
        assertThrows(Exception::class.java) {
            BasicInfrastructureSnapshotCodec.decode(
                """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[],
                    "operatingDirections":[{"railId":"${rail.id}","permittedDirection":"SIDEWAYS"}]}"""
            )
        }
    }

    @Test
    fun `capture refuses orphan directions`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val missing = UUID.randomUUID()
        world.basicWorldData.operatingDirections.put(missing, ViewDirection.AB)
        val failure = assertThrows(IllegalStateException::class.java) {
            BasicInfrastructureSnapshotV2.capture(world, 7L, emptySet())
        }
        assertTrue(failure.message.orEmpty().contains(missing.toString()))
    }

    @Test
    fun `current Basic infrastructure rejects removed destination fields`() {
        assertThrows(Exception::class.java) {
            BasicInfrastructureSnapshotCodec.decode(
                """
                {"formatVersion":2,"infrastructureRevision":1,"destinations":[],"wayPoints":[],"railMetadata":[]}
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
    fun `capture rejects orphan waypoints before writing a corrupt snapshot`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("orphan", "Orphan", rail.id, 20.0))
        world.removeRail(rail)

        val failure = assertThrows(IllegalStateException::class.java) {
            BasicInfrastructureSnapshotV2.capture(world, infrastructureRevision = 1L, projectedWayPointIds = emptySet())
        }

        assertTrue(failure.message.orEmpty().contains("orphan"))
        assertTrue(failure.message.orEmpty().contains(rail.id.toString()))
    }

    @Test
    fun `capture omits explicitly identified derived waypoint projections`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("standalone", "Standalone", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("projected", "Projected", rail.id, 40.0))

        val snapshot = BasicInfrastructureSnapshotV2.capture(
            world,
            infrastructureRevision = 1L,
            projectedWayPointIds = setOf("projected")
        )

        assertEquals(listOf("standalone"), snapshot.wayPoints.map { it.id })
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
        val snapshot = BasicInfrastructureSnapshotV2.capture(source, infrastructureRevision = 2L, projectedWayPointIds = emptySet())

        val restored = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(restored)
        rail.save().load(CoreRail.SavedRailV1.Dependency(restored))
        snapshot.restore(restored)

        assertEquals(metadata, restored.basicWorldData.railMetadata.getValue(rail.id))
    }

}
