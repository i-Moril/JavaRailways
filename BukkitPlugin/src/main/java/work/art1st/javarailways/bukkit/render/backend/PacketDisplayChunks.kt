package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.world.Location
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/** Tracks client chunk generations, not whether a server chunk is loaded. */
internal class PacketDisplayChunks : Listener, AutoCloseable {
    private val generations = ConcurrentHashMap<UUID, ConcurrentHashMap<ChunkKey, Long>>()
    private val sequence = AtomicLong()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChunkLoad(event: PlayerChunkLoadEvent) {
        loaded(event.player.uniqueId, event.world.uid, event.chunk.x, event.chunk.z)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChunkUnload(event: PlayerChunkUnloadEvent) {
        generations[event.player.uniqueId]?.remove(ChunkKey(event.world.uid, Chunk.getChunkKey(event.chunk.x, event.chunk.z)))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { generations.remove(event.player.uniqueId) }

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) { generations.remove(event.player.uniqueId) }

    private fun loaded(playerId: UUID, worldId: UUID, x: Int, z: Int) {
        generations.computeIfAbsent(playerId) { ConcurrentHashMap() }[ChunkKey(worldId, Chunk.getChunkKey(x, z))] =
            sequence.incrementAndGet()
    }

    fun state(player: Player, location: Location): State? {
        val chunkKey = Chunk.getChunkKey(floor(location.x).toInt() shr 4, floor(location.z).toInt() shr 4)
        if (!player.isChunkSent(chunkKey)) return null
        val key = ChunkKey(player.world.uid, chunkKey)
        return State(key.worldId, key.chunkKey, generations[player.uniqueId]?.get(key) ?: 0L)
    }

    fun isCurrent(player: Player, state: State): Boolean = player.world.uid == state.worldId &&
        player.isChunkSent(state.chunkKey) &&
        (generations[player.uniqueId]?.get(ChunkKey(state.worldId, state.chunkKey)) ?: 0L) == state.generation

    override fun close() {
        HandlerList.unregisterAll(this)
        generations.clear()
    }

    private data class ChunkKey(val worldId: UUID, val chunkKey: Long)
    data class State(val worldId: UUID, val chunkKey: Long, val generation: Long)
}
