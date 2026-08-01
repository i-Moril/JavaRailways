package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DisplayVisualTest {
    @Test
    fun `display view range converts client multiplier to blocks`() {
        assertEquals(1.0f, displayViewRangeForDistance(64.0))
        assertEquals(8.0f, displayViewRangeForDistance(512.0))
    }

    @Test
    fun `display brightness uses protocol packed light layout`() {
        assertEquals(0, packDisplayBrightness(0, 0))
        assertEquals((7 shl 4) or (12 shl 20), packDisplayBrightness(7, 12))
        assertThrows(IllegalArgumentException::class.java) { packDisplayBrightness(16, 0) }
        assertThrows(IllegalArgumentException::class.java) { packDisplayBrightness(0, -1) }
    }
}
