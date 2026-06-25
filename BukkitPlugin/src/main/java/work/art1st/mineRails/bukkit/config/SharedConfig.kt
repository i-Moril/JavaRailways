package work.art1st.mineRails.bukkit.config

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
    val x: Double,
    val y: Double,
    val z: Double
)

/** Parse offset from YAML map. */
internal fun parseOffset(map: Map<String, Any>): OffsetConfig {
    return OffsetConfig(
        x = (map["x"] as? Number)?.toDouble() ?: 0.0,
        y = (map["y"] as? Number)?.toDouble() ?: 0.0,
        z = (map["z"] as? Number)?.toDouble() ?: 0.0
    )
}

/** Parse scale from YAML map. */
internal fun parseScale(map: Map<String, Any>): ScaleConfig {
    return ScaleConfig(
        x = (map["x"] as? Number)?.toDouble() ?: error("Missing scale.x"),
        y = (map["y"] as? Number)?.toDouble() ?: error("Missing scale.y"),
        z = (map["z"] as? Number)?.toDouble() ?: error("Missing scale.z")
    )
}
