package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.route.WayPoint
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
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class BasicInfrastructureSnapshotTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestBasicWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }

    @Test
    fun `later blank waypoint refuses all Basic materialization`() = withFreshWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[
                {"id":"valid","railId":"${rail.id}","arcLengthInAbMeters":10.0},
                {"id":" ","railId":"${rail.id}","arcLengthInAbMeters":20.0}],
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
        IsolatedSimulationThreadMarker.run { original.save().copy(id = railId).load(CoreRail.SavedRailV1.Dependency(world)) }
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[
                {"id":"valid","railId":"$railId","arcLengthInAbMeters":10.0}],
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
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        run {
            run {
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
    fun `standalone waypoint identity changes preserve geometry in the current snapshot`() = withFreshWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val snapshot = BasicInfrastructureSnapshotCodec.decode(
            """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[
                {"id":"yard-entry","railId":"${rail.id}","arcLengthInAbMeters":20.0}]}"""
        )
        snapshot.restore(world)
        assertEquals(WayPoint("yard-entry", rail.id, 20.0), world.basicWorldData.wayPoints["yard-entry"])

        val renamed = world.basicWorldData.wayPoints.rename("yard-entry", "yard-exit")

        assertNull(world.basicWorldData.wayPoints["yard-entry"])
        assertEquals(WayPoint("yard-exit", rail.id, 20.0), renamed)
        val captured = BasicInfrastructureSnapshotV2.capture(world, 7L, emptySet())
        assertEquals(listOf(SavedWayPointV1("yard-exit", rail.id.toString(), 20.0)), captured.wayPoints)
        assertEquals(captured, BasicInfrastructureSnapshotCodec.decode(BasicInfrastructureSnapshotCodec.encode(captured)))
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
        val world = TestWorldFixture.create().also(worldFixtures::add).world
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
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val direction = SavedOperatingDirectionV1.capture(rail.id, ViewDirection.AB)
        val base = BasicInfrastructureSnapshotV2(
            2, 7L,
            listOf(SavedWayPointV1("standalone", rail.id.toString(), 20.0)),
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
        val world = TestWorldFixture.create().also(worldFixtures::add).world
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



    @Test
    fun `capture rejects orphan waypoints before writing a corrupt snapshot`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("orphan", rail.id, 20.0))
        IsolatedSimulationThreadMarker.run { world.removeRail(rail) }

        val failure = assertThrows(IllegalStateException::class.java) {
            BasicInfrastructureSnapshotV2.capture(world, infrastructureRevision = 1L, projectedWayPointIds = emptySet())
        }

        assertTrue(failure.message.orEmpty().contains("orphan"))
        assertTrue(failure.message.orEmpty().contains(rail.id.toString()))
    }

    @Test
    fun `capture omits explicitly identified derived waypoint projections`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        world.basicWorldData.wayPoints.add(WayPoint("standalone", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("projected", rail.id, 40.0))

        val snapshot = BasicInfrastructureSnapshotV2.capture(
            world,
            infrastructureRevision = 1L,
            projectedWayPointIds = setOf("projected")
        )

        assertEquals(listOf("standalone"), snapshot.wayPoints.map { it.id })
    }

    @Test
    fun `round trips rail infrastructure metadata`() {
        val source = TestWorldFixture.create().also(worldFixtures::add).world
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

        val restored = TestWorldFixture.create().also(worldFixtures::add).world
        TestBasicWorldDataRegistry.getOrCreate(restored)
        IsolatedSimulationThreadMarker.run { rail.save().load(CoreRail.SavedRailV1.Dependency(restored)) }
        snapshot.restore(restored)

        assertEquals(metadata, restored.basicWorldData.railMetadata.getValue(rail.id))
    }

}
