package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes
import me.tofaa.entitylib.meta.EntityMeta
import me.tofaa.entitylib.meta.other.ArmorStandMeta
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Stable client-only mount for one occupied Paper/Folia Seat. */
internal class PacketVirtualSeat(
    private val authorityEntityId: Int,
    private val bundles: TrainPacketBundleCoordinator
) {
    val entityId: Int = ENTITY_IDS.getAndDecrement()
    private val entityUuid = UUID.randomUUID()
    private val viewers = LinkedHashSet<UUID>()
    private val viewerEntityIds = HashMap<UUID, Int>()
    private val passengersByViewer = HashMap<UUID, List<Int>>()
    private var latestPassengers: List<Int> = emptyList()
    private val metadata = createMetadata()

    @Synchronized
    fun sync(location: Location, nextViewerEntityIds: Map<UUID, Int>) {
        val nextTarget = SeatPacketTarget.from(location)
        val viewerIds = nextViewerEntityIds.keys

        val removed = viewers - viewerIds
        removed.forEach { viewerId ->
            bundles.enqueue(listOf(viewerId)) { WrapperPlayServerDestroyEntities(entityId) }
            passengersByViewer.remove(viewerId)
            viewerEntityIds.remove(viewerId)
        }

        val added = viewerIds - viewers
        added.forEach { viewerId ->
            bundles.enqueue(listOf(viewerId)) { WrapperPlayServerDestroyEntities(authorityEntityId) }
            bundles.enqueue(listOf(viewerId)) { nextTarget.spawnPacket(entityId, entityUuid) }
            bundles.enqueue(listOf(viewerId)) {
                WrapperPlayServerEntityMetadata(entityId, metadata)
            }
            latestPassengers.takeIf(List<Int>::isNotEmpty)?.let { passengers ->
                if (shouldHideVirtualMountHealth(nextViewerEntityIds[viewerId], passengers.toIntArray())) {
                    enqueueZeroMountHealth(viewerId)
                }
                bundles.enqueue(listOf(viewerId)) {
                    WrapperPlayServerSetPassengers(entityId, passengers.toIntArray())
                }
                passengersByViewer[viewerId] = passengers
            }
        }

        viewers.clear()
        viewers.addAll(viewerIds)
        viewerEntityIds.putAll(nextViewerEntityIds)
        if (viewers.isNotEmpty()) {
            bundles.enqueue(viewers.toList()) { nextTarget.positionPacket(entityId) }
        }
    }

    @Synchronized
    fun redirectPassengers(viewerId: UUID, passengers: IntArray) {
        if (viewerId !in viewers) return
        val next = passengers.toList()
        latestPassengers = next
        if (passengersByViewer.put(viewerId, next) == next) return
        if (shouldHideVirtualMountHealth(viewerEntityIds[viewerId], passengers)) {
            enqueueZeroMountHealth(viewerId)
        }
        bundles.enqueue(listOf(viewerId)) {
            WrapperPlayServerSetPassengers(entityId, passengers.copyOf())
        }
    }

    @Synchronized
    fun removeViewer(viewerId: UUID) {
        if (!viewers.remove(viewerId)) return
        passengersByViewer.remove(viewerId)
        viewerEntityIds.remove(viewerId)
        bundles.enqueue(listOf(viewerId)) { WrapperPlayServerDestroyEntities(entityId) }
    }

    @Synchronized
    fun destroy() {
        val currentViewers = viewers.toList()
        viewers.clear()
        passengersByViewer.clear()
        viewerEntityIds.clear()
        latestPassengers = emptyList()
        currentViewers.forEach { viewerId ->
            bundles.enqueue(listOf(viewerId)) { WrapperPlayServerDestroyEntities(entityId) }
        }
    }

    private fun enqueueZeroMountHealth(viewerId: UUID) {
        bundles.enqueue(listOf(viewerId)) {
            WrapperPlayServerUpdateAttributes(
                entityId,
                listOf(
                    WrapperPlayServerUpdateAttributes.Property(
                        Attributes.MAX_HEALTH,
                        0.0,
                        emptyList()
                    )
                )
            )
        }
    }

    private fun createMetadata() =
        (EntityMeta.createMeta(entityId, EntityTypes.ARMOR_STAND) as ArmorStandMeta).run {
            setInvisible(true)
            setSilent(true)
            setHasNoGravity(true)
            setSmall(true)
            setHasNoBasePlate(true)
            setMarker(true)
            createPacket().entityMetadata.toList()
        }

    private data class SeatPacketTarget(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float
    ) {
        fun spawnPacket(entityId: Int, entityUuid: UUID) = WrapperPlayServerSpawnEntity(
            entityId,
            java.util.Optional.of(entityUuid),
            EntityTypes.ARMOR_STAND,
            Vector3d(x, y, z),
            pitch,
            yaw,
            yaw,
            0,
            java.util.Optional.of(Vector3d.zero())
        )

        fun positionPacket(entityId: Int) = WrapperPlayServerEntityPositionSync(
            entityId,
            EntityPositionData(Vector3d(x, y, z), Vector3d.zero(), yaw, pitch),
            false
        )

        companion object {
            fun from(location: Location) = SeatPacketTarget(
                location.x,
                location.y,
                location.z,
                location.yaw,
                0.0f
            )
        }
    }

    private companion object {
        val ENTITY_IDS = AtomicInteger(1_500_000_000)
    }
}

internal fun shouldHideVirtualMountHealth(viewerEntityId: Int?, passengers: IntArray): Boolean =
    viewerEntityId != null && viewerEntityId in passengers
