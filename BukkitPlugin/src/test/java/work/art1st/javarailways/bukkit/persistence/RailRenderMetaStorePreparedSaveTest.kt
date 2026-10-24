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
    fun `queued preparation merges unrelated edits without losing the first commit`() {
        val worldId = UUID.randomUUID()
        seed(worldId, emptyList())
        val firstRecords = listOf(record())
        val secondRecord = record()
        val first = RailRenderMetaStore.prepareSave(
            worldId, "test", emptyList(), firstRecords, dataFolder
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<RailRenderMetaStore.PreparedSave> {
                RailRenderMetaStore.prepareSave(
                    worldId, "test", emptyList(), listOf(secondRecord), dataFolder
                )
            }
            first.commitPrimary()
            first.close()

            waiting.get(2, TimeUnit.SECONDS).use { it.commitPrimary() }
            val saved = target(worldId).readText()
            assertTrue(saved.contains(firstRecords.single().railId.toString()))
            assertTrue(saved.contains(secondRecord.railId.toString()))

            // A conflicting edit to the same identity still rejects the old baseline.
            assertThrows(IllegalStateException::class.java) {
                RailRenderMetaStore.prepareSave(worldId, "test", emptyList(),
                    listOf(firstRecords.single().copy(segments = emptyList())), dataFolder)
            }
        } finally {
            first.close()
            executor.shutdownNow()
        }

        assertNoPreparedFiles()
    }

    @Test
    fun `synchronous decoration writer rejects corruption and releases writer without overwriting bytes`() {
        val worldId = UUID.randomUUID()
        seed(worldId, listOf(record()))
        val target = target(worldId)
        val corrupt = "{broken decoration metadata".toByteArray()
        target.writeBytes(corrupt)
        var transformed = false

        repeat(2) {
            val failure = assertThrows(IllegalStateException::class.java) {
                RailRenderMetaStore.updateAll(worldId, "test", dataFolder) {
                    transformed = true
                    emptyList()
                }
            }
            assertTrue(failure.message!!.contains("corrupted"))
            assertArrayEquals(corrupt, target.readBytes())
        }
        assertFalse(transformed)
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test
    fun `synchronous decoration writer rejects prepared writer and preserves published records`() {
        val worldId = UUID.randomUUID()
        seed(worldId, listOf(record()))
        val before = target(worldId).readBytes()
        val current = RailRenderMetaStore.prepareSave(worldId, "test", emptyList(), emptyList(), dataFolder)
        current.use {
            assertThrows(IllegalStateException::class.java) {
                RailRenderMetaStore.updateAll(worldId, "test", dataFolder) { emptyList() }
            }
            assertArrayEquals(before, target(worldId).readBytes())
        }
        RailRenderMetaStore.updateAll(worldId, "test", dataFolder) { emptyList() }
        assertFalse(before.contentEquals(target(worldId).readBytes()))
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
