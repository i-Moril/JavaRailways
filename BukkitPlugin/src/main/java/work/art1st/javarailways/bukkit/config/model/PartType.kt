package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Part types matching template.yml.
 */
@Serializable
enum class PartType {
    ITEM_DISPLAY,
    INTERACT,
    DUMMY,
    SEAT,
    PLATFORM,
    SCREEN;

    companion object {
        fun fromString(s: String): PartType = when (s.lowercase()) {
            "item_display" -> ITEM_DISPLAY
            "interact" -> INTERACT
            "dummy" -> DUMMY
            "seat" -> SEAT
            "platform" -> PLATFORM
            "screen" -> SCREEN
            else -> throw IllegalArgumentException("Unknown part type: $s")
        }
    }
}
