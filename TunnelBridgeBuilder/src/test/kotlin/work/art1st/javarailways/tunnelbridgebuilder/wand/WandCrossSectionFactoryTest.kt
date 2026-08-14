package work.art1st.javarailways.tunnelbridgebuilder.wand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Carve
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Ignore
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Place
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.SectionCoordinate

class WandCrossSectionFactoryTest {
    @Test
    fun `uses the primary rail as anchor and translates cells`() {
        val state = BlockWandState.createDefault()
            .withCell(0, WandCell.Air)
            .withCell(1, WandCell.Material("minecraft:stone"))
            .makePrimaryRailCell(10)

        val section = WandCrossSectionFactory.create(state) { it.uppercase() }

        assertEquals(SectionCoordinate(2, 1), section.anchor)
        assertSame(Carve, section[0, 0])
        assertEquals(Place("MINECRAFT:STONE"), section[1, 0])
        assertSame(Ignore, section[7, 4])
        assertSame(Carve, section[2, 1])
    }

    @Test
    fun `requires a primary rail`() {
        assertThrows(IllegalArgumentException::class.java) {
            WandCrossSectionFactory.create(BlockWandState.createDefault()) { it }
        }
    }
}
