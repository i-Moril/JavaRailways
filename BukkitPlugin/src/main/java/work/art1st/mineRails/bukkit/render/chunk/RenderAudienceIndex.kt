package work.art1st.mineRails.bukkit.render.chunk

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bidirectional spatial audience index used to avoid scanning every world player per renderable. */
internal class RenderAudienceIndex<R : Any> {
    private val watchedChunksByPlayer = ConcurrentHashMap<UUID, Set<RenderChunk>>()
    private val watchersByChunk = ConcurrentHashMap<RenderChunk, MutableSet<UUID>>()
    private val visiblePlayersByRenderable = ConcurrentHashMap<R, MutableSet<UUID>>()

    fun replaceWatchedChunks(playerId: UUID, chunks: Set<RenderChunk>) {
        val previous = watchedChunksByPlayer.put(playerId, chunks).orEmpty()
        (previous - chunks).forEach { chunk ->
            watchersByChunk.computeIfPresent(chunk) { _, watchers ->
                watchers.remove(playerId)
                watchers.takeUnless { it.isEmpty() }
            }
        }
        (chunks - previous).forEach { chunk ->
            watchersByChunk.compute(chunk) { _, current ->
                (current ?: ConcurrentHashMap.newKeySet()).also { it.add(playerId) }
            }
        }
    }

    fun removePlayer(playerId: UUID) {
        replaceWatchedChunks(playerId, emptySet())
        watchedChunksByPlayer.remove(playerId)
    }

    fun markVisible(playerId: UUID, renderable: R) {
        visiblePlayersByRenderable.compute(renderable) { _, current ->
            (current ?: ConcurrentHashMap.newKeySet()).also { it.add(playerId) }
        }
    }

    fun markHidden(playerId: UUID, renderable: R) {
        visiblePlayersByRenderable.computeIfPresent(renderable) { _, players ->
            players.remove(playerId)
            players.takeUnless { it.isEmpty() }
        }
    }

    fun candidates(renderable: R, newChunk: RenderChunk): Set<UUID> = buildSet {
        watchersByChunk[newChunk]?.let(::addAll)
        visiblePlayersByRenderable[renderable]?.let(::addAll)
    }

    fun removeRenderable(renderable: R): Set<UUID> =
        visiblePlayersByRenderable.remove(renderable)?.toSet().orEmpty()

    fun clear() {
        watchedChunksByPlayer.clear()
        watchersByChunk.clear()
        visiblePlayersByRenderable.clear()
    }
}
