package work.art1st.mineRails.bukkit.config.model

import kotlinx.serialization.Serializable

/** Interaction hitbox dimensions for INTERACT parts. */
@Serializable
data class PartInteractionConfig(
    val width: Double = 1.0,
    val height: Double = 1.0
)
