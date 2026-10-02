package work.art1st.javarailways.bukkit.render.backend

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
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import work.art1st.javarailways.bukkit.BukkitPlugin

class EntityLibDisplayRenderBackend : DisplayRenderBackend {
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
        }

        try {
            check(wrapper.spawn(SpigotConversionUtil.fromBukkitLocation(descriptor.location))) {
                "EntityLib refused to spawn a newly-created display visual"
            }
            return EntityLibDisplayVisual(wrapper)
        } catch (failure: Throwable) {
            try { wrapper.remove() } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
                throw DisplayCleanupException(failure)
            }
            throw failure
        }
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

private class EntityLibDisplayVisual(
    private val entity: WrapperEntity
) : DisplayVisual {
    @Volatile
    private var destroyed = false

    init {
        entity.entityMeta.setNotifyAboutChanges(false)
    }

    @Synchronized
    override fun showTo(player: Player) {
        if (!destroyed) entity.addViewer(player.uniqueId)
    }

    @Synchronized
    override fun hideFrom(player: Player) {
        if (!destroyed) entity.removeViewer(player.uniqueId)
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
        return true
    }

    @Synchronized
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        entity.remove()
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
