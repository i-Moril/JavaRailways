package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class WebUiLoginTokensTest {
    @Test
    fun `token is one-time and expires`() {
        var now = 1_000L
        val tokens = WebUiLoginTokens(2_000L) { now }
        val player = UUID.randomUUID()
        val (token, grant) = tokens.issue(player, "Alice", "world")

        assertEquals(player, grant.playerId)
        assertEquals(grant, tokens.consume(token))
        assertNull(tokens.consume(token))

        val (expired, _) = tokens.issue(player, "Alice", "world")
        now = 3_001L
        assertNull(tokens.consume(expired))
    }

    @Test
    fun `issued tokens are random`() {
        val tokens = WebUiLoginTokens()
        val player = UUID.randomUUID()
        assertNotEquals(
            tokens.issue(player, "Alice", "world").first,
            tokens.issue(player, "Alice", "world").first
        )
    }
}
