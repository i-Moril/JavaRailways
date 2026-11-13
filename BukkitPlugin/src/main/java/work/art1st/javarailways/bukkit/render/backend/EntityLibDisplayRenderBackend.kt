package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletionStage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import work.art1st.javarailways.core.api.util.Destruction
import work.art1st.javarailways.core.api.util.DestructionSteps

import com.github.retrooper.packetevents.protocol.entity.EntityPositionData
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.BlockDisplayMeta
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import net.kyori.adventure.text.Component
import me.tofaa.entitylib.wrapper.WrapperEntity
import me.tofaa.entitylib.utils.PacketUtil
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import work.art1st.javarailways.bukkit.BukkitPlugin

class EntityLibDisplayRenderBackend internal constructor(
    private val chunks: PacketDisplayChunks
) : DisplayRenderBackend {
    override val supportsTerminalServerStopRetirement = true

    override fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual {
        require(descriptor.location.world == world) {
            "Display visual location must belong to the backend world"
        }

        val wrapper = when (val content = descriptor.content) {
            is DisplayVisualContent.Item -> PassengerManagedDisplayEntity(EntityTypes.ITEM_DISPLAY).also { entity ->
                val meta = entity.getEntityMeta(ItemDisplayMeta::class.java)
                configureCommon(meta, descriptor)
                meta.setDisplayType(ItemDisplayMeta.DisplayType.NONE)
                meta.setItem(SpigotConversionUtil.fromBukkitItemStack(content.itemStack))
            }

            is DisplayVisualContent.Block -> PassengerManagedDisplayEntity(EntityTypes.BLOCK_DISPLAY).also { entity ->
                val meta = entity.getEntityMeta(BlockDisplayMeta::class.java)
                configureCommon(meta, descriptor)
                meta.setBlockState(SpigotConversionUtil.fromBukkitBlockData(content.blockData))
            }
            is DisplayVisualContent.Text -> PassengerManagedDisplayEntity(EntityTypes.TEXT_DISPLAY).also { entity ->
                val meta = entity.getEntityMeta(TextDisplayMeta::class.java)
                configureCommon(meta, descriptor)
                configureText(meta, content.text)
            }
        }

        try {
            check(wrapper.spawn(SpigotConversionUtil.fromBukkitLocation(descriptor.location))) {
                "EntityLib refused to spawn a newly-created display visual"
            }
            return EntityLibDisplayVisual(wrapper, chunks)
        } catch (failure: Throwable) {
            try { wrapper.remove() } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
                throw DisplayCleanupException(failure)
            }
            throw failure
        }
    }

    private fun configureText(meta: TextDisplayMeta, text: Component) {
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED)
        meta.setLineWidth(Int.MAX_VALUE)
        meta.setBackgroundColor(0)
        meta.setTextOpacity((-1).toByte())
        meta.setShadow(false)
        meta.setSeeThrough(false)
        meta.setUseDefaultBackground(false)
        meta.setAlignLeft(false)
        meta.setAlignRight(false)
        meta.setText(text)
    }

    private fun configureCommon(meta: AbstractDisplayMeta, descriptor: DisplayVisualDescriptor) {
        meta.setInterpolationDelay(0)
        meta.setTransformationInterpolationDuration(descriptor.interpolationDuration)
        meta.setPositionRotationInterpolationDuration(descriptor.teleportDuration)
        applyTransformation(meta, descriptor.transformation)
        meta.setViewRange(descriptor.viewRange)
    }

    private fun applyTransformation(meta: AbstractDisplayMeta, transformation: Transformation) {
        meta.setTranslation(transformation.translation.toPacketVector())
        meta.setScale(transformation.scale.toPacketVector())
        meta.setLeftRotation(transformation.leftRotation.toPacketQuaternion())
        meta.setRightRotation(transformation.rightRotation.toPacketQuaternion())
    }

    private fun org.joml.Vector3f.toPacketVector(): Vector3f = Vector3f(x(), y(), z())

    private fun org.joml.Quaternionf.toPacketQuaternion(): Quaternion4f = Quaternion4f(x(), y(), z(), w())
}

/**
 * Display mounts have viewer-specific children. EntityLib still owns riding bookkeeping, but its
 * automatic full passenger broadcasts would expose hidden/unspawned glyphs once per added leaf.
 * Keep spawn/removal in the same existing frame as the explicit, filtered mount packet as well.
 */
internal class PassengerManagedDisplayEntity : WrapperEntity {
    constructor(type: EntityType) : super(type) { requireDisplay(type) }
    constructor(id: Int, uuid: UUID, type: EntityType) : super(id, uuid, type) { requireDisplay(type) }

    private fun requireDisplay(type: EntityType) {
        require(type == EntityTypes.ITEM_DISPLAY || type == EntityTypes.BLOCK_DISPLAY || type == EntityTypes.TEXT_DISPLAY)
    }

    override fun createPassengerPacket(): WrapperPlayServerSetPassengers? = null

    override fun addViewer(uuid: UUID) {
        if (hasViewer(uuid)) return
        addViewerSilently(uuid)
        if (!isSpawned) return
        val bundles = BukkitPlugin.INSTANCE.trainPacketBundles
        bundles.enqueue(listOf(uuid)) { createSpawnPacket() }
        val entries = entityMeta.createPacket().entityMetadata.toList()
        queueViewerMetadata(listOf(uuid), entityId, entries)
    }

    override fun removeViewer(uuid: UUID) {
        if (!hasViewer(uuid)) return
        removeViewerSilently(uuid)
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(listOf(uuid)) { WrapperPlayServerDestroyEntities(entityId) }
    }

    override fun sendPacketToViewers(packet: PacketWrapper<*>?) {
        // The library can wrap null packets in delimiters when forceBundles is enabled.
        if (packet == null || viewers.isEmpty()) return
        val capturedViewers = viewers.toList()
        val bundles = BukkitPlugin.INSTANCE.trainPacketBundles
        if (packet is WrapperPlayServerEntityMetadata) {
            val entries = packet.entityMetadata.toList()
            queueViewerMetadata(capturedViewers, packet.entityId, entries)
        } else {
            bundles.enqueue(capturedViewers) { packet }
        }
    }

    private fun queueViewerMetadata(viewerIds: List<UUID>, id: Int, entries: List<EntityData<*>>) {
        val bundles = BukkitPlugin.INSTANCE.trainPacketBundles
        viewerIds.forEach { viewer ->
            bundles.enqueue(listOf(viewer)) {
                WrapperPlayServerEntityMetadata(id, entries).also { packet ->
                    // Preserve EntityLib's initial name/text translation. Its helper copies each
                    // EntityData before adapting, so another viewer and the source remain intact.
                    PacketUtil.renderPacket(viewer, packet)
                }
            }
        }
    }
}

internal class EntityLibDisplayVisual(
    private val entity: WrapperEntity,
    private val chunks: PacketDisplayChunks
) : DisplayVisual {
    override val packetEntityId = entity.entityId
    override val packetGlowBaseline = entity.entityMeta.getMask(me.tofaa.entitylib.meta.EntityMeta.OFFSET) to
        (entity.entityMeta as AbstractDisplayMeta).glowColorOverride
    @Volatile
    private var destroyed = false
    private val destruction = Destruction()
    private val shownChunks = ConcurrentHashMap<UUID, PacketDisplayChunks.State>()
    @Volatile private var passengers: List<EntityLibDisplayVisual> = emptyList()
    @Volatile private var passengerParent: EntityLibDisplayVisual? = null
    private val sentPassengers = ConcurrentHashMap<UUID, List<Int>>()
    private val metadataBaseline = entity.entityMeta.entityData().associateBy { it.index }.toMutableMap()

    @Synchronized private fun rememberPassenger(child: EntityLibDisplayVisual) {
        if (passengers.none { it === child }) passengers = passengers + child
    }
    @Synchronized private fun forgetPassenger(id: Int) { passengers = passengers.filter { it.packetEntityId != id } }

    @Synchronized
    override fun refreshPassengersFor(player: Player) {
        if (destroyed) return
        val viewer = player.uniqueId
        val shown = shownChunks[viewer]
        if (shown == null || !entity.hasViewer(viewer) || !chunks.isCurrent(player, shown) ||
            chunks.state(player, entity.location) == null) {
            sentPassengers.remove(viewer)
            return
        }
        // Every mounted display shares the root anchor. Cache repeated chunk-state checks only
        // within this refresh; no player or entity state escapes its existing owner boundary.
        val currentStates = mutableMapOf(shown to true)
        val captured = passengers.filter { child ->
            val childChunk = child.shownChunks[viewer]
            !child.destroyed && child.passengerParent === this && child.entity.hasViewer(viewer) && childChunk != null &&
                currentStates.getOrPut(childChunk) { chunks.isCurrent(player, childChunk) }
        }.map { it.packetEntityId }
        val previous = sentPassengers.put(viewer, captured)
        if (previous == captured || previous == null && captured.isEmpty()) return
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(listOf(viewer)) {
            WrapperPlayServerSetPassengers(packetEntityId, captured.toIntArray())
        }
    }


    init {
        entity.entityMeta.setNotifyAboutChanges(false)
    }

    @Synchronized
    override fun showTo(player: Player) {
        if (destroyed) return
        // Mounted children must spawn at their live root anchor, not their construction location.
        passengerParent?.let { entity.setLocation(it.entity.location) }
        val state = chunks.state(player, entity.location)
        if (state == null) {
            hideFrom(player)
            return
        }
        val previous = shownChunks[player.uniqueId]
        if (previous != null && state != previous && !chunks.isCurrent(player, previous)) {
            entity.removeViewer(player.uniqueId)
            sentPassengers.remove(player.uniqueId)
        }
        if (!entity.hasViewer(player.uniqueId)) sentPassengers.remove(player.uniqueId)
        entity.addViewer(player.uniqueId)
        shownChunks[player.uniqueId] = state
        // Ordinary composites have no extension replay callback. Their initial mount belongs here,
        // after the root's spawn and after CompositeModel has shown its children.
        refreshPassengersFor(player)
    }

    @Synchronized
    override fun hideFrom(player: Player) {
        if (!destroyed) {
            entity.removeViewer(player.uniqueId)
            shownChunks.remove(player.uniqueId)
            sentPassengers.remove(player.uniqueId)
        }
    }

    override fun teleport(location: Location) {
        if (destroyed) return
        val packetLocation = SpigotConversionUtil.fromBukkitLocation(location)
        entity.setLocation(packetLocation)
        val position = packetLocation.position
        val yaw = packetLocation.yaw
        val pitch = packetLocation.pitch
        queueToViewers {
            WrapperPlayServerEntityPositionSync(
                entity.entityId,
                EntityPositionData(
                    Vector3d(position.x, position.y, position.z),
                    Vector3d.zero(),
                    yaw,
                    pitch
                ),
                false
            )
        }
    }

    override fun setTransformation(transformation: Transformation) {
        if (destroyed) return
        val meta = entity.entityMeta as AbstractDisplayMeta
        // Display interpolation only restarts when the start-delta metadata is updated.
        // Resend it with every transform so curve rotations interpolate instead of snapping.
        meta.setInterpolationDelay(0)
        meta.setTranslation(transformation.translation.toPacketVector())
        meta.setScale(transformation.scale.toPacketVector())
        meta.setLeftRotation(transformation.leftRotation.toPacketQuaternion())
        meta.setRightRotation(transformation.rightRotation.toPacketQuaternion())
        queueMetadata(meta, restartInterpolation = true)
    }

    override fun setItemStack(itemStack: ItemStack) {
        if (destroyed) return
        val meta = entity.entityMeta as? ItemDisplayMeta ?: return
        meta.setItem(SpigotConversionUtil.fromBukkitItemStack(itemStack))
        queueMetadata(meta)
    }

    override fun setText(text: Component) {
        if (destroyed) return
        val meta = entity.entityMeta as? TextDisplayMeta
            ?: throw IllegalArgumentException("Text updates require a text display visual")
        meta.setText(text)
        queueMetadata(meta)
    }

    override fun setBrightness(blockLight: Int, skyLight: Int) {
        val packed = packDisplayBrightness(blockLight, skyLight)
        if (!destroyed) {
            val meta = entity.entityMeta as AbstractDisplayMeta
            meta.setBrightnessOverride(packed)
            queueMetadata(meta)
        }
    }

    override fun clearBrightness() {
        if (!destroyed) {
            val meta = entity.entityMeta as AbstractDisplayMeta
            // EntityLib's AbstractDisplayMeta uses -1 for the absent protocol override.
            meta.setBrightnessOverride(-1)
            queueMetadata(meta)
        }
    }

    override fun addPassenger(passenger: DisplayVisual): Boolean {
        if (destroyed) return false
        val packetPassenger = passenger as? EntityLibDisplayVisual
            ?: throw IllegalArgumentException("Cannot mix native and packet display passengers")
        if (packetPassenger.destroyed) return false
        val previousParent = packetPassenger.passengerParent
        if (previousParent !== this) {
            previousParent?.forgetPassenger(packetPassenger.packetEntityId)
            if (previousParent != null && previousParent.entity.hasPassenger(packetPassenger.packetEntityId)) {
                previousParent.entity.removePassenger(packetPassenger.packetEntityId)
            }
        }
        if (!entity.hasPassenger(packetPassenger.entity)) {
            entity.addPassenger(packetPassenger.entity)
        }
        packetPassenger.passengerParent = this
        rememberPassenger(packetPassenger)
        return true
    }

    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        destroyed = true
        shownChunks.clear()
        sentPassengers.clear()
        val oldParent = passengerParent
        oldParent?.forgetPassenger(packetEntityId)
        passengerParent = null
        passengers = emptyList()
        val steps = DestructionSteps()
        steps.action {
            if (oldParent != null && oldParent.entity.hasPassenger(packetEntityId)) oldParent.entity.removePassenger(packetEntityId)
        }
        steps.action { entity.remove() }
        steps.finish()
    }

    private fun queueMetadata(meta: AbstractDisplayMeta, restartInterpolation: Boolean = false) {
        // EntityLib replaces entries on each setter. Keep those captured values for the queued
        // delta, while the wrapper retains the complete current state for newly shown viewers.
        val entries = meta.entityData().filter { entry ->
            val previous = metadataBaseline.put(entry.index, entry)
            restartInterpolation && entry.index == AbstractDisplayMeta.OFFSET.toInt() ||
                previous == null || previous.type != entry.type || !sameMetadataValue(previous.value, entry.value)
        }
        if (entries.isNotEmpty()) queueToViewers { WrapperPlayServerEntityMetadata(entity.entityId, entries) }
    }

    private fun sameMetadataValue(previous: Any?, current: Any?): Boolean {
        // PacketEvents Quaternion4f has no value equality; compare its stored components exactly.
        return if (previous is Quaternion4f && current is Quaternion4f) {
            previous.x == current.x && previous.y == current.y && previous.z == current.z && previous.w == current.w
        } else previous == current
    }

    private fun queueToViewers(packetFactory: () -> com.github.retrooper.packetevents.wrapper.PacketWrapper<*>) {
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(entity.viewers.toList(), packetFactory)
    }

    private fun org.joml.Vector3f.toPacketVector(): Vector3f = Vector3f(x(), y(), z())

    private fun org.joml.Quaternionf.toPacketQuaternion(): Quaternion4f = Quaternion4f(x(), y(), z(), w())
}
