package work.art1st.mineRails.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3f
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

        check(wrapper.spawn(SpigotConversionUtil.fromBukkitLocation(descriptor.location))) {
            "EntityLib refused to spawn a newly-created display visual"
        }
        return EntityLibDisplayVisual(wrapper)
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
    private var destroyed = false

    override fun showTo(player: Player) {
        if (!destroyed) entity.addViewer(player.uniqueId)
    }

    override fun hideFrom(player: Player) {
        if (!destroyed) entity.removeViewer(player.uniqueId)
    }

    override fun teleport(location: Location) {
        if (!destroyed) entity.teleport(SpigotConversionUtil.fromBukkitLocation(location))
    }

    override fun setTransformation(transformation: Transformation) {
        if (destroyed) return
        val meta = entity.entityMeta as AbstractDisplayMeta
        meta.setNotifyAboutChanges(false)
        try {
            meta.setTranslation(transformation.translation.toPacketVector())
            meta.setScale(transformation.scale.toPacketVector())
            meta.setLeftRotation(transformation.leftRotation.toPacketQuaternion())
            meta.setRightRotation(transformation.rightRotation.toPacketQuaternion())
        } finally {
            meta.setNotifyAboutChanges(true)
        }
    }

    override fun setItemStack(itemStack: ItemStack) {
        if (!destroyed) {
            (entity.entityMeta as? ItemDisplayMeta)?.setItem(SpigotConversionUtil.fromBukkitItemStack(itemStack))
        }
    }

    override fun setBrightness(blockLight: Int, skyLight: Int) {
        val packed = packDisplayBrightness(blockLight, skyLight)
        if (!destroyed) {
            (entity.entityMeta as AbstractDisplayMeta).setBrightnessOverride(packed)
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

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        entity.remove()
    }

    private fun org.joml.Vector3f.toPacketVector(): Vector3f = Vector3f(x(), y(), z())

    private fun org.joml.Quaternionf.toPacketQuaternion(): Quaternion4f = Quaternion4f(x(), y(), z(), w())
}
