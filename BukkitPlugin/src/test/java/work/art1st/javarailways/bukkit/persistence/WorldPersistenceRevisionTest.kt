package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV2
import work.art1st.javarailways.basic.persistence.BasicRuntimeSnapshotV3
import work.art1st.javarailways.bukkit.logic.WorldManager
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotV1
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import java.io.File
import java.util.logging.Logger

class WorldPersistenceRevisionTest {
    @TempDir
    lateinit var directory: File
    private val logger = Logger.getAnonymousLogger()

    @Test
    fun `first save after restart outranks old backup even with physics epoch reset`() {
        assertRestartSave(interruptedWrite = false)
    }

    @Test
    fun `recovered backup advances beyond unmatched interrupted write revision`() {
        assertRestartSave(interruptedWrite = true)
    }

    private fun assertRestartSave(interruptedWrite: Boolean) {
        val infrastructure = File(directory, "world-infrastructure.json")
        val runtime = File(directory, "world-runtime.json")
        WorldPersistence.saveSplitSnapshots(infrastructure, runtime, snapshots(10_000L, 9_000L))
        if (interruptedWrite) {
            infrastructure.copyTo(File(directory, "${infrastructure.name}.bak"))
            infrastructure.writeText(SplitWorldSnapshotCodec.encode(snapshots(10_001L, 9_001L).infrastructure))
        }
        val restored = WorldPersistence.loadSplitSnapshots(infrastructure, runtime, logger)
            as WorldPersistence.SplitSnapshotLoadResult.Loaded
        assertEquals(10_000L, restored.snapshots.infrastructure.infrastructureRevision)
        assertEquals(if (interruptedWrite) 10_001L else 10_000L, restored.highestRevision)

        val world = createTestWorld()
        val restartedManager = WorldManager()
        try {
            restartedManager.restoreSnapshotRevision(world, restored.highestRevision)
            val revision = restartedManager.nextSnapshotRevision(world)
            assertEquals(restored.highestRevision + 1L, revision)
            WorldPersistence.saveSplitSnapshots(infrastructure, runtime, snapshots(revision, 1L))
            val reloaded = WorldPersistence.loadSplitSnapshots(infrastructure, runtime, logger)
                as WorldPersistence.SplitSnapshotLoadResult.Loaded
            assertEquals(revision, reloaded.snapshots.infrastructure.infrastructureRevision)
            assertEquals(1L, reloaded.snapshots.runtime.physicsEpoch)
            assertEquals(revision + 1L, restartedManager.nextSnapshotRevision(world), "same-tick saves have distinct revisions")
        } finally {
            restartedManager.stop()
            restartedManager.finishStopping()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `exhausted revision fails instead of wrapping below persisted snapshots`() {
        val world = createTestWorld()
        val manager = WorldManager()
        try {
            manager.restoreSnapshotRevision(world, Long.MAX_VALUE)
            assertThrows(ArithmeticException::class.java) { manager.nextSnapshotRevision(world) }
        } finally {
            manager.stop()
            manager.finishStopping()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private fun snapshots(revision: Long, epoch: Long): CapturedWorldSnapshots = WorldSnapshotCapture(
        CoreWorld.SavedInfrastructureV2(2, revision, emptyList()),
        BasicInfrastructureSnapshotV2(2, revision, emptyList()),
        SimpleATOInfrastructureSnapshotV1(1, revision, emptyList(), emptyList(), 0L),
        CoreWorld.SavedRuntimeV1(1, revision, epoch, emptyList()),
        BasicRuntimeSnapshotV3(3, revision, epoch),
        SimpleATORuntimeSnapshotV1(1, revision, epoch,
            dispatcher = SavedDispatcherV1(1, 0L, 0L, emptyList(), emptyList()),
            timetables = emptyList(), rollingStock = emptyList())
    ).encode()
}
