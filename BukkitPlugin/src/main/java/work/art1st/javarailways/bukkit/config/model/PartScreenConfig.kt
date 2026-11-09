package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Train screen installation metadata. The part origin is its centre, with the
 * screen in local XY, facing +Z. Dimensions are in metres.
 *
 * [interior] selects future distance filtering; it does not imply occupancy or
 * occlusion checks. This definition does not create screen visuals.
 */
@Serializable
data class PartScreenConfig(
    val category: String,
    val interior: Boolean,
    val width: Double,
    val height: Double
) {
    init {
        require(category.isNotEmpty() && category.none { it.isWhitespace() }) {
            "screen.category must be nonempty and contain no whitespace"
        }
        require(width.isFinite() && width > 0.0) { "screen.width must be finite and positive" }
        require(height.isFinite() && height > 0.0) { "screen.height must be finite and positive" }
    }
}
