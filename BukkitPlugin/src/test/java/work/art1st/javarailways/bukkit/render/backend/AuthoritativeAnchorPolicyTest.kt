package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthoritativeAnchorPolicyTest {
    @Test
    fun `small movement inside one chunk remains packet only`() {
        val anchor = AuthoritativeAnchor("world", 1.0, 20.0, 1.0)
        val target = AuthoritativeAnchor("world", 5.0, 20.0, 4.0)

        assertFalse(AuthoritativeAnchorPolicy.shouldSync(anchor, target))
    }

    @Test
    fun `large error requests an authoritative sync`() {
        val anchor = AuthoritativeAnchor("world", 1.0, 20.0, 1.0)
        val target = AuthoritativeAnchor("world", 14.0, 20.0, 1.0)

        assertTrue(AuthoritativeAnchorPolicy.shouldSync(anchor, target))
    }

    @Test
    fun `crossing a chunk boundary requests an authoritative sync`() {
        val anchor = AuthoritativeAnchor("world", 15.9, 20.0, 1.0)
        val target = AuthoritativeAnchor("world", 16.1, 20.0, 1.0)

        assertTrue(AuthoritativeAnchorPolicy.shouldSync(anchor, target))
    }

    @Test
    fun `seat authority crosses chunks using native movement without teleport`() {
        val anchor = AuthoritativeAnchor("world", 15.9, 20.0, 1.0)
        val target = AuthoritativeAnchor("world", 16.1, 20.0, 1.0)

        assertFalse(SeatAuthorityAnchorPolicy.shouldSync(anchor, target))
    }

    @Test
    fun `seat authority still corrects large movement error`() {
        val anchor = AuthoritativeAnchor("world", 1.0, 20.0, 1.0)
        val target = AuthoritativeAnchor("world", 14.0, 20.0, 1.0)

        assertTrue(SeatAuthorityAnchorPolicy.shouldSync(anchor, target))
    }

    @Test
    fun `changing worlds always requests an authoritative sync`() {
        val anchor = AuthoritativeAnchor("first", 1.0, 20.0, 1.0)
        val target = AuthoritativeAnchor("second", 1.0, 20.0, 1.0)

        assertTrue(AuthoritativeAnchorPolicy.shouldSync(anchor, target))
    }
}
