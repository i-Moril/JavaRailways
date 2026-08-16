package work.art1st.javarailways.core.persistence

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.di.KoinWorldParentContext
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.di.ensureJavaRailwaysKoinStarted
import work.art1st.javarailways.core.di.javaRailwaysParentScope
import work.art1st.javarailways.core.api.physics.rail.ParallelRailMetadata
import work.art1st.javarailways.core.api.physics.rail.RailCurveKind
import work.art1st.javarailways.core.api.physics.rail.RailGeometryMetadata
import work.art1st.javarailways.core.api.physics.rail.RailMetadata
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.SavedTrainV1
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class WorldPersistenceRoundTripTest {

    @Test
    fun worldSnapshotJsonRoundTrip() {
        ensureJavaRailwaysKoinStarted()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val tickManager = TickManagerImpl(scheduler, System.currentTimeMillis())
        val worldParent = KoinWorldParentContext(javaRailwaysParentScope())
        val world = CoreWorld.createEmpty(tickManager, worldParent)
        val segments = PrimeCurveGenerator.buildStraight(80.0, segmentCount = 1)
        val rail = createStraightRailFromSegments(world, segments)
        rail.metadata = RailMetadata(
            designSpeedMetersPerSecond = 22.0,
            speedLimitMetersPerSecond = 20.0,
            derailSpeedMetersPerSecond = 31.0,
            allowedDirection = ViewDirection.AB,
            geometry = RailGeometryMetadata(
                curveKind = RailCurveKind.FIVE_SEGMENT,
                curveRadiusMeters = 300.0,
                automaticSuperelevation = true,
                allowableCantDeficiencyMeters = 0.09,
                parallel = ParallelRailMetadata("P1", index = 0, count = 2, spacingMeters = 4.5)
            ),
            gradeLockedAtA = true,
            gradeLockedAtB = false
        )
        val sourceTrain = createTrainWithConfig("persist-t1", rail, world, listOf(8.0), isLoop = false)
        world.renameTrain(sourceTrain, "Player Express")

        val infrastructure = world.saveInfrastructure(1L)
        val runtime = world.saveRuntime(1L, 2L)
        val decodedInfrastructure = WorldSnapshotCodec.decodeInfrastructure(
            WorldSnapshotCodec.encodeInfrastructure(infrastructure)
        )
        val decodedRuntime = WorldSnapshotCodec.decodeRuntime(
            WorldSnapshotCodec.encodeRuntime(runtime)
        )

        val scheduler2 = Executors.newSingleThreadScheduledExecutor()
        val tickManager2 = TickManagerImpl(scheduler2, System.currentTimeMillis())
        val loaded = CoreWorld.load(
            decodedInfrastructure,
            decodedRuntime,
            CoreWorld.SnapshotDependency(
                tickManager2,
                KoinWorldParentContext(javaRailwaysParentScope())
            )
        )

        assertEquals("Player Express", loaded.getTrain("persist-t1")?.name)
        assertEquals("persist-t1", loaded.getTrain("Player Express")?.id)
        assertEquals(22.0, loaded.getRail(rail.id)?.metadata?.designSpeedMetersPerSecond)
        assertEquals(20.0, loaded.getRail(rail.id)?.metadata?.speedLimitMetersPerSecond)
        assertEquals(31.0, loaded.getRail(rail.id)?.metadata?.derailSpeedMetersPerSecond)
        assertEquals(ViewDirection.AB, loaded.getRail(rail.id)?.metadata?.allowedDirection)
        assertEquals(0.09, loaded.getRail(rail.id)?.metadata?.geometry?.allowableCantDeficiencyMeters)
        assertEquals(300.0, loaded.getRail(rail.id)?.metadata?.geometry?.curveRadiusMeters)
        assertEquals("P1", loaded.getRail(rail.id)?.metadata?.geometry?.parallel?.groupId)
        assertEquals(true, loaded.getRail(rail.id)?.metadata?.gradeLockedAtA)
        assertEquals(false, loaded.getRail(rail.id)?.metadata?.gradeLockedAtB)
        val roundInfrastructure = loaded.saveInfrastructure(1L)
        val roundRuntime = loaded.saveRuntime(1L, 2L)
        assertEquals(infrastructure.rails.size, roundInfrastructure.rails.size)
        assertEquals(runtime.trains.size, roundRuntime.trains.size)
        assertEquals(
            WorldSnapshotCodec.encodeInfrastructure(infrastructure),
            WorldSnapshotCodec.encodeInfrastructure(roundInfrastructure)
        )
    }

    @Test
    fun worldLoadReportsTheTrainThatFailedToRestore() {
        val infrastructure = CoreWorld.SavedInfrastructureV1(
            formatVersion = CoreWorld.SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            rails = emptyList()
        )
        val runtime = CoreWorld.SavedRuntimeV1(
            formatVersion = CoreWorld.SNAPSHOT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 1L,
            trains = listOf(
                SavedTrainV1(
                    id = "00000000-0000-0000-0000-000000000001",
                    name = "broken-train",
                    cars = emptyList(),
                    reversed = false,
                    extension = emptyMap(),
                    headLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-000000000002", 0.0, ViewDirection.AB
                    ),
                    tailLocation = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing.SavedRailLocationWithFacingV1(
                        "00000000-0000-0000-0000-000000000002", 0.0, ViewDirection.AB
                    )
                )
            )
        )
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                CoreWorld.load(
                    infrastructure,
                    runtime,
                    CoreWorld.SnapshotDependency(
                        TickManagerImpl(scheduler, System.currentTimeMillis()),
                        NoOpWorldParentContext
                    )
                )
            }
            assertTrue(failure.message!!.contains("broken-train"))
            assertNotNull(failure.cause)
        } finally {
            scheduler.shutdownNow()
        }
    }

    @Test
    fun decodeRejectsUnsupportedSnapshotVersion() {
        val encoded = WorldSnapshotCodec.encodeInfrastructure(
            CoreWorld.SavedInfrastructureV1(
                formatVersion = CoreWorld.SNAPSHOT_FORMAT_VERSION + 1,
                infrastructureRevision = 0L,
                rails = emptyList()
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure(encoded)
        }
    }

    @Test
    fun decodeInvalidJsonThrows() {
        assertThrows(SerializationException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure("{")
        }
    }
}
