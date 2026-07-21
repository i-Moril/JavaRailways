package work.art1st.mineRails.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ManagedMovementViewersTest {
    @Test
    fun `suppression applies only to the current managed viewers`() {
        val viewers = ManagedMovementViewers()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        viewers.replace(setOf(first))
        assertTrue(viewers.contains(first))
        assertFalse(viewers.contains(second))

        viewers.replace(setOf(second))
        assertFalse(viewers.contains(first))
        assertTrue(viewers.contains(second))
    }

    @Test
    fun `clear releases every viewer`() {
        val viewers = ManagedMovementViewers()
        val playerId = UUID.randomUUID()

        viewers.replace(setOf(playerId))
        viewers.remove(playerId)
        assertFalse(viewers.contains(playerId))

        viewers.replace(setOf(playerId))
        viewers.clear()
        assertFalse(viewers.contains(playerId))
    }
}
