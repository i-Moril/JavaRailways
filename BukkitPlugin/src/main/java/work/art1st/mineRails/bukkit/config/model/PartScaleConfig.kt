package work.art1st.mineRails.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Scale configuration (used in item scale and part offsets).
 */
@Serializable
data class PartScaleConfig(
    val x: Double = 1.0,
    val y: Double = 1.0,
    val z: Double = 1.0
)
