package work.art1st.mineRails.core.persistence

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.mineRails.core.di.KoinWorldParentContext
import work.art1st.mineRails.core.di.ensureMineRailsKoinStarted
import work.art1st.mineRails.core.di.mineRailsParentScope
import work.art1st.mineRails.core.api.physics.rail.ParallelRailMetadata
import work.art1st.mineRails.core.api.physics.rail.RailCurveKind
import work.art1st.mineRails.core.api.physics.rail.RailGeometryMetadata
import work.art1st.mineRails.core.api.physics.rail.RailMetadata
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

class WorldPersistenceRoundTripTest {

    @Test
    fun worldSnapshotJsonRoundTrip() {
        ensureMineRailsKoinStarted()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val tickManager = TickManagerImpl(scheduler, System.currentTimeMillis())
        val worldParent = KoinWorldParentContext(mineRailsParentScope())
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
            )
        )
        createTrainWithConfig("persist-t1", rail, world, listOf(8.0), isLoop = false)

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
                KoinWorldParentContext(mineRailsParentScope())
            )
        )

        assertNotNull(loaded.getTrain("persist-t1"))
        assertEquals(22.0, loaded.getRail(rail.id)?.metadata?.designSpeedMetersPerSecond)
        assertEquals(20.0, loaded.getRail(rail.id)?.metadata?.speedLimitMetersPerSecond)
        assertEquals(31.0, loaded.getRail(rail.id)?.metadata?.derailSpeedMetersPerSecond)
        assertEquals(ViewDirection.AB, loaded.getRail(rail.id)?.metadata?.allowedDirection)
        assertEquals(0.09, loaded.getRail(rail.id)?.metadata?.geometry?.allowableCantDeficiencyMeters)
        assertEquals(300.0, loaded.getRail(rail.id)?.metadata?.geometry?.curveRadiusMeters)
        assertEquals("P1", loaded.getRail(rail.id)?.metadata?.geometry?.parallel?.groupId)
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
    fun decodeInvalidJsonThrows() {
        assertThrows(SerializationException::class.java) {
            WorldSnapshotCodec.decodeInfrastructure("{")
        }
    }
}
