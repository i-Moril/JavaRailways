package work.art1st.mineRails.basic.curve

import kotlinx.serialization.Serializable

/**
 * 可序列化的锚点 DTO（用于 YAML 持久化）。
 */
@Serializable
data class SavedAnchor(
    val id: String,
    val position: SavedVec3d,
    val tangent: SavedVec3d,
    val worldId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SavedVec3d(
    val x: Double,
    val y: Double,
    val z: Double
)

@Serializable
data class SavedAnchorFile(
    val version: Int = 1,
    val anchors: List<SavedAnchor> = emptyList()
)
