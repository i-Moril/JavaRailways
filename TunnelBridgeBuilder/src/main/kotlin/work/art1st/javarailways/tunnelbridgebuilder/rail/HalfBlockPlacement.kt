package work.art1st.javarailways.tunnelbridgebuilder.rail

import work.art1st.javarailways.tunnelbridgebuilder.wand.PlacementRule
import kotlin.math.floor

internal enum class HalfBlockHalf {
    BOTTOM,
    TOP,
    DOUBLE
}

internal data class HalfBlockPlacement(
    val blockY: Int,
    val half: HalfBlockHalf
)

/** Quantizes a continuous target surface to Minecraft's 0.5 metre slab grid. */
internal object HalfBlockMatcher {
    fun match(targetSurfaceY: Double, rule: PlacementRule): HalfBlockPlacement {
        require(targetSurfaceY.isFinite()) { "Target surface height must be finite" }
        require(rule != PlacementRule.REGULAR) { "Regular placement does not use half-block matching" }

        val halfUnits = floor(targetSurfaceY * 2.0 + 0.5).toInt()
        val matchedTop = Math.floorMod(halfUnits, 2) == 0
        val blockY = if (matchedTop) halfUnits / 2 - 1 else Math.floorDiv(halfUnits, 2)
        val matchedHalf = if (matchedTop) HalfBlockHalf.TOP else HalfBlockHalf.BOTTOM
        val resolvedHalf = when (rule) {
            PlacementRule.REGULAR -> error("Regular placement was rejected above")
            PlacementRule.MATCH_HALF -> matchedHalf
            PlacementRule.MATCH_HALF_FILL_DOWN ->
                if (matchedHalf == HalfBlockHalf.TOP) HalfBlockHalf.DOUBLE else matchedHalf
            PlacementRule.MATCH_HALF_FILL_UP ->
                if (matchedHalf == HalfBlockHalf.BOTTOM) HalfBlockHalf.DOUBLE else matchedHalf
        }
        return HalfBlockPlacement(blockY, resolvedHalf)
    }
}
