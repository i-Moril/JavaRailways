package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
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

class BasicInfrastructureSnapshotTest {
    @Test
    fun `current Basic infrastructure rejects removed destination fields`() {
        assertThrows(Exception::class.java) {
            BasicInfrastructureSnapshotCodec.decode(
                """
                {"formatVersion":3,"infrastructureRevision":1,"destinations":[],"wayPoints":[],"railMetadata":[]}
                """.trimIndent()
            )
        }
    }

    @Test
    fun `infrastructure snapshot rejects unsupported format version`() {
        val encoded = BasicInfrastructureSnapshotCodec.encode(
            BasicInfrastructureSnapshotV3(
                formatVersion = BasicInfrastructureSnapshotV3.CURRENT_FORMAT_VERSION + 1,
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
            BasicInfrastructureSnapshotV3.capture(world, infrastructureRevision = 1L, projectedWayPointIds = emptySet())
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

        val snapshot = BasicInfrastructureSnapshotV3.capture(
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
        val snapshot = BasicInfrastructureSnapshotV3.capture(source, infrastructureRevision = 2L, projectedWayPointIds = emptySet())

        val restored = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(restored)
        rail.save().load(CoreRail.SavedRailV1.Dependency(restored))
        snapshot.restore(restored)

        assertEquals(metadata, restored.basicWorldData.railMetadata.getValue(rail.id))
    }

}
