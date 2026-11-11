package work.art1st.javarailways.bukkit.render.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException
import java.util.concurrent.CompletableFuture

class ModelConstructionTest {
    @Test
    fun `later construction failure cleans prior body and bogies and preserves delayed cleanup failure`() {
        val original = IllegalArgumentException("second bogie failed")
        val cleanupFailure = IllegalStateException("body cleanup failed")
        val bodyCleanup = CompletableFuture<Void>()
        val destroyed = mutableListOf<String>()
        val failure = assertThrows(DisplayCleanupException::class.java) {
            ModelConstruction.build<Unit> { construction ->
                construction.own(Any()) { destroyed += "body"; bodyCleanup }
                construction.own(Any()) { destroyed += "first bogie"; CompletableFuture.completedFuture(null) }
                throw original
            }
        }
        assertSame(original, failure.cause)
        assertEquals(listOf("first bogie", "body"), destroyed)
        assertFalse(failure.cleanup.toCompletableFuture().isDone)
        bodyCleanup.completeExceptionally(cleanupFailure)
        assertTrue(failure.cleanup.toCompletableFuture().isCompletedExceptionally)
        assertTrue(original.suppressed.any { it === cleanupFailure || it.cause === cleanupFailure })
    }
}
