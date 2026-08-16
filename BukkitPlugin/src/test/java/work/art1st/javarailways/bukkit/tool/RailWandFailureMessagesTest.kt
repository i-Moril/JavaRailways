package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RailWandFailureMessagesTest {
    @Test
    fun `uses deepest meaningful cause and flattens whitespace`() {
        val failure = IllegalStateException("outer", IllegalArgumentException("invalid  curve\n radius"))

        assertEquals("invalid curve radius", railWandFailureReason(failure))
    }

    @Test
    fun `falls back to exception type when messages are blank`() {
        val reason = railWandFailureReason(IllegalStateException("  "))

        assertEquals("IllegalStateException", reason)
        assertFalse(reason.contains("null"))
    }

    @Test
    fun `limits player-facing reason length`() {
        assertEquals(240, railWandFailureReason(IllegalArgumentException("x".repeat(500))).length)
    }
}
