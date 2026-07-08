package work.art1st.mineRails.bukkit.render.model

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.render.AnimationDisplayState
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread

class DisplayModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val entity: Display
) : AbstractModel(world, state, attachedModels, animation) {
    private var lastDisplayState: AnimationDisplayState? = null
    private var mountedToRoot: Boolean = false

    init {
        entity.isPersistent = false
        entity.configureTrainDisplaySmoothing()
    }

    fun mountTo(parent: Entity) {
        if (entity.vehicle != parent) {
            entity.vehicle?.removePassenger(entity)
            parent.addPassenger(entity)
            RenderVerificationStats.onDisplayMount()
        }
        mountedToRoot = true
    }

    // P4: transformDirty 控制是否重设 transformation（teleport 总是执行）
    override fun updateState(parentRelativeTransform: CompactTransform, rootTransform: CompactTransform, rootVelocity: Vector3dc, transformDirty: Boolean) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (!mountedToRoot) {
            val worldPos = state.worldTransform.position
            entity.teleport(Location(entity.world, worldPos.x(), worldPos.y(), worldPos.z()))
            RenderVerificationStats.onDisplayWorldTeleport()
        } else {
            RenderVerificationStats.onDisplayMountedUpdate()
        }
        if (transformDirty) {
            entity.configureTrainDisplaySmoothing()
            entity.transformation = org.bukkit.util.Transformation(
                if (mountedToRoot) state.rootRelativeTransform.position.get(Vector3f()) else Vector3f(0f, 0f, 0f),
                Quaternionf(),
                state.rootRelativeTransform.scale.get(Vector3f()),
                state.rootRelativeTransform.rotation.get(Quaternionf())
            )
        }
        applyDisplayState(currentDisplayState)
    }

    private fun applyDisplayState(displayState: AnimationDisplayState?) {
        if (displayState == null || displayState == lastDisplayState) return
        if (displayState.hasItemOverride && entity is ItemDisplay) {
            entity.setItemStack(buildItemStack(displayState, entity.itemStack))
        }
        if (displayState.hasBrightnessOverride) {
            entity.brightness = Display.Brightness(
                displayState.brightnessBlock ?: 0,
                displayState.brightnessSky ?: 0
            )
        }
        lastDisplayState = displayState
    }

    private fun buildItemStack(displayState: AnimationDisplayState, current: ItemStack): ItemStack {
        val material = displayState.itemType?.let { type ->
            runCatching { Material.valueOf(type.uppercase()) }.getOrNull()
        } ?: current.type
        val itemStack = if (material == current.type) current.clone() else ItemStack(material)

        displayState.itemModel?.let { itemModel ->
            itemStack.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:$itemModel"))
        }
        displayState.customModelData?.let { customModelData ->
            itemStack.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloat(customModelData.toFloat()).build()
            )
        }
        return itemStack
    }

    @MainThread
    override fun destroy() {
        super.destroy()
        entity.remove()
    }

}
