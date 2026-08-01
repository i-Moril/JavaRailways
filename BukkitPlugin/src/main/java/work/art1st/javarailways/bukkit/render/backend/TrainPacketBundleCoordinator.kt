package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.netty.channel.ChannelHelper
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Collects one render frame per viewer and emits it between Bundle delimiters on Netty's event loop. */
class TrainPacketBundleCoordinator internal constructor(
    private val testSender: ((UUID, List<PacketWrapper<*>>) -> Unit)?
) {
    constructor() : this(null)

    private val bundlesSent = AtomicLong()
    private val packetsBundled = AtomicLong()
    private val bundleTrace = PacketMotionTrace()
    private val frames = ViewerPacketFrame<PacketWrapper<*>>(::send)

    fun <T> frame(block: () -> T): T = frames.frame(block)

    fun enqueue(viewerIds: Iterable<UUID>, packetFactory: () -> PacketWrapper<*>) {
        frames.enqueue(viewerIds, packetFactory)
    }

    fun recentTrace(): List<String> = bundleTrace.snapshot()

    fun metrics(): TrainPacketBundleMetrics = TrainPacketBundleMetrics(
        bundlesSent = bundlesSent.get(),
        packetsBundled = packetsBundled.get()
    )

    private fun send(viewerId: UUID, packets: List<PacketWrapper<*>>) {
        if (packets.isEmpty()) return
        testSender?.let { sender ->
            sender(viewerId, packets)
            return
        }
        val channel = ProtocolManager.CHANNELS[viewerId] ?: return
        val protocolManager = PacketEvents.getAPI().protocolManager
        val sendTask = Runnable {
            protocolManager.sendPacketSilently(channel, WrapperPlayServerBundle())
            packets.forEach { packet -> protocolManager.sendPacketSilently(channel, packet) }
            protocolManager.sendPacketSilently(channel, WrapperPlayServerBundle())
            val bundleSequence = bundlesSent.incrementAndGet()
            packetsBundled.addAndGet(packets.size.toLong())
            val lifecyclePacket = packets.any { packet ->
                packet is com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity ||
                    packet is com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities ||
                    packet is com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers ||
                    packet is com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
            }
            if (lifecyclePacket || bundleSequence % TRACE_SAMPLE_INTERVAL == 0L) {
                bundleTrace.record(
                    "viewer=${viewerId.toString().take(8)} packets=${packets.size} " +
                        packets.joinToString(",") { packet -> packet.javaClass.simpleName.removePrefix("WrapperPlayServer") }
                )
            }
        }
        ChannelHelper.runInEventLoop(channel, sendTask)
    }
}

internal class ViewerPacketFrame<T>(
    private val sender: (UUID, List<T>) -> Unit
) {
    private val activeFrame = ThreadLocal<Frame<T>?>()

    fun <R> frame(block: () -> R): R {
        if (activeFrame.get() != null) return block()

        val frame = Frame<T>()
        activeFrame.set(frame)
        return try {
            block()
        } finally {
            activeFrame.remove()
            frame.itemsByViewer.forEach(sender)
        }
    }

    fun enqueue(viewerIds: Iterable<UUID>, itemFactory: () -> T) {
        val frame = activeFrame.get()
        if (frame == null) {
            viewerIds.forEach { viewerId -> sender(viewerId, listOf(itemFactory())) }
            return
        }
        viewerIds.forEach { viewerId ->
            frame.itemsByViewer.getOrPut(viewerId, ::ArrayList).add(itemFactory())
        }
    }

    private class Frame<T> {
        val itemsByViewer = LinkedHashMap<UUID, MutableList<T>>()
    }
}

private const val TRACE_SAMPLE_INTERVAL = 20L

data class TrainPacketBundleMetrics(
    val bundlesSent: Long,
    val packetsBundled: Long
)
