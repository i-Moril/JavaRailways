package work.art1st.mineRails.bukkit.config.model

/**
 * Part types matching template.yml.
 */
enum class PartType {
    ITEM_DISPLAY,
    INTERACT,
    DUMMY;

    companion object {
        fun fromString(s: String): PartType = when (s.lowercase()) {
            "item_display" -> ITEM_DISPLAY
            "interact" -> INTERACT
            "dummy" -> DUMMY
            else -> throw IllegalArgumentException("Unknown part type: $s")
        }
    }
}
