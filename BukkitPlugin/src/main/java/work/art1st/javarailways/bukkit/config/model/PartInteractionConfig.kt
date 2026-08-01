package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/** Interaction hitbox dimensions and seat metadata for INTERACT/SEAT parts. */
@Serializable
data class PartInteractionConfig(
    val width: Double = 1.0,
    val height: Double = 1.0,
    /** For SEAT parts: whether sitting here authorizes train handle control. */
    val driver: Boolean = false,
    /** For driver seats: which train end this cab controls from. */
    val controlEnd: DriverControlEnd? = null
)

@Serializable
enum class DriverControlEnd {
    HEAD,
    TAIL;

    companion object {
        fun fromConfig(value: String?): DriverControlEnd? {
            return when (value?.trim()?.lowercase()) {
                "head", "front", "f" -> HEAD
                "tail", "rear", "back", "r" -> TAIL
                null, "" -> null
                else -> null
            }
        }
    }
}
