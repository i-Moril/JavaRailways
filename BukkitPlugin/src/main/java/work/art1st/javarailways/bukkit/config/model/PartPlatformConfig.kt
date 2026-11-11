package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Collision platform grid for PLATFORM parts.
 *
 * Rows run across local X, columns run along local Z. The grid is centered on
 * the part origin, so rows=3 and columns=18 with 1m spacing produce x=-1..1
 * and z=-8.5..8.5.
 */
@Serializable
data class PartPlatformConfig(
    val rows: Int = 1,
    val columns: Int = 1,
    val spacingX: Double = 1.0,
    val spacingZ: Double = 1.0
) {
    init {
        require(rows > 0) { "platform.rows must be positive" }
        require(columns > 0) { "platform.columns must be positive" }
        require(spacingX.isFinite()) { "platform.spacing-x must be finite" }
        require(spacingZ.isFinite()) { "platform.spacing-z must be finite" }
    }

    fun cellOffsets(): List<Vec3Config> {
        val startX = -((rows - 1) * spacingX) / 2.0
        val startZ = -((columns - 1) * spacingZ) / 2.0
        return buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    add(Vec3Config(
                        x = startX + row * spacingX,
                        y = 0.0,
                        z = startZ + column * spacingZ
                    ))
                }
            }
        }
    }
}
