package work.art1st.mineRails.bukkit.render.persistence

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.joml.Vector3dc

data class ItemStackConfigV1(
    val material: Material,
    val customModelData: Int
)

data class Transform(
    val yaw: Double,
    val pitch: Double,
    val roll: Double
)

// TODO: Add annotations like @Case
data class ModelConfigV1(
    val height: Double,
    val width: Double,
    val itemStack: ItemStack,
    val offset: Vector3dc,
    val transform: Transform,
    val children: List<ModelConfigV1>,
    val animation: AnimationConfigV1
)