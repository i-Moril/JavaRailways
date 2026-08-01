package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.entity.EntityPositionData
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Location
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.BukkitPlugin
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Packet platform pair: Chicken is the continuous mover and Shulker is its passenger. */
class PacketPlatformCarrier(initialLocation: Location) {
    private val chicken = WrapperEntity(EntityTypes.CHICKEN)
    private val shulker = WrapperEntity(EntityTypes.SHULKER)
    private val viewers = linkedSetOf<UUID>()
    private var destroyed = false

    init {
        chicken.entityMeta.setInvisible(true)
        shulker.entityMeta.setInvisible(true)
        val packetLocation = SpigotConversionUtil.fromBukkitLocation(initialLocation)
        check(chicken.spawn(packetLocation)) {
            "EntityLib refused to register a packet platform Chicken"
        }
        check(shulker.spawn(packetLocation)) {
            chicken.remove()
            "EntityLib refused to register a packet platform Shulker"
        }
        chicken.addPassenger(shulker)
        PacketPlatformDiagnostics.onCarrierCreated()
    }

    @Synchronized
    fun sync(location: Location, currentViewers: Collection<Player>) {
        if (destroyed) return
        val packetLocation = SpigotConversionUtil.fromBukkitLocation(location)
        chicken.setLocation(packetLocation)
        val position = packetLocation.position
        val yaw = packetLocation.yaw
        val pitch = packetLocation.pitch
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(viewers.toList()) {
            WrapperPlayServerEntityPositionSync(
                chicken.entityId,
                EntityPositionData(
                    Vector3d(position.x, position.y, position.z),
                    Vector3d.zero(),
                    yaw,
                    pitch
                ),
                false
            )
        }
        PacketPlatformDiagnostics.onTeleport(viewers.size)
        replaceViewers(currentViewers)
    }

    @Synchronized
    fun updateViewers(currentViewers: Collection<Player>) {
        if (destroyed) return
        replaceViewers(currentViewers)
    }

    @Synchronized
    fun showTo(player: Player) {
        if (destroyed || !player.isOnline || !viewers.add(player.uniqueId)) return
        // Spawn passenger first. Chicken addViewer then sends its stored SET_PASSENGERS relation.
        shulker.addViewer(player.uniqueId)
        chicken.addViewer(player.uniqueId)
        PacketPlatformDiagnostics.onViewerAdded()
    }

    @Synchronized
    fun hideFrom(playerId: UUID) {
        if (destroyed || !viewers.remove(playerId)) return
        chicken.removeViewer(playerId)
        shulker.removeViewer(playerId)
        PacketPlatformDiagnostics.onViewerRemoved()
    }

    private fun replaceViewers(currentViewers: Collection<Player>) {
        val desired = currentViewers.asSequence()
            .filter { it.isOnline }
            .map { it.uniqueId }
            .toSet()
        (viewers - desired).forEach { viewerId ->
            chicken.removeViewer(viewerId)
            shulker.removeViewer(viewerId)
            PacketPlatformDiagnostics.onViewerRemoved()
        }
        (desired - viewers).forEach { viewerId ->
            shulker.addViewer(viewerId)
            chicken.addViewer(viewerId)
            PacketPlatformDiagnostics.onViewerAdded()
        }
        viewers.clear()
        viewers.addAll(desired)
    }

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        repeat(viewers.size) { PacketPlatformDiagnostics.onViewerRemoved() }
        viewers.clear()
        chicken.remove()
        shulker.remove()
        PacketPlatformDiagnostics.onCarrierDestroyed()
    }
}

data class PacketPlatformMetrics(
    val activeCarriers: Long,
    val createdCarriers: Long,
    val destroyedCarriers: Long,
    val viewerAdds: Long,
    val viewerRemoves: Long,
    val teleportCalls: Long,
    val estimatedTeleportPackets: Long
)

object PacketPlatformDiagnostics {
    private val activeCarriers = AtomicLong()
    private val createdCarriers = AtomicLong()
    private val destroyedCarriers = AtomicLong()
    private val viewerAdds = AtomicLong()
    private val viewerRemoves = AtomicLong()
    private val teleportCalls = AtomicLong()
    private val estimatedTeleportPackets = AtomicLong()

    internal fun onCarrierCreated() {
        activeCarriers.incrementAndGet()
        createdCarriers.incrementAndGet()
    }

    internal fun onCarrierDestroyed() {
        activeCarriers.decrementAndGet()
        destroyedCarriers.incrementAndGet()
    }

    internal fun onViewerAdded() {
        viewerAdds.incrementAndGet()
    }

    internal fun onViewerRemoved() {
        viewerRemoves.incrementAndGet()
    }

    internal fun onTeleport(viewerCount: Int) {
        teleportCalls.incrementAndGet()
        estimatedTeleportPackets.addAndGet(viewerCount.toLong())
    }

    fun snapshot(): PacketPlatformMetrics = PacketPlatformMetrics(
        activeCarriers = activeCarriers.get(),
        createdCarriers = createdCarriers.get(),
        destroyedCarriers = destroyedCarriers.get(),
        viewerAdds = viewerAdds.get(),
        viewerRemoves = viewerRemoves.get(),
        teleportCalls = teleportCalls.get(),
        estimatedTeleportPackets = estimatedTeleportPackets.get()
    )
}
