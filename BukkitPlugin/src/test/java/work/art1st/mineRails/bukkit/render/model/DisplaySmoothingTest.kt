package work.art1st.mineRails.bukkit.render.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplaySmoothingTest {
    @Test
    fun `all train displays match the vanilla ArmorStand seat interpolation window`() {
        assertEquals(3, TRAIN_DISPLAY_INTERPOLATION_TICKS)
        assertEquals(3, TRAIN_DISPLAY_TELEPORT_TICKS)
        assertEquals(3, TRAIN_ROOT_INTERPOLATION_TICKS)
        assertEquals(3, TRAIN_ROOT_TELEPORT_TICKS)
    }
}
