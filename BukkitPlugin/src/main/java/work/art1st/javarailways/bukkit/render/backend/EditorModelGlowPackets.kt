package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import me.tofaa.entitylib.meta.EntityMeta
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import java.util.UUID

/** Packet thread reads/merges plain values only. Original metadata is never modified in place. */
internal class EditorGlowMetadata(private val flagsIndex: Int, private val colorIndex: Int) {
    private class State {
        var flags: Byte = 0
        var color: Int = -1
        var colors: Map<UUID, Int> = emptyMap()
        val seen = mutableSetOf<UUID>()
    }
    private val entities = mutableMapOf<Int, State>()

    @Synchronized fun register(entityId: Int, flags: Byte = 0, color: Int = -1) {
        entities.putIfAbsent(entityId, State().also { it.flags = flags; it.color = color })
    }
    @Synchronized fun remove(entityId: Int) { entities.remove(entityId) }

    /** Caller sends returned resets/updates silently, so they cannot become the baseline. */
    @Synchronized fun select(entityId: Int, colors: Map<UUID, Int>): Map<UUID, List<EntityData<*>>> {
        val state = entities[entityId] ?: return emptyMap()
        val previous = state.colors
        state.colors = colors.toMap()
        return (previous.keys + colors.keys).filter { previous[it] != colors[it] && it in state.seen }
            .associateWith { entries(state, colors[it]) }
    }

    @Synchronized fun merge(entityId: Int, viewer: UUID, input: List<EntityData<*>>): List<EntityData<*>>? {
        val state = entities[entityId] ?: return null
        state.seen += viewer
        input.forEach {
            if (it.index == flagsIndex) state.flags = it.value as Byte
            if (it.index == colorIndex) state.color = it.value as Int
        }
        val color = state.colors[viewer] ?: return null
        return input.filter { it.index != flagsIndex && it.index != colorIndex } + entries(state, color)
    }

    private fun entries(state: State, rgb: Int?): List<EntityData<*>> = listOf(
        EntityData(flagsIndex, EntityDataTypes.BYTE, if (rgb == null) state.flags else (state.flags.toInt() or 0x40).toByte()),
        EntityData(colorIndex, EntityDataTypes.INT, rgb ?: state.color)
    )

    @Synchronized fun current(entityId: Int, viewer: UUID): List<EntityData<*>>? = entities[entityId]?.let { entries(it, it.colors[viewer]) }

    /** Queued sends belong to this registration, never a later entity reusing its numeric ID. */
    @Synchronized fun pending(entityId: Int, viewer: UUID): () -> List<EntityData<*>>? {
        val captured = entities[entityId]
        return {
            synchronized(this) {
                entities[entityId]?.takeIf { it === captured }?.let { entries(it, it.colors[viewer]) }
            }
        }
    }

    @Synchronized fun forget(viewer: UUID) { entities.values.forEach { it.seen.remove(viewer) } }
    @Synchronized fun clear() { entities.clear() }
}

/** Lazy host-owned packet filter shared by both real display backends. */
internal class EditorModelGlowPackets(
    private val send: (UUID, Int, () -> List<EntityData<*>>?) -> Unit
) : AutoCloseable {
    private val metadata = EditorGlowMetadata(EntityMeta.OFFSET.toInt(), AbstractDisplayMeta.MAX_OFFSET.toInt() - 1)
    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.packetType != PacketType.Play.Server.ENTITY_METADATA) return
            val packet = WrapperPlayServerEntityMetadata(event)
            val merged = metadata.merge(packet.entityId, event.user.uuid, packet.entityMetadata) ?: return
            // Use an independent wrapper: bundled EntityLib packets may be shared by viewers.
            event.lastUsedWrapper = WrapperPlayServerEntityMetadata(packet.entityId, merged).also {
                it.serverVersion = packet.serverVersion
                it.clientVersion = packet.clientVersion
            }
            event.markForReEncode(true)
        }
    }
    init { PacketEvents.getAPI().eventManager.registerListener(listener) }
    fun register(entityId: Int, baseline: Pair<Byte, Int>) = metadata.register(entityId, baseline.first, baseline.second)
    fun remove(entityId: Int) = metadata.remove(entityId)
    fun select(entityId: Int, colors: Map<UUID, Int>) {
        metadata.select(entityId, colors).forEach { (viewer, _) -> send(viewer, entityId, metadata.pending(entityId, viewer)) }
    }
    fun forget(viewer: UUID) = metadata.forget(viewer)
    override fun close() {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
        metadata.clear()
    }
}
