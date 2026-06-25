package work.art1st.mineRails.bukkit.config.model

/**
 * Item configuration for ITEM_DISPLAY type parts.
 *
 * At least one of [itemModel] or [customModelData] must be set.
 * [itemModel] uses the modern item_model component (1.21.4+).
 * [customModelData] uses the legacy CMD predicate system.
 */
data class PartItemConfig(
    val type: String,                           // Material name e.g. "IRON_NUGGET"
    val itemModel: String? = null,              // item_model component path e.g. "art1st/tc/s100/body_seated"
    val customModelData: Int? = null,           // Legacy CMD value
    val scale: PartScaleConfig? = null,
    val animations: List<PartAnimationConfig> = emptyList()
)
