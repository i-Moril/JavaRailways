package work.art1st.javarailways.bukkit.config

import org.joml.Vector3d

/**
 * Shared 3D offset configuration used by both rail and car model configs.
 */
data class OffsetConfig(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
) {
    fun toVector3d(): Vector3d = Vector3d(x, y, z)
}

/**
 * Shared 3D scale configuration used by both rail and car model configs.
 */
data class ScaleConfig(
    val x: Double = 1.0,
    val y: Double = 1.0,
    val z: Double = 1.0
)

/** Parse an offset from a YAML map. */
internal fun parseOffset(map: Map<String, Any>, subject: String = "offset"): OffsetConfig {
    return OffsetConfig(
        x = numberOrDefault(map, "x", subject, 0.0),
        y = numberOrDefault(map, "y", subject, 0.0),
        z = numberOrDefault(map, "z", subject, 0.0)
    )
}

/** Parse scale from YAML map. */
internal fun parseScale(map: Map<String, Any>): ScaleConfig {
    return ScaleConfig(
        x = numberOrDefault(map, "x", "scale", 1.0),
        y = numberOrDefault(map, "y", "scale", 1.0),
        z = numberOrDefault(map, "z", "scale", 1.0)
    )
}

private fun numberOrDefault(map: Map<String, Any>, key: String, subject: String, defaultValue: Double): Double {
    if (!map.containsKey(key)) return defaultValue
    val value = map[key] ?: throw IllegalArgumentException("$subject.$key cannot be null")
    return (value as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$subject.$key must be a number")
}
