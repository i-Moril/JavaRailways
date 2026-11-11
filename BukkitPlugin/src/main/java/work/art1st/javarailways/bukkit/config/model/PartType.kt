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
    EXTENSION;

    companion object {
        fun fromString(s: String): PartType = when (s.lowercase()) {
            "item_display" -> ITEM_DISPLAY
            "interact" -> INTERACT
            "dummy" -> DUMMY
            "seat" -> SEAT
            "platform" -> PLATFORM
            else -> if (':' in s) {
                work.art1st.javarailways.bukkit.api.model.extension.requirePartExtensionTypeId(s)
                EXTENSION
            } else throw IllegalArgumentException("Unknown part type: $s")
        }
    }
}
