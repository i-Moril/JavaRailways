package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FoliaVirtualSeatTest {
    @Test
    fun `own virtual living mount hides vehicle health`() {
        assertTrue(shouldHideVirtualMountHealth(42, intArrayOf(42)))
        assertTrue(shouldHideVirtualMountHealth(42, intArrayOf(7, 42)))
    }

    @Test
    fun `other viewers do not receive fake mount health override`() {
        assertFalse(shouldHideVirtualMountHealth(42, intArrayOf(7)))
        assertFalse(shouldHideVirtualMountHealth(null, intArrayOf(42)))
    }
}
