package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletionStage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
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
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import work.art1st.javarailways.bukkit.BukkitPlugin

class EntityLibDisplayRenderBackend internal constructor(
    private val chunks: PacketDisplayChunks
) : DisplayRenderBackend {
    override fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual {
        require(descriptor.location.world == world) {
            "Display visual location must belong to the backend world"
        }

        val wrapper = when (val content = descriptor.content) {
            is DisplayVisualContent.Item -> WrapperEntity(EntityTypes.ITEM_DISPLAY).also { entity ->
                val meta = entity.getEntityMeta(ItemDisplayMeta::class.java)
                configureCommon(meta, descriptor)
                meta.setDisplayType(ItemDisplayMeta.DisplayType.NONE)
                meta.setItem(SpigotConversionUtil.fromBukkitItemStack(content.itemStack))
            }

            is DisplayVisualContent.Block -> WrapperEntity(EntityTypes.BLOCK_DISPLAY).also { entity ->
                val meta = entity.getEntityMeta(BlockDisplayMeta::class.java)
                configureCommon(meta, descriptor)
                meta.setBlockState(SpigotConversionUtil.fromBukkitBlockData(content.blockData))
            }
            is DisplayVisualContent.Text -> WrapperEntity(EntityTypes.TEXT_DISPLAY).also { entity ->
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
    @Volatile private var passengerIds: List<Int> = emptyList()
    @Volatile private var passengerParent: EntityLibDisplayVisual? = null

    @Synchronized private fun rememberPassenger(id: Int) { if (id !in passengerIds) passengerIds = passengerIds + id }
    @Synchronized private fun forgetPassenger(id: Int) { passengerIds = passengerIds.filter { it != id } }

    override fun refreshPassengersFor(player: Player) {
        if (destroyed) return
        val captured = passengerIds.toIntArray()
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(listOf(player.uniqueId)) {
            WrapperPlayServerSetPassengers(packetEntityId, captured)
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
        }
        entity.addViewer(player.uniqueId)
        shownChunks[player.uniqueId] = state
    }

    @Synchronized
    override fun hideFrom(player: Player) {
        if (!destroyed) {
            entity.removeViewer(player.uniqueId)
            shownChunks.remove(player.uniqueId)
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
        queueMetadata(meta)
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
        if (!entity.hasPassenger(packetPassenger.entity)) {
            entity.addPassenger(packetPassenger.entity)
        }
        packetPassenger.passengerParent?.forgetPassenger(packetPassenger.packetEntityId)
        packetPassenger.passengerParent = this
        rememberPassenger(packetPassenger.packetEntityId)
        return true
    }

    @Synchronized
    override fun destroy(): CompletionStage<Void> = destruction.run {
        destroyed = true
        shownChunks.clear()
        val oldParent = passengerParent
        oldParent?.forgetPassenger(packetEntityId)
        passengerParent = null
        passengerIds = emptyList()
        val steps = DestructionSteps()
        steps.action {
            if (oldParent != null && oldParent.entity.hasPassenger(packetEntityId)) oldParent.entity.removePassenger(packetEntityId)
        }
        steps.action { entity.remove() }
        steps.finish()
    }

    private fun queueMetadata(meta: AbstractDisplayMeta) {
        val entries = meta.createPacket().entityMetadata.toList()
        queueToViewers { WrapperPlayServerEntityMetadata(entity.entityId, entries) }
    }

    private fun queueToViewers(packetFactory: () -> com.github.retrooper.packetevents.wrapper.PacketWrapper<*>) {
        BukkitPlugin.INSTANCE.trainPacketBundles.enqueue(entity.viewers.toList(), packetFactory)
    }

    private fun org.joml.Vector3f.toPacketVector(): Vector3f = Vector3f(x(), y(), z())

    private fun org.joml.Quaternionf.toPacketQuaternion(): Quaternion4f = Quaternion4f(x(), y(), z(), w())
}
