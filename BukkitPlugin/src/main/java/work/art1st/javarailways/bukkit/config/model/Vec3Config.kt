package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * A 3D vector for offset/rotation configuration.
 */
@Serializable
data class Vec3Config(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
)
