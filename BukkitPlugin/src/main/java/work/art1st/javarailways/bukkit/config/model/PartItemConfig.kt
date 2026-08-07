package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Item configuration for ITEM_DISPLAY type parts.
 *
 * At least one of [itemModel] or [customModelData] must be set.
 * [itemModel] uses the item_model component; [customModelData] targets resource packs
 * that intentionally use numeric CustomModelData predicates.
 */
@Serializable
data class PartItemConfig(
    val type: String,                           // Material name e.g. "IRON_NUGGET"
    val itemModel: String? = null,              // item_model component path e.g. "art1st/tc/s100/body_seated"
    val customModelData: Int? = null,
    val scale: PartScaleConfig? = null
)
