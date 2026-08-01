package work.art1st.javarailways.bukkit.render.chunk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RenderAudienceIndexTest {
    @Test
    fun `candidates contain only new chunk watchers and current viewers`() {
        val index = RenderAudienceIndex<String>()
        val nearby = UUID.randomUUID()
        val leaving = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        val targetChunk = RenderChunk(4, 7)

        index.replaceWatchedChunks(nearby, setOf(targetChunk))
        index.replaceWatchedChunks(unrelated, setOf(RenderChunk(100, 100)))
        index.markVisible(leaving, "car-a")

        assertEquals(setOf(nearby, leaving), index.candidates("car-a", targetChunk))
    }

    @Test
    fun `replacing watched chunks removes stale subscriptions`() {
        val index = RenderAudienceIndex<String>()
        val player = UUID.randomUUID()
        val oldChunk = RenderChunk(1, 1)
        val newChunk = RenderChunk(2, 2)

        index.replaceWatchedChunks(player, setOf(oldChunk))
        index.replaceWatchedChunks(player, setOf(newChunk))

        assertTrue(player !in index.candidates("car-a", oldChunk))
        assertEquals(setOf(player), index.candidates("car-a", newChunk))
    }

    @Test
    fun `concurrent watcher replacement does not lose new subscribers`() {
        val target = RenderChunk(3, 3)
        repeat(50) {
            val index = RenderAudienceIndex<String>()
            val leaving = List(16) { UUID.randomUUID() }
            val joining = List(16) { UUID.randomUUID() }
            leaving.forEach { index.replaceWatchedChunks(it, setOf(target)) }
            val executor = Executors.newFixedThreadPool(8)
            try {
                leaving.forEach { playerId ->
                    executor.submit { index.replaceWatchedChunks(playerId, emptySet()) }
                }
                joining.forEach { playerId ->
                    executor.submit { index.replaceWatchedChunks(playerId, setOf(target)) }
                }
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
            assertEquals(joining.toSet(), index.candidates("car-a", target))
        }
    }

    @Test
    fun `removing renderable returns only its current viewers`() {
        val index = RenderAudienceIndex<String>()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        index.markVisible(first, "car-a")
        index.markVisible(second, "car-a")
        index.markVisible(second, "car-b")

        assertEquals(setOf(first, second), index.removeRenderable("car-a"))
        assertTrue(index.candidates("car-a", RenderChunk(0, 0)).isEmpty())
        assertEquals(setOf(second), index.removeRenderable("car-b"))
    }
}
