package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.extension.rail.RailArcSegmentMeta
import work.art1st.javarailways.bukkit.extension.rail.RailTemplateSnapshot
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RailRenderMetaStorePreparedSaveTest {
    @TempDir
    lateinit var dataFolder: File

    @Test
    fun `prepare leaves primary untouched until atomic publication`() {
        val worldId = UUID.randomUUID()
        val target = target(worldId)

        RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), emptyList(), dataFolder).use { prepared ->
            assertFalse(target.exists())

            prepared.commitPrimary()

            assertTrue(target.exists())
            assertTrue(target.readText().contains("\"version\": 2"))
        }

        assertNoPreparedFiles()
    }

    @Test
    fun `publication rejects a primary changed after preparation`() {
        val worldId = UUID.randomUUID()
        seed(worldId, emptyList())
        val target = target(worldId)
        val prepared = RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), listOf(record()), dataFolder)
        target.appendText("\n")

        try {
            assertThrows(IllegalStateException::class.java, prepared::commitPrimary)
        } finally {
            prepared.close()
        }

        assertTrue(target.readText().endsWith("\n"))
        assertNoPreparedFiles()
    }

    @Test
    fun `rollback restores the exact primary bytes after publication`() {
        val worldId = UUID.randomUUID()
        seed(worldId, emptyList())
        val target = target(worldId)
        val before = target.readBytes()

        RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), listOf(record()), dataFolder).use { prepared ->
            prepared.commitPrimary()
            assertFalse(before.contentEquals(target.readBytes()))

            prepared.rollbackPrimary()

            assertArrayEquals(before, target.readBytes())
        }

        assertNoPreparedFiles()
    }

    @Test
    fun `close cleans staging files and releases the per-world writer`() {
        val worldId = UUID.randomUUID()
        val first = RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), emptyList(), dataFolder)
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<RailRenderMetaStore.PreparedSave> {
                entered.countDown()
                RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), listOf(record()), dataFolder)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)

            first.close()
            waiting.get(2, TimeUnit.SECONDS).close()
        } finally {
            first.close()
            executor.shutdownNow()
        }

        assertNoPreparedFiles()
    }

    @Test
    fun `queued preparation rejects a stale whole-world record set`() {
        val worldId = UUID.randomUUID()
        seed(worldId, emptyList())
        val firstRecords = listOf(record())
        val first = RailRenderMetaStore.prepareSave(
            worldId, "test", emptyList(), firstRecords, dataFolder
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<RailRenderMetaStore.PreparedSave> {
                RailRenderMetaStore.prepareSave(
                    worldId, "test", emptyList(), listOf(record()), dataFolder
                )
            }
            first.commitPrimary()
            first.close()

            val failure = assertThrows(ExecutionException::class.java) {
                waiting.get(2, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IllegalStateException)

            RailRenderMetaStore.prepareSave(
                worldId,
                "test",
                firstRecords,
                emptyList(),
                dataFolder
            ).close()
        } finally {
            first.close()
            executor.shutdownNow()
        }

        assertNoPreparedFiles()
    }

    private fun seed(worldId: UUID, records: List<PersistedRailRenderMeta>) {
        RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), records, dataFolder).use { it.commitPrimary() }
    }

    private fun record(): PersistedRailRenderMeta = PersistedRailRenderMeta(
        railId = UUID.randomUUID(),
        segments = listOf(RailArcSegmentMeta(0.0, 1.0, listOf(0.0, 1.0))),
        templateSnapshot = RailTemplateSnapshot("test", 1.0, emptyList())
    )

    private fun target(worldId: UUID): File = File(dataFolder, "worlds/$worldId-rail-render.json")

    private fun assertNoPreparedFiles() {
        val leftovers = File(dataFolder, "worlds").listFiles().orEmpty().filter {
            it.name.endsWith(".staged") || it.name.endsWith(".rollback")
        }
        assertTrue(leftovers.isEmpty(), "Prepared files were not cleaned: $leftovers")
    }
}
