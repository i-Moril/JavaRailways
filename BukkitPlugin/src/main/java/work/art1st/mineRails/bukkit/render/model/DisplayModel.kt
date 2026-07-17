package work.art1st.mineRails.bukkit.render.model

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3dc
import org.joml.Vector3f
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.render.AnimationDisplayState
import work.art1st.mineRails.bukkit.render.backend.DisplayVisual
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.annotation.MainThread

class DisplayModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val visual: DisplayVisual,
    initialItemStack: ItemStack
) : AbstractModel(world, state, attachedModels, animation) {
    private var lastDisplayState: AnimationDisplayState? = null
    private var currentItemStack: ItemStack = initialItemStack.clone()
    private var mountedToRoot: Boolean = false

    fun mountTo(parent: DisplayVisual) {
        mountedToRoot = parent.addPassenger(visual)
    }

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (!mountedToRoot) {
            val worldPos = state.worldTransform.position
            val bukkitWorld = world.bukkitWorldData.bukkitWorld
            visual.teleport(Location(bukkitWorld, worldPos.x(), worldPos.y(), worldPos.z()))
        }
        if (transformDirty) {
            visual.setTransformation(
                org.bukkit.util.Transformation(
                    if (mountedToRoot) state.rootRelativeTransform.position.get(Vector3f()) else Vector3f(),
                    Quaternionf(),
                    state.rootRelativeTransform.scale.get(Vector3f()),
                    state.rootRelativeTransform.rotation.get(Quaternionf())
                )
            )
        }
        applyDisplayState(currentDisplayState)
    }

    override fun showVisualsTo(player: Player) {
        visual.showTo(player)
        super.showVisualsTo(player)
    }

    override fun hideVisualsFrom(player: Player) {
        visual.hideFrom(player)
        super.hideVisualsFrom(player)
    }

    private fun applyDisplayState(displayState: AnimationDisplayState?) {
        if (displayState == null || displayState == lastDisplayState) return
        if (displayState.hasItemOverride) {
            currentItemStack = buildItemStack(displayState, currentItemStack)
            visual.setItemStack(currentItemStack)
        }
        if (displayState.hasBrightnessOverride) {
            visual.setBrightness(
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
        visual.destroy()
    }
}
