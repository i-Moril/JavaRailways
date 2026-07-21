package work.art1st.mineRails.bukkit.render.chunk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerAudienceRefreshPolicyTest {
    @Test
    fun `sub-block authoritative passenger movement remains coalesced`() {
        assertFalse(PlayerAudienceRefreshPolicy.movedEnough(
            previousX = 10.0,
            previousY = 64.0,
            previousZ = -5.0,
            currentX = 10.99,
            currentY = 64.0,
            currentZ = -5.0
        ))
    }

    @Test
    fun `one block of authoritative passenger movement refreshes audience`() {
        assertTrue(PlayerAudienceRefreshPolicy.movedEnough(
            previousX = 10.0,
            previousY = 64.0,
            previousZ = -5.0,
            currentX = 11.0,
            currentY = 64.0,
            currentZ = -5.0
        ))
    }
}
