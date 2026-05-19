@file:UseSerializers(work.art1st.mineRails.bukkit.render.persistence.BukkitColorSerializer::class)

package work.art1st.mineRails.bukkit.render.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.jetbrains.annotations.ApiStatus
import work.art1st.configLib.persistence.Loads

@Serializable
abstract class ItemStackConfig(
    val material: String = "AIR",
) : Loads<ItemStack, Unit>

@Serializable
class ItemStackConfigV1(
    val customModelData: Int?
) : ItemStackConfig() {
    @Suppress("DEPRECATION")
    override fun load(dependency: Unit): ItemStack {
        val itemStack = ItemStack(Material.valueOf(material))
        val itemMeta = itemStack.itemMeta
        itemMeta.setCustomModelData(customModelData)
        itemStack.itemMeta = itemMeta
        return itemStack
    }
}

@Serializable
class CustomModelDataComponentConfigV1(
    val floats: List<Float> = listOf(),
    val strings: List<String> = listOf(),
    val flags: List<Boolean> = listOf(),
    val colors: List<Color> = listOf(),
)

@ApiStatus.Experimental
@Serializable
class ItemStackConfigV2(
    val customModelDataComponent: CustomModelDataComponentConfigV1
) : ItemStackConfig() {
    override fun load(dependency: Unit): ItemStack {
        val itemStack = ItemStack(Material.valueOf(material))
        val itemMeta = itemStack.itemMeta
        itemMeta.customModelDataComponent.let {
            it.floats.addAll(customModelDataComponent.floats)
            it.strings.addAll(customModelDataComponent.strings)
            it.flags.addAll(customModelDataComponent.flags)
            it.colors.addAll(customModelDataComponent.colors)
        }
        itemStack.itemMeta = itemMeta
        return itemStack
    }
}