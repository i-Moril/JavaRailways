package work.art1st.mineRails.bukkit.config.model

import kotlinx.serialization.Serializable

/** Interaction hitbox dimensions and seat metadata for INTERACT/SEAT parts. */
@Serializable
data class PartInteractionConfig(
    val width: Double = 1.0,
    val height: Double = 1.0,
    /** For SEAT parts: whether sitting here authorizes train handle control. */
    val driver: Boolean = false
)
