package work.art1st.javarailways.bukkit.tool

import org.bukkit.event.block.Action
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WandInteractionRoutingTest {
    @Test
    fun `rail wand may handle sneak left click air for additive anchor selection`() {
        assertFalse(
            shouldGloballyClearSelection(
                sneaking = true,
                action = Action.LEFT_CLICK_AIR,
                modeHandlesSneakLeftClickAir = true
            )
        )
    }

    @Test
    fun `default wand behavior keeps sneak left click air as clear shortcut`() {
        assertTrue(
            shouldGloballyClearSelection(
                sneaking = true,
                action = Action.LEFT_CLICK_AIR,
                modeHandlesSneakLeftClickAir = false
            )
        )
    }
}
