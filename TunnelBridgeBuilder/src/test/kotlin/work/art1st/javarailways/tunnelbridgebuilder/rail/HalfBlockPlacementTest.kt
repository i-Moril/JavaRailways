package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.tunnelbridgebuilder.wand.PlacementRule

class HalfBlockPlacementTest {
    @Test
    fun `matches a continuous target surface to the nearest half metre`() {
        assertEquals(
            HalfBlockPlacement(blockY = 64, half = HalfBlockHalf.BOTTOM),
            HalfBlockMatcher.match(64.36, PlacementRule.MATCH_HALF)
        )
        assertEquals(
            HalfBlockPlacement(blockY = 64, half = HalfBlockHalf.BOTTOM),
            HalfBlockMatcher.match(64.74, PlacementRule.MATCH_HALF)
        )
        assertEquals(
            HalfBlockPlacement(blockY = 64, half = HalfBlockHalf.TOP),
            HalfBlockMatcher.match(64.76, PlacementRule.MATCH_HALF)
        )
        assertEquals(
            HalfBlockPlacement(blockY = 65, half = HalfBlockHalf.BOTTOM),
            HalfBlockMatcher.match(65.26, PlacementRule.MATCH_HALF)
        )
    }

    @Test
    fun `fill down completes matched top slabs and fill up completes bottom slabs`() {
        assertEquals(
            HalfBlockPlacement(64, HalfBlockHalf.DOUBLE),
            HalfBlockMatcher.match(65.0, PlacementRule.MATCH_HALF_FILL_DOWN)
        )
        assertEquals(
            HalfBlockPlacement(64, HalfBlockHalf.TOP),
            HalfBlockMatcher.match(65.0, PlacementRule.MATCH_HALF_FILL_UP)
        )
        assertEquals(
            HalfBlockPlacement(65, HalfBlockHalf.BOTTOM),
            HalfBlockMatcher.match(65.5, PlacementRule.MATCH_HALF_FILL_DOWN)
        )
        assertEquals(
            HalfBlockPlacement(65, HalfBlockHalf.DOUBLE),
            HalfBlockMatcher.match(65.5, PlacementRule.MATCH_HALF_FILL_UP)
        )
    }

    @Test
    fun `matching also uses floor coordinates below zero`() {
        assertEquals(
            HalfBlockPlacement(-2, HalfBlockHalf.TOP),
            HalfBlockMatcher.match(-1.0, PlacementRule.MATCH_HALF)
        )
        assertEquals(
            HalfBlockPlacement(-1, HalfBlockHalf.BOTTOM),
            HalfBlockMatcher.match(-0.5, PlacementRule.MATCH_HALF)
        )
    }
}
