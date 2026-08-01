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
    fun cellOffsets(): List<Vec3Config> {
        val safeRows = rows.coerceAtLeast(1)
        val safeColumns = columns.coerceAtLeast(1)
        val startX = -((safeRows - 1) * spacingX) / 2.0
        val startZ = -((safeColumns - 1) * spacingZ) / 2.0
        return buildList {
            for (row in 0 until safeRows) {
                for (column in 0 until safeColumns) {
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
